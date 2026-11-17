package app.aemu.core


object HostInfo {
    
    fun gpu(): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, "ro.hardware.egl") as String).lowercase()
    }.getOrDefault("")
}
