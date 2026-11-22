
package app.aemu.core

import app.aemu.R
import android.content.Context
import android.os.Process as AProcess
import android.os.SystemClock
import android.view.Surface
import dev.lk.dhd.Slot
import dev.lk.m7sense.GlBridge
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList


private const val MIN_JAVA_HEAP_MB = 256

private const val FOREIGN_MAX_JAVA_HEAP_MB = 320


private const val PURE32_GPU_MODE = "3"


private fun pure32HwcShowsGpuFrames(root: File): Boolean =
    listOf("vendor/lib/hw", "system/vendor/lib/hw").any { d ->
        val f = File(root, "$d/hwcomposer.default.so")
        runCatching { f.isFile && String(f.readBytes(), Charsets.ISO_8859_1).contains("debug.aemu.gl") }.getOrDefault(false)
    }


private val ALWAYS_RESTART = setOf("mediaserver", "installd", "netd", "keystore")


private val N64_SKIP = setOf("audiod", "ATFWD-daemon", "imsqmidaemon", "loc_launcher", "cnd", "dpmd", "cnss-daemon",
    "tftp_server", "qcom-system-daemon", "hvdcp_opti", "pm-service", "mtd", "mlipayd", "vsimd", "fdpp", "perfd", "ipacm",
    "qti", "netmgrd", "port-bridge", "ims_rtp_daemon", "imsdatadaemon", "wcnss_service", "msm_irqbalance", "energy-awareness", "sscrpcd", "adsprpcd", "cdsprpcd", "audioadsprpcd")


class GuestVm(val ctx: Context, val img: GuestImage, val lowPowerBoot: Boolean = false) {
    enum class State { STOPPED, PREPARING, BOOTING, RUNNING, FAILED, STOPPING }

    val paths = VmPaths(ctx, img.id)
    
    val slot = VmSlots.current()
    val kernel: Kernel64 get() = Kernel64.forApi(img.api)
    
    val settings = img.settings.let { s ->
        
        
        
        
        
        val marker = File(paths.dir, "jit-x86-default")
        if ((img.arch == GuestArch.X86 || img.arch == GuestArch.X86_64) && !marker.exists()) {
            runCatching { marker.writeText("1"); if (s.jit) ImageStore.update(ctx, img.id) { it.copy(settings = it.settings.copy(jit = false)) } }
            s.copy(jit = false)
        } else s
    }.let { s ->
        
        
        if (img.arch != GuestArch.ARM && s.gpu &&
            !(if (img.engine == Engine.N64) img.arch == GuestArch.X86_64 && EngineAssets.exists(ctx, EngineAssets.X86_64_GPU_BRIDGE)
              else EngineAssets.exists(ctx, "engines/${Engine.KK.id}${img.arch.assetSuffix}/libGLES.so"))) s.copy(gpu = false, hwui = false) else s
    }.let { s ->
        
        
        if (s.gpu && img.arch == GuestArch.ARM && BroadcomGpu.v3dFirmware(paths.root)) s.copy(gpu = false, hwui = false) else s
    }
    
    val nativeBoot: Boolean by lazy { !lowPowerBoot && MmiBoot.frameworkless(paths.root) && MmiBoot.service(paths.root) != null }
    
    val nativeUi: Boolean get() = lowPowerBoot || nativeBoot
    private val storageLease = VmStorageLease.forVm(ctx.filesDir)
    val engine get() = img.engine

    @Volatile var state = State.STOPPED
        private set

    
    @Volatile var adbdUp = false
        private set
    @Volatile var adbdAt = 0L
        private set
    private val adbdListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    fun onAdbd(l: (Boolean) -> Unit) { adbdListeners.add(l) }
    fun removeOnAdbd(l: (Boolean) -> Unit) { adbdListeners.remove(l) }
    private fun setAdbd(up: Boolean) {
        if (adbdUp == up) return
        adbdUp = up
        adbdAt = if (up) System.currentTimeMillis() else 0L
        adbdListeners.forEach { runCatching { it(up) } }
    }

    
    val adbAvailable: Boolean get() = VmUiPolicy.adbAvailable(state.name, adbdUp, recoveryMode)
    @Volatile var bootAt = 0L
        private set
    @Volatile var bootDoneAt = 0L
    private val noteBoot = OneTimeVmNote()
    @Volatile var pendingNote = img.oneTimeNote
        private set
    
    @Volatile private var everBooted = false
    @Volatile private var zygoteRestarts = 0
    @Volatile private var imeRetried = false
    
    @Volatile private var heapAbortRetries = 0
    
    @Volatile var screenUpAt = 0L
    
    private val zygoteCrashes = ArrayDeque<Long>()
    
    @Volatile var failure: Msg? = null
        private set
    @Volatile var surface: Surface? = null
        set(v) { field = v; if (engine == Engine.KK) GlBridge.surface(v) else if (engine == Engine.N64 && kernel.gpuBridge64) GlHost.surface(v) }

    private val lines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val logFile = File(paths.bin, "aemu.log")

    fun log(s: String) {
        val line = "${stamp()} $s"
        lines.add(line)
        while (lines.size > 2000) lines.removeAt(0)
        runCatching {
            if (engine == Engine.N64 && kernel.syncLog) FileOutputStream(logFile, true).use { it.write((line + "\n").toByteArray()); it.fd.sync() }
            else logFile.appendText(line + "\n")
        }
        listeners.forEach { runCatching { it(line) } }
    }
    fun lines(): List<String> = lines.toList()
    fun onLog(l: (String) -> Unit) { listeners.add(l) }
    fun onState(l: (State) -> Unit) { stateListeners.add(l) }
    
    fun removeOnLog(l: (String) -> Unit) { listeners.remove(l) }
    fun removeOnState(l: (State) -> Unit) { stateListeners.remove(l) }
    private val liveStatus = LiveVmStatus(ctx.filesDir, slot)
    private fun setState(s: State) {
        state = s
        runCatching { liveStatus.publish(img.id, VmUiPolicy.active(s.name)) }
            .onFailure { log("library status: ${it.message}") }
        stateListeners.forEach { runCatching { it(s) } }
    }

    val props = PropService(paths, ::log).also {
        
        
        if (img.arch != GuestArch.ARM && !settings.gpu) it.pinned = setOf("qemu.gles")
    }
    
    val adb = AdbServer(this)
    val input = InputService(paths, ::log)
    private val vibration = VibrationBridge(ctx, paths, { settings.vibration }, ::log)
    private val battery = HostBatteryBridge(ctx, paths.root, img.api, settings.hostBattery,
        { everBooted && state == State.RUNNING && !stopping && !recoveryMode && !lowPowerBoot },
        { zygoteRestarts }, { command ->
            val result = guestRunner.run(listOf("/system/bin/sh", "-c", command), 4_000)
            if (result.first != 0 && engine != Engine.N64) {
                check(result.first == 0) { "Guest battery update failed: ${result.first}" }
            }
        }, ::log)
    fun setManualBattery(percent: Int, charging: Boolean) = battery.applyManual(percent, charging)
    fun resetBattery() = battery.reset(settings.hostBattery)
    val hostBatteryLevel get() = battery.latest?.percent ?: 80
    val hostBatteryCharging get() = battery.latest?.status in listOf(2, 5)
    val hostBatteryFollowing get() = battery.following
    private val motion = MotionBridge(ctx, paths, { img.api in 9..25 && engine != Engine.N64 && !recoveryMode && !nativeUi }, { settings.motionSensors }, { settings.gyroLock }, ::log)
    fun motionVisible(visible: Boolean) { motion.visible(visible) }
    fun simulateRotation() = motion.simulateRotation()
    
    val mouseKeyboardSupported get() = VirtualInputPolicy.mouseKeyboardSupported(engine, img.arch, recoveryMode, nativeUi, LegacyFirmware.isPreRelease(img.release))
    val mouseKeyboardActive get() = settings.mouseKeyboard && mouseKeyboardSupported
    val cameraSupported get() = engine == Engine.KK && img.api in 14..25 && !recoveryMode && !nativeUi
    private val camera = HostCameraBridge(ctx, paths, { settings.camera && cameraSupported }, ::log)
    fun cameraVisible(visible: Boolean) { camera.visible(visible) }
    
    val microphoneSupported get() = engine == Engine.KK && img.api in 14..25 && !recoveryMode && !nativeUi
    private val microphone = MicBridge(ctx, paths, { settings.microphone && microphoneSupported }, ::log)
    fun micVisible(visible: Boolean) { microphone.visible(visible) }
    val bluetoothSupported get() = engine == Engine.KK && img.api in 9..25 && !recoveryMode && !nativeUi
    private val bluetooth = BluetoothBridge(ctx, paths, { settings.bluetooth && bluetoothSupported }, ::log)
    val wifiSupported get() = WifiEmu.supported(engine, img.api, recoveryMode, nativeUi)
    
    val audio = AudioOut(paths, ::log, if (img.api < 14) 44100 else AudioOut.RATE,
        if (TreeFixer.isMtkAudio(paths.root)) "dev/aemu_pcm" else "dev/eac")
    
    private val cdmaPlan = CdmaMode.plan(img.settings, img.api, paths)
    val ril = RilStub(paths, ::log, img.settings.imei.ifBlank { VmSettings.DEFAULT_IMEI }, img.api,
        VmSettings.cleanBaseband(img.settings.baseband).ifBlank { VmSettings.DEFAULT_BASEBAND },
        img.settings.networkType, img.settings.sim, cdmaPlan.cdma, cdmaPlan.lteOnCdma)
    
    val vold = VoldStub(paths, img.sdcardPath, ::log, others = img.volumes, api = BionicProbe.ruleApi(paths.root, img.api), onStorageState = ::setStorageState)
    
    private fun setStorageState(v: String) { if (img.api in 1..7) props.put(VoldStub.STATE_PROP, v) }
    var onFrame: (() -> Unit)? = null
    val frames = FrameBell(paths, ::log) { onFrame?.invoke() }
    private val events = EventsSink(paths, ::log)
    private val logd = LogdSink(paths, ::log)
    
    val lights = Lights(paths.root, ::log)
    
    val sensors = Sensors(ctx, paths.root, ::log)
    
    val mic = AudioIn(ctx, paths.root, ::log)
    
    val cameraFeed = CameraFeed(ctx, paths, ::log)
    @Volatile private var lmk: GuestLmk? = null
    val net = NetProxy(ctx, paths, ::log)
    
    val netDebug = app.aemu.netdebug.NetDebugger(File(paths.dir, "netdebug"), File(ctx.filesDir, "netdebug-ca"), ::log)
    private val dns = DnsProxy(paths, img.api, ::log, img.engine == Engine.N64 && Kernel64.forApi(img.api).dnsLayoutProbe)
    
    private fun singleTouchWanted(s: VmSettings) = s.mtMode == 4 || img.api <= 4
    private fun effectiveMtMode(s: VmSettings) = if (singleTouchWanted(s)) 4 else s.mtMode

    val runner by lazy { GuestRunner(paths, img.copy(settings = settings)) }

