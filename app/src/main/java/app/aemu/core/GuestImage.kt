
package app.aemu.core

import org.json.JSONArray
import org.json.JSONObject


data class VmSettings(
    val width: Int = 540,
    val height: Int = 960,
    val density: Int = 240,
    
    val gpu: Boolean = true,
    
    val hwui: Boolean = true,
    
    val jit: Boolean = true,
    
    val fullDexopt: Boolean = false,
    
    val fbHz: Int = 60,
    
    val touchHz: Int = 60,
    
    val netProxy: Boolean = true,
    
    val netDebug: Boolean = false,
    val lowRam: Boolean = false,
    
    val memoryMode: String = MemoryMode.DEFAULT.id,
    val showFrame: Boolean = false,
    val showNavBar: Boolean = true,
    val hideMenuButton: Boolean = false,
    val navButtons: String = NavControls.DEFAULT_BUTTONS,
    val trackball: Boolean = false,
    val trackballDpad: Boolean = false,
    val trackballStepDp: Int = 18,
    
    val mouseKeyboard: Boolean = false,
    
    val keypad: Boolean = false,
    val keepScreenOn: Boolean = true,
    
    val showLed: Boolean = true,
    val vibration: Boolean = true,
    val hostBattery: Boolean = true,
    
    val camera: Boolean = false,
    
    val microphone: Boolean = false,
    
    val bluetooth: Boolean = false,
    
    val sfTombstone: Boolean = false,
    val motionSensors: Boolean = false,
    
    val disableSensors: Boolean? = null,
    
    val guestNavBar: Boolean = false,
    
    val gyroLock: Int = GyroLock.DISABLED,
    val hostResolution: Boolean = false,
    val skipSetupWizard: Boolean = false,
    val disableGoogleApps: Boolean = false,
    val mtMode: Int = 0,
    
    val legacyEngine: Boolean = false,
    
    val ramMb: Int = 0,
    
    val radio: Boolean = true,
    
    val native32: Boolean = false,
    
    val translate32: Boolean = true,
    
    val imei: String = "",
    
    val serial: String = "",
    
    val kernel: String = "",
    
    val kernelBranch: String = "",
    
    val kernelCommit: String = "",
    
    val kernelUser: String = "",
    
    val kernelHost: String = "",
    
    val kernelCompiler: String = "",
    
    val kernelBuild: String = "",
    
    val kernelFlags: String = "",
    
    val kernelDate: String = "",
    
    val baseband: String = "",
    
    val networkType: Int = NetworkType.DEFAULT,
    
    val cdma: Boolean? = null,
    
    val qemuArgs: String = "",
    
    val straceServices: String = "",
    
    val hardware: String = "",
    
    val wifi: Boolean = false,
    
    val wifiSsid: String = WifiEmu.DEFAULT_SSID,
    
    val wifiPassword: String = "",
    
    val sim: SimCard = SimCard(),
    
    val sim2: SimCard = SimCard(present = false),
    val dualSim: Boolean = false,
    
    val wifiSignal: Int = WifiEmu.MAX_BARS,
    
    val wifiNeighbors: Boolean = true,
    
    val thermalPassthrough: Boolean = false,
    
    val thermalTemp: Int? = null,
    
    val disableWatchdog: Boolean = false,
    
    val skipPermissionWait: Boolean = false,
    
    val useArtBootImage: Boolean = false,

    val spoofSoc: String = "",

    val spoofGpu: String = "",

    val spoofStorageGb: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("density", density)
        .put("gpu", gpu).put("hwui", hwui).put("jit", jit).put("fullDexopt", fullDexopt).put("fbHz", fbHz)
        .put("touchHz", touchHz).put("netProxy", netProxy).put("netDebug", netDebug).put("lowRam", lowRam).put("memoryMode", memoryMode)
        .put("showFrame", showFrame).put("showNavBar", showNavBar)
        .put("hideMenuButton", hideMenuButton)
        .put("navButtons", NavControls.encode(NavControls.parse(navButtons)))
        .put("trackball", trackball).put("trackballDpad", trackballDpad)
        .put("trackballStepDp", trackballStepDp.coerceIn(4, 48))
        .put("mouseKeyboard", mouseKeyboard).put("keypad", keypad)
        .put("keepScreenOn", keepScreenOn).put("showLed", showLed).put("native32", native32).put("translate32", translate32).put("mtMode", mtMode).put("legacyEngine", legacyEngine)
        .put("vibration", vibration)
        .put("hostBattery", hostBattery)
        .put("camera", camera)
        .put("microphone", microphone)
        .put("bluetooth", bluetooth)
        .put("sfTombstone", sfTombstone)
        .put("motionSensors", motionSensors).put("disableSensors", disableSensors).put("guestNavBar", guestNavBar).put("gyroLock", gyroLock).put("hostResolution", hostResolution)
        .put("skipSetupWizard", skipSetupWizard)
        .put("disableGoogleApps", disableGoogleApps)
        .put("ramMb", ramMb).put("radio", radio).put("imei", imei).put("serial", serial).put("kernel", kernel).put("kernelBranch", kernelBranch).put("kernelCommit", kernelCommit).put("kernelUser", kernelUser).put("kernelHost", kernelHost).put("kernelCompiler", kernelCompiler).put("kernelBuild", kernelBuild).put("kernelFlags", kernelFlags).put("kernelDate", kernelDate).put("baseband", baseband).put("networkType", networkType).apply { if (cdma != null) put("cdma", cdma) }.put("qemuArgs", qemuArgs).put("straceServices", straceServices)
        .put("hardware", hardware)
        .put("wifi", wifi).put("wifiSsid", wifiSsid).put("wifiPassword", wifiPassword)
        .put("sim", sim.toJson()).put("sim2", sim2.toJson()).put("dualSim", dualSim)
        .put("wifiSignal", wifiSignal).put("wifiNeighbors", wifiNeighbors)
        .put("thermalPassthrough", thermalPassthrough).put("thermalTemp", thermalTemp ?: JSONObject.NULL)
        .put("disableWatchdog", disableWatchdog)
        .put("skipPermissionWait", skipPermissionWait)
        .put("useArtBootImage", useArtBootImage)
        .put("spoofSoc", spoofSoc).put("spoofGpu", spoofGpu).put("spoofStorageGb", spoofStorageGb)

    companion object {
        const val NAV_DEFAULT = "back,home,recents,menu"
        val NAV_ALL = listOf("back", "home", "recents", "menu", "search", "voldown", "volup", "power", "dpad")

        
        const val DEFAULT_IMEI = "490154203237518"

        
        const val DEFAULT_SERIAL = "0123456789abcdef"

        
        const val SERIAL_MAX = 32
        
        val HARDWARE_PRESETS = listOf("goldfish", "ranchu", "qcom")

        
        const val HARDWARE_MAX = 32
        fun cleanHardware(v: String): String =
            v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }.take(HARDWARE_MAX)

        const val DEFAULT_KERNEL = "3.0.31-aemu"
        const val DEFAULT_BASEBAND = "AEmulator"
        const val KERNEL_MAX = 120
        const val BASEBAND_MAX = 64

        
        private fun cleanLine(v: String, max: Int): String = v.filter { it in ' '..'~' }.take(max)
        fun cleanKernel(v: String): String = cleanLine(v, KERNEL_MAX)
        
        const val BANNER_MAX = 512
        
        fun cleanBanner(v: String): String = cleanLine(v, BANNER_MAX)
        const val TOKEN_MAX = 32
        
        fun cleanToken(v: String, max: Int): String = v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' || it == '+' }.take(max)
        
        
        fun cleanCompiler(v: String): String = cleanLine(v, 80).filter { it != '#' }.trim()
        fun cleanText(v: String, max: Int): String = cleanLine(v, max).filter { it != '(' && it != ')' && it != '#' }.trim()
        fun cleanBaseband(v: String): String = cleanLine(v, BASEBAND_MAX)

        
        fun kernelRelease(v: String): String {
            val t = cleanKernel(v).trim().removePrefix("Linux version").trim()
            return t.substringBefore(' ').ifEmpty { DEFAULT_KERNEL }
        }

        private fun isFullLine(v: String) = cleanKernel(v).trim().startsWith("Linux version")
        
        fun kernelCustom(s: VmSettings): Boolean = listOf(s.kernel, s.kernelBranch, s.kernelCommit, s.kernelUser, s.kernelHost,
            s.kernelCompiler, s.kernelBuild, s.kernelFlags, s.kernelDate).any { it.isNotBlank() }
        
        fun kernelRelease(s: VmSettings): String {
            val base = kernelRelease(s.kernel)
            if (isFullLine(s.kernel)) return base
            return base + (if (s.kernelBranch.isNotBlank()) "-" + s.kernelBranch.trim('-') else "") +
                (if (s.kernelCommit.isNotBlank()) "-g" + s.kernelCommit.removePrefix("g") else "")
        }
        
        fun procVersion(s: VmSettings): String {
            if (isFullLine(s.kernel)) return procVersion(s.kernel)
            val user = s.kernelUser.ifBlank { "aemu" }; val host = s.kernelHost.ifBlank { "aemu" }
            
            
            
            val cc = s.kernelCompiler.ifBlank { "gcc version 4.6" }.trim() + " "
            val build = s.kernelBuild.ifBlank { "1" }
            val flags = s.kernelFlags.ifBlank { "SMP PREEMPT" }
            val date = s.kernelDate.ifBlank { "Thu Jan 1 00:00:00 UTC 2026" }
            return "Linux version ${kernelRelease(s)} ($user@$host) ($cc) #$build ${flags.trim()} $date".replace("  ", " ") + "\n"
        }

        
        fun procVersion(v: String): String {
            val t = cleanKernel(v).trim()
            return if (t.startsWith("Linux version")) t + "\n"
            else "Linux version ${kernelRelease(t).ifEmpty { DEFAULT_KERNEL }} (aemu@aemu) (gcc version 4.6 ) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026\n"
        }

        
        fun straceNames(v: String): Set<String> =
            v.split(',', ' ', '\t', ';', '\n').map { n -> n.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' || it == '.' } }
                .filter { it.isNotEmpty() && it != "." && it != ".." }.toCollection(LinkedHashSet())

        fun cleanSerial(v: String): String = v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(SERIAL_MAX)

        
        fun randomSerial(): String = (1..16).joinToString("") { "0123456789abcdef"[(0..15).random()].toString() }

        
        fun randomImei(): String {
            val d = IntArray(14) { if (it < 2) intArrayOf(3, 5)[it] else (0..9).random() }
            var sum = 0
            for (i in 0 until 14) { var x = d[i]; if (i % 2 == 1) { x *= 2; if (x > 9) x -= 9 }; sum += x }
            return d.joinToString("") + ((10 - sum % 10) % 10)
        }

        fun fromJson(o: JSONObject?): VmSettings {
            if (o == null) return VmSettings()
            val d = VmSettings()
            return VmSettings(
                width = o.optInt("width", d.width),
                height = o.optInt("height", d.height),
                density = o.optInt("density", d.density),
                gpu = o.optBoolean("gpu", d.gpu),
                hwui = o.optBoolean("hwui", d.hwui),
                jit = o.optBoolean("jit", d.jit),
                fullDexopt = o.optBoolean("fullDexopt", d.fullDexopt),
                fbHz = o.optInt("fbHz", d.fbHz),
                touchHz = o.optInt("touchHz", d.touchHz),
                netProxy = o.optBoolean("netProxy", d.netProxy),
                netDebug = o.optBoolean("netDebug", d.netDebug),
                lowRam = o.optBoolean("lowRam", d.lowRam),
                memoryMode = MemoryMode.parse(o.optString("memoryMode", d.memoryMode)).id,
                showFrame = o.optBoolean("showFrame", d.showFrame),
                showNavBar = o.optBoolean("showNavBar", d.showNavBar),
                hideMenuButton = o.optBoolean("hideMenuButton", d.hideMenuButton),
                navButtons = NavControls.encode(NavControls.parse(o.optString("navButtons", d.navButtons))),
                trackball = o.optBoolean("trackball", false),
                trackballDpad = o.optBoolean("trackballDpad", false),
                trackballStepDp = o.optInt("trackballStepDp", 18).coerceIn(4, 48),
                mouseKeyboard = o.optBoolean("mouseKeyboard", false),
                keypad = o.optBoolean("keypad", false),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                showLed = o.optBoolean("showLed", d.showLed),
                vibration = o.optBoolean("vibration", d.vibration),
                hostBattery = o.optBoolean("hostBattery", d.hostBattery),
                camera = o.optBoolean("camera", d.camera),
                microphone = o.optBoolean("microphone", d.microphone),
                bluetooth = o.optBoolean("bluetooth", d.bluetooth),
                sfTombstone = o.optBoolean("sfTombstone", d.sfTombstone),
                motionSensors = o.optBoolean("motionSensors", false),
                disableSensors = if (o.has("disableSensors")) o.optBoolean("disableSensors") else null,
                guestNavBar = o.optBoolean("guestNavBar", d.guestNavBar),
                gyroLock = GyroLock.sanitize(o.optInt("gyroLock", GyroLock.DISABLED)),
                hostResolution = o.optBoolean("hostResolution", false),
                skipSetupWizard = o.optBoolean("skipSetupWizard", d.skipSetupWizard),
                disableGoogleApps = o.optBoolean("disableGoogleApps", false),
                mtMode = o.optInt("mtMode", d.mtMode),
                legacyEngine = o.optBoolean("legacyEngine", false),
                ramMb = o.optInt("ramMb", 0),
                radio = o.optBoolean("radio", true),
                native32 = o.optBoolean("native32", false),
                translate32 = o.optBoolean("translate32", true),
                imei = o.optString("imei", ""),
                serial = cleanSerial(o.optString("serial", "")),
                kernel = cleanKernel(o.optString("kernel", "")),
                kernelBranch = cleanToken(o.optString("kernelBranch", ""), TOKEN_MAX),
                kernelCommit = cleanToken(o.optString("kernelCommit", ""), 40),
                kernelUser = cleanToken(o.optString("kernelUser", ""), TOKEN_MAX),
                kernelHost = cleanToken(o.optString("kernelHost", ""), 64),
                kernelCompiler = cleanCompiler(o.optString("kernelCompiler", "")),
                kernelBuild = o.optString("kernelBuild", "").filter { it in '0'..'9' }.take(6),
                kernelFlags = cleanText(o.optString("kernelFlags", ""), 40),
                kernelDate = cleanText(o.optString("kernelDate", ""), 40),
                baseband = cleanBaseband(o.optString("baseband", "")),
                networkType = NetworkType.normalize(o.optInt("networkType", d.networkType)),
                cdma = if (o.has("cdma") && !o.isNull("cdma")) o.optBoolean("cdma") else null,
                qemuArgs = o.optString("qemuArgs", ""),
                straceServices = o.optString("straceServices", ""),
                hardware = cleanHardware(o.optString("hardware", "")),
                wifi = o.optBoolean("wifi", d.wifi),
                wifiSsid = WifiEmu.cleanSsid(o.optString("wifiSsid", d.wifiSsid)).ifEmpty { WifiEmu.DEFAULT_SSID },
                wifiPassword = WifiEmu.cleanPassword(o.optString("wifiPassword", "")),
                sim = SimCard.fromJson(o.optJSONObject("sim")),
                sim2 = o.optJSONObject("sim2")?.let { SimCard.fromJson(it) } ?: d.sim2,
                dualSim = o.optBoolean("dualSim", false),
                wifiSignal = o.optInt("wifiSignal", d.wifiSignal).coerceIn(1, WifiEmu.MAX_BARS),
                wifiNeighbors = o.optBoolean("wifiNeighbors", d.wifiNeighbors),
                thermalPassthrough = o.optBoolean("thermalPassthrough", d.thermalPassthrough),
                thermalTemp = if (o.has("thermalTemp") && !o.isNull("thermalTemp")) o.optInt("thermalTemp").coerceIn(ThermalSysfs.MIN_MILLI_C, ThermalSysfs.MAX_MILLI_C) else null,
                disableWatchdog = o.optBoolean("disableWatchdog", d.disableWatchdog),
                skipPermissionWait = o.optBoolean("skipPermissionWait", d.skipPermissionWait),
                useArtBootImage = o.optBoolean("useArtBootImage", d.useArtBootImage),
                spoofSoc = cleanToken(o.optString("spoofSoc", ""), TOKEN_MAX),
                spoofGpu = cleanToken(o.optString("spoofGpu", ""), TOKEN_MAX),
                spoofStorageGb = o.optString("spoofStorageGb", "").filter { it in '0'..'9' }.take(4),
            )
        }
    }
}


