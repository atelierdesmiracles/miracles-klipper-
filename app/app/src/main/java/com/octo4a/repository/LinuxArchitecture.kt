package com.octo4a.repository

import android.content.Context
import android.os.Build
import com.octo4a.utils.preferences.MainPreferences
import java.io.IOException

/**
 * Resolves the Debian root filesystem architecture selected at startup.
 * A PRoot rootfs must match the native Android ABI because the app does not
 * ship a cross-architecture emulator.
 */
internal object LinuxArchitecture {
    const val ARM64 = "arm64"
    const val X86_64 = "x86_64"

    fun hostArchitecture(): String? = when {
        Build.SUPPORTED_ABIS.contains("arm64-v8a") -> ARM64
        Build.SUPPORTED_ABIS.contains("x86_64") -> X86_64
        else -> null
    }

    fun resolve(context: Context): String {
        val host = hostArchitecture()
            ?: throw IOException("This Android device does not support ARM64 or x86_64")
        val selected = MainPreferences(context).selectedLinuxArchitecture
            ?.takeIf { it.isNotBlank() }
            ?: host
        if (selected != ARM64 && selected != X86_64) {
            throw IOException("Unsupported Linux architecture selected: $selected")
        }
        if (selected != host) {
            throw IOException(
                "Selected Debian architecture $selected is incompatible with this device. " +
                    "This phone requires $host because cross-architecture emulation is not installed."
            )
        }
        return selected
    }
}
