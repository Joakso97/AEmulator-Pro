package app.aemu.core

import java.io.File
import java.nio.file.Files


internal object FbDirectPatch {
    private const val ANY = -1

    
    private val API_RANGE = 1..2

    
    
    private val MAP_SIG = intArrayOf(
        ANY, 0x98, ANY, ANY, ANY, ANY, 0x00, 0x28, 0x06, 0xd1, ANY, 0x99, 0x20, 0x1c, ANY, ANY, ANY, ANY, 0x0c, 0x27, 0x7f, 0x42,
    )
    private val MAP_NEW = intArrayOf(0x20, 0x1c, 0x09, 0xe0, 0xc0, 0x46) 

    
    private val DTOR_SIG = intArrayOf(
        0xa3, 0x68, 0xda, 0x07, ANY, 0xd4, 0x32, 0x1c, 0xf0, 0x32, 0x10, 0x68, ANY, ANY, ANY, ANY, 0x30, 0x1c, 0x70, 0xbd,
    )

    
    private val DONE_SIG = intArrayOf(0x20, 0x1c, 0x09, 0xe0, 0xc0, 0x46, 0x00, 0x28, 0x06, 0xd1, ANY, 0x99, 0x20, 0x1c)

    
    
    
    
    private val MAP_B_SIG = intArrayOf(
        ANY, 0x98, ANY, 0xf7, ANY, ANY,                          
        0x00, 0x28, ANY, 0xd0, ANY, 0x90, ANY, 0xe7,             
    )
    private const val MAP_B_AT = 0
    private val MAP_B_NEW = intArrayOf(0x20, 0x1c, 0xc0, 0x46, 0xc0, 0x46) 
    private val MAP_B_DONE = intArrayOf(0x20, 0x1c, 0xc0, 0x46, 0xc0, 0x46, 0x00, 0x28, ANY, 0xd0, ANY, 0x90)

    
    
    private val DTOR_B_SIG = intArrayOf(
        0xb4, 0x20, 0x23, 0x58, 0xda, 0x07, ANY, 0xd4, 0x9c, 0x22, 0xa0, 0x58, ANY, 0xf7,
    )

    
    private fun findAll(d: ByteArray, sig: IntArray, accept: (Int) -> Boolean = { true }): List<Int> {
        val hits = ArrayList<Int>()
        var i = 0
        while (i <= d.size - sig.size) {
            var ok = true
            for (k in sig.indices) if (sig[k] != ANY && (d[i + k].toInt() and 0xff) != sig[k]) { ok = false; break }
            if (ok && accept(i)) hits += i
            i += 2
        }
        return hits
    }

    private fun mapSite(d: ByteArray): List<Int> =
        findAll(d, MAP_SIG) { d[it] == d[it + 10] } 

    private fun dtorSite(d: ByteArray): List<Int> =
        findAll(d, DTOR_SIG) { (d[it + 4].toInt() and 0xff) < 0x80 } 

    
    fun patch(d: ByteArray): Boolean {
        val m = mapSite(d)
        val t = dtorSite(d)
        if (m.size != 1 || t.size != 1) return patchB(d)
        for (k in MAP_NEW.indices) d[m[0] + k] = MAP_NEW[k].toByte()
        
        d[t[0] + 5] = 0xe0.toByte()
        return true
    }

    fun alreadyPatched(d: ByteArray): Boolean = findAll(d, DONE_SIG).isNotEmpty() || findAll(d, MAP_B_DONE).isNotEmpty()

    
    private fun siteCountsB(d: ByteArray): Pair<Int, Int> =
        findAll(d, MAP_B_SIG).size to findAll(d, DTOR_B_SIG) { (d[it + 6].toInt() and 0xff) < 0x80 }.size

    private fun patchB(d: ByteArray): Boolean {
        val m = findAll(d, MAP_B_SIG)
        val t = findAll(d, DTOR_B_SIG) { (d[it + 6].toInt() and 0xff) < 0x80 }
        if (m.size != 1 || t.isEmpty() || t.size > 2) return false
        val at = m[0] + MAP_B_AT
        for (k in MAP_B_NEW.indices) d[at + k] = MAP_B_NEW[k].toByte()
        for (x in t) d[x + 7] = 0xe0.toByte()   
        return true
    }

    
    private fun candidates(root: File): List<File> {
        val out = LinkedHashSet<File>()
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            val base = File(root, dir)
            if (!base.isDirectory) continue
            base.walkTopDown().maxDepth(2).filter { it.name == "libui.so" }.forEach { out += it }
        }
        return out.toList()
    }

    private fun patchFile(root: File, f: File, log: (String) -> Unit) {
        if (!f.isFile || Files.isSymbolicLink(f.toPath())) return
        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) return
        val d = f.readBytes()
        if (alreadyPatched(d)) { log("display: ${f.relativeTo(root).path} already carries the direct-to-fb0 patch"); return }
        val orig = d.copyOf()
        
        
        val mapHits = mapSite(d).size
        val dtorHits = dtorSite(d).size
        val (mapB, dtorB) = siteCountsB(d)
        val matchesA = mapHits == 1 && dtorHits == 1
        val matchesB = mapB == 1 && dtorB in 1..2
        if (!matchesA && !matchesB) {
            val text = String(d, Charsets.ISO_8859_1)
            val at = text.indexOf("page flipping not supported")
            log("display: ${f.relativeTo(root).path} (${d.size} bytes) NOT patched: layout A map/dtor matches=$mapHits/$dtorHits (1/1 needed), layout B map/dtor matches=$mapB/$dtorB (1/1-2 needed); " +
                "message string ${if (at >= 0) "at 0x" + at.toString(16) else "not found"}; ELF class ${d.getOrNull(4)?.toInt()}, machine 0x" +
                (if (d.size > 19) (((d[19].toInt() and 0xff) shl 8) or (d[18].toInt() and 0xff)).toString(16) else "?"))
            return
        }
        if (patch(d)) {
            val bak = File(f.parentFile, "libui.so.aemu-orig")
            if (!bak.exists()) bak.writeBytes(orig)
            f.setWritable(true)
            f.writeBytes(d)
            log("display: ${f.relativeTo(root).path} patched (Android 1.x draws straight into fb0 when the framebuffer cannot page-flip)")
        }
    }

    
    fun apply(root: File, api: Int, log: (String) -> Unit) {
        if (api !in API_RANGE) return
        for (f in candidates(root)) runCatching { patchFile(root, f, log) }
            .onFailure { log("display: libui patch failed (${f.name}): ${it.message}") }
    }
}
