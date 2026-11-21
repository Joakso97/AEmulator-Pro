package app.aemu.importer


internal object PartitionNames {
    private val SYSTEM = Regex("(?i)system(\\.ext4)?\\.img(\\.ext4)?|system_image\\.img|system\\.raw\\.img|system\\.rfs|factoryfs(\\.img|\\.rfs)?|system_signed(\\.img)?|system_a\\.(img|bin)")
    private val BOOT = Regex("(?i)boot(\\.img|_signed(\\.img)?)|boot_a\\.(img|bin)")
    private val OEM = Regex("(?i)oem(\\.ext4)?\\.img(\\.ext4)?|oem_signed(\\.img)?|oem\\.rfs|oem_a\\.(img|bin)")
    private val VENDOR = Regex("(?i)vendor(\\.ext4)?\\.img|vendor_a\\.(img|bin)")
    
    private val EXTRA = Regex("(?i)(product|system_ext|odm|mi_ext|vgc|prism|optics|my_[a-z0-9]+)(_[ab])?(\\.ext4|\\.erofs|\\.f2fs)?\\.img(\\.ext4)?(\\.lz4)?")
    private val RECOVERY = Regex("(?i)recovery(\\.img|_signed(\\.img)?)(\\.lz4)?|recovery\\.(emmc|mmc)\\.win")

    
    
    private val SYSTEM_ALT = Regex("(?i)system(_a)?(\\.(ext4|f2fs|erofs|rfs|raw|simg|sparse))?\\.(img|bin)(\\.(ext4|f2fs|erofs|lz4|xz|gz|zst|zstd|bz2|br|sparse|simg))?")
    private val BOOT_ALT = Regex("(?i)boot(_a)?\\.(img|bin)(\\.(lz4|xz|gz|zst|zstd|bz2|br|sparse|simg))?")
    private val OEM_ALT = Regex("(?i)oem(_a)?(\\.(ext4|f2fs|erofs|rfs|raw))?\\.(img|bin)(\\.(ext4|f2fs|erofs|lz4|xz|gz|zst|zstd|bz2|br|sparse|simg))?")
    private val VENDOR_ALT = Regex("(?i)vendor(_a)?(\\.(ext4|f2fs|erofs|rfs|raw))?\\.(img|bin)(\\.(ext4|f2fs|erofs|lz4|xz|gz|zst|zstd|bz2|br|sparse|simg))?")
    private val EXTRA_ALT = Regex("(?i)(product|system_ext|odm|mi_ext|vgc|prism|optics|my_[a-z0-9]+)(_[ab])?(\\.(ext4|erofs|f2fs|rfs|raw|simg|sparse))?\\.(img|bin)(\\.(ext4|erofs|f2fs|lz4|xz|gz|zst|zstd|bz2|br|sparse|simg))?")
    private val RECOVERY_ALT = Regex("(?i)recovery(_a)?\\.(img|bin)(\\.(lz4|xz|gz|zst|zstd|bz2|br|sparse|simg))?")

    
    private val SYSTEM_BARE = Regex("(?i)system(_a)?")
    private val BOOT_BARE = Regex("(?i)boot(_a)?")
    private val OEM_BARE = Regex("(?i)oem(_a)?")
    private val VENDOR_BARE = Regex("(?i)vendor(_a)?")
    private val RECOVERY_BARE = Regex("(?i)recovery(_a)?")
    private val EXTRA_BARE = Regex("(?i)(product|system_ext|odm|mi_ext|vgc|prism|optics|my_[a-z0-9]+)(_[ab])?")

    
    private const val MIN_BARE_SIZE = 64L * 1024
    
    private val SYSTEM_TREE_DIRS = setOf("bin", "sbin", "xbin", "lib", "lib64", "etc", "app", "priv-app", "framework", "usr")

    
    fun bareOk(path: String, size: Long): Boolean {
        if (size in 0 until MIN_BARE_SIZE) return false 
        val dirs = path.replace('\\', '/').split('/').dropLast(1)
        return dirs.none { it.lowercase() in SYSTEM_TREE_DIRS }
    }

    
    private val NUMBERED = Regex("^[0-9]{1,3}[._-](?=[A-Za-z])")
    private fun n(base: String) = NUMBERED.replaceFirst(base, "")
    
    private fun d(base: String) = ImageCodecs.stripSuffix(n(base))

    
    fun isSystem(base: String, bareOk: Boolean = false) = d(base).let { SYSTEM.matches(it) || SYSTEM_ALT.containsMatchIn(it) || bareOk && SYSTEM_BARE.matches(it) }
    
    fun isOem(base: String, bareOk: Boolean = false) = d(base).let { OEM.matches(it) || OEM_ALT.containsMatchIn(it) || bareOk && OEM_BARE.matches(it) }
    
    fun isVendor(base: String, bareOk: Boolean = false) = d(base).let { VENDOR.matches(it) || VENDOR_ALT.containsMatchIn(it) || bareOk && VENDOR_BARE.matches(it) }
    fun isBoot(base: String, bareOk: Boolean = false) = n(base).let { BOOT.matches(it) || BOOT_ALT.containsMatchIn(it) || bareOk && BOOT_BARE.matches(it) }
    
    
    fun extraMount(base: String, bareOk: Boolean = false): String? {
        val name = d(base)
        val m = EXTRA.matchEntire(name) ?: EXTRA_ALT.find(name) ?: (if (bareOk) EXTRA_BARE.matchEntire(name) else null) ?: return null
        if (m.groupValues[2].equals("_b", true)) return null
        val p = m.groupValues[1].lowercase()
        return if (p == "product" || p == "system_ext") "system/$p" else p
    }
    
    fun isRecovery(base: String, bareOk: Boolean = false) = n(base).let { RECOVERY.matches(it) || RECOVERY_ALT.containsMatchIn(it) || bareOk && RECOVERY_BARE.matches(it) }

    private val IMAGE_TAIL = Regex("(?i)(\\.(img|bin|raw|disk|dd|ext4|erofs|f2fs|rfs|simg|sparse))+$")
    private val PARTITION_NAME = Regex("[a-z][a-z0-9_]*")

    fun anyMount(base: String, bareOk: Boolean = false): String? {
        var s = d(base).lowercase()
        val hadExtension = s.contains('.')
        s = IMAGE_TAIL.replace(s, "")
        if (s.endsWith("_a") || s.endsWith("_b")) s = s.dropLast(2)
        if (!bareOk && !hadExtension) return null
        if (!s.matches(PARTITION_NAME)) return null
        return if (s == "product" || s == "system_ext") "system/$s" else s
    }

    
    fun stripOdinMd5(base: String): String =
        if (base.endsWith(".md5", true) && !base.endsWith(".tar.md5", true) && base.length > 4) base.dropLast(4) else base

    
    fun isRawSystemDump(names: Collection<String>): Boolean {
        var buildProp = false
        var bin = false
        for (raw in names) {
            val n = raw.replace('\\', '/').trimStart('/')
            if (n.startsWith("__MACOSX/")) continue
            if (n == "build.prop") buildProp = true
            else if (n == "bin" || n.startsWith("bin/")) bin = true
            if (buildProp && bin) return true
        }
        return false
    }

    
    fun isDiskImageName(base: String): Boolean {
        val b = base.replace('\\', '/').substringAfterLast('/').lowercase()
        if (!b.contains('.')) return true
        return b.substringAfterLast('.') in setOf("img", "bin", "raw", "disk", "dd")
    }
}
