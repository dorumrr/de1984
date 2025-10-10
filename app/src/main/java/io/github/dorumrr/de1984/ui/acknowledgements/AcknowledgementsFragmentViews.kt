package io.github.dorumrr.de1984.ui.acknowledgements

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import io.github.dorumrr.de1984.R
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

    override fun scrollToTop() {
        binding.root.scrollTo(0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Contribute link
        binding.contributeLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
            startActivity(intent)
        }

        // Footer (author link) - make only "Doru Moraru" clickable
        setupFooterLink()
    }

    private fun setupFooterLink() {
        val fullText = "Giving Privacy its due, by Doru Moraru"
        val clickableText = "Doru Moraru"
        val startIndex = fullText.indexOf(clickableText)
        val endIndex = startIndex + clickableText.length

        val spannableString = android.text.SpannableString(fullText)

        val clickableSpan = object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
                startActivity(intent)
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = ContextCompat.getColor(requireContext(), R.color.lineage_teal)
                ds.isUnderlineText = false  // No underline
            }
        }

        spannableString.setSpan(
            clickableSpan,
            startIndex,
            endIndex,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.footerText.text = spannableString
        binding.footerText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }
}

