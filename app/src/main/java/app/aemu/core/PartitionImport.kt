
package app.aemu.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.aemu.R
import app.aemu.importer.AbLayout
import app.aemu.importer.BootImage
import app.aemu.importer.ImageCodecs
import app.aemu.importer.Importer
import app.aemu.importer.OemPartitionSource
import app.aemu.importer.PartitionNames
import java.io.File
import java.nio.file.Files


internal object PartitionImport {
    const val BOOT = "boot"
    const val RECOVERY = "recovery"

    
    private const val RECOVERY_LIMIT = 64_000_000

    
    val CHOICES = listOf(BOOT, RECOVERY, "oem", "vendor", "odm", "system/product", "system/system_ext")

    
    val UNSUPPORTED = setOf("system")

    private val BOOT_NAME = Regex("(?i)boot\\.(emmc|mtd|mmc)\\.win|zimage|kernel")
    private val MOUNT = Regex("[a-z][a-z0-9_]*(?:/[a-z][a-z0-9_]*)*")

    fun validMount(kind: String): Boolean = kind.matches(MOUNT) && kind !in UNSUPPORTED

    fun label(kind: String) = kind.substringAfterLast('/')

    
    fun replaceable(kind: String) = kind == RECOVERY

    fun present(dir: File, kind: String): Boolean = when (kind) {
        BOOT -> BootPartitionImport.present(dir)
        RECOVERY -> File(dir, "recovery/sbin/recovery").isFile
        else -> File(dir, "root/$kind").list()?.isNotEmpty() == true
    }

    
    fun displayName(ctx: Context, uri: Uri, fallback: String): String {
        if (uri.scheme == "file") uri.path?.let { return File(it).name }
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: fallback
    }

    
    fun detect(ctx: Context, uri: Uri): String? {
        val name = displayName(ctx, uri, "")
        val head = ByteArray(8)
        val n = (ctx.contentResolver.openInputStream(uri) ?: localizedError("Cannot open the file", R.string.err_cannot_open_archive)).use { it.read(head) }
        val plain = ImageCodecs.stripSuffix(PartitionNames.stripOdinMd5(name.substringAfterLast('/')))
        val boot = n >= 8 && String(head, 0, 8, Charsets.ISO_8859_1) == "ANDROID!"
        return when {
            PartitionNames.isRecovery(plain, true) -> RECOVERY   
            PartitionNames.isSystem(plain, true) -> "system"
            
            boot && !PartitionNames.isBoot(plain, true) && !BOOT_NAME.matches(plain) && holdsRecovery(ctx, uri) -> RECOVERY
            boot || PartitionNames.isBoot(plain, true) || BOOT_NAME.matches(plain) -> BOOT
            PartitionNames.isOem(plain, true) -> "oem"
            PartitionNames.isVendor(plain, true) -> "vendor"
            else -> PartitionNames.extraMount(plain, true) ?: PartitionNames.anyMount(plain, true)
        }
    }

    private fun holdsRecovery(ctx: Context, uri: Uri): Boolean = runCatching {
        val data = readLimited(ctx, uri) ?: return@runCatching false
        BootImage.ramdisk(data)?.let { AbLayout.isRecoveryRamdisk(it) } == true
    }.getOrDefault(false)

    
    private fun readLimited(ctx: Context, uri: Uri): ByteArray? {
        val input = ctx.contentResolver.openInputStream(uri) ?: localizedError("Cannot open the image", R.string.err_cannot_open_oem_image)
        return input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > RECOVERY_LIMIT) return@use null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun installRecovery(ctx: Context, id: String, uri: Uri) {
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: localizedError("VM no longer exists", R.string.err_vm_no_longer_exists)
            require(paths.dir.isDirectory && !Files.isSymbolicLink(paths.dir.toPath())) { "Conflicting partition path" }
            val data = readLimited(ctx, uri) ?: localizedError("Not a recovery image", R.string.vs_recovery_failed)
            if (!RecoveryImage.install(paths, data) {}) localizedError("Not a recovery image", R.string.vs_recovery_failed)
            ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
        }
    }

    private fun matcher(kind: String): (String, Boolean) -> Boolean = when (kind) {
        "oem" -> { b, ok -> PartitionNames.isOem(b, ok) }
        "vendor" -> { b, ok -> PartitionNames.isVendor(b, ok) }
        "product", "system/product", "system_ext", "system/system_ext", "odm" ->
            { b, ok -> PartitionNames.extraMount(b, ok) == kind }
        else -> { b, ok -> PartitionNames.anyMount(b, ok) == kind || PartitionNames.isDiskImageName(b) }
    }

    fun install(ctx: Context, id: String, uri: Uri, kind: String) {
        if (kind == BOOT) { BootPartitionImport.install(ctx, id, uri); return }
        if (kind == RECOVERY) { installRecovery(ctx, id, uri); return }
        require(validMount(kind)) { "Unsupported partition" }
        val label = label(kind)
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: localizedError("VM no longer exists", R.string.err_vm_no_longer_exists)
            if (present(paths.dir, kind)) localizedError("VM already has a $label partition", R.string.vm_import_partition_exists, label)
            for (dir in listOf(paths.dir, paths.root)) require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath()))
            val target = File(paths.root, kind)
            val parent = target.parentFile ?: localizedError("Conflicting partition path", R.string.vm_import_partition_conflict)
            require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "Conflicting partition path" }
            require(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isDirectory)) { "Conflicting partition path" }
            val name = displayName(ctx, uri, "$label.img")
            val scratch = File(ctx.cacheDir, "partition-import-${System.nanoTime()}").apply { mkdirs() }
            val stage = File(paths.dir, ".partition-stage").apply { deleteRecursively(); mkdirs() }
            
            val layout = SystemLayout.partitionOf(kind)?.let { SystemLayout.file(paths.dir, it) }
            val hadLayout = layout?.exists() == true
            try {
                val image = (ctx.contentResolver.openInputStream(uri) ?: localizedError("Cannot open the image", R.string.err_cannot_open_oem_image)).use {
                    OemPartitionSource.read(it, name, scratch, 0, matcher(kind), label)
                }
                Importer(ctx, { _, _ -> }, { }).importPartitionInto(image, stage, kind, paths)
                val staged = File(stage, kind)
                require(staged.list()?.isNotEmpty() == true) { "The $label image holds no files" }
                if (target.exists()) check(target.delete()) { "Cannot replace the empty $label folder" }
                check(staged.renameTo(target)) { "Cannot place the $label partition" }
                ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
            } catch (t: Throwable) {
                if (layout != null && !hadLayout) layout.delete()
                throw t
            } finally {
                scratch.deleteRecursively()
                stage.deleteRecursively()
            }
        }
    }
}
