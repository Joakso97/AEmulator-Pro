package app.aemu.core

import android.os.Build
import java.io.File

object CpuInfo {
    
    val hasAArch32: Boolean by lazy {
        Build.SUPPORTED_32_BIT_ABIS.isNotEmpty() || runCatching {
            val f = File("/proc/cpuinfo")
            if (f.canRead()) {
                val t = f.readText()
                t.contains("Features") && (t.contains("swp") || t.contains("half") || t.contains("thumb"))
            } else false
        }.getOrDefault(false)
    }

    
    val is64Bit: Boolean by lazy {
        Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    }
}
