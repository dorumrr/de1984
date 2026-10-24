package io.github.dorumrr.de1984.ui.common

import android.view.View
import android.widget.PopupMenu
import androidx.core.view.MenuCompat
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.utils.PackageUtils.SortCriteria

object SortMenu {

    private fun labelOf(sort: SortCriteria): Int = when (sort) {
        SortCriteria.NAME -> R.string.sort_by_name
        SortCriteria.INSTALL_TIME -> R.string.sort_recently_installed
        SortCriteria.UPDATE_TIME -> R.string.sort_recently_updated
    }

    /** [configuredFirst] null hides the "Configured first" box, for a screen with no rules. */
    fun show(
        anchor: View,
        current: SortCriteria,
        onPick: (SortCriteria) -> Unit,
        configuredFirst: Boolean? = null,
        onConfiguredFirst: (Boolean) -> Unit = {},
    ) {
        val popup = PopupMenu(anchor.context, anchor)
        val orders = SortCriteria.entries
        orders.forEachIndexed { index, sort ->
            popup.menu.add(ORDER_GROUP, index, index, labelOf(sort)).isChecked = sort == current
        }
        popup.menu.setGroupCheckable(ORDER_GROUP, true, true)
        if (configuredFirst != null) {
            popup.menu.add(CONFIGURED_GROUP, CONFIGURED_ID, orders.size, R.string.sort_configured_first).apply {
                isCheckable = true
                isChecked = configuredFirst
            }
            MenuCompat.setGroupDividerEnabled(popup.menu, true)
        }
        popup.setOnMenuItemClickListener { item ->
            if (item.groupId == CONFIGURED_GROUP) {
                onConfiguredFirst(!item.isChecked)
            } else {
                val picked = orders[item.itemId]
                if (picked != current) onPick(picked)
            }
            true
        }
        popup.show()
    }

    private const val ORDER_GROUP = 0
    private const val CONFIGURED_GROUP = 1
    private const val CONFIGURED_ID = 100
}
