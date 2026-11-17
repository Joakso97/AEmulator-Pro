package app.aemu.core


object GlHost {
    private val ok: Boolean = try { System.loadLibrary("aemugl"); true } catch (_: Throwable) { false }

    
    fun start(socket: String): String = if (ok) runCatching { nativeStart(socket) }.getOrElse { it.toString() } else "libaemugl.so does not load"
    fun stop() { if (ok) runCatching { nativeStop() } }

    
    fun surface(s: android.view.Surface?) { if (ok) runCatching { nativeSurface(s) } }
    
    fun presented(): Long = if (ok) runCatching { nativePresented() }.getOrDefault(0L) else 0L

    @JvmStatic private external fun nativeStart(socket: String): String
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeSurface(s: android.view.Surface?)
    @JvmStatic private external fun nativePresented(): Long
}
