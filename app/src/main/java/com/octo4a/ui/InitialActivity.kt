package com.octo4a.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.octo4a.R
import com.octo4a.bootstrap.DebianRootfsInstaller
import java.io.File
import java.util.concurrent.Future

/**
 * Miracle Klipper setup screen. Installs the pinned official Debian rootfs,
 * rather than offering the old OctoPrint bootstrap catalogue.
 *
 * Runtime launch remains disabled until the Android PRoot adapter is integrated.
 */
class InitialActivity : AppCompatActivity() {
    private lateinit var setupStatus: TextView
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupButton: Button
    private lateinit var openReleaseButton: Button
    private var installJob: Future<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_landing)

        setupStatus = findViewById(R.id.setupStatus)
        setupProgress = findViewById(R.id.setupProgress)
        setupButton = findViewById(R.id.installButton)
        openReleaseButton = findViewById(R.id.openBootstrapRelease)

        val rooted = isDeviceRooted()
        Toast.makeText(
            this,
            getString(if (rooted) R.string.root_status_rooted else R.string.root_status_not_rooted),
            Toast.LENGTH_LONG
        ).show()

        val supportsArm64Rootfs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!supportsArm64Rootfs) {
            setupStatus.text = getString(R.string.bootstrap_unsupported_device)
            setupButton.isEnabled = false
            openReleaseButton.isEnabled = true
            return
        }

        val existingRootfs = File(filesDir, "miracle-klipper/rootfs")
        if (File(existingRootfs, "opt/klipper/klippy/klippy.py").isFile) {
            setupStatus.text = getString(R.string.bootstrap_installed_runtime_pending)
            setupButton.text = getString(R.string.bootstrap_reinstall_button)
        } else {
            setupStatus.text = getString(R.string.bootstrap_not_installed_status)
            setupButton.text = getString(R.string.landing_install)
        }

        openReleaseButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DebianRootfsInstaller.ROOTFS_RELEASE_URL)))
        }
        setupButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.bootstrap_confirm_title)
                .setMessage(R.string.bootstrap_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_ok) { _, _ -> installRootfs() }
                .show()
        }
    }

    private fun installRootfs() {
        setupButton.isEnabled = false
        openReleaseButton.isEnabled = false
        setupProgress.visibility = ProgressBar.VISIBLE
        setupProgress.isIndeterminate = true
        setupStatus.text = getString(R.string.bootstrap_downloading_status)

        val installer = DebianRootfsInstaller(this) { downloaded, total ->
            runOnUiThread {
                setupProgress.isIndeterminate = false
                setupProgress.max = 100
                setupProgress.progress = ((downloaded * 100L) / total.coerceAtLeast(1L)).toInt()
                setupStatus.text = getString(
                    R.string.bootstrap_download_progress,
                    (downloaded / (1024 * 1024)).toInt(),
                    (total / (1024 * 1024)).toInt()
                )
            }
        }
        installJob = installer.installAsync(
            callback = { result ->
                runOnUiThread {
                    setupButton.isEnabled = true
                    openReleaseButton.isEnabled = true
                    setupProgress.visibility = ProgressBar.GONE
                    setupStatus.text = getString(R.string.bootstrap_installed_runtime_pending)
                    Toast.makeText(this, getString(R.string.bootstrap_install_success), Toast.LENGTH_LONG).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    setupButton.isEnabled = true
                    openReleaseButton.isEnabled = true
                    setupProgress.visibility = ProgressBar.GONE
                    setupStatus.text = getString(R.string.bootstrap_install_failed, error.message ?: error.javaClass.simpleName)
                }
            }
        )
    }

    override fun onDestroy() {
        // Do not interrupt an archive write mid-rename when screen rotates.
        super.onDestroy()
    }

    private fun isDeviceRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        val knownSuPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su",
            "/system/bin/.ext/.su", "/system/usr/we-need-root/su",
            "/su/bin/su", "/magisk/.core/bin/su"
        )
        return knownSuPaths.any { File(it).exists() }
    }
}
