
package app.aemu.ui

import app.aemu.core.userMessage
import app.aemu.R
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore
import app.aemu.core.VmSettings
import app.aemu.importer.Importer
import app.aemu.importer.PartitionChooser
import app.aemu.importer.PartitionPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportState(
    val active: Boolean = false,
    val file: String = "",
    val step: String = "",
    val progress: Float = -1f,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val done: GuestImage? = null,
    
    val choice: PartitionPrompt? = null,
)

class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val _images = MutableStateFlow<List<GuestImage>>(emptyList())
    val images: StateFlow<List<GuestImage>> = _images
    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import
    private var importer: Importer? = null
    
    @Volatile private var pendingChoice: java.util.concurrent.CompletableFuture<Int>? = null
    
    private val lctx: android.content.Context get() = app.aemu.AppPrefs.wrap(getApplication())

    private fun failureText(t: Throwable): String =
        if (t.message == "cancelled") lctx.getString(R.string.import_cancelled) else t.userMessage(lctx)

    @Volatile private var cloneCancelled = false

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _images.value = withContext(Dispatchers.IO) { ImageStore.list(getApplication()) }
        }
    }

    
    fun import(uri: Uri, deleteWhenDone: Boolean = false): Unit = import(listOf(uri), deleteWhenDone)

    
    fun import(uris: List<Uri>, deleteWhenDone: Boolean = false) {
        if (uris.isEmpty() || _import.value.active) return
        val ctx = getApplication<Application>()
        val names = uris.map { displayName(it) }
        _import.value = ImportState(active = true, file = names.joinToString(", "), step = lctx.getString(R.string.import_start))
        viewModelScope.launch(Dispatchers.IO) {
            val log = ArrayList<String>()
            val imp = Importer(ctx,
                onProgress = { step, p -> _import.value = _import.value.copy(step = step, progress = if (p >= 0) p else _import.value.progress.takeIf { it >= 0 } ?: -1f) },
                log = { s -> log.add(s); _import.value = _import.value.copy(log = log.takeLast(50)) })
            imp.chooser = PartitionChooser { prompt -> ask(prompt) }
            importer = imp
            try {
                
                val raw = imp.importAll(uris.zip(names) { u, n -> Importer.Source(u, n) })
                val d = app.aemu.AppPrefs.defaults(ctx)
                
                val img = if (names.any { it.endsWith(".aessvm", true) }) raw else raw.copy(settings = raw.settings.copy(gpu = d.gpu, jit = d.jit && !(raw.arch.foreign.let { it && raw.arch != app.aemu.core.GuestArch.ARM }), netProxy = d.netProxy,
                    showNavBar = d.showNavBar, navButtons = d.navButtons, trackball = d.trackball,
                    trackballDpad = d.trackballDpad, trackballStepDp = d.trackballStepDp,
                    mouseKeyboard = d.mouseKeyboard, keypad = d.keypad,
                    keepScreenOn = d.keepScreenOn, showLed = d.showLed))
                if (img != raw) ImageStore.save(ctx, img)
                _import.value = _import.value.copy(active = false, done = img, step = lctx.getString(R.string.import_ready), progress = 1f)
                if (deleteWhenDone) uris.forEach { u -> if (u.scheme == "file") u.path?.let { java.io.File(it).delete() } }
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = failureText(t))
            } finally {
                importer = null
                refresh()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") uri.path?.let { return java.io.File(it).name }
        val ctx = getApplication<Application>()
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: lctx.getString(R.string.import_default_name)
    }

    fun clone(src: GuestImage, name: String, copyData: Boolean, customSettings: app.aemu.core.VmSettings? = null) {
        if (_import.value.active) return
        val ctx = getApplication<Application>()
        cloneCancelled = false
        _import.value = ImportState(active = true, file = name, step = lctx.getString(R.string.import_copying))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val img = ImageStore.clone(ctx, src, name, copyData, customSettings, { cloneCancelled }) { s -> _import.value = _import.value.copy(step = lctx.getString(R.string.import_copying_x, s)) }
                _import.value = _import.value.copy(active = false, done = img, step = lctx.getString(R.string.import_ready), progress = 1f)
            } catch (t: Throwable) {
                _import.value = _import.value.copy(active = false, error = failureText(t))
            } finally { refresh() }
        }
    }

    
    private fun ask(prompt: PartitionPrompt): Int {
        val answer = java.util.concurrent.CompletableFuture<Int>()
        pendingChoice = answer
        _import.value = _import.value.copy(choice = prompt)
        return try { answer.get() } catch (_: InterruptedException) { PartitionChooser.CANCEL } finally {
            pendingChoice = null
            _import.value = _import.value.copy(choice = null)
        }
    }

    
    fun answerChoice(index: Int) { pendingChoice?.complete(index) }

    fun cancelImport() { importer?.cancelled = true; cloneCancelled = true; pendingChoice?.complete(PartitionChooser.CANCEL) }

    
    fun vmRunning(): Boolean = app.aemu.core.VmSlots.anyAlive(getApplication<Application>())

    fun resetData(img: GuestImage) {
        viewModelScope.launch(Dispatchers.IO) {
            ImageStore.resetData(getApplication(), img.id)
            refresh()
        }
    }
    fun dismissImport() { _import.value = ImportState() }

    fun delete(img: GuestImage) {
        viewModelScope.launch(Dispatchers.IO) {
            ImageStore.delete(getApplication(), img.id)
            refresh()
        }
    }

    fun rename(img: GuestImage, name: String) = update(img) { it.copy(name = name) }

    
    fun updateSettings(img: GuestImage, s: VmSettings, name: String? = null) =
        update(img) { it.copy(settings = s, name = name?.trim()?.takeIf { n -> n.isNotEmpty() } ?: it.name) }

    private fun update(img: GuestImage, f: (GuestImage) -> GuestImage) {
        viewModelScope.launch(Dispatchers.IO) {
            ImageStore.update(getApplication(), img.id, f)
            refresh()
        }
    }
}
