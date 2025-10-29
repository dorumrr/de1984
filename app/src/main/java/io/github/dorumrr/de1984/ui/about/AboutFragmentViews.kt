package io.github.dorumrr.de1984.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.FragmentAboutBinding
import io.github.dorumrr.de1984.ui.base.BaseFragment

/**
 * About Fragment - Shows app information, dependencies, and contribution links
 * Following RULES.md: Uses constants, proper naming, and Material Design 3
 */
class AboutFragmentViews : BaseFragment<FragmentAboutBinding>() {

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentAboutBinding {
        return FragmentAboutBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Donate button (included layout - access via root view)
        binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.donate_button)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/ossdev"))
            startActivity(intent)
        }

        // Contribute link
        binding.contributeLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
            startActivity(intent)
        }

        // Footer (author link) - make only "Doru Moraru" clickable
        setupFooterLink()
    }

    private fun setupFooterLink() {
        val fullText = getString(R.string.footer_tagline)
        val clickableText = getString(R.string.footer_author_name)
        val startIndex = fullText.indexOf(clickableText)
        val endIndex = startIndex + clickableText.length

        val spannableString = android.text.SpannableString(fullText)

        val clickableSpan = object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.footer_author_url)))
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

    override fun scrollToTop() {
        // Only scroll if binding is available (fragment view is created)
        _binding?.scrollView?.scrollTo(0, 0)
    }
}
