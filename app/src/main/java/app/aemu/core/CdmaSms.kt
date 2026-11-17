
package app.aemu.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone


internal object CdmaSms {
    
    private const val TELESERVICE_WMT = 0x1002
    private const val SUBPARAM_MESSAGE_ID = 0
    private const val SUBPARAM_USER_DATA = 1
    private const val SUBPARAM_TIME_STAMP = 3
    private const val MSG_TYPE_DELIVER = 1
    private const val ENC_7BIT_ASCII = 2
    private const val ENC_UNICODE_16 = 4
    private const val ENC_OCTET = 0
    
    private const val TELESERVICE_NONE = 0
    private const val SUBPARAM_PRIORITY = 8
    private const val SUBPARAM_LANGUAGE = 0x0d
    private const val PRIORITY_EMERGENCY = 3
    private const val LANGUAGE_ENGLISH = 1
    private const val CMAS_PROTOCOL_VERSION = 0
    private const val CMAS_RECORD_TEXT = 0
    private const val CMAS_RECORD_INFO = 1
    
    const val CMAS_MAX_ASCII = 200
    const val CMAS_MAX_UNICODE = 90

    fun deliverParcel(from: String, body: String, messageId: Int, now: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

        val num = from.trim()
        
        val digits = num.filter { it.isDigit() || it == '*' || it == '#' }.take(20).ifEmpty { "0" }
        int(TELESERVICE_WMT)
        int(0)                              
        int(0)                              
        int(0)                              
        int(0)                              
        int(if (num.startsWith("+")) 1 else 0)   
        int(1)                              
        int(digits.length)
        for (c in digits) int(dtmf(c))
        int(0); int(0); int(0)              
        val bearer = bearerData(body, messageId, now)
        int(bearer.size)
        for (b in bearer) int(b.toInt() and 0xff)
        return out.toByteArray()
    }

    private fun dtmf(c: Char): Int = when (c) { in '1'..'9' -> c - '0'; '0' -> 10; '*' -> 11; '#' -> 12; else -> 10 }

    fun bearerData(body: String, messageId: Int, now: Calendar): ByteArray {
        val b = ByteArrayOutputStream()
        
        b.write(SUBPARAM_MESSAGE_ID); b.write(3)
        b.write(Bits().put(MSG_TYPE_DELIVER, 4).put(messageId and 0xffff, 16).put(0, 1).put(0, 3).bytes())
        
        val text = body.ifEmpty { " " }
        val ascii = text.all { it.code in 0x20..0x7e || it == '\n' }
        val chars = text.take(if (ascii) 160 else 70)
        val ud = Bits().put(if (ascii) ENC_7BIT_ASCII else ENC_UNICODE_16, 5).put(chars.length, 8)
        for (c in chars) if (ascii) ud.put(c.code, 7) else ud.put(c.code, 16)
        val udBytes = ud.bytes()
        b.write(SUBPARAM_USER_DATA); b.write(udBytes.size); b.write(udBytes)
        b.write(timeStamp(now))
        return b.toByteArray()
    }

    
    private fun timeStamp(now: Calendar): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(SUBPARAM_TIME_STAMP); b.write(6)
        for (v in intArrayOf(now.get(Calendar.YEAR) % 100, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))) b.write(((v / 10) shl 4) or (v % 10))
        return b.toByteArray()
    }

    
    fun cmasParcel(type: AlertType, body: String, messageId: Int, now: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

        int(TELESERVICE_NONE)               
        int(1)                              
        int(type.cdmaCategory)
        int(0); int(0); int(0); int(1)      
        int(0)                              
        int(0); int(0); int(0)              
        val bearer = cmasBearerData(type, body, messageId, now)
        int(bearer.size)
        for (b in bearer) int(b.toInt() and 0xff)
        return out.toByteArray()
    }

    
    fun cmasBearerData(type: AlertType, body: String, messageId: Int, now: Calendar): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(SUBPARAM_MESSAGE_ID); b.write(3)
        b.write(Bits().put(MSG_TYPE_DELIVER, 4).put(messageId and 0xffff, 16).put(0, 1).put(0, 3).bytes())

        val text = body.ifEmpty { " " }
        val ascii = text.all { it.code in 0x20..0x7e || it == '\n' }
        var chars = text.take(if (ascii) CMAS_MAX_ASCII else CMAS_MAX_UNICODE)
        if (!ascii && chars.isNotEmpty() && Character.isHighSurrogate(chars.last())) chars = chars.dropLast(1)
        val charBits = if (ascii) 7 else 16
        val recordBits = 5 + chars.length * charBits               
        val recordLen = (recordBits + 7) / 8                       
        val cmae = Bits().put(CMAS_PROTOCOL_VERSION, 8)
        cmae.put(CMAS_RECORD_TEXT, 8).put(recordLen, 8).put(if (ascii) ENC_7BIT_ASCII else ENC_UNICODE_16, 5)
        for (c in chars) cmae.put(c.code, charBits)
        cmae.put(0, recordLen * 8 - recordBits)
        cmae.put(CMAS_RECORD_INFO, 8).put(4, 8)                    
        cmae.put(0xff, 8).put(0xff, 8).put(type.severity, 4).put(type.urgency, 4).put(type.certainty, 4).put(0, 4)
        val cmaeBytes = cmae.bytes()
        val ud = Bits().put(ENC_OCTET, 5).put(cmaeBytes.size, 8)
        for (x in cmaeBytes) ud.put(x.toInt() and 0xff, 8)
        val udBytes = ud.bytes()
        b.write(SUBPARAM_USER_DATA); b.write(udBytes.size); b.write(udBytes)

        b.write(timeStamp(now))
        b.write(SUBPARAM_PRIORITY); b.write(1); b.write(Bits().put(PRIORITY_EMERGENCY, 2).put(0, 6).bytes())
        b.write(SUBPARAM_LANGUAGE); b.write(1); b.write(LANGUAGE_ENGLISH)
        return b.toByteArray()
    }

    
    private class Bits {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun put(value: Int, bits: Int): Bits {
            for (i in bits - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                if (++n == 8) { out.write(cur); cur = 0; n = 0 }
            }
            return this
        }
        fun bytes(): ByteArray { if (n > 0) { out.write(cur shl (8 - n)); cur = 0; n = 0 }; return out.toByteArray() }
    }
}
