package app.aemu.core

import java.io.File


object GuestPreload {
    const val ASHMEM = "/system/lib/libashmemshim.so"
    const val SHIM = "/system/lib/libaemushim.so"
    
    const val RLIMIT = "/system/lib/libaemurlimit.so"
    
    const val XATTR = "/system/lib/libaemuxattr.so"

    
    const val XATTR_VENDOR = "/vendor/lib/libaemuxattr.so"

    
    fun xattrFor(program: String?): String =
        if (program != null && (program.startsWith("/vendor/") || program.startsWith("/odm/") ||
                program.startsWith("/system/vendor/"))) XATTR_VENDOR else XATTR

    
    const val GUARD = BinderGuard.LIB

    
    const val SHMSHIM = "/system/lib/libshmshim.so"

    
    fun isGlibcFirmware(root: File): Boolean =
        (File(root, "lib/ld-linux.so.3").exists() || File(root, "lib/ld-linux.so.2").exists()) &&
            !File(root, "system/bin/linker").exists()

    fun value(glibc: Boolean, xattr: Boolean = false, xattrPath: String = XATTR, guard: Boolean = false): String =
        if (glibc) "$ASHMEM:$SHMSHIM" else "$ASHMEM:" + (if (guard) "$GUARD:" else "") + "$SHIM:$RLIMIT" + (if (xattr) ":$xattrPath" else "")
}
