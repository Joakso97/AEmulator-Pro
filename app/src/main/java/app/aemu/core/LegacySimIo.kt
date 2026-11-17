
package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder


object LegacySimIo {
    const val ICCID = "89000000000000000004"
    data class Reply(val sw1: Int, val sw2: Int, val hex: String? = null)
    private val notFound = Reply(0x6a, 0x82)
    private val badArguments = Reply(0x6a, 0x86)

    
    class Files(val iccid: ByteArray, val spn: ByteArray? = null, val msisdn: ByteArray? = null, val ad: ByteArray? = null)
    private val DEFAULT = Files(SimCards.efIccid(ICCID))

    
    private const val STATUS_DF = "00000000" + "7F20" + "02" + "0000000000" + "09" + "00" + "00" + "0A" + "03" + "00" + "838A838A"
    private const val EF_ICCID = 0x2fe2
    private const val EF_SPN = 0x6f46
    private const val EF_MSISDN = 0x6f40
    private const val EF_AD = 0x6fad

    fun handle(args: ByteBuffer, files: Files = DEFAULT): Reply = try {
        val b = args.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val file = b.int
        val length = b.int
        val path = if (length == -1) null else {
            require(length in 0..128)
            val byteCount = (length + 1) * 2
            val padded = (byteCount + 3) and -4
            require(b.remaining() >= padded + 12)
            val start = b.position()
            val value = String(CharArray(length) { b.char })
            require(b.char == '\u0000')
            b.position(start + padded)
            value
        }
        val p1 = b.int; val p2 = b.int; val p3 = b.int
        
        val data: ByteArray? = when (file) {
            EF_ICCID -> if (path == null || path.equals("3F00", true)) files.iccid else null
            EF_SPN -> files.spn
            EF_AD -> files.ad
            EF_MSISDN -> files.msisdn
            else -> null
        }
        val record = file == EF_MSISDN
        if (command == 0xf2) Reply(0x90, 0, STATUS_DF)   
        else if (data == null) notFound
        else if (p1 !in 0..255 || p2 !in 0..255 || p3 !in 0..255) badArguments
        else when (command) {
            0xc0 -> { 
                if (p1 != 0 || p2 != 0 || p3 != 15) badArguments
                else Reply(0x90, 0, "0000%04X%04X04000000000102%02X%02X".format(data.size, file, if (record) 1 else 0, if (record) data.size else 0))
            }
            0xb0 -> { 
                val offset = (p1 shl 8) or p2
                if (record || p3 == 0 || offset + p3 > data.size) badArguments
                else Reply(0x90, 0, hex(data, offset, p3))
            }
            0xb2 -> { 
                if (!record || p2 != 4 || p1 != 1 || p3 != data.size) badArguments
                else Reply(0x90, 0, hex(data, 0, p3))
            }
            else -> Reply(0x6d, 0) 
        }
    } catch (_: Exception) { badArguments }

    private fun hex(b: ByteArray, off: Int, n: Int) = (off until off + n).joinToString("") { "%02X".format(b[it].toInt() and 0xff) }
}