data class GuestService(
    val name: String,
    val argv: List<String>,
    
    val sockets: Map<String, String> = emptyMap(),
    val uid: Int = 0,
    val gid: Int = 0,
    
    val waitSocket: String? = null,
    val delayMs: Long = 0,
    val restart: Boolean = false,
    
    val optional: Boolean = false,
    
    val onDemand: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("optional", optional).put("onDemand", onDemand)
        .put("name", name)
        .put("argv", JSONArray(argv))
        .put("sockets", JSONObject(sockets as Map<*, *>))
        .put("uid", uid).put("gid", gid)
        .put("waitSocket", waitSocket ?: JSONObject.NULL)
        .put("delayMs", delayMs).put("restart", restart)

    companion object {
        fun fromJson(o: JSONObject): GuestService {
            val argv = o.getJSONArray("argv").let { a -> List(a.length()) { a.getString(it) } }
            val socks = o.optJSONObject("sockets")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } } ?: emptyMap()
            return GuestService(
                name = o.getString("name"),
                argv = argv,
                sockets = socks,
                uid = o.optInt("uid", 0),
                gid = o.optInt("gid", 0),
                waitSocket = if (o.isNull("waitSocket")) null else o.optString("waitSocket"),
                delayMs = o.optLong("delayMs", 0),
                restart = o.optBoolean("restart", false),
                optional = o.optBoolean("optional", false),
                onDemand = o.optBoolean("onDemand", false),
            )
        }
    }
}


