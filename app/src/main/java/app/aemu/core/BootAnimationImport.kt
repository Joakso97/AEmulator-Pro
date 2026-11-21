package app.aemu.core

import android.content.Context
import android.net.Uri
import app.aemu.R
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

internal object BootAnimationImport {
    const val TARGET = "system/media/bootanimation.zip"

    fun present(root: File) = File(root, TARGET).isFile

    fun install(ctx: Context, id: String, uri: Uri, replace: Boolean) {
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: localizedError("VM no longer exists", R.string.err_vm_no_longer_exists)
            val target = File(paths.root, TARGET)
            if (!replace && target.isFile) localizedError("Boot animation already exists", R.string.vm_bootanim_exists)
            val scratch = File(ctx.cacheDir, "bootanim-import-${System.nanoTime()}").apply { mkdirs() }
            try {
                val staged = File(scratch, "bootanimation.zip")
                (ctx.contentResolver.openInputStream(uri)
                    ?: localizedError("Cannot open the file", R.string.err_cannot_open_archive)).use { input ->
                    Files.copy(input, staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                val valid = runCatching { ZipFile(staged).use { it.getEntry("desc.txt") != null } }.getOrDefault(false)
                if (!valid) localizedError("Not a boot animation ZIP", R.string.vm_bootanim_invalid)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".aemu-tmp")
                Files.move(staged.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target.setReadable(true, false)
                ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
            } finally { scratch.deleteRecursively() }
        }
    }
}
