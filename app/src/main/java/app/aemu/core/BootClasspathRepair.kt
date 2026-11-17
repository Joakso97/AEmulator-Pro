package app.aemu.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile


object BootClasspathRepair {
    data class Result(
        
        val bootclasspath: String,
        val added: List<String>,
        
        val absent: List<String>,
        
        val undefined: Int,
        
        val recovered: List<String>,
        
        val skipped: String?,
    ) {
        fun report(): String = when {
            skipped != null -> "bootclasspath: not checked ($skipped)"
            added.isEmpty() -> "bootclasspath: ok" + if (absent.isEmpty()) "" else ", not in the tree and dropped by the VM: ${absent.joinToString { it.substringAfterLast('/') }}"
            else -> "bootclasspath: added ${added.joinToString { it.substringAfterLast('/') }} (${recovered.size} class(es) that libandroid_runtime, the boot jars or the system apps use are in " +
                "them, e.g. ${recovered.take(3).joinToString()}); the firmware's init.rc leaves them out" +
                if (absent.isEmpty()) "" else "; not in the tree: ${absent.joinToString { it.substringAfterLast('/') }}"
        }
    }

    private val cache = ConcurrentHashMap<String, Result>()

    
    fun applies(root: File): Boolean =
        File(root, "system/lib/libdvm.so").isFile && !File(root, "system/lib/libart.so").isFile && File(root, "system/lib/libandroid_runtime.so").isFile

    
    fun repair(root: File, bootclasspath: String): Result =
        cache.getOrPut(root.absolutePath + "|" + bootclasspath) {
            runCatching { compute(root, bootclasspath) }.getOrElse {
                Result(bootclasspath, emptyList(), emptyList(), 0, emptyList(), "failed: ${it.javaClass.simpleName} ${it.message}")
            }
        }

    
    fun forget(root: File) { cache.keys.removeAll { it.startsWith(root.absolutePath + "|") } }

    private fun compute(root: File, bootclasspath: String): Result {
        fun unchanged(why: String) = Result(bootclasspath, emptyList(), emptyList(), 0, emptyList(), why)
        if (bootclasspath.isBlank()) return unchanged("empty list")
        if (!applies(root)) return unchanged("not a Dalvik tree")

        val entries = bootclasspath.split(':').filter { it.isNotBlank() }
        val files = entries.map { it to File(root, it.removePrefix("/")) }
        val absent = files.filter { !it.second.isFile }.map { it.first }

        
        val defined = HashSet<String>()
        val unreadable = ArrayList<String>()
        val bootDex = ArrayList<ByteArray>()
        for ((name, f) in files) {
            if (!f.isFile) continue
            val bytes = dexOf(f)
            if (bytes == null) unreadable += name.substringAfterLast('/') else { defined += dexClasses(bytes); bootDex += bytes }
        }
        
        if (unreadable.isNotEmpty()) return Result(bootclasspath, emptyList(), absent, 0, emptyList(), "no classes.dex in ${unreadable.joinToString()} (odexed)")

        val referenced = referencedClasses(File(root, "system/lib/libandroid_runtime.so"))
        val missing = referenced.filter { it !in defined }
        
        
        
        val unresolved = HashSet<String>()
        for (b in bootDex) for (t in typeRefs(b)) if (t !in defined) unresolved += t
        unresolved.removeAll { it.startsWith("Ljava/") || it.startsWith("Ljavax/") }

        
        val onPath = entries.toSet()
        val extras = File(root, "system/framework").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar") && "/system/framework/${it.name}" !in onPath }
            ?.sortedBy { it.name } ?: emptyList()

        val remaining = missing.toMutableSet()
        val added = ArrayList<String>()
        val recovered = ArrayList<String>()
        for (jar in extras) {
            
            if (jar.name.startsWith("aemu-")) continue
            val set = classesOf(jar) ?: continue
            val hit = remaining.filter { it in set }
            val refd = unresolved.filter { it in set }
            if (hit.isEmpty() && refd.isEmpty()) continue
            added += "/system/framework/${jar.name}"
            recovered += (hit + refd).distinct().map { it.removePrefix("L").removeSuffix(";") }
            remaining.removeAll(hit.toSet())
            unresolved.removeAll(refd.toSet())
        }
        
        
        
        
        val appNeeds = appUnresolved(root, defined, extras.filter { !it.name.startsWith("aemu-") && "/system/framework/${it.name}" !in added }, libraryJars(root))
        for ((jarPath, classes) in appNeeds) {
            if (jarPath in added) continue
            added += jarPath
            recovered += classes.take(5).map { it.removePrefix("L").removeSuffix(";") }
        }
        val list = if (added.isEmpty()) bootclasspath else bootclasspath.trimEnd(':') + ":" + added.joinToString(":")
        return Result(list, added, absent, remaining.size + unresolved.size, recovered, null)
    }

    
    internal fun dexOf(jar: File): ByteArray? = runCatching {
        ZipFile(jar).use { z ->
            val e = z.getEntry("classes.dex")
            if (e == null) null else z.getInputStream(e).use { it.readBytes() }
        }
    }.getOrNull()

    
    internal fun classesOf(jar: File): Set<String>? = dexOf(jar)?.let { dexClasses(it) }

    
    internal fun typeRefs(dex: ByteArray): Set<String> {
        val x = DexTools.Dex(dex)
        val out = HashSet<String>()
        for (i in 0 until x.typeIds) { val t = x.type(i); if (t.startsWith("L")) out += t }
        return out
    }

