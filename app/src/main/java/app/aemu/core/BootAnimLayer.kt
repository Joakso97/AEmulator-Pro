package app.aemu.core


internal object BootAnimLayer {
    private val layerLine = Regex("""^\+ \S+(?: 0x[0-9a-fA-F]+)? \((.+)\)\s*$""")

    
    fun visibleLayers(dump: String): List<String> {
        val out = ArrayList<String>()
        var inSection = false
        for (line in dump.lineSequence()) {
            if (!inSection) { if (line.startsWith("Visible layers")) inSection = true; continue }
            val m = layerLine.matchEntire(line)
            if (m != null) { out += m.groupValues[1]; continue }
            
            if (line.isNotEmpty() && line[0] != ' ' && line[0] != '\t' && line[0] != '+') break
        }
        return out
    }

    fun isBootAnim(name: String) = name.contains("BootAnimation", ignoreCase = true) || name.equals("bootanim", ignoreCase = true)

    
    fun topIsBootAnim(dump: String): Boolean = visibleLayers(dump).lastOrNull()?.let(::isBootAnim) == true
}
