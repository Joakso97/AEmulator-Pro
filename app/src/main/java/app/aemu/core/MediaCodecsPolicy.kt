
package app.aemu.core

import java.io.File


object MediaCodecsPolicy {
    
    private val SOFTWARE_PREFIXES = listOf("OMX.google.", "c2.android.")

    
    private val ETC_DIRS = listOf("system/etc", "vendor/etc", "system/vendor/etc", "odm/etc", "product/etc")

    
    private const val BACKUP_DIR = "system/.aemu-codecs-orig"

    private val DECODERS = Regex("<Decoders\\b[^>]*>.*?</Decoders>", RegexOption.DOT_MATCHES_ALL)

    
    private val MEDIA_CODEC = Regex(
        "<MediaCodec\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*?(?:/>|>.*?</MediaCodec>)", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val INCLUDE_GOOGLE = Regex("<Include\\b[^>]*\\bhref\\s*=\\s*[\"'](media_codecs_google[^\"']*)[\"']")

    fun isSoftware(codecName: String) = SOFTWARE_PREFIXES.any { codecName.startsWith(it) }

    
    private fun isBoardList(name: String) = name.startsWith("media_codecs") && name.endsWith(".xml") &&
        !name.startsWith("media_codecs_google") && !name.startsWith("media_codecs_performance") &&
        !name.startsWith("media_codecs_profiling")

    
    fun filter(xml: String, softwareIncluded: Boolean): String {
        var keepsSoftware = softwareIncluded
        val out = DECODERS.replace(xml) { block ->
            MEDIA_CODEC.replace(block.value) { m ->
                val tag = m.value.substringBefore('>')
                val name = NAME.find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
                if (isSoftware(name)) { keepsSoftware = true; m.value } else ""
            }
        }
        return if (keepsSoftware) out else xml
    }

    
    fun configure(root: File, enabled: Boolean = true): List<String> {
        val changed = ArrayList<String>()
        for (dir in ETC_DIRS) {
            val etc = File(root, dir)
            for (f in etc.listFiles().orEmpty().sortedBy { it.name }) {
                if (!f.isFile || !isBoardList(f.name)) continue
                val rel = "$dir/${f.name}"
                if (process(root, f, rel, enabled)) changed += rel
            }
        }
        
        for (b in File(root, BACKUP_DIR).listFiles().orEmpty()) {
            if (!File(root, b.name.replace('#', '/')).isFile) b.delete()
        }
        return changed
    }

    private fun process(root: File, f: File, rel: String, enabled: Boolean): Boolean {
        val backup = File(root, "$BACKUP_DIR/${rel.replace('/', '#')}")
        if (!enabled) {
            if (!backup.isFile) return false
            f.setWritable(true)
            backup.copyTo(f, overwrite = true)
            backup.delete()
            return true
        }
        val current = f.readText()
        
        if (backup.isFile) {
            val kept = backup.readText()
            if (filter(kept, included(root, kept)) == current) return false
        }
        val original = current
        val patched = filter(original, included(root, original))
        if (patched == current) {
            
            if (backup.isFile) backup.delete()
            return false
        }
        backup.parentFile?.mkdirs()
        backup.writeText(original)
        f.setWritable(true)
        f.writeText(patched)
        f.setReadable(true, false)
        return true
    }

    
    private fun included(root: File, xml: String): Boolean = INCLUDE_GOOGLE.findAll(xml).any { m ->
        ETC_DIRS.any { d -> File(root, "$d/${m.groupValues[1]}").isFile }
    }
}
