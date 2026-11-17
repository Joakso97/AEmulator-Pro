
package app.aemu.core


internal object ManualMotion {
    fun gravity(turns: Int): FloatArray = when (Math.floorMod(turns, 4)) {
        0 -> floatArrayOf(0f, 9.80665f, 0f)
        1 -> floatArrayOf(9.80665f, 0f, 0f)
        2 -> floatArrayOf(0f, -9.80665f, 0f)
        else -> floatArrayOf(-9.80665f, 0f, 0f)
    }
}


object GyroLock {
    const val DISABLED = 0
    const val DEG_0 = 1
    const val DEG_180 = 2
    
    const val NO_GYRO = 3
    fun sanitize(mode: Int): Int = if (mode in DISABLED..NO_GYRO) mode else DISABLED
    fun removesGyro(mode: Int): Boolean = mode == NO_GYRO
    
    fun turns(mode: Int): Int? = when (mode) { DEG_0 -> 0; DEG_180 -> 2; else -> null }
}
