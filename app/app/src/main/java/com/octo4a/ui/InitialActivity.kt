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

class BootstrapSpinnerAdapter(context: Context, @LayoutRes private val layoutResource: Int, private val releases: List<BootstrapItem>):
    ArrayAdapter<BootstrapItem>(context, layoutResource, releases) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return createViewFromResource(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return createViewFromResource(position, convertView, parent)
    }

    private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup?): View{
        val view = convertView ?: LayoutInflater.from(context).inflate(layoutResource, parent, false)
        val release = releases[position]
        view.octoprintVersion.text = release.title

        var bootstrapVersionText = release.bootstrapVersion

        if (release.recommended) {
            bootstrapVersionText += " • recommended"
        } else if (release.prerelease) {
            bootstrapVersionText += " • prerelease"
        }
        view.bootstrapVersion.text = bootstrapVersionText
        return view
    }
}
class InitialActivity: AppCompatActivity() {
    private val bootstrapRepository: BootstrapRepository by inject()
    private val prefs: MainPreferences by inject()
    private val cameraEnumerationRepository: CameraEnumerationRepository by inject()
    private val linuxEnvironment: LinuxEnvironmentRepository by inject()
    private val klipper: KlipperRepository by inject()
    private val moonraker: MoonrakerRepository by inject()
    private val klipperScreen: KlipperScreenRepository by inject()
    private val webInterface: KlipperWebInterfaceRepository by inject()
    private val installationViewModel: InstallationViewModel by viewModel()
    private val spinnerArrayAdapter: BootstrapSpinnerAdapter by lazy { BootstrapSpinnerAdapter(this, R.layout.bootstrap_spinner_view, mutableListOf()) }
    private var rootAuthorized = false
    private var bootstrapReleaseAvailable = false

    // Storage permission request
    private val hasStoragePermission: Boolean
        get() = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    // Detects legacy (<1.0.0) release bootstrap
    private val pre1LegacyBootstrapInstalled by lazy { File("/data/data/com.octo4a/files/home").exists() }
    private val legacyBootstrapInstalled by lazy { File("/data/data/com.octo4a/files/bootstrap/add-user.sh").exists() }

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
            if (isGranted.values.all { it }) {
                launchBootstrapInstaller()
            } else {
                Toast.makeText(this, getString(R.string.missing_write_permission), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (prefs.currentRelease.isNullOrBlank()) {
            prefs.currentRelease = "1.0.1"
        }

        supportActionBar?.hide()
        setContentView(R.layout.activity_landing)

        installationViewModel.bootstrapReleases.observe(this) {
            updateBootstrapSpinnerItems(it)
            bootstrapReleaseAvailable = it.isNotEmpty() && bootstrapRepository.isBootstrapInstallItemSelected
            if (rootAuthorized) updateActionsForRootState()
        }

        // Setup spinner's adapter
        spinnerArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bootstrapVersionSpinner.adapter = spinnerArrayAdapter
        bootstrapVersionSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                installationViewModel.selectBootstrapRelease(spinnerArrayAdapter.getItem(position)!!)
            }
        }

        val selectedWeb = KlipperWebInterface.fromPreference(prefs.selectedKlipperWebInterface)
        webInterfaceChoice.check(if (selectedWeb == KlipperWebInterface.Fluidd) R.id.chooseFluidd else R.id.chooseMainsail)
        installButton.isEnabled = false
        skipSetupButton.isEnabled = false
        installButton.setOnClickListener {
            if (!rootAuthorized) return@setOnClickListener
            persistWebInterfaceChoice()
            prefs.setupAssistantSkipped = false
            if (!isNetworkConnected()) {
                Toast.makeText(this, getString(R.string.missing_network), Toast.LENGTH_LONG).show()
            } else if (bootstrapRepository.isBootstrapInstalled) {
                launchKlipperSetup()
            } else if (!bootstrapReleaseAvailable) {
                Toast.makeText(this, getString(R.string.bootstrap_release_unavailable), Toast.LENGTH_LONG).show()
                installationViewModel.fetchBootstrapReleases()
            } else {
                // Install only the PRoot compatibility tools from the bootstrap archive.
                // The service must not launch the bundled OctoPrint server.
                continueSetupAfterStoragePermission()
            }
        }

