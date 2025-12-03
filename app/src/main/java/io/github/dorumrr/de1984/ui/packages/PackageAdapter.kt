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
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils

class PackageAdapter(
    private var showIcons: Boolean,
    private val onPackageClick: (Package) -> Unit,
    private val onPackageLongClick: (Package) -> Boolean = { false }
) : ListAdapter<Package, PackageAdapter.PackageViewHolder>(PackageDiffCallback()) {

    companion object {
        private const val TAG = "PackageAdapter"
    }

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
    private var onSelectionChanged: ((Set<PackageId>) -> Unit)? = null
    private var onSelectionLimitReached: (() -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val binding = ItemPackageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PackageViewHolder(
            binding,
            onPackageClick,
            onPackageLongClick,
            ::isPackageSelected,
            ::canSelectPackage,
            ::togglePackageSelection
        )
    }

    fun setOnSelectionLimitReachedListener(listener: () -> Unit) {
        onSelectionLimitReached = listener
    }

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, showIcons, isSelectionMode)
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

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedPackages.clear()
            }
            notifyDataSetChanged()
        }
    }

    fun setOnSelectionChangedListener(listener: (Set<PackageId>) -> Unit) {
        onSelectionChanged = listener
    }

    fun getSelectedPackages(): Set<PackageId> = selectedPackages.toSet()

    fun clearSelection() {
        selectedPackages.clear()
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    /**
     * Programmatically select a package (used when entering selection mode via long press)
     */
    fun selectPackage(packageId: PackageId) {
        if (!selectedPackages.contains(packageId) &&
            selectedPackages.size < Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
            selectedPackages.add(packageId)
            onSelectionChanged?.invoke(selectedPackages)
            notifyDataSetChanged()
        }
    }

    /**
     * Check if a package can be selected (public for fragment access)
     */
    fun canSelectPackage(pkg: Package): Boolean {
        // User apps: always selectable
        if (pkg.type == PackageType.USER) return true

        // System apps: only Bloatware and Optional
        return when (pkg.criticality) {
            PackageCriticality.BLOATWARE, PackageCriticality.OPTIONAL -> true
            else -> false
        }
    }

    private fun isPackageSelected(packageId: PackageId): Boolean {
        return selectedPackages.contains(packageId)
    }

    private fun togglePackageSelection(pkg: Package) {
        if (!isSelectionMode) return

        val packageId = pkg.id
        if (selectedPackages.contains(packageId)) {
            selectedPackages.remove(packageId)
        } else {
            if (selectedPackages.size >= Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
                // Notify listener when limit is reached
                onSelectionLimitReached?.invoke()
                return
            }
            selectedPackages.add(packageId)
        }
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    class PackageViewHolder(
        private val binding: ItemPackageBinding,
        private val onPackageClick: (Package) -> Unit,
        private val onPackageLongClick: (Package) -> Boolean,
        private val isPackageSelected: (PackageId) -> Boolean,
        private val canSelectPackage: (Package) -> Boolean,
        private val togglePackageSelection: (Package) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pkg: Package, showIcons: Boolean, isSelectionMode: Boolean) {
            binding.appName.text = pkg.name
            binding.packageName.text = pkg.packageName

            // Handle selection mode
            if (isSelectionMode) {
                binding.selectionCheckbox.visibility = View.VISIBLE
                val isSelected = isPackageSelected(pkg.id)
                val canSelect = canSelectPackage(pkg)

                binding.selectionCheckbox.isChecked = isSelected
                binding.selectionCheckbox.isEnabled = canSelect

                // Dim the entire card if not selectable
                binding.root.alpha = if (canSelect) 1.0f else 0.5f
            } else {
                binding.selectionCheckbox.visibility = View.GONE
                binding.root.alpha = 1.0f
            }

            // Set app icon (pass userId for multi-user support)
            if (showIcons) {
                val realIcon = PackageUtils.getPackageIcon(binding.root.context, pkg.packageName, pkg.userId)
                if (realIcon != null) {
                    binding.appIcon.setImageDrawable(realIcon)
                } else {
                    binding.appIcon.setImageResource(R.drawable.de1984_icon)
                }
            } else {
                binding.appIcon.setImageResource(R.drawable.de1984_icon)
            }

            // Row 1 Right: Enabled/Disabled/Uninstalled Badge (always shown)
            // Check if package is uninstalled (versionName is null for uninstalled packages)
            if (pkg.versionName == null && !pkg.isEnabled && pkg.type == PackageType.SYSTEM) {
                binding.enabledBadge.text = Constants.Packages.STATE_UNINSTALLED
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_background)
            } else if (pkg.isEnabled) {
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
                            binding.safetyBadge.text = binding.root.context.getString(R.string.action_sheet_safety_badge_essential)
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_essential)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_essential_text)
                            )
                        }
                        PackageCriticality.IMPORTANT -> {
                            binding.safetyBadge.text = binding.root.context.getString(R.string.action_sheet_safety_badge_important)
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_important)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_important_text)
                            )
                        }
                        PackageCriticality.OPTIONAL -> {
                            binding.safetyBadge.text = binding.root.context.getString(R.string.action_sheet_safety_badge_optional)
                            binding.safetyBadge.setBackgroundResource(R.drawable.safety_badge_optional)
                            binding.safetyBadge.setTextColor(
                                ContextCompat.getColor(binding.root.context, R.color.badge_optional_text)
                            )
                        }
                        PackageCriticality.BLOATWARE -> {
                            binding.safetyBadge.text = binding.root.context.getString(R.string.action_sheet_safety_badge_bloatware)
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
                binding.packageTypeBadge.text = binding.root.context.getString(R.string.action_sheet_type_badge_user)

                // Hide criticality badge for user packages
                binding.safetyBadge.visibility = View.GONE
            }

            // Show/hide profile badge for non-personal profiles (Work/Clone)
            when {
                pkg.userId >= 10 && pkg.userId < 100 -> {
                    // Work profile (typically userId 10-99)
                    binding.profileBadge.text = binding.root.context.getString(R.string.badge_work_profile)
                    binding.profileBadge.visibility = View.VISIBLE
                }
                pkg.userId >= 100 -> {
                    // Clone profile (typically userId 100+)
                    binding.profileBadge.text = binding.root.context.getString(R.string.badge_clone_profile)
                    binding.profileBadge.visibility = View.VISIBLE
                }
                else -> {
                    // Personal profile (userId 0)
                    binding.profileBadge.visibility = View.GONE
                }
            }

            // Set click listeners
            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    if (canSelectPackage(pkg)) {
                        togglePackageSelection(pkg)
                    } else {
                        // Show toast for non-selectable packages
                        android.widget.Toast.makeText(
                            binding.root.context,
                            Constants.Packages.MultiSelect.TOAST_CANNOT_SELECT_CRITICAL,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    onPackageClick(pkg)
                }
            }

            binding.root.setOnLongClickListener {
                onPackageLongClick(pkg)
            }
        }
    }

    private class PackageDiffCallback : DiffUtil.ItemCallback<Package>() {
        override fun areItemsTheSame(oldItem: Package, newItem: Package): Boolean {
            // Compare by both packageName and userId for multi-user support
            return oldItem.packageName == newItem.packageName && oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: Package, newItem: Package): Boolean {
            return oldItem == newItem
        }
    }
}

