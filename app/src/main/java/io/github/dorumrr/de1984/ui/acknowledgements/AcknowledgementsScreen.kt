package io.github.dorumrr.de1984.ui.acknowledgements

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.dorumrr.de1984.utils.Constants

@Composable
fun AcknowledgementsScreen(
    onNavigateBack: () -> Unit
) {
    val libraries = listOf(
        Library("Jetpack Compose", "2024.02.00"),
        Library("Material Design 3", "Latest"),
        Library("AndroidX Navigation", "2.7.6"),
        Library("Room Database", "2.6.1"),
        Library("Kotlin", "1.9.x"),
        Library("Kotlin Coroutines", "1.7.3")
    )

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Constants.UI.SPACING_SMALL,
                    vertical = Constants.UI.SPACING_SMALL
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Text(
                text = "Acknowledgements",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = Constants.UI.SPACING_TINY)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Constants.UI.SPACING_STANDARD,
                end = Constants.UI.SPACING_STANDARD,
                top = Constants.UI.SPACING_TINY,
                bottom = Constants.UI.SPACING_STANDARD
            )
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Constants.UI.SPACING_STANDARD)
                    ) {
                        libraries.forEachIndexed { index, library ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Constants.UI.SPACING_SMALL),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = library.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = "v${library.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (index < libraries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = Constants.UI.SPACING_TINY),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class Library(
    val name: String,
    val version: String
)

