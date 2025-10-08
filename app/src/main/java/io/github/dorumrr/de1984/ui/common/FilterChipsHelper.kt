package io.github.dorumrr.de1984.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.dorumrr.de1984.R

/**
 * Helper class for managing filter chips in Views
 * Reusable across Firewall and Packages screens
 */
object FilterChipsHelper {
    
    /**
     * Setup filter chips in a ChipGroup
     * 
     * @param chipGroup The ChipGroup to populate
     * @param filters List of filter labels
     * @param selectedFilter Currently selected filter (or null)
     * @param onFilterSelected Callback when a filter is selected
     */
    fun setupFilterChips(
        chipGroup: ChipGroup,
        filters: List<String>,
        selectedFilter: String?,
        onFilterSelected: (String) -> Unit
    ) {
        chipGroup.removeAllViews()
        
        filters.forEach { filter ->
            val chip = LayoutInflater.from(chipGroup.context)
                .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip
            
            chip.text = filter
            chip.isChecked = filter == selectedFilter
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    onFilterSelected(filter)
                }
            }
            
            chipGroup.addView(chip)
        }
        
        // Ensure single selection
        chipGroup.isSingleSelection = false // We handle selection manually
    }
    
    /**
     * Setup filter chips with multiple selection support
     * Used when filters can be toggled independently
     */
    fun setupMultiSelectFilterChips(
        chipGroup: ChipGroup,
        typeFilters: List<String>,
        stateFilters: List<String>,
        selectedTypeFilter: String?,
        selectedStateFilter: String?,
        onTypeFilterSelected: (String) -> Unit,
        onStateFilterSelected: (String?) -> Unit
    ) {
        chipGroup.removeAllViews()
        
        // Add type filters (single selection)
        typeFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedTypeFilter)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    onTypeFilterSelected(filter)
                    // Uncheck other type filters
                    updateChipSelection(chipGroup, typeFilters, filter)
                }
            }
            chipGroup.addView(chip)
        }
        
        // Add state filters (toggle selection)
        stateFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedStateFilter)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    onStateFilterSelected(filter)
                } else {
                    // If unchecked and it was selected, clear selection
                    if (filter == selectedStateFilter) {
                        onStateFilterSelected(null)
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }
    
    private fun createFilterChip(
        chipGroup: ChipGroup,
        label: String,
        isChecked: Boolean
    ): Chip {
        val chip = LayoutInflater.from(chipGroup.context)
            .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip
        chip.text = label
        chip.isChecked = isChecked
        return chip
    }
    
    private fun updateChipSelection(
        chipGroup: ChipGroup,
        typeFilters: List<String>,
        selectedFilter: String
    ) {
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip != null && typeFilters.contains(chip.text.toString())) {
                chip.isChecked = chip.text == selectedFilter
            }
        }
    }
}

