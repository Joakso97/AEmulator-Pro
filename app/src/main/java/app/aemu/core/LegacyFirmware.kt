
package app.aemu.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths


object LegacyFirmware {

    
    private const val ANDROID_1_0_UTC = 1222128000L

    
    const val PRE_RELEASE_API = 1
    const val PRE_RELEASE_RELEASE = "0.9"

    
    
    const val BOOT_SCAN_DIR = "system/media"

    const val PRE_RELEASE_TRANSLATOR = "libqemu_arm_pre1.0.so"

    
    const val API1_TRANSLATOR = "libqemu_arm_1.0.so"

    
    fun isPreRelease(release: String): Boolean = release.startsWith("0.")

    
    const val DISPLAY_NAME = "Android Pre-1.0"

    
    const val SYSTEM_SERVICE = "zygote"

    

    private fun norm(n: String) = n.removePrefix("./").trimStart('/')

    
    fun hasInitScript(names: Collection<String>): Boolean = names.any { val n = norm(it); n == "init.rc" || n == "etc/init.rc" }

    
    fun isLegacyInitRc(text: String): Boolean {
        val lines = text.lineSequence().map { stripComment(it).trim() }.filter { it.isNotEmpty() }.toList()
        val block = lines.any { Regex("^(startup|service|env)\\s*\\{$").matches(it) }
        val trigger = lines.any { Regex("^on\\s+\\S+").containsMatchIn(it) }
        return block && !trigger
    }

    

    
    data class Entry(val name: String, val argv: List<String>, val autostart: Boolean, val console: Boolean)

    class Rc(val exports: Map<String, String>, val startup: List<Entry>, val services: List<Entry>)

    private fun stripComment(raw: String): String {
        val t = raw.trimStart()
        if (t.startsWith("#")) return ""
        
        val i = Regex("\\s#").find(raw)?.range?.first ?: return raw
        return raw.substring(0, i)
    }

