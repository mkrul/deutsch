package com.misha.holdtranslate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.misha.holdtranslate.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var downloading = false
    private var downloadError: String? = null
    private var askedNotification = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.forcedEnglish())
    }

    override fun onStart() {
        super.onStart()
        EnButtonEvents.onVisibility = {
            if (!isDestroyed) render()
        }
    }

    override fun onStop() {
        EnButtonEvents.onVisibility = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extra = dp(24)
            view.setPadding(bars.left + extra, bars.top + extra, bars.right + extra, bars.bottom + extra)
            insets
        }
        binding.primaryButton.setOnClickListener { onPrimary() }
        binding.secondaryButton.setOnClickListener { onSecondary() }
    }

    override fun onResume() {
        super.onResume()
        val connected = HoldTranslateService.instance
        if (connected != null) {
            connected.applyButtonPreference()
        } else if (Prefs.buttonOn(this) && Settings.canDrawOverlays(this)) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, ButtonService::class.java))
            } catch (_: Exception) {
                ButtonStatus.failed = true
            }
            if (
                !askedNotification &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                askedNotification = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        render()
        if (!downloading) refreshModel()
    }

    private fun refreshModel() {
        lifecycleScope.launch {
            val ready = try {
                TranslatorEngine.isModelDownloaded()
            } catch (_: Exception) {
                return@launch
            }
            if (!ready) return@launch
            Prefs.setModelReady(this@MainActivity, true)
            if (!isDestroyed) render()
        }
    }

    private fun render() {
        val overlay = Settings.canDrawOverlays(this)
        val serviceOn = HoldTranslateService.instance != null
        val wantButton = Prefs.buttonOn(this)
        val placed = ButtonStatus.visible
        val failed = wantButton && !placed && ButtonStatus.failed
        val model = Prefs.modelReady(this)

        binding.statusAccessibility.setText(
            if (serviceOn) R.string.status_accessibility_on else R.string.status_accessibility_off,
        )
        binding.statusModel.setText(
            if (model) R.string.status_model_on else R.string.status_model_off,
        )
        binding.statusButton.setText(
            when {
                placed -> R.string.status_button_on
                !overlay -> R.string.status_button_waiting
                else -> R.string.status_button_off
            },
        )

        binding.progress.visibility = if (downloading) View.VISIBLE else View.GONE
        binding.primaryButton.isEnabled = !downloading

        when {
            !overlay -> {
                binding.primaryButton.setText(R.string.action_overlay)
                binding.primaryButton.visibility = View.VISIBLE
                binding.helper.setText(R.string.help_overlay_first)
                binding.secondaryButton.visibility = View.GONE
            }
            failed -> {
                binding.primaryButton.setText(R.string.action_overlay)
                binding.primaryButton.visibility = View.VISIBLE
                binding.helper.setText(R.string.help_overlay)
                binding.secondaryButton.visibility = View.GONE
            }
            !serviceOn -> {
                binding.primaryButton.setText(R.string.action_accessibility)
                binding.primaryButton.visibility = View.VISIBLE
                binding.helper.setText(R.string.help_accessibility)
                binding.secondaryButton.setText(R.string.action_app_info)
                binding.secondaryButton.visibility = View.VISIBLE
            }
            !model -> {
                binding.primaryButton.setText(
                    if (downloading) R.string.action_downloading else R.string.action_download,
                )
                binding.primaryButton.visibility = View.VISIBLE
                binding.helper.text = downloadError ?: getString(R.string.help_download)
                binding.secondaryButton.visibility = View.GONE
            }
            else -> {
                binding.primaryButton.setText(if (placed) R.string.action_hide else R.string.action_show)
                binding.primaryButton.visibility = View.VISIBLE
                binding.helper.setText(R.string.help_ready)
                binding.secondaryButton.visibility = View.GONE
            }
        }
    }

    private fun onPrimary() {
        val overlay = Settings.canDrawOverlays(this)
        val serviceOn = HoldTranslateService.instance != null
        val model = Prefs.modelReady(this)
        val failed = Prefs.buttonOn(this) && !ButtonStatus.visible && ButtonStatus.failed
        when {
            !overlay || failed -> openOverlaySettings()
            !serviceOn -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            !model -> startDownload()
            else -> toggleButton()
        }
    }

    private fun onSecondary() {
        if (HoldTranslateService.instance == null) openAppInfo() else openOverlaySettings()
    }

    private fun toggleButton() {
        val show = !ButtonStatus.visible
        Prefs.setButtonOn(this, show)
        val connected = HoldTranslateService.instance
        if (!show) {
            stopService(Intent(this, ButtonService::class.java))
        } else if (connected == null && Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(this, Intent(this, ButtonService::class.java))
        }
        connected?.applyButtonPreference()
        render()
    }

    private fun startDownload() {
        if (downloading) return
        downloading = true
        downloadError = null
        render()
        lifecycleScope.launch {
            val error = try {
                TranslatorEngine.downloadModel()
                Prefs.setModelReady(this@MainActivity, true)
                null
            } catch (e: CancellationException) {
                downloading = false
                throw e
            } catch (e: Exception) {
                Prefs.setModelReady(this@MainActivity, false)
                e.message ?: getString(R.string.download_failed)
            }
            downloading = false
            downloadError = error
            if (!isDestroyed) render()
        }
    }

    private fun openOverlaySettings() {
        val specific = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(specific)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }
}
