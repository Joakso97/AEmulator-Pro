package app.aemu.core

import java.io.File
import java.util.concurrent.TimeUnit


class GuestRunner(
    val paths: VmPaths,
    val img: GuestImage,
    val net: NetConfig = NetConfig(),
) {
    
    data class NetConfig(val http80: Int = 0, val https443: Int = 0, val dns53: Int = 0, val glPath: String? = null, val netDbg: Int = 0, val netDns: Int = 0)

    var qemuLog = false
    var qemuStrace = false
    var tbFlush = 0
    var noSmc = false
    var noTcgOpt = false
    var singleTouch = false
    var binderVerbose = false
    
    @Volatile var guardArmed = false
    var sdcardHost: File? = null
    
    var userQemuArgs: String = ""

    private val userTokens: List<String> get() = Regex("""("[^"]*"|\S+)""").findAll(userQemuArgs).map { it.value.trim('"') }.toList()
    private fun isEnvToken(t: String) = !t.startsWith("-") && Regex("^[A-Z_][A-Z0-9_]*=").containsMatchIn(t)

    
    private val jamVm: Boolean by lazy { LegacyFirmware.usesJamVm(paths.root) }

    val engine: Engine get() = img.engine
    
    val arch: GuestArch get() = img.arch
    val kernel: Kernel64 get() = Kernel64.forApi(img.api)
    val qemu: File get() = paths.nativeBin(
        if (engine == Engine.N64) kernel.runner
        
        else if (arch != GuestArch.ARM) arch.translator(engine)
        
        else if (LegacyFirmware.isPreRelease(img.release) && paths.nativeBin(LegacyFirmware.PRE_RELEASE_TRANSLATOR).canExecute()) LegacyFirmware.PRE_RELEASE_TRANSLATOR
        
        else if (img.api in 1..2 && engine == Engine.KK && paths.nativeBin(LegacyFirmware.API1_TRANSLATOR).canExecute()) LegacyFirmware.API1_TRANSLATOR
        else if (QemuFallback.enabled(paths) && paths.nativeBin(engine.qemuXperiaPlay).canExecute()) engine.qemuXperiaPlay else engine.qemu)
    val runner: File get() = paths.nativeBin(engine.runner)

    fun cmdline(argv: List<String>, propsFile: File? = null): List<String> {
        val prog = argv.first()
        val host = File(paths.root, prog.removePrefix("/")).absolutePath
        val cmd = ArrayList<String>()
        if (engine == Engine.N64) return listOf(qemu.absolutePath, prog) + argv
        val props = propsFile ?: paths.props
        if (runner.canExecute() && props.isFile) {
            cmd += runner.absolutePath; cmd += props.absolutePath; cmd += "30"
        }
        
        if (arch == GuestArch.ARM && img.settings.native32 && CpuInfo.hasAArch32) {
            cmd += host
            cmd += argv.drop(1)
            return cmd
        }
        cmd += qemu.absolutePath
        cmd += listOf("-L", paths.root.absolutePath, "-0", prog)
        if (qemuLog || qemuStrace) {
            if (qemuLog) cmd += listOf("-d", "unimp,guest_errors")
            if (qemuStrace) cmd += "-strace"
            cmd += listOf("-D", File(paths.bin, "qemu-${prog.substringAfterLast('/')}.log").absolutePath)
        }
        cmd += userTokens.filterNot(::isEnvToken)
        cmd += host
        cmd += argv.drop(1)
        return cmd
    }

    
    @Volatile var derivedExports: Map<String, String> = emptyMap()

    
    private fun env64(extra: Map<String, String>, program: String?): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        for ((k, v) in img.exports) if (k !in EXPORTS_SKIP) e[k] = v
        e["PATH"] = img.exports["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        e["LD_LIBRARY_PATH"] = "/vendor/lib64:/system/lib64"
        e["ANDROID_ROOT"] = img.exports["ANDROID_ROOT"] ?: "/system"
        e["ANDROID_DATA"] = img.exports["ANDROID_DATA"] ?: "/data"
        e["ANDROID_ASSETS"] = img.exports["ANDROID_ASSETS"] ?: "/system/app"
        e["EXTERNAL_STORAGE"] = img.exports["EXTERNAL_STORAGE"] ?: img.sdcardPath
        e["BOOTCLASSPATH"] = img.bootclasspath
        e.putIfAbsent("EMULATED_STORAGE_SOURCE", "/mnt/shell/emulated")
        e.putIfAbsent("EMULATED_STORAGE_TARGET", "/storage/emulated")
        
        
        for ((k, apex) in listOf("ANDROID_ART_ROOT" to "com.android.art", "ANDROID_I18N_ROOT" to "com.android.i18n",
            "ANDROID_TZDATA_ROOT" to "com.android.tzdata", "ANDROID_RUNTIME_ROOT" to "com.android.runtime"))
            if (File(paths.root, "apex/$apex").isDirectory) e.putIfAbsent(k, "/apex/$apex")
        
        e.putIfAbsent("ASEC_MOUNTPOINT", "/mnt/asec")
        e.putIfAbsent("ANDROID_BOOTLOGO", "1")
        e.putIfAbsent("ANDROID_STORAGE", "/storage")
        if ("DOWNLOAD_CACHE" !in e && img.api >= 26) {
            File(paths.root, "data/cache").mkdirs()
            e["DOWNLOAD_CACHE"] = "/data/cache"
        }
        if ("SYSTEMSERVERCLASSPATH" !in e) {
            val jars = listOf("services.jar", "ethernet-service.jar", "wifi-service.jar")
                .filter { File(paths.root, "system/framework/$it").isFile }
            if (jars.isNotEmpty()) e["SYSTEMSERVERCLASSPATH"] = jars.joinToString(":") { "/system/framework/$it" }
        }
        e["HOME"] = "/data"; e["TMPDIR"] = "/data/local/tmp"; e["TZ"] = "UTC"
        e["AEMU64_ROOT"] = paths.root.absolutePath
        e["AEMU64_PROPS"] = paths.props.absolutePath
        e["AEMU64_BINDER"] = paths.binderSock.absolutePath
        if (kernel.treble) {
            e["AEMU64_HWBINDER"] = paths.hwbinderSock.absolutePath
            e["AEMU64_VNDBINDER"] = paths.vndbinderSock.absolutePath
        }
        e["AEMU64_INPUT"] = paths.inputSock.absolutePath
        
        
        e["AEMU64_PAD"] = File(paths.root, "dev/aemu_touchpad").absolutePath
        e["AEMU64_KBD"] = File(paths.root, "dev/aemu_keyboard").absolutePath
        
        
        BinderProtocol.saved(paths.bin)?.let { e[BinderProtocol.GUEST_ENV] = it.toString() }
        
        
        
        arch.n64Lib32.takeIf { it.isNotEmpty() }?.let { paths.nativeBin(it) }?.takeIf { it.canExecute() }?.let { e["AEMU64_QEMU"] = it.absolutePath }
        arch.n64Lib64?.let { paths.nativeBin(it) }?.takeIf { it.canExecute() }?.let { e["AEMU64_QEMU64"] = it.absolutePath }
        sdcardHost?.let { e["AEMU64_SDCARD"] = it.absolutePath }
        
        if (img.settings.gpu && kernel.gpuBridge32 != null && !arch.foreign) {
            e["DHD_GL"] = "/dev/socket/gl"
            if (!HostInfo.gpu().contains("adreno")) e["DHD_GL3"] = "0"
            e["AEMU_GL_RETARGET"] = "0"; e["AEMU_GL_CLIENT_ARRAYS"] = "0"      
        }
        e["AEMU64_FB"] = "${img.settings.width}x${img.settings.height}"
        if (net.netDbg > 0) e["AEMU64_NETDBG"] = net.netDbg.toString()
        if (net.netDns > 0) e["AEMU64_NETDNS"] = net.netDns.toString()
        
        
        
        
        if (VmSettings.kernelCustom(img.settings)) e["QEMU_UNAME"] = VmSettings.kernelRelease(img.settings)
        
        
        
        
        
        
        
        val sfTombstone = program == "/system/bin/surfaceflinger" && img.settings.sfTombstone
        if (File(paths.bin, "crash.report").exists() || (program == "/system/bin/surfaceflinger" && !sfTombstone)) e["AEMU64_CRASH"] = "1"
        for (t in userTokens.filter(::isEnvToken)) e[t.substringBefore('=')] = t.substringAfter('=')
        for ((k, v) in extra) when {
            k.startsWith("DHD_SOCK_") -> e["AEMU64_SOCK_" + k.removePrefix("DHD_SOCK_")] = v
            k == "DHD_UID" -> e["AEMU64_ID"] = v + "," + (extra["DHD_GID"] ?: v)
            k == "DHD_GID" -> {}
            k == "AEMU64_PROPS_FILE" -> e["AEMU64_PROPS"] = v
            else -> e[k] = v
        }
        
        
        
        
        
        
        
        
        val xattrPath = GuestPreload.xattrFor(program)
        
        
        
        
        
        
        
        
        
        
        
        val xattrTarget = !arch.foreign && (isPure32Tree(paths.root) || (has32BitSide(paths.root) && isGuestElf32(paths.root, program)))
        if (xattrTarget && File(paths.root, xattrPath.removePrefix("/")).isFile &&
            program?.substringAfterLast('/') != "installd") {
            val key = "DHD_ENV_LD_PRELOAD"
            val have = e[key].orEmpty().split(':').filter { it.isNotEmpty() && it != GuestPreload.XATTR && it != GuestPreload.XATTR_VENDOR }
            e[key] = (have + xattrPath).joinToString(":")
            
            
            
            val set = e["QEMU_SET_ENV"].orEmpty().split(',').filter { it.isNotEmpty() && !it.startsWith("LD_PRELOAD=") }
            e["QEMU_SET_ENV"] = (set + ("LD_PRELOAD=" + e.getValue(key))).joinToString(",")
        }
        e.putAll(derivedExports)
        return e
    }

    
    fun env(extra: Map<String, String> = emptyMap(), program: String? = null): Map<String, String> {
        if (engine == Engine.N64) return env64(extra, program)
        val s = img.settings
        val e = LinkedHashMap<String, String>()
        
        for ((k, v) in img.exports) if (k !in EXPORTS_SKIP) e[k] = v
        e["PATH"] = img.exports["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        e["DHD_ENV_LD_LIBRARY_PATH"] = (img.exports["LD_LIBRARY_PATH"] ?: "/vendor/lib:/system/lib").let { lp ->
            
            
            if (lp.split(':').contains("/system/lib")) lp else "$lp:/system/lib"
        }
        
        
        
        
        val guard = guardArmed && !arch.foreign && !GuestPreload.isGlibcFirmware(paths.root) &&
            File(paths.root, GuestPreload.GUARD.removePrefix("/")).isFile
        e["DHD_ENV_LD_PRELOAD"] = GuestPreload.value(GuestPreload.isGlibcFirmware(paths.root),
            xattr = isPure32Tree(paths.root) && File(paths.root, GuestPreload.xattrFor(program).removePrefix("/")).isFile,
            xattrPath = GuestPreload.xattrFor(program), guard = guard)
        if (guard) {
            e[BinderGuard.ENV] = "1"
            e[BinderGuard.ENV_LOG] = File(paths.bin, BinderGuard.LOG).absolutePath
        }
        e["ANDROID_ROOT"] = img.exports["ANDROID_ROOT"] ?: "/system"
        e["ANDROID_DATA"] = img.exports["ANDROID_DATA"] ?: "/data"
        e["ANDROID_ASSETS"] = img.exports["ANDROID_ASSETS"] ?: "/system/app"
        e["EXTERNAL_STORAGE"] = img.exports["EXTERNAL_STORAGE"] ?: img.sdcardPath
        if (jamVm) e[LegacyFirmware.VM_ENV] = LegacyFirmware.VM_JAM
        e["ASEC_MOUNTPOINT"] = img.exports["ASEC_MOUNTPOINT"] ?: "/mnt/asec"
        e["LOOP_MOUNTPOINT"] = img.exports["LOOP_MOUNTPOINT"] ?: "/mnt/obb"
        
        e["BOOTCLASSPATH"] = BootClasspathRepair.repair(paths.root, img.bootclasspath).bootclasspath
        
        
        
        
        val ext = img.exports["EXTERNAL_STORAGE"]
        val physicalOnly = img.api >= 21 && ext != null && !ext.startsWith("/storage/emulated")
        if (img.api >= 17 && !physicalOnly) {
            e.putIfAbsent("EMULATED_STORAGE_SOURCE", "/mnt/shell/emulated")
            e.putIfAbsent("EMULATED_STORAGE_TARGET", "/storage/emulated")
        }
        
        e["AEMU_RO_SYSTEM"] = "1"
        
        e["AEMU_RAM_KB"] = (guestRamMb(img.api, s.ramMb) * 1024L).toString()
        e["HOME"] = "/data"
        e["TMPDIR"] = "/data/local/tmp"
        e["ASHMEM_SHIM_DIR"] = "/data/local/tmp"
        e["TZ"] = "UTC"
        
        if (VmSettings.kernelCustom(img.settings)) e["QEMU_UNAME"] = VmSettings.kernelRelease(img.settings)
        e["DHD_FB_W"] = s.width.toString()
        e["DHD_FB_H"] = s.height.toString()
        e["DHD_IN_W"] = s.width.toString()
        e["DHD_IN_H"] = s.height.toString()
        e["DHD_FB_HZ"] = s.fbHz.toString()
        e["DHD_OWNERS"] = paths.owners.absolutePath
        e["DHD_NET_LOG"] = File(paths.bin, "net.log").absolutePath
        e["DHD_ASHMEM_LOG"] = File(paths.bin, "ashmem.log").absolutePath
        e["DHD_BINDER"] = paths.binderSock.absolutePath
        e["DHD_BINDER_LOG"] = File(paths.bin, "binder-warn.log").absolutePath
        e["DHD_CREDS"] = paths.creds.absolutePath
        e["DHD_INPUT"] = paths.inputSock.absolutePath
        if (singleTouch) e["DHD_INPUT_ST"] = "1"
        if (binderVerbose) e["DHD_BINDER_VERBOSE"] = "1"
        sdcardHost?.let { e["DHD_SDCARD"] = it.absolutePath }
        if (net.http80 > 0) e["DHD_HTTP80"] = net.http80.toString()
        if (net.https443 > 0) e["DHD_HTTPS443"] = net.https443.toString()
        if (net.dns53 > 0) {
            e["DHD_DNS53"] = net.dns53.toString()
            e["DHD_ENV_ANDROID_DNS_MODE"] = "local"
        }
        net.glPath?.let { e["DHD_GL"] = it }
        
        
        if (img.api < 19 || !HostInfo.gpu().contains("adreno")) e["DHD_GL3"] = "0"
        
        if (img.api >= 18) { e["AEMU_GL_RETARGET"] = "0"; e["AEMU_GL_CLIENT_ARRAYS"] = "0" }
        if (tbFlush > 0) e["DHD_TBFLUSH"] = tbFlush.toString()
        if (noSmc && arch.supportsNoSmc) e["DHD_NO_SMC"] = "1"
        if (noTcgOpt) e["DHD_NO_TCGOPT"] = "1"
        for (t in userTokens.filter(::isEnvToken)) e[t.substringBefore('=')] = t.substringAfter('=')
        e.putAll(extra)
        if (arch != GuestArch.ARM) archFixups(e)
        return e
    }

    
    private fun archFixups(e: MutableMap<String, String>) {
        if (!arch.supportsNoSmc) { e.remove("DHD_NO_SMC"); e.remove("GUEST_NO_SMC") }
        
        
        
        
        
        
        for ((k, v) in e.entries.toList()) {
            if (k.startsWith("DHD_") && !k.startsWith("DHD_ENV_") && !k.startsWith("DHD_SOCK_")) {
                e.putIfAbsent("GUEST_" + k.removePrefix("DHD_"), v)
            }
        }
        for (key in listOf("DHD_ENV_LD_PRELOAD", "LD_PRELOAD")) {
            val v = e[key] ?: continue
            val kept = v.split(':').filter { it.isNotEmpty() && GuestArch.isOfArch(File(paths.root, it.removePrefix("/")), arch) }
            if (kept.isEmpty()) e.remove(key) else e[key] = kept.joinToString(":")
        }
    }

    
    fun run(argv: List<String>, timeoutMs: Long = 120_000, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        if (!qemu.canExecute()) return -1 to "no qemu"
        val pb = ProcessBuilder(cmdline(argv)).directory(paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env(extra))
        val p = pb.start()
        val sb = StringBuilder()
        val t = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { if (sb.length < 256_000) sb.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        t.join(2000)
        val code = runCatching { p.exitValue() }.getOrDefault(-9)
        return code to sb.toString()
    }

    companion object {
        
        fun shellPath(path: String?): String {
            val rest = (path ?: "").split(':').filter { it.isNotEmpty() && it != "/system/bin" && it != "/system/xbin" }
            return (listOf("/system/bin", "/system/xbin") + rest).joinToString(":")
        }

        
        fun scriptFns(root: File): String {
            val sb = StringBuilder()
            for (n in listOf("am", "pm", "input", "monkey", "svc", "ime", "bmgr", "wm", "content", "settings")) {
                val f = File(root, "system/bin/$n")
                if (!f.isFile) continue
                val head = runCatching { f.inputStream().use { s -> ByteArray(4).also { b -> s.read(b) } } }.getOrNull() ?: continue
                if (head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte()) continue
                sb.append(n).append("() { /system/bin/sh /system/bin/").append(n).append(" \"\$@\"; }; ")
            }
            return sb.toString()
        }

        private val EXPORTS_SKIP = setOf("PATH", "LD_LIBRARY_PATH", "BOOTCLASSPATH", "LD_PRELOAD")

        
        fun guestRamMb(api: Int, chosen: Int): Int {
            
            val picked = RamChoice.resolve(chosen)
            if (picked > 0) return picked
            val typical = when { api <= 10 -> 512; api <= 15 -> 1024; api <= 19 -> 2048; api <= 23 -> 3072; else -> 4096 }
            val hostMb = runCatching {
                java.io.File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemTotal:") } }.split(Regex("\\s+"))[1].toLong() / 1024
            }.getOrDefault(8192L)
            return typical.coerceAtMost((hostMb / 2).toInt())
        }
    }
}
