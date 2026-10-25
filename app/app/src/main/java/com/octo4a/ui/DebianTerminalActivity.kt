package com.octo4a.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.octo4a.R
import com.octo4a.repository.DebianTerminalRepository
import kotlinx.android.synthetic.main.activity_debian_terminal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class DebianTerminalActivity : AppCompatActivity() {
    private val terminalRepository: DebianTerminalRepository by inject()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debian_terminal)
        val startKiauh = intent.getBooleanExtra(EXTRA_START_KIAUH, false)
        if (startKiauh) {
            debianTerminalTitle.text = getString(R.string.kiauh_terminal_title)
            debianTerminalHint.text = getString(R.string.kiauh_terminal_hint)
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        closeDebianTerminal.setOnClickListener { finish() }
        keyboardDebianTerminal.setOnClickListener { focusTerminalInput() }
        keyboardDebianTerminal.isEnabled = false
        debianTerminalWebView.settings.javaScriptEnabled = true
        debianTerminalWebView.settings.domStorageEnabled = true
        debianTerminalWebView.settings.allowFileAccess = false
        debianTerminalWebView.settings.allowContentAccess = false
        debianTerminalWebView.webViewClient = TerminalWebViewClient()

        lifecycleScope.launch {
            try {
                val address = withContext(Dispatchers.IO) {
                    if (startKiauh) terminalRepository.startKiauh() else terminalRepository.start()
                }
                if (isFinishing || isDestroyed) return@launch
                terminalLoading.visibility = View.GONE
                terminalStatus.visibility = View.GONE
                debianTerminalWebView.visibility = View.VISIBLE
                debianTerminalWebView.loadUrl(address)
            } catch (error: Exception) {
                if (isFinishing || isDestroyed) return@launch
                terminalLoading.visibility = View.GONE
                terminalStatus.text = getString(
                    R.string.debian_terminal_error,
                    error.message ?: getString(R.string.debian_terminal_unknown_error)
                )
            }
        }
    }

    override fun onDestroy() {
        debianTerminalWebView.stopLoading()
        debianTerminalWebView.destroy()
        if (isFinishing) terminalRepository.stop()
        super.onDestroy()
    }

    private inner class TerminalWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            if (url?.startsWith(TERMINAL_ORIGIN) == true) {
                keyboardDebianTerminal.isEnabled = true
            }
            super.onPageFinished(view, url)
        }

        @Suppress("DEPRECATION")
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            if (failingUrl?.startsWith(TERMINAL_ORIGIN) == true && !isFinishing) {
                terminalLoading.visibility = View.GONE
                terminalStatus.visibility = View.VISIBLE
                terminalStatus.text = getString(
                    R.string.debian_terminal_error,
                    description ?: getString(R.string.debian_terminal_unknown_error)
                )
            }
            super.onReceivedError(view, errorCode, description, failingUrl)
        }
    }

    private fun focusTerminalInput() {
        debianTerminalWebView.requestFocusFromTouch()
        debianTerminalWebView.evaluateJavascript(
            "var terminalInput = document.querySelector('.xterm-helper-textarea'); " +
                "if (terminalInput) terminalInput.focus();",
        ) { _ ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val inputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(
                debianTerminalWebView,
                InputMethodManager.SHOW_IMPLICIT
            )
        }
    }

    companion object {
        const val EXTRA_START_KIAUH = "com.octo4a.ui.EXTRA_START_KIAUH"
        private const val TERMINAL_ORIGIN = "http://127.0.0.1:"
    }
}
