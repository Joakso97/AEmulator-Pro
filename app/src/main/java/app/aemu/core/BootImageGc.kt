
package app.aemu.core

import java.io.File


object BootImageGc {
    private const val KEY = "concurrent-copying"
    
    
    private const val SCAN_BYTES = 256 * 1024

    
    private fun candidates(root: File): List<File> {
        val out = ArrayList<File>()
        for (base in listOf("system/framework", "system/system/framework")) {
            for (isa in listOf("arm", "arm64", "x86", "x86_64")) out += File(root, "$base/$isa/boot.oat")
            out += File(root, "$base/boot.oat")
        }
        return out
    }

    
    fun bootOat(root: File): File? = candidates(root).firstOrNull { it.isFile }

    
    fun oatReadBarrier(oat: File): Boolean? {
        if (!oat.isFile) return null
        val buf = ByteArray(SCAN_BYTES)
        val n = runCatching { oat.inputStream().use { it.read(buf) } }.getOrDefault(-1)
        return if (n > 0) parseReadBarrier(buf, n) else null
    }

    
    fun parseReadBarrier(buf: ByteArray, len: Int = buf.size): Boolean? {
        val key = KEY.toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i + key.size + 2 <= len) {
            
            if (buf[i] == key[0] && (i == 0 || buf[i - 1].toInt() == 0) && buf[i + key.size].toInt() == 0 &&
                (key.indices).all { buf[i + it] == key[it] }) {
                val v = i + key.size + 1
                var e = v
                while (e < len && buf[e].toInt() != 0) e++
                if (e >= len) return null
                return when (String(buf, v, e - v, Charsets.US_ASCII)) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            i++
        }
        return null
    }

    
    fun bootImageIsNonCc(root: File): Boolean =
        candidates(root).firstNotNullOfOrNull { oatReadBarrier(it) } == false
}
