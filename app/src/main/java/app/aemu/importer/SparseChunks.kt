package app.aemu.importer


object SparseChunks {
    private val NAME = nameRegex("system")

    private fun nameRegex(partition: String) =
        Regex("(?i)(" + Regex.escape(partition) + "(?:(?:\\.img)?_sparsechunk\\.?|\\.img\\.?|[._]))(\\d{1,9})")

    class Chunk(val path: String, val index: Int)

    
    fun indexOf(path: String): Int? = indexOf(path, "system")

    
    fun indexOf(path: String, partition: String): Int? =
        (if (partition == "system") NAME else nameRegex(partition)).matchEntire(baseName(path))?.groupValues?.get(2)?.toIntOrNull()

    fun isChunkName(path: String) = indexOf(path) != null

    private fun baseName(path: String) = path.replace('\\', '/').substringAfterLast('/')

    
    fun select(paths: Collection<String>, partition: String = "system"): List<String> {
        val name = if (partition == "system") NAME else nameRegex(partition)
        val groups = LinkedHashMap<String, MutableList<Chunk>>()
        for (p in paths) {
            val norm = p.replace('\\', '/')
            val m = name.matchEntire(norm.substringAfterLast('/')) ?: continue
            val idx = m.groupValues[2].toIntOrNull() ?: continue
            val key = norm.substringBeforeLast('/', "") + "/" + m.groupValues[1].lowercase()
            groups.getOrPut(key) { ArrayList() }.add(Chunk(p, idx))
        }
        val best = groups.entries.minWithOrNull(
            compareBy<Map.Entry<String, MutableList<Chunk>>>({ -it.value.size },
                { it.key.substringBeforeLast('/').count { c -> c == '/' } }, { it.key })
        ) ?: return emptyList()
        
        return best.value.distinctBy { it.index }.sortedBy { it.index }.map { it.path }
    }
}
