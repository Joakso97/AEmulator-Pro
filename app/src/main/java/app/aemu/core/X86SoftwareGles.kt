package app.aemu.core

import android.content.Context
import android.system.Os
import java.io.File


object X86SoftwareGles {
    fun asset(api: Int) = "engines/x86-gles/libGLES_android-$api.so"

    
    private val DEPS = listOf("libETC1.so", "libpixelflinger.so", "libui.so", "libhardware.so", "libcutils.so", "libutils.so")

    private fun present(f: File): Boolean = f.isFile && f.length() > 0   

    
    private fun installMissing(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        val m = GuestArch.machineOf(root) ?: return false
        if (m.first != 1 || m.second != GuestArch.X86.elfMachine) return false          
        val dirs = listOf("system/lib/egl", "vendor/lib/egl", "system/vendor/lib/egl").map { File(root, it) }
        if (dirs.any { present(File(it, "libGLES_android.so")) }) return false
        val asset = asset(api)
        if (!EngineAssets.exists(ctx, asset)) { log("GL: x86 API $api firmware has no libGLES_android.so and this build carries none for that API"); return false }
        val dir = dirs[0].apply { mkdirs() }
        val dst = File(dir, "libGLES_android.so")
        return runCatching {
            if (dst.exists() || runCatching { Os.lstat(dst.absolutePath) }.isSuccess) dst.delete()   
            EngineAssets.open(ctx, asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setReadable(true, false); dst.setWritable(true, true); dst.setExecutable(false, false)
            log("GL: x86 API $api firmware has no libGLES_android.so, installed the bundled software renderer (libGLES_android-$api.so) in system/lib/egl")
            val missing = DEPS.filter { n -> listOf("system/lib", "vendor/lib", "system/vendor/lib").none { present(File(root, "$it/$n")) } }
            if (missing.isNotEmpty()) log("GL: warning, the firmware lacks ${missing.joinToString()} that libGLES_android.so links to")
            true
        }.getOrElse { log("GL: could not install libGLES_android.so: ${it.message}"); false }
    }

    
    const val ALIAS = "libGLES_aemusw.so"

    
    private fun installAlias(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        if (api < 24) return false
        val dirs = listOf("vendor/lib/egl", "system/vendor/lib/egl", "system/lib/egl").map { File(root, it) }
        
        if (dirs.any { d -> d.listFiles().orEmpty().any { it.name.startsWith("libGLES_") && it.name != "libGLES_android.so" && present(it) } }) return false
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val own = File(dir, "libGLES_android.so")
        val dst = File(dir, ALIAS)
        return runCatching {
            if (dst.exists() || runCatching { Os.lstat(dst.absolutePath) }.isSuccess) dst.delete()
            val from = if (present(own)) { own.inputStream() } else {
                val a = asset(api)
                if (!EngineAssets.exists(ctx, a)) { log("GL: API $api libEGL needs a libGLES_<tag>.so and there is no software renderer to use"); return false }
                EngineAssets.open(ctx, a)
            }
            from.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setReadable(true, false); dst.setWritable(true, true); dst.setExecutable(false, false)
            log("GL: API $api libEGL skips libGLES_android.so when /vendor/lib/egl exists, software renderer also installed as $ALIAS")
            true
        }.getOrElse { log("GL: could not install $ALIAS: ${it.message}"); false }
    }

    
    private fun installPlain(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        if (api >= 24) return false            
        val dirs = listOf("vendor/lib/egl", "system/vendor/lib/egl", "system/lib/egl").map { File(root, it) }
        if (dirs.any { present(File(it, "libGLES.so")) }) return false
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val own = File(dir, "libGLES_android.so")
        val dst = File(dir, "libGLES.so")
        return runCatching {
            if (dst.exists() || runCatching { Os.lstat(dst.absolutePath) }.isSuccess) dst.delete()
            val from = if (present(own)) own.inputStream() else {
                val a = asset(api)
                if (!EngineAssets.exists(ctx, a)) { log("GL: API $api libEGL looks for egl/libGLES.so and there is no software renderer to use"); return false }
                EngineAssets.open(ctx, a)
            }
            from.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setReadable(true, false); dst.setWritable(true, true); dst.setExecutable(false, false)
            log("GL: API $api libEGL looks for egl/libGLES.so first, software renderer also installed under that name")
            true
        }.getOrElse { log("GL: could not install libGLES.so: ${it.message}"); false }
    }

    
    fun ensure(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        val m = GuestArch.machineOf(root) ?: return false
        if (m.first != 1 || m.second != GuestArch.X86.elfMachine) return false
        val a = installMissing(ctx, root, api, log)
        val b = installAlias(ctx, root, api, log)
        val c = installPlain(ctx, root, api, log)
        return a || b || c
    }
}
