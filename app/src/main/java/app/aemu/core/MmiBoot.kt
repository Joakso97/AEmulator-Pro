
package app.aemu.core

import java.io.File


internal object MmiBoot {
    
    val properties = mapOf("ro.bootmode" to "ffbm-01", "ro.boot.bootmode" to "ffbm-01", "ro.factorytest" to "1")

    
    fun frameworkless(root: File): Boolean =
        !File(root, "system/framework/framework.jar").isFile && !File(root, "system/framework/services.jar").isFile

    private val NAMES = listOf("fastmmi", "mmi", "ftm", "ftm_mode")

    private fun rcFiles(root: File): List<File> =
        listOf(root, File(root, "system/etc/init")).flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList()
        }

    private fun exists(root: File, path: String) = path.startsWith("/") && File(root, path.trimStart('/')).isFile

    
    fun service(root: File): GuestService? {
        val rc = InitPlan.parse(rcFiles(root))
        val found = NAMES.firstNotNullOfOrNull { rc.services[it] }
            ?: rc.services.values.firstOrNull { it.argv.firstOrNull()?.substringAfterLast('/') == "mmi" }
        if (found != null && found.argv.isNotEmpty() && exists(root, found.argv.first()))
            return GuestService("fastmmi", found.argv, found.sockets.toMap())
        
        for (p in listOf("/system/bin/mmi", "/sbin/mmi", "/system/bin/fastmmi"))
            if (exists(root, p)) return GuestService("fastmmi", listOf(p))
        return null
    }

    
    fun controllable(root: File): Map<String, GuestService> {
        val rc = InitPlan.parse(rcFiles(root))
        val dirs = listOf("/system/bin/", "/system/xbin/", "/vendor/bin/", "/sbin/")
        return rc.services.values.filter { s ->
            s.argv.isNotEmpty() && dirs.any { s.argv.first().startsWith(it) } && exists(root, s.argv.first()) &&
                s.name !in setOf("zygote", "surfaceflinger", "vold", "ueventd", "adbd", "debuggerd", "logd", "healthd")
        }.associate { it.name to GuestService(it.name, it.argv, it.sockets.toMap(), optional = true) }
    }
}
