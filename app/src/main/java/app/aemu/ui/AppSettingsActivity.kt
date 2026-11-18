
package app.aemu.ui

import app.aemu.core.userMessageOrNull
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.aemu.update.AppUpdateManager
import app.aemu.update.ReleaseInfo
import app.aemu.update.UpdateState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.BuildConfig
import app.aemu.R


object Links {
    const val GITHUB = "https://github.com/Joakso97/AEmulator-Pro"
    const val AUTHOR = "https://github.com/Joakso97"
    const val SUNSET = "https://github.com/drel4/AEmulator-Sunset"
    const val UPSTREAM = "https://github.com/uxazu/AEmulator"
    const val ORIGINAL = "https://t.me/istratiit_ech"
}

class AppSettingsActivity : ComponentActivity() {
    private val languagePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) restart()
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AemuTheme {
                TabletRailLayout(TabletRailDestination.SETTINGS) {
                    AppSettings(
                        onBack = ::finish,
                        onRestyle = ::restart,
                        onLanguage = { languagePicker.launch(Intent(this, LanguageActivity::class.java)) },
                    )
                }
            }
        }
    }

    
    private fun restart() {
        recreate()
    }
}

@Composable
private fun AppSettings(onBack: () -> Unit, onRestyle: () -> Unit, onLanguage: () -> Unit) {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var defaults by remember { mutableStateOf(AppPrefs.defaults(ctx)) }
    var theme by remember { mutableStateOf(AppPrefs.theme(ctx)) }
    var dynamic by remember { mutableStateOf(AppPrefs.dynamicColor(ctx)) }
    var accent by remember { mutableStateOf(AppPrefs.accentColor(ctx)) }
    var accentDialog by remember { mutableStateOf(false) }
    var hostUi by remember { mutableStateOf(AppPrefs.hostUiOptions(ctx)) }
    val scope = rememberCoroutineScope()
    var autoUpdates by remember { mutableStateOf(AppPrefs.autoCheckUpdates(ctx)) }
    var library3d by remember { mutableStateOf(AppPrefs.library3d(ctx)) }
    var builtinBrowser by remember { mutableStateOf(AppPrefs.builtinBrowser(ctx)) }
    var newHome by remember { mutableStateOf(AppPrefs.newHome(ctx)) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    if (accentDialog) {
        ColorPickerDialog(
            initial = if (accent == 0) Color(0xFF0061A4) else Color(accent),
            onDismiss = { accentDialog = false },
            onReset = { accent = 0; AppPrefs.setAccentColor(ctx, 0); accentDialog = false; onRestyle() },
            onApply = { c ->
                val argb = c.toArgb() or 0xFF000000.toInt()
                accent = argb; AppPrefs.setAccentColor(ctx, argb); accentDialog = false; onRestyle()
            },
        )
    }

    fun checkForUpdates() {
        updateState = UpdateState.Checking
        scope.launch {
            val res = AppUpdateManager.checkLatestRelease()
            res.fold(
                onSuccess = { rel ->
                    if (rel != null) {
                        if (AppUpdateManager.isNewerVersion(rel.versionName, BuildConfig.VERSION_NAME)) {
                            updateState = UpdateState.Available(rel)
                        } else {
                            updateState = UpdateState.UpToDate(BuildConfig.VERSION_NAME, rel.tagName)
                        }
                    } else {
                        updateState = UpdateState.UpToDate(BuildConfig.VERSION_NAME, "")
                    }
                },
                onFailure = { err ->
                    updateState = UpdateState.Error(err.userMessageOrNull(ctx) ?: ctx.getString(R.string.update_network_error))
                }
            )
        }
    }

    fun startDownload(rel: ReleaseInfo) {
        updateState = UpdateState.Downloading(rel, 0f, 0L, rel.sizeBytes)
        downloadJob = scope.launch {
            try {
                val file = AppUpdateManager.downloadApk(ctx, rel) { progress, downloaded, total ->
                    updateState = UpdateState.Downloading(rel, progress, downloaded, total)
                }
                updateState = UpdateState.ReadyToInstall(rel, file)
                AppUpdateManager.installApk(ctx, file)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    updateState = UpdateState.Error(e.userMessageOrNull(ctx) ?: ctx.getString(R.string.update_download_failed_generic))
                }
            }
        }
    }

    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val langTag = AppPrefs.language(ctx)
    val langName = AppPrefs.LANGUAGES.firstOrNull { it.first == langTag }?.second?.ifEmpty { null } ?: stringResource(R.string.as_lang_system)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Section(stringResource(R.string.catalog_title)) {
                    Row_(Icons.Rounded.List, stringResource(R.string.catalog_open), "") {
                        ctx.startActivity(Intent(ctx, RomCatalogActivity::class.java))
                    }
                }
            }

            item {
                Section(stringResource(R.string.as_updates)) {
                    Toggle(
                        stringResource(R.string.as_auto_updates),
                        stringResource(R.string.as_auto_updates_sub),
                        autoUpdates
                    ) {
                        autoUpdates = it
                        AppPrefs.setAutoCheckUpdates(ctx, it)
                    }
                    Row_(
                        Icons.Rounded.SystemUpdate,
                        stringResource(R.string.as_check_updates),
                        stringResource(R.string.as_check_updates_sub, BuildConfig.VERSION_NAME)
                    ) {
                        checkForUpdates()
                    }

                }
            }

            item {
                Section(stringResource(R.string.as_appearance)) {
                    Toggle(stringResource(R.string.as_new_home), stringResource(R.string.as_new_home_sub), newHome) {
                        newHome = it; AppPrefs.setNewHome(ctx, it)
                    }
                    Toggle(stringResource(R.string.as_library_3d), stringResource(R.string.as_library_3d_sub), library3d) {
                        library3d = it; AppPrefs.setLibrary3d(ctx, it)
                    }
                    Toggle(stringResource(R.string.as_builtin_browser), stringResource(R.string.as_builtin_browser_sub), builtinBrowser) {
                        builtinBrowser = it; AppPrefs.setBuiltinBrowser(ctx, it)
                    }
                    if (app.aemu.core.HostDisplayPolicy.rotateWindow(ctx.resources.configuration.smallestScreenWidthDp)) {
                        Toggle(stringResource(R.string.as_tablet_navbar_rotation), stringResource(R.string.as_tablet_navbar_rotation_sub), hostUi.tabletNavbarRotation) {
                            hostUi = hostUi.copy(tabletNavbarRotation = it); AppPrefs.setHostUiOptions(ctx, hostUi)
                        }
                    }
                    Toggle(stringResource(R.string.as_cutout_barrier), stringResource(R.string.as_cutout_barrier_sub), hostUi.cutoutBarrier) {
                        hostUi = hostUi.copy(cutoutBarrier = it); AppPrefs.setHostUiOptions(ctx, hostUi)
                    }
                    
                    VmThemeRow()
                    Row_(Icons.Rounded.Translate, stringResource(R.string.as_language), langName, onClick = onLanguage)
                    Text(stringResource(R.string.as_theme), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
                    val options = listOf(R.string.as_theme_system, R.string.as_theme_light, R.string.as_theme_dark)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        options.forEachIndexed { i, res ->
                            ToggleButton(
                                checked = theme == i,
                                onCheckedChange = { if (theme != i) { theme = i; AppPrefs.setTheme(ctx, i) } },
                                shapes = when (i) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                
                                Text(stringResource(res), maxLines = 1, softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.as_accent)) },
                        supportingContent = { Text(if (accent == 0) stringResource(R.string.as_accent_default)
                            else "#%06X".format(accent and 0xFFFFFF)) },
                        trailingContent = {
                            androidx.compose.foundation.layout.Box(Modifier.size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (accent == 0) Color(0xFF0061A4) else Color(accent)))
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { accentDialog = true },
                    )
                    if (Build.VERSION.SDK_INT >= 31 && accent == 0) {
                        Toggle(stringResource(R.string.as_dynamic), stringResource(R.string.as_dynamic_sub), dynamic) {
                            dynamic = it; AppPrefs.setDynamicColor(ctx, it)
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.as_defaults), stringResource(R.string.as_defaults_sub)) {
                    fun set(s: app.aemu.core.VmSettings) { defaults = s; AppPrefs.setDefaults(ctx, s) }
                    Toggle(stringResource(R.string.vs_gpu), stringResource(R.string.vs_gpu_sub), defaults.gpu) { set(defaults.copy(gpu = it)) }
                    Toggle(stringResource(R.string.vs_jit), stringResource(R.string.vs_jit_sub), defaults.jit) { set(defaults.copy(jit = it)) }
                    Toggle(stringResource(R.string.vs_proxy), stringResource(R.string.vs_proxy_sub), defaults.netProxy) { set(defaults.copy(netProxy = it)) }
                    Toggle(stringResource(R.string.vs_nav), stringResource(R.string.vs_nav_sub), defaults.showNavBar) { set(defaults.copy(showNavBar = it)) }
                    NavigationSettings(defaults, set = ::set)
                    Toggle(stringResource(R.string.vs_awake), stringResource(R.string.vs_awake_sub), defaults.keepScreenOn) { set(defaults.copy(keepScreenOn = it)) }
                    Toggle(stringResource(R.string.as_led), stringResource(R.string.as_led_sub), defaults.showLed) { set(defaults.copy(showLed = it)) }
                }
            }
        }
    }

    UpdateDialog(
        state = updateState,
        onDismiss = { updateState = UpdateState.Idle },
        onStartDownload = ::startDownload,
        onCancelDownload = {
            downloadJob?.cancel()
            updateState = UpdateState.Idle
        },
        onInstall = { file -> AppUpdateManager.installApk(ctx, file) },
        onOpenUrl = { url -> open(url) }
    )
}

@Composable
internal fun AboutCard(onClick: (() -> Unit)? = null) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SunsetTitle(
                style = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.as_about),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = if (subtitle == null) 8.dp else 0.dp))
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
        Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
internal fun Row_(icon: ImageVector?, title: String, sub: String, mono: Boolean = false, onClick: () -> Unit) {
    val supporting: (@Composable () -> Unit)? =
        if (sub.isEmpty()) null else ({ Text(sub, fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 2) })
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting,
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
