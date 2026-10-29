package com.octo4a.ui.fragments

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.octo4a.Octo4aApplication
import com.octo4a.R
import com.octo4a.camera.CameraService
import com.octo4a.repository.GithubRelease
import com.octo4a.repository.LoggerRepository
import com.octo4a.repository.LinuxEnvironmentRepository
import com.octo4a.repository.KlipperRepository
import com.octo4a.repository.KlipperStage
import com.octo4a.repository.MoonrakerRepository
import com.octo4a.repository.MoonrakerStage
import com.octo4a.repository.KlipperWebInterface
import com.octo4a.repository.KlipperWebInterfaceRepository
import com.octo4a.repository.WebInterfaceStage
import com.octo4a.serial.VirtualSerialDriver
import com.octo4a.ui.InitialActivity
import com.octo4a.ui.WebinterfaceActivity
import com.octo4a.ui.showBugReportingDialog
import com.octo4a.service.KlipperRuntimeService
import com.octo4a.service.MoonrakerRuntimeService
import com.octo4a.service.KlipperWebInterfaceRuntimeService
import com.octo4a.ui.views.UsbDeviceView
import com.octo4a.utils.preferences.MainPreferences
import com.octo4a.utils.ManualLifecycleOwner
import com.octo4a.viewmodel.IPAddress
import com.octo4a.viewmodel.IPAddressType
import com.octo4a.viewmodel.NetworkStatusViewModel
import com.octo4a.viewmodel.StatusViewModel
import kotlinx.android.synthetic.main.fragment_server.*
import kotlinx.android.synthetic.main.view_status_card.view.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.sharedViewModel


class ServerFragment : Fragment() {
    class PreviewViewModel : ViewModel() {
        var visible: Boolean = false
        var preview: Preview? = null
        var lifecycle: ManualLifecycleOwner? = null
    }

