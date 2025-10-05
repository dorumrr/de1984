package io.github.dorumrr.de1984.ui.permissions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.dorumrr.de1984.utils.Constants

@Composable
fun RootInstructionsDialog(
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Constants.UI.SPACING_STANDARD),
            elevation = CardDefaults.cardElevation(defaultElevation = Constants.UI.SPACING_SMALL)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Constants.UI.SPACING_LARGE)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(Constants.UI.ICON_SIZE_MEDIUM),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.width(Constants.UI.SPACING_MEDIUM))

                    Text(
                        text = "Root Access Required",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = Constants.RootAccess.SETUP_INSTRUCTIONS,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start
                    )
                }

                Spacer(modifier = Modifier.height(Constants.UI.SPACING_LARGE))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Got it")
                }
            }
        }
    }
}
