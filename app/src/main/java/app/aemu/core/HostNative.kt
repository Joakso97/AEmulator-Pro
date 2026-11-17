package app.aemu.core


object HostNative {
    private val ok: Boolean = try { System.loadLibrary("aemuhost"); true } catch (_: Throwable) { false }

    @JvmStatic private external fun relaxFdsan()
    @JvmStatic private external fun limitStack()

    
    
    fun limitStackSafe() { if (ok) try { limitStack() } catch (_: Throwable) {} }

    fun relaxFdsanSafe() { if (ok) try { relaxFdsan() } catch (_: Throwable) {} }
}
