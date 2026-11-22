
package app.aemu.ui

import app.aemu.core.userMessage
import app.aemu.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aemu.core.CdmaMode
import app.aemu.core.CpuInfo
import app.aemu.core.Engine
import app.aemu.core.GuestImage
import app.aemu.core.GyroLock
import app.aemu.core.MemoryMode
import app.aemu.core.NetworkType
import app.aemu.core.RamChoice
import kotlin.math.roundToInt
import app.aemu.core.VmSettings
import app.aemu.core.WifiEmu
import app.aemu.core.VmStorageLease
import app.aemu.core.VmDataReset
import app.aemu.core.ImageStore
import app.aemu.core.ResolutionPreset
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RESOLUTIONS = ResolutionPreset.entries



private const val RAM_CUSTOM = -2
private val RAM_STEPS = listOf(RamChoice.AUTO) + RamChoice.PRESETS_MB + RamChoice.HOST + RAM_CUSTOM

private fun ramText(mb: Int): String =
    if (mb < 1024) "$mb MB"
    else if (mb % 1024 == 0) "${mb / 1024} GB"
    else String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0) + " (" + mb + " MB)"

@Composable
fun SettingsSheet(img: GuestImage, onDismiss: () -> Unit, onSave: (VmSettings, String) -> Unit) {
    var s by remember { mutableStateOf(img.settings) }
    var name by remember { mutableStateOf(img.name) }
    val ctx = LocalContext.current
    
    val wifiOffered = WifiEmu.offered(img.copy(settings = s))
    
    val fwLteOnCdma = remember(img.id) { CdmaMode.supported(img.api) && CdmaMode.firmwareLteOnCdma(app.aemu.core.VmPaths(ctx, img.id)) }
    val cdmaOn = CdmaMode.supported(img.api) && (s.cdma ?: fwLteOnCdma)
    val scope = rememberCoroutineScope()
    var resetConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (!resetting) onDismiss() }, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            Text(img.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_screen), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (s.legacyEngine && img.api < 14) {
                Text(stringResource(R.string.vs_fixed_2x), style = MaterialTheme.typography.bodyMedium)
            } else {
                val firmwareDensity = remember(img.id) { ResolutionPreset.buildPropDensity(app.aemu.core.VmPaths(ctx, img.id).root) }
                var presetMenu by remember { mutableStateOf(false) }
                val current = RESOLUTIONS.firstOrNull { it.selected(s) }
                val hostLabel = stringResource(R.string.vs_resolution_host)
                val customLabel = stringResource(R.string.vs_resolution_custom)
                fun name(r: ResolutionPreset) = if (r.isHost) hostLabel else r.label
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(current?.let(::name) ?: customLabel, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("▾")
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        RESOLUTIONS.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(name(r), fontWeight = if (r == current) FontWeight.Bold else null) },
                                onClick = { s = r.apply(s, firmwareDensity); presetMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumField(stringResource(R.string.vs_width), s.width, Modifier.weight(1f)) { s = s.copy(width = it.coerceAtLeast(2) and 0x7ffffffe, hostResolution = false) }
                    NumField(stringResource(R.string.vs_height), s.height, Modifier.weight(1f)) { s = s.copy(height = it.coerceAtLeast(2) and 0x7ffffffe, hostResolution = false) }
                    NumField(stringResource(R.string.vs_dpi), s.density, Modifier.weight(1f)) { s = s.copy(density = it.coerceIn(96, 640), hostResolution = false) }
                }
                Spacer(Modifier.height(8.dp))
                NumField(stringResource(R.string.vs_fb_hz), s.fbHz, Modifier.fillMaxWidth()) { s = s.copy(fbHz = it.coerceIn(10, 120)) }
                Text(stringResource(R.string.vs_screen_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (s.hostResolution) Text(stringResource(R.string.vs_host_resolution_sub),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.vs_perf), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), s.gpu) { s = s.copy(gpu = it) }
            if (img.api >= 14) Toggle(stringResource(R.string.vs_hwui), stringResource(R.string.vs_hwui_sub), s.hwui) { s = s.copy(hwui = it) }
            Toggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), s.jit) { s = s.copy(jit = it) }
            if (img.api >= 21) Toggle(stringResource(R.string.vs_fulldex), stringResource(R.string.vs_fulldex_sub), s.fullDexopt) { s = s.copy(fullDexopt = it) }
            if (img.api < 14) Toggle(stringResource(R.string.vs_legacy), stringResource(R.string.vs_legacy_sub), s.legacyEngine) { s = s.copy(legacyEngine = it) }
            if (img.api >= 26 && img.abi.startsWith("armeabi")) Toggle(
                "Translate on the native engine",
                "32-bit firmware of Android 8+ (Wear OS 9, ...): run it on the arm64 engine through qemu-arm. The universal engine cannot start it (binder protocol 8). With the GPU option on, apps' OpenGL ES drawing goes to the phone's GPU (8.0+). Takes effect on the next start.",
                s.translate32
            ) { s = s.copy(translate32 = it) }
            val has32 = CpuInfo.hasAArch32
            Toggle(
                stringResource(R.string.vs_native32),
                if (has32) stringResource(R.string.vs_native32_sub) else stringResource(R.string.vs_native32_unsupported),
                s.native32 && has32
            ) { if (has32) s = s.copy(native32 = it) }
            Toggle(stringResource(R.string.vs_thermal), stringResource(R.string.vs_thermal_sub), s.thermalPassthrough) { s = s.copy(thermalPassthrough = it) }
            
            var ramCustom by remember { mutableStateOf(s.ramMb > 0 && s.ramMb !in RamChoice.PRESETS_MB) }
            var ramBox by remember { mutableStateOf(if (s.ramMb > 0 && s.ramMb !in RamChoice.PRESETS_MB) s.ramMb.toString() else "") }
            val hostMb = remember { RamChoice.hostMb() }
            val ramIdx = if (ramCustom) RAM_STEPS.lastIndex else RAM_STEPS.indexOf(s.ramMb).coerceAtLeast(0)
            ListItem(
                headlineContent = { Text(stringResource(R.string.vs_ram)) },
                supportingContent = {
                    Column {
                        Text(when {
                            ramCustom -> stringResource(R.string.vs_ram_custom)
                            s.ramMb == RamChoice.AUTO -> stringResource(R.string.vs_ram_auto)
                            s.ramMb == RamChoice.HOST -> stringResource(R.string.vs_ram_host) + " (" + ramText(hostMb) + ")"
                            else -> ramText(s.ramMb)
                        })
                        Slider(value = ramIdx.toFloat(), onValueChange = {
                            val step = RAM_STEPS[it.roundToInt().coerceIn(0, RAM_STEPS.lastIndex)]
                            if (step == RAM_CUSTOM) {
                                if (!ramCustom) {
                                    
                                    val seed = when { s.ramMb > 0 -> s.ramMb; s.ramMb == RamChoice.HOST -> hostMb; else -> 2048 }
                                    ramCustom = true; ramBox = seed.toString(); s = s.copy(ramMb = seed)
                                }
                            } else { ramCustom = false; s = s.copy(ramMb = step) }
                        }, valueRange = 0f..RAM_STEPS.lastIndex.toFloat(), steps = RAM_STEPS.size - 2)
                        if (ramCustom) OutlinedTextField(
                            value = ramBox,
                            onValueChange = { v ->
                                val digits = v.filter { c -> c.isDigit() }.take(6)
                                ramBox = digits
                                
                                digits.toIntOrNull()?.takeIf { n -> n >= RamChoice.CUSTOM_MIN_MB }?.let { n -> s = s.copy(ramMb = n) }
                            },
                            label = { Text(stringResource(R.string.vs_ram_custom_hint)) },
                            suffix = { Text("MB") },
                            singleLine = true,
                            isError = (ramBox.toIntOrNull() ?: 0) < RamChoice.CUSTOM_MIN_MB,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Toggle(stringResource(R.string.vs_lowram), stringResource(R.string.vs_lowram_sub), s.lowRam) { s = s.copy(lowRam = it) }
            MemoryModePicker(MemoryMode.parse(s.memoryMode)) { s = s.copy(memoryMode = it.id) }
            Toggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), s.netProxy) { s = s.copy(netProxy = it) }
            Toggle(stringResource(R.string.vs_netdebug), stringResource(R.string.vs_netdebug_sub), s.netDebug) { s = s.copy(netDebug = it) }
            Toggle(stringResource(R.string.vs_vibration), stringResource(R.string.vs_vibration_sub), s.vibration) { s = s.copy(vibration = it) }
            Toggle(stringResource(R.string.vs_host_battery), stringResource(R.string.vs_host_battery_sub), s.hostBattery) { s = s.copy(hostBattery = it) }
            if (img.api in 9..25) Toggle(stringResource(R.string.vs_no_sensors), stringResource(R.string.vs_no_sensors_sub), img.sensorsOff(s)) { s = s.copy(disableSensors = it) }
            if (img.api in 9..25 && !img.sensorsOff(s)) Toggle(stringResource(R.string.vs_motion), stringResource(R.string.vs_motion_sub), s.motionSensors) { s = s.copy(motionSensors = it) }
            if (img.api in 9..25 && !img.sensorsOff(s) && s.motionSensors) GyroLockPicker(s.gyroLock) { s = s.copy(gyroLock = it) }
            if (img.api >= 16) Toggle(stringResource(R.string.vs_skip_setup), stringResource(R.string.vs_skip_setup_sub), s.skipSetupWizard) { s = s.copy(skipSetupWizard = it) }
            if (img.api in 9..25) Toggle(stringResource(R.string.vs_disable_google), stringResource(R.string.vs_disable_google_sub), s.disableGoogleApps) { s = s.copy(disableGoogleApps = it) }
            if (img.api in 9..25) {
                Toggle(stringResource(R.string.vs_bluetooth), stringResource(R.string.vs_bluetooth_sub), s.bluetooth) { s = s.copy(bluetooth = it) }
            }
            if (img.api >= 26) Toggle(stringResource(R.string.vs_sf_tombstone), stringResource(R.string.vs_sf_tombstone_sub), s.sfTombstone) { s = s.copy(sfTombstone = it) }
            if (img.api >= 34) Toggle(stringResource(R.string.vs_art_boot_image), stringResource(R.string.vs_art_boot_image_sub), s.useArtBootImage) { s = s.copy(useArtBootImage = it) }
            if (img.api >= 34) Toggle(stringResource(R.string.vs_no_watchdog), stringResource(R.string.vs_no_watchdog_sub), s.disableWatchdog) { s = s.copy(disableWatchdog = it) }
            if (img.api in 29..33) Toggle(stringResource(R.string.vs_skip_perm_wait), stringResource(R.string.vs_skip_perm_wait_sub), s.skipPermissionWait) { s = s.copy(skipPermissionWait = it) }
            if (wifiOffered) {
                Toggle(stringResource(R.string.vs_wifi), stringResource(R.string.vs_wifi_sub), s.wifi) { s = s.copy(wifi = it) }
                if (s.wifi) WifiFields(s) { s = it }
            }
            if (img.api in 14..25) {
                Toggle(stringResource(R.string.vs_camera), stringResource(R.string.vs_camera_sub), s.camera) { s = s.copy(camera = it) }
            }
            if (img.api in 14..25) {
                Toggle(stringResource(R.string.vs_microphone), stringResource(R.string.vs_microphone_sub), s.microphone) { s = s.copy(microphone = it) }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_radio_title), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_radio), stringResource(R.string.vs_radio_sub), s.radio) { s = s.copy(radio = it) }
            if (s.radio && CdmaMode.supported(img.api)) CdmaToggle(s, fwLteOnCdma) { s = it }
            if (s.radio) NetworkTypePicker(s.networkType, img.api, cdmaOn, cdmaOn && fwLteOnCdma) { s = s.copy(networkType = it) }
            if (s.radio) SimSection(img, s) { s = it }

            var basebandDialog by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.vs_baseband)) },
                supportingContent = { Text(s.baseband.ifBlank { VmSettings.DEFAULT_BASEBAND }) },
                trailingContent = {
                    TextButton(onClick = { basebandDialog = true }) { Text(stringResource(R.string.th_edit)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable { basebandDialog = true },
            )
            if (basebandDialog) {
                var tempBaseband by remember { mutableStateOf(s.baseband.ifBlank { VmSettings.DEFAULT_BASEBAND }) }
                AlertDialog(
                    onDismissRequest = { basebandDialog = false },
                    title = { Text(stringResource(R.string.vs_baseband)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tempBaseband,
                                onValueChange = { tempBaseband = VmSettings.cleanBaseband(it) },
                                label = { Text(stringResource(R.string.vs_baseband)) },
                                placeholder = { Text(VmSettings.DEFAULT_BASEBAND) },
                                supportingText = { Text(stringResource(R.string.vs_baseband_sub)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = { basebandDialog = false }) { Text(stringResource(R.string.cancel)) } },
                    confirmButton = {
                        TextButton(onClick = {
                            s = s.copy(baseband = tempBaseband.ifBlank { VmSettings.DEFAULT_BASEBAND })
                            basebandDialog = false
                        }) { Text(stringResource(R.string.ok)) }
                    }
                )
            }
            val kernelPreview = stringResource(R.string.vs_kernel_preview) + " " + VmSettings.procVersion(s).trim()
            val previewExtra: @Composable (String, (String) -> Unit) -> Unit = { _, _ ->
                Text(kernelPreview, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }
            EditableSetting(
                label = stringResource(R.string.vs_kernel),
                value = s.kernel,
                placeholder = VmSettings.DEFAULT_KERNEL,
                supportingText = stringResource(R.string.vs_kernel_sub),
                sanitize = { VmSettings.cleanKernel(it) },
            ) { s = s.copy(kernel = it) }
            EditableSetting(stringResource(R.string.vs_kernel_branch), s.kernelBranch, "master",
                sanitize = { VmSettings.cleanToken(it, VmSettings.TOKEN_MAX) }) { s = s.copy(kernelBranch = it) }
            EditableSetting(stringResource(R.string.vs_kernel_commit), s.kernelCommit, "1a2b3c4d",
                sanitize = { VmSettings.cleanToken(it, 40) }) { s = s.copy(kernelCommit = it) }
            EditableSetting(stringResource(R.string.vs_kernel_user), s.kernelUser, "aemu",
                sanitize = { VmSettings.cleanToken(it, VmSettings.TOKEN_MAX) }) { s = s.copy(kernelUser = it) }
            EditableSetting(stringResource(R.string.vs_kernel_host), s.kernelHost, "aemu",
                sanitize = { VmSettings.cleanToken(it, 64) }) { s = s.copy(kernelHost = it) }
            EditableSetting(stringResource(R.string.vs_kernel_number), s.kernelBuild, "1",
                sanitize = { it.filter { c -> c in '0'..'9' }.take(6) }) { s = s.copy(kernelBuild = it) }
            EditableSetting(stringResource(R.string.vs_kernel_flags), s.kernelFlags, "SMP PREEMPT",
                sanitize = { VmSettings.cleanText(it, 40) }) { s = s.copy(kernelFlags = it) }
            EditableSetting(stringResource(R.string.vs_kernel_compiler), s.kernelCompiler, "gcc version 4.6",
                sanitize = { VmSettings.cleanCompiler(it) }) { s = s.copy(kernelCompiler = it) }
            EditableSetting(stringResource(R.string.vs_kernel_date), s.kernelDate, "Thu Jan 1 00:00:00 UTC 2026",
                sanitize = { VmSettings.cleanText(it, 40) }, dialogExtra = previewExtra) { s = s.copy(kernelDate = it) }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_device_id), style = MaterialTheme.typography.titleMedium)
            EditableSetting(
                label = stringResource(R.string.vs_serial),
                value = s.serial,
                placeholder = VmSettings.DEFAULT_SERIAL,
                supportingText = stringResource(R.string.vs_serial_sub),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                sanitize = { VmSettings.cleanSerial(it) },
                dialogExtra = { _, update ->
                    TextButton(onClick = { update(VmSettings.randomSerial()) }) { Text(stringResource(R.string.vs_random)) }
                },
            ) { s = s.copy(serial = it) }
            Spacer(Modifier.height(4.dp))
            EditableSetting(stringResource(R.string.vs_spoof_soc), s.spoofSoc,
                supportingText = stringResource(R.string.vs_spoof_soc_sub),
                sanitize = { VmSettings.cleanToken(it, 64) }) { s = s.copy(spoofSoc = it) }
            EditableSetting(stringResource(R.string.vs_spoof_gpu), s.spoofGpu,
                supportingText = stringResource(R.string.vs_spoof_gpu_sub),
                sanitize = { VmSettings.cleanToken(it, 64) }) { s = s.copy(spoofGpu = it) }
            EditableSetting(stringResource(R.string.vs_spoof_storage), s.spoofStorageGb,
                supportingText = stringResource(R.string.vs_spoof_storage_sub),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                sanitize = { it.filter(Char::isDigit).take(4) }) { s = s.copy(spoofStorageGb = it) }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_advanced), style = MaterialTheme.typography.titleMedium)
            EditableSetting(
                label = stringResource(R.string.vs_qemu_args),
                value = s.qemuArgs,
                supportingText = stringResource(R.string.vs_qemu_args_sub),
                singleLine = false,
            ) { s = s.copy(qemuArgs = it) }
            EditableSetting(
                label = stringResource(R.string.vs_strace),
                value = s.straceServices,
                placeholder = "mediaserver,zygote",
                supportingText = stringResource(R.string.vs_strace_sub),
            ) { s = s.copy(straceServices = it) }
            HardwarePicker(s.hardware) { s = s.copy(hardware = it) }

            Spacer(Modifier.height(8.dp))
            CscImportSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            OtaSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            PartitionImportSection(img, resetting) { resetting = it }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.vs_controls), style = MaterialTheme.typography.titleMedium)
            Toggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), s.showNavBar) { s = s.copy(showNavBar = it) }
            Toggle(stringResource(R.string.vs_guest_nav), stringResource(R.string.vs_guest_nav_sub), s.guestNavBar) { s = s.copy(guestNavBar = it) }
            Toggle(stringResource(R.string.vs_hide_menu), stringResource(R.string.vs_hide_menu_sub), s.hideMenuButton) { s = s.copy(hideMenuButton = it) }
            NavigationSettings(s, app.aemu.core.LegacyFirmware.isPreRelease(img.release)) { s = it }
            Toggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), s.keepScreenOn) { s = s.copy(keepScreenOn = it) }
            Toggle(stringResource(R.string.as_led), stringResource(R.string.as_led_sub), s.showLed) { s = s.copy(showLed = it) }
            Toggle(stringResource(R.string.vs_single), stringResource(R.string.vs_single_sub), s.mtMode == 4) { s = s.copy(mtMode = if (it) 4 else 0) }

            Spacer(Modifier.height(16.dp))
            VmExportSection(img, s, resetting) { resetting = it }
            VmImportSection(img, resetting, { resetting = it }) { s = it }
            ApkInstallSection(img, resetting, { resetting = it })
            VmFilesSection(img, resetting)
            TextButton(enabled = !resetting, onClick = { resetConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vm_reset_data))
            }
            resetMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            
            val wifiOk = !(s.wifi && wifiOffered) || (WifiEmu.cleanSsid(s.wifiSsid).isNotEmpty() && WifiEmu.passwordValid(s.wifiPassword))
            Button(enabled = !resetting && wifiOk, onClick = { onSave(s, name) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (resetConfirm) AlertDialog(
        onDismissRequest = { resetConfirm = false },
        title = { Text(stringResource(R.string.vm_reset_data)) },
        text = { Text(stringResource(R.string.vm_reset_warning)) },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            resetConfirm = false
            resetting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        VmStorageLease(ctx.filesDir).use { lease ->
                            lease.acquire()
                            app.aemu.core.GuestStorageWriters.requireIdle(ctx)
                            VmDataReset.reset(ImageStore.imagesDir(ctx), img.id) { file ->
                                val stat = android.system.Os.lstat(file.path)
                                String.format(java.util.Locale.ROOT, "%016x %016x", stat.st_dev, stat.st_ino)
                            }
                        }
                    }
                }
                resetting = false
                resetMessage = if (result.isSuccess) ctx.getString(R.string.vm_reset_done)
                    else ctx.getString(R.string.vm_reset_failed)
            }
        }) { Text(stringResource(R.string.vm_reset_data)) } },
    )
}

