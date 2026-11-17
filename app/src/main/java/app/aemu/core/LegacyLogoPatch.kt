package app.aemu.core

import java.io.File
import java.nio.file.Files


internal object LegacyLogoPatch {
    private const val ANY = -1
    private val SIG = intArrayOf(
        0x00, 0x28, 0x04, 0x46, 0x03, 0xda, ANY, ANY, ANY, ANY,
        0x64, 0x25, 0x08, 0xe0, 0x44, 0xf6, 0x3a, 0x31, 0x01, 0x22, ANY, ANY, ANY, ANY,
        0x05, 0x46, 0x20, 0x46,
    )
    private const val OPEN_FAIL_AT = 10 
    private const val IOCTL_RESULT_AT = 24 

    
    fun patch(data: ByteArray): Int {
        var n = 0
        var i = 0
        while (i <= data.size - SIG.size) {
            if (matches(data, i)) {
                data[i + OPEN_FAIL_AT] = 0x00
                data[i + IOCTL_RESULT_AT] = 0x00; data[i + IOCTL_RESULT_AT + 1] = 0x25
                n++
                i += SIG.size
            } else i++
        }
        return n
    }

    private fun matches(d: ByteArray, at: Int): Boolean {
        for (k in SIG.indices) if (SIG[k] != ANY && (d[at + k].toInt() and 0xff) != SIG[k]) return false
        return true
    }

    fun apply(root: File, log: (String) -> Unit) {
        runCatching {
            val f = File(root, "system/bin/${BootMediaServices.LEGACY_LOGO}")
            if (!f.isFile || Files.isSymbolicLink(f.toPath())) return
            if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) return
            val data = f.readBytes()
            if (patch(data) > 0) {
                f.setWritable(true)
                f.writeBytes(data)
                log("boot logo: playlogos1 patched (console graphics-mode check skipped)")
            }
        }.onFailure { log("boot logo: playlogos1 patch failed: ${it.message}") }
    }

    
    fun provideHeadsetState(root: File, log: (String) -> Unit) {
        runCatching {
            val dir = File(root, "sys/class/switch/h2w")
            dir.mkdirs()
            val state = File(dir, "state")
            if (!state.isFile || state.readText() != "0\n") state.writeText("0\n")
            state.setReadable(true, false)
        }.onFailure { log("boot logo: cannot create the headset switch state (${it.message})") }
    }
}
