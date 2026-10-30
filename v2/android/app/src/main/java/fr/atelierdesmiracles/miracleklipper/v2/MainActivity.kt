package fr.atelierdesmiracles.miracleklipper.v2

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(0xFFF3F5F8.toInt())
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("MIRACLE KLIPPER", 13, 0xFF536579.toInt(), true))
        content.addView(label("Version 2 — banc de test", 27, 0xFF142338.toInt(), true))
        content.addView(label(
            "Nouvelle base Android ARM64. Cette version valide l'application et le diagnostic de l'appareil. QEMU, Alpine et Docker ne sont pas encore intégrés.",
            15, 0xFF405168.toInt(), false
        ))
        content.addView(label("ÉTAT DU PROTOTYPE", 12, 0xFF536579.toInt(), true))
        report = label("", 15, 0xFF17283B.toInt(), false).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        content.addView(report)
        content.addView(Button(this).apply {
            text = "Relancer le diagnostic"
            setOnClickListener { updateReport() }
        })
        content.addView(label(
            "Prochain jalon : démarrage réel d'une VM Alpine ARM64 dans QEMU. Aucun service Klipper n'est lancé par cette version.",
            13, 0xFF64748B.toInt(), false
        ))
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateReport()
    }

    private fun updateReport() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024L * 1024L)
        val stat = StatFs(filesDir.absolutePath)
        val freeMb = stat.availableBytes / (1024L * 1024L)
        val usb = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        val readiness = DeviceReadiness.evaluate(DeviceProfile(
            Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT, totalMb, freeMb, usb
        ))
        report.text = buildString {
            appendLine(if (readiness.canRunV2Shell) "✓ Socle Android compatible" else "✕ Appareil non pris en charge")
            appendLine()
            appendLine("ABI : " + Build.SUPPORTED_ABIS.joinToString())
            appendLine("Android : API " + Build.VERSION.SDK_INT)
            appendLine("Mémoire totale : " + formatMb(totalMb))
            appendLine("Stockage libre privé : " + formatMb(freeMb))
            appendLine("USB Host : " + if (usb) "disponible" else "non détecté")
            appendLine()
            appendLine("RAM recommandée (4 Go) : " + if (readiness.memoryRecommended) "OK" else "insuffisante")
            appendLine("Stockage recommandé (4 Go) : " + if (readiness.storageRecommended) "OK" else "insuffisant")
            appendLine("Pré-requis VM : " + if (readiness.readyForVmPrototype) "de base OK" else "incomplets")
            appendLine()
            appendLine("QEMU : non intégré")
            appendLine("Alpine Linux : non intégré")
            appendLine("Docker : non intégré")
            appendLine("Klipper / Moonraker / Mainsail : non intégrés")
        }
    }

    private fun formatMb(value: Long) =
        String.format(Locale.getDefault(), "%.1f Mo", value.toDouble())

    private fun label(value: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
