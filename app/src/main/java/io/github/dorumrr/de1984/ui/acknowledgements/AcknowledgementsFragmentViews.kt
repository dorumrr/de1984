package io.github.dorumrr.de1984.ui.acknowledgements

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.dorumrr.de1984.databinding.FragmentAcknowledgementsBinding
import io.github.dorumrr.de1984.ui.base.BaseFragment

/**
 * Acknowledgements screen showing libraries used in the app
 */
class AcknowledgementsFragmentViews : BaseFragment<FragmentAcknowledgementsBinding>() {

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentAcknowledgementsBinding {
        return FragmentAcknowledgementsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back button
        binding.backButton.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Contribute link
        binding.contributeLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
            startActivity(intent)
        }
    }
}

