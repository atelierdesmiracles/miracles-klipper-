package com.octo4a.ui.fragments

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.octo4a.Octo4aApplication
import com.octo4a.R
import com.octo4a.camera.*
import com.octo4a.repository.OctoPrintHandlerRepository
import com.octo4a.camera.CameraService
import com.octo4a.repository.ExtrasStatus
import com.octo4a.repository.LoggerRepository
import com.octo4a.repository.LinuxEnvironmentRepository
import com.octo4a.repository.LinuxEnvironmentStage
import com.octo4a.repository.KlipperRepository
import com.octo4a.repository.KlipperScreenRepository
import com.octo4a.repository.KlipperScreenStage
import com.octo4a.repository.TelegramBotRepository
import com.octo4a.repository.TelegramBotStage
import com.octo4a.repository.KlipperStage
import com.octo4a.repository.MoonrakerRepository
import com.octo4a.repository.MoonrakerStage
import com.octo4a.repository.KlipperWebInterface
import com.octo4a.repository.KlipperWebInterfaceRepository
import com.octo4a.repository.WebInterfaceStage
import com.octo4a.service.KlipperRuntimeService
import com.octo4a.service.KlipperScreenRuntimeService
import com.octo4a.service.MoonrakerRuntimeService
import com.octo4a.service.KlipperWebInterfaceRuntimeService
import com.octo4a.ui.WebinterfaceActivity
import com.octo4a.utils.isServiceRunning
import com.octo4a.utils.ipAddress
import com.octo4a.utils.preferences.MainPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.observeOn
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val XSERVER_PACKAGE = "x.org.server"
private const val XSERVER_DOWNLOAD_URL = "https://sourceforge.net/projects/libsdl-android/files/apk/XServer-XSDL/"

class SettingsFragment : PreferenceFragmentCompat() {
    private val cameraEnumerationRepository: CameraEnumerationRepository by inject()
    private val prefs: MainPreferences by inject()
    private val octoprintHandler: OctoPrintHandlerRepository by inject()
    private val logger: LoggerRepository by inject()
    private val linuxEnvironment: LinuxEnvironmentRepository by inject()
    private val klipper: KlipperRepository by inject()
    private val klipperScreen: KlipperScreenRepository by inject()
    private val moonraker: MoonrakerRepository by inject()
    private val telegramBot: TelegramBotRepository by inject()

    // Camera permission request
    private val hasCameraPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    // Preferences
    private val enableCameraPref by lazy { findPreference<SwitchPreferenceCompat>("enableCameraServer") }
    private val enableSSH by lazy { findPreference<SwitchPreferenceCompat>("enableSSH") }
    private val sshPortPref by lazy { findPreference<EditTextPreference>("sshPort") }
    private val selectedCameraPref by lazy { findPreference<ListPreference>("selectedCamera") }
    private val selectedCameraResolution by lazy { findPreference<ListPreference>("selectedResolution") }
    private val selectedCameraVideoResolution by lazy { findPreference<ListPreference>("selectedVideoResolution") }
    private val sshPasswordPref by lazy { findPreference<EditTextPreference>("changeSSHPassword") }
    private val fpsLimit by lazy { findPreference<ListPreference>("fpsLimit") }
    private val flashWhenObserved by lazy { findPreference<SwitchPreferenceCompat>("flashWhenObserved") }
    private val installPluginExtras by lazy { findPreference<Preference>("installPluginExtras") }
    private val imageRotation by lazy { findPreference<ListPreference>("imageRotation") }
    private val manualAF by lazy { findPreference<SwitchPreferenceCompat>("manualAF") }
    private val enableBugReporting by lazy { findPreference<SwitchPreferenceCompat>("enableBugReporting") }
    private val installKlipperEnvironment by lazy { findPreference<Preference>("installKlipperEnvironment") }
    private val installKlipper by lazy { findPreference<Preference>("installKlipper") }
    private val startKlipper by lazy { findPreference<Preference>("startKlipper") }
    private val stopKlipper by lazy { findPreference<Preference>("stopKlipper") }
    private val installMoonraker by lazy { findPreference<Preference>("installMoonraker") }
    private val startMoonraker by lazy { findPreference<Preference>("startMoonraker") }
    private val stopMoonraker by lazy { findPreference<Preference>("stopMoonraker") }
    private val webInterface: KlipperWebInterfaceRepository by inject()
    private val installMainsail by lazy { findPreference<Preference>("installMainsail") }
    private val installFluidd by lazy { findPreference<Preference>("installFluidd") }
    private val selectedKlipperWebInterface by lazy {
        findPreference<ListPreference>("selectedKlipperWebInterface")
    }
    private val openKlipperWebInterface by lazy { findPreference<Preference>("openKlipperWebInterface") }
    private val stopKlipperWebInterface by lazy { findPreference<Preference>("stopKlipperWebInterface") }
    private val installKlipperScreen by lazy { findPreference<Preference>("installKlipperScreen") }
    private val installXServerXSDL by lazy { findPreference<Preference>("installXServerXSDL") }
    private val startKlipperScreen by lazy { findPreference<Preference>("startKlipperScreen") }
    private val stopKlipperScreen by lazy { findPreference<Preference>("stopKlipperScreen") }
    private val configureTelegramBot by lazy { findPreference<Preference>("configureTelegramBot") }
    private val stopTelegramBot by lazy { findPreference<Preference>("stopTelegramBot") }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            enableCameraPref?.isChecked = isGranted
            if (isGranted) {
                cameraEnumerationRepository.enumerateCameras()
                octoprintHandler.isCameraServerRunning = true
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_preferences, rootKey)

