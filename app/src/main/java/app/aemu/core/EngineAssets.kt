package app.aemu.core

import android.content.Context
import java.io.InputStream


object EngineAssets {
    private class Entry(val blob: String, val size: Long)

    @Volatile private var index: Map<String, Entry>? = null
    @Volatile private var dirs: Map<String, List<String>>? = null

    private fun load(ctx: Context): Map<String, Entry> {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val m = HashMap<String, Entry>(4096)
            val d = HashMap<String, MutableList<String>>()
            ctx.assets.open("engines.idx").bufferedReader().useLines { lines ->
                for (l in lines) {
                    val p = l.split('\t')
                    if (p.size < 3) continue
                    m[p[0]] = Entry(p[1], p[2].toLong())
                    d.getOrPut(p[0].substringBeforeLast('/')) { ArrayList() }.add(p[0].substringAfterLast('/'))
                }
            }
            dirs = d
            index = m
            return m
        }
    }

    
    fun list(ctx: Context, dir: String): Array<String> {
        load(ctx)
        return dirs?.get(dir.trimEnd('/'))?.toTypedArray() ?: emptyArray()
    }

    const val ARM32 = "arm32"
    const val ARM64 = "arm64"

    
    fun layerFiles(ctx: Context, dir: String, guest64: Boolean): Map<String, String> {
        load(ctx)
        val d = dir.trimEnd('/')
        val own = if (guest64) ARM64 else ARM32
        val other = if (guest64) ARM32 else ARM64
        val out = LinkedHashMap<String, String>()
        for (sub in listOf("$d/$other", d, "$d/$own")) for (n in dirs?.get(sub).orEmpty()) out[n] = "$sub/$n"
        return out
    }

    
    fun layerNames(ctx: Context, dir: String): Set<String> {
        load(ctx)
        val d = dir.trimEnd('/')
        return listOf(d, "$d/$ARM32", "$d/$ARM64").flatMap { dirs?.get(it).orEmpty() }.toSet()
    }

    
    fun layerAsset(ctx: Context, dir: String, name: String, prefer: String): String? {
        val d = dir.trimEnd('/')
        val other = if (prefer == ARM64) ARM32 else ARM64
        return listOf("$d/$prefer/$name", "$d/$other/$name", "$d/$name").firstOrNull { exists(ctx, it) }
    }

    fun exists(ctx: Context, path: String): Boolean = load(ctx).containsKey(path)

    
    const val X86_64_GPU_BRIDGE = "engines/common-x86_64/system#lib64#egl#libGLES_aemu.so"

    
    fun x86_64Dirs(tag: String, api: Int): List<String> =
        listOf("engines/common-x86_64", "engines/n64-$tag/x86_64") + (if (api == 29 && tag != "30") listOf("engines/n64-30/x86_64") else emptyList())

    
    fun x86_64Files(ctx: Context, tag: String, api: Int): Map<String, String> {
        load(ctx)
        val out = LinkedHashMap<String, String>()
        for (d in x86_64Dirs(tag, api)) for (n in dirs?.get(d).orEmpty()) if ('#' in n) out[n] = "$d/$n"
        return out
    }

    
    fun forArch(ctx: Context, path: String, arch: GuestArch): String {
        if (arch == GuestArch.ARM) return path
        val m = Regex("^engines/(common|kk|gb)/(.+)$").find(path) ?: return path
        val alt = "engines/${m.groupValues[1]}${arch.assetSuffix}/${m.groupValues[2]}"
        if (exists(ctx, alt)) return alt
        return if (isMachineIndependent(path) && exists(ctx, path)) path else "engines/.missing-${arch.id}/${m.groupValues[2]}"
    }

    
    fun isMachineIndependent(path: String): Boolean =
        path.endsWith(".jar") || path.endsWith(".props") || path.endsWith(".xml") || path.endsWith(".txt")

    
    fun size(ctx: Context, path: String): Long = load(ctx)[path]?.size ?: -1L

    fun open(ctx: Context, path: String): InputStream {
        val e = load(ctx)[path] ?: throw java.io.FileNotFoundException(path)
        return ctx.assets.open("blobs/" + e.blob)
    }
}
