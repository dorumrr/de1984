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
     * Setup filter chips with multiple selection support (INITIAL SETUP ONLY)
     * Used when filters can be toggled independently
     * Call this ONCE in onViewCreated, then use updateMultiSelectFilterChips to update selection
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

        // Add type filters (single selection - radio button behavior)
        typeFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedTypeFilter)
            chip.tag = "type:$filter" // Tag to identify chip type
            chip.setOnCheckedChangeListener { buttonView, isChecked ->
                if (isChecked) {
                    // When a type chip is checked, uncheck all other type chips
                    val clickedFilter = chip.tag.toString().removePrefix("type:")

                    // Uncheck other type chips
                    for (i in 0 until chipGroup.childCount) {
                        val otherChip = chipGroup.getChildAt(i) as? Chip
                        if (otherChip != null &&
                            otherChip.tag.toString().startsWith("type:") &&
                            otherChip != chip) {
                            // Temporarily remove listener to avoid recursion
                            otherChip.setOnCheckedChangeListener(null)
                            otherChip.isChecked = false
                            // Re-attach listener
                            setupTypeChipListener(otherChip, chipGroup, onTypeFilterSelected)
                        }
                    }

                    // Notify the callback
                    onTypeFilterSelected(clickedFilter)
                } else {
                    // Prevent unchecking - at least one type must be selected
                    chip.setOnCheckedChangeListener(null)
                    chip.isChecked = true
                    setupTypeChipListener(chip, chipGroup, onTypeFilterSelected)
                }
            }
            chipGroup.addView(chip)
        }

        // Add state filters (single selection with optional deselection)
        stateFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedStateFilter)
            chip.tag = "state:$filter" // Tag to identify chip type
            chip.setOnCheckedChangeListener { buttonView, isChecked ->
                val clickedFilter = chip.tag.toString().removePrefix("state:")
                if (isChecked) {
                    // When a state chip is checked, uncheck all other state chips
                    for (i in 0 until chipGroup.childCount) {
                        val otherChip = chipGroup.getChildAt(i) as? Chip
                        if (otherChip != null &&
                            otherChip.tag.toString().startsWith("state:") &&
                            otherChip != chip) {
                            // Temporarily remove listener to avoid recursion
                            otherChip.setOnCheckedChangeListener(null)
                            otherChip.isChecked = false
                            // Re-attach listener
                            setupStateChipListener(otherChip, chipGroup, onStateFilterSelected)
                        }
                    }

                    // Notify the callback
                    onStateFilterSelected(clickedFilter)
                } else {
                    // Allow unchecking state chips (optional filter)
                    onStateFilterSelected(null)
                }
            }
            chipGroup.addView(chip)
        }
    }

    /**
     * Helper to setup type chip listener (for re-attaching after programmatic changes)
     */
    private fun setupTypeChipListener(
        chip: Chip,
        chipGroup: ChipGroup,
        onTypeFilterSelected: (String) -> Unit
    ) {
        chip.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val clickedFilter = chip.tag.toString().removePrefix("type:")

                // Uncheck other type chips
                for (i in 0 until chipGroup.childCount) {
                    val otherChip = chipGroup.getChildAt(i) as? Chip
                    if (otherChip != null &&
                        otherChip.tag.toString().startsWith("type:") &&
                        otherChip != chip) {
                        otherChip.setOnCheckedChangeListener(null)
                        otherChip.isChecked = false
                        setupTypeChipListener(otherChip, chipGroup, onTypeFilterSelected)
                    }
                }

                onTypeFilterSelected(clickedFilter)
            } else {
                // Prevent unchecking
                chip.setOnCheckedChangeListener(null)
                chip.isChecked = true
                setupTypeChipListener(chip, chipGroup, onTypeFilterSelected)
            }
        }
    }

    /**
     * Helper to setup state chip listener (for re-attaching after programmatic changes)
     */
    private fun setupStateChipListener(
        chip: Chip,
        chipGroup: ChipGroup,
        onStateFilterSelected: (String?) -> Unit
    ) {
        chip.setOnCheckedChangeListener { buttonView, isChecked ->
            val clickedFilter = chip.tag.toString().removePrefix("state:")
            if (isChecked) {
                // Uncheck other state chips
                for (i in 0 until chipGroup.childCount) {
                    val otherChip = chipGroup.getChildAt(i) as? Chip
                    if (otherChip != null &&
                        otherChip.tag.toString().startsWith("state:") &&
                        otherChip != chip) {
                        otherChip.setOnCheckedChangeListener(null)
                        otherChip.isChecked = false
                        setupStateChipListener(otherChip, chipGroup, onStateFilterSelected)
                    }
                }

                onStateFilterSelected(clickedFilter)
            } else {
                // Allow unchecking state chips (optional filter)
                onStateFilterSelected(null)
            }
        }
    }

    /**
     * Update chip selection without recreating chips or triggering listeners
     * Use this in updateUI() to reflect state changes
     */
    fun updateMultiSelectFilterChips(
        chipGroup: ChipGroup,
        selectedTypeFilter: String?,
        selectedStateFilter: String?
    ) {
        // Disable all listeners temporarily
        chipGroup.setOnCheckedStateChangeListener(null)

        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            val tag = chip.tag as? String ?: continue

            when {
                tag.startsWith("type:") -> {
                    val filterName = tag.removePrefix("type:")
                    chip.isChecked = filterName == selectedTypeFilter
                }
                tag.startsWith("state:") -> {
                    val filterName = tag.removePrefix("state:")
                    chip.isChecked = filterName == selectedStateFilter
                }
            }
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