        enableBugReporting?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                (activity?.application as Octo4aApplication).startBugsnag()
            }
            true
        }

        setupSSHSettings()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!hasCameraPermission) {
            enableCameraPref?.isChecked = false
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        cameraEnumerationRepository.enumeratedCameras.observe(viewLifecycleOwner) {
            setupCameraSettings(it)
        }

        octoprintHandler.extrasStatus.asLiveData().observe(viewLifecycleOwner) {
            when (it) {
                ExtrasStatus.Installing -> {
                    installPluginExtras?.summary = "Installing..."
                }

                ExtrasStatus.NotInstalled -> {
                    installPluginExtras?.summary = "Not installed"
                }

                else -> {
                    installPluginExtras?.summary = "Installed"
                }
            }
        }

        installPluginExtras?.setOnPreferenceClickListener {
            octoprintHandler.installExtras()
            true
        }

        linuxEnvironment.progress.asLiveData().observe(viewLifecycleOwner) { progress ->
            installKlipperEnvironment?.apply {
                summary = progress.message
                isEnabled = progress.stage != LinuxEnvironmentStage.Downloading &&
                    progress.stage != LinuxEnvironmentStage.Extracting &&
                    progress.stage != LinuxEnvironmentStage.InstallingPackages
            }
        }

        installKlipperEnvironment?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    linuxEnvironment.install()
                    Toast.makeText(requireContext(), "Debian 13 environment is ready", Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {
                    context?.applicationContext?.let {
                        Toast.makeText(
                            it,
                            "Debian installation interrupted. Tap again to retry.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: "Debian environment installation failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }

        klipper.progress.asLiveData().observe(viewLifecycleOwner) { progress ->
            installKlipper?.apply {
                summary = progress.message
                isEnabled = progress.stage != KlipperStage.Installing
            }
            startKlipper?.apply {
                summary = if (klipper.isPrinterConfigurationReady) {
                    getString(R.string.klipper_started)
                } else {
                    getString(R.string.klipper_configuration_required)
                }
                isEnabled = klipper.isInstalled && klipper.isPrinterConfigurationReady &&
                    progress.stage != KlipperStage.Running && progress.stage != KlipperStage.Starting
            }
            stopKlipper?.apply {
                summary = progress.message
                isEnabled = progress.stage == KlipperStage.Running ||
                    progress.stage == KlipperStage.Starting || progress.stage == KlipperStage.Stopping
            }
        }

        installKlipper?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    klipper.install()
                    Toast.makeText(requireContext(), R.string.klipper_install_success, Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: getString(R.string.klipper_install_failure),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }

        startKlipper?.setOnPreferenceClickListener {
            if (!klipper.isPrinterConfigurationReady) {
                Toast.makeText(requireContext(), R.string.klipper_configuration_required, Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(requireContext(), KlipperRuntimeService::class.java)
                    .setAction(KlipperRuntimeService.ACTION_START)
                ContextCompat.startForegroundService(requireContext(), intent)
            }
            true
        }

        stopKlipper?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), KlipperRuntimeService::class.java)
                .setAction(KlipperRuntimeService.ACTION_STOP)
            requireContext().startService(intent)
            true
        }

        moonraker.progress.asLiveData().observe(viewLifecycleOwner) { progress ->
            installMoonraker?.apply {
                summary = progress.message
                isEnabled = progress.stage != MoonrakerStage.Installing
            }
            startMoonraker?.apply {
                summary = progress.message
                isEnabled = moonraker.isInstalled && progress.stage !in setOf(
                    MoonrakerStage.Starting,
                    MoonrakerStage.Ready,
                    MoonrakerStage.KlipperDisconnected,
                    MoonrakerStage.Running,
                    MoonrakerStage.ApiUnavailable,
                    MoonrakerStage.Stopping
                )
            }
            stopMoonraker?.apply {
                summary = progress.message
                isEnabled = progress.stage in setOf(
                    MoonrakerStage.Starting,
                    MoonrakerStage.Ready,
                    MoonrakerStage.KlipperDisconnected,
                    MoonrakerStage.Running,
                    MoonrakerStage.ApiUnavailable,
                    MoonrakerStage.Stopping
                )
            }
        }

        installMoonraker?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    moonraker.install()
                    Toast.makeText(requireContext(), R.string.moonraker_install_success, Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: getString(R.string.moonraker_install_failure),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }

        startMoonraker?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), MoonrakerRuntimeService::class.java)
                .setAction(MoonrakerRuntimeService.ACTION_START)
            ContextCompat.startForegroundService(requireContext(), intent)
            true
        }

        stopMoonraker?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), MoonrakerRuntimeService::class.java)
                .setAction(MoonrakerRuntimeService.ACTION_STOP)
            requireContext().startService(intent)
            true
        }

        klipperScreen.progress.asLiveData().observe(viewLifecycleOwner) { progress ->
            val busy = progress.stage in setOf(
                KlipperScreenStage.Installing,
                KlipperScreenStage.Starting,
                KlipperScreenStage.Stopping
            )
            installKlipperScreen?.apply {
                summary = progress.message
                isEnabled = progress.stage != KlipperScreenStage.Installing
            }
            startKlipperScreen?.apply {
                summary = when {
                    progress.stage == KlipperScreenStage.Running -> progress.message
                    !klipperScreen.isInstalled -> "Install KlipperScreen before opening it."
                    else -> getString(R.string.klipperscreen_start_summary)
                }
                isEnabled = klipperScreen.isInstalled && !busy && !klipperScreen.isRunning
            }
            stopKlipperScreen?.apply {
                summary = progress.message
                isEnabled = klipperScreen.isRunning || progress.stage == KlipperScreenStage.Starting
            }
        }

        installKlipperScreen?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    klipperScreen.install()
                    Toast.makeText(requireContext(), R.string.klipperscreen_install_success, Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: getString(R.string.klipperscreen_install_failure),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }

        installXServerXSDL?.setOnPreferenceClickListener {
            openXServerDownloadPage()
            true
        }

        startKlipperScreen?.setOnPreferenceClickListener {
            val host = requireContext().ipAddress
            if (host.isBlank() || host == "0.0.0.0") {
                Toast.makeText(requireContext(), R.string.klipperscreen_wifi_required, Toast.LENGTH_LONG).show()
            } else {
                val launchIntent = requireContext().packageManager.getLaunchIntentForPackage(XSERVER_PACKAGE)
                if (launchIntent == null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.klipperscreen_xserver_title)
                        .setMessage(R.string.klipperscreen_xserver_missing)
                        .setPositiveButton(R.string.klipperscreen_download) { _, _ -> openXServerDownloadPage() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    try {
                        startActivity(launchIntent)
                        val serviceIntent = Intent(requireContext(), KlipperScreenRuntimeService::class.java)
                            .setAction(KlipperScreenRuntimeService.ACTION_START)
                            .putExtra(KlipperScreenRuntimeService.EXTRA_DISPLAY_HOST, host)
                        ContextCompat.startForegroundService(requireContext(), serviceIntent)
                    } catch (error: Exception) {
                        Toast.makeText(
                            requireContext(),
                            error.message ?: getString(R.string.klipperscreen_start_failure),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            true
        }

        stopKlipperScreen?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), KlipperScreenRuntimeService::class.java)
                .setAction(KlipperScreenRuntimeService.ACTION_STOP)
            requireContext().startService(intent)
            true
        }

        telegramBot.progress.asLiveData().observe(viewLifecycleOwner) { progress ->
            configureTelegramBot?.apply {
                summary = progress.message
                isEnabled = progress.stage != TelegramBotStage.Installing &&
                    progress.stage != TelegramBotStage.Starting &&
                    progress.stage != TelegramBotStage.Stopping
            }
            stopTelegramBot?.apply {
                summary = progress.message
                isEnabled = telegramBot.isRunning || progress.stage == TelegramBotStage.Starting
            }
        }

        configureTelegramBot?.setOnPreferenceClickListener {
            val form = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, 0)
            }
            val tokenInput = android.widget.EditText(requireContext()).apply {
                hint = "Jeton fourni par @BotFather"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("")
            }
            val chatInput = android.widget.EditText(requireContext()).apply {
                hint = "Identifiant de chat Telegram"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText("")
            }
            form.addView(tokenInput, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            form.addView(chatInput, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            AlertDialog.Builder(requireContext())
                .setTitle("Configurer le bot Telegram")
                .setMessage("Les identifiants sont enregistrés dans les données privées de l’application. Ne partage pas le jeton.")
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Installer et démarrer", null)
                .create().also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val token = tokenInput.text.toString().trim()
                            val chatId = chatInput.text.toString().trim()
                            try {
                                telegramBot.configure(token, chatId)
                                dialog.dismiss()
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        telegramBot.install()
                                        telegramBot.start()
                                        Toast.makeText(requireContext(), "Bot Telegram démarré", Toast.LENGTH_LONG).show()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        Toast.makeText(requireContext(), error.message ?: "Échec du démarrage Telegram", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } catch (error: Exception) {
                                Toast.makeText(requireContext(), error.message ?: "Configuration Telegram invalide", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    dialog.show()
                }
            true
        }

        stopTelegramBot?.setOnPreferenceClickListener {
            telegramBot.stop()
            true
        }

        webInterface.progress.asLiveData().observe(viewLifecycleOwner) { progress ->
            val busy = progress.stage in setOf(
                WebInterfaceStage.InstallingNginx,
                WebInterfaceStage.Downloading,
                WebInterfaceStage.Extracting
            )
            installMainsail?.apply {
                summary = when {
                    progress.client == KlipperWebInterface.Mainsail -> progress.message
                    webInterface.isInstalled(KlipperWebInterface.Mainsail) -> "Mainsail is installed"
                    else -> getString(R.string.klipper_web_interface_install_mainsail_summary)
                }
                isEnabled = !busy
            }
            installFluidd?.apply {
                summary = when {
                    progress.client == KlipperWebInterface.Fluidd -> progress.message
                    webInterface.isInstalled(KlipperWebInterface.Fluidd) -> "Fluidd is installed"
                    else -> getString(R.string.klipper_web_interface_install_fluidd_summary)
                }
                isEnabled = !busy
            }
            openKlipperWebInterface?.apply {
                summary = if (progress.stage == WebInterfaceStage.Ready) {
                    progress.message
                } else {
                    getString(R.string.klipper_web_interface_url)
                }
                isEnabled = webInterface.isInstalled(webInterface.selectedClient)
            }
            stopKlipperWebInterface?.apply {
                summary = if (webInterface.isRunning) {
                    "Nginx is running on port 8080."
                } else {
                    "Stops Nginx and releases port 8080."
                }
                isEnabled = webInterface.isRunning
            }
        }

        installMainsail?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    webInterface.install(KlipperWebInterface.Mainsail)
                    Toast.makeText(requireContext(), "Mainsail is installed", Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: "Mainsail installation failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }

        installFluidd?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    webInterface.install(KlipperWebInterface.Fluidd)
                    Toast.makeText(requireContext(), "Fluidd is installed", Toast.LENGTH_LONG).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: "Fluidd installation failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }

        selectedKlipperWebInterface?.setOnPreferenceChangeListener { _, _ ->
            if (webInterface.isRunning) {
                val intent = Intent(requireContext(), KlipperWebInterfaceRuntimeService::class.java)
                    .setAction(KlipperWebInterfaceRuntimeService.ACTION_RESTART)
                ContextCompat.startForegroundService(requireContext(), intent)
            }
            true
        }

        openKlipperWebInterface?.setOnPreferenceClickListener {
            val client = webInterface.selectedClient
            if (!webInterface.isInstalled(client)) {
                Toast.makeText(
                    requireContext(),
                    "Install " + client.displayName + " before opening the Klipper interface",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val serviceIntent = Intent(requireContext(), KlipperWebInterfaceRuntimeService::class.java)
                    .setAction(KlipperWebInterfaceRuntimeService.ACTION_START)
                ContextCompat.startForegroundService(requireContext(), serviceIntent)
                startActivity(
                    Intent(requireContext(), WebinterfaceActivity::class.java)
                        .putExtra(WebinterfaceActivity.EXTRA_WEB_URL, KlipperWebInterfaceRepository.LOCAL_URL)
                )
            }
            true
        }

        stopKlipperWebInterface?.setOnPreferenceClickListener {
            val intent = Intent(requireContext(), KlipperWebInterfaceRuntimeService::class.java)
                .setAction(KlipperWebInterfaceRuntimeService.ACTION_STOP)
            requireContext().startService(intent)
            true
        }

        octoprintHandler.getExtrasStatus()
    }

    private fun setupSSHSettings() {
        sshPortPref?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            summary = prefs.sshPort

            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).toIntOrNull()
                if (value != null && value in 1025..65534) {
                    summary = newValue

                    // Restart SSH
                    prefs.sshPort = newValue
                    octoprintHandler.startSSH()

                    true
                } else {
                    false
                }
            }
            setDefaultValue("8022")
        }
        enableSSH?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                if (octoprintHandler.isSSHConfigured) {
                    octoprintHandler.startSSH()
                } else {
                    enableSSH?.isChecked = false
                    preferenceManager.showDialog(sshPasswordPref)
                }
            } else {
                octoprintHandler.stopSSH()
            }
            true
        }

        sshPasswordPref?.apply {
            summary = "*".repeat(prefs.changeSSHPassword?.length ?: 0)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                sshPasswordPref?.summaryProvider =
                    Preference.SummaryProvider<EditTextPreference> { pref ->
                        "*".repeat(pref?.text?.length ?: 0)
                    }
            }
            setOnPreferenceChangeListener { _, newValue ->
                octoprintHandler.stopSSH()
                octoprintHandler.resetSSHPassword(newValue as String)
                octoprintHandler.startSSH()
                prefs.sshPasword = newValue
                enableSSH?.isChecked = true
                true
            }
        }
    }

    private fun setupCameraSettings(cameras: List<CameraDescription>) {
        val enableCameraPref = findPreference<SwitchPreferenceCompat>("enableCameraServer")
        if (!hasCameraPermission) {
            prefs.enableCameraServer = false
            enableCameraPref?.isChecked = false
        } else {
            initCameraConfig(cameras)
        }

        imageRotation?.setOnPreferenceChangeListener { _, value ->
            prefs.imageRotation = value as String
            stopCameraServer()
            startCameraServer()
            true
        }

        fpsLimit?.setOnPreferenceChangeListener { _, value ->
            prefs.fpsLimit = value as String
            stopCameraServer()
            startCameraServer()
            true
        }

        flashWhenObserved?.setOnPreferenceChangeListener { _, value ->
            stopCameraServer()
            startCameraServer()
            true
        }

        enableCameraPref?.setOnPreferenceChangeListener { _, newValue ->
            if (cameras.isEmpty()) {
                enableCameraPref.isChecked = false
                Toast.makeText(requireContext(), "No cameras available", Toast.LENGTH_LONG).show()
            } else {
                if (!hasCameraPermission) {
                    enableCameraPref.isChecked = false
                    requestCameraPermission.launch(Manifest.permission.CAMERA)
                } else {
                    initCameraConfig(cameras)
                    if (newValue as Boolean) {
                        startCameraServer()
                    } else {
                        stopCameraServer()
                    }
                }
            }
            true
        }
    }

    // Injects proper camera resolution configs
    private fun initCameraConfig(cameras: List<CameraDescription>) {
        if (cameras.isEmpty()) {
            return
        }
        if (prefs.selectedCamera == null) {
            // Select first back camera or use whatever camera's available
            val selectedCamera =
                cameras.firstOrNull { it.isBackFacing } ?: cameras.firstOrNull()

            // First resolution that's wider than 1000px
            val selectedResolution =
                selectedCamera?.sizes?.sortedBy { it.width }?.firstOrNull { it.width >= 1000 }
                    ?: selectedCamera?.sizes?.first()
            prefs.selectedCamera = selectedCamera?.id
            prefs.selectedResolution = selectedResolution?.readableString()
            prefs.selectedVideoResolution = selectedResolution?.readableString()
        }

        manualAF?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                prefs.manualAF = newValue as Boolean
                stopCameraServer()
                startCameraServer()
                true
            }
        }

        selectedCameraPref?.apply {
            // Set values and descriptions
            entries = cameras.map { it.describeString() }.toTypedArray()
            entryValues = cameras.map { it.id }.toTypedArray()

            // When camera changes, reset to default resolution
            setOnPreferenceChangeListener { _, newValue ->
                val selectedCam = cameraEnumerationRepository.cameraWithId(newValue as String)
                prefs.selectedResolution = selectedCam?.getRecommendedSize()?.readableString()
                setCameraResolutions(newValue)
                selectedCameraResolution?.value = prefs.selectedResolution
                selectedCameraVideoResolution?.value = prefs.selectedVideoResolution
                startCameraServer()
                true
            }
        }
        val currentCam = prefs.selectedCamera
        selectedCameraPref?.value = currentCam!!
        setCameraResolutions(currentCam)
        selectedCameraResolution?.value = prefs.selectedResolution
        selectedCameraVideoResolution?.value = prefs.selectedVideoResolution
    }

    private fun setCameraResolutions(selectedCam: String) {
        val camera = cameraEnumerationRepository.cameraWithId(selectedCam)
        selectedCameraResolution?.apply {
            entries = camera?.sizes?.map { it.readableString() }?.toTypedArray()
            entryValues = entries
            setOnPreferenceChangeListener { _, value ->
                prefs.selectedResolution = value as String
                startCameraServer()
                true
            }
        }
        selectedCameraVideoResolution?.apply {
            entries = camera?.sizes?.map { it.readableString() }?.toTypedArray()
            entryValues = entries
            setOnPreferenceChangeListener { _, value ->
                prefs.selectedVideoResolution = value as String
                startCameraServer()
                true
            }
        }
    }

    private fun startCameraServer() {
        val activityContext = requireActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cameraServiceIntent = Intent(activityContext, CameraService::class.java)

            if (activityContext.isServiceRunning(CameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
            activityContext.startService(cameraServiceIntent)
        } else {
            val cameraServiceIntent = Intent(activityContext, LegacyCameraService::class.java)

            if (activityContext.isServiceRunning(LegacyCameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
            activityContext.startService(cameraServiceIntent)
        }
    }

    private fun openXServerDownloadPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XSERVER_DOWNLOAD_URL)))
        } catch (error: Exception) {
            Toast.makeText(requireContext(), error.message ?: getString(R.string.klipperscreen_start_failure), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopCameraServer() {
        val activityContext = requireActivity()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cameraServiceIntent = Intent(activityContext, CameraService::class.java)

            if (activityContext.isServiceRunning(CameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
        } else {
            val cameraServiceIntent = Intent(activityContext, LegacyCameraService::class.java)

            if (activityContext.isServiceRunning(LegacyCameraService::class.java)) {
                activityContext.stopService(cameraServiceIntent)
            }
        }
    }
}
