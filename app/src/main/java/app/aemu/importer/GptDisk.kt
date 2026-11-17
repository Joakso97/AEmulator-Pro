package app.aemu.importer

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


object GptDisk {
    
    enum class Kind { BOOT, VENDOR_BOOT, RECOVERY, OEM, SYSTEM, SUPER, VENDOR, GAPPS }

    class Part(val name: String, val start: Long, val size: Long)

    private val SIG = "EFI PART".toByteArray(Charsets.US_ASCII)
    private const val MAX_HEAD = 4 shl 20

    private fun hasSig(b: ByteArray, n: Int, at: Int): Boolean {
        if (n < at + 8) return false
        for (i in SIG.indices) if (b[at + i] != SIG[i]) return false
        return true
    }

    
    private fun sectorSize(b: ByteArray, n: Int) = when {
        hasSig(b, n, 512) -> 512
        hasSig(b, n, 4096) -> 4096
        else -> 0
    }

    fun probe(b: ByteArray) = sectorSize(b, b.size) != 0

    
    fun headSize(b: ByteArray, n: Int): Int? {
        val ss = sectorSize(b, n)
        if (ss == 0 || n < ss + 92) return null
        val h = ByteBuffer.wrap(b, ss, 92).order(ByteOrder.LITTLE_ENDIAN)
        val lba = h.getLong(ss + 72)
        val count = h.getInt(ss + 80).toLong() and 0xffffffffL
        val sz = h.getInt(ss + 84).toLong() and 0xffffffffL
        if (count !in 1..1024 || sz !in 128..512 || lba < 2) return null
        val need = lba * ss + count * sz
        return if (need > MAX_HEAD) null else need.toInt()
    }

    
    fun parse(b: ByteArray): List<Part>? {
        val need = headSize(b, b.size) ?: return null
        if (b.size < need) return null
        val ss = sectorSize(b, b.size)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val lba = bb.getLong(ss + 72)
        val count = bb.getInt(ss + 80)
        val sz = bb.getInt(ss + 84)
        val out = ArrayList<Part>()
        for (i in 0 until count) {
            val o = (lba * ss + i.toLong() * sz).toInt()
            if (o + sz > b.size) break
            var empty = true
            for (k in 0 until 16) if (b[o + k].toInt() != 0) { empty = false; break }
            if (empty) continue
            val first = bb.getLong(o + 32)
            val last = bb.getLong(o + 40)
            if (first < 0 || last < first) continue
            val name = String(b, o + 56, minOf(72, sz - 56), Charsets.UTF_16LE).substringBefore('\u0000').trim()
            out.add(Part(name, first * ss, (last - first + 1) * ss))
        }
        return out
    }

    fun kindOf(name: String): Kind? = when (name.lowercase()) {
        "boot", "boot_a", "bootimg" -> Kind.BOOT
        "vendor_boot", "vendor_boot_a" -> Kind.VENDOR_BOOT 
        "recovery", "recovery_a", "fotakernel" -> Kind.RECOVERY
        "oem", "oem_a" -> Kind.OEM
        "system", "system_a", "factoryfs" -> Kind.SYSTEM
        "super" -> Kind.SUPER 
        "vendor", "vendor_a" -> Kind.VENDOR
        "gapps", "gapps_a" -> Kind.GAPPS
        else -> null
    }

    
    fun pick(parts: List<Part>): List<Pair<Kind, Part>> =
        Kind.values().mapNotNull { k -> parts.firstOrNull { kindOf(it.name) == k }?.let { k to it } }

    fun readFully(i: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = i.read(b, off + got, len - got)
            if (n < 0) break
            got += n
        }
        return got
    }
}
