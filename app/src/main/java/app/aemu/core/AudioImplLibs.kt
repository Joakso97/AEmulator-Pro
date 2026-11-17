package app.aemu.core

import java.io.File


object AudioImplLibs {
    const val IMPL_PLAIN = "android.hardware.audio@2.0-impl.so"
    const val IMPL_MEDIATEK = "android.hardware.audio@2.0-impl-mediatek.so"

    
    const val RC_PLAIN = "android.hardware.audio@2.0-service.rc"
    const val RC_MEDIATEK = "android.hardware.audio@2.0-service-mediatek.rc"

    
    val HOST_IMPLS = listOf(
        IMPL_PLAIN,
        IMPL_MEDIATEK,
        "android.hardware.audio.effect@2.0-impl.so",
        "android.hardware.soundtrigger@2.0-impl.so",
    )

    
    private val REACH = listOf("vendor/lib", "odm/lib")

    
    private val HW_DIRS = listOf("vendor/lib/hw", "odm/lib/hw", "system/vendor/lib/hw", "system/vendor/lib")

    class Result(
        
        val copied: List<String>,
        
        val alreadyReachable: List<String>,
        
        val absent: List<String>,
        
        val unresolved: List<String>,
    )

    fun reachable(root: File, name: String): File? = REACH.map { File(root, "$it/$name") }.firstOrNull { it.isFile }

    private fun anywhere(root: File, name: String): File? = HW_DIRS.map { File(root, "$it/$name") }.firstOrNull { it.isFile }

    
    fun expose(root: File, log: (String) -> Unit): Result {
        val copied = ArrayList<String>()
        val already = ArrayList<String>()
        val absent = ArrayList<String>()
        val unresolved = ArrayList<String>()
        for (name in HOST_IMPLS) {
            val hit = reachable(root, name)
            if (hit != null) { already += name; continue }
            val src = anywhere(root, name)
            if (src == null) { absent += name; continue }
            val dst = File(root, "vendor/lib/$name")
            val ok = runCatching {
                dst.parentFile?.mkdirs()
                if (dst.exists() || java.nio.file.Files.isSymbolicLink(dst.toPath())) dst.delete()
                src.copyTo(dst, overwrite = true)
                dst.setReadable(true, false); dst.setExecutable(true, false)
                true
            }.getOrElse { log("audio: $name could not be put into /vendor/lib: ${it.message}"); false }
            if (ok) copied += "${src.relativeTo(root).path}"
        }
        
        val first = reachable(root, IMPL_MEDIATEK) ?: reachable(root, IMPL_PLAIN)
        if (first != null) runCatching { unresolved += KeymasterLibs.closure(root, first).missing }
            .onFailure { log("audio: library closure of ${first.name} could not be read: ${it.message}") }
        if (copied.isNotEmpty()) log("audio: HAL implementation reachable by bare name, copied into /vendor/lib: ${copied.joinToString()} (the engine's audio host dlopen()s it without a directory)")
        if (HOST_IMPLS.take(2).all { it in absent })
            log("audio: the tree has no android.hardware.audio@2.0 implementation (neither -impl.so nor -impl-mediatek.so), the audio host cannot start")
        if (unresolved.isNotEmpty()) log("audio: ${first?.name} names libraries no directory of the tree has: ${unresolved.joinToString()}")
        return Result(copied, already, absent, unresolved)
    }

    
    fun rcToSetAside(
        hasPlainImpl: Boolean, hasMediatekImpl: Boolean,
        hasPlainRc: Boolean, hasMediatekRc: Boolean, hasMediatekService: Boolean,
    ): String? =
        if (hasMediatekImpl && !hasPlainImpl && hasPlainRc && hasMediatekRc && hasMediatekService) RC_PLAIN else null

    
    fun rcsToPark(root: File): List<File> {
        val rcDirs = listOf("vendor/etc/init", "system/vendor/etc/init", "odm/etc/init")
        fun rcs(name: String) = rcDirs.map { File(root, "$it/$name") }.filter { it.isFile }
        val binDirs = listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")
        val mtkService = binDirs.any { File(root, "$it/android.hardware.audio@2.0-service-mediatek").isFile }
        val aside = rcToSetAside(
            hasPlainImpl = (anywhere(root, IMPL_PLAIN) ?: reachable(root, IMPL_PLAIN)) != null,
            hasMediatekImpl = (anywhere(root, IMPL_MEDIATEK) ?: reachable(root, IMPL_MEDIATEK)) != null,
            hasPlainRc = rcs(RC_PLAIN).isNotEmpty(), hasMediatekRc = rcs(RC_MEDIATEK).isNotEmpty(),
            hasMediatekService = mtkService,
        )
        return if (aside == RC_PLAIN) rcs(RC_PLAIN) else emptyList()
    }
}