    private fun unquote(v: String): String = v.trim().let { if (it.length >= 2 && it.first() == '"' && it.last() == '"') it.substring(1, it.length - 1) else it }

    
    fun parseInitRc(text: String): Rc {
        val exports = LinkedHashMap<String, String>()
        val startup = ArrayList<Entry>()
        val services = ArrayList<Entry>()
        val stack = ArrayList<String>()
        var exec: String? = null
        var args = java.util.TreeMap<Int, String>()
        var autostart = true
        var console = false

        fun finish(section: String, name: String) {
            val bin = exec
            if (bin != null) {
                val e = Entry(name, listOf(bin) + args.values, autostart, console)
                if (section == "startup") startup.add(e) else if (section == "service") services.add(e)
            }
            exec = null; args = java.util.TreeMap(); autostart = true; console = false
        }

        for (raw in text.lineSequence()) {
            val ln = stripComment(raw).trim()
            if (ln.isEmpty()) continue
            if (ln.endsWith("{")) { stack.add(ln.removeSuffix("{").trim()); continue }
            if (ln == "}") {
                if (stack.isEmpty()) continue
                
                if (stack.size == 2 && (stack[0] == "startup" || stack[0] == "service")) finish(stack[0], stack[1])
                stack.removeAt(stack.size - 1)
                continue
            }
            val key = ln.substringBefore(' ').substringBefore('\t')
            val value = ln.substring(key.length).trim()
            when {
                stack.size == 1 && stack[0] == "env" -> if (value.isNotEmpty()) exports[key] = unquote(value)
                stack.size >= 2 && (stack[0] == "startup" || stack[0] == "service") -> when {
                    
                    stack.size == 3 && stack[2] == "args" -> key.toIntOrNull()?.let { args[it] = unquote(value) }
                    stack.size != 2 -> {}                       
                    key == "exec" -> exec = value
                    key.startsWith("args.") -> key.removePrefix("args.").toIntOrNull()?.let { args[it] = unquote(value) }
                    key == "autostart" -> autostart = value != "0"
                    key == "console" -> console = value.equals("yes", true) || value == "1"
                }
            }
        }
        return Rc(exports, startup, services)
    }

    
    fun hostFile(root: File, guestPath: String): File =
        if (guestPath.startsWith("/android/")) File(root, "system/" + guestPath.removePrefix("/android/"))
        else File(root, guestPath.removePrefix("/"))

    
    fun services(rc: Rc?, root: File): List<Entry> {
        val declared = rc?.services?.firstOrNull { it.name == "runtime" && it.autostart }
        val entry = declared ?: Entry("runtime", listOf("/android/bin/runtime"), true, false)
        return if (hostFile(root, entry.argv.first()).isFile) listOf(entry) else emptyList()
    }

    
    fun plan(rc: Rc?, root: File): List<GuestService> =
        services(rc, root).map { GuestService(SYSTEM_SERVICE, it.argv, restart = true) }

    
    fun bootPlan(root: File): List<GuestService>? {
        val rc = File(root, "etc/init.rc").takeIf { it.isFile && !File(root, "init.rc").isFile }
            ?.let { f -> runCatching { f.readText() }.getOrNull() }
            ?.takeIf { isLegacyInitRc(it) }
            ?.let { runCatching { parseInitRc(it) }.getOrNull() }
        if (isLegacyLayout(root)) return plan(rc, root)
        return splitPlan(rc, root)
    }

    
    fun splitPlan(rc: Rc?, root: File): List<GuestService>? {
        if (rc == null) return null
        val entries = rc.services.filter { it.autostart && (it.name == "zygote" || it.name == "runtime") && hostFile(root, it.argv.first()).isFile }
            .sortedBy { if (it.name == "zygote") 0 else 1 }
        if (entries.isEmpty()) return null
        return entries.map { GuestService(it.name, it.argv, restart = true) }
    }

    

    
    fun frameworkDirName(root: File): String? = when {
        File(root, "system/framework").isDirectory -> "framework"
        File(root, "system/javalib").isDirectory -> "javalib"
        else -> null
    }

    
    fun isLegacyLayout(root: File): Boolean {
        val runtime = File(root, "system/bin/runtime")
        if (!runtime.isFile) return false
        val head = runCatching { runtime.inputStream().use { i -> ByteArray(4096).let { b -> b.copyOf(maxOf(0, i.read(b))) } } }.getOrNull() ?: return false
        return String(head, Charsets.ISO_8859_1).contains("/android/bin/linker")
    }

    
    fun linksAgainstAndroidDir(root: File): Boolean {
        for (rel in listOf("system/bin/app_process", "system/bin/servicemanager", "system/bin/surfaceflinger", "system/bin/runtime", "system/bin/toolbox")) {
            val f = File(root, rel)
            if (!f.isFile) continue
            val head = runCatching { f.inputStream().use { i -> ByteArray(4096).let { b -> b.copyOf(maxOf(0, i.read(b))) } } }.getOrNull() ?: continue
            return String(head, Charsets.ISO_8859_1).contains("/android/bin/linker")
        }
        return false
    }

    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    
    private fun isEmptyMountPoint(f: File): Boolean {
        if (!Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val kids = f.listFiles() ?: return false
        return kids.all { isEmptyMountPoint(it) }
    }

    
    fun ensureLayout(root: File, log: (String) -> Unit = {}): List<String> {
        val made = ArrayList<String>()
        fun link(at: File, target: String, what: String) {
            
            
            
            if (exists(at) && !(isEmptyMountPoint(at) && at.deleteRecursively())) return
            runCatching {
                at.parentFile?.mkdirs()
                Files.createSymbolicLink(at.toPath(), Paths.get(target))
                made.add(what)
            }.onFailure { log("legacy layout: cannot link $what (${it.message})") }
        }
        if (File(root, "system/javalib").isDirectory) link(File(root, "system/framework"), "javalib", "system/framework -> javalib")
        if (isLegacyLayout(root) || linksAgainstAndroidDir(root)) link(File(root, "android"), "system", "android -> system")
        made.forEach { log("legacy layout: $it") }
        return made
    }

    
    fun ensureFbAlias(root: File): Boolean {
        
        
        if (!isLegacyLayout(root) && !(File(root, "system/javalib").isDirectory && File(root, "system/bin/runtime").isFile)) return false
        val at = File(root, "dev/fb0")
        if (exists(at)) return false
        return runCatching {
            at.parentFile?.mkdirs()
            Files.createSymbolicLink(at.toPath(), Paths.get("graphics/fb0"))
            true
        }.getOrDefault(false)
    }

    

    
    const val VM_JAM = "jam"
    const val VM_DALVIK = "dalvik"

    
    const val VM_ENV = "ANDROIDVM"

    
    fun runtimeVm(root: File): String? {
        val sys = File(root, "system")
        if (!isLegacyLayout(root) && !linksAgainstAndroidDir(root) && !File(sys, "javalib").isDirectory) return null
        val declared = runCatching {
            File(sys, "build.prop").takeIf { it.isFile }?.readLines()
                ?.map { it.trim() }?.firstOrNull { it.startsWith("runtime.vm=") }
                ?.substringAfter('=')?.trim()?.lowercase()
        }.getOrNull()
        if (declared == VM_JAM || declared == VM_DALVIK) return declared
        val libDir = File(sys, "lib")
        if (File(libDir, "libjamvm.so").isFile && !bootJarsHaveDex(sys)) return VM_JAM
        return null
    }

    fun usesJamVm(root: File): Boolean = runtimeVm(root) == VM_JAM

    
    private fun bootJarsHaveDex(sys: File): Boolean {
        val core = listOf("javalib", "framework").map { File(sys, "$it/core.jar") }.firstOrNull { it.isFile } ?: return false
        return runCatching { java.util.zip.ZipFile(core).use { it.getEntry("classes.dex") != null } }.getOrDefault(false)
    }

    

    
    fun completeBuildProps(props: Map<String, String>, root: File): LinkedHashMap<String, String> {
        val out = LinkedHashMap(props)
        if (!isPreRelease(props, root)) return out
        fun put(k: String, v: String) { if (out[k].isNullOrBlank()) out[k] = v }
        val id = props["ro.build.id"].orEmpty().trim()
        
        val maker = Regex("^([A-Za-z]+)[-_]").find(id)?.groupValues?.get(1)?.uppercase().orEmpty().ifEmpty { "generic" }
        val number = Regex("(\\d{3,})").find(id)?.groupValues?.get(1)
        put("ro.build.version.sdk", PRE_RELEASE_API.toString())
        put("ro.build.version.release", PRE_RELEASE_RELEASE)
        number?.let { put("ro.build.version.incremental", it) }
        put("ro.build.type", "eng")
        put("ro.product.brand", maker)
        put("ro.product.manufacturer", maker)
        put("ro.product.model", if (number != null) "Pre-1.0 build $number" else "Pre-1.0 prototype")
        put("ro.product.name", "generic")
        put("ro.product.device", "generic")
        put("ro.build.product", "generic")
        return out
    }

    fun isPreRelease(props: Map<String, String>, root: File): Boolean {
        if (!props["ro.build.version.sdk"].isNullOrBlank() || !props["ro.build.version.release"].isNullOrBlank()) return false
        val utc = props["ro.build.date.utc"]?.trim()?.toLongOrNull()
        val oldDate = utc != null && utc in 1 until ANDROID_1_0_UTC
        return oldDate || isLegacyLayout(root) || File(root, "system/javalib").isDirectory   
    }
}
