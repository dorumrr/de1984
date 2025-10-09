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
import io.github.dorumrr.de1984.domain.model.PackageType
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
        if (position == 0) {
            Log.d(TAG, ">>> onBindViewHolder: position=0, package=${item.name}, packageType=${item.type}, currentList.size=${currentList.size}")
            // Log first 5 packages to see what's being rendered
            val preview = currentList.take(5).map { "${it.name}(${it.type})" }.joinToString(", ")
            Log.d(TAG, ">>> First 5 packages: $preview")
        }
        holder.bind(item, showIcons)
    }

    override fun submitList(list: List<Package>?) {
        Log.d(TAG, ">>> submitList called: newList.size=${list?.size}, currentList.size=${currentList.size}")
        super.submitList(list)
    }

    override fun submitList(list: List<Package>?, commitCallback: Runnable?) {
        Log.d(TAG, ">>> submitList (with callback) called: newList.size=${list?.size}, currentList.size=${currentList.size}")
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

            // Set enabled/disabled badge and status icon
            if (pkg.isEnabled) {
                binding.enabledBadge.text = "Enabled"
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_complete)
                binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.statusIcon.setColorFilter(
                    ContextCompat.getColor(binding.root.context, android.R.color.holo_green_dark)
                )
            } else {
                binding.enabledBadge.text = "Disabled"
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_background)
                binding.statusIcon.setImageResource(R.drawable.ic_block)
                binding.statusIcon.setColorFilter(
                    ContextCompat.getColor(binding.root.context, android.R.color.darker_gray)
                )
            }

            // Set package type badge
            when (pkg.type) {
                PackageType.SYSTEM -> {
                    binding.packageTypeBadge.text = "System"
                    binding.packageTypeBadge.setBackgroundResource(R.drawable.root_status_background)
                    binding.packageTypeBadge.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.blue_grey)
                    )
                }
                PackageType.USER -> {
                    binding.packageTypeBadge.text = "User"
                    binding.packageTypeBadge.setBackgroundResource(R.drawable.status_badge_light_blue)
                    binding.packageTypeBadge.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.dark_teal)
                    )
                }
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

