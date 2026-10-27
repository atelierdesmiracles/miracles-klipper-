package com.octo4a

import androidx.multidex.MultiDexApplication
import com.bugsnag.android.Bugsnag
import com.octo4a.utils.TLSSocketFactory
import com.octo4a.utils.preferences.MainPreferences
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import javax.net.ssl.HttpsURLConnection

class Octo4aApplication : MultiDexApplication() {
    val preferences by lazy { MainPreferences(this) }
    var bugsnagStarted = false

    override fun onCreate() {
        super.onCreate()
        initializeSSLContext()

        // Start Koin
        startKoin {
            androidLogger()
            androidContext(this@Octo4aApplication)
            modules(appModule)
        }

        if (preferences.enableBugReporting) {
            startBugsnag()
        }
    }

    fun startBugsnag() {
        if (bugsnagStarted) return
        if (!hasBugsnagKey()) return

        Bugsnag.start(this)
        bugsnagStarted = true
    }

    private fun hasBugsnagKey(): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
        val key = info.metaData?.getString("com.bugsnag.android.API_KEY")
        !key.isNullOrBlank() && key.any { it != '0' }
    } catch (_: Exception) {
        false
    }

    fun initializeSSLContext() {
        val noSSLv3Factory: TLSSocketFactory = TLSSocketFactory(applicationContext)

        HttpsURLConnection.setDefaultSSLSocketFactory(noSSLv3Factory)
    }
}