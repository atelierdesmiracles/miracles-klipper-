package com.octo4a.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.octo4a.R
import kotlinx.android.synthetic.main.activity_webinterface.*

const val WEBINTERFACE_ADDRESS = "127.0.0.1:5000"

class WebinterfaceActivity : AppCompatActivity() {
    private lateinit var startUrl: String
    private var initialLoadRetries = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webinterface)
        startUrl = intent.getStringExtra(EXTRA_WEB_URL) ?: "http://$WEBINTERFACE_ADDRESS"
        val startUri = Uri.parse(startUrl)
        webview.webViewClient = WebinterfaceClient(this, startUri.scheme, startUri.authority)
        webview.settings.loadsImagesAutomatically = true
        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.settings.userAgentString = "TouchUI"
        webview.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webview.loadUrl(startUrl)
    }

    override fun onResume() {
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !isPinned()) {
            startLockTask()
        }
        super.onResume()
    }

    @Suppress("DEPRECATION")
    fun isPinned(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (activityManager.lockTaskModeState
                    != ActivityManager.LOCK_TASK_MODE_NONE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            activityManager.isInLockTaskMode
        } else {
            false
        }
    }

    override fun onBackPressed() {
        if (!isPinned()){
            super.onBackPressed()
        } else if (webview.canGoBack()) {
            webview.goBack()
        }
    }

    private fun webViewLoadingFinished() {
        webview.visibility = View.VISIBLE
        loadingIndicator.visibility = View.GONE
    }

    private fun retryInterfaceLoad() {
        if (initialLoadRetries >= MAX_INITIAL_LOAD_RETRIES) {
            webViewLoadingFinished()
            Toast.makeText(this, "Web interface is not responding. Check that Nginx is running.", Toast.LENGTH_LONG)
                .show()
            return
        }
        initialLoadRetries++
        webview.postDelayed({
            if (!isFinishing) webview.reload()
        }, RETRY_DELAY_MS)
    }

    private class WebinterfaceClient(
        val activity: WebinterfaceActivity,
        private val internalScheme: String?,
        private val internalAuthority: String?
    ) : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            activity.webViewLoadingFinished()
            super.onPageFinished(view, url)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            if (failingUrl == activity.startUrl) {
                activity.retryInterfaceLoad()
            }
            super.onReceivedError(view, errorCode, description, failingUrl)
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val requestUri = request?.url
            return if (requestUri != null && requestUri.scheme == internalScheme &&
                requestUri.authority == internalAuthority
            ) {
                false
            } else {
                activity.stopLockTask()
                val intent = Intent(Intent.ACTION_VIEW, request?.url)
                activity.startActivity(intent)
                true
            }
        }
    }

    companion object {
        const val EXTRA_WEB_URL = "com.octo4a.extra.WEB_INTERFACE_URL"
        private const val MAX_INITIAL_LOAD_RETRIES = 20
        private const val RETRY_DELAY_MS = 750L
    }
}
