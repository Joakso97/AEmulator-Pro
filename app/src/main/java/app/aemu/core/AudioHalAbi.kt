package app.aemu.core


object AudioHalAbi {
    private val timestampPrefix = byteArrayOf(
        0x13, 0xb5.toByte(), 0x0c, 0x46, 
        0x80.toByte(), 0x69,            
        0x83.toByte(), 0x6e,            
        0x13, 0xb9.toByte(),            
        0x6f, 0xf0.toByte(), 0x25, 0x00 
    )

    
    fun withDeviceVersion(elf: ByteArray, version: Int): ByteArray? {
        
        var at = -1
        for (i in 0..elf.size - 8) {
            if (elf[i] == 0x54.toByte() && elf[i + 1] == 0x44.toByte() && elf[i + 2] == 0x57.toByte() && elf[i + 3] == 0x48.toByte()) {
                if (at >= 0) return null
                at = i
            }
        }
        if (at >= 0) {
            val cur = (elf[at + 4].toInt() and 0xff) or ((elf[at + 5].toInt() and 0xff) shl 8) or
                ((elf[at + 6].toInt() and 0xff) shl 16) or ((elf[at + 7].toInt() and 0xff) shl 24)
            if (cur != 0x0200 && cur != version) return null
            val out = elf.copyOf()
            out[at + 4] = (version and 0xff).toByte(); out[at + 5] = ((version shr 8) and 0xff).toByte()
            out[at + 6] = ((version shr 16) and 0xff).toByte(); out[at + 7] = ((version shr 24) and 0xff).toByte()
            return out
        }
        
        
        
        val imm8 = when (version) { 0x0100 -> 0x80; 0x0200 -> 0x00; 0x0300 -> 0x40; else -> return null }
        val movVer = byteArrayOf(0x4f, 0xf4.toByte(), 0x00, 0x7c)
        val movwTag = byteArrayOf(0x44, 0xf2.toByte(), 0x54, 0x42)
        var site = -1
        for (i in 0..elf.size - 4) {
            if (elf[i] == movVer[0] && elf[i + 1] == movVer[1] && elf[i + 2] == movVer[2] && elf[i + 3] == movVer[3]) {
                var tagNear = false
                for (k in i + 4..minOf(i + 48, elf.size - 4)) {
                    if (elf[k] == movwTag[0] && elf[k + 1] == movwTag[1] && elf[k + 2] == movwTag[2] && elf[k + 3] == movwTag[3]) { tagNear = true; break }
                }
                if (tagNear) { if (site >= 0) return null; site = i }
            }
        }
        if (site < 0) return null
        val out = elf.copyOf()
        out[site + 2] = imm8.toByte()
        return out
    }

    
    fun expectedDeviceVersion(elf: ByteArray): Int? = runCatching {
        fun u16(o: Int) = (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[4] != 1.toByte() ||
            u16(0x12) != 40) return null
        val phoff = u32(0x1c); val phentsize = u16(0x2a); val phnum = u16(0x2c)
        val shoff = u32(0x20); val shentsize = u16(0x2e); val shnum = u16(0x30)
        fun fileOffset(vaddr: Int): Int? {
            for (i in 0 until phnum) {
                val ph = phoff + i * phentsize
                if (u32(ph) != 1) continue 
                val off = u32(ph + 4); val va = u32(ph + 8); val filesz = u32(ph + 16)
                if (vaddr >= va && vaddr < va + filesz) return vaddr - va + off
            }
            return null
        }
        fun thumbExpandImm(imm12: Int): Int {
            val b = imm12 and 0xff
            if ((imm12 shr 10) == 0) return when ((imm12 shr 8) and 3) {
                0 -> b
                1 -> (b shl 16) or b
                2 -> (b shl 24) or (b shl 8)
                else -> (b shl 24) or (b shl 16) or (b shl 8) or b
            }
            val v = 0x80 or (b and 0x7f)
            val r = (imm12 shr 7) and 31
            return (v ushr r) or (v shl (32 - r))
        }
        var start = -1; var size = 0
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (u32(sh + 4) != 11) continue 
            val symOff = u32(sh + 16); val symSize = u32(sh + 20)
            val strOff = u32(shoff + u32(sh + 24) * shentsize + 16)
            var sym = symOff
            while (sym + 16 <= symOff + symSize) {
                val nameOff = strOff + u32(sym)
                if ((elf[sym + 12].toInt() and 0xf) == 2 && u32(sym + 8) > 0) {
                    var e = nameOff
                    while (e < elf.size && elf[e].toInt() != 0) e++
                    if (String(elf, nameOff, e - nameOff, Charsets.ISO_8859_1).startsWith("_ZN7android12AudioFlinger14loadHwModule_l")) {
                        start = fileOffset(u32(sym + 4) and 1.inv()) ?: return null
                        size = u32(sym + 8)
                    }
                }
                sym += 16
            }
        }
        if (start < 0) return null
        val end = minOf(start + size, elf.size - 6)
        var found = -1
        var p = start
        while (p + 6 <= end) {
            val h = u16(p)
            if ((h and 0xfbf0) == 0xf1b0) { 
                val h2 = u16(p + 2)
                if ((h2 and 0x8f00) == 0x0f00 && (u16(p + 4) and 0xfe00) == 0xd000) { 
                    val rn = h and 15
                    val imm = thumbExpandImm((((h shr 10) and 1) shl 11) or (((h2 shr 12) and 7) shl 8) or (h2 and 0xff))
                    var loadsVersion = false
                    var j = maxOf(start, p - 16)
                    while (j < p) {
                        val g = u16(j)
                        if ((g and 0xf800) == 0x6800 && ((g shr 6) and 31) == 1 && (g and 7) == rn) loadsVersion = true 
                        else if ((g and 0xfff0) == 0xf8d0 && j + 2 < p && (u16(j + 2) shr 12) == rn && (u16(j + 2) and 0xfff) == 4) loadsVersion = true
                        j += 2
                    }
                    if (loadsVersion && (imm shr 8) in 1..3 && (imm and 0xff) == 0) {
                        if (found >= 0 && found != imm) return null
                        found = imm
                    }
                }
            }
            p += 2
        }
        if (found > 0) found else null
    }.getOrNull()

    fun usesDirectTrackTail(elf: ByteArray): Boolean {
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() ||
            elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte() ||
            elf[4] != 1.toByte() || elf[5] != 1.toByte() ||
            elf[18] != 40.toByte() || elf[19] != 0.toByte()) return false
        if (!String(elf, Charsets.ISO_8859_1).contains("AudioStreamOutSink")) return false
        return (0..elf.size - timestampPrefix.size).any { start ->
            timestampPrefix.indices.all { elf[start + it] == timestampPrefix[it] }
        }
    }

    
    fun openOutputSlot(elf: ByteArray): Int? = runCatching {
        fun u16(o: Int) = (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[4] != 1.toByte()) return null
        val phoff = u32(0x1c); val phentsize = u16(0x2a); val phnum = u16(0x2c)
        val shoff = u32(0x20); val shentsize = u16(0x2e); val shnum = u16(0x30)
        fun fileOffset(vaddr: Int): Int? {
            for (i in 0 until phnum) {
                val ph = phoff + i * phentsize
                if (u32(ph) != 1) continue 
                val off = u32(ph + 4); val va = u32(ph + 8); val filesz = u32(ph + 16)
                if (vaddr >= va && vaddr < va + filesz) return vaddr - va + off
            }
            return null
        }
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (u32(sh + 4) != 11) continue 
            val symOff = u32(sh + 16); val symSize = u32(sh + 20)
            val strSh = shoff + u32(sh + 24) * shentsize
            val strOff = u32(strSh + 16)
            var sym = symOff
            while (sym + 16 <= symOff + symSize) {
                val nameOff = strOff + u32(sym)
                val value = u32(sym + 4); val size = u32(sym + 8)
                val isFunc = (elf[sym + 12].toInt() and 0xf) == 2
                if (isFunc && size > 0) {
                    var e = nameOff
                    while (e < elf.size && elf[e].toInt() != 0) e++
                    val name = String(elf, nameOff, e - nameOff, Charsets.ISO_8859_1)
                    if (name.startsWith("_ZN7android12AudioFlinger10openOutput")) {
                        val start = fileOffset(value and 1.inv()) ?: return null
                        var best = -1
                        var p = start
                        val end = minOf(start + size, elf.size - 4)
                        while (p < end) {
                            val h = u16(p)
                            if ((h and 0xff87) == 0x4780) { 
                                val rm = (h shr 3) and 15
                                var j = maxOf(start, p - 14)
                                while (j < p) {
                                    val g = u16(j)
                                    var imm = -1
                                    if ((g shr 11) == 0b01101 && (g and 7) == rm) imm = ((g shr 6) and 31) * 4
                                    else if ((g and 0xfff0) == 0xf8d0 && j + 2 < p && (u16(j + 2) shr 12) == rm) imm = u16(j + 2) and 0xfff
                                    
                                    
                                    if (imm in 100..160 && (best < 0 || imm < best)) best = imm
                                    j += 2
                                }
                            }
                            p += 2
                        }
                        return if (best > 0) best else null
                    }
                }
                sym += 16
            }
        }
        null
    }.getOrNull()

    
    fun hasFmVolumeAfterMasterVolume(elf: ByteArray): Boolean = runCatching {
        val fm = deviceSlots(elf, "_ZN7android12AudioFlinger11setFmVolumeEf")
        val mode = deviceSlots(elf, "_ZN7android12AudioFlinger7setModeE12audio_mode_t")
        val master = deviceSlots(elf, "_ZN7android12AudioFlinger15setMasterVolumeEf")
        84 in fm && 88 in mode && 84 !in mode && 76 in master
    }.getOrDefault(false)

    
    private fun deviceSlots(elf: ByteArray, prefix: String): Set<Int> {
        fun u16(o: Int) = (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[4] != 1.toByte()) return emptySet()
        val phoff = u32(0x1c); val phentsize = u16(0x2a); val phnum = u16(0x2c)
        val shoff = u32(0x20); val shentsize = u16(0x2e); val shnum = u16(0x30)
        fun fileOffset(vaddr: Int): Int? {
            for (i in 0 until phnum) {
                val ph = phoff + i * phentsize
                if (u32(ph) != 1) continue
                val off = u32(ph + 4); val va = u32(ph + 8); val filesz = u32(ph + 16)
                if (vaddr >= va && vaddr < va + filesz) return vaddr - va + off
            }
            return null
        }
        val out = HashSet<Int>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (u32(sh + 4) != 11) continue
            val symOff = u32(sh + 16); val symSize = u32(sh + 20)
            val strOff = u32(shoff + u32(sh + 24) * shentsize + 16)
            var sym = symOff
            while (sym + 16 <= symOff + symSize) {
                val nameOff = strOff + u32(sym)
                val value = u32(sym + 4); val size = u32(sym + 8)
                if ((elf[sym + 12].toInt() and 0xf) == 2 && size > 0 && (value and 1) == 1) {
                    var e = nameOff
                    while (e < elf.size && elf[e].toInt() != 0) e++
                    if (String(elf, nameOff, e - nameOff, Charsets.ISO_8859_1).startsWith(prefix)) {
                        val start = fileOffset(value and 1.inv()) ?: return out
                        var p = start
                        val end = minOf(start + size, elf.size - 4)
                        while (p < end) {
                            val g = u16(p)
                            val imm = when {
                                (g shr 11) == 0b01101 -> ((g shr 6) and 31) * 4                   
                                (g and 0xfff0) == 0xf8d0 -> u16(p + 2) and 0xfff                 
                                else -> -1
                            }
                            if (imm in 60..160) out.add(imm)
                            p += if ((g and 0xf800) >= 0xe800) 4 else 2
                        }
                    }
                }
                sym += 16
            }
        }
        return out
    }
}
