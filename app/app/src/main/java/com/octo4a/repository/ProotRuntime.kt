package com.octo4a.repository

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Single source of truth for the Android-native PRoot runtime.
 *
 * PRoot and its loader ship as jniLibs (libproot.so / libproot-loader.so) and are extracted
 * into nativeLibraryDir, the only app-private location that is executable on Android 10+.
 * Every component that launches PRoot must go through this object so they all agree.
 */
object ProotRuntime {
    fun nativeDirectory(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun proot(context: Context): File = File(nativeDirectory(context), "libproot.so")

    fun loader(context: Context): File = File(nativeDirectory(context), "libproot-loader.so")

    /** Optional /proc/stat replacement; lives next to the downloaded bootstrap files. */
    fun fakeProcStat(context: Context): File =
        File(context.filesDir, "miracle-klipper/bootstrap/fake_proc_stat")

    fun isAvailable(context: Context): Boolean =
        proot(context).canExecute() && loader(context).canExecute()

    fun requireAvailable(context: Context) {
        if (!isAvailable(context)) {
            throw IOException(
                "The bundled Android PRoot libraries are missing or not executable in " +
                    "${nativeDirectory(context).absolutePath}. Rebuild the APK with libproot.so " +
                    "and libproot-loader.so for this ABI (see scripts/fetch-proot.sh)."
            )
        }
    }

    /** Environment PRoot needs to find its Android/Bionic loader. */
    fun applyLoader(builder: ProcessBuilder, context: Context) {
        builder.environment()["PROOT_LOADER"] = loader(context).absolutePath
    }
}