        skipSetupButton.setOnClickListener {
            if (!rootAuthorized) return@setOnClickListener
            prefs.pendingKlipperSetup = false
            prefs.setupAssistantSkipped = true
            launchMainActivity()
        }
        retryRootButton.setOnClickListener { requestRootAccess() }

        cameraEnumerationRepository.enumerateCameras()
        if (!bootstrapRepository.isBootstrapInstalled) installationViewModel.fetchBootstrapReleases()
        showArchitectureSelectionDialog()
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
            .setMessage(getString(R.string.architecture_message, hostArchitecture))
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
                    requestRootAccess()
                }
            }
            .create()
        dialog.setOnCancelListener { finish() }
        dialog.show()
    }

    private fun updateBootstrapSpinnerItems(releases: List<BootstrapItem>) {
        spinnerArrayAdapter.clear()
        spinnerArrayAdapter.addAll(releases)
        spinnerArrayAdapter.notifyDataSetChanged()

        val recommendedItem = releases.indexOfFirst { it.recommended }

        if (recommendedItem > -1) {
            bootstrapVersionSpinner.setSelection(recommendedItem)
        }
    }

    private fun requestRootAccess() {
        rootStatusText.text = getString(R.string.setup_root_checking)
        retryRootButton.visibility = View.GONE
        installButton.isEnabled = false
        skipSetupButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { checkSuperuserAccess() }
            // Miracle Klipper runs its Linux environment with PRoot inside the app sandbox.
            // Android root access is optional and must never block installation.
            rootAuthorized = true
            rootStatusText.text = if (result.first) {
                getString(R.string.setup_root_granted)
            } else {
                getString(R.string.setup_non_root_supported)
            }
            retryRootButton.visibility = View.GONE
            updateActionsForRootState()
            routeExistingInstallation()
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

    private fun updateActionsForRootState() {
        // Root is optional; the bootstrap archive supplies PRoot/minitar needed by Debian.
        installButton.isEnabled = rootAuthorized &&
            (bootstrapRepository.isBootstrapInstalled || bootstrapReleaseAvailable)
        skipSetupButton.isEnabled = rootAuthorized
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

    private fun continueSetupAfterStoragePermission() {
        // Bootstrap and Debian data live in the app-private files directory.
        // Do not block setup on obsolete external-storage permissions on modern Android.
        launchBootstrapInstaller()
    }

    private fun launchBootstrapInstaller() {
        if (!bootstrapRepository.isBootstrapInstallItemSelected) {
            Toast.makeText(this, getString(R.string.bootstrap_not_selected), Toast.LENGTH_LONG).show()
            return
        }
        prefs.pendingKlipperSetup = true
        startOctoService()
        startActivity(Intent(this, InstallationActivity::class.java))
        finish()
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

    private fun startOctoService() {
        startService(Intent(this, OctoPrintService::class.java))
    }

    private fun showLegacyBootstrapDialog() {
        val alertDialog: AlertDialog? = this.let {
            val builder = AlertDialog.Builder(it)
            builder.apply {
                setTitle(R.string.legacy_bootstrap)
                setMessage(R.string.legacy_bootstrap_msg)
                setNeutralButton(android.R.string.cancel) { dialog, id ->
                }

                setPositiveButton(R.string.clear_and_install) { dialog, id ->
                    // Remove all bootstrap-related data
                    bootstrapRepository.runCommand("cd .. && rm -rf *", prooted = false).getOutputAsString()
                    installationViewModel.fetchBootstrapReleases()
                }

            }

            builder.create()
        }

        alertDialog?.show()
    }

    private fun showPre1LegacyBootstrapDialog() {
        val alertDialog: AlertDialog? = this.let {
            val builder = AlertDialog.Builder(it)
            builder.apply {
                setTitle(R.string.legacy_bootstrap)
                setMessage(R.string.legacy_bootstrap_msg)
                setNeutralButton(android.R.string.cancel) { dialog, id ->
                }

                setPositiveButton(R.string.clear_and_install) { dialog, id ->
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    // clearing app data
                    if (Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT) {
                        activityManager.clearApplicationUserData()
                    } else {
                        val packageName = applicationContext.packageName
                        val runtime = Runtime.getRuntime();
                        runtime.exec("pm clear $packageName");
                    }

                    installationViewModel.fetchBootstrapReleases()
                }

            }

            builder.create()
        }

        alertDialog?.show()
    }
}
