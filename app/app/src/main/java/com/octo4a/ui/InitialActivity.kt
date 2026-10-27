package com.octo4a.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.octo4a.Octo4aApplication
import com.octo4a.R
import com.octo4a.camera.CameraEnumerationRepository
import com.octo4a.repository.BootstrapRepository
import com.octo4a.repository.KlipperRepository
import com.octo4a.repository.KlipperScreenRepository
import com.octo4a.repository.KlipperWebInterface
import com.octo4a.repository.KlipperWebInterfaceRepository
import com.octo4a.repository.LinuxEnvironmentRepository
import com.octo4a.repository.MoonrakerRepository
import com.octo4a.repository.LinuxArchitecture
import com.octo4a.service.OctoPrintService
import com.octo4a.utils.getOutputAsString
import com.octo4a.utils.preferences.MainPreferences
import com.octo4a.viewmodel.BootstrapItem
import com.octo4a.viewmodel.InstallationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.android.synthetic.main.activity_landing.*
import kotlinx.android.synthetic.main.bootstrap_spinner_view.view.bootstrapVersion
import kotlinx.android.synthetic.main.bootstrap_spinner_view.view.octoprintVersion
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.concurrent.TimeUnit
import java.io.File

class InitialActivity: AppCompatActivity() {
    private val prefs: MainPreferences by inject()
    private val cameraEnumerationRepository: CameraEnumerationRepository by inject()
    private val linuxEnvironment: LinuxEnvironmentRepository by inject()
    private val klipper: KlipperRepository by inject()
    private val moonraker: MoonrakerRepository by inject()
    private val klipperScreen: KlipperScreenRepository by inject()
    private val webInterface: KlipperWebInterfaceRepository by inject()
    private var startupCheckComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (prefs.currentRelease.isNullOrBlank()) {
            prefs.currentRelease = "1.0.1"
        }

        supportActionBar?.hide()
        setContentView(R.layout.activity_landing)

        val selectedWeb = KlipperWebInterface.fromPreference(prefs.selectedKlipperWebInterface)
        webInterfaceChoice.check(if (selectedWeb == KlipperWebInterface.Fluidd) R.id.chooseFluidd else R.id.chooseMainsail)
        installButton.isEnabled = false
        skipSetupButton.isEnabled = false
        installButton.setOnClickListener {
            if (!startupCheckComplete) return@setOnClickListener
            persistWebInterfaceChoice()
            prefs.setupAssistantSkipped = false
            if (!isNetworkConnected()) {
                Toast.makeText(this, getString(R.string.missing_network), Toast.LENGTH_LONG).show()
            } else {
                // Install through Miracle Klipper's own verified PRoot bundle and Debian rootfs.
                // Do not download or launch the legacy Octo4a runtime.
                launchKlipperSetup()
            }
        }

        skipSetupButton.setOnClickListener {
            if (!startupCheckComplete) return@setOnClickListener
            prefs.pendingKlipperSetup = false
            prefs.setupAssistantSkipped = true
            launchMainActivity()
        }
        retryRootButton.setOnClickListener { requestRootAccess() }

