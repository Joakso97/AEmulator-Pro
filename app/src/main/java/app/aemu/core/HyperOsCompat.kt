package app.aemu.core

import java.io.File


internal object HyperOsCompat {
    
    val BLUR_PROPS: Map<String, String> = linkedMapOf(
        "persist.sys.background_blur_supported" to "false",
        "persist.sys.background_blur_status_default" to "false",
        "persist.sys.background_blur_setting_enabled" to "false",
        "persist.sys.background_blur_version" to "0",
        "persist.sys.add_blurenable" to "false",
        "persist.sys.advanced_visual_release" to "0",
        "ro.miui.backdrop_sampling_enabled" to "false",
    )

    
    val RUST_PROPS: Map<String, String> = linkedMapOf(
        "persist.sys.rust_runtime_active" to "1",
        "rust.runtime_active" to "1",
    )

    private val FRAMEWORK_DIRS = listOf("system_ext/framework", "system/system_ext/framework", "system/framework", "product/framework")

    
    fun isMiui(root: File): Boolean = FRAMEWORK_DIRS.any { File(root, "$it/miui-framework.jar").isFile }

    
    fun hasRustRuntime(root: File): Boolean {
        val rc = listOf("system_ext/etc/init", "system/system_ext/etc/init").any { File(root, "$it/init.hyos_spawner.rc").isFile }
        if (rc) return true
        return listOf("product/etc/permissions", "system/product/etc/permissions", "system_ext/etc/permissions", "system/system_ext/etc/permissions", "system/etc/permissions")
            .any { d -> File(root, d).listFiles().orEmpty().any { it.name.startsWith("rustruntime_cfg") && it.name.endsWith(".xml") } }
    }

    
    fun overrides(root: File, api: Int, n64: Boolean): Map<String, String> {
        if (!n64 || api < 29 || !isMiui(root)) return emptyMap()
        val m = LinkedHashMap<String, String>(BLUR_PROPS)
        if (hasRustRuntime(root)) m.putAll(RUST_PROPS)
        return m
    }
}
