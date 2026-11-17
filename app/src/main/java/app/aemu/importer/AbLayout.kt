
package app.aemu.importer


internal object AbLayout {
    
    fun isSystemAsRoot(fs: Ext4Reader): Boolean = runCatching {
        fs.lookup("init")?.let { it.isFile || it.isLink } == true &&
            fs.lookup("system")?.isDir == true &&
            (fs.lookup("system/build.prop") != null || fs.lookup("system/framework") != null)
    }.getOrDefault(false)

    
    fun isSystemAsRoot(init: Int?, system: Int?, hasBuildProp: Boolean, hasFramework: Boolean): Boolean =
        (init == 1 || init == 2) && system == 3 && (hasBuildProp || hasFramework)

    
    fun isRecoveryRamdisk(rd: List<BootImage.CpioEntry>): Boolean {
        if (rd.none { it.name == "sbin/recovery" }) return false
        val rc = rd.firstOrNull { it.name == "init.rc" } ?: return true
        return !String(rc.data, Charsets.ISO_8859_1).contains("zygote")
    }

    
    fun skipRootFile(path: String): Boolean = path == "init" || path == "sbin/adbd" || path.startsWith("sbin/ueventd")

    
    fun skipRootEntry(path: String, isFile: Boolean, isLink: Boolean): Boolean =
        (isFile && skipRootFile(path)) || (isLink && path == "init")
}