@Composable
internal fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(checked = on, onCheckedChange = set) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}


@Composable
private fun WifiFields(s: VmSettings, set: (VmSettings) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value = s.wifiSsid, onValueChange = { set(s.copy(wifiSsid = WifiEmu.cleanSsid(it))) },
        label = { Text(stringResource(R.string.vs_wifi_ssid)) }, singleLine = true,
        isError = s.wifiSsid.isEmpty(),
        supportingText = { Text(stringResource(if (s.wifiSsid.isEmpty()) R.string.vs_wifi_ssid_error else R.string.vs_wifi_ssid_sub)) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    OutlinedTextField(value = s.wifiPassword, onValueChange = { set(s.copy(wifiPassword = WifiEmu.cleanPassword(it))) },
        label = { Text(stringResource(R.string.vs_wifi_password)) }, singleLine = true,
        isError = !WifiEmu.passwordValid(s.wifiPassword),
        supportingText = { Text(stringResource(if (WifiEmu.passwordValid(s.wifiPassword)) R.string.vs_wifi_password_sub else R.string.vs_wifi_password_error)) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(stringResource(if (show) R.string.vs_wifi_hide else R.string.vs_wifi_show)) } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    var open by remember { mutableStateOf(false) }
    fun bars(n: Int) = "▂▄▆█".take(n) + "·".repeat(WifiEmu.MAX_BARS - n)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_wifi_signal)) },
        supportingContent = { Text(stringResource(R.string.vs_wifi_signal_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(bars(s.wifiSignal)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (n in WifiEmu.MAX_BARS downTo 1) DropdownMenuItem(text = { Text(bars(n)) }, onClick = { open = false; set(s.copy(wifiSignal = n)) })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    Toggle(stringResource(R.string.vs_wifi_neighbors), stringResource(R.string.vs_wifi_neighbors_sub), s.wifiNeighbors) { set(s.copy(wifiNeighbors = it)) }
}


@Composable
private fun MemoryModePicker(mode: MemoryMode, set: (MemoryMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(m: MemoryMode) = when (m) {
        MemoryMode.LIGHT -> R.string.vs_memory_light
        MemoryMode.MEDIUM -> R.string.vs_memory_medium
        MemoryMode.AGGRESSIVE -> R.string.vs_memory_aggressive
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_memory_mode)) },
        supportingContent = {
            Text(stringResource(when (mode) {
                MemoryMode.LIGHT -> R.string.vs_memory_light_sub
                MemoryMode.MEDIUM -> R.string.vs_memory_medium_sub
                MemoryMode.AGGRESSIVE -> R.string.vs_memory_aggressive_sub
            }))
        },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(stringResource(label(mode))) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    MemoryMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(stringResource(label(m))) }, onClick = { open = false; set(m) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}


@Composable
private fun GyroLockPicker(mode: Int, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = listOf(R.string.vs_gyro_disabled, R.string.vs_gyro_0, R.string.vs_gyro_180, R.string.vs_gyro_none)
        .map { stringResource(it) }
    val current = GyroLock.sanitize(mode)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_gyro_lock)) },
        supportingContent = { Text(stringResource(R.string.vs_gyro_lock_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(labels[current]) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    labels.forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; set(i) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}


@Composable
private fun NetworkTypePicker(tech: Int, api: Int, cdma: Boolean, lteOnCdma: Boolean, set: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_network)) },
        supportingContent = { Text(stringResource(R.string.vs_network_sub)) },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }) { Text(NetworkType.label(NetworkType.sanitize(tech, api, cdma, lteOnCdma))) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    NetworkType.available(api, cdma, lteOnCdma).forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; set(e.tech) })
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}


