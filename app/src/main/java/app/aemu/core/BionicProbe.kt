package app.aemu.core

import java.io.File
import java.io.RandomAccessFile


object BionicProbe {
    private val MARKER = "error setting PER_LINUX32 personality".toByteArray(Charsets.US_ASCII)

    
    fun hasPersonalityCheck(f: File): Boolean {
        if (!f.isFile || f.length() < MARKER.size) return false
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(256 * 1024)
                val keep = MARKER.size - 1
                var have = 0
                var found = false
                while (!found) {
                    val n = raf.read(buf, have, buf.size - have)
                    if (n <= 0) break
                    val end = have + n
                    if (indexOf(buf, end) >= 0) { found = true; break }
                    
                    val tail = minOf(keep, end)
                    System.arraycopy(buf, end - tail, buf, 0, tail)
                    have = tail
                }
                found
            }
        }.getOrDefault(false)
    }

    private fun indexOf(b: ByteArray, end: Int): Int {
        val m = MARKER
        var i = 0
        while (i + m.size <= end) {
            var j = 0
            while (j < m.size && b[i + j] == m[j]) j++
            if (j == m.size) return i
            i++
        }
        return -1
    }

    
    fun isMarshmallowPlus(root: File, api: Int): Boolean =
        api >= 23 || carriesCheck(root)

    
    private fun carriesCheck(root: File): Boolean =
        listOf("system/bin/linker", "system/lib/libc.so", "system/bin/linker64", "system/lib64/libc.so")
            .any { hasPersonalityCheck(File(root, it)) }

    
    fun ruleApi(root: File, api: Int): Int = if (api < 23 && isMarshmallowPlus(root, api)) 23 else api
}