    private val statusViewModel: StatusViewModel by sharedViewModel()
    private val networkStatusViewModel: NetworkStatusViewModel by sharedViewModel()
    private var _cameraService: CameraService? = null
    private lateinit var _previewViewModel: PreviewViewModel // needed to handle screen rotation while preview window up
    private val vspDriver: VirtualSerialDriver by inject()
    private val mainPreferences: MainPreferences by inject()
    private val logger: LoggerRepository by inject()
    private val linuxEnvironment: LinuxEnvironmentRepository by inject()
    private val klipper: KlipperRepository by inject()
    private val moonraker: MoonrakerRepository by inject()
    private val klipperWebInterface: KlipperWebInterfaceRepository by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_server, container, false)
    }

    override fun onStart() {
        super.onStart()
        networkStatusViewModel.scanIPAddresses()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusViewModel.updateAvailable.observe(viewLifecycleOwner) {
            logger.log(this) { "Update available" }
            showUpdateDialog(it)
        }

        _previewViewModel = ViewModelProvider(this).get(PreviewViewModel::class.java)
        if (_previewViewModel.visible) {
            showPreviewDialog()
        }

        vspDriver.connectedDevices.asLiveData().observe(viewLifecycleOwner) { devices ->
            usbDevicesList.removeAllViews()
            devices.forEach {
                val usbDeviceView = UsbDeviceView(requireContext(), vspDriver)
                usbDevicesList.addView(usbDeviceView)
                usbDeviceView.setUsbDevice(it)
            }
        }

        statusViewModel.usbStatus.observe(viewLifecycleOwner) {
            if (it.isAttached) {
                connectionStatus.title = getString(R.string.connection_connected)
                connectionStatus.subtitle = it.port
            } else {
                connectionStatus.title = getString(R.string.connection_not_connected)
                connectionStatus.subtitle = getString(R.string.connection_otg_cable)
            }
        }

        camServerStatus.setOnClickListener {
            if (statusViewModel.cameraStatus.value == true) {
                showPreviewDialog()
            }
        }

        serverStatus.setOnClickListener { openOrStartKlipperInterface() }

        statusViewModel.cameraStatus.observe(viewLifecycleOwner) {
            if (it) {
                camServerStatus.title = getString(R.string.camserver_running)
                camServerStatus.subtitle = getString(R.string.camserver_status_tap)
            } else {
                camServerStatus.title = getString(R.string.camserver_disabled)
                camServerStatus.subtitle = getString(R.string.camserver_enable)
            }
        }

        klipper.progress.asLiveData().observe(viewLifecycleOwner) { updateKlipperDashboardCard() }
        moonraker.progress.asLiveData().observe(viewLifecycleOwner) { updateKlipperDashboardCard() }
        klipperWebInterface.progress.asLiveData().observe(viewLifecycleOwner) { updateKlipperDashboardCard() }
        linuxEnvironment.progress.asLiveData().observe(viewLifecycleOwner) { updateKlipperDashboardCard() }
        updateKlipperDashboardCard()

        networkStatusViewModel.ipAddresses.observe(viewLifecycleOwner) {
            Log.d("ServerFragment", "IP addresses: ${it.joinToString(", ") { it.address }} }}")
            serverStatus.ipAddresses = it.map { it.copy(port = "8080") }.toTypedArray()
            val hasNoLocalNetwork = it.isEmpty() || it.all { it.type == IPAddressType.Cellular }
            updateDashboardWarning(hasNoLocalNetwork)
        }

        // Fetch autoupdater
        statusViewModel.checkUpdateAvailable()
    }


    private fun showUpdateDialog(update: GithubRelease) {
        if (update.tagName != mainPreferences.updateDismissed) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.update_available))
                .setMessage(getString(R.string.update_available_message).format(update.tagName))
                .setPositiveButton(getString(R.string.action_download)) { dialog, _ ->
                    val i = Intent(Intent.ACTION_VIEW)
                    i.data = Uri.parse(update.htmlUrl)
                    startActivity(i)
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.action_later)) { dialog, _ ->
                    dialog.dismiss()
                    mainPreferences.updateDismissed = update.tagName
                }
                .show()
        }
    }

    private fun updateKlipperDashboardCard() {
        val selected = klipperWebInterface.selectedClient
        val klipperState = klipper.progress.value
        val moonrakerState = moonraker.progress.value
        val webState = klipperWebInterface.progress.value
        val isReady = webState.stage == WebInterfaceStage.Ready &&
            webState.client == selected && klipperWebInterface.isRunning
        val isStarting = klipperState.stage in setOf(
            KlipperStage.Installing, KlipperStage.Starting, KlipperStage.Stopping
        ) || moonrakerState.stage in setOf(
            MoonrakerStage.Installing, MoonrakerStage.Starting, MoonrakerStage.Stopping
        ) || webState.stage in setOf(
            WebInterfaceStage.InstallingNginx,
            WebInterfaceStage.Downloading,
            WebInterfaceStage.Extracting,
            WebInterfaceStage.Starting,
            WebInterfaceStage.Stopping
        )

        serverStatus.setDrawableAndColor(
            if (isReady) R.drawable.ic_stop_24px else R.drawable.ic_play_arrow_24px,
            if (isReady) android.R.color.holo_red_light else R.color.iconGreen
        )
        serverStatus.actionProgressbar.isGone = !isStarting
        serverStatus.actionButton.isGone = isStarting
        serverStatus.onActionClicked = {
            if (isReady) stopKlipperStack() else openOrStartKlipperInterface()
        }
        serverStatus.showIpAddresses = isReady
        updateDashboardWarning()

        when {
            isReady -> {
                serverStatus.title = getString(R.string.klipper_dashboard_running, selected.displayName)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_open)
            }
            isStarting -> {
                serverStatus.title = getString(R.string.klipper_dashboard_starting)
                serverStatus.subtitle = getString(R.string.status_starting_subtitle)
            }
            !linuxEnvironment.isReady -> {
                serverStatus.title = getString(R.string.klipper_dashboard_setup_required)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_install_debian)
            }
            !klipper.isInstalled -> {
                serverStatus.title = getString(R.string.klipper_dashboard_setup_required)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_install_klipper)
            }
            !klipper.isPrinterConfigurationReady -> {
                serverStatus.title = getString(R.string.klipper_dashboard_setup_required)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_configure_printer)
            }
            !moonraker.isInstalled -> {
                serverStatus.title = getString(R.string.klipper_dashboard_setup_required)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_install_moonraker)
            }
            moonrakerState.stage == MoonrakerStage.NeedsConfiguration -> {
                serverStatus.title = getString(R.string.klipper_dashboard_setup_required)
                serverStatus.subtitle = moonrakerState.message
            }
            !klipperWebInterface.isInstalled(selected) -> {
                serverStatus.title = getString(R.string.klipper_dashboard_setup_required)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_install_web, selected.displayName)
            }
            webState.stage == WebInterfaceStage.Failed -> {
                serverStatus.title = getString(R.string.klipper_dashboard_error)
                serverStatus.subtitle = webState.message
            }
            moonrakerState.stage == MoonrakerStage.Failed -> {
                serverStatus.title = getString(R.string.klipper_dashboard_error)
                serverStatus.subtitle = moonrakerState.message
            }
            klipperState.stage == KlipperStage.Failed -> {
                serverStatus.title = getString(R.string.klipper_dashboard_error)
                serverStatus.subtitle = klipperState.message
            }
            else -> {
                serverStatus.title = getString(R.string.klipper_dashboard_stopped, selected.displayName)
                serverStatus.subtitle = getString(R.string.klipper_dashboard_start)
            }
        }
    }

    private fun updateDashboardWarning(noLocalNetwork: Boolean? = null) {
        val webState = klipperWebInterface.progress.value
        val noLocalNetworkAvailable = noLocalNetwork ?: networkStatusViewModel.ipAddresses.value.let { addresses ->
            addresses.isNullOrEmpty() || addresses.all { it.type == IPAddressType.Cellular }
        }
        serverStatus.warning = when {
            webState.stage == WebInterfaceStage.Ready && webState.moonrakerReachable == false ->
                getString(R.string.klipper_dashboard_moonraker_unavailable)
            noLocalNetworkAvailable -> getString(R.string.no_local_network)
            else -> ""
        }
    }

    private fun openOrStartKlipperInterface() {
        if (klipper.progress.value.stage in setOf(KlipperStage.Installing, KlipperStage.Starting, KlipperStage.Stopping) ||
            moonraker.progress.value.stage in setOf(MoonrakerStage.Installing, MoonrakerStage.Starting, MoonrakerStage.Stopping) ||
            klipperWebInterface.progress.value.stage in setOf(
                WebInterfaceStage.InstallingNginx,
                WebInterfaceStage.Downloading,
                WebInterfaceStage.Extracting,
                WebInterfaceStage.Starting,
                WebInterfaceStage.Stopping
            )
        ) return

        if (klipperWebInterface.progress.value.stage == WebInterfaceStage.Ready &&
            klipperWebInterface.progress.value.client == klipperWebInterface.selectedClient &&
            klipperWebInterface.isRunning
        ) {
            openKlipperInterfaceActivity()
            return
        }

        // Mainsail must remain available before the printer has been configured.
        // Klipper cannot start without [mcu] and [printer], but Moonraker can start
        // disconnected and expose its configuration-file API through Mainsail.
        val missingSetup = when {
            !linuxEnvironment.isReady -> getString(R.string.klipper_dashboard_install_debian)
            !klipper.isInstalled -> getString(R.string.klipper_dashboard_install_klipper)
            !moonraker.isInstalled -> getString(R.string.klipper_dashboard_install_moonraker)
            !klipperWebInterface.isInstalled(klipperWebInterface.selectedClient) ->
                getString(R.string.klipper_dashboard_install_web, klipperWebInterface.selectedClient.displayName)
            else -> null
        }
        if (missingSetup != null) {
            Toast.makeText(requireContext(), missingSetup, Toast.LENGTH_LONG).show()
            findNavController().navigate(R.id.settingsNav)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withTimeout(180_000L) {
                    // Only start Klipper when a real printer configuration is present.
                    // Otherwise keep it stopped and bring up Moonraker + Mainsail so the
                    // user can edit printer.cfg instead of being trapped outside the UI.
                    if (klipper.isPrinterConfigurationReady &&
                        klipper.progress.value.stage != KlipperStage.Running
                    ) {
                        val previousKlipperState = klipper.progress.value
                        ContextCompat.startForegroundService(
                            requireContext(),
                            Intent(requireContext(), KlipperRuntimeService::class.java)
                                .setAction(KlipperRuntimeService.ACTION_START)
                        )
                        val firstKlipperUpdate = klipper.progress.first { it != previousKlipperState }
                        val finalKlipperState = if (firstKlipperUpdate.stage == KlipperStage.Starting) {
                            klipper.progress.first {
                                it.stage in setOf(KlipperStage.Running, KlipperStage.Failed, KlipperStage.NeedsConfiguration)
                            }
                        } else {
                            firstKlipperUpdate
                        }
                        check(finalKlipperState.stage == KlipperStage.Running) {
                            finalKlipperState.message
                        }
                    }

                    // Moonraker is allowed to report KlipperDisconnected while the
                    // printer configuration is being edited; that is not a startup failure.
                    if (moonraker.progress.value.stage !in setOf(
                            MoonrakerStage.Ready,
                            MoonrakerStage.KlipperDisconnected,
                            MoonrakerStage.Running
                        )
                    ) {
                        val previousMoonrakerState = moonraker.progress.value
                        ContextCompat.startForegroundService(
                            requireContext(),
                            Intent(requireContext(), MoonrakerRuntimeService::class.java)
                                .setAction(MoonrakerRuntimeService.ACTION_START)
                        )
                        val firstMoonrakerUpdate = moonraker.progress.first { it != previousMoonrakerState }
                        val finalMoonrakerState = if (firstMoonrakerUpdate.stage == MoonrakerStage.Starting) {
                            moonraker.progress.first {
                                it.stage in setOf(
                                    MoonrakerStage.Ready,
                                    MoonrakerStage.KlipperDisconnected,
                                    MoonrakerStage.Running,
                                    MoonrakerStage.NeedsConfiguration,
                                    MoonrakerStage.ApiUnavailable,
                                    MoonrakerStage.Failed
                                )
                            }
                        } else {
                            firstMoonrakerUpdate
                        }
                        check(finalMoonrakerState.stage in setOf(
                            MoonrakerStage.Ready,
                            MoonrakerStage.KlipperDisconnected,
                            MoonrakerStage.Running
                        )) {
                            finalMoonrakerState.message
                        }
                    }

                    if (klipperWebInterface.progress.value.stage != WebInterfaceStage.Ready ||
                        !klipperWebInterface.isRunning
                    ) {
                        val previousWebInterfaceState = klipperWebInterface.progress.value
                        ContextCompat.startForegroundService(
                            requireContext(),
                            Intent(requireContext(), KlipperWebInterfaceRuntimeService::class.java)
                                .setAction(KlipperWebInterfaceRuntimeService.ACTION_START)
                        )
                        klipperWebInterface.progress.first {
                            it != previousWebInterfaceState && (
                                it.stage == WebInterfaceStage.Failed ||
                                    (it.stage == WebInterfaceStage.Ready && klipperWebInterface.isRunning)
                                )
                        }
                        check(klipperWebInterface.progress.value.stage == WebInterfaceStage.Ready &&
                            klipperWebInterface.isRunning
                        ) {
                            klipperWebInterface.progress.value.message
                        }
                    }
                }
                openKlipperInterfaceActivity()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.klipper_dashboard_start_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun stopKlipperStack() {
        val context = requireContext()
        context.startService(
            Intent(context, KlipperWebInterfaceRuntimeService::class.java)
                .setAction(KlipperWebInterfaceRuntimeService.ACTION_STOP)
        )
        context.startService(
            Intent(context, MoonrakerRuntimeService::class.java)
                .setAction(MoonrakerRuntimeService.ACTION_STOP)
        )
        context.startService(
            Intent(context, KlipperRuntimeService::class.java)
                .setAction(KlipperRuntimeService.ACTION_STOP)
        )
    }

    private fun openKlipperInterfaceActivity() {
        startActivity(
            Intent(context, WebinterfaceActivity::class.java)
                .putExtra(WebinterfaceActivity.EXTRA_WEB_URL, KlipperWebInterfaceRepository.LOCAL_URL)
        )
    }

    private fun showPreviewDialog() {
        logger.log(this) { "showPreviewDialog" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CameraPreviewDialogFragment().show(childFragmentManager, "CameraPreviewDialogFragment")
        } else {
            Toast.makeText(context, getString(R.string.api_too_low), Toast.LENGTH_LONG).show()
        }
    }

    private fun clearDataAndRestartApp() {
        val builder = AlertDialog.Builder(context)
        builder.apply {
            setTitle(getString(R.string.reinstall_dialog_title))
            setMessage(R.string.app_will_restart_to_clear)
            setNegativeButton(getString(R.string.reinstall_dialog_dismiss)) { dialog, id ->

            }
            setPositiveButton(getString(R.string.reinstall_dialog_continue)) { dialog, id ->
                try {
                    val activityManager =
                        requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    // clearing app data
                    if (Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT) {
                        activityManager.clearApplicationUserData()
                    } else {
                        val packageName = requireActivity().applicationContext.packageName
                        val runtime = Runtime.getRuntime();
                        runtime.exec("pm clear $packageName");
                    }

                    // restart the app
                    val intent = Intent(context, InitialActivity::class.java)
                    intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
                    requireActivity().startActivity(intent)

                    Runtime.getRuntime().exit(0)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        val dialog = builder.create()
        dialog.show()

    }
}