    internal fun dexClasses(dex: ByteArray): Set<String> {
        val x = DexTools.Dex(dex)
        val out = HashSet<String>(x.classDefs * 2)
        for (c in 0 until x.classDefs) out += x.type(DexTools.u4(dex, x.classDefsOff + 32 * c))
        return out
    }

    
    internal fun libraryJars(root: File): Set<String> {
        val out = HashSet<String>()
        val re = Regex("""<library\s[^>]*file\s*=\s*"([^"]+)"""")
        File(root, "system/etc/permissions").listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { f ->
            runCatching { re.findAll(f.readText()).forEach { out += it.groupValues[1] } }
        }
        return out
    }

     
 
 
 
    internal fun appUnresolved(root: File, bootDefined: Set<String>, extras: List<File>, libraries: Set<String>): LinkedHashMap<String, MutableList<String>> {
        val owner = HashMap<String, String>()
        for (jar in extras) {
            val path = "/system/framework/${jar.name}"
            if (path in libraries) continue
            val set = classesOf(jar) ?: continue
            for (c in set) if (c !in bootDefined) owner.putIfAbsent(c, path)
        }
        val out = LinkedHashMap<String, MutableList<String>>()
        if (owner.isEmpty()) return out
        val apks = listOf("system/app", "system/priv-app", "vendor/app").flatMap { d ->
            File(root, d).listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.sortedBy { it.name } ?: emptyList()
        }
        for (apk in apks) {
            val dexes = runCatching {
                ZipFile(apk).use { z ->
                    z.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                        .map { e -> z.getInputStream(e).use { it.readBytes() } }.toList()
                }
            }.getOrNull() ?: continue
            if (dexes.isEmpty()) continue
            val own = HashSet<String>()
            for (b in dexes) own += dexClasses(b)
            for (b in dexes) for (t in typeRefs(b)) {
                val jar = owner[t] ?: continue
                if (t in own) continue
                out.getOrPut(jar) { ArrayList() }.let { if (it.size < 50 && t !in it) it += t }
            }
        }
        return out
    }

    private val NAME = Regex("^(android|com|java|javax|dalvik|libcore|org|junit|sun)/[A-Za-z0-9_$]+(/[A-Za-z0-9_$]+)+$")

     
 
 
 
    internal fun referencedClasses(lib: File): Set<String> {
        val d = lib.readBytes()
        val out = LinkedHashSet<String>()
        var start = -1
        for (i in 0..d.size) {
            val b = if (i < d.size) d[i].toInt() and 0xff else 0
            val printable = b in 0x21..0x7e
            if (printable) { if (start < 0) start = i; continue }
            if (start >= 0 && i - start in 9..160) {
                val s = String(d, start, i - start, Charsets.ISO_8859_1)
                if (NAME.matches(s) && !s.endsWith(".so") && !s.endsWith(".jar")) out += "L$s;"
            }
            start = -1
        }
        return out
    }
}
