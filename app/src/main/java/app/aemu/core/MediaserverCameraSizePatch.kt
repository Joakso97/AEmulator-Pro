
package app.aemu.core

import java.security.MessageDigest


internal object MediaserverCameraSizePatch {
    const val PATCHED = 1
    const val ALREADY = 0
    
    const val ABSENT = -1

    const val OFFSET = 0xc2e        
    const val STOCK = 0x58          
    const val FIXED = 0x60          
    private const val MOVS_R0_HIGH = 0x20

    
    const val MEDIASERVER_SHA256 = "d0bd50a2254fe32ea10814992fe0b6d03862d71db796c552a6405364ebb42bca"
    
    const val CAMERASERVICE_SHA256 = "f614effdc091118a8f6791a9f853dbd6395c73574acc3cbcec11f4ba06b99e54"

    
    fun apply(
        mediaserver: ByteArray,
        cameraLib: ByteArray,
        mediaserverSha: String = MEDIASERVER_SHA256,
        cameraLibSha: String = CAMERASERVICE_SHA256,
    ): Int {
        if (mediaserver.size <= OFFSET + 1) return ABSENT
        if (!sha256(cameraLib).equals(cameraLibSha, ignoreCase = true)) return ABSENT
        val cur = mediaserver[OFFSET].toInt() and 0xff
        if ((mediaserver[OFFSET + 1].toInt() and 0xff) != MOVS_R0_HIGH || (cur != STOCK && cur != FIXED)) return ABSENT
        val stock = mediaserver.copyOf().also { it[OFFSET] = STOCK.toByte() }
        if (!sha256(stock).equals(mediaserverSha, ignoreCase = true)) return ABSENT
        if (cur == FIXED) return ALREADY
        mediaserver[OFFSET] = FIXED.toByte()
        return PATCHED
    }

    private fun sha256(d: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
