
package app.aemu.importer




data class BuildIdentity(val device: String?, val api: String?, val build: String?) {
    val isEmpty: Boolean get() = device == null && api == null && build == null
}


class PartitionPrompt(val partition: String, val index: Int, val total: Int, val options: List<String>)


fun interface PartitionChooser {
    fun choose(prompt: PartitionPrompt): Int

    companion object { const val CANCEL = -1 }
}

internal object PartitionChoice {
    enum class Kind { IMAGE, TAR, FOLDER }

    
    private val GROUP_ORDER = listOf("system", "vendor", "product", "system_ext", "odm", "oem", "boot", "recovery")

    fun groupRank(group: String): Int = GROUP_ORDER.indexOf(group).let { if (it < 0) GROUP_ORDER.size else it }

    private val SLOT = Regex("(?i)_[ab](?=\\.|$)")

    
    fun slotOf(base: String): Char? = SLOT.find(base)?.value?.get(1)?.lowercaseChar()

    
    fun withoutSlot(base: String): String = SLOT.replaceFirst(base, "")

    
    fun imageGroup(base: String, bareOk: Boolean, lz4Names: Boolean = false): String? {
        val stripped = PartitionNames.stripOdinMd5(base)
        
        
        return groupOf(withoutSlot(stripped), bareOk, lz4Names) ?: groupOf(asSlotA(stripped), bareOk, lz4Names)
    }

    private fun groupOf(name: String, bareOk: Boolean, lz4Names: Boolean): String? {
        val n = if (lz4Names) LZ4.replaceFirst(name, "") else name
        return when {
            PartitionNames.isSystem(n, bareOk) -> "system"
            PartitionNames.isVendor(n, bareOk) -> "vendor"
            PartitionNames.isOem(n, bareOk) -> "oem"
            PartitionNames.isBoot(n, bareOk) -> "boot"
            PartitionNames.isRecovery(n, bareOk) -> "recovery"
            else -> PartitionNames.extraMount(n, bareOk)?.substringAfterLast('/')
        }
    }

    
    fun asSlotA(base: String): String = SLOT.replaceFirst(base, "_a")

    
    fun importName(base: String, bareOk: Boolean, lz4Names: Boolean = false): String {
        if (slotOf(base) == null) return base
        val plain = withoutSlot(base)
        return if (groupOf(withoutSlot(PartitionNames.stripOdinMd5(base)), bareOk, lz4Names) != null) plain else asSlotA(base)
    }

    private val LZ4 = ImageCodecs.SUFFIX

    private val TAR = Regex("(?i)(.+?)\\.(tar|tar\\.md5|tar\\.gz|tgz)")

    
    fun tarGroup(base: String): String? {
        val stem = TAR.matchEntire(base)?.groupValues?.get(1) ?: return null
        return imageGroup("$stem.img", bareOk = false)
    }

    
    fun takesFiles(group: String): Boolean = group != "system" && group != "boot" && group != "recovery"

    
    fun tarPath(group: String, name: String): String {
        val mount = mountOf(group)
        val p = name.removePrefix("./").trim('/')
        if (p == group || p == mount) return mount
        val prefix = listOf("$group/", "$mount/").firstOrNull { p.startsWith(it) }
        return mount + "/" + (if (prefix != null) p.removePrefix(prefix) else p)
    }

    
    fun isSystemTarTopLevel(n: String, regularFile: Boolean, size: Long): Boolean {
        val p = n.removePrefix("./").trimStart('/')
        if (p.isEmpty() || p == "system" || p.startsWith("system/")) return false
        
        if (p == "init" || p.startsWith("init.") || p.startsWith("default.prop") || p.startsWith("dhd.") || p.matches(ROOT_RC)) return false
        if (ROOT_ONLY_DIRS.any { p == it || p.startsWith("$it/") }) return false
        if (regularFile && '/' !in p) {
            val base = p
            if (imageGroup(base, PartitionNames.bareOk(p, size), lz4Names = true) != null) return false
            if (base.endsWith(".sin", true) || base.endsWith(".ftf", true) || base.endsWith(".yaffs2.img", true)) return false
            if (ARCHIVE_EXT.matches(base)) return false
        }
        return true
    }

    private val ROOT_RC = Regex("^[^/]+\\.rc$")
    private val ROOT_ONLY_DIRS = listOf("data", "dev", "sbin", "oem", "proc", "sys")
    private val ARCHIVE_EXT = Regex("(?i).+\\.(tar|tar\\.md5|tar\\.gz|tgz|zip|7z|rar)")

    
    fun mountOf(group: String): String = if (group == "product" || group == "system_ext") "system/$group" else group

    

    
    val BUILD_PROP_PATHS = listOf("build.prop", "system/build.prop", "etc/build.prop")

    
    const val MAX_PROP_BYTES = 1 shl 20

    
    fun identity(group: String, p: Map<String, String>): BuildIdentity {
        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> p[k]?.trim()?.takeIf { it.isNotEmpty() } }
        val system = group == "system"

        val brand = first("ro.product.brand", "ro.product.$group.brand", "ro.product.manufacturer", "ro.product.$group.manufacturer")
            ?.replaceFirstChar { it.uppercase() }
        val model = first("ro.product.model", "ro.product.$group.model", "ro.product.device", "ro.product.$group.device")
        val device = when {
            model == null -> null
            brand == null || model.startsWith(brand, ignoreCase = true) -> model
            else -> "$brand $model"
        }

        val sdkKeys = if (system) arrayOf("ro.build.version.sdk", "ro.system.build.version.sdk")
            else arrayOf("ro.$group.build.version.sdk", "ro.build.version.sdk")
        val sdk = first(*sdkKeys)?.toIntOrNull()
        val release = first("ro.build.version.release", "ro.$group.build.version.release")
        val codename = first("ro.build.version.codename")
        val api = if (sdk == null && release == null) null
            else Analyzer.effectiveApi(sdk, codename, release).toString()
        
        val minor = if (system) first("ro.build.version.sdk_minor")?.toIntOrNull()?.takeIf { it > 0 } else null
        val apiText = if (api != null && minor != null) "$api.$minor" else api

        val buildKeys = if (system) arrayOf("ro.build.id", "ro.system.build.id", "ro.build.display.id")
            else arrayOf("ro.$group.build.id", "ro.build.id", "ro.build.display.id")
        return BuildIdentity(device, apiText, first(*buildKeys))
    }

    

    
    fun line(label: String, id: BuildIdentity?, apiText: (String) -> String, buildText: (String) -> String): String {
        if (id == null || id.isEmpty) return label
        val parts = listOfNotNull(id.device, id.api?.let(apiText), id.build?.let(buildText))
        return "$label: ${parts.joinToString(", ")}"
    }

    
    fun optionOrder(kind: Kind, slot: Char?): Int = kind.ordinal * 10 + when (slot) { 'a' -> 0; null -> 1; else -> 2 }

    
    fun isBlank(head: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (head[i].toInt() != 0) return false
        return true
    }
}
