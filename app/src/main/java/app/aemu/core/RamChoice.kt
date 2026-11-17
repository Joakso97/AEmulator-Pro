
package app.aemu.core


object RamChoice {
    const val AUTO = 0
    const val HOST = -1

    
    val PRESETS_MB = listOf(256, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288)

    
    const val CUSTOM_MIN_MB = 64

    
    fun hostMb(): Int = runCatching {
        java.io.File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemTotal:") } }
            .split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrDefault(8192L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    
    fun resolve(chosen: Int): Int = if (chosen == HOST) hostMb() else chosen
}
