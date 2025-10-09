package io.github.dorumrr.de1984.ui.common

import android.util.Log
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

    private const val TAG = "FilterChipsHelper"

    // Flag to prevent triggering callbacks during programmatic updates
    private var isUpdatingProgrammatically = false
    
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
                Log.d(TAG, "Type chip listener fired: filter=$filter, isChecked=$isChecked, isUpdatingProgrammatically=$isUpdatingProgrammatically")

                // Skip if this is a programmatic update
                if (isUpdatingProgrammatically) {
                    Log.d(TAG, "Skipping type chip callback - programmatic update")
                    return@setOnCheckedChangeListener
                }

                if (isChecked) {
                    // When a type chip is checked, uncheck all other type chips
                    val clickedFilter = chip.tag.toString().removePrefix("type:")
                    Log.d(TAG, "Type chip checked by user: $clickedFilter")

                    // Use programmatic flag to prevent recursion
                    isUpdatingProgrammatically = true

                    // Uncheck other type chips
                    for (i in 0 until chipGroup.childCount) {
                        val otherChip = chipGroup.getChildAt(i) as? Chip
                        if (otherChip != null &&
                            otherChip.tag.toString().startsWith("type:") &&
                            otherChip != chip) {
                            Log.d(TAG, "Unchecking other type chip: ${otherChip.tag}")
                            otherChip.isChecked = false
                        }
                    }

                    isUpdatingProgrammatically = false

                    // Notify the callback
                    Log.d(TAG, "Calling onTypeFilterSelected: $clickedFilter")
                    onTypeFilterSelected(clickedFilter)
                } else {
                    // Prevent unchecking - at least one type must be selected
                    Log.d(TAG, "Preventing type chip uncheck")
                    isUpdatingProgrammatically = true
                    chip.isChecked = true
                    isUpdatingProgrammatically = false
                }
            }
            chipGroup.addView(chip)
        }

        // Add state filters (single selection with optional deselection)
        stateFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedStateFilter)
            chip.tag = "state:$filter" // Tag to identify chip type
            chip.setOnCheckedChangeListener { buttonView, isChecked ->
                Log.d(TAG, "State chip listener fired: filter=$filter, isChecked=$isChecked, isUpdatingProgrammatically=$isUpdatingProgrammatically")

                // Skip if this is a programmatic update
                if (isUpdatingProgrammatically) {
                    Log.d(TAG, "Skipping state chip callback - programmatic update")
                    return@setOnCheckedChangeListener
                }

                val clickedFilter = chip.tag.toString().removePrefix("state:")
                if (isChecked) {
                    // When a state chip is checked, uncheck all other state chips
                    Log.d(TAG, "State chip checked by user: $clickedFilter")
                    isUpdatingProgrammatically = true

                    for (i in 0 until chipGroup.childCount) {
                        val otherChip = chipGroup.getChildAt(i) as? Chip
                        if (otherChip != null &&
                            otherChip.tag.toString().startsWith("state:") &&
                            otherChip != chip) {
                            Log.d(TAG, "Unchecking other state chip: ${otherChip.tag}")
                            otherChip.isChecked = false
                        }
                    }

                    isUpdatingProgrammatically = false

                    // Notify the callback
                    Log.d(TAG, "Calling onStateFilterSelected: $clickedFilter")
                    onStateFilterSelected(clickedFilter)
                } else {
                    // Allow unchecking state chips (optional filter)
                    Log.d(TAG, "State chip unchecked by user, calling onStateFilterSelected: null")
                    onStateFilterSelected(null)
                }
            }
            chipGroup.addView(chip)
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
        Log.d(TAG, "updateMultiSelectFilterChips called: typeFilter=$selectedTypeFilter, stateFilter=$selectedStateFilter")

        // Set flag to prevent callbacks during programmatic updates
        isUpdatingProgrammatically = true
        Log.d(TAG, "Set isUpdatingProgrammatically = true")

        // Update chip checked states
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            val tag = chip.tag as? String ?: continue

            when {
                tag.startsWith("type:") -> {
                    val filterName = tag.removePrefix("type:")
                    val shouldBeChecked = filterName == selectedTypeFilter
                    Log.d(TAG, "Updating type chip: $tag, shouldBeChecked=$shouldBeChecked, currentlyChecked=${chip.isChecked}")
                    chip.isChecked = shouldBeChecked
                }
                tag.startsWith("state:") -> {
                    val filterName = tag.removePrefix("state:")
                    val shouldBeChecked = filterName == selectedStateFilter
                    Log.d(TAG, "Updating state chip: $tag, shouldBeChecked=$shouldBeChecked, currentlyChecked=${chip.isChecked}")
                    chip.isChecked = shouldBeChecked
                }
            }
        }

        // Reset flag after updates are complete
        isUpdatingProgrammatically = false
        Log.d(TAG, "Set isUpdatingProgrammatically = false")
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