        cameraEnumerationRepository.enumerateCameras()
        // Check root status immediately on launch, before asking the user to choose an architecture.
        // Root is optional: the app uses its own PRoot runtime either way.
        requestRootAccess {
            val hostArchitecture = LinuxArchitecture.hostArchitecture()
            if (hostArchitecture != null && prefs.selectedLinuxArchitecture == hostArchitecture) {
                // Do not interrupt every later launch with the first-run architecture dialog.
                routeExistingInstallation()
            } else {
                showArchitectureSelectionDialog()
            }
        }
    }

    private fun showArchitectureSelectionDialog() {
        val hostArchitecture = LinuxArchitecture.hostArchitecture()
        if (hostArchitecture == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.architecture_title)
                .setMessage(R.string.architecture_unsupported)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return
        }

        val architectures = arrayOf("ARM64", "x86_64")
        val selectedArchitecture = prefs.selectedLinuxArchitecture?.takeIf { it == hostArchitecture } ?: hostArchitecture
        var selectedIndex = if (selectedArchitecture == "x86_64") 1 else 0
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.architecture_title)
            .setMessage(
                getString(R.string.architecture_message, hostArchitecture) +
                    "\n\n" + rootStatusText.text.toString()
            )
            .setCancelable(false)
            .setSingleChoiceItems(architectures, selectedIndex) { _, which ->
                // Keep the dialog open while the user makes their choice.
                selectedIndex = which
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.continue_setup) { _, _ ->
                val choice = if (selectedIndex == 1) LinuxArchitecture.X86_64 else LinuxArchitecture.ARM64
                if (choice != hostArchitecture) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.architecture_incompatible_title)
                        .setMessage(getString(R.string.architecture_incompatible_message, choice, hostArchitecture))
                        .setPositiveButton(android.R.string.ok) { _, _ -> showArchitectureSelectionDialog() }
                        .setOnCancelListener { showArchitectureSelectionDialog() }
                        .show()
                } else {
                    prefs.selectedLinuxArchitecture = choice
                    updateActionsForStartupCheck()
                    routeExistingInstallation()
                }
            }
            .create()
        dialog.setOnCancelListener { finish() }
        dialog.show()
    }

    private fun requestRootAccess(onChecked: (() -> Unit)? = null) {
        rootStatusText.text = getString(R.string.setup_root_checking)
        retryRootButton.visibility = View.GONE
        installButton.isEnabled = false
        skipSetupButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { checkSuperuserAccess() }
            // Miracle Klipper runs its Linux environment with PRoot inside the app sandbox.
            // Android root access is optional and must never block installation.
            startupCheckComplete = true
            rootStatusText.text = if (result.first) {
                getString(R.string.setup_root_granted)
            } else {
                getString(R.string.setup_non_root_supported)
            }
            retryRootButton.visibility = View.GONE
            updateActionsForStartupCheck()
            if (onChecked != null) {
                onChecked()
            } else {
                routeExistingInstallation()
            }
        }
    }

    private fun checkSuperuserAccess(): Pair<Boolean, String> {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false to getString(R.string.setup_root_timeout)
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                (process.exitValue() == 0 && output == "0") to output
            }
        } catch (error: Exception) {
            process?.destroyForcibly()
            false to (error.message ?: getString(R.string.setup_root_unavailable))
        }
    }

    private fun updateActionsForStartupCheck() {
        // Enable setup after the root-status check completes; root itself is not required.
        installButton.isEnabled = startupCheckComplete
        skipSetupButton.isEnabled = startupCheckComplete
    }

    private fun routeExistingInstallation() {
        // Existing Octo4a/OctoPrint bootstrap files are legacy data and must not
        // block the Miracle Klipper Debian/PRoot setup flow.
        if (prefs.pendingKlipperSetup) {
            launchKlipperSetup()
            return
        }
        if (prefs.setupAssistantSkipped || isKlipperStackInstalled()) {
            launchMainActivity()
        }
    }

    private fun isKlipperStackInstalled(): Boolean {
        val selected = KlipperWebInterface.fromPreference(prefs.selectedKlipperWebInterface)
        return linuxEnvironment.isReady && klipper.isInstalled && moonraker.isInstalled &&
            webInterface.isInstalled(selected) && klipperScreen.isInstalled
    }

    private fun persistWebInterfaceChoice() {
        prefs.selectedKlipperWebInterface = if (webInterfaceChoice.checkedRadioButtonId == R.id.chooseFluidd) {
            KlipperWebInterface.Fluidd.key
        } else {
            KlipperWebInterface.Mainsail.key
        }
    }

    private fun launchKlipperSetup() {
        prefs.pendingKlipperSetup = false
        prefs.setupAssistantSkipped = false
        startActivity(Intent(this, KlipperSetupActivity::class.java))
        finish()
    }

    private fun launchMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo != null && cm.activeNetworkInfo.isConnected
    }

}
