package app.aemu.core

import java.io.File


object KeymasterLibs {
    
    private val DEFAULT_DIRS = listOf("vendor/lib", "odm/lib")

    
    private val SEARCH = listOf(
        "vendor/lib", "vendor/lib/hw", "odm/lib", "odm/lib/hw", "system/vendor/lib", "system/vendor/lib/hw",
        "system/lib", "system/lib/hw", "apex/com.android.runtime/lib/bionic", "apex/com.android.runtime/lib",
    )

    class Closure(val files: List<File>, val missing: List<String>)

    class Result(
        
        val impl: File?,
        
        val copied: List<String>,
        
        val missing: List<String>,
        
        val unresolved: List<String>,
        
        val replaced: List<String> = emptyList(),
    ) {
        val ok get() = impl != null && missing.isEmpty() && unresolved.isEmpty()
    }

    
    fun vndkDirs(root: File): List<String> {
        val out = ArrayList<String>()
        File(root, "system/lib").listFiles()?.filter { it.isDirectory && it.name.startsWith("vndk") }?.sortedBy { it.name }
            ?.forEach { out += "system/lib/${it.name}" }
        File(root, "apex").listFiles()?.filter { it.isDirectory && it.name.startsWith("com.android.vndk") }?.sortedBy { it.name }
            ?.forEach { out += "apex/${it.name}/lib" }
        return out
    }

    
    fun bringable(name: String) = name.startsWith("libkeymaster") || name.startsWith("libsoft") || name.startsWith("libpuresoft")

    
    private const val SOFT = "libsoftkeymasterdevice.so"
    private const val SOFT_REPLACEMENT = "libkeymaster_portable.so"

    
    private fun implNeedsSoftDevice(root: File, impl: File): Boolean {
        val c = closure(root, impl)
        val soft = c.files.firstOrNull { it.name == SOFT } ?: return false
        val implSyms = ElfPatch.dynSyms32(impl) ?: return true
        val softDefs = ElfPatch.dynSyms32(soft)?.defined ?: return true
        val others = HashSet<String>()
        for (f in c.files) if (f != impl && f != soft) ElfPatch.dynSyms32(f)?.let { others += it.defined }
        return implSyms.undefined.any { it in softDefs && it !in others }
    }

    
    fun dropDuplicateSoftDevice(root: File, impl: File, log: (String) -> Unit): Boolean {
        val names = ElfPatch.needed32(impl).orEmpty()
        if (SOFT !in names) return true
        if (!names.contains("libpuresoftkeymasterdevice.so")) return false      
        if (implNeedsSoftDevice(root, impl)) {
            log("keymaster: ${impl.name} imports symbols only $SOFT defines, DT_NEEDED left as it is")
            return false
        }
        val n = runCatching {
            impl.setWritable(true, true)
            ElfPatch.redirectNeeded(impl, SOFT, SOFT_REPLACEMENT)
        }.getOrDefault(0)
        runCatching { impl.setReadable(true, false) }
        log(if (n > 0) "keymaster: ${impl.name}: DT_NEEDED $SOFT -> $SOFT_REPLACEMENT (libpuresoftkeymasterdevice and libsoftkeymasterdevice " +
            "both define PureSoftKeymasterContext; one copy only, else importKey frees through the wrong vtable, exit 139)"
        else "keymaster: ${impl.name}: DT_NEEDED $SOFT could not be redirected")
        return n > 0
    }

