package com.misha.holdtranslate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HoldTranslateService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speechJob: Job? = null
    private var speakingView: TextView? = null
    private var player: MediaPlayer? = null
    private var audioFocus: AudioFocusRequest? = null
    private val windowManager: WindowManager
        get() = getSystemService(WindowManager::class.java)

    private var windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    private val mover by lazy { ButtonMover(this, windowManager) }
    private var buttonRow: View? = null
    private var buttonView: TextView? = null
    private var messageView: TextView? = null
    private var buttonLayoutParams: WindowManager.LayoutParams? = null
    private val chipWindows = ArrayList<View>()
    private var job: Job? = null
    private var clearStatusJob: Job? = null
    private var holdGeneration = 0
    private var buttonState = EnButtonState.IDLE
    private var overlayOpen = false
    private var translationPages: List<List<PlacedTranslation>> = emptyList()
    private var pageIndex = 0
    private var openChip: TranslationChip? = null
    private var visited = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        }
        if (Prefs.buttonOn(this)) showButton()
    }

    fun onTap() {
        if (buttonState == EnButtonState.WORKING) {
            endHold()
            return
        }
        if (overlayOpen) {
            if (pageIndex + 1 < translationPages.size) {
                showPage(pageIndex + 1)
            } else {
                endHold()
            }
            return
        }
        startHold()
    }

    fun onButtonDragged() {
        if (overlayOpen) return
        cancelHold()
    }

    fun cancelHold() {
        holdGeneration++
        job?.cancel()
        clearStatusJob?.cancel()
        concealOverlay()
        setButtonState(EnButtonState.IDLE)
        hideMessage()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun hasButton(): Boolean = buttonRow != null

    fun applyButtonPreference() {
        if (!Prefs.buttonOn(this)) {
            hideButton()
            stopService(Intent(this, ButtonService::class.java))
            return
        }
        showButton()
    }

    private fun teardown() {
        val restoreOverlay = Prefs.buttonOn(this) && Settings.canDrawOverlays(this)
        hideButton()
        if (instance === this) instance = null
        if (!restoreOverlay) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, ButtonService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "button service failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun showButton() {
        addAccessibilityButton()
        if (buttonRow != null) {
            stopService(Intent(this, ButtonService::class.java))
        }
    }

    private fun addAccessibilityButton() {
        if (buttonRow != null) return
        val row = buildButtonRow()
        val params = buttonParams()
        try {
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            addWindow(row, params)
            buttonRow = row
            buttonLayoutParams = params
            row.post { mover.clamp(row, params) }
            if (instance === this) {
                ButtonStatus.visible = true
                ButtonStatus.failed = false
                EnButtonEvents.visibilityChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "button add failed: ${e.javaClass.simpleName}: ${e.message}")
            buttonView = null
            messageView = null
            if (instance === this) {
                ButtonStatus.visible = false
                ButtonStatus.failed = true
                EnButtonEvents.visibilityChanged()
            }
        }
    }

    private fun hideButton() {
        if (instance === this) ButtonStatus.failed = false
        concealOverlay()
        removeOverlayWindow()
        removeButton()
    }

    private fun removeOverlayWindow() {
        removeChips()
    }

    private fun removeButton() {
        val row = buttonRow
        buttonRow = null
        buttonView = null
        messageView = null
        buttonLayoutParams = null
        if (instance === this) {
            ButtonStatus.visible = false
            EnButtonEvents.visibilityChanged()
        }
        if (row == null) return
        try {
            windowManager.removeView(row)
        } catch (_: Exception) {
        }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams) {
        params.type = windowType
        try {
            windowManager.addView(view, params)
        } catch (first: Exception) {
            if (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) throw first
            if (!Settings.canDrawOverlays(this)) throw first
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            params.type = windowType
            windowManager.addView(view, params)
        }
    }

    private fun buildButtonRow(): LinearLayout {
        val size = dp(64)
        val button = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            contentDescription = getString(R.string.cd_button)
            isHapticFeedbackEnabled = true
            isClickable = true
            background = translateButtonBackground(this@HoldTranslateService, COLOR_IDLE)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnTouchListener { _, event -> onButtonTouch(event) }
        }
        val message = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = dp(240)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dpF(10f)
                setColor(COLOR_ERROR)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }
        buttonView = button
        messageView = message
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(message)
            addView(button)
        }
    }

    private fun onButtonTouch(event: MotionEvent): Boolean {
        val params = buttonLayoutParams ?: return true
        val row = buttonRow ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> mover.down(params, event)
            MotionEvent.ACTION_MOVE -> {
                if (mover.onMove(event, row, params)) onButtonDragged()
            }
            MotionEvent.ACTION_UP -> {
                if (mover.dragging) mover.save(params) else onTap()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mover.dragging) mover.save(params)
            }
        }
        return true
    }

    private fun startHold() {
        val generation = ++holdGeneration
        clearStatusJob?.cancel()
        job?.cancel()
        concealOverlay()
        hideMessage()
        setButtonState(EnButtonState.WORKING)
        job = scope.launch {
            if (!Prefs.modelReady(this@HoldTranslateService)) {
                showError(getString(R.string.error_need_model))
                return@launch
            }
            val captured = try {
                captureVisibleText()
            } catch (e: Exception) {
                Log.e(TAG, "capture failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            if (generation != holdGeneration) return@launch
            if (captured.isEmpty()) {
                showError(getString(R.string.error_no_text))
                return@launch
            }
            val batch = try {
                TranslatorEngine.translateAll(captured)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "translate failed: ${e.javaClass.simpleName}")
                showError(getString(R.string.error_translate))
                return@launch
            }
            if (generation != holdGeneration) return@launch
            if (batch.attempted > 0 && batch.failed == batch.attempted) {
                Prefs.setModelReady(this@HoldTranslateService, false)
                showError(getString(R.string.error_translate))
                return@launch
            }
            if (batch.items.isEmpty()) {
                showError(getString(R.string.error_no_german))
                return@launch
            }
            val pages = TranslationPages.layout(
                this@HoldTranslateService,
                batch.items,
                windowManager.currentWindowMetrics.bounds,
            )
            if (generation != holdGeneration) return@launch
            if (pages.isEmpty()) {
                showError(getString(R.string.error_draw))
                return@launch
            }
            translationPages = pages
            showPage(0)
        }
    }

    private fun endHold() {
        holdGeneration++
        job?.cancel()
        concealOverlay()
        if (buttonState == EnButtonState.ERROR) {
            clearStatusJob?.cancel()
            clearStatusJob = scope.launch {
                delay(2200)
                if (buttonState == EnButtonState.ERROR) {
                    setButtonState(EnButtonState.IDLE)
                    hideMessage()
                }
            }
        } else {
            setButtonState(EnButtonState.IDLE)
            hideMessage()
        }
    }

    private fun showPage(index: Int) {
        val page = translationPages.getOrNull(index) ?: return
        stopSpeech()
        openChip = null
        removeChips()
        pageIndex = index
        for (placed in page) {
            val chip = TranslationChips.create(
                this,
                placed.block.text,
                placed.textWidth,
                placed.window.height(),
                { view -> speak(placed.block.source, view) },
            ) { changed, open ->
                onChipToggled(changed, placed, open)
            }
            val params = WindowManager.LayoutParams(
                placed.collapsedWidth,
                placed.collapsedHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = placed.window.left
                y = placed.window.top
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            try {
                windowManager.addView(chip.view, params)
                chipWindows.add(chip.view)
            } catch (e: Exception) {
                Log.e(TAG, "chip add failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (chipWindows.isEmpty()) {
            translationPages = emptyList()
            pageIndex = 0
            overlayOpen = false
            showError(getString(R.string.error_draw))
            return
        }
        overlayOpen = true
        if (buttonState != EnButtonState.ERROR) setButtonState(EnButtonState.SHOWING, pageLabel())
        raiseButton()
        ButtonService.instance?.raise()
        buttonView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun onChipToggled(chip: TranslationChip, placed: PlacedTranslation, open: Boolean) {
        if (open) {
            val previous = openChip
            openChip = chip
            if (previous != null && previous !== chip) previous.setOpen(false)
        } else if (openChip === chip) {
            openChip = null
        }
        val params = chip.view.layoutParams as? WindowManager.LayoutParams ?: return
        params.width = if (open) placed.window.width() else placed.collapsedWidth
        params.height = if (open) placed.window.height() else placed.collapsedHeight
        try {
            windowManager.updateViewLayout(chip.view, params)
        } catch (e: Exception) {
            Log.e(TAG, "chip resize failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        raiseButton()
        ButtonService.instance?.raise()
    }

    private fun pageLabel(): String? {
        val count = translationPages.size
        if (count <= 1) return null
        return "${pageIndex + 1}/$count"
    }

    private fun concealOverlay() {
        stopSpeech()
        overlayOpen = false
        translationPages = emptyList()
        pageIndex = 0
        openChip = null
        removeChips()
    }

    private fun speak(german: String, speaker: TextView) {
        if (german.isBlank()) return
        speakingView?.text = getString(R.string.speaker)
        speakingView = speaker
        speechJob?.cancel()
        releasePlayer()
        speaker.text = getString(R.string.speaker_working)
        speechJob = scope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { GermanSpeech.audio(this@HoldTranslateService, german) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                if (speakingView === speaker) {
                    speaker.text = getString(R.string.speaker)
                    speakingView = null
                }
                val message = when (e.kind) {
                    SpeechException.Kind.MISSING_KEY -> R.string.error_speech_key
                    SpeechException.Kind.REJECTED -> R.string.error_speech_rejected
                    SpeechException.Kind.FAILED -> R.string.error_speech
                }
                Toast.makeText(this@HoldTranslateService, message, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!isActive || speakingView !== speaker) return@launch
            speaker.text = getString(R.string.speaker)
            play(bytes)
        }
    }

    private fun play(bytes: ByteArray) {
        releasePlayer()
        try {
            val file = File(cacheDir, "german-speech.mp3")
            file.writeBytes(bytes)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val next = MediaPlayer()
            next.setAudioAttributes(attributes)
            next.setDataSource(file.absolutePath)
            next.setOnCompletionListener {
                if (player === next) releasePlayer()
            }
            next.prepare()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            getSystemService(AudioManager::class.java).requestAudioFocus(request)
            audioFocus = request
            next.start()
            player = next
        } catch (e: Exception) {
            Log.e(TAG, "speech play failed: ${e.javaClass.simpleName}")
            releasePlayer()
            Toast.makeText(this, R.string.error_speech, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSpeech() {
        speechJob?.cancel()
        speechJob = null
        speakingView?.text = getString(R.string.speaker)
        speakingView = null
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        val request = audioFocus ?: return
        audioFocus = null
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
    }

    private fun removeChips() {
        for (chip in chipWindows) {
            try {
                windowManager.removeView(chip)
            } catch (_: Exception) {
            }
        }
        chipWindows.clear()
    }

    private fun raiseButton() {
        val row = buttonRow ?: return
        val params = buttonLayoutParams ?: return
        try {
            windowManager.removeView(row)
        } catch (_: Exception) {
        }
        try {
            windowManager.addView(row, params)
        } catch (e: Exception) {
            Log.e(TAG, "button raise failed: ${e.javaClass.simpleName}")
        }
    }

    private fun showError(message: String) {
        buttonState = EnButtonState.ERROR
        EnButtonEvents.publish(EnButtonState.ERROR, message)
        val view = buttonView ?: return
        paintTranslateButton(view, EnButtonState.ERROR, COLOR_ERROR, null)
        view.contentDescription = getString(R.string.cd_button)
        messageView?.text = message
        messageView?.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        messageView?.visibility = View.GONE
        messageView?.text = ""
    }

    private fun setButtonState(state: EnButtonState, label: String? = null) {
        buttonState = state
        EnButtonEvents.publish(state, null, label)
        val view = buttonView ?: return
        val color = when (state) {
            EnButtonState.IDLE -> COLOR_IDLE
            EnButtonState.WORKING -> COLOR_WORKING
            EnButtonState.SHOWING -> COLOR_SHOWING
            EnButtonState.ERROR -> COLOR_ERROR
        }
        paintTranslateButton(view, state, color, label)
        view.contentDescription = if (label == null) {
            getString(R.string.cd_button)
        } else {
            getString(R.string.cd_button_page, label)
        }
    }

    private fun buttonParams(): WindowManager.LayoutParams {
        return baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.LEFT
            ButtonMover.place(this@HoldTranslateService, this, dp(16), dp(120))
        }
    }

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            width,
            height,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun captureVisibleText(): List<ScreenText> {
        check(Looper.myLooper() == Looper.getMainLooper())
        visited = 0
        val out = ArrayList<ScreenText>()
        val windowList = try {
            windows
        } catch (e: Exception) {
            Log.e(TAG, "windows failed: ${e.javaClass.simpleName}")
            null
        }
        if (windowList.isNullOrEmpty()) {
            val root = try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            } ?: return emptyList()
            try {
                collect(root, out, 0)
            } catch (e: Exception) {
                Log.e(TAG, "collect failed: ${e.javaClass.simpleName}")
            } finally {
                try {
                    root.recycle()
                } catch (_: Exception) {
                }
            }
        } else {
            for (window in windowList) {
                val type = try {
                    window.type
                } catch (_: Exception) {
                    continue
                }
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                } ?: continue
                try {
                    collect(root, out, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "collect failed: ${e.javaClass.simpleName}")
                } finally {
                    try {
                        root.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val merged = LineMerger.merge(out, dp(32))
        val ordered = merged.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        return if (ordered.size <= 100) ordered else ordered.take(100)
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenText>, depth: Int) {
        if (depth > 50 || visited++ > 800) return
        if (node.packageName?.toString() == packageName) return
        val children = ArrayList<AccessibilityNodeInfo>(node.childCount)
        try {
            for (index in 0 until node.childCount) {
                val child = try {
                    node.getChild(index)
                } catch (_: Exception) {
                    null
                } ?: continue
                children.add(child)
            }
            val childHasText = children.any { readableText(it) != null }
            if (!childHasText) {
                val text = readableText(node)
                if (text != null && node.isVisibleToUser && !isSecret(node)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() >= 8 && bounds.height() >= 8 && text.any { it.isLetter() }) {
                        out.add(ScreenText(text, bounds))
                    }
                }
            }
            for (child in children) {
                collect(child, out, depth + 1)
            }
        } finally {
            for (child in children) {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readableText(node: AccessibilityNodeInfo): String? {
        val text = normalizeScreenText(node.text?.toString().orEmpty())
        if (text.isNotEmpty()) return text
        val className = node.className?.toString().orEmpty()
        if (!isTextClass(className)) return null
        val description = normalizeScreenText(node.contentDescription?.toString().orEmpty())
        return description.ifEmpty { null }
    }

    private fun isTextClass(className: String): Boolean {
        return className.contains("TextView") || className.contains("EditText")
    }

    private fun isSecret(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    companion object {
        private const val TAG = "HoldTranslate"
        private const val COLOR_IDLE = 0xFFE4572E.toInt()
        private const val COLOR_WORKING = 0xFFC47B2B.toInt()
        private const val COLOR_SHOWING = 0xFF1F7A4D.toInt()
        private const val COLOR_ERROR = 0xFF8C2F2F.toInt()

        @Volatile
        var instance: HoldTranslateService? = null
    }
}
