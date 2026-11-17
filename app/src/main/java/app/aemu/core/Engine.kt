package app.aemu.core



fun isPure32Tree(root: java.io.File): Boolean =
    
    !java.io.File(root, "system/lib64/libc.so").isFile && !java.io.File(root, "system/bin/app_process64").isFile


fun has32BitSide(root: java.io.File): Boolean =
    java.io.File(root, "system/bin/app_process32").isFile || java.io.File(root, "system/lib/libc.so").isFile ||
        java.io.File(root, "apex/com.android.runtime/lib/bionic/libc.so").isFile


fun isGuestElf32(root: java.io.File, program: String?): Boolean {
    if (program == null) return false
    var f = java.io.File(root, program.removePrefix("/"))
    repeat(8) {
        val p = f.toPath()
        if (!java.nio.file.Files.isSymbolicLink(p)) return runCatching {
            f.inputStream().use { i ->
                val h = ByteArray(5)
                i.read(h) == 5 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() &&
                    h[3] == 'F'.code.toByte() && h[4] == 1.toByte()
            }
        }.getOrDefault(false)
        val t = java.nio.file.Files.readSymbolicLink(p).toString()
        f = if (t.startsWith("/")) java.io.File(root, t.removePrefix("/")) else java.io.File(f.parentFile, t)
    }
    return false
}

enum class Engine(
    val id: String,
    val title: String,
    val qemu: String,
    
    val qemuXperiaPlay: String,
    val binderd: String,
    val glserverd: String,
    val runner: String,
    
    val guestGles: String,
) {
    GB(
        id = "gb",
        title = "Fallback for 2.x",
        qemu = "libqemu_gb.so",
        qemuXperiaPlay = "libqemu_gb_xperiaplay.so",
        binderd = "libbinderd_gb.so",
        glserverd = "libglserverd_gb.so",
        runner = "libdhdrun_gb.so",
        guestGles = "libGLES_dhd.so",
    ),
    KK(
        id = "kk",
        title = "Universal (2.2–7.x)",
        qemu = "libqemu_kk.so",
        qemuXperiaPlay = "libqemu_kk_xperiaplay.so",
        binderd = "libbinderd_kk.so",
        glserverd = "libglserverd_kk.so",
        runner = "libdhdrun_kk.so",
        guestGles = "libGLES.so",
    ),
    
    N64(
        id = "n64",
        title = "Native arm64",
        qemu = "",      
        qemuXperiaPlay = "",
        binderd = "",
        glserverd = "",
        runner = "",
        guestGles = "",
    );

    
    fun glserverdFor(gpu: String): String =
        if (this == KK && gpu.contains("mali")) "libglserverd_kk_mali.so" else glserverd

    companion object {
        
        fun forApi(api: Int): Engine = KK
        fun byId(id: String?): Engine = entries.firstOrNull { it.id == id } ?: KK
    }
}
