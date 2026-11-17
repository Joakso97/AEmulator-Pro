package app.aemu.core


object GlassLocationPolicy {
    const val LOCATION_PACKAGE = "com.google.android.location"

    
    val LOCATION_COMPONENTS = listOf(
        "com.google.android.location.NetworkLocationService",
        "com.google.android.location.internal.server.NetworkLocationService",
    )

    fun isGlass(name: String, model: String, hasGlassApk: Boolean): Boolean =
        hasGlassApk || name.contains("glass", ignoreCase = true) || model.contains("glass", ignoreCase = true)

    
    fun keepEnabled(component: String, glass: Boolean): Boolean =
        glass && component.startsWith("$LOCATION_PACKAGE/")

    
    fun reenable(packagesXml: String): String? {
        val start = Regex("<package name=\"${Regex.escape(LOCATION_PACKAGE)}\"[^>]*>").find(packagesXml) ?: return null
        val end = packagesXml.indexOf("</package>", start.range.last)
        if (end < 0) return null
        val block = packagesXml.substring(start.range.last + 1, end)
        var changed = block
        for (c in LOCATION_COMPONENTS)
            changed = changed.replace(Regex("[ \\t]*<item name=\"${Regex.escape(c)}\"\\s*/>[ \\t]*\\r?\\n?"), "")
        changed = changed.replace(Regex("[ \\t]*<disabled-components>\\s*</disabled-components>[ \\t]*\\r?\\n?"), "")
        if (changed == block) return null
        return packagesXml.substring(0, start.range.last + 1) + changed + packagesXml.substring(end)
    }
}
