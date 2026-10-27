package com.octo4a.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.octo4a.R
import com.octo4a.repository.BootstrapRepository
import kotlinx.android.synthetic.main.fragment_about.*
import org.koin.android.ext.android.inject

class AboutFragment : Fragment() {
    private val bootstrapRepository: BootstrapRepository by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        val version = pInfo.versionName
        val bootstrapVersion = bootstrapRepository.bootstrapVersion

        appVersionText.text = getString(R.string.app_version_format, version, bootstrapVersion)

        // The Telegram group and donation link belonged to upstream Octo4a, not to this project.
        joinTelegramButton.visibility = View.GONE
        donateButton.visibility = View.GONE

        appProjectButton.setOnClickListener {
            openWebsite("https://github.com/atelierdesmiracles/miracles-klipper-")
        }
    }

    fun openWebsite(url: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = Uri.parse(url)
        startActivity(i)
    }
}
