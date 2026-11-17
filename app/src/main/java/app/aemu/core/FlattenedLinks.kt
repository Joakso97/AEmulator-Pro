
package app.aemu.core

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes


object FlattenedLinks {
    const val MAX_LEN = 255
    private val TOKEN = Regex("^[A-Za-z0-9._+@,:~-]+(/[A-Za-z0-9._+@,:~-]+)*/?$|^/[A-Za-z0-9._+@,:~/-]+$")

    
    fun targetOf(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_LEN) return null
        if (bytes.any { it < 0x21 || it > 0x7e }) return null
        val t = String(bytes, Charsets.ISO_8859_1)
        if (!TOKEN.matches(t) || t == "." || t == ".." || t.endsWith("/")) return null
        return t
    }

    
    fun repair(root: File, log: (String) -> Unit = {}): Int {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val found = ArrayList<Pair<Path, String>>()
        for (top in listOf("system", "vendor", "sbin")) {
            val dir = rootPath.resolve(top)
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
            
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, a: BasicFileAttributes) =
                    if (d.fileName?.toString() == ".aemu-parked") FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
                    if (a.isRegularFile && a.size() in 1..MAX_LEN.toLong()) {
                        val t = runCatching { targetOf(Files.readAllBytes(f)) }.getOrNull()
                        if (t != null) found += f to t
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(f: Path, e: java.io.IOException) = FileVisitResult.CONTINUE
            })
        }
        var made = 0
        val names = ArrayList<String>()
        for ((file, target) in found) {
            val parent = file.parent ?: continue
            val resolved = (if (target.startsWith("/")) rootPath.resolve(target.trimStart('/')) else parent.resolve(target)).normalize()
            if (!resolved.startsWith(rootPath) || resolved == file || resolved == rootPath) continue
            if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) continue
            val rel = parent.relativize(resolved).toString().ifEmpty { continue }
            runCatching {
                Files.delete(file)
                Files.createSymbolicLink(file, Paths.get(rel))
                made++
                if (names.size < 12) names += file.fileName.toString()
            }
        }
        if (made > 0) log("symlinks: $made flattened link file(s) restored (${names.joinToString()}${if (made > names.size) ", …" else ""})")
        return made
    }
}
