package io.github.dorumrr.de1984.ui.firewall

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.telephony.TelephonyManager
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
        private val systemCriticalBadge: TextView = itemView.findViewById(R.id.system_critical_badge)
        private val wifiIcon: ImageView = itemView.findViewById(R.id.wifi_icon)
        private val wifiBlockedOverlay: ImageView = itemView.findViewById(R.id.wifi_blocked_overlay)
        private val mobileIcon: ImageView = itemView.findViewById(R.id.mobile_icon)
        private val mobileBlockedOverlay: ImageView = itemView.findViewById(R.id.mobile_blocked_overlay)
        private val roamingContainer: View = itemView.findViewById(R.id.roaming_container)
        private val roamingIcon: ImageView = itemView.findViewById(R.id.roaming_icon)
        private val roamingBlockedOverlay: ImageView = itemView.findViewById(R.id.roaming_blocked_overlay)

        // Check device capability once
        private val hasCellular: Boolean by lazy {
            val telephonyManager = itemView.context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE
        }

        fun bind(pkg: NetworkPackage) {
            // Set app name and package name
            appName.text = pkg.name
            packageName.text = pkg.packageName

            // Show/hide system critical badge
            systemCriticalBadge.visibility = if (pkg.isSystemCritical) View.VISIBLE else View.GONE

            // Dim the entire item if system critical
            itemView.alpha = if (pkg.isSystemCritical) 0.6f else 1.0f

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

            // Get colors for allowed (teal) and blocked (red)
            val allowedColor = ContextCompat.getColor(itemView.context, R.color.lineage_teal)
            val blockedColor = ContextCompat.getColor(itemView.context, R.color.error_red)

            // Set WiFi icon color and overlay
            wifiIcon.setColorFilter(
                if (pkg.wifiBlocked) blockedColor else allowedColor,
                PorterDuff.Mode.SRC_IN
            )
            wifiBlockedOverlay.visibility = if (pkg.wifiBlocked) View.VISIBLE else View.GONE
            wifiBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)

            // Set Mobile icon color and overlay
            mobileIcon.setColorFilter(
                if (pkg.mobileBlocked) blockedColor else allowedColor,
                PorterDuff.Mode.SRC_IN
            )
            mobileBlockedOverlay.visibility = if (pkg.mobileBlocked) View.VISIBLE else View.GONE
            mobileBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)

            // Set Roaming icon visibility, color, and overlay
            // Always show if device has cellular
            if (hasCellular) {
                roamingContainer.visibility = View.VISIBLE
                roamingIcon.setColorFilter(
                    if (pkg.roamingBlocked) blockedColor else allowedColor,
                    PorterDuff.Mode.SRC_IN
                )
                roamingBlockedOverlay.visibility = if (pkg.roamingBlocked) View.VISIBLE else View.GONE
                roamingBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)
            } else {
                // Hide roaming icon on WiFi-only devices
                roamingContainer.visibility = View.GONE
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

