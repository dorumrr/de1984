package io.github.dorumrr.de1984.ui.packages

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.ItemPackageBinding
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageCriticality
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils

class PackageAdapter(
    private var showIcons: Boolean,
    private val onPackageClick: (Package) -> Unit
) : ListAdapter<Package, PackageAdapter.PackageViewHolder>(PackageDiffCallback()) {

    companion object {
        private const val TAG = "PackageAdapter"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val binding = ItemPackageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PackageViewHolder(binding, onPackageClick)
    }

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, showIcons)
    }

    override fun submitList(list: List<Package>?) {
        super.submitList(list)
    }

    override fun submitList(list: List<Package>?, commitCallback: Runnable?) {
        super.submitList(list, commitCallback)
    }

    fun updateShowIcons(show: Boolean) {
        if (showIcons != show) {
            showIcons = show
            notifyDataSetChanged()
        }
    }

    class PackageViewHolder(
        private val binding: ItemPackageBinding,
        private val onPackageClick: (Package) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pkg: Package, showIcons: Boolean) {
            binding.appName.text = pkg.name
            binding.packageName.text = pkg.packageName

            // Set app icon
            if (showIcons) {
                val realIcon = PackageUtils.getPackageIcon(binding.root.context, pkg.packageName)
                if (realIcon != null) {
                    binding.appIcon.setImageDrawable(realIcon)
                } else {
                    binding.appIcon.setImageResource(R.drawable.de1984_icon)
                }
            } else {
                binding.appIcon.setImageResource(R.drawable.de1984_icon)
            }

            // Row 1 Right: Enabled/Disabled Badge (always shown)
            if (pkg.isEnabled) {
                binding.enabledBadge.text = Constants.Packages.STATE_ENABLED
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_complete)
            } else {
                binding.enabledBadge.text = Constants.Packages.STATE_DISABLED
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_background)
            }

            // Layout logic based on package type
            if (pkg.type == PackageType.SYSTEM) {
                // SYSTEM PACKAGE LAYOUT:
                // Row 1: [Icon] [Name]                    [Enabled]
                // Row 2:        [Package ID]              [Criticality]

                // Row 2 Right: Show criticality badge for system packages
                if (pkg.criticality != null && pkg.criticality != PackageCriticality.UNKNOWN) {
                    binding.safetyBadge.visibility = View.VISIBLE
                    when (pkg.criticality) {
                        PackageCriticality.ESSENTIAL -> {
                            binding.safetyBadge.text = "Essential"
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_essential)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_essential_text)
                            )
                        }
                        PackageCriticality.IMPORTANT -> {
                            binding.safetyBadge.text = "Important"
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_important)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_important_text)
                            )
                        }
                        PackageCriticality.OPTIONAL -> {
                            binding.safetyBadge.text = "Optional"
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_optional)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_optional_text)
                            )
                        }
                        PackageCriticality.BLOATWARE -> {
                            binding.safetyBadge.text = "Bloatware"
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_bloatware)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_bloatware_text)
                            )
                        }
                        else -> {
                            binding.safetyBadge.visibility = View.GONE
                        }
                    }
                } else {
                    binding.safetyBadge.visibility = View.GONE
                }

                // Hide package type badge for system packages
                binding.packageTypeBadge.visibility = View.GONE

            } else {
                // USER PACKAGE LAYOUT:
                // Row 1: [Icon] [Name]                    [Enabled]
                // Row 2:        [Package ID]              [User]

                // Row 2 Right: Show "User" badge for user packages
                binding.packageTypeBadge.visibility = View.VISIBLE
                binding.packageTypeBadge.text = "User"

                // Hide criticality badge for user packages
                binding.safetyBadge.visibility = View.GONE
            }

            // Set click listener
            binding.root.setOnClickListener {
                onPackageClick(pkg)
            }
        }
    }

    private class PackageDiffCallback : DiffUtil.ItemCallback<Package>() {
        override fun areItemsTheSame(oldItem: Package, newItem: Package): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: Package, newItem: Package): Boolean {
            return oldItem == newItem
        }
    }
}

