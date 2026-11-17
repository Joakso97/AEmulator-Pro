package app.aemu.importer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder


internal object AcerBin {
    const val RECORD = 0x40
    private const val NAME_AT = 0x14
    private const val MAX_ENTRIES = 256
    private const val MAX_PROFILE = 64 * 1024

    
    const val MIN_SIZE = 2L * RECORD

    
    class Entry(val name: String, val offset: Long, val size: Long) {
        
        val base: String get() = name.substringAfterLast('/').substringAfterLast('\\')
        override fun toString() = "$name ($size bytes)"
    }

    
    fun probe(h: ByteArray): Boolean {
        if (h.size < MIN_SIZE) return false
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val count = u32(b, 0)
        if (count < 2 || count > MAX_ENTRIES) return false
        val records = minOf(count, (h.size / RECORD).toLong()).toInt()
        if (records < 2) return false
        var expect = 0L
        for (i in 0 until records) {
            val at = i * RECORD
            if (u32(b, at + 4) != expect) return false
            if (readName(h, at + NAME_AT) == null) return false
            expect += u32(b, at + 8)
        }
        return true
    }

    
    fun entries(src: RandomSource): List<Entry> {
        if (src.size < MIN_SIZE) throw IOException("Acer package: file too small")
        val first = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        src.read(0, first); first.flip()
        val count = u32(first, 0)
        if (count < 2 || count > MAX_ENTRIES) throw IOException("Acer package: bad entry count $count")
        val tableSize = count * RECORD
        if (tableSize > src.size) throw IOException("Acer package: truncated file (table needs $tableSize bytes)")
        val t = ByteBuffer.allocate(tableSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        src.read(0, t); t.flip()
        val out = ArrayList<Entry>(count.toInt())
        var expect = 0L
        for (i in 0 until count.toInt()) {
            val at = i * RECORD
            val off = u32(t, at + 4)
            val size = u32(t, at + 8)
            val name = readName(t.array(), at + NAME_AT) ?: throw IOException("Acer package: bad file name in record $i")
            if (off != expect) throw IOException("Acer package: record $i ($name) is not contiguous")
            val abs = tableSize + off
            if (abs + size > src.size)
                throw IOException("Acer package: truncated file ($name needs ${abs + size} bytes, file has ${src.size})")
            out.add(Entry(name, abs, size))
            expect = off + size
        }
        return out
    }

    
    fun profile(src: RandomSource, entries: List<Entry>): Map<String, String> {
        val e = entries.firstOrNull { it.base.endsWith(".mlf", true) } ?: return emptyMap()
        if (e.size <= 0) return emptyMap()
        val buf = ByteBuffer.allocate(minOf(e.size, MAX_PROFILE.toLong()).toInt())
        SliceSource(src, e.offset, e.size).read(0, buf)
        val text = String(buf.array(), Charsets.ISO_8859_1)
        val out = LinkedHashMap<String, String>()
        var inFirst = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                if (inFirst) break 
                inFirst = line.equals("[Package Download Profile]", true)
                continue
            }
            if (!inFirst) continue
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq).trim().uppercase()] = line.substring(eq + 1).trim()
        }
        return out
    }

    
    fun isRootfs(base: String) = ROOTFS.matches(base)

    private val ROOTFS = Regex("(?i)rootfs(\\.img)?")

    
    private fun readName(a: ByteArray, at: Int): String? {
        val len = RECORD - NAME_AT
        if (at + len > a.size) return null
        var end = 0
        while (end < len && a[at + end].toInt() != 0) end++
        if (end == 0) return null
        for (i in 0 until end) {
            val c = a[at + i].toInt() and 0xff
            if (c < 0x20 || c >= 0x7f) return null
        }
        for (i in end until len) if (a[at + i].toInt() != 0) return null
        return String(a, at, end, Charsets.US_ASCII)
    }

    private fun u32(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and 0xffffffffL
}