data class GuestImage(
    val id: String,
    val name: String,
    val release: String,
    val api: Int,
    val brand: String,
    val model: String,
    val skin: String,
    val engine: Engine,
    val abi: String = "armeabi-v7a",
    
    val arch: GuestArch = GuestArch.ARM,
    val bootclasspath: String,
    val exports: Map<String, String>,
    val dirs: List<String>,
    val services: List<GuestService>,
    val sdcardPath: String,
    
    val volumes: List<String> = emptyList(),
    val settings: VmSettings,
    val createdAt: Long,
    val sizeBytes: Long = 0,
    val sourceName: String = "",
    val runtime: String = "dalvik",
    val warnings: List<String> = emptyList(),
    val lastBootMs: Long = 0,
    val bootCount: Int = 0,
    
    val profileVersion: Int = 0,
    
    val baseId: String = "",
    
    val buildId: String = "",
    
    val oneTimeNote: String = "",
) {
    val displayVersion: String get() = "Android $release (API $api)" + if (buildId.isNotBlank()) " (Build $buildId)" else ""

    
    val sensorsOff: Boolean get() = sensorsOff(settings)
    fun sensorsOff(s: VmSettings): Boolean = SensorHalApi.sensorsOff(s.disableSensors, api, skin, brand, model)

    
    fun effective(binder64: Boolean = false): GuestImage = copy(engine = when {
        
        arch == GuestArch.X86 && api < GuestArch.X86.n64MinApi -> Engine.KK
        
        
        arch.foreign -> Engine.N64
        abi == "arm64-v8a" -> Engine.N64
        settings.legacyEngine && api < 14 -> Engine.GB
        
        
        settings.translate32 && (api >= 26 || binder64) && abi.startsWith("armeabi") -> Engine.N64
        else -> Engine.KK
    })

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("release", release).put("api", api)
        .put("brand", brand).put("model", model).put("skin", skin)
        .put("engine", engine.id).put("abi", abi).put("arch", arch.id)
        .put("bootclasspath", bootclasspath)
        .put("exports", JSONObject(exports as Map<*, *>))
        .put("dirs", JSONArray(dirs))
        .put("services", JSONArray(services.map { it.toJson() }))
        .put("sdcardPath", sdcardPath)
        .put("volumes", JSONArray(volumes))
        .put("settings", settings.toJson())
        .put("createdAt", createdAt).put("sizeBytes", sizeBytes)
        .put("sourceName", sourceName).put("runtime", runtime)
        .put("warnings", JSONArray(warnings))
        .put("lastBootMs", lastBootMs).put("bootCount", bootCount)
        .put("profileVersion", profileVersion)
        .put("baseId", baseId)
        .put("buildId", buildId)
        .put("oneTimeNote", OneTimeVmNote.normalize(oneTimeNote))

    companion object {
        fun fromJson(o: JSONObject): GuestImage {
            val ex = o.optJSONObject("exports")
            val exports = ex?.keys()?.asSequence()?.associateWith { ex.getString(it) } ?: emptyMap()
            val dirs = o.optJSONArray("dirs")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val svcs = o.optJSONArray("services")?.let { a -> List(a.length()) { GuestService.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            val warn = o.optJSONArray("warnings")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return GuestImage(
                id = o.getString("id"),
                name = o.optString("name", "Android"),
                release = o.optString("release", "?"),
                api = o.optInt("api", 19),
                brand = o.optString("brand", ""),
                model = o.optString("model", ""),
                skin = o.optString("skin", "AOSP"),
                engine = Engine.byId(o.optString("engine")),
                abi = o.optString("abi", "armeabi-v7a"),
                arch = GuestArch.byId(o.optString("arch", "arm")),
                bootclasspath = o.optString("bootclasspath", ""),
                exports = exports,
                dirs = dirs,
                services = svcs,
                sdcardPath = o.optString("sdcardPath", "/mnt/sdcard"),
                volumes = o.optJSONArray("volumes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                settings = VmSettings.fromJson(o.optJSONObject("settings")),
                createdAt = o.optLong("createdAt", 0),
                sizeBytes = o.optLong("sizeBytes", 0),
                sourceName = o.optString("sourceName", ""),
                runtime = o.optString("runtime", "dalvik"),
                warnings = warn,
                lastBootMs = o.optLong("lastBootMs", 0),
                bootCount = o.optInt("bootCount", 0),
                profileVersion = o.optInt("profileVersion", 0),
                baseId = o.optString("baseId", ""),
                buildId = o.optString("buildId", ""),
                oneTimeNote = OneTimeVmNote.normalize(o.optString("oneTimeNote", "")),
            )
        }
    }
}
