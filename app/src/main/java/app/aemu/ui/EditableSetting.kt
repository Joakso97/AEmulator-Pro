package app.aemu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R

@Composable
internal fun EditableSetting(
    label: String,
    value: String,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    sanitize: (String) -> String = { it },
    dialogExtra: @Composable (String, (String) -> Unit) -> Unit = { _, _ -> },
    onSave: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value.ifBlank { placeholder ?: "—" }) },
        trailingContent = { TextButton(onClick = { open = true }) { Text(stringResource(R.string.th_edit)) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable { open = true },
    )
    if (open) {
        var temp by remember(open) { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = temp,
                        onValueChange = { temp = sanitize(it) },
                        label = { Text(label) },
                        placeholder = placeholder?.let { text -> { Text(text) } },
                        supportingText = supportingText?.let { text -> { Text(text) } },
                        singleLine = singleLine,
                        keyboardOptions = keyboardOptions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dialogExtra(temp) { temp = sanitize(it) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = { onSave(temp); open = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
