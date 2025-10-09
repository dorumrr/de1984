package io.github.dorumrr.de1984.ui.common

import android.util.Log
import io.github.dorumrr.de1984.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.platform.LocalContext
import io.github.dorumrr.de1984.utils.PackageUtils

import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.dorumrr.de1984.utils.Constants

@Composable
fun De1984TopBar(
    title: String,
    modifier: Modifier = Modifier,
    sectionIcon: ImageVector? = null,
    showSwitch: Boolean = false,
    switchChecked: Boolean = false,
    onSwitchChange: (Boolean) -> Unit = {}
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(statusBarPadding)
                .padding(horizontal = Constants.UI.SPACING_STANDARD, vertical = Constants.UI.SPACING_MEDIUM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.de1984_logo),
                contentDescription = Constants.App.LOGO_DESCRIPTION,
                modifier = Modifier.size(Constants.UI.ICON_SIZE_MEDIUM),
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))
            Text(
                text = Constants.App.NAME,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))

                if (showSwitch) {
                    CustomTextSwitch(
                        checked = switchChecked,
                        onCheckedChange = onSwitchChange
                    )
                } else {
                    sectionIcon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = "$title section icon",
                            modifier = Modifier.size(Constants.UI.ICON_SIZE_SMALL),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilterChipsRow(
    chips: List<FilterChipData>,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Constants.UI.SPACING_SMALL),
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
        items(chips) { chip ->
            FilterChip(
                onClick = chip.onClick,
                label = {
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                selected = chip.selected,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = chip.selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.outline,
                    borderWidth = Constants.UI.BORDER_WIDTH_THIN,
                    selectedBorderWidth = Constants.UI.BORDER_WIDTH_THIN
                )
            )
        }
    }
}

data class FilterChipData(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit
)

@Composable
fun StatusDot(
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(Constants.UI.STATUS_DOT_SIZE)
            .clip(CircleShape)
            .background(
                if (isEnabled) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.outline
            )
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    hasRoundedCorners: Boolean = true,
    content: @Composable () -> Unit
) {

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Constants.UI.ELEVATION_CARD),
        shape = if (hasRoundedCorners) {
            RoundedCornerShape(Constants.UI.CORNER_RADIUS_STANDARD)
        } else {
            RoundedCornerShape(0.dp)
        }
    ) {
        content()
    }
}

@Composable
fun PackageIcon(
    packageName: String,
    fallbackIcon: String,
    isEnabled: Boolean,
    showRealIcons: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var realIcon by remember(packageName, showRealIcons) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(packageName, showRealIcons) {
        if (showRealIcons) {
            realIcon = PackageUtils.getPackageIcon(context, packageName)
        } else {
            realIcon = null
        }
    }

    val enabledColor = if (isEnabled) {
        Color.Unspecified
    } else {
        Color.Gray.copy(alpha = Constants.UI.ALPHA_DISABLED)
    }

    if (showRealIcons && realIcon != null) {
        Image(
            bitmap = realIcon!!.toBitmap().asImageBitmap(),
            contentDescription = "App icon for $packageName",
            modifier = modifier,
            alpha = if (isEnabled) Constants.UI.ALPHA_FULL else Constants.UI.ALPHA_DISABLED
        )
    } else {
        Text(
            text = fallbackIcon,
            style = MaterialTheme.typography.headlineSmall,
            color = enabledColor,
            modifier = modifier
        )
    }
}