    private fun keymasterSymbol(s: String) = s.contains("8keymaster") || s.contains("9keymaster")

    
    fun closure(root: File, start: File): Closure {
        val dirs = (SEARCH + vndkDirs(root)).map { File(root, it) }
        val seen = HashSet<String>()
        val missing = ArrayList<String>()
        val files = arrayListOf(start)
        val queue = ArrayDeque<File>()
        queue.add(start)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 300) {
            val cur = queue.removeFirst()
            for (lib in ElfPatch.needed32(cur).orEmpty()) {
                if (!seen.add(lib)) continue
                val hit = dirs.map { File(it, lib) }.firstOrNull { it.isFile }
                if (hit == null) missing += "$lib (from ${cur.name})" else { queue.add(hit); files += hit }
            }
        }
        return Closure(files, missing)
    }

    
    private val REACH = listOf("odm/lib", "vendor/lib")

    
    private fun platformDirs(root: File) = (listOf("system/lib") + vndkDirs(root)).map { File(root, it) }

    
    class Member(val name: String, val reachable: String?, val platform: String?)

    class Plan(
        
        val take: List<String>,
        
        val overwrite: List<String>,
        
        val verdict: String,
    )

    
    fun plan(members: List<Member>): Plan {
        val absent = members.filter { it.reachable == null && it.platform != null }.map { it.name }
        val same = members.filter { it.reachable != null && it.platform != null && it.reachable == it.platform }.map { it.name }
        val differ = members.filter { it.reachable != null && it.platform != null && it.reachable != it.platform }.map { it.name }
        return when {
            differ.isEmpty() -> Plan(absent, emptyList(),
                if (absent.isEmpty()) "one release (the platform's)" else "one release (the platform's; ${absent.size} brought over)")
            same.isEmpty() && absent.isEmpty() -> Plan(emptyList(), emptyList(), "one release (the firmware's own set)")
            else -> Plan(absent + differ, differ,
                "MIXED releases: ${same.size + absent.size} of the platform's (${(same + absent).joinToString()}), " +
                    "${differ.size} of the firmware's (${differ.joinToString()})")
        }
    }

    private fun sha1(f: File): String = java.security.MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    
    private fun familyNames(root: File, impl: File): List<String> {
        val c = closure(root, impl)
        return (c.files.drop(1).map { it.name } + c.missing.map { it.substringBefore(' ') }).filter { bringable(it) }.distinct().sorted()
    }

    private fun members(root: File, names: List<String>): List<Pair<Member, Pair<File?, File?>>> {
        val platform = platformDirs(root)
        return names.map { n ->
            val reach = REACH.map { File(root, "$it/$n") }.firstOrNull { it.isFile }
            val plat = platform.map { File(it, n) }.firstOrNull { it.isFile }
            Member(n, reach?.let { sha1(it) }, plat?.let { sha1(it) }) to (reach to plat)
        }
    }

    
    fun complete(root: File, log: (String) -> Unit): Result {
        val impl = listOf("vendor/lib/android.hardware.keymaster@3.0-impl.so", "vendor/lib/hw/android.hardware.keymaster@3.0-impl.so")
            .map { File(root, it) }.firstOrNull { it.isFile }
        if (impl == null) {
            log("keymaster: the tree has no 32-bit android.hardware.keymaster@3.0-impl.so")
            return Result(null, emptyList(), emptyList(), emptyList())
        }
        val copied = ArrayList<String>()
        val replaced = ArrayList<String>()
        
        for (round in 0 until 4) {
            val ms = members(root, familyNames(root, impl))
            val p = plan(ms.map { it.first })
            if (round == 0) {
                for ((m, files) in ms) log("keymaster: ${m.name}: " + when {
                    files.first == null && files.second == null -> "in no directory of the tree"
                    files.first == null -> "not in /vendor/lib, the platform has it (${files.second!!.parentFile?.relativeTo(root)?.path})"
                    files.second == null -> "${files.first!!.parentFile?.relativeTo(root)?.path} only (${m.reachable!!.take(8)})"
                    m.reachable == m.platform -> "${files.first!!.parentFile?.relativeTo(root)?.path} is the platform's copy (${m.reachable!!.take(8)})"
                    else -> "${files.first!!.parentFile?.relativeTo(root)?.path} ${m.reachable!!.take(8)} differs from the platform's ${m.platform!!.take(8)}"
                })
                log("keymaster: ${p.verdict}")
            }
            if (p.take.isEmpty()) break
            var changed = false
            for (n in p.take) {
                val (_, files) = ms.first { it.first.name == n }
                val src = files.second ?: continue
                val dst = files.first ?: File(root, "vendor/lib/$n")
                runCatching {
                    if (dst.isFile) {
                        val park = File(root, "system/.aemu-parked/${dst.relativeTo(root).path.replace('/', '#')}")
                        if (!park.exists()) { park.parentFile?.mkdirs(); dst.copyTo(park) }
                    }
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.setWritable(true, true)
                    src.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    changed = true
                    if (n in p.overwrite) { replaced += n; log("keymaster: $n of /${dst.parentFile?.relativeTo(root)?.path} replaced by the platform's ${src.parentFile?.relativeTo(root)?.path}/$n (the firmware's is kept in system/.aemu-parked)") }
                    else { copied += n; log("keymaster: $n brought into /${dst.parentFile?.relativeTo(root)?.path} from ${src.parentFile?.relativeTo(root)?.path}") }
                }.onFailure { log("keymaster: $n could not be put into /vendor/lib: ${it.message}") }
            }
            if (!changed) break
        }
        dropDuplicateSoftDevice(root, impl, log)
        val c = closure(root, impl)
        
        val kmMissing = c.missing.any { bringable(it.substringBefore(' ')) }
        val unresolved = ArrayList<String>()
        if (!kmMissing) {
            val syms = c.files.map { it to ElfPatch.dynSyms32(it) }
            val defined = HashSet<String>()
            for ((_, s) in syms) if (s != null) defined += s.defined
            for ((file, s) in syms) if (s != null) for (u in s.undefined.sorted()) if (keymasterSymbol(u) && u !in defined) unresolved += "$u (${file.name})"
        }
        log("keymaster: ${impl.name}: " + if (c.missing.isEmpty()) "every library found (${c.files.size - 1})" else "NOT FOUND " + c.missing.joinToString(", "))
        
        
        val outside = c.files.drop(1).filter { f -> DEFAULT_DIRS.none { File(root, "$it/${f.name}").isFile } }
        if (outside.isNotEmpty()) log("keymaster: loaded from outside /vendor/lib (the linker config decides whether the vendor namespace sees them): " +
            outside.take(40).joinToString(", ") { it.name + " [" + (it.parentFile?.relativeTo(root)?.path ?: "?") + "]" })
        if (unresolved.isNotEmpty())
            log("keymaster: ${unresolved.size} keymaster symbols have no definition in the libraries the HAL would load (${c.files.joinToString(", ") { it.name }}); " +
                "the keymaster libraries of the firmware are not the release the engine's keymaster was built against. First: " + unresolved.take(6).joinToString("; "))
        if (kmMissing) log("keymaster: the tree has no 32-bit " + c.missing.map { it.substringBefore(' ') }.filter { bringable(it) }.joinToString(", ") +
            " anywhere (system/lib, the VNDK directories, vendor/lib): the HAL cannot load until they are in the tree")
        val r = Result(impl, copied, c.missing, unresolved, replaced)
        if (r.ok) log("keymaster: the 32-bit software keymaster can be loaded (libraries found, keymaster symbols resolve)")
        return r
    }
}
