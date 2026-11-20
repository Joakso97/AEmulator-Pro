

package app.aemu.ui

import android.text.format.Formatter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.GuestImage


@Composable
fun MachineCard(
    img: GuestImage,
    baseName: String?,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onSettings: () -> Unit,
    onRename: () -> Unit,
    onNewMachine: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: (() -> Unit)? = null,
    active: Boolean = false,
) {
    val ctx = LocalContext.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        onClick = onStart,
        interactionSource = press,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(
            Modifier.padding(8.dp).fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(img.skin, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 14.dp, end = 48.dp))
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.menu), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_machine)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onNewMachine() })
                    if (onMoveToFolder != null) DropdownMenuItem(text = { Text(stringResource(R.string.folder_move)) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                        onClick = { menu = false; onMoveToFolder() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.reset_data)) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                        onClick = { menu = false; onReset() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() })
                }
            }
            Column(Modifier.align(Alignment.Center).padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(img.release.substringBefore(' ').take(5), style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("API ${img.api}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            FilledIconButton(
                onClick = onStart,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp),
            ) { Icon(Icons.Rounded.PlayArrow, stringResource(if (active) R.string.open_vm else R.string.start)) }
            
            if (active) Box(Modifier.align(Alignment.TopStart).padding(12.dp).size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Text(img.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    img.settings.let { "${it.width}×${it.height}" },
                    Formatter.formatShortFileSize(ctx, img.sizeBytes),
                    if (img.lastBootMs > 0) "${img.lastBootMs / 1000} s" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
            if (baseName != null) Text(stringResource(R.string.container_of, baseName), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
fun HomeFab(expanded: Boolean, onToggle: (Boolean) -> Unit, onImport: () -> Unit, onLibrary: () -> Unit, onNewFolder: () -> Unit) {
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            FloatingActionButton(onClick = { onToggle(!expanded) }) {
                Icon(if (expanded) Icons.Rounded.Close else Icons.Rounded.Add, stringResource(R.string.add_firmware))
            }
        },
    ) {
        FloatingActionButtonMenuItem(onClick = { onToggle(false); onImport() }, icon = { Icon(Icons.Rounded.FileOpen, null) },
            text = { Text(stringResource(R.string.add_import)) })
        FloatingActionButtonMenuItem(onClick = { onToggle(false); onLibrary() }, icon = { Icon(Icons.Rounded.Inventory2, null) },
            text = { Text(stringResource(R.string.lib_title)) })
        FloatingActionButtonMenuItem(onClick = { onToggle(false); onNewFolder() }, icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
            text = { Text(stringResource(R.string.folder_new)) })
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    firmwares: List<GuestImage>,
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onImportFolder: (java.io.File) -> Unit,
    onNewMachine: (GuestImage) -> Unit,
) {
    var files by remember { mutableStateOf(emptyList<java.io.File>()) }
    DisposableEffect(Unit) {
        val t = Thread { files = app.aemu.core.FirmwareFolder.list() }.apply { start() }
        onDispose { t.interrupt() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.vmos_add_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.add_container_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            
            Surface(
                onClick = onImportFile,
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().shadow(
                    2.dp, MaterialTheme.shapes.extraLarge,
                    ambientColor = Color.Black.copy(alpha = 0.03f),
                    spotColor = Color.Black.copy(alpha = 0.03f),
                )
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.FileOpen,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.add_import),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.add_import_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            
            Text(
                stringResource(R.string.vmos_tab_roms),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (firmwares.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.lib_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                for (fw in firmwares) {
                    Surface(
                        onClick = { onNewMachine(fw) },
                        shape = MaterialTheme.shapes.large,
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth().shadow(
                            2.dp, MaterialTheme.shapes.large,
                            ambientColor = Color.Black.copy(alpha = 0.03f),
                            spotColor = Color.Black.copy(alpha = 0.03f),
                        )
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    fw.release.take(4),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    fw.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${fw.displayVersion} · ${fw.skin}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onNewMachine(fw) },
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.container_create))
                            }
                        }
                    }
                }
            }

            
            if (files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.fw_folder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                for (f in files.take(12)) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.secondary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${f.length() / 1_048_576} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(onClick = { onImportFolder(f) }) {
                                Text(stringResource(R.string.fw_folder_import))
                            }
                        }
                    }
                }
            }
        }
    }
}
