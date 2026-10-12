package io.github.dorumrr.de1984.ui.packages

import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackageAdapter(
    private var showIcons: Boolean,
    private val onPackageClick: (Package) -> Unit,
    private val onPackageLongClick: (Package) -> Boolean = { false }
) : ListAdapter<Package, PackageAdapter.PackageViewHolder>(PackageDiffCallback()) {

    companion object {
        private const val TAG = "PackageAdapter"
        private const val ICON_CACHE_SIZE = 100
    }

    init {
        // Same reasoning as NetworkPackageAdapter - see the comment there for the full mechanism.
        //
        // This screen has never been reported for it because MainActivity hides the non-current tabs
        // on restore and a hidden fragment's view is GONE, so it is not laid out while empty. That
        // shield only holds while Packages is NOT the restored tab. Leave the last session on
        // Packages, let the process die, and this list drops to the top exactly like Firewall did.
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
    private var onSelectionChanged: ((Set<PackageId>) -> Unit)? = null
    private var onSelectionLimitReached: (() -> Unit)? = null

    private val iconCache = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    fun clearIconCache() {
        iconCache.evictAll()
    }

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
            ::togglePackageSelection,
            iconCache
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


    /**
     * Put a saved selection back in one go.
     *
     * selectPackage() would work but fires notifyDataSetChanged() per item - 40 full refreshes for a
     * restore. Capped the same way selectPackage caps, so a tampered or stale bundle cannot exceed
     * the multi-select limit.
     */
    fun restoreSelection(ids: Set<PackageId>) {
        selectedPackages.clear()
        selectedPackages.addAll(ids.take(Constants.Packages.MultiSelect.MAX_SELECTION_COUNT))
        onSelectionChanged?.invoke(selectedPackages)
        // No notify: this is called at the end of onViewCreated, before the first submitList, so
        // there is nothing bound yet. Every row reads selectedPackages when it binds, so the list
        // arrives already showing the restored selection.
    }

    fun clearSelection() {
        selectedPackages.clear()
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    fun selectPackage(packageId: PackageId) {
        if (!selectedPackages.contains(packageId) &&
            selectedPackages.size < Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
            selectedPackages.add(packageId)
            onSelectionChanged?.invoke(selectedPackages)
            notifyDataSetChanged()
        }
    }

    fun canSelectPackage(pkg: Package): Boolean {
        if (pkg.type == PackageType.USER) return true

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
        private val togglePackageSelection: (Package) -> Unit,
        private val iconCache: LruCache<String, Drawable>
    ) : RecyclerView.ViewHolder(binding.root) {

        private val scope = CoroutineScope(Dispatchers.Main)

        // Track current package for race condition prevention
        private var currentPackage: Package? = null

        fun bind(pkg: Package, showIcons: Boolean, isSelectionMode: Boolean) {
            currentPackage = pkg
            binding.appName.text = pkg.name
            binding.packageName.text = pkg.packageName

            if (isSelectionMode) {
                binding.selectionCheckbox.visibility = View.VISIBLE
                val isSelected = isPackageSelected(pkg.id)
                val canSelect = canSelectPackage(pkg)

                binding.selectionCheckbox.isChecked = isSelected
                binding.selectionCheckbox.isEnabled = canSelect

                binding.root.alpha = if (canSelect) 1.0f else 0.5f
            } else {
                binding.selectionCheckbox.visibility = View.GONE
                binding.root.alpha = 1.0f
            }

            if (showIcons) {
                val iconCacheKey = "${pkg.packageName}_${pkg.userId}"

                val cachedIcon = iconCache.get(iconCacheKey)
                if (cachedIcon != null) {
                    binding.appIcon.setImageDrawable(cachedIcon)
                } else {
                    binding.appIcon.setImageResource(R.drawable.de1984_icon)

                    val context = binding.root.context
                    scope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            PackageUtils.getPackageIcon(context, pkg.packageName, pkg.userId)
                        }

                        if (currentPackage?.packageName == pkg.packageName && currentPackage?.userId == pkg.userId) {
                            if (icon != null) {
                                iconCache.put(iconCacheKey, icon)
                                binding.appIcon.setImageDrawable(icon)
                            }
                        }
                    }
                }
            } else {
                binding.appIcon.setImageResource(R.drawable.de1984_icon)
            }

            // Row 1 Right: Enabled/Disabled/Uninstalled Badge (always shown)
            // Check if package is uninstalled (versionName is null for uninstalled packages)
            // Dedicated SINGULAR status strings, not the filter-chip labels. The chips are plural
            // ("Activate", "Ativados"), and reusing them on a single row mixed plural and singular
            // in five of the seven locales, beside the already-singular "Uninstalled".
            //
            // Resources, not Constants.Packages.STATE_*. Those constants are internal filter keys -
            // they are lowercased and compared in a dozen places - and putting them on screen made
            // the list mix languages: translated filter chips beside hardcoded English badges.
            // The keys stay English; only what the user reads is translated.
            if (pkg.versionName == null && !pkg.isEnabled && pkg.type == PackageType.SYSTEM) {
                binding.enabledBadge.setText(R.string.status_uninstalled)
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_background)
            } else if (pkg.isEnabled) {
                binding.enabledBadge.setText(R.string.packages_status_enabled)
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_complete)
            } else {
                binding.enabledBadge.setText(R.string.packages_status_disabled)
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_background)
            }

            if (pkg.type == PackageType.SYSTEM) {

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

                binding.packageTypeBadge.visibility = View.GONE

            } else {

                binding.packageTypeBadge.visibility = View.VISIBLE
                binding.packageTypeBadge.text = binding.root.context.getString(R.string.action_sheet_type_badge_user)

                binding.safetyBadge.visibility = View.GONE
            }

            when {
                pkg.isFullUser -> {
                    binding.profileBadge.text = binding.root.context.getString(R.string.badge_other_user)
                    binding.profileBadge.visibility = View.VISIBLE
                }
                pkg.userId >= 10 && pkg.userId < 100 -> {
                    binding.profileBadge.text = binding.root.context.getString(R.string.badge_work_profile)
                    binding.profileBadge.visibility = View.VISIBLE
                }
                pkg.userId >= 100 -> {
                    binding.profileBadge.text = binding.root.context.getString(R.string.badge_clone_profile)
                    binding.profileBadge.visibility = View.VISIBLE
                }
                else -> {
                    binding.profileBadge.visibility = View.GONE
                }
            }

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    if (canSelectPackage(pkg)) {
                        togglePackageSelection(pkg)
                    } else {
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
            return oldItem.packageName == newItem.packageName && oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: Package, newItem: Package): Boolean {
            return oldItem == newItem
        }
    }
}

