package app.aemu.core


object SensorHalApi {
    
    fun crashesWithHostSensors(api: Int, skin: String, brand: String, model: String) =
        api >= 24 && skin.equals("Wear", true) && brand.equals("generic", true) && model.contains("sdk", true)

    
    fun sensorsOff(override: Boolean?, api: Int, skin: String, brand: String, model: String) =
        override ?: crashesWithHostSensors(api, skin, brand, model)

    private fun has(d: ByteArray, s: String): Boolean {
        val n = byteArrayOf(0) + s.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        outer@ for (i in 0..d.size - n.size) {
            for (k in n.indices) if (d[i + k] != n[k]) continue@outer
            return true
        }
        return false
    }

    
    fun controlDataOnly(lib: ByteArray): Boolean =
        lib.size > 4 && lib[0] == 0x7f.toByte() && lib[1] == 'E'.code.toByte() &&
            has(lib, "control") && has(lib, "data") && !has(lib, "poll")
}
