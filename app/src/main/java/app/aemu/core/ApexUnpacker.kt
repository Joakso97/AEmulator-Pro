package app.aemu.core

import android.system.Os
import app.aemu.importer.ChannelSource
import app.aemu.importer.ErofsImage
import app.aemu.importer.Ext4Reader
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile


object ApexUnpacker {
    
    fun unpackAll(root: File, sysApex: File, log: (String) -> Unit): Int {
        val apexDir = File(root, "apex").apply { mkdirs() }
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var done = 0
        sysApex.listFiles()?.filter { it.isFile && (it.name.endsWith(".apex") || it.name.endsWith(".capex")) }?.sortedBy { it.name }?.forEach { pkg ->
            runCatching {
                val name = unpack(pkg, apexDir)
                if (name == null) {         
                    pkg.renameTo(File(parked, "${sysApex.relativeTo(root).path.replace('/', '#')}#${pkg.name}"))
                    return@runCatching
                }
                
                val flat = File(sysApex, pkg.name.removeSuffix(".apex").removeSuffix(".capex"))
                pkg.renameTo(File(parked, "${sysApex.relativeTo(root).path.replace('/', '#')}#${pkg.name}"))
                val up = "../".repeat(sysApex.relativeTo(root).path.count { it == '/' } + 1)
                if (!flat.exists()) Os.symlink("${up}apex/$name", flat.path)
                done++
            }.onFailure {
                
                
                val head = runCatching { pkg.inputStream().use { i -> ByteArray(8).let { b -> b.copyOf(maxOf(0, i.read(b))) } } }
                    .getOrDefault(ByteArray(0)).joinToString(" ") { "%02x".format(it) }
                log("apex ${pkg.name}: ${it.message} (${pkg.length()} bytes, starts with ${head.ifEmpty { "nothing" }})")
            }
        }
        return done
    }

    
    private fun unpack(pkg: File, apexDir: File): String? {
        ZipFile(pkg).use { zip ->
            
            zip.getEntry("original_apex")?.let { inner ->
                val orig = File(apexDir, ".original.tmp")
                try {
                    zip.getInputStream(inner).use { i -> orig.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    return unpack(orig, apexDir)
                } finally {
                    orig.delete()
                }
            }
            val manifest = zip.getEntry("apex_manifest.pb")?.let { zip.getInputStream(it).use { s -> s.readBytes() } }
            val name = manifest?.let(::moduleName) ?: pkg.name.removeSuffix(".apex").removeSuffix(".debug").removeSuffix(".release")
            val home = File(apexDir, name)
            if (File(home, "apex_manifest.pb").isFile || File(home, "apex_manifest.json").isFile) return null
            val payload = zip.getEntry("apex_payload.img") ?: error("no apex_payload.img")
            val img = File(apexDir, ".payload.tmp")
            try {
                zip.getInputStream(payload).use { i -> img.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                FileChannel.open(img.toPath(), StandardOpenOption.READ).use { ch ->
                    val src = ChannelSource(ch)
                    val links = ArrayList<Pair<String, File>>()
                    home.mkdirs()
                    
                    if (!Ext4Reader.probe(src)) (runCatching { ErofsImage(src) }.getOrNull() ?: error("payload is neither ext4 nor EROFS"))
                        .walk { path, isDir, link, mode, _, data ->
                            val f = File(home, path.trimStart('/'))
                            when {
                                isDir -> f.mkdirs()
                                link != null -> links.add(link to f)
                                data != null -> {
                                    f.parentFile?.mkdirs()
                                    data().use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                                    f.setReadable(true, false)
                                    if (mode and 0x49 != 0) f.setExecutable(true, false)
                                }
                            }
                        }
                    else Ext4Reader(src).let { fs -> fs.walk { path, node ->
                        val f = File(home, path)
                        when {
                            node.isDir -> f.mkdirs()
                            node.isLink -> links.add(fs.linkTarget(node) to f)
                            node.isFile -> {
                                f.parentFile?.mkdirs()
                                f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                                f.setReadable(true, false)
                                if (node.perm and 0x49 != 0) f.setExecutable(true, false)
                            }
                        }
                    } }
                    for ((target, f) in links) runCatching {
                        f.parentFile?.mkdirs()
                        
                        val depth = f.relativeTo(apexDir.parentFile!!).path.count { it == '/' }
                        val t = if (target.startsWith("/")) "../".repeat(depth) + target.trimStart('/') else target
                        if (!f.exists()) Os.symlink(t, f.path)
                    }
                }
            } catch (t: Throwable) {
                ImageStore.wipe(home)       
                throw t
            } finally {
                img.delete()
            }
            if (manifest != null && !File(home, "apex_manifest.pb").isFile) File(home, "apex_manifest.pb").writeBytes(manifest)
            return name
        }
    }

    
    private fun moduleName(pb: ByteArray): String? {
        if (pb.size < 3 || pb[0].toInt() != 0x0A) return null
        val len = pb[1].toInt() and 0xff
        if (len == 0 || len > 127 || 2 + len > pb.size) return null
        return String(pb, 2, len, Charsets.UTF_8).takeIf { n -> n.all { it.isLetterOrDigit() || it == '.' || it == '_' } }
    }
}