@Composable
fun PackageItemRow(
    packageName: String,
    icon: String,
    name: String,
    details: String,
    isEnabled: Boolean,
    showRealIcons: Boolean = true,
    actionText: String? = null,
    secondaryText: String? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(Constants.UI.SPACING_STANDARD),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PackageIcon(
            packageName = packageName,
            fallbackIcon = icon,
            isEnabled = isEnabled,
            showRealIcons = showRealIcons,
            modifier = Modifier.size(Constants.UI.ICON_SIZE_LARGE)
        )

        Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(Constants.UI.SPACING_SMALL))
                StatusDot(isEnabled = isEnabled)
            }

            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (actionText != null || secondaryText != null) {
            Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))
            Column(
                horizontalAlignment = Alignment.End
            ) {
                actionText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
                secondaryText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

data class ActionItem(
    val title: String,
    val description: String? = null,
    val icon: ImageVector? = null,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionBottomSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    icon: String? = null,
    packageName: String? = null,
    showRealIcons: Boolean = true,
    actions: List<ActionItem>,
    modifier: Modifier = Modifier
) {
    if (isVisible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            val density = LocalDensity.current
            var offsetY by remember { mutableStateOf(0f) }
            val coroutineScope = rememberCoroutineScope()

            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onDismiss()
                        }
                )

                Surface(
                    modifier = modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .offset(y = with(density) { offsetY.toDp() })
                        .clip(RoundedCornerShape(topStart = Constants.UI.BOTTOM_SHEET_CORNER_RADIUS, topEnd = Constants.UI.BOTTOM_SHEET_CORNER_RADIUS))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    coroutineScope.launch {
                                        if (offsetY > with(density) { Constants.UI.BOTTOM_SHEET_DISMISS_THRESHOLD.toPx() }) {
                                            onDismiss()
                                        } else {
                                            offsetY = 0f
                                        }
                                    }
                                }
                            ) { _, dragAmount ->
                                val newOffset = offsetY + dragAmount.y
                                if (newOffset >= 0) {
                                    offsetY = newOffset
                                }
                            }
                        },
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = Constants.UI.ELEVATION_SURFACE
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Constants.UI.SPACING_LARGE, vertical = Constants.UI.SPACING_STANDARD)
                ) {
                    Box(
                        modifier = Modifier
                            .width(Constants.UI.DRAG_HANDLE_WIDTH)
                            .height(Constants.UI.DRAG_HANDLE_HEIGHT)
                            .clip(RoundedCornerShape(Constants.UI.SPACING_TINY / 2))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            .align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (packageName != null && icon != null) {
                            PackageIcon(
                                packageName = packageName,
                                fallbackIcon = icon,
                                isEnabled = true,
                                showRealIcons = showRealIcons,
                                modifier = Modifier.size(Constants.UI.ICON_SIZE_EXTRA_LARGE)
                            )
                            Spacer(modifier = Modifier.width(Constants.UI.SPACING_STANDARD))
                        } else {
                            icon?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.headlineMedium,
                                    modifier = Modifier.size(Constants.UI.ICON_SIZE_EXTRA_LARGE)
                                )
                                Spacer(modifier = Modifier.width(Constants.UI.SPACING_STANDARD))
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

                    actions.forEach { action ->
                        ActionButton(
                            title = action.title,
                            description = action.description,
                            icon = action.icon,
                            isDestructive = action.isDestructive,
                            onClick = {
                                action.onClick()
                            }
                        )
                        Spacer(modifier = Modifier.height(Constants.UI.SPACING_SMALL))
                    }

                    Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))
                }
            }
            }
        }
    }
}

@Composable
private fun ActionButton(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val buttonColors = if (isDestructive) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = buttonColors,
        shape = RoundedCornerShape(Constants.UI.CORNER_RADIUS_STANDARD)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Constants.UI.SPACING_SMALL),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(Constants.UI.ICON_SIZE_SMALL)
                )
                Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start
                )
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

@Composable
fun CustomTextSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (checked) Color(0xFF4CAF50) else Color(0xFFF44336)
    val text = if (checked) "ON" else "OFF"

    Box(
        modifier = modifier
            .height(Constants.UI.ICON_SIZE_MEDIUM)
            .width(Constants.UI.TOGGLE_SWITCH_WIDTH)
            .clip(RoundedCornerShape(Constants.UI.SPACING_STANDARD))
            .background(backgroundColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onCheckedChange(!checked)
            }
            .padding(horizontal = Constants.UI.SPACING_EXTRA_TINY, vertical = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}
