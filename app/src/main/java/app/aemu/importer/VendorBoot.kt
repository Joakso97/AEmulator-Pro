
package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder


internal object VendorBoot {
    private const val MAGIC = "VNDRBOOT"
    private const val MAX_RAMDISK = 256L shl 20
    private const val TYPE_RECOVERY = 2

    class Result(val ramdisk: List<BootImage.CpioEntry>, val bootconfig: String?)

    fun probe(first: ByteArray): Boolean = first.size >= 8 && String(first, 0, 8, Charsets.ISO_8859_1) == MAGIC

    private fun align(n: Long, page: Long) = (n + page - 1) / page * page

    
    fun read(src: RandomSource): Result? = runCatching {
        val h = ByteBuffer.allocate(4096).also { src.read(0, it) }.array()
        if (!probe(h)) return null
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(8)
        val page = (bb.getInt(12).toLong() and 0xffffffffL)
        val ramdiskSize = bb.getInt(24).toLong() and 0xffffffffL
        val headerSize = bb.getInt(2096).toLong() and 0xffffffffL
        val dtbSize = bb.getInt(2100).toLong() and 0xffffffffL
        if ((version != 3 && version != 4) || page !in 2048..65536 || headerSize !in 2112..4096 || ramdiskSize > MAX_RAMDISK) return null
        val ramStart = align(headerSize, page)
        if (ramStart + ramdiskSize > src.size) return null
        fun bytes(at: Long, len: Long): ByteArray = ByteBuffer.allocate(len.toInt()).also { src.read(at, it) }.array()

        val entries = ArrayList<BootImage.CpioEntry>()
        var bootconfig: String? = null
        val section = if (ramdiskSize > 0) bytes(ramStart, ramdiskSize) else ByteArray(0)
        var tableEntries = 0
        if (version >= 4) {
            val tableSize = bb.getInt(2112).toLong() and 0xffffffffL
            tableEntries = bb.getInt(2116)
            val entrySize = bb.getInt(2120).toLong() and 0xffffffffL
            val bcSize = bb.getInt(2124).toLong() and 0xffffffffL
            val tableStart = ramStart + align(ramdiskSize, page) + align(dtbSize, page)
            if (tableEntries in 1..64 && entrySize >= 12 && tableEntries * entrySize <= tableSize && tableStart + tableSize <= src.size) {
                val table = ByteBuffer.wrap(bytes(tableStart, tableEntries * entrySize)).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until tableEntries) {
                    val o = (i * entrySize).toInt()
                    val size = table.getInt(o).toLong() and 0xffffffffL
                    val off = table.getInt(o + 4).toLong() and 0xffffffffL
                    val type = table.getInt(o + 8)
                    
                    if (type == TYPE_RECOVERY || size == 0L || off + size > section.size) continue
                    unpack(section.copyOfRange(off.toInt(), (off + size).toInt()), entries)
                }
            } else tableEntries = 0
            if (bcSize in 1..65536) {
                val bcStart = tableStart + align(tableSize, page)
                if (bcStart + bcSize <= src.size) bootconfig = String(bytes(bcStart, bcSize), Charsets.UTF_8).trim { it <= ' ' }
            }
        }
        
        if (tableEntries == 0 && section.isNotEmpty()) unpack(section, entries)
        Result(entries, bootconfig)
    }.getOrNull()

    private fun unpack(compressed: ByteArray, into: MutableList<BootImage.CpioEntry>) {
        val cpio = BootImage.decompress(compressed) ?: return
        into.addAll(BootImage.cpio(cpio))
    }

    
    fun merge(base: List<BootImage.CpioEntry>, overlay: List<BootImage.CpioEntry>): List<BootImage.CpioEntry> {
        val index = HashMap<String, Int>()
        val out = ArrayList<BootImage.CpioEntry>(base.size + overlay.size)
        fun norm(n: String) = n.removePrefix("./").trimEnd('/')
        for (e in base) { index[norm(e.name)] = out.size; out.add(e) }
        for (e in overlay) {
            val at = index[norm(e.name)]
            if (at != null) out[at] = e else { index[norm(e.name)] = out.size; out.add(e) }
        }
        return out
    }
}
