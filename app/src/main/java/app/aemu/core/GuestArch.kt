package app.aemu.core

import java.io.File
import java.io.RandomAccessFile


enum class GuestArch(
    val id: String,
    
    val qemuLib: String,
    
    val elfMachine: Int,
    
    val assetSuffix: String,
    
    val elfClass: Int = 1,
    
    val n64Lib32: String = "",
    
    val n64Lib64: String? = null,
    
    val n64MinApi: Int = -1,
) {
    ARM("arm", "", 40, "", n64Lib32 = "libqemu_arm.so"),
    
    
    X86("x86", "libqemu_kk_i386.so", 3, "-x86", n64Lib32 = "libqemu_i386.so", n64MinApi = 26),
    
    X86_64("x86_64", "libqemu_x86_64.so", 62, "-x86_64", elfClass = 2, n64Lib32 = "libqemu_i386.so", n64Lib64 = "libqemu_x86_64.so", n64MinApi = 21);

    
    val supportsNoSmc: Boolean get() = this == ARM

    
    val foreign: Boolean get() = this != ARM

    
    fun runsOnNative(api: Int): Boolean = this == ARM || (n64MinApi in 0..api)

    
    val n64Translators: List<String> get() = listOfNotNull(n64Lib32.ifEmpty { null }, n64Lib64)

    
    fun translator(engine: Engine, xperiaPlay: Boolean = false): String = when {
        this == ARM -> if (xperiaPlay) engine.qemuXperiaPlay else engine.qemu
        else -> qemuLib
    }

    companion object {
        const val EM_386 = 3
        const val EM_ARM = 40
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183

        fun byId(id: String?): GuestArch = entries.firstOrNull { it.id == id } ?: ARM

        
        fun elfInfo(f: File): Pair<Int, Int>? = runCatching {
            RandomAccessFile(f, "r").use { r ->
                val h = ByteArray(20)
                if (r.read(h) < 20) return@use null
                if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'L'.code.toByte() || h[3] != 'F'.code.toByte()) return@use null
                
                (h[4].toInt() and 0xff) to ((h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8))
            }
        }.getOrNull()

        
        private val PROBES64 = listOf(
            "system/bin/app_process64", "system/bin/linker64", "system/lib64/libc.so", "system/lib64/libcutils.so",
            "system/bin/surfaceflinger", "system/bin/servicemanager",
        )

        
        private val PROBES = listOf(
            "system/bin/app_process", "system/bin/app_process32", "system/bin/linker", "system/bin/sh",
            "system/bin/servicemanager", "system/bin/surfaceflinger", "system/bin/toolbox", "system/bin/toybox",
            "system/lib/libc.so", "system/lib/libcutils.so", "system/lib/libdvm.so", "system/lib/libart.so",
            "init", "sbin/adbd", "system/bin/adbd", "system/bin/vold",
        )

        
        fun machineOf(root: File): Pair<Int, Int>? {
            val top = runCatching { root.canonicalPath + File.separator }.getOrNull() ?: return null
            return (PROBES64 + PROBES).firstNotNullOfOrNull { rel ->
                val f = File(root, rel)
                
                
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@firstNotNullOfOrNull null
                if (runCatching { !f.canonicalPath.startsWith(top) }.getOrDefault(true)) return@firstNotNullOfOrNull null
                elfInfo(f)
            }
        }

        
        fun detect(root: File, abi: String): GuestArch {
            val m = machineOf(root)
            return when {
                m != null && m.first == 1 && m.second == EM_386 -> X86
                m != null && m.first == 2 && m.second == EM_X86_64 -> X86_64
                m != null -> ARM
                abi.startsWith("x86_64") -> X86_64
                abi == "x86" -> X86
                else -> ARM
            }
        }

        
        fun isUnsupported(root: File, abi: String): Boolean {
            val m = machineOf(root)
            if (m != null) return !(m.first == 1 && (m.second == EM_ARM || m.second == EM_386)) &&
                !(m.first == 2 && (m.second == EM_AARCH64 || m.second == EM_X86_64))
            return abi.startsWith("mips")
        }

        
        fun isOfArch(f: File, arch: GuestArch): Boolean = elfInfo(f)?.let {
            (it.first == 1 && it.second == arch.elfMachine) || (arch == X86_64 && it.first == 1 && it.second == EM_386)
        } == true
    }
}
