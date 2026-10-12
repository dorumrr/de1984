package io.github.dorumrr.de1984.ui.common

import android.view.View
import android.widget.PopupMenu
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.utils.PackageUtils.SortCriteria

object SortMenu {

    private fun labelOf(sort: SortCriteria): Int = when (sort) {
        SortCriteria.NAME -> R.string.sort_by_name
        SortCriteria.INSTALL_TIME -> R.string.sort_recently_installed
        SortCriteria.UPDATE_TIME -> R.string.sort_recently_updated
        SortCriteria.CONFIGURED_FIRST -> R.string.sort_configured_first
    }

    fun show(anchor: View, current: SortCriteria, options: Set<SortCriteria>, onPick: (SortCriteria) -> Unit) {
        val popup = PopupMenu(anchor.context, anchor)
        val ordered = SortCriteria.entries.filter { it in options }
        ordered.forEachIndexed { index, sort ->
            popup.menu.add(0, index, index, labelOf(sort)).isChecked = sort == current
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            val picked = ordered[item.itemId]
            if (picked != current) onPick(picked)
            true
        }
        popup.show()
    }
}
