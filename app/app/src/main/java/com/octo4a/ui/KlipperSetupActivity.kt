package com.octo4a.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.octo4a.R
import com.octo4a.repository.KlipperRepository
import com.octo4a.repository.KlipperScreenRepository
import com.octo4a.repository.KlipperWebInterface
import com.octo4a.repository.KlipperWebInterfaceRepository
import com.octo4a.repository.LinuxEnvironmentRepository
import com.octo4a.repository.MoonrakerRepository
import com.octo4a.utils.preferences.MainPreferences
import kotlinx.android.synthetic.main.activity_klipper_setup.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class KlipperSetupActivity : AppCompatActivity() {
    private val preferences: MainPreferences by inject()
    private val linuxEnvironment: LinuxEnvironmentRepository by inject()
    private val klipper: KlipperRepository by inject()
    private val moonraker: MoonrakerRepository by inject()
    private val klipperScreen: KlipperScreenRepository by inject()
    private val webInterface: KlipperWebInterfaceRepository by inject()
    private var setupJob: Job? = null
    private var setupCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_klipper_setup)
        openAppButton.setOnClickListener { openMainActivity(markSkipped = !setupCompleted) }
        skipSetupButton.setOnClickListener { openMainActivity(markSkipped = true) }
        retryButton.setOnClickListener { startSetup() }
        copySetupLogButton.setOnClickListener {
            try {
                val logFile = File(filesDir, "miracle-klipper/logs/debian-install.log")
                val logText = if (logFile.isFile) logFile.readText() else "Journal Debian introuvable."
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Miracle Klipper Debian log", logText))
                Toast.makeText(this, "Journal copié. Colle-le dans le chat.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this, "Impossible de lire le journal : ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        startSetup()
    }

    private fun startSetup() {
        if (setupJob?.isActive == true) return
        retryButton.visibility = View.GONE
        openAppButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        setupStatus.text = getString(R.string.setup_step_debian)
        setupJob = lifecycleScope.launch {
            try {
                linuxEnvironment.install()

                setupStatus.text = getString(R.string.setup_step_klipper)
                klipper.install()

                setupStatus.text = getString(R.string.setup_step_moonraker)
                moonraker.install()

                val client = KlipperWebInterface.fromPreference(preferences.selectedKlipperWebInterface)
                setupStatus.text = getString(R.string.setup_step_web, client.displayName)
                webInterface.install(client)

                setupStatus.text = getString(R.string.setup_step_klipperscreen)
                klipperScreen.install()

                progressBar.isIndeterminate = false
                progressBar.progress = 100
                progressBar.visibility = View.GONE
                setupStatus.text = getString(R.string.setup_complete, client.displayName)
                setupCompleted = true
                openAppButton.visibility = View.VISIBLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                progressBar.visibility = View.GONE
                setupStatus.text = getString(
                    R.string.setup_failed,
                    error.message ?: getString(R.string.debian_terminal_unknown_error)
                )
                retryButton.visibility = View.VISIBLE
                openAppButton.visibility = View.VISIBLE
                copySetupLogButton.visibility = View.VISIBLE
            }
        }
    }

    private fun openMainActivity(markSkipped: Boolean) {
        if (markSkipped) preferences.setupAssistantSkipped = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        setupJob?.cancel()
        super.onDestroy()
    }
}
