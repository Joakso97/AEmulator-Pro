
package app.aemu.core

import java.io.File

internal data class HostBatteryState(val percent: Int, val status: Int, val plugged: Int, val timeMinutes: Int = 0) {
    val ac get() = plugged and 1 != 0
    val usb get() = plugged and 2 != 0
    val wireless get() = plugged and 4 != 0
    val statusText get() = when (status) {
        2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not charging"; 5 -> "Full"; else -> "Unknown"
    }
    fun command(api: Int): String = buildList {
        add("dumpsys battery set ac ${if (ac || wireless && api < 19) 1 else 0}")
        add("dumpsys battery set usb ${if (usb) 1 else 0}")
        if (api >= 19) add("dumpsys battery set wireless ${if (wireless) 1 else 0}")
        add("dumpsys battery set status $status")
        add("dumpsys battery set level $percent")
    }.joinToString("; ")

    
    fun writeSysfs(root: File) {
        val base = root.canonicalFile
        val entries = mapOf("battery/capacity" to percent.toString(), "battery/status" to statusText,
            "ac/online" to if (ac) "1" else "0", "usb/online" to if (usb) "1" else "0",
            "wireless/type" to "Wireless", "wireless/online" to if (wireless) "1" else "0",
            
            "battery/charging_source" to if (ac || wireless) "2" else if (usb) "1" else "0",
            "battery/time_to_full_now" to timeMinutes.coerceAtLeast(0).toString())
        for ((path, value) in entries) {
            val file = File(base, "sys/class/power_supply/$path")
            check(file.canonicalFile == file.absoluteFile) { "Battery node is a symlink: $path" }
            file.parentFile!!.mkdirs()
            file.writeText("$value\n")
        }
        writeAndroidPower(base)
    }

    
    private fun writeAndroidPower(base: File) {
        val dir = File(base, "sys/android_power")
        if (!File(dir, "battery_level").isFile) return
        val charging = when {
            status == 5 && (ac || usb || wireless) -> "Maintaining"
            status == 2 || (status != 3 && status != 4 && (ac || usb || wireless)) -> "Charging"
            else -> "Discharging"
        }
        val entries = mapOf("battery_level" to percent.toString(), "battery_level_scale" to "100",
            "battery_level_raw" to percent.toString(), "charging_state" to charging)
        for ((name, value) in entries) {
            val file = File(dir, name)
            check(file.canonicalFile == file.absoluteFile) { "Battery node is a symlink: $name" }
            file.writeText("$value\n")
            file.setReadable(true, false); file.setWritable(true, false)
        }
    }
    companion object {
        fun from(level: Int, scale: Int, status: Int, plugged: Int): HostBatteryState? {
            if (level < 0 || scale <= 0) return null
            val percent = (level.toLong() * 100 / scale).coerceIn(0, 100).toInt()
            return HostBatteryState(percent, status.takeIf { it in 1..5 } ?: 1, plugged and 7)
        }
    }
}
