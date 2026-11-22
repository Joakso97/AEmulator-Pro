



package app.aemu.importer

import app.aemu.R
import android.content.Context
import android.net.Uri
import android.system.Os
import app.aemu.core.CscImport
import app.aemu.core.GuestImage
import app.aemu.core.PropArea
import app.aemu.core.LegacyFirmware
import app.aemu.core.LocalizedIOException
import app.aemu.core.ImageStore
import app.aemu.core.TreeFixer
import app.aemu.core.X86SoftwareGles
import app.aemu.core.VmPaths
import app.aemu.core.RecoveryImage
import app.aemu.core.SystemLayout
import app.aemu.core.StockImage
import app.aemu.core.SquashLayout
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Archive as RarArchive
import com.github.junrar.rarfile.FileHeader as RarFileHeader
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.XZInputStream
import app.aemu.importer.tools.FirmwareToolset
import app.aemu.importer.tools.WimReader
import app.aemu.importer.tools.WindowsCab
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream


class Importer(
    private val ctx: Context,
    private val onProgress: (String, Float) -> Unit,
    private val log: (String) -> Unit,
) {
    private lateinit var paths: VmPaths
    private lateinit var root: File
    private val tmp = File(ctx.cacheDir, "import").apply { mkdirs() }
    private var ramdisk: List<BootImage.CpioEntry>? = null
    private val MAX_RAMDISK = 64L * 1024 * 1024
    
    private val LP_PROBE = 4096 + 52
    private val MAX_RAMDISK_FILE = 32L * 1024 * 1024
    private var recovery: ByteArray? = null
    
    private var rfbPatch: ByteArray? = null
    private var rfbScript: String? = null
    private var rfbBonus: ByteArray? = null
    private val symlinks = ArrayList<Pair<String, String>>() 
    private val perms = ArrayList<Triple<String, Int, Boolean>>() 
    private var files = 0
    private var bytes = 0L
    private var gotSystem = false
    private var gotOem = false
    private var gotVendor = false
    
    private var pendingCsc: File? = null
    
    private var systemAsRoot = false
    
    private var recoveryBoot: ByteArray? = null

    
    private var kernelBoot: ByteArray? = null
    
    private var vendorRamdisk: List<BootImage.CpioEntry>? = null
    @Volatile var cancelled = false
    
    var chooser: PartitionChooser? = null
    
    private val localized: Context by lazy { app.aemu.AppPrefs.wrap(ctx) }
    private fun lt(@androidx.annotation.StringRes id: Int, vararg args: Any?): String = localized.getString(id, *args)

    
    class Source(val uri: Uri, val name: String)

    fun import(uri: Uri, name: String): GuestImage = importAll(listOf(Source(uri, name)))

    
    fun importAll(sources: List<Source>): GuestImage {
        require(sources.isNotEmpty()) { "nothing to import" }
        if (sources.any { it.name.endsWith(".aessvm", true) }) {
            if (sources.size > 1) throw LocalizedIOException("a VM archive cannot be imported together with other files", R.string.imp_archive_alone)
            return app.aemu.core.VmArchiveStorage.restore(ctx, sources[0].uri) { path ->
                if (cancelled) throw IOException("cancelled")
                onProgress(lt(R.string.imp_restoring, path), -1f)
            }.also { onProgress(lt(R.string.import_ready), 1f) }
        }
        
        val ordered = sources.sortedBy { PartitionNames.isBoot(it.name.replace('\\', '/').substringAfterLast('/')) }
        
        
        val plan = if (ordered.size > 1) planSafely { planLooseFiles(ordered) } else null
        val kept = ordered.withIndex().filter { (i, _) -> plan == null || i.toString() !in plan.skip }
        val name = kept.first().value.name
        val id = ImageStore.newId(ctx)
        paths = VmPaths(ctx, id)
        root = paths.root
        root.mkdirs()
        try {
            for ((n, iv) in kept.withIndex()) {
                val s = iv.value
                if (cancelled) throw IOException("cancelled")
                onProgress(lt(R.string.imp_opening, if (kept.size > 1) "${s.name} (${n + 1}/${kept.size})" else s.name), 0f)
                
                readSource(s, plan?.canonical?.get(iv.index.toString()) ?: s.name)
            }
            if (plan != null) for ((i, s) in ordered.withIndex()) if (i.toString() in plan.skip) log("skipped ${s.name}: another image of the same partition was chosen")
            useDeferredBoot()
            
            if (LegacyFirmware.frameworkDirName(root) == null && !File(root, "system/build.prop").isFile)
                throw LocalizedIOException("no Android system partition found in file", R.string.imp_no_android_system_partition_found_in)
            finishTree()
            applyPendingCsc()
            if (recovery == null) runCatching { rebuildRecoveryFromBoot() }.onFailure { log("recovery: rebuild from boot failed (${it.message}), skipped") }
            if (recovery != null) recovery?.let { r -> runCatching { RecoveryImage.install(paths, r, log) }.onFailure { log("recovery: install failed (${it.message}), skipped") } }
            
            else ramdisk?.let { rd -> runCatching { RecoveryImage.installEmbedded(paths, rd, log) }.onFailure { log("recovery: embedded recovery cannot be installed (${it.message}), skipped") } }
            onProgress(lt(R.string.imp_analyzing_firmware), 0.97f)
            val img = Analyzer(ctx, paths, ramdisk).analyze(id, name)
            
            X86SoftwareGles.ensure(ctx, root, img.api, log)
            TreeFixer(ctx, paths, img, log).sanitize()
            ImageStore.save(ctx, img.copy(sizeBytes = ImageStore.du(paths.dir)))
            onProgress(lt(R.string.import_ready), 1f)
            log("import: $files files, ${bytes shr 20} MB")
            return ImageStore.get(ctx, id)!!
        } catch (t: Throwable) {
            ImageStore.delete(ctx, id)
            throw t
        } finally {
            tmp.listFiles()?.forEach { deleteTree(it) }
        }
    }

    private fun readSource(s: Source, name: String) {
        val pfd = openSource(s.uri) ?: throw LocalizedIOException("file failed to open", R.string.imp_file_failed_to_open)
        pfd.use {
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val seekable = runCatching { ch.position(0); ch.size() > 0 }.getOrDefault(false)
            if (seekable) handle(ChannelSource(ch), ch, name, 0)
            else {
                val t = spill(FileInputStream(pfd.fileDescriptor), "whole")
                FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, name, 0) }
                t.delete()
            }
        }
    }

    
    private fun planLooseFiles(files: List<Source>): ArchivePlan? {
        val clash = files.map { it.name.lowercase() }.let { l -> l.distinct().size < l.size }
        val members = files.mapIndexed { i, f ->
            val folder = if (clash && f.uri.scheme == "file") f.uri.path?.let { File(it).parentFile?.name } else null
            val size = runCatching { openSource(f.uri)?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
            Member(i.toString(), if (folder != null) "$folder/${f.name}" else f.name, size, i)
        }
        val reader = object : MemberReader {
            private fun open(m: Member) = openSource(files[m.index].uri) ?: throw IOException("file failed to open")
            override fun head(m: Member, n: Int): ByteArray =
                android.os.ParcelFileDescriptor.AutoCloseInputStream(open(m)).use { readUpTo(it, n) }
            override fun <T> source(m: Member, block: (RandomSource) -> T): T = open(m).use { pfd ->
                val ch = FileInputStream(pfd.fileDescriptor).channel
                if (runCatching { ch.position(0); ch.size() > 0 }.getOrDefault(false)) block(ChannelSource(ch))
                else withSpilledSource(FileInputStream(pfd.fileDescriptor), m.name, block)
            }
            override fun <T> stream(m: Member, block: (InputStream) -> T): T =
                android.os.ParcelFileDescriptor.AutoCloseInputStream(open(m)).use(block)
        }
        return planPartitionChoices(members, reader, null, false)
    }

    
    private fun openSource(uri: Uri): android.os.ParcelFileDescriptor? =
        if (uri.scheme == "file") uri.path?.let { android.os.ParcelFileDescriptor.open(File(it), android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
        else ctx.contentResolver.openFileDescriptor(uri, "r")

    

    private fun handle(src: RandomSource, ch: FileChannel?, name: String, depth: Int, role: FirmwareToolset.Role? = null) {
        if (depth > 8) return
        val head = ByteBuffer.allocate(1100)
        src.read(0, head); head.flip()
        val h = ByteArray(head.remaining()).also { head.get(it) }
        if (h.size < 2) throw LocalizedIOException("empty or truncated firmware file", R.string.imp_empty_or_truncated_firmware_file)
        
        if (depth > 0 && name.endsWith(".ops", true) && src.size < 0x200) { log("skipped $name: too small to be an OPS firmware container"); return }
        
        
        val bareRamdisk = decodeBareRamdisk(src, name, h)
        
        val bare = PartitionNames.bareOk(name.replace('\\', '/'), src.size)
        val codec = ImageCodecs.detect(h, name)?.takeIf { it != ImageCodecs.Codec.LZ4 }
        when {
            bareRamdisk != null -> takeBareRamdisk(bareRamdisk, name)
            
            CrxContainer.probe(h) -> importCrx(src, name, depth)
            
            
            h.size >= 4 && h[0] == 'M'.code.toByte() && h[1] == 'S'.code.toByte() && h[2] == 'C'.code.toByte() && h[3] == 'F'.code.toByte() &&
                !name.endsWith(".kdz", true) && WindowsCab.hasWim(cabSource(src)) -> importWindowsCab(src, name)
            
            WimReader.probe(h) -> importWimStandalone(src, name)
            
            
            FirmwareToolset.isEncryptedKdz(h, src.size) -> importViaToolset(src, name, depth)
            
            
            
            depth > 0 && isSkippablePartition(name, h) -> log("skipped $name: partition the guest does not use")
            
            NandDump.isMtdName(name.replace('\\', '/').substringAfterLast('/')) -> importMtdDump(src, name)
            name.endsWith(".ofp", true) && h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                val source = spill(streamOf(src), name)
                val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
                try {
                    val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
                    if (artifacts.isEmpty()) throw LocalizedIOException("OFP ZIP produced no files", R.string.imp_ofp_zip_produced_no_files)
                    for (artifact in artifacts) {
                        if (!artifact.file.isFile) continue
                        FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, artifact.file.name, depth + 1, artifact.role)
                        }
                    }
                } finally { deleteTree(toolWork); source.delete() }
            }
            
            
            name.endsWith(".sin", true) || (h.size > 4 && h[0].toInt() == 3 && String(h, 1, 3, Charsets.ISO_8859_1) == "SIN") ->
                importSin(src, name)
            h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                if (ch != null) importZip(ch, name, depth) else throw LocalizedIOException("zip without random access", R.string.imp_zip_without_random_access)
            }
            h.size > 6 && h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() && h[2] == 0xBC.toByte() && h[3] == 0xAF.toByte() -> {
                if (ch != null) importSevenZ(ch, name, depth) else throw LocalizedIOException("7z without random access", R.string.imp_z_without_random_access)
            }
            h.size >= 7 && h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() &&
                h[2] == 'r'.code.toByte() && h[3] == '!'.code.toByte() && h[4] == 0x1a.toByte() -> {
                val archive = spill(streamOf(src), name)
                try { importRar(archive, name, depth) } finally { archive.delete() }
            }
            h.size >= 6 && (String(h, 0, 6, Charsets.US_ASCII) == "070701" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070702" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070707") ->
                importCpioStream(streamOf(src), name, depth, reopen = { streamOf(src) })
            
            
            Iso9660.probe(src) -> importIso(src, name, depth)
            
            GptDisk.probe(h) && src.size > (1L shl 20) -> importGptDisk(src, name)
            
            
            MbrDisk.probe(h) && src.size > (1L shl 20) -> importMbrDisk(src, name)
            
            HuaweiApp.probe(h) -> importHuaweiApp(src, name, strict = depth == 0)
            
            AcerBin.probe(h) -> importAcerBin(src, name, strict = depth == 0)
            
            
            FirmwareToolset.isHtcContainer(h) -> importViaToolset(src, name, depth)
            
            
            
            
            role == FirmwareToolset.Role.OEM || isOemName(name.replace('\\', '/').substringAfterLast('/'), bare) -> importOem(src)
            
            isVendorName(name.replace('\\', '/').substringAfterLast('/'), bare) -> importVendor(src)
            
            isExtraName(name.replace('\\', '/').substringAfterLast('/'), bare) -> importExtra(src, name.replace('\\', '/').substringAfterLast('/'))
            role == FirmwareToolset.Role.RECOVERY && src.size < 64_000_000 && isAndroidImage(h) ->
                takeRecovery(streamOf(src), recoveryLabel(name, h))
            role == FirmwareToolset.Role.RECOVERY && src.size < 64_000_000 && isLz4Frame(h) ->
                takeRecovery(streamOf(src), recoveryLabel(name, h))
            SparseSource.probe(src) -> importImage(SparseSource(listOf(src)), "system")
            Ext4Reader.probe(src) -> importImage(src, "system")
            MotoImage.probe(src) -> importImage(src, "system")
            UbiFsReader.probe(src) -> importImage(src, "system")
            Yaffs2Reader.probe(src) -> importImage(src, "system")
            ErofsReader.probe(src) -> importImage(src, "system")
            
            
            codec != null -> importCompressed(ImageCodecs.open(codec, streamOf(src)), name, depth)
            isTar(h) || name.endsWith(".tar", true) || name.endsWith(".md5", true) || name.endsWith(".win", true) ->
                importTarStream(streamOf(src), name, depth, src)
            
            isRecoveryName(name.replace('\\', '/').substringAfterLast('/'), bare) && src.size < 64_000_000 ->
                takeRecovery(streamOf(src), name.replace('\\', '/').substringAfterLast('/'))
            
            isLz4Frame(h) -> importCompressed(org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(streamOf(src)), name, depth)
            h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!" -> {
                val data = readAllFrom(src)
                val base = name.replace('\\', '/').substringAfterLast('/')
                
                if (role != FirmwareToolset.Role.BOOT && !isBootName(base, bare) && hasRecoveryBinary(data)) takeRecovery(data.inputStream(), recoveryLabel(name, h))
                else takeBoot(data)
            }
            
            isSystemImageName(name.replace('\\', '/').substringAfterLast('/'), bare) ->
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
            else -> importViaToolset(src, name, depth)
        }
    }

    
    private fun importViaToolset(src: RandomSource, name: String, depth: Int) =
        importToolsetFile(spill(streamOf(src), name), name, depth)

    
    private fun importToolsetFile(source: File, name: String, depth: Int) {
        val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
        try {
            val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
            if (artifacts.isEmpty()) {
                
                
                if (depth > 0 && name.endsWith(".bin", true)) { log("skipped $name: not a firmware container"); return }
                throw LocalizedIOException("unknown file format \"$name\"", R.string.imp_unknown_file_format, name)
            }
            
            if (artifacts.none { it.file == source }) source.delete()
            for (artifact in artifacts) {
                if (cancelled) throw IOException("cancelled")
                if (!artifact.file.isFile) continue
                FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                    handle(ChannelSource(c), c, artifact.file.name, depth + 1, artifact.role)
                }
                if (artifact.cleanup && artifact.file != source) artifact.file.delete()
            }
        } finally {
            deleteTree(toolWork)
            source.delete()
        }
    }

    private fun recognizedFirmwareTool(name: String): Boolean {
        val n = name.lowercase()
        if (n == "file_contexts.bin" || n == "file_contexts" || n.endsWith("/file_contexts.bin")) return false
        
        if (n.endsWith(".exe") && n.substringAfterLast('/').startsWith("ruu")) return true
        return n.endsWith(".pac") || n.endsWith(".sbf") || n.endsWith(".nbh") ||
            n.endsWith(".tot") || n.endsWith(".kdz") || n.endsWith(".dz") || isLikelyLgBinName(n) || n.endsWith(".ofp") ||
            n.endsWith(".ops") || n.endsWith(".pkg") || n.endsWith(".rfs") ||
            n.endsWith(".squashfs") || n.endsWith(".new.dat") || n.endsWith(".new.dat.br") ||
            n.endsWith(".transfer.list") || n == "payload.bin" || n == "super.img"
    }

    
    
    
    
    private fun isLikelyLgBinName(n: String): Boolean {
        val b = n.substringAfterLast('/')
        if (!b.endsWith(".bin")) return false
        if (b == "file_contexts.bin") return false
        return b.startsWith("lg", true) || b.contains("kdz", true) ||
            b.contains("firmware", true) || b.contains("flash", true)
    }

    private fun spillPreservingName(i: InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace('\\', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "entry" }
        val dir = File(tmp, "entries-${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, safe)
        f.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
        return f
    }

    
    private fun importCompressed(decoded: InputStream, name: String, depth: Int) {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val inner = base.replace(Regex("(?i)\\.(tgz|tbz2?|txz)$"), ".tar")
            .replace(Regex("(?i)\\.(tlz|tzst)$"), ".tar").let { ImageCodecs.stripSuffix(it) }.ifEmpty { "payload" }
        val f = decoded.use { spill(it, inner) }
        try {
            if (f.length() < 2) throw LocalizedIOException("compressed file is empty: $name", R.string.imp_compressed_file_is_empty, name)
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, inner, depth + 1) }
        } finally { deleteTree(f.parentFile ?: f) }
    }

    
    private fun isRecoveryName(base: String, bare: Boolean = false) = PartitionNames.isRecovery(base, bare)

    
    private fun isSkippablePartition(name: String, h: ByteArray): Boolean {
        fun be(i: Int) = if (h.size >= 4) ((h[i].toInt() and 255) shl 24) or ((h[i + 1].toInt() and 255) shl 16) or
            ((h[i + 2].toInt() and 255) shl 8) or (h[i + 3].toInt() and 255) else 0
        if (h.size >= 4 && (String(h, 0, 4, Charsets.ISO_8859_1) == "AVB0" || be(0) == 0xD7B7AB1E.toInt() || be(0) == 0xD00DFEED.toInt())) return true
        val stem = name.replace('\\', '/').substringAfterLast('/').lowercase()
            .substringBefore('.').removeSuffix("_a").removeSuffix("_b")
        return stem.startsWith("vbmeta") || stem in SKIPPABLE_PARTITIONS
    }

    private val SKIPPABLE_PARTITIONS = setOf(
        "dtbo", "dtb", "abl", "xbl", "xbl_config", "tz", "hyp", "aop", "devcfg", "keymaster", "cmnlib", "cmnlib64",
        "qupfw", "uefisecapp", "imagefv", "modem", "bluetooth",
        "pmic", "rpm", "sbl1", "uefi", "uefi_varstore", "dsp", "multiimgoem", "featenabler", "shrm", "storsec",
        "toolsfv", "cpucp", "qweslicstore", "vm-bootsys", "tee", "splash",
    )

    private fun isOemName(base: String, bare: Boolean = false) = PartitionNames.isOem(base, bare)

    private fun isVendorName(base: String, bare: Boolean = false) = PartitionNames.isVendor(base, bare)

    private fun isExtraName(base: String, bare: Boolean = false) = PartitionNames.extraMount(base, bare) != null

    
    private fun importExtra(src: RandomSource, base: String) {
        val mount = PartitionNames.extraMount(base, bareOk = true) ?: return
        if (src.size < 4096) return
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, mount)
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("$mount: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
        }
    }

    
    private fun importVendor(src: RandomSource) {
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "vendor")
            gotVendor = true
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("vendor: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
        }
    }

    
    private fun importSplitSparse(parts: List<File>, partition: String) {
        val chans = parts.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
        try {
            val srcs = chans.map { ChannelSource(it) }
            val names = parts.joinToString { it.name }
            if (!srcs.all { SparseSource.probe(it) }) { log("ignoring ${parts.first().name}: not a sparse image set"); return }
            log("sparse $partition in ${srcs.size} part(s): $names")
            if (partition == "system") { importImage(SparseSource(srcs), "system"); gotSystem = true }
            else try {
                importImage(SparseSource(srcs), partition)
                if (partition == "vendor") gotVendor = true
            } catch (t: Throwable) {
                if (cancelled) throw t
                log("$partition: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            }
        } finally { chans.forEach { it.close() }; parts.forEach { it.delete() } }
    }

    
    private fun takeCsc(s: InputStream, name: String) {
        if (pendingCsc != null) { drain(s); return }
        pendingCsc = spill(s, name)
    }

    
    private fun applyPendingCsc() {
        val f = pendingCsc ?: return
        pendingCsc = null
        if (!CscImport.isSamsung(root)) return
        val scratch = File(tmp, "csc-${System.nanoTime()}").apply { mkdirs() }
        try {
            val csc = f.inputStream().use { CscPackage.read(it, f.name, scratch) }
            val n = CscImport.apply(root, csc)
            log("csc: $n files from ${f.name} installed into /system/csc")
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("csc: skipped ${f.name} (${t.message ?: t.javaClass.simpleName})")
        } finally { deleteTree(scratch) }
    }

    
    private fun importOem(src: RandomSource) {
        if (gotOem) return
        val dir = File(root, "oem")
        val existed = dir.exists()
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "oem")
            gotOem = true
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("oem: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            symlinks.removeAll { it.second.startsWith("/oem/") }
            if (!existed) deleteTree(dir)
        }
    }

    
    internal fun importOemInto(image: File, dest: File) {
        root = dest
        File(dest, "oem").mkdirs()
        FileChannel.open(image.toPath(), StandardOpenOption.READ).use { c ->
            val src = ChannelSource(c)
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "oem")
        }
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            if (!rel.startsWith("oem/")) continue
            val f = File(root, rel)
            if (!f.canonicalPath.startsWith(File(root, "oem").canonicalPath)) continue
            f.parentFile?.mkdirs()
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(relTarget(link, target), f.absolutePath)
            }
        }
    }

    
    internal fun importPartitionInto(image: File, dest: File, mount: String, vm: VmPaths) {
        paths = vm
        root = dest
        File(dest, mount).mkdirs()
        FileChannel.open(image.toPath(), StandardOpenOption.READ).use { c ->
            val src = ChannelSource(c)
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, mount)
        }
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            if (!rel.startsWith("$mount/")) continue
            val f = File(root, rel)
            if (!f.canonicalPath.startsWith(File(root, mount).canonicalPath)) continue
            f.parentFile?.mkdirs()
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(relTarget(link, target), f.absolutePath)
            }
        }
    }

    
    private fun importZipHuaweiApp(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } 
            if (e.dataOffset > 0) { importHuaweiApp(ChannelSource(ch, e.dataOffset, e.size), base, strict = false); return }
        }
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importHuaweiApp(ChannelSource(c), base, strict = false) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    
    private fun importZipGptDisk(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } 
            if (e.dataOffset > 0) { importDiskImage(ChannelSource(ch, e.dataOffset, e.size), base); return }
        }
        if (zip.getInputStream(e).use { importDiskStream(it, base) }) return
        log("$base: not readable as a stream, copying it out first")
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importDiskImage(ChannelSource(c), base) }
        } catch (ex: IOException) {
            if (cancelled) throw ex
            log("$base: partition table unreadable, skipped (${ex.message})")
        } finally { t.delete() }
    }

    
    private fun zipEntryHead(zip: ZipFile, e: ZipArchiveEntry, n: Int): ByteArray = zip.getInputStream(e).use { s ->
        val b = ByteArray(n)
        var o = 0
        while (o < n) { val r = s.read(b, o, n - o); if (r <= 0) break; o += r }
        b.copyOf(o)
    }

    
    private fun importZipAcerBin(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } 
            if (e.dataOffset > 0) { importAcerBin(ChannelSource(ch, e.dataOffset, e.size), base, strict = false); return }
        }
        onProgress(lt(R.string.imp_unpacking, base), -1f)
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importAcerBin(ChannelSource(c), base, strict = false) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    
    private fun importAcerBin(src: RandomSource, name: String, strict: Boolean) {
        val label = name.replace('\\', '/').substringAfterLast('/')
        val entries = try { AcerBin.entries(src) } catch (ex: IOException) {
            if (strict) throw ex
            log("skipped $label: ${ex.message}"); return
        }
        val profile = AcerBin.profile(src, entries)
        log("acer $label: ${entries.size} images" +
            (profile["PROJECT"]?.let { ", project $it" } ?: "") + (profile["PLATFORM"]?.let { ", platform $it" } ?: "") +
            (profile["MODEL"]?.let { ", model $it" } ?: ""))
        fun slice(e: AcerBin.Entry): RandomSource = SliceSource(src, e.offset, e.size)
        fun head(e: AcerBin.Entry, n: Int): ByteArray {
            val b = ByteBuffer.allocate(minOf(n.toLong(), e.size).toInt()); slice(e).read(0, b); return b.array()
        }

        
        for (e in entries) {
            if (cancelled) throw IOException("cancelled")
            if (e.size < 4096 || e.size > 64_000_000) continue
            if (String(head(e, 8), Charsets.ISO_8859_1) != "ANDROID!") continue
            val data = readAllFrom(slice(e))
            if (isRecoveryName(e.base) || hasRecoveryBinary(data)) takeRecovery(data.inputStream(), "recovery (${e.base})") else takeBoot(data)
        }

        
        if (ramdisk == null) entries.firstOrNull { AcerBin.isRootfs(it.base) && it.size > 0 }?.let { takeYaffs2Ramdisk(slice(it), "$label ${it.base}") }

        
        val fsEntries = ArrayList<AcerBin.Entry>()
        val systemEntry = entries.firstOrNull { isSystemImageName(it.base) && it.size > 0 } ?: run {
            for (e in entries) {
                if (e.size < (4L shl 20) || AcerBin.isRootfs(e.base) || e.base.contains("ftm", true)) continue
                val h = head(e, 8)
                if (String(h, Charsets.ISO_8859_1) == "ANDROID!" || (h[0].toInt() == 0x7f && h[1] == 'E'.code.toByte())) continue
                val s = slice(e)
                if (SparseSource.probe(s) || Ext4Reader.probe(s) || Yaffs2Reader.probe(s) || UbiFsReader.probe(s) || ErofsReader.probe(s)) fsEntries.add(e)
            }
            fsEntries.firstOrNull { looksLikeSystem(slice(it)) }
        }
        if (systemEntry == null || gotSystem) {
            val what = if (gotSystem) "system already imported" else "no system partition"
            if (strict && !gotSystem) throw LocalizedIOException("\"$label\" has no system partition (not a full Acer firmware package?)", R.string.imp_has_no_system_partition_not_a, label)
            log("acer $label: $what")
            return
        }
        val s = slice(systemEntry)
        importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
        for (e in entries) {
            if (e === systemEntry) continue
            if (e.base.endsWith(".img", true) && e.size >= 4096)
                log("acer $label: skipped ${e.base} (${e.size shr 10} KB)")
        }
    }

    
    private fun takeYaffs2Ramdisk(src: RandomSource, label: String) {
        if (ramdisk != null) return
        try {
            val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
            val out = ArrayList<BootImage.CpioEntry>()
            var total = 0L
            fs.walk { path, node ->
                val perm = node.mode and 0xfff
                when (node.type) {
                    3 -> { out.add(BootImage.CpioEntry(path, 0x4000 or (if (perm != 0) perm else 0x1ed), ByteArray(0))) }
                    2 -> { if (node.alias.isNotEmpty()) out.add(BootImage.CpioEntry(path, 0xA000 or 0x1ff, node.alias.toByteArray())) }
                    1, 4 -> {
                        if (node.size > MAX_RAMDISK_FILE) { log("ramdisk $label: skipped $path (${node.size shr 20} MB)"); return@walk }
                        val bytes = java.io.ByteArrayOutputStream(node.size.toInt().coerceAtLeast(16))
                        fs.copy(node, bytes)
                        total += bytes.size()
                        if (total > MAX_RAMDISK) throw LocalizedIOException("ramdisk too large", R.string.imp_ramdisk_too_large)
                        out.add(BootImage.CpioEntry(path, 0x8000 or (fs.fileMode(node) and 0xfff), bytes.toByteArray()))
                    }
                    else -> {} 
                }
            }
            fs.warnings.forEach { log("ramdisk $label: $it") }
            if (out.none { it.name == "init.rc" }) { log("ramdisk $label: no init.rc, not a root file system"); return }
            ramdisk = out
            log("boot: ramdisk from YAFFS2 $label, ${out.size} entries")
        } catch (ex: IOException) {
            if (cancelled) throw ex
            log("ramdisk $label: ${ex.message}, skipped")
        }
    }

    
    private fun importHuaweiApp(src: RandomSource, name: String, strict: Boolean) {
        val label = name.replace('\\', '/').substringAfterLast('/')
        val packets = HuaweiApp.packets(src)
        if (packets.isEmpty()) {
            if (strict) throw LocalizedIOException("not a Huawei update package: $label", R.string.imp_not_a_huawei_update_package, label)
            log("skipped $label: not a Huawei update package"); return
        }
        log("huawei $label: ${packets.size} packets, hardware ${packets[0].hardware.ifEmpty { "?" }}, built ${packets[0].date}")
        fun slice(p: HuaweiApp.Packet): RandomSource = SliceSource(src, p.dataOffset, p.size)
        fun head(p: HuaweiApp.Packet, n: Int): ByteArray {
            val b = ByteBuffer.allocate(minOf(n.toLong(), p.size).toInt()); slice(p).read(0, b); return b.array()
        }

        
        for (p in packets) {
            if (cancelled) throw IOException("cancelled")
            if (p.size < 4096 || p.size > 64_000_000) continue
            if (String(head(p, 8), Charsets.ISO_8859_1) != "ANDROID!") continue
            val data = readAllFrom(slice(p))
            val tag = "$label id 0x%02x".format(p.id)
            if (hasRecoveryBinary(data)) takeRecovery(data.inputStream(), "recovery ($tag)") else takeBoot(data)
        }

        
        val fsParts = packets.filter { p ->
            if (p.size < (4L shl 20)) return@filter false
            val h = head(p, 8)
            if (String(h, Charsets.ISO_8859_1) == "ANDROID!" || (h[0].toInt() == 0x7f && h[1] == 'E'.code.toByte())) return@filter false
            val s = slice(p)
            SparseSource.probe(s) || Ext4Reader.probe(s) || Yaffs2Reader.probe(s) || UbiFsReader.probe(s) || ErofsReader.probe(s)
        }
        val systemPacket = fsParts.firstOrNull { it.id == HuaweiApp.ID_SYSTEM }
            ?: fsParts.firstOrNull { looksLikeSystem(slice(it)) }
        if (systemPacket == null || gotSystem) {
            val what = if (gotSystem) "system already imported" else "no system partition"
            if (strict && !gotSystem) throw LocalizedIOException("\"$label\" has no system partition (customization-only package?); import UPDATE.APP instead", R.string.imp_has_no_system_partition_customization_only, label)
            log("huawei $label: $what; skipped ${fsParts.size} other partition(s)")
            return
        }
        onProgress(lt(R.string.imp_verifying, label), -1f)
        val bad = HuaweiApp.verify(src, systemPacket) { if (cancelled) throw IOException("cancelled") }
        if (bad > 0) log("huawei $label: $bad block(s) of the system image fail their CRC, the download may be damaged")
        val s = slice(systemPacket)
        importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
        for (p in fsParts) if (p !== systemPacket) log("huawei $label: skipped partition id 0x%02x (%d KB)".format(p.id, p.size shr 10))
    }

    private fun looksLikeSystem(src: RandomSource): Boolean = runCatching {
        var found = false
        val s = if (SparseSource.probe(src)) SparseSource(listOf(src)) else MotoImage.unwrap(src)
        if (UbiFsReader.probe(s)) UbiFsReader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        else if (Yaffs2Reader.probe(s)) Yaffs2Reader(s).walk { path, _ -> if (path == "build.prop" || path == "framework" || path == "javalib") found = true }
        else if (Ext4Reader.probe(s)) Ext4Reader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        else if (ErofsReader.probe(s)) ErofsReader(s).let { fs ->
            found = fs.lookup("build.prop") != null || fs.lookup("framework") != null || fs.lookup("system/build.prop") != null
        }
        found
    }.getOrDefault(false)

    
    private fun importProbableSystem(src: RandomSource, label: String): Boolean {
        val probe = if (SparseSource.probe(src)) SparseSource(listOf(src)) else src
        val ok = Ext4Reader.probe(probe) || UbiFsReader.probe(probe) || Yaffs2Reader.probe(probe) ||
            ErofsReader.probe(probe) || MotoImage.probe(probe) || looksLikeSystem(probe)
        if (ok) {
            log("$label: importing as system image (content detected)")
            importImage(probe, "system")
        }
        return ok
    }

    private fun isAndroidImage(h: ByteArray) = h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!"
    private fun isLz4Frame(h: ByteArray) = h.size >= 4 && h[0] == 0x04.toByte() && h[1] == 0x22.toByte() && h[2] == 0x4d.toByte() && h[3] == 0x18.toByte()
    
    private fun recoveryLabel(name: String, h: ByteArray): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        return if (isLz4Frame(h) && !base.endsWith(".lz4", true)) "$base.lz4" else base
    }
    private fun hasRecoveryBinary(img: ByteArray): Boolean =
        runCatching { BootImage.ramdisk(img)?.any { it.name.removePrefix("./") == "sbin/recovery" } == true }.getOrDefault(false)

    
    private fun isBootName(base: String, bare: Boolean = false) = PartitionNames.isBoot(base, bare)

    
    private fun takeRecovery(i: InputStream, base: String) {
        runCatching {
            val data = BootImage.stripHtcSignature((if (base.endsWith(".lz4", true))
                org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(i) else i).readBytes())
            if (recovery == null && data.size >= 512) { recovery = data; log("recovery: found $base, ${data.size shr 10} KB") }
        }.onFailure { log("recovery: cannot read $base (${it.message}), skipped") }
    }

    
    private fun rebuildRecoveryFromBoot() {
        fun tree(rel: String): ByteArray? = File(root, rel).takeIf { it.isFile && it.length() in 1..(64L shl 20) }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        val patch = rfbPatch ?: tree("system/recovery-from-boot.p") ?: return
        val script = rfbScript ?: (tree("system/etc/install-recovery.sh") ?: tree("system/bin/install-recovery.sh"))?.toString(Charsets.UTF_8)
        val bonus = rfbBonus ?: tree("system/etc/recovery-resource.dat")
        val boot = File(paths.dir, "boot.img").takeIf { it.isFile }?.readBytes()
        if (boot == null) { log("recovery: recovery-from-boot.p found, but the firmware has no boot image to patch"); return }
        log("recovery: no recovery image, rebuilding it from the boot image and recovery-from-boot.p")
        RecoveryFromBoot.rebuild(boot, script, patch, bonus, log)?.let { takeRecovery(it.inputStream(), "recovery (recovery-from-boot.p)") }
        rfbPatch = null; rfbScript = null; rfbBonus = null
    }

    private fun isTar(h: ByteArray) = h.size > 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar"

    private fun streamOf(src: RandomSource): InputStream = object : InputStream() {
        var pos = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= src.size) return -1
            val k = minOf(len.toLong(), src.size - pos).toInt()
            src.read(pos, ByteBuffer.wrap(b, off, k))
            pos += k
            return k
        }
        
        override fun skip(n: Long): Long {
            val k = minOf(maxOf(n, 0L), maxOf(src.size - pos, 0L))
            pos += k
            return k
        }
    }.let { BufferedInputStream(it, 1 shl 20) }

    private fun readAllFrom(src: RandomSource): ByteArray {
        val b = ByteBuffer.allocate(src.size.toInt()); src.read(0, b); return b.array()
    }

    private var zipChannel: FileChannel? = null
    
    private var deferred: MutableList<Pair<File, String>>? = null

    private fun importZip(ch: FileChannel, name: String, depth: Int) {
        val zip = ZipFile.builder().setSeekableByteChannel(ch).get()
        val prevCh = zipChannel
        zipChannel = ch
        try { importZipEntries(zip, name, depth) } finally { zipChannel = prevCh }
    }

    private fun importZipEntries(zip: ZipFile, name: String, depth: Int) {
        val entries = zip.entries.toList()
        val total = entries.sumOf { maxOf(0L, it.size) }.coerceAtLeast(1)
        var done = 0L
        
        val extraEntries = ArrayList<Pair<String, ZipArchiveEntry>>()
        
        val extraTars = ArrayList<Pair<String, ZipArchiveEntry>>()
        
        entries.firstOrNull { it.name.endsWith("META-INF/com/google/android/updater-script") }?.let { e ->
            zip.getInputStream(e).use { parseUpdaterScript(String(it.readBytes())) }
        }
        
        val wrap = entries.firstOrNull { it.name.endsWith("META-INF/com/google/android/updater-script") }
            ?.name?.substringBefore("META-INF/")?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 } ?: ""
        if (wrap.isNotEmpty()) log("archive with wrapper folder \"${wrap.trimEnd('/')}\"")
        val names = entries.map { it.name.replace('\\', '/').removePrefix(wrap) }
        
        val foundSysRoot = findSystemRoot(names.filter { !it.contains("__MACOSX/") })
        if (foundSysRoot != null && foundSysRoot.isNotEmpty()) log("system folder dump under \"${foundSysRoot.trimEnd('/')}\"")
        
        val rawDump = foundSysRoot == null && PartitionNames.isRawSystemDump(names)
        
        
        val plan = planSafely { planZip(zip, entries, wrap, foundSysRoot, rawDump) }
        
        val sysRoot = if (plan?.dropSystemFolder == true) null else foundSysRoot
        if (rawDump) log("raw system partition dump (build.prop and bin/ at the archive root)")
        
        archiveHasVendorImage = entries.any { !it.isDirectory && it.size > 0 && it.name.replace('\\', '/').substringAfterLast('/').let { b -> PartitionNames.isVendor(b) || PartitionChoice.tarGroup(b) == "vendor" } }
        val bootEntries = ArrayList<Pair<String, ZipArchiveEntry>>()
        val pendingZips = ArrayList<ZipArchiveEntry>()
        
        val sparseChunks = SparseChunks.select(entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") }.map { it.name })
            .let { sel -> val byName = entries.associateBy { it.name }; sel.mapNotNull { byName[it] } }
        val rawProgramEntries = entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") &&
            it.name.substringAfterLast('/').matches(Regex("(?i)rawprogram.*\\.xml")) }
        for (e in entries) {
            if (cancelled) throw IOException("cancelled")
            val n = e.name.replace('\\', '/').removePrefix(wrap)
            
            val base = plan?.canonical?.get(e.name) ?: n.substringAfterLast('/')
            val bare = PartitionNames.bareOk(n, e.size) && !e.isUnixSymlink
            when {
                n.contains("__MACOSX/") || base.startsWith("._") -> {}
                
                plan != null && e.name in plan.skip -> {}
                rawDump -> {
                    val rel = "system/" + n.trimStart('/')
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, rel).mkdirs()
                        e.isUnixSymlink -> {
                            val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                            if (target.isNotEmpty()) symlinks.add(target to "/$rel")
                        }
                        else -> zip.getInputStream(e).use { writeFile(rel, it, e.unixMode.takeIf { m -> m != 0 }) }
                    }
                    gotSystem = true
                }
                e.isDirectory -> {}
                
                systemRel(sysRoot, n) != null -> {
                    val srel = systemRel(sysRoot, n)!!
                    if (e.isUnixSymlink) {
                        
                        
                        val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }.trim('\u0000', '\n', '\r')
                        if (target.isNotEmpty()) symlinks.add(target to "/$srel")
                    } else zip.getInputStream(e).use { writeFile(srel, it, e.unixMode.takeIf { m -> m != 0 }) }
                    gotSystem = true
                }
                
                
                
                PartitionNames.isDiskImageName(base) && e.size > (16L shl 20) && zipEntryHead(zip, e, 4104).let { GptDisk.probe(it) || MbrDisk.probe(it) } ->
                    importZipGptDisk(zip, e, base)
                
                RecoveryFromBoot.isPatchFile(base) && e.size in 1..(32L shl 20) -> zip.getInputStream(e).use { rfbPatch = it.readBytes() }
                RecoveryFromBoot.isScriptFile(base) && e.size in 1..(1L shl 20) ->
                    zip.getInputStream(e).use { val t = String(it.readBytes(), Charsets.UTF_8); if (rfbScript == null) rfbScript = t }
                RecoveryFromBoot.isBonusFile(base) && e.size in 1..(8L shl 20) -> zip.getInputStream(e).use { rfbBonus = it.readBytes() }
                base.equals("system.yaffs2.img", true) -> withEntrySource(zip, e) { importImage(it, "system") }
                base.endsWith(".yaffs2.img", true) -> {} 
                
                
                NandDump.isMtdName(base) && e.size > 0 -> withEntrySource(zip, e) { importMtdDump(it, base) }
                
                base.endsWith(".sin", true) -> withSinSource(zip, e) { importSin(it, base) }
                
                base.endsWith(".ftf", true) && e.size > 0 -> importSonyFile(spillEntry(zip, e), depth)
                
                isBootName(base, bare) -> bootEntries.add(n to e)
                isRecoveryName(base, bare) && e.size < 64_000_000 -> zip.getInputStream(e).use { takeRecovery(it, base) }
                CscPackage.isCscContainer(base) && e.size > 0 -> zip.getInputStream(e).use { takeCsc(it, base) }
                isOemName(base, bare) -> withEntrySource(zip, e) { importOem(it) }
                isSystemImageName(base, bare) ->
                    withEntrySource(zip, e) { importImage(if (SparseSource.probe(it)) SparseSource(listOf(it)) else it, "system") }
                isVendorName(base, bare) -> withEntrySource(zip, e) { importVendor(it) }
                isExtraName(base, bare) && e.size > 4096 -> extraEntries.add(base to e)
                base.lowercase().endsWith(".zip") && (base.startsWith("image-") || depth == 0 && e.size > 50_000_000) ||
                    base.lowercase().endsWith(".rar") || base.lowercase().endsWith(".7z") -> {
                    handleNestedZip(spillEntry(zip, e), base, depth)
                }
                base.lowercase().endsWith(".zip") && e.size > 0 && !SKIP_NESTED.matches(base) -> pendingZips.add(e)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5|md5|tgz|tar\\.gz)")) 
                    
                    && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                    val tarPart = PartitionChoice.tarGroup(base)
                    if (tarPart != null && PartitionChoice.mountOf(tarPart).startsWith("system/")) extraTars.add(base to e)
                    else importZipTar(zip, e, base, depth)
                }
                
                base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) ||
                    base.endsWith(".transfer.list", true) -> {}
                base.endsWith(".app", true) && e.size > 0 -> importZipHuaweiApp(zip, e, base)
                
                (base.endsWith(".nb0", true) || base.endsWith(".bin", true)) && e.size > AcerBin.MIN_SIZE &&
                    AcerBin.probe(zipEntryHead(zip, e, 1100)) -> importZipAcerBin(zip, e, base)
                
                base.equals("payload.bin", true) && e.size > 0 -> importToolsetFile(spillEntry(zip, e), base, depth + 1)
                recognizedFirmwareTool(base) -> {
                    val nestedFile = spillEntry(zip, e)
                    try {
                        FileChannel.open(nestedFile.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, nestedFile.name, depth + 1)
                        }
                    } finally { nestedFile.delete() }
                }
                else -> {
                    if (e.size >= 4096 && PartitionNames.isDiskImageName(base)) withEntrySource(zip, e) { src ->
                        if (!importProbableSystem(src, base)) log("$base: unrecognized file, skipped")
                    }
                }
            }
            done += maxOf(0L, e.size)
            onProgress(lt(R.string.imp_extracting, base), 0.9f * done / total)
        }
        for ((_, be) in bootEntries.sortedBy { it.first.count { c -> c == '/' } }) {
            if (ramdisk != null) break
            zip.getInputStream(be).use { takeBoot(it.readBytes()) }
        }
        
        
        if (ramdisk == null && !systemAsRoot) takeLooseRamdisk(zip, entries, wrap, foundSysRoot)
        for (partition in listOf("system", "vendor", "product", "system_ext", "odm")) {
            if (partition == "system" && gotSystem) continue
            val dataE = entries.firstOrNull { it.name.matches(Regex("(?i)(.*/)?$partition\\.new\\.dat(\\.br)?")) } ?: continue
            val dir = dataE.name.substringBeforeLast('/', "")
            val listE = entries.firstOrNull {
                it.name.substringBeforeLast('/', "") == dir &&
                    it.name.substringAfterLast('/').equals("$partition.transfer.list", true)
            }
            if (listE == null) {
                if (partition == "system") throw LocalizedIOException("$partition.transfer.list missing", R.string.imp_transfer_list_missing, partition)
                log("$partition.transfer.list missing, skipping $partition.new.dat"); continue
            }
            val list = zip.getInputStream(listE).use { String(it.readBytes(), Charsets.UTF_8) }
            onProgress(lt(R.string.imp_building_img_from_ota, partition), 0.5f)
            val raw = File(tmp, "$partition.raw")
            
            
            val mount = if (partition == "product" || partition == "system_ext") "system/$partition" else partition
            try {
                zip.getInputStream(dataE).use { s ->
                    val data = if (dataE.name.endsWith(".br", true)) BrotliInputStream(BufferedInputStream(s, 1 shl 20)) else s
                    TransferList.build(list, data, raw)
                }
                keepStockImage = partition == "system"
                FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                    if (partition == "system") importImage(ChannelSource(c), mount)
                    else runCatching { importImage(ChannelSource(c), mount) }.onFailure { if (cancelled) throw it; log("$partition partition: ${it.message}") }
                }
            } finally { keepStockImage = false; raw.delete() }
        }
        for ((base, e) in extraEntries) withEntrySource(zip, e) { importExtra(it, base) }
        for ((base, e) in extraTars) { if (cancelled) throw IOException("cancelled"); importZipTar(zip, e, base, depth) }
        if (sparseChunks.isNotEmpty() && !gotSystem) {
            val parts = sparseChunks.map { spillEntry(zip, it) }
            val chans = parts.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
            try {
                val srcs = chans.map { ChannelSource(it) }
                
                if (srcs.all { SparseSource.probe(it) }) {
                    log("sparse system in ${srcs.size} part(s): ${sparseChunks.joinToString { it.name.substringAfterLast('/') }}")
                    importImage(SparseSource(srcs), "system")
                } else log("ignoring ${sparseChunks.first().name}: not a sparse image set")
            } finally { chans.forEach { it.close() }; parts.forEach { it.delete() } }
        }
        
        if (!gotVendor) {
            val byName = entries.associateBy { it.name }
            val vendorChunks = SparseChunks.select(
                entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") }.map { it.name }, "vendor").mapNotNull { byName[it] }
            if (vendorChunks.isNotEmpty()) importSplitSparse(vendorChunks.map { spillEntry(zip, it) }, "vendor")
        }
        if (rawProgramEntries.isNotEmpty() && (!gotSystem || ramdisk == null || recovery == null || !gotOem)) importEdl(zip, entries, rawProgramEntries)
        
        for (pe in pendingZips) {
            if (cancelled) throw IOException("cancelled")
            if (gotSystem && ramdisk != null) break
            val nested = spillEntry(zip, pe)
            if (!gotSystem) handleNestedZip(nested, pe.name.substringAfterLast('/'), depth)
            else try { bootFromArchive(nested, pe.name) } finally { nested.delete() }
        }
        if (!gotSystem) log("no system found in archive $name")
    }

    

    
    private class ArchivePlan(val skip: Set<String>, val canonical: Map<String, String>, val dropSystemFolder: Boolean)

    
    private class Member(val key: String, val name: String, val size: Long, val index: Int, val offset: Long = -1)

    
    private interface MemberReader {
        fun head(m: Member, n: Int): ByteArray
        fun <T> source(m: Member, block: (RandomSource) -> T): T
        fun <T> stream(m: Member, block: (InputStream) -> T): T
    }

    
    private class Candidate(
        val kind: PartitionChoice.Kind, val label: String, val slot: Char?,
        val members: List<Member>, var identity: BuildIdentity? = null,
    )

    
    private class GroupPlan(val group: String, val all: List<Candidate>, val keep: List<Candidate>)

    
    private class Inspected(val blank: Boolean, val props: Map<String, String>?)

    private class FirstEntry : RuntimeException()

    
    private class NonClosingChannel(private val c: java.nio.channels.SeekableByteChannel) : java.nio.channels.SeekableByteChannel by c {
        override fun close() {}
    }

    
    private fun planPartitionChoices(members: List<Member>, reader: MemberReader, sysRoot: String?, rawDump: Boolean, lz4Names: Boolean = false): ArchivePlan? {
        if (rawDump) return null
        class Raw(val m: Member, val base: String, val kind: PartitionChoice.Kind, val group: String)

        val raws = ArrayList<Raw>()
        val folderFiles = ArrayList<Member>()
        for (m in members) {
            val n = m.name
            val base = n.substringAfterLast('/')
            if (n.contains("__MACOSX/") || base.startsWith("._")) continue
            
            if (systemRel(sysRoot, n) != null) { folderFiles.add(m); continue }
            val tar = PartitionChoice.tarGroup(base)
            if (tar != null) { raws.add(Raw(m, base, PartitionChoice.Kind.TAR, tar)); continue }
            val group = PartitionChoice.imageGroup(base, PartitionNames.bareOk(n, m.size), lz4Names) ?: continue
            raws.add(Raw(m, base, PartitionChoice.Kind.IMAGE, group))
        }
        val doubled = raws.map { it.group }.distinct()
            .filter { g -> raws.count { it.group == g } + (if (g == "system" && sysRoot != null) 1 else 0) >= 2 }
            .sortedWith(compareBy({ PartitionChoice.groupRank(it) }, { it }))
        if (doubled.isEmpty()) return null

        
        val plans = ArrayList<GroupPlan>()
        for (g in doubled) {
            val mine = raws.filter { it.group == g }
            
            val clash = mine.map { it.base.lowercase() }.distinct().size < mine.size
            val all = ArrayList<Candidate>()
            for (r in mine) all.add(Candidate(r.kind, if (clash) r.m.name else r.base, PartitionChoice.slotOf(r.base), listOf(r.m)))
            if (g == "system" && sysRoot != null) all.add(Candidate(PartitionChoice.Kind.FOLDER, sysRoot + "system", null, folderFiles))
            val keep = ArrayList<Candidate>()
            for (c in all.sortedBy { PartitionChoice.optionOrder(it.kind, it.slot) }) {
                if (cancelled) throw IOException("cancelled")
                onProgress(lt(R.string.imp_inspecting, c.label), -1f)
                val seen = when (c.kind) {
                    PartitionChoice.Kind.IMAGE -> inspectImageMember(reader, c.members[0], g)
                    PartitionChoice.Kind.TAR -> inspectTarMember(reader, c.members[0], g)
                    PartitionChoice.Kind.FOLDER -> inspectSystemFolder(reader, folderFiles, sysRoot!!)
                }
                if (seen.blank) { log("${c.label}: empty, not offered"); continue }
                c.identity = seen.props?.let { PartitionChoice.identity(g, it) }
                keep.add(c)
            }
            
            if (keep.isNotEmpty()) plans.add(GroupPlan(g, all, keep))
        }
        if (plans.isEmpty()) return null

        
        val questions = plans.count { it.keep.size > 1 }
        var asked = 0
        val skip = HashSet<String>()
        val canonical = HashMap<String, String>()
        var dropFolder = false
        for (plan in plans) {
            val chosen = if (plan.keep.size == 1) plan.keep[0] else {
                val options = plan.keep.map { c ->
                    PartitionChoice.line(c.label, c.identity, { lt(R.string.imp_choice_api, it) }, { lt(R.string.imp_choice_build, it) })
                }
                val pick = chooser?.choose(PartitionPrompt(plan.group, ++asked, questions, options)) ?: 0
                if (pick == PartitionChooser.CANCEL || cancelled) throw IOException("cancelled")
                plan.keep.getOrElse(pick) { plan.keep[0] }.also {
                    log("${plan.group}: ${plan.keep.size} candidates (${plan.keep.joinToString { k -> k.label }}), taking ${it.label}")
                }
            }
            for (c in plan.all) {
                if (c !== chosen) { for (m in c.members) skip.add(m.key); continue }
                if (c.kind == PartitionChoice.Kind.FOLDER) continue
                val m = c.members[0]
                val base = m.name.substringAfterLast('/')
                
                val plain = PartitionChoice.importName(base, PartitionNames.bareOk(m.name, m.size), lz4Names)
                if (plain != base) canonical[m.key] = plain
                
                if (plan.group == "system" && sysRoot != null) dropFolder = true
            }
        }
        if (dropFolder) for (m in folderFiles) skip.add(m.key)
        return ArchivePlan(skip, canonical, dropFolder)
    }

    
    private fun <T> planSafely(block: () -> T?): T? = try { block() } catch (t: Throwable) {
        if (cancelled || t.message == "cancelled") throw t
        log("choice between several images skipped: ${t.message ?: t.javaClass.simpleName}")
        null
    }

    private fun readUpTo(s: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) { val r = s.read(b, o, n - o); if (r <= 0) break; o += r }
        return b.copyOf(o)
    }

    
    private fun <T> withSpilledSource(input: InputStream, name: String, block: (RandomSource) -> T): T {
        val t = spill(input, name)
        try {
            return FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    
    private fun <T> withInspectSource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> T): T {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } 
            if (e.dataOffset > 0) return block(ChannelSource(ch, e.dataOffset, e.size))
        }
        val t = spillEntry(zip, e)
        try {
            return FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        } finally { t.delete() }
    }

    private fun zipReader(zip: ZipFile, byName: Map<String, ZipArchiveEntry>): MemberReader = object : MemberReader {
        override fun head(m: Member, n: Int): ByteArray = zipEntryHead(zip, byName.getValue(m.key), n)
        override fun <T> source(m: Member, block: (RandomSource) -> T): T = withInspectSource(zip, byName.getValue(m.key), block)
        override fun <T> stream(m: Member, block: (InputStream) -> T): T = zip.getInputStream(byName.getValue(m.key)).use(block)
    }

    
    private fun planZip(zip: ZipFile, entries: List<ZipArchiveEntry>, wrap: String, sysRoot: String?, rawDump: Boolean): ArchivePlan? {
        val members = ArrayList<Member>()
        entries.forEachIndexed { i, e ->
            if (e.isDirectory || e.isUnixSymlink) return@forEachIndexed
            members.add(Member(e.name, e.name.replace('\\', '/').removePrefix(wrap), e.size, i))
        }
        return planPartitionChoices(members, zipReader(zip, entries.associateBy { it.name }), sysRoot, rawDump)
    }

    
    private fun planSevenZ(ch: FileChannel): ArchivePlan? {
        val members = ArrayList<Member>()
        val names = ArrayList<String>()
        ch.position(0)
        SevenZFile.builder().setSeekableByteChannel(NonClosingChannel(ch)).get().use { pz ->
            var i = 0
            for (e in pz.entries) {
                val idx = i++
                val n = e.name.replace('\\', '/').trimStart('/')
                names.add(n)
                if (e.isDirectory) continue
                members.add(Member(n, n, e.size, idx))
            }
        }
        val reader = SevenZMemberReader(ch)
        try {
            return planPartitionChoices(members, reader, findSystemRoot(names), false)
        } finally { reader.close() }
    }

    
    private inner class SevenZMemberReader(private val ch: FileChannel) : MemberReader, AutoCloseable {
        private var z: SevenZFile? = null
        
        private var at = -1
        private var left = 0L
        
        private var headBytes: ByteArray? = null

        private val body = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (cancelled) throw IOException("cancelled")
                if (left <= 0) return -1
                val r = z!!.read(b, off, minOf(len.toLong(), left).toInt())
                if (r > 0) left -= r else if (r < 0) left = 0
                return r
            }
        }

        private fun enter(m: Member) {
            if (at == m.index && headBytes != null) return
            var f = z
            if (f == null || m.index <= at) {
                f?.close()
                ch.position(0)
                f = SevenZFile.builder().setSeekableByteChannel(NonClosingChannel(ch)).get()
                z = f; at = -1; left = 0; headBytes = null
            }
            val sink = ByteArray(1 shl 16)
            while (at < m.index) {
                while (left > 0) if (body.read(sink, 0, sink.size) < 0) break 
                val e = f!!.nextEntry ?: throw IOException("7z entry ${m.name} not found")
                at++
                left = if (e.isDirectory) 0 else e.size
                headBytes = null
            }
        }

        
        private fun whole(): InputStream {
            val front = headBytes ?: ByteArray(0)
            headBytes = null
            return java.io.SequenceInputStream(java.io.ByteArrayInputStream(front), body)
        }

        override fun head(m: Member, n: Int): ByteArray {
            enter(m)
            headBytes?.let { return it.copyOf(minOf(n, it.size)) }
            return readUpTo(body, n).also { headBytes = it }
        }

        override fun <T> source(m: Member, block: (RandomSource) -> T): T {
            enter(m)
            return withSpilledSource(whole(), m.name.substringAfterLast('/'), block)
        }

        override fun <T> stream(m: Member, block: (InputStream) -> T): T {
            enter(m)
            return block(whole())
        }

        override fun close() { z?.close(); z = null }
    }

    
    private class HeadSink(private val limit: Int) : java.io.OutputStream() {
        val buf = java.io.ByteArrayOutputStream()
        override fun write(b: Int) { buf.write(b); if (buf.size() >= limit) throw FirstEntry() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            buf.write(b, off, minOf(len, limit - buf.size()))
            if (buf.size() >= limit) throw FirstEntry()
        }
    }

    
    private fun planRar(file: File): ArchivePlan? {
        RarArchive(file).use { rar ->
            val headers = rar.fileHeaders
            val byName = LinkedHashMap<String, RarFileHeader>()
            val members = ArrayList<Member>()
            val names = ArrayList<String>()
            headers.forEachIndexed { i, h ->
                val n = (h.fileName ?: "").replace('\\', '/').trimStart('/')
                names.add(n)
                if (h.isDirectory || n.isBlank() || n.split('/').any { it == ".." }) return@forEachIndexed
                byName[n] = h
                members.add(Member(n, n, h.fullUnpackSize, i))
            }
            fun extractTo(m: Member): File {
                val dir = File(tmp, "rarplan-${System.nanoTime()}").apply { mkdirs() }
                val f = File(dir, m.name.substringAfterLast('/'))
                f.outputStream().buffered(1 shl 20).use { out -> rar.extractFile(byName.getValue(m.key), out) }
                return f
            }
            val reader = object : MemberReader {
                override fun head(m: Member, n: Int): ByteArray {
                    val sink = HeadSink(n)
                    try { rar.extractFile(byName.getValue(m.key), sink) } catch (t: Throwable) { if (cancelled) throw IOException("cancelled") }
                    return sink.buf.toByteArray()
                }
                override fun <T> source(m: Member, block: (RandomSource) -> T): T {
                    val f = extractTo(m)
                    try { return FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) } }
                    finally { deleteTree(f.parentFile ?: f) }
                }
                override fun <T> stream(m: Member, block: (InputStream) -> T): T {
                    val f = extractTo(m)
                    try { return f.inputStream().use(block) } finally { deleteTree(f.parentFile ?: f) }
                }
            }
            return planPartitionChoices(members, reader, findSystemRoot(names), false)
        }
    }

    
    private fun planStreamArchive(open: () -> org.apache.commons.compress.archivers.ArchiveInputStream<*>, under: RandomSource?): ArchivePlan? {
        val members = ArrayList<Member>()
        open().use { a ->
            var i = 0
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = a.nextEntry ?: break
                val idx = i++
                val regular = when (e) {
                    is TarArchiveEntry -> e.isFile && !e.isSymbolicLink && !e.isLink
                    is CpioArchiveEntry -> e.isRegularFile
                    else -> !e.isDirectory
                }
                if (!regular) continue
                val n = e.name.removePrefix("./").trimStart('/')
                if (n.isEmpty() || n == "TRAILER!!!") continue
                members.add(Member(n, n, e.size, idx, if (under != null) a.bytesRead else -1L))
            }
        }
        
        val sysRoot = if (members.any { systemRel("", it.name) != null }) "" else null
        val reader = object : MemberReader {
            override fun head(m: Member, n: Int): ByteArray = stream(m) { readUpTo(it, n) }
            override fun <T> source(m: Member, block: (RandomSource) -> T): T =
                if (under != null && m.offset >= 0) block(ExtentSource(under, listOf(m.offset to m.size)))
                else stream(m) { s -> withSpilledSource(s, m.name.substringAfterLast('/'), block) }
            override fun <T> stream(m: Member, block: (InputStream) -> T): T {
                if (under != null && m.offset >= 0) return block(streamOf(ExtentSource(under, listOf(m.offset to m.size))))
                return open().use { a ->
                    var i = 0
                    while (true) {
                        a.nextEntry ?: throw IOException("archive changed while it was read")
                        if (i++ == m.index) break
                    }
                    block(a.nonClosing())
                }
            }
        }
        return planPartitionChoices(members, reader, sysRoot, false, lz4Names = true)
    }

    
    private fun inspectImageMember(reader: MemberReader, m: Member, group: String): Inspected {
        val head = try { reader.head(m, 4096) } catch (t: Throwable) {
            if (cancelled) throw IOException("cancelled")
            return Inspected(false, null) 
        }
        if (m.size == 0L || head.isEmpty() || PartitionChoice.isBlank(head, head.size)) return Inspected(true, null)
        
        if (group == "boot" || group == "recovery") return Inspected(false, null)
        if (group != "system" && m.size in 0L..4096L) return Inspected(true, null)
        
        if (ImageCodecs.SUFFIX.containsMatchIn(m.name)) return Inspected(false, null)
        return try { reader.source(m) { inspectFilesystem(it) } } catch (t: Throwable) {
            if (cancelled) throw IOException("cancelled")
            Inspected(false, null)
        }
    }

    private fun inspectFilesystem(source: RandomSource): Inspected = try {
        val src = MotoImage.unwrap(if (SparseSource.probe(source)) SparseSource(listOf(source)) else source)
        val first = ByteBuffer.allocate(minOf(65536L, src.size).toInt())
        src.read(0, first)
        when {
            PartitionChoice.isBlank(first.array(), first.capacity()) -> Inspected(true, null)
            Ext4Reader.probe(src) -> {
                val fs = Ext4Reader(src)
                if (fs.list(fs.inode(2)).none { it.name != "lost+found" }) Inspected(true, null)
                else Inspected(false, readBuildProp { path ->
                    fs.lookup(path)?.takeIf { it.isFile && it.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong() }
                        ?.let { String(fs.readAll(it), Charsets.UTF_8) }
                })
            }
            ErofsReader.probe(src) -> {
                val fs = ErofsReader(src) { if (cancelled) throw IOException("cancelled") }
                val props = readBuildProp { path ->
                    fs.lookup(path)?.takeIf { it.type == 1 && it.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong() }?.let {
                        val out = java.io.ByteArrayOutputStream(it.size.toInt())
                        fs.copy(it, out)
                        String(out.toByteArray(), Charsets.UTF_8)
                    }
                }
                if (props != null) Inspected(false, props)
                else {
                    var any = false
                    try { fs.walk { _, _ -> any = true; throw FirstEntry() } } catch (_: FirstEntry) {}
                    Inspected(!any, null)
                }
            }
            
            else -> Inspected(false, null)
        }
    } catch (t: Throwable) {
        if (cancelled) throw IOException("cancelled")
        Inspected(false, null) 
    }

    
    private fun readBuildProp(read: (String) -> String?): Map<String, String>? {
        for (path in PartitionChoice.BUILD_PROP_PATHS) {
            val text = runCatching { read(path) }.getOrNull() ?: continue
            return PropArea.parseProps(text)
        }
        return null
    }

    
    private fun inspectTarMember(reader: MemberReader, m: Member, group: String): Inspected {
        if (m.size == 0L) return Inspected(true, null)
        val wanted = setOf("${PartitionChoice.mountOf(group)}/build.prop", "$group/build.prop", "$group/etc/build.prop", "build.prop", "system/system/build.prop")
        return try {
            reader.stream(m) { raw ->
                val lower = m.name.lowercase()
                val s: InputStream = if (lower.endsWith(".gz") || lower.endsWith(".tgz")) GZIPInputStream(raw, 1 shl 16) else raw
                val tar = TarArchiveInputStream(s, "UTF-8")
                var any = false
                var props: Map<String, String>? = null
                while (props == null) {
                    if (cancelled) throw IOException("cancelled")
                    val te: TarArchiveEntry = tar.nextEntry ?: break
                    if (!te.isFile) continue
                    if (te.size > 0) any = true
                    val tn = te.name.removePrefix("./").trimStart('/')
                    if (tn in wanted && te.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong())
                        props = PropArea.parseProps(String(tar.readBytes(), Charsets.UTF_8))
                }
                Inspected(!any, props)
            }
        } catch (t: Throwable) {
            if (cancelled) throw IOException("cancelled")
            Inspected(false, null)
        }
    }

    
    private fun inspectSystemFolder(reader: MemberReader, folderFiles: List<Member>, sysRoot: String): Inspected {
        val prop = folderFiles.firstOrNull { systemRel(sysRoot, it.name) == "system/build.prop" }
        val props = prop?.takeIf { it.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong() }?.let {
            runCatching { PropArea.parseProps(String(reader.stream(it) { s -> s.readBytes() }, Charsets.UTF_8)) }.getOrNull()
        }
        return Inspected(folderFiles.isEmpty(), props)
    }

    
    private fun importZipTar(zip: ZipFile, e: ZipArchiveEntry, base: String, depth: Int) {
        val gz = base.endsWith("gz", true)
        val ch = zipChannel
        if (!gz && e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } 
            if (e.dataOffset > 0) {
                val src = ChannelSource(ch, e.dataOffset, e.size)
                importTarStream(streamOf(src), base, depth + 1, src)
                return
            }
        }
        zip.getInputStream(e).use { s ->
            if (gz) importCompressed(GZIPInputStream(s, 1 shl 16), base, depth + 1)
            else importTarStream(s, base, depth + 1, reopen = { zip.getInputStream(e) })
        }
    }

    
    private fun importEdl(zip: ZipFile, entries: List<ZipArchiveEntry>, xmlEntries: List<ZipArchiveEntry>) {
        val edlDir = File(tmp, "edl-${System.nanoTime()}").apply { mkdirs() }
        try {
            val xmls = xmlEntries.map { spillEntry(zip, it) }
            val byBase = HashMap<String, MutableList<ZipArchiveEntry>>()
            for (x in entries) if (!x.isDirectory && !x.name.contains("__MACOSX/"))
                byBase.getOrPut(x.name.replace('\\', '/').substringAfterLast('/').lowercase()) { ArrayList() }.add(x)
            for (label in listOf("system", "boot", "recovery", "oem")) {
                if (cancelled) throw IOException("cancelled")
                if (label == "system" && gotSystem) continue
                if (label == "boot" && ramdisk != null) continue
                if (label == "recovery" && recovery != null) continue
                if (label == "oem" && gotOem) continue
                val chunks = File(edlDir, label).apply { mkdirs() }
                val raw = File(tmp, "edl-$label.raw.img")
                try {
                    val plan = FirmwareToolset.planEdl(xmls, byBase.keys, label) { msg -> log(msg) } ?: continue
                    if (plan.missing > 0) log("EDL: ${plan.missing} $label chunk(s) missing from archive")
                    val xmlDir = xmlEntries.getOrNull(xmls.indexOf(plan.xml))?.name?.replace('\\', '/')?.substringBeforeLast('/', "") ?: ""
                    for (file in plan.files()) {
                        val base = file.replace('\\', '/').substringAfterLast('/')
                        val cands = byBase[base.lowercase()] ?: continue
                        
                        val pick = cands.firstOrNull { it.name.replace('\\', '/').substringBeforeLast('/', "") == xmlDir }
                            ?: cands.minByOrNull { it.name.length }!!
                        onProgress(lt(R.string.imp_edl, base), -1f)
                        zip.getInputStream(pick).use { i -> File(chunks, base).outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    }
                    FirmwareToolset.combineEdl(plan, chunks, raw) { msg -> log(msg) }
                    deleteTree(chunks)
                    FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (label == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (label == "oem") importOem(src)
                        else if (raw.length() <= 128L shl 20) {
                            if (label == "recovery") takeRecovery(raw.inputStream(), "recovery.img") else takeBoot(raw.readBytes())
                        }
                    }
                } catch (t: Throwable) {
                    if (cancelled) throw t
                    log("EDL $label combine skipped: ${t.message ?: t.javaClass.simpleName}")
                } finally { deleteTree(chunks); raw.delete() }
            }
            xmls.forEach { it.delete() }
        } finally { deleteTree(edlDir) }
    }

    
    private fun importSevenZ(ch: FileChannel, name: String, depth: Int) {
        
        
        
        val plan = planSafely { planSevenZ(ch) }
        ch.position(0)
        SevenZFile.builder().setSeekableByteChannel(ch).get().use { z ->
            val foundSysRoot = findSystemRoot(z.entries.map { it.name.replace('\\', '/').trimStart('/') })
            
            val sysRoot = if (plan?.dropSystemFolder == true) null else foundSysRoot
            val nested = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            
            val splitParts = LinkedHashMap<String, MutableList<Pair<String, File>>>()
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/').trimStart('/')
                
                val base = plan?.canonical?.get(n) ?: n.substringAfterLast('/')
                val bare = PartitionNames.bareOk(n, e.size)
                val entryStream = object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        val count = read(one, 0, 1)
                        return if (count < 0) -1 else one[0].toInt() and 0xff
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (cancelled) throw IOException("cancelled")
                        return z.read(b, off, len)
                    }
                }
                when {
                    n.contains("__MACOSX/") || base.startsWith("._") -> drain(entryStream)
                    
                    plan != null && n in plan.skip -> drain(entryStream)
                    
                    isSonyName(base) && e.size > 0 -> nested.add(spill(entryStream, base) to "archive")
                    systemRel(sysRoot, n) != null -> {
                        writeFile(systemRel(sysRoot, n)!!, entryStream, null)
                        gotSystem = true
                    }
                    base.endsWith(".yaffs2.img", true) && !base.equals("system.yaffs2.img", true) -> drain(entryStream)
                    e.size > 4096 && listOf("system", "vendor").any { SparseChunks.indexOf(n, it) != null } -> {
                        val part = if (SparseChunks.indexOf(n, "system") != null) "system" else "vendor"
                        splitParts.getOrPut(part) { ArrayList() }.add(n to spill(entryStream, base))
                    }
                    isBootName(base, bare) -> takeBoot(entryStream.readBytes())
                    isRecoveryName(base, bare) && e.size < 64_000_000 -> takeRecovery(entryStream, base)
                    CscPackage.isCscContainer(base) && e.size > 0 -> takeCsc(entryStream, base)
                    isOemName(base, bare) -> nested.add(spill(entryStream, base) to "oem")
                    isVendorName(base, bare) -> nested.add(spill(entryStream, base) to "vendor")
                    isExtraName(base, bare) && e.size > 4096 -> nested.add(spill(entryStream, base) to "extra")
                    base.equals("system.yaffs2.img", true) ||
                        isSystemImageName(base, bare) -> {
                        nested.add(spill(entryStream, base) to "system")
                    }
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz)")) &&
                        e.size > 0 && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                        nested.add(spill(entryStream, base) to "archive")
                    }
                    base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) || base.endsWith(".transfer.list", true) -> {
                        val nestedFile = spillPreservingName(entryStream, base)
                        val key = base.lowercase()
                            .removeSuffix(".br")
                            .removeSuffix(".new.dat")
                            .removeSuffix(".transfer.list")
                        val slot = vdat.getOrPut(key) { linkedMapOf() }
                        when {
                            base.endsWith(".transfer.list", true) -> slot["list"] = nestedFile
                            else -> slot["data"] = nestedFile
                        }
                    }
                    recognizedFirmwareTool(base) -> {
                        val nestedFile = spill(entryStream, base)
                        nested.add(nestedFile to "tool")
                    }
                    
                    NandDump.isMtdName(base) && e.size > 0 -> nested.add(spill(entryStream, base) to "mtd")
                    else -> {
                        if (e.size >= 4096 && PartitionNames.isDiskImageName(base)) {
                            val t = spill(entryStream, base)
                            try {
                                val asDisk = t.inputStream().use { importDiskStream(it, base) }
                                if (!asDisk) FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                    if (!importProbableSystem(ChannelSource(c), base)) log("$base: unrecognized file, skipped")
                                }
                            } finally { t.delete() }
                        } else drain(entryStream)
                    }
                }
                onProgress(lt(R.string.imp_extracting, base), 0.5f)
            }
            for ((part, files) in splitParts) {
                val byPath = files.toMap()
                val chosen = SparseChunks.select(byPath.keys, part).mapNotNull { byPath[it] }
                (files.map { it.second } - chosen.toSet()).forEach { it.delete() }
                if (chosen.isEmpty() || (part == "system" && gotSystem) || (part == "vendor" && gotVendor)) chosen.forEach { it.delete() }
                else importSplitSparse(chosen, part)
            }
            for ((key, pair) in vdat) {
                val data = pair["data"]
                val list = pair["list"]
                if (data != null && list != null) {
                    try {
                        importVdatPair(data, list, key, depth + 1)
                    } finally { data.delete(); list.delete() }
                } else {
                    pair.values.forEach { it.delete() }
                    log("incomplete VDAT pair in 7z archive: $key")
                }
            }
            for ((f, kind) in nested.sortedBy { nestedRank(it.first.name) }) {
                try {
                    FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (kind == "oem") importOem(src)
                        else if (kind == "vendor") importVendor(src)
                        else if (kind == "extra") importExtra(src, f.name)
                        else handle(src, c, f.name, depth + 1)
                    }
                } finally { f.delete() }
            }
            if (!gotSystem && nested.isEmpty()) log("no system found in archive $name")
        }
    }

    
    private fun importIso(src: RandomSource, name: String, depth: Int) {
        val iso = Iso9660(src)
        val files = iso.entries(checkCancelled = { if (cancelled) throw IOException("cancelled") }).filter { !it.isDir }
        fun find(vararg names: String) = files.filter { f -> names.any { f.name.equals(it, true) } }
            .minByOrNull { it.path.count { c -> c == '/' } }
        val sfs = find("system.sfs")
        val plain = find("system.img")
        val efs = find("system.efs", "system.erofs", "system.img.erofs")
        if (sfs == null && plain == null && efs == null) {
            throw LocalizedIOException("no Android system found in the ISO image (expected system.sfs, system.efs or system.img)", R.string.imp_iso_no_system)
        }
        log("iso: ${files.size} files; system from ${(sfs ?: efs ?: plain)!!.path}")
        
        find("ramdisk.img")?.takeIf { ramdisk == null }?.let { rd ->
            if (rd.size > MAX_RAMDISK) log("iso: ${rd.path} is too large for a ramdisk, skipped")
            else {
                val list = runCatching { BootImage.decompress(readAllFrom(iso.open(rd)))?.let { BootImage.cpio(it) } }.getOrNull()
                if (!list.isNullOrEmpty() && list.any { it.name == "init.rc" }) { ramdisk = list; log("boot: ramdisk ${rd.path}, ${list.size} files") }
                else log("iso: ${rd.path} is not a ramdisk with init.rc, skipped")
            }
        }
        if (efs != null) {
            onProgress(lt(R.string.imp_reading_image, "system", "EROFS"), -1f)
            importErofs(iso.open(efs), "system")
            return
        }
        if (sfs == null) {
            val s = iso.open(plain!!)
            onProgress(lt(R.string.imp_reading_image, "system", "ISO 9660"), -1f)
            importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
            return
        }
        
        onProgress(lt(R.string.imp_reading_image, "system", "SquashFS"), -1f)
        val sfsFile = spill(streamOf(iso.open(sfs)), "system.sfs")
        val dir = sfsFile.parentFile ?: sfsFile
        try {
            var image: File? = null
            FirmwareToolset.walkSquashFs(sfsFile, { log(it) }, object : FirmwareToolset.SquashVisitor {
                override fun dir(path: String, mode: Int) {}
                override fun link(path: String, target: String) {}
                override fun file(path: String, mode: Int, size: Long): java.io.OutputStream? {
                    if (cancelled) throw IOException("cancelled")
                    if (image != null || !path.substringAfterLast('/').equals("system.img", true)) return null
                    if (tmp.usableSpace < size + 64L * 1024 * 1024)
                        throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                    return File(dir, "system.img").also { image = it }.outputStream().buffered(1 shl 20)
                }
                override fun fileEnd(path: String) {}
            })
            val img = image
            if (img == null) {
                
                log("iso: system.sfs holds no system.img, read as a file tree")
                FileChannel.open(sfsFile.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), "system") }
                return
            }
            sfsFile.delete()
            log("iso: system.img, ${img.length() shr 20} MB, from system.sfs")
            FileChannel.open(img.toPath(), StandardOpenOption.READ).use { c ->
                val s = ChannelSource(c)
                importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
            }
        } finally { deleteTree(dir) }
    }

    
    private fun importMtdDump(src: RandomSource, name: String) {
        val base = name.replace('\\', '/').substringAfterLast('/')
        if (src.size < 4096) { log("$base: too small to be a partition, skipped"); return }
        if (NandDump.isBlank(src)) { log("$base: erased flash, skipped"); return }
        if (Yaffs2Reader.probe(src) && yaffs2HoldsSystem(src)) {
            log("$base: YAFFS2 system partition")
            importImage(src, "system")
            return
        }
        if (src.size <= NandDump.MAX_BOOT_BYTES) {
            val raw = readAllFrom(src)
            for (image in NandDump.candidates(raw)) {
                val rd = runCatching { BootImage.ramdisk(image) }.getOrNull()
                if (rd.isNullOrEmpty() || rd.none { e -> e.name.removePrefix("./").let { it == "init.rc" || it == "sbin/recovery" } }) continue
                
                
                if (hasRecoveryBinary(image) && !startsLegacySystem(rd)) takeRecovery(image.inputStream(), "recovery ($base)") else takeBoot(image)
                return
            }
            
            
            BootImage.legacyRamdisk(raw)?.let { rd ->
                log("$base: pre-1.0 ramdisk partition")
                takeBareRamdisk(rd, base)
                return
            }
        }
        log("$base: not a boot, recovery or system partition, skipped")
    }

    
    private fun startsLegacySystem(rd: List<BootImage.CpioEntry>): Boolean = runCatching {
        val rc = rd.firstOrNull { it.name.removePrefix("./") == "etc/init.rc" } ?: return false
        val text = String(rc.data, Charsets.ISO_8859_1)
        LegacyFirmware.isLegacyInitRc(text) &&
            LegacyFirmware.parseInitRc(text).services.any { it.autostart && (it.name == "zygote" || it.name == "runtime") }
    }.getOrDefault(false)

    
    private fun yaffs2HoldsSystem(src: RandomSource): Boolean = try {
        var found = false
        Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }.walk { path, node ->
            if (path == "build.prop" && node.type == 1) found = true
        }
        found
    } catch (t: Throwable) {
        if (cancelled) throw t
        false
    }

    
    private fun importGapps(src: RandomSource) {
        val dir = File(root, "gapps")
        val existed = dir.exists()
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "gapps")
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("gapps: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            symlinks.removeAll { it.second.startsWith("/gapps/") }
            if (!existed) deleteTree(dir)
        }
    }

    private fun applyGptPart(kind: GptDisk.Kind, src: RandomSource) {
        when (kind) {
            GptDisk.Kind.BOOT -> takeBoot(readAllFrom(src))
            GptDisk.Kind.VENDOR_BOOT -> takeVendorBoot(src)
            GptDisk.Kind.RECOVERY -> takeRecovery(streamOf(src), "recovery.img")
            GptDisk.Kind.OEM -> importOem(src)
            GptDisk.Kind.GAPPS -> importGapps(src)
            GptDisk.Kind.VENDOR -> importVendor(src)
            GptDisk.Kind.SYSTEM -> {
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                gotSystem = true
            }
            GptDisk.Kind.SUPER -> importSuper(src)
        }
    }

    
    private var archiveHasVendorImage = false

    
    private fun importSuper(src: RandomSource) {
        val first = ByteBuffer.allocate(LP_PROBE).also { src.read(0, it) }.array()
        val need = LpMetadata.headSize(first) ?: throw LocalizedIOException("super: unreadable partition metadata", R.string.imp_unreadable_gpt, "super")
        val head = if (need <= first.size) first else ByteBuffer.allocate(need).also { src.read(0, it) }.array()
        val parts = LpMetadata.parse(head) ?: throw LocalizedIOException("super: unreadable partition metadata", R.string.imp_unreadable_gpt, "super")
        val picks = LpMetadata.pick(parts)
        if (picks.isEmpty()) { log("super: no system partition among ${parts.joinToString { it.name }}"); return }
        log("super: " + picks.joinToString { "${it.name} ${it.size shr 20} MB" })
        for (p in picks) {
            if (cancelled) throw IOException("cancelled")
            applyLogical(LpMetadata.baseName(p.name), ExtentSource(src, p.extents.map { it.superOffset to it.length }))
        }
    }

    
    private fun applyLogical(name: String, src: RandomSource) {
        when (name) {
            "system" -> { importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system"); gotSystem = true }
            "vendor" -> if (archiveHasVendorImage) log("super: vendor skipped, the package has a vendor image of its own") else importVendor(src)
            else -> importExtra(src, "$name.img")
        }
    }

    
    private fun importGptDisk(src: RandomSource, name: String) {
        val first = ByteBuffer.allocate(8192)
        src.read(0, first)
        val fb = first.array()
        val need = GptDisk.headSize(fb, fb.size) ?: throw LocalizedIOException("$name: unreadable GPT", R.string.imp_unreadable_gpt, name)
        val head = ByteBuffer.allocate(need).also { src.read(0, it) }.array()
        val picks = GptDisk.pick(GptDisk.parse(head) ?: throw LocalizedIOException("$name: unreadable GPT", R.string.imp_unreadable_gpt, name))
        if (picks.isEmpty()) throw LocalizedIOException("$name: no boot/recovery/system partition in GPT", R.string.imp_no_boot_recovery_system_partition_in, name)
        log("$name: full dump, partitions " + picks.joinToString { "${it.second.name} ${it.second.size shr 20} MB" })
        for ((kind, p) in picks) {
            if (cancelled) throw IOException("cancelled")
            applyGptPart(kind, SliceSource(src, p.start, minOf(p.size, src.size - p.start)))
        }
    }

    
    private fun importGptStream(input: InputStream, label: String): Boolean {
        val first = ByteArray(8192)
        val n = GptDisk.readFully(input, first, 0, first.size)
        val need = GptDisk.headSize(first, n) ?: return false
        var head = first.copyOf(n)
        if (need > n) {
            val big = first.copyOf(need)
            val got = GptDisk.readFully(input, big, n, need - n)
            if (n + got < need) return false
            head = big
        }
        val picks = GptDisk.pick(GptDisk.parse(head) ?: return false)
        if (picks.isEmpty()) { log("$label: GPT disk without boot/recovery/system partitions"); return true }
        log("$label: full dump, partitions " + picks.joinToString { "${it.second.name} ${it.second.size shr 20} MB" })
        
        
        class Out(val kind: GptDisk.Kind, val logical: String?, val runs: List<LpMetadata.Extent>, val file: File)
        
        class Run(val start: Long, val length: Long, val fileOff: Long, val out: Int)
        val dir = File(tmp, "gpt-${System.nanoTime()}").apply { mkdirs() }
        try {
            val outs = ArrayList<Out>()
            val runs = ArrayList<Run>()
            for ((k, p) in picks) {
                if (k != GptDisk.Kind.SUPER) {
                    outs.add(Out(k, null, listOf(LpMetadata.Extent(p.start, p.size)), File(dir, "${k.name.lowercase()}-${outs.size}.img")))
                    continue
                }
                
                val lpNeed = p.start + LP_PROBE + (4 shl 20)
                if (head.size < lpNeed) {
                    if (lpNeed > (64L shl 20)) return false
                    val big = head.copyOf(lpNeed.toInt())
                    val got = GptDisk.readFully(input, big, head.size, big.size - head.size)
                    if (head.size + got < big.size) return false
                    head = big
                }
                val sup = head.copyOfRange(p.start.toInt(), p.start.toInt() + LP_PROBE)
                val lpHead = LpMetadata.headSize(sup)?.let { n -> if (p.start + n <= head.size) head.copyOfRange(p.start.toInt(), p.start.toInt() + n) else null }
                val lp = lpHead?.let { LpMetadata.parse(it) }
                if (lp == null) { log("$label: super partition without readable metadata"); continue }
                val chosen = LpMetadata.pick(lp)
                log("$label: super holds " + lp.joinToString { "${it.name} ${it.size shr 20} MB" })
                for (lg in chosen) {
                    val base = LpMetadata.baseName(lg.name)
                    if (base == "vendor" && archiveHasVendorImage) { log("super: vendor skipped, the package has a vendor image of its own"); continue }
                    val abs = lg.extents.map { LpMetadata.Extent(p.start + it.superOffset, it.length) }
                    
                    if (abs.any { it.superOffset + it.length > p.start + p.size }) { log("super: $base extends past the partition, skipped"); continue }
                    outs.add(Out(GptDisk.Kind.SUPER, base, abs, File(dir, "super-$base.img")))
                }
            }
            if (outs.isEmpty()) { log("$label: nothing to import in the GPT disk"); return true }
            for ((i, o) in outs.withIndex()) {
                var off = 0L
                for (r in o.runs) { runs.add(Run(r.superOffset, r.length, off, i)); off += r.length }
            }
            runs.sortBy { it.start }
            val chans = outs.map { FileChannel.open(it.file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ) }
            try {
                val end = runs.maxOf { it.start + it.length }
                var pos = 0L
                fun feed(buf: ByteArray, len: Int) {
                    for (r in runs) {
                        val a = maxOf(r.start, pos)
                        val bb = minOf(r.start + r.length, pos + len)
                        if (bb > a) {
                            val w = ByteBuffer.wrap(buf, (a - pos).toInt(), (bb - a).toInt())
                            var at = r.fileOff + (a - r.start)
                            while (w.hasRemaining()) at += chans[r.out].write(w, at)
                        }
                    }
                    pos += len
                }
                
                feed(head, head.size)
                val buf = ByteArray(1 shl 20)
                var tick = 0
                while (pos < end) {
                    if (cancelled) throw IOException("cancelled")
                    if (tmp.usableSpace < (64L shl 20)) throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                    if (r < 0) break
                    if (r > 0) feed(buf, r)
                    if (++tick % 16 == 0) onProgress(lt(R.string.imp_reading, label), (pos.toFloat() / end).coerceIn(0f, 1f) * 0.5f)
                }
            } finally { chans.forEach { runCatching { it.close() } } }
            for (o in outs) {
                if (cancelled) throw IOException("cancelled")
                FileChannel.open(o.file.toPath(), StandardOpenOption.READ).use { c ->
                    val src = ChannelSource(c)
                    if (o.logical != null) applyLogical(o.logical, src) else applyGptPart(o.kind, src)
                }
                o.file.delete()
            }
        } finally { deleteTree(dir) }
        return true
    }

    
    private fun importDiskImage(src: RandomSource, name: String) {
        val first = ByteBuffer.allocate(8192).also { src.read(0, it) }.array()
        if (!GptDisk.probe(first) && MbrDisk.probe(first)) importMbrDisk(src, name) else importGptDisk(src, name)
    }

    
    private fun importDiskStream(input: InputStream, label: String): Boolean {
        val first = ByteArray(8192)
        val n = GptDisk.readFully(input, first, 0, first.size)
        val head = first.copyOf(n)
        val rest = java.io.SequenceInputStream(java.io.ByteArrayInputStream(first, 0, n), input)
        return if (!GptDisk.probe(head) && MbrDisk.probe(head)) importMbrStream(rest, label) else importGptStream(rest, label)
    }

    
    private fun importMbrDisk(src: RandomSource, name: String) {
        val parts = MbrDisk.parse(src).filter { !MbrDisk.isExtendedPart(it) && it.size > 0 && it.start < src.size }
        if (parts.isEmpty()) throw LocalizedIOException("$name: unreadable MBR", R.string.imp_unreadable_mbr, name)
        fun slice(p: MbrDisk.Part) = SliceSource(src, p.start, minOf(p.size, src.size - p.start))
        val found = ArrayList<Pair<MbrDisk.Role, MbrDisk.Part>>()
        val idents = HashMap<Int, MbrDisk.Ident>()
        var rawBoot = false
        for ((i, p) in parts.withIndex()) {
            val head = ByteBuffer.allocate(MbrDisk.IDENT_BYTES).also { src.read(p.start, it) }.array()
            val id = MbrDisk.identify(head)
            idents[p.number] = id
            val role = MbrDisk.roleOf(id, slice(p).size, i == 0, rawBoot)
            if (role == MbrDisk.Role.BOOT_RAW) rawBoot = true
            if (role != null) found.add(role to p)
        }
        if (found.none { it.first == MbrDisk.Role.SYSTEM }) {
            
            for (p in parts) {
                val id = idents[p.number] ?: continue
                if (id.fs != MbrDisk.Fs.EXT || MbrDisk.isDataLabel(id.label) || found.any { it.second === p }) continue
                val holdsSystem = runCatching {
                    val fs = Ext4Reader(slice(p))
                    fs.lookup("build.prop") != null || fs.lookup("system/build.prop") != null
                }.getOrDefault(false)
                if (holdsSystem) { found.add(MbrDisk.Role.SYSTEM to p); break }
            }
        }
        val picks = MbrDisk.pick(found)
        if (picks.none { it.first == MbrDisk.Role.SYSTEM })
            throw LocalizedIOException("$name: no system partition in MBR", R.string.imp_no_system_partition_in_mbr, name)
        log("$name: full dump (MBR), partitions " + picks.joinToString { "${it.first.name.lowercase()} #${it.second.number} ${it.second.size shr 20} MB" })
        for ((role, p) in picks) {
            if (cancelled) throw IOException("cancelled")
            applyMbrPart(role, slice(p))
        }
    }

    private fun applyMbrPart(role: MbrDisk.Role, src: RandomSource) {
        when (role) {
            MbrDisk.Role.BOOT_RAW -> applyGptPart(GptDisk.Kind.BOOT, src)
            MbrDisk.Role.BOOT_FAT -> {
                
                val volume = spill(streamOf(src), "boot-fat.img")
                try { takeUbootBoot(volume) } finally { deleteTree(volume.parentFile ?: volume) }
            }
            MbrDisk.Role.OEM -> applyGptPart(GptDisk.Kind.OEM, src)
            MbrDisk.Role.SYSTEM -> applyGptPart(GptDisk.Kind.SYSTEM, src)
            MbrDisk.Role.VENDOR -> applyGptPart(GptDisk.Kind.VENDOR, src)
        }
    }

    
    private fun takeUbootBoot(volume: File) {
        val img = UbootBoot.fromFat(volume) { log(it) }
        if (img == null) { log("boot: nothing to boot from on the FAT partition, skipped"); return }
        takeBoot(img)
    }

    
    private fun importMbrStream(input: InputStream, label: String): Boolean {
        val s0 = ByteArray(512)
        if (GptDisk.readFully(input, s0, 0, 512) < 512 || !MbrDisk.probe(s0)) return false
        val prim = MbrDisk.primaries(s0).sortedBy { it.start }
        class Out(val role: MbrDisk.Role, val part: MbrDisk.Part, val file: File)
        val outs = ArrayList<Out>()
        val dir = File(tmp, "mbr-${System.nanoTime()}").apply { mkdirs() }
        try {
            var pos = 512L
            var eof = false
            var rawBoot = false
            var seen = 0
            val scratch = ByteArray(1 shl 20)
            var tick = 0
            fun skipTo(target: Long): Boolean {
                while (pos < target) {
                    if (cancelled) throw IOException("cancelled")
                    val r = input.read(scratch, 0, minOf(scratch.size.toLong(), target - pos).toInt())
                    if (r < 0) { eof = true; return false }
                    pos += r
                    if (++tick % 16 == 0) onProgress(lt(R.string.imp_reading, label), -1f)
                }
                return true
            }
            fun done() = outs.any { it.role == MbrDisk.Role.SYSTEM } && outs.any { it.role == MbrDisk.Role.BOOT_FAT || it.role == MbrDisk.Role.BOOT_RAW }
            
            fun visit(p: MbrDisk.Part): Boolean {
                val first = seen++ == 0
                if (p.size < MbrDisk.IDENT_BYTES || p.start < pos || !skipTo(p.start)) return !eof
                val head = ByteArray(MbrDisk.IDENT_BYTES)
                val got = GptDisk.readFully(input, head, 0, head.size)
                pos += got
                if (got < head.size) { eof = true; return false }
                val role = MbrDisk.roleOf(MbrDisk.identify(head), p.size, first, rawBoot)
                if (role == null || outs.any { it.role == role }) return true
                if (role == MbrDisk.Role.BOOT_RAW) rawBoot = true
                val f = File(dir, "${role.name.lowercase()}-${outs.size}.img")
                f.outputStream().buffered(1 shl 20).use { o ->
                    o.write(head)
                    var left = p.size - head.size
                    while (left > 0) {
                        if (cancelled) throw IOException("cancelled")
                        if (tmp.usableSpace < (64L shl 20)) throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                        val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                        if (r < 0) { eof = true; break } 
                        o.write(scratch, 0, r)
                        left -= r
                        pos += r
                        if (++tick % 16 == 0) onProgress(lt(R.string.imp_reading, label), -1f)
                    }
                }
                outs.add(Out(role, p, f))
                return !eof
            }
            loop@ for (p in prim) {
                if (!MbrDisk.isExtendedPart(p)) {
                    if (!visit(p)) break@loop
                } else {
                    if (outs.any { it.role == MbrDisk.Role.SYSTEM }) break@loop
                    
                    val base = p.start / MbrDisk.SECTOR
                    var cur = base
                    var number = 5
                    var guard = 0
                    while (guard++ < 128 && !done()) {
                        if (cur * MbrDisk.SECTOR < pos || !skipTo(cur * MbrDisk.SECTOR)) break@loop
                        val ebr = ByteArray(512)
                        if (GptDisk.readFully(input, ebr, 0, 512) < 512) break@loop
                        pos += 512
                        val es = MbrDisk.entries(ebr) ?: break
                        val data = es.firstOrNull { !MbrDisk.isExtendedType(it.type) }
                        if (data != null && data.count > 0 &&
                            !visit(MbrDisk.Part(number++, data.type, (cur + data.first) * MbrDisk.SECTOR, data.count * MbrDisk.SECTOR, true))) break@loop
                        val next = es.firstOrNull { MbrDisk.isExtendedType(it.type) } ?: break
                        if (base + next.first <= cur) break
                        cur = base + next.first
                    }
                }
                if (done()) break@loop
            }
            if (outs.none { it.role == MbrDisk.Role.SYSTEM }) return false
            log("$label: full dump (MBR), partitions " + outs.joinToString { "${it.role.name.lowercase()} #${it.part.number} ${it.part.size shr 20} MB" })
            for (role in MbrDisk.Role.values()) {
                val o = outs.firstOrNull { it.role == role } ?: continue
                if (cancelled) throw IOException("cancelled")
                if (role == MbrDisk.Role.BOOT_FAT) takeUbootBoot(o.file)
                else FileChannel.open(o.file.toPath(), StandardOpenOption.READ).use { c -> applyMbrPart(role, ChannelSource(c)) }
                o.file.delete()
            }
            return true
        } finally { deleteTree(dir) }
    }

    private fun importVdatPair(data: File, list: File, key: String, depth: Int) {
        val part = key.substringAfterLast('/').lowercase()
        val mount = when { part.isBlank() || part == "system" -> "system"; part == "vendor" || part == "odm" -> part
            part == "product" || part == "system_ext" -> "system/$part"; else -> {
            log("skipping OTA partition \"$part\" (not system/vendor/product/system_ext/odm)"); return } }
        val raw = File(tmp, "${if (key.isBlank()) "system" else key}.img")
        val actualData = if (data.name.endsWith(".br", true)) {
            val dec = File(tmp, "${if (key.isBlank()) "system" else key}.new.dat")
            BrotliInputStream(BufferedInputStream(FileInputStream(data), 1 shl 20)).use { input ->
                dec.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            dec
        } else data
        try {
            val transfer = list.readText(Charsets.UTF_8)
            FileInputStream(actualData).use { dataIn ->
                TransferList.build(transfer, dataIn, raw)
            }
            keepStockImage = mount == "system"
            FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), mount) }
        } finally {
            keepStockImage = false
            if (actualData !== data) actualData.delete()
            raw.delete()
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) >= 0) if (cancelled) throw IOException("cancelled")
    }

    
    private fun importRar(file: File, name: String, depth: Int) {
        if (depth > 8) throw LocalizedIOException("nested archive depth exceeded", R.string.imp_nested_archive_depth_exceeded)
        
        val plan = planSafely { planRar(file) }
        RarArchive(file).use { rar ->
            val foundSysRoot = findSystemRoot(rar.fileHeaders.map { (it.fileName ?: "").replace('\\', '/').trimStart('/') })
            
            val sysRoot = if (plan?.dropSystemFolder == true) null else foundSysRoot
            val pending = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            for (e: RarFileHeader in rar.fileHeaders) {
                if (cancelled) throw IOException("cancelled")
                if (e.isDirectory) continue
                val entryName = (e.fileName ?: "").replace('\\', '/').trimStart('/')
                if (entryName.isBlank() || entryName.split('/').any { it == ".." }) continue
                
                if (plan != null && entryName in plan.skip) continue
                
                val base = plan?.canonical?.get(entryName) ?: entryName.substringAfterLast('/')
                val sysRel = systemRel(sysRoot, entryName)
                val bare = PartitionNames.bareOk(entryName, Long.MAX_VALUE)
                val interesting = sysRel != null || isSonyName(base) ||
                    isBootName(base, bare) || isRecoveryName(base, bare) || isOemName(base, bare) ||
                    
                    isVendorName(base, bare) || isExtraName(base, bare) ||
                    base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base, bare) ||
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz|new\\.dat|new\\.dat\\.br|transfer\\.list)")) ||
                    recognizedFirmwareTool(base)
                if (!interesting) continue
                val dir = File(tmp, "rar-${System.nanoTime()}").apply { mkdirs() }
                val temp = File(dir, base).apply { parentFile?.mkdirs() }
                try {
                    temp.outputStream().buffered(1 shl 20).use { out -> rar.extractFile(e, out) }
                } catch (t: com.github.junrar.exception.RarException) {
                    temp.delete()
                    pending.forEach { it.first.delete() }
                    vdat.values.forEach { slot -> slot.values.forEach { it.delete() } }
                    throw LocalizedIOException("RAR entry '$entryName' failed to unpack (${t.javaClass.simpleName}); " +
                        "the archive may be damaged or the RAR decoder (junrar) is too old", R.string.imp_rar_entry_failed_to_unpack_the, entryName, t.javaClass.simpleName)
                        .also { it.initCause(t) }
                }
                if (sysRel != null) {
                    FileInputStream(temp).use { input -> writeFile(sysRel, input, null) }
                    gotSystem = true
                    temp.delete()
                } else if (isSonyName(base)) {
                    pending.add(temp to "archive")
                } else if (isBootName(base, bare)) {
                    takeBoot(temp.readBytes()); temp.delete()
                } else if (isRecoveryName(base, bare) && temp.length() < 64_000_000) {
                    temp.inputStream().use { takeRecovery(it, base) }; temp.delete()
                } else if (isOemName(base, bare)) {
                    pending.add(temp to "oem")
                } else if (base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base, bare)) {
                    pending.add(temp to "system")
                } else if (base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) || base.endsWith(".transfer.list", true)) {
                    val key = base.lowercase().removeSuffix(".br").removeSuffix(".new.dat").removeSuffix(".transfer.list")
                    val slot = vdat.getOrPut(key) { linkedMapOf() }
                    when {
                        base.endsWith(".transfer.list", true) -> slot["list"] = temp
                        else -> slot["data"] = temp
                    }
                } else pending.add(temp to "archive")
            }
            for ((key, pair) in vdat) {
                val data = pair["data"]
                val list = pair["list"]
                if (data != null && list != null) {
                    try { importVdatPair(data, list, key, depth + 1) }
                    finally { data.delete(); list.delete() }
                } else { pair.values.forEach { it.delete() }; log("incomplete VDAT pair in RAR archive: $key") }
            }
            for ((temp, kind) in pending.sortedBy { nestedRank(it.first.name) }) {
                try {
                    FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (kind == "oem") importOem(src)
                        else handle(src, c, temp.name, depth + 1)
                    }
                } finally { temp.delete() }
            }
            if (!gotSystem && pending.isEmpty()) log("no system found in RAR archive $name")
        }
    }

    
    private fun nestedRank(name: String): Int =
        PartitionChoice.tarGroup(name.substringAfterLast('/'))?.let { if (PartitionChoice.mountOf(it).startsWith("system/")) 1 else 0 } ?: 0

    
    private fun isSonyName(base: String) = base.endsWith(".sin", true) || base.endsWith(".ftf", true)

    
    private fun importSonyFile(f: File, depth: Int) {
        try {
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, f.name, depth + 1) }
        } finally { deleteTree(f.parentFile ?: f) }
    }

    
    private fun withSinSource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> Unit) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() }
            if (e.dataOffset > 0) { block(ChannelSource(ch, e.dataOffset, e.size)); return }
        }
        val t = spillEntry(zip, e)
        try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) } } finally { deleteTree(t.parentFile ?: t) }
    }

    
    private fun importSin(src: RandomSource, name: String) {
        val base = name.replace('\\', '/').substringAfterLast('/')
        val part = SonySin.partition(base)
        val kind = SonySin.kind(part)
        if (kind == SonySin.Kind.SKIP) { log("FTF: skipped $base ($part)"); return }
        if (kind == SonySin.Kind.SYSTEM && gotSystem) { log("FTF: skipped $base, system already imported"); return }
        onProgress(lt(R.string.imp_unpacking_sony_image, part), -1f)
        val work = File(tmp, "sin-${System.nanoTime()}").apply { mkdirs() }
        try {
            val images = SonySin.unpack(src, work, base, { log(it) }) { cancelled }
            log("FTF: $base -> $part, ${images.sumOf { it.length() } shr 20} MB")
            when (kind) {
                SonySin.Kind.BOOT -> takeBoot(images.first().readBytes())
                SonySin.Kind.RECOVERY -> images.first().inputStream().use { takeRecovery(it, "$part.img") }
                else -> {
                    val chans = images.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    try {
                        val srcs = chans.map { ChannelSource(it) }
                        val img = when {
                            srcs.all { SparseSource.probe(it) } -> SparseSource(srcs)
                            srcs.size == 1 -> srcs[0]
                            else -> throw LocalizedIOException("$base: ${srcs.size} image parts that are not a sparse set", R.string.imp_image_parts_that_are_not_a, base, srcs.size)
                        }
                        when (kind) {
                            SonySin.Kind.OEM -> importOem(img)
                            SonySin.Kind.VENDOR -> importVendor(img)
                            else -> importImage(img, "system")
                        }
                    } finally { chans.forEach { it.close() } }
                }
            }
        } finally { deleteTree(work) }
    }

    private fun withEntrySource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> Unit) {
        
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } 
            if (e.dataOffset > 0) { block(ChannelSource(ch, e.dataOffset, e.size)); return }
        }
        val t = spillEntry(zip, e)
        deferred?.let { d -> d.add(t to e.name); return }
        FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        t.delete()
    }

    
    private fun handleNestedZip(t: File, base: String, depth: Int) {
        val outer = deferred
        val mine = ArrayList<Pair<File, String>>()
        deferred = mine
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, base, depth + 1) }
        } finally { deferred = outer; t.delete() }
        try {
            
            
            fun rank(entry: String): Int {
                val b = entry.substringAfterLast('/')
                return when {
                    PartitionNames.extraMount(b, bareOk = true) != null -> 3
                    isOemName(b, bare = true) -> 2
                    isVendorName(b, bare = true) -> 1
                    else -> 0
                }
            }
            for ((f, entry) in mine.sortedBy { rank(it.second) }) {
                if (cancelled) throw IOException("cancelled")
                val entryBase = entry.substringAfterLast('/')
                FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                    val src = ChannelSource(c)
                    when {
                        PartitionNames.extraMount(entryBase, bareOk = true) != null -> importExtra(src, entryBase)
                        isOemName(entryBase, bare = true) -> importOem(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src)
                        isVendorName(entryBase, bare = true) -> importVendor(src)
                        isSystemImageName(entryBase, bare = true) ->
                            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else -> log("$entryBase: not a partition image the importer uses, skipped")
                    }
                }
                f.delete()
            }
        } finally { mine.forEach { it.first.delete() } }
    }

    private fun spillEntry(zip: ZipFile, e: ZipArchiveEntry): File = zip.getInputStream(e).use { spill(it, e.name.substringAfterLast('/')) }

    
    private fun spill(i: InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace('\\', '_').ifEmpty { "entry" }
        val dir = File(tmp, "spill-${System.nanoTime()}").apply { mkdirs() }
        val t = File(dir, safe)
        try {
            t.outputStream().use { o ->
                val buffer = ByteArray(1 shl 20)
                var count = 0L
                while (true) {
                    if (cancelled) throw IOException("cancelled")
                    val n = i.read(buffer)
                    if (n < 0) break
                    count += n
                    if (count > 16L * 1024 * 1024 * 1024 || tmp.usableSpace < n + 32L * 1024 * 1024)
                        throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                    o.write(buffer, 0, n)
                }
            }
            return t
        } catch (toss: Throwable) { deleteTree(dir); throw toss }
    }

    
    private fun importCpioStream(input: InputStream, name: String, depth: Int, reopen: (() -> InputStream)? = null) {
        if (depth > 8) throw LocalizedIOException("nested archive depth exceeded", R.string.imp_nested_archive_depth_exceeded)
        
        val plan = if (reopen != null) planSafely { planStreamArchive({ CpioArchiveInputStream(reopen(), "UTF-8") }, null) } else null
        val dropSystem = plan?.dropSystemFolder == true
        CpioArchiveInputStream(input, "UTF-8").use { cpio ->
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e: CpioArchiveEntry = cpio.nextEntry ?: break
                val n = e.name.removePrefix("./").trimStart('/')
                if (n.isBlank() || n == "TRAILER!!!" || n.split('/').any { it == ".." }) continue
                
                if (plan != null && n in plan.skip) continue
                if (dropSystem && n.startsWith("system/")) continue
                
                val base = plan?.canonical?.get(n) ?: n.substringAfterLast('/')
                val bare = e.isRegularFile && PartitionNames.bareOk(n, e.size)
                when {
                    e.isDirectory -> FirmwareContainers.destination(root, n).mkdirs()
                    isSonyName(base) && e.isRegularFile && e.size > 0 -> importSonyFile(spill(cpio.nonClosing(), base), depth)
                    n.startsWith("system/") -> {
                        writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        gotSystem = true
                    }
                    isBootName(base, bare) -> takeBoot(cpio.readBytes())
                    isRecoveryName(base, bare) && e.size < 64_000_000 -> takeRecovery(cpio, base)
                    isOemName(base, bare) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importOem(ChannelSource(c)) } } finally { t.delete() }
                    }
                    isSystemImageName(base, bare) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                val src = ChannelSource(c)
                                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                            }
                        } finally { t.delete() }
                    }
                    
                    e.isRegularFile && (isVendorName(base, bare) || isExtraName(base, bare) && e.size > 4096) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                if (isVendorName(base, bare)) importVendor(ChannelSource(c)) else importExtra(ChannelSource(c), base)
                            }
                        } finally { t.delete() }
                    }
                    n.matches(Regex("^(data|dev|sbin|vendor|oem|etc)(/.*)?$")) ||
                        n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" -> {
                        if (e.isSymbolicLink) {
                            
                            
                            val target = cpio.readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
                            if (target.isNotEmpty() && target.length <= 4096) {
                                symlinks.add(target to "/$n")
                            }
                        } else if (e.isRegularFile) {
                            writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        }
                    }
                }
            }
        }
    }

    
    private fun importTarStream(s: InputStream, name: String, depth: Int, under: RandomSource? = null, reopen: (() -> InputStream)? = null) {
        
        
        
        val partGroup = PartitionChoice.tarGroup(name.substringAfterLast('/').substringAfterLast('\\'))?.takeIf { PartitionChoice.takesFiles(it) }
        if (partGroup == "vendor") archiveHasVendorImage = true
        
        
        
        val plan = if (partGroup != null || name.endsWith(".win", true)) null else when {
            under != null -> planSafely { planStreamArchive({ TarArchiveInputStream(streamOf(under), "UTF-8") }, under) }
            reopen != null -> planSafely { planStreamArchive({ TarArchiveInputStream(reopen(), "UTF-8") }, null) }
            else -> null
        }
        val dropSystem = plan?.dropSystemFolder == true
        
        
        
        
        val systemTar = partGroup == null && !name.endsWith(".win", true) &&
            PartitionChoice.tarGroup(name.substringAfterLast('/').substringAfterLast('\\')) == "system"
        val tar = TarArchiveInputStream(s, "UTF-8")
        var fullRoot: Boolean? = null
        
        var topLevelTar = false
        while (true) {
            if (cancelled) throw IOException("cancelled")
            val e: TarArchiveEntry = tar.nextEntry ?: break
            var n = e.name.removePrefix("./").trimStart('/')
            if (n.isEmpty()) continue
            
            if (plan != null && n in plan.skip) continue
            if (dropSystem && n.startsWith("system/")) continue
            
            val topLevel = systemTar && (topLevelTar || fullRoot != true) &&
                PartitionChoice.isSystemTarTopLevel(n, e.isFile && !e.isSymbolicLink && !e.isLink, e.size)
            if (topLevel) { n = "system/$n"; topLevelTar = true }
            
            
            val base = PartitionNames.stripOdinMd5(plan?.canonical?.get(n) ?: n.substringAfterLast('/'))
            
            
            val bare = e.isFile && !e.isSymbolicLink && !e.isLink && PartitionNames.bareOk(n, e.size)
            
            if (fullRoot == null && ((n == "system" && !e.isFile) || n.startsWith("system/") || n.startsWith("init.rc") || n.startsWith("default.prop"))) fullRoot = true
            when {
                
                partGroup != null && !(('/' !in n) && PartitionChoice.imageGroup(base, bare, lz4Names = true) != null) -> {
                    val p = PartitionChoice.tarPath(partGroup, n)
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isLink -> hardlink(p, PartitionChoice.tarPath(partGroup, e.linkName))
                        e.isFile -> { writeFile(p, tar.nonClosing(), e.mode); if (partGroup == "vendor") gotVendor = true }
                    }
                }
                isSonyName(base) && e.isFile && e.size > 0 -> importSonyFile(spill(tar.nonClosing(), base), depth)
                base.equals("system.yaffs2.img", true) && e.isFile -> {
                    val t = spill(tar.nonClosing(), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { importImage(ChannelSource(it), "system") } }
                    finally { t.delete() }
                }
                base.endsWith(".yaffs2.img", true) -> {}
                
                
                
                n == "system" && e.isFile && e.size > 1_000_000 -> {
                    val t = spill(tar.nonClosing(), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), "system") } }
                    finally { t.delete() }
                }
                n == "userdata" && e.isFile -> {}
                CscPackage.isCscContainer(base) && e.isFile && e.size > 0 && !inSystemDump(n) -> takeCsc(tar.nonClosing(), base)
                isOemName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importOem(ChannelSource(c)) } } finally { t.delete() }
                }
                isSystemImageName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile -> {
                    if (under != null && !base.endsWith(".lz4", true)) {
                        
                        val ext = ExtentSource(under, listOf(tar.bytesRead to e.size))
                        importImage(if (SparseSource.probe(ext)) SparseSource(listOf(ext)) else ext, "system")
                    } else {
                        val t = spill(maybeLz4(tar, base), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                val src = ChannelSource(c)
                                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                            }
                        } finally { t.delete() }
                    }
                }
                
                isExtraName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile && e.size > 4096 && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importExtra(ChannelSource(c), base.replace(Regex("(?i)\\.lz4$"), "")) } } finally { t.delete() }
                }
                isVendorName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile && e.size > 4096 && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importVendor(ChannelSource(c)) } } finally { t.delete() }
                }
                (base.matches(Regex("(?i)(boot\\.img|boot_signed(\\.img)?|zImage|kernel)(\\.lz4)?")) || isBootName(base, bare)) && e.isFile && !inSystemDump(n) -> takeBoot(maybeLz4(tar, base).readBytes())
                isRecoveryName(base, bare) && e.isFile && e.size < 64_000_000 && !inSystemDump(n) -> takeRecovery(tar.nonClosing(), base)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5)")) && e.isFile && (e.size > 20_000_000 || PartitionChoice.tarGroup(base) != null) -> importTarStream(tar.nonClosing(), base, depth + 1)
                
                base.lowercase().endsWith(".zip") && e.isFile && e.size > 20_000_000 -> {
                    handleNestedZip(spill(tar.nonClosing(), base), base, depth)
                }
                n.matches(Regex("^(system|data|dev|sbin|vendor|oem|etc)(/.*)?$")) || n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" || n.startsWith("dhd.") -> {
                    
                    if (fullRoot != true && !n.startsWith("system") && !(n.startsWith("dhd.") || n.endsWith(".rc") || n == "default.prop" || n.startsWith("sbin"))) continue
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, n).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$n")
                        e.isLink -> hardlink(n, if (topLevel) "system/" + e.linkName.removePrefix("./").trimStart('/') else e.linkName)
                        e.isFile -> {
                            writeFile(n, tar.nonClosing(), e.mode)
                            if (n.startsWith("system/")) gotSystem = true
                        }
                    }
                }
                
                name.contains("oem", true) && name.endsWith(".win", true) && !name.contains("system", true) -> {
                    val p = "oem/$n"
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> writeFile(p, tar.nonClosing(), e.mode)
                    }
                }
                
                name.contains("system", true) && name.endsWith(".win", true) -> {
                    val p = "system/$n"
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> { writeFile(p, tar.nonClosing(), e.mode); gotSystem = true }
                    }
                }
            }
            if (files % 200 == 0) onProgress(lt(R.string.imp_extracting, base), -1f)
        }
    }

    private fun InputStream.nonClosing(): InputStream = object : java.io.FilterInputStream(this) { override fun close() {} }

    private fun maybeLz4(s: InputStream, name: String): InputStream =
        if (name.endsWith(".lz4", true)) org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(s.nonClosing()) else s.nonClosing()

    private fun hardlink(n: String, target: String) {
        val src = FirmwareContainers.destination(root, target.removePrefix("./").trimStart('/'))
        val dst = FirmwareContainers.destination(root, n)
        if (src.isFile) { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }
    }

    

    
    private var keepStockImage = false

    
    private fun importImage(source: RandomSource, mount: String) = withDecoded(source) { s ->
        importImageRaw(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, mount)
    }

    private fun <T> withDecoded(src: RandomSource, level: Int = 0, block: (RandomSource) -> T): T {
        if (level < 4 && src.size >= 32) {
            val buf = ByteBuffer.allocate(minOf(src.size, 65536L).toInt())
            src.read(0, buf); buf.flip()
            val h = ByteArray(buf.remaining()).also { buf.get(it) }
            val codec = ImageCodecs.detect(h, null)
            if (codec != null) {
                val f = spill(ImageCodecs.open(codec, streamOf(src)), "image.img")
                try {
                    if (f.length() < 2) throw LocalizedIOException("compressed file is empty: image", R.string.imp_compressed_file_is_empty, "image")
                    log("image: ${codec.name.lowercase()}-compressed, unpacked ${f.length() shr 20} MB")
                    return FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> withDecoded(ChannelSource(c), level + 1, block) }
                } finally { deleteTree(f.parentFile ?: f) }
            }
        }
        return block(src)
    }

    private fun importImageRaw(source: RandomSource, mount: String) {
        
        val src = MotoImage.unwrap(source)
        if (src !== source) log("image $mount: Motorola signature header skipped, ${src.size shr 20} MB filesystem")
        if (UbiFsReader.probe(src)) { importUbifs(src, mount); return }
        if (Yaffs2Reader.probe(src)) { importYaffs2(src, mount); return }
        if (ErofsReader.probe(src)) { importErofs(src, mount); return }
        if (!Ext4Reader.probe(src)) { importOtherFilesystem(src, mount, keep = mount == "system" && src === source); return }
        val fs = Ext4Reader(src)
        
        
        val asRoot = mount == "system" && AbLayout.isSystemAsRoot(fs)
        if (asRoot) { systemAsRoot = true; log("image system: system-as-root layout (root file system in the partition)") }
        onProgress(lt(R.string.imp_reading_image_ext_block, mount, fs.blockSize), -1f)
        var n = 0
        
        
        
        val layoutPartition = SystemLayout.partitionOf(mount)
        val cap = if (layoutPartition != null) SystemLayout.Capture(fs, src, SystemLayout.prefixOf(mount, asRoot)) else null
        fs.walk { path, node ->
            if (cancelled) throw IOException("cancelled")
            if (asRoot && AbLayout.skipRootEntry(path, node.isFile, node.isLink)) return@walk
            val rel = if (asRoot) path else "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when {
                node.isDir -> { f.mkdirs(); cap?.dir(path, node.perm) }
                node.isLink -> { val t = fs.linkTarget(node); symlinks.add(t to "/$rel"); cap?.link(path, t) }
                node.isFile -> {
                    f.parentFile?.mkdirs()
                    val md = java.security.MessageDigest.getInstance("SHA-1")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, java.security.DigestOutputStream(it, md)) }
                    cap?.file(path, node, md.digest())
                    applyMode(f, node.perm)
                    files++; bytes += node.size
                }
            }
            if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, path.substringAfterLast('/')), -1f)
        }
        if (cap != null && layoutPartition != null) {
            onProgress(lt(R.string.imp_recording_the_layout_of_image, mount), -1f)
            val lf = SystemLayout.file(paths.dir, layoutPartition)
            runCatching { cap.finish(lf) }.onFailure {
                lf.delete()
                log("image $mount: layout not kept (${it.message}); block-based OTAs cannot be applied to this partition of the VM")
            }
        }
        if (mount == "system") gotSystem = true
        log("image $mount: $n objects")
    }

    
    private fun importOtherFilesystem(src: RandomSource, mount: String, keep: Boolean = false) {
        val headFile = File(tmp, "probe-${System.nanoTime()}.bin")
        val kind = try {
            val head = ByteBuffer.allocate(minOf((8L shl 20) + 512, src.size).toInt()); src.read(0, head)
            headFile.writeBytes(head.array())
            FirmwareToolset.detect(headFile)
        } finally { headFile.delete() }
        if (kind != "SQUASHFS" && kind != "RFS")
            throw LocalizedIOException("unrecognized filesystem for $mount (expected ext4, YAFFS2, UBIFS, SquashFS or RFS)", R.string.imp_unrecognized_filesystem_for_expected_ext_yaf, mount)
        val fsName = if (kind == "SQUASHFS") "SquashFS" else "RFS"
        onProgress(lt(R.string.imp_reading_image, mount, fsName), -1f)
        val dir = File(tmp, "fs-${System.nanoTime()}").apply { mkdirs() }
        try {
            val image = spill(streamOf(src), "$mount.img").let { File(dir, "$mount.img").also { d -> it.copyTo(d, true); it.delete() } }
            val artifacts = FirmwareToolset.extract(image, File(dir, "out")) { msg -> log(msg) }
            val zipFile = artifacts.firstOrNull { it.file.isFile }?.file
                ?: throw LocalizedIOException("$fsName image produced no files", R.string.imp_image_produced_no_files, fsName)
            if (keep && kind == "SQUASHFS") recordSquashLayout(image, mount)
            image.delete()
            var n = 0
            ZipFile.builder().setFile(zipFile).get().use { zip ->
                for (e in zip.entries) {
                    if (cancelled) throw IOException("cancelled")
                    
                    val inner = e.name.replace('\\', '/').substringAfter('/', "").trimEnd('/')
                    if (inner.isEmpty() || inner.split('/').any { it == ".." }) continue
                    val rel = "$mount/$inner"
                    val f = FirmwareContainers.destination(root, rel)
                    when {
                        e.isDirectory -> f.mkdirs()
                        e.isUnixSymlink -> {
                            val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                            if (target.isNotEmpty()) symlinks.add(target to "/$rel")
                        }
                        else -> {
                            zip.getInputStream(e).use { writeFile(rel, it, e.unixMode.takeIf { m -> m != 0 }) }
                        }
                    }
                    if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, inner.substringAfterLast('/')), -1f)
                }
            }
            if (mount == "system") gotSystem = true
            log("$fsName $mount: $n objects")
        } finally { deleteTree(dir) }
    }

    
    private fun recordSquashLayout(image: File, mount: String) {
        val lf = SquashLayout.file(paths.dir)
        val copy = StockImage.file(paths.dir)
        try {
            onProgress(lt(R.string.imp_recording_the_layout_of_image, mount), -1f)
            val st = SquashLayout.capture(image, lf, { log(it) }, { cancelled })
            copy.delete()
            log("image $mount: SquashFS layout kept, ${st.storedBytes shr 10} KB of the ${st.imageBytes shr 20} MB image stored " +
                "(${st.rebuilt} data blocks are rebuilt from the files, ${st.stored} are stored as they were)")
        } catch (e: Exception) {
            lf.delete()
            if (cancelled) throw IOException("cancelled")
            log("image $mount: layout not kept (${e.message}); keeping the image itself")
            runCatching { image.copyTo(copy, overwrite = true) }.onFailure {
                copy.delete()
                log("image $mount: stock image not kept (${it.message}); block-based OTAs cannot be applied to this VM")
            }
        }
    }

    

    private fun cabSource(src: RandomSource) = object : WindowsCab.Source {
        override fun length() = src.size
        override fun readFully(pos: Long, b: ByteArray, off: Int, len: Int) { src.read(pos, ByteBuffer.wrap(b, off, len)) }
    }

    
    private fun importWindowsCab(src: RandomSource, name: String) {
        val work = File(tmp, "cab-${System.nanoTime()}").apply { mkdirs() }
        try {
            onProgress(lt(R.string.imp_opening, name), -1f)
            val wims = WindowsCab.extractWims(cabSource(src), work) { msg -> log(msg) }
            var any = false
            for (w in wims) {
                if (cancelled) throw IOException("cancelled")
                if (importWimFile(w, w.name)) any = true
                w.delete()                       
            }
            if (!any) throw LocalizedIOException("no Android system partition found in file", R.string.imp_no_android_system_partition_found_in)
        } finally { deleteTree(work) }
    }

    private fun importWimStandalone(src: RandomSource, name: String) {
        val f = spill(streamOf(src), name)
        try {
            if (!importWimFile(f, name))
                throw LocalizedIOException("no Android system partition found in file", R.string.imp_no_android_system_partition_found_in)
        } finally { deleteTree(f.parentFile ?: f) }
    }

    
    private fun importWimFile(f: File, name: String): Boolean {
        WimReader(f).use { wim ->
            var idx = 0
            for (i in 1..wim.imageCount()) {
                var android = false
                wim.walk(i, WimReader.Visitor { e -> if (e.path == "system/build.prop" || e.path == "system/framework") android = true })
                if (android) { idx = i; break }
            }
            if (idx == 0) { log("wim $name: no Android system image in it"); return false }
            log("wim $name: image $idx of ${wim.imageCount()} is Android")
            onProgress(lt(R.string.imp_reading_image, "system", "WIM"), -1f)
            var n = 0
            wim.walk(idx, WimReader.Visitor { e ->
                if (cancelled) throw IOException("cancelled")
                val rel = e.path
                val top = rel.substringBefore('/')
                
                if (top == "dev" || top == "proc" || top == "sys") return@Visitor
                val aow = e.named["aow"]?.takeIf { it.size in 4..4096 }?.let { runCatching { wim.read(it) }.getOrNull() }
                val unixMode = if (aow != null && aow.size >= 4)
                    (aow[0].toInt() and 255) or ((aow[1].toInt() and 255) shl 8) or ((aow[2].toInt() and 255) shl 16) or ((aow[3].toInt() and 255) shl 24)
                else -1
                val type = if (unixMode >= 0) unixMode and 0xF000 else 0
                val dest = FirmwareContainers.destination(root, rel)
                when {
                    e.isDir -> { if (!dest.isDirectory && !dest.mkdirs()) throw LocalizedIOException("cannot create $rel", R.string.imp_cannot_create, rel) }
                    type == 0xA000 -> {
                        val target = wimLinkTarget(String(wim.read(e.data), Charsets.UTF_8))
                        val ok = target != null && runCatching { FirmwareContainers.checkLink(rel, target) }.isSuccess
                        if (ok) symlinks.add(target!! to "/$rel") else log("wim: symlink $rel has no usable target, skipped")
                    }
                    type == 0 || type == 0x8000 -> {
                        
                        if (AbLayout.skipRootFile(rel)) return@Visitor
                        dest.parentFile?.mkdirs()
                        
                        if (isLink(dest)) throw LocalizedIOException("WIM destination is a symlink", R.string.imp_yaffs_destination_is_a_symlink)
                        wim.open(e.data).use { i -> dest.outputStream().buffered(1 shl 20).use { o -> i.copyTo(o, 1 shl 16) } }
                        applyMode(dest, if (unixMode >= 0) unixMode and 0xfff else 0x1a4)
                        files++; bytes += dest.length()
                    }
                    else -> {}   
                }
                if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, "system", rel.substringAfterLast('/')), -1f)
            })
            gotSystem = true
            systemAsRoot = true
            log("WIM system: $n objects (root file system in the image)")
        }
        return true
    }

    
    private fun wimLinkTarget(raw: String): String? {
        val t = raw.replace('\\', '/').trimEnd('\u0000', '\r', '\n', ' ')
        if (t.isEmpty()) return null
        val norm = runCatching { java.nio.file.Paths.get(t).normalize().toString() }.getOrNull()
        return norm?.ifEmpty { null }
    }

    private fun importYaffs2(src: RandomSource, mount: String) {
        onProgress(lt(R.string.imp_reading_image_yaffs, mount), -1f)
        val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw LocalizedIOException("cannot create $rel", R.string.imp_cannot_create, rel) }
                2 -> {
                    val target = if (node.alias.startsWith("/")) node.alias.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.alias
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.alias.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw LocalizedIOException("YAFFS2 symlink escapes guest root", R.string.imp_yaffs_symlink_escapes_guest_root)
                    symlinks.add(node.alias to "/$rel")
                }
                1, 4 -> {
                    f.parentFile?.mkdirs()
                    
                    if (isLink(f)) throw LocalizedIOException("YAFFS2 destination is a symlink", R.string.imp_yaffs_destination_is_a_symlink)
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                5 -> {} 
            }
            if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, path.substringAfterLast('/')), -1f)
        }
        if (mount == "system") gotSystem = true
        val geo = fs.geometry
        log("YAFFS2 $mount: $n objects, page ${geo.pageSize}+${geo.spareSize}, " +
            (if (geo.sequential) "spare-less (sequential), " else "tags at spare+${geo.tagOffset}, start offset ${geo.base}, ") +
            (if (geo.order == java.nio.ByteOrder.LITTLE_ENDIAN) "LE" else "BE"))
        fs.warnings.forEach { log("YAFFS2 $mount: $it") }
    }

    
    private fun importUbifs(src: RandomSource, mount: String) {
        onProgress(lt(R.string.imp_reading_image_ubifs, mount), -1f)
        val fs = UbiFsReader(src) { if (cancelled) throw IOException("cancelled") }
        log("UBIFS $mount: ${fs.summary}")
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw LocalizedIOException("cannot create $rel", R.string.imp_cannot_create, rel) }
                2 -> {
                    val target = if (node.target.startsWith("/")) node.target.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.target
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.target.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw LocalizedIOException("UBIFS symlink escapes guest root", R.string.imp_ubifs_symlink_escapes_guest_root)
                    symlinks.add(node.target to "/$rel")
                }
                1 -> {
                    f.parentFile?.mkdirs()
                    
                    if (isLink(f)) throw LocalizedIOException("UBIFS destination is a symlink", R.string.imp_ubifs_destination_is_a_symlink)
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                else -> {} 
            }
            if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, path.substringAfterLast('/')), -1f)
        }
        if (mount == "system") gotSystem = true
        log("UBIFS $mount: $n objects")
        fs.warnings.forEach { log("UBIFS $mount: $it") }
    }

    
    private fun importErofs(src: RandomSource, mount: String) {
        onProgress("Reading image $mount (EROFS)", -1f)
        val fs = ErofsReader(src) { if (cancelled) throw IOException("cancelled") }
        
        val asRoot = mount == "system" && runCatching {
            AbLayout.isSystemAsRoot(fs.lookup("init")?.type, fs.lookup("system")?.type,
                fs.lookup("system/build.prop") != null, fs.lookup("system/framework") != null)
        }.getOrDefault(false)
        if (asRoot) { systemAsRoot = true; log("image system: system-as-root layout (root file system in the partition)") }
        var n = 0
        fs.walk { path, node ->
            if (asRoot && AbLayout.skipRootEntry(path, node.type == 1, node.type == 2)) return@walk
            val rel = if (asRoot) path else "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create $rel") }
                2 -> {
                    val target = if (node.target.startsWith("/")) node.target.trimStart('/')
                        else rel.substringBeforeLast('/', "") + "/" + node.target
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.target.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw IOException("EROFS symlink escapes guest root: $rel")
                    symlinks.add(node.target to "/$rel")
                }
                1 -> {
                    f.parentFile?.mkdirs()
                    
                    if (isLink(f)) throw IOException("EROFS destination is a symlink: $rel")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (mount == "system") gotSystem = true
        log("EROFS $mount: $n objects")
    }

    
    private val LOOSE_RAMDISK = Regex("(?i)(ramdisk|initrd|initramfs)(\\.cpio)?(\\.(gz|xz|lzma|lz4|img))?")

    
    
    private fun decodeBareRamdisk(src: RandomSource, name: String, h: ByteArray): List<BootImage.CpioEntry>? {
        val base = name.replace('\\', '/').substringAfterLast('/')
        if (!LOOSE_RAMDISK.matches(base) || src.size !in 1024..MAX_RAMDISK) return null
        val gzip = h.size > 2 && h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte()
        val lzma = h.size > 2 && h[0] == 0x5d.toByte() && h[1] == 0x00.toByte()
        val xz = h.size > 6 && h[0] == 0xfd.toByte() && String(h, 1, 4, Charsets.ISO_8859_1) == "7zXZ"
        val lz4 = h.size > 3 && h[0] == 0x02.toByte() && h[1] == 0x21.toByte() && h[2] == 0x4c.toByte()
        val cpio = h.size > 6 && String(h, 0, 6, Charsets.ISO_8859_1).let { it == "070701" || it == "070702" }
        if (!(gzip || lzma || xz || lz4 || cpio)) return null
        return runCatching { BootImage.decompress(readAllFrom(src))?.let { BootImage.cpio(it) } }.getOrNull()
            ?.takeIf { it.isNotEmpty() && LegacyFirmware.hasInitScript(it.map { e -> e.name }) }
    }

    private fun takeBareRamdisk(rd: List<BootImage.CpioEntry>, name: String) {
        if (ramdisk != null) { log("boot: $name ignored (a ramdisk was already taken)"); return }
        ramdisk = rd
        log("boot: bare ramdisk ${name.substringAfterLast('/')}, ${rd.size} files")
    }

    private fun takeLooseRamdisk(zip: ZipFile, entries: List<ZipArchiveEntry>, wrap: String, sysRoot: String?) {
        fun rel(e: ZipArchiveEntry) = e.name.replace('\\', '/').removePrefix(wrap)
        val usable = entries.filter { e ->
            val n = rel(e)
            !e.isDirectory && !n.contains("__MACOSX/") && systemRel(sysRoot, n) == null
        }
        
        for (e in usable.filter { LOOSE_RAMDISK.matches(rel(it).substringAfterLast('/')) && it.size in 1024..MAX_RAMDISK }
            .sortedBy { rel(it).count { c -> c == '/' } }) {
            val rd = runCatching {
                val raw = zip.getInputStream(e).use { it.readBytes() }
                BootImage.decompress(raw)?.let { BootImage.cpio(it) }
            }.getOrNull()
            if (!rd.isNullOrEmpty() && LegacyFirmware.hasInitScript(rd.map { it.name })) {
                ramdisk = rd
                log("boot: loose ramdisk ${rel(e)}, ${rd.size} files")
                return
            }
        }
        
        
        val rc = usable.firstOrNull { rel(it).substringAfterLast('/') == "init.rc" && rel(it).substringBeforeLast('/', "").let { d ->
            d.substringAfterLast('/').matches(Regex("(?i)(initrd|ramdisk|rootfs)")) ||
                d.substringAfterLast('/') == "etc" && d.substringBeforeLast('/', "").substringAfterLast('/').matches(Regex("(?i)(initrd|ramdisk|rootfs)")) } } ?: return
        val dir = rel(rc).substringBeforeLast('/').removeSuffix("/etc") + "/"
        val out = ArrayList<BootImage.CpioEntry>()
        for (e in usable) {
            val n = rel(e)
            if (!n.startsWith(dir) || e.size > MAX_RAMDISK) continue
            val name = n.removePrefix(dir)
            if (name.isEmpty()) continue
            val data = zip.getInputStream(e).use { it.readBytes() }
            if (e.isUnixSymlink) { out.add(BootImage.CpioEntry(name, 0xA000 or 0x1ff, data)); continue }
            val perm = e.unixMode.and(0xfff).takeIf { it != 0 } ?: if (name.startsWith("sbin/") || name == "init" || name.endsWith(".sh")) 0x1ed else 0x1a4
            out.add(BootImage.CpioEntry(name, 0x8000 or perm, data))
        }
        if (LegacyFirmware.hasInitScript(out.map { it.name })) {
            ramdisk = out
            log("boot: unpacked ramdisk folder ${dir.trimEnd('/')}, ${out.size} files")
        }
    }

    
    private fun useDeferredBoot() {
        applyVendorRamdisk()
        val data = recoveryBoot
        recoveryBoot = null
        val kernel = kernelBoot
        kernelBoot = null
        if (data != null && !systemAsRoot && ramdisk == null) {
            val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (rd != null) {
                ramdisk = rd
                File(paths.dir, "boot.img").writeBytes(data)
                log("boot: ramdisk, ${rd.size} files")
                return
            }
        } else if (data != null) log("boot: recovery ramdisk ignored (the root file system comes from the system partition)")
        
        
        val keep = data ?: kernel ?: return
        val target = File(paths.dir, "boot.img")
        if (target.isFile) return
        val out = BootImage.trimmed(keep)
        target.writeBytes(out)
        log("boot: boot.img kept (${out.size} bytes, ramdisk not used)")
    }

    
    private fun takeVendorBoot(src: RandomSource) {
        val vb = VendorBoot.read(src)
        if (vb == null) { log("vendor_boot: not readable, skipped"); return }
        if (vb.ramdisk.isEmpty()) { log("vendor_boot: no vendor ramdisk"); return }
        vendorRamdisk = vb.ramdisk
        log("vendor_boot: vendor ramdisk, ${vb.ramdisk.size} entries" + (vb.bootconfig?.let { "; bootconfig: " + it.lines().joinToString(" ") } ?: ""))
    }

    
    private fun applyVendorRamdisk() {
        val vendor = vendorRamdisk ?: return
        vendorRamdisk = null
        val base = ramdisk
        if (base == null) { log("vendor_boot: no boot ramdisk to lay the vendor ramdisk over, ignored"); return }
        ramdisk = VendorBoot.merge(base, vendor)
        log("boot: vendor ramdisk merged, ${ramdisk!!.size} files")
    }

    
    private fun importCrx(src: RandomSource, name: String, depth: Int) {
        val start = CrxContainer.zipStart(src) ?: throw LocalizedIOException("unknown file format \"$name\"", R.string.imp_unknown_file_format, name)
        ZipFile.builder().setSeekableByteChannel(RandomSourceChannel(src, start)).get().use { zip ->
            val entries = zip.entries.toList()
                .filter { !it.isDirectory && it.size >= 1024 && !it.name.startsWith("_metadata/") && !it.name.equals("manifest.json", true) }
                .sortedByDescending { it.size }
            if (entries.isEmpty()) throw LocalizedIOException("unknown file format \"$name\"", R.string.imp_unknown_file_format, name)
            log("crx: " + entries.joinToString { "${it.name} ${it.size shr 20} MB" })
            for (e in entries) {
                if (cancelled) throw IOException("cancelled")
                val base = e.name.substringAfterLast('/')
                onProgress(lt(R.string.imp_extracting, base), 0.1f)
                zip.getInputStream(e).use { importCrxEntry(it, e.size, base, depth) }
                if (gotSystem) break
            }
        }
    }

    private fun importCrxEntry(input: InputStream, size: Long, base: String, depth: Int) {
        
        val first = ByteArray(minOf(size, CrxContainer.MAX_STUB.toLong()).toInt())
        val n = GptDisk.readFully(input, first, 0, first.size)
        val at = CrxContainer.locateSevenZ(first, n, size)
        val rest: InputStream
        val label: String
        if (at >= 0) {
            log("crx: $base holds a 7z archive at offset $at (${(size - at) shr 20} MB)")
            rest = java.io.SequenceInputStream(java.io.ByteArrayInputStream(first, at, n - at), input)
            label = "payload.7z"
        } else {
            rest = java.io.SequenceInputStream(java.io.ByteArrayInputStream(first, 0, n), input)
            label = base
        }
        
        val file = spill(rest, label)
        try {
            try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, label, depth + 1) }
            } catch (t: Throwable) {
                
                if (cancelled || at >= 0 || gotSystem) throw t
                log("crx: $base skipped (${t.message ?: t.javaClass.simpleName})")
            }
        } finally { deleteTree(file.parentFile ?: file) }
    }

    private fun takeBoot(raw: ByteArray) {
        if (ramdisk != null) return
        val data = BootImage.stripHtcSignature(raw)
        val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: squashRamdisk(data)
        if (rd.isNullOrEmpty()) {
            
            
            if (BootImage.isKernelOnly(data)) { kernelBoot = data; log("boot: kernel-only boot image (GKI), kept as boot.img"); return }
            log("boot: ramdisk not recognized"); return
        }
        if (AbLayout.isRecoveryRamdisk(rd)) {
            
            recoveryBoot = data
            log("boot: recovery ramdisk (A/B boot image), ${rd.size} files; kept aside")
            return
        }
        ramdisk = rd
        File(paths.dir, "boot.img").writeBytes(data)
        log("boot: ramdisk, ${rd.size} files")
    }

    
    private fun squashRamdisk(raw: ByteArray): List<BootImage.CpioEntry>? {
        if (raw.size < 96) return null
        val magic = String(raw, 0, 4, Charsets.ISO_8859_1)
        if (magic != "hsqs" && magic != "sqsh") return null
        val tmp = File(paths.dir, "boot-rootfs.sqfs")
        return try {
            tmp.writeBytes(raw)
            val out = ArrayList<BootImage.CpioEntry>()
            var total = 0L
            var curMode = 0
            var cur: java.io.ByteArrayOutputStream? = null
            FirmwareToolset.walkSquashFs(tmp, { log(it) }, object : FirmwareToolset.SquashVisitor {
                override fun dir(path: String, mode: Int) { out.add(BootImage.CpioEntry(path, 0x4000 or (mode and 0xfff), ByteArray(0))) }
                override fun link(path: String, target: String) { out.add(BootImage.CpioEntry(path, 0xA000 or 0x1ff, target.toByteArray())) }
                override fun file(path: String, mode: Int, size: Long): java.io.OutputStream? {
                    if (size > MAX_RAMDISK_FILE) { log("boot rootfs: skipped $path (${size shr 20} MB)"); return null }
                    total += size
                    if (total > MAX_RAMDISK) throw LocalizedIOException("boot rootfs too large", R.string.imp_boot_rootfs_too_large)
                    curMode = mode
                    return java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(16)).also { cur = it }
                }
                override fun fileEnd(path: String) {
                    cur?.let { out.add(BootImage.CpioEntry(path, 0x8000 or (curMode and 0xfff), it.toByteArray())) }
                    cur = null
                }
            })
            if (out.none { it.name == "init.rc" }) { log("boot: SquashFS has no init.rc, not a root file system"); null }
            else { log("boot: SquashFS root file system, ${out.size} entries (glibc userland: ${out.count { it.name.startsWith("lib/") }} files in /lib)"); out }
        } catch (ex: Exception) {
            log("boot: SquashFS root file system: ${ex.message}, skipped"); null
        } finally { tmp.delete() }
    }

    

    private fun writeFile(rel: String, i: InputStream, mode: Int?) {
        val f = FirmwareContainers.destination(root, rel)
        f.parentFile?.mkdirs()
        if (runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)) f.delete()
        
        val src = java.io.BufferedInputStream(i, 1 shl 16)
        src.mark(1024)
        val head = ByteArray(512)
        var hn = 0
        while (hn < head.size) { val r = src.read(head, hn, head.size - hn); if (r < 0) break; hn += r }
        src.reset()
        if (hn in 12 until head.size && String(head, 0, 10, Charsets.ISO_8859_1) == "!<symlink>") {
            val body = head.copyOfRange(10, hn)
            val target = (if (body.size >= 2 && body[0] == 0xff.toByte() && body[1] == 0xfe.toByte())
                String(body, 2, body.size - 2, Charsets.UTF_16LE) else String(body, Charsets.UTF_8)).trimEnd('\u0000', '\n', '\r').trim()
            if (target.isNotEmpty() && !target.contains('\u0000')) {
                symlinks.add(target to "/" + rel.trimStart('/'))
                return
            }
        }
        f.outputStream().buffered(1 shl 20).use { o ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val n = src.read(buffer)
                if (n < 0) break
                bytes += n
                if (bytes > 16L * 1024 * 1024 * 1024 || root.usableSpace < n + 32L * 1024 * 1024)
                    throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                o.write(buffer, 0, n)
            }
        }
        if (mode != null) applyMode(f, mode and 0xfff)
        files++
    }

    private fun applyMode(f: File, perm: Int) {
        if (perm and 0x49 != 0) f.setExecutable(true, false)
        f.setReadable(true, false)
    }

    private fun parseUpdaterScript(s: String) {
        val text = s.replace(Regex("#[^\n]*"), "")
        for (m in Regex("symlink\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val args = Regex("\"([^\"]*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            if (args.size >= 2) for (link in args.drop(1)) symlinks.add(args[0] to link)
        }
        for (m in Regex("set_perm_recursive\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[1].split(',').map { it.trim().trim('"') }
            if (a.size >= 5) a[3].toIntOrNull(8)?.let { perms.add(Triple(a[4], it, true)) }
        }
        for (m in Regex("set_perm\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[1].split(',').map { it.trim().trim('"') }
            if (a.size >= 4) a[2].toIntOrNull(8)?.let { perms.add(Triple(a[3], it, false)) }
        }
        for (m in Regex("set_metadata(_recursive)?\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[2].split(',').map { it.trim().trim('"') }
            if (a.isEmpty()) continue
            val key = if (m.groupValues[1].isNotEmpty()) "fmode" else "mode"
            val i = a.indexOf(key)
            if (i > 0 && i + 1 < a.size) a[i + 1].toIntOrNull(8)?.let { perms.add(Triple(a[0], it, m.groupValues[1].isNotEmpty())) }
        }
        log("updater-script: ${symlinks.size} symlinks, ${perms.size} perms")
    }

    
    private fun finishTree() {
        
        ramdisk?.forEach { e ->
            val type = e.mode and 0xF000
            val n = e.name
            if (n.isEmpty() || n == "." || n.startsWith("system/") || n.startsWith("data/") || n.startsWith("dev/") || n.startsWith("proc") || n.startsWith("sys/")) return@forEach
            val f = FirmwareContainers.destination(root, n)
            when (type) {
                0x4000 -> f.mkdirs()
                0xA000 -> symlinks.add(String(e.data).trimEnd('\u0000') to "/$n")
                0x8000 -> {
                    if (n == "init" || n.startsWith("sbin/ueventd") || n.startsWith("sbin/adbd")) return@forEach
                    f.parentFile?.mkdirs(); f.writeBytes(e.data)
                    applyMode(f, e.mode and 0xfff)
                }
            }
        }
        var made = 0
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            val f = FirmwareContainers.destination(root, rel)
            FirmwareContainers.checkLink(rel, target)
            f.parentFile?.mkdirs()
            val t = relTarget(link, target)
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(t, f.absolutePath); made++
            }
        }
        for ((p, mode, rec) in perms) {
            val f = FirmwareContainers.destination(root, p.trimStart('/'))
            if (rec) f.walkTopDown().filter { it.isFile }.forEach { applyMode(it, mode) } else if (f.isFile) applyMode(f, mode)
        }
        
        for (d in listOf("system/bin", "system/xbin", "sbin", "vendor/bin", "system/vendor/bin")) {
            File(root, d).listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        }
        
        made += app.aemu.core.FlattenedLinks.repair(root, log)
        standardLinks()
        fun rootLink(name: String, target: String) {
            val f = File(root, name)
            if (!f.exists() && !isLink(f) && File(root, target).exists()) runCatching { Os.symlink(target, f.absolutePath) }
        }
        rootLink("vendor", "system/vendor")
        rootLink("etc", "system/etc")
        rootLink("bin", "system/bin")
        
        LegacyFirmware.ensureLayout(root, log)
        runCatching { File(root, "system/etc/mtab").let { if (!it.exists() && !isLink(it)) Os.symlink("../../proc/mounts", it.absolutePath) } }
        log("symlinks created: $made")
    }

    private fun isLink(f: File) = runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode) }.getOrDefault(false)

    
    
    private fun standardLinks() {
        val bin = File(root, "system/bin")
        fun missing(n: String) = File(bin, n).let { !it.exists() && !isLink(it) }
        var made = 0
        if (missing("sh")) {
            val sh = listOf("mksh", "ash").firstOrNull { File(bin, it).isFile }
            if (sh != null && runCatching { Os.symlink(sh, File(bin, "sh").absolutePath) }.isSuccess) made++
        }
        val toolbox = File(bin, "toolbox")
        if (toolbox.isFile) {
            val text = runCatching { String(toolbox.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            for (a in TOOLBOX_APPLETS) {
                if (!missing(a)) continue
                if (!text.contains("\u0000$a\u0000")) continue
                if (runCatching { Os.symlink("toolbox", File(bin, a).absolutePath) }.isSuccess) made++
            }
        }
        if (made > 0) log("sh/toolbox symlinks created: $made (missing from archive)")
    }

    private fun relTarget(link: String, target: String): String {
        if (!target.startsWith("/")) return target
        val from = link.trimStart('/').split('/').dropLast(1)
        val to = target.trimStart('/').split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < from.size && i < to.size && from[i] == to[i]) i++
        val ups = List(from.size - i) { ".." }
        return (ups + to.drop(i)).joinToString("/").ifEmpty { "." }
    }

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        runCatching { f.delete() }
    }

    
    private fun isSystemImageName(base: String, bare: Boolean = false) = PartitionNames.isSystem(base, bare)

    
    private fun findSystemRoot(names: Collection<String>): String? {
        val markers = listOf("framework/", "app/", "bin/", "lib/", "etc/")
        val found = LinkedHashMap<String, Boolean>() 
        for (raw in names) {
            val n = raw.replace('\\', '/').trimStart('/')
            var i = n.indexOf('/')
            var start = 0
            while (true) {
                if (n.regionMatches(start, "system/", 0, 7, ignoreCase = true)) {
                    val prefix = n.substring(0, start)
                    val rest = n.substring(start + 7)
                    if (start == 0) found.putIfAbsent("", false) 
                    if (rest == "build.prop") found[prefix] = true
                    else if (markers.any { rest.startsWith(it) }) found.putIfAbsent(prefix, false)
                }
                if (i < 0) break
                start = i + 1
                i = n.indexOf('/', start)
            }
        }
        if (found.isEmpty()) return null
        return found.entries.sortedWith(compareBy<Map.Entry<String, Boolean>>({ it.key.count { c -> c == '/' } }, { !it.value })).first().key
    }

    
    private fun systemRel(root: String?, n: String): String? {
        if (root == null || n.length < root.length + 7 || !n.startsWith(root)) return null
        if (!n.regionMatches(root.length, "system/", 0, 7, ignoreCase = true)) return null
        return "system/" + n.substring(root.length + 7)
    }

    
    private fun inSystemDump(n: String) = n.startsWith("system/") || n.contains("/system/")

    private fun bootFromArchive(f: File, name: String) {
        runCatching { FileInputStream(f).use { takeBoot(BootPartitionSource.read(it, name, tmp)) } }
    }

    companion object {
        private val SKIP_NESTED = Regex("(?i).*(gapps|supersu|magisk|busybox|xposed|twrp|modem|csc).*")
        
        private val TOOLBOX_APPLETS = listOf(
            "cat", "chcon", "chmod", "chown", "clear", "cmp", "cp", "date", "dd", "df", "dmesg", "du", "getenforce",
            "getevent", "getprop", "getsebool", "grep", "hd", "id", "ifconfig", "iftop", "insmod", "ioctl", "ionice",
            "kill", "ln", "load_policy", "log", "ls", "lsmod", "lsof", "md5", "mkdir", "mkswap", "mount", "mv",
            "nandread", "netstat", "newfs_msdos", "nohup", "notify", "printenv", "ps", "readlink", "renice",
            "restorecon", "rm", "rmdir", "rmmod", "route", "runcon", "schedtop", "sendevent", "setenforce",
            "setprop", "setsebool", "sleep", "smd", "start", "stop", "swapoff", "swapon", "sync", "top", "touch",
            "umount", "uptime", "vmstat", "watchprops", "wipe", "chroot", "sh",
        ).filter { it != "sh" }
    }
}