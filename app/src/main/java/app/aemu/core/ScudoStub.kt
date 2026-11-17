
package app.aemu.core

import java.io.File
import java.nio.file.Files


internal object ScudoStub {
    const val LIB = "libc_scudo.so"

    
    fun candidates(root: File): List<File> {
        val dirs = ArrayList<File>()
        for (d in listOf("system/lib64", "vendor/lib64", "system/vendor/lib64")) dirs += File(root, d)
        File(root, "apex").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dirs += File(it, "lib64") }
        return dirs.map { File(it, LIB) }.filter { it.isFile }
    }

    
    fun isX8664SharedObject(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val h = ByteArray(20)
            if (i.read(h) != 20) return@use false
            h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                h[4] == 2.toByte() && h[5] == 1.toByte() &&
                (h[16].toInt() and 0xff) == 3 && (h[17].toInt() and 0xff) == 0 &&       
                (h[18].toInt() and 0xff) == 62 && (h[19].toInt() and 0xff) == 0         
        }
    }.getOrDefault(false)

    
    fun needsStub(f: File, stub: ByteArray): Boolean {
        if (!f.isFile) return false
        if (f.length() == stub.size.toLong() && runCatching { f.readBytes().contentEquals(stub) }.getOrDefault(false)) return false
        return isX8664SharedObject(f)
    }

    
    fun apply(root: File, stub: ByteArray, log: (String) -> Unit): List<String> {
        val done = ArrayList<String>()
        for (f in candidates(root)) {
            if (!needsStub(f, stub)) continue
            val rel = f.relativeTo(root).path
            runCatching {
                val parked = File(root, "system/.aemu-parked/" + rel.replace('/', '#'))
                parked.parentFile?.mkdirs()
                
                if (!parked.exists()) f.copyTo(parked)
                if (Files.isSymbolicLink(f.toPath())) Files.delete(f.toPath())
                f.writeBytes(stub)
                f.setReadable(true, false)
                f.setExecutable(true, false)
                done += "/$rel"
            }.onFailure { log("scudo: /$rel not replaced: ${it.message}") }
        }
        if (done.isNotEmpty()) log("scudo: ${done.joinToString()} replaced by an empty library; mediaextractor and media.swcodec use bionic's malloc (the 4 TiB region of the x86-64 Scudo does not fit the phone's 39-bit address space)")
        return done
    }
}
