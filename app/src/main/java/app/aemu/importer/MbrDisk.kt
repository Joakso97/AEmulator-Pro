package app.aemu.importer

import java.nio.ByteBuffer


object MbrDisk {
    
    enum class Role { BOOT_FAT, BOOT_RAW, OEM, SYSTEM, VENDOR }

    enum class Fs { EXT, FAT, ANDROID_BOOT, OTHER }

    
    class Part(val number: Int, val type: Int, val start: Long, val size: Long, val logical: Boolean)

    
    class Ident(val fs: Fs, val label: String)

    const val SECTOR = 512L
    
    const val IDENT_BYTES = 2048
    
    const val MAX_BOOT_FAT = 1L shl 30

    private const val MAX_LOGICAL = 128
    private val BOOT_LABELS = setOf("boot", "bootimg", "boot_a", "android_boot", "androidboot", "kernel", "uboot", "u-boot", "bootfs")
    private val SYSTEM_LABELS = setOf("system", "system_a", "android_system", "androidsystem", "system_image", "systemfs")
    private val VENDOR_LABELS = setOf("vendor", "vendor_a")
    private val OEM_LABELS = setOf("oem", "oem_a")
    
    private val DATA_LABELS = setOf("cache", "userdata", "data", "sdcard", "media", "persist", "misc", "metadata", "storage", "internal", "extsd")

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    private fun isExtended(type: Int) = type == 0x05 || type == 0x0f || type == 0x85

    
    class Entry(val type: Int, val first: Long, val count: Long)

    
    fun entries(sector: ByteArray): List<Entry>? {
        if (sector.size < 512 || u8(sector, 510) != 0x55 || u8(sector, 511) != 0xAA) return null
        val out = ArrayList<Entry>(4)
        for (i in 0 until 4) {
            val o = 446 + i * 16
            val type = u8(sector, o + 4)
            if (type == 0) continue
            out.add(Entry(type, u32(sector, o + 8), u32(sector, o + 12)))
        }
        return out
    }

    
    fun probe(h: ByteArray): Boolean {
        if (h.size < 512 || u8(h, 510) != 0x55 || u8(h, 511) != 0xAA) return false
        if (FatReader.probe(h)) return false
        val oem = String(h, 3, 8, Charsets.ISO_8859_1)
        if (oem.startsWith("NTFS") || oem.startsWith("EXFAT")) return false
        var used = 0
        val spans = ArrayList<LongArray>()
        for (i in 0 until 4) {
            val o = 446 + i * 16
            val flag = u8(h, o)
            if (flag != 0 && flag != 0x80) return false
            val type = u8(h, o + 4)
            if (type == 0) continue
            if (type == 0xEE || type == 0xEF) return false
            val first = u32(h, o + 8)
            val count = u32(h, o + 12)
            if (first < 1 || count < 1 || first + count > 0xFFFFFFFFL + 1) return false
            spans.add(longArrayOf(first, first + count))
            used++
        }
        if (used == 0) return false
        spans.sortBy { it[0] }
        for (i in 1 until spans.size) if (spans[i][0] < spans[i - 1][1]) return false
        return true
    }

    
    fun primaries(sector0: ByteArray): List<Part> {
        val es = entries(sector0) ?: return emptyList()
        return es.mapIndexed { i, e -> Part(i + 1, e.type, e.first * SECTOR, e.count * SECTOR, false) }
    }

    fun isExtendedPart(p: Part) = isExtended(p.type)

    fun isExtendedType(type: Int) = isExtended(type)

    
    fun parse(src: RandomSource): List<Part> {
        val s0 = ByteBuffer.allocate(512).also { src.read(0, it) }.array()
        val prim = primaries(s0)
        val out = ArrayList<Part>()
        var number = 5
        for (p in prim) {
            if (!isExtended(p.type)) { out.add(p); continue }
            val base = p.start / SECTOR
            var cur = base
            var guard = 0
            while (guard++ < MAX_LOGICAL) {
                if ((cur + 1) * SECTOR > src.size) break
                val sec = ByteBuffer.allocate(512).also { src.read(cur * SECTOR, it) }.array()
                val es = entries(sec) ?: break
                val data = es.firstOrNull { !isExtended(it.type) }
                if (data != null && data.count > 0) out.add(Part(number++, data.type, (cur + data.first) * SECTOR, data.count * SECTOR, true))
                val next = es.firstOrNull { isExtended(it.type) } ?: break
                val nextLba = base + next.first
                if (nextLba <= cur) break
                cur = nextLba
            }
        }
        return out.sortedBy { it.start }
    }

    
    fun identify(head: ByteArray): Ident {
        if (head.size >= 8 && String(head, 0, 8, Charsets.ISO_8859_1) == "ANDROID!") return Ident(Fs.ANDROID_BOOT, "")
        if (head.size >= 1082 && (u8(head, 1080) or (u8(head, 1081) shl 8)) == 0xEF53) {
            val raw = String(head, 1024 + 120, 16, Charsets.ISO_8859_1)
            return Ident(Fs.EXT, raw.substringBefore('\u0000').trim())
        }
        if (head.size >= 512 && FatReader.probe(head)) {
            
            val label = when {
                head.size >= 87 && u8(head, 66) == 0x29 && String(head, 82, 5, Charsets.ISO_8859_1).startsWith("FAT") -> String(head, 71, 11, Charsets.ISO_8859_1)
                head.size >= 54 && u8(head, 38) == 0x29 -> String(head, 43, 11, Charsets.ISO_8859_1)
                else -> ""
            }.trim().let { if (it.equals("NO NAME", true)) "" else it }
            return Ident(Fs.FAT, label)
        }
        return Ident(Fs.OTHER, "")
    }

    
    private fun norm(label: String) = label.trim().trim('/').lowercase()

    fun isDataLabel(label: String) = norm(label) in DATA_LABELS

    
    fun roleOf(id: Ident, size: Long, firstFat: Boolean, rawBootTaken: Boolean): Role? {
        val l = norm(id.label)
        return when (id.fs) {
            Fs.EXT -> when (l) {
                in SYSTEM_LABELS -> Role.SYSTEM
                in VENDOR_LABELS -> Role.VENDOR
                in OEM_LABELS -> Role.OEM
                else -> null
            }
            Fs.FAT -> if (size <= MAX_BOOT_FAT && (l in BOOT_LABELS || (firstFat && l.isEmpty()))) Role.BOOT_FAT else null
            Fs.ANDROID_BOOT -> if (rawBootTaken) null else Role.BOOT_RAW
            Fs.OTHER -> null
        }
    }

    
    fun pick(found: List<Pair<Role, Part>>): List<Pair<Role, Part>> =
        Role.values().mapNotNull { r -> found.firstOrNull { it.first == r } }
}
