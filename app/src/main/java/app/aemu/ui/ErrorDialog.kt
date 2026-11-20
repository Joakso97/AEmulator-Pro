package app.aemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aemu.R


@Composable
fun ErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    onRetry: (() -> Unit)? = null,
    logText: String? = null,
    subtitle: String? = null,
) {
    var showLog by remember { mutableStateOf(false) }
    val logScroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                SelectionContainer {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start
                    )
                }
                if (!logText.isNullOrEmpty() && showLog) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(logScroll)
                                .padding(10.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = logText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            } else {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = when {
            onRetry != null -> ({
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            })
            !logText.isNullOrEmpty() -> ({
                TextButton(onClick = { showLog = !showLog }) {
                    Text(stringResource(if (showLog) R.string.close else R.string.log))
                }
            })
            else -> null
        }
    )
}
