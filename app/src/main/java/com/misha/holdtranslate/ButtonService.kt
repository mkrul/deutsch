package com.misha.holdtranslate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

internal enum class EnButtonState {
    IDLE,
    WORKING,
    SHOWING,
    ERROR,
}

internal fun enButtonTextSizeSp(label: String): Float {
    return when {
        label.length <= 2 -> 18f
        label.length <= 4 -> 14f
        else -> 11f
    }
}

internal fun translateButtonBackground(context: Context, color: Int): LayerDrawable {
    val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
    val mark = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!.mutate()
    return LayerDrawable(arrayOf(circle, mark))
}

internal fun paintTranslateButton(view: TextView, state: EnButtonState, color: Int, pageLabel: String?) {
    val background = view.background as LayerDrawable
    (background.getDrawable(0) as GradientDrawable).setColor(color)
    val caption = when {
        state == EnButtonState.WORKING -> "…"
        state == EnButtonState.ERROR -> "!"
        pageLabel != null -> pageLabel
        else -> null
    }
    val mark = background.getDrawable(1)
    if (caption == null) {
        view.text = ""
        mark.alpha = 255
    } else {
        view.text = caption
        view.textSize = enButtonTextSizeSp(caption)
        mark.alpha = 0
    }
    background.invalidateSelf()
}

internal object EnButtonEvents {
    var onState: ((EnButtonState, String?, String?) -> Unit)? = null
    var onVisibility: (() -> Unit)? = null

    fun publish(state: EnButtonState, message: String?, label: String? = null) {
        onState?.invoke(state, message, label)
    }

    fun visibilityChanged() {
        onVisibility?.invoke()
    }
}

internal object ButtonStatus {
    @Volatile
    var visible: Boolean = false

    @Volatile
    var failed: Boolean = false
}

class ButtonService : Service() {
    private val windowManager: WindowManager
        get() = getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mover by lazy { ButtonMover(this, windowManager) }
    private var buttonRow: LinearLayout? = null
    private var buttonView: TextView? = null
    private var messageView: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var clearError: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        EnButtonEvents.onState = { state, message, label -> applyState(state, message, label) }
    }

    fun raise() {
        val row = buttonRow ?: return
        val params = buttonParams ?: return
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!Settings.canDrawOverlays(this) || !Prefs.buttonOn(this)) {
            removeButton()
            stopSelf()
            return START_NOT_STICKY
        }
        showButton()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (EnButtonEvents.onState != null) {
            EnButtonEvents.onState = null
        }
        removeButton()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_en_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun showButton() {
        if (buttonRow != null) return
        val row = buildButtonRow()
        val params = buttonParams()
        try {
            windowManager.addView(row, params)
            buttonRow = row
            buttonParams = params
            row.post { mover.clamp(row, params) }
            ButtonStatus.visible = true
            ButtonStatus.failed = false
            EnButtonEvents.visibilityChanged()
        } catch (e: Exception) {
            Log.e(TAG, "button add failed: ${e.javaClass.simpleName}: ${e.message}")
            buttonView = null
            messageView = null
            ButtonStatus.visible = false
            ButtonStatus.failed = true
            EnButtonEvents.visibilityChanged()
        }
    }

    private fun removeButton() {
        clearError?.let { handler.removeCallbacks(it) }
        val row = buttonRow
        buttonRow = null
        buttonView = null
        messageView = null
        buttonParams = null
        ButtonStatus.visible = HoldTranslateService.instance?.hasButton() == true
        if (row != null) {
            try {
                windowManager.removeView(row)
            } catch (_: Exception) {
            }
        }
        EnButtonEvents.visibilityChanged()
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
            minimumWidth = size
            minimumHeight = size
            background = translateButtonBackground(this@ButtonService, COLOR_IDLE)
            setOnTouchListener { _, event -> onButtonTouch(event) }
        }
        val message = TextView(this).apply {
            visibility = android.view.View.GONE
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = dp(240)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dpF(10f)
                setColor(COLOR_ERROR)
            }
        }
        val messageParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
        buttonView = button
        messageView = message
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(message, messageParams)
            addView(button, LinearLayout.LayoutParams(size, size))
        }
    }

    private fun onButtonTouch(event: MotionEvent): Boolean {
        val params = buttonParams ?: return true
        val row = buttonRow ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mover.down(params, event)
                clearError?.let { handler.removeCallbacks(it) }
                clearError = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (mover.onMove(event, row, params)) {
                    HoldTranslateService.instance?.onButtonDragged()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mover.dragging) {
                    mover.save(params)
                } else {
                    val service = HoldTranslateService.instance
                    if (service == null) {
                        applyState(EnButtonState.ERROR, getString(R.string.error_need_accessibility))
                        val clear = Runnable { applyState(EnButtonState.IDLE, null) }
                        clearError = clear
                        handler.postDelayed(clear, 2200)
                    } else {
                        service.onTap()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mover.dragging) mover.save(params)
            }
        }
        return true
    }

    private fun applyState(state: EnButtonState, message: String?, label: String? = null) {
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
        if (message.isNullOrBlank()) {
            messageView?.visibility = android.view.View.GONE
            messageView?.text = ""
        } else {
            messageView?.text = message
            messageView?.visibility = android.view.View.VISIBLE
        }
    }

    private fun buttonParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.LEFT
            ButtonMover.place(this@ButtonService, this, dp(16), dp(120))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    companion object {
        private const val TAG = "HoldTranslate"
        private const val CHANNEL_ID = "en_button"
        private const val NOTIFICATION_ID = 7
        private const val COLOR_IDLE = 0xFFE4572E.toInt()
        private const val COLOR_WORKING = 0xFFC47B2B.toInt()
        private const val COLOR_SHOWING = 0xFF1F7A4D.toInt()
        private const val COLOR_ERROR = 0xFF8C2F2F.toInt()

        @Volatile
        var instance: ButtonService? = null
    }
}
