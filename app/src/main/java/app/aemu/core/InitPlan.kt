
package app.aemu.core

import java.io.File


object InitPlan {

    data class RcService(
        val name: String,
        val argv: List<String>,
        val sockets: MutableMap<String, String> = LinkedHashMap(),
        var user: String = "root",
        var group: String = "root",
        var oneshot: Boolean = false,
        var disabled: Boolean = false,
        var cls: String = "default",
    )

    data class Rc(
        val services: Map<String, RcService>,
        val exports: Map<String, String>,
        val dirs: List<String>,
        
        val setprops: Map<String, String> = emptyMap(),
        
        val shadowed: Map<String, List<RcService>> = emptyMap(),
    )

    
    
    
    fun hwRcFiles(root: File, props: Map<String, String>): List<File> {
        val zygote = "init.${props["ro.zygote"] ?: "zygote64_32"}.rc"
        return File(root, "system/etc/init/hw").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".rc") && (!it.name.startsWith("init.zygote") || it.name == zygote) }
            ?.sortedBy { if (it.name == "init.rc") "" else it.name } ?: emptyList()
    }

    fun parse(files: List<File>, props: Map<String, String> = emptyMap(), includeCharging: Boolean = false): Rc {
        val services = LinkedHashMap<String, RcService>()
        val shadowed = LinkedHashMap<String, MutableList<RcService>>()
        val exports = LinkedHashMap<String, String>()
        val condExports = LinkedHashMap<String, String>()
        
        
        
        val skip = Regex("""(?i)^(factory_init|(advanced_)?meta_init|init[.]charging|init[.]recovery|lpm|fota|FWUpgradeInit).*[.]rc$""")
        val dirs = ArrayList<String>()
        val setprops = LinkedHashMap<String, String>()
        for (f in files) {
            val chargingRc = f.name.startsWith("init.charging", true) || f.name.startsWith("lpm", true)
            if (!f.isFile || (skip.matches(f.name) && !(includeCharging && chargingRc))) continue
            var cur: RcService? = null
            var propTrigger = false
            var condOk: Boolean? = null
            for (raw in f.readLines()) {
                val ln = raw.trim()
                if (ln.isEmpty() || ln.startsWith("#")) continue
                val t = ln.split(Regex("\\s+"))
                when (t[0]) {
                    "service" -> {
                        cur = if (t.size >= 3) RcService(t[1], t.drop(2)).also {
                            
                            services[t[1]]?.let { old -> shadowed.getOrPut(t[1]) { ArrayList() }.add(old) }
                            services[t[1]] = it
                        } else null
                    }
                    "on" -> {
                        cur = null
                        val conds = t.drop(1).filter { it.startsWith("property:") || it.startsWith("early_property:") }
                        propTrigger = conds.isNotEmpty()
                        condOk = if (conds.isEmpty()) null else conds.all { c ->
                            val kv = c.substringAfter(':'); val k = kv.substringBefore('='); val v = kv.substringAfter('=', "*")
                            props[k]?.let { v == "*" || it == v } ?: false
                        }
                    }
                    "import" -> cur = null
                    "setprop" -> if (cur == null && !propTrigger && t.size >= 3 && !t[2].startsWith("$")) setprops.putIfAbsent(t[1], t.drop(2).joinToString(" ").removeSurrounding("\""))   
                    "export" -> if (t.size >= 3) when (condOk) {
                        null -> if (f == files.first() || t[1] !in exports) exports[t[1]] = t.drop(2).joinToString(" ")
                        true -> condExports[t[1]] = t.drop(2).joinToString(" ")
                        false -> {}
                    }
                    "mkdir" -> if (t.size >= 2) dirs.add(t[1])
                    
                    "socket" -> cur?.let { if (t.size >= 4) it.sockets[t[1]] = if (t[2] == "stream") t[3] else "${t[3]},${t[2]}" }
                    "user" -> cur?.let { if (t.size >= 2) it.user = t[1] }
                    "group" -> cur?.let { if (t.size >= 2) it.group = t[1] }
                    "oneshot" -> cur?.oneshot = true
                    "disabled" -> cur?.disabled = true
                    "class" -> cur?.let { if (t.size >= 2) it.cls = t[1] }
                }
            }
        }
        exports.putAll(condExports)
        return Rc(services, exports, dirs, setprops, shadowed)
    }

    
    private fun withProgramsInTree(rc: Rc, root: File): Rc {
        if (rc.shadowed.isEmpty()) return rc
        fun inTree(s: RcService) = File(root, s.argv.first().removePrefix("/")).isFile
        val services = LinkedHashMap(rc.services)
        for ((name, earlier) in rc.shadowed) {
            val last = services[name] ?: continue
            if (inTree(last)) continue
            earlier.lastOrNull { inTree(it) }?.let { services[name] = it }
        }
        return rc.copy(services = services)
    }

    private val AID = mapOf(
        "root" to 0, "system" to 1000, "radio" to 1001, "bluetooth" to 1002, "graphics" to 1003,
        "input" to 1004, "audio" to 1005, "camera" to 1006, "log" to 1007, "compass" to 1008,
        "mount" to 1009, "wifi" to 1010, "adb" to 1011, "install" to 1012, "media" to 1013,
        "dhcp" to 1014, "sdcard_rw" to 1015, "vpn" to 1016, "keystore" to 1017, "usb" to 1018,
        "drm" to 1019, "mdnsr" to 1020, "gps" to 1021, "media_rw" to 1023, "shell" to 2000, "net_bt_admin" to 3001,
        "inet" to 3003, "nobody" to 9999,
    )

    
    private data class Rule(val name: String, val alt: List<String> = emptyList(), val waitSocket: String? = null,
                            val delayMs: Long = 0, val restart: Boolean = false, val minApi: Int = 0)

    private val ORDER = listOf(
        Rule("servicemanager", delayMs = 1500),
        Rule("hwservicemanager", delayMs = 800, minApi = 26),
        Rule("vndservicemanager", delayMs = 500, minApi = 26),
        Rule("surfaceflinger", delayMs = 2000, minApi = 14),
        Rule("healthd", delayMs = 500, minApi = 19),
        Rule("keystore", delayMs = 300),
        Rule("installd", waitSocket = "installd"),
        Rule("netd", waitSocket = "netd"),
        Rule("drm", alt = listOf("drmserver"), delayMs = 500),
        Rule("media", alt = listOf("mediaserver"), delayMs = 1000, restart = true),
        Rule("zygote"),
        Rule("zygote_secondary", minApi = 21),
    )

    
    fun plan(parsed: Rc, api: Int, root: File): List<GuestService> {
        val rc = withProgramsInTree(parsed, root)
        val out = ArrayList<GuestService>()
        for (r in ORDER) {
            if (r.name == "zygote_secondary" && (rc.setprops["ro.zygote"] == "zygote64" || !File(root, "system/bin/app_process32").isFile)) continue
            val found = (listOf(r.name) + r.alt).firstNotNullOfOrNull { rc.services[it] }
            
            
            if (api in 1 until r.minApi && found == null) continue
            
            
            
            val zygote32Only = r.name == "zygote" &&
                !File(root, "system/bin/app_process").isFile && File(root, "system/bin/app_process32").isFile
            var def = found?.takeUnless { zygote32Only }?.let { toService(it, r) } ?: defaults(r.name, api, root)?.let {
                it.copy(waitSocket = r.waitSocket, delayMs = r.delayMs, restart = r.restart)
            } ?: continue
            
            if (r.name == "installd" && api >= 26) def = def.copy(waitSocket = null, delayMs = 300)
            
            if (r.name == "netd" && api >= 29) def = def.copy(waitSocket = null, delayMs = 500)
            if (!File(root, def.argv.first().removePrefix("/")).isFile) continue
            out.add(normalHealthd(def))
        }
        
        val known = ORDER.flatMap { listOf(it.name) + it.alt }.toSet() + setOf("surfaceflinger")
        val extras = rc.services.values.filter { sv ->
            val bin = sv.argv.first()
            val base = bin.substringAfterLast('/')
            
            (!sv.disabled || isStartedDisabledHal(sv)) && (!sv.oneshot || bin.substringBeforeLast('/').endsWith("/bin/hw")) && sv.cls != "charger" && !isLpm(sv.name, base, sv.cls) &&
                sv.name !in known && sv.name !in SKIP && base !in SKIP_BIN &&
                
                
                (base == "sensorhubservice" || SKIP_BIN.none { base == it || ((it.endsWith("_") || it.length >= 5) && base.startsWith(it)) }) && !base.endsWith(".sh") &&
                
                
                
                
                
                (bin.startsWith("/system/bin/") || bin.startsWith("/system/xbin/") || bin.startsWith("/vendor/bin/") || bin.startsWith("/apex/") ||
                    isHidlAllocator(bin)) &&
                File(root, bin.removePrefix("/")).isFile
        }.map { sv -> GuestService(sv.name, sv.argv, sv.sockets.toMap(), delayMs = 200, optional = true,
            
            
            
            uid = if (sv.name == "cameraserver") 1047 else 0, gid = if (sv.name == "cameraserver") 1047 else 0,
            
            
            onDemand = api >= 26 && sv.cls == "default") }
        
        
        val (hals, rest) = extras.partition { it.argv.first().substringBeforeLast('/').endsWith("/bin/hw") }
        val sf = out.indexOfFirst { it.name == "surfaceflinger" }.let { if (it < 0) out.size else it }
        out.addAll(sf, hals.map { it.copy(delayMs = 100, optional = false) })
        val z = out.indexOfFirst { it.name == "zygote" }.let { if (it < 0) out.size else it }
        out.addAll(z, rest)
        
        
        
        rc.services["hyos_spawner"]?.takeIf { api >= 34 && File(root, it.argv.first().removePrefix("/")).isFile }?.let { sv ->
            out.add(GuestService(sv.name, sv.argv, sv.sockets.toMap(), uid = 0, gid = AID[sv.group] ?: 1000, delayMs = 500, optional = true))
        }
        
        return BootMediaServices.withLegacyLogo(out, root, api)
    }

    
    private fun isThermalHal(sv: RcService): Boolean {
        val bin = sv.argv.first()
        return bin.substringBeforeLast('/').endsWith("/bin/hw") &&
            (sv.name.contains("thermal", true) || bin.substringAfterLast('/').contains("thermal-service", true))
    }

    
    private fun isVibratorHal(sv: RcService): Boolean {
        val bin = sv.argv.first()
        return bin.substringBeforeLast('/').endsWith("/bin/hw") &&
            (sv.name.contains("vibrator", true) || bin.substringAfterLast('/').contains("vibrator-service", true))
    }

    
    private fun isWifiHal(sv: RcService): Boolean {
        val bin = sv.argv.first()
        return bin.substringBeforeLast('/').endsWith("/bin/hw") &&
            (sv.name.startsWith("vendor.wifi_hal", true) || bin.substringAfterLast('/').startsWith("android.hardware.wifi@", true))
    }

    private fun isStartedDisabledHal(sv: RcService) = isThermalHal(sv) || isVibratorHal(sv) || isWifiHal(sv)

    
    
    private val LPM = Regex("""(?i)^(play)?lpm([_.-]?\w*)?$""")
    private fun isLpm(name: String, base: String, cls: String?) =
        LPM.matches(name) || LPM.matches(base) || cls.equals("lpm", true)

    
    val NEVER_START = setOf("common_time", "tf_daemon")

    private val HEALTHD_MODE_FLAGS = Regex("^-[rcn]+$")

    
    fun normalHealthd(s: GuestService): GuestService =
        if (s.name == "healthd" && s.argv.drop(1).any { HEALTHD_MODE_FLAGS.matches(it) })
            s.copy(argv = s.argv.take(1) + s.argv.drop(1).filterNot { HEALTHD_MODE_FLAGS.matches(it) })
        else s

    
    fun isHidlAllocator(bin: String): Boolean =
        bin.startsWith("/system_ext/bin/hw/android.hidl.allocator@") || bin.startsWith("/system/system_ext/bin/hw/android.hidl.allocator@")

    private val SKIP = setOf(
        
        "lpm",
        
        "kpoc_charger",
        
        "mtkbt",
        
        
        
        
        "aal",
        
        "mountd",
        "ueventd", "vold", "rild", "ril-daemon", "adbd", "debuggerd", "debuggerd64", "console", "dbus", "bluetoothd",
        "btld", "wpa_supplicant", "p2p_supplicant", "dhcpcd", "racoon", "mtpd", "qemud", "goldfish-setup", "goldfish-logcat",
        "dumpstate", "flash_recovery", "recovery", "redbend_ua", "sreadaheadd", "lpm", "lpmkey", "playlpm", "charger", "macloader",
        "mfgloader", "wlandutservice", "bt_dut_cmd", "bootanim", "samsungani", "playsound", BootMediaServices.LEGACY_LOGO, "sdcard", "fuse_sdcard0",
        "healthd", "logd", "lmkd", "watchdogd", "ril-daemon1", "ril-daemon2", "fota", "gpsd", "sensors", "akmd",
        "rmt_storage", "qmuxd", "netmgrd", "thermald", "mpdecision", "thermal-engine", "time_daemon", "diag", "common_time", "tf_daemon",
    )
    private val SKIP_BIN = setOf(
        "logwrapper", "aal", "sh", "rild", "vold", "wpa_supplicant", "dhcpcd", "bluetoothd", "hciattach", "btld", "adbd",
        "sdcard", "gpsd", "gps", "akmd", "orientationd", "geomagneticd", "sensor", "charging", "playlpm", "lpm", "lpmkey", "bootanimation",
        "rmt_storage", "qmuxd", "netmgrd", "thermald", "mpdecision", "thermal-engine", "fm_", "wifi", "wifi_", "gps_", "hostapd", "mediaserver",
        "common_time", "tf_daemon", "drexe", "npsmobex", "immvibed", "cbd", "smdexe", "ddexe", "mdm_helper", "rilproxy",
        
        "mm-qcamera", "qcamerasvr", "qseecomd", "bridgemgrd", "irsc_util", "mm-pp-daemon", "ks", "qcks", "efsks",
        "sensors.qcom", "location-mq", "xtwifi", "quipc", "usf_", "adsprpcd", "subsystem_ramdump",
    )

    private fun toService(s: RcService, r: Rule): GuestService {
        val name = when (r.name) { "media" -> "mediaserver"; "drm" -> "drmserver"; else -> r.name }
        return GuestService(
            name = name,
            argv = s.argv,
            sockets = s.sockets.toMap(),
            
            uid = 0,
            gid = 0,
            waitSocket = r.waitSocket,
            delayMs = r.delayMs,
            restart = r.restart,
        )
    }

    
    fun defaults(name: String, api: Int, root: File): GuestService? = when (name) {
        "servicemanager" -> GuestService("servicemanager", listOf("/system/bin/servicemanager"))
        "hwservicemanager" -> if (File(root, "system/bin/hwservicemanager").isFile)
            GuestService("hwservicemanager", listOf("/system/bin/hwservicemanager"), mapOf("hwservicemanager" to "0660")) else null
        "vndservicemanager" -> if (File(root, "system/bin/vndservicemanager").isFile)
            GuestService("vndservicemanager", listOf("/system/bin/vndservicemanager", "/dev/vndbinder")) else null
        "surfaceflinger" -> if (api >= 14) GuestService("surfaceflinger", listOf("/system/bin/surfaceflinger")) else null
        
        "healthd" -> listOf("/sbin/healthd", "/system/bin/healthd").firstOrNull { File(root, it.drop(1)).isFile }?.let { GuestService("healthd", listOf(it)) }
        
        "keystore" -> GuestService("keystore", listOf("/system/bin/keystore", "/data/misc/keystore"),
            if (api <= 17) mapOf("keystore" to "0666") else emptyMap())
        "installd" -> GuestService("installd", listOf("/system/bin/installd"), mapOf("installd" to "0600"))
        "netd" -> GuestService("netd", listOf("/system/bin/netd"),
            if (api >= 14) mapOf("netd" to "0660", "dnsproxyd" to "0660", "mdns" to "0660") else mapOf("netd" to "0660"))
        "media", "mediaserver" -> GuestService("mediaserver", listOf("/system/bin/mediaserver"))
        "drm", "drmserver" -> if (api >= 11) GuestService("drmserver", listOf("/system/bin/drmserver")) else null
        "zygote" -> {
            val app = when {
                File(root, "system/bin/app_process64").isFile -> "/system/bin/app_process64"
                File(root, "system/bin/app_process32").isFile && !File(root, "system/bin/app_process").isFile -> "/system/bin/app_process32"
                else -> "/system/bin/app_process"
            }
            
            GuestService("zygote", listOf(app, "-Xzygote", "/system/bin", "--zygote", "--start-system-server") + (if (api >= 29) listOf("--socket-name=zygote") else emptyList()),
                mapOf("zygote" to "0666") + (if (api >= 29) mapOf("usap_pool_primary" to "0660") else emptyMap()))
        }
        "zygote_secondary" -> {
            val app = if (File(root, "system/bin/app_process32").isFile) "/system/bin/app_process32" else null
            if (app != null && File(root, "system/bin/app_process64").isFile) {
                GuestService("zygote_secondary", listOf(app, "-Xzygote", "/system/bin", "--zygote", "--socket-name=zygote_secondary"), mapOf("zygote_secondary" to "0666") + (if (api >= 29) mapOf("usap_pool_secondary" to "0660") else emptyMap()))
            } else null
        }
        else -> null
    }

    
    fun optional(name: String, img: GuestImage, root: File): GuestService? = when (name) {
        "bootanim", "bootanimation", "samsungani", "playsound", BootMediaServices.LEGACY_LOGO -> BootMediaServices.resolve(root, name)
        else -> null
    }

    
    fun lazyAidl(iface: String, root: File): GuestService? {
        val dirs = listOf("system/etc/init", "system_ext/etc/init", "system/system_ext/etc/init", "product/etc/init", "system/product/etc/init",
            
            
            
            
            "vendor/etc/init", "system/vendor/etc/init", "odm/etc/init", "system/odm/etc/init").map { File(root, it) } +
            File(root, "apex").listFiles().orEmpty().map { File(it, "etc") }
        for (f in dirs.flatMap { d -> d.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".rc") }.sortedBy { it.name } }) {
            var name = ""; var argv = emptyList<String>()
            for (raw in runCatching { f.readLines() }.getOrDefault(emptyList())) {
                val t = raw.trim().split(Regex("\\s+"))
                if (t[0] == "service" && t.size >= 3) { name = t[1]; argv = t.drop(2) }
                else if (t[0] == "on") name = ""
                else if (t[0] == "interface" && t.size >= 3 && t[1] == "aidl" && t[2] == iface && name.isNotEmpty() &&
                    File(root, argv.first().removePrefix("/")).isFile) return GuestService(name, argv, uid = 0, gid = 0)
            }
        }
        return null
    }

    fun fallback(img: GuestImage, root: File): List<GuestService> =
        plan(Rc(emptyMap(), emptyMap(), emptyList()), img.api, root)
}
