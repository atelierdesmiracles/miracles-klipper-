package com.octo4a.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.octo4a.R
import kotlinx.android.synthetic.main.activity_webinterface.*

const val WEBINTERFACE_ADDRESS = "127.0.0.1"

class WebinterfaceActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webinterface)
        webview.webViewClient = WebinterfaceClient()
        webview.settings.loadsImagesAutomatically = true
        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.settings.userAgentString = "TouchUI"
        webview.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        webinterfaceRetryButton.setOnClickListener { loadMainsail() }
        loadMainsail()
    }

    private fun loadMainsail() {
        webinterfaceErrorPanel.visibility = View.GONE
        webview.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE
        webview.loadUrl("http://$WEBINTERFACE_ADDRESS")
    }

    private fun showUnavailableError() {
        loadingIndicator.visibility = View.GONE
        webview.visibility = View.GONE
        webinterfaceErrorPanel.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        webview.stopLoading()
        webview.webViewClient = WebViewClient()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webview.canGoBack()) webview.goBack() else super.onBackPressed()
    }

    private inner class WebinterfaceClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            if (url?.startsWith("http://$WEBINTERFACE_ADDRESS") == true) {
                loadingIndicator.visibility = View.GONE
                webinterfaceErrorPanel.visibility = View.GONE
                webview.visibility = View.VISIBLE
            }
            super.onPageFinished(view, url)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) showUnavailableError()
            super.onReceivedError(view, request, error)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri: Uri = request?.url ?: return false
            return if (uri.host == WEBINTERFACE_ADDRESS || uri.host == "localhost") {
                false
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            }
        }
    }
}
