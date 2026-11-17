package app.aemu.core

import java.io.File


object ComposerLibs {
    private val REACH = listOf("odm/lib", "vendor/lib")

    
    fun bringable(name: String) = name.startsWith("android.hardware.") || name.startsWith("android.hidl.") ||
        name.startsWith("android.frameworks.") || name.startsWith("android.system.")

    class Result(val impl: File?, val copied: List<String>, val missing: List<String>) {
        val ok get() = impl != null && missing.isEmpty()
    }

    fun complete(root: File, log: (String) -> Unit): Result {
        val impl = listOf("vendor/lib/hw", "system/vendor/lib/hw", "vendor/lib", "system/vendor/lib")
            .map { File(root, "$it/android.hardware.graphics.composer@2.1-impl.so") }.firstOrNull { it.isFile }
        if (impl == null) { log("composer: no 32-bit composer@2.1-impl.so in the tree"); return Result(null, emptyList(), emptyList()) }
        val copied = ArrayList<String>()
        
        for (round in 0 until 6) {
            val c = KeymasterLibs.closure(root, impl)
            var changed = false
            for (f in c.files.drop(1)) {
                if (!bringable(f.name) || REACH.any { File(root, "$it/${f.name}").isFile }) continue
                val dst = File(root, "vendor/lib/${f.name}")
                runCatching {
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    copied += f.name; changed = true
                    log("composer: ${f.name} brought into /vendor/lib from ${f.parentFile?.relativeTo(root)?.path} (not visible to a vendor program there)")
                }.onFailure { log("composer: ${f.name} could not be put into /vendor/lib: ${it.message}") }
            }
            if (!changed) break
        }
        val c = KeymasterLibs.closure(root, impl)
        val missing = c.missing
        if (missing.isEmpty()) log("composer: every library of ${impl.name} found (${c.files.size - 1})")
        else log("composer: NOT FOUND anywhere in the tree (system/lib, VNDK dirs, vendor/lib): " + missing.joinToString(", ") +
            (if (missing.any { it.startsWith("android.hardware.graphics.composer@2.1.so") })
                " - the 32-bit composer@2.1 service cannot start without its interface library; it has to be added to engines/<kernel>/arm32 " +
                "(vendor#lib#android.hardware.graphics.composer@2.1.so, built from AOSP hardware/interfaces/graphics/composer/2.1)" else ""))
        
        return Result(impl, copied, missing)
    }
}
