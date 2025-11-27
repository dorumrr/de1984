package io.github.dorumrr.de1984.ui.firewall

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.telephony.TelephonyManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils

/**
 * Adapter for displaying network packages in Firewall screen
 * Reusable and optimized with DiffUtil
 */
class NetworkPackageAdapter(
    private val showIcons: Boolean,
    private val onPackageClick: (NetworkPackage) -> Unit,
    private val onPackageLongClick: (NetworkPackage) -> Boolean = { false }
) : ListAdapter<NetworkPackage, NetworkPackageAdapter.NetworkPackageViewHolder>(NetworkPackageDiffCallback()) {

    companion object {
        private const val TAG = "NetworkPackageAdapter"
    }

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<String>()
    private var onSelectionChanged: ((Set<String>) -> Unit)? = null
    private var onSelectionLimitReached: (() -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkPackageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_package, parent, false)
        val context = parent.context
        return NetworkPackageViewHolder(
            view,
            showIcons,
            onPackageClick,
            onPackageLongClick,
            ::isPackageSelected,
            { pkg -> canSelectPackage(pkg, context) },
            ::togglePackageSelection
        )
    }

    override fun onBindViewHolder(holder: NetworkPackageViewHolder, position: Int) {
        holder.bind(getItem(position), isSelectionMode)
    }

    fun setOnSelectionLimitReachedListener(listener: () -> Unit) {
        onSelectionLimitReached = listener
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

    fun setOnSelectionChangedListener(listener: (Set<String>) -> Unit) {
        onSelectionChanged = listener
    }

    fun getSelectedPackages(): Set<String> = selectedPackages.toSet()

    fun clearSelection() {
        selectedPackages.clear()
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    /**
     * Programmatically select a package (used when entering selection mode via long press)
     */
    fun selectPackage(packageName: String) {
        if (!selectedPackages.contains(packageName) &&
            selectedPackages.size < Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
            selectedPackages.add(packageName)
            onSelectionChanged?.invoke(selectedPackages)
            notifyDataSetChanged()
        }
    }

    /**
     * Check if a package can be selected (with context).
     * Critical packages and VPN apps cannot be selected unless the setting is enabled.
     */
    fun canSelectPackage(pkg: NetworkPackage, context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )
        // Cannot select if critical/VPN and setting is OFF
        if ((pkg.isSystemCritical || pkg.isVpnApp) && !allowCritical) return false
        return true
    }

    private fun isPackageSelected(packageName: String): Boolean {
        return selectedPackages.contains(packageName)
    }

    private fun togglePackageSelection(pkg: NetworkPackage, context: Context) {
        if (!canSelectPackage(pkg, context)) {
            // Show toast for non-selectable packages
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.firewall_multiselect_toast_cannot_select_critical),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (selectedPackages.contains(pkg.packageName)) {
            selectedPackages.remove(pkg.packageName)
        } else {
            if (selectedPackages.size >= Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
                onSelectionLimitReached?.invoke()
                return
            }
            selectedPackages.add(pkg.packageName)
        }
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    class NetworkPackageViewHolder(
        itemView: View,
        private val showIcons: Boolean,
        private val onPackageClick: (NetworkPackage) -> Unit,
        private val onPackageLongClick: (NetworkPackage) -> Boolean,
        private val isPackageSelected: (String) -> Boolean,
        private val canSelectPackage: (NetworkPackage) -> Boolean,
        private val togglePackageSelection: (NetworkPackage, Context) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val selectionCheckbox: CheckBox = itemView.findViewById(R.id.selection_checkbox)
        private val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        private val appName: TextView = itemView.findViewById(R.id.app_name)
        private val packageName: TextView = itemView.findViewById(R.id.package_name)
        private val systemCriticalBadge: TextView = itemView.findViewById(R.id.system_critical_badge)
        private val vpnAppBadge: TextView = itemView.findViewById(R.id.vpn_app_badge)
        private val noInternetBadge: TextView = itemView.findViewById(R.id.no_internet_badge)
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

        // Store current package for selection mode click handling
        private var currentPackage: NetworkPackage? = null
        private var currentIsSelectionMode: Boolean = false

        fun bind(pkg: NetworkPackage, isSelectionMode: Boolean) {
            currentPackage = pkg
            currentIsSelectionMode = isSelectionMode

            // Set app name and package name
            appName.text = pkg.name
            packageName.text = pkg.packageName

            // Show/hide system critical badge
            systemCriticalBadge.visibility = if (pkg.isSystemCritical) View.VISIBLE else View.GONE

            // Show/hide VPN app badge
            vpnAppBadge.visibility = if (pkg.isVpnApp) View.VISIBLE else View.GONE

            // Show/hide no internet permission badge
            noInternetBadge.visibility = if (!pkg.hasInternetPermission) View.VISIBLE else View.GONE

            // Dim the entire item if system critical or VPN app (unless setting is enabled)
            val prefs = itemView.context.getSharedPreferences(
                Constants.Settings.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val allowCritical = prefs.getBoolean(
                Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
            )
            val shouldDim = !allowCritical && (pkg.isSystemCritical || pkg.isVpnApp)
            itemView.alpha = if (shouldDim) 0.6f else 1.0f

            // Selection mode UI
            if (isSelectionMode) {
                selectionCheckbox.visibility = View.VISIBLE
                val isSelected = isPackageSelected(pkg.packageName)
                selectionCheckbox.isChecked = isSelected

                // Dim checkbox for non-selectable packages
                val canSelect = !shouldDim // If dimmed, can't select
                selectionCheckbox.alpha = if (canSelect) 1.0f else 0.5f
            } else {
                selectionCheckbox.visibility = View.GONE
            }

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
                currentPackage?.let { pkg ->
                    if (currentIsSelectionMode) {
                        togglePackageSelection(pkg, itemView.context)
                    } else {
                        onPackageClick(pkg)
                    }
                }
            }

            // Set long click listener
            itemView.setOnLongClickListener {
                currentPackage?.let { pkg ->
                    onPackageLongClick(pkg)
                } ?: false
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