@Composable
private fun HardwarePicker(value: String, set: (String) -> Unit) {
    EditableSetting(
        label = stringResource(R.string.vs_hardware),
        value = value,
        placeholder = stringResource(R.string.vs_hardware_default),
        supportingText = stringResource(R.string.vs_hardware_sub),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        sanitize = { VmSettings.cleanHardware(it) },
        dialogExtra = { _, update ->
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }) { Text(stringResource(R.string.vs_hardware_pick)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.vs_hardware_default)) }, onClick = { open = false; update("") })
                    VmSettings.HARDWARE_PRESETS.forEach { hw ->
                        DropdownMenuItem(text = { Text(hw) }, onClick = { open = false; update(hw) })
                    }
                }
            }
        },
        onSave = set,
    )
}

@Composable
private fun NumField(label: String, value: Int, modifier: Modifier, set: (Int) -> Unit) {
    
    
    
    
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
    OutlinedTextField(value = text, onValueChange = { v -> text = v.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(set) },
        label = { Text(label) }, singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}


@Composable
private fun OtaSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<app.aemu.core.DeltaOta.Prepared?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { prepared?.close() } }
    val pick = rememberSingleFilePicker { uri ->
        picking = false
        if (uri == null) return@rememberSingleFilePicker
        setBusy(true)
        message = ctx.getString(R.string.vs_ota_checking)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.aemu.core.DeltaOta.prepare(ctx, img.id, uri) } }
            setBusy(false)
            result.onSuccess { prepared?.close(); prepared = it; message = null }
                .onFailure { message = ctx.getString(R.string.vs_ota_failed, it.userMessage(ctx)) }
        }
    }
    Text(stringResource(R.string.vs_ota), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(stringResource(R.string.vs_ota_install)) },
        supportingContent = { Text(message ?: stringResource(R.string.vs_ota_sub)) },
        trailingContent = {
            TextButton(enabled = !busy && !picking, onClick = { picking = true; pick.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.vs_ota_button))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    prepared?.let { p ->
        AlertDialog(
            onDismissRequest = { p.close(); prepared = null },
            title = { Text(stringResource(R.string.vs_ota_confirm_title)) },
            text = {
                val base = stringResource(R.string.vs_ota_confirm, p.fromBuild, p.toBuild)
                Text(if (p.partial) base + "\n\n" + stringResource(R.string.vs_ota_partial) else base)
            },
            dismissButton = { TextButton(onClick = { p.close(); prepared = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = {
                prepared = null
                setBusy(true)
                message = ctx.getString(R.string.vs_ota_working, "")
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            p.use { app.aemu.core.DeltaOta.install(ctx, img.id, it) { stage ->
                                message = ctx.getString(R.string.vs_ota_working, stage.text(ctx))
                            } }
                        }
                    }
                    setBusy(false)
                    message = result.fold(
                        { ctx.getString(R.string.vs_ota_done, it.toBuild, it.patched, it.added, it.removed) +
                            it.notes.joinToString("") { n -> "\n" + n.text(ctx) } },
                        { ctx.getString(R.string.vs_ota_failed, it.userMessage(ctx)) },
                    )
                }
            }) { Text(stringResource(R.string.vs_ota_confirm_ok)) } },
        )
    }
}