    private val extraStubs = ArrayList<VoldStub>()
    private val procs = LinkedHashMap<String, Process>()
    private val lazyStarts = HashMap<String, Int>()
    private val pgids = LinkedHashSet<Int>()
    @Volatile private var stopping = false

    
    private val zygoteCompat by lazy {
        UnixServer(paths.socket("zygote_compat"), "zygote_compat") { c ->
            val ins = c.inputStream
            val out = c.outputStream
            fun line(): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = ins.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString()
                    sb.append(b.toChar())
                }
            }
            fun writeInt(v: Int) {
                out.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
                out.flush()
            }
            while (!stopping) {
                val n = line()?.trim()?.toIntOrNull() ?: break
                if (n < 1 || n > 1024) break
                val args = ArrayList<String>(n)
                for (i in 0 until n) args += line() ?: return@UnixServer
                val cmd = args[0].trim()
                when {
                    cmd == "--query-abi-list" -> {
                        val abis = compatAbiList()
                        val b = abis.toByteArray(Charsets.US_ASCII)
                        writeInt(b.size)
                        out.write(b)
                        out.flush()
                        log("zygote_compat: ABI list asked, answered \"$abis\"")
                    }
                    cmd == "--boot-completed" -> {
                        log("zygote_compat: boot-completed received")
                        
                        
                        writeInt(0)
                        forceBootFlagIfStuck()
                    }
                    cmd == "--set-api-denylist-exemptions" ||
                        cmd.startsWith("--hidden-api-log-sampling-rate=") ||
                        cmd.startsWith("--hidden-api-statslog-sampling-rate=") -> writeInt(0)
                    else -> {
                        log("zygote_compat: cannot carry out \"$cmd\" (no fork here), closing the connection")
                        return@UnixServer
                    }
                }
            }
        }
    }

    
    private val tombstonedTrace by lazy {
        val seq = java.util.concurrent.atomic.AtomicInteger(0)
        UnixServer(paths.socket("tombstoned_java_trace"), "tombstoned", android.net.LocalSocket.SOCKET_SEQPACKET) { c ->
            val buf = ByteArray(64)
            val n = c.inputStream.read(buf)
            if (n <= 0) return@UnixServer
            val dir = File(paths.root, "data/anr").apply { mkdirs() }
            val f = File(dir, "trace_" + String.format("%02d", seq.getAndIncrement() % 100))
            java.io.RandomAccessFile(f, "rw").use { raf ->
                raf.setLength(0)
                runCatching { android.system.Os.chmod(f.absolutePath, "666".toInt(8)) }
                buf[0] = 2   
                c.setFileDescriptorsForSend(arrayOf(raf.fd))
                c.outputStream.write(buf, 0, n)
                log("tombstoned: java trace requested, writing to data/anr/${f.name}")
                val tmp = ByteArray(64)
                while (c.inputStream.read(tmp) > 0) {  }
            }
        }
    }

    
    private fun compatAbiList(): String {
        fun prop(k: String) = props.get(k)?.trim()?.takeIf { it.isNotEmpty() }
        return prop("ro.product.cpu.abilist")
            ?: listOfNotNull(prop("ro.product.cpu.abilist64"), prop("ro.product.cpu.abilist32")).joinToString(",").ifEmpty { null }
            ?: "arm64-v8a,armeabi-v7a,armeabi"
    }

    

    @Synchronized fun boot() {
        if (state == State.BOOTING || state == State.RUNNING || state == State.PREPARING) return
        try { storageLease.acquire() } catch (t: Throwable) {
            failure = Msg("VM storage is busy: ${t.message}", R.string.vmfail_storage_busy, t.message)
            log(failure!!.english)
            setState(State.FAILED)
            return
        }
        stopping = false
        failure = null
        setAdbd(false)
        setState(State.PREPARING)
        try {
            if (recoveryMode) doRecovery() else if (lowPowerBoot) doCharging() else if (nativeBoot) doMmi() else doBoot()
        } catch (t: Throwable) {
            vibration.stop()
            battery.stop()
            camera.stop()
            bluetooth.stop()
            motion.stop()
            failure = Msg.of(t)
            log("✖ boot aborted: $failure")
            setState(State.FAILED)
        }
    }

    
    @Volatile var recoveryMode = false
    
    @Volatile var recoveryFormat = 0

    
    private fun doCharging() {
        paths.bin.mkdirs()
        logFile.writeText("")
        log("Offline charging boot: ${img.name}")
        val services = ChargingBoot.services(paths.root, img.api)
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()
        battery.start()
        File(paths.root, "dev/tty0").delete() 
        val sysLink = File(paths.bin, "sys")
        if (!java.nio.file.Files.exists(sysLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            android.system.Os.symlink(File(paths.root, "sys").path, sysLink.path)
        if (!props.prepare(ChargingBoot.properties + ("ro.aemu.host" to "qemu-user"))) error("property area not ready")
        props.onSet = { k, v -> if (k == "sys.powerctl") powerRequest(v) }
        props.onCtl = { start, name ->
            services.firstOrNull { it.name == name }?.let { service ->
                if (start && !stopping) startService(ChargingBoot.runtimeService(paths.root, service))
                else synchronized(procs) { procs.remove(name) }?.destroyForcibly()
            }
        }
        props.serve()
        input.rateHz = settings.touchHz
        input.mtMode = effectiveMtMode(settings)
        input.legacyMouseButton = LegacyFirmware.isPreRelease(img.release)
        input.touchEnabled = !LegacyFirmware.isPreRelease(img.release)
        input.serve()
        frames.serve()
        power.writeText("")
        bootAt = System.currentTimeMillis()
        setState(State.BOOTING)
        services.forEach { service ->
            if (stopping) return
            startService(ChargingBoot.runtimeService(paths.root, service))
        }
        setState(State.RUNNING)
        log("Charging services started: ${services.joinToString { it.name }}; Android framework not started")
        Thread({
            val renderers = services.filter { it.name != "healthd-charger" }.ifEmpty { services }
            while (!stopping) {
                Thread.sleep(500)
                if (power.length() > 0) { val request = power.readText().trim(); power.writeText(""); powerRequest(request) }
                val exited = renderers.firstOrNull { service -> synchronized(procs) { procs[service.name]?.isAlive != true } }
                if (exited != null && !stopping) {
                    failure = Msg("Charging service ${exited.name} exited. Check its host log; vendor hardware support may be missing.", R.string.vmfail_charging_service_exited, exited.name)
                    log(failure!!.english); setState(State.FAILED); break
                }
            }
        }, "charging-watchdog").apply { isDaemon = true; start() }
    }

    
    private fun doMmi() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, native MMI/FTM boot (no Android framework)")
        val service = MmiBoot.service(paths.root) ?: error("no MMI/FTM test program found in this firmware")
        val qemu = paths.nativeBin(img.arch.translator(engine))
        if (!qemu.canExecute()) error(translatorMissing(qemu.name))
        val others = MmiBoot.controllable(paths.root)
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()
        File(paths.root, "dev/tty0").delete() 
        val sysLink = File(paths.bin, "sys")
        if (!java.nio.file.Files.exists(sysLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            android.system.Os.symlink(File(paths.root, "sys").path, sysLink.path)
        
        for (n in listOf("system", "vendor")) {
            val l = File(paths.bin, n)
            if (!java.nio.file.Files.exists(l.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                runCatching { android.system.Os.symlink(File(paths.root, n).path, l.path) }
        }
        val serial = VmSettings.cleanSerial(settings.serial).ifBlank { VmSettings.DEFAULT_SERIAL }
        val overrides = LinkedHashMap(MmiBoot.properties)
        overrides["ro.aemu.host"] = "qemu-user"
        overrides["ro.serialno"] = serial
        overrides["ro.boot.serialno"] = serial
        overrides["gsm.version.baseband"] = VmSettings.cleanBaseband(settings.baseband).ifBlank { VmSettings.DEFAULT_BASEBAND }
        overrides["persist.sys.timezone"] = gmtZone()
        
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        if (!props.prepare(overrides)) error("property area not ready")
        props.onSet = { k, v -> if (k == "sys.powerctl") powerRequest(v) }
        
        props.onCtl = { start, name ->
            val def = others[name]
            if (def == null) log("ctl.${if (start) "start" else "stop"} $name: not started (no guest binary)")
            else {
                synchronized(procs) { procs.remove(name) }?.destroyForcibly()
                if (start && !stopping) Thread { runCatching { startService(def) } }.start()
            }
        }
        props.serve()
        input.rateHz = settings.touchHz
        
        input.mtMode = if (settings.mtMode == 0 || settings.mtMode == 4) 5 else settings.mtMode
        input.multiPerPid = true 
        input.serve()
        frames.serve()
        runCatching { power.writeText(""); power.setWritable(true, false) }
        Keeper.hold(ctx, img.name)
        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        setState(State.BOOTING)
        
        
        
        
        paths.binderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")
        
        startService(GuestService("servicemanager", listOf("/system/bin/servicemanager"), optional = true))
        
        
        
        runCatching {
            val modemapi = File(paths.root, "system/lib/libmodemapi.so")
            if (modemapi.isFile) {
                val n = ElfPatch.returnZero(modemapi, setOf("modem_api_init", "modem_api_release", "modem_read_nv"))
                if (n > 0) log("modem API: no modem here, init/read_nv answer at once ($n func.)")
            }
        }.onFailure { log("modem API patch failed: ${it.message}") }
        
        
        runCatching {
            val flag = File(paths.bin, "strace.${service.name}")
            if (File(paths.bin, "strace.keep").exists()) flag.createNewFile() else flag.delete()
            paths.log(service.name).delete() 
        }
        startService(service)
        setState(State.RUNNING)
        log("MMI started: ${service.argv.joinToString(" ")}; Android framework not started")
        Thread({
            while (!stopping) {
                Thread.sleep(500)
                if (power.length() > 0) { val request = power.readText().trim(); power.writeText(""); powerRequest(request) }
                val dead = synchronized(procs) { procs[service.name]?.isAlive != true }
                if (dead && !stopping && !powerHandled) {
                    val code = synchronized(procs) { procs[service.name] }?.let { runCatching { it.exitValue() }.getOrNull() }
                    failure = Msg("MMI program ${service.argv.first()} exited (code ${code ?: "?"}). See the ${service.name} log in the VM log folder; missing vendor hardware is expected, a crash is not.",
                        R.string.vmfail_mmi_program_exited, service.argv.first(), code ?: "?", service.name)
                    log("✖ $failure"); dumpMmiLog(service.name); setState(State.FAILED); break
                }
            }
        }, "mmi-watchdog").apply { isDaemon = true; start() }
    }

    
    private fun dumpMmiLog(name: String) {
        runCatching {
            val f = paths.log(name)
            val len = f.length()
            val text = java.io.RandomAccessFile(f, "r").use { r ->
                val n = minOf(len, 600_000L).toInt(); r.seek(len - n)
                val b = ByteArray(n); r.readFully(b); String(b, Charsets.ISO_8859_1)
            }
            val lines = text.lines().map { it.take(220) }
            val key = Regex("fb0|graphics|tty0|/dev/input|ioctl|mmap|openat|open\\(|ENOENT|EACCES|EINVAL|error|fail|FATAL|cannot|can't|could not|not found", RegexOption.IGNORE_CASE)
            log("── ${name} log: ${lines.size} lines, last 40 ──")
            lines.takeLast(40).forEach { log("  $it") }
            val picked = lines.dropLast(40).filter { key.containsMatchIn(it) && !it.contains("EVIOCGBIT") }.takeLast(80)
            log("── ${name} log: ${picked.size} earlier lines about display/input/errors ──")
            picked.forEach { log("  $it") }
        }.onFailure { log("cannot read the $name log: ${it.message}") }
    }

    private fun doRecovery() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": recovery mode")
        if (!RecoveryImage.installed(paths)) error("this firmware has no recovery installed")
        val is64 = RecoveryImage.isElf64(paths)
        val qemu = paths.nativeBin(if (is64) kernel.runner else img.arch.translator(Engine.KK))
        if (!is64 && !(img.arch == GuestArch.ARM && img.settings.native32 && CpuInfo.hasAArch32) && !qemu.canExecute()) error(translatorMissing(qemu.name))
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()   
        battery.start()
        val sd = Sdcard.setup(ctx, paths, img, ::log)
        val prep = RecoveryImage.prepare(paths, sd, ::log, img.api, settings)
        input.sinkProtocolB = prep.touchProtocolB
        prep.format?.let { recoveryFormat = it; log("recovery framebuffer: ${when (it) { 0 -> "RGB565"; 2 -> "BGRA_8888"; else -> "32-bit RGBX" }}") }
        input.rateHz = settings.touchHz
        input.mtMode = settings.mtMode
        input.trackballEnabled = false 
        input.serve()
        
        
        runCatching {
            val ev = File(RecoveryImage.dir(paths), "dev/input/event0")
            ev.delete()
            android.system.Os.mkfifo(ev.path, "600".toInt(8))
            val fd = android.system.Os.open(ev.path, android.system.OsConstants.O_RDWR or android.system.OsConstants.O_NONBLOCK, 0)
            input.sink = java.io.FileOutputStream(fd)
            for (alt in listOf("event1", "event2", "event3")) {
                val f = File(RecoveryImage.dir(paths), "dev/input/$alt")
                f.delete()
                runCatching { android.system.Os.symlink(ev.path, f.path) }
            }
        }.onFailure { log("recovery: no input channel: ${it.message}") }
        val (w, h) = runCatching { File(paths.root, "dhd.fbgeom").readText().trim().split(Regex("\\s+")).map { it.toInt() } }
            .getOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (settings.width to settings.height)
        val r = RecoveryImage.dir(paths)
        runCatching { File(r, "tmp/recovery.log").delete() }
        runCatching { File(paths.bin, "recovery.trace").delete() }
        
        val trace = if (File(paths.bin, "recovery.strace").exists()) {
            log("recovery: tracing system calls into run/recovery.trace")
            listOf("-strace", "-D", File(paths.bin, "recovery.trace").absolutePath)
        } else emptyList()
        val cmd = when {
            img.arch == GuestArch.ARM && img.settings.native32 && CpuInfo.hasAArch32 && !is64 -> listOf(File(r, "sbin/recovery").absolutePath)
            is64 -> listOf(qemu.absolutePath, "/sbin/recovery", File(r, "sbin/recovery").absolutePath)
            else -> listOf(qemu.absolutePath, "-L", r.absolutePath, "-0", "/sbin/recovery") + trace + File(r, "sbin/recovery").absolutePath
        }
        val pb = ProcessBuilder(cmd)
            .directory(r).redirectErrorStream(true).redirectOutput(File(paths.bin, "recovery.out"))
        pb.environment().clear()
        pb.environment().putAll(mapOf(
            "PATH" to "/sbin:/system/bin", "LD_LIBRARY_PATH" to ".:/sbin", "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data",
            "EXTERNAL_STORAGE" to "/sdcard", "TZ" to "UTC",
            "LD_PRELOAD" to "/sbin/librecshim.so:/sbin/libaemushim.so", "AEMU_MOUNTS" to "/sdcard:/data:/system:/cache:/emmc:/external_sd:/usb-otg",
            "DHD_FB_W" to "$w", "DHD_FB_H" to "$h", "DHD_IN_W" to "$w", "DHD_IN_H" to "$h",
            "DHD_INPUT" to paths.inputSock.absolutePath,
        ))
        powerHandled = false
        bootAt = System.currentTimeMillis()
        val p = pb.start()
        synchronized(procs) { procs["recovery"] = p }
        setState(State.RUNNING)
        log("recovery started (${w}x$h)")
        
        Thread {
            val rlog = File(r, "tmp/recovery.log")
            repeat(40) {
                if (!p.isAlive || stopping) return@Thread
                val t = runCatching { rlog.readText() }.getOrDefault("")
                val fmt = Regex("Pixel format: (BGRA_8888|RGBX_8888|RGBA_8888|RGB_565)").findAll(t).lastOrNull()?.groupValues?.get(1)
                if (fmt != null) {
                    recoveryFormat = when (fmt) { "BGRA_8888" -> 2; "RGB_565" -> 0; else -> 1 }
                    log("recovery draws $fmt")
                    return@Thread
                }
                Thread.sleep(250)
            }
        }.start()
        
        val power = File(r, "dev/aemu_power")
        Thread {
            while (p.isAlive && !stopping) {
                if (power.length() > 0) {
                    val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText("")
                    powerRequest(v); break
                }
                Thread.sleep(500)
            }
        }.start()
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (stopping || powerHandled) return@Thread
            
            val pw = File(RecoveryImage.dir(paths), "dev/aemu_power")
            val req = if (pw.length() > 0) runCatching { pw.readText().trim() }.getOrDefault("") else ""
            if (req.isNotEmpty()) { runCatching { pw.writeText("") }; powerRequest(req); return@Thread }
            powerHandled = true
            
            
            if (code == 159) runCatching {
                File(paths.bin, "recovery.strace").createNewFile()
                log("recovery: killed by the sandbox (SIGSYS); the next try traces its system calls")
            }
            
            recoveryEnded("recovery process exited by itself (code $code) after ${(System.currentTimeMillis() - bootAt) / 1000.0} s",
                "exit code $code")
        }.start()
    }

    
    private fun recoveryEarlyExit(): Boolean = recoveryMode && System.currentTimeMillis() - bootAt < RECOVERY_EARLY_MS

    
    private fun recoveryEnded(what: String, shortWhat: String) {
        log("✖ $what")
        val r = RecoveryImage.dir(paths)
        val recLog = runCatching { File(r, "tmp/recovery.log").readLines() }.getOrDefault(emptyList())
        val recOut = runCatching { File(paths.bin, "recovery.out").readLines() }.getOrDefault(emptyList())
        log("recovery: tmp/recovery.log ${if (recLog.isEmpty()) "is empty or missing" else "(last ${minOf(40, recLog.size)} of ${recLog.size} lines)"}")
        recLog.takeLast(40).forEach { log("  recovery.log| $it") }
        log("recovery: stdout/stderr ${if (recOut.isEmpty()) "is empty (a recovery writes its messages to tmp/recovery.log)" else "(last ${minOf(40, recOut.size)} lines)"}")
        recOut.takeLast(40).forEach { log("  recovery.out| $it") }
        runCatching {
            File(paths.bin, "recovery-last.log").writeText(buildString {
                append("=== host log (aemu.log) ===\n"); this@GuestVm.lines().forEach { append(it).append('\n') }
                append("\n=== recovery tmp/recovery.log (last 400 lines) ===\n"); recLog.takeLast(400).forEach { append(it).append('\n') }
                append("\n=== recovery stdout/stderr (run/recovery.out, last 400 lines) ===\n"); recOut.takeLast(400).forEach { append(it).append('\n') }
                val tr = runCatching { File(paths.bin, "recovery.trace").readLines() }.getOrDefault(emptyList())
                if (tr.isNotEmpty()) { append("\n=== system calls (run/recovery.trace, last 150 of ${tr.size} lines) ===\n"); tr.takeLast(150).forEach { append(it).append('\n') } }
            })
            log("recovery: log saved to run/recovery-last.log (it is kept across restarts and goes into the exported log bundle)")
        }.onFailure { log("recovery: could not save the log: ${it.message}") }
        synchronized(procs) { procs["recovery"] }?.let { runCatching { it.destroyForcibly() } }
        failure = Msg("Recovery stopped on its own ($shortWhat). Its log was saved to run/recovery-last.log.",
            R.string.vmfail_recovery_exited, shortWhat)
        setState(State.FAILED)
    }

    
    private fun translatorMissing(name: String): String = when {
        name == GuestArch.X86.qemuLib -> "this firmware is x86 and the x86 translator ($name) is not in this build of the app"
        
        name == GuestArch.X86.n64Lib32 || name == GuestArch.X86_64.n64Lib64 ->
            "this firmware is ${img.arch.id} and the translator ($name) is not in this build of the app"
        else -> "translator $name is not executable"
    }

    
    private val legacyFw: Boolean by lazy { LegacyFirmware.isLegacyLayout(paths.root) }

    
    private fun storedServices(): List<GuestService> = if (legacyFw) servicePlan() else img.services

    private fun doBoot() {
        paths.bin.mkdirs()
        x86Proto8AtBoot = BinderProtocol.x86Learned8(paths.bin)
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, ${img.skin}, engine ${engine.title}")
        if (img.arch != GuestArch.ARM && engine == Engine.N64)
            log("· guest architecture ${img.arch.id} on the native engine: translators ${img.arch.n64Translators.joinToString(" + ")}, the engine's arm64 guest files are not installed, GPU off")
        else if (img.arch != GuestArch.ARM) log("· guest architecture ${img.arch.id}: translator ${img.arch.qemuLib}, no helper shims until the ${img.arch.id} builds are in assets/engines/*${img.arch.assetSuffix}")
        else if (QemuFallback.enabled(paths)) log("translator: ${engine.qemuXperiaPlay} (patched guest mmap base)")
        
        
        LegacyFirmware.ensureLayout(paths.root, ::log)
        if (!File(paths.root, "system/framework").isDirectory) error("firmware tree missing: no system/framework (or system/javalib) in the imported tree")
        if (MmiBoot.frameworkless(paths.root))
            error("this firmware has no Android framework (system/framework/framework.jar) and no native MMI/FTM test program to run instead")
        val preOne = engine != Engine.N64 && img.arch == GuestArch.ARM && LegacyFirmware.isPreRelease(img.release) &&
            paths.nativeBin(LegacyFirmware.PRE_RELEASE_TRANSLATOR).canExecute()
        val qemu = paths.nativeBin(if (engine == Engine.N64) kernel.runner else if (preOne) LegacyFirmware.PRE_RELEASE_TRANSLATOR else img.arch.translator(engine))
        if (!qemu.canExecute()) error(translatorMissing(qemu.name))
        if (preOne) log("translator: ${LegacyFirmware.PRE_RELEASE_TRANSLATOR} (pre-1.0 build: readable memory is executable)")
        if (engine == Engine.N64) log("· kernel ${kernel.tag} (${kernel.runner})")
        
        
        if (engine == Engine.N64 && img.arch.foreign) {
            val arch = img.arch
            if (!arch.runsOnNative(img.api)) error("${arch.id} firmware needs Android ${if (arch == GuestArch.X86) "8.0" else "5.0"} or newer on the native engine (this one is API ${img.api})")
            
            val needed = if (arch.n64Lib64 != null) listOf(arch.n64Lib64) else listOf(arch.n64Lib32)
            needed.firstOrNull { !paths.nativeBin(it).canExecute() }?.let { error(translatorMissing(it)) }
            if (arch.n64Lib64 != null && !paths.nativeBin(arch.n64Lib32).canExecute() && has32BitSide(paths.root))
                log("✖ ${arch.n64Lib32} is not in this build of the app: the 32-bit programs of this firmware cannot start")
            log("· ${arch.id} firmware: translated by ${arch.n64Translators.joinToString(" / ") { it.removeSuffix(".so") }} on the native engine")
        } else
        
        if (engine == Engine.N64 && isPure32Tree(paths.root)) {
            if (!paths.nativeBin("libqemu_arm.so").canExecute())
                error("this firmware is 32-bit only and the native engine has no translator (libqemu_arm.so) in this build")
            log("· 32-bit firmware: translated by qemu-arm on the native engine")
        }
        
        
        
        
        if (engine == Engine.N64) runCatching {
            if (File(paths.bin, "SwiftShader.off").exists()) File(paths.bin, "SwiftShader.ini").delete()
            else File(paths.bin, "SwiftShader.ini").writeText("[Processor]\nThreadCount=${Runtime.getRuntime().availableProcessors().coerceIn(1, 8)}\n")
        }
        killLeftovers()
        HostNative.limitStackSafe()

        
        val fixer = TreeFixer(ctx, paths, img, ::log)
        fixer.fixup()
        battery.start()
        
        if (File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so").isFile) swapAudioPolicy()
        GuestLog.clear(paths.root)
        val sd = Sdcard.setup(ctx, paths, img, ::log)

        
        val s = settings
        val overrides = LinkedHashMap<String, String>()
        
        overrides["ro.kernel.qemu"] = if (engine == Engine.GB) "0" else "1"
        overrides["ro.kernel.qemu.gles"] = if (s.gpu && (s.hwui || engine == Engine.GB)) "1" else "0"
        overrides["qemu.gles"] = if (s.gpu) "1" else "0"
        if (engine == Engine.N64) {             
            
            
            
            
            
            
            val hasSs = File(paths.root, kernel.eglLib).isFile ||
                File(paths.root, "system/lib/egl/libEGL_swiftshader.so").isFile
            val ss = if (hasSs) kernel.gles else "0"
            
            overrides["ro.kernel.qemu.gles"] = ss; overrides["qemu.gles"] = ss
        }
        var glBridge = false
        var glInfo = ""
        
        
        val pure32Gpu = engine == Engine.N64 && isPure32Tree(paths.root) && File(paths.root, "${kernel.eglDir32}/libGLES_aemu.so").isFile
        if (engine == Engine.N64 && kernel.gpuBridge64 && s.gpu &&
            (pure32Gpu || File(paths.root, "${kernel.eglDir}/libGLES_aemu.so").isFile)) {
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("GPU bridge: $r")
            glBridge = r.startsWith("ok"); glInfo = r
        }
        
        
        
        
        if (engine == Engine.KK && s.gpu && File(paths.dir, "glopen").isFile && File(paths.root, "system/lib/egl/libGLES_split.so").isFile) {
            paths.socket("aemu_gl").parentFile?.mkdirs()
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("open GPU bridge (32-bit): $r")
            if (r.startsWith("ok")) overrides["debug.aemu.gl"] = File(paths.dir, "glopen").readText().trim().takeIf { it.isNotEmpty() } ?: "2"
        }
        
        if (img.api >= 24 && !s.gpu) {
            overrides["ro.hardware.egl"] = "android"
        }
        
        
        
        if (engine == Engine.N64 && kernel.bootHardwareProp) {
            val hw = File(paths.root, "vendor/etc/init/hw").list().orEmpty().mapNotNull { Regex("^init[.](qcom|mt[0-9]+[a-z]*)[.]rc$").find(it)?.groupValues?.get(1) }.firstOrNull()
            if (hw != null) overrides["ro.boot.hardware"] = hw
        }
        overrides["ro.sf.lcd_density"] = s.density.toString()
        overrides["qemu.sf.lcd_density"] = s.density.toString()
        val hz = s.fbHz.coerceIn(10, 120)
        overrides["debug.aemu.hz"] = hz.toString()
        overrides["ro.surface_flinger.primary_display_refresh_rate"] = hz.toString()
        val soc = s.spoofSoc.trim()
        if (soc.isNotEmpty()) {
            overrides["ro.board.platform"] = soc
            overrides["ro.chipname"] = soc
            overrides["ro.hardware.chipname"] = soc
            overrides["ro.product.board"] = soc
            log("spoof: SoC reported as $soc")
        }
        val gpuModel = s.spoofGpu.trim()
        if (gpuModel.isNotEmpty()) {
            overrides["ro.gpu.model"] = gpuModel
            overrides["ro.hardware.gpu"] = gpuModel
            overrides["debug.aemu.gpu_model"] = gpuModel
            log("spoof: GPU reported as $gpuModel")
        }
        val storageGb = s.spoofStorageGb.toIntOrNull() ?: 0
        if (storageGb > 0) {
            overrides["ro.aemu.storage_gb"] = storageGb.toString()
            overrides["debug.aemu.storage_gb"] = storageGb.toString()
            log("spoof: storage reported as $storageGb GB")
        }
        
        if (listOf("system/build.prop", "vendor/build.prop").any { runCatching { File(paths.root, it).readText() }.getOrDefault("").contains("persist.miui.density_v2") }) overrides["persist.miui.density_v2"] = s.density.toString()
        
        
        
        
        
        val hwRot = runCatching {
            File(paths.root, "system/build.prop").readLines()
                .firstOrNull { it.trim().startsWith("ro.sf.hwrotation=") }?.substringAfter('=')?.trim()?.toIntOrNull()
        }.getOrNull()
        if (hwRot != null && hwRot != 0) {
            overrides["ro.sf.hwrotation"] = "0"
            log("display: ignoring the firmware's panel rotation ($hwRot°), the UI follows the framebuffer")
        }
        
        
        
        
        
        val sfOrient = runCatching {
            listOf("system/build.prop", "vendor/build.prop", "system/vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
                "product/etc/build.prop", "system/product/etc/build.prop", "system_ext/etc/build.prop", "system/system_ext/etc/build.prop")
                .asSequence().map { File(paths.root, it) }.filter { it.isFile }
                .mapNotNull { f -> f.readLines().lastOrNull { it.trim().startsWith("ro.surface_flinger.primary_display_orientation=") } }
                .map { it.substringAfter('=').trim() }.firstOrNull()
        }.getOrNull()
        if (sfOrient != null && sfOrient != "ORIENTATION_0" && sfOrient != "0") {
            overrides["ro.surface_flinger.primary_display_orientation"] = "ORIENTATION_0"
            log("display: ignoring the firmware's primary display orientation ($sfOrient), the UI follows the framebuffer")
        }
        
        if (img.api in 1..7) overrides[VoldStub.STATE_PROP] = VoldStub.MOUNTED_PROP
        overrides["ro.aemu.host"] = if (engine == Engine.N64) "native" else "qemu-user"
        
        val serial = VmSettings.cleanSerial(s.serial).ifBlank { VmSettings.DEFAULT_SERIAL }
        overrides["ro.serialno"] = serial
        overrides["ro.boot.serialno"] = serial
        
        overrides["gsm.version.baseband"] = VmSettings.cleanBaseband(s.baseband).ifBlank { VmSettings.DEFAULT_BASEBAND }
        
        VmSettings.cleanHardware(s.hardware).takeIf { it.isNotEmpty() }?.let {
            overrides["ro.hardware"] = it
            log("props: ro.hardware=$it (from settings)")
        }
        if (s.camera && cameraSupported) overrides["ro.hardware.camera"] = "aemu_host"
        if (img.api in 9..25 && engine != Engine.N64) overrides["ro.hardware.sensors"] = "aemu_host"
        if (engine == Engine.N64) {
            
            if (isPure32Tree(paths.root)) {
                
                
                
                overrides["ro.zygote"] = "zygote32"
                val abi32 = pure32Abilist32()
                overrides["ro.product.cpu.abilist32"] = abi32
                overrides["ro.product.cpu.abilist"] = abi32
                overrides["ro.product.cpu.abilist64"] = ""
                log("props: 32-bit firmware, ro.product.cpu.abilist32=$abi32")
            } else {
                
                
                
                val has32 = n64Has32
                val abis = NativeAbiLists.forArch(img.arch, has32)
                overrides["ro.zygote"] = if (has32) "zygote64_32" else "zygote64"
                overrides["ro.product.cpu.abilist"] = abis.abilist
                overrides["ro.product.cpu.abilist64"] = abis.abilist64
                overrides["ro.product.cpu.abilist32"] = abis.abilist32
                if (img.arch.foreign) log("props: ${img.arch.id} firmware, ro.product.cpu.abilist=${abis.abilist}")
            }
        }
        
        
        val jitOn = s.jit
        overrides["dalvik.vm.execution-mode"] = if (jitOn) "int:jit" else "int:fast"
        
        
        
        
        if (!jitOn && img.api >= 21) overrides["dalvik.vm.usejit"] = "false"
        
        val chosenRamMb = RamChoice.resolve(s.ramMb)
        if (s.lowRam || chosenRamMb in 1..768) overrides["ro.config.low_ram"] = "true"
        if (chosenRamMb > 0) {
            
            val heap = (chosenRamMb / 4).coerceIn(32, 512)
            overrides["dalvik.vm.heapsize"] = "${heap}m"
            overrides["dalvik.vm.heapgrowthlimit"] = "${(heap / 2).coerceAtLeast(24)}m"
        }
        
        
        
        
        if (!s.radio) overrides["ro.radio.noril"] = "true"
        else if (img.api <= 8) overrides["ro.radio.noril"] = ""
        
        props.pinned = props.pinned + CdmaMode.pinnedProps(cdmaPlan, img.api)
        overrides.putAll(CdmaMode.propOverrides(cdmaPlan, img.api, ril.operatorNumeric, ril.operatorName) { CdmaMode.firmwareProp(paths, it) })
        
        
        
        
        
        
        if (engine == Engine.N64 && img.api >= 29) {
            overrides["debug.hwui.renderer"] = "skiagl"
            overrides["ro.hwui.use_vulkan"] = "false"
            props.pinned = props.pinned + "debug.hwui.renderer"
        }
        else if (!s.hwui) overrides["debug.hwui.renderer"] = "skia"
        
        
        else if (engine == Engine.N64) overrides["debug.hwui.renderer"] = "skiagl"
        
        
        HyperOsCompat.overrides(paths.root, img.api, engine == Engine.N64).let { hy ->
            if (hy.isNotEmpty()) {
                overrides.putAll(hy)
                props.pinned = props.pinned + hy.keys
                log("MIUI/HyperOS: background blur off and the Rust runtime not used (${hy.size} properties pinned), SystemUI and the launcher start through zygote")
            }
        }
        
        if (HostInfo.gpu().contains("mali")) overrides["ro.hwui.use_gpu_pixel_buffers"] = "false"
        if (img.api in 19..20 && !isLpw39) overrides["persist.sys.dalvik.vm.lib"] = "libdvm.so"
        
        
        
        if (img.api in 9..15) overrides["persist.lab.gcam"] = "0"
        
        
        if (img.api >= 19) overrides["ro.zygote.disable_gl_preload"] = "1"
        if (engine == Engine.N64) overrides.putAll(kernel.props)
        if (glBridge) {
            overrides["ro.hardware.egl"] = "aemu"
            if (kernel.bridgeAsEmulation) {
                overrides["ro.kernel.qemu.gles"] = "1"; overrides["qemu.gles"] = "1"
                
                if (!pure32Gpu) File(paths.root, "${kernel.eglDir}/libGLES_aemu.so").copyTo(File(paths.root, "${kernel.emulationDir ?: kernel.eglDir}/libGLES_emulation.so"), overwrite = true)
            }
            
            
            
            
            
            
            
            
            
            
            
            
            overrides["debug.aemu.gl"] = File(paths.dir, "glmode").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (pure32Gpu) { if (pure32HwcShowsGpuFrames(paths.root)) PURE32_GPU_MODE else "2" }
                
                
                
                
                
                
                else kernel.gpuBridgeMode
            if (pure32Gpu && overrides["debug.aemu.gl"] == "2" && !pure32HwcShowsGpuFrames(paths.root))
                log("GPU (32-bit firmware): this tree's hwcomposer only copies guest memory to fb0 (no direct mode), compositor stays on SwiftShader")
            if (pure32Gpu) log("GPU (32-bit firmware): debug.aemu.gl=${overrides["debug.aemu.gl"]}" + when (overrides["debug.aemu.gl"]) {
                "1" -> " (programs' own contexts only; the UI is drawn by SwiftShader)"
                "2" -> " (programs' and the window toolkit's contexts on the phone's GPU; the compositor stays on SwiftShader)"
                "3" -> " (everything on the phone's GPU, the compositor too)"
                else -> ""
            })
            
            overrides["ro.opengles.version"] = if (glInfo.contains("ES 3.1 to the guest")) "196609" else "196608"
        }
        
        
        if (img.api >= 21) {
            
            
            if (!s.fullDexopt) overrides["dalvik.vm.dex2oat-filter"] = "verify-none"
            
            if (img.api >= 24 && !s.fullDexopt && engine != Engine.N64) for (r in listOf("first-boot", "boot", "install", "bg-dexopt", "ab-ota", "core-app", "forced-dexopt", "nsys-library"))
                overrides["pm.dexopt.$r"] = "verify-none"
            overrides["dalvik.vm.dex2oat-flags"] = "-j" + Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            
            
            if (img.api >= 21) {
                overrides["dalvik.vm.extra-opts"] = "-Xnorelocate"
                
                overrides["dalvik.vm.dex2oat-flags"] = overrides["dalvik.vm.dex2oat-flags"] + " --runtime-arg -Xnorelocate"
                
                
                
                if (img.api in 21..23 && Dex2oatArgSplit.install(paths.root))
                    log("dex2oat: wrapper splits the combined dex2oat-flags argument (API 21-23 installd)")
            }
        }
        
        
        
        
        
        
        
        
        
        val bootImageOn = img.api >= 34 && s.useArtBootImage
        if (img.api >= 34 && !bootImageOn) runWithoutBootImage { k, v -> overrides[k] = v }
        val ccRebuild = bootImageOn && CcBootImage.pending(paths.root, img.api)
        if (ccRebuild) log("art: boot image has no read barrier: it will be rebuilt for CC before the guest starts")
        else if (bootImageOn && CcBootImage.needed(paths.root, img.api)) {
            log("art: a boot image rebuild failed before for this firmware; using the libart patch")
            applyNoReadBarrierFallback({ overrides[it] }, { k, v -> overrides[k] = v })
        }

        
        
        
        
        
        
        if (isLpw39 && img.api == 20) {
            overrides["dalvik.vm.dex2oat-flags"] = "--compiler-filter=interpret-only"
            
            if (Lpw39Dex2oat.install(paths.root)) log("LPW39: dex2oat wrapped (SIGABRT after a complete write counts as success)")
            if (writeLpw39ImageClasses()) {
                
                overrides["dalvik.vm.image-dex2oat-flags"] = "--image-classes-zip=/system/framework/aemu-imgclasses.zip --image-classes=preloaded-classes"
            }
        }
        
        
        if (img.skin.contains("MIUI", true) && img.runtime == "dalvik") overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a,m=y"
        
        
        else if (img.runtime == "dalvik" && !s.fullDexopt) overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a"
        
        if (TreeFixer.isMtkAudio(paths.root) || File(paths.root, "system/bin/nvram_daemon").isFile) overrides["nvram_init"] = "Ready"
        
        if (img.api >= 16 && (img.skin.contains("TouchWiz", true) || img.skin.contains("One UI", true))) {
            overrides["ro.tvout.enable"] = "false"
            overrides["ro.SecEDS.enable"] = "false"
        }
        
        
        
        
        overrides["qemu.hw.mainkeys"] = if (s.guestNavBar || !(s.showNavBar && !(engine == Engine.N64 && kernel.navBarRequired))) "0" else "1"
        
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        
        
        
        if (engine == Engine.N64) overrides["persist.sys.binary_xml"] = "true"
        overrides["net.dns1"] = "8.8.8.8"
        overrides["net.dns2"] = "1.1.1.1"
        
        overrides["persist.sys.timezone"] = gmtZone()
        
        
        val later = LinkedHashMap<String, String>()
        
        
        
        val vendorProps = listOf("vendor/build.prop", "system/vendor/build.prop", "vendor/etc/build.prop", "system/vendor/etc/build.prop")
            .map { File(paths.root, it) }.filter { it.isFile }
        val fileLists = listOf("system/etc/prop.default", "vendor/default.prop").map { File(paths.root, it) } + vendorProps
        for (f in fileLists) runCatching {
            if (f.isFile) PropArea.parseProps(f.readText()).forEach { (k, v) -> later.putIfAbsent(k, v) }
        }
        if (vendorProps.isNotEmpty() && !File(paths.root, "vendor/build.prop").isFile) log("props: vendor properties read from ${vendorProps.first().relativeTo(paths.root).path}")
        
        
        
        
        val llndkList = listOf("system/etc", "system/system/etc", "vendor/etc").map { File(paths.root, it) }.any { d ->
            d.listFiles().orEmpty().any { it.isFile && Regex("""llndk[._]libraries([._][0-9]+)?\.txt""").matches(it.name) }
        }
        
        
        
        
        
        
        
        val vndkLessVendor = kernel.trebleLinkerConfig && !later.containsKey("ro.vndk.version") && llndkList &&
            (vendorProps.isNotEmpty() || File(paths.root, "apex").listFiles().orEmpty().none { it.name.startsWith("com.android.vndk.v") })
        log("props: vendor build.prop files=${vendorProps.map { it.relativeTo(paths.root).path }}, llndk list=$llndkList, vndk-less=$vndkLessVendor")
        
        
        if (vndkLessVendor && !later.containsKey("ro.board.api_level") && !later.containsKey("ro.vendor.api_level")) {
            val llndk = runCatching { PropArea.parseProps(File(paths.root, "system/build.prop").readText())["ro.llndk.api_level"] }.getOrNull()
                ?: kernel.props["ro.llndk.api_level"]
            if (llndk != null) { later["ro.board.api_level"] = llndk; log("props: ro.board.api_level = $llndk (taken from ro.llndk.api_level)") }
        }
        
        
        
        
        
        
        val vndkPackages: List<Int>? =
            if (engine == Engine.N64 && kernel.trebleLinkerConfig && File(paths.root, kernel.linkerConfigTool).isFile)
                File(paths.root, "apex").listFiles().orEmpty().mapNotNull { d ->
                    Regex("""com\.android\.vndk\.v(\d+)""").matchEntire(d.name)?.groupValues?.get(1)?.toIntOrNull()
                        ?.takeIf { d.isDirectory && (File(d, "lib64").isDirectory || File(d, "lib").isDirectory) }
                }.sorted()
            else null
        if (engine == Engine.N64) kernel.propDefaults.forEach { (k, v) ->
            if (k == "ro.vndk.version") {
                if (vndkLessVendor) return@forEach
                if (vndkPackages != null) {
                    val want = v.toIntOrNull()
                    if (want != null && want !in vndkPackages) {
                        val use = vndkPackages.lastOrNull { it <= want } ?: vndkPackages.firstOrNull()
                        if (use == null) log("vndk: no com.android.vndk.v* package in the tree, ro.vndk.version left unset (engine default was $v)")
                        else { log("vndk: com.android.vndk.v$v is not in the tree, ro.vndk.version = $use (has: ${vndkPackages.joinToString()})"); later.putIfAbsent(k, use.toString()) }
                        return@forEach
                    }
                }
            }
            later.putIfAbsent(k, v)
        }
        if (engine == Engine.N64 && kernel.derivedIdentity) deriveIdentity(later)
        
        
        
        
        
        if (engine == Engine.N64 && kernel.vendorHeapProps) runCatching {
            PropArea.parseProps(File(paths.root, "vendor/build.prop").readText()).forEach { (k, v) ->
                if (k.startsWith("dalvik.vm.heap")) overrides.putIfAbsent(k, v)
            }
        }
        if (engine == Engine.N64 && kernel.mediaProfilesFallback) runCatching {
            val etc = File(paths.root, "vendor/etc")
            
            listOf("media_profiles_V1_0.xml", "media_profiles.xml").firstOrNull { File(etc, it).isFile }?.let { have ->
                for (name in listOf("vendor", "khaje", "scuba")) File(etc, "media_profiles_$name.xml").let {
                    if (!it.exists()) runCatching { android.system.Os.symlink(have, it.absolutePath) }
                }
            }
        }
        if (engine == Engine.N64 && kernel.oneFingerprint) runCatching {
            PropArea.parseProps(File(paths.root, "system/build.prop").readText())["ro.system.build.fingerprint"]?.let { fp ->
                overrides["ro.vendor.build.fingerprint"] = fp; overrides["ro.bootimage.build.fingerprint"] = fp
            }
        }
        if (!props.prepare(overrides, later, if (engine == Engine.N64) kernel.propAreaSize else PropArea.SIZE)) error("property area not ready")
        
        
        
        
        LegacyFirmware.runtimeVm(paths.root)?.let { vm ->
            if (props.get("runtime.vm") != vm) props.put("runtime.vm", vm)
            log("runtime: ${if (vm == LegacyFirmware.VM_JAM) "JamVM" else "Dalvik"} (runtime.vm=$vm)")
        }
        
        
        
        
        
        
        
        if (engine == Engine.N64 && img.api >= 26 && !overrides.containsKey("dalvik.vm.heapsize")) {
            fun mb(v: String?) = v?.trim()?.removeSuffix("m")?.toIntOrNull() ?: 0
            val heap = mb(props.get("dalvik.vm.heapsize"))
            if (heap < MIN_JAVA_HEAP_MB) {
                val growth = mb(props.get("dalvik.vm.heapgrowthlimit"))
                props.put("dalvik.vm.heapsize", "512m")
                if (growth < 192) props.put("dalvik.vm.heapgrowthlimit", "256m")
                log("props: dalvik.vm.heapsize was ${heap}m, too small for system_server: heapsize 512m, " +
                    "heapgrowthlimit ${props.get("dalvik.vm.heapgrowthlimit")}")
            }
        }
        
        
        
        
        
        
        
        if (engine == Engine.N64 && img.arch.foreign) {
            fun mb(v: String?) = v?.trim()?.removeSuffix("m")?.toIntOrNull() ?: 0
            val heap = mb(props.get("dalvik.vm.heapsize"))
            if (heap > FOREIGN_MAX_JAVA_HEAP_MB) {
                props.put("dalvik.vm.heapsize", "${FOREIGN_MAX_JAVA_HEAP_MB}m")
                if (mb(props.get("dalvik.vm.heapgrowthlimit")) > FOREIGN_MAX_JAVA_HEAP_MB)
                    props.put("dalvik.vm.heapgrowthlimit", "${FOREIGN_MAX_JAVA_HEAP_MB}m")
                log("props: dalvik.vm.heapsize was ${heap}m, ${FOREIGN_MAX_JAVA_HEAP_MB}m for an ${img.arch.name} firmware " +
                    "(the 32-bit zygote's 2 x Xmx region space has to fit below the guest stack at 0x40000000)")
            }
        }
        
        if (img.api <= 15) for ((k, v) in app.aemu.importer.Analyzer.LMK_DEFAULTS) if (props.get(k).isNullOrEmpty()) props.put(k, v)
        ensureMediaProfiles()
        fixer.skipPreBoot(props)
        
        if (SetupWizardOption.restoreSonyFlow(s.skipSetupWizard,
                File(paths.root, "data/system/aemu-setup-skip.properties").isFile))
            fixer.restoreSonySetupFlow()
        fixer.noScreenSleep()
        if (s.skipSetupWizard) fixer.provisionBeforeBoot()
        props.onSet = { k, v -> onProp(k, v) }
        props.onCtl = { start, svc -> onCtl(start, svc) }

        
        props.serve()
        input.rateHz = s.touchHz
        input.mtMode = effectiveMtMode(s)
        input.legacyMouseButton = LegacyFirmware.isPreRelease(img.release)
        input.touchEnabled = !LegacyFirmware.isPreRelease(img.release)
        input.detectHome(paths.root)
        input.trackballEnabled = settings.trackball
        input.mouseKeyboardEnabled = mouseKeyboardActive
        input.api = img.api
        
        input.wide = InputService.wideFor(engine, isPure32Tree(paths.root))
        input.serve()
        if (engine != Engine.N64) vibration.serve()   
        motion.serve()
        if (s.camera && cameraSupported) camera.serve()
        if (s.microphone && microphoneSupported) microphone.serve()
        if (s.bluetooth && bluetoothSupported) bluetooth.serve()
        frames.serve()
        if (s.radio) {
            if (cdmaPlan.cdma) log("radio: CDMA emulation on (" + (if (cdmaPlan.lteOnCdma) "firmware is LTE on CDMA" else "CDMA-only phone") + ")")
            else if (cdmaPlan.firmwareLteOnCdma) log("radio: firmware declares LTE on CDMA, CDMA emulation is off in settings: telephony.lteOnCdmaDevice cleared, GSM phone")
            ril.serve()
        } else log("radio: emulation disabled in settings")
        vold.serve()
        
        if (img.api in 1..3) VoldStub(paths, img.sdcardPath, ::log, "mountd", api = img.api, onStorageState = ::setStorageState)
            .also { it.serve(); extraStubs.add(it) }
        
        runCatching {
            
            val rcFiles = listOf(paths.root, File(paths.root, "system/etc/init"))
                .flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") } ?: emptyList() }
            val rc = InitPlan.parse(rcFiles)
            rc.services["vold"]?.sockets?.keys?.filter { it != "vold" }?.forEach { name ->
                VoldStub(paths, img.sdcardPath, ::log, name).also { it.serve(); extraStubs.add(it) }
            }
        }
        
        
        
        if (TreeFixer.isMtkAudio(paths.root)) {
            runCatching {
                val eac = audio.fifo
                if (!isLinkTo(eac, "/dev/null")) { eac.delete(); android.system.Os.symlink("/dev/null", eac.absolutePath) }
                
                for (n in listOf("ccci_pcm_rx", "ccci_pcm_tx")) {
                    val f = File(paths.root, "dev/$n")
                    if (!isLinkTo(f, "/dev/null")) { f.delete(); android.system.Os.symlink("/dev/null", f.absolutePath) }
                }
            }
            
            audio.makeFifo(); audio.start()
            log("audio: MediaTek, emulator HAL via /dev/aemu_pcm")
        } else { audio.makeFifo(); audio.start() }
        events.start()
        
        if (img.api >= 21 || isLpw39) logd.start()
        lights.start()
        if (engine == Engine.N64) { sensors.start(); mic.start(); cameraFeed.start() }
        if (img.api >= 14) runCatching { dns.ensure() }
        
        if (engine == Engine.N64) runCatching {
            val f = File(paths.root, "dev/.aemu-ram")
            if (kernel.ramBudget) f.writeText((GuestRunner.guestRamMb(img.api, s.ramMb) * 1024L).toString()) else f.delete()
        }
        lmk = GuestLmk(paths.root, s.lowRam, ::log, RamChoice.resolve(s.ramMb), MemoryMode.parse(s.memoryMode)).also { it.start() }
        val netCfg = startNetDebug(s) ?: if (s.netProxy) net.start() else GuestRunner.NetConfig()
        runner.sdcardHost = sd
        val r = GuestRunner(paths, img.copy(settings = settings), netCfg.copy(glPath = if (s.gpu) "/dev/socket/gl" else null)).also {
            it.sdcardHost = sd
            it.singleTouch = singleTouchWanted(s)
            
            it.binderVerbose = File(paths.bin, "binder.verbose").exists()
            it.userQemuArgs = s.qemuArgs
        }
        runnerRef = r
        Keeper.hold(ctx, img.name)

        watchFb0()
        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        everBooted = false; zygoteRestarts = 0; imeRetried = false; heapAbortRetries = 0
        bootAnimCheckRunning.set(false); bootAnimFixes = 0; peripheralClaimReleased = false
        touchWizMediaStarted = false
        noteBoot.interrupted()
        pendingNote = ImageStore.get(ctx, img.id)?.oneTimeNote ?: img.oneTimeNote
        screenUpAt = 0; zygoteCrashes.clear()
        setState(State.BOOTING)

        
        paths.binderSock.delete()
        paths.hwbinderSock.delete()
        paths.vndbinderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")
        if (engine == Engine.N64 && kernel.treble) {
            waitFor("hwbinder socket", 5_000) { paths.hwbinderSock.exists() }
            waitFor("vndbinder socket", 5_000) { paths.vndbinderSock.exists() }
        }

        
        
        if (engine == Engine.N64 && File(paths.root, kernel.linkerConfigTool).isFile) runCatching {
            apexInfoList()
            File(paths.root, "linkerconfig").mkdirs()
            
            
            val own = if (kernel.trebleLinkerConfig) props.privateCopy("linkerconfig", mapOf("ro.treble.enabled" to "true")) else null
            val (code, out) = r.run(listOf("/" + kernel.linkerConfigTool, "--target", "/linkerconfig"), 30_000,
                if (own != null) mapOf("AEMU64_PROPS_FILE" to own.absolutePath) else emptyMap())
            log("linkerconfig: exit $code, ld.config.txt=${File(paths.root, "linkerconfig/ld.config.txt").isFile}")
            if (code != 0 || !File(paths.root, "linkerconfig/ld.config.txt").isFile) log("linkerconfig: exit $code ${out.trim().let { if (it.length > 700) it.take(400) + " ... " + it.takeLast(300) else it }}")
        }

        r.derivedExports = emptyMap()
        if (engine == Engine.N64 && File(paths.root, "apex/com.android.sdkext/bin/derive_classpath").isFile) runCatching {
            val out = File(paths.root, "data/system/environ/classpath")
            out.parentFile?.mkdirs(); out.delete()
            
            
            
            
            
            
            
            
            
            
            val baklavaJars = baklavaClasspathJars()
            val fixed = if (baklavaJars.isEmpty()) 0 else patchBaklavaFragments()
            log("derive_classpath: sdk=${props.get("ro.build.version.sdk")} codename=${props.get("ro.build.version.codename")}, " +
                "${baklavaJars.size} jar(s) required Baklava in the fragments, $fixed requirement(s) rewritten")
            val (code, msg) = r.run(listOf("/apex/com.android.sdkext/bin/derive_classpath", "/data/system/environ/classpath"), 30_000)
            if (code != 0 || !out.isFile) log("derive_classpath: exit $code ${msg.trim().takeLast(300)}")
            else r.derivedExports = out.readLines().mapNotNull { l -> l.trim().split(' ', limit = 3).takeIf { it.size == 3 && it[0] == "export" }?.let { it[1] to it[2] } }.toMap()
            
            val exported = r.derivedExports.values.joinToString(":")
            val left = baklavaJars.filter { File(paths.root, it.removePrefix("/")).isFile && it !in exported }
            if (baklavaJars.isNotEmpty())
                log(if (left.isEmpty()) "derive_classpath: all ${baklavaJars.size} held-back jars are in the class paths"
                    else "✖ derive_classpath: still left out: ${left.joinToString()}; zygote will fail in SystemServiceRegistry")
        }.onFailure { log("✖ derive_classpath: ${it.javaClass.simpleName}: ${it.message}") }

        
        val primary64 = primaryZygoteIs64()
        if (ccRebuild) {
            val res = runCatching { CcBootImage.rebuild(paths.root, r, r.derivedExports, { props.get(it) }, ::log, is64 = primary64) }
                .getOrElse { CcBootImage.Result(false, it.message ?: it.toString()) }
            if (!res.ok) {
                log("art: falling back to the libart patch (${res.note})")
                applyNoReadBarrierFallback({ props.get(it) }, { k, v -> props.put(k, v) })
            }
        }
        
        
        if (bootImageOn && primary64) {
            val res = runCatching { CcBootImage.ensureScratch(paths.root, r, r.derivedExports, { props.get(it) }, ::log) }
                .getOrElse { CcBootImage.Result(false, it.message ?: it.toString()) }
            if (!res.ok) {
                
                
                log("art: no boot image (${res.note}); the guest runs without one")
                runWithoutBootImage { k, v -> props.put(k, v) }
            }
        }

        
        val basePlan = servicePlan().filter { it.name !in InitPlan.NEVER_START }.ifEmpty { InitPlan.fallback(img, paths.root) }
        
        val logoPlan = if (File(paths.bin, BootMediaServices.LEGACY_LOGO_OFF).exists()) basePlan
                       else BootMediaServices.withLegacyLogo(basePlan, paths.root, img.api)
        
        
        
        
        
        
        
        
        val plan = if (engine == Engine.N64 && img.arch.foreign && img.api in 23..32 && logoPlan.any { it.name == "keystore" } &&
            logoPlan.none { s -> isKeymasterHal(s.name) || isKeymasterHal(s.argv.firstOrNull().orEmpty()) }) {
            log("keymaster: the firmware has no keymaster HAL service, so the keystore daemon is not started (it would abort in every start); the stand-in serves \"android.security.keystore\"")
            logoPlan.filter { it.name != "keystore" }
        } else logoPlan
        if (plan.any { it.name == BootMediaServices.LEGACY_LOGO }) log("boot logo: playlogos1 will run (Samsung 2.x boot animation and sound)")
        var glDone = false
        val wifiOn = settings.wifi && wifiSupported && File(paths.root, WifiEmu.JAR_PATH).isFile
        if (settings.wifi && wifiSupported && !wifiOn) log("wifi: ${WifiEmu.JAR_NAME} is not installed (build guest-wifi/build.sh), the guest keeps its normal network")
        WifiEmu.write(paths.root, settings, wifiOn)
        if (wifiOn) log("wifi: emulated network \"${settings.wifiSsid}\" (${if (WifiEmu.secured(settings)) "WPA2" else "open"})")
        
        val stopAfter = if (engine == Engine.N64 && kernel.syncLog) runCatching { File(paths.root, "dev/.aemu-stop-after").readText().trim().toInt() }.getOrNull() else null
        if (stopAfter != null) log("stop-after $stopAfter of: " + plan.filter { !it.onDemand }.mapIndexed { i, s -> "$i ${s.name}" }.joinToString())
        var started = 0
        
        
        
        if (!stopping) {
            setAdbd(true)
            log("★ adbd initialized ${(System.currentTimeMillis() - bootAt) / 1000} s after start: adb shell is available before the system finishes booting")
        }
        for (svc in plan) {
            if (stopping) return
            if (svc.onDemand) { props.put("init.svc.${svc.name}", "stopped"); continue }
            if (stopAfter != null && started++ >= stopAfter) {
                log("stop-after $stopAfter: holding before ${svc.name}")
                Thread.sleep(60_000)
                log("stop-after $stopAfter: 60 s passed, alive: ${alive().joinToString()}")
                return
            }
            
            if (!glDone && (svc.name == "surfaceflinger" || svc.name == "zygote" || svc.name == "bootanim")) {
                glUp(); glDone = true
            }
            
            
            if (svc.name == "zygote" && !legacyFw && img.api >= 2 && engine == Engine.KK && File(paths.root, "system/framework/aemu-sensorhub.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-sensorhub") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-sensorhub", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-sensorhub.jar", "/system/bin",
                    "app.aemu.stub.SensorHubStub", "sensorhubservice"), optional = true))
            }
            
            
            
            
            
            if (svc.name == "zygote" && !legacyFw && img.api in 2..8 && engine == Engine.KK && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-a2dp") }?.let { runCatching { it.destroyForcibly() } }
                startStub(GuestService("aemu-a2dp", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin",
                    "app.aemu.stub.BtStub", "bluetooth_a2dp"), optional = true))
            }
            
            if (svc.name == "zygote" && engine == Engine.N64 && plan.any { it.name == "apexd" })
                waitFor("apexd", 15_000) { props.get("apexd.status") == "ready" }
            
            if (svc.name == "zygote" && !legacyFw && img.api >= (if (engine == Engine.N64) 9 else 5) && engine != Engine.GB && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                
                
                
                val btName = if (img.api >= 17) "bluetooth_manager" else "bluetooth"
                
                val bridgeJar = img.api == 19 && File(paths.root, "system/framework/aemu-bt.jar").isFile
                synchronized(procs) { procs.remove("aemu-bt") }?.let { runCatching { it.destroyForcibly() } }
                startStub(GuestService("aemu-bt", listOf(appProcess,
                    "-Djava.class.path=/system/framework/${if (bridgeJar) "aemu-bt.jar" else "aemu-stubs.jar"}", "/system/bin",
                    if (bridgeJar) "app.aemu.stub.BtService" else "app.aemu.stub.BtStub", btName), optional = true))
                
                
                
                if (!bridgeJar && img.api >= 21) {
                    val t0 = SystemClock.uptimeMillis()
                    waitFor("aemu-bt first start", 4_000) { SystemClock.uptimeMillis() - t0 > 2_500 || !alive().contains("aemu-bt") }
                }
            }
            
            
            if (svc.name == "zygote" && !legacyFw && wifiOn) {
                synchronized(procs) { procs.remove("aemu-wifi") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-wifi", listOf(appProcess,
                    "-Djava.class.path=/${WifiEmu.JAR_PATH}", "/system/bin", "app.aemu.stub.WifiStub"), optional = true))
            }
            
            
            if (svc.name == "surfaceflinger" && engine == Engine.N64 && kernel.binderVold && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-vold") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-vold", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.VoldStub") + (if (kernel.volumeAtUserStart) listOf("userstart") else emptyList()), optional = true))
            }
            
            if (svc.name == "zygote" && !legacyFw && !wifiOn && (if (engine == Engine.N64) kernel.registeredNetwork else img.api in 21..25) && engine != Engine.GB && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-net") }?.let { runCatching { it.destroyForcibly() } }
                startStub(GuestService("aemu-net", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.NetStub") + (if (engine == Engine.N64 && Kernel64.forApi(img.api).ethernetStandIn) listOf("ethernet") else emptyList()), optional = true))
            }
            if (svc.name == "zygote" && engine == Engine.N64 && kernel.apexStandIn && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                apexStandInList()
                synchronized(procs) { procs.remove("aemu-apex") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-apex", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.ApexStub"), optional = true))
            }
            
            if (svc.name == "zygote" && engine == Engine.N64 && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                val names = Kernel64.forApi(img.api).standInServices.filter { (name, user) ->
                    runCatching { String(File(paths.root, user.substringBefore('#')).readBytes(), Charsets.ISO_8859_1).contains(user.substringAfter('#', name)) }.getOrDefault(false) }.keys
                synchronized(procs) { procs.remove("aemu-standin") }?.let { runCatching { it.destroyForcibly() } }
                if (names.isNotEmpty()) startService(GuestService("aemu-standin", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.StandIn") + names, optional = true))
            }
            
            
            
            
            
            
            if (svc.name == "zygote" && engine == Engine.N64 && img.api in 23..32 &&
                File(paths.root, "system/framework/aemu-stubs.jar").isFile &&
                synchronized(procs) { procs.filter { (n, _) -> n.startsWith("keymaster") }.none { (_, p) -> p.isAlive } }) {
                log("keymaster: no keymaster HAL is running, so keystore cannot register; a stand-in answers the \"android.security.keystore\" calls")
                synchronized(procs) { procs.remove("aemu-keystore") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-keystore", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.StandIn", "android.security.keystore"), optional = true))
            }
            
            
            
            
            
            if (svc.name == "zygote" && engine == Engine.N64 && img.api >= 21 &&
                File(paths.root, "system/framework/aemu-stubs.jar").isFile &&
                synchronized(procs) { procs["healthd"]?.isAlive != true }) {
                log("healthd is not running: the battery service is served by the engine")
                synchronized(procs) { procs.remove("aemu-battery") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-battery", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.BatteryStub"), optional = true))
            }
            if (svc.name == BootMediaServices.LEGACY_LOGO) { LegacyLogoPatch.apply(paths.root, ::log); LegacyLogoPatch.provideHeadsetState(paths.root, ::log) }
            
            
            
            
            if (svc.name == "mediaserver" && img.api < 16) LegacyLogoPatch.provideHeadsetState(paths.root, ::log)
            if (svc.name == "zygote" && needsBatteryPropertiesGuard) ensureBatteryProperties(plan)
            startService(svc)
            svc.waitSocket?.let { sock -> waitFor("socket $sock", 20_000) { paths.socket(sock).exists() } }
            if (svc.delayMs > 0) Thread.sleep(svc.delayMs)
            if (svc.name == "surfaceflinger") startTouchWizBootMedia()
            if (svc.name == "installd") watchUserZeroLink()
        }
        if (!glDone) glUp()
        startTouchWizBootMedia() 
        log("system started: ${alive().joinToString()}")
        watchLegacyBoot()
        if (SetupWizardOption.runHelper(settings.skipSetupWizard,
                File(paths.root, "data/system/aemu-setup-skip.properties").isFile)) {
            val setupRunner = guestRunner
            Thread({
                if (!stopping) runCatching {
                    val (code, output) = setupRunner.run(listOf(appProcess,
                        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin",
                        "app.aemu.setup.SetupCtl", if (settings.skipSetupWizard) "skip" else "restore"), 180_000,
                        mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))
                    log("setup option: exit=$code ${output.trim()}")
                    
                    
                    
                    if (settings.skipSetupWizard) {
                        val seeded = guestShell(
                            "if [ -f /system/priv-app/ClockworkSettings/ClockworkSettings.apk ]; then " +
                                "content update --uri content://com.google.android.wearable.settings/bluetooth_mode --bind bluetooth_mode:i:1 2>&1; " +
                                "content query --uri content://com.google.android.wearable.settings/bluetooth_mode 2>&1; " +
                                "else echo not-a-clockwork-image; fi", 180_000)
                        log("wear: bluetooth_mode seed (non-alt=1): $seeded")
                    }
                }.onFailure { log("setup option failed: ${it.message}") }
            }, "setup-option").start()
        }
        if (GoogleAppsOption.runHelper(img.api, settings.disableGoogleApps,
                File(paths.root, "data/system/aemu-google-disabled.properties").isFile, recoveryMode, lowPowerBoot)) {
            val googleRunner = guestRunner
            Thread({
                if (!stopping) runCatching {
                    val (code, output) = googleRunner.run(listOf(appProcess,
                        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin", "app.aemu.setup.GoogleCtl",
                        if (settings.disableGoogleApps) "disable" else "restore"), 180_000,
                        mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))
                    log("Google-app option: exit=$code ${output.trim()}")
                }.onFailure { log("Google-app option failed: ${it.message}") }
            }, "google-app-option").start()
        }
        watchdog()
    }

    
    @Volatile private var user0Watcher: Thread? = null
    private fun watchUserZeroLink() {
        if (img.api < 14 || user0Watcher?.isAlive == true) return
        user0Watcher = Thread({
            val link = File(paths.root, "data/user/0").absolutePath
            while (!stopping) {
                runCatching {
                    val cur = android.system.Os.readlink(link)
                    if (cur != "../data") {
                        android.system.Os.remove(link)
                        android.system.Os.symlink("../data", link)
                        log("data/user/0: link was \"$cur\", restored ../data")
                    }
                }
                try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
            }
        }, "user0-link").apply { isDaemon = true; start() }
    }

    @Volatile private var runnerRef: GuestRunner? = null
    val guestRunner: GuestRunner get() = runnerRef ?: runner

    
    private fun primaryZygoteIs64(): Boolean {
        val argv0 = img.services.firstOrNull { it.name == "zygote" }?.argv?.firstOrNull()
        return if (argv0 != null) argv0.endsWith("app_process64") else File(paths.root, "system/bin/app_process64").isFile
    }

    
    private fun runWithoutBootImage(put: (String, String) -> Unit) {
        put("dalvik.vm.boot-image", "/system/framework/aemu-no-boot-image.art")
        put("dalvik.vm.image-dex2oat-flags", "--aemu-no-boot-image")
        log("art: boot image off: the guest runs without one (setting \"Use ART boot image\")")
    }

    
    private fun applyNoReadBarrierFallback(get: (String) -> String?, put: (String, String) -> Unit) {
        val n = LibArtReadBarrierPatch.install(paths.root)
        if (n > 0) log("art: boot image is built without a read barrier; libart accepts it now ($n library patched)")
        if (!LibArtReadBarrierPatch.isInstalled(paths.root)) {
            log("art: boot image is built without a read barrier and libart has no known check to switch off")
            return
        }
        
        
        
        
        
        
        put("dalvik.vm.execution-mode", "int:fast")
        put("dalvik.vm.usejit", "false")
        put("dalvik.vm.extra-opts", LibArtReadBarrierPatch.withInterpretOnly(get("dalvik.vm.extra-opts")))
        log("art: no read barrier in the boot image: runtime kept interpreter-only (-Xint, no JIT)")
        
        
        
        
        
        
        fun mb(v: String?) = v?.trim()?.removeSuffix("m")?.toIntOrNull() ?: 0
        val growth = mb(get("dalvik.vm.heapgrowthlimit")).takeIf { it > 0 } ?: 192
        put("dalvik.vm.heapstartsize", "${maxOf(mb(get("dalvik.vm.heapstartsize")), 32).coerceAtMost(growth)}m")
        put("dalvik.vm.heapminfree", "${maxOf(mb(get("dalvik.vm.heapminfree")), 8)}m")
        put("dalvik.vm.heapmaxfree", "${maxOf(mb(get("dalvik.vm.heapmaxfree")), 32)}m")
        log("art: fewer GCs while the boot image has no read barrier (heapstartsize ${get("dalvik.vm.heapstartsize")}, " +
            "minfree ${get("dalvik.vm.heapminfree")}, maxfree ${get("dalvik.vm.heapmaxfree")})")
    }

    
    private val binderdId: String by lazy { BinderGuard.binderdId(paths.nativeBin(img.engine.binderd)) }
    private val binderGuardDone = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var binderGuardAtBoot = false
    private var binderdLogLen = -1L

    
    private fun prepareBinderGuard() {
        if (engine == Engine.N64 || img.engine.binderd.isEmpty()) return
        BinderGuard.resetLog(paths.bin)
        binderGuardDone.set(false); binderdLogLen = -1L
        val on = BinderGuard.armed(paths.bin, binderdId)
        binderGuardAtBoot = on
        runner.guardArmed = on
        runnerRef?.guardArmed = on
        if (on) {
            val st = BinderGuard.state(paths.bin)
            log("· binder guard on: this firmware's binder daemon (${binderdId}) delivered bare objects before (since ${st?.since ?: "?"}), libaemubinderguard.so contains them")
        }
    }

    
    private fun checkBinderGuard() {
        if (engine == Engine.N64 || img.engine.binderd.isEmpty() || binderGuardDone.get()) return
        val f = paths.log("binderd")
        val len = f.length()
        if (!f.isFile || len == binderdLogLen) return
        binderdLogLen = len
        val run = QemuFallback.lastRun(f.readText(), "binderd")
        if (!BinderGuard.detect(run)) return
        if (!binderGuardDone.compareAndSet(false, true)) return
        val bare = BinderGuard.bareCount(run)
        val since = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val known = BinderGuard.known(paths.bin, binderdId)
        when {
            binderGuardAtBoot -> {
                val ev = BinderGuard.events(paths.bin)
                if (ev == 0) {
                    BinderGuard.markIneffective(paths.bin, binderdId, since, bare)
                    log("✖ binder: hung transaction again with the guard on, and the guard saw no foreign object (the daemon counts $bare bare replies): it is not the cause here, guard switched off for this daemon build")
                } else log("✖ binder: hung transaction although the guard cleared $ev foreign object(s) (daemon: $bare bare replies); see run/${BinderGuard.LOG}")
            }
            known != null -> log("✖ binder: bare-object replies and a hung transaction, but the guard was already ${if (known.ineffective) "found useless" else "tried"} for this daemon build (${binderdId}); not armed again")
            else -> {
                BinderGuard.arm(paths.bin, binderdId, since, bare)
                log("✖ binder: the daemon reports bare-object replies ($bare) and a hung transaction; remembered for this firmware (run/binder.guard), starting the system again with the binder guard")
                onPower?.invoke(true, "")
            }
        }
    }
    
    private fun startNetDebug(s: VmSettings): GuestRunner.NetConfig? {
        if (!s.netDebug) {
            runCatching { app.aemu.netdebug.GuestTrust.uninstall(paths.root, netDebug.ca) }
            dns.debugger = null
            return null
        }
        return runCatching {
            val qemu = engine != Engine.N64
            val ports = netDebug.start(qemuNames = qemu, dnsUdp = true)
            val done = app.aemu.netdebug.GuestTrust.install(paths.root, netDebug.ca, ::log)
            log("netdebug: CA installed in " + (done.joinToString().ifEmpty { "no trust store found" }))
            dns.debugger = netDebug
            if (qemu) GuestRunner.NetConfig(http80 = ports.http80, https443 = ports.https443, dns53 = ports.dnsUdp)
            else GuestRunner.NetConfig(netDbg = ports.header, netDns = ports.dnsUdp)
        }.onFailure { log("netdebug: cannot start: $it") }.getOrNull()
    }


    private fun startBinder() {
        prepareBinderGuard()
        val sock = paths.binderSock.absolutePath
        when (engine) {
            Engine.KK -> spawn("binderd", listOf(paths.nativeBin(engine.binderd).absolutePath, "-s", sock), emptyMap())
            Engine.N64 -> {
                val env = LinkedHashMap<String, String>()
                if (File(paths.bin, "binder.verbose").exists()) env["AEMU_BINDER_VERBOSE"] = "1"
                
                BinderProtocol.saved(paths.bin)?.let { env[BinderProtocol.ENV] = it.toString(); log("· binder protocol $it (learned from this firmware)") }
                val bin = paths.nativeBin(kernel.binderd).absolutePath
                spawn("binderd", listOf(bin, sock), env)
                if (kernel.treble) {
                    spawn("hwbinderd", listOf(bin, paths.hwbinderSock.absolutePath), env)
                    spawn("vndbinderd", listOf(bin, paths.vndbinderSock.absolutePath), env)
                    log("· Treble hwbinder and vndbinder daemons started")
                }
            }
            Engine.GB -> if (File(paths.bin, "binderd.kk").exists()) {
                
                spawn("binderd", listOf(paths.nativeBin(Engine.KK.binderd).absolutePath, "-s", sock), emptyMap())
            } else {
                Slot.start(ctx, VmSlots.binderSlotClass(slot), listOf("binderd", "-s", sock), emptyMap(), paths.log("binderd"))
                log("· binderd started (:binder service)")
            }
        }
    }

    private fun glUp() {
        if (!settings.gpu) { log("GPU bridge off, software rendering"); return }
        val (w, h) = if (engine == Engine.GB) 480 to 800 else settings.width to settings.height
        paths.glSock.parentFile?.mkdirs()
        paths.glSock.delete()
        when (engine) {
            Engine.N64 -> {
                log("native arm64: graphics are served by the engine's own HALs")
                
                if (kernel.gpuBridge32 != null && n64Has32) {
                    spawn("glserverd", listOf(paths.nativeBin(Engine.KK.glserverd).absolutePath,
                        "-s", paths.glSock.absolutePath, "-fb", File(paths.bin, "gl32.fb").absolutePath, "-w", "$w", "-h", "$h"), glServerEnv())
                    waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
                }
            }
            Engine.GB -> {
                Slot.start(ctx, VmSlots.glSlotClass(slot),
                    listOf("glserverd", "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath, "-w", "$w", "-h", "$h"),
                    emptyMap(), paths.log("glserverd"))
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
            Engine.KK -> {
                if (surface == null) waitFor("screen surface", 10_000) { surface != null }
                val sf = surface
                HostNative.relaxFdsanSafe()
                
                
                
                
                
                
                
                val mali = HostInfo.gpu().contains("mali")
                if (img.api < 21 && !mali && sf != null && GlBridge.start(sf, paths.glSock.absolutePath, paths.log("glbridge").absolutePath, w, h, false)) {
                    log("★ GPU bridge running in-app: frames render straight to the surface")
                    waitFor("GL bridge socket", 5_000) { paths.glSock.exists() }
                    return
                }
                log(when {
                    img.api >= 21 -> "GPU bridge: standalone glserverd"
                    mali -> "GPU bridge: standalone glserverd (Mali: the in-app bridge hands ES2 apps an ES 1.1 context)"
                    else -> "in-app GPU bridge failed, falling back to standalone glserverd"
                })
                spawn("glserverd", listOf(paths.nativeBin(engine.glserverdFor(HostInfo.gpu())).absolutePath,
                    "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath,
                    "-notify", paths.frameSock.absolutePath, "-w", "$w", "-h", "$h"), glServerEnv())
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
        }
    }

    
    private fun glServerEnv(): Map<String, String> {
        val buf = paths.nativeBin("libglbuf.so")
        return if (buf.isFile) mapOf("LD_PRELOAD" to buf.absolutePath) else emptyMap()
    }

    
    
    private fun isKeymasterHal(nameOrPath: String): Boolean = nameOrPath.contains("keymaster") && !nameOrPath.contains("wait_for_keymaster")

    private val engineFiles: Set<String> by lazy { listOf("engines/n64-${kernel.tag}", kernel.gsiAssets).flatMap { runCatching { EngineAssets.layerNames(ctx, it) }.getOrNull().orEmpty() }.toSet() }

    
    private fun ensureMediaProfiles() = runCatching {
        val want = props.get("media.settings.xml")?.trim().orEmpty()
        if (!want.startsWith("/")) return@runCatching
        val f = File(paths.root, want.removePrefix("/"))
        if (f.exists()) return@runCatching        
        val have = listOf("vendor/etc/media_profiles_V1_0.xml", "vendor/etc/media_profiles.xml",
            "system/etc/media_profiles_V1_0.xml", "system/etc/media_profiles.xml").map { File(paths.root, it) }.firstOrNull { it.isFile }
        if (have != null) {
            f.parentFile?.mkdirs()
            runCatching { android.system.Os.remove(f.absolutePath) }
            android.system.Os.symlink(f.parentFile!!.toPath().relativize(have.toPath()).toString(), f.absolutePath)
            log("media: $want was missing, now a link to ${have.relativeTo(paths.root).path}")
        } else {
            props.put("media.settings.xml", "")
            log("media: $want was missing and the firmware has no media_profiles.xml: libmedia's defaults")
        }
    }

    private val n64Has32: Boolean get() = paths.nativeBin(img.arch.n64Lib32.ifEmpty { "libqemu_arm.so" }).canExecute() &&
            File(paths.root, "system/bin/app_process32").isFile

    val glInApp: Boolean get() = engine == Engine.KK && GlBridge.running()

    
    private fun writeLpw39ImageClasses(): Boolean = runCatching {
        val fw = File(paths.root, "system/framework/framework.jar")
        val out = File(paths.root, "system/framework/aemu-imgclasses.zip")
        val keep = listOf("java.", "javax.", "libcore.", "dalvik.", "sun.", "org.apache.harmony.", "com.android.org.",
            "org.json.", "org.w3c.", "org.xml.", "org.kxml2.", "org.xmlpull.", "org.apache.xml.", "junit.")
        val text = java.util.zip.ZipFile(fw).use { z ->
            val e = z.getEntry("preloaded-classes") ?: return@runCatching false
            z.getInputStream(e).bufferedReader().readText()
        }
        val lines = text.lineSequence().map { it.trim() }.filter { l -> l.isNotEmpty() && !l.startsWith("#") && keep.any { l.startsWith(it) } }.toList()
        if (lines.isEmpty()) return@runCatching false
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zo ->
            zo.putNextEntry(java.util.zip.ZipEntry("preloaded-classes"))
            zo.write((lines.joinToString("\n") + "\n").toByteArray())
            zo.closeEntry()
        }
        log("LPW39: boot image classes limited to the core libraries (${lines.size} of the preloaded classes)")
        true
    }.getOrDefault(false)

    
    private val isLpw39: Boolean by lazy { img.buildId.trim().equals("LPW39", ignoreCase = true) }

    
    private val appProcess: String get() = AppProcess.path(paths.root)

    
    private val stubServices = java.util.concurrent.ConcurrentHashMap<String, GuestService>()

    private fun startStub(svc: GuestService) {
        stubServices[svc.name] = svc
        startService(svc)
    }

    
    private fun servicePlan(): List<GuestService> =
        
        LegacyFirmware.bootPlan(paths.root)?.takeIf { it.isNotEmpty() } ?: genericServicePlan()

    
    private fun zygoteRcFilter(root: File, props: Map<String, String>, files: List<File>): List<File> {
        if (files.none { it.name.startsWith("init.zygote") }) return files
        val wanted = listOfNotNull(
            props["ro.zygote"],
            if (engine != Engine.N64) null else if (isPure32Tree(root)) "zygote32" else if (n64Has32) "zygote64_32" else "zygote64",
            if (engine == Engine.N64) "zygote64_32" else null,
        ).map { "init.$it.rc" }
        val keep = wanted.firstOrNull { w -> files.any { it.name == w } } ?: return files
        return files.filter { !it.name.startsWith("init.zygote") || it.name == keep }
    }

    private fun genericServicePlan(): List<GuestService> = (runCatching {
        val root = paths.root
        val props = HashMap<String, String>()
        for (n in listOf("default.prop", "system/build.prop")) runCatching {
            File(root, n).forEachLine { l -> val i = l.indexOf('='); if (i > 0 && !l.startsWith("#")) props[l.substring(0, i).trim()] = l.substring(i + 1).trim() }
        }
        val rcFiles = (zygoteRcFilter(root, props, root.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") } ?: emptyList()).sortedBy { if (it.name == "init.rc") 0 else 1 }) +
            InitPlan.hwRcFiles(root, props) +
            
            listOf("system/etc/init", "system_ext/etc/init", "system/system_ext/etc/init", "vendor/etc/init").flatMap { d -> File(root, d).listFiles()?.filter { it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList() } +
            
            (File(root, "apex").listFiles()?.sortedBy { it.name }?.flatMap { m -> File(m, "etc").listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList() } ?: emptyList())
        InitPlan.plan(InitPlan.parse(rcFiles, props), img.api, root).takeIf { p -> p.any { it.name == "zygote" } }
    }.getOrNull() ?: img.services.ifEmpty { InitPlan.fallback(img, paths.root) }).map { InitPlan.normalHealthd(it) }


    private fun startService(svc0: GuestService, propsFile: File? = null) {
        
        
        
        fun is64(path: String?): Boolean = path != null && runCatching {
            File(paths.root, path.removePrefix("/")).inputStream().use { i -> ByteArray(5).also { i.read(it) }[4].toInt() == 2 }
        }.getOrDefault(false)
        val svc = if (svc0.name == "zygote_secondary" && is64(svc0.argv.firstOrNull()) &&
            is64(img.services.firstOrNull { it.name == "zygote" }?.argv?.firstOrNull()) &&
            File(paths.root, "system/bin/app_process32").isFile)
            svc0.copy(argv = listOf("/system/bin/app_process32") + svc0.argv.drop(1)) else svc0
        if (!File(paths.root, svc.argv.first().removePrefix("/")).isFile) {
            log("· ${svc.name}: ${svc.argv.first()} missing, skipping")
            return
        }
        
        
        if (engine == Engine.N64 && kernel.treble && TreeFixer.TEE_KEYMINT_SERVICE
                .matches(svc.argv.first().substringAfterLast('/'))) {
            log("· ${svc.name}: TEE KeyMint, skipped (keystore2 uses its software KeyMint)")
            return
        }
        
        
        
        if (engine == Engine.N64 && kernel.treble && TreeFixer.BOARD_COMPOSER_SERVICE.matches(svc.argv.first().substringAfterLast('/')) &&
            listOf("vendor/bin/hw", "system/vendor/bin/hw").any { File(paths.root, "$it/android.hardware.graphics.composer@2.1-service").isFile }) {
            log("· ${svc.name}: the board's composer, skipped (the engine's composer@2.1 service serves IComposer)")
            return
        }
        
        
        if (engine == Engine.N64 && kernel.treble && TreeFixer.BOARD_POWER_SERVICE.matches(svc.argv.first().substringAfterLast('/')) &&
            listOf("vendor/bin/hw", "system/vendor/bin/hw").any { d ->
                File(paths.root, "$d/android.hardware.power-service.aemu").isFile || File(paths.root, "$d/android.hardware.power@1.0-service").isFile } &&
            svc.argv.first().substringAfterLast('/') != "android.hardware.power-service.aemu" &&
            svc.argv.first().substringAfterLast('/') != "android.hardware.power@1.0-service") {
            log("· ${svc.name}: the board's power HAL, skipped (the engine's power service serves IPower)")
            return
        }
        
        
        if (engine == Engine.N64 && kernel.treble && TreeFixer.BOARD_AIDL_AUDIO_SERVICE.matches(svc.argv.first().substringAfterLast('/')) &&
            listOf("vendor/bin/hw", "system/vendor/bin/hw").any { File(paths.root, "$it/android.hardware.audio.service").isFile }) {
            log("· ${svc.name}: the board's AIDL audio service, skipped (the engine's audio HAL serves audio)")
            return
        }
        
        
        if (engine == Engine.N64 && svc.argv.first().substringAfterLast('/').startsWith("android.hardware.gnss")) {
            log("· ${svc.name}: GNSS HAL, skipped")
            return
        }
        if (engine == Engine.N64 && ((svc.name == "zygote_secondary" && !n64Has32) || svc.argv.first().substringAfterLast('/') in N64_SKIP)) return
        if (engine == Engine.N64 && !paths.nativeBin(img.arch.n64Lib32.ifEmpty { "libqemu_arm.so" }).canExecute() && !runsNatively(File(paths.root, svc.argv.first().removePrefix("/")))) {
            log("· ${svc.name}: 32-bit program, skipped by the native arm64 engine")
            return
        }
        val extra = LinkedHashMap<String, String>()
        for ((sock, mode) in svc.sockets) extra["DHD_SOCK_$sock"] = "${paths.socket(sock).absolutePath},${if (engine == Engine.N64) mode else mode.substringBefore(',')}"
        if (svc.uid != 0) { extra["DHD_UID"] = svc.uid.toString(); extra["DHD_GID"] = svc.gid.toString() }
        var props = propsFile
        
        if (svc.name == "mediaserver") props = this.props.privateCopy("media", mapOf("ro.kernel.qemu" to "1"))
        
        
        val argv = if (svc.name == "zygote" && !legacyFw && img.api >= 2 && !settings.jit && img.runtime == "dalvik")
            svc.argv.take(1) + "-Xint:fast" + svc.argv.drop(1) else svc.argv
        if (svc.name == "zygote") log("zygote argv: " + argv.joinToString(" "))
        
        
        
        if (svc.name in VmSettings.straceNames(settings.straceServices) || File(paths.bin, "strace.${svc.name}").exists())
            extra["QEMU_STRACE"] = "1"
        
        
        
        
        if (engine == Engine.N64 && img.arch.foreign) extra["QEMU_LOG"] = "unimp,guest_errors"
        if (engine == Engine.N64 && props != null) extra["AEMU64_PROPS_FILE"] = props.absolutePath
        
        
        if (engine == Engine.N64 && kernel.tag == "26" && svc.name == "keymaster-3-0") extra["LD_DEBUG"] = "2"
        
        if (engine == Engine.N64) kernel.ownVndkDir?.let { dir ->
            val name = svc.argv.first().removePrefix("/").replace('/', '#')
            if (name.startsWith("vendor#bin#") && name !in kernel.firmwareOwn && File(paths.root, dir).isDirectory &&
                name in engineFiles && runsNatively(File(paths.root, svc.argv.first().removePrefix("/")))) extra["AEMU64_LIBPATH"] = "/$dir"
        }
        if (engine == Engine.N64) kernel.ownLibDirs[svc.argv.first().removePrefix("/").replace('/', '#')]?.let { dir ->
            
            val dirs = dir.split(':').filter { File(paths.root, it).isDirectory }
            if (dirs.isNotEmpty()) extra["AEMU64_LIBPATH"] = dirs.joinToString(":") { "/$it" }
            
            
            
            dirs.map { "$it/libaemu_hidlcompat.so" }.firstOrNull { File(paths.root, it).isFile }?.let { extra["LD_PRELOAD"] = "/$it" }
        }
        spawn(svc.name, guestRunner.cmdline(argv, props), guestRunner.env(extra, svc.argv.first()))
    }

    
    private fun runsNatively(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val b = ByteArray(20)
            val n = i.read(b)
            
            val x64 = n == 20 && b[0] == 0x7f.toByte() && b[4] == 2.toByte() && (b[18].toInt() and 0xff) == GuestArch.EM_X86_64
            if (x64) img.arch.n64Lib64?.let { paths.nativeBin(it).canExecute() } == true
            else n >= 5 && ((b[0] == 0x7f.toByte() && b[4] == 2.toByte()) || b[0] == 0x23.toByte())
        }
    }.getOrDefault(false)

    private fun spawn(name: String, cmd: List<String>, env: Map<String, String>) {
        val log = paths.log(name)
        
        
        
        val cwd = if (name == "netd") paths.root else paths.bin
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(if (env.isEmpty()) mapOf("PATH" to "/system/bin") else env)
        
        
        val direct = name == "zygote" && img.api >= 24
        if (direct) {
            runCatching { log.appendText("\n=== $name ${stamp()} ===\n") }
            pb.redirectInput(File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }
        
        
        
        
        
        
        val traceSpawner = name == "hyos_spawner"
        val traceMarker = File(paths.root, if (traceSpawner) "dev/.aemu-trace-all" else "dev/.aemu-trace")
        val traceZygote = (name == "zygote" || traceSpawner) && engine == Engine.N64 &&
            runCatching { File(android.os.Environment.getExternalStorageDirectory(), "AEmulator/trace-native").exists() }.getOrDefault(false)
        if (traceZygote) runCatching { if (!traceSpawner) File(paths.root, "dev/.aemu-trace.log").delete(); traceMarker.createNewFile() }
        val p = pb.start()
        if (traceZygote) { runCatching { Thread.sleep(1500) }; runCatching { traceMarker.delete() }; log("native syscall trace of $name on: dev/.aemu-trace.log") }
        synchronized(procs) { procs[name] = p }
        pidOf(p)?.let { pgids.add(it) }
        log("· $name started")
        if (name == "zygote") { watchSocketDeletes(); if (img.api >= 37) zygoteCompat.start(::log); if (img.api >= 29) tombstonedTrace.start(::log) }
        
        
        props.put("init.svc.$name", "running")
        Thread({
            if (!direct) runCatching {
                OutputStreamWriter(FileOutputStream(log, true)).use { w ->
                    w.write("\n=== $name ${stamp()} ===\n")
                    p.inputStream.bufferedReader().forEachLine { w.write(it); w.write("\n"); w.flush() }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!stopping) log("· $name exited, code $code")
            if (!stopping && code != 0) learnBinderProtocol(name, log)
            if (!stopping && code != 0 && !QemuFallback.enabled(paths) &&
                runCatching { QemuFallback.isLinkFailure(QemuFallback.lastRun(log.readText(), name)) }.getOrDefault(false))
                fallbackToPatchedQemu(name)
            if (!stopping && name == "surfaceflinger" && HwcFallback.isCrashExit(code)) fallbackWithoutHwc()
            
            synchronized(procs) { if (procs[name].let { it == null || it === p }) props.put("init.svc.$name", "stopped") }
        }, "log-$name").apply { isDaemon = true; start() }
    }

    @Volatile private var socketDirObserver: android.os.FileObserver? = null

    
    private fun watchSocketDeletes() {
        runCatching {
            socketDirObserver?.stopWatching()
            val dir = File(paths.root, "dev/socket")
            if (!dir.isDirectory) return
            val mask = android.os.FileObserver.DELETE or android.os.FileObserver.MOVED_FROM
            @Suppress("DEPRECATION")
            val o = object : android.os.FileObserver(dir.path, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || stopping) return
                    
                    if (path.startsWith("zygote") || path.startsWith("usap_pool"))
                        log("dev/socket: removed $path")
                }
            }
            o.startWatching()
            socketDirObserver = o
        }
    }

    
    @Volatile private var x86Proto8AtBoot = false

    private val binderProtocolRetried = java.util.concurrent.atomic.AtomicBoolean(false)

    
    private fun learnBinderProtocol(name: String, log: File) {
        
        
        if (engine != Engine.N64 && img.arch == GuestArch.X86) {
            if (!x86Proto8AtBoot && BinderProtocol.x86Learned8(paths.bin) && binderProtocolRetried.compareAndSet(false, true)) {
                log("✖ $name: the x86 guest refused binder protocol 7; the translator remembered 8 (run/binder.proto), starting the system again")
                onPower?.invoke(true, "")
            }
            return
        }
        if (engine != Engine.N64) {
            
            
            val (driver, wanted) = runCatching { BinderProtocol.parseMismatch(QemuFallback.lastRun(log.readText(), name)) }.getOrNull() ?: return
            if (wanted != 8 || !img.abi.startsWith("armeabi") || !img.settings.translate32 || !paths.nativeBin("libqemu_arm.so").canExecute()) return
            if (!binderProtocolRetried.compareAndSet(false, true)) return
            BinderProtocol.save(paths.bin, wanted)
            log("✖ $name: binder driver speaks protocol $driver, the firmware wants $wanted; remembered, starting the system again on the native engine (qemu-arm)")
            onPower?.invoke(true, "")
            return
        }
        val (driver, wanted) = runCatching { BinderProtocol.parseMismatch(QemuFallback.lastRun(log.readText(), name)) }.getOrNull() ?: return
        if (BinderProtocol.saved(paths.bin) == wanted) {
            log("✖ $name still wants binder protocol $wanted although ${BinderProtocol.ENV}=$wanted was set: the guest runner of this Android version (libaemu64_${kernel.tag}) does not honour ${BinderProtocol.GUEST_ENV} (only the 8.x kernels 26/27 translate protocol 7)")
            return
        }
        if (!binderProtocolRetried.compareAndSet(false, true)) return
        BinderProtocol.save(paths.bin, wanted)
        log("✖ $name: binder driver speaks protocol $driver, the firmware wants $wanted; remembered, starting the system again with protocol $wanted")
        onPower?.invoke(true, "")
    }

    private val hwcFallbackStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    
    private fun fallbackWithoutHwc() {
        if (bootDoneAt != 0L || everBooted || !HwcFallback.installed(paths.root)) return
        if (!hwcFallbackStarted.compareAndSet(false, true)) return
        if (!HwcFallback.disable(paths)) { log("surfaceflinger: cannot remember the composer choice"); return }
        log("✖ surfaceflinger crashed with the stand-in hwcomposer; it is left out of this image from now on, starting the system again")
        HwcFallback.remove(paths.root)
        onPower?.invoke(true, "")
    }

    private val qemuFallbackStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val swGlFallbackStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    
    private fun softwareGlCrashed(): Boolean = runCatching {
        val last = paths.log("zygote").readText().substringAfterLast("=== zygote")
        (last.contains("SIGSEGV") || last.contains("Segmentation fault")) && last.contains("libGLES_android.so")
    }.getOrDefault(false)

    
    private fun fallbackToPatchedQemu(service: String) {
        if (!qemuFallbackStarted.compareAndSet(false, true)) return
        if (!paths.nativeBin(engine.qemuXperiaPlay).canExecute()) { log("$service: ${engine.qemuXperiaPlay} is not available"); return }
        if (!QemuFallback.enable(paths)) { log("$service: cannot remember the translator choice"); return }
        log("$service: guest linker reported \"${QemuFallback.ERROR}\", restarting with ${engine.qemuXperiaPlay}")
        onPower?.invoke(true, "")
    }

    private fun alive(): List<String> = synchronized(procs) { procs.filterValues { it.isAlive }.keys.toList() }

    
    val logoFormat: Int get() = runCatching { File(paths.bin, "logo-format").readText().trim().toInt() }.getOrNull()?.takeIf { it in 0..2 } ?: 2

    
    fun legacyLogoRunning(): Boolean = bootDoneAt == 0L && synchronized(procs) { procs[BootMediaServices.LEGACY_LOGO]?.isAlive == true }

    
    private val isSamsungImage: Boolean by lazy {
        img.brand.equals("samsung", ignoreCase = true) || img.skin.contains("touchwiz", ignoreCase = true)
    }
    private val needsBatteryPropertiesGuard: Boolean by lazy { img.api >= 21 && isSamsungImage }

    
    private fun ensureBatteryProperties(plan: List<GuestService>) {
        
        val hd = plan.firstOrNull { it.name == "healthd" }?.let { InitPlan.normalHealthd(it) } ?: return
        val running = synchronized(procs) { procs["healthd"] }?.takeIf { it.isAlive } ?: return
        log("healthd: command ${hd.argv.joinToString(" ")}, pid ${pidOf(running) ?: "?"}")
        for (attempt in 1..3) {
            if (stopping) return
            val until = SystemClock.uptimeMillis() + 6_000
            var verdict: Boolean? = null
            var lastCheck = ""
            do {
                val out = runCatching {
                    guestRunner.run(listOf("/system/bin/service", "check", "batteryproperties"), 10_000).second
                }.getOrDefault("")
                lastCheck = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING: linker") } ?: "(no output)"
                verdict = when {
                    out.contains("not found", true) -> false
                    out.contains("found", true) -> true
                    else -> null
                }
                if (verdict != false) break
                Thread.sleep(500)
            } while (SystemClock.uptimeMillis() < until && !stopping)
            log("healthd: service check batteryproperties -> \"${lastCheck.take(160)}\" ($attempt/3)")
            if (verdict == true) {
                if (attempt > 1) log("healthd: batteryproperties registered after restart ($attempt/3)")
                File(paths.bin, "strace.healthd").delete()
                return
            }
            if (verdict == null) { log("healthd: batteryproperties cannot be queried (no guest 'service' tool), continuing"); return }
            val alive = synchronized(procs) { procs["healthd"]?.isAlive == true }
            log("⚠ healthd (running=$alive) has not registered batteryproperties ($attempt/3)")
            if (attempt == 3) break
            
            runCatching { File(paths.bin, "strace.healthd").createNewFile() }
            synchronized(procs) { procs.remove("healthd") }?.let { runCatching { it.destroyForcibly() } }
            Thread.sleep(300)
            runCatching { startService(hd) }
        }
        File(paths.bin, "strace.healthd").delete()
        log("✖ batteryproperties is still missing, system_server will fail in BatteryService; healthd command: ${hd.argv.joinToString(" ")}")
    }

    private fun waitFor(what: String, ms: Long, cond: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until && !stopping) {
            if (cond()) return true
            Thread.sleep(100)
        }
        val ok = cond()
        if (!ok) log("⚠ timed out waiting for: $what")
        return ok
    }

    

    
    @Volatile var onPower: ((reboot: Boolean, reason: String) -> Unit)? = null
    @Volatile private var powerHandled = false

    private fun powerRequest(v: String) {
        if (powerHandled || v.isEmpty()) return
        powerHandled = true
        val reboot = v.startsWith("reboot")
        val reason = v.substringAfter(',', "")
        log("guest requested ${if (reboot) "reboot" else "power off"}${if (reason.isNotEmpty()) " ($reason)" else ""}")
        if (recoveryEarlyExit()) {
            
            recoveryEnded("recovery asked to ${if (reboot) "reboot" else "power off"} ${(System.currentTimeMillis() - bootAt) / 1000.0} s after it started",
                "it asked to ${if (reboot) "reboot" else "power off"} right away")
            return
        }
        if (reason == "recovery" && !RecoveryImage.installed(paths)) log("no recovery installed, booting the system again")
        onPower?.invoke(reboot, reason)
    }

    
    private val UNUSED_FIELD_TAG = (15 shl 3) or 2

    private fun classpathFragments(): List<File> =
        (listOf(File(paths.root, "system/etc/classpaths")) +
            File(paths.root, "apex").listFiles().orEmpty().map { File(it, "etc/classpaths") })
            .flatMap { d -> d.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".pb") } }

    private fun baklavaClasspathJars(): List<String> {
        val out = LinkedHashSet<String>()
        for (f in classpathFragments()) {
            val b = runCatching { f.readBytes() }.getOrNull() ?: continue
            collectBaklavaJars(b, 0, b.size, 0, out, null)
        }
        return out.toList()
    }

    
    private fun patchBaklavaFragments(): Int {
        var n = 0
        for (f in classpathFragments()) {
            val b = runCatching { f.readBytes() }.getOrNull() ?: continue
            val fixed = IntArray(1)
            collectBaklavaJars(b, 0, b.size, 0, LinkedHashSet(), fixed)
            if (fixed[0] > 0 && runCatching { f.writeBytes(b) }.isSuccess) n += fixed[0]
        }
        return n
    }

    private fun collectBaklavaJars(b: ByteArray, from: Int, to: Int, depth: Int, out: MutableSet<String>, fixed: IntArray?) {
        var i = from
        fun varint(): Long {
            var v = 0L; var sh = 0
            while (i < to && sh < 64) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) return v }
            return -1
        }
        val fields = ArrayList<IntArray>()
        while (i < to) {
            val tagAt = i
            val tag = varint(); if (tag < 0) return
            when ((tag and 7).toInt()) {
                0 -> if (varint() < 0) return
                1 -> i += 8
                5 -> i += 4
                2 -> { val n = varint(); if (n < 0 || i + n > to) return; fields.add(intArrayOf(i, i + n.toInt(), tagAt)); i += n.toInt() }
                else -> return
            }
        }
        val strings = fields.map { String(b, it[0], it[1] - it[0], Charsets.ISO_8859_1) }
        val marked = strings.indices.filter { strings[it] == "Baklava" || strings[it] == "0000000" }
        if (marked.isNotEmpty()) {
            strings.filter { it.startsWith("/") && it.endsWith(".jar") }.forEach { out.add(it) }
            if (fixed != null) for (k in marked) {
                
                val at = fields[k][2]; val tag = b[at].toInt() and 0xff
                if (tag == UNUSED_FIELD_TAG || tag >= 0x80 || (tag and 7) != 2) continue
                b[at] = UNUSED_FIELD_TAG.toByte(); fixed[0]++
            }
        }
        if (depth < 3) for (fl in fields) if (fl[1] > fl[0]) collectBaklavaJars(b, fl[0], fl[1], depth + 1, out, fixed)
    }

    
    private fun apexInfoList() {
        val apex = File(paths.root, "apex")
        val mods = apex.listFiles()?.filter { it.isDirectory && '@' !in it.name && File(it, "apex_manifest.pb").isFile }?.sortedBy { it.name } ?: return
        if (mods.isEmpty()) return
        val pre = File(paths.root, "system/apex").list() ?: emptyArray()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<apex-info-list>\n")
        for (m in mods) {
            
            val b = File(m, "apex_manifest.pb").readBytes()
            var i = 0; var version = 1L
            fun varint(): Long { var v = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) break }; return v }
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) { 0 -> { val v = varint(); if (tag shr 3 == 2) version = v }; 2 -> i += varint().toInt(); 1 -> i += 8; 5 -> i += 4; else -> i = b.size }
            }
            val file = pre.firstOrNull { it == m.name || it.substringBeforeLast('.') == m.name || it.startsWith(m.name + ".") } ?: m.name
            sb.append("  <apex-info moduleName=\"${m.name}\" modulePath=\"/system/apex/$file\" preinstalledModulePath=\"/system/apex/$file\" versionCode=\"$version\" versionName=\"\" isFactory=\"true\" isActive=\"true\" />\n")
        }
        sb.append("</apex-info-list>\n")
        File(apex, "apex-info-list.xml").writeText(sb.toString())
    }

    private fun clientComposition() {
        runCatching { guestShell("service call SurfaceFlinger 1008 i32 1", 15_000) }
    }

    private fun onProp(k: String, v: String) {
        if (k == "sys.powerctl") { powerRequest(v); return }
        if (k == "service.bootanim.exit") {
            for ((start, name) in BootMediaServices.triggers(paths.root, k, v)) onCtl(start, name)
            if (v == "1") onCtl(false, "bootanim")
        }
        
        if (k == "service.bootanim.exit" && v == "0" && engine == Engine.N64 && kernel.clientComposition)
            Thread { clientComposition() }.start()
        if (k == "service.bootanim.exit" && v == "1" && screenUpAt == 0L) screenUpAt = System.currentTimeMillis()
        if ((k == "sys.boot_completed" || k == "dev.bootcomplete") && v == "1" && bootDoneAt == 0L)
            completeBoot("guest reported boot completion")
    }

    
    private fun forceBootFlagIfStuck() {
        val gen = bootAt
        Thread({
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                if (stopping || bootDoneAt != 0L || bootAt != gen) return@Thread
                Thread.sleep(500)
            }
            if (stopping || bootDoneAt != 0L || bootAt != gen) return@Thread
            if (props.get("sys.boot_completed") == "1") { completeBoot("sys.boot_completed was already set"); return@Thread }
            log("boot flag: sys.boot_completed not set 20 s after the zygotes got --boot-completed (finishBooting stalled), applying it from the host")
            props.put("sys.boot_completed", "1")
            props.put("dev.bootcomplete", "1")
            completeBoot("boot flag applied by the host")
        }, "boot-flag-fallback").apply { isDaemon = true; start() }
    }

    
    @Synchronized private fun completeBoot(why: String) {
        if (bootDoneAt != 0L) return
        if (!recoveryMode && !lowPowerBoot) noteBoot.reported(SystemClock.elapsedRealtime())
        bootDoneAt = System.currentTimeMillis()
        everBooted = true
        log("★ $why in ${(bootDoneAt - bootAt) / 1000} s (UI not verified)")
        setState(State.RUNNING)
        Thread { afterBoot() }.start()
    }

    @Volatile private var legacyBootObservers: List<android.os.FileObserver> = emptyList()

    private fun stopLegacyBootWatch() {
        legacyBootObservers.forEach { runCatching { it.stopWatching() } }
        legacyBootObservers = emptyList()
    }

    
    private fun watchLegacyBoot() {
        if (recoveryMode || lowPowerBoot || !LegacyFirmware.isPreRelease(img.release)) return
        stopLegacyBootWatch()
        val observers = ArrayList<android.os.FileObserver>()
        fun fire(what: String) {
            if (bootDoneAt != 0L || stopping) return
            if (alive().none { it != "binderd" }) return    
            completeBoot("pre-1.0 build: $what (BOOT_COMPLETED was delivered)")
            stopLegacyBootWatch()
        }
        
        val dir = File(paths.root, LegacyFirmware.BOOT_SCAN_DIR)
        if (dir.isDirectory) {
            @Suppress("DEPRECATION")
            observers += object : android.os.FileObserver(dir.absolutePath, android.os.FileObserver.OPEN or android.os.FileObserver.ACCESS) {
                override fun onEvent(event: Int, path: String?) = fire("the media scanner opened /${LegacyFirmware.BOOT_SCAN_DIR}")
            }
        } else log("pre-1.0 boot watch: ${LegacyFirmware.BOOT_SCAN_DIR} is not in this tree")
        
        
        
        runCatching {
            val cardAndroid = File(paths.root, "sdcard/android")
            if (!cardAndroid.isDirectory) cardAndroid.mkdirs()
            if (cardAndroid.isDirectory) {
                @Suppress("DEPRECATION")
                observers += object : android.os.FileObserver(cardAndroid.absolutePath, android.os.FileObserver.CREATE or android.os.FileObserver.OPEN) {
                    override fun onEvent(event: Int, path: String?) { if (path == "media.db") fire("the media scanner opened /sdcard/android/media.db") }
                }
            }
        }.onFailure { log("pre-1.0 boot watch: cannot watch /sdcard/android: ${it.message}") }
        if (observers.isEmpty()) { log("pre-1.0 boot watch: nothing to watch, no boot-completed signal to wait for"); return }
        observers.forEach { it.startWatching() }
        legacyBootObservers = observers
        log("pre-1.0 boot watch: waiting for the guest to open /${LegacyFirmware.BOOT_SCAN_DIR} or /sdcard/android/media.db (media scanner, after BOOT_COMPLETED)")
    }

    @Volatile private var touchWizMediaStarted = false

    
    private fun startTouchWizBootMedia() {
        if (touchWizMediaStarted || stopping || recoveryMode || lowPowerBoot) return
        touchWizMediaStarted = true
        runCatching {
            for (name in BootMediaServices.touchWizBootMedia(paths.root, img.skin)) {
                
                if (name == "samsungani" && img.api < 16) { log("TouchWiz boot media: samsungani skipped on API ${img.api}"); continue }
                val key = if (name == "playsound") name else "bootanim"
                if (synchronized(procs) { procs[key]?.isAlive == true }) continue
                val def = BootMediaServices.resolve(paths.root, name) ?: continue
                Thread { runCatching { startService(def) } }.start()
                log("TouchWiz boot media: $name started")
            }
        }.onFailure { log("TouchWiz boot media: ${it.message}") }
    }

    
    private fun apexStandInList() = runCatching {
        val apex = File(paths.root, "apex")
        val out = File(apex, ".aemu-apexes")
        val parked = File(paths.root, "system/.aemu-parked").listFiles()?.filter { it.name.endsWith(".apex") || it.name.endsWith(".capex") }?.sortedBy { it.name } ?: emptyList()
        if (out.isFile && out.lastModified() >= (parked.maxOfOrNull { it.lastModified() } ?: 0L) && out.length() > 0) return@runCatching
        fun fields(b: ByteArray): Pair<String?, Long> {
            var i = 0; var name: String? = null; var version = 1L
            fun varint(): Long { var v = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) break }; return v }
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) {
                    0 -> { val v = varint(); if (tag shr 3 == 2) version = v }
                    2 -> { val n = varint().toInt(); if (tag shr 3 == 1 && i + n <= b.size) name = String(b, i, n); i += n }
                    1 -> i += 8; 5 -> i += 4; else -> i = b.size
                }
            }
            return name to version
        }
        val sb = StringBuilder(); val seen = HashSet<String>()
        for (f in parked) runCatching {
            val (name, version) = java.util.zip.ZipFile(f).use { z -> fields(z.getInputStream(z.getEntry("apex_manifest.pb")).use { it.readBytes() }) }
            if (name == null || !seen.add(name) || !File(apex, "$name/apex_manifest.pb").isFile) return@runCatching
            
            val was = "/" + f.name.replace('#', '/')
            sb.append("$name\t/system/.aemu-parked/${f.name}\t$was\t$version\n")
        }
        out.writeText(sb.toString())
        log("apex stand-in: ${seen.size} modules")
    }.onFailure { log("apex stand-in: ${it.message}") }

    private fun onCtl(start: Boolean, svc: String) {
        if (svc.startsWith("aidl/")) {
            
            
            if (!kernel.lazyAidlServices) return
            if (kernel.apexStandIn && svc == "aidl/apexservice") return
            val def = InitPlan.lazyAidl(svc.removePrefix("aidl/"), paths.root) ?: return
            synchronized(procs) {
                if (procs[def.name]?.isAlive == true) return
                val n = lazyStarts[def.name] ?: 0
                if (n >= 3) return
                lazyStarts[def.name] = n + 1
            }
            log("lazy service ${def.name} asked for ($svc)")
            Thread { runCatching { startService(def) } }.start()
            return
        }
        val plan = servicePlan().filter { it.name !in InitPlan.NEVER_START }.ifEmpty { InitPlan.fallback(img, paths.root) }
        if (svc == "bootanim" || svc == "bootanimation" || svc == "samsungani") {
            if (!start) {
                
                
                
                
                
                
                val p = synchronized(procs) { procs["bootanim"] } ?: return
                Thread {
                    var waited = 0
                    while (!stopping && p.isAlive) {
                        if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) break
                        waited++
                        
                        
                        
                        if (waited == 10) {
                            val seen = props.get("service.bootanim.exit")
                            log("boot animation watchdog: still alive 10 s after exit=1; area value=\"$seen\", re-asserting")
                            props.put("service.bootanim.exit", "1")
                        }
                        
                        
                        
                        
                        if (waited >= 20 && bootDoneAt != 0L && !recoveryMode) releaseStuckPeripheralClaim()
                        if (waited == 30) log("boot animation is still running 30 s after service.bootanim.exit=1, waiting")
                        
                        if (waited == 60 && bootDoneAt != 0L && !recoveryMode && !hasPeripheralman()) {
                            log("boot animation did not exit 60 s after boot completion (area value=\"${props.get("service.bootanim.exit")}\"), stopping it")
                            p.destroy()
                            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                        }
                    }
                    
                    
                }.apply { isDaemon = true; start() }
                return
            }
            
            if (img.api < 16 || bootDoneAt != 0L || synchronized(procs) { procs.containsKey("bootanim") }) return
            InitPlan.optional(svc, img, paths.root)?.let { def ->
                Thread { runCatching { startService(def) } }.start()
                log("boot animation started")
            }
            return
        }
        val def = plan.firstOrNull { it.name == svc } ?: InitPlan.optional(svc, img, paths.root)
        if (svc == "playsound" && start && (bootDoneAt != 0L || synchronized(procs) { procs["playsound"]?.isAlive == true })) return
        if (def == null) { log("ctl.${if (start) "start" else "stop"} $svc: no such service"); return }
        synchronized(procs) { procs.remove(svc) }?.destroyForcibly()
        if (start) Thread { runCatching { startService(def) } }.start()
    }

    private fun hasPeripheralman() = servicePlan().any { it.name == "peripheralman" }

    
    private fun cpuTicks(pid: Int): Long = runCatching {
        val f = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
        f[11].toLong() + f[12].toLong()   
    }.getOrDefault(-1L)

    private var peripheralClaimReleased = false

    
    private fun releaseStuckPeripheralClaim() {
        if (peripheralClaimReleased) return
        val old = synchronized(procs) { procs["peripheralman"] } ?: return
        if (!old.isAlive) return
        val def = servicePlan().firstOrNull { it.name == "peripheralman" } ?: return
        peripheralClaimReleased = true
        val pid = pidOf(old)
        val before = pid?.let { cpuTicks(it) } ?: -1L
        try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        val after = pid?.let { cpuTicks(it) } ?: -1L
        val bus = File(paths.root, "dev/i2c-1")
        log("I2C1 is held by the boot animation which is still running; peripheralman (pid $pid) used " +
            "${if (before >= 0 && after >= 0) "${after - before} ticks of CPU in 1 s" else "an unknown amount of CPU"}; " +
            "bus file ${bus.length()} bytes. Starting peripheralman afresh (the boot animation is left alone)")
        synchronized(procs) { procs.remove("peripheralman", old) }
        old.destroyForcibly()
        props.put("init.svc.peripheralman", "stopped")
        runCatching { startService(def) }.onFailure { log("peripheralman: restart failed: ${it.message}") }
    }

    
    private fun watchFb0() {
        if (recoveryMode) return
        val f = paths.fb
        Thread({
            val start = System.currentTimeMillis()
            val buf = ByteArray(64 * 1024)
            var seen = false
            while (!stopping && System.currentTimeMillis() - start < 120_000 && !seen) {
                runCatching {
                    java.io.RandomAccessFile(f, "r").use { r ->
                        val total = minOf(r.length(), 4L shl 20)
                        var pos = 0L; var nz = 0L; var firstAt = -1L
                        while (pos < total) {
                            val n = r.read(buf); if (n <= 0) break
                            for (i in 0 until n) if (buf[i].toInt() != 0) { nz++; if (firstAt < 0) firstAt = pos + i }
                            pos += n
                        }
                        if (nz > 0) {
                            seen = true
                            log("fb0: first non-black pixels ${(System.currentTimeMillis() - start) / 1000.0} s after boot start ($nz non-zero bytes, first at offset $firstAt)")
                        }
                    }
                }
                if (!seen) try { Thread.sleep(300) } catch (_: InterruptedException) { return@Thread }
            }
            if (!seen && !stopping) log("fb0: no non-black pixel in the first 120 s (guest wrote nothing, or only black frames)")
        }, "fb0-watch").apply { isDaemon = true; start() }
    }

    private fun afterBoot() {
        val r = guestRunner
        if (LegacyFirmware.isPreRelease(img.release)) {   
            runCatching {
                ImageStore.update(ctx, img.id) { it.copy(lastBootMs = bootDoneAt - bootAt, bootCount = it.bootCount + 1) }
            }.onFailure { log("boot statistics: could not save: ${it.message}") }
            return
        }
        if (skipWizardNow()) return   
        
        
        if (engine == Engine.N64 && kernel.clientComposition) Thread {
            for (wait in longArrayOf(0, 10_000, 20_000)) {
                Thread.sleep(wait)
                if (state != State.RUNNING) return@Thread
                clientComposition()
            }
        }.start()
        r.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; svc power stayon true"), 60_000)
        
        
        if (isTvBuild()) runCatching {
            val out = guestShell("settings put secure screensaver_enabled 0; settings put secure sleep_timeout 0; svc power stayon true", 60_000)
            log("tv: screensaver_enabled 0, sleep_timeout 0, svc power stayon true" + if (out.isNotBlank()) " (${out.trim().take(200)})" else "")
        }.onFailure { log("tv: could not switch the idle timers off: ${it.message}") }
        if (engine == Engine.N64 && kernel.webViewSingleProcess)
            runCatching { guestShell("cmd webviewupdate disable-multiprocess", 30_000) }
        TreeFixer(ctx, paths, img, ::log).noScreenSleep()
        disableBrokenComponents()
        disableSharedConnectivity()
        skipInternetCheck()
        stopCrashLoopReboot()
        if (img.api >= 34) runCatching { switchToFullUserOnHsum() }.onFailure { log("headless system user check failed: $it") }
        if (img.api >= 24) runCatching { enableHome() }.onFailure { log("home screen check failed: $it") }
        runCatching {
            ImageStore.update(ctx, img.id) { it.copy(lastBootMs = bootDoneAt - bootAt, bootCount = it.bootCount + 1) }
        }.onFailure { log("boot statistics: could not save: ${it.message}") }
    }

    
    private fun isTvBuild(): Boolean {
        val line = props.get("ro.build.characteristics")?.takeIf { it.isNotBlank() }
            ?: listOf("system/build.prop", "vendor/build.prop", "system/vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
                "product/etc/build.prop", "system/product/etc/build.prop").firstNotNullOfOrNull { n ->
                runCatching { PropArea.parseProps(File(paths.root, n).readText())["ro.build.characteristics"] }.getOrNull()
            }
        return app.aemu.importer.Analyzer.isTvCharacteristics(line)
    }

    
    private fun skipWizardNow(): Boolean {
        
        if (!img.settings.skipSetupWizard || wizardSkipped || zygoteRestarts > 0 || img.bootCount > 0) return false
        
        val wizards = TreeFixer.FIRST_RUN_WIZARDS + (if (File(paths.root, "system/app/miui/miui.apk").isFile) listOf("com.android.provision") else emptyList())
        val pkgFile = File(paths.root, "data/system/packages.xml")
        
        
        if (runCatching { pkgFile.inputStream().use { i -> ByteArray(3).also { i.read(it) } }.contentEquals("ABX".toByteArray()) }.getOrDefault(false)) {
            val listed = runCatching { guestShell("pm list packages", 60_000) }.getOrDefault("")
            val wizard = wizards.firstOrNull { listed.lines().any { l -> l.trim() == "package:$it" } } ?: return false
            wizardSkipped = true
            plannedRestart = true
            log("first boot: setup wizard ($wizard) switched off in the running system, restarting it")
            runCatching { guestShell("settings put global device_provisioned 1; settings put secure user_setup_complete 1; pm disable-user --user 0 $wizard", 60_000) }
            synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
            return true
        }
        val pkgs = runCatching { pkgFile.readText() }.getOrNull() ?: return false
        val restr = runCatching { File(paths.root, "data/system/users/0/package-restrictions.xml").readText() }.getOrDefault("")
        val wizard = wizards.firstOrNull {
            pkgs.contains("<package name=\"$it\"") && !Regex("<pkg name=\"${Regex.escape(it)}\"[^>]*enabled=\"2\"").containsMatchIn(restr)
        } ?: return false
        wizardSkipped = true
        plannedRestart = true
        log("first boot: restarting the system past the setup wizard ($wizard)")
        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
        return true
    }

    
    private fun switchToFullUserOnHsum() {
        val hsum = guestShell("cmd user is-headless-system-user-mode 2>/dev/null; getprop ro.fw.mu.headless_system_user", 30_000)
            .lines().any { it.trim() == "true" }
        if (!hsum) return
        val cur = guestShell("am get-current-user", 30_000).trim().toIntOrNull() ?: return
        if (cur != 0) return
        
        val flagsOf = Regex("""UserInfo\{(\d+):[^:}]*:([0-9a-fA-F]+)\}""")
        val full = 0x400; val guest = 0x4; val profile = 0x1000; val disabled = 0x40; val demo = 0x200; val system = 0x800
        val candidates = flagsOf.findAll(guestShell("pm list users", 30_000)).mapNotNull { m ->
            val id = m.groupValues[1].toInt(); val f = m.groupValues[2].toInt(16)
            if (id == 0 || f and full == 0 || f and (guest or profile or disabled or demo or system) != 0) null else id to f
        }.toList()
        
        val pick = candidates.sortedWith(compareByDescending<Pair<Int, Int>> { it.second and 0x4000 != 0 }
            .thenByDescending { it.second and 0x2 != 0 }.thenBy { it.first }).firstOrNull()?.first
        if (pick == null) { log("headless system user mode: the system user is current and there is no full user to switch to"); return }
        log("headless system user mode: the system user cannot run activities, switching to user $pick")
        prepareUserDirs(pick)
        val out = guestShell("am switch-user -w $pick", 90_000).trim()
        if (out.isNotEmpty()) log("switch-user $pick: ${out.lines().take(3).joinToString(" | ")}")
        reportUserState(pick)
    }

    
    private fun prepareUserDirs(id: Int) {
        val data = File(paths.root, "data")
        val made = ArrayList<String>()
        for (d in listOf("user/$id", "user_de/$id", "misc_ce/$id", "misc_de/$id", "system_ce/$id", "system_de/$id", "media/$id",
                "misc/profiles/cur/$id")) {
            val dir = File(data, d)
            val existed = dir.isDirectory
            runCatching { dir.mkdirs() }
            if (!dir.isDirectory) { log("user $id: /data/$d could not be created"); continue }
            
            var p: File? = dir
            while (p != null && p != data) { TreeFixer.makeTraversable(p); p = p.parentFile }
            if (!existed) made += d
        }
        log("user $id: per-user directories " + if (made.isEmpty()) "already there" else "created (${made.joinToString(", ")})")
    }

    
    private fun reportUserState(id: Int) {
        var state = ""
        for (i in 0 until 10) {
            state = guestShell("am get-started-user-state $id 2>&1", 15_000).trim().lines().lastOrNull().orEmpty()
            if (state.contains("RUNNING_UNLOCKED")) break
            
            
            
            
            
            if (i == 3) {
                log("user $id: still ${state.ifEmpty { "unknown" }} 6 s after the switch, asking for the unlock: " +
                    guestShell("am unlock-user $id 2>&1", 30_000).trim().lines().take(3).joinToString(" | ").ifEmpty { "(no output)" })
            }
            Thread.sleep(2_000)
        }
        if (state.contains("RUNNING_UNLOCKED")) { log("user $id: unlocked ($state)"); return }
        log("user $id: NOT unlocked after the switch (state: ${state.ifEmpty { "unknown" }})")
        val dirs = listOf("user/$id", "user_de/$id", "misc_ce/$id", "misc_de/$id", "system_ce/$id", "system_de/$id", "media/$id", "misc/profiles/cur/$id")
            .joinToString(" ") { "/data/$it" }
        log("user $id: " + guestShell("pm list users 2>&1 | tr '\\n' ' '; echo; ls -ld $dirs 2>&1; " +
            "getprop sys.user.$id.ce_available; getprop sys.boot_completed; getprop ro.crypto.state", 30_000).trim().lines().joinToString(" | "))
        log("user $id: " + guestShell("logcat -d -v brief -t 4000 2>&1 | grep -i -E 'user $id|userId=$id|UserController|unlockUserKey|StorageManagerService|UserDataPreparer|onBeforeUnlockUser' | tail -25", 30_000)
            .trim().lines().joinToString("\n  "))
    }

    
    private fun enableHome() {
        val resolved = guestShell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
        val homes = Regex("""\S+/\S+""").findAll(resolved).map { it.value }.toSet()
        if (homes.any { !it.endsWith("FallbackHome") }) return
        val table = guestShell("dumpsys package -f r", 90_000).lines()
        val off = LinkedHashSet<String>()
        for (i in table.indices) {
            if (!table[i].contains("\"android.intent.category.HOME\"")) continue
            
            val comp = (i downTo maxOf(0, i - 8)).firstNotNullOfOrNull { j -> Regex("""\s[0-9a-f]+ (\S+/\S+) filter""").find(table[j])?.groupValues?.get(1) } ?: continue
            if (comp !in homes) off += comp
        }
        
        val own = homes.map { it.substringBefore('/') }.toSet() + "android"
        val listed = Regex("""(\S+)/\S+""").findAll(guestShell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER", 30_000))
            .map { it.groupValues[1] }.toSet()
        val c = off.firstOrNull { it.substringBefore('/') !in own && it.substringBefore('/') in listed } ?: return
        log("home screen $c was switched off, switching it on")
        guestShell("pm enable $c; am start -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
    }

    
    
    private fun stopCrashLoopReboot() {
        if (img.api < 30) return
        runCatching {
            val sh = "export PATH=/system/bin:/system/xbin:\$PATH; "
            val out = guestShell(sh + "setprop persist.sys.disable_rescue true; " +
                "if pm path com.droidlogic >/dev/null 2>&1; then pm disable-user --user 0 com.droidlogic; else echo no-droidlogic; fi", 60_000)
            log("crash loops: RescueParty off, com.droidlogic: " + out.trim().lines().lastOrNull().orEmpty())
        }.onFailure { log("crash loops: ${it.message}") }
    }

    
    private fun skipInternetCheck() {
        if (img.api < 21) return
        runCatching {
            val out = guestShell("export PATH=/system/bin:/system/xbin:\$PATH; " +
                "settings put global captive_portal_mode 0; settings put global captive_portal_detection_enabled 0; " +
                "settings get global captive_portal_mode", 60_000)
            log("wifi: internet check off (captive_portal_mode=" + out.trim().lines().lastOrNull().orEmpty() + ")")
        }.onFailure { log("wifi: could not switch the internet check off: ${it.message}") }
    }

    
    private fun disableSharedConnectivity() {
        if (img.api < 34) return
        val marker = File(paths.root, "data/.aemu-sharedconn-disabled")
        if (marker.isFile) return
        runCatching {
            val out = guestShell("export PATH=/system/bin:/system/xbin:\$PATH; cmd package query-services --brief -a android.service.sharedconnectivity.SharedConnectivityService", 60_000)
            val comps = out.lines().map { it.trim() }.filter { Regex("^[\\w.]+/[\\w.$]+$").matches(it) }.distinct()
            log("shared connectivity: services answering the action: ${comps.ifEmpty { listOf("none") }.joinToString(", ")}")
            for (c in comps) {
                val r = guestShell("export PATH=/system/bin:/system/xbin:\$PATH; pm disable $c", 60_000).trim().lines().lastOrNull().orEmpty()
                log("shared connectivity: pm disable $c -> $r")
            }
            if (comps.isNotEmpty()) marker.writeText("1")
        }.onFailure { log("shared connectivity: ${it.message}") }
    }

    private fun disableBrokenComponents() {
        if (img.api !in 9..16) return
        val marker = File(paths.root, "data/.aemu-components-disabled2")
        if (marker.isFile) return
        val list = listOf(
            "com.google.android.location/com.google.android.location.NetworkLocationService",
            "com.google.android.location/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.NetworkLocationService",
        )
        
        
        val glass = GlassLocationPolicy.isGlass(img.name, img.model, File(paths.root, "system/app/GlassBluetooth.apk").isFile ||
            File(paths.root, "system/.aemu-parked/system#app#GlassBluetooth.apk").isFile)
        for (c in list) if (!GlassLocationPolicy.keepEnabled(c, glass)) runCatching { guestRunner.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; pm disable $c"), 120_000) }
        runCatching { marker.writeText("1") }
    }

    
    private val power: File get() = File(paths.root, "dev/aemu_power")

    private fun watchdog() {
        runCatching { power.writeText(""); power.setWritable(true, false) }
        
        
        if (img.api >= 24) Thread({
            val fixer = TreeFixer(ctx, paths, img, ::log)
            while (!stopping) {
                runCatching { fixer.ensureHostPathAlias() }
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
        }, "host-path-alias").apply { isDaemon = true; start() }
        Thread({
            var restarts = HashMap<String, Int>()
            while (!stopping) {
                Thread.sleep(3000)
                if (power.length() > 0) { val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText(""); powerRequest(v) }
                if (img.api >= 14) runCatching { dns.ensure() }
                if (state == State.BOOTING && bootDoneAt == 0L && !recoveryMode && bootSeconds() > 180) {
                    log("boot watchdog: no completion after 180 s (guest stuck in boot animation), applying the boot flag from the host")
                    runCatching { props.put("sys.boot_completed", "1"); props.put("dev.bootcomplete", "1") }
                    completeBoot("boot watchdog: boot flag applied after 180 s")
                }
                runCatching { checkBinderGuard() }
                val dead = synchronized(procs) { procs.filter { !it.value.isAlive }.toList() }
                for ((name, p) in dead) {
                    if (stopping) break
                    
                    
                    if (!synchronized(procs) { procs.remove(name, p) }) continue
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (name in setOf("zygote", "surfaceflinger", "mediaserver")) noteBoot.interrupted()
                    
                    if (name == "bootanim" && code != 0) checkDeadBootAnimLayer(code)
                    if (name == "zygote" || name == "zygote_secondary") {
                        
                        
                        runCatching {
                            val d = File(paths.root, "dev/socket")
                            val rows = d.listFiles()?.sortedBy { it.name }?.joinToString("; ") { f ->
                                val st = runCatching { android.system.Os.lstat(f.path) }.getOrNull()
                                val kind = if (st == null) "?" else when (st.st_mode and 0xF000) { 0xC000 -> "sock"; 0x8000 -> "file"; 0xA000 -> "link"; else -> "other" }
                                "${f.name}[$kind ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(f.lastModified()))}]"
                            }
                            log("dev/socket when $name exited (code $code): ${rows ?: "directory missing"}")
                        }
                        
                        
                        if (code == 0) log("zygote: exit code 0 is how app_process ends after an uncaught exception in ZygoteInit; the cause is in the guest log (zygote.log), e.g. NoClassDefFoundError = a jar missing from BOOTCLASSPATH")
                        
                        
                        
                        if (state != State.FAILED && engine == Engine.GB && !settings.gpu && softwareGlCrashed() &&
                            swGlFallbackStarted.compareAndSet(false, true)) {
                            log("✖ software renderer (libGLES_android.so) crashed in SurfaceFlinger; turning the Phone GPU bridge on and restarting the VM")
                            ImageStore.update(ctx, img.id) { it.copy(settings = it.settings.copy(gpu = true)) }
                            onPower?.invoke(true, "")
                            continue
                        }
                        
                        if (state != State.FAILED && !everBooted && !imeRetried && guestLogHas(GlassImePolicy.FAILURE_MARK)) {
                            imeRetried = true
                            if (TreeFixer(ctx, paths, img, ::log).seedDefaultIme()) {
                                log("✖ system_server failed in InputMethodManagerService, default input method set, starting the system again")
                                restartZygote()
                                continue
                            }
                        }
                        
                        
                        val heapAbort = code == 137 && guestLogHas("GC tried to mark invalid reference", 3_000_000L)
                        if (state != State.FAILED && !everBooted && heapAbort && heapAbortRetries < 2) {
                            heapAbortRetries++
                            log("✖ system_server aborted: the garbage collector met a corrupt heap (boot image without read barrier); starting the system again ($heapAbortRetries/2)")
                            restartZygote()
                            continue
                        }
                        if (state != State.FAILED && bootDoneAt == 0L && !(everBooted && zygoteRestarts < 6)) {
                            failure = if (name != "zygote") Msg("$name exited (code $code)" + if (code == 137) " — killed: the phone ran out of memory, or system_server died and took zygote with it (see the guest log)" else "")
                                else if (code == 137) Msg("zygote exited (code $code) — SIGKILL (cause not established)", R.string.vmfail_zygote_exited_sigkill, code)
                                else Msg("zygote exited (code $code)", R.string.vmfail_zygote_exited, code)
                            
                            if (heapAbort)
                                failure = Msg("system_server aborted: its garbage collector found a corrupt heap (GC tried to mark invalid reference). This firmware's boot image was built without a read barrier but the guest runs the concurrent-copying collector; zygote exited with 137 as a result. See the first \"Runtime aborting\" in the guest log")
                            if (code == 137 && guestLogHas("Required services extension package is missing"))
                                failure = Msg("system_server died in PackageManagerService: the services extension package (ExtServices) was not installed" +
                                    if (guestLogHas("errorCode=-113")) " because its native libraries match none of the guest's ABIs (INSTALL_FAILED_NO_MATCHING_ABIS); see ro.product.cpu.abilist in the props lines of this log" else "; see the PackageManager lines of the guest log")
                            if (code == 137 && guestLogHas("Failure starting system services"))
                                failure = Msg("system_server failed to start its services (zygote exited with 137 as a result); see the first BOOT FAILURE in the guest log", R.string.vmfail_system_server_services)
                            if (code == 137 && guestLogHas("IKeystoreService"))
                                failure = Msg("system_server died because the keystore service does not exist (no keymaster HAL registered); see the \"keymaster:\" lines of this log")
                            if (code == 137 && guestLogHas("IBatteryPropertiesRegistrar.registerListener")) {
                                
                                runCatching {
                                    val r = guestRunner.run(listOf("/system/bin/service", "check", "batteryproperties"), 8_000).second
                                    log("healthd: after the failure, service check batteryproperties -> \"${r.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING: linker") }.orEmpty().take(160)}\"")
                                }
                            }
                            if (code == 137 && guestLogHas("IBatteryPropertiesRegistrar.registerListener"))
                                failure = Msg("system_server cannot start: healthd did not register the \"batteryproperties\" service (BatteryService got null); see the healthd log in the VM log folder", R.string.vmfail_system_server_healthd)
                            if (code == 137 && guestLogHas("No original dex files found"))
                                failure = Msg("system_server cannot start: the precompiled code of the framework (system/framework/oat) is missing from this container — import the firmware again", R.string.vmfail_system_server_oat_missing)
                            
                            
                            
                            if (code == 137 && guestLogHas("Failed to inform zygote of boot_completed"))
                                failure = Msg("system_server died at the end of the boot: its connect to a zygote socket failed with ENOENT (\"Failed to inform zygote of boot_completed\") although the socket files exist, so the boot finished but the system was killed; zygote exited with 137 as a result. Run with strace on to see which path the connect used")
                            if (code == 137 && guestLogHas("WiFi display was requested, but there is no WiFi Direct feature"))
                                failure = Msg("system_server died in WifiDisplayAdapter: WiFi display is enabled by the firmware but android.hardware.wifi.direct is not declared (zygote exited with 137 as a result)")
                            log("✖ $failure"); setState(State.FAILED)
                        } else if (plannedRestart) {
                            plannedRestart = false
                            killZygoteChildren()
                            TreeFixer(ctx, paths, img, ::log).noScreenSleep()
                            restartZygote()
                        } else if (state != State.FAILED && (bootDoneAt > 0 || everBooted)) {
                            zygoteRestarts++
                            val now = System.currentTimeMillis()
                            zygoteCrashes.addLast(now)
                            while (zygoteCrashes.isNotEmpty() && zygoteCrashes.first() < now - 600_000) zygoteCrashes.removeFirst()
                            val recentCrashesIn1Min = zygoteCrashes.count { it >= now - 60_000 }
                            if (recentCrashesIn1Min >= 5 || zygoteCrashes.size > 6) {
                                failure = Msg("system keeps crashing: $name exited $recentCrashesIn1Min times in 1 min (${zygoteCrashes.size} times in 10 min, last code $code)")
                                log("✖ $failure"); setState(State.FAILED)
                            } else {
                                
                                
                                if (code == 137 && guestLogHas("android.security.KeyStore.state"))
                                    log("keymaster: system_server died in KeyStore.state(): the \"android.security.keystore\" service gave getState an answer " +
                                        "it does not know (the stand-in must say NO_ERROR; the real keystore needs the keymaster HAL, see the \"keymaster:\" lines)")
                                log("✖ $name crashed (code $code), restarting system ($zygoteRestarts)")
                                restartZygote()
                            }
                        }
                        continue
                    }
                    
                    
                    val stub = stubServices[name]
                    if (stub != null) {
                        val k = restarts.getOrDefault(name, 0)
                        if (code in 129..159 && code != 137 && code != 143 && k < 3) {
                            restarts[name] = k + 1
                            log("service $name crashed (code $code), restarting (${k + 1}/3)")
                            runCatching { startService(stub) }
                        }
                        continue
                    }
                    val n = restarts.getOrDefault(name, 0)
                    val svc = storedServices().firstOrNull { it.name == name }
                        ?: if (name.contains("-hal-") && n < 3) servicePlan().firstOrNull { it.name == name } else null
                    
                    
                    
                    if (engine == Engine.N64 && (name.startsWith("hwcomposer") || name.contains("graphics.composer")) &&
                        (code == 139 || code == 135 || code == 134) && n < 6) {
                        val composer = svc ?: servicePlan().firstOrNull { it.name == name }
                        if (composer != null) {
                            restarts[name] = n + 1
                            log("service $name crashed (code $code), restarting (${n + 1}/6)")
                            runCatching { startService(composer) }
                            continue
                        }
                    }
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    
                    if (name == "surfaceflinger" && code == 134 && bootDoneAt == 0L && engine == Engine.N64 && kernel == Kernel64.A16 &&
                        needsGralloc5()) {
                        if (restartForGralloc5.compareAndSet(false, true)) {
                            log("✖ surfaceflinger: no graphics mapper 4/5 (gralloc-mapper is missing), restarting the VM now with the engine's gralloc5")
                            onPower?.invoke(true, "")
                        }
                        continue
                    }
                    if (name == "surfaceflinger" && code == 139 && bootDoneAt == 0L && engine == Engine.N64 && useFirmwareSurfaceFlinger()) {
                        if (restartForFirmwareSurfaceFlinger.compareAndSet(false, true)) {
                            log("✖ surfaceflinger: restarting the VM now so the whole tree is set up for the firmware's own SurfaceFlinger")
                            onPower?.invoke(true, "")
                        }
                        continue
                    }
                    if (name == "surfaceflinger" && svc != null && bootDoneAt > 0 && n < 12) {
                        restarts[name] = n + 1
                        log("✖ surfaceflinger crashed (code $code), restarting it and the system (${n + 1}/12)")
                        runCatching { startService(svc) }
                        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
                        continue
                    }
                    
                    if (name == "mediaserver" && (code == 139 || code == 134) && n == 1) swapAudioPolicy()
                    
                    if (name == "mediaserver" && (code == 139 || code == 134) && n == 2) abandonAospPolicy()
                    
                    if (name == "mediaserver" && (code == 139 || code == 134) && n == 3) abandonRomPolicy()
                    
                    
                    val hal = name.contains("-hal-") && n < 3
                    if (svc != null && (svc.restart || name in ALWAYS_RESTART || hal) && code != 137 && code != 143 && n < 12) {
                        restarts[name] = n + 1
                        log("service $name crashed (code $code), restarting (${n + 1}/12)")
                        runCatching { startService(svc) }
                    }
                }
                if (pendingNote.isNotBlank() && !recoveryMode && !lowPowerBoot && !stopping) {
                    val healthy = synchronized(procs) {
                        listOf("zygote", "surfaceflinger").all { procs[it]?.isAlive == true }
                    }
                    if (noteBoot.ready(SystemClock.elapsedRealtime(), state == State.RUNNING, healthy)) {
                        runCatching {
                            val saved = ImageStore.update(ctx, img.id) { it.copy(oneTimeNote = "") }
                            if (saved != null) pendingNote = ""
                        }.onFailure { log("ROM note: could not save completion: ${it.message}") }
                    }
                }
            }
        }, "vm-watchdog").apply { isDaemon = true; start() }
    }

    
    private fun swapAudioPolicy(crashed: Boolean = false) {
        if (img.api < 16) return
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        val failedMark = File(paths.root, "system/.aemu-parked/audio-aosp-failed")
        if (failedMark.isFile) { 
            
            
            
            
            if (!File(paths.root, "system/.aemu-parked/audio-rom-failed").isFile && installRomPolicy()) return
            if (parked.isFile) runCatching { parked.copyTo(f, overwrite = true); aosp.delete(); File(paths.root, "system/lib/libaemu_approm.so").delete() }
                .onFailure { log("audio: cannot restore the firmware audio policy: ${it.message}") }
            return
        }
        
        
        
        val directTrack = img.api in 19..20 && runCatching {
            AudioHalAbi.usesDirectTrackTail(File(paths.root, "system/lib/libnbaio.so").readBytes())
        }.getOrDefault(false)
        if (directTrack) {
            log("audio: CAF direct-track build, the AOSP policy stand-in is not used")
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true) 
                log("audio: CAF direct-track, restored firmware audio policy")
            }.onFailure { log("audio: policy restore failed: ${it.message}") }
            return
        }
        
        
        if (TreeFixer.isMtkPlatform(paths.root)) {
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true); parked.delete(); aosp.delete()
                log("audio: MediaTek, restored stock audio_policy")
            }
            return
        }
        
        
        
        val bad = File(paths.root, "system/.aemu-parked/audio_policy.aosp-bad")
        if (bad.isFile) { log("audio: the AOSP policy stand-in was taken out earlier (audio_policy.aosp-bad), the firmware's policy stays"); return }
        fun assetSize(n: String) = runCatching { EngineAssets.size(ctx, "engines/kk/$n") }.getOrDefault(-1L)
        if (aosp.length() == assetSize("audio_policy.default.so") && f.length() == assetSize("audio_policy.wrap.so")) {
            fixAospPolicyLibc(aosp) 
            if (!crashed) return
            runCatching {
                bad.parentFile?.mkdirs(); bad.writeText("")
                aosp.delete()
                if (parked.isFile) { parked.copyTo(f, overwrite = true); parked.delete() } else f.delete()
                log("audio: the AOSP audio_policy crashes here too, removed it")
            }
            return
        }
        runCatching {
            parked.parentFile?.mkdirs()
            if (!parked.isFile && f.isFile) f.copyTo(parked, overwrite = true)
            EngineAssets.open(ctx, "engines/kk/audio_policy.default.so").use { i -> aosp.outputStream().use { o -> i.copyTo(o) } }
            EngineAssets.open(ctx, "engines/kk/audio_policy.wrap.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            fixAospPolicyLibc(aosp)
            log("audio: firmware audio_policy crashes, installed AOSP one (stock kept)")
        }.onFailure { log("audio: cannot install the AOSP policy stand-in: ${it.message}") }
    }

    
    private fun fixAospPolicyLibc(aosp: File) {
        runCatching {
            val libc = File(paths.root, "system/lib/libc.so")
            if (!aosp.isFile || !libc.isFile) return@runCatching
            var bytes = aosp.readBytes()
            val fixed = ElfAlias.aliasMissing(bytes, libc.readBytes())
            if (fixed != null) {
                aosp.writeBytes(fixed.bytes)
                bytes = fixed.bytes
                log("audio: AOSP policy stand-in pointed at plain functions this libc has (${fixed.renamed.joinToString()})")
            }
            val left = ElfAlias.unresolved(bytes, File(paths.root, "system/lib"))
            if (left.isNotEmpty()) log("audio: AOSP policy stand-in still has symbols this firmware does not define: ${left.take(12).joinToString()}")
        }.onFailure { log("audio: AOSP policy libc check failed: ${it.message}") }
    }

    
    private val restartForFirmwareSurfaceFlinger = java.util.concurrent.atomic.AtomicBoolean(false)
    private val restartForGralloc5 = java.util.concurrent.atomic.AtomicBoolean(false)

    
    private fun needsGralloc5(): Boolean {
        val dir = File(paths.root, "system/.aemu-parked")
        val marker = File(dir, "gralloc5")
        if (marker.exists()) { log("surfaceflinger: aborted without a mapper although the engine's gralloc5 is in use; nothing left to try"); return false }
        val said = runCatching {
            val f = File(paths.bin, "surfaceflinger.log")
            val t = f.readText(Charsets.ISO_8859_1)
            t.substring(maxOf(0, t.length - 65536)).contains("gralloc-mapper is missing")
        }.getOrDefault(false)
        if (!said) return false
        return runCatching { dir.mkdirs(); marker.writeText("1"); true }.getOrElse { log("gralloc5: marker not written: ${it.message}"); false }
    }

    private fun useFirmwareSurfaceFlinger(): Boolean {
        val dir = File(paths.root, "system/.aemu-parked")
        val marker = File(dir, "firmware-sf")
        if (marker.exists()) {
            log("surfaceflinger: crashed on the first frame although the firmware's own SurfaceFlinger is already in use; nothing left to swap")
            return false
        }
        val pairs = listOf("system#bin#surfaceflinger" to "system/bin/surfaceflinger",
            "system#lib64#libsurfaceflinger.so" to "system/lib64/libsurfaceflinger.so")
        val missing = pairs.filter { !File(dir, it.first).isFile }.map { it.first }
        if (missing.isNotEmpty()) {
            log("surfaceflinger: crashed on the first frame, but the firmware's own copy was not kept aside (missing: ${missing.joinToString()}); cannot swap")
            return false
        }
        return runCatching {
            for ((parked, live) in pairs) {
                val dst = File(paths.root, live)
                dst.delete()
                if (!File(dir, parked).renameTo(dst)) File(dir, parked).copyTo(dst, overwrite = true)
                dst.setReadable(true, false); dst.setExecutable(true, false)
            }
            marker.writeText("1")
            log("surfaceflinger: the engine's build crashed on the first frame (libgui mismatch), the firmware's own SurfaceFlinger takes over")
            true
        }.getOrElse { log("surfaceflinger: cannot put the firmware's own back: ${it.message}"); false }
    }

    
    private fun abandonAospPolicy() {
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        if (!parked.isFile || !aosp.isFile) return
        runCatching {
            parked.copyTo(f, overwrite = true)
            aosp.delete()
            File(paths.root, "system/.aemu-parked/audio-aosp-failed").writeText("1")
            log("audio: AOSP policy crashed too, restored the firmware's own audio policy")
        }.onFailure { log("audio: cannot restore firmware audio policy: ${it.message}") }
        
        installRomPolicy()
    }

    
    private fun installRomPolicy(): Boolean {
        val dir = File(paths.root, "system/.aemu-parked")
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val parked = File(dir, "system#lib#hw#audio_policy.default.so")
        val approm = File(paths.root, "system/lib/libaemu_approm.so")
        val marker = File(dir, "audio-rom-wrap")
        return runCatching {
            val wrapSize = runCatching { EngineAssets.size(ctx, "engines/kk/audio_policy.wrap.so") }.getOrDefault(-1L)
            if (wrapSize <= 0) error("the engine has no audio_policy.wrap.so")
            dir.mkdirs()
            
            if (!parked.isFile) {
                if (f.isFile && f.length() != wrapSize) f.copyTo(parked, overwrite = true)
                else error("no firmware audio policy to run")
            }
            if (marker.isFile && f.length() == wrapSize && approm.isFile && approm.length() == parked.length()) return@runCatching true
            parked.copyTo(approm, overwrite = true)
            approm.setReadable(true, false); approm.setExecutable(true, false)
            EngineAssets.open(ctx, "engines/kk/audio_policy.wrap.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.setReadable(true, false); f.setExecutable(true, false)
            File(paths.root, "system/lib/libaemu_apaosp.so").delete()
            marker.writeText("1")
            log("audio: firmware audio policy runs behind the engine wrapper (binder thread pool started before the policy)")
            true
        }.getOrElse { log("audio: cannot put the firmware audio policy behind the wrapper: ${it.message}"); false }
    }

    
    private fun abandonRomPolicy() {
        val dir = File(paths.root, "system/.aemu-parked")
        if (!File(dir, "audio-rom-wrap").isFile) return
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val parked = File(dir, "system#lib#hw#audio_policy.default.so")
        runCatching {
            if (parked.isFile) parked.copyTo(f, overwrite = true)
            File(paths.root, "system/lib/libaemu_approm.so").delete()
            File(dir, "audio-rom-wrap").delete()
            File(dir, "audio-rom-failed").writeText("1")
            log("audio: the firmware policy crashed behind the wrapper as well, its own library is back (no wrapper, no AOSP stand-in)")
        }.onFailure { log("audio: cannot take the policy wrapper out: ${it.message}") }
    }

    private val bootAnimCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var bootAnimFixes = 0

    
    private fun checkDeadBootAnimLayer(code: Int) {
        if (!bootAnimCheckRunning.compareAndSet(false, true)) return
        log("boot animation died (code $code), will check the top SurfaceFlinger layer after boot")
        Thread({
            try {
                while (!stopping && bootAnimFixes < 2) {
                    
                    if (!waitFor("boot completion (bootanim check)", 300_000) { bootDoneAt != 0L }) return@Thread
                    Thread.sleep(5000)
                    if (stopping) return@Thread
                    val (_, dump) = guestRunner.run(listOf("/system/bin/sh", "-c",
                        "export PATH=/system/bin:/system/xbin:\$PATH; dumpsys SurfaceFlinger"), 30_000)
                    val layers = BootAnimLayer.visibleLayers(dump)
                    if (layers.isEmpty() || !BootAnimLayer.isBootAnim(layers.last())) {
                        log("boot animation died, top layer is ${layers.lastOrNull() ?: "unknown"}: nothing to fix")
                        return@Thread
                    }
                    bootAnimFixes++
                    log("✖ dead boot animation is still the top layer, restarting SurfaceFlinger ($bootAnimFixes/2)")
                    val sf = synchronized(procs) { procs["surfaceflinger"] }
                    val pid = sf?.let { pidOf(it) }
                    if (pid == null) { log("surfaceflinger is not running, cannot restart it"); return@Thread }
                    
                    runCatching { AProcess.sendSignal(pid, 9) }
                    Thread.sleep(10_000) 
                }
            } catch (_: InterruptedException) {
            } finally { bootAnimCheckRunning.set(false) }
        }, "bootanim-layer-check").apply { isDaemon = true; start() }
    }
    @Volatile private var plannedRestart = false
    private var wizardSkipped = false

    
    @Volatile var resurface: (() -> Unit)? = null

    
    private fun reseatGlSurface() {
        if (!glInApp) return
        val cycle = resurface ?: return
        val old = surface
        runCatching {
            cycle()
            val end = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < end && (surface == null || surface === old)) Thread.sleep(50)
            log("GPU bridge: screen surface recreated before the system restarts (${if (surface != null && surface !== old) "ok" else "no new surface"})")
        }
    }

    private fun restartZygote() {
        val z = storedServices().firstOrNull { it.name == "zygote" } ?: return
        if (img.api >= 24) runCatching { TreeFixer(ctx, paths, img, ::log).ensureHostPathAlias() }
        bootDoneAt = 0
        setState(State.BOOTING)
        reseatGlSurface()
        paths.socket("zygote").delete()
        paths.socket("zygote_secondary").delete()
        
        
        val n = killZygoteChildren()
        if (n > 0) log("killed apps of previous zygote: $n")
        if (img.arch == GuestArch.ARM && BroadcomGpu.v3dFirmware(paths.root) && BroadcomGpu.resetPoolHeader(paths.root)) log("gememalloc: pool allocator reset for the new system")
        
        
        servicePlan().firstOrNull { it.name == "healthd" }?.let { h ->
            synchronized(procs) { procs.remove("healthd") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(h) }
        }
        runCatching { startService(z) }
        watchLegacyBoot()   
        img.services.firstOrNull { it.name == "zygote_secondary" }?.let { z2 ->
            synchronized(procs) { procs.remove("zygote_secondary") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(z2) }
        }
    }

    private fun guestLogHas(text: String, window: Long = 400_000L): Boolean = runCatching {
        val f = File(paths.root, "dev/log/main")
        val len = f.length()
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(len, window).toInt(); r.seek(len - n)
            val b = ByteArray(n); r.readFully(b)
            String(b, Charsets.ISO_8859_1).contains(text)
        }
    }.getOrDefault(false)

    private fun killZygoteChildren(): Int {
        val marker = "/images/${img.id}/"
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            
            if (cmd.contains(marker) && cmd.contains("/system/bin/app_process") && !cmd.contains("aemu-stubs.jar") && !cmd.contains("aemu-bt.jar") && !cmd.contains(WifiEmu.JAR_NAME) && !cmd.contains("aemu-sensorhub.jar")) {
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            } else if (orphanOfThisImage(d)) {
                
                
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            }
        }
        return n
    }

    
    private fun orphanOfThisImage(proc: File): Boolean {
        val status = runCatching { File(proc, "status").readLines() }.getOrNull() ?: return false
        if (status.none { it.startsWith("PPid:") && it.substringAfter(':').trim() == "1" }) return false
        val env = runCatching { String(File(proc, "environ").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return false
        return env.split(0.toChar()).any { it == "AEMU64_ROOT=${paths.root.absolutePath}" || it == "AEMU64_ROOT=${paths.root.canonicalPath}" }
    }

    

    
    private fun step(name: String, timeoutMs: Long = 4_000, action: () -> Unit) {
        val t = Thread({ runCatching { action() }.onFailure { log("stop: $name failed: $it") } }, "stop-$name").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) log("stop: $name did not finish in ${timeoutMs / 1000} s, skipped")
    }

    fun stop() {
        if (state == State.STOPPED) return
        stopping = true
        setState(State.STOPPING)
        log("stopping system")
        try {
            step("vibration") { vibration.stop() }
            step("battery", 8_000) { battery.stop() }
            step("camera") { camera.stop() }
            step("microphone") { microphone.stop() }
            step("bluetooth") { bluetooth.stop() }
            step("motion") { motion.stop() }
            step("sync", 8_000) { guestRunner.run(listOf("/system/bin/sync"), 5_000) }
            step("legacyBootWatch") { stopLegacyBootWatch() }
            step("kill") { killAll() }
            step("adb") { adb.stop() }
            setAdbd(false)
            step("props") { props.stop() }
            step("zygoteCompat") { zygoteCompat.stop() }
            step("tombstonedTrace") { tombstonedTrace.stop() }
            step("input") { input.stop() }
            step("frames") { frames.stop() }
            step("ril") { ril.stop() }
            step("vold") { vold.stop() }
            step("audio") { audio.stop() }
            step("net") { net.stop() }
            step("netdebug") { netDebug.stop(); dns.debugger = null }
            step("dns") { dns.stop() }
            step("events") { events.stop() }
            step("logd") { logd.stop() }
            step("lights") { lights.stop() }
            step("sensors") { sensors.stop() }
            step("mic") { mic.stop() }
            step("cameraFeed") { cameraFeed.stop() }
            step("gl") { GlHost.stop() }
            step("stubs") { extraStubs.forEach { it.stop() }; extraStubs.clear() }
            step("lmk") { lmk?.stop(); lmk = null }
        } finally {
            runCatching { Keeper.release(ctx) }
            setState(State.STOPPED)
        }
    }

    private fun killAll() {
        val list = synchronized(procs) { procs.values.toList() }
        for (g in pgids) runCatching { android.system.Os.kill(-g, android.system.OsConstants.SIGKILL) }
        list.forEach { runCatching { it.destroyForcibly() } }
        synchronized(procs) { procs.clear() }
        pgids.clear()
        killLeftovers()
    }

    
    fun killLeftovers(): Int {
        val mine = AProcess.myPid()
        val marker = "/images/${img.id}/"
        val roots = setOf("AEMU64_ROOT=${paths.root.absolutePath}", "AEMU64_ROOT=${paths.root.canonicalPath}")
        val helpers = setOf(VmSlots.binderProcessName(ctx, slot), VmSlots.glProcessName(ctx, slot))
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == mine) return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1).replace('\u0000', ' ') }.getOrNull() ?: return@forEach
            val helper = cmd.trim() in helpers
            val engine = cmd.contains("libaemu64") || cmd.contains("libbinderd64") ||
                (cmd.contains("/images/") && (cmd.contains("libqemu") || cmd.contains("libbinderd") || cmd.contains("libdhdrun") || cmd.contains("libglserverd")))
            val ours = helper || (engine && (cmd.contains(marker) || runCatching {
                String(File(d, "environ").readBytes(), Charsets.ISO_8859_1).split(0.toChar()).any { it in roots }
            }.getOrDefault(false)))
            if (ours) { runCatching { AProcess.sendSignal(pid, 9) }; n++ }
        }
        if (n > 0) log("cleaned up processes from previous run: $n")
        return n
    }

    

    fun bootSeconds(): Long = if (bootAt == 0L) 0 else ((if (bootDoneAt > 0) bootDoneAt else System.currentTimeMillis()) - bootAt) / 1000

    
    fun guestShell(cmd: String, timeoutMs: Long = 60_000): String {
        if (engine == Engine.N64) return guestRunner.run(listOf("/system/bin/sh", "-c", shellPrefix + cmd), timeoutMs).second
        val r = guestRunner
        val path = GuestRunner.shellPath(r.env()["PATH"])
        return r.run(listOf("/system/bin/sh", "-c", GuestRunner.scriptFns(paths.root) + cmd), timeoutMs, mapOf("PATH" to path)).second
    }

    fun rotateGuest(): Pair<Int, String> = guestRunner.run(listOf(appProcess,
        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin", "app.aemu.setup.RotationCtl"),
        15_000, mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))

    
    val shellPrefix: String get() = "export PATH=/system/bin:/system/xbin:\$PATH; $toolWrappers"

    
    private val toolWrappers: String by lazy {
        val stubs = "/system/framework/aemu-stubs.jar"
        if (!File(paths.root, stubs.removePrefix("/")).isFile) return@lazy ""
        val sb = StringBuilder()
        File(paths.root, "system/bin").listFiles()?.forEach { f ->
            if (!f.isFile || f.length() > 8_000 || !f.name.matches(Regex("[a-z][a-z0-9_]*"))) return@forEach
            val text = runCatching { f.readText(Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            val cls = Regex("app_process\\S*\\s+\\S+\\s+([A-Za-z_][\\w.$]*\\.[A-Z][\\w$]*)").find(text)?.groupValues?.get(1) ?: return@forEach
            val jar = Regex("framework/([\\w.-]+\\.jar)").find(text)?.groupValues?.get(1) ?: return@forEach
            sb.append("${f.name}() { CLASSPATH=/system/framework/$jar:$stubs app_process /system/bin app.aemu.stub.Run $cls \"\$@\"; }; ")
        }
        sb.toString()
    }

    fun installApk(apkOnSdcard: String): String = guestShell("pm install -r $apkOnSdcard", 600_000)

    /**
     * Stage an APK inside the guest's own filesystem and install it from there.
     *
     * Installing from the shared vfat SD card ("/sdcard/aemu-install.apk") makes the
     * guest package manager fail with INSTALL_FAILED_INVALID_APK: the file there is
     * exposed with the host's ownership/mode rather than the guest's shell user, so
     * installd cannot read/parse it. /data/local/tmp is a real guest directory and is
     * the path the project's guest tests install from after a chmod 0644.
     */
    fun installApkFrom(input: InputStream): String {
        val apk = File(paths.root, STAGED_APK_PATH.removePrefix("/"))
        apk.parentFile?.mkdirs()
        if (apk.exists() && !apk.delete()) apk.writeBytes(ByteArray(0))
        input.use { i -> FileOutputStream(apk).use { o -> i.copyTo(o) } }
        runCatching { android.system.Os.chmod(apk.absolutePath, "644".toInt(8)) }
        return guestShell("pm install -r $STAGED_APK_PATH", 300_000)
    }

    companion object {
        const val STAGED_APK_PATH = "/data/local/tmp/aemu-install.apk"
    }

    private fun pidOf(p: Process): Int? {
        var c: Class<*>? = p.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == "pid" }
            if (f != null) { f.isAccessible = true; return f.getInt(p) }
            c = c.superclass
        }
        return null
    }

    
    private fun pure32Abilist32(): String {
        val all = HashMap<String, String>()
        for (n in listOf("system/build.prop", "vendor/default.prop", "vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
            "product/etc/build.prop", "product/build.prop", "system_ext/etc/build.prop", "system_ext/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> if (v.isNotBlank()) all.putIfAbsent(k, v.trim()) } }
        }
        val order = (all["ro.product.property_source_order"] ?: "product,product_services,odm,vendor,system").split(',').map { it.trim() }
        val direct = all["ro.product.cpu.abilist32"]
            ?: all["ro.vendor.product.cpu.abilist32"]
            ?: order.firstNotNullOfOrNull { all["ro.product.$it.cpu.abilist32"] }
        if (direct != null) return direct
        fun is32(a: String) = !a.contains("64")
        val combined = (all["ro.product.cpu.abilist"] ?: order.firstNotNullOfOrNull { all["ro.product.$it.cpu.abilist"] })
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && is32(it) }
        if (!combined.isNullOrEmpty()) return combined.joinToString(",")
        val abi = (all["ro.product.cpu.abi"] ?: all["ro.product.cpu.abi2"])?.takeIf { is32(it) }
        return if (abi != null) NativeAbiLists.pure32FromAbi(img.arch, abi) else NativeAbiLists.pure32Fallback(img.arch)
    }

    
    private fun deriveIdentity(later: MutableMap<String, String>) {
        val all = LinkedHashMap<String, String>()
        for (n in listOf("system/build.prop", "vendor/default.prop", "vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
            "product/etc/build.prop", "product/build.prop", "product_services/build.prop", "system_ext/etc/build.prop", "system_ext/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> all.putIfAbsent(k, v) } }
        }
        
        for (n in listOf("odm/etc/build.prop", "odm/build.prop", "product/etc/build.prop", "product/build.prop", "product_services/build.prop",
            "system_ext/etc/build.prop", "system_ext/build.prop", "mi_ext/etc/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> later.putIfAbsent(k, v) } }
        }
        val order = (all["ro.product.property_source_order"] ?: "product,product_services,odm,vendor,system").split(',')
        for (what in listOf("brand", "device", "manufacturer", "model", "name")) {
            if (all.containsKey("ro.product.$what")) continue
            val v = order.firstNotNullOfOrNull { all["ro.product.${it.trim()}.$what"]?.takeIf { s -> s.isNotEmpty() } } ?: continue
            all["ro.product.$what"] = v; later.putIfAbsent("ro.product.$what", v)
        }
        
        
        
        
        
        
        if (all["ro.board.platform"].isNullOrEmpty() && later["ro.board.platform"].isNullOrEmpty()) {
            val board = listOf("ro.product.board", "ro.hardware.chipname", "ro.boot.hardware.platform", "ro.vendor.product.board")
                .firstNotNullOfOrNull { k -> (all[k] ?: later[k])?.takeIf { it.isNotEmpty() } }
            
            val ids = listOf("ro.product.device", "ro.product.name", "ro.product.system.device", "ro.product.system.name", "ro.product.model", "ro.product.system.model")
                .mapNotNull { k -> (all[k] ?: later[k])?.takeIf { it.isNotEmpty() } }
            val kalama = ids.any { id -> listOf("dm1q", "dm2q", "dm3q", "SM-S911", "SM-S916", "SM-S918").any { id.startsWith(it) } }
            
            
            val exynos2200 = ids.any { id -> listOf("r0s", "g0s", "b0s").any { id.startsWith(it) } ||
                Regex("^SM-S90[168][BE]").containsMatchIn(id) }
            val soc = board ?: if (kalama) "kalama" else if (exynos2200) "exynos2200" else null
            if (soc != null) { later["ro.board.platform"] = soc; log("props: no ro.board.platform in the firmware, set to $soc") }
            
            
            
            if (kalama) {
                for ((k, v) in listOf("ro.hardware.chipname" to "SM8550", "ro.soc.manufacturer" to "QTI", "ro.soc.model" to "SM8550"))
                    if ((all[k] ?: later[k]).isNullOrEmpty()) { later[k] = v; log("props: $k=$v (the firmware has none)") }
            }
            else if (exynos2200) {
                
                for ((k, v) in listOf("ro.hardware.chipname" to "exynos2200", "ro.soc.manufacturer" to "Samsung", "ro.soc.model" to "S5E9925",
                    "ro.hardware" to "s5e9925", "ro.product.board" to "exynos2200"))
                    if ((all[k] ?: later[k]).isNullOrEmpty()) { later[k] = v; log("props: $k=$v (the firmware has none)") }
            }
            else log("props: no ro.board.platform in the firmware and no board property to take it from")
        }
        if (!all.containsKey("ro.build.fingerprint")) {
            fun p(k: String) = all[k] ?: "unknown"
            later.putIfAbsent("ro.build.fingerprint", p("ro.product.brand") + "/" + p("ro.product.name") + "/" + p("ro.product.device") + ":" +
                p("ro.build.version.release") + "/" + p("ro.build.id") + "/" + p("ro.build.version.incremental") + ":" + p("ro.build.type") + "/" + p("ro.build.tags"))
        }
    }

    private fun gmtZone(): String {
        val h = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000
        return if (h == 0) "Etc/GMT" else "Etc/GMT" + (if (h > 0) "-$h" else "+${-h}")
    }

    private fun isLinkTo(f: File, target: String) = runCatching { android.system.Os.readlink(f.absolutePath) == target }.getOrDefault(false)

    private fun stamp() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}


private const val RECOVERY_EARLY_MS = 8_000L
