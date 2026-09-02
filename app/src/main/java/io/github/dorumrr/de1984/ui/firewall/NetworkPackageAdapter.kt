package io.github.dorumrr.de1984.ui.firewall

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.telephony.TelephonyManager
import android.util.Log
import android.util.LruCache
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
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.domain.firewall.BlockingContext
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.blockingRefused
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NetworkType {
    WIFI, MOBILE, ROAMING
}

class NetworkPackageAdapter(
    private val showIcons: Boolean,
    private val onPackageClick: (NetworkPackage) -> Unit,
    private val onPackageLongClick: (NetworkPackage) -> Boolean = { false },
    private val onQuickToggle: ((NetworkPackage, NetworkType) -> Unit)? = null
) : ListAdapter<NetworkPackage, NetworkPackageAdapter.NetworkPackageViewHolder>(NetworkPackageDiffCallback()) {

    companion object {
        private const val TAG = "NetworkPackageAdapter"
        private const val ICON_CACHE_SIZE = 100
    }

    init {
        // Issue #73. Hold the restored scroll anchor until there is something to scroll to.
        //
        // The package list arrives asynchronously - AndroidPackageDataSource.getPackages() runs its
        // scan in onStart on Dispatchers.IO, so even the replay=1 cache is withheld until that
        // finishes. Meanwhile FirewallFragmentViews.updateUI sets the RecyclerView to INVISIBLE
        // while the list is empty, and INVISIBLE views are still measured and laid out. With the
        // default ALLOW policy, that layout pass consumes the anchor Android had just restored
        // against an empty adapter and throws it away, so the data lands at position 0.
        //
        // PREVENT_WHEN_EMPTY makes RecyclerView keep the anchor until the first submitList lands.
        //
        // Why the reporter saw Firewall lose it and Packages hold it: MainActivity hides the
        // non-current tabs on restore, and a hidden fragment's view is GONE, so it is never laid out
        // empty. Firewall is the tab that is visible on restore, so Firewall is the one that got
        // hit. PackageAdapter has the same shape and is only shielded by being hidden.
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
    private var onSelectionChanged: ((Set<PackageId>) -> Unit)? = null
    private var onSelectionLimitReached: (() -> Unit)? = null

    private val iconCache = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    /**
     * The running backend, or null while the firewall is stopped. Decides whether a row outside the
     * app-uid range can be blocked at all - see [blockingRefused].
     */
    private var cachedBackendType: FirewallBackendType? = null

    /**
     * From FirewallUiState. Needs the UNFILTERED package list to be correct, which only the
     * ViewModel has, so it arrives by setter rather than being worked out here.
     */
    private var cachedBlockingContext: BlockingContext = BlockingContext()

    private var hasCellular: Boolean = true

    fun initialize(context: Context) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        refreshSettings(context)
    }

    fun refreshSettings(context: Context) {
        // Read here rather than through a setter because the fragment builds the adapter in two
        // places - setupRecyclerView and the icons-changed branch of observeSettingsState - and
        // both call initialize(), which calls this. A setter would have to be repeated at both.
        cachedBackendType = (context.applicationContext as? De1984Application)
            ?.dependencies?.firewallManager?.activeBackendType?.value
    }

    /**
     * A row whose block nothing here can make happen. Dimmed with dead toggles, the same treatment
     * a protected package gets. NOT used for what the row DISPLAYS - the list arrives already
     * carrying the enforced flags, see FirewallViewModel.filterPackages.
     */
    private fun isRefusedByBackend(pkg: NetworkPackage): Boolean =
        cachedBackendType.blockingRefused(pkg, cachedBlockingContext)

    /** Rebinds only when it actually changed - this is called on every list update. */
    fun setBlockingContext(context: BlockingContext) {
        if (cachedBlockingContext == context) return
        cachedBlockingContext = context
        notifyDataSetChanged()
    }

    fun clearIconCache() {
        iconCache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkPackageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_package, parent, false)
        return NetworkPackageViewHolder(
            view,
            showIcons,
            onPackageClick,
            onPackageLongClick,
            ::isPackageSelected,
            ::togglePackageSelection,
            onQuickToggle,
            iconCache,
            { hasCellular },
            ::isRefusedByBackend
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

    fun setOnSelectionChangedListener(listener: (Set<PackageId>) -> Unit) {
        onSelectionChanged = listener
    }


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

    fun getSelectedPackages(): Set<PackageId> = selectedPackages.toSet()

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

    /**
     * pkg.paintedAllowCritical, never the preference. The row's blocking flags were painted with
     * that value and the blocking context is built from it, so reading the preference here made this
     * screen answer one question with two different settings for the length of a rescan - a row
     * dimmed and unselectable beside a sheet saying its switches work, or the reverse.
     *
     * There were two of these, a "cached" one and one that read preferences. Once both took the
     * answer from the row the bodies were identical, and two names for one rule is the shape that
     * produced the shadowed-mapper defect in this same change. One function.
     */
    fun canSelectPackage(pkg: NetworkPackage): Boolean {
        if ((pkg.isSystemCritical || pkg.isVpnApp) && !pkg.paintedAllowCritical) return false
        if (isRefusedByBackend(pkg)) return false
        return true
    }

    private fun isPackageSelected(packageId: PackageId): Boolean {
        return selectedPackages.contains(packageId)
    }

    private fun togglePackageSelection(pkg: NetworkPackage, context: Context) {
        val packageId = pkg.id

        // Removing is always allowed, and it has to come BEFORE the guard. A row can become
        // unselectable while it is already selected - the firewall starts, or a privilege gain
        // switches the backend, neither of which the user did from this screen - and with the guard
        // first every further tap only re-showed the toast. The row could then never be taken out of
        // the selection, and the batch actions still applied to it. The tap on the row is the only
        // deselect there is: the checkbox is not clickable and the toolbar has no "unselect".
        if (selectedPackages.contains(packageId)) {
            selectedPackages.remove(packageId)
            onSelectionChanged?.invoke(selectedPackages)
            notifyDataSetChanged()
            return
        }

        if (!canSelectPackage(pkg)) {
            // Protection by the setting comes first: when it applies that is the operative reason,
            // and there is a switch in Settings for it.
            val protectedBySetting = (pkg.isSystemCritical || pkg.isVpnApp) && !pkg.paintedAllowCritical
            val reason = if (!protectedBySetting && isRefusedByBackend(pkg)) {
                R.string.firewall_multiselect_toast_cannot_select_system_uid
            } else {
                R.string.firewall_multiselect_toast_cannot_select_critical
            }
            android.widget.Toast.makeText(
                context,
                context.getString(reason),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (selectedPackages.size >= Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
            onSelectionLimitReached?.invoke()
            return
        }
        selectedPackages.add(packageId)

        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    class NetworkPackageViewHolder(
        itemView: View,
        private val showIcons: Boolean,
        private val onPackageClick: (NetworkPackage) -> Unit,
        private val onPackageLongClick: (NetworkPackage) -> Boolean,
        private val isPackageSelected: (PackageId) -> Boolean,
        private val togglePackageSelection: (NetworkPackage, Context) -> Unit,
        private val onQuickToggle: ((NetworkPackage, NetworkType) -> Unit)?,
        private val iconCache: LruCache<String, Drawable>,
        private val getHasCellular: () -> Boolean,
        private val isRefusedByBackend: (NetworkPackage) -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {

        private val selectionCheckbox: CheckBox = itemView.findViewById(R.id.selection_checkbox)
        private val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        private val appName: TextView = itemView.findViewById(R.id.app_name)
        private val packageName: TextView = itemView.findViewById(R.id.package_name)
        private val systemCriticalBadge: TextView = itemView.findViewById(R.id.system_critical_badge)
        private val vpnAppBadge: TextView = itemView.findViewById(R.id.vpn_app_badge)
        private val noInternetBadge: TextView = itemView.findViewById(R.id.no_internet_badge)
        private val profileBadge: TextView = itemView.findViewById(R.id.profile_badge)
        private val wifiContainer: View = itemView.findViewById(R.id.wifi_container)
        private val wifiIcon: ImageView = itemView.findViewById(R.id.wifi_icon)
        private val wifiBlockedOverlay: ImageView = itemView.findViewById(R.id.wifi_blocked_overlay)
        private val mobileContainer: View = itemView.findViewById(R.id.mobile_container)
        private val mobileIcon: ImageView = itemView.findViewById(R.id.mobile_icon)
        private val mobileBlockedOverlay: ImageView = itemView.findViewById(R.id.mobile_blocked_overlay)
        private val roamingContainer: View = itemView.findViewById(R.id.roaming_container)
        private val roamingIcon: ImageView = itemView.findViewById(R.id.roaming_icon)
        private val roamingBlockedOverlay: ImageView = itemView.findViewById(R.id.roaming_blocked_overlay)

        private val scope = CoroutineScope(Dispatchers.Main)

        private var currentPackage: NetworkPackage? = null
        private var currentIsSelectionMode: Boolean = false
        // Track which package the icon was loaded for (to avoid race conditions)
        private var iconLoadedForPackage: String? = null

        fun bind(pkg: NetworkPackage, isSelectionMode: Boolean) {
            currentPackage = pkg
            currentIsSelectionMode = isSelectionMode

            appName.text = pkg.name
            packageName.text = pkg.packageName

            systemCriticalBadge.visibility = if (pkg.isSystemCritical) View.VISIBLE else View.GONE

            vpnAppBadge.visibility = if (pkg.isVpnApp) View.VISIBLE else View.GONE

            noInternetBadge.visibility = if (!pkg.hasInternetPermission) View.VISIBLE else View.GONE

            when {
                pkg.userId >= 10 && pkg.userId < 100 -> {
                    profileBadge.text = itemView.context.getString(R.string.badge_work_profile)
                    profileBadge.visibility = View.VISIBLE
                }
                pkg.userId >= 100 -> {
                    profileBadge.text = itemView.context.getString(R.string.badge_clone_profile)
                    profileBadge.visibility = View.VISIBLE
                }
                else -> {
                    profileBadge.visibility = View.GONE
                }
            }

            // Dim the entire item if system critical or VPN app (unless setting is enabled), or if
            // the running backend cannot act on this uid at all. Dimming is what already means
            // "the toggles here will not respond", and it gates canQuickToggle below, so a row the
            // backend would silently skip stops offering a switch that writes a rule nothing
            // enforces. Tapping the row still opens the sheet, which explains why.
            // From the ROW, which records the setting its flags were painted with - no preference
            // read per bind, and no way for the dimming to describe a different setting than the row.
            val shouldDim = (!pkg.paintedAllowCritical && (pkg.isSystemCritical || pkg.isVpnApp)) ||
                isRefusedByBackend(pkg)
            itemView.alpha = if (shouldDim) 0.6f else 1.0f

            if (isSelectionMode) {
                selectionCheckbox.visibility = View.VISIBLE
                val isSelected = isPackageSelected(pkg.id)
                selectionCheckbox.isChecked = isSelected

                val canSelect = !shouldDim
                selectionCheckbox.alpha = if (canSelect) 1.0f else 0.5f
            } else {
                selectionCheckbox.visibility = View.GONE
            }

            if (showIcons) {
                appIcon.visibility = View.VISIBLE
                val iconCacheKey = "${pkg.packageName}_${pkg.userId}"

                val cachedIcon = iconCache.get(iconCacheKey)
                if (cachedIcon != null) {
                    appIcon.setImageDrawable(cachedIcon)
                    iconLoadedForPackage = iconCacheKey
                } else {
                    appIcon.setImageResource(R.drawable.de1984_icon)
                    iconLoadedForPackage = null

                    val context = itemView.context
                    scope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            try {
                                val pm = context.packageManager
                                val appInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                                    context, pkg.packageName, 0, pkg.userId
                                )
                                if (appInfo != null) {
                                    pm.getApplicationIcon(appInfo)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }

                        if (currentPackage?.packageName == pkg.packageName && currentPackage?.userId == pkg.userId) {
                            if (icon != null) {
                                iconCache.put(iconCacheKey, icon)
                                appIcon.setImageDrawable(icon)
                                iconLoadedForPackage = iconCacheKey
                            }
                        }
                    }
                }
            } else {
                appIcon.visibility = View.GONE
            }

            val allowedColor = ContextCompat.getColor(itemView.context, R.color.lineage_teal)
            val blockedColor = ContextCompat.getColor(itemView.context, R.color.error_red)

            // These icons report what the NETWORK is doing, not what the database holds, and the two
            // part company for a uid the running backend cannot act on. Under the Block All default
            // every package with no rule of its own is synthesised as blocked, so an unreachable row
            // was born red and struck through while the app kept full network - the exact false
            // "Blocked" this change exists to remove. Dimming alone did not fix it, because a dimmed
            // critical row means "forced Allowed" and the same pixels would have meant the opposite
            // here.
            // The WIDER test: the icons report what the network is doing, and under Block All in a
            // protected uid the app is online even though its controls stay usable.
            // No masking here. FirewallViewModel.filterPackages hands this list out with the
            // ENFORCED flags already applied, so these report what the network is doing.
            // Masking a second time is what let the icons and the quick toggle disagree.
            val wifiBlocked = pkg.wifiBlocked
            val mobileBlocked = pkg.mobileBlocked
            val roamingBlocked = pkg.roamingBlocked

            wifiIcon.setColorFilter(
                if (wifiBlocked) blockedColor else allowedColor,
                PorterDuff.Mode.SRC_IN
            )
            wifiBlockedOverlay.visibility = if (wifiBlocked) View.VISIBLE else View.GONE
            wifiBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)

            mobileIcon.setColorFilter(
                if (mobileBlocked) blockedColor else allowedColor,
                PorterDuff.Mode.SRC_IN
            )
            mobileBlockedOverlay.visibility = if (mobileBlocked) View.VISIBLE else View.GONE
            mobileBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)

            val hasCellular = getHasCellular()
            if (hasCellular) {
                roamingContainer.visibility = View.VISIBLE
                roamingIcon.setColorFilter(
                    if (roamingBlocked) blockedColor else allowedColor,
                    PorterDuff.Mode.SRC_IN
                )
                roamingBlockedOverlay.visibility = if (roamingBlocked) View.VISIBLE else View.GONE
                roamingBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)
            } else {
                roamingContainer.visibility = View.GONE
            }

            val canQuickToggle = onQuickToggle != null && !shouldDim && !isSelectionMode

            wifiContainer.setOnClickListener {
                if (canQuickToggle) {
                    currentPackage?.let { pkg -> onQuickToggle?.invoke(pkg, NetworkType.WIFI) }
                }
            }
            wifiContainer.isClickable = canQuickToggle

            mobileContainer.setOnClickListener {
                if (canQuickToggle) {
                    currentPackage?.let { pkg -> onQuickToggle?.invoke(pkg, NetworkType.MOBILE) }
                }
            }
            mobileContainer.isClickable = canQuickToggle

            roamingContainer.setOnClickListener {
                if (canQuickToggle && hasCellular) {
                    currentPackage?.let { pkg -> onQuickToggle?.invoke(pkg, NetworkType.ROAMING) }
                }
            }
            if (hasCellular) {
                roamingContainer.isClickable = canQuickToggle
            }

            itemView.setOnClickListener {
                currentPackage?.let { pkg ->
                    if (currentIsSelectionMode) {
                        togglePackageSelection(pkg, itemView.context)
                    } else {
                        onPackageClick(pkg)
                    }
                }
            }

            itemView.setOnLongClickListener {
                currentPackage?.let { pkg ->
                    onPackageLongClick(pkg)
                } ?: false
            }
        }
    }

    class NetworkPackageDiffCallback : DiffUtil.ItemCallback<NetworkPackage>() {
        override fun areItemsTheSame(oldItem: NetworkPackage, newItem: NetworkPackage): Boolean {
            return oldItem.packageName == newItem.packageName && oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: NetworkPackage, newItem: NetworkPackage): Boolean {
            return oldItem == newItem
        }
    }
}

