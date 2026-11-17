
package app.aemu.core


enum class AlertType(val gsmId: Int, val cdmaCategory: Int, val severity: Int, val urgency: Int, val certainty: Int) {
    
    PRESIDENTIAL(0x1112, 0x1000, 0xF, 0xF, 0xF),
    
    EXTREME(0x1113, 0x1001, 0, 0, 0),
    
    SEVERE(0x1117, 0x1002, 1, 0, 0),
    
    AMBER(0x111B, 0x1003, 0xF, 0xF, 0xF),
    
    TEST(0x111C, 0x1004, 0xF, 0xF, 0xF),
}


object WirelessAlert {
    const val MAX_PAGES = 15
    const val GSM7_CHARS = 93
    const val UCS2_CHARS = 41
    
    const val MAX_TEXT = 600
    const val PDU_SIZE = 88
    private const val HEADER = 6
    
    private const val GS_CELL_IMMEDIATE = 0
    
    private const val DCS_GSM7_EN = 0x01
    
    private const val DCS_UCS2 = 0x48
    private const val CR = '\r'

    
    private val GSM7_SAFE: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + " \n\r!\"#%&'()*+,-./:;<=>?".toList()).toSet()

    fun isGsm7(text: String): Boolean = text.all { it in GSM7_SAFE }

    
    fun gsmPages(type: AlertType, text: String, messageCode: Int): List<ByteArray> {
        val body = text.ifEmpty { " " }
        val gsm = isGsm7(body)
        val chunks = (if (gsm) body.chunked(GSM7_CHARS) else ucs2Chunks(body)).take(MAX_PAGES)
        val serial = (GS_CELL_IMMEDIATE shl 14) or ((messageCode and 0x3ff) shl 4)   
        return chunks.mapIndexed { i, chunk ->
            val pdu = ByteArray(PDU_SIZE)
            pdu[0] = (serial shr 8).toByte()
            pdu[1] = serial.toByte()
            pdu[2] = (type.gsmId shr 8).toByte()
            pdu[3] = type.gsmId.toByte()
            pdu[4] = (if (gsm) DCS_GSM7_EN else DCS_UCS2).toByte()
            pdu[5] = (((i + 1) shl 4) or chunks.size).toByte()
            val content = if (gsm) gsm7Pack(chunk.padEnd(GSM7_CHARS, CR))
                else chunk.padEnd(UCS2_CHARS, CR).toByteArray(Charsets.UTF_16BE)
            content.copyInto(pdu, HEADER)
            pdu
        }
    }

    
    private fun ucs2Chunks(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val n = if (Character.isHighSurrogate(s[i]) && i + 1 < s.length) 2 else 1
            if (cur.length + n > UCS2_CHARS) { out.add(cur.toString()); cur.setLength(0) }
            cur.append(s, i, i + n)
            i += n
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun gsm7Pack(s: String): ByteArray {
        val out = ByteArray((s.length * 7 + 7) / 8)
        s.forEachIndexed { i, c ->
            val bit = i * 7
            val v = c.code and 0x7f
            val idx = bit / 8
            val sh = bit % 8
            out[idx] = (out[idx].toInt() or (v shl sh)).toByte()
            if (sh > 1) out[idx + 1] = (out[idx + 1].toInt() or (v shr (8 - sh))).toByte()
        }
        return out
    }
}
