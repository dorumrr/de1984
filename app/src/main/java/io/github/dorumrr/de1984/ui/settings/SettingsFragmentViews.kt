package io.github.dorumrr.de1984.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.databinding.FragmentSettingsBinding
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.launch

/**
 * Settings Fragment using XML Views
 * 
 * Features:
 * - App info with version and donate button
 * - Options: Firewall policy, Show icons, New app notifications
 * - Permissions setup (placeholder for now)
 * - Acknowledgements navigation
 */
class SettingsFragmentViews : BaseFragment<FragmentSettingsBinding>() {

    private val viewModel: SettingsViewModel by viewModels {
        val app = requireActivity().application as De1984Application
        SettingsViewModel.Factory(
            requireContext(),
            app.dependencies.permissionManager,
            app.dependencies.rootManager
        )
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentSettingsBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: SettingsFragmentViews initialized")

        setupViews()
        observeUiState()
    }

    private fun setupViews() {
        // Donate button
        binding.donateButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/ossdev"))
            startActivity(intent)
        }

        // Firewall policy switch
        binding.firewallPolicySwitch.setOnCheckedChangeListener { _, isChecked ->
            val policy = if (isChecked) {
                Constants.Settings.POLICY_BLOCK_ALL
            } else {
                Constants.Settings.POLICY_ALLOW_ALL
            }
            viewModel.setDefaultFirewallPolicy(policy)
            updateFirewallPolicyDescription(isChecked)
        }

        // Show app icons switch
        binding.showAppIconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowAppIcons(isChecked)
        }

        // New app notifications switch
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }

        // Acknowledgements card
        binding.acknowledgementsCard.setOnClickListener {
            // TODO: Navigate to acknowledgements
            Log.d(TAG, "Acknowledgements clicked - navigation not yet implemented")
        }

        // Footer (author link)
        binding.footerText.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dorumrr/de1984"))
            startActivity(intent)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.SettingsUiState) {
        Log.d(TAG, "updateUI: version=${state.appVersion}, " +
                "showIcons=${state.showAppIcons}, " +
                "notifications=${state.newAppNotifications}, " +
                "policy=${state.defaultFirewallPolicy}")

        // Update app version
        binding.appVersion.text = "Version ${state.appVersion}"

        // Update switches (without triggering listeners)
        binding.firewallPolicySwitch.setOnCheckedChangeListener(null)
        binding.firewallPolicySwitch.isChecked = 
            state.defaultFirewallPolicy == Constants.Settings.POLICY_BLOCK_ALL
        binding.firewallPolicySwitch.setOnCheckedChangeListener { _, isChecked ->
            val policy = if (isChecked) {
                Constants.Settings.POLICY_BLOCK_ALL
            } else {
                Constants.Settings.POLICY_ALLOW_ALL
            }
            viewModel.setDefaultFirewallPolicy(policy)
            updateFirewallPolicyDescription(isChecked)
        }
        updateFirewallPolicyDescription(binding.firewallPolicySwitch.isChecked)

        binding.showAppIconsSwitch.setOnCheckedChangeListener(null)
        binding.showAppIconsSwitch.isChecked = state.showAppIcons
        binding.showAppIconsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowAppIcons(isChecked)
        }

        binding.newAppNotificationsSwitch.setOnCheckedChangeListener(null)
        binding.newAppNotificationsSwitch.isChecked = state.newAppNotifications
        binding.newAppNotificationsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewAppNotifications(isChecked)
        }
    }

    private fun updateFirewallPolicyDescription(isBlockAll: Boolean) {
        binding.firewallPolicyDescription.text = if (isBlockAll) {
            "Block All, Allow Wanted"
        } else {
            "Allow All, Block Unwanted"
        }
    }

    companion object {
        private const val TAG = "SettingsFragmentViews"
    }
}

