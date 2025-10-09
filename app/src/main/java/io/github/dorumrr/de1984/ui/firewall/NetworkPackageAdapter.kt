package io.github.dorumrr.de1984.ui.firewall

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.PackageUtils

/**
 * Adapter for displaying network packages in Firewall screen
 * Reusable and optimized with DiffUtil
 */
class NetworkPackageAdapter(
    private val showIcons: Boolean,
    private val onPackageClick: (NetworkPackage) -> Unit
) : ListAdapter<NetworkPackage, NetworkPackageAdapter.NetworkPackageViewHolder>(NetworkPackageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkPackageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_package, parent, false)
        return NetworkPackageViewHolder(view, showIcons, onPackageClick)
    }

    override fun onBindViewHolder(holder: NetworkPackageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NetworkPackageViewHolder(
        itemView: View,
        private val showIcons: Boolean,
        private val onPackageClick: (NetworkPackage) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        private val appName: TextView = itemView.findViewById(R.id.app_name)
        private val packageName: TextView = itemView.findViewById(R.id.package_name)
        private val networkStatusBadge: TextView = itemView.findViewById(R.id.network_status_badge)
        private val packageTypeBadge: TextView = itemView.findViewById(R.id.package_type_badge)
        private val networkStatusIcon: ImageView = itemView.findViewById(R.id.network_status_icon)

        fun bind(pkg: NetworkPackage) {
            // Set app name and package name
            appName.text = pkg.name
            packageName.text = pkg.packageName

            // Set app icon
            if (showIcons) {
                appIcon.visibility = View.VISIBLE
                try {
                    val pm = itemView.context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg.packageName, 0)
                    val icon = pm.getApplicationIcon(appInfo)
                    appIcon.setImageDrawable(icon)
                } catch (e: Exception) {
                    appIcon.setImageResource(R.drawable.de1984_icon)
                }
            } else {
                appIcon.visibility = View.GONE
            }

            // Set network status badge and icon based on granular state
            when {
                pkg.isFullyAllowed -> {
                    networkStatusBadge.text = "Allowed"
                    networkStatusBadge.setBackgroundResource(R.drawable.status_badge_complete)
                    networkStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    networkStatusIcon.setColorFilter(
                        ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                    )
                }
                pkg.isFullyBlocked -> {
                    networkStatusBadge.text = "Blocked"
                    networkStatusBadge.setBackgroundResource(R.drawable.status_badge_background)
                    networkStatusIcon.setImageResource(R.drawable.ic_block)
                    networkStatusIcon.setColorFilter(
                        ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    )
                }
                pkg.isPartiallyBlocked -> {
                    // Show specific state for partial blocking
                    networkStatusBadge.text = pkg.networkState
                    networkStatusBadge.setBackgroundResource(R.drawable.status_badge_partial)
                    networkStatusIcon.setImageResource(R.drawable.ic_warning)
                    networkStatusIcon.setColorFilter(
                        ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark)
                    )
                }
                else -> {
                    networkStatusBadge.text = "Unknown"
                    networkStatusBadge.setBackgroundResource(R.drawable.root_status_background)
                    networkStatusIcon.setImageResource(R.drawable.ic_warning)
                    networkStatusIcon.setColorFilter(
                        ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                    )
                }
            }

            // Set package type badge
            packageTypeBadge.text = when (pkg.type) {
                PackageType.SYSTEM -> "System"
                PackageType.USER -> "User"
            }

            // Set click listener
            itemView.setOnClickListener {
                onPackageClick(pkg)
            }
        }
    }

    class NetworkPackageDiffCallback : DiffUtil.ItemCallback<NetworkPackage>() {
        override fun areItemsTheSame(oldItem: NetworkPackage, newItem: NetworkPackage): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: NetworkPackage, newItem: NetworkPackage): Boolean {
            return oldItem == newItem
        }
    }
}

