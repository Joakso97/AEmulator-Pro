
package app.aemu.core

import java.io.File


object Dex2oatSkip {
    const val MARKER = "# aemu-dex2oat-skip-services-wrapper"
    
    private const val PATTERNS = "*=services.jar|*/services.jar|services.jar|*=services.odex|*/services.odex|*system@framework@services.jar@*"
    private val NAMES = listOf("dex2oat", "dex2oatd")
    private const val DIR = "apex/com.android.runtime/bin"

    fun script(dir: String, name: String): String = listOf(
        "#!/system/bin/sh",
        MARKER,
        "for a in \"\$@\"; do",
        "  case \"\$a\" in",
        "    $PATTERNS) echo \"aemu: dex2oat skipped for the patched services.jar\" >&2; exit 1 ;;",
        "  esac",
        "done",
        "exec /$dir/$name.real \"\$@\"",
        ""
    ).joinToString("\n")

    fun isWrapper(f: File): Boolean =
        f.isFile && f.length() < 4096 && runCatching { f.readText(Charsets.ISO_8859_1).contains(MARKER) }.getOrDefault(false)

    
    fun install(root: File): Boolean {
        var changed = false
        for (n in NAMES) {
            val d2o = File(root, "$DIR/$n")
            val real = File(root, "$DIR/$n.real")
            if (!d2o.exists() && !real.isFile) continue
            if (d2o.exists() && !isWrapper(d2o)) {
                
                if (d2o.length() < 4096 && runCatching { d2o.readText(Charsets.ISO_8859_1).startsWith("#!") }.getOrDefault(false)) continue
                real.delete()
                if (!d2o.renameTo(real)) continue
                changed = true
            }
            if (!real.isFile) continue
            val want = script(DIR, n)
            if (!isWrapper(d2o) || d2o.readText(Charsets.ISO_8859_1) != want) {
                d2o.delete()
                d2o.writeText(want, Charsets.ISO_8859_1)
                changed = true
            }
            for (f in listOf(d2o, real)) { f.setReadable(true, false); f.setExecutable(true, false) }
        }
        return changed
    }

    
    fun uninstall(root: File): Boolean {
        var changed = false
        for (n in NAMES) {
            val d2o = File(root, "$DIR/$n")
            val real = File(root, "$DIR/$n.real")
            if (!isWrapper(d2o) || !real.isFile) continue
            d2o.delete()
            if (real.renameTo(d2o)) changed = true
        }
        return changed
    }
}
