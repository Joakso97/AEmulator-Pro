
package app.aemu.ui

import app.aemu.core.userMessageOrNull
import app.aemu.core.ImportWarnings
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import app.aemu.importer.PartitionChooser
import app.aemu.importer.PartitionPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.aemu.BuildConfig
import app.aemu.update.AppUpdateManager
import app.aemu.update.ReleaseInfo
import app.aemu.update.UpdateState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.core.GuestImage
import app.aemu.AppPrefs
import app.aemu.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.List
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback

const val ACTION_BOOT = "app.aemu.BOOT"


const val EXTRA_IMPORT = "import"
const val EXTRA_IMPORT_DELETE = "import_delete"

class MainActivity : ComponentActivity() {
    private val model: LibraryModel by viewModels()

    
    private var style = ""
    private fun styleOf(c: Context) = app.aemu.AppPrefs.language(c)

    override fun attachBaseContext(base: Context) {
        style = styleOf(base)
        super.attachBaseContext(app.aemu.AppPrefs.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askPermissions()
        handleView(intent)
        
        setContent { AemuTheme { Library(model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleView(intent)
        if (styleOf(applicationContext) != style) recreate()
    }

    override fun onResume() {
        super.onResume()
        if (styleOf(applicationContext) != style) { recreate(); return }
        model.refresh()
    }

    private fun handleView(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) {
            val u = i.data
            if (u != null) {
                model.import(u)
            } else {
                val p = i.getStringExtra("path") ?: i.getStringExtra("file")
                if (p != null) model.import(android.net.Uri.fromFile(java.io.File(p)))
            }
        }
        val importPath = i?.getStringExtra(EXTRA_IMPORT)
        if (importPath != null) {
            val delete = i.getBooleanExtra(EXTRA_IMPORT_DELETE, false)
            
            i.removeExtra(EXTRA_IMPORT)
            i.removeExtra(EXTRA_IMPORT_DELETE)
            model.import(android.net.Uri.fromFile(java.io.File(importPath)), delete)
        }
        
        if (i?.action == ACTION_BOOT) i.getStringExtra("id")?.let { VmActivity.start(this, it, i.getBooleanExtra("recovery", false)) }
    }

    private fun askPermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 32) want += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val need = want.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }
}


@Composable
private fun HomeWithTabs(model: LibraryModel) {
    Library(model)
}

@Composable
fun Library(model: LibraryModel) {
    val images by model.images.collectAsState()
    val imp by model.import.collectAsState()
    val ctx = LocalContext.current
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val lifecycleOwner = LocalLifecycleOwner.current
    var activeVmIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                activeVmIds = withContext(Dispatchers.IO) { app.aemu.core.VmSlots.snapshot(ctx).ids }
                delay(1000)
            }
        }
    }
    
    val pick = rememberFilePicker(multiple = true) { uris -> if (uris.isNotEmpty()) model.import(uris) }
    var settingsFor by remember { mutableStateOf<GuestImage?>(null) }
    var deleteFor by remember { mutableStateOf<GuestImage?>(null) }
    var resetFor by remember { mutableStateOf<GuestImage?>(null) }
    var renameFor by remember { mutableStateOf<GuestImage?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var containerFrom by remember { mutableStateOf<GuestImage?>(null) }
    
    var view3d by remember { mutableStateOf(AppPrefs.library3d(ctx)) }
    
    var newHome by remember { mutableStateOf(AppPrefs.newHome(ctx)) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { view3d = AppPrefs.library3d(ctx); newHome = AppPrefs.newHome(ctx) }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val scroll = if (tablet) TopAppBarDefaults.pinnedScrollBehavior()
        else TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    
    val arranger = remember { LibraryArranger(ctx) }
    arranger.images = images
    arranger.systemsFirst = !newHome
    val drag = remember { LibraryDragState() }
    val dragKey = drag.key
    val rows = arranger.rows(collapse = if (dragKey != null && arranger.layout().isFolder(dragKey)) dragKey else null)
    val haptic = LocalHapticFeedback.current
    val folderEdge = MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
    var moveFor by remember { mutableStateOf<GuestImage?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newFolderRom by remember { mutableStateOf<String?>(null) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    fun openNewFolder(rom: String? = null) { newFolderRom = rom; newFolderOpen = true }
    fun toggleFolder(id: String) {
        val l = arranger.layout()
        val f = l.folders[id] ?: return
        arranger.update(l.setOpen(id, !f.open)); arranger.commit()
    }
    fun deleteFolder(id: String) { arranger.update(arranger.layout().deleteFolder(id)); arranger.commit() }

    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        if (app.aemu.AppPrefs.autoCheckUpdates(ctx)) {
            val now = System.currentTimeMillis()
            if (now - app.aemu.AppPrefs.lastUpdateCheck(ctx) > 3600_000L) {
                app.aemu.AppPrefs.setLastUpdateCheck(ctx, now)
                val res = AppUpdateManager.checkLatestRelease()
                res.getOrNull()?.let { rel ->
                    if (AppUpdateManager.isNewerVersion(rel.versionName, BuildConfig.VERSION_NAME)) {
                        updateState = UpdateState.Available(rel)
                    }
                }
            }
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

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (tablet) TabletRail()
            Box(Modifier.weight(1f).fillMaxSize()) {
            Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                if (tablet) {
                    TopAppBar(
                        title = { SunsetTitle() },
                        scrollBehavior = scroll,
                    )
                } else {
                    LargeFlexibleTopAppBar(
                        title = { SunsetTitle() },
                        actions = {
                            IconButton(onClick = { ctx.startActivity(Intent(ctx, RomCatalogActivity::class.java)) }) {
                                Icon(Icons.Rounded.List, stringResource(R.string.catalog_title))
                            }
                            IconButton(onClick = { ctx.startActivity(Intent(ctx, SupportedFirmwareActivity::class.java)) }) { Icon(Icons.Rounded.Info, stringResource(R.string.help)) }
                            IconButton(onClick = { ctx.startActivity(Intent(ctx, AppSettingsActivity::class.java)) }) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
                        },
                        scrollBehavior = scroll,
                    )
                }
            },
            floatingActionButton = {
                if (!newHome) ExtendedFloatingActionButton(
                    onClick = { if (images.isEmpty()) pick.launch(arrayOf("*/*")) else addMenu = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.add_firmware)) },
                    expanded = images.isEmpty() || !scroll.state.collapsedFraction.let { it > 0.5f },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                StorageAccessCard()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (images.isEmpty() && !imp.active) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = if (tablet) Alignment.TopCenter else Alignment.Center,
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .then(if (tablet) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState()))
                                    .padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (tablet) Arrangement.Top else Arrangement.Center
                            ) {
                                if (tablet) {
                                    Box(
                                        Modifier.weight(1f).fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                            EmptyState(onHelp = { ctx.startActivity(Intent(ctx, SupportedFirmwareActivity::class.java)) })
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.height(16.dp))
                                    EmptyState(onHelp = { ctx.startActivity(Intent(ctx, SupportedFirmwareActivity::class.java)) })
                                }
                            }
                        }
                    } else if (newHome) {
                        val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
                            { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
                        val gridState = rememberLazyGridState()
                        val controller = remember(gridState, arranger, drag) { LibraryDragController(drag, gridState.dragHost(), arranger) }
                        LibraryDragAutoScroll(controller)
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            state = gridState,
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(148.dp),
                            modifier = Modifier.fillMaxSize()
                                .libraryDragGesture(controller) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(rows.size, key = { rows[it].key },
                                span = { i -> if (rows[i] is FolderRow) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i ->
                                when (val row = rows[i]) {
                                    is FolderRow -> FolderHeader(
                                        row.folder,
                                        hovered = drag.hoverFolder == row.id,
                                        modifier = (if (drag.key == row.id) Modifier else Modifier.animateItem())
                                            .libraryDragVisual(controller, row.id, lockX = false),
                                        onToggle = { toggleFolder(row.id) },
                                        onRename = { renameFolderId = row.id },
                                        onDelete = { deleteFolder(row.id) },
                                    )
                                    is RomRow -> {
                                        val img = row.img
                                        MachineCard(
                                            img,
                                            baseName = images.firstOrNull { it.id == img.baseId }?.name,
                                            modifier = (if (drag.key == img.id) Modifier else Modifier.animateItem())
                                                .libraryDragVisual(controller, img.id, lockX = false)
                                                .then(if (row.folderId != null) Modifier.border(2.dp, folderEdge, MaterialTheme.shapes.extraLarge) else Modifier),
                                            active = img.id in activeVmIds,
                                            onStart = { VmActivity.start(ctx, img.id) },
                                            onSettings = { settingsFor = img },
                                            onRename = { renameFor = img },
                                            onNewMachine = { containerFrom = img },
                                            onReset = { resetFor = img },
                                            onDelete = { deleteFor = img },
                                            onMoveToFolder = { moveFor = img },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            if (view3d && images.isNotEmpty()) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
                                if (!imp.active) FirmwareFolderCard(onImport = { f -> model.import(android.net.Uri.fromFile(f)) })
                                
                                val ordered = remember(images) { images.filter { it.baseId.isEmpty() } + images.filter { it.baseId.isNotEmpty() } }
                                Library3D(
                                    ordered,
                                    activeVmIds,
                                    baseNameOf = { img -> images.firstOrNull { it.id == img.baseId }?.name },
                                    onStart = { img, lowPower -> VmActivity.start(ctx, img.id, lowPower = lowPower) },
                                    onSettings = { settingsFor = it },
                                    onRename = { renameFor = it },
                                    onDelete = { deleteFor = it },
                                )
                            } else {
                                val listState = rememberLazyListState()
                                
                                val keepForCard = with(LocalDensity.current) { 88.dp.toPx() }
                                val controller = remember(listState, arranger, drag, keepForCard) {
                                    LibraryDragController(drag, listState.dragHost(), arranger, keepForCard)
                                }
                                LibraryDragAutoScroll(controller)
                                
                                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().clipToBounds()
                                        .libraryDragGesture(controller) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                                ) {
                                    item { if (!imp.active) FirmwareFolderCard(onImport = { f -> model.import(android.net.Uri.fromFile(f)) }) }
                                    
                                    items(rows, key = { it.key }) { row ->
                                        when (row) {
                                            is FolderRow -> Box(
                                                (if (drag.key == row.id) Modifier else Modifier.animateItem())
                                                    .libraryDragVisual(controller, row.id, lockX = true)
                                                    .padding(bottom = 12.dp),
                                            ) {
                                                FolderHeader(
                                                    row.folder,
                                                    hovered = drag.hoverFolder == row.id,
                                                    onToggle = { toggleFolder(row.id) },
                                                    onRename = { renameFolderId = row.id },
                                                    onDelete = { deleteFolder(row.id) },
                                                )
                                            }
                                            is RomRow -> {
                                                val img = row.img
                                                Box(
                                                    (if (drag.key == img.id) Modifier else Modifier.animateItem())
                                                        .libraryDragVisual(controller, img.id, lockX = true)
                                                        .padding(start = if (row.folderId != null) 20.dp else 0.dp, bottom = 12.dp),
                                                ) {
                                                    ImageCard(
                                                        img,
                                                        onStart = { VmActivity.start(ctx, img.id) },
                                                        onSettings = { settingsFor = img },
                                                        onRename = { renameFor = img },
                                                        onDelete = { deleteFor = img },
                                                        onMoveToFolder = { moveFor = img },
                                                        onNewFolder = { openNewFolder(img.id) },
                                                        baseName = images.firstOrNull { it.id == img.baseId }?.name,
                                                        active = img.id in activeVmIds,
                                                        modifier = if (row.folderId != null) Modifier.border(2.dp, folderEdge, MaterialTheme.shapes.large) else Modifier,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = newHome && addMenu,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = { addMenu = false }
                    )
            )
        }

        if (newHome) {
            Box(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, end = 16.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                HomeFab(
                    expanded = addMenu,
                    onToggle = { addMenu = it },
                    onImport = { pick.launch(arrayOf("*/*")) },
                    onCatalog = { ctx.startActivity(Intent(ctx, RomCatalogActivity::class.java)) },
                    onLibrary = { library = true },
                    onNewFolder = { openNewFolder() }
                )
            }
        }
    }

    settingsFor?.let { img ->
        SettingsSheet(img, onDismiss = { settingsFor = null }, onSave = { s, name -> model.updateSettings(img, s, name); settingsFor = null })
    }
    
    
    if ((imp.active || imp.error != null || imp.done != null) && imp.choice == null) {
        ImportDialog(imp, onCancel = model::cancelImport, onDismiss = model::dismissImport,
            onStart = { img -> model.dismissImport(); VmActivity.start(ctx, img.id) })
    }
    
    imp.choice?.let { c ->
        PartitionChoiceDialog(c, onPick = model::answerChoice, onCancel = { model.answerChoice(PartitionChooser.CANCEL) })
    }
    resetFor?.let { img ->
        AlertDialog(
            onDismissRequest = { resetFor = null },
            title = { Text(stringResource(R.string.reset_title, img.name)) },
            text = { Text(stringResource(R.string.reset_text)) },
            confirmButton = { Button(onClick = {
                resetFor = null
                if (model.vmRunning()) android.widget.Toast.makeText(ctx, R.string.reset_running, android.widget.Toast.LENGTH_LONG).show()
                else model.resetData(img)
            }) { Text(stringResource(R.string.reset_data)) } },
            dismissButton = { TextButton(onClick = { resetFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteFor?.let { img ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.delete_title, img.name)) },
            text = { Text(stringResource(R.string.delete_text, Formatter.formatShortFileSize(ctx, img.sizeBytes))) },
            confirmButton = { Button(onClick = { model.delete(img); deleteFor = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    renameFor?.let { img ->
        var name by remember { mutableStateOf(img.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(stringResource(R.string.name)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { Button(onClick = { model.rename(img, name.trim().ifEmpty { img.name }); renameFor = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (newFolderOpen) FolderNameDialog(R.string.folder_new, "", onDismiss = { newFolderOpen = false; newFolderRom = null },
        onConfirm = { name ->
            arranger.update(arranger.layout().createFolder(LibLayout.newFolderId(), name, newFolderRom)); arranger.commit()
            newFolderOpen = false; newFolderRom = null
        })
    renameFolderId?.let { id ->
        arranger.layout().folders[id]?.let { f ->
            FolderNameDialog(R.string.folder_rename, f.name, onDismiss = { renameFolderId = null },
                onConfirm = { name -> arranger.update(arranger.layout().renameFolder(id, name)); arranger.commit(); renameFolderId = null })
        }
    }
    moveFor?.let { img ->
        MoveToFolderDialog(img, arranger.layout(), onDismiss = { moveFor = null },
            onPick = { folder ->
                val l = arranger.layout()
                
                arranger.update(if (folder == null) l.takeOut(img.id) else l.setOpen(folder, true).moveRomToFolder(img.id, folder))
                arranger.commit(); moveFor = null
            },
            onNew = { openNewFolder(img.id); moveFor = null })
    }
    if (!newHome && addMenu) AddSheet(images, onDismiss = { addMenu = false },
        onImport = { addMenu = false; pick.launch(arrayOf("*/*")) },
        onCatalog = { addMenu = false; ctx.startActivity(Intent(ctx, RomCatalogActivity::class.java)) },
        onContainer = { addMenu = false; containerFrom = it },
        onNewFolder = if (view3d) null else ({ addMenu = false; openNewFolder() }))
    if (newHome && library) LibrarySheet(images.filter { it.baseId.isEmpty() }, onDismiss = { library = false },
        onImportFile = { library = false; pick.launch(arrayOf("*/*")) },
        onImportFolder = { f -> library = false; model.import(android.net.Uri.fromFile(f)) },
        onNewMachine = { library = false; containerFrom = it })
    containerFrom?.let { src -> ContainerDialog(src, images, onDismiss = { containerFrom = null },
        onCreate = { from, name, copy, settings -> containerFrom = null; model.clone(from, name, copy, settings) }) }

    PhantomProcessWarning()

    UpdateDialog(
        state = updateState,
        onDismiss = { updateState = UpdateState.Idle },
        onStartDownload = ::startDownload,
        onCancelDownload = {
            downloadJob?.cancel()
            updateState = UpdateState.Idle
        },
        onInstall = { file -> AppUpdateManager.installApk(ctx, file) },
        onOpenUrl = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
    )
}


internal enum class TabletRailDestination(val activityClass: Class<out ComponentActivity>) {
    CATALOG(RomCatalogActivity::class.java),
    SUPPORTED_FILES(SupportedFirmwareActivity::class.java),
    SETTINGS(AppSettingsActivity::class.java),
}

@Composable
internal fun TabletRailLayout(selected: TabletRailDestination, content: @Composable () -> Unit) {
    content()
}

@Composable
private fun TabletRail(selected: TabletRailDestination? = null) {
    val ctx = LocalContext.current
    fun navigateTo(destination: TabletRailDestination) {
        if (destination != selected) {
            ctx.startActivity(Intent(ctx, destination.activityClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        NavigationRailItem(
            selected = selected == TabletRailDestination.CATALOG,
            onClick = { navigateTo(TabletRailDestination.CATALOG) },
            icon = { Icon(Icons.Rounded.List, null) },
            label = {
                Text(
                    stringResource(R.string.catalog_title),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        NavigationRailItem(
            selected = selected == TabletRailDestination.SUPPORTED_FILES,
            onClick = { navigateTo(TabletRailDestination.SUPPORTED_FILES) },
            icon = { Icon(Icons.Rounded.Info, null) },
            label = {
                Text(
                    stringResource(R.string.help),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        NavigationRailItem(
            selected = selected == TabletRailDestination.SETTINGS,
            onClick = { navigateTo(TabletRailDestination.SETTINGS) },
            icon = { Icon(Icons.Rounded.Settings, null) },
            label = {
                Text(
                    stringResource(R.string.settings),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(images: List<GuestImage>, onDismiss: () -> Unit, onImport: () -> Unit, onCatalog: () -> Unit, onContainer: (GuestImage) -> Unit, onNewFolder: (() -> Unit)? = null) {
    
    
    var choosing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (choosing) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_container), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                
                for (img in images.filter { it.baseId.isEmpty() } + images.filter { it.baseId.isNotEmpty() }) {
                    ListItem(headlineContent = { Text(img.name) },
                        supportingContent = { Text("${img.displayVersion} · ${img.skin}") },
                        leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onContainer(img) })
                }
            }
        } else Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListItem(headlineContent = { Text(stringResource(R.string.add_import)) },
                supportingContent = { Text(stringResource(R.string.add_import_sub)) },
                leadingContent = { Icon(Icons.Rounded.FileOpen, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onImport))
            ListItem(headlineContent = { Text(stringResource(R.string.catalog_title)) },
                leadingContent = { Icon(Icons.Rounded.List, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onCatalog))
            ListItem(headlineContent = { Text(stringResource(R.string.add_container)) },
                supportingContent = { Text(stringResource(R.string.add_container_sub)) },
                leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable {
                    if (images.size <= 1) images.firstOrNull()?.let(onContainer) else choosing = true
                })
            if (onNewFolder != null) ListItem(headlineContent = { Text(stringResource(R.string.folder_new)) },
                leadingContent = { Icon(Icons.Rounded.FolderOpen, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onNewFolder))
        }
    }
}

@Composable
private fun ContainerDialog(
    src: GuestImage,
    images: List<GuestImage>,
    onDismiss: () -> Unit,
    onCreate: (GuestImage, String, Boolean, app.aemu.core.VmSettings) -> Unit
) {
    var from by remember(src.id) { mutableStateOf(src) }
    var name by remember(from) { mutableStateOf("${from.name} (${images.count { it.baseId == from.id || it.baseId == from.baseId.ifEmpty { "-" } } + 2})") }
    var copy by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(1) } 
    var settings by remember(from) { mutableStateOf(from.settings) }
    val has32 = app.aemu.core.CpuInfo.hasAArch32

    fun applyPreset(p: Int) {
        preset = p
        settings = when (p) {
            0 -> settings.copy(gpu = true, hwui = true, jit = true, lowRam = false, native32 = has32)
            1 -> settings.copy(gpu = true, hwui = true, jit = true, lowRam = false, native32 = false)
            else -> settings.copy(gpu = false, hwui = false, jit = true, lowRam = true, width = 480, height = 800, native32 = false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.vmos_add_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.section_systems),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${from.name} · ${from.displayVersion}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Text(
                            from.skin,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                
                Text(
                    stringResource(R.string.vmos_preset_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = preset == 0,
                        onClick = { applyPreset(0) },
                        label = { Text(stringResource(R.string.vmos_preset_perf), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = preset == 1,
                        onClick = { applyPreset(1) },
                        label = { Text(stringResource(R.string.vmos_preset_balanced), maxLines = 1) },
                    )
                    FilterChip(
                        selected = preset == 2,
                        onClick = { applyPreset(2) },
                        label = { Text(stringResource(R.string.vmos_preset_lite), maxLines = 1) },
                    )
                }

                
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { copy = !copy },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(copy, { copy = it })
                    Text(stringResource(R.string.container_copy_data), style = MaterialTheme.typography.bodyMedium)
                }

                if (has32) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable {
                            settings = settings.copy(native32 = !settings.native32)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(settings.native32, { settings = settings.copy(native32 = it) })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.vs_native32), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.vs_native32_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Text(
                    stringResource(R.string.container_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(from, name.trim().ifEmpty { from.name }, copy, settings) }) {
                Text(stringResource(R.string.container_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}


@Composable
private fun StorageAccessCard() {
    if (Build.VERSION.SDK_INT < 30) return
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    var dismissed by remember { mutableStateOf(AppPrefs.storageBannerDismissed(ctx)) }
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = android.os.Environment.isExternalStorageManager()
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    AnimatedVisibility(
        visible = !granted && !dismissed,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Column(Modifier.padding(end = 32.dp)) {
                    Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.storage_text), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${ctx.packageName}")))
                        }.onFailure { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }) { Text(stringResource(R.string.allow)) }
                }
                IconButton(
                    onClick = {
                        dismissed = true
                        AppPrefs.setStorageBannerDismissed(ctx, true)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onHelp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Android, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_text),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onHelp) { Text(stringResource(R.string.empty_help)) }
    }
}


@Composable
private fun PartitionChoiceDialog(prompt: PartitionPrompt, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    var selected by remember(prompt) { mutableStateOf(0) }
    AlertDialog(
        
        onDismissRequest = {},
        title = { Text(stringResource(R.string.imp_choice_title, prompt.partition)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.imp_choice_text, prompt.partition, prompt.index, prompt.total),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                prompt.options.forEachIndexed { i, option ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { selected = i }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == i, onClick = { selected = i })
                        Spacer(Modifier.width(8.dp))
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onPick(selected) }) { Text(stringResource(R.string.imp_choice_ok)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.imp_choice_cancel)) } },
    )
}


@Composable
private fun ImportDialog(s: ImportState, onCancel: () -> Unit, onDismiss: () -> Unit, onStart: (GuestImage) -> Unit) {
    if (s.error != null) {
        ErrorDialog(
            title = stringResource(R.string.import_failed),
            subtitle = s.file,
            message = stringResource(R.string.import_error_desc),
            onDismiss = onDismiss,
            logText = s.log.takeIf { it.isNotEmpty() }?.joinToString("\n")
        )
        return
    }
    val ctx = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    val logScroll = rememberScrollState()
    
    LaunchedEffect(s.log.size, showLog) { if (showLog) logScroll.scrollTo(logScroll.maxValue) }
    AlertDialog(
        onDismissRequest = { if (!s.active) onDismiss() },
        title = { Text(stringResource(if (s.active) R.string.import_running else R.string.import_done)) },
        text = {
            Column {
                Text(s.file, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                if (s.active) {
                    if (s.progress in 0f..1f) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.step, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (s.progress in 0f..1f) {
                            Spacer(Modifier.width(8.dp))
                            Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (showLog && s.log.isNotEmpty()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(Modifier.fillMaxWidth().height(140.dp).verticalScroll(logScroll).padding(10.dp)) {
                            s.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                        }
                    }
                } else s.done?.let { done ->
                    Text("${done.name} · ${done.displayVersion} · ${done.skin}", style = MaterialTheme.typography.bodyMedium)
                    ImportWarnings.visible(done.warnings).forEach { Text("• " + ImportWarnings.text(ctx, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                }
            }
        },
        confirmButton = {
            val done = s.done
            when {
                s.active -> TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                done != null -> Button(onClick = { onStart(done) }) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.start))
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = when {
            s.active && s.log.isNotEmpty() -> ({
                TextButton(onClick = { showLog = !showLog }) {
                    Text(stringResource(if (showLog) R.string.close else R.string.log))
                }
            })
            s.done != null -> ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
            else -> null
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ImageCard(img: GuestImage, onStart: () -> Unit, onSettings: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onMoveToFolder: () -> Unit, onNewFolder: (String) -> Unit, baseName: String? = null, active: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var moreOpen by remember { mutableStateOf(false) }
    val lowPowerLabel = stringResource(R.string.lpm_start)
    Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp).clip(CircleShape)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(img.release.take(3), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(img.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(img.displayVersion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (baseName != null) Text(stringResource(R.string.container_of, baseName), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (img.lastBootMs > 0) Text(stringResource(R.string.last_boot, (img.lastBootMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ImportWarnings.visible(img.warnings).firstOrNull()?.let { Text("⚠ " + ImportWarnings.text(ctx, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = ButtonDefaults.shape, color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.height(42.dp).combinedClickable(role = Role.Button,
                        onClick = onStart, onLongClickLabel = if (!active) lowPowerLabel else null,
                        onLongClick = if (!active) ({
                            VmActivity.start(ctx, img.id, lowPower = true)
                        }) else null)) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (active) R.string.open_vm else R.string.start), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    FilledTonalIconButton(onClick = { moreOpen = true }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.vm_more), Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.vm_more_move)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { moreOpen = false; onMoveToFolder() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.settings)) },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { moreOpen = false; onSettings() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.vm_more_name)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { moreOpen = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.vm_more_folder)) },
                            leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                            onClick = { moreOpen = false; onNewFolder(img.id) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { moreOpen = false; onDelete() })
                    }
                }
            }
        }
    }
}


@Composable
private fun FirmwareFolderCard(onImport: (java.io.File) -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(emptyList<java.io.File>()) }
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) Thread { files = app.aemu.core.FirmwareFolder.list() }.start()
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    if (files.isEmpty()) return
    Card(modifier = Modifier.padding(bottom = 12.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.fw_folder_title), style = MaterialTheme.typography.titleMedium)
            Text("aemulator/firmware", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            for (f in files.take(8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(android.text.format.Formatter.formatShortFileSize(ctx, f.length()), style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.FilledTonalButton(onClick = { onImport(f) }) { Text(stringResource(R.string.fw_folder_import)) }
                }
            }
        }
    }
}
