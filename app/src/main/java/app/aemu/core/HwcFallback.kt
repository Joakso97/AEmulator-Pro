package app.aemu.core

import java.io.File


object HwcFallback {
    private const val MARKER = "hwc-standin-off"
    private const val NAME_STRING = "AEmulator framebuffer hwcomposer"

    private fun marker(paths: VmPaths) = File(paths.dir, MARKER)

    
    fun disabled(paths: VmPaths): Boolean = marker(paths).exists()

    fun disable(paths: VmPaths): Boolean = runCatching { marker(paths).writeText("1"); true }.getOrDefault(false)

    
    fun isStandIn(f: File): Boolean = f.isFile && runCatching {
        !java.nio.file.Files.isSymbolicLink(f.toPath()) && String(f.readBytes(), Charsets.ISO_8859_1).contains(NAME_STRING)
    }.getOrDefault(false)

    
    fun installed(root: File): Boolean = isStandIn(File(root, "system/lib/hw/hwcomposer.default.so"))

    
    fun isCrashExit(code: Int): Boolean = code == 139 || code == 134

    
    fun remove(root: File): Boolean {
        var removed = false
        File(root, "system/lib/hw/hwcomposer.default.so").takeIf { isStandIn(it) }?.let { removed = it.delete() || removed }
        File(root, "system/.aemu-parked/system#lib#hw#hwcomposer.default.so").takeIf { isStandIn(it) }?.let { removed = it.delete() || removed }
        return removed
    }
}
