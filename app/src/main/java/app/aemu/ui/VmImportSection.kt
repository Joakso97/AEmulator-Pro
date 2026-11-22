
package app.aemu.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.*
import app.aemu.importer.Analyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VmImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit, apply: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var warning by remember { mutableStateOf<VmArchiveStorage.SettingsImport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    fun accept(source: VmArchiveStorage.SettingsImport) {
        apply(source.image.settings)
        message = ctx.getString(R.string.vm_import_settings_done)
    }
    val pickSettings = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val source = VmArchiveStorage.readSettings(ctx, uri)
                    source to source.differs(ctx, ImageStore.get(ctx, img.id) ?: img)
                } }
                setBusy(false)
                result.onSuccess { (source, differs) -> if (differs) warning = source else accept(source) }
                    .onFailure { message = ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) }
            }
        }
    }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickSettings.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_settings)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    warning?.let { source -> AlertDialog(
        onDismissRequest = { warning = null },
        title = { Text(stringResource(R.string.vm_import_settings_warning_title)) },
        text = { Text(stringResource(R.string.vm_import_settings_warning,
            "${source.image.brand} ${source.image.model} / ${source.image.release} / ${source.image.skin}",
            "${img.brand} ${img.model} / ${img.release} / ${img.skin}")) },
        dismissButton = { TextButton(onClick = { warning = null }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { warning = null; accept(source) }) { Text(stringResource(R.string.vm_import_settings)) } },
    ) }
}


@Composable
internal fun PartitionImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pendingPartition by remember { mutableStateOf<Uri?>(null) }
    var customMount by remember { mutableStateOf("") }
    fun startPartition(uri: Uri, kind: String) {
        val label = PartitionImport.label(kind)
        if (kind in PartitionImport.UNSUPPORTED) { message = ctx.getString(R.string.vm_import_partition_unsupported, label); return }
        setBusy(true)
        message = ctx.getString(R.string.vm_import_partition_working, label)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PartitionImport.install(ctx, img.id, uri, kind) } }
            setBusy(false)
            message = result.fold({
                when (kind) {
                    PartitionImport.BOOT -> ctx.getString(R.string.vm_import_boot_done)
                    PartitionImport.RECOVERY -> ctx.getString(R.string.vm_import_recovery_done)
                    else -> ctx.getString(R.string.vm_import_partition_done, label)
                }
            }, { ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) })
        }
    }
    val pickPartition = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) scope.launch {
            val kind = withContext(Dispatchers.IO) { runCatching { PartitionImport.detect(ctx, uri) } }
            kind.onSuccess { if (it != null) { startPartition(uri, it) } else { pendingPartition = uri } }
                .onFailure { message = ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) }
        }
    }
    Text(stringResource(R.string.vm_import_partition), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(stringResource(R.string.vm_import_partition_headline)) },
        supportingContent = { Text(message ?: stringResource(R.string.vm_import_partition_sub)) },
        trailingContent = {
            TextButton(enabled = !busy && !picking, onClick = { picking = true; pickPartition.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.vm_import_partition_button))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    pendingPartition?.let { uri ->
        val dir = remember(uri) { VmPaths(ctx, img.id).dir }
        AlertDialog(
            onDismissRequest = { pendingPartition = null },
            title = { Text(stringResource(R.string.vm_import_partition_which)) },
            text = {
                Column {
                    Text(stringResource(R.string.vm_import_partition_which_info))
                    for (kind in PartitionImport.CHOICES) {
                        val have = PartitionImport.present(dir, kind) && !PartitionImport.replaceable(kind)
                        val label = PartitionImport.label(kind)
                        TextButton(enabled = !have, modifier = Modifier.fillMaxWidth(), onClick = { pendingPartition = null; startPartition(uri, kind) }) {
                            Text(if (have) stringResource(R.string.vm_import_partition_have, label) else label)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customMount,
                            onValueChange = { customMount = it },
                            label = { Text(stringResource(R.string.vm_import_partition_custom)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = PartitionImport.validMount(customMount.trim()),
                            onClick = { val kind = customMount.trim().lowercase(); pendingPartition = null; startPartition(uri, kind) },
                        ) { Text(stringResource(R.string.vm_import_partition_custom_use)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingPartition = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}


@Composable
internal fun CscImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = VmPaths(ctx, img.id).root
    if (!remember(img.id) { CscImport.isSamsung(root) }) return
    var present by remember { mutableStateOf(CscImport.present(root)) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val pickCsc = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            message = ctx.getString(R.string.vm_import_csc_working)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { CscImport.install(ctx, img.id, uri) } }
                present = CscImport.present(root)
                setBusy(false)
                message = result.fold({ ctx.getString(R.string.vm_import_csc_done) },
                    { ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) })
            }
        }
    }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickCsc.launch(arrayOf("*/*"))
    }) { Text(stringResource(if (present) R.string.vm_import_csc_replace else R.string.vm_import_csc)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun ApkInstallSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf<String?>(null) }
    var installOk by remember { mutableStateOf(false) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var installVm by remember { mutableStateOf<GuestVm?>(null) }
    var installToken by remember { mutableStateOf(0) }

    fun cancelInstall() {
        installToken++
        installJob?.cancel()
        installVm?.guestRunner?.cancelActive()
        installJob = null
        installVm = null
        installing = false
        finished = null
        message = null
        setBusy(false)
    }

    val pickApk = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            installing = true
            finished = null
            message = null
            val token = ++installToken
            installJob = scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val stored = ImageStore.get(ctx, img.id) ?: img
                    val resolved = Analyzer.refresh(ctx, stored).let { it.effective(BinderProtocol.wants8(VmPaths(ctx, it.id))) }
                    val vm = GuestVm(ctx, resolved)
                    installVm = vm
                    runCatching {
                        val input = ctx.contentResolver.openInputStream(uri) ?: throw Exception("Cannot open APK")
                        val out = vm.installApkFrom(input)
                        TreeFixer(ctx, VmPaths(ctx, img.id), img) {}.seedOwners()
                        out
                    }
                }
                installVm = null
                installJob = null
                if (token != installToken) return@launch
                setBusy(false)
                installing = false
                installOk = result.getOrNull()?.contains("Success") == true
                finished = if (installOk) ctx.getString(R.string.m_install_apk_done)
                    else result.fold(
                        { out -> out.trim().lines().lastOrNull { it.isNotBlank() }?.take(200) ?: ctx.getString(R.string.m_failed) },
                        { ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) })
            }
        }
    }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
    }) { Text(stringResource(R.string.m_install_apk)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (installing || finished != null) AlertDialog(
        onDismissRequest = { if (installing) cancelInstall() else finished = null },
        title = { Text(stringResource(if (installing) R.string.m_install_apk_start else if (installOk) R.string.m_install_apk_done else R.string.m_install_apk_failed)) },
        text = {
            Column {
                Text(stringResource(if (installing) R.string.m_install_apk_start_desc else if (installOk) R.string.m_install_apk_done_desc else R.string.m_install_apk_failed_desc),
                    style = MaterialTheme.typography.bodyMedium)
                if (!installing && !installOk && !finished.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(finished.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                if (installing || installOk) {
                    Spacer(Modifier.height(12.dp))
                    if (installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (installing) cancelInstall() else finished = null }) {
                Text(stringResource(if (installing) R.string.cancel else R.string.ok))
            }
        }
    )
}
