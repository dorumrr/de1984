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

            if (pkg.isEnabled) {
                binding.enabledBadge.text = Constants.Packages.STATE_ENABLED
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_complete)
            } else {
                binding.enabledBadge.text = Constants.Packages.STATE_DISABLED
                binding.enabledBadge.setBackgroundResource(R.drawable.status_badge_background)
            }

            // Set package type badge (Chip handles styling automatically)
            binding.packageTypeBadge.text = when (pkg.type) {
                PackageType.SYSTEM -> "System"
                PackageType.USER -> "User"
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

