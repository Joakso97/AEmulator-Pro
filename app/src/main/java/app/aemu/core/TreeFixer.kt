
package app.aemu.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile


class TreeFixer(
    private val ctx: Context,
    private val paths: VmPaths,
    private val img: GuestImage,
    private val log: (String) -> Unit,
) {
    private val root get() = paths.root
    private val engine get() = img.engine
    private val kernel get() = Kernel64.forApi(img.api)

    
    private val gpuOn: Boolean by lazy { img.settings.gpu && !(img.arch == GuestArch.ARM && BroadcomGpu.v3dFirmware(root)) }

    
    private val mPlus: Boolean by lazy { BionicProbe.isMarshmallowPlus(root, img.api) }

    

    
    private fun isGraphicsHidlImpl(name: String) =
        Regex("^android\\.hardware\\.graphics\\.(allocator|composer)@[0-9.]+-impl\\.so$").matches(name)

    
    private fun isHidlImpl(name: String) =
        Regex("^(android\\.(hardware|hidl)|vendor\\.google)\\.[A-Za-z0-9_.]+@[0-9.]+-impl[A-Za-z0-9_.-]*\\.so$").matches(name)

    fun sanitize() {
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var n = 0
        fun park(f: File) {
            if (!f.exists() && !isLink(f)) return
            val rel = f.relativeTo(root).path.replace('/', '#')
            val dst = File(parked, rel)
            dst.delete()
            if (f.renameTo(dst)) n++
        }
        
        val keepClasses = setOf("audio_policy", "local_time", "power", "keystore", "consumerir", "vibrator", "memtrack")
        val parkClasses = setOf(
            "gralloc", "hwcomposer", "copybit", "overlay", "sensors", "camera", "gps", "nfc", "nfc_nci",
            "lights", "audio", "fm", "bluetooth", "hdmi", "tv", "display", "ir", "wlan", "gpu", "fingerprint",
        )
        for (dir in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                val name = f.name
                if (!name.endsWith(".so")) return@forEach
                val cls = name.substringBefore('.')
                val variant = name.removeSuffix(".so").substringAfter('.', "")
                val isDefault = variant == "default" || variant == "goldfish"
                when {
                    
                    
                    
                    
                    
                    
                    img.arch.foreign && (isHidlImpl(name) || cls == "audio") -> {}
                    
                    
                    name.contains('@') && dir == "system/lib/hw" -> {}
                    
                    
                    
                    
                    
                    
                    isPure32Tree(root) && (isGraphicsHidlImpl(name) || isHidlImpl(name)) -> {}
                    
                    
                    
                    
                    isPure32Tree(root) && (cls == "memtrack" || cls == "bootctrl") -> {}
                    
                    cls == "gralloc" && isDefault -> {}
                    
                    cls == "gralloc" || (cls == "audio" && variant == "primary.default") -> park(f)
                    cls == "audio" && (variant.startsWith("a2dp") || variant.startsWith("usb") || variant.startsWith("r_submix")) -> {}
                    
                    
                    
                    cls == "peripheral_io" -> {}
                    cls in keepClasses && isDefault -> {}
                    cls == "audio_policy" -> if (!isDefault) park(f)
                    cls in parkClasses -> park(f)
                    !isDefault && cls !in keepClasses -> park(f)
                    !isDefault -> park(f)
                }
            }
        }
        
        for (dir in listOf("system/lib/egl", "vendor/lib/egl", "system/vendor/lib/egl")) {
            File(root, dir).listFiles()?.forEach { f ->
                val nm = f.name
                val vendorGl = (nm.startsWith("libEGL_") || nm.startsWith("libGLESv1_CM_") || nm.startsWith("libGLESv2_") ||
                    nm.startsWith("libGLESv3_") || (nm.startsWith("libGLES_") && nm != "libGLES_android.so" && nm != X86SoftwareGles.ALIAS) ||
                    nm.startsWith("libq3dtools") || nm.startsWith("eglsubAndroid") || nm.startsWith("libRBEGL") || nm.startsWith("libRBGLES"))
                if (vendorGl) park(f)
            }
        }
        
        for (rel in listOf("system/lib/libstagefrighthw.so", "vendor/lib/libstagefrighthw.so", "system/vendor/lib/libstagefrighthw.so")) park(File(root, rel))
        
        
        
        
        
        for (rel in listOf("system/lib/libavenhancements.so", "vendor/lib/libavenhancements.so", "system/vendor/lib/libavenhancements.so")) park(File(root, rel))
        
        File(root, "dev/binder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/cpuctl").takeIf { it.exists() }?.let { wipe(it) }
        log("firmware prep: vendor modules set aside: $n")
    }

    

    
    
    private fun keepOatCopies() = runCatching {
        val sys = File(root, "system")
        val store = File(sys, ".aemu-oat")
        fun walk(d: File, out: MutableList<File>) {
            d.listFiles()?.forEach { f -> if (f.isDirectory) walk(f, out) else if (f.isFile) out.add(f) }
        }
        fun open(d: File) {
            d.listFiles()?.forEach { if (it.isDirectory) open(it) }
            d.setWritable(true, false)
        }
        fun link(from: File, to: File): Boolean {
            to.parentFile?.mkdirs()
            if (runCatching { Os.link(from.absolutePath, to.absolutePath) }.isSuccess) return true
            return runCatching { from.copyTo(to, overwrite = true) }.isSuccess
        }
        var saved = 0
        var back = 0
        val fw = File(sys, "framework")
        for (name in listOf("oat", "arm", "arm64")) {
            val live = File(fw, name)
            val keep = File(store, "framework/$name")
            if (!live.isDirectory && !keep.isDirectory) continue
            if (live.isDirectory) open(live) else live.mkdirs()
            
            val kept = ArrayList<File>()
            walk(keep, kept)
            for (k in kept) {
                val f = File(live, k.relativeTo(keep).path)
                if (f.exists()) continue
                f.parentFile?.mkdirs()
                if (link(k, f)) { f.setReadable(true, false); back++ }
            }
            
            val now = ArrayList<File>()
            walk(live, now)
            for (f in now) {
                val k = File(keep, f.relativeTo(live).path)
                if (k.isFile && k.length() == f.length()) continue
                k.delete()
                if (link(f, k)) saved++
            }
        }
        if (back > 0) log("framework oat: $back files of the firmware's precompiled code were gone and are put back from system/.aemu-oat")
        if (saved > 0) log("framework oat: $saved files kept in system/.aemu-oat")
    }.onFailure { log("framework oat: copies not kept: ${it.message}") }

    private fun protectOat() {
        keepOatCopies()
        val sys = File(root, "system")
        var n = 0
        fun lock(d: File) {
            d.listFiles()?.forEach { f -> if (f.isFile) { if (f.canWrite()) { f.setWritable(false, false); n++ } } else if (f.isDirectory) lock(f) }
            d.setWritable(false, false)
        }
        File(sys, "framework").listFiles()?.filter { it.isDirectory && (it.name == "oat" || it.name == "arm" || it.name == "arm64" || it.name == "x86") }?.forEach { lock(it) }
        for (top in listOf("app", "priv-app")) File(sys, top).listFiles()?.forEach { app ->
            File(app, "oat").takeIf { it.isDirectory }?.let { lock(it) }
        }
        if (n > 0) log("oat files write-protected: $n")
    }

    fun fixup(owners: Boolean = true) {
        if (owners) seedOwners()
        installEngineFiles()
        installSensorHal()
        File(root, "system/framework/aemu-setup.jar").let { dst ->
            dst.parentFile?.mkdirs()
            eaOpen("engines/common/aemu-setup.jar").use { src -> dst.outputStream().use { src.copyTo(it) } }
            dst.setReadable(true, false)
        }
        installCameraHal()
        protectOat()
        fixThemeXml()
        parkWhetstone()
        parkPlayReady()
        yamahaCodecGuard()
        mediaserverCameraSize()
        disableTegraRmInit()
        parkQcomServices()
        swapMtkAudioHal()
        
        BootClasspathRepair.forget(root)
        runCatching { log(BootClasspathRepair.repair(root, img.bootclasspath).report()) }
        
        
        
        
        
        
        val gpuForArch = img.arch == GuestArch.ARM || EngineAssets.exists(ctx, "engines/${Engine.KK.id}${img.arch.assetSuffix}/libGLES.so") ||
            (img.engine == Engine.N64 && img.arch == GuestArch.X86_64 && EngineAssets.exists(ctx, EngineAssets.X86_64_GPU_BRIDGE))
        eglConfig(gpuOn && gpuForArch && !GuestPreload.isGlibcFirmware(root))
        makeDataDirs()
        fixDataRootAccess()
        runCatching {
            if (GuestRootAliases.ensureEtc(root)) log("init: restored /etc -> /system/etc alias")
        }.onFailure { log("init: could not prepare /etc alias: ${it.message}") }
        miuiSdkFiles()
        parkRustRuntimeConfig()
        makeUserZeroLink()
        makeDevNodes()
        rootLinks()
        fixApex()
        scudoStub()
        guestOverlay()
        bpfMapNotes()
        aconfigStorage()
        kernelPolicyUnread()
        makeSysNodes()
        ThermalSysfs.apply(root, img.settings, log)
        makeProcMounts()
        vendorAudioPolicy()
        resolvConf()
        caCerts()
        audioPolicy()
        audioOpenSlot()
        seedSamsungCsc()
        qemuAudioPolicy()
        qtaguid()
        if (engine == Engine.N64 && kernel.webView64) for (dir in listOf("product/app", "system/app", "system/product/app", "system_ext/app"))
            File(root, dir).listFiles().orEmpty().filter { it.isDirectory && it.name.contains("webview", true) }
                .flatMap { it.listFiles().orEmpty().filter { f -> f.isFile && f.name.endsWith(".apk") } }
                .forEach { apk ->
                    
                    val undo = File(root, "system/.aemu-parked/" + apk.relativeTo(root).path.replace('/', '#') + WebView64.UNDO_SUFFIX)
                    WebView64.prefer64(apk, log, undo)
                }
        vendorChecks()
        flattenedLinks()
        shellLink()
        pldHints()
        berlinOsalGuard()
        intelCeShims()
        hdmiRxGuard()
        appProcessLink()
        scriptShebangs()
        samsungEfs()
        selinuxOff()
        surfaceGuard()
        surfaceFlingerGuard()
        framebufferComposer()
        btiGuard()
        executableCodeCache()
        mspaceHooks()
        glassGestureGuard()
        glassBluetoothPark()
        disableStallingPackages()
        glassLocationProvider()
        seedDefaultIme()
        lockWearRotation()
        keyLayout()
        for (n in LOGS) File(root, "dev/log/$n").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.createNewFile() } }
        makeFb()
        
        
        if (mPlus) {
            val n = LibcVersions.relaxEngineLibs(root)
            if (n > 0) log("libc: version tags removed from $n engine librar${if (n == 1) "y" else "ies"} (libc.so defines the symbols unversioned)")
        }
    }

    
    
    private fun appProcessLink() {
        if (AppProcess.ensureLink(root)) log("app_process: only app_process32 present, linked system/bin/app_process -> app_process32")
    }

    private fun scriptShebangs() {
        val ap = AppProcess.path(root)
        var fixed = 0
        val failed = ArrayList<String>()
        
        
        val files = ArrayList<File>()
        for (dir in listOf("system/bin", "system/xbin")) File(root, dir).listFiles()?.let { files += it }
        File(root, "system/etc").walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".sh") }.forEach { files += it }
        run {
            for (f in files) {
                if (!f.isFile || f.length() > 64_000 || java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val head = runCatching { f.inputStream().use { s -> ByteArray(2).also { s.read(it) } } }.getOrNull() ?: continue
                
                
                
                if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                    runCatching {
                        val t = f.readText(Charsets.ISO_8859_1)
                        val u = AppProcess.fixExec(t, ap)
                        if (u != t) { f.writeText(u, Charsets.ISO_8859_1); fixed++ }
                    }
                    continue
                }
                if (head[0] != '#'.code.toByte()) continue
                
                
                val ok = runCatching {
                    val body = ("#!/system/bin/sh\n" + AppProcess.fixExec(String(f.readBytes(), Charsets.ISO_8859_1), ap)).toByteArray(Charsets.ISO_8859_1)
                    val exec = f.canExecute()
                    try { f.writeBytes(body) } catch (_: Exception) {
                        f.setWritable(true, true)
                        val tmp = File(f.parentFile, f.name + ".aemu-tmp")
                        tmp.writeBytes(body)
                        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); error("cannot replace") } }
                    }
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                    if (exec) f.setExecutable(true, false)
                }.isSuccess
                if (ok) fixed++ else failed += f.name
            }
        }
        if (fixed > 0) log("scripts: #! added to $fixed shell script(s) and \"exec app_process\" made absolute (system/bin, system/xbin, system/etc/*.sh)")
        if (failed.isNotEmpty()) log("scripts: could not add #! to ${failed.joinToString()}")
    }

    
    private fun samsungEfs() {
        val efs = File(root, "efs")
        if (!efs.isDirectory) return
        val dir = File(efs, "FactoryApp").apply { mkdirs() }
        for (n in listOf("factorymode", "keystr")) {
            val f = File(dir, n)
            if (runCatching { f.readText().trim() }.getOrNull() != "ON") runCatching { f.writeText("ON") }
        }
    }

    private fun qcomAudioFlinger(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && String(af.readBytes(), Charsets.ISO_8859_1).contains("setFmVolume")
    }.getOrDefault(false)

    
    private fun vendorAudioPolicy() {
        
        
        
        
        
        
        val samsungJb = img.api in 16..17 && (img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true))
        if (img.api >= 16 && !samsungJb) return
        runCatching {
            val af = File(root, "system/lib/libaudioflinger.so")
            val marker = "getParamFromPolicy".toByteArray(Charsets.ISO_8859_1)
            fun has(b: ByteArray): Boolean {
                outer@ for (i in 0..b.size - marker.size) {
                    for (k in marker.indices) if (b[i + k] != marker[k]) continue@outer
                    return true
                }
                return false
            }
            if (!samsungJb && (!af.isFile || !has(af.readBytes()))) return
            val parkedDir = File(root, "system/.aemu-parked")
            val hw = File(root, "system/lib/hw")
            val candidates = (parkedDir.listFiles()?.filter { it.name.startsWith("system#lib#hw#audio_policy.") && it.name.endsWith(".so") && !it.name.endsWith("#audio_policy.default.so") } ?: emptyList()) +
                (hw.listFiles()?.filter { it.name.startsWith("audio_policy.") && it.name.endsWith(".so") && it.name != "audio_policy.default.so" } ?: emptyList())
            val vendor = candidates.firstOrNull { has(it.readBytes()) } ?: return
            val dst = File(hw, "audio_policy.default.so")
            val bytes = vendor.readBytes()
            if (dst.isFile && dst.length() == bytes.size.toLong() && dst.readBytes().contentEquals(bytes)) return
            val keep = File(parkedDir, "audio_policy.default.so.generic")
            if (!keep.isFile && dst.isFile) { parkedDir.mkdirs(); dst.copyTo(keep) }
            dst.writeBytes(bytes)
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
            if (samsungJb) { 
                for (n in listOf("system#lib#hw#audio_policy.default.so", "audio-aosp-failed", "audio-rom-wrap", "audio-rom-failed", "audio_policy.aosp-bad"))
                    File(parkedDir, n).delete()
                for (n in listOf("libaemu_apaosp.so", "libaemu_approm.so")) File(root, "system/lib/$n").delete()
            }
            log("audio: Samsung audio policy (${vendor.name.substringAfterLast('#')}) installed as the default policy")
        }.onFailure { log("audio: Samsung audio policy install failed: ${it.message}") }
    }

    
    private fun seedSamsungCsc() {
        runCatching {
            val samsung = isSamsungFirmware()
            val f = File(root, "system/csc/customer.xml")
            if (!samsung || f.exists() || !File(root, "system/app").isDirectory) return
            f.parentFile?.mkdirs()
            f.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CustomerData>\n  <GeneralInfo>\n  </GeneralInfo>\n  <Settings>\n  </Settings>\n</CustomerData>\n")
            f.setReadable(true, false)
            f.parentFile?.setReadable(true, false); f.parentFile?.setExecutable(true, false)
            log("csc: wrote an empty /system/csc/customer.xml (Samsung Messaging needs the file)")
        }.onFailure { log("csc: cannot write customer.xml: ${it.message}") }
    }

    
    private fun audioOpenSlot() {
        val f = File(root, "aemu.audio.slot")
        runCatching {
            val samsung = isSamsungFirmware()
            val af = File(root, "system/lib/libaudioflinger.so")
            val slot = if (img.api >= 16 && samsung && af.isFile) AudioHalAbi.openOutputSlot(af.readBytes()) else null
            if (slot != null && slot > 108 && slot <= 172 && slot % 4 == 0) {
                if (!f.isFile || f.readText().trim() != slot.toString()) f.writeText("$slot\n")
                f.setReadable(true, false)
                log("audio: AudioFlinger opens the output at device slot $slot (AOSP 108), HAL layout shifted")
            } else {
                if (f.exists()) f.delete()
                if (img.api >= 16 && samsung) log("audio: AudioFlinger open_output_stream slot: ${slot ?: "unknown"}")
            }
        }.onFailure { log("audio: cannot write aemu.audio.slot: ${it.message}") }
    }

    
    private fun qemuAudioPolicy() = runCatching {
        val lib = File(root, "system/lib/libaudioflinger.so")
        if (!lib.isFile) return@runCatching
        val keep = File(root, "system/.aemu-parked/system#lib#libaudioflinger.so")
        val d = lib.readBytes()
        if (ElfPatch.policyBranchOffsets(d).isEmpty()) { 
            log("audio: libaudioflinger policy branch not found (already patched or different build)")
            return@runCatching
        }
        backupOnce(lib, keep)
        if (ElfPatch.policyManagerAlways(lib) > 0) log("audio: AudioPolicyService creates its policy in emulator mode (libaudioflinger patched)")
        else log("audio: libaudioflinger policy patch was not written")
    }.onFailure { log("audio: libaudioflinger policy patch failed: ${it.message}") }

    
    private fun expectedHalVersion(): Int? = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        if (af.isFile) AudioHalAbi.expectedDeviceVersion(af.readBytes()) else null
    }.getOrNull()

    
    private fun isSamsungFirmware(): Boolean {
        if (img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)) return true
        if (img.brand.contains("samsung", true) || img.name.contains("samsung", true)) return true
        if (Regex("^(GT|SM|SGH|SCH|SHV|SPH|SHW|YP)-", RegexOption.IGNORE_CASE).containsMatchIn(img.model.trim())) return true
        return runCatching {
            File(root, "system/build.prop").readText().lowercase().let {
                it.contains("ro.product.manufacturer=samsung") || it.contains("ro.product.brand=samsung")
            }
        }.getOrDefault(false)
    }

    private fun samsungDeviceSlots(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.openOutputSlot(af.readBytes()) == 116
    }.getOrDefault(false)

    private fun openOutputSlotIs(slot: Int): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.openOutputSlot(af.readBytes()) == slot
    }.getOrDefault(false)

    private fun afHasFmVolumeSlot(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.hasFmVolumeAfterMasterVolume(af.readBytes())
    }.getOrDefault(false)

    private fun directTrackAudio(): Boolean = img.api in 19..20 && runCatching {
        AudioHalAbi.usesDirectTrackTail(File(root, "system/lib/libnbaio.so").readBytes())
    }.getOrDefault(false)

    
    private fun swapMtkAudioHal() {
        swapMtkAudioHalImpl()
        redirectMtkAudioDeps()
    }

    
    private fun redirectMtkAudioDeps() = runCatching {
        if (!File(root, "system/lib/libaudio.mtk.so").isFile) return@runCatching
        var n = 0
        for (dir in listOf("system/lib", "system/lib/hw", "vendor/lib", "vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so") && f.name != "libaudioflinger.so" && f.name != "libaudio.mtk.so" &&
                    f.name != "libaudio.primary.default.so" && !isLink(f))
                    n += ElfPatch.redirectNeeded(f, "libaudio.primary.default.so", "/system/lib/libaudio.mtk.so")
            }
        }
        if (n > 0) log("audio: $n MediaTek librar${if (n == 1) "y" else "ies"} linked to the original HAL (libaudio.mtk.so)")
    }.onFailure { log("audio: HAL link redirect failed: ${it.message}") }

    private fun swapMtkAudioHalImpl() {
        if (engine != Engine.KK || img.api < 17) return
        val lib = File(root, "system/lib/libaudio.primary.default.so")
        val orig = File(root, "system/lib/libaudio.mtk.so")
        if (!orig.isFile) {
            if (!lib.isFile || !isMtkAudio(root)) return
            if (!lib.renameTo(orig)) return
        }
        val asset = "engines/kk/audio.primary.mtk.so"
        runCatching {
            
            
            
            val raw = eaOpen(asset).use { it.readBytes() }
            val hal = HalModuleId.retarget(raw, MTK_HAL_MODULE_ID)
            if (hal == null) log("audio: cannot set the HAL module id to $MTK_HAL_MODULE_ID, AudioFlinger will refuse it")
            val bytes = hal ?: raw
            if (lib.isFile && lib.length() == bytes.size.toLong() && lib.readBytes().contentEquals(bytes)) return
            lib.outputStream().use { it.write(bytes) }
            lib.setReadable(true, false)
            log("audio: MediaTek HAL replaced with the emulator HAL (module id $MTK_HAL_MODULE_ID)")
        }.onFailure { log("audio: MediaTek HAL swap failed: ${it.message}") }
    }

    
    private fun fixThemeXml() {
        val f = File(root, "system/media/theme/theme_compatibility.xml")
        if (!f.isFile || f.length() > 4_000_000) return
        runCatching {
            val s = f.readText()
            if (!s.contains("<!-- <!--")) return
            val out = StringBuilder(s.length)
            var i = 0
            while (true) {
                val a = s.indexOf("<!--", i)
                if (a < 0) { out.append(s, i, s.length); break }
                out.append(s, i, a + 4)
                val e = s.indexOf("-->", a + 4)
                if (e < 0) { out.append(s, a + 4, s.length); break }
                out.append(s.substring(a + 4, e).replace("--", "- -")).append("-->")
                i = e + 3
            }
            f.writeText(out.toString())
            log("theme_compatibility.xml: nested comments fixed")
        }
    }

    
    private fun parkWhetstone() {
        val apk = File(root, "system/app/Whetstone.apk")
        if (!apk.isFile || !File(root, "system/app/miuisystem.apk").isFile) return
        runCatching {
            val dir = File(root, "system/.aemu-parked").apply { mkdirs() }
            if (apk.renameTo(File(dir, "Whetstone.apk"))) {
                File(root, "data/dalvik-cache/system@app@Whetstone.apk@classes.dex").delete()
                log("MIUI: Whetstone package set aside (deadlock with the activity manager)")
            }
        }
    }

    
    private fun parkPlayReady() = runCatching {
        val plugin = File(root, "system/lib/drm/libplayreadyplugin.so")
        if (!plugin.isFile && !isLink(plugin)) return@runCatching
        val parked = File(root, "system/.aemu-parked/system#lib#drm#libplayreadyplugin.so")
        parked.parentFile?.mkdirs()
        val moved = if (isLink(plugin) || parked.exists()) plugin.delete() else plugin.renameTo(parked)
        if (moved) log("DRM: PlayReady plugin set aside (drmserver crashed in it)")
    }.onFailure { log("DRM: could not set aside the PlayReady plugin: ${it.message}") }

    
    private fun yamahaCodecGuard() = runCatching {
        val funcs = setOf(
            "_ZN7android6yamaha5media13AlsaHwdepCtrl4openEv",
            "_ZN7android6yamaha5media13AlsaHwdepCtrl5closeEv",
            "_ZN7android6yamaha5media13AlsaHwdepCtrl11sendCommandEP10_snd_hwdepmPvm",
            "_ZN7android6yamaha5media13AlsaHwdepCtrl13notifyCommandEP10_snd_hwdepmPvm",
            "_ZN7android6yamaha5media13AlsaMixerCtrl4openEPKc",
            "_ZN7android6yamaha5media13AlsaMixerCtrl5closeEv",
            "_ZN7android6yamaha5media13AlsaMixerCtrl9setVolumeEPKciPKNS2_7_VOLUMEE",
            "_ZN7android6yamaha5media13AlsaMixerCtrl9setSwitchEPKciPKNS2_7_SWITCHE",
            "_ZN7android6yamaha5media13AlsaMixerCtrl9find_elemEPKc",
        )
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            val lib = File(root, "$dir/libmediayamahaservice.so")
            if (!lib.isFile || isLink(lib)) continue
            
            val have = ElfPatch.symbols(lib, funcs)?.keys ?: continue
            if (have.size != funcs.size) { log("Yamaha: ${lib.name} lacks ${funcs.size - have.size} of the ${funcs.size} ALSA wrapper functions, left as is"); continue }
            backupOnce(lib, File(root, "system/.aemu-parked/${dir.replace('/', '#')}#${lib.name}"))
            lib.setWritable(true, true)
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("Yamaha: ${lib.name} codec access (ALSA hwdep/mixer) made a no-op, the service initialises without the chip ($n func.)")
        }
    }.onFailure { log("Yamaha: codec guard failed: ${it.message}") }

    
    private fun mediaserverCameraSize() = runCatching {
        val ms = File(root, "system/bin/mediaserver")
        val cam = File(root, "system/lib/libcameraservice.so")
        if (!ms.isFile || isLink(ms) || !cam.isFile || isLink(cam)) return@runCatching
        val d = ms.readBytes()
        if (MediaserverCameraSizePatch.apply(d, cam.readBytes()) == MediaserverCameraSizePatch.PATCHED) {
            backupOnce(ms, File(root, "system/.aemu-parked/system#bin#mediaserver"))
            ms.setWritable(true, true)
            ms.writeBytes(d)
            log("mediaserver: CameraService allocation raised from 0x58 to 0x60 bytes to match libcameraservice.so (heap overrun, SIGBUS at start)")
        }
    }.onFailure { log("mediaserver: CameraService size patch failed: ${it.message}") }

    
    private fun disableTegraRmInit() = runCatching {
        var patched = 0
        for (dir in listOf("vendor/lib64", "vendor/lib", "system/vendor/lib64", "system/vendor/lib", "system/lib64", "system/lib")) {
            val f = File(root, "$dir/libnvtvmr.so")
            if (!f.isFile || File(f.parentFile, "libnvrm.so").let { !it.isFile && !isLink(it) }) continue
            val real = f.canonicalFile
            if (!real.path.startsWith(root.canonicalPath + "/")) continue
            val stock = File(root, "system/.aemu-parked/" + real.relativeTo(root.canonicalFile).path.replace('/', '#') + ".stock")
            if (!stock.exists()) { stock.parentFile?.mkdirs(); runCatching { real.copyTo(stock) } }
            if (ElfPatch.disableInit(real) == 1) { patched++; log("NVIDIA: ${real.relativeTo(root.canonicalFile).path}: constructor switched off (libnvrm has no Tegra chip here)") }
        }
    }.onFailure { log("NVIDIA: could not switch off the libnvtvmr constructor: ${it.message}") }

    
    private fun keyLayout() {
        if (LegacyFirmware.isPreRelease(img.release)) {
            
            
            
            
            File(root, "system/usr/keylayout/dhd-touch.kl").delete()
            return
        }
        val f = File(root, "system/usr/keylayout/dhd-touch.kl")
        val vf = File(root, "vendor/usr/keylayout/dhd-touch.kl")
        val text = buildString {
            append("# AEmulator: navigation bar and hardware keys of the emulator\n")
            append("key ${InputService.KEY_HOME} HOME WAKE\n")
            append("key 172 HOME WAKE\n")
            append("key ${InputService.KEY_BACK} BACK WAKE_DROPPED\n")
            append("key ${InputService.KEY_MENU} MENU WAKE_DROPPED\n")
            append("key ${InputService.KEY_SEARCH} SEARCH WAKE_DROPPED\n")
            append("key ${InputService.KEY_POWER} POWER WAKE\n")
            append("key ${InputService.KEY_VOLUMEDOWN} VOLUME_DOWN WAKE\n")
            append("key ${InputService.KEY_VOLUMEUP} VOLUME_UP WAKE\n")
            append("key 113 VOLUME_MUTE\n")
            append("key 212 CAMERA\n")
            append("key 103 DPAD_UP\nkey 108 DPAD_DOWN\nkey 105 DPAD_LEFT\nkey 106 DPAD_RIGHT\nkey 232 DPAD_CENTER\n")
            
            append("key 2 1\nkey 3 2\nkey 4 3\nkey 5 4\nkey 6 5\nkey 7 6\nkey 8 7\nkey 9 8\nkey 10 9\nkey 11 0\n")
            append("key ${VirtualKeys.STAR} STAR\nkey ${VirtualKeys.POUND} POUND\nkey ${VirtualKeys.SEND} CALL\nkey ${VirtualKeys.ENDCALL} ENDCALL\n")
            
            if (img.api >= 11) {
                append("key ${InputService.KEY_APPSELECT} APP_SWITCH\n")
                append("key 254 APP_SWITCH\n")
                append("key 187 APP_SWITCH\n")
            }
        }.let {
            
            if (img.api >= 21) it.replace(" WAKE_DROPPED", "").replace(" WAKE", "") else it
        }
        val targets = listOf(
            f, vf,
            File(root, "system/usr/keylayout/Vendor_18d1_Product_0001.kl"),
            File(root, "system/usr/keylayout/AEmulator_Touchscreen.kl")
        )
        for (target in targets) {
            if (target == vf && !File(root, "vendor/usr/keylayout").isDirectory) continue
            if (runCatching { target.readText() }.getOrNull() != text) {
                runCatching { target.parentFile?.mkdirs(); target.writeText(text); target.setReadable(true, false) }
                    .onFailure { log("key layout (${target.name}): ${it.message}") }
            }
        }
        
        for (dir in listOf(File(root, "system/usr/keylayout"), File(root, "vendor/usr/keylayout"))) {
            if (!dir.isDirectory) continue
            for (name in listOf("Generic.kl", "qwerty.kl")) {
                val kl = File(dir, name)
                if (kl.isFile) runCatching {
                    var s = kl.readText()
                    var changed = false
                    if (s.contains("key 102   MOVE_HOME")) {
                        s = s.replace("key 102   MOVE_HOME", "key 102   HOME")
                        changed = true
                    } else if (s.contains("key 102 MOVE_HOME")) {
                        s = s.replace("key 102 MOVE_HOME", "key 102 HOME")
                        changed = true
                    }
                    if (!s.contains("APP_SWITCH") && img.api >= 11) {
                        s += "\n# AEmulator navigation recents keys\nkey 580 APP_SWITCH\nkey 187 APP_SWITCH\nkey 254 APP_SWITCH\n"
                        changed = true
                    }
                    if (changed) kl.writeText(s)
                }
            }
        }
    }

    
    private fun parkQcomServices() {
        val parked = File(root, "system/.aemu-parked")
        var n = 0
        
        for (rel in QCOM_PARK.flatMap { if (it.endsWith(".xml")) listOf(it) else listOf(it, "$it.apk", "$it.odex") }) {
            val f = File(root, rel)
            if (!f.exists() && !isLink(f)) continue
            parked.mkdirs()
            val dst = File(parked, rel.replace('/', '#'))
            if (dst.exists()) ImageStore.wipe(dst)
            if (f.renameTo(dst)) n++
        }
        if (n > 0) log("Qualcomm: vendor services set aside: $n")
    }

    
    
    
    private fun hasHal(cls: String) = listOf("system/lib64/hw", "vendor/lib64/hw", "system/vendor/lib64/hw")
        .any { d -> File(root, d).list()?.any { it.startsWith("$cls.") && it.endsWith(".so") } == true }

    private fun contains(f: File, text: String): Boolean = runCatching {
        f.isFile && String(f.readBytes(), Charsets.ISO_8859_1).contains(text)
    }.getOrDefault(false)

    private fun exynosLibgui(lib: String): Boolean = runCatching {
        String(File(root, "system/$lib/libgui.so").readBytes(), Charsets.ISO_8859_1).contains("invalid gralloc handle (at %p)")
    }.getOrDefault(false)

    
    private fun installPure32GpuBridge(haveSwiftShader: Boolean) {
        
        
        val want = gpuOn && kernel.gpuBridge64 && kernel.treble && haveSwiftShader && assetSize(OPEN_GL) > 0
        val bridge = File(root, "${kernel.eglDir32}/libGLES_aemu.so")
        val emulation = File(root, "${kernel.emulationDir32}/libGLES_emulation.so")
        val emulationWanted = want && kernel.bridgeAsEmulation
        if (!want) {
            for (f in listOf(bridge, emulation)) if (isLink(f)) Os.remove(f.absolutePath) else if (f.isFile) f.delete()
            if (gpuOn && kernel.gpuBridge64 && kernel.treble) log("GPU (32-bit firmware): no bridge installed (${if (haveSwiftShader) "bridge asset missing" else "no 32-bit SwiftShader or not the engine's gralloc"}), software rendering")
            return
        }
        runCatching {
            bridge.parentFile?.mkdirs()
            installAsset(OPEN_GL, bridge)
            if (emulationWanted) { emulation.parentFile?.mkdirs(); installAsset(OPEN_GL, emulation) }
            else if (isLink(emulation)) Os.remove(emulation.absolutePath) else if (emulation.isFile) emulation.delete()
            log("GPU (32-bit firmware): bridge in ${bridge.parentFile?.relativeTo(root)?.path}")
        }.onFailure { log("GPU (32-bit firmware): bridge failed to install: ${it.message}") }
    }

    private fun installNative64() {
         
         
        
        
        
        var copied = 0
        
        
        
        
        
        
        val foreign = img.arch.foreign
        var skippedElf = 0
        
        
        kernel.gpuBridge32?.let { name ->
            val on = !foreign && gpuOn && File(root, "system/bin/app_process32").isFile
            for ((asset, to) in listOf((if (kernel.gpuBridge32Open) "libGLES_aemu.so" else "libGLES_route.so") to name, "libGLES_split.so" to "libGLES_split.so", "libGLES.so" to "libGLES_bridge.so")) {
                val f = File(root, "system/lib/egl/$to")
                if (on) runCatching {
                    val len = eaSize("engines/kk/$asset")
                    if (isLink(f) || !f.isFile || f.length() != len) {
                        f.delete()
                        eaOpen("engines/kk/$asset").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        f.setReadable(true, false); f.setExecutable(true, false); copied++
                    }
                } else if (to != name || !isLink(f)) f.delete()
            }
        }
        
        
        
        
        
        
        
        
        if (kernel.oneIncludePolicy) {
            val policyDirs = listOf(File(root, "system/etc/seccomp_policy")) +
                File(root, "apex").listFiles().orEmpty().filter { it.isDirectory }.map { File(it, "etc/seccomp_policy") }
            for (d in policyDirs) d.listFiles { f -> f.isFile && f.name.endsWith(".policy") }.orEmpty().forEach { f ->
                runCatching {
                    val lines = f.readLines()
                    
                    val kept = lines.filterNot { it.startsWith("@include") && "code_coverage" in it }.map { it.replace("MREMAP_MAYMOVE", "1") }
                    if (kept != lines) f.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            }
        }
        
        
        
        if (kernel.treble) for (n in listOf("hwservice", "file", "property", "seapp", "service")) {
            val vendor = File(root, "vendor/etc/selinux/nonplat_${n}_contexts")
            if (File(root, "system/etc/selinux/plat_${n}_contexts").isFile && !vendor.exists() &&
                !File(root, "vendor/etc/selinux/vendor_${n}_contexts").exists()) runCatching {
                vendor.parentFile?.mkdirs()
                vendor.writeText("")
                vendor.setReadable(true, false)
            }
        }
        
        val ownVendor = kernel.treble && File(root, "vendor/build.prop").isFile
        val layers = ArrayList<String>(kernel.assets)
        if (ownVendor || kernel.treble && kernel.ownVendorUnderGsi) layers += kernel.ownVendorAssets
        if (kernel.treble && !ownVendor) layers += kernel.gsiAssets
        
        
        
        
        
        
        if (!foreign && kernel != Kernel64.A17 && !isPure32Tree(root) && File(root, "system/.aemu-parked/gralloc5").isFile &&
            runCatching { EngineAssets.layerNames(ctx, kernel.gralloc5Assets) }.getOrNull().orEmpty().isNotEmpty()) {
            layers += kernel.gralloc5Assets
            log("gralloc5: SurfaceFlinger aborted for want of a graphics mapper 4/5 on an earlier start, the engine's gralloc5 (IAllocator/default, mapper.aemu.so) goes in")
        }
        val files = LinkedHashMap<String, String>()         
        
        
        fun is64BitElf(asset: String): Boolean = runCatching {
            eaOpen(asset).use { i ->
                val h = ByteArray(5)
                i.read(h) == 5 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() && h[4] == 2.toByte()
            }
        }.getOrDefault(false)
        val guest64 = !isPure32Tree(root)
        
        
        
        
        
        for (dir in layers) for ((n, asset) in runCatching { EngineAssets.layerFiles(ctx, dir, guest64) }.getOrNull().orEmpty()) {
            val had = files[n]
            if (!guest64 && had != null && is64BitElf(asset) && !is64BitElf(had)) continue
            files[n] = asset
        }
        
        
        
        
        
        
        
        
        if (guest64) {
            val sensors = "vendor#bin#hw#android.hardware.sensors@1.0-service"
            val had = files[sensors]
            if (had != null && !is64BitElf(had)) {
                layers.asReversed().map { "${it.trimEnd('/')}/$sensors" }.firstOrNull { EngineAssets.exists(ctx, it) && is64BitElf(it) }
                    ?.let { files[sensors] = it; log("sensors: the 64-bit service is installed (the layer's arm32 host cannot find its interface library on a 64-bit tree)") }
            }
        }
        
        
        if (foreign) { skippedElf = files.size; files.clear() }
        
        
        
        
        
        
        if (foreign && img.arch == GuestArch.X86_64) {
            val ownHwc = listOf("vendor/lib64/hw", "system/lib64/hw", "system/vendor/lib64/hw").any { dir ->
                File(root, dir).listFiles()?.any { it.name.startsWith("hwcomposer.") && it.name.endsWith(".so") && !isEngineX86Hwc(it) } == true
            }
            val gralloc2 = x86Gralloc2Wanted()
            if (!gralloc2) restoreFirmwareGralloc()
            val audio = x86AudioWanted()
            val composer = x86ComposerWanted()
            val health = x86HealthWanted()
            val keymasterAnswerer = x86KeymasterAnswererWanted()
            ensureDeviceManifest()
            for ((n, asset) in EngineAssets.x86_64Files(ctx, kernel.tag, img.api)) {
                if (n == "vendor#lib64#hw#hwcomposer.default.so" && ownHwc) { log("x86_64: the firmware's own hwcomposer HAL is kept"); continue }
                
                
                
                if (n in X86_AUDIO_FILES && !audio) continue
                
                if (n in X86_COMPOSER_FILES && !composer) continue
                
                if (n in X86_HEALTH_FILES && !health) continue
                
                if (n in X86_KEYMASTER_ANSWERER_FILES && !keymasterAnswerer) continue
                if (n in X86_GRALLOC2_FILES) {
                    if (!gralloc2) continue
                    File(root, n.replace('#', '/')).takeIf { it.isFile && !isEngineX86Gralloc2(n, it) }
                        ?.let { backupOnce(it, File(root, "system/.aemu-gralloc2-orig/$n")) }
                }
                files[n] = asset
            }
            if (files.isNotEmpty()) log("x86_64: engine files installed: ${files.keys.joinToString { it.substringAfterLast('#') }}")
        }
        
        
        
        
        
        
        
        if (foreign) {
            val featureXml = "vendor#etc#permissions#handheld_core_hardware.xml"
            val featureAsset = "engines/n64-${kernel.tag}/$featureXml"
            val declared = listOf("vendor", "system/vendor", "odm", "system/odm", "system", "system/product", "system/system_ext", "product", "system_ext")
                .any { base ->
                    File(root, "$base/etc/permissions").listFiles()?.any { f ->
                        f.isFile && f.name.endsWith(".xml") && f.name != "aemu-absent.xml" &&
                            runCatching { f.length() < 65536 && f.readText().contains("android.software.app_widgets") }.getOrDefault(false)
                    } == true
                }
            if (!declared && EngineAssets.exists(ctx, featureAsset)) {
                files[featureXml] = featureAsset
                log("${img.arch.id} firmware: no device feature list in the tree (android.software.app_widgets is not declared), the engine's handheld_core_hardware.xml is installed")
            }
        }
        
        if (ownVendor) for (n in kernel.firmwareOwn) {
            val parked = File(root, "system/.aemu-parked/$n")
            val own = File(root, n.replace('#', '/'))
            if (parked.isFile) { own.delete(); if (parked.renameTo(own)) log("${own.name}: the firmware's own file is back") }
            if (own.isFile) files.remove(n)
        }
        if (ownVendor) for (n in kernel.firmwareAside) {
            val own = File(root, n.replace('#', '/'))
            val parked = File(root, "system/.aemu-parked/$n")
            if (own.isFile && !parked.exists()) { parked.parentFile?.mkdirs(); if (own.renameTo(parked)) log("${own.name}: the firmware's own file is set aside") }
        }
        
        
        
        
        
        
        val keepFirmwareVintf = ownVendor && isPure32Tree(root)
        if (keepFirmwareVintf) {
            for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
                if (!parked.isFile) continue
                val rel = parked.name.replace('#', '/')
                val vintf = rel == "vendor/etc/vintf/manifest.xml" || rel.startsWith("vendor/etc/vintf/manifest/") ||
                    rel.startsWith("odm/etc/vintf/manifest/")
                if (!vintf) continue
                val own = File(root, rel)
                if (rel == "vendor/etc/vintf/manifest.xml") own.delete() else if (own.exists()) continue
                own.parentFile?.mkdirs()
                if (parked.renameTo(own)) { own.setReadable(true, false); log("${own.name}: the firmware's own HAL manifest is back (32-bit firmware)") }
            }
            files.remove("vendor#etc#vintf#manifest.xml")
        }
        if (ownVendor && !keepFirmwareVintf && kernel.manifestFragmentsAside && "vendor#etc#vintf#manifest.xml" in files) {
            for (f in File(root, "vendor/etc/vintf/manifest").listFiles().orEmpty()) {
                val parked = File(root, "system/.aemu-parked/vendor#etc#vintf#manifest#${f.name}")
                if (kernel.manifestFragmentsKept.any { it in f.name }) continue
                if (f.isFile && !parked.exists()) { parked.parentFile?.mkdirs(); if (f.renameTo(parked)) log("${f.name}: the firmware's own file is set aside") }
            }
        }
        
        
        
        if (ownVendor && !keepFirmwareVintf && kernel.odmManifestFragmentsAside && "vendor#etc#vintf#manifest.xml" in files) {
            for (f in File(root, "odm/etc/vintf/manifest").listFiles().orEmpty()) {
                val parked = File(root, "system/.aemu-parked/odm#etc#vintf#manifest#${f.name}")
                val link = java.nio.file.Files.isSymbolicLink(f.toPath())
                if ((f.isFile || link) && !parked.exists()) { parked.parentFile?.mkdirs(); if (f.renameTo(parked)) log("odm ${f.name}: the firmware's own file is set aside") }
            }
        }
        
        if (ownVendor) for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            val name = parked.name.removePrefix("vendor#etc#vintf#manifest#")
            if (name != parked.name && kernel.manifestFragmentsKept.any { it in name }) {
                val own = File(root, "vendor/etc/vintf/manifest/$name")
                if (!own.exists() && parked.renameTo(own)) log("$name: the firmware's own file is back")
            }
        }
        
        
        val shipped = files.keys
        val anyKernel = Kernel64.entries.flatMap { k -> listOf("engines/n64-${k.tag}", k.ownVendorAssets).flatMap { runCatching { EngineAssets.layerNames(ctx, it) }.getOrNull().orEmpty() } }.toSet()
        for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            if (!parked.isFile || parked.name !in anyKernel || parked.name in shipped) continue
            val dst = File(root, parked.name.replace('#', '/'))
            dst.delete()
            if (parked.renameTo(dst)) log("${dst.name}: the firmware's own file is back")
        }
        
        
        
        
        val ownSf = (kernel.exynosHandles && exynosLibgui("lib64")) || File(root, "system/.aemu-parked/firmware-sf").isFile
        
        
        val soundTriggerImpl = File(root, "vendor/lib64/hw").list().orEmpty().any { it.startsWith("android.hardware.soundtrigger@") && it.endsWith("-impl.so") }
        
        
        
        
        val pure32 = isPure32Tree(root)
        if (pure32) log("32-bit firmware: the engine's 64-bit files are not installed")
        
        
        
        if (pure32) for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            val rel = parked.name.replace('#', '/')
            val dir = rel.substringBeforeLast('/')
            val base = rel.substringAfterLast('/')
            val plainModule = dir in setOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw") &&
                (base.startsWith("memtrack.") || base.startsWith("bootctrl.")) && base.endsWith(".so")
            if (!parked.isFile || !plainModule && (dir !in setOf("vendor/lib/hw", "system/vendor/lib/hw") || !(isGraphicsHidlImpl(base) || isHidlImpl(base)))) continue
            
            if (parked.name in kernel.firmwareAside) continue
            val own = File(root, rel)
            if (own.exists()) continue
            own.parentFile?.mkdirs()
            if (parked.renameTo(own)) { own.setReadable(true, false); log("${own.name}: the firmware's own 32-bit file is back") }
        }
        
        
        
        
        
        
        if (pure32) ensureDefaultHwModule("memtrack")
        
        
        
        
        
        
        if (!foreign && (pure32 || has32BitSide(root))) {
            val asset = "engines/common/libaemuxattr.so"
            
            
            runCatching {
                val size = eaSize(asset)
                for (guestPath in listOf(GuestPreload.XATTR, GuestPreload.XATTR_VENDOR)) {
                    val dst = File(root, guestPath.removePrefix("/"))
                    if (dst.isFile && dst.length() == size && sameContent(asset, dst)) continue
                    dst.parentFile?.mkdirs()
                    if (isLink(dst)) Os.remove(dst.absolutePath)
                    eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    log("xattr: libaemuxattr.so installed at $guestPath (the kernel refuses extended attributes)")
                }
            }.onFailure { log("xattr: libaemuxattr.so missing from the engine set: ${it.message}") }
        }
        for ((n, asset) in files) {
            if (pure32 && is64BitElf(asset)) continue
            
            
            
            
            
            
            
            
            
            val ownAudio = n == "vendor#bin#hw#android.hardware.audio@2.0-service" &&
                File(root, "vendor/lib/hw/android.hardware.audio@2.0-impl.so").isFile
            if (pure32 && (ownAudio || n == "vendor#bin#hw#android.hardware.graphics.allocator@2.0-service" || n == "vendor#bin#hw#android.hardware.keymaster@3.0-service")) {
                val own = File(root, n.replace('#', '/'))
                val parked = File(root, "system/.aemu-parked/$n")
                if (parked.isFile) {
                    own.delete()
                    if (parked.renameTo(own)) { own.setReadable(true, false); own.setExecutable(true, false); log("${own.name}: the firmware's own service is back (32-bit firmware)") }
                    continue
                }
                if (own.isFile) { log("${own.name}: the firmware's own service is kept (32-bit firmware)"); continue }
            }
            if (!soundTriggerImpl && "aemu-soundtrigger" in n) { File(root, n.replace('#', '/')).delete(); continue }
            if (ownSf && (n == "system#bin#surfaceflinger" || n == "system#lib64#libsurfaceflinger.so")) {
                val parked = File(root, "system/.aemu-parked/$n")
                val own = File(root, n.replace('#', '/'))
                if (parked.isFile) { own.delete(); if (parked.renameTo(own)) log("${own.name}: the firmware's own file is back") }
                continue
            }
            
            
            
            val eglDir32 = kernel.eglDir.replace("/lib64/", "/lib/")
            val dst = File(root, n.replace('#', '/').let {
                when {
                    it.startsWith("system/lib64/egl/") -> kernel.eglDir + it.removePrefix("system/lib64/egl")
                    it.startsWith("system/lib/egl/") -> eglDir32 + it.removePrefix("system/lib/egl")
                    else -> it
                }
            })
            val size = runCatching { eaSize(asset) }.getOrDefault(-1L)
            if (dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
            runCatching {
                dst.parentFile?.mkdirs()
                if (isLink(dst)) Os.remove(dst.absolutePath)
                else if (dst.isFile) {          
                    val parked = File(root, "system/.aemu-parked/$n")
                    parked.parentFile?.mkdirs()
                    if (!parked.exists()) dst.renameTo(parked)
                }
                eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false); dst.setExecutable(true, false)
                copied++
            }.onFailure { log("$n: ${it.message}") }
        }
        
        
        val engineGralloc32 = listOf("vendor#lib#hw#gralloc.default.so", "system#lib#hw#gralloc.default.so").any { n ->
            val a = files[n]; val f = File(root, n.replace('#', '/'))
            a != null && f.isFile && runCatching { sameContent(a, f) }.getOrDefault(false)
        }
        if (foreign) log("${img.arch.id} firmware: the engine's $skippedElf arm/arm64 files and HAL declarations are not installed; the firmware's own ${img.arch.id} side is used")
        if (pure32 && !foreign) installPure32GpuBridge(
            (files.containsKey("system#lib#egl#libEGL_swiftshader.so") || File(root, "${kernel.eglDir32}/libEGL_swiftshader.so").isFile) && engineGralloc32)
        
        
        if (kernel.eglName != "swiftshader") listOf(kernel.eglDir, "system/lib/egl").flatMap { File(root, it).listFiles().orEmpty().toList() }.filter { it.name.endsWith("_swiftshader.so") }.forEach { f ->
            val link = File(f.parentFile, f.name.replace("_swiftshader.so", "_${kernel.eglName}.so"))
            if (!isLink(link)) { link.delete(); runCatching { Os.symlink(f.name, link.path) } }
        }
        
        
        
        
        
        if (kernel.eglName != "swiftshader" && File(root, "system/lib/egl/libGLESv2_swiftshader.so").isFile) {
            val single = File(root, "system/lib/egl/libGLES_${kernel.eglName}.so")
            if (single.isFile || isLink(single)) {
                val parked = File(root, "system/.aemu-parked/system#lib#egl#${single.name}")
                parked.parentFile?.mkdirs()
                if (isLink(single)) Os.remove(single.absolutePath)
                else { parked.delete(); if (!single.renameTo(parked)) single.delete() }
                log("${single.name}: set aside, the engine's 32-bit SwiftShader takes its place")
                copied++
            }
        }
        
        
        
        
        
        
        
        if (kernel.emulationDir != null || pure32) File(root, "vendor/lib/egl").listFiles().orEmpty().filter { it.name.endsWith("_swiftshader.so") }.forEach { f ->
            for (n in listOfNotNull(f.name, f.name.replace("_swiftshader.so", "_emulation.so").takeIf { kernel.emulationDir != null })) {
                val link = File(root, "system/lib/egl/$n")
                link.parentFile?.mkdirs()
                if (!isLink(link)) { link.delete(); runCatching { Os.symlink("../../../vendor/lib/egl/${f.name}", link.path) } }
            }
        }
        
        
        
        
        if (kernel.emulationDir != null) File(root, "system/etc").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("ld.config") && it.name.endsWith(".txt") }.forEach { f ->
                runCatching {
                    val text = f.readText()
                    var changed = false
                    val out = text.lines().joinToString("\n") { line ->
                        val m = Regex("^(\\s*namespace\\.sphal\\.permitted\\.paths\\s*[+]?=\\s*)(.*)$").matchEntire(line)
                        if (m == null) return@joinToString line
                        val value = m.groupValues[2].trim()
                        val lib = if (value.contains("\${LIB}")) "\${LIB}" else "lib64"
                        val extra = "/system/$lib/egl"
                        if (value.split(':').contains(extra)) line
                        else { changed = true; m.groupValues[1] + value + ":" + extra }
                    }
                    if (changed) { f.writeText(out); log("linker: ${f.name}: /system/lib64/egl added to the sphal namespace"); copied++ }
                }.onFailure { log("linker: ${f.name} not patched: ${it.message}") }
            }
        
        
        
        
        if (kernel.tag == "26") {
            val stub = File(root, "vendor/lib64/android.hidl.base@1.0.so")
            
            val staleStub = stub.isFile && stub.length() < 1024 && runCatching {
                stub.inputStream().use { ins -> val h = ByteArray(64); ins.read(h) == 64 && h[60] == 0.toByte() && h[61] == 0.toByte() }
            }.getOrDefault(false)
            if ((!stub.exists() || staleStub) && !File(root, "system/lib64/android.hidl.base@1.0.so").exists()) runCatching {
                stub.parentFile?.mkdirs()
                stub.writeBytes(android.util.Base64.decode(HIDL_BASE_STUB, android.util.Base64.DEFAULT))
                stub.setReadable(true, false); stub.setExecutable(true, false)
                log("hidl: empty android.hidl.base@1.0.so added (8.0 has it inside libhidlbase.so)")
                copied++
            }.onFailure { log("hidl: android.hidl.base@1.0.so not added: ${it.message}") }
        }
        
        
        
        
        if (kernel.eglCfgTags) {
            val cfg = File(root, "system/lib/egl/egl.cfg")
            val rom = File(root, "system/lib/egl/egl.cfg.rom")
            if (cfg.isFile) runCatching {
                val lines = cfg.readLines()
                val out = lines.map { l ->
                    val t = l.trim().split(Regex("\\s+"))
                    if (t.size == 3 && !l.trim().startsWith("#") && t[2] != "android") "${t[0]} ${t[1]} swiftshader" else l
                }
                if (out != lines) {
                    if (!rom.isFile) cfg.copyTo(rom)
                    cfg.writeText(out.joinToString("\n", postfix = "\n"))
                    log("egl.cfg: the vendor's driver tag replaced with SwiftShader")
                }
            }
        }
        
        
        
        
        if (kernel.bootImageInCache) for ((lib, isa) in listOf("lib64" to "arm64", "lib" to "arm")) runCatching {
            if (!contains(File(root, "system/$lib/libart.so"), "PruneDalvikCache : File Not Exist")) return@runCatching
            for (ext in listOf("art", "oat")) {
                val src = File(root, "system/framework/$isa/boot.$ext")
                val dst = File(root, "data/dalvik-cache/$isa/system@framework@boot.$ext")
                if (!src.isFile || (dst.isFile && dst.length() == src.length())) continue
                dst.parentFile?.mkdirs()
                dst.delete()
                runCatching { Os.link(src.absolutePath, dst.absolutePath) }.onFailure { src.copyTo(dst, overwrite = true) }
                dst.setReadable(true, false)
                log("dalvik-cache/$isa: boot.$ext of the firmware linked in")
            }
        }
        
        
        
        
        
        
        val qcomSf = kernel.qcomHandles && runCatching {
            String(File(root, "system/lib64/libsurfaceflinger.so").readBytes(), Charsets.ISO_8859_1).contains("ExSurfaceFlinger")
        }.getOrDefault(false)
        for ((on, name) in listOf(kernel.exynosHandles to "exynos", kernel.qcomHandles to "qcom")) if (on) for (lib in listOf("lib64", "lib")) runCatching {
            
            val side = if (kernel.treble) "vendor" else "system"
            val wrap = File(root, "$side/$lib/libgralloc_$name.so")
            val hw = File(root, "$side/$lib/hw/gralloc.default.so")
            val rom = File(root, "$side/$lib/libgralloc_rom.so")
            val gui = File(root, "system/$lib/libgui.so")
            if (!wrap.isFile || !hw.isFile || !gui.isFile) return@runCatching
            
            
            
            
            val wrapBytes = wrap.readBytes()
            var chosen = wrapBytes
            var variant = ""
            if (name == "qcom" && lib == "lib64" && qcomSf && kernel.tag == "27") {
                val altPath = "engines/n64-27-alt/vendor#lib64#libgralloc_qcom.so"
                val layout = QcomHandleProbe.classify(File(root, "system/lib64/libsurfaceflinger.so"))
                if (layout == QcomHandleProbe.Layout.METADATA_POINTER && EngineAssets.exists(ctx, altPath)) {
                    runCatching { eaOpen(altPath).use { it.readBytes() } }.onSuccess { chosen = it; variant = " (empty metadata slot: SurfaceFlinger follows handle+0x48 as a pointer)" }
                        .onFailure { log("$lib: alternative Qualcomm gralloc unreadable: ${it.message}") }
                } else log("$lib: SurfaceFlinger's use of handle+0x48 is $layout, standard Qualcomm gralloc layout kept")
            }
            val cur = hw.readBytes()
            val inPlace = cur.contentEquals(chosen)
            val wanted = if (name == "exynos") exynosLibgui(lib) else qcomSf
            if (wanted && !inPlace) {
                if (!rom.isFile) hw.renameTo(rom)
                hw.delete()
                hw.writeBytes(chosen)
                hw.setReadable(true, false)
                log("$lib: gralloc with ${if (name == "exynos") "Samsung" else "Qualcomm"}'s handle layout in front of the firmware's$variant")
            } else if (!wanted && (inPlace || cur.contentEquals(wrapBytes)) && rom.isFile) {
                hw.delete(); rom.renameTo(hw)
            }
        }
        
        
        
        
        if (kernel.taskMapsIsSelfMaps) {
            val libc = File(root, "system/lib/libc.so")
            if (libc.isFile) runCatching {
                val d = libc.readBytes()
                val a = "/proc/self/task/%d/maps".toByteArray() + 0
                val b = "/proc/self/maps".toByteArray()
                val at = (0..d.size - a.size).firstOrNull { i -> a.indices.all { d[i + it] == a[it] } }
                if (at != null) {
                    a.indices.forEach { d[at + it] = if (it < b.size) b[it] else 0 }
                    libc.writeBytes(d)
                    log("libc.so (32-bit): the main thread's stack is read from /proc/self/maps")
                }
            }
        }
        
        
        
        if (!foreign && File(root, "system/bin/mediaserver").let { it.isFile && !isElf64(it) }) {
            
            val audioHal = if (kernel.wideAudioFormat && contains(File(root, "system/lib/libaudioflinger.so"), "HAL format %ll"))
                "engines/kk/audio.primary.fmt64.so" else "engines/kk/audio.primary.aosp.so"
            
            val cameraHal = kernel.assets.reversed().firstNotNullOfOrNull { EngineAssets.layerAsset(ctx, it, "system#lib#hw#camera.default.so", EngineAssets.ARM32) }
                ?: "engines/common/camera.default.so"
            for ((asset, to) in listOf(cameraHal to "system/lib/hw/camera.default.so",
                audioHal to "system/lib/hw/audio.primary.default.so")) {
                val dst = File(root, to)
                val size = runCatching { eaSize(asset) }.getOrDefault(-1L)
                if (dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
                runCatching {
                    dst.parentFile?.mkdirs()
                    val parked = File(root, "system/.aemu-parked/${to.replace('/', '#')}")
                    parked.parentFile?.mkdirs()
                    if (dst.isFile && !parked.exists()) dst.renameTo(parked)
                    eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    dst.setReadable(true, false); copied++
                }.onFailure { log("$to: ${it.message}") }
            }
        }
        
        runCatching {
            
            val jar = File(root, "system/framework/aemu-stubs.jar")
            val bytes = eaOpen("engines/kk/aemu-stubs.jar").use { it.readBytes() }
            if (!jar.isFile || !jar.readBytes().contentEquals(bytes)) {
                jar.writeBytes(bytes)
                jar.setReadable(true, false); copied++
            }
        }.onFailure { log("aemu-stubs.jar: ${it.message}") }
        
        
        runCatching {
            val jar = File(root, WifiEmu.JAR_PATH)
            if (img.api in WifiEmu.TREBLE_MIN_API..WifiEmu.TREBLE_MAX_API) {
                val bytes = eaOpen("engines/kk/${WifiEmu.JAR_NAME}").use { it.readBytes() }
                if (!jar.isFile || !jar.readBytes().contentEquals(bytes)) {
                    jar.parentFile?.mkdirs()
                    jar.writeBytes(bytes)
                    jar.setReadable(true, false); copied++
                }
            } else jar.delete()
        }.onFailure { log("${WifiEmu.JAR_NAME}: ${it.message}") }
        if (copied > 0) log("engine n64 files updated: $copied")
        
        
        
        
        runCatching {
            File(root, "proc/sys/vm").mkdirs()
            File(root, "proc/sys/vm/mmap_min_addr").writeText(if (linksBelow64K(File(root, "sbin/healthd"))) "32768" else "65536")
        }
        runCatching { File(root, "proc/.one/1").mkdirs(); File(root, "proc/.task1").mkdirs(); File(root, "proc/.task1/none").writeText("") }     
        
        
        var parked = 0
        for (dir in listOf("system/lib64/hw", "system/vendor/lib64/hw", "system/lib/hw", "system/vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                val id = f.name.substringBefore('.')
                
                if (!f.isFile || (f.name.endsWith(".default.so") && id != "gps") || id !in VENDOR_HALS) return@forEach
                
                if (img.arch.foreign && id == "audio") return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) parked++
            }
        }
        
        
        
        
        
        if (kernel.treble) {
            val seen = ArrayList<String>()
            for (dir in listOf("vendor/lib64/hw", "vendor/lib/hw", "system/lib64/hw", "system/lib/hw")) {
                File(root, dir).listFiles()?.forEach { f ->
                    if (!f.isFile || !(f.name.startsWith("keystore.") || f.name.startsWith("android.hardware.keymaster") || f.name.startsWith("vendor.google.iot.keymaster"))) return@forEach
                    seen += "$dir/${f.name}"
                    
                    
                    if (!f.name.startsWith("keystore.") || (f.name.endsWith(".default.so") && kernel.tag != "26") || isLink(f)) return@forEach
                    val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.delete()
                    if (f.renameTo(dst)) { parked++; log("${f.name}: the firmware's keystore module is set aside") }
                }
            }
            log("keymaster: hw modules seen: ${seen.ifEmpty { listOf("none") }.joinToString(", ")}")
            
            
            
            if (seen.none { it.contains("keymaster@") && it.contains("-impl") }) {
                val aside = File(root, "system/.aemu-parked").list().orEmpty().filter { it.contains("keymaster@") && it.contains("-impl") }
                log("keymaster: no HIDL impl in the tree" + if (aside.isEmpty()) " (and none set aside: the firmware has none)" else ", set aside: ${aside.joinToString(", ")}")
            }
            
            
            
            
            if (isPure32Tree(root)) log("deps: 32-bit firmware, the 64-bit library checks do not apply")
            else {
                for (rel in listOf("vendor/lib64/hw/android.hardware.keymaster@3.0-impl.so", "system/lib64/hw/keystore.default.so",
                    "vendor/bin/hw/android.hardware.keymaster@3.0-service", "vendor/lib64/hw/android.hardware.gatekeeper@1.0-impl.so",
                    "vendor/lib64/hw/android.hardware.light@2.0-impl.so")) logMissingLibs(rel)
                logUnresolvedSymbols("vendor/lib64/hw/android.hardware.keymaster@3.0-impl.so", "HIDL_FETCH_IKeymasterDevice")
                logUnresolvedSymbols("system/lib64/hw/keystore.default.so")
            }
        }
        
        
        for (dir in listOf("system/etc/permissions", "vendor/etc/permissions")) for (hw in listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef")) {
            val f = File(root, "$dir/android.hardware.$hw.xml")
            val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
            
            val absent = hw in kernel.absentFeatures || (hw == "consumerir" && kernel.irFollowsHal && !hasHal("consumerir"))
            if (!absent) { if (dst.isFile && !f.exists()) dst.renameTo(f); continue }
            if (!f.isFile) continue
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            if (f.renameTo(dst)) parked++
        }
        
        
        
        File(root, "system/etc/permissions/aemu-absent.xml").let { f ->
            
            
            
            
            
            val cecHal = listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw").any { d ->
                File(root, d).listFiles()?.any { it.name.startsWith("android.hardware.tv.hdmi.cec") } == true
            } ||
                
                
                (kernel.treble && !hasHwModule("hdmi_cec", !isPure32Tree(root)) &&
                    (hwPrograms("android.hardware.tv.cec@").isNotEmpty() ||
                        File(root, "system/.aemu-parked").list().orEmpty().any { it.substringAfterLast('#').startsWith("android.hardware.tv.cec@") }))
            
            
            
            
            val thread = File(root, "apex/com.android.tethering").isDirectory
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            val faceNeeded = isSamsungFirmware()
            if (faceNeeded) log("face: Samsung firmware, android.hardware.biometrics.face stays declared (its SystemUI needs a FaceManager)")
            val faceDead = !faceNeeded && kernel.treble && img.api >= 29 &&
                listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw", "system_ext/bin/hw", "system/system_ext/bin/hw", "odm/bin/hw", "system/odm/bin/hw").none { d ->
                    File(root, d).listFiles().orEmpty().any { it.name.startsWith("android.hardware.biometrics.face") && (it.isFile || isLink(it)) }
                }
            if (faceDead) log("face: no face HAL program in the tree, android.hardware.biometrics.face declared unavailable (FaceService would wait for ever for IVirtualHal/virtual)")
            
            
            
            
            
            val un = kernel.absentFeatures.filter { it.startsWith("bluetooth") || it == "fingerprint" } +
                (if (cecHal) listOf("hdmi.cec") else emptyList()) + (if (thread) listOf("thread_network") else emptyList()) +
                (if (faceDead) listOf("biometrics.face") else emptyList())
            if (un.isEmpty()) f.delete()
            else f.writeText("<permissions>" + un.joinToString("") { "<unavailable-feature name=\"android.hardware.$it\" />" } + "</permissions>")
        }
        
        
        
        
        File(root, "system/etc/permissions/aemu-wifi-direct.xml").let { f ->
            val declared = listOf("system/etc/permissions", "vendor/etc/permissions", "system/vendor/etc/permissions").any { d ->
                File(root, d).listFiles()?.any { it.isFile && it.name != f.name && it.name.endsWith(".xml") &&
                    runCatching { it.readText().contains("android.hardware.wifi.direct") && !it.readText().contains("unavailable-feature name=\"android.hardware.wifi.direct") }.getOrDefault(false) } == true
            }
            
            
            
            
            val huaweiWifiEnhance = File(root, "system/framework/hwWifiEnhance-service.jar").isFile
            if ((img.api >= 34 || huaweiWifiEnhance) && !declared) {
                f.writeText("<permissions><feature name=\"android.hardware.wifi.direct\" /></permissions>")
                log("wifi direct: android.hardware.wifi.direct declared (" + (if (huaweiWifiEnhance) "Huawei WifiEnhanceService needs the wifip2p manager" else "WifiDisplayAdapter refuses to start without it") + ")")
            } else f.delete()
        }
        
        
        for (rel in listOf("system/lib64/libloc_api_v02.so", "system/vendor/lib64/libloc_api_v02.so", "system/lib/libloc_api_v02.so", "system/vendor/lib/libloc_api_v02.so")) {
            val f = File(root, rel)
            if (!f.isFile) continue
            val dst = File(root, "system/.aemu-parked/${rel.replace('/', '#')}")
            dst.parentFile?.mkdirs()
            if (f.renameTo(dst)) parked++
        }
        
        for (dir in listOf("system/lib64", "system/vendor/lib64", "system/lib", "system/vendor/lib")) {
            File(root, dir).listFiles()?.filter { it.name.startsWith("libRSDriver_") }?.forEach { f ->
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (f.renameTo(dst)) parked++
            }
        }
        
        
        if (File(root, kernel.eglLib).isFile) {
            for (dir in listOf("system/lib64/egl", "system/vendor/lib64/egl", "system/vendor/lib/egl").filter { !kernel.treble || it != "system/lib64/egl" }) {
                File(root, dir).listFiles()?.forEach { f ->
                    if (!f.isFile || isLink(f) || kernel.isOwnSuffix(f.name) || f.name == "libGLES_android.so") return@forEach
                    val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.delete()
                    if (f.renameTo(dst)) parked++
                }
            }
            val egl32 = File(root, "system/lib/egl")
            
            File(egl32, "libGLES_android.so.sw").let { sw ->
                if (sw.isFile) { File(egl32, "libGLES_android.so").delete(); sw.renameTo(File(egl32, "libGLES_android.so")) }
            }
            val bridge32 = gpuOn && kernel.gpuBridge32 != null
            if (File(egl32, "libGLES_android.so").isFile && !bridge32) {
                for (n in listOf("libGLES_aemu.so", "libGLES_bridge.so", "libEGL_aemu.so", "libGLESv1_CM_aemu.so", "libGLESv2_aemu.so", "libGLES.so"))
                    File(egl32, n).let { if (isLink(it)) Os.remove(it.absolutePath) else it.delete() }
                
                
                if (!File(egl32, "libGLESv2_swiftshader.so").isFile) runCatching { Os.symlink("libGLES_android.so", File(egl32, "libGLES.so").path) }
            }
        }
        if (parked > 0) log("vendor HALs set aside: $parked")
        restoreForeignAudioHal()
        restorePeripheralIo()
        parkSensorsHalOfPure32Treble()
        parkBoardAidlSensorsHal()
        parkSensorsHalWithoutImpl()
        parkEngineAudioHostOfFirmwareWithOwnAudio()
        fixAudioHostImplLookupOfPure32Treble()
        parkCodec2HalOfPure32Treble()
        parkVendorHalsThatCannotLink()
        parkUsbGadgetHal()
        parkHalsWithoutModuleOfPure32Treble()
        parkRemoteKeymasterOfForeignTree()
        disableTeeKeyMint()
        disableTeeOemLock()
        dropHalsWithoutService()
        disableHardwareVibratorHalOfPure32()
        useSoftwareGrallocOnPure32()
        useSoftwareGrallocOnX86()
        useEngineComposerOnPure32()
        
        
        if (useEngineHwServiceManager()) restoreComposerHwbinderDeclaration() else useComposerPassthroughWithoutHwServiceManager()
        dropStalePlatformCopiesInVendor()
        bringSwiftShaderDepsWithoutVndk()
        bringFirmwareHidlStackForAudio()
        useEngineAllocatorOnA17()
        restoreFirmwareAidlDeclarations()
        useSoftwareKeymasterOnPure32()
    }

    
    private fun useSoftwareGrallocOnPure32() {
        if (!kernel.treble || !isPure32Tree(root)) return
        val svc = listOf("vendor/bin/hw", "system/vendor/bin/hw").map { File(root, "$it/android.hardware.graphics.allocator@2.0-service") }.firstOrNull { it.isFile } ?: return
        if (listOf("vendor/lib/hw", "system/vendor/lib/hw").none { File(root, "$it/android.hardware.graphics.mapper@2.0-impl.so").isFile }) return
        declareGralloc2("32-bit firmware, software gralloc")
    }

    
    private fun useSoftwareGrallocOnX86() {
        if (img.arch != GuestArch.X86_64 || !x86Gralloc2Wanted()) return
        val need = listOf("vendor/bin/hw/android.hardware.graphics.allocator@2.0-service", "vendor/lib64/hw/android.hardware.graphics.allocator@2.0-impl.so",
            "vendor/lib64/hw/android.hardware.graphics.mapper@2.0-impl.so", "vendor/lib64/hw/gralloc.default.so", "vendor/lib64/libgralloc_aosp.so")
        val missing = need.filter { !File(root, it).isFile }
        if (missing.isNotEmpty()) { log("x86_64: allocator@2.0 stack not complete in the tree (${missing.joinToString { it.substringAfterLast('/') }}), the firmware's graphics HALs stay"); return }
        declareGralloc2("x86-64 firmware, software gralloc")
    }

    
    private fun x86Asset(name: String): String =
        EngineAssets.x86_64Dirs(kernel.tag, img.api).asReversed().map { "$it/$name" }.firstOrNull { EngineAssets.exists(ctx, it) } ?: "engines/common-x86_64/$name"

    
    private fun isEngineX86Gralloc2(name: String, f: File): Boolean =
        runCatching { x86Asset(name).let { a -> f.length() == eaSize(a) && sameContent(a, f) } }.getOrDefault(false) || isSupersededX86Engine(name, f)

    
    private val SUPERSEDED_X86_ENGINE_SERVICES: Map<String, List<Pair<Long, Long>>> = mapOf(
        "vendor#bin#hw#android.hardware.graphics.allocator@2.0-service" to listOf(4800L to 0x92ffc028L),
        
        
        "vendor#bin#hw#android.hardware.graphics.composer@2.1-service" to listOf(7488L to 0x2a84c845L, 7509L to 0x6de09a44L),
        
        
        
        
        "vendor#lib64#hw#hwcomposer.default.so" to listOf(24352L to 0xaebace0cL),
        "vendor#lib64#hw#gralloc.default.so" to listOf(6064L to 0xc7ad2eabL, 6368L to 0x66d7de11L))

    private fun isSupersededX86Engine(name: String, f: File): Boolean = runCatching {
        val known = SUPERSEDED_X86_ENGINE_SERVICES[name]?.filter { it.first == f.length() } ?: return@runCatching false
        if (known.isEmpty()) return@runCatching false
        val c = java.util.zip.CRC32()
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; c.update(b, 0, n) } }
        known.any { it.second == c.value }
    }.getOrDefault(false)

    
    private fun x86Gralloc2Wanted(): Boolean {
        if (!kernel.treble || img.api < 30) return false
        val own = listOf("vendor/bin/hw", "system/vendor/bin/hw").map { File(root, "$it/android.hardware.graphics.allocator@2.0-service") }
            .any { it.isFile && !isEngineX86Gralloc2("vendor#bin#hw#android.hardware.graphics.allocator@2.0-service", it) }
        return !own
    }

    
    private fun restoreFirmwareGralloc() {
        val back = ArrayList<String>()
        for (n in X86_GRALLOC2_FILES) {
            val dst = File(root, n.replace('#', '/'))
            if (!dst.isFile || !isEngineX86Gralloc2(n, dst)) continue
            val keep = File(root, "system/.aemu-gralloc2-orig/$n")
            val parked = File(root, "system/.aemu-parked/$n")
            runCatching {
                when {
                    keep.isFile -> { dst.delete(); keep.copyTo(dst) }
                    parked.isFile && !isEngineX86Gralloc2(n, parked) -> { dst.delete(); parked.renameTo(dst) }
                    else -> dst.delete()
                }
                if (dst.isFile) { dst.setReadable(true, false); if (n.startsWith("vendor#bin#") || n.endsWith(".so")) dst.setExecutable(true, false) }
                back += dst.name
            }.onFailure { log("${dst.name}: the firmware's own file could not be put back: ${it.message}") }
        }
        File(root, "vendor/etc/vintf/manifest/$FRAG_GRALLOC2").takeIf { it.isFile }?.let { if (it.delete()) back += it.name }
        if (back.isNotEmpty()) log("x86_64: the firmware keeps its own graphics allocator, engine allocator@2.0 files taken out / firmware's put back: ${back.joinToString()}")
    }

    
    private fun declareGralloc2(what: String) {
        val gone = setOf("android.hardware.graphics.allocator", "android.hardware.graphics.mapper",
            "vendor.qti.hardware.display.allocator", "vendor.qti.hardware.display.mapper")
        
        
        
        
        
        
        val monolithic = listOf("vendor/etc/vintf/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml")
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val origDir = File(root, "system/.aemu-vintf-orig")
        val fragDirs = listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest")
        val seen = HashSet<String>()
        val manifestFiles = fragDirs.flatMap { File(root, it).listFiles().orEmpty().toList() } + monolithic.map { File(root, it) }
        for (f in manifestFiles) {
            if (!f.isFile || !f.name.endsWith(".xml") || f.name == FRAG_GRALLOC2) continue
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                val removed = ArrayList<String>()
                val out = hal.replace(text) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in gone) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    val keep = File(origDir, f.relativeTo(root).path.replace('/', '#'))
                    backupOnce(f, keep)
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: board graphics HAL declaration dropped: ${removed.distinct().joinToString()}")
                }
            }.onFailure { log("${f.name}: graphics declarations not checked: ${it.message}") }
        }
        val xml = """<manifest version="1.0" type="device">
    <hal format="hidl">
        <name>android.hardware.graphics.allocator</name>
        <transport>hwbinder</transport>
        <version>2.0</version>
        <interface><name>IAllocator</name><instance>default</instance></interface>
    </hal>
    <hal format="hidl">
        <name>android.hardware.graphics.mapper</name>
        <transport arch="32+64">passthrough</transport>
        <version>2.0</version>
        <interface><name>IMapper</name><instance>default</instance></interface>
    </hal>
</manifest>
"""
        val dst = File(root, "vendor/etc/vintf/manifest/$FRAG_GRALLOC2")
        dst.parentFile?.mkdirs()
        if (!dst.isFile || dst.readText() != xml) { dst.writeText(xml); dst.setReadable(true, false); log("${dst.name}: allocator@2.0 / mapper@2.0 declared ($what)") }
        declareInMonolithicManifests("android.hardware.graphics.allocator", """    <hal format="hidl">
        <name>android.hardware.graphics.allocator</name>
        <transport>hwbinder</transport>
        <version>2.0</version>
        <interface>
            <name>IAllocator</name>
            <instance>default</instance>
        </interface>
    </hal>
""")
        declareInMonolithicManifests("android.hardware.graphics.mapper", """    <hal format="hidl">
        <name>android.hardware.graphics.mapper</name>
        <transport arch="32+64">passthrough</transport>
        <version>2.0</version>
        <interface>
            <name>IMapper</name>
            <instance>default</instance>
        </interface>
    </hal>
""")
        
        for (d in listOf("vendor/bin/hw", "system/vendor/bin/hw")) for (board in listOf("vendor.qti.hardware.display.allocator-service", "android.hardware.graphics.allocator-service",
                
                
                
                "android.hardware.graphics.allocator@4.0-service", "android.hardware.graphics.allocator@3.0-service",
                
                "android.hardware.graphics.allocator@4.0-service.minigbm")) {
            val q = File(root, "$d/$board")
            if (!q.isFile) continue
            val park = File(root, "system/.aemu-parked/${q.relativeTo(root).path.replace('/', '#')}")
            park.parentFile?.mkdirs(); park.delete()
            if (q.renameTo(park)) log("${q.name}: set aside (its HALs are replaced by allocator@2.0)")
        }
    }

    
    private fun useEngineAllocatorOnA17() {
        
        val gralloc5On16 = kernel == Kernel64.A16 && File(root, "vendor/bin/hw/android.hardware.graphics.allocator-service.aemu").isFile
        if ((kernel != Kernel64.A17 && !gralloc5On16) || isPure32Tree(root)) return
        val parked = File(root, "system/.aemu-parked")
        val ours = File(root, "vendor/bin/hw/android.hardware.graphics.allocator-service.aemu").isFile &&
            File(root, "vendor/lib64/hw/mapper.aemu.so").isFile
        
        
        
        
        for (d in listOf("vendor/bin/hw", "system/vendor/bin/hw")) for (board in listOf("vendor.qti.hardware.display.allocator-service",
                "android.hardware.graphics.allocator-service.minigbm")) {
            val q = File(root, "$d/$board")
            val park = File(parked, q.relativeTo(root).path.replace('/', '#'))
            if (ours) {
                if (!q.isFile) continue
                parked.mkdirs(); park.delete()
                if (q.renameTo(park)) log("${q.name}: set aside (the engine's aemu-allocator and mapper.aemu.so serve IAllocator/default)")
            } else if (park.isFile && !q.exists()) {
                if (park.renameTo(q)) log("${q.name}: put back (the engine's allocator is not in the tree)")
            }
        }
        if (!ours) log("allocator: the engine's aemu-allocator or mapper.aemu.so is not in the tree, the board's allocator stays")
    }

    
    private fun bringSwiftShaderDepsWithoutVndk() {
        if (!kernel.treble || isPure32Tree(root)) return
        val vndkDir = kernel.ownVndkDir ?: return
        val hasVndkPackage = File(root, "apex").listFiles().orEmpty().any {
            it.isDirectory && Regex("com\\.android\\.vndk\\.v\\d+").matches(it.name) && File(it, "lib64").isDirectory
        }
        
        
        
        
        
        
        
        
        val egl = File(root, kernel.eglDir)
        val eglRoots = egl.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith("_swiftshader.so") || it.name == "libGLESv1_CM_ss.so" || it.name == "libGLES_aemu.so") }
        
        
        
        
        val halRoots = File(root, "vendor/lib64/hw").listFiles().orEmpty().filter {
            it.isFile && (it.name.startsWith("android.hardware.graphics.mapper@") && it.name.endsWith("-impl.so") ||
                it.name.startsWith("gralloc.") || it.name.startsWith("mapper.") || it.name == "hwcomposer.default.so")
        } + listOf("libgralloc_aosp.so", "libhwc2on1adapter.so", "libhwc2onfbadapter.so").map { File(root, "vendor/lib64/$it") }.filter { it.isFile }
        val roots = eglRoots + halRoots
        if (roots.isEmpty()) return
        
        
        val llndk = HashSet(listOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libnativewindow.so", "libsync.so", "libvndksupport.so",
            "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libandroid_net.so", "libbinder_ndk.so",
            "libmediandk.so", "libcgrouprc.so", "libselinux.so", "libaaudio.so", "libneuralnetworks.so"))
        for (d in listOf("system/etc", "system/system/etc", "vendor/etc")) File(root, d).listFiles().orEmpty()
            .filter { it.isFile && Regex("llndk[._]libraries([._][0-9]+)?\\.txt").matches(it.name) }
            .forEach { f -> runCatching { f.readLines().map { it.trim() }.filter { it.endsWith(".so") }.forEach { l -> llndk += l.substringAfterLast(' ') } } }
        
        
        
        
        
        
        
        val stackSet = listOf("android.hardware.graphics.mapper@2.0.so", "android.hardware.graphics.common@1.0.so", "libhidlbase.so",
            "libhwbinder.so", "libhidltransport.so")
        
        
        
        
        
        
        
        
        
        val newStackSym = "_ZNK7android4hidl4base4V1_08BnHwBase13checkSubclassEPKv"
        val vendorNeedsNewStack = runCatching {
            val engineDefs = ElfPatch.dynSyms64(File(root, "$vndkDir/libhidlbase.so"))?.defined
            engineDefs != null && newStackSym !in engineDefs &&
                File(root, "vendor/lib64").listFiles().orEmpty().any { f ->
                    f.isFile && f.name.endsWith(".so") && f.name !in stackSet && f.name.contains('@') &&
                        runCatching { ElfPatch.dynSyms64(f)?.undefined?.contains(newStackSym) == true }.getOrDefault(false)
                }
        }.getOrDefault(false)
        if (vendorNeedsNewStack) {
            val origDir = File(root, "system/.aemu-vintf-orig")
            val undone = ArrayList<String>()
            
            for (n in stackSet) {
                val dst = File(root, "vendor/lib64/$n")
                val keep = File(origDir, dst.relativeTo(root).path.replace('/', '#'))
                runCatching {
                    if (keep.isFile) {
                        dst.setWritable(true); keep.copyTo(dst, overwrite = true)
                        dst.setReadable(true, false); dst.setExecutable(true, false); undone += n
                    } else if (!isEngineStandIn(dst)) {
                        val src = File(root, "$vndkDir/$n")
                        if (dst.isFile && src.isFile && dst.length() == src.length() && dst.readBytes().contentEquals(src.readBytes()) && dst.delete()) undone += n
                    }
                }.onFailure { log("GL: $n could not be taken out of /vendor/lib64: ${it.message}") }
            }
            log("GL: the board's vendor HIDL libraries are built for the new stack, the engine's old one is not put into /vendor/lib64" +
                (if (undone.isEmpty()) "" else " (taken out again: ${undone.joinToString()})"))
        }
        
        
        
        
        
        
        if (vendorNeedsNewStack) {
            val gone = ArrayList<String>(); val fixed = ArrayList<String>()
            for (f in File(root, "vendor/lib64").listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".so") || f.name in stackSet) continue
                val engineCopy = File(root, "$vndkDir/${f.name}")
                if (!engineCopy.isFile || engineCopy.length() != f.length()) continue
                if (!runCatching { f.readBytes().contentEquals(engineCopy.readBytes()) }.getOrDefault(false)) continue
                val fwCopy = listOf("system/lib64", "system/vendor/lib64").map { File(root, "$it/${f.name}") }.firstOrNull { it.isFile }
                runCatching {
                    f.setWritable(true)
                    if (hasVndkPackage) { if (f.delete()) gone += f.name }
                    else if (fwCopy != null) {
                        fwCopy.copyTo(f, overwrite = true); f.setReadable(true, false); f.setExecutable(true, false); fixed += f.name
                    }
                }.onFailure { log("GL: stale ${f.name} could not be cleaned out of /vendor/lib64: ${it.message}") }
            }
            if (gone.isNotEmpty()) log("GL: the engine's old libraries taken out of /vendor/lib64 (the VNDK package has them): ${gone.joinToString()}")
            if (fixed.isNotEmpty()) log("GL: the engine's old libraries in /vendor/lib64 replaced by the firmware's own: ${fixed.joinToString()}")
        }
        if (!vendorNeedsNewStack && File(root, "vendor/lib64/hw/android.hardware.graphics.mapper@2.0-impl.so").isFile &&
            stackSet.all { File(root, "$vndkDir/$it").isFile }) {
            val origDir = File(root, "system/.aemu-vintf-orig")
            val swapped = ArrayList<String>()
            for (n in stackSet) {
                val src = File(root, "$vndkDir/$n"); val dst = File(root, "vendor/lib64/$n")
                runCatching {
                    if (dst.isFile && dst.length() == src.length() && dst.readBytes().contentEquals(src.readBytes())) return@runCatching
                    if (dst.isFile && !isEngineStandIn(dst)) backupOnce(dst, File(origDir, dst.relativeTo(root).path.replace('/', '#')))
                    if (dst.isFile) dst.setWritable(true)
                    src.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    swapped += n
                }.onFailure { log("GL: $n could not be put into /vendor/lib64: ${it.message}") }
            }
            if (swapped.isNotEmpty()) log("GL: passthrough mapper stack taken whole from ${vndkDir}: ${swapped.joinToString()}")
        }
        if (hasVndkPackage) return 
        val queue = ArrayDeque(roots)
        val seen = HashSet<String>()
        val brought = ArrayList<String>(); val missing = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur.name)) continue
            for (n in elfNeeded64(cur)) {
                if (n in llndk || n in seen) continue
                val inVendor = File(root, "vendor/lib64/$n")
                if (inVendor.isFile && vendorNeedsNewStack) {
                    
                    val engineCopy = File(root, "$vndkDir/$n")
                    val fwCopy = listOf("system/lib64", "system/vendor/lib64").map { File(root, "$it/$n") }.firstOrNull { it.isFile }
                    if (fwCopy != null && engineCopy.isFile && inVendor.length() == engineCopy.length() &&
                        inVendor.length() != fwCopy.length() && runCatching { inVendor.readBytes().contentEquals(engineCopy.readBytes()) }.getOrDefault(false)) {
                        runCatching {
                            inVendor.setWritable(true); fwCopy.copyTo(inVendor, overwrite = true)
                            inVendor.setReadable(true, false); inVendor.setExecutable(true, false)
                            brought += "$n (firmware's)"
                        }.onFailure { log("GL: $n could not be put right in /vendor/lib64: ${it.message}") }
                    }
                }
                if (inVendor.isFile) { queue.add(inVendor); continue }
                
                
                val srcDirs = if (vendorNeedsNewStack) listOf("system/lib64", "system/vendor/lib64", vndkDir) else listOf(vndkDir, "system/lib64", "system/vendor/lib64")
                val src = srcDirs.map { File(root, "$it/$n") }.firstOrNull { it.isFile }
                if (src == null) { if (n !in missing) missing += n; continue }
                runCatching {
                    src.copyTo(inVendor, overwrite = true)
                    inVendor.setReadable(true, false); inVendor.setExecutable(true, false)
                    brought += n; queue.add(inVendor)
                }.onFailure { log("GL: $n could not be put into /vendor/lib64: ${it.message}") }
            }
        }
        if (brought.isNotEmpty()) log("GL: no VNDK package in this tree; brought into /vendor/lib64 for SwiftShader and the passthrough mapper: ${brought.joinToString()}")
        if (missing.isNotEmpty()) log("GL: libraries of SwiftShader / the mapper not found anywhere in the tree: ${missing.joinToString()}")
    }

    
    private fun bringFirmwareHidlStackForAudio() {
        if (!kernel.treble || isPure32Tree(root)) return
        val dirRel = kernel.ownLibDirs["vendor#bin#hw#android.hardware.audio.service"]?.substringBefore(':') ?: return
        val dir = File(root, dirRel)
        if (!dir.isDirectory || !File(root, "vendor/bin/hw/android.hardware.audio.service").isFile) return
        val hasVndkPackage = File(root, "apex").listFiles().orEmpty().any {
            it.isDirectory && Regex("com\\.android\\.vndk\\.v\\d+").matches(it.name) && File(it, "lib64").isDirectory
        }
        
        
        
        
        
        
        
        val vndkPkgDirs = File(root, "apex").listFiles().orEmpty()
            .filter { it.isDirectory && Regex("com\\.android\\.vndk\\.v\\d+").matches(it.name) }
            .sortedByDescending { it.name }.map { "apex/${it.name}/lib64" }.filter { File(root, it).isDirectory }
        if (hasVndkPackage) log("audio: tree with a VNDK package (${vndkPkgDirs.joinToString { it.substringAfter('/').substringBefore('/') }}), the service's closure is made all the same")
        val fwDirs = listOf("system/lib64", "system/system/lib64", "system_ext/lib64", "system/system_ext/lib64") + vndkPkgDirs
        
        
        
        
        
        
        
        
        val snapshotDirs = File(root, "system/lib64").listFiles().orEmpty()
            .filter { it.isDirectory && !isLink(it) && Regex("vndk(-sp)?(-[0-9]+)?").matches(it.name) }
            .sortedByDescending { it.name }.map { "system/lib64/${it.name}" }
        val closureDirs = fwDirs + listOf("vendor/lib64", "system/vendor/lib64") + snapshotDirs
        fun fw(n: String) = closureDirs.map { File(root, "$it/$n") }.firstOrNull { it.isFile }
        fun has(f: File, sym: String) = runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains(sym) }.getOrDefault(false)
        val symInit = "_ZN7android8hardware12ProcessState16initWithMmapSizeEm"
        val symReg = "registerPassthroughServiceImplementationERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_S9_"
        
        val fwHidl = fwDirs.flatMap { d -> File(root, d).listFiles().orEmpty().toList() }
            .filter { it.isFile && (it.name.contains("hwbinder") || it.name.contains("hidlbase") || it.name.contains("hidltransport")) }
        log("audio: firmware HIDL libraries: " + (fwHidl.joinToString { "${it.parentFile.name}/${it.name} (${it.length()}b" +
            (if (has(it, symInit)) ", initWithMmapSize" else "") + (if (has(it, symReg)) ", registerPassthrough3" else "") + ")" }.ifEmpty { "none" }))
        
        val hwb = fwHidl.firstOrNull { has(it, symInit) }
        val hidl = fwHidl.firstOrNull { has(it, symReg) }
        val vndkDir = kernel.ownVndkDir?.let { File(root, it) }
        val engineHwb = vndkDir?.let { File(it, "libhwbinder.so") }?.takeIf { it.isFile && has(it, symInit) }
        val roots: List<File?> = listOf(hwb ?: engineHwb, hidl)
        if (hwb == null) log("audio: no firmware library has ProcessState::initWithMmapSize" +
            (if (engineHwb != null) ", the engine's own libhwbinder.so (aemu-vndk) takes the stand-in's place" else " and the engine has none either"))
        if (hidl == null) log("audio: no firmware library has the three-string registerPassthroughServiceImplementation: the service cannot link until libhidlbase of the 15+ stack is in the engine set")
        val llndk = HashSet(listOf("libc.so", "libm.so", "libdl.so", "liblog.so", "libnativewindow.so", "libsync.so", "libvndksupport.so",
            "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libandroid_net.so", "libbinder_ndk.so",
            "libmediandk.so", "libcgrouprc.so", "libselinux.so", "libaaudio.so", "libneuralnetworks.so"))
        for (d in listOf("system/etc", "system/system/etc", "vendor/etc")) File(root, d).listFiles().orEmpty()
            .filter { it.isFile && Regex("llndk[._]libraries([._][0-9]+)?\\.txt").matches(it.name) }
            .forEach { f -> runCatching { f.readLines().map { it.trim() }.filter { it.endsWith(".so") }.forEach { l -> llndk += l.substringAfterLast(' ') } } }
        
        
        
        
        
        
        llndk.removeAll(setOf("libapexsupport.so"))
        val brought = ArrayList<String>()
        val unresolved = sortedMapOf<String, String>() 
        val seen = HashSet<String>()
        val queue = ArrayDeque<File>()
        fun put(src: File, force: Boolean, name: String = src.name) {
            val dst = File(dir, name)
            runCatching {
                if (dst.isFile && !force && !isEngineStandIn(dst)) { queue.add(dst); return }
                if (dst.isFile && dst.length() == src.length() && dst.readBytes().contentEquals(src.readBytes())) { queue.add(dst); return }
                if (dst.isFile) dst.setWritable(true)
                src.copyTo(dst, overwrite = true)
                dst.setReadable(true, false); dst.setExecutable(true, false)
                brought += name; queue.add(dst)
            }.onFailure { log("audio: $name could not be put into /$dirRel: ${it.message}") }
        }
        
        roots.filterNotNull().forEach { src ->
            val wanted = if (has(src, symInit) && !src.name.contains("hidlbase")) "libhwbinder.so" else src.name
            put(src, force = true, name = wanted)
            
            if (src === engineHwb && queue.isNotEmpty()) queue.removeLast()
        }
        
        
        
        
        if (hwb != null) {
            File(root, "vendor/bin/hw/android.hardware.audio.service").takeIf { it.isFile }?.let { queue.add(it) }
            dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".so") && !isEngineStandIn(it) }.forEach { queue.add(it) }
            
            
            File(root, "vendor/lib64/hw").listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".so") && (it.name.startsWith("android.hardware.audio") || it.name.startsWith("audio.")) }
                .forEach { queue.add(it) }
            File(root, "vendor/lib64").listFiles().orEmpty()
                .filter { it.isFile && (it.name == "libeffects.so" || it.name == "libeffectsconfig.so") }.forEach { queue.add(it) }
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur.name)) continue
            for (n in elfNeeded64(cur)) {
                if (n in llndk || n in seen) continue
                val here = File(dir, n)
                if (here.isFile && !isEngineStandIn(here)) { queue.add(here); continue }
                val src = fw(n)
                if (src == null) { 
                    if (vndkDir == null || !File(vndkDir, n).isFile) unresolved.putIfAbsent(n, cur.name)
                    continue
                }
                put(src, force = false)
            }
        }
        if (brought.isNotEmpty()) log("audio: the new HIDL stack taken from the firmware into /$dirRel: ${brought.joinToString()}")
        
        if (unresolved.isNotEmpty()) log("audio: not found in the firmware nor in the engine's VNDK directory: " +
            unresolved.entries.joinToString { "${it.key} (needed by ${it.value})" })
    }

    
    private fun isEngineStandIn(f: File) = f.name in setOf("libhwbinder.so", "libhidltransport.so") && f.length() < 64 * 1024

    
    private fun hasHwServiceManager() = File(root, "system/bin/hwservicemanager").isFile && !File(root, ENGINE_HWSM_MARKER).isFile

    
    private fun useEngineHwServiceManager(): Boolean {
        if (kernel !in ENGINE_HWSM_KERNELS || isPure32Tree(root)) return false
        val dst = File(root, "system/bin/hwservicemanager")
        val marker = File(root, ENGINE_HWSM_MARKER)
        val src = File(root, "system/bin/aemu-hwservicemanager")
        if (dst.isFile) {
            
            val ours = marker.isFile && runCatching { marker.readText().trim().toLong() == dst.length() }.getOrDefault(false)
            if (!ours) {
                if (marker.isFile) marker.delete()
                log("hwservicemanager: the firmware has its own (${dst.length()} bytes), the engine's is not used")
                return false
            }
        }
        if (!src.isFile) {
            log("hwservicemanager: the engine's aemu-hwservicemanager is not in the tree (built with native/guest64/37/build-hwsm.sh), HIDL stays passthrough")
            return false
        }
        runCatching {
            if (!dst.isFile || dst.length() != src.length() || !dst.readBytes().contentEquals(src.readBytes())) {
                if (isLink(dst)) Os.remove(dst.absolutePath)
                dst.parentFile?.mkdirs()
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                log("hwservicemanager: this image (Android ${img.api}) has none, the engine's hwservicemanager installed (${dst.length()} bytes)")
            }
            dst.setReadable(true, false); dst.setExecutable(true, false)
            marker.parentFile?.mkdirs()
            marker.writeText(dst.length().toString())
        }.onFailure {
            log("hwservicemanager: could not be put into /system/bin: ${it.message}")
            return false
        }
        return true
    }

    
    private fun restoreComposerHwbinderDeclaration() {
        if (!kernel.treble || isPure32Tree(root)) return
        val origDir = File(root, "system/.aemu-vintf-orig")
        val seen = HashSet<String>()
        for (f in deviceManifestFiles()) {
            if (!f.isFile || !f.name.endsWith(".xml")) continue
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                val orig = File(origDir, f.relativeTo(root).path.replace('/', '#')).takeIf { it.isFile }?.readText()
                fun isComposerHidl(block: String) =
                    manifestHalName.find(block)?.groupValues?.get(1) == "android.hardware.graphics.composer" &&
                        manifestHalFormat.find(block)?.groupValues?.get(1) == "hidl"
                var changed = false
                val out = manifestHalBlock.replace(text) { m ->
                    
                    if (isComposerHidl(m.value) && m.value.contains("passthrough") && orig != null &&
                        manifestHalBlock.findAll(orig).any { o -> isComposerHidl(o.value) && o.value.contains("hwbinder") }) {
                        changed = true
                        m.value.replace(Regex("<transport[^>]*>[^<]*</transport>"), "<transport>hwbinder</transport>")
                    } else m.value
                }
                if (changed) {
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: composer@2.1 declared hwbinder again (the engine's hwservicemanager serves it)")
                }
            }.onFailure { log("${f.name}: composer transport not restored: ${it.message}") }
        }
    }

    private val manifestHalBlock = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
    private val manifestHalName = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
    private val manifestHalFormat = Regex("<hal\\b[^>]*\\bformat=\"([a-z]+)\"")

    private fun deviceManifestFiles(): List<File> =
        listOf("vendor/etc/vintf/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml").map { File(root, it) } +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest").flatMap { File(root, it).listFiles().orEmpty().toList() }

    
    private fun elfNeeded64(f: File): List<String> = runCatching {
        val b = f.readBytes()
        fun u16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        fun u64(o: Int) = u32(o) or (u32(o + 4) shl 32)
        if (b.size < 64 || b[0] != 0x7f.toByte() || b[1] != 'E'.code.toByte() || b[4] != 2.toByte()) return@runCatching emptyList<String>()
        val phoff = u64(0x20).toInt(); val phentsize = u16(0x36); val phnum = u16(0x38)
        fun fileOff(v: Long): Int? {
            for (i in 0 until phnum) {
                val ph = phoff + i * phentsize
                if (u32(ph) != 1L) continue 
                val o = u64(ph + 8); val va = u64(ph + 16); val fs = u64(ph + 32)
                if (v >= va && v < va + fs) return (v - va + o).toInt()
            }
            return null
        }
        var dyn = -1; var dynSize = 0
        for (i in 0 until phnum) {
            val ph = phoff + i * phentsize
            if (u32(ph) == 2L) { dyn = u64(ph + 8).toInt(); dynSize = u64(ph + 32).toInt() } 
        }
        if (dyn < 0) return@runCatching emptyList<String>()
        var strtab = -1
        val needed = ArrayList<Long>()
        var q = dyn
        while (q + 16 <= dyn + dynSize && q + 16 <= b.size) {
            val tag = u64(q); val v = u64(q + 8)
            if (tag == 0L) break
            if (tag == 1L) needed += v
            if (tag == 5L) strtab = fileOff(v) ?: -1
            q += 16
        }
        if (strtab < 0) return@runCatching emptyList<String>()
        needed.map { n ->
            val st = strtab + n.toInt(); var e = st
            while (e < b.size && b[e].toInt() != 0) e++
            String(b, st, e - st, Charsets.ISO_8859_1)
        }
    }.getOrDefault(emptyList())

    
    private fun useComposerPassthroughWithoutHwServiceManager() {
        
        if (!kernel.treble || isPure32Tree(root) || hasHwServiceManager()) return
        val impl = File(root, "vendor/lib64/hw/android.hardware.graphics.composer@2.1-impl.so")
        if (!impl.isFile) { log("composer: no composer@2.1-impl.so under vendor/lib64/hw, passthrough not set up"); return }
        val origDir = File(root, "system/.aemu-vintf-orig")
        val seen = HashSet<String>()
        for (f in deviceManifestFiles()) {
            if (!f.isFile || !f.name.endsWith(".xml")) continue
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                var changed = false
                val out = manifestHalBlock.replace(f.readText()) { m ->
                    val n = manifestHalName.find(m.value)?.groupValues?.get(1)
                    val fmt = manifestHalFormat.find(m.value)?.groupValues?.get(1)
                    if (n == "android.hardware.graphics.composer" && fmt == "hidl" && !m.value.contains("passthrough")) {
                        changed = true
                        m.value.replace(Regex("<transport[^>]*>[^<]*</transport>"), "<transport arch=\"32+64\">passthrough</transport>")
                    } else m.value
                }
                if (changed) {
                    backupOnce(f, File(origDir, f.relativeTo(root).path.replace('/', '#')))
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: composer@2.1 declared as passthrough (no hwservicemanager on this release)")
                }
            }.onFailure { log("${f.name}: composer transport not changed: ${it.message}") }
        }
        
        val reach = listOf("vendor/lib64", "odm/lib64")
        val platform = listOf("system/lib64", "system_ext/lib64", "apex/com.android.runtime/lib64/bionic", "apex/com.android.runtime/lib64")
        val vndk = kernel.ownVndkDir?.let { listOf(it) }.orEmpty() + "system/vendor/lib64"
        fun find(dirs: List<String>, n: String) = dirs.map { File(root, "$it/$n") }.firstOrNull { it.isFile }
        val queue = ArrayDeque(listOf(impl, File(root, "vendor/lib64/libhwc2on1adapter.so"), File(root, "vendor/lib64/libhwc2onfbadapter.so")).filter { it.isFile })
        val done = HashSet<String>()
        val brought = ArrayList<String>(); val missing = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!done.add(cur.name)) continue
            for (n in elfNeeded64(cur)) {
                if (n in done) continue
                val have = find(reach, n)
                if (have != null) { queue.add(have); continue }
                val plat = find(platform, n)
                if (plat != null && !ComposerLibs.bringable(n)) continue 
                val src = plat ?: find(vndk, n)
                if (src == null) { if (n !in missing) missing += n; continue }
                val dst = File(root, "vendor/lib64/$n")
                runCatching {
                    dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    brought += n; queue.add(dst)
                }.onFailure { log("composer: $n could not be put into /vendor/lib64: ${it.message}") }
            }
        }
        if (brought.isNotEmpty()) log("composer: brought into /vendor/lib64 for the passthrough impl: ${brought.joinToString()}")
        if (missing.isNotEmpty()) log("composer: libraries of composer@2.1-impl not found anywhere in the tree: ${missing.joinToString()}")
        else log("composer: every library of ${impl.name} reachable (passthrough in SurfaceFlinger)")
    }

    
    private fun restoreFirmwareAidlDeclarations() {
        if (!kernel.treble || !kernel.manifestFragmentsAside || isPure32Tree(root) || hasHwServiceManager()) return
        val parkedDir = File(root, "system/.aemu-parked")
        val parked = parkedDir.listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(".xml") &&
                (it.name.startsWith("vendor#etc#vintf#manifest#") || it.name.startsWith("odm#etc#vintf#manifest#"))
        }
        
        
        val boardAidlLight = parked.any { pf ->
            runCatching {
                manifestHalBlock.findAll(pf.readText()).any { m ->
                    manifestHalName.find(m.value)?.groupValues?.get(1) == "android.hardware.light" && manifestHalFormat.find(m.value)?.groupValues?.get(1) == "aidl"
                }
            }.getOrDefault(false)
        }
        if (boardAidlLight) for (f in deviceManifestFiles()) {
            if (!f.isFile || !f.name.endsWith(".xml") || f.name.startsWith("aemu-fw-")) continue
            runCatching {
                var dropped = false
                val out = manifestHalBlock.replace(f.readText()) { m ->
                    if (manifestHalName.find(m.value)?.groupValues?.get(1) == "android.hardware.light" && manifestHalFormat.find(m.value)?.groupValues?.get(1) == "hidl") {
                        dropped = true; ""
                    } else m.value
                }
                if (dropped) {
                    backupOnce(f, File(File(root, "system/.aemu-vintf-orig"), f.relativeTo(root).path.replace('/', '#')))
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: engine HIDL light declaration dropped (the board's AIDL lights HAL is declared)")
                }
            }
        }
        val engineNames = HashSet<String>()
        for (f in deviceManifestFiles()) {
            if (!f.isFile || f.name.startsWith("aemu-fw-")) continue
            runCatching { manifestHalBlock.findAll(f.readText()).forEach { m -> manifestHalName.find(m.value)?.groupValues?.get(1)?.let { engineNames += it } } }
        }
        val deny = setOf(
            "android.hardware.graphics.composer3", "android.hardware.graphics.composer", "android.hardware.graphics.allocator",
            "android.hardware.graphics.mapper", "mapper", "android.hardware.power", "android.hardware.audio.core",
            "android.hardware.audio.core.sounddose", "android.hardware.audio.effect", "android.hardware.audio",
            "android.hardware.security.keymint", "android.hardware.security.secretkeeper", "android.hardware.security.authgraph",
            "android.hardware.gatekeeper", "android.hardware.weaver", "android.hardware.authsecret", "android.hardware.oemlock",
            "android.hardware.keymaster", "android.hardware.health", "android.hardware.sensors", "android.hardware.drm",
            "android.hardware.media.omx", "android.hardware.configstore",
            
            
            
            
            "android.hardware.soundtrigger3") + parkedHalPackages()
        val written = ArrayList<String>()
        for (pf in parked) {
            runCatching {
                val kept = StringBuilder()
                val names = ArrayList<String>()
                for (m in manifestHalBlock.findAll(pf.readText())) {
                    val n = manifestHalName.find(m.value)?.groupValues?.get(1) ?: continue
                    val fmt = manifestHalFormat.find(m.value)?.groupValues?.get(1)
                    if (fmt != "aidl") continue
                    if (n in deny || n.startsWith("vendor.qti.hardware.display.")) continue
                    
                    if (n in engineNames) continue
                    if (n in names) continue
                    names += n; kept.append(m.value.trimEnd()).append('\n')
                }
                val dstDir = File(root, if (pf.name.startsWith("odm#")) "odm/etc/vintf/manifest" else "vendor/etc/vintf/manifest")
                val dst = File(dstDir, "aemu-fw-" + pf.name.substringAfterLast('#'))
                if (names.isEmpty()) { if (dst.isFile) dst.delete(); return@runCatching }
                val xml = "<manifest version=\"1.0\" type=\"device\">\n$kept</manifest>\n"
                if (!dst.isFile || dst.readText() != xml) {
                    dstDir.mkdirs(); dst.writeText(xml); dst.setReadable(true, false)
                    written += "${dst.name}(${names.joinToString(",") { it.removePrefix("android.hardware.") }})"
                }
            }.onFailure { log("${pf.name}: board AIDL declarations not restored: ${it.message}") }
        }
        if (written.isNotEmpty()) log("vintf: board AIDL declarations restored for the board's own services: ${written.joinToString("; ")}")
    }

    private val FRAG_COMPOSER2 = "aemu.graphics.composer2.xml"

    
    private fun useEngineComposerOnPure32() {
        if (!kernel.treble || !isPure32Tree(root)) { undoEngineComposer(); return }
        
        
        fun is32BitElf(f: File) = runCatching {
            f.inputStream().use { i -> ByteArray(5).also { i.read(it) }.let { h -> h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[4] == 1.toByte() } }
        }.getOrDefault(false)
        val svc = listOf("vendor/bin/hw", "system/vendor/bin/hw").map { File(root, "$it/android.hardware.graphics.composer@2.1-service") }
            .firstOrNull { it.isFile } ?: run {
                
                
                undoEngineComposer()
                log("composer: the engine's composer@2.1 service is not in the tree, the board's stays")
                return
            }
        val libDirs = listOf("vendor/lib/hw", "system/vendor/lib/hw")
        fun hasLib(n: String) = libDirs.any { File(root, "$it/$n").isFile }
        if (!is32BitElf(svc) || !hasLib("android.hardware.graphics.composer@2.1-impl.so") || !hasLib("hwcomposer.default.so")) {
            undoEngineComposer()
            log("composer: the engine's 32-bit composer@2.1 is incomplete in the tree, the board's stays")
            return
        }
        
        
        
        runCatching { ComposerLibs.complete(root, log) }.onFailure { log("composer: library check failed: ${it.message}") }
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        val gone = setOf("vendor.qti.hardware.display.composer", "vendor.display.config", "android.hardware.graphics.composer",
            "android.hardware.graphics.composer3")
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val origDir = File(root, "system/.aemu-vintf-orig")
        val monolithic = listOf("vendor/etc/vintf/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml")
        val fragDirs = listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest")
        val seen = HashSet<String>()
        for (f in fragDirs.flatMap { File(root, it).listFiles().orEmpty().toList() } + monolithic.map { File(root, it) }) {
            if (!f.isFile || !f.name.endsWith(".xml") || f.name == FRAG_COMPOSER2) continue
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val removed = ArrayList<String>()
                val out = hal.replace(f.readText()) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in gone) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    backupOnce(f, File(origDir, f.relativeTo(root).path.replace('/', '#')))
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: board composer declaration dropped: ${removed.distinct().joinToString()}")
                }
            }.onFailure { log("${f.name}: composer declarations not checked: ${it.message}") }
        }
        val xml = """<manifest version="1.0" type="device">
    <hal format="hidl">
        <name>android.hardware.graphics.composer</name>
        <transport>hwbinder</transport>
        <version>2.1</version>
        <interface><name>IComposer</name><instance>default</instance></interface>
    </hal>
</manifest>
"""
        val dst = File(root, "vendor/etc/vintf/manifest/$FRAG_COMPOSER2")
        dst.parentFile?.mkdirs()
        if (!dst.isFile || dst.readText() != xml) { dst.writeText(xml); dst.setReadable(true, false); log("${dst.name}: composer@2.1 declared (32-bit firmware, the engine's composer)") }
        
        for (d in listOf("vendor/bin/hw", "system/vendor/bin/hw")) {
            val q = File(root, "$d/vendor.qti.hardware.display.composer-service")
            if (!q.isFile) continue
            val park = File(root, "system/.aemu-parked/${q.relativeTo(root).path.replace('/', '#')}")
            park.parentFile?.mkdirs(); park.delete()
            if (q.renameTo(park)) log("${q.name}: set aside (replaced by the engine's composer@2.1)")
        }
    }

    
    private fun undoEngineComposer() {
        for (d in listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest")) {
            val frag = File(root, "$d/$FRAG_COMPOSER2")
            if (frag.isFile || isLink(frag)) {
                if (frag.delete()) log("${frag.relativeTo(root).path}: removed (the board's own composer declaration is used)")
            }
        }
        for (d in listOf("vendor/bin/hw", "system/vendor/bin/hw")) {
            val q = File(root, "$d/vendor.qti.hardware.display.composer-service")
            if (q.isFile || isLink(q)) continue
            val park = File(root, "system/.aemu-parked/${q.relativeTo(root).path.replace('/', '#')}")
            if (!park.isFile) continue
            q.parentFile?.mkdirs()
            if (park.renameTo(q)) log("${q.name}: put back (the board's composer is used)")
        }
    }

    
    private fun disableHardwareVibratorHalOfPure32() {
        if (!kernel.treble || !isPure32Tree(root)) return
        
        
        val vendorBin = Regex("^android\\.hardware\\.vibrator-service\\.(?!(default|example)$).+$")
        val bin = vendorBin
        val seen = HashSet<String>()
        var parked = 0
        for (d in listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")) File(root, d).listFiles()?.forEach { f ->
            if (!bin.matches(f.name) || (!f.isFile && !isLink(f))) return@forEach
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
            if (parkFile(f)) { parked++; log("${f.name}: set aside (no haptic hardware in the guest; it dies on its first call)") }
        }
        
        val parkedBefore = File(root, "system/.aemu-parked").list().orEmpty().any { bin.matches(it.substringAfterLast('#')) }
        if (parked == 0 && !parkedBefore) return
        val fragDirs = listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest")
        val origDir = File(root, "system/.aemu-vintf-orig")
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*android\\.hardware\\.vibrator\\s*</name>")
        val done = HashSet<String>()
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml")
            .map { File(root, it) } +
            fragDirs.flatMap { d -> File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty() }
        for (f in files) {
            if (!f.isFile || !done.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                var removed = 0
                val out = hal.replace(text) { m -> if (name.containsMatchIn(m.value)) { removed++; "" } else m.value }
                if (removed > 0) {
                    if (f.parentFile?.relativeTo(root)?.path in fragDirs) backupOnce(f, File(origDir, f.relativeTo(root).path.replace('/', '#')))
                    f.setWritable(true)
                    f.writeText(out)
                    log("${f.relativeTo(root).path}: vibrator HAL declaration dropped")
                }
            }.onFailure { log("${f.name}: vibrator declaration not checked: ${it.message}") }
        }
    }

    private val FRAG_KEYMASTER3 = "aemu.keymaster3.xml"

    
    private fun declareInMonolithicManifests(halName: String, halXml: String) {
        
        
        
        
        if (img.api >= 29) {
            for (rel in listOf("vendor/etc/vintf/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml")) {
                val f = File(root, rel)
                if (!f.isFile) continue
                runCatching {
                    val text = f.readText()
                    if (!text.contains(halXml)) return@runCatching
                    f.setWritable(true); f.writeText(text.replace(halXml, "")); f.setReadable(true, false)
                    log("$rel: $halName declaration of the engine removed (the fragment declares it, Android ${img.api} reads both)")
                }.onFailure { log("$rel: $halName declaration not removed: ${it.message}") }
            }
            return
        }
        val seenM = HashSet<String>()
        val nameRe = Regex("<name>\\s*" + Regex.escape(halName) + "\\s*</name>")
        for (rel in listOf("vendor/etc/vintf/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml")) {
            val f = File(root, rel)
            if (!f.isFile || !seenM.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                if (nameRe.containsMatchIn(text)) return@runCatching
                val at = text.lastIndexOf("</manifest>")
                if (at < 0) return@runCatching
                f.setWritable(true); f.writeText(text.substring(0, at) + halXml + text.substring(at)); f.setReadable(true, false)
                log("$rel: $halName declared in the monolithic manifest (the fragment directory is not read before Android 10)")
            }.onFailure { log("$rel: $halName declaration not added: ${it.message}") }
        }
    }

    
    private fun skuManifests(): List<File> =
        listOf("vendor/etc/vintf", "system/vendor/etc/vintf", "odm/etc/vintf").flatMap { d ->
            File(root, d).listFiles()?.filter { it.isFile && it.name.startsWith("manifest_") && it.name.endsWith(".xml") }?.sortedBy { it.name }.orEmpty()
        }

    
    private fun useSoftwareKeymasterOnPure32() {
        if (!kernel.treble || !isPure32Tree(root)) return
        val binDirs = listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")
        val libDirs = listOf("vendor/lib/hw", "system/vendor/lib/hw", "odm/lib/hw")
        
        
        
        
        
        val qseeBin = Regex("^android\\.hardware\\.(keymaster|gatekeeper)(@[0-9.]+)?-service[.-]qti$")
        val qseeLib = Regex("^android\\.hardware\\.(keymaster|gatekeeper)(@[0-9.]+)?-impl[.-]qti\\.so$")
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        
        val teeBin = Regex("^android\\.hardware\\.(keymaster|gatekeeper)@[0-9.]+(?:[_-]tee-service(\\..*)?|-service\\.amlogic)$")
        val teeLib = Regex("^android\\.hardware\\.(keymaster|gatekeeper)@[0-9.]+-impl[._-](tee|amlogic)\\.so$")
        val plainGatekeeperBin = Regex("^android\\.hardware\\.gatekeeper@[0-9.]+-service$")
        val parkedDir = File(root, "system/.aemu-parked")
        fun park(f: File, why: String): Boolean {
            val dst = File(parkedDir, f.relativeTo(root).path.replace('/', '#'))
            dst.parentFile?.mkdirs()
            if (dst.exists() || isLink(dst)) dst.delete()
            return f.renameTo(dst).also { if (it) log("${f.name}: set aside ($why)") }
        }
        var qsee = false
        var tee = false
        val seen = HashSet<String>()
        for (d in binDirs) File(root, d).listFiles()?.forEach { f ->
            val isTee = teeBin.matches(f.name)
            if (!(qseeBin.matches(f.name) || isTee) || (!f.isFile && !isLink(f))) return@forEach
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
            qsee = true; if (isTee) tee = true
            park(f, if (isTee) "it needs the TEE, which the guest does not have" else "it needs the secure world (/dev/qseecom)")
        }
        for (d in libDirs) File(root, d).listFiles()?.forEach { f ->
            val isTee = teeLib.matches(f.name)
            if (!(qseeLib.matches(f.name) || isTee) || (!f.isFile && !isLink(f))) return@forEach
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
            qsee = true; if (isTee) tee = true
            park(f, "it waits for the secure world for ever")
        }
        
        val parkedNames = parkedDir.list().orEmpty().map { it.substringAfterLast('#') }
        if (!qsee) qsee = parkedNames.any { n -> qseeBin.matches(n) || qseeLib.matches(n) || teeBin.matches(n) || teeLib.matches(n) }
        if (!tee) tee = parkedNames.any { n -> teeBin.matches(n) || teeLib.matches(n) }
        
        
        
        if (tee) for (d in binDirs) File(root, d).listFiles()?.forEach { f ->
            if (!plainGatekeeperBin.matches(f.name) || (!f.isFile && !isLink(f))) return@forEach
            if (!seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
            park(f, "its gatekeeper module needs the TEE, there is none here")
        }
        
        
        
        
        
        val hostThere = binDirs.any { File(root, "$it/android.hardware.keymaster@3.0-service").isFile } &&
            (libDirs + listOf("vendor/lib")).any { File(root, "$it/android.hardware.keymaster@3.0-impl.so").isFile }
        if (!qsee && !hostThere) return
        
        if (!qsee) {
            val kmHal = Regex("<hal\\b[^>]*>(?:(?!</hal>).)*?<name>\\s*android\\.hardware\\.keymaster\\s*</name>.*?</hal>", RegexOption.DOT_MATCHES_ALL)
            val ver = Regex("<version>\\s*([0-9.]+)\\s*</version>")
            val own = (listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml") +
                listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest", "odm/etc/vintf/manifest").flatMap { d -> File(root, d).list().orEmpty().filter { it.endsWith(".xml") && it != FRAG_KEYMASTER3 }.map { "$d/$it" } })
                .map { File(root, it) }.filter { it.isFile }
                .any { f -> runCatching { kmHal.findAll(f.readText()).any { m -> ver.findAll(m.value).any { !it.groupValues[1].startsWith("3.") } } }.getOrDefault(false) }
            if (own) return
        }
        if (!hostThere) log("keymaster: the engine has no 32-bit software keymaster for this release; keystore will have no keymaster")
        val gone = HashSet<String>().apply { if (qsee) add("android.hardware.gatekeeper"); if (hostThere) add("android.hardware.keymaster") }
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml",
            "odm/etc/vintf/manifest.xml").map { File(root, it) } + skuManifests() +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest", "odm/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") && it.name != FRAG_KEYMASTER3 }.orEmpty()
            }
        val seenXml = HashSet<String>()
        for (f in files) {
            if (!f.isFile || !seenXml.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val removed = ArrayList<String>()
                val out = hal.replace(f.readText()) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in gone) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: declarations dropped (${removed.distinct().joinToString()}; no secure world here)")
                }
            }.onFailure { log("${f.name}: keymaster/gatekeeper declarations not checked: ${it.message}") }
        }
        if (!hostThere) return
        val xml = """<manifest version="1.0" type="device">
    <hal format="hidl">
        <name>android.hardware.keymaster</name>
        <transport>hwbinder</transport>
        <version>3.0</version>
        <interface><name>IKeymasterDevice</name><instance>default</instance></interface>
    </hal>
</manifest>
"""
        val dst = File(root, "vendor/etc/vintf/manifest/$FRAG_KEYMASTER3")
        dst.parentFile?.mkdirs()
        if (!dst.isFile || dst.readText() != xml) { dst.writeText(xml); dst.setReadable(true, false); log("${dst.name}: keymaster@3.0 declared (software keymaster, 32-bit firmware)") }
        declareInMonolithicManifests("android.hardware.keymaster", """    <hal format="hidl">
        <name>android.hardware.keymaster</name>
        <transport>hwbinder</transport>
        <version>3.0</version>
        <interface>
            <name>IKeymasterDevice</name>
            <instance>default</instance>
        </interface>
    </hal>
""")
        
        
        
        
        
        
        
        val legacyHal = """    <hal format="hidl">
        <name>android.hardware.keymaster</name>
        <transport>hwbinder</transport>
        <version>3.0</version>
        <interface>
            <name>IKeymasterDevice</name>
            <instance>default</instance>
        </interface>
    </hal>
"""
        val monolithicThere = listOf("vendor/etc/vintf/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml")
            .any { File(root, it).isFile }
        if (!monolithicThere) {
            val seenLegacy = HashSet<String>()
            for (rel in listOf("vendor/manifest.xml", "system/vendor/manifest.xml")) {
                val f = File(root, rel)
                if (!f.isFile || !seenLegacy.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
                runCatching {
                    val text = f.readText()
                    if (Regex("<name>\\s*android\\.hardware\\.keymaster\\s*</name>").containsMatchIn(text)) return@runCatching
                    val at = text.lastIndexOf("</manifest>")
                    if (at < 0) return@runCatching
                    f.setWritable(true); f.writeText(text.substring(0, at) + legacyHal + text.substring(at)); f.setReadable(true, false)
                    log("$rel: keymaster@3.0 declared (software keymaster, legacy manifest)")
                }.onFailure { log("$rel: keymaster declaration not added: ${it.message}") }
            }
        }
        
        
        
        
        
        
        
        runCatching { KeymasterLibs.complete(root, log) }.onFailure { log("keymaster: library check failed: ${it.message}") }
    }

    
    private val X86_AUDIO_FILES = setOf("vendor#bin#hw#android.hardware.audio@2.0-service", "vendor#etc#init#android.hardware.audio@2.0-service.rc",
        "vendor#etc#vintf#manifest#aemu.audio.xml", "vendor#lib64#hw#android.hardware.audio@2.0-impl.so", "vendor#lib64#hw#android.hardware.audio.effect@2.0-impl.so",
        "vendor#lib64#hw#audio.primary.default.so", "vendor#lib64#libeffects.so", "vendor#lib64#android.hardware.audio.common-util.so",
        "vendor#lib64#android.hardware.audio.common@2.0-util.so", "vendor#etc#audio_policy_configuration.xml", "vendor#etc#audio_policy_volumes.xml",
        "vendor#etc#default_volume_tables.xml", "vendor#etc#primary_audio_policy_configuration.xml")

    private fun isEngineX86Audio(name: String, f: File): Boolean =
        runCatching { x86Asset(name).let { a -> f.length() == eaSize(a) && sameContent(a, f) } }.getOrDefault(false)

    
    private fun x86AudioWanted(): Boolean {
        if (!kernel.treble || img.api !in 29..30) return false
        val halDirs = listOf("vendor/lib64/hw", "vendor/lib/hw", "system/lib64/hw", "system/lib/hw", "system/vendor/lib64/hw", "system/vendor/lib/hw", "odm/lib64/hw", "odm/lib/hw")
        val ownImpl = halDirs.any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.audio@") && it.name.contains("-impl") && !isEngineX86Audio("vendor#lib64#hw#${it.name}", it) } == true
        }
        val ownService = listOf("vendor/etc/init", "system/vendor/etc/init", "odm/etc/init").any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.audio") && it.name.endsWith(".rc") && !isEngineX86Audio("vendor#etc#init#${it.name}", it) } == true
        }
        return !ownImpl && !ownService
    }

    
    private val X86_COMPOSER_FILES = setOf("vendor#bin#hw#android.hardware.graphics.composer@2.1-service", "vendor#etc#init#android.hardware.graphics.composer@2.1-service.rc",
        "vendor#etc#vintf#manifest#aemu.composer.xml", "vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so",
        "vendor#lib64#android.hardware.graphics.composer@2.1-resources.so")

    
    private fun isEngineX86File(name: String, f: File): Boolean =
        runCatching { x86Asset(name).let { a -> f.length() == eaSize(a) && sameContent(a, f) } }.getOrDefault(false) || isSupersededX86Engine(name, f)

    
    private fun x86ComposerWanted(): Boolean {
        if (!kernel.treble || img.api !in 29..30) return false
        val halDirs = listOf("vendor/lib64/hw", "vendor/lib/hw", "system/lib64/hw", "system/lib/hw", "system/vendor/lib64/hw", "system/vendor/lib/hw", "odm/lib64/hw", "odm/lib/hw")
        val ownImpl = halDirs.any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.graphics.composer@") && it.name.contains("-impl") && !isEngineX86File("vendor#lib64#hw#${it.name}", it) } == true
        }
        val ownService = listOf("vendor/etc/init", "system/vendor/etc/init", "odm/etc/init").any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.graphics.composer") && it.name.endsWith(".rc") && !isEngineX86File("vendor#etc#init#${it.name}", it) } == true
        }
        return !ownImpl && !ownService
    }

    
    private val X86_KEYMASTER_ANSWERER_FILES = setOf("vendor#bin#hw#aemu-keymaster-answerer", "vendor#etc#init#aemu-keymaster-answerer.rc")

    
    private fun isKiwiDevice(): Boolean {
        val key = Regex("(?m)^\\s*ro\\.product\\.system\\.device\\s*=\\s*(.*?)\\s*$")
        for (rel in listOf("system/build.prop", "system/system/build.prop", "build.prop")) {
            val text = runCatching { File(root, rel).takeIf { it.isFile }?.readText() }.getOrNull() ?: continue
            val v = key.find(text)?.groupValues?.get(1) ?: continue
            return v.contains("vsoc_kiwi_")
        }
        return false
    }

    
    private fun hasRemoteKeymasterProgram(): Boolean =
        hwPrograms("android.hardware.keymaster@").any { it.name.contains("-service.remote") } ||
            File(root, "system/.aemu-parked").list().orEmpty().any { it.startsWith("vendor#bin#hw#android.hardware.keymaster@") && it.contains("-service.remote") }

    
    private fun x86KeymasterAnswererWanted(): Boolean {
        if (!kernel.treble || img.arch != GuestArch.X86_64) return false
        if (!isKiwiDevice() || !hasRemoteKeymasterProgram()) return false
        return EngineAssets.exists(ctx, x86Asset("vendor#bin#hw#aemu-keymaster-answerer"))
    }

    
    private fun restoreRemoteKeymaster() {
        var n = 0
        for (f in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            if (!f.isFile || !f.name.contains("android.hardware.keymaster@") || !f.name.contains("-service.remote")) continue
            val own = File(root, f.name.replace('#', '/'))
            if (own.exists()) continue
            own.parentFile?.mkdirs()
            if (f.renameTo(own)) n++
        }
        log(if (n > 0) "keymaster: the remote keymaster service is back ($n files); aemu-keymaster-answerer answers it on /dev/hvc1"
            else "keymaster: the remote keymaster service is kept; aemu-keymaster-answerer answers it on /dev/hvc1")
    }

    
    private val X86_HEALTH_FILES = setOf("vendor#bin#hw#android.hardware.health@2.0-service", "vendor#etc#init#android.hardware.health@2.0-service.rc",
        "vendor#etc#vintf#manifest#aemu.health.xml")

    
    private fun x86HealthWanted(): Boolean {
        if (!kernel.treble || img.api !in 29..30) return false
        val halDirs = listOf("vendor/lib64/hw", "vendor/lib/hw", "system/lib64/hw", "system/lib/hw", "system/vendor/lib64/hw", "system/vendor/lib/hw", "odm/lib64/hw", "odm/lib/hw")
        val binDirs = listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw", "system/bin/hw")
        val ownImpl = halDirs.any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.health@") && it.name.contains("-impl") } == true
        }
        val ownService = binDirs.any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.health@") && !isEngineX86File("vendor#bin#hw#${it.name}", it) } == true
        }
        val ownRc = listOf("vendor/etc/init", "system/vendor/etc/init", "odm/etc/init", "system/etc/init").any { d ->
            File(root, d).listFiles()?.any { it.isFile && it.name.startsWith("android.hardware.health@") && it.name.endsWith(".rc") && !isEngineX86File("vendor#etc#init#${it.name}", it) } == true
        }
        return !ownImpl && !ownService && !ownRc
    }

    
    private fun ensureDeviceManifest() {
        if (!kernel.treble) return
        val own = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml", "system/vendor/manifest.xml", "odm/etc/vintf/manifest.xml", "odm/manifest.xml")
        if (own.any { File(root, it).isFile }) return
        val level = when { img.api <= 26 -> 1; img.api == 27 -> 2; img.api == 28 -> 3; img.api == 29 -> 4; img.api == 30 -> 5; img.api <= 32 -> 6; img.api == 33 -> 7; else -> 8 }
        runCatching {
            val f = File(root, "vendor/etc/vintf/manifest.xml")
            f.parentFile?.mkdirs()
            f.writeText("<manifest version=\"2.0\" type=\"device\" target-level=\"$level\">\n</manifest>\n")
            f.setReadable(true, false)
            log("vintf: the firmware has no device manifest, an empty one (target-level $level) is written to vendor/etc/vintf/manifest.xml so that the HAL declarations of the fragment directory count")
        }.onFailure { log("vintf: device manifest not written: ${it.message}") }
    }

    private val FRAG_GRALLOC2 = "aemu.graphics.gralloc2.xml"
    
    private val X86_GRALLOC2_FILES = setOf("vendor#bin#hw#android.hardware.graphics.allocator@2.0-service", "vendor#etc#init#android.hardware.graphics.allocator@2.0-service.rc",
        "vendor#lib64#hw#android.hardware.graphics.allocator@2.0-impl.so", "vendor#lib64#hw#android.hardware.graphics.mapper@2.0-impl.so",
        "vendor#lib64#hw#gralloc.default.so", "vendor#lib64#libgralloc_aosp.so")
    
    private val ENGINE_HWSM_MARKER = "system/.aemu-engine-hwservicemanager"
    
    private val ENGINE_HWSM_KERNELS = setOf(Kernel64.A15, Kernel64.A16, Kernel64.A17)

    
    private fun disableTeeOemLock() {
        if (!kernel.treble) return
        val service = Regex("android\\.hardware\\.oemlock(@[0-9.]+)?-service.*")
        val seenBins = HashSet<String>()
        for (dir in listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (!service.matches(f.name) || (!f.isFile && !isLink(f))) return@forEach
                if (!seenBins.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists() || isLink(dst)) dst.delete()
                if (f.renameTo(dst)) log("${f.name}: OemLock service set aside (no secure world here; it never registers and sleeps)")
            }
        }
        if (listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw").any { d -> File(root, d).list().orEmpty().any { service.matches(it) } }) return
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml",
            "odm/etc/vintf/manifest.xml").map { File(root, it) } + skuManifests() +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest", "odm/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
            }
        val seen = HashSet<String>()
        for (f in files) {
            if (!f.isFile || !seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                var dropped = false
                val out = hal.replace(f.readText()) { m ->
                    if (name.find(m.value)?.groupValues?.get(1) == "android.hardware.oemlock") { dropped = true; "" } else m.value
                }
                if (dropped) {
                    f.setWritable(true); f.writeText(out); f.setReadable(true, false)
                    log("${f.relativeTo(root).path}: OemLock declaration dropped")
                }
            }.onFailure { log("${f.name}: OemLock declaration not checked: ${it.message}") }
        }
    }

    
    private fun disableTeeKeyMint() {
        if (!kernel.treble) return
        
        
        
        
        
        val teeService = TEE_KEYMINT_SERVICE
        var parkedBins = 0
        val seenBins = HashSet<String>()
        for (dir in listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (!teeService.matches(f.name) || (!f.isFile && !isLink(f))) return@forEach
                if (!seenBins.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists() || isLink(dst)) dst.delete()
                if (f.renameTo(dst)) { parkedBins++; log("${f.name}: TEE KeyMint service set aside (no TEE here; keystore2 uses its software KeyMint)") }
            }
        }
        
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val dropNames = setOf("android.hardware.security.keymint", "android.hardware.security.sharedsecret", "android.hardware.security.secureclock")
        val stillThere = listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw").any { d ->
            File(root, d).list().orEmpty().any { teeService.matches(it) } }
        if (stillThere) return   
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml",
            "odm/etc/vintf/manifest.xml",
            
            
            
            "system_ext/etc/vintf/manifest.xml", "system/system_ext/etc/vintf/manifest.xml", "product/etc/vintf/manifest.xml",
            "system/product/etc/vintf/manifest.xml").map { File(root, it) } + skuManifests() +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest", "odm/etc/vintf/manifest",
                "system_ext/etc/vintf/manifest", "system/system_ext/etc/vintf/manifest", "product/etc/vintf/manifest",
                "system/product/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
            }
        val seen = HashSet<String>()
        var edited = 0
        for (f in files) {
            if (!f.isFile || !seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                val removed = ArrayList<String>()
                val out = hal.replace(text) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in dropNames) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    f.setWritable(true)
                    f.writeText(out)
                    edited++
                    log("${f.relativeTo(root).path}: KeyMint declarations dropped (${removed.distinct().joinToString()})")
                }
            }.onFailure { log("${f.name}: KeyMint declarations not checked: ${it.message}") }
        }
        if (parkedBins > 0 || edited > 0) log("keymint: TEE KeyMint disabled (service $parkedBins, manifests $edited)")
    }

    
    
    private fun restorePeripheralIo() = runCatching {
        val parked = File(root, "system/.aemu-parked")
        var back = 0
        parked.listFiles()?.filter { it.isFile && it.name.contains("#lib#hw#peripheral_io.") && it.name.endsWith(".so") }?.forEach { f ->
            val dst = File(root, f.name.replace('#', '/'))
            if (!dst.exists() && dst.parentFile?.isDirectory == true && f.renameTo(dst)) { dst.setReadable(true, false); back++; log("${dst.name}: the board's pin map is back") }
        }
        val hasMap = listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw").any { d ->
            File(root, d).list()?.any { it.startsWith("peripheral_io.") && it.endsWith(".so") } == true }
        if (!hasMap) return@runCatching
        File(root, "dev").mkdirs()
        
        
        
        
        
        
        
        
        for (n in 0..4) File(root, "dev/i2c-$n").let { l ->
            if (isLink(l) || !l.isFile || l.length() != 4096L) {
                l.delete()
                runCatching { l.writeBytes(ByteArray(4096)) }
            }
            l.setReadable(true, false); l.setWritable(true, false)
        }
        gpioSysfsStandIns()
    }.onFailure { log("peripheral_io: ${it.message}") }

    
    private fun gpioSysfsStandIns() {
        val base = File(root, "sys/class/gpio").apply { mkdirs() }
        var d: File? = base
        while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
        for (n in listOf("export", "unexport")) File(base, n).let { f ->
            if (!f.isFile) runCatching { f.writeText("") }
            f.setReadable(true, false); f.setWritable(true, false)
        }
        var made = 0
        for (pin in 0 until GPIO_STAND_IN_PINS) {
            val dir = File(base, "gpio$pin")
            if (dir.isDirectory && File(dir, "value").isFile && File(dir, "direction").isFile) continue
            runCatching {
                dir.mkdirs()
                dir.setReadable(true, false); dir.setExecutable(true, false); dir.setWritable(true, false)
                for ((n, v) in listOf("direction" to "in", "value" to "0", "edge" to "none", "active_low" to "0")) {
                    File(dir, n).let { f ->
                        if (!f.isFile) f.writeText(v + "\n")
                        f.setReadable(true, false); f.setWritable(true, false)
                    }
                }
                made++
            }
        }
        if (made > 0) log("peripheral_io: GPIO sysfs nodes for pins 0..${GPIO_STAND_IN_PINS - 1} ($made new)")
    }

    private fun parkSensorsHalOfPure32Treble() {
        if (!kernel.treble || !isPure32Tree(root)) return
        var n = 0
        for (dir in listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (!f.name.startsWith("android.hardware.sensors@") || "-service" !in f.name) return@forEach
                if (!f.isFile && !isLink(f)) return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists() || isLink(dst)) dst.delete()
                if (f.renameTo(dst)) n++
            }
        }
        if (n > 0) log("sensors: no sensors HAL for a 32-bit Treble firmware, its service is set aside ($n); the guest runs without sensors")
    }

    
    private fun parkBoardAidlSensorsHal() {
        if (!kernel.treble || isPure32Tree(root)) return
        if (hwPrograms("android.hardware.sensors@1.0-service").isEmpty()) return
        var n = 0
        for (f in hwPrograms("android.hardware.sensors-service.")) {
            for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) n++
            if (parkFile(f)) n++
        }
        if (n > 0) log("sensors: the board's AIDL sensors HAL is not declared (the engine's sensors@1.0 is) and has no hub here, it is set aside ($n)")
    }

    
    private fun parkSensorsHalWithoutImpl() {
        if (!kernel.treble) return
        val services = hwPrograms("android.hardware.sensors@1.0-service")
        if (services.isEmpty()) return
        
        val libDirs = if (isPure32Tree(root)) listOf("vendor/lib/hw", "system/vendor/lib/hw", "odm/lib/hw", "system/lib/hw")
            else listOf("vendor/lib64/hw", "system/vendor/lib64/hw", "odm/lib64/hw", "system/lib64/hw")
        val impl = libDirs.any { d ->
            File(root, d).list().orEmpty().any { it.startsWith("android.hardware.sensors@1.0-impl") && it.endsWith(".so") }
        }
        if (impl) return
        var n = 0
        for (f in services) if (parkFile(f)) n++
        if (n > 0) log("sensors: no android.hardware.sensors@1.0-impl in the tree, so the sensors host cannot register ISensors; its service is set aside ($n) and the declaration is dropped, the guest runs without sensors")
    }

    
    private fun hwPrograms(prefix: String): List<File> =
        listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw").flatMap { d ->
            File(root, d).listFiles().orEmpty().filter { it.name.startsWith(prefix) && (it.isFile || isLink(it)) }
        }

    
    private fun backupOnce(src: File, keep: File) {
        if (keep.exists()) return
        keep.parentFile?.mkdirs()
        src.copyTo(keep)
    }

    
    private fun parkRustRuntimeConfig() {
        val parked = File(root, "system/.aemu-parked").listFiles().orEmpty().filter {
            it.isFile && it.name.contains("#permissions#rustruntime_cfg") && it.name.endsWith(".xml")
        }
        for (f in parked) {
            val own = File(root, f.name.replace('#', '/'))
            if (own.exists()) continue
            own.parentFile?.mkdirs()
            if (f.renameTo(own)) log("Rust runtime: ${own.name} put back (it declares the hyperos.rustruntime shared libraries)")
        }
    }

    
    private fun parkFile(f: File): Boolean {
        if (!f.isFile && !isLink(f)) return false
        val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
        dst.parentFile?.mkdirs()
        if (dst.exists() || isLink(dst)) dst.delete()
        return f.renameTo(dst)
    }

    
    private fun rcFilesRunning(program: String): List<File> =
        listOf("vendor/etc/init", "system/vendor/etc/init", "system/etc/init").flatMap { d ->
            File(root, d).listFiles().orEmpty().filter { rc ->
                rc.isFile && rc.name.endsWith(".rc") && runCatching { program in rc.readText() }.getOrDefault(false)
            }
        }

    
    
    private fun removeAudio32Hal() {
        runCatching {
            val marker = File(root, "system/.aemu-parked/audio32-installed")
            if (!marker.isFile) return
            val dirs = mapOf(
                "android.hardware.audio@2.0-impl.so" to "vendor/lib/hw", "android.hardware.audio.effect@2.0-impl.so" to "vendor/lib/hw",
                "audio.primary.default.so" to "vendor/lib/hw", "android.hardware.audio.common@2.0-util.so" to "vendor/lib",
            )
            var n = 0
            for (name in marker.readLines()) {
                val dir = dirs[name] ?: continue
                val f = File(root, "$dir/$name")
                if (f.isFile && !isLink(f) && sameContent("engines/common/audio32/$name", f) && f.delete()) n++
            }
            marker.delete()
            if (n > 0) log("audio: the engine's 32-bit audio HAL 2.0 is taken out again ($n files), the engine's 64-bit 4.0 host serves")
        }
    }

    private fun installAudio32Hal(boardAudio: List<File>): Boolean {
        if (isPure32Tree(root) || img.arch.foreign) return false
        fun is32BitElf(f: File) = runCatching {
            f.inputStream().use { i -> val h = ByteArray(5); i.read(h) == 5 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[4] == 1.toByte() }
        }.getOrDefault(false)
        if (!boardAudio.all { is32BitElf(it) }) return false
        val set = mapOf(
            "android.hardware.audio@2.0-impl.so" to "vendor/lib/hw",
            "android.hardware.audio.effect@2.0-impl.so" to "vendor/lib/hw",
            "audio.primary.default.so" to "vendor/lib/hw",
            "android.hardware.audio.common@2.0-util.so" to "vendor/lib",
        )
        return runCatching {
            val marker = File(root, "system/.aemu-parked/audio32-installed")
            val ours = if (marker.isFile) marker.readLines().toSet() else emptySet()
            
            val foreignImpl = listOf("vendor/lib/hw", "system/vendor/lib/hw", "vendor/lib", "system/vendor/lib").any { d ->
                File(root, d).listFiles().orEmpty().any { f ->
                    f.isFile && f.name.startsWith("android.hardware.audio") && "-impl" in f.name && f.name.endsWith(".so") && f.name !in set && f.name !in ours
                }
            }
            if (foreignImpl) return false
            for ((name, dir) in set) {
                val dst = File(root, "$dir/$name")
                if ((dst.isFile || isLink(dst)) && name !in ours && !sameContent("engines/common/audio32/$name", dst)) {
                    log("audio: $dir/$name is the firmware's own, the engine's 32-bit audio HAL is not installed")
                    return false
                }
            }
            var n = 0
            for ((name, dir) in set) {
                val asset = "engines/common/audio32/$name"
                val dst = File(root, "$dir/$name")
                if (dst.isFile && dst.length() == eaSize(asset) && sameContent(asset, dst)) continue
                dst.parentFile?.mkdirs()
                if (isLink(dst)) Os.remove(dst.absolutePath)
                eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false); dst.setExecutable(true, false)
                n++
            }
            marker.parentFile?.mkdirs()
            marker.writeText(set.keys.joinToString("\n"))
            log(if (n > 0) "audio: the engine's 32-bit audio HAL 2.0 installed for the firmware's 32-bit audio service ($n files; silent output module)"
                else "audio: the engine's 32-bit audio HAL 2.0 is in place")
            true
        }.getOrElse { log("audio: the engine's 32-bit audio HAL could not be installed: ${it.message}"); false }
    }

    
    private fun parkEngineAudioHostOfFirmwareWithOwnAudio() {
        if (!kernel.treble) return
        val boardAudio = hwPrograms("android.hardware.audio.service")
        if (boardAudio.isEmpty()) return
        
        
        
        
        
        
        
        
        val engine4Route = File(root, "vendor/lib64/hw/android.hardware.audio@4.0-impl.so").isFile &&
            hwPrograms("android.hardware.audio@2.0-service").any { f ->
                !isLink(f) && !runCatching { f.inputStream().use { i -> val h = ByteArray(5); i.read(h) == 5 && h[4] == 1.toByte() } }.getOrDefault(false)
            }
        val ownAudio32 = if (engine4Route) { removeAudio32Hal(); false } else installAudio32Hal(boardAudio)
        
        
        
        
        
        
        
        fun is32BitElf(f: File) = runCatching {
            f.inputStream().use { i -> val h = ByteArray(5); i.read(h) == 5 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[4] == 1.toByte() }
        }.getOrDefault(false)
        
        
        
        
        
        
        
        
        fun boardHas32BitAudioImpl() = listOf("vendor/lib/hw", "system/vendor/lib/hw", "vendor/lib", "system/vendor/lib").any { d ->
            File(root, d).listFiles().orEmpty().any { it.isFile && it.name.startsWith("android.hardware.audio@") && "-impl" in it.name && it.name.endsWith(".so") }
        }
        val engineHas4 = File(root, "vendor/lib64/hw/android.hardware.audio@4.0-impl.so").isFile
        val engineHas2 = File(root, "vendor/lib64/hw/android.hardware.audio@2.0-impl.so").isFile
        if (!ownAudio32 && boardAudio.all { is32BitElf(it) } && (engineHas4 || (engineHas2 && !boardHas32BitAudioImpl())) &&
            hwPrograms("android.hardware.audio@2.0-service").any { !isLink(it) && !is32BitElf(it) }) {
            var m = 0
            for (f in boardAudio) {
                for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) m++
                if (parkFile(f)) m++
            }
            if (m > 0) log("audio: the firmware's audio service is 32-bit and has no implementation here, the engine's 64-bit audio host serves instead ($m set aside)")
            return
        }
        var n = 0
        for (f in hwPrograms("android.hardware.audio@2.0-service")) {
            for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) n++
            if (parkFile(f)) n++
        }
        if (n > 0) log("audio: the firmware has its own audio service, the engine's 2.0 audio host is set aside ($n)")
    }

    
    private fun fixAudioHostImplLookupOfPure32Treble() {
        if (!kernel.treble || !isPure32Tree(root)) return
        runCatching { AudioImplLibs.expose(root, log) }.onFailure { log("audio: HAL implementation check failed: ${it.message}") }
        var n = 0
        for (rc in AudioImplLibs.rcsToPark(root)) if (parkFile(rc)) n++
        if (n > 0) log("audio: the board's implementation is ${AudioImplLibs.IMPL_MEDIATEK}, the engine's plain audio rc is set aside so the MediaTek audio host runs ($n)")
    }

    
    private fun parkCodec2HalOfPure32Treble() {
        if (!kernel.treble || !isPure32Tree(root)) return
        var n = 0
        for (prefix in C2_HAL_PREFIXES) for (f in hwPrograms(prefix)) {
            for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) n++
            if (parkFile(f)) n++
        }
        if (n > 0) log("media: the board's Codec2 HAL fronts hardware codecs that are not here, its service is set aside ($n); software codecs are used")
    }

    
    private fun parkedHalPackages(): Set<String> {
        val parkedNames = File(root, "system/.aemu-parked").list().orEmpty().map { it.substringAfterLast('#') }
        val out = HashSet<String>()
        if (parkedNames.any { n -> C2_HAL_PREFIXES.any { n.startsWith(it) } }) out += "android.hardware.media.c2"
        if (parkedNames.any { it.startsWith("android.hardware.usb-service.") }) {
            out += "android.hardware.usb"; out += "android.hardware.usb.gadget"
        }
        if (parkedNames.any { n -> USB_GADGET_PREFIXES.any { n.startsWith(it) } }) out += "android.hardware.usb.gadget"
        return out
    }

    
    private val USB_GADGET_PREFIXES = listOf("android.hardware.usb.gadget@", "android.hardware.usb.gadget-")

    
    private fun parkUsbGadgetHal() {
        if (!kernel.treble) return
        var n = 0
        for (prefix in USB_GADGET_PREFIXES) for (f in hwPrograms(prefix)) {
            for (rc in rcFilesRunning(f.name)) {
                val services = runCatching { rc.readLines().count { it.trim().startsWith("service ") } }.getOrDefault(2)
                if (services == 1 && parkFile(rc)) n++
            }
            if (parkFile(f)) n++
        }
        if (n > 0) log("usb: the board's USB gadget HAL aborts at start (no gadget controller here) and was restarted for ever, " +
            "its service is set aside ($n) and the declaration is dropped")
    }

    
    private fun parkVendorHalsThatCannotLink() {
        if (!kernel.treble || isPure32Tree(root)) return
        val stack = listOf("libhidlbase.so", "libhwbinder.so", "libhidltransport.so")
            .map { File(root, "vendor/lib64/$it") }.filter { it.isFile }
        if (stack.isEmpty()) return
        
        
        val defined = stack.mapNotNull { f -> runCatching { ElfPatch.dynSyms64(f)?.defined }.getOrNull() }
        if (defined.isEmpty()) return
        fun lacks(sym: String) = defined.none { sym in it }
        val cases = listOf(
            Triple("vendor.qti.media.c2@", "_ZNK7android8hardware7details13return_status16onValueRetrievalEv", "media: the board's Codec2 HAL"),
            Triple("android.hardware.usb-service.", "_ZN7android8hardware11BpHwRefBase10onFirstRefEv", "usb: the board's USB HAL"))
        for ((prefix, sym, what) in cases) {
            val progs = hwPrograms(prefix)
            if (progs.isEmpty() || !lacks(sym)) continue
            var n = 0
            for (f in progs) {
                for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) n++
                if (parkFile(f)) n++
            }
            if (n > 0) log("$what cannot link against the HIDL stack in /vendor/lib64 (no $sym) and drives hardware that is not here, " +
                "its service is set aside ($n)")
        }
    }

    
    private fun restoreForeignAudioHal() {
        if (!img.arch.foreign || !kernel.treble) return
        val aside = File(root, "system/.aemu-parked")
        val re = Regex("^(vendor|system#vendor|odm|system)#lib(64)?#hw#(android\\.hardware\\.(audio|audio\\.effect|soundtrigger)@[0-9.]+-impl[A-Za-z0-9_.-]*\\.so|audio\\.[A-Za-z0-9_.-]+\\.so)$")
        var n = 0
        for (p in aside.listFiles().orEmpty()) {
            if (!p.isFile || !re.matches(p.name) || p.name in kernel.firmwareAside) continue
            val dst = File(root, p.name.replace('#', '/'))
            if (dst.isFile || isLink(dst)) continue
            dst.parentFile?.mkdirs()
            if (p.renameTo(dst)) { dst.setReadable(true, false); dst.setExecutable(true, false); n++; log("audio: ${dst.relativeTo(root).path}: the firmware's own file is back (${img.arch.id} firmware)") }
        }
        if (n > 0) log("audio: $n HAL files of the ${img.arch.id} firmware put back")
    }

    
    private fun parkHalsWithoutModuleOfPure32Treble() {
        if (!kernel.treble) return
        val pure32 = isPure32Tree(root)
        var n = 0
        for ((prefix, id) in MODULE_HALS) {
            
            
            
            
            
            if (!pure32 && !img.arch.foreign && id != "hdmi_cec") continue
            if (hasHwModule(id, !pure32)) continue
            for (f in hwPrograms(prefix)) {
                for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) n++
                if (parkFile(f)) { n++; log("${f.name}: set aside (the board has no $id hardware module, the service cannot start)") }
            }
        }
        if (n > 0) log("HALs without a hardware module set aside: $n")
    }

    
    private fun parkRemoteKeymasterOfForeignTree() {
        if (!kernel.treble || !img.arch.foreign) return
        
        if (x86KeymasterAnswererWanted()) { restoreRemoteKeymaster(); return }
        var n = 0
        for (f in hwPrograms("android.hardware.keymaster@")) {
            if (!f.name.contains("-service.remote")) continue
            for (rc in rcFilesRunning(f.name)) if (parkFile(rc)) n++
            if (parkFile(f)) { n++; log("${f.name}: set aside (it talks to a keymaster on the emulator's host, none here: \"Could not connect to keymaster\", exit 134)") }
        }
        if (n > 0) log("keymaster: remote keymaster service set aside ($n); keystore is served by the stand-in")
    }

    
    private fun dropStalePlatformCopiesInVendor() {
        if (img.arch != GuestArch.X86_64 || !kernel.treble || isPure32Tree(root)) return
        val vndkDirs = File(root, "apex").listFiles().orEmpty()
            .filter { it.isDirectory && Regex("com\\.android\\.vndk\\.v\\d+").matches(it.name) }
            .map { File(it, "lib64") }.filter { it.isDirectory }
        if (vndkDirs.isEmpty()) return
        val platformDirs = listOf("system/lib64", "system/system/lib64").map { File(root, it) }
        val gone = ArrayList<String>()
        for (n in STALE_VENDOR_PLATFORM_LIBS) {
            val v = File(root, "vendor/lib64/$n")
            if (!v.isFile) continue
            val vndk = vndkDirs.map { File(it, n) }.firstOrNull { it.isFile } ?: continue    
            val platform = platformDirs.map { File(it, n) }.firstOrNull { it.isFile }
            val duplicate = runCatching {
                listOfNotNull(platform, vndk).any { p -> p.length() == v.length() && p.readBytes().contentEquals(v.readBytes()) }
            }.getOrDefault(false)
            if (!duplicate) continue
            if (parkFile(v)) gone += n
            else log("GL: stale /vendor/lib64/$n could not be set aside")
        }
        if (gone.isNotEmpty()) log("GL: copies of the platform's libraries taken out of /vendor/lib64 (the VNDK package serves them; a second " +
            "libhidlbase.so has its own empty BnConstructorMap, \"gBnConstructorMap returned null\" in allocator@2.0-service): ${gone.joinToString()}")
    }

    
    private val STALE_VENDOR_PLATFORM_LIBS = listOf("libhidlbase.so", "libhwbinder.so", "libhidltransport.so", "libbase.so", "libutils.so",
        "libcutils.so", "libc++.so", "libprocessgroup.so", "libhardware.so")

    
    private val MODULE_HALS = listOf(
        "android.hardware.gatekeeper@" to "gatekeeper",
        "android.hardware.camera.provider@" to "camera",
        
        
        
        "android.hardware.tv.cec@" to "hdmi_cec")

    
    private fun hasHwModule(id: String, lib64: Boolean = false): Boolean {
        val dirs = listOf("vendor/lib/hw", "system/vendor/lib/hw", "odm/lib/hw", "system/lib/hw") +
            (if (lib64) listOf("vendor/lib64/hw", "system/vendor/lib64/hw", "odm/lib64/hw", "system/lib64/hw") else emptyList())
        return dirs.any { d ->
            File(root, d).listFiles().orEmpty().any { f ->
                val n = f.name
                f.isFile && n.startsWith("$id.") && n.endsWith(".so") && "@" !in n &&
                    !n.startsWith("$id.device") && !n.startsWith("$id.provider") &&
                    !(id == "camera" && isEngineCameraStub(f))
            }
        }
    }

    
    private fun isEngineCameraStub(f: File): Boolean {
        if (f.name == "camera.aemu_host.so") return true
        val assets = kernel.assets.reversed().flatMap { l ->
            listOfNotNull(EngineAssets.layerAsset(ctx, l, "system#lib#hw#camera.default.so", EngineAssets.ARM32),
                EngineAssets.layerAsset(ctx, l, "system#lib64#hw#camera.default.so", EngineAssets.ARM64))
        } + listOf("engines/common/camera.default.so", "engines/common/camera.default64.so", "engines/common/camera.aemu_host.so")
        return assets.any { a -> runCatching { eaSize(a) == f.length() && sameContent(a, f) }.getOrDefault(false) }
    }

    
    private fun parkedModuleHalPackages(present: List<String>): Set<String> {
        if (!kernel.treble) return emptySet()
        val parkedNames = File(root, "system/.aemu-parked").list().orEmpty().map { it.substringAfterLast('#') }
        val parked = listOf("android.hardware.gatekeeper", "android.hardware.camera.provider", "android.hardware.tv.cec").filter { pkg ->
            present.none { it.startsWith("$pkg@") } && parkedNames.any { it.startsWith("$pkg@") }
        }.toMutableSet()
        
        
        if (img.arch.foreign && present.none { it.startsWith("android.hardware.keymaster@") } &&
            parkedNames.any { it.startsWith("android.hardware.keymaster@") && it.contains("-service.remote") })
            parked += "android.hardware.keymaster"
        return parked
    }

    
    private val C2_HAL_PREFIXES = listOf("android.hardware.media.c2@", "vendor.qti.media.c2@")

    
    private fun dropHalsWithoutService() {
        val binDirs = listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw")
        val present = binDirs.flatMap { d -> File(root, d).list().orEmpty().toList() }
        
        
        
        val fragDirs = listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest")
        val origDir = File(root, "system/.aemu-vintf-orig")
        for (orig in origDir.listFiles().orEmpty()) {
            if (!orig.isFile) continue
            val dst = File(root, orig.name.replace('#', '/'))
            if (dst.parentFile?.isDirectory != true) continue
            runCatching {
                if (!dst.exists() || dst.readText() != orig.readText()) {
                    dst.setWritable(true); orig.copyTo(dst, overwrite = true); dst.setReadable(true, false)
                }
            }
        }
        
        
        
        
        
        val served = HashSet<String>()
        val pkgName = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        for (d in fragDirs) for (f in File(root, d).listFiles().orEmpty()) {
            if (!f.isFile || !f.name.endsWith(".xml")) continue
            val base = f.name.removeSuffix(".xml")
            if (present.none { it == base }) continue
            runCatching { Regex("<hal\\b[^>]*>.*?</hal>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()) }
                .getOrNull()?.forEach { m -> pkgName.find(m.value)?.groupValues?.get(1)?.let { served += it } }
        }
        val rcDirs = listOf("vendor/etc/init", "system/vendor/etc/init", "system/etc/init", "")
        
        val missing = HashMap<String, MutableSet<String>>()
        for (d in rcDirs) for (rc in File(root, d).listFiles().orEmpty()) {
            if (!rc.isFile || !rc.name.endsWith(".rc")) continue
            for (raw in runCatching { rc.readLines() }.getOrDefault(emptyList())) {
                val w = raw.trim().split(Regex("\\s+"))
                if (w.size < 3 || w[0] != "service" || "/bin/hw/" !in w[2]) continue
                if (File(root, w[2].removePrefix("/")).exists() || File(root, "system" + w[2]).exists()) continue
                val bin = w[2].substringAfterLast('/')
                val pkg = bin.substringBefore('@').takeIf { '@' in bin } ?: continue
                missing.getOrPut(pkg) { HashSet() }.add(bin)
            }
        }
        
        
        
        val parkedNames = File(root, "system/.aemu-parked").list().orEmpty().map { it.substringAfterLast('#') }
        
        val c2Parked = kernel.treble &&
            present.none { n -> C2_HAL_PREFIXES.any { n.startsWith(it) } } &&
            parkedNames.any { n -> C2_HAL_PREFIXES.any { n.startsWith(it) } }
        
        val usbParked = kernel.treble && present.none { it.startsWith("android.hardware.usb-service.") } &&
            parkedNames.any { it.startsWith("android.hardware.usb-service.") }
        
        
        
        
        
        
        
        val omxPkg = "android.hardware.media.omx"
        val omxDead = omxPkg !in served &&
            present.none { it.startsWith("$omxPkg@") || it.startsWith("$omxPkg-") || it.startsWith("$omxPkg.") } &&
            rcDirs.none { d ->
                File(root, d).listFiles().orEmpty().any { rc ->
                    rc.isFile && rc.name.endsWith(".rc") && runCatching { rc.readText().contains(omxPkg) }.getOrDefault(false)
                }
            }
        val moduleParked = parkedModuleHalPackages(present)
        
        val gadgetParked = kernel.treble &&
            present.none { n -> USB_GADGET_PREFIXES.any { n.startsWith(it) } } &&
            parkedNames.any { n -> USB_GADGET_PREFIXES.any { n.startsWith(it) } }
        if (missing.isEmpty() && !c2Parked && !usbParked && !gadgetParked && !omxDead && moduleParked.isEmpty()) return
        
        
        val dead = missing.keys.filter { pkg -> pkg !in served && present.none { it.startsWith("$pkg@") || it.startsWith("$pkg-") || it.startsWith("$pkg.") } }.toSet()
        var deadAll = if (c2Parked) dead + "android.hardware.media.c2" else dead
        if (omxDead) deadAll = deadAll + omxPkg
        if (usbParked) deadAll = deadAll + setOf("android.hardware.usb", "android.hardware.usb.gadget")
        if (gadgetParked) deadAll = deadAll + "android.hardware.usb.gadget"
        deadAll = deadAll + moduleParked
        if (deadAll.isEmpty()) return
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val seen = HashSet<String>()
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml")
            .map { File(root, it) } +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
            }
        for (f in files) {
            if (!f.isFile || !seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                val removed = ArrayList<String>()
                val out = hal.replace(text) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in deadAll) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    if (f.parentFile?.relativeTo(root)?.path in fragDirs) {
                        val keep = File(origDir, f.relativeTo(root).path.replace('/', '#'))
                        backupOnce(f, keep)
                    }
                    f.setWritable(true)
                    f.writeText(out)
                    log("${f.relativeTo(root).path}: HAL without a service dropped: ${removed.distinct().joinToString()}")
                }
            }.onFailure { log("${f.name}: HAL entries not checked: ${it.message}") }
        }
    }

    
    private fun libClosure(rel: String): Pair<List<File>, List<String>>? {
        val f = File(root, rel)
        if (!f.isFile) return null
        val dirs = listOf("vendor/lib64", "vendor/lib64/hw", "system/lib64", "system/lib64/hw", "system/lib64/vndk", "system/lib64/vndk-sp",
            "system/lib64/vndk-26", "system/lib64/vndk-sp-26", "system/lib64/vndk-27", "system/lib64/vndk-sp-27").map { File(root, it) }
        val seen = HashSet<String>(); val missing = ArrayList<String>(); val files = arrayListOf(f); val queue = ArrayDeque<File>(); queue.add(f)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 300) {
            val cur = queue.removeFirst()
            for (lib in ElfPatch.needed64(cur).orEmpty()) {
                if (!seen.add(lib)) continue
                val hit = dirs.map { File(it, lib) }.firstOrNull { it.isFile }
                if (hit == null) missing += "$lib (from ${cur.name})" else { queue.add(hit); files += hit }
            }
        }
        return files to missing
    }

    
    
    private fun ensureDefaultHwModule(cls: String) {
        val dirs = listOf("vendor/lib/hw", "system/vendor/lib/hw", "odm/lib/hw", "system/lib/hw")
        if (dirs.any { File(root, "$it/$cls.default.so").exists() }) return
        for (dir in dirs) {
            val d = File(root, dir)
            val own = d.listFiles()?.filter { it.isFile && it.name.startsWith("$cls.") && it.name.endsWith(".so") }
                ?.minByOrNull { it.name } ?: continue
            val dst = File(d, "$cls.default.so")
            runCatching {
                own.copyTo(dst, overwrite = false)
                dst.setReadable(true, false); dst.setExecutable(true, false)
                log("${dst.name}: made from ${own.name} (the lookup by the board's name found no $cls module)")
            }.onFailure { log("${dst.name}: could not be made from ${own.name}: ${it.message}") }
            return
        }
        log("$cls: the tree has no $cls module at all; its HAL service will not start")
    }

    private fun logMissingLibs(rel: String) {
        val (files, missing) = libClosure(rel) ?: run { log("deps: $rel: not in the tree"); return }
        log("deps: ${files[0].name}: " + if (missing.isEmpty()) "every library found (${files.size - 1})" else "NOT FOUND " + missing.joinToString(", "))
    }

    
    private fun logUnresolvedSymbols(rel: String, entry: String? = null) {
        val (files, _) = libClosure(rel) ?: return
        val syms = files.map { it to ElfPatch.dynSyms64(it) }
        val defined = HashSet<String>(); for ((_, s) in syms) if (s != null) defined += s.defined
        val unresolved = ArrayList<String>()
        for ((file, s) in syms) if (s != null) for (u in s.undefined) if (u !in defined) unresolved += "$u (${file.name})"
        val head = files[0].name + (if (entry != null) (if (syms[0].second?.defined?.contains(entry) == true) ": exports $entry" else ": does NOT export $entry") else "")
        log("symbols: $head; " + if (unresolved.isEmpty()) "every symbol resolves" else "UNRESOLVED ${unresolved.size}: " + unresolved.sorted().take(25).joinToString(", "))
    }

    private val VENDOR_HALS = setOf("gralloc", "hwcomposer", "copybit", "lights", "sensors", "audio", "power", "memtrack",
        "camera", "gps", "fingerprint", "gxfingerprint", "consumerir", "bluetooth", "nfc", "vibrator", "keystore", "gatekeeper")

    private fun installCameraHal() {
        val enabled = img.settings.camera && engine == Engine.KK && img.api in 14..25
        
        runCatching {
            if (CamcorderProfiles.configure(root, enabled))
                log(if (enabled) "camera: camcorder profiles set to ${CamcorderProfiles.WIDTH}x${CamcorderProfiles.HEIGHT} (original kept)" else "camera: original camcorder profiles restored")
        }.onFailure { log("camera: camcorder profiles not changed: ${it.message}") }
        val destination = File(root, "system/lib/hw/camera.aemu_host.so")
        if (!enabled) {
            if (CameraHalFallback.configure(root, destination, false)) log("camera: restored previous default HAL slot")
            return
        }
        
        val asset = "engines/common/camera.aemu_host.so"
        if (img.arch != GuestArch.ARM && eaSize(asset) < 0) { log("camera: no ${img.arch.id} host-camera HAL in this build, the guest has no camera"); return }
        if (destination.isFile && sameContent(asset, destination)) {
            if (CameraHalFallback.configure(root, destination, img.api <= 20)) log("camera: legacy default HAL fallback installed")
            return
        }
        val backup = File(root, "system/.aemu-parked/system#lib#hw#camera.aemu_host.so")
        runCatching {
            if (destination.exists() || isLink(destination)) {
                backup.parentFile?.mkdirs()
                if (!backup.exists() && !destination.renameTo(backup)) error("cannot preserve existing camera HAL")
                if (isLink(destination)) Os.remove(destination.absolutePath)
            }
            destination.parentFile?.mkdirs()
            eaOpen(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
            destination.setReadable(true, false); destination.setExecutable(true, false)
            log("camera: standard HAL1 host-camera bridge installed")
            if (CameraHalFallback.configure(root, destination, img.api <= 20)) log("camera: legacy default HAL fallback installed")
        }.onFailure { error("camera HAL installation failed: ${it.message}") }
    }

    private fun installSensorHal() {
        if (img.api !in 9..25) return
        val legacyApi = File(root, "system/lib/libsensorservice.so").takeIf { it.isFile }
            ?.let { runCatching { SensorHalApi.controlDataOnly(it.readBytes()) }.getOrDefault(false) } == true
        
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        for (directory in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw")) {
            File(root, directory).listFiles()?.filter { it.name.startsWith("sensors.") && it.name.endsWith(".so") }?.forEach { file ->
                val dest = File(parked, (directory + "/" + file.name).replace('/', '#'))
                if (!dest.exists()) check(file.renameTo(dest)) { "Cannot park sensor HAL" } else check(file.delete())
            }
        }
        if (legacyApi || img.sensorsOff) {
            
            
            
            
            for (name in listOf("sensors.aemu_host.so", "sensors.default.so")) File(root, "system/lib/hw/$name").delete()
            log(if (legacyApi) "motion: this firmware's SensorService needs the control/data sensor HAL, which the engine lacks; guest runs without sensors"
                else if (img.settings.disableSensors == null) "motion: Nougat+ Wear SDK build: libgui's unaligned sensor serialisation crashes under qemu (SIGBUS), guest runs without sensors (Settings > Disable all sensors)"
                else "motion: all sensors disabled in the settings; guest runs without sensors")
            return
        }
        if (img.arch != GuestArch.ARM && eaSize("engines/common/sensors.aemu_host.so") < 0) {
            log("motion: no ${img.arch.id} sensor HAL in this build, the guest has no sensors")
            return
        }
        for (name in listOf("sensors.aemu_host.so", "sensors.default.so")) {
            val file = File(root, "system/lib/hw/$name")
            file.parentFile?.mkdirs()
            eaOpen("engines/common/sensors.aemu_host.so").use { input -> file.outputStream().use { input.copyTo(it) } }
            file.setReadable(true, false)
        }
        log("motion: installed legacy host sensor HAL")
    }

    
    
    private fun eaOpen(path: String) = EngineAssets.open(ctx, EngineAssets.forArch(ctx, path, img.arch))
    private fun eaSize(path: String) = EngineAssets.size(ctx, EngineAssets.forArch(ctx, path, img.arch))

    private fun installEngineFiles() {
        
        File(root, "data/local/tmp").listFiles()?.filter { it.name == "shm_cache.bin" || it.name == "shm_noncache.bin" }?.forEach { it.delete() }
        val copies = when (engine) {
            Engine.KK -> listOf(
                "libashmemshim.so" to "system/lib/libashmemshim.so",
                "libGLES.so" to "system/lib/egl/libGLES_bridge.so",
                "audio.primary.default.so" to "system/lib/hw/audio.primary.default.so",
                "gralloc.default.so" to "system/lib/hw/gralloc.default.so",
                "aemu-stubs.jar" to "system/framework/aemu-stubs.jar",
                "aemu-bt.jar" to "system/framework/aemu-bt.jar",
                "aemu-wifi.jar" to "system/framework/aemu-wifi.jar",
                "aemu-sensorhub.jar" to "system/framework/aemu-sensorhub.jar",
            ).filter { (asset, to) ->
                
                
                val skip = img.api <= 1 && (asset == "aemu-stubs.jar" || asset == "aemu-sensorhub.jar")
                if (skip) File(root, to).delete()
                !skip
            }
            Engine.GB -> listOf(
                "libashmemshim.so" to "system/lib/libashmemshim.so",
                "libGLES_dhd.so" to "system/lib/egl/libGLES_dhd.so",
                "gralloc.default.so" to "system/lib/hw/gralloc.default.so",
            )
            Engine.N64 -> return installNative64()
        }
        File(root, "system/bin/fbpaint").delete()
        
        File(root, "system/bin/netprobe").delete()
        
        
        
        if (engine == Engine.KK) for (d in listOf("system/vendor/lib/mediadrm", "vendor/lib/mediadrm")) {
            val wv = File(root, "$d/libwvdrmengine.so")
            if (wv.isFile && !isLink(wv)) {
                val parked = File(root, "system/.aemu-parked/${d.replace('/', '#')}#libwvdrmengine.so")
                parked.parentFile?.mkdirs(); parked.delete()
                if (wv.renameTo(parked)) log("Widevine plugin set aside (aborts under the translator)")
            }
        }
        
        val parkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so")
        if (parkedGralloc.isFile) {
            val g = File(root, "system/lib/hw/gralloc.default.so")
            g.delete(); parkedGralloc.renameTo(g)
        }
        
        
        val romGralloc = File(root, "system/lib/hw/gralloc.default.so")
        
        if (engine == Engine.KK && img.arch == GuestArch.ARM && romGralloc.isFile && romGralloc.length() < 2_000_000) {
            val text = runCatching { String(romGralloc.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            
            
            
            if (text.contains("libion.so") || text.contains("ion_alloc") || text.contains("/dev/ion") ||
                text.contains("libmemalloc") || text.contains("libgenlock") || text.contains("pmem_adsp")) {
                val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.ion")
                parked.parentFile?.mkdirs()
                if (romGralloc.renameTo(parked)) log("firmware gralloc uses ION / Qualcomm memalloc, replaced with engine gralloc")
            } else if ((GrallocProbe.allocDeviceSize(runCatching { romGralloc.readBytes() }.getOrDefault(ByteArray(0))) ?: 0) >
                GrallocProbe.AOSP_ALLOC_DEVICE_SIZE) {
                
                
                val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.ion")
                parked.parentFile?.mkdirs()
                if (romGralloc.renameTo(parked)) log("gralloc: firmware gralloc.default.so builds an extended alloc_device_t (${romGralloc.length()} bytes), replaced with engine gralloc")
            } else {
                log("gralloc: firmware gralloc.default.so kept (${romGralloc.length()} bytes, no ION / memalloc references)")
            }
        }
        
        
        
        
        if (img.arch == GuestArch.ARM) {
            val n = BroadcomGpu.restoreParkedGralloc(root)
            if (n > 0) log("gralloc: firmware gralloc put back ($n file${if (n == 1) "" else "s"}); /dev/gememalloc is emulated by libaemushim")
            else if (usesGememalloc(runCatching { romGralloc.readBytes() }.getOrNull()))
                log("gralloc: firmware gralloc.default.so kept (Broadcom /dev/gememalloc, emulated by libaemushim)")
            if (n > 0 || usesGememalloc(runCatching { romGralloc.readBytes() }.getOrNull())) ensureGememallocPool()
        }
        
        
        
        val eclairGralloc = img.api in 5..7 && img.arch == GuestArch.ARM
        if (eclairGralloc && romGralloc.isFile) {
            val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.eclair")
            if (!parked.exists()) {
                parked.parentFile?.mkdirs()
                if (runCatching { romGralloc.copyTo(parked) }.isSuccess) log("Eclair: firmware gralloc replaced with engine gralloc")
            }
        }
        
        
        
        val eclairParkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.eclair")
        val ionParkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.ion")
        val replacedGralloc = listOf(eclairParkedGralloc.takeIf { eclairGralloc }, ionParkedGralloc)
            .firstOrNull { it != null && it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        val replacingFirmwareGralloc = eclairGralloc || ionParkedGralloc.isFile
        val engineGralloc = if (replacedGralloc == null) null
            else runCatching { eaOpen("engines/${engine.id}/gralloc.default.so").use { it.readBytes() } }.getOrNull()
        val grallocVariant = GtiGralloc.applies(replacedGralloc, engineGralloc)
        if (replacedGralloc != null && engineGralloc != null)
            log("gralloc: firmware ${GrallocProbe.verdict(replacedGralloc)}, engine ${GrallocProbe.verdict(engineGralloc)} -> " +
                if (grallocVariant) "Eclair-behaviour variant" else "standard engine gralloc")
        var copied = 0
        var skippedForArch = 0
        
        val nougat = if (img.api >= 24 && copies.any { it.second == "system/lib/egl/libGLES_bridge.so" })
            listOf("libGLES_split.so" to "system/lib/egl/libGLES_emulation.so") else emptyList()
        
        val noBridge = img.api >= 24 && !gpuOn
        if (noBridge) {
            for (n in listOf("libGLES_bridge.so", "libGLES_emulation.so")) File(root, "system/lib/egl/$n").delete()
            
            
            val sw = File(root, "system/lib/egl/libGLES_swiftshader.so")
            if (!sw.exists() && !isLink(sw) && File(root, "system/lib/egl/libGLES_android.so").isFile)
                runCatching { Os.symlink("libGLES_android.so", sw.path) }
        }
        for ((from, to) in (copies + nougat).filter { !noBridge || !it.second.startsWith("system/lib/egl/libGLES_") } +
            listOf("@libaemushim.so" to "system/lib/libaemushim.so", "@libaemurlimit.so" to GuestPreload.RLIMIT.removePrefix("/"),
                "@libaemubinderguard.so" to GuestPreload.GUARD.removePrefix("/"),
                "@lights.default.so" to "system/lib/hw/lights.default.so") +
            
            (if (GuestPreload.isGlibcFirmware(root)) listOf("@libshmshim.so" to GuestPreload.SHMSHIM.removePrefix("/")) else emptyList())) {
            val dst = File(root, to)
            
            if (from == "gralloc.default.so" && dst.isFile && !replacingFirmwareGralloc) continue
            
            
            
            
            
            
            
            
            
            val samsung = isSamsungFirmware()
            val htcLike = img.skin.contains("HTC", true) || (samsung && img.api in 16..17)
            val mtkHw = from == "audio.primary.default.so" && engine == Engine.KK && isMtkHwOnlyAudio(root)
            val name = when {
                
                from == "gralloc.default.so" && grallocVariant -> GtiGralloc.ASSET
                mtkHw -> "audio.primary.mtk.so"
                
                
                
                
                
                
                samsung && img.api in 16..17 && from == "audio.primary.default.so" && openOutputSlotIs(108) -> "audio.primary.aosp.so"
                
                
                
                
                samsung && img.api == 16 && from == "audio.primary.default.so" && openOutputSlotIs(104) -> {
                    val jb41 = runCatching { eaSize("engines/${engine.id}/audio.primary.jb41.so") }.getOrDefault(-1L) > 0
                    log("audio: Samsung 4.1 AudioFlinger calls open_output_stream at +104 (ICS-style device), using the ${if (jb41) "4.1" else "ICS"} layout HAL")
                    if (jb41) "audio.primary.jb41.so" else "audio.primary.ics.so"
                }
                from != "audio.primary.default.so" || htcLike -> from
                
                img.api in 14..15 -> when {
                    qcomAudioFlinger() -> "audio.primary.ics-qcom.so"
                    
                    
                    
                    samsung && samsungDeviceSlots() -> "audio.primary.ics-samsung.so"
                    
                    
                    
                    openOutputSlotIs(108) -> "audio.primary.aosp.so"
                    else -> "audio.primary.ics.so"
                }
                
                
                
                
                samsung && img.api >= 18 && samsungDeviceSlots() -> {
                    log("audio: Samsung AudioFlinger calls open_output_stream at +116, using the shifted-layout HAL")
                    "audio.primary.ics-samsung.so"
                }
                
                
                
                img.api in 16..18 && afHasFmVolumeSlot() -> {
                    log("audio: AudioFlinger has set_fm_volume after get_master_volume, using the FM-volume layout HAL")
                    "audio.primary.fmvol.so"
                }
                directTrackAudio() -> "audio.primary.directtrack.so"
                img.api >= 16 -> "audio.primary.aosp.so"
                else -> from
            }
            val asset = if (name.startsWith("@")) "engines/common/${name.drop(1)}" else "engines/${engine.id}/$name"
            
            if (img.arch != GuestArch.ARM && eaSize(asset) < 0) { skippedForArch++; continue }
            val size = runCatching { eaSize(asset) }.getOrDefault(-1L)
            
            
            
            val wantVer: Int? = if (name == "audio.primary.default.so" || name == "audio.primary.aosp.so") expectedHalVersion() else null
            
            
            
            
            val libuiSlot = if (from == "gralloc.default.so") runCatching {
                GrallocProbe.libuiFreeSlot(File(root, "system/lib/libui.so").readBytes())
            }.getOrNull() else null
            val sonyFree = from == "gralloc.default.so" && replacedGralloc != null && libuiSlot != 0x44 &&
                (GrallocProbe.allocDeviceSize(replacedGralloc) ?: 0) > GrallocProbe.AOSP_ALLOC_DEVICE_SIZE
            
            
            val windowShift = if (to == "system/lib/egl/libGLES_bridge.so" && img.arch == GuestArch.ARM && isMocorDroidFirmware()) nativeWindowShift() else 0
            val halBytes: ByteArray? = if (sonyFree)
                runCatching { eaOpen(asset).use { it.readBytes() } }.getOrNull()?.let { GrallocProbe.withFreeAtSlot48(it) }
            else if (windowShift != 0)
                runCatching { eaOpen(asset).use { it.readBytes() } }.getOrNull()?.let { NativeWindowProbe.withWindowShift(it, windowShift) }
                    ?: run { log("GL: bridge window slots could not be moved for this firmware's native window layout, bridge left as is"); null }
            else if (wantVer != null && wantVer != 0x0200)
                runCatching { eaOpen(asset).use { it.readBytes() } }.getOrNull()?.let { AudioHalAbi.withDeviceVersion(it, wantVer) }
            else null
            if (halBytes != null && dst.isFile && !isLink(dst) && dst.length() == halBytes.size.toLong() &&
                runCatching { dst.readBytes().contentEquals(halBytes) }.getOrDefault(false)) continue
            if (halBytes == null && dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
            if (mtkHw) runCatching {
                
                val parked = File(root, "system/.aemu-parked/system#lib#hw#audio.primary.default.so")
                if (!parked.isFile && dst.isFile) { parked.parentFile?.mkdirs(); dst.copyTo(parked) }
                log("audio: MediaTek HAL layout in hw/audio.primary.default.so")
            }
            runCatching {
                dst.parentFile?.mkdirs()
                if (isLink(dst)) Os.remove(dst.absolutePath)
                if (halBytes != null) {
                    dst.outputStream().use { o -> o.write(halBytes) }
                    if (sonyFree) log("gralloc: Sony libui frees through alloc_device_t +0x48, engine gralloc adjusted (free moved from +0x44)")
                    else if (windowShift != 0) log("GL: this firmware's native windows are $windowShift bytes longer than AOSP's (buffer handle at +0x${(NativeWindowProbe.AOSP_HANDLE_OFFSET + windowShift).toString(16)}), bridge window slots moved to match")
                    else log("audio: this AudioFlinger accepts only hw device version 0x${wantVer?.toString(16)} (read from libaudioflinger), HAL adjusted from 0x200")
                } else eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
                copied++
                if (name == "audio.primary.directtrack.so") log("audio: verified CAF direct-track stream ABI selected")
                if (name == GtiGralloc.ASSET) log("gralloc: Eclair-behaviour variant installed (same-process buffers are not re-mapped or unmapped)")
            }.onFailure { log("${from.removePrefix("@")} missing from engine set: ${it.message}") }
        }
        if (skippedForArch > 0)
            log("${img.arch.id}: $skippedForArch engine helper files have no ${img.arch.id} build yet (assets/engines/*${img.arch.assetSuffix}); the guest runs without them")
        
        val cam32 = File(root, "system/lib/hw/camera.default.so")
        val vcam32 = File(root, "vendor/lib/hw/camera.default.so")
        if (cam32.isFile && File(root, "vendor/lib/hw").isDirectory && (!vcam32.exists() || vcam32.length() != cam32.length())) {
            runCatching { cam32.copyTo(vcam32, overwrite = true) }
        }
        if (File(root, "system/lib64").isDirectory) {
            val cam64 = File(root, "system/lib64/hw/camera.default.so")
            val vcam64 = File(root, "vendor/lib64/hw/camera.default.so")
            runCatching {
                
                val asset64 = (if (engine == Engine.N64) kernel.assets.reversed() else emptyList())
                    .firstNotNullOfOrNull { EngineAssets.layerAsset(ctx, it, "system#lib64#hw#camera.default.so", EngineAssets.ARM64) }
                    ?: "engines/common/camera.default64.so"
                val size64 = eaSize(asset64)
                if (!cam64.exists() || cam64.length() != size64) {
                    cam64.parentFile?.mkdirs()
                    eaOpen(asset64).use { i -> cam64.outputStream().use { o -> i.copyTo(o) } }
                    cam64.setReadable(true, false)
                }
                if (File(root, "vendor/lib64/hw").isDirectory && (!vcam64.exists() || vcam64.length() != cam64.length())) {
                    cam64.copyTo(vcam64, overwrite = true)
                }
            }
        }
        linkerLibRange()
        
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            File(root, dir).listFiles()?.filter { it.name.startsWith("libRSDriver_") }?.forEach { f ->
                val parked = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                parked.parentFile?.mkdirs()
                if (f.renameTo(parked)) log("RenderScript: removed vendor driver ${f.name}")
            }
        }
        
        
        val trueSize = runCatching { eaSize("engines/common/aemu_true.so") }.getOrDefault(-1L)
        
        
        val fakes = listOf("bin/iptables", "bin/ip6tables") + if (img.skin.contains("MIUI", true)) listOf("xbin/invoke-as") else emptyList()
        for (rel in fakes) {
            val n = rel.substringAfter('/')
            val f = File(root, "system/$rel")
            if (!f.exists() && !isLink(f) || trueSize < 0 || (!isLink(f) && f.length() == trueSize)) continue
            runCatching {
                val parked = File(root, "system/.aemu-parked/system#${rel.replace('/', '#')}")
                parked.parentFile?.mkdirs()
                if (isLink(f)) Os.remove(f.absolutePath) else if (!parked.exists()) f.renameTo(parked) else f.delete()
                eaOpen("engines/common/aemu_true.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                f.setReadable(true, false); f.setExecutable(true, false)
                copied++
            }.onFailure { log("$n not replaced: ${it.message}") }
        }
        
        val cam = File(root, "system/lib/libcamera.so")
        val stamp = File(root, "system/lib/libcamera.so.aemu")
        val parkedCam = File(root, "system/.aemu-parked/system#lib#libcamera.so")
        val htc = img.skin.contains("HTC", true)
        
        if (!htc && stamp.isFile && parkedCam.isFile) { cam.delete(); parkedCam.renameTo(cam); stamp.delete() }
        if (engine == Engine.GB && htc) {
            if (cam.isFile && !stamp.isFile) {
                runCatching {
                    File(root, "system/.aemu-parked").mkdirs()
                    cam.copyTo(File(root, "system/.aemu-parked/system#lib#libcamera.so"), overwrite = true)
                    eaOpen("engines/gb/libcamera.so").use { i -> cam.outputStream().use { o -> i.copyTo(o) } }
                    stamp.writeText("1")
                }
            }
        }
        
        
        
        
        fun needsLegacyCameraSymbol(): Boolean = listOf("libcameraservice.so", "libseccameraadaptor.so").any { n ->
            val f = File(root, "system/lib/$n")
            f.isFile && runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains("openCameraHardware") }.getOrDefault(false)
        }
        val stubOk = img.api in 9..10 && !htc && !needsLegacyCameraSymbol()
        
        if (!stubOk && !htc && parkedCam.isFile && !stamp.isFile) runCatching {
            val noneSize = eaSize("engines/common/libcamera.none.so")
            if (noneSize > 0 && cam.length() == noneSize) {
                cam.delete(); parkedCam.renameTo(cam)
                log("camera: stock libcamera.so restored (the empty HAL does not fit this firmware)")
                copied++
            }
        }
        if (stubOk && cam.isFile && !parkedCam.exists() && cam.length() > 4096) {
            val text = runCatching { String(cam.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            if (listOf("libcald", "liboemcamera", "oncrpc").any { text.contains(it) }) runCatching {
                File(root, "system/.aemu-parked").mkdirs()
                cam.copyTo(parkedCam, overwrite = true)
                eaOpen("engines/common/libcamera.none.so").use { i -> cam.outputStream().use { o -> i.copyTo(o) } }
                log("camera: the firmware's 2.3 HAL needs the modem, replaced with an empty one (stock kept)")
                copied++
            }
        }
        if (copied > 0) log("engine ${engine.id} files updated: $copied")
    }

    
    private fun linkerLibRange() {
        if (img.api > 10) return
        val f = File(root, "system/bin/linker")
        if (!f.isFile || f.length() > 4_000_000) return
        runCatching {
            val d = f.readBytes()
            val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var off = 0
            while (off + 12 <= d.size) {
                if (b.getInt(off) == 0x40000000 && b.getInt(off + 4) == 0x10000000 && b.getInt(off + 8) == 0x100000) {
                    b.putInt(off, 0x90000000.toInt())
                    f.writeBytes(d)
                    log("linker: range for non-prelinked libraries moved from 0x40000000 to 0x90000000")
                    break
                }
                off += 4
            }
        }
    }

    
    private fun eglConfig(gpu: Boolean) {
        
        X86SoftwareGles.ensure(ctx, root, img.api, log)
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val cfg = File(dir, "egl.cfg")
        val cfgRom = File(dir, "egl.cfg.rom")
        
        
        if (isLink(cfg)) runCatching { Os.remove(cfg.absolutePath) }.onFailure { log("egl.cfg: symlink removal failed: ${it.message}"); cfg.delete() }
        if (!cfgRom.isFile && cfg.isFile) cfg.copyTo(cfgRom, overwrite = true)
        when (engine) {
            Engine.N64 -> {}
            Engine.GB -> cfg.writeText(if (gpu) "0 0 dhd\n" else "0 0 android\n")
            Engine.KK -> {
                
                
                
                val bridge = File(dir, "libGLES_bridge.so")
                val sw = File(dir, "libGLES_android.so")
                val swOff = File(dir, "libGLES_android.so.sw")
                val names = listOf("libGLES.so", "libGLES_aemu.so", "libGLES_android.so", "libEGL_aemu.so", "libGLESv1_CM_aemu.so", "libGLESv2_aemu.so")
                if (gpu && bridge.isFile) {
                    if (!swOff.isFile && sw.isFile && sw.length() != splitSize() && sw.length() != assetSize(OPEN_GL)) sw.renameTo(swOff)
                    
                    
                    val open = openGl32()
                    val asset = if (open) OPEN_GL else "engines/kk/libGLES_split.so"
                    val all = names + (if (img.api >= 24) listOf("libGLES_emulation.so") else emptyList())
                    runCatching {
                        for (n in all) installAsset(asset, File(dir, n))
                        val shim = File(dir, "libGLES_split.so")
                        if (open) installAsset("engines/kk/libGLES_split.so", shim) else shim.delete()
                    }.onFailure { log("GL: shim failed to install: ${it.message}") }
                    if (open) log("GL: open bridge in front of the 4.4 engine's one (glopen)")
                    
                    cfg.writeText("0 0 android\n0 1 aemu\n")
                } else {
                    
                    
                    
                    
                    val keepPlain = img.arch != GuestArch.ARM && img.api in 20..23
                    for (n in names) if (n != "libGLES_android.so" && !(keepPlain && n == "libGLES.so")) File(dir, n).delete()
                    if (swOff.isFile) { sw.delete(); swOff.renameTo(sw) }
                    cfg.writeText("0 0 android\n")
                }
            }
        }
    }

    private fun isElf64(f: File): Boolean = runCatching {
        f.inputStream().use { i -> val b = ByteArray(5); i.read(b) == 5 && b[4] == 2.toByte() }
    }.getOrDefault(false)

    private fun sameContent(asset: String, f: File): Boolean = runCatching {
        fun crc(i: java.io.InputStream): Long {
            val c = java.util.zip.CRC32(); val b = ByteArray(1 shl 16)
            while (true) { val n = i.read(b); if (n < 0) break; c.update(b, 0, n) }
            return c.value
        }
        eaOpen(asset).use { crc(it) } == f.inputStream().use { crc(it) }
    }.getOrDefault(false)

    
    
    private fun flattenedLinks() = runCatching {
        val stamp = File(root, ".aemu-flatlinks")
        if (stamp.isFile) return@runCatching
        FlattenedLinks.repair(root, log)
        stamp.writeText("1\n")
    }.onFailure { log("symlinks: flattened link repair failed: ${it.message}") }

    
    private fun shellLink() = runCatching {
        val bin = File(root, "system/bin")
        val path = File(bin, "sh").toPath()
        val nofollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        val rootPath = root.canonicalPath + File.separator
        
        fun inTree(f: File) = runCatching { f.canonicalFile.let { it.isFile && it.path.startsWith(rootPath) } }.getOrDefault(false)
        val isLink = java.nio.file.Files.isSymbolicLink(path)
        if (isLink) {
            val t = java.nio.file.Files.readSymbolicLink(path).toString()
            if (!t.startsWith("/") && inTree(File(bin, "sh"))) return@runCatching
        } else if (java.nio.file.Files.exists(path, nofollow)) {
            
            val head = runCatching { path.toFile().takeIf { it.length() <= FlattenedLinks.MAX_LEN }?.readBytes() }.getOrNull()
            if (head == null || FlattenedLinks.targetOf(head) == null) return@runCatching
            path.toFile().delete()
        }
        val target = listOf("mksh", "ash", "toybox", "busybox", "../xbin/busybox").firstOrNull { inTree(File(bin, it)) }
        if (target == null) {
            
            if (!LegacyFirmware.isLegacyLayout(paths.root)) log("sh: system/bin/sh is missing and there is no mksh/ash/toybox/busybox to link it to")
            return@runCatching
        }
        if (isLink) java.nio.file.Files.delete(path)
        java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(target))
        log("sh: system/bin/sh -> $target restored")
    }.onFailure { log("sh: could not repair system/bin/sh: ${it.message}") }

    
    private fun pldHints() = runCatching {
        var words = 0; var files = 0
        val hit = ArrayList<String>()
        for (dir in listOf("system/bin", "system/xbin", "system/lib", "vendor/lib", "vendor/bin")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    val n = ElfPatch.fixPldHints(f)
                    if (n > 0) { words += n; files++; hit.add("${f.name}=$n") }
                }
            }
        }
        if (words > 0) log("pld hints: $words word(s) rewritten in $files file(s) (qemu rejects pldw and malformed pld): ${hit.joinToString(", ")}")
    }.onFailure { log("pld hints: failed: ${it.message}") }

    
    private fun vendorChecks() = runCatching {
        val drvb = File(root, "system/lib/libmtk_drvb.so")
        if (drvb.isFile) {
            val n = ElfPatch.returnZero(drvb, setOf("mtk_drvb_basechk", "platform_init", "platform_advchk", "drvb_ext_input"))
            if (n > 0) log("MediaTek DRVB: platform check disabled ($n func.)")
        }
    }.onFailure { log("MediaTek DRVB: failed: ${it.message}") }

    
    
    private val HDMIRX_NOOPS = setOf(
        "GetBoardVersion", "kg2h_gpio_exit", "kg2h_gpio_set", "kg2h_gpio_enable_irq", "kg2h_gpio_disable_irq",
    )

    
    private val HDMIRX_INIT = setOf("KG2H_InitDevice", "AvcInitKG2h", "AvcInitHdmiRx", "Avc275xI2CInit")

    private fun berlinOsalGuard() = runCatching {
        if (!GuestPreload.isGlibcFirmware(root)) return@runCatching
        
        
        
        
        
        
        
        
        val targets = mapOf(
            "libOSAL.so" to setOf("os_set_task_prio", "MV_CC_DSS_Init", "MV_CC_UDP_Init"),
            
            
            "libPEAgent.so" to setOf(
                "MV_PE_Init", "MV_PE_Remove", "MV_PE_RegisterEventCallBack", "MV_PE_ClearScreen",
                "MV_PE_VOutSetEnable", "MV_PE_VOutSetInput", "MV_PE_VOutHDMIGetSinkCaps", "MV_PE_VOutGetCPCBResolution",
                "MV_PE_VOutHDMISetVideoFormat", "MV_PE_VOutHDMISetAudioFormat", "MV_PE_VOutSetCPCBResolutionBDEx",
                "MV_PE_VOutHDMISet3DVideoFormat", "MV_PE_VOutHDMILoadHDCPKeys", "MV_PE_VOutHDMISetHDCP",
                "MV_PE_VideoSet3DConvertMode", "MV_PE_VideoSetSSType",
                "MV_PE_AOutGetDigitalOutCaps", "MV_PE_AOutSetVolume", "MV_PE_AOutSetMute", "MV_PE_AOutGetHDMIFormat",
                "MV_PE_AOutSetHDMIFormat", "MV_PE_AOutGetSpdifFormat", "MV_PE_AOutSetSpdifFormat",
            ),
        )
        for ((name, funcs) in targets) for (dir in listOf("system/vendor/lib", "system/lib")) {
            val rel = "$dir/$name"
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            backupOnce(lib, keep)
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("Berlin: $name patched ($n func.: ${funcs.joinToString()})")
        }
        
        
        
        
        
        
        
        
        
        
        for (dir in listOf("system/vendor/lib", "system/lib")) {
            val rel = "$dir/libHdmiRx.so"
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            backupOnce(lib, keep)
            val n = ElfPatch.returnZero(lib, HDMIRX_NOOPS, includeSymtab = true)
            if (n > 0) log("Berlin: $rel patched ($n func.: ${HDMIRX_NOOPS.joinToString()})")
        }
        
        
        
        
        
        
        
        val glibcs = File(root, "lib").listFiles().orEmpty()
            .filter { it.isFile && Regex("libc-\\d+(\\.\\d+)*\\.so").matches(it.name) }.map { "lib/" + it.name }.sorted()
        for (rel in glibcs) {
            val libc = File(root, rel)
            if (!libc.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            backupOnce(libc, keep)
            val n = ElfPatch.returnZeroAnyArch(libc, setOf("settimeofday", "__settimeofday"))
            if (n > 0) log("Berlin: glibc settimeofday() no longer reaches the host ($n func.)")
        }
        
        
        
        
        
        
        if (!File(root, "system/lib/libPEAgent.so").exists() && !File(root, "system/vendor/lib/libPEAgent.so").exists()) return@runCatching
        var rel = "tmp"
        for (i in 0 until 8) {
            val f = File(root, rel)
            if (!isLink(f)) break
            val t = Os.readlink(f.absolutePath)
            rel = if (t.startsWith("/")) t.trimStart('/') else File(rel).parent.let { if (it == null) t else "$it/$t" }
        }
        val tmp = File(root, rel).apply { mkdirs() }
        val marker = File(tmp, ".PE_AV_Init.done")
        if (!marker.exists() && marker.createNewFile()) log("Berlin: /tmp/.PE_AV_Init.done created (in /$rel)")
    }.onFailure { log("Berlin: vendor lib patch failed: ${it.message}") }

    
    private fun intelCeShims() = runCatching {
        if (img.arch != GuestArch.X86 || !GuestPreload.isGlibcFirmware(root)) return@runCatching
        if (!File(root, "lib/libgdl.so").exists()) return@runCatching
        var n = 0
        for (name in listOf("libgdl.so", "libgma.so", "libismd_core.so", "libismd_audio.so", "libclock_control.so", "libGLES_CM.so")) {
            val asset = "engines/glibc-x86/$name"
            if (!EngineAssets.exists(ctx, asset)) continue
            val dst = File(root, "lib/$name")
            if (dst.isFile && !isLink(dst)) {
                backupOnce(dst, File(root, "system/.aemu-parked/lib#$name"))
                
                
                
                
                if (dst.length() == EngineAssets.size(ctx, asset) &&
                    runCatching { dst.readBytes().contentEquals(EngineAssets.open(ctx, asset).use { it.readBytes() }) }.getOrDefault(false)) continue
            }
            dst.delete()
            EngineAssets.open(ctx, asset).use { src -> dst.outputStream().use { src.copyTo(it) } }
            dst.setReadable(true, false); dst.setExecutable(true, false)
            n++
        }
        val agl = File(root, "system/lib/egl/libGLES_android.so")
        if (agl.isFile) {
            val egl = File(root, "lib/libEGL.so")
            if (egl.isFile && !isLink(egl)) backupOnce(egl, File(root, "system/.aemu-parked/lib#libEGL.so"))
            if (isLink(egl) || !egl.isFile || egl.length() != agl.length()) {
                egl.delete(); agl.copyTo(egl)
                egl.setReadable(true, false); egl.setExecutable(true, false)
                n++
            }
        }
        if (n > 0) log("Intel CE (Google TV x86): $n display/media library stand-in(s) installed")
        gtvMediaApiGuard()
    }.onFailure { log("Intel CE (Google TV x86): stand-ins failed: ${it.message}") }

    
    private fun gtvMediaApiGuard() = runCatching {
        val lib = File(root, "system/lib/libgtv_mediaapi.so")
        if (!lib.isFile || isLink(lib)) return@runCatching
        val d = lib.readBytes()
        
        val pat = byteArrayOf(0xba.toByte(), 1, 0, 0, 0, 0x84.toByte(), 0xc0.toByte(), 0x75, 0xcd.toByte())
        val at = indexOf(d, pat)
        if (at < 0) {
            val done = byteArrayOf(0xba.toByte(), 1, 0, 0, 0, 0x84.toByte(), 0xc0.toByte(), 0xeb.toByte(), 0xcd.toByte())
            if (indexOf(d, done) < 0) log("libgtv_mediaapi: InitializeGTVMediaLibraries not found (other firmware), left as is")
            return@runCatching
        }
        if (indexOf(d, pat, at + 1) >= 0) { log("libgtv_mediaapi: InitializeGTVMediaLibraries pattern is not unique, left as is"); return@runCatching }
        backupOnce(lib, File(root, "system/.aemu-parked/system#lib#libgtv_mediaapi.so"))
        d[at + 7] = 0xeb.toByte()
        lib.writeBytes(d)
        log("libgtv_mediaapi: a failing GLCompositor init no longer stops the Ismd resource manager from starting")
    }.onFailure { log("libgtv_mediaapi: patch failed: ${it.message}") }

    
    private fun hdmiRxGuard() = runCatching {
        val stamp = File(root, ".aemu-hdmirx")
        
        
        if (stamp.isFile && runCatching { stamp.readText().trim() }.getOrNull() == "2") return@runCatching
        val markers = listOf("Avc275x", "kg2h_gpio", "twsi_fp").map { it.toByteArray(Charsets.ISO_8859_1) }
        fun mentions(d: ByteArray) = markers.any { m -> indexOf(d, m) >= 0 }
        val wanted = { n: String -> n in HDMIRX_NOOPS || n in HDMIRX_INIT || n.startsWith("Avc275x_i2c") }
        val candidates = ArrayList<File>()
        for (dir in listOf("system/lib", "system/vendor/lib", "vendor/lib", "lib"))
            File(root, dir).listFiles()?.filterTo(candidates) { it.isFile && !isLink(it) && it.name.endsWith(".so") }
        for (n in listOf("mediaserver", "av_settings")) File(root, "system/bin/$n").takeIf { it.isFile && !isLink(it) }?.let { candidates += it }
        var patched = 0
        var seen = 0
        for (lib in candidates) {
            if (lib.length() < 4096 || lib.length() > 30L shl 20) continue
            val d = runCatching { lib.readBytes() }.getOrNull() ?: continue
            if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || !mentions(d)) continue
            seen++
            val rel = lib.relativeTo(root).path
            val found = ElfPatch.symbolsMatching(d, true, wanted)?.keys.orEmpty()
            if (found.isEmpty()) {
                val like = ElfPatch.symbolsMatching(d, true) { Regex("(?i)avc275x|kg2h|hdmi_?rx").containsMatchIn(it) }?.keys.orEmpty()
                log("HDMI RX: $rel mentions the receiver driver but exports none of its i2c/gpio functions" +
                    if (like.isEmpty()) " (stripped)" else "; candidates: ${like.take(80).joinToString()}")
                continue
            }
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            backupOnce(lib, keep)
            val n = ElfPatch.returnZeroMatching(lib, true, wanted)
            patched += n
            log("HDMI RX: $rel patched ($n of ${found.size} func.: ${found.joinToString()})")
        }
        if (seen > 0 && patched == 0) log("HDMI RX: driver functions already patched or not found in $seen file(s)")
        stamp.writeText("2\n")
    }.onFailure { log("HDMI RX: guard failed: ${it.message}") }

    
    private fun isEngineX86Hwc(f: File): Boolean = f.name == "hwcomposer.default.so" &&
        (runCatching { x86Asset("vendor#lib64#hw#hwcomposer.default.so").let { a -> f.length() == eaSize(a) && sameContent(a, f) } }.getOrDefault(false) ||
            isSupersededX86Engine("vendor#lib64#hw#hwcomposer.default.so", f))

    
    private fun hasHwcModule(): Boolean = listOf("system/lib/hw", "vendor/lib/hw").any { dir ->
        File(root, dir).listFiles()?.any { it.name.startsWith("hwcomposer.") && it.name.endsWith(".so") } == true
    }

    
    private fun btiGuard() = runCatching {
        if (img.api < 29) return@runCatching          
        var n = 0
        val dirs = listOf("system/lib64", "vendor/lib64", "system/vendor/lib64", "system/system_ext/lib64",
            "system_ext/lib64", "product/lib64", "system/product/lib64")
        for (d in dirs) {
            val dir = File(root, d)
            val files = (dir.listFiles().orEmpty().toList() + File(dir, "hw").listFiles().orEmpty().toList())
                .filter { it.isFile && it.name.endsWith(".so") }
            for (f in files) {
                val force = f.name == "libsurfaceflinger.so"
                val r = runCatching {
                    if (ElfPatch.dropBtiIfBroken(f, force, dryRun = true) == 0) return@runCatching 0
                    
                    backupOnce(f, File(root, "system/.aemu-parked/" + f.relativeTo(root).path.replace('/', '#')))
                    ElfPatch.dropBtiIfBroken(f, force)
                }.getOrDefault(0)
                if (r > 0) { n++; log("BTI: ${f.relativeTo(root).path}: landing-pad check dropped (stub without a landing pad)") }
            }
        }
        if (n > 0) log("BTI: $n librar${if (n == 1) "y" else "ies"} patched")
    }.onFailure { log("BTI: guard failed: ${it.message}") }

    
    
    private var gememallocPoolChecked = false
    private fun ensureGememallocPool() {
        if (gememallocPoolChecked) return
        gememallocPoolChecked = true
        if (BroadcomGpu.ensurePoolFile(root)) log("gememalloc: pool file dev/${BroadcomGpu.POOL_FILE} is in the tree")
        else log("gememalloc: could not create dev/${BroadcomGpu.POOL_FILE}; the firmware gralloc will fail to open /dev/gememalloc")
    }

    private fun surfaceFlingerGuard() = runCatching {
        for (rel in listOf("system/lib/libsurfaceflinger.so", "system/lib64/libsurfaceflinger.so")) {
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace('/', '#'))
            backupOnce(lib, keep)
            if (ElfPatch.skipNullDeviceHook(lib) > 0) log("$rel: the missing HAL device hook is skipped")
            if (ElfPatch.revertHwcEventControlPatch(lib, keep) > 0) log("$rel: old vsync byte patch removed (the stand-in hwcomposer is used instead)")
            if (rel == "system/lib/libsurfaceflinger.so") {
                if (BroadcomGpu.hasV3dComposer(lib)) {
                    ensureGememallocPool()
                    if (img.settings.gpu) log("GPU: this firmware composes through V3D into fb0, so the GPU bridge is not used (software path)")
                    if (BroadcomGpu.undoLegacyComposerPatch(lib, keep) > 0) log("libsurfaceflinger: old software-composer patch removed (V3D is emulated now)")
                    File(root, "system/lib/libui.so").takeIf { it.isFile }?.let { ui ->
                        if (BroadcomGpu.undoLegacyLibuiPatch(ui, File(root, "system/.aemu-parked/system#lib#libui.so")) > 0)
                            log("libui: old framebuffer-layout patch removed (the firmware gralloc is used again)")
                    }
                }
                if (img.api <= 8 && ElfPatch.skipUndrawnLayerClear(lib) > 0) log("libsurfaceflinger: a layer that was never drawn is no longer cleared to black")
            }
        }
    }.onFailure { log("libsurfaceflinger: patch failed: ${it.message}") }

    
    private fun framebufferComposer() = runCatching {
        if (engine != Engine.KK || img.arch != GuestArch.ARM || img.api !in 17..19) return@runCatching
        if (!File(root, "system/lib/libsurfaceflinger.so").isFile) return@runCatching
        val dst = File(root, "system/lib/hw/hwcomposer.default.so")
        
        val ours = HwcFallback.isStandIn(dst)
        
        if (HwcFallback.disabled(paths)) {
            if (HwcFallback.remove(root)) log("hwcomposer: stand-in composer removed (surfaceflinger crashed with it on this image; file \"hwc-standin-off\" in the image directory)")
            return@runCatching
        }
        
        if (File(paths.dir, "nohwc").isFile) {
            if (ours && dst.delete()) log("hwcomposer: stand-in composer removed (file \"nohwc\" in the image directory)")
            return@runCatching
        }
        if (hasHwcModule() && !ours) return@runCatching              
        if (assetSize(HWC_FB) <= 0) { log("hwcomposer: no stand-in composer in this build; SurfaceFlinger will fake its vsync"); return@runCatching }
        if (ours && sameContent(HWC_FB, dst)) return@runCatching
        dst.parentFile?.mkdirs()
        dst.delete()
        installAsset(HWC_FB, dst)
        log("hwcomposer: stand-in composer installed (HWC 1.1, GLES composition, vsync at fb0's refresh rate)")
    }.onFailure { log("hwcomposer: stand-in composer failed: ${it.message}") }

    
    private fun executableCodeCache() = runCatching {
        if (img.api > 8) return@runCatching
        val lib = File(root, "system/lib/libc.so")
        if (!lib.isFile) return@runCatching
        val keep = File(root, "system/.aemu-parked/system#lib#libc.so")
        backupOnce(lib, keep)
        if (ElfPatch.execCacheflush(lib) > 0) log("libc: cacheflush() makes generated code executable (no LD_PRELOAD before 2.3)")
    }.onFailure { log("libc: cacheflush patch failed: ${it.message}") }

    
    private fun mspaceHooks() = runCatching {
        for (dir in listOf("system/lib", "system/vendor/lib", "vendor/lib")) {
            val lib = File(root, "$dir/libpixelflinger.so")
            if (!lib.isFile || isLink(lib)) continue
            val d = lib.readBytes()
            if (MspaceHooks.patched(d) == null) continue
            backupOnce(lib, File(root, "system/.aemu-parked/${dir.replace('/', '#')}#libpixelflinger.so"))
            if (MspaceHooks.apply(lib)) log("libpixelflinger: the malloc debug hooks it imports weakly are no longer called through a null pointer ($dir)")
        }
    }.onFailure { log("libpixelflinger: patch failed: ${it.message}") }

    
    private fun surfaceGuard() = runCatching {
        val lib = File(root, "system/lib/libgui.so")
        if (lib.isFile) {
            val keep = File(root, "system/.aemu-parked/system#lib#libgui.so")
            backupOnce(lib, keep)
            if (ElfPatch.unlockAndPostNeverFails(lib) > 0) log("libgui: a failed frame post no longer kills the caller")
        }
    }.onFailure { log("libgui: patch failed: ${it.message}") }

    
    private fun glassGestureGuard() = runCatching {
        fun patch(name: String, funcs: Set<String>, label: String) {
            val lib = File(root, "system/lib/$name")
            if (!lib.isFile) return
            val keep = File(root, "system/.aemu-parked/system#lib#$name")
            backupOnce(lib, keep)
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("$label ($n func.)")
        }
        patch("libinvensense_hal.so", setOf("_ZN9MPLSensor11getInstanceEv"),
            "glass: MPLSensor::getInstance() no longer waits for the Invensense HAL")
        patch("libglassgesture.so",
            setOf("_ZNK5glass10DMPManager22isDMPGesturesSupportedEv", "_ZN5glass10DMPManager27blockUntilGestureRecognizedEPNS_10DMPGestureE"),
            "glass: DMP gestures reported unavailable")
    }.onFailure { log("glass: gesture patch failed: ${it.message}") }

    
    private fun glassBluetoothPark() = runCatching {
        if (img.api >= 16) return@runCatching
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var moved = 0
        for (dir in listOf("system/app", "system/priv-app")) {
            val apk = File(root, "$dir/GlassBluetooth.apk")
            for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                if (!f.isFile) continue
                val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) moved++ else log("glass: could not park ${f.name}")
            }
        }
        if (moved > 0) {
            File(root, "data/dalvik-cache/system@app@GlassBluetooth.apk@classes.dex").delete()
            log("glass: Bluetooth app parked ($moved file(s)); RFCOMM sockets are unavailable in the guest")
        }
    }.onFailure { log("glass: could not park the Bluetooth app: ${it.message}") }

    private fun selinuxOff() = runCatching {
        val lib = File(root, "system/lib/libselinux.so")
        if (lib.isFile) {
            
            val keep = File(root, "system/.aemu-parked/system#lib#libselinux.so")
            backupOnce(lib, keep)
            val n = ElfPatch.returnZero(lib, setOf("is_selinux_enabled", "is_selinux_mls_enabled"))
            if (n > 0) log("SELinux: libselinux reports disabled ($n func.)")
            
            
            
            
            
            if (isPure32Tree(root)) {
                val r = ElfPatch.returnZero(lib, setOf("selinux_android_restorecon", "selinux_android_restorecon_pkgdir",
                    "selinux_android_setfilecon", "selinux_android_setcon", "selinux_android_setcontext"))
                if (r > 0) log("SELinux: restorecon/setfilecon of libselinux do nothing ($r func.)")
                xattrNoop()
            }
        }
    }.onFailure { log("SELinux: failed to patch libselinux: ${it.message}") }

    
    private fun xattrNoop() = runCatching {
        val libc = File(root, "system/lib/libc.so")
        if (!libc.isFile) return@runCatching
        
        
        backupOnce(libc, File(root, "system/.aemu-parked/system#lib#libc.so"))
        
        
        
        val sets = ElfPatch.returnZero(libc, setOf("setxattr", "lsetxattr", "fsetxattr"))
        val gets = ElfPatch.returnValue(libc, setOf("getxattr", "lgetxattr", "fgetxattr"), 1)
        if (sets + gets > 0) log("xattr: libc get/set xattr calls succeed without the kernel (${sets + gets} func.)")
    }.onFailure { log("xattr: failed to patch libc: ${it.message}") }

    private fun qtaguid() = runCatching {
        val d = File(root, "data/.aemu_qtaguid").apply { mkdirs() }
        d.setReadable(true, false); d.setExecutable(true, false)
        val files = mapOf(
            "stats" to "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets " +
                "rx_tcp_bytes rx_tcp_packets rx_udp_bytes rx_udp_packets rx_other_bytes rx_other_packets " +
                "tx_tcp_bytes tx_tcp_packets tx_udp_bytes tx_udp_packets tx_other_bytes tx_other_packets\n",
            "iface_stat_fmt" to "ifname total_skb_rx_bytes total_skb_rx_packets total_skb_tx_bytes total_skb_tx_packets\n",
            "iface_stat_all" to "",
        )
        for ((n, text) in files) File(d, n).apply { writeText(text); setReadable(true, false) }
        
        
        if (engine == Engine.N64 && kernel.trafficAccounting) {
            val p = File(root, "proc/net/xt_qtaguid").apply { mkdirs() }
            for ((n, text) in files + ("ctrl" to "")) File(p, n).writeText(text)
            File(root, "proc/net/xt_quota").mkdirs()           
            File(root, "proc/net/xt_quota/globalAlert").writeText("")
        }
        if (engine == Engine.N64) for ((n, text) in kernel.procFiles) File(root, "proc/$n").let {
            it.parentFile?.mkdirs()
            if (n.endsWith(".gz")) {            
                val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { z -> z.write(text.toByteArray()) } }.toByteArray()
                if (!it.isFile || !it.readBytes().contentEquals(gz)) it.writeBytes(gz)
            } else if (!it.isFile || it.readText() != text) it.writeText(text)
        }
    }

    private fun assetSize(a: String): Long = runCatching { eaSize(a) }.getOrDefault(-1L)

    
    private fun nativeWindowShift(): Int = runCatching {
        NativeWindowProbe.windowShift(File(root, "system/lib/libui.so").readBytes())
    }.getOrNull() ?: 0

    private fun isMocorDroidFirmware(): Boolean {
        if (nativeWindowShift() != 4) return false
        val buildProp = File(root, "system/build.prop")
        val text = runCatching { buildProp.readText() }.getOrDefault("")
        return text.contains("mocor", true) || text.contains("spreadtrum", true) || text.contains("sprd", true) ||
            text.contains("sc88", true) || text.contains("sc68", true) || img.brand.contains("sprd", true) ||
            img.brand.contains("spreadtrum", true) || img.name.contains("mocor", true)
    }
    private fun installAsset(a: String, f: File) {
        if (f.isFile && !isLink(f) && f.length() == assetSize(a)) return
        f.delete()
        eaOpen(a).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.setReadable(true, false); f.setExecutable(true, false)
    }
    
    private fun openGl32(): Boolean = engine == Engine.KK && File(paths.dir, "glopen").isFile &&
        assetSize(OPEN_GL) > 0

    private fun splitSize(): Long = runCatching { eaSize("engines/kk/libGLES_split.so") }.getOrDefault(-1L)

    private fun makeDataDirs() {
        var made = 0
        val rcs = root.listFiles { f -> f.isFile && f.name.endsWith(".rc") }?.map { f ->
            runCatching { f.readText() }.getOrDefault("")
        }.orEmpty()
        val dbdata = dbdataDirs(rcs, img.dirs,
            legacySamsung = img.api < 14 && img.brand.contains("samsung", true) || img.name.contains("samsung", true) && img.api < 14,
            alreadyThere = File(root, "dbdata").exists())
        val want = (DATA_DIRS + img.dirs.map { it.trim().removePrefix("/") } + dbdata).filter { it.isNotEmpty() && !it.startsWith("#") }
        for (d in want.distinct()) {
            
            if (d.startsWith("proc") || d.startsWith("sys/") || d == "sys" || d.startsWith("acct")) continue
            val f = File(root, d)
            if (!f.isDirectory && f.mkdirs()) made++
            if (d in dbdata) { f.setReadable(true, false); f.setWritable(true, false); f.setExecutable(true, false) }
        }
        if (made > 0) log("created init directories: $made")
    }

    
    private fun fixDataRootAccess() = runCatching {
        val have = if (paths.owners.isFile) paths.owners.readLines().map { it.trim() }.toHashSet() else hashSetOf()
        val sb = StringBuilder()
        for (rel in listOf("data", "data/data")) {
            val f = File(root, rel)
            if (!f.isDirectory || isLink(f)) continue
            Os.chmod(f.absolutePath, 0x1F9) 
            val st = Os.lstat(f.absolutePath)
            val row = String.format("%016x %016x %08x %08x", st.st_dev, st.st_ino, 1000, 1000)
            if (row !in have) sb.append(row).append('\n')
        }
        if (sb.isNotEmpty()) { paths.owners.appendText(sb.toString()); log("data: /data and /data/data set to system 0771") }
    }.onFailure { log("data: could not fix /data access: ${it.message}") }

    
    private fun miuiSdkFiles() {
        for ((jar, apk) in listOf("miuisdk" to "system/app/miui/miui.apk", "miuisystem" to "system/app/miuisystem/miuisystem.apk")) {
            val link = File(root, "system/framework/$jar.jar")
            val target = runCatching { Os.readlink(link.absolutePath) }.getOrNull() ?: continue
            if (!target.endsWith("data/miui/$jar.apk")) continue
            val src = File(root, apk).takeIf { it.isFile } ?: File(root, "system/app/${apk.substringAfterLast('/')}").takeIf { it.isFile } ?: continue
            val dst = File(root, "data/miui/$jar.apk")
            if (dst.isFile && dst.length() == src.length()) continue
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                log("MIUI: /data/miui/$jar.apk restored from /$apk")
            }
        }
    }

    private fun makeUserZeroLink() {
        if (img.api < 14) return 
        
        if (img.api >= 24) for (d in listOf("data", "user_de/0", "misc_ce/0", "misc_de/0", "system_ce/0", "system_de/0", "media/0", "misc/profiles/cur/0", "misc/profiles/ref"))
            File(root, "data/$d").apply { mkdirs() }.let { dir ->
                
                var p: File? = dir
                while (p != null && p != File(root, "data")) { makeTraversable(p); p = p.parentFile }
            }
        val user = File(root, "data/user").apply { mkdirs() }
        
        
        
        
        
        makeTraversable(user)
        val zero = File(user, "0")
        val cur = runCatching { Os.readlink(zero.absolutePath) }.getOrNull()
        if (cur == "../data") return
        runCatching {
            if (cur != null || zero.exists()) { if (zero.isDirectory && !isLink(zero)) wipe(zero) else zero.delete() }
            Os.symlink("../data", zero.absolutePath)
        }
    }

    private fun makeDevNodes() {
        File(root, "dev/input/event0").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.createNewFile() } }
        File(root, "dev/socket").mkdirs()
        File(root, "dev/graphics").mkdirs()
        
        if (LegacyFirmware.ensureFbAlias(root)) log("legacy layout: dev/fb0 -> graphics/fb0")
        
        if (engine == Engine.N64 && kernel.noQemuPipe) File(root, "dev/qemu_pipe").let { if (!it.isDirectory) { it.delete(); it.mkdirs() } }
        AudioOut(paths, log).makeFifo()
        File(root, "dev/binder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/hwbinder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/vndbinder").takeIf { it.isDirectory }?.let { wipe(it) }
        for (n in listOf("binder", "hwbinder", "vndbinder", "ashmem")) {
            File(root, "dev/$n").let { if (!it.exists()) { it.parentFile?.mkdirs(); it.createNewFile() } }
        }
        File(root, "dev/cpuctl").takeIf { it.exists() }?.let { wipe(it) }
        
        
        
        
        
        
        if (engine == Engine.N64 && kernel == Kernel64.A17) runCatching {
            val f = File(root, "dev/binderfs/features/freeze_notification")
            f.parentFile?.mkdirs()
            if (!f.isFile || f.readText() != "1") { f.writeText("1"); f.setReadable(true, false) }
        }.onFailure { log("binderfs features: freeze_notification not written: ${it.message}") }
        gpuHeapNodes()
        adssNodes()
    }

    
    private fun adssNodes() = runCatching {
        val rt = listOf("system/lib/libandroid_runtime.so", "system/lib64/libandroid_runtime.so")
            .map { File(root, it) }.firstOrNull { it.isFile } ?: return@runCatching
        val marker = "AdssBusJNI".toByteArray(Charsets.ISO_8859_1)
        if (indexOf(rt.readBytes(), marker) < 0) return@runCatching
        for (n in listOf("adss", "AdssBus")) {
            val f = File(root, "dev/$n")
            if (f.isDirectory) wipe(f) 
            if (!f.isFile) {
                f.parentFile?.mkdirs()
                f.createNewFile()
                log("adss: placeholder /dev/$n (AdssBus.getInstance() opens it before zygote forks system_server)")
            }
            f.setReadable(true, false); f.setWritable(true, false)
        }
    }.onFailure { log("adss: placeholder nodes not made: ${it.message}") }

    
    private fun gpuHeapNodes() = runCatching {
        if (img.api > 10) return@runCatching
        for ((n, size) in listOf("pmem_gpu0" to (8L shl 20), "pmem_gpu1" to (8L shl 20), "hw3d" to (1L shl 20))) {
            val f = File(root, "dev/$n")
            if (f.isDirectory) continue
            if (!f.isFile) { f.parentFile?.mkdirs(); f.createNewFile() }
            if (f.length() != size) java.io.RandomAccessFile(f, "rw").use { it.setLength(size) }
            f.setReadable(true, false); f.setWritable(true, false)
        }
    }.onFailure { log("gpu heap nodes: ${it.message}") }

    
    
    
    private fun scudoStub() {
        if (img.arch != GuestArch.X86_64) return
        if (ScudoStub.candidates(root).isEmpty()) return
        runCatching {
            val stub = eaOpen("engines/common/libc_scudo_stub.so").use { it.readBytes() }
            ScudoStub.apply(root, stub, log)
        }.onFailure { log("scudo: libc_scudo.so stub missing from the engine set: ${it.message}") }
    }

    private fun fixApex() {
        
        
        val sources = listOf("system/apex", "system/system_ext/apex", "system/product/apex").map { File(root, it) }.filter { it.isDirectory }
        if (sources.isEmpty()) return
        val apexDir = File(root, "apex").apply { mkdirs() }
        
        
        for (n in listOf("com.android.art", "com.android.runtime")) File(apexDir, n).let {
            if (it.isDirectory && !isLink(it) && !File(it, "apex_manifest.json").exists() && !File(it, "apex_manifest.pb").exists()) wipe(it)
        }
        for (arch in listOf("lib", "lib64")) File(root, "system/$arch/bionic").let { if (isLink(it) && runCatching { Os.readlink(it.path) }.getOrNull() == ".") Os.remove(it.path) }
        apexDir.listFiles()?.filter { isLink(it) && runCatching { Os.readlink(it.path) }.getOrNull()?.startsWith("../system/apex/") == true }?.forEach { Os.remove(it.path) }
        var moved = 0
        var unpacked = 0
        for (dir in sources) {
            val up = "../".repeat(dir.relativeTo(root).path.count { it == '/' } + 1)
            
            if (dir.listFiles()?.any { it.isFile && (it.name.endsWith(".apex") || it.name.endsWith(".capex")) } == true) unpacked += ApexUnpacker.unpackAll(root, dir, log)
            dir.listFiles()?.filter { it.isDirectory && !isLink(it) }?.forEach { pkg ->
                val name = moduleName(pkg)
                val home = File(apexDir, name)
                runCatching {
                    if (home.exists() || isLink(home)) return@forEach      
                    if (!pkg.renameTo(home)) error("cannot move")
                    Os.symlink("${up}apex/$name", pkg.path); moved++
                }.onFailure { log("apex $name: ${it.message}") }
            }
        }
        
        
        
        var relinked = 0
        for (dir in sources) dir.listFiles()?.filter { isLink(it) }?.forEach { was ->
            val home = File(apexDir, runCatching { Os.readlink(was.path) }.getOrNull()?.substringAfterLast('/') ?: return@forEach)
            if (!home.isDirectory || isLink(home)) return@forEach
            fun walk(d: File) {
                d.listFiles()?.forEach { f ->
                    if (!isLink(f)) { if (f.isDirectory) walk(f); return@forEach }
                    val t = runCatching { Os.readlink(f.path) }.getOrNull() ?: return@forEach
                    if (t.startsWith("/") || File(d, t).exists()) return@forEach
                    val old = File(File(was.parentFile, home.name + "/" + d.relativeTo(home).path), t).normalize()
                    if (!old.exists() || !old.path.startsWith(root.path + "/")) return@forEach
                    runCatching { Os.remove(f.path); Os.symlink(old.relativeTo(d).path, f.path); relinked++ }
                }
            }
            walk(home)
        }
        
        
        var shared = 0
        apexDir.listFiles()?.filter { it.isDirectory && !isLink(it) && it.name != "sharedlibs" }?.forEach { mod ->
            for (lib in listOf("lib", "lib64")) File(mod, lib).listFiles()?.filter { it.isDirectory && !isLink(it) && it.name.endsWith(".so") }?.forEach { d ->
                d.listFiles()?.filter { it.isDirectory }?.forEach { hash ->
                    val link = File(apexDir, "sharedlibs/$lib/${d.name}/${hash.name}")
                    if (link.exists() || isLink(link)) return@forEach
                    link.parentFile?.mkdirs()
                    runCatching { Os.symlink("../../../${mod.name}/$lib/${d.name}/${hash.name}", link.path); shared++ }
                }
            }
        }
        if (shared > 0) log("APEX shared libraries linked: $shared")
        if (relinked > 0) log("APEX links to /system set right: $relinked")
        if (moved > 0) log("APEX modules put in place: $moved")
        if (unpacked > 0) log("APEX packages unpacked: $unpacked")
    }

    
    private fun moduleName(pkg: File): String {
        runCatching { Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(File(pkg, "apex_manifest.json").readText())?.groupValues?.get(1) }.getOrNull()?.let { return it }
        runCatching {
            val pb = File(pkg, "apex_manifest.pb").readBytes()
            if (pb.size > 2 && pb[0].toInt() == 0x0A && pb[1] in 1..127 && 2 + pb[1] <= pb.size) return String(pb, 2, pb[1].toInt(), Charsets.UTF_8)
        }
        return pkg.name.removeSuffix(".debug").removeSuffix(".release")
    }

    
    private fun rootLinks() {
        for (n in listOf("product", "system_ext")) {
            val link = File(root, n)
            if (engine == Engine.N64 && kernel.partitionsAtRoot) {
                val inSystem = File(root, "system/$n")
                if (isLink(inSystem) || !inSystem.isDirectory) continue
                if (isLink(link) || link.isDirectory && link.list().isNullOrEmpty()) link.delete()
                if (link.exists() || !inSystem.renameTo(link)) continue
                runCatching { Os.symlink("../$n", inSystem.path); log("/$n is a directory of the root, /system/$n links to it") }
                continue
            }
            if (!File(root, "system/$n").isDirectory || isLink(link)) continue
            
            if (link.isDirectory && link.list().isNullOrEmpty()) link.delete()
            if (link.exists()) continue
            runCatching { Os.symlink("system/$n", link.path); log("/$n -> /system/$n") }
        }
    }

    
    
    private val NETD_MAP_SIZES = mapOf("cookie_tag_map" to (8 to 8), "uid_counterset_map" to (4 to 1), "app_uid_stats_map" to (4 to 32),
        "stats_map_A" to (16 to 32), "stats_map_B" to (16 to 32), "iface_stats_map" to (4 to 32), "configuration_map" to (4 to 4),
        "uid_owner_map" to (4 to 8), "data_saver_enabled_map" to (4 to 1), "iface_index_name_map" to (4 to 16), "packet_trace_enabled_map" to (4 to 1),
        
        "uid_permission_chunk_map" to (4 to 1024), "blocked_ports_map" to (4 to 8), "ingress_discard_map" to (16 to 4), "netd_pid_map" to (4 to 4),
        "lock_array_test_map" to (4 to 1), "lock_hash_test_map" to (4 to 1), "sk_storage" to (4 to 192), "l4s_accecn_enabled_map" to (4 to 1),
        "l4s_conn_counter" to (4 to 4), "local_net_access_map" to (28 to 1), "local_net_uid_host_allowlist_map" to (28 to 1),
        "local_net_note_op_cache_map" to (4 to 4), "local_net_cache_generation_id_map" to (4 to 8), "loopback_access_cache_map" to (12 to 8),
        "loopback_permission_enabled_map" to (4 to 1), "require_internet_permission_map" to (4 to 1))

    private val BPF_NOTES_STAMP = "6"      

    
    private fun bpfMapNotes() {
        if (engine != Engine.N64 || !kernel.bpfMapNotes) return
        val dir = File(root, "dev/.aemu-bpf")
        
        
        val stamp = File(dir, ".stamp")
        if (dir.isDirectory && runCatching { stamp.readText().trim() }.getOrNull() == BPF_NOTES_STAMP) return
        val tmp = File(root, "dev/.aemu-bpf.tmp").apply { deleteRecursively(); mkdirs() }
        
        
        
        val rootReal = runCatching { root.canonicalFile }.getOrDefault(root)
        val dirs = ArrayList<File>()
        for (part in listOf("system", "vendor", "odm", "product", "system_ext")) {
            dirs += File(root, "$part/etc/bpf")
            if (part != "system") dirs += File(root, "system/$part/etc/bpf")
        }
        File(root, "apex").listFiles()?.sortedBy { it.name }?.forEach { dirs += File(it, "etc/bpf") }
        val seen = HashSet<String>()
        val objects = ArrayList<File>()
        val perPart = LinkedHashMap<String, Int>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            val list = d.walkTopDown().maxDepth(3).filter { it.isFile && (it.name.endsWith(".o") || it.name.endsWith(".bpf")) }.sortedBy { it.path }
            for (f in list) {
                
                val real = runCatching { f.canonicalFile }.getOrDefault(f)
                val inTree = real.path.startsWith(rootReal.path + File.separator)
                val key = if (inTree) real.path else "${f.name}:${f.length()}"
                if (!seen.add(key)) continue
                objects += f
                val rel = runCatching { f.relativeTo(root).path.removePrefix("system/") }.getOrDefault(f.path)
                val part = rel.substringBefore('/')
                perPart[part] = (perPart[part] ?: 0) + 1
            }
        }
        var n = 0
        for (o in objects) runCatching {
            val b = java.nio.ByteBuffer.wrap(o.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != 0x464c457f || b.get(4).toInt() != 2) return@runCatching
            val shoff = b.getLong(0x28).toInt(); val shnum = b.getShort(0x3c).toInt(); val shstr = b.getShort(0x3e).toInt()
            fun sh(i: Int) = shoff + i * 64
            fun str(tab: Int, off: Int): String {
                var p = b.getLong(sh(tab) + 0x18).toInt() + off
                val sb = StringBuilder()
                while (b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar())
                return sb.toString()
            }
            if (o.name.endsWith(".bpf")) {
                val find = { name: String -> (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == name } }
                val btf = find(".BTF") ?: return@runCatching
                val sec = find(".maps") ?: return@runCatching
                val symtab = (0 until shnum).firstOrNull { b.getInt(sh(it) + 4) == 2 } ?: return@runCatching
                val strtab = b.getInt(sh(symtab) + 0x28)
                val symOff = b.getLong(sh(symtab) + 0x18).toInt(); val symCount = (b.getLong(sh(symtab) + 0x20) / 24).toInt()
                val names = (0 until symCount).map { symOff + it * 24 }.filter { (b.getShort(it + 6).toInt() and 0xffff) == sec }.map { str(strtab, b.getInt(it)) }.toSet()
                val at = b.getLong(sh(btf) + 0x18).toInt(); val size = b.getLong(sh(btf) + 0x20).toInt()
                for ((name, note) in btfMapNotes(java.nio.ByteBuffer.wrap(b.array(), at, size).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN), names)) {
                    val out = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    note.forEach { out.putInt(it) }
                    File(tmp, "map_${o.name.removeSuffix(".bpf")}_$name").writeBytes(out.array())
                    n++
                }
                return@runCatching
            }
            val maps = (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == "maps" }
                ?: (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == ".android_maps" } ?: return@runCatching
            val pinned = str(shstr, b.getInt(sh(maps))) == ".android_maps"
            val symtab = (0 until shnum).firstOrNull { b.getInt(sh(it) + 4) == 2 } ?: return@runCatching
            val strtab = b.getInt(sh(symtab) + 0x28)
            val symOff = b.getLong(sh(symtab) + 0x18).toInt(); val symCount = (b.getLong(sh(symtab) + 0x20) / 24).toInt()
            val mapsOff = b.getLong(sh(maps) + 0x18).toInt(); val mapsSize = b.getLong(sh(maps) + 0x20)
            val obj = o.name.removeSuffix(".o").substringBefore('@')
            
            val btfSizes = (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == ".BTF" }?.let { bi ->
                runCatching { btfAndroidMapSizes(java.nio.ByteBuffer.wrap(b.array(), b.getLong(sh(bi) + 0x18).toInt(), b.getLong(sh(bi) + 0x20).toInt()).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)) }.getOrNull()
            } ?: emptyMap()
            for (i in 0 until symCount) {
                val s = symOff + i * 24
                if ((b.getShort(s + 6).toInt() and 0xffff) != maps) continue
                val name = str(strtab, b.getInt(s))
                val at = b.getLong(s + 8)
                if (name.isEmpty() || at + 12 > mapsSize) continue
                val note = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until 3) note.putInt(b.getInt(mapsOff + at.toInt() + k * 4))
                var file = "map_${obj}_$name"
                
                
                
                btfSizes[name.removeSuffix("_def")]?.let { if (note.getInt(0) != 27) { note.putInt(4, it.first); note.putInt(8, it.second) } }
                
                
                
                
                
                if (obj == "netd") {
                    val base = name.removeSuffix("_def")
                    val known = NETD_MAP_SIZES[base]
                    if (known != null && (note.getInt(4) == 0 || note.getInt(8) == 0)) { note.putInt(4, known.first); note.putInt(8, known.second) }
                    val raw = (0 until minOf(10, (mapsSize - at).toInt() / 4)).joinToString(" ") { "%08x".format(b.getInt(mapsOff + at.toInt() + it * 4)) }
                    log("eBPF def $base in ${o.name} (size ${b.getLong(s + 16)}): $raw -> key ${note.getInt(4)} value ${note.getInt(8)}")
                }
                if (pinned) {       
                    val size = b.getLong(s + 16)
                    val n = ((size - 52) / 2).toInt()
                    if (n <= 0 || at + size > mapsSize) continue
                    val path = StringBuilder()
                    var q = mapsOff + at.toInt() + 48 + n
                    val end = q + n
                    while (q < end && b.get(q).toInt() != 0) path.append(b.get(q++).toInt().toChar())
                    if (path.isNotEmpty()) file = path.substring(path.lastIndexOf('/') + 1)
                }
                File(tmp, file).writeBytes(note.array())
                n++
            }
        }
        runCatching { File(tmp, ".stamp").writeText(BPF_NOTES_STAMP) }
        dir.deleteRecursively()
        val vendorSide = perPart.filterKeys { it != "system" && it != "apex" }
        if (tmp.renameTo(dir)) log("eBPF: $n map notes from ${objects.size} objects (vendor ${vendorSide.values.sum()}" +
            (if (vendorSide.isNotEmpty()) ": " + vendorSide.entries.joinToString(", ") { "${it.key} ${it.value}" } else "") + ")")
    }

    
    private fun guestOverlay() {
        val base = ctx.getExternalFilesDir(null) ?: return
        val rootPath = root.canonicalFile.toPath()
        var n = 0
        for (dir in listOf(File(base, "guest-overlay"), File(base, "guest-overlay-${img.id}"))) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                runCatching {
                    val rel = f.relativeTo(dir).path
                    val dst = File(root, rel).canonicalFile
                    if (!dst.toPath().startsWith(rootPath) || dst == root.canonicalFile) { log("overlay: $rel is outside the tree, skipped"); return@forEach }
                    if (dst.isFile && dst.length() == f.length()) return@forEach
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    n++
                    log("overlay: $rel (${f.length()} bytes)")
                }.onFailure { log("overlay: ${f.name}: ${it.message}") }
            }
        }
        if (n > 0) log("overlay: $n files added to the guest tree")
    }

    
    private fun kernelPolicyUnread() {
        if (engine != Engine.N64 || !kernel.kernelPolicyUnread) return
        val asked = Regex("<kernel-sepolicy-version>\\s*[1-9][0-9]*\\s*</kernel-sepolicy-version>")
        for (part in listOf("system", "system_ext", "product")) {
            for (f in File(root, "$part/etc/vintf").listFiles() ?: continue) {
                if (!f.isFile || !f.name.startsWith("compatibility_matrix") || !f.name.endsWith(".xml")) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!asked.containsMatchIn(text)) continue
                runCatching { f.setWritable(true); f.writeText(asked.replace(text, "<kernel-sepolicy-version>0</kernel-sepolicy-version>")) }
                    .onSuccess { log("build check: ${f.name} no longer asks for a kernel policy version") }
            }
        }
    }

    
    private fun aconfigStorage() {
        if (engine != Engine.N64 || !kernel.aconfigStorage) return
        val maps = File(root, "metadata/aconfig/maps").apply { mkdirs() }
        val boot = File(root, "metadata/aconfig/boot").apply { mkdirs() }
        val sources = listOf("system", "system_ext", "product", "vendor").map { it to File(root, "$it/etc/aconfig") } +
            (File(root, "apex").listFiles()?.filter { it.isDirectory && it.name != "sharedlibs" }?.map { it.name to File(it, "etc") } ?: emptyList())
        var n = 0
        for ((container, dir) in sources) {
            val pkg = File(dir, "package.map"); val flag = File(dir, "flag.map"); val value = File(dir, "flag.val")
            if (!pkg.isFile || !flag.isFile || !value.isFile) continue
            val out = File(boot, "$container.val")
            if (out.isFile && out.length() == value.length() && out.lastModified() >= value.lastModified()) continue
            runCatching {
                pkg.copyTo(File(maps, "$container.package.map"), true)
                flag.copyTo(File(maps, "$container.flag.map"), true)
                File(dir, "flag.info").takeIf { it.isFile }?.copyTo(File(boot, "$container.info"), true)
                value.copyTo(out, true)
                for (f in listOf(File(maps, "$container.package.map"), File(maps, "$container.flag.map"), File(boot, "$container.info"), out)) f.setReadable(true, false)
                n++
            }.onFailure { log("aconfig $container: ${it.message}") }
        }
        if (n > 0) log("aconfig: the flag storage of $n containers")
    }

    
    private fun btfAndroidMapSizes(b: java.nio.ByteBuffer): Map<String, Pair<Int, Int>> {
        if (b.limit() < 24 || (b.getShort(0).toInt() and 0xffff) != 0xeb9f) return emptyMap()
        val hdr = b.getInt(4); val typeOff = hdr + b.getInt(8); val typeLen = b.getInt(12); val strOff = hdr + b.getInt(16)
        fun str(off: Int): String { val sb = StringBuilder(); var p = strOff + off; while (p < b.limit() && b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar()); return sb.toString() }
        val types = ArrayList<Int>().apply { add(-1) }      
        var p = typeOff
        while (p + 12 <= typeOff + typeLen) {
            types.add(p)
            val info = b.getInt(p + 4); val vlen = info and 0xffff
            p += 12 + when ((info ushr 24) and 0x1f) { 1, 14, 17 -> 4; 3 -> 12; 4, 5, 15, 19 -> vlen * 12; 6, 13 -> vlen * 8; else -> 0 }
        }
        fun kind(id: Int) = if (id <= 0 || id >= types.size) 0 else (b.getInt(types[id] + 4) ushr 24) and 0x1f
        fun ref(id: Int) = b.getInt(types[id] + 8)
        fun sizeOf(id: Int, depth: Int = 0): Int = when (if (depth > 16) 0 else kind(id)) {
            1, 4, 5, 6, 16, 19 -> ref(id)
            2 -> 8
            3 -> sizeOf(b.getInt(types[id] + 12), depth + 1) * b.getInt(types[id] + 20)
            8, 9, 10, 11, 14, 18 -> sizeOf(ref(id), depth + 1)
            else -> 0
        }
        val out = HashMap<String, Pair<Int, Int>>()
        for (id in 1 until types.size) {
            if (kind(id) != 15) continue
            val sec = str(b.getInt(types[id]))
            if (!sec.startsWith(".maps.")) continue
            for (v in 0 until (b.getInt(types[id] + 4) and 0xffff)) {
                var t = b.getInt(types[id] + 12 + v * 12)               
                if (kind(t) != 14) continue
                t = ref(t)
                while (kind(t) in listOf(8, 9, 10, 11)) t = ref(t)
                if (kind(t) != 4) continue
                var key = 0; var value = 0
                for (m in 0 until (b.getInt(types[t] + 4) and 0xffff)) {
                    val e = types[t] + 12 + m * 12
                    when (str(b.getInt(e))) { "key" -> key = sizeOf(b.getInt(e + 4)); "value" -> value = sizeOf(b.getInt(e + 4)) }
                }
                if (key > 0 && value > 0) out[sec.removePrefix(".maps.")] = key to value
            }
        }
        return out
    }

    
    private fun btfMapNotes(b: java.nio.ByteBuffer, names: Set<String>): Map<String, IntArray> {
        if (b.limit() < 24 || (b.getShort(0).toInt() and 0xffff) != 0xeb9f) return emptyMap()
        val hdr = b.getInt(4); val typeOff = hdr + b.getInt(8); val typeLen = b.getInt(12); val strOff = hdr + b.getInt(16)
        fun str(off: Int): String { val sb = StringBuilder(); var p = strOff + off; while (p < b.limit() && b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar()); return sb.toString() }
        val types = ArrayList<Int>().apply { add(-1) }      
        var p = typeOff
        while (p + 12 <= typeOff + typeLen) {
            types.add(p)
            val info = b.getInt(p + 4); val vlen = info and 0xffff
            p += 12 + when ((info ushr 24) and 0x1f) { 1, 14, 17 -> 4; 3 -> 12; 4, 5, 15, 19 -> vlen * 12; 6, 13 -> vlen * 8; else -> 0 }
        }
        fun kind(id: Int) = if (id <= 0 || id >= types.size) 0 else (b.getInt(types[id] + 4) ushr 24) and 0x1f
        fun ref(id: Int) = b.getInt(types[id] + 8)
        fun sizeOf(id: Int, depth: Int = 0): Int = when (if (depth > 16) 0 else kind(id)) {
            1, 4, 5, 6, 16, 19 -> ref(id)
            2 -> 8
            3 -> sizeOf(b.getInt(types[id] + 12), depth + 1) * b.getInt(types[id] + 20)
            8, 9, 10, 11, 14, 18 -> sizeOf(ref(id), depth + 1)
            else -> 0
        }
        fun count(id: Int): Int = if (kind(id) == 2 && kind(ref(id)) == 3) b.getInt(types[ref(id)] + 20) else 0
        val out = HashMap<String, IntArray>()
        for (id in 1 until types.size) {
            if (kind(id) != 14) continue
            val name = str(b.getInt(types[id]))
            var t = ref(id)
            while (kind(t) in listOf(8, 9, 10, 11)) t = ref(t)
            if (name !in names || kind(t) != 4) continue
            val note = IntArray(3)
            for (m in 0 until (b.getInt(types[t] + 4) and 0xffff)) {
                val e = types[t] + 12 + m * 12
                val type = b.getInt(e + 4)
                when (str(b.getInt(e))) {
                    "type" -> note[0] = count(type)
                    "key_size" -> note[1] = count(type)
                    "value_size" -> note[2] = count(type)
                    "key" -> if (kind(type) == 2) note[1] = sizeOf(ref(type))
                    "value" -> if (kind(type) == 2) note[2] = sizeOf(ref(type))
                }
            }
            if (note[0] != 0) out[name] = note
        }
        return out
    }

    
    private fun apexVersion(f: File): Long {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        var i = 0
        while (i < d.size) {
            val tag = d[i++].toInt() and 0xff
            var v = 0L; var s = 0
            fun varint(): Long { v = 0; s = 0; while (i < d.size) { val b = d[i++].toInt() and 0xff; v = v or ((b and 0x7f).toLong() shl s); s += 7; if (b < 0x80) break }; return v }
            when (tag and 7) {
                0 -> { val x = varint(); if (tag ushr 3 == 2) return x }
                2 -> { val n = varint().toInt(); i += n }
                else -> return 0
            }
        }
        return 0
    }

    
    private fun libKnows(lib: String, marker: String): Boolean {
        val f = listOf("system/lib64/$lib", "system/lib/$lib").map { File(root, it) }.firstOrNull { it.isFile } ?: return true
        val m = marker.toByteArray()
        val d = runCatching { f.readBytes() }.getOrNull() ?: return true
        outer@ for (i in 0..d.size - m.size) {
            for (j in m.indices) if (d[i + j] != m[j]) continue@outer
            return true
        }
        return false
    }

    
    private fun preOnePowerNodes(dir: File) = runCatching {
        val names = LinkedHashSet<String>()
        names += listOf("last_user_activity", "auto_off_timeout", "brightness", "state")
        val candidates = ArrayList<File>()
        for (d in listOf("system/bin", "system/lib", "android/bin", "android/lib")) {
            File(root, d).listFiles()?.filter { it.isFile && (it.name == "runtime" || it.name.endsWith(".so")) }?.let { candidates += it }
        }
        val re = Regex("/sys/android_power/([a-z_0-9]+)")
        for (f in candidates) {
            if (f.length() > 30_000_000) continue
            val text = runCatching { String(f.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: continue
            if (!text.contains("android_power")) continue
            for (m in re.findAll(text)) names += m.groupValues[1]
        }
        for (n in names) {
            if (n.startsWith("wait_for_fb")) continue   
            File(dir, n).let { f ->
                if (!f.exists()) f.createNewFile()
                f.setReadable(true, false); f.setWritable(true, false)
            }
        }
        
        for ((n, v) in listOf("battery_level" to "100", "battery_level_scale" to "100", "battery_level_raw" to "100", "charging_state" to "Maintaining")) {
            File(dir, n).let { f ->
                if (!f.isFile || f.length() == 0L) f.writeText(v + "\n")
                f.setReadable(true, false); f.setWritable(true, false)
            }
        }
        log("legacy power: /sys/android_power has ${dir.list()?.size ?: 0} nodes (${names.joinToString(",")})")
    }.onFailure { log("legacy power: cannot make the /sys/android_power nodes: ${it.message}") }

    private fun makeSysNodes() {
        val freq = File(root, "sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state")
        if (!freq.isFile) {
            freq.parentFile?.mkdirs()
            freq.writeText(listOf(384000, 594000, 810000, 1026000, 1242000, 1512000).joinToString("\n") { "$it 0" } + "\n")
        }
        
        
        
        runCatching {
            val cf = File(root, "sys/devices/system/cpu/cpu0/cpufreq").apply { mkdirs() }
            run { var d: File? = cf; while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile } }
            for ((n, v) in listOf(
                "scaling_governor" to "ondemand",
                "scaling_available_governors" to "ondemand performance powersave userspace",
                "scaling_available_frequencies" to "384000 594000 810000 1026000 1242000 1512000",
                "scaling_min_freq" to "384000", "scaling_max_freq" to "1512000", "scaling_cur_freq" to "1512000",
                "cpuinfo_min_freq" to "384000", "cpuinfo_max_freq" to "1512000", "cpuinfo_cur_freq" to "1512000",
            )) {
                File(cf, n).let { f ->
                    if (!f.isFile || f.length() == 0L) f.writeText(v + "\n")
                    f.setReadable(true, false); f.setWritable(true, false)
                }
            }
        }.onFailure { log("sysfs: cannot create cpufreq nodes: ${it.message}") }
        val cpus = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val cpu = File(root, "sys/devices/system/cpu").apply { mkdirs() }
        for ((n, v) in listOf("present" to "0-${cpus - 1}", "possible" to "0-${cpus - 1}", "online" to "0-${cpus - 1}", "offline" to "")) {
            File(cpu, n).let { if (!it.isFile) it.writeText(v + "\n") }
        }
        
        
        
        val lo = File(root, "sys/class/net/lo").apply { mkdirs() }
        run { var d: File? = lo; while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile } }
        for ((n, v) in listOf("ifindex" to "1", "flags" to "0x9", "mtu" to "16436", "type" to "772", "operstate" to "unknown",
            "carrier" to "1", "address" to "00:00:00:00:00:00", "addr_len" to "6", "dev_id" to "0x0")) {
            runCatching {
                File(lo, n).let { f -> if (!f.isFile || f.length() == 0L) f.writeText(v + "\n"); f.setReadable(true, false) }
            }.onFailure { log("sysfs: cannot create /sys/class/net/lo/$n: ${it.message}") }
        }
        
        
        for (n in 0 until cpus) File(cpu, "cpu$n/online").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.writeText("1\n") } }
        if (engine == Engine.N64 && kernel.cgroup2Root) {
            File(root, "sys/fs/cgroup").mkdirs()
            for (d in kernel.cgroupUidParents) File(root, "sys/fs/cgroup/$d").mkdirs()
            
            
            
            
            
            val depth = kernel.cgroupRcDepth && libKnows("libcgrouprc.so", "MaxActivationDepth")
            val b = java.nio.ByteBuffer.allocate(8 + (if (depth) 60 else 56)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putInt(1).putInt(1).putInt(2).putInt(1)
            if (depth) b.putInt(0)
            b.put("cgroup2".toByteArray().copyOf(16)).put("/sys/fs/cgroup".toByteArray().copyOf(32))
            File(root, "dev/cgroup_info").let { it.mkdirs(); File(it, "cgroup.rc").writeBytes(b.array()) }
        }
        
        
        if (engine == Engine.N64) {
            val v = apexVersion(File(root, "apex/com.android.tethering/apex_manifest.pb"))
            File(root, "dev").mkdirs(); File(root, "dev/.aemu-clat-uid").writeText(if (v >= 360000000L) "1029" else "0")
        }
        if (engine == Engine.N64) for ((n, text) in kernel.sysFiles) File(root, "sys/$n").let {
            if (!it.exists()) { it.parentFile?.mkdirs(); it.writeText(text) }
        }
        val base = File(root, "sys/class/power_supply")
        val bat = mapOf(
            "battery/type" to "Battery", "battery/status" to "Full", "battery/health" to "Good",
            "battery/present" to "1", "battery/capacity" to "100", "battery/batt_vol" to "4200",
            "battery/voltage_now" to "4200000", "battery/batt_temp" to "250", "battery/temp" to "250",
            "battery/technology" to "Li-ion", "ac/type" to "Mains", "ac/online" to "1",
            "usb/type" to "USB", "usb/online" to "0",
            
            
            "battery/charging_source" to "0",
            
            
            
            "battery/charge_counter" to "3000000", "battery/charge_full" to "4000000", "battery/charge_full_design" to "4000000",
            "battery/current_now" to "0", "battery/current_avg" to "0", "battery/cycle_count" to "1",
            "battery/capacity_level" to "Full", "battery/energy_full" to "4000000", "battery/energy_now" to "4000000",
            "battery/time_to_full_now" to "0",
            
            
            
            "battery/charging_enabled" to "1", "battery/battery_charging_enabled" to "1",
            "battery/input_suspend" to "0", "qcom-battery/input_suspend" to "0",
        )
        for ((rel, v) in bat) File(base, rel).let { if (!it.isFile) { it.parentFile?.mkdirs(); it.writeText(v + "\\n"); it.setWritable(true, false) } }
        
        
        
        if (engine == Engine.N64) runCatching {
            val pw = File(root, "sys/power").apply { mkdirs() }
            File(pw, "wakeup_count").let { if (!it.isFile) it.writeText("0\\n"); it.setReadable(true, false); it.setWritable(true, false) }
            File(pw, "suspend_stats").apply { mkdirs() }.let { d ->
                for (n in listOf("success", "fail", "failed_freeze", "failed_prepare", "failed_suspend", "failed_suspend_late",
                    "failed_suspend_noirq", "failed_resume", "failed_resume_early", "failed_resume_noirq"))
                    File(d, n).let { if (!it.isFile) it.writeText("0\\n") }
                for (n in listOf("last_failed_dev", "last_failed_step")) File(d, n).let { if (!it.isFile) it.writeText("\\n") }
                File(d, "last_failed_errno").let { if (!it.isFile) it.writeText("0\\n") }
            }
            for (g in listOf("", "foreground", "background", "top-app", "system-background", "restricted", "camera-daemon"))
                File(root, "dev/cpuset/$g").apply { mkdirs() }.let { d ->
                    for (n in listOf("tasks", "cgroup.procs")) File(d, n).let { f -> if (!f.exists()) f.createNewFile(); f.setReadable(true, false); f.setWritable(true, false) }
                    if (g.isNotEmpty()) File(d, "cpus").let { f -> if (!f.isFile) f.writeText("0-7\\n") }
                    if (g.isNotEmpty()) File(d, "mems").let { f -> if (!f.isFile) f.writeText("0\\n") }
                }
        }.onFailure { log("sysfs: cannot create the suspend / cpuset nodes: ${it.message}") }
        healthdRelativeSysfs(base)
        personalityNoop()
        hostPathAlias()
        parkNfc()
        mainStackMaps()
        val power = File(root, "sys/power").apply { mkdirs() }
        for (n in listOf("state", "wake_lock", "wake_unlock", "autosleep")) File(power, n).let { if (!it.isFile) it.createNewFile(); it.setWritable(true, false) }
        
        
        
        
        runCatching {
            File(power, "cpufreq_table").let { f ->
                if (!f.isFile || f.length() == 0L) f.writeText("1512000 1242000 1026000 810000 594000 384000\n")
                f.setReadable(true, false); f.setWritable(true, false)
            }
        }.onFailure { log("sysfs: cannot create /sys/power/cpufreq_table: ${it.message}") }
        
        
        runCatching {
            val lib = File(root, "system/lib/libhardware_legacy.so")
            if (lib.isFile) {
                val text = String(lib.readBytes(), Charsets.ISO_8859_1)
                for (m in Regex("/sys/(power|android_power)/[a-z_0-9]+").findAll(text)) {
                    val rel = m.value.removePrefix("/")
                    if (rel.contains("wait_for_fb")) continue 
                    File(root, rel).let { f -> if (!f.exists()) { f.parentFile?.mkdirs(); f.createNewFile(); f.setWritable(true, false) } }
                }
            }
        }
        
        
        
        
        val androidPower = File(root, "sys/android_power").apply { mkdirs() }
        
        for (n in listOf("acquire_partial_wake_lock", "acquire_full_wake_lock", "release_wake_lock", "request_state"))
            File(androidPower, n).let { f -> runCatching { if (!f.exists()) f.createNewFile(); f.setReadable(true, false); f.setWritable(true, false) } }
        if (LegacyFirmware.isPreRelease(img.release)) preOnePowerNodes(androidPower)
        for (dir in listOf(power, androidPower)) runCatching {
            File(dir, "wait_for_fb_wake").let { if (!it.isFile) it.writeText("awake") }
            val sleep = File(dir, "wait_for_fb_sleep")
            if (!sleep.exists()) android.system.Os.mkfifo(sleep.absolutePath, "666".toInt(8))
        }
        
        
        
        runCatching {
            val lib = File(root, "system/lib/libsuspend.so")
            if (lib.isFile && contains(lib, "wait_for_fb_sleep")) File(power, "state").setWritable(false, false)
        }
        
        val bl = File(root, "sys/class/leds/lcd-backlight").apply { mkdirs() }
        File(bl, "brightness").let { if (!it.isFile) it.writeText("255\n") }
        File(bl, "max_brightness").let { if (!it.isFile) it.writeText("255\n") }
        
        
        if (img.brand.equals("Samsung", true) || img.skin.contains("TouchWiz", true)) {
            val key = File(root, "sys/class/sec/sec_key").apply { mkdirs() }
            File(key, "hall_detect").let { if (!it.isFile) it.writeText("OPEN\n") }
        }
        lowMemoryKillerNodes()
        procNetNodes()
        javaIfInet6Redirect()
        settingsCpuinfoRedirect()
        launcherProximityGuard()
        systemWatchdogGuard()
        keyguardSecureGuard()
        dropGnssHal()
        libRsUploadGuard()
        tungstenLedNodes()
        tungstenLedInitPatch()
        tungstenLedCountPatch()
        idmeSysNodes()
    }

    
    private fun idmeSysNodes() = runCatching {
        val libs = IDME_LIBS.map { File(root, it) }.filter { it.isFile }
        if (libs.isEmpty()) return@runCatching 
        val paths = LinkedHashSet<String>().apply { add(IDME_SHIFT_NODE) }
        for (lib in libs) if (lib.length() < 20_000_000) paths += idmeSysPaths(lib.readBytes())
        for (path in idmeNodesToCreate(paths)) runCatching {
            val f = File(root, path.removePrefix("/"))
            if (f.exists()) { f.setReadable(true, false); f.setWritable(true, false); return@runCatching }
            f.parentFile?.let { dir ->
                dir.mkdirs()
                var d: File? = dir
                while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
            }
            f.writeText("0\n")
            f.setReadable(true, false); f.setWritable(true, false)
            log("idme: placeholder sysfs node $path")
        }.onFailure { log("idme: cannot create $path: ${it.message}") }
    }.onFailure { log("idme: sysfs node scan failed: ${it.message}") }

    
    private val GUEST_CPUINFO: String = buildString {
        for (i in 0 until 4) {
            append("processor\t: $i\nmodel name\t: ARMv7 Processor rev 3 (v7l)\nBogoMIPS\t: 38.40\n")
            append("Features\t: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm\n")
            append("CPU implementer\t: 0x41\nCPU architecture: 7\nCPU variant\t: 0x2\nCPU part\t: 0xc0f\nCPU revision\t: 3\n\n")
        }
        append("Hardware\t: Yellowstone\nRevision\t: 0000\nSerial\t\t: 0000000000000000\n")
    }

    
    private fun procNetNodes() {
        val nodes = linkedMapOf(
            "proc/net/if_inet6" to "00000000000000000000000000000001 01 80 10 80       lo\n",
            "proc/version" to VmSettings.procVersion(img.settings),
            
            
            
            "proc/versioncheck" to "",
            
            
            "proc/cpuinfo" to GUEST_CPUINFO,
        )
        
        val writable = setOf("proc/versioncheck")
        
        for (rel in listOf("proc", "proc/net")) runCatching {
            val d = File(root, rel)
            if (isLink(d) || (d.exists() && !d.isDirectory)) wipe(d)
            if (!d.isDirectory && !d.mkdirs()) error("mkdirs failed")
            Os.chmod(d.path, 0b111_101_101)
        }.onFailure { log("procfs: cannot prepare /$rel: ${it.message}") }
        for ((rel, v) in nodes) runCatching {
            val f = File(root, rel)
            if (isLink(f) || f.isDirectory) wipe(f)
            f.parentFile?.mkdirs()
            if (!f.isFile || (rel !in writable && f.readText() != v)) f.writeText(v)
            
            Os.chmod(f.path, if (rel in writable) 0b110_110_110 else 0b110_100_100)
            val back = f.readText()
            val mode = Os.stat(f.path).st_mode and 0xfff
            if (rel !in writable && back != v) error("read-back mismatch (${back.length} bytes)")
            log("procfs: /$rel served from the tree (${Integer.toOctalString(mode)}, ${f.length()} bytes)")
        }.onFailure { log("procfs: cannot create /$rel: ${it.message}") }
    }

    
    private fun javaIfInet6Redirect() = runCatching {
        if (img.api > 20) return@runCatching
        val odex = File(root, "system/framework/core.odex").takeIf { it.isFile }
        val targets = if (odex != null) listOf(odex)
        else listOfNotNull(File(root, "data/dalvik-cache/system@framework@core.jar@classes.dex").takeIf { it.isFile })
        if (targets.isEmpty()) {
            if (File(root, "system/framework/core.jar").isFile) log("libcore: no core.odex or dalvik-cache copy yet; if_inet6 redirect follows after the first boot")
            return@runCatching
        }
        val node = File(root, "data/.aemu_ifinet")
        val text = "00000000000000000000000000000001 01 80 10 80       lo\n"
        node.parentFile?.mkdirs()
        if (isLink(node) || node.isDirectory) wipe(node)
        if (!node.isFile || node.readText() != text) node.writeText(text)
        Os.chmod(node.path, 0b110_100_100)
        for (f in targets) {
            val d = f.readBytes()
            when (DexStringPatch.apply(d)) {
                1 -> {
                    f.setWritable(true, true)
                    f.writeBytes(d)
                    log("libcore: ${f.name} now reads the interface list from /data/.aemu_ifinet")
                }
                0 -> {}
                else -> log("libcore: /proc/net/if_inet6 string not found in ${f.name}, left as is")
            }
        }
    }.onFailure { log("libcore: if_inet6 redirect failed: ${it.message}") }

    
    private fun settingsCpuinfoRedirect() = runCatching {
        if (img.api > 20) return@runCatching
        val targets = listOf(
            "system/priv-app/Settings.odex", "system/app/Settings.odex",
            "data/dalvik-cache/system@priv-app@Settings.apk@classes.dex",
            "data/dalvik-cache/system@app@Settings.apk@classes.dex",
        ).map { File(root, it) }.filter { it.isFile }
        if (targets.isEmpty()) return@runCatching
        val node = File(root, "data/.cpuinf")
        node.parentFile?.mkdirs()
        if (isLink(node) || node.isDirectory) wipe(node)
        if (!node.isFile || node.readText() != GUEST_CPUINFO) node.writeText(GUEST_CPUINFO)
        Os.chmod(node.path, 0b110_100_100)
        for (f in targets) {
            val d = f.readBytes()
            when (DexStringPatch.apply(d, "/proc/cpuinfo", "/data/.cpuinf")) {
                1 -> { f.setWritable(true, true); f.writeBytes(d); log("settings: ${f.name} now reads cpuinfo from /data/.cpuinf") }
                0 -> {}
                else -> log("settings: /proc/cpuinfo string not found in ${f.name}, left as is")
            }
        }
    }.onFailure { log("settings: cpuinfo redirect failed: ${it.message}") }

    
    private fun dropGnssHal() = runCatching {
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val origDir = File(root, "system/.aemu-vintf-orig")
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml",
            "system/vendor/etc/vintf/manifest.xml", "odm/etc/vintf/manifest.xml").map { File(root, it) } +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest", "odm/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
            }
        val seen = HashSet<String>()
        for (f in files) {
            if (!f.isFile || !seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if ("gnss" !in text) continue
            val removed = ArrayList<String>()
            val out = hal.replace(text) { m ->
                val n = name.find(m.value)?.groupValues?.get(1)
                if (n != null && (n == "android.hardware.gnss" || n.contains(".gnss"))) { removed += n; "" } else m.value
            }
            if (removed.isEmpty()) continue
            runCatching {
                backupOnce(f, File(origDir, f.relativeTo(root).path.replace('/', '#')))
                f.setWritable(true); f.writeText(out)
                log("${f.relativeTo(root).path}: GNSS HAL dropped: ${removed.distinct().joinToString()}")
            }.onFailure { log("${f.name}: GNSS HAL not dropped: ${it.message}") }
        }
    }.onFailure { log("gnss: manifest check failed: ${it.message}") }

    
    private fun systemWatchdogGuard() = runCatching {
        val jar = listOf("system/framework/services.jar", "system/system/framework/services.jar")
            .map { File(root, it) }.firstOrNull { it.isFile }
        val backup = File(root, "system/.aemu-parked/system#framework#services.jar.orig")
        
        
        val dog = img.api >= 34 && img.settings.disableWatchdog
        val perm = dog || (img.api in 29..33 && img.settings.skipPermissionWait)
        fun restoreStock(why: String) {
            backup.copyTo(jar!!, overwrite = true)
            Os.chmod(jar.path, 0b110_100_100)
            log("watchdog: $why, services.jar restored to the stock one")
        }
        if (!perm) {
            if (jar != null && backup.isFile && !backup.readBytes().contentEquals(jar.readBytes())) restoreStock("option is off")
            if (Dex2oatSkip.uninstall(root)) log("dex2oat: original binaries put back")
            return@runCatching
        }
        if (jar == null) { log("watchdog: no system/framework/services.jar to patch"); return@runCatching }
        if (backup.isFile && !WatchdogPatch.isStockPlusPatch(jar, backup, dog, perm)) restoreStock("the jar was edited by something else")
        val r = WatchdogPatch.patchJar(jar, backup) { WatchdogPatch.patchDex(it, dog, perm) }
        val what = if (dog) "system_server's Watchdog thread now ends at once (a real deadlock is no longer recovered by a restart) and the boot no longer waits for the permission controller's default grants"
            else "the boot no longer waits for the permission controller's default grants (PermissionPolicyService.grantOrUpgradeDefaultRuntimePermissionsIfNeeded returns at once)"
        when (r.outcome) {
            WatchdogPatch.Outcome.PATCHED -> {
                Os.chmod(jar.path, 0b110_100_100)
                log("watchdog: services.jar patched, $what")
            }
            WatchdogPatch.Outcome.ALREADY -> log("watchdog: services.jar is already patched")
            WatchdogPatch.Outcome.ABSENT -> log("watchdog: the classes to patch were not found in services.jar, left as is")
            WatchdogPatch.Outcome.UNEXPECTED -> log("watchdog: services.jar has the classes, but not the code this patch knows (${r.detail}), left as is")
        }
        
        
        val patched = r.outcome == WatchdogPatch.Outcome.PATCHED || r.outcome == WatchdogPatch.Outcome.ALREADY
        if (img.api in 29..33) {
            if (patched) { if (Dex2oatSkip.install(root)) log("dex2oat: wrapper refuses to compile the patched services.jar") }
            else if (Dex2oatSkip.uninstall(root)) log("dex2oat: original binaries put back")
        }
    }.onFailure { log("watchdog: services.jar patch failed: ${it.message}") }

    
    private fun keyguardSecureGuard() = runCatching {
        if (img.api > 20) return@runCatching
        val jar = listOf("system/framework/android.policy.jar", "system/system/framework/android.policy.jar")
            .map { File(root, it) }.firstOrNull { it.isFile } ?: return@runCatching
        val backup = File(root, "system/.aemu-parked/system#framework#android.policy.jar.orig")
        if (backup.isFile && !KeyguardSecurePatch.isStockPlusPatch(jar, backup)) {
            backup.copyTo(jar, overwrite = true)
            Os.chmod(jar.path, 0b110_100_100)
            log("keyguard: the jar was edited by something else, android.policy.jar restored to the stock one")
        }
        val r = KeyguardSecurePatch.patchJar(jar, backup)
        when (r.outcome) {
            WatchdogPatch.Outcome.PATCHED -> {
                Os.chmod(jar.path, 0b110_100_100)
                log("keyguard: android.policy.jar patched, KeyguardServiceWrapper.isSecure()/isShowing() no longer call SystemUI while the ActivityManager lock is held")
            }
            WatchdogPatch.Outcome.ALREADY -> log("keyguard: android.policy.jar is already patched")
            WatchdogPatch.Outcome.ABSENT -> {}
            WatchdogPatch.Outcome.UNEXPECTED -> log("keyguard: android.policy.jar has KeyguardServiceWrapper, but not the code this patch knows (${r.detail}), left as is")
        }
    }.onFailure { log("keyguard: android.policy.jar patch failed: ${it.message}") }

    
    private fun launcherProximityGuard() = runCatching {
        if (img.api > 20) return@runCatching
        val targets = ArrayList<File>()
        for (dir in listOf("system/app", "system/priv-app"))
            File(root, dir).listFiles()?.filterTo(targets) { it.isFile && it.name.endsWith(".odex") && it.name.contains("Launcher", true) }
        File(root, "data/dalvik-cache").listFiles()?.filterTo(targets) {
            it.isFile && it.name.contains("Launcher", true) && it.name.endsWith("@classes.dex")
        }
        for (f in targets) {
            val d = f.readBytes()
            when (LauncherProximityPatch.apply(d)) {
                LauncherProximityPatch.PATCHED -> {
                    f.setWritable(true, true); f.writeBytes(d)
                    log("launcher: ${f.name} no longer fails when the guest has no proximity sensor")
                }
                LauncherProximityPatch.UNEXPECTED -> log("launcher: ${f.name} looks up the proximity sensor, but not in the code this patch knows, left as is")
                else -> {}
            }
        }
    }.onFailure { log("launcher: proximity guard failed: ${it.message}") }

    
    private fun libRsUploadGuard() = runCatching {
        if (img.api >= 9 || !(img.model.contains("Nexus One", true) || img.name.contains("Nexus One", true))) return@runCatching
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            val f = File(root, "$dir/libRS.so")
            if (!f.isFile || isLink(f)) continue
            val d = f.readBytes()
            when (LibRsUploadPatch.apply(d)) {
                LibRsUploadPatch.PATCHED -> {
                    f.setWritable(true, true); f.writeBytes(d)
                    log("RenderScript: ${f.name} no longer aborts when a texture is uploaded without a GL context")
                }
                LibRsUploadPatch.UNEXPECTED -> log("RenderScript: ${f.name} has the texture-upload assert, but not in the code this patch knows, left as is")
                else -> {}
            }
        }
    }.onFailure { log("RenderScript: upload guard failed: ${it.message}") }

    
    private fun tungstenLedNodes() = runCatching {
        val re = Regex("/dev/[A-Za-z0-9_./-]{2,40}")
        val found = LinkedHashSet<String>()
        fun scan(bytes: ByteArray) { for (m in re.findAll(String(bytes, Charsets.ISO_8859_1))) found += m.value.trimEnd('.', '/') }
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib", "system/app", "system/priv-app")) {
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name.contains("led", true) }.forEach { libs += it }
        }
        for (f in libs) {
            if (f.name.endsWith(".so")) scan(f.readBytes())
            else if (f.name.endsWith(".apk")) java.util.zip.ZipFile(f).use { z ->
                for (e in z.entries()) if (e.name.startsWith("lib/armeabi") && e.name.endsWith(".so") && e.name.contains("led", true))
                    scan(z.getInputStream(e).use { it.readBytes() })
            }
        }
        val skip = setOf("/dev/null", "/dev/zero", "/dev/log", "/dev/input", "/dev/socket", "/dev/graphics", "/dev/binder", "/dev/ashmem", "/dev/random", "/dev/urandom", "/dev/tty")
        for (path in found) {
            if (path in skip || path.startsWith("/dev/log/") || path.startsWith("/dev/socket/")) continue
            val f = File(root, path.removePrefix("/"))
            if (f.exists()) continue
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(4096))
            f.setReadable(true, false); f.setWritable(true, false)
            log("tungsten: placeholder LED node $path")
        }
        if (found.isNotEmpty()) log("tungsten: /dev paths found in LED libs: ${found.joinToString()}")
    }.onFailure { log("tungsten: LED node scan failed: ${it.message}") }

    
    private fun hw(r: java.io.RandomAccessFile, at: Long): Int { r.seek(at); val a = r.read(); val b = r.read(); return a or (b shl 8) }

    
    private fun tungstenLedInitPatch() = runCatching {
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib"))
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name == "libtungsten_led.so" }.forEach { libs += it }
        val name = "_ZN7android12TungstenLEDs10InitBufferEv"
        for (f in libs) {
            val sym = ElfPatch.symbols(f, setOf(name))?.get(name) ?: continue
            val off = sym and 0xffffffffffL
            java.io.RandomAccessFile(f, "rw").use { r ->
                if (hw(r, off) != 0xb510) { log("tungsten: InitBuffer prologue unexpected in ${f.name}, not patched"); return@use }
                if (hw(r, off + 2) == 0x2120) return@use 
                
                if (hw(r, off + 2) != 0x1d01 || hw(r, off + 4) != 0x4604 || hw(r, off + 6) != 0xf7ff || (hw(r, off + 8) and 0xd000) != 0xd000) {
                    log("tungsten: InitBuffer body unexpected in ${f.name}, not patched"); return@use
                }
                val patch = intArrayOf(0x2120, 0x4604, 0x7121, 0x2000) 
                r.seek(off + 2)
                for (h in patch) { r.write(h and 0xff); r.write(h shr 8) }
                log("tungsten: InitBuffer in ${f.name} no longer needs the LED ioctl (32 LEDs)")
            }
        }
    }.onFailure { log("tungsten: InitBuffer patch failed: ${it.message}") }

    
    private fun tungstenLedCountPatch() = runCatching {
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib"))
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name == "libtungsten_led.so" }.forEach { libs += it }
        val name = "_ZN7android12TungstenLEDs11getLedCountEPh"
        for (f in libs) {
            val off = (ElfPatch.symbols(f, setOf(name))?.get(name) ?: continue) and 0xffffffffffL
            java.io.RandomAccessFile(f, "rw").use { r ->
                if (hw(r, off + 12) == 0x2320) return@use 
                
                if (hw(r, off) != 0xb510 || hw(r, off + 2) != 0x460a || hw(r, off + 4) != 0xb911 ||
                    hw(r, off + 12) != 0x6800 || hw(r, off + 14) != 0x4902 || hw(r, off + 16) != 0xf7ff) {
                    log("tungsten: getLedCount unexpected in ${f.name}, not patched"); return@use
                }
                r.seek(off + 12)
                for (h in intArrayOf(0x2320, 0x7013, 0x2000, 0xbf00)) { r.write(h and 0xff); r.write(h shr 8) }
                log("tungsten: getLedCount in ${f.name} reports 32 LEDs")
            }
        }
    }.onFailure { log("tungsten: getLedCount patch failed: ${it.message}") }

    
    private fun lowMemoryKillerNodes() {
        val nodes = mapOf(
            "sys/module/lowmemorykiller/parameters/adj" to "0,1,2,4,9,15",
            "sys/module/lowmemorykiller/parameters/minfree" to "1536,2048,4096,5120,5632,6144",
            "proc/sys/vm/extra_free_kbytes" to "0",
        )
        for ((rel, v) in nodes) runCatching {
            val f = File(root, rel)
            if (!f.isFile || f.length() == 0L) {
                f.parentFile?.mkdirs()
                f.writeText(v + "\n")
            }
            f.setReadable(true, false)
            f.setWritable(true, false)
        }.onFailure { log("sysfs: cannot create /$rel: ${it.message}") }
    }

    
    private fun makeProcMounts() {
        val mounts = LinkedHashMap<String, String>() 
        fun add(dev: String, point: String, type: String, opts: String) { mounts.putIfAbsent(point, "$dev $point $type $opts 0 0") }
        add("rootfs", "/", "rootfs", "ro,relatime")
        add("tmpfs", "/dev", "tmpfs", "rw,nosuid,relatime,mode=755")
        add("devpts", "/dev/pts", "devpts", "rw,relatime,mode=600")
        add("proc", "/proc", "proc", "rw,relatime")
        add("sysfs", "/sys", "sysfs", "rw,relatime")
        add("none", "/acct", "cgroup", "rw,relatime,cpuacct")
        add("tmpfs", "/mnt/asec", "tmpfs", "rw,relatime,mode=755,gid=1000")
        add("tmpfs", "/mnt/obb", "tmpfs", "rw,relatime,mode=755,gid=1000")
        add("/dev/block/platform/aemu/by-name/system", "/system", "ext4", "ro,relatime")
        add("/dev/block/platform/aemu/by-name/userdata", "/data", "ext4", "rw,nosuid,nodev,noatime")
        add("/dev/block/platform/aemu/by-name/cache", "/cache", "ext4", "rw,nosuid,nodev,noatime")
        val special = setOf("dev", "proc", "sys", "run", "mnt", "storage", "sdcard", "acct", "data", "cache", "system")
        for (d in root.listFiles() ?: emptyArray()) {
            val n = d.name
            if (!d.isDirectory || n in special || java.nio.file.Files.isSymbolicLink(d.toPath())) continue
            add("/dev/block/platform/aemu/by-name/$n", "/$n", "ext4", "rw,relatime")
        }
        
        val rcs = (root.listFiles()?.filter { it.isFile && (it.name.endsWith(".rc") || it.name.startsWith("fstab")) } ?: emptyList()) +
            listOfNotNull(File(root, "system/etc/vold.fstab").takeIf { it.isFile })
        for (f in rcs) runCatching {
            for (raw in f.readLines()) {
                val t = raw.trim().split(Regex("\\s+"))
                if (t.isEmpty() || t[0].startsWith("#")) continue
                when {
                    t[0] == "mount" && t.size >= 4 -> {
                        val point = t[3]
                        if (point.startsWith("/") && point != "/" && !point.startsWith("/proc") && !point.startsWith("/sys") && !point.startsWith("/dev"))
                            add(t[2].substringBefore('@').ifEmpty { "none" }, point, t[1], if (point == "/system") "ro,relatime" else "rw,relatime")
                    }
                    f.name.startsWith("fstab") && t.size >= 3 && t[0].startsWith("/dev") && t[1].startsWith("/") ->
                        add(t[0], t[1], t[2], "rw,relatime")
                }
            }
        }
        
        add("/dev/block/vold/179:1", img.sdcardPath, "vfat", "rw,dirsync,nosuid,nodev,noexec,relatime,uid=1000,gid=1015,fmask=0702,dmask=0702")
        for (p in mounts.keys) if (p != "/" && !p.startsWith("/proc") && !p.startsWith("/sys") && !p.startsWith("/dev")) File(root, p.trimStart('/')).mkdirs()
        val f = File(root, "proc/mounts")
        f.parentFile?.mkdirs()
        val text = mounts.values.joinToString("\n", postfix = "\n")
        if (f.takeIf { it.isFile }?.readText() != text) f.writeText(text)
    }

    
    
    private fun caCerts() = runCatching {
        val dst = File(root, "system/etc/security/cacerts")
        if (!dst.isDirectory) return@runCatching
        val src = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts").map(::File).firstOrNull { it.isDirectory } ?: return@runCatching
        val have = dst.listFiles().orEmpty().mapNotNull { runCatching { it.readText() }.getOrNull() }.toHashSet()
        var added = 0
        for (f in src.listFiles().orEmpty()) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text in have) continue
            val hash = f.name.substringBefore('.')
            val out = (0..9).map { File(dst, "$hash.$it") }.firstOrNull { !it.exists() } ?: continue
            out.writeText(text); out.setReadable(true, false); have += text; added++
        }
        if (added > 0) log("certificates: $added roots added from the phone")
    }

    private fun resolvConf() {
        val conf = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n"
        runCatching {
            val f1 = File(root, "system/etc/resolv.conf")
            f1.parentFile?.mkdirs()
            if (!f1.exists() || f1.length() == 0L) f1.writeText(conf)
            val f2 = File(root, "etc/resolv.conf")
            f2.parentFile?.mkdirs()
            if (!f2.exists() || f2.length() == 0L) f2.writeText(conf)
        }
    }

    
    private fun audioPolicy() {
        val cur = File(root, "system/etc/audio_policy.conf")
        val rom = File(root, "system/etc/audio_policy.conf.rom")
        if (!cur.isFile && !rom.isFile && img.api < 16) return
        runCatching {
            if (cur.isFile && !rom.isFile) cur.copyTo(rom)
            val text = """
                |# создано AEmulator: единственный модуль — звуковой HAL эмулятора
                |global_configuration {
                |  attached_output_devices AUDIO_DEVICE_OUT_SPEAKER
                |  default_output_device AUDIO_DEVICE_OUT_SPEAKER
                |  attached_input_devices AUDIO_DEVICE_IN_BUILTIN_MIC
                |}
                |
                |audio_hw_modules {
                |  primary {
                |    outputs {
                |      primary {
                |        sampling_rates 48000
                |        channel_masks AUDIO_CHANNEL_OUT_STEREO
                |        formats AUDIO_FORMAT_PCM_16_BIT
                |        devices AUDIO_DEVICE_OUT_SPEAKER
                |        flags AUDIO_OUTPUT_FLAG_PRIMARY
                |      }
                |    }
                |    inputs {
                |      primary {
                |        sampling_rates 8000|16000|44100|48000
                |        channel_masks AUDIO_CHANNEL_IN_MONO|AUDIO_CHANNEL_IN_STEREO
                |        formats AUDIO_FORMAT_PCM_16_BIT
                |        devices AUDIO_DEVICE_IN_BUILTIN_MIC
                |      }
                |    }
                |  }
                |}
                |""".trimMargin()
            if (cur.takeIf { it.isFile }?.readText() != text) cur.writeText(text)
        }.onFailure { log("audio: failed to write audio_policy.conf: ${it.message}") }
        
        
        
        val xml = File(root, "vendor/etc/audio_policy_configuration.xml")
        if (img.engine == Engine.N64 && kernel.audioPolicyXml && File(root, "system/etc/audio_policy_configuration.xml").isFile && File(root, "vendor/etc").isDirectory) runCatching {
            val own = File(root, "system/.aemu-parked/vendor#etc#audio_policy_configuration.xml")
            if (xml.isFile && !own.exists() && !xml.readText().contains("AEmulator")) { own.parentFile?.mkdirs(); xml.renameTo(own) }
            val text = """
                |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                |<!-- AEmulator: the one module is the engine's audio HAL -->
                |<audioPolicyConfiguration version="1.0" xmlns:xi="http://www.w3.org/2001/XInclude">
                |    <globalConfiguration speaker_drc_enabled="false"/>
                |    <modules>
                |        <module name="primary" halVersion="2.0">
                |            <attachedDevices>
                |                <item>Speaker</item>
                |                <item>Built-In Mic</item>
                |            </attachedDevices>
                |            <defaultOutputDevice>Speaker</defaultOutputDevice>
                |            <mixPorts>
                |                <mixPort name="primary output" role="source" flags="AUDIO_OUTPUT_FLAG_PRIMARY">
                |                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                |                </mixPort>
                |                <mixPort name="primary input" role="sink">
                |                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="8000,16000,44100,48000" channelMasks="AUDIO_CHANNEL_IN_MONO,AUDIO_CHANNEL_IN_STEREO"/>
                |                </mixPort>
                |            </mixPorts>
                |            <devicePorts>
                |                <devicePort tagName="Speaker" type="AUDIO_DEVICE_OUT_SPEAKER" role="sink"/>
                |                <devicePort tagName="Built-In Mic" type="AUDIO_DEVICE_IN_BUILTIN_MIC" role="source"/>
                |            </devicePorts>
                |            <routes>
                |                <route type="mix" sink="Speaker" sources="primary output"/>
                |                <route type="mix" sink="primary input" sources="Built-In Mic"/>
                |            </routes>
                |        </module>
                |    </modules>
                |    <xi:include href="/system/etc/audio_policy_volumes_drc.xml"/>
                |    <xi:include href="/system/etc/default_volume_tables.xml"/>
                |</audioPolicyConfiguration>
                |""".trimMargin()
            if (xml.takeIf { it.isFile }?.readText() != text) { xml.writeText(text); xml.setReadable(true, false) }
        }.onFailure { log("audio: failed to write audio_policy_configuration.xml: ${it.message}") }
        
        
        
        val fx = File(root, "vendor/etc/audio_effects.conf")
        if (img.engine != Engine.N64 && fx.isFile && File(root, "system/etc/audio_effects.conf").isFile) runCatching {
            val own = File(root, "system/.aemu-parked/vendor#etc#audio_effects.conf")
            own.parentFile?.mkdirs()
            if (fx.renameTo(own)) log("audio: vendor audio_effects.conf parked, AOSP effects used")
        }
    }

    
    private fun makeFb() {
        val s = img.settings
        val (w, h) = if (engine == Engine.GB) 480 to 800 else s.width to s.height
        
        
        val need = w.toLong() * h * 4 * 2
        val f = paths.fb
        
        
        val geom = "$w $h\n"
        val geomFile = File(root, "dhd.fbgeom")
        val geomOk = runCatching { geomFile.readText() == geom }.getOrDefault(false)
        if (f.isFile && f.length() == need && geomOk) return
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(need) }
        runCatching { geomFile.writeText(geom) }
        log("framebuffer: ${w}x$h")
    }

    
    fun seedOwners() {
        val stamp = File(root, "dhd.owners.seeded")
        val done = if (stamp.isFile) stamp.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        val pkgXml = File(root, "data/system/packages.xml")
        val dataDir = File(root, "data/data")
        if (!pkgXml.isFile || !dataDir.isDirectory) return
        runCatching {
            val text = pkgXml.readText()
            val sharedUsers = HashMap<String, Int>()
            val suRe = Regex("<shared-user name=\"([^\"]+)\"[^>]*?\\suserId=\"(\\d+)\"")
            for (m in suRe.findAll(text)) {
                sharedUsers[m.groupValues[1]] = m.groupValues[2].toInt()
            }
            val sb = StringBuilder()
            var rows = 0
            var pkgs = 0
            val pkgRe = Regex("<package name=\"([^\"]+)\"[^>]*?>")
            for (m in pkgRe.findAll(text)) {
                val tag = m.value
                val name = m.groupValues[1]
                val uid = Regex("\\suserId=\"(\\d+)\"").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\ssharedUserId=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)?.let { sharedUsers[it] }
                    ?: continue
                val d = File(dataDir, name)
                if (!d.isDirectory) continue
                val key = "$name $uid"
                if (key in done) continue
                done.add(key)
                pkgs++
                val todo = ArrayDeque<File>().apply { add(d) }
                while (todo.isNotEmpty()) {
                    val f = todo.removeFirst()
                    runCatching {
                        f.setReadable(true, false)
                        f.setWritable(true, false)
                        if (f.isDirectory) f.setExecutable(true, false)
                    }
                    val st = runCatching { Os.lstat(f.absolutePath) }.getOrNull() ?: continue
                    sb.append(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, uid, uid))
                    rows++
                    if (!OsConstants.S_ISLNK(st.st_mode) && f.isDirectory) f.listFiles()?.forEach { todo.add(it) }
                }
            }
            if (rows > 0) {
                paths.owners.appendText(sb.toString())
                log("file owners restored: $pkgs packages, $rows entries")
            }
            stamp.writeText(done.joinToString("\n", postfix = "\n"))
        }.onFailure { log("owners: ${it.message}") }
    }

    
    fun skipPreBoot(props: PropService) {
        if (img.api < 14) return
        val f = File(root, "data/system/called_pre_boots.dat")
        val rel = props.get("ro.build.version.release") ?: img.release
        val code = props.get("ro.build.version.codename") ?: "REL"
        val incr = props.get("ro.build.version.incremental") ?: ""
        val fresh = runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                i.readInt() == 10000 && i.readUTF() == rel && i.readUTF() == code && i.readUTF() == incr
            }
        }.getOrDefault(false)
        if (fresh) return
        runCatching {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(10000); o.writeUTF(rel); o.writeUTF(code); o.writeUTF(incr); o.writeInt(0)
            }
        }
    }

    
    private fun hasSonySetupFlow(): Boolean =
        File(root, "system/app/Initial-boot-setup.apk").isFile && File(root, "system/priv-app/SEMCSetupWizard.apk").isFile

    
    fun restoreSonySetupFlow() {
        if (!hasSonySetupFlow()) return
        val marker = File(paths.bin, "sony-setup-flow-v1")
        if (marker.isFile) return
        val db = File(root, "data/data/com.android.providers.settings/databases/settings.db")
        val restrictions = File(root, "data/system/users/0/package-restrictions.xml")
        if (!db.isFile || !restrictions.isFile) return 
        val prefs = File(root, "data/data/${SonySetupPolicy.PACKAGE}/shared_prefs/SetupWizard.xml")
        runCatching {
            val completed = prefs.isFile && SonySetupPolicy.completed(prefs.readText())
            if (!completed) {
                val xml = restrictions.readText()
                val restored = SonySetupPolicy.restorePackage(xml)
                
                if (xml != restored) {
                    for (suffix in listOf("", "-wal", "-shm")) {
                        val source = File(db.path + suffix)
                        val backup = File(source.path + ".aemu-before-sony-setup-v1")
                        if (source.isFile && !backup.exists()) source.copyTo(backup)
                    }
                    val backup = File(restrictions.path + ".aemu-before-sony-setup-v1")
                    if (!backup.exists()) restrictions.copyTo(backup)
                    SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                        d.beginTransaction()
                        try {
                            for ((table, name) in listOf("global" to "device_provisioned", "secure" to "user_setup_complete")) {
                                val cv = android.content.ContentValues().apply { put("value", "0") }
                                d.update(table, cv, "name=?", arrayOf(name))
                            }
                            d.setTransactionSuccessful()
                        } finally { d.endTransaction() }
                    }
                    restrictions.writeText(restored)
                    log("setup: restored Sony wizard and incomplete-setup flags; backups retained")
                }
            }
            marker.parentFile?.mkdirs()
            marker.writeText("Sony owns setup completion; migration checked\n")
        }.onFailure { log("setup: Sony flow migration failed: ${it.message}") }
    }

    
    private fun settingsDb(): File? = listOf(
        "data/data/com.android.providers.settings/databases/settings.db",
        "dbdata/databases/com.android.providers.settings/settings.db",
    ).map { File(root, it) }.firstOrNull { it.isFile }

    
    fun provisionBeforeBoot() {
        if (img.api < 16) return
        if (!File(root, "data/system/aemu-setup-skip.properties").isFile) return
        val db = settingsDb() ?: return
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                fun put(table: String, name: String, value: String) {
                    val exists = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.count > 0 }
                    if (!exists) return
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update(table, cv, "name=?", arrayOf(name)) == 0) d.insert(table, null, cv)
                }
                if (img.api >= 17) put("global", "device_provisioned", "1")
                put("secure", "device_provisioned", "1")
                put("secure", "user_setup_complete", "1")
            }
            log("setup: device marked provisioned before boot (setup wizard skipped)")
        }.onFailure { log("setup: pre-boot provisioning failed: ${it.message}") }
    }

    
    fun lockWearRotation() {
        if (!img.skin.equals("Wear", ignoreCase = true)) return
        val db = settingsDb() ?: return
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                val hasTable = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='system'", null).use { it.count > 0 }
                if (!hasTable) return@use
                fun put(name: String, value: String) {
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update("system", cv, "name=?", arrayOf(name)) == 0) d.insert("system", null, cv)
                }
                put("accelerometer_rotation", "0")
                put("user_rotation", "0")
            }
            log("wear: screen rotation locked to 0° (avoids the rotation-animation screenshot that can hang system_server)")
        }.onFailure { log("wear: could not lock the rotation: ${it.message}") }
    }

    fun noScreenSleep() {
val db = settingsDb()
        if (db == null) {
            
            if (mPlus && kernel.xmlSettings) { xmlSettings(); disableLegacyGoogleLogin() }
            return
        }
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                fun put(table: String, name: String, value: String) {
                    val exists = runCatching {
                        d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.count > 0 }
                    }.getOrDefault(false)
                    if (!exists) return
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update(table, cv, "name=?", arrayOf(name)) == 0) d.insert(table, null, cv)
                }
                put("system", "screen_off_timeout", "2147483647")
                val t = if (img.api >= 17) "global" else "system"
                put(t, "stay_on_while_plugged_in", "7")
                put(if (img.api >= 17) "global" else "secure", "mobile_data", "1")
                put(if (img.api >= 17) "global" else "secure", "package_verifier_enable", "0")
                put(if (img.api >= 17) "global" else "secure", "install_non_market_apps", "1")
                put("secure", "install_non_market_apps", "1")
                if (img.api >= 17) {
                    put("global", "verifier_verify_adb_installs", "0")
                    put("global", "upload_apk_enable", "0")
                    put("global", "package_verifier_include_adb", "0")
                }
                put("secure", "package_verifier_user_consent", "-1")
                
                
                
                if (File(root, "dbdata").isDirectory) put("secure", "sys_storage_threshold_percentage", "1")
                
                if (img.api < 16 && !hasSonySetupFlow()) {
                    put("secure", "device_provisioned", "1")
                    put("secure", "user_setup_complete", "1")
                }
            }
        }.onFailure { log("settings: database failed to open: ${it.message}") }
        disableLegacyGoogleLogin()
    }

    
    
    private fun disableStallingPackages() = runCatching {
        if (img.api >= 16) { log("boot stall guard: skipped (api ${img.api})"); return@runCatching }
        val pkg = "com.google.android.backup"
        val packagesXml = File(root, "data/system/packages.xml")
        val declared = if (packagesXml.isFile) Regex("<package name=\"${Regex.escape(pkg)}\"[^>]*?codePath=\"([^\"]+)\"").find(packagesXml.readText())?.groupValues?.get(1) else null
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        val candidates = LinkedHashSet<File>()
        if (declared != null) candidates += File(root, declared.trimStart('/'))
        for (dir in listOf("system/app", "system/priv-app", "system/vendor/app", "vendor/app"))
            File(root, dir).listFiles()?.filter { it.isFile && it.name.endsWith(".apk") && it.name.contains("Backup", ignoreCase = true) && it.name.contains("Google", ignoreCase = true) }?.let { candidates += it }
        log("boot stall guard: ${pkg} codePath=${declared ?: "unknown (no packages.xml entry)"}, candidates=${candidates.joinToString { it.name }}")
        var moved = 0
        for (apk in candidates) {
            if (!apk.isFile) continue
            for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                if (!f.isFile) continue
                val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) { moved++; log("boot stall guard: parked ${f.name}") } else log("boot stall guard: could not park ${f.name}")
            }
        }
        
        val restrictions = File(root, "data/system/users/0/package-restrictions.xml")
        if (restrictions.isFile && packagesXml.isFile && packagesXml.readText().contains("<package name=\"$pkg\"")) {
            var xml = restrictions.readText()
            val m = Regex("<pkg name=\"${Regex.escape(pkg)}\"([^>]*?)(/?)>").find(xml)
            if (m == null) {
                xml = xml.replace("</package-restrictions>", "    <pkg name=\"$pkg\" enabled=\"2\" />\n</package-restrictions>")
                restrictions.writeText(xml); log("boot stall guard: $pkg marked disabled")
            } else if (!m.groupValues[1].contains("enabled=\"2\"")) {
                val attrs = m.groupValues[1].replace(Regex(" enabled=\"\\d\""), "")
                xml = xml.replaceRange(m.range, "<pkg name=\"$pkg\"$attrs enabled=\"2\"${m.groupValues[2]}>")
                restrictions.writeText(xml); log("boot stall guard: $pkg marked disabled")
            }
        } else log("boot stall guard: no restrictions entry changed (restrictions=${restrictions.isFile}, packages.xml=${packagesXml.isFile})")
        if (moved == 0 && declared == null) log("boot stall guard: $pkg not found in this tree, nothing to do")
    }.onFailure { log("boot stall guard failed: ${it.message}") }

    
    private fun glassLocationProvider() = runCatching {
        if (img.api !in 9..16) return@runCatching
        val glass = GlassLocationPolicy.isGlass(img.name, img.model,
            File(root, "system/app/GlassBluetooth.apk").isFile || File(root, "system/.aemu-parked/system#app#GlassBluetooth.apk").isFile)
        if (!glass) return@runCatching
        val f = File(root, "data/system/packages.xml")
        if (!f.isFile) return@runCatching
        GlassLocationPolicy.reenable(f.readText())?.let {
            f.writeText(it)
            log("glass: network location service enabled again (settings needs the network provider)")
        }
    }.onFailure { log("glass: could not re-enable network location: ${it.message}") }

    
    fun seedDefaultIme(): Boolean {
        if (img.api < 19) return false
        val installed = listOf("system/priv-app/GlassRemoteIme.apk", "system/app/GlassRemoteIme.apk").any { File(root, it).isFile }
        if (!installed) return false
        val db = File(root, "data/data/com.android.providers.settings/databases/settings.db")
        if (!db.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                val hasTable = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='secure'", null).use { it.count > 0 }
                if (!hasTable) return@use false
                fun get(name: String): String? = d.rawQuery("SELECT value FROM secure WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
                fun put(name: String, value: String) {
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update("secure", cv, "name=?", arrayOf(name)) == 0) d.insert("secure", null, cv)
                }
                val v = GlassImePolicy.valueToSeed(get("default_input_method"), true) ?: return@use false
                put("default_input_method", v)
                put("enabled_input_methods", GlassImePolicy.enabledWith(get("enabled_input_methods"), v))
                log("glass: default input method set to $v (InputMethodManagerService no longer fails on first boot)")
                true
            }
        }.onFailure { log("glass: could not set the default input method: ${it.message}") }.getOrDefault(false)
    }

    
    private fun xmlSettings() {
        val values = listOf(
            Triple("system", "screen_off_timeout", "2147483647"),
            Triple("global", "stay_on_while_plugged_in", "7"),
            Triple("global", "package_verifier_enable", "0"),
            Triple("global", "verifier_verify_adb_installs", "0"),
            Triple("global", "upload_apk_enable", "0"),
            Triple("global", "package_verifier_include_adb", "0"),
            Triple("secure", "package_verifier_user_consent", "-1"),
        ) + (if (img.settings.skipSetupWizard) listOf(
            Triple("global", "device_provisioned", "1"),
            Triple("secure", "user_setup_complete", "1"),
        ) else emptyList()) + listOf(
            Triple("secure", "install_non_market_apps", "1"),
        )
        for ((table, list) in values.groupBy { it.first }) {
            val f = File(root, "data/system/users/0/settings_$table.xml")
            var xml = runCatching { f.readText() }.getOrNull() ?: continue
            if (!xml.contains("</settings>")) continue
            val before = xml
            for ((_, name, value) in list) {
                val m = Regex("(<setting id=\"[0-9]+\" name=\"${Regex.escape(name)}\" value=\")[^\"]*\"").find(xml)
                if (m != null) { xml = xml.replaceRange(m.range, m.groupValues[1] + value + "\""); continue }
                val id = (Regex("<setting id=\"([0-9]+)\"").findAll(xml).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1
                xml = xml.replace("</settings>", "  <setting id=\"$id\" name=\"$name\" value=\"$value\" package=\"android\" />\n</settings>")
            }
            if (xml != before) runCatching { f.writeText(xml) }.onFailure { log("settings_$table.xml: ${it.message}") }
        }
    }

    private fun disableLegacyGoogleLogin() {
        val f = File(root, "data/system/users/0/package-restrictions.xml")
        if (!f.isFile) return
        
        val pkgs = runCatching { File(root, "data/system/packages.xml").readText() }.getOrNull() ?: return
        val legacy = if (img.api < 16) listOf("com.sec.android.app.SecSetupWizard",
            "com.google.android.setupwizard", "com.android.provision", "com.miui.provision",
            "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard") else emptyList()
        val targets = (legacy + "com.google.android.gsf.login").filter {
            pkgs.contains("<package name=\"$it\"") && !(hasSonySetupFlow() && it == SonySetupPolicy.PACKAGE)
        }
        if (targets.isEmpty()) return
        var xml = runCatching { f.readText() }.getOrNull() ?: return
        var changed = false
        for (p in targets) {
            val open = Regex("<pkg name=\"${Regex.escape(p)}\"([^>]*?)(/?)>")
            val m = open.find(xml)
            if (m == null) {
                xml = xml.replace("</package-restrictions>", "    <pkg name=\"$p\" enabled=\"2\" />\n</package-restrictions>")
                changed = true
            } else if (!m.groupValues[1].contains("enabled=\"2\"")) {
                val attrs = m.groupValues[1].replace(Regex(" enabled=\"\\d\""), "")
                xml = xml.replaceRange(m.range, "<pkg name=\"$p\"$attrs enabled=\"2\"${m.groupValues[2]}>")
                changed = true
            }
        }
        if (changed) runCatching { f.writeText(xml); log("legacy Google login disabled: ${targets.joinToString()}") }
    }

    
    private fun linksBelow64K(f: File): Boolean = runCatching {
        if (!f.isFile || isLink(f)) return@runCatching false
        val h = f.inputStream().use { i -> ByteArray(4096).also { n -> i.read(n) } }
        val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[4] != 1.toByte() || b.getShort(16).toInt() != 2) return@runCatching false
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        (0 until phnum).any { k ->
            val o = phoff + k * phentsize
            o + 12 <= h.size && b.getInt(o) == 1 && (b.getInt(o + 8).toLong() and 0xffffffffL) < 0x10000L
        }
    }.getOrDefault(false)

    
    private fun healthdRelativeSysfs(base: File) {
        if (img.api < 21) return
        runCatching {
            val link = File(paths.bin, "sys")
            paths.bin.mkdirs()
            if (!isLink(link)) { wipe(link); Os.symlink(File(root, "sys").absolutePath, link.path) }
            val hd = File(root, "sbin/healthd").takeIf { it.isFile } ?: return
            val d = hd.readBytes()
            val from = "/sys/class/power_supply".toByteArray()
            val to = "sys/class/power_supply".toByteArray() + 0
            var n = 0
            var i = 0
            while (i <= d.size - from.size) {
                if (d[i] == from[0] && (0 until from.size).all { d[i + it] == from[it] }) { System.arraycopy(to, 0, d, i, to.size); n++; i += from.size } else i++
            }
            if (n > 0) { hd.writeBytes(d); log("healthd: battery sysfs path made relative ($n)") }
        }.onFailure { log("healthd patch failed: ${it.message}") }
        if (!base.isDirectory) log("no fake power_supply")
    }

    
    private fun personalityNoop() {
        
        
        if (!mPlus) return
        val stamp = File(root, ".aemu-personality")
        if (stamp.isFile && runCatching { stamp.readText().trim().toInt() }.getOrDefault(0) > 0) return
        val files = listOf(File(root, "system/lib/libc.so")) +
            listOf("sbin", "system/bin", "system/xbin").flatMap { File(root, it).listFiles().orEmpty().toList() }
        var n = 0
        for (f in files) {
            if (!f.isFile || isLink(f) || f.length() > 8_000_000 || f.length() < 1024) continue
            runCatching {
                val d = f.readBytes()
                if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte()) return@runCatching
                val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var hit = false
                var i = 0
                while (i + 12 <= d.size) {
                    
                    if (b.getInt(i) == 0xe1a0c007.toInt() && b.getInt(i + 8) == 0xef000000.toInt()) {
                        val w = b.getInt(i + 4)
                        val nr = when {
                            w == 0xe3a07088.toInt() -> 136
                            w and 0xfffff000.toInt() == 0xe59f7000.toInt() ->
                                (i + 4 + 8 + (w and 0xfff)).takeIf { it + 4 <= d.size }?.let { b.getInt(it) } ?: -1
                            else -> -1
                        }
                        if (nr == 136) { b.putInt(i + 8, 0xe3a00000.toInt()); hit = true }   
                    }
                    i += 4
                }
                if (hit) { f.writeBytes(d); n++ }
            }
        }
        log("personality(PER_LINUX32) made a no-op in $n files")
        runCatching { stamp.writeText("$n\n") }
    }

    
    fun ensureHostPathAlias() = hostPathAlias()

    private var aliasMade = false

    private fun hostPathAlias() {
        if (img.api < 24) return
        runCatching {
            val hostRoot = root.absoluteFile.toPath().normalize()
            val rel = hostRoot.root.relativize(hostRoot)           
            if (rel.nameCount < 2) return
            val alias = File(root, rel.toString())
            val target = hostRoot.toString()
            alias.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val cur = runCatching { Os.readlink(alias.path) }.getOrNull()
            if (cur == target) return
            if (cur != null || alias.exists()) wipe(alias)
            Os.symlink(target, alias.path)
            log(if (aliasMade) "linker: host path of the guest root alias was removed by the guest, restored" else "linker: host path of the guest root aliased inside it")
            aliasMade = true
        }.onFailure { log("host path alias failed: ${it.message}") }
    }

    
    
    private fun parkNfc() {
        
        
        
        
        if (mPlus) runCatching {
            val link = File(root, "system/framework/aemu-owners.jar")
            if (!isLink(link)) { link.delete(); Os.symlink(paths.owners.absolutePath, link.path) }
        }
        if (img.api < 21) {
            
            if (img.api >= 19) { restoreNfc(); nfcOffByDefault() }
            return
        }
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        for (dir in listOf("system/app", "system/priv-app")) File(root, dir).listFiles()?.filter { it.name.startsWith("Nfc") }?.forEach { f ->
            val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
            if (f.renameTo(dst)) log("parked ${f.name}")
        }
    }

    
    private fun restoreNfc() = runCatching {
        val parked = File(root, "system/.aemu-parked")
        parked.listFiles()?.filter {
            it.name.startsWith("system#app#Nfc") || it.name.startsWith("system#priv-app#Nfc") ||
                it.name.startsWith("system#etc#permissions#android.hardware.nfc")
        }?.forEach { f ->
            val dst = File(root, f.name.replace('#', '/'))
            if (!dst.exists() && f.renameTo(dst)) log("restored ${dst.name}")
        }
    }.onFailure { log("nfc restore failed: ${it.message}") }

    
    private fun nfcOffByDefault() = runCatching {
        val pkgDir = File(root, "data/data/com.android.nfc")
        val prefsDir = File(pkgDir, "shared_prefs")
        val prefs = File(prefsDir, "NfcServicePrefs.xml")
        val fresh = mutableListOf<File>()
        for (d in listOf(pkgDir, prefsDir)) if (!d.isDirectory) { d.mkdirs(); d.setReadable(true, false); d.setWritable(true, false); d.setExecutable(true, false); fresh += d }
        val old = if (prefs.isFile) prefs.readText() else ""
        val flag = Regex("<boolean name=\"nfc_on\" value=\"[a-z]+\"\\s*/>")
        val text = when {
            old.isBlank() || !old.contains("<map") ->
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"nfc_on\" value=\"false\" />\n    <boolean name=\"first_boot\" value=\"false\" />\n</map>\n"
            flag.containsMatchIn(old) -> old.replace(flag, "<boolean name=\"nfc_on\" value=\"false\" />")
            old.contains("<map />") -> old.replace("<map />", "<map>\n    <boolean name=\"nfc_on\" value=\"false\" />\n</map>")
            else -> old.replace("</map>", "    <boolean name=\"nfc_on\" value=\"false\" />\n</map>")
        }
        if (text != old) {
            if (!prefs.isFile) fresh += prefs
            prefs.writeText(text)
            prefs.setReadable(true, false); prefs.setWritable(true, false)
            log("nfc: NfcService preference nfc_on forced off (no NFC hardware)")
        }
        if (fresh.isNotEmpty()) {
            
            val sb = StringBuilder()
            for (f in fresh) {
                val st = Os.lstat(f.absolutePath)
                sb.append(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, 1027, 1027))
            }
            paths.owners.appendText(sb.toString())
        }
    }.onFailure { log("nfc: could not switch NFC off: ${it.message}") }

    private fun mainStackMaps() {
        if (img.api < 21) return
        val libc = File(root, "system/lib/libc.so")
        runCatching {
            val d = libc.readBytes()
            val from = "/proc/self/task/%d/maps".toByteArray()
            val i = indexOf(d, from)
            if (i < 0) return
            val to = "/proc/self/maps".toByteArray()
            for (k in from.indices) d[i + k] = if (k < to.size) to[k] else 0
            libc.writeBytes(d)
            log("libc: main thread stack read from /proc/self/maps")
        }.onFailure { log("libc maps patch failed: ${it.message}") }
    }

    private fun indexOf(d: ByteArray, p: ByteArray, from: Int = 0): Int {
        var i = from
        while (i <= d.size - p.size) {
            if (d[i] == p[0] && (1 until p.size).all { d[i + it] == p[it] }) return i
            i++
        }
        return -1
    }

    private fun isLink(f: File) = runCatching { OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode) }.getOrDefault(false)
    private fun wipe(f: File) = ImageStore.wipe(f)

    companion object {
        
        val TEE_KEYMINT_SERVICE = Regex("^android\\.hardware\\.security\\.keymint(@[0-9.]+-service(\\..*)?|-service([.-].+)?)$")

        
        val BOARD_AIDL_AUDIO_SERVICE = Regex("^(android\\.hardware\\.audio\\.service-aidl(\\..+)?|audio_input_service)$")

        
        val BOARD_POWER_SERVICE = Regex("^(vendor\\.mediatek\\.hardware\\.mtkpower(_applist)?-service(\\..*)?|android\\.hardware\\.power-service([.-]mediatek)?|android\\.hardware\\.power@1\\.0-service\\..+)$")

        
        val BOARD_COMPOSER_SERVICE = Regex("^(android\\.hardware\\.graphics\\.composer@(2\\.[2-9]|3\\.[0-9])-service(\\..*)?|android\\.hardware\\.graphics\\.composer3-service(\\..*)?|vendor\\.qti\\.hardware\\.display\\.composer-service)$")

        
        fun usesGememalloc(elf: ByteArray?): Boolean =
            elf != null && String(elf, Charsets.ISO_8859_1).contains("/dev/gememalloc")

        
        private const val GPIO_STAND_IN_PINS = 512
        
        private const val MTK_HAL_MODULE_ID = "libaudio"

        private const val OPEN_GL = "engines/kk/libGLES_aemu.so"
        private const val HWC_FB = "engines/kk/hwcomposer.default.so"
        
        val SETUP_WIZARDS = listOf(
            "com.sec.android.app.SecSetupWizard", "com.google.android.setupwizard", "com.android.provision",
            "com.miui.provision", "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard",
            
            
            "com.google.android.gsf.login",
            
            
            "com.samsung.android.sconnect", "com.sec.android.sviewcover",
            
            "com.samsung.android.beaconmanager",
        )
        
        val FIRST_RUN_WIZARDS = listOf("com.sec.android.app.SecSetupWizard", "com.google.android.setupwizard", "com.miui.provision",
            "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard")
        
        fun isMtkAudio(root: File): Boolean {
            if (File(root, "system/lib/libaudio.mtk.so").isFile) return true   
            if (isMtkHwOnlyAudio(root)) return true
            val f = File(root, "system/lib/libaudio.primary.default.so")
            if (!f.isFile || f.length() > 20_000_000) return false
            return runCatching {
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                
                
                text.contains("AudioMTK") || text.contains("AudioALSAHardware") ||
                    File(root, "system/lib/libaudioflinger.so").let { af ->
                        af.isFile && af.length() < 20_000_000 &&
                            String(af.readBytes(), Charsets.ISO_8859_1).contains("libaudio.primary.default.so")
                    }
            }.getOrDefault(false)
        }

        
        fun isMtkPlatform(root: File): Boolean = isMtkAudio(root) || mtkTraces(root)

        
        fun mtkTraces(root: File): Boolean {
            if (listOf("nvram_daemon", "ccci_mdinit", "muxreport", "gsm0710muxd").any { File(root, "system/bin/$it").isFile }) return true
            return runCatching {
                File(root, "system/build.prop").readText()
                    .contains(Regex("(?m)^(ro\\.mediatek\\.[^=\\s]*=|ro\\.board\\.platform=mt|ro\\.hardware=mt)"))
            }.getOrDefault(false)
        }

        
        fun isMtkHwOnlyAudio(root: File): Boolean {
            if (File(root, "system/lib/libaudio.primary.default.so").isFile || File(root, "system/lib/libaudio.mtk.so").isFile) return false
            if (!File(root, "system/lib/hw/audio.primary.default.so").isFile) return false
            val sdk = runCatching {
                Regex("(?m)^ro\\.build\\.version\\.sdk=(\\d+)").find(File(root, "system/build.prop").readText())?.groupValues?.get(1)?.toInt()
            }.getOrNull() ?: return false
            return sdk in 17..20 && mtkTraces(root)
        }

        
        private const val IDME_SHIFT_NODE = "/sys/class/mmc_host/mmc0/mmc0:0001/shift"
        private val IDME_LIBS = listOf("system/lib/libidme.so", "vendor/lib/libidme.so", "system/vendor/lib/libidme.so")
        private val SYS_PATH = Regex("/sys/[A-Za-z0-9_.:+-]+(?:/[A-Za-z0-9_.:+-]+)*")

        
        internal fun idmeSysPaths(bytes: ByteArray): List<String> {
            val text = String(bytes, Charsets.ISO_8859_1)
            val out = LinkedHashSet<String>()
            for (m in SYS_PATH.findAll(text)) {
                val next = text.getOrNull(m.range.last + 1)
                if (next == '%' || next == '/') continue
                val path = m.value.trimEnd('.')
                if (path.split('/').contains("..")) continue
                if (path.removePrefix("/sys/").split('/').size < 3) continue
                out += path
            }
            return out.toList()
        }

        
        internal fun idmeNodesToCreate(paths: Collection<String>): List<String> {
            val all = paths.filter { it.startsWith("/sys/") && !it.endsWith("/") }.distinct()
            return all.filter { p -> all.none { it.startsWith("$p/") } }
        }

        
        internal fun makeTraversable(dir: File) {
            if (!dir.isDirectory) return
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }

        private val DBDATA_DIRS = listOf("dbdata", "dbdata/databases", "dbdata/system")

        
        internal fun dbdataDirs(rcTexts: List<String>, initDirs: List<String>, legacySamsung: Boolean = false, alreadyThere: Boolean = false): List<String> =
            
            if (legacySamsung || alreadyThere || initDirs.any { it.contains("dbdata") } || rcTexts.any { it.contains("/dbdata") }) DBDATA_DIRS else emptyList()

        val QCOM_PARK = listOf(
            "system/etc/permissions/qti_permissions.xml",
            "system/etc/permissions/com.qti.dpmframework.xml",
            "system/priv-app/com.qualcomm.location",
            "system/priv-app/qcrilmsgtunnel",
            "system/priv-app/dpmserviceapp",
            "system/priv-app/DiracAudioControlService",
            "system/app/DiracManager",
        )
        private val LOGS = listOf("main", "system", "radio") 
        private val DATA_DIRS = listOf(
            "data", "data/app", "data/app-private", "data/app-lib", "data/app-asec", "data/data", "data/dalvik-cache",
            "data/local", "data/local/tmp", "data/misc", "data/misc/keystore", "data/misc/wifi", "data/misc/zoneinfo",
            "data/property", "data/system", "data/anr", "data/backup", "data/lost+found", "data/drm", "data/media",
            "data/resource-cache", "data/security", "data/tombstones", "cache", "cache/download", "cache/lost+found",
            
            
            "cache/dalvik-cache",
            
            "data/misc/user", "data/misc/systemkeys", "data/misc/vpn", "data/misc/net", "data/misc/shared_relro", "data/misc/media", "data/misc/gatekeeper",
            "mnt", "mnt/asec", "mnt/obb", "mnt/secure", "mnt/secure/asec", "mnt/shell", "mnt/media_rw", "storage",
        )
    }
}


private const val HIDL_BASE_STUB = "f0VMRgIBAQAAAAAAAAAAAAMAtwABAAAAAAAAAAAAAABAAAAAAAAAANABAAAAAAAAAAAAAEAAOAADAEAABgAFAAEAAAAGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAEAAAAAAACgAQAAAAAAAAAQAAAAAAAAAgAAAAYAAAAwAQAAAAAAADABAAAAAAAAMAEAAAAAAABwAAAAAAAAAHAAAAAAAAAACAAAAAAAAABR5XRkBgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAEAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYW5kcm9pZC5oaWRsLmJhc2VAMS4wLnNvAAAAAAAAAAQAAAAAAAAA6AAAAAAAAAAFAAAAAAAAABABAAAAAAAABgAAAAAAAAD4AAAAAAAAAAoAAAAAAAAAGgAAAAAAAAALAAAAAAAAABgAAAAAAAAADgAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAALmhhc2gALmR5bnN5bQAuZHluc3RyAC5keW5hbWljAC5zaHN0cnRhYgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAUAAAACAAAAAAAAAOgAAAAAAAAA6AAAAAAAAAAQAAAAAAAAAAIAAAAAAAAACAAAAAAAAAAEAAAAAAAAAAcAAAALAAAAAgAAAAAAAAD4AAAAAAAAAPgAAAAAAAAAGAAAAAAAAAADAAAAAQAAAAgAAAAAAAAAGAAAAAAAAAAPAAAAAwAAAAIAAAAAAAAAEAEAAAAAAAAQAQAAAAAAABoAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAFwAAAAYAAAADAAAAAAAAADABAAAAAAAAMAEAAAAAAABwAAAAAAAAAAMAAAAAAAAACAAAAAAAAAAQAAAAAAAAACAAAAADAAAAAAAAAAAAAAAAAAAAAAAAAKABAAAAAAAAKgAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAA="
