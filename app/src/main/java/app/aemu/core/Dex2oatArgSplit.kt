package app.aemu.core

import java.io.File


object Dex2oatArgSplit {
    const val MARKER = "# aemu-dex2oat-argsplit-wrapper"
    private const val REAL = "dex2oat.real"

    fun script(): String = listOf(
        "#!/system/bin/sh",
        MARKER,
        "set -f",
        "n=\$#",
        "while [ \$n -gt 0 ]; do",
        "  a=\"\$1\"; shift",
        "  case \"\$a\" in",
        "    -j*\" \"*) set -- \"\$@\" \$a ;;",
        "    *) set -- \"\$@\" \"\$a\" ;;",
        "  esac",
        "  n=\$((n-1))",
        "done",
        "exec /system/bin/$REAL \"\$@\"",
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
            
            if (d2o.length() < 4096 && runCatching { d2o.readText(Charsets.ISO_8859_1).startsWith("#!") }.getOrDefault(false)) return false
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
