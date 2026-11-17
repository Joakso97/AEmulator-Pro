package app.aemu.core

import java.io.File


object Lpw39Dex2oat {
    const val MARKER = "# aemu-lpw39-dex2oat-wrapper"
    private const val REAL = "dex2oat.real"

    fun script(): String = listOf(
        "#!/system/bin/sh",
        MARKER,
        "/system/bin/$REAL \"\$@\"",
        "rc=\$?",
        "[ \$rc -eq 134 ] || exit \$rc",
        "img=; oat=; fd=",
        "for a in \"\$@\"; do",
        "  case \"\$a\" in",
        "    --image=*) img=\${a#--image=} ;;",
        "    --oat-file=*) oat=\${a#--oat-file=} ;;",
        "    --oat-fd=*) fd=\${a#--oat-fd=} ;;",
        "  esac",
        "done",
        "if [ -n \"\$img\" ]; then [ -s \"\$img\" ] || exit \$rc; fi",
        "if [ -n \"\$oat\" ]; then [ -s \"\$oat\" ] || exit \$rc",
        "elif [ -n \"\$fd\" ]; then [ -s \"/proc/\$\$/fd/\$fd\" ] || exit \$rc",
        "else exit \$rc; fi",
        "exit 0",
        ""
    ).joinToString("\n")

    
    fun isWrapper(f: File): Boolean =
        f.isFile && f.length() < 4096 && runCatching { f.readText(Charsets.ISO_8859_1).contains(MARKER) }.getOrDefault(false)

    
    fun install(root: File): Boolean {
        val bin = File(root, "system/bin")
        val d2o = File(bin, "dex2oat")
        val real = File(bin, REAL)
        if (!d2o.exists() && !real.isFile) return false
        var changed = false
        if (d2o.exists() && !isWrapper(d2o)) {
            
            real.delete()
            if (!d2o.renameTo(real)) return false
            changed = true
        }
        if (!real.isFile) return false
        if (!isWrapper(d2o) || d2o.readText(Charsets.ISO_8859_1) != script()) {
            d2o.delete()
            d2o.writeText(script(), Charsets.ISO_8859_1)
            changed = true
        }
        for (f in listOf(d2o, real)) { f.setReadable(true, false); f.setExecutable(true, false) }
        return changed
    }
}
