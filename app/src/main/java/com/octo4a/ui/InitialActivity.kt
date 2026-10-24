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
 * Installs the pinned rootfs and exposes the PRoot-backed runtime and Mainsail entry point.
 */
class InitialActivity : AppCompatActivity() {
    private lateinit var setupStatus: TextView
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupButton: Button
    private lateinit var openReleaseButton: Button
    private lateinit var openMainsailButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var printerConfigButton: Button
    private var installJob: Future<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_landing)

        setupStatus = findViewById(R.id.setupStatus)
        setupProgress = findViewById(R.id.setupProgress)
        setupButton = findViewById(R.id.installButton)
        openReleaseButton = findViewById(R.id.openBootstrapRelease)
        openMainsailButton = findViewById(R.id.openMainsailButton)
        diagnosticsButton = findViewById(R.id.runtimeDiagnosticsButton)
        diagnosticsButton.setOnClickListener { showRuntimeDiagnostics() }
        printerConfigButton = findViewById(R.id.printerConfigButton)
        printerConfigButton.setOnClickListener { showPrinterConfigEditor() }
        findViewById<Button>(R.id.moonrakerConfigButton).setOnClickListener { showMoonrakerConfigEditor() }

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
            setupButton.text = getString(R.string.runtime_start_button)
            openMainsailButton.visibility = android.view.View.VISIBLE
            printerConfigButton.visibility = android.view.View.VISIBLE
            findViewById<Button>(R.id.moonrakerConfigButton).visibility = android.view.View.VISIBLE
        } else {
            setupStatus.text = getString(R.string.bootstrap_not_installed_status)
            setupButton.text = getString(R.string.landing_install)
        }

        openReleaseButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DebianRootfsInstaller.ROOTFS_RELEASE_URL)))
        }
        openMainsailButton.setOnClickListener {
            startActivity(Intent(this, WebinterfaceActivity::class.java))
        }
        setupButton.setOnClickListener {
            if (File(existingRootfs, "opt/klipper/klippy/klippy.py").isFile) {
                startMiracleKlipper()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.bootstrap_confirm_title)
                    .setMessage(R.string.bootstrap_confirm_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_ok) { _, _ -> installRootfs() }
                    .show()
            }
        }
    }

    /**
     * Lets the user provide a printer-specific configuration. Never generates a
     * pretend printer.cfg: MCU paths, pins and kinematics must match real hardware.
     */
    private fun showPrinterConfigEditor() {
        val rootfs = File(filesDir, "miracle-klipper/rootfs")
        val configDir = File(rootfs, "home/klipper/printer_data/config")
        if (!configDir.isDirectory() && !configDir.mkdirs()) {
            Toast.makeText(this, "Impossible de créer le dossier de configuration.", Toast.LENGTH_LONG).show()
            return
        }
        val configFile = File(configDir, "printer.cfg")
        val editor = android.widget.EditText(this).apply {
            minLines = 14
            maxLines = 24
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextIsSelectable(false)
            typeface = android.graphics.Typeface.MONOSPACE
            setText(
                if (configFile.isFile) configFile.readText()
                else "# Colle ici le printer.cfg correspondant exactement à ton imprimante.\n" +
                    "# Ne démarre pas une impression avant d'avoir vérifié la carte, les broches et les déplacements.\n"
            )
            setPadding(28, 20, 28, 20)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(editor) }
        AlertDialog.Builder(this)
            .setTitle(R.string.printer_config_title)
            .setMessage(R.string.printer_config_instructions)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.printer_config_validate, null)
            .setPositiveButton(R.string.printer_config_save, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val validation = validatePrinterConfig(editor.text.toString())
                        AlertDialog.Builder(this)
                            .setTitle(R.string.printer_config_validation_title)
                            .setMessage(validation)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val content = editor.text.toString()
                        val validation = validatePrinterConfig(content)
                        if (content.isBlank() || content.trimStart().startsWith("# Colle ici") ||
                            validation.contains("ERREUR")) {
                            Toast.makeText(this, validation, Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        try {
                            if (configFile.isFile) {
                                val backup = File(configDir, "printer.cfg.backup-${System.currentTimeMillis()}")
                                configFile.copyTo(backup, overwrite = false)
                            }
                            configFile.writeText(content.trimEnd() + "\n")
                            Toast.makeText(this, getString(R.string.printer_config_saved), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } catch (error: Exception) {
                            Toast.makeText(this, "Échec de sauvegarde : ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    /**
     * Edits Moonraker's server configuration with a backup and basic syntax checks.
     * This is not a substitute for validating the config against the installed Moonraker version.
     */
    private fun showMoonrakerConfigEditor() {
        val rootfs = File(filesDir, "miracle-klipper/rootfs")
        val configDir = File(rootfs, "home/klipper/printer_data/config")
        if (!configDir.isDirectory() && !configDir.mkdirs()) {
            Toast.makeText(this, "Impossible de créer le dossier de configuration.", Toast.LENGTH_LONG).show()
            return
        }
        val configFile = File(configDir, "moonraker.conf")
        if (!configFile.isFile) {
            Toast.makeText(this, "moonraker.conf est absent du rootfs. Réinstalle ou vérifie le bootstrap Debian.", Toast.LENGTH_LONG).show()
            return
        }
        val editor = android.widget.EditText(this).apply {
            minLines = 14
            maxLines = 24
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            typeface = android.graphics.Typeface.MONOSPACE
            setText(configFile.readText())
            setPadding(28, 20, 28, 20)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(editor) }
        AlertDialog.Builder(this)
            .setTitle(R.string.moonraker_config_title)
            .setMessage(R.string.moonraker_config_instructions)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.printer_config_validate, null)
            .setPositiveButton(R.string.printer_config_save, null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val content = editor.text.toString()
                        val hasServer = content.lineSequence().any { it.trim().equals("[server]", ignoreCase = true) }
                        val message = if (content.isBlank()) {
                            "ERREUR : le fichier est vide."
                        } else if (!hasServer) {
                            "ERREUR : la section [server] est absente."
                        } else {
                            "Contrôle structurel de base réussi : section [server] détectée.\n\n" +
                                "Les permissions, les sockets et les options doivent encore être validés par Moonraker."
                        }
                        AlertDialog.Builder(this)
                            .setTitle(R.string.moonraker_config_validation_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val content = editor.text.toString()
                        val hasServer = content.lineSequence().any { it.trim().equals("[server]", ignoreCase = true) }
                        if (content.isBlank() || !hasServer) {
                            Toast.makeText(this, "ERREUR : le fichier doit être non vide et contenir [server].", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        try {
                            val backup = File(configDir, "moonraker.conf.backup-${System.currentTimeMillis()}")
                            configFile.copyTo(backup, overwrite = false)
                            configFile.writeText(content.trimEnd() + "\n")
                            Toast.makeText(this, getString(R.string.moonraker_config_saved), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } catch (error: Exception) {
                            Toast.makeText(this, "Échec de sauvegarde : ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun validatePrinterConfig(content: String): String {
        val sections = content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[") && it.endsWith("]") }
            .map { it.substring(1, it.length - 1).trim().substringBefore(" ").toLowerCase(java.util.Locale.ROOT) }
            .toSet()
        val errors = mutableListOf<String>()
        if (content.isBlank()) errors += "ERREUR : le fichier est vide."
        if (sections.none { it == "printer" }) errors += "ERREUR : section [printer] absente."
        if (sections.none { it == "mcu" || it.startsWith("mcu ") }) errors += "ERREUR : section [mcu] absente."
        if (sections.none { it.startsWith("stepper_") }) errors += "ERREUR : aucune section [stepper_x/y/z] détectée."
        if (errors.isNotEmpty()) return errors.joinToString("\n")
        return "Contrôle structurel de base réussi : [printer], [mcu] et au moins un moteur détectés.\n\n" +
            "Ce contrôle ne valide pas les broches, le modèle de carte, les limites de déplacement ni la compatibilité réelle. " +
            "Vérifie le fichier pour ton imprimante avant toute impression."
    }

    private fun showRuntimeDiagnostics() {
        val base = File(filesDir, "miracle-klipper")
        val rootfs = File(base, "rootfs")
        val configDir = File(rootfs, "home/klipper/printer_data/config")
        val logDir = File(rootfs, "home/klipper/printer_data/logs")
        val sections = listOf(
            "État des fichiers" to listOf(
                "Rootfs Debian : " + if (rootfs.isDirectory) "présent" else "absent",
                "Klipper : " + if (File(rootfs, "opt/klipper/klippy/klippy.py").isFile) "présent" else "absent",
                "Moonraker : " + if (File(rootfs, "opt/moonraker/moonraker/moonraker.py").isFile) "présent" else "absent",
                "printer.cfg : " + if (File(configDir, "printer.cfg").isFile) "présent" else "MANQUANT",
                "moonraker.conf : " + if (File(configDir, "moonraker.conf").isFile) "présent" else "MANQUANT"
            ).joinToString("\n"),
            "Journal du superviseur Android" to readLastLines(File(base, "logs/proot-runtime.log")),
            "Journal Klipper" to readLastLines(File(logDir, "klippy.log")),
            "Journal Moonraker" to readLastLines(File(logDir, "moonraker.log")),
            "Journal du superviseur Linux" to readLastLines(File(logDir, "klipper-supervisor.log"))
        )
        val report = sections.joinToString("\n\n") { (title, body) ->
            "$title\n${body.ifBlank { "Aucune donnée disponible." }}"
        }
        val textView = TextView(this).apply {
            text = report
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 8)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle(R.string.runtime_diagnostics_title)
            .setView(scroll)
            .setPositiveButton(R.string.runtime_diagnostics_refresh) { _, _ -> showRuntimeDiagnostics() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun readLastLines(file: File, maxLines: Int = 80): String {
        if (!file.isFile) return "Journal non créé."
        return try {
            file.useLines { lines -> lines.toList().takeLast(maxLines).joinToString("\n") }
                .ifBlank { "Journal vide." }
        } catch (error: Exception) {
            "Lecture impossible : ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun startMiracleKlipper() {
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        val runtimeFiles = listOf(
            "libproroot.so",
            "libproroot-runtime.so",
            "libproroot-bridge.so",
            "libproroot-linker.so",
            "libproroot-stub-loader.so"
        )
        if (!runtimeFiles.all { File(nativeDir, it).isFile }) {
            setupStatus.text = getString(R.string.runtime_missing_assets)
            Toast.makeText(this, getString(R.string.runtime_missing_assets), Toast.LENGTH_LONG).show()
            return
        }

        val rootfs = File(filesDir, "miracle-klipper/rootfs")
        val config = File(rootfs, "home/klipper/printer_data/config")
        if (!File(config, "printer.cfg").isFile || !File(config, "moonraker.conf").isFile) {
            setupStatus.text = "Configuration Klipper/Moonraker absente dans le rootfs. Vérifie le bootstrap Debian."
            return
        }

        setupButton.isEnabled = false
        setupStatus.text = getString(R.string.status_starting)
        val serviceIntent = Intent(this, com.octo4a.service.MiracleKlipperService::class.java)
            .setAction(com.octo4a.service.MiracleKlipperService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            setupStatus.text = getString(R.string.status_starting_subtitle)
            Toast.makeText(this, getString(R.string.runtime_notification_starting), Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            setupStatus.text = getString(R.string.runtime_start_failed, error.message ?: "erreur inconnue")
        } finally {
            setupButton.isEnabled = true
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
                    setupButton.text = getString(R.string.runtime_start_button)
                    openMainsailButton.visibility = android.view.View.VISIBLE
                    printerConfigButton.visibility = android.view.View.VISIBLE
                    findViewById<Button>(R.id.moonrakerConfigButton).visibility = android.view.View.VISIBLE
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
