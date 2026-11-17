package app.aemu.core


enum class Kernel64(
    val tag: String,
    val apis: IntRange,
    
    val treble: Boolean,
    
    val eglDir: String,
    
    val gles: String,
    
    val absentFeatures: List<String>,
    
    val trafficAccounting: Boolean = false,
    
    val noQemuPipe: Boolean = false,
    
    val binderVold: Boolean = false,
    
    val volumeAtUserStart: Boolean = false,
    
    val gpuBridge32: String? = null,
    
    val gpuBridge32Open: Boolean = false,
    
    val clientComposition: Boolean = false,
    
    val webViewSingleProcess: Boolean = false,
    
    val procFiles: Map<String, String> = emptyMap(),
    
    val eglName: String = "swiftshader",
    
    val eglCfgTags: Boolean = false,
    
    val taskMapsIsSelfMaps: Boolean = false,
    
    val exynosHandles: Boolean = false,
    
    val qcomHandles: Boolean = false,
    
    val irFollowsHal: Boolean = false,
    
    val wideAudioFormat: Boolean = false,
    
    val xmlSettings: Boolean = false,
    
    val bootImageInCache: Boolean = false,
    
    val ethernetStandIn: Boolean = false,
    
    val registeredNetwork: Boolean = false,
    
    val oneIncludePolicy: Boolean = false,
    
    val standInServices: Map<String, String> = emptyMap(),
    
    val sysFiles: Map<String, String> = emptyMap(),
    
    val dnsLayoutProbe: Boolean = false,
    
    val props: Map<String, String> = emptyMap(),
    
    val propDefaults: Map<String, String> = emptyMap(),
    
    val firmwareOwn: List<String> = emptyList(),
    
    val firmwareAside: List<String> = emptyList(),
    
    val cgroup2Root: Boolean = false,
    
    val cgroupRcDepth: Boolean = false,
    
    val cgroupUidParents: List<String> = emptyList(),
    
    val aconfigStorage: Boolean = false,
    
    val kernelPolicyUnread: Boolean = false,
    
    val gpuBridge64: Boolean = false,
    
    val gpuBridgeMode: String = "3",
    
    val partitionsAtRoot: Boolean = false,
    
    val bpfMapNotes: Boolean = false,
    
    val linkerConfigTool: String = "system/bin/linkerconfig",
    
    val mediaProfilesFallback: Boolean = false,
    
    val oneFingerprint: Boolean = false,
    
    val vendorHeapProps: Boolean = false,
    
    val derivedIdentity: Boolean = false,
    
    val ownVndkDir: String? = null,
    
    val ownLibDirs: Map<String, String> = emptyMap(),
    
    val manifestFragmentsAside: Boolean = false,
    
    val odmManifestFragmentsAside: Boolean = false,
    
    val lazyAidlServices: Boolean = false,
    
    val apexStandIn: Boolean = false,
    
    val webView64: Boolean = false,
    
    val bridgeAsEmulation: Boolean = false,
    
    val emulationDir: String? = null,
    
    val navBarRequired: Boolean = false,
    
    val audioPolicyXml: Boolean = false,
    
    val ramBudget: Boolean = false,
    
    val syncLog: Boolean = false,
    
    val bootHardwareProp: Boolean = false,
    
    val manifestFragmentsKept: List<String> = emptyList(),
    
    val trebleLinkerConfig: Boolean = false,
    
    val propAreaSize: Int = PropArea.SIZE,
    
    val ownVendorUnderGsi: Boolean = false,
) {
    
    A5_0("21", 21..21, registeredNetwork = true, eglCfgTags = true, trafficAccounting = true, treble = false, eglDir = "system/lib64/egl", gles = "1", absentFeatures = emptyList(),
        procFiles = mapOf("asound/cards" to "--- no soundcards ---\n")),
    
    
    A5_1("22", 22..22, registeredNetwork = true, dnsLayoutProbe = true, exynosHandles = true, wideAudioFormat = true, bootImageInCache = true, ethernetStandIn = true, eglCfgTags = true, taskMapsIsSelfMaps = true, trafficAccounting = true, treble = false, eglDir = "system/lib64/egl", gles = "1",
        absentFeatures = listOf("consumerir"),
        standInServices = mapOf("Exynos.HWCService" to "system/lib64/libsurfaceflinger.so"),
        sysFiles = mapOf(
            "power/cpufreq_table" to "1500000 1400000 1300000 1200000 1100000 1000000 900000 800000 700000 600000 500000 400000\n",
            "devices/14ac0000.mali/dvfs_table" to "772 700 600 544 420 350 266\n",
            "devices/platform/exynos7-devfreq-mif/devfreq/exynos7-devfreq-mif/available_frequencies" to
                "1552000 1456000 1264000 1068000 828000 676000 543000 416000 348000 276000\n",
            "devices/platform/exynos5-devfreq-mif/devfreq/exynos5-devfreq-mif/available_frequencies" to
                "825000 728000 633000 543000 413000 275000 206000 165000 138000\n"),
        procFiles = mapOf("asound/cards" to "--- no soundcards ---\n")),
    
    A6("23", 23..23, gpuBridge32 = "libGLES.so", gpuBridge32Open = true, gpuBridge64 = true, gpuBridgeMode = "3", registeredNetwork = true, exynosHandles = true, irFollowsHal = true, wideAudioFormat = true, xmlSettings = true, bootImageInCache = true, ethernetStandIn = true, treble = false, eglDir = "system/lib64/egl", gles = "1", absentFeatures = emptyList(),
        
        
        procFiles = mapOf("asound/cards" to "--- no soundcards ---\n"),
        
        
        
        props = mapOf("ro.vold.primary_physical" to "0")),
    
    A7_0("24", 24..24, registeredNetwork = true, qcomHandles = true, irFollowsHal = true, xmlSettings = true, treble = false, eglDir = "system/lib64/egl", gles = "1", absentFeatures = emptyList(),
        eglName = "emulation",
        procFiles = mapOf("asound/cards" to "--- no soundcards ---\n")),
    
    A7_1("25", 25..25, gpuBridge64 = true, gpuBridgeMode = "1", bridgeAsEmulation = true, registeredNetwork = true, qcomHandles = true, irFollowsHal = true, xmlSettings = true, treble = false, eglDir = "system/lib64/egl", gles = "1", absentFeatures = emptyList(),
        eglName = "emulation",
        procFiles = mapOf("asound/cards" to "--- no soundcards ---\n"),
        
        
        
        props = mapOf("pm.dexopt.first-boot" to "verify-at-runtime", "pm.dexopt.boot" to "verify-at-runtime")),
    
    A8_0("26", 26..26, gpuBridge64 = true, bridgeAsEmulation = true, emulationDir = "system/lib64/egl", navBarRequired = true, audioPolicyXml = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef"), trafficAccounting = true, noQemuPipe = true,
        
        
        
        
        
        props = mapOf("ro.treble.enabled" to "false", "debug.hwui.renderer" to "opengl", "ro.cp_system_other_odex" to "0"),
        
        
        
        
        
        
        
        procFiles = mapOf("wakelocks" to "name\tcount\texpire_count\twake_count\tactive_since\ttotal_time\tsleep_time\tmax_time\tlast_change\n"),
        
        
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libmediacodecservice.so"),
        
        
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so")),
        
        
        
        
    
    A8_1("27", 27..27, gpuBridge64 = true, bridgeAsEmulation = true, qcomHandles = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        
        
        
        
        
        
        
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n",
            
            
            
            
            
            "wakelocks" to "name\tcount\texpire_count\twake_count\tactive_since\ttotal_time\tsleep_time\tmax_time\tlast_change\n" +
                "aemu-idle\t1\t0\t0\t0\t1000000\t0\t1000000\t0\n"),
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef"), trafficAccounting = true, noQemuPipe = true,
        
        
        
        
        
        
        
        
        props = mapOf("ro.treble.enabled" to "false", "ro.property_service.version" to "2",
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract"),
        
        
        
        
        
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so",
            "vendor#lib#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    
    
    A9("28", 28..28, gpuBridge64 = true, webView64 = true, bridgeAsEmulation = true, qcomHandles = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n"),
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        props = mapOf("ro.treble.enabled" to "false", "ro.property_service.version" to "2",
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract"),
        
        
        
        propDefaults = mapOf("ro.vndk.version" to "27"),
        
        
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            "vendor#lib#hw#android.hardware.graphics.mapper@2.0-impl.so"),
        
        
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so",
            "vendor#lib#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A10("29", 29..29, gpuBridge64 = true, webView64 = true, qcomHandles = true, derivedIdentity = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        
        
        
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27",
            "dalvik.vm.jdwp-provider" to "none",
            
            "ro.property_service.version" to "2",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract",
            
            
            "ro.hardware.egl" to "swiftshader",
            
            "ro.frp.pst" to ""),
        
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n"),
        
        propDefaults = mapOf("ro.vndk.version" to "29"), ownVendorUnderGsi = true,
        
        
        ownLibDirs = mapOf("vendor#bin#hw#android.hardware.health@2.0-service.goldfish" to "vendor/lib64/aemu-health"),
        
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            "vendor#lib#hw#android.hardware.graphics.mapper@2.0-impl.so"),
        
        
        
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.audio@5.0-impl.so", "vendor#lib64#hw#android.hardware.audio.effect@5.0-impl.so",
            
            "vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so")),
    
    A11("30", 30..30, webView64 = true, derivedIdentity = true, clientComposition = true, gpuBridge64 = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            
            
            "persist.zygote.app_data_isolation" to "false",
            
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            "debug.stagefright.c2inputsurface" to "1"),
        
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n"),
        
        propDefaults = mapOf("ro.vndk.version" to "28", "ro.vendor.build.security_patch" to "2020-08-05"), ownVendorUnderGsi = true,
        
        ownLibDirs = mapOf("vendor#bin#hw#android.hardware.health@2.0-service.goldfish" to "vendor/lib64/aemu-health"),
        
        
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n"),
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024,
        
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A12("31", 31..31, derivedIdentity = true, gpuBridge64 = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27", "debug.stagefright.c2inputsurface" to "1",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            "persist.zygote.app_data_isolation" to "false",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            "debug.renderengine.backend" to "gles"),
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n"),
        propDefaults = mapOf("ro.vndk.version" to "32", "ro.vendor.build.security_patch" to "2022-07-05"), ownVendorUnderGsi = true,
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n"),
        
        
        
        
        ownLibDirs = mapOf("vendor#bin#hw#android.hardware.audio.service" to "vendor/lib64/aemu-audio"),
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024, cgroup2Root = true, oneFingerprint = true, vendorHeapProps = true,
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A13("33", 33..33, volumeAtUserStart = true, derivedIdentity = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27", "debug.stagefright.c2inputsurface" to "1",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            "persist.zygote.app_data_isolation" to "false",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            "debug.renderengine.backend" to "gles"),
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n"),
        propDefaults = mapOf("ro.vndk.version" to "28", "ro.vendor.build.security_patch" to "2023-08-05"), ownVendorUnderGsi = true, gpuBridge64 = true,
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n"),
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024, cgroup2Root = true, partitionsAtRoot = true, bpfMapNotes = true, oneFingerprint = true, vendorHeapProps = true,
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A14("34", 34..34, volumeAtUserStart = true, derivedIdentity = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27", "debug.stagefright.c2inputsurface" to "1",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            "persist.zygote.app_data_isolation" to "false",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            "debug.renderengine.backend" to "gles",
            
            
            
            
            
            "service.sf.prime_shader_cache" to "0",
            
            "ro.hardware.sound_trigger.primary" to "aemu",
            
            
            "init.svc.mdnsd_netbpfload" to "stopped",
            "dalvik.vm.dex2oat-threads" to "2", "dalvik.vm.background-dex2oat-threads" to "1", "dalvik.vm.boot-dex2oat-threads" to "2"),
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n",
            
            
            
            
            "config.gz" to "CONFIG_ARM64=y\nCONFIG_64BIT=y\nCONFIG_VMAP_STACK=y\n"),
        
        
        
        
        propDefaults = mapOf("ro.vndk.version" to "34", "ro.vendor.build.security_patch" to "2023-12-05"),
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n",
            
            "fs/bpf/netd_shared/mainline_done" to ""),
        
        
        
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024, cgroup2Root = true, partitionsAtRoot = true, bpfMapNotes = true, linkerConfigTool = "apex/com.android.runtime/bin/linkerconfig", mediaProfilesFallback = true, manifestFragmentsKept = listOf("health", "aemu-"), oneFingerprint = true, vendorHeapProps = true, ownVendorUnderGsi = true, odmManifestFragmentsAside = true, lazyAidlServices = true, apexStandIn = true, ramBudget = true, gpuBridge64 = true, bootHardwareProp = true,
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A15("35", 35..35, volumeAtUserStart = true, derivedIdentity = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27", "debug.stagefright.c2inputsurface" to "1",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            "persist.zygote.app_data_isolation" to "false",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            "debug.renderengine.backend" to "gles",
            
            "service.sf.prime_shader_cache" to "0",
            
            
            
            
            "ro.vendor.api_level" to "27",
            
            "ro.cp_system_other_odex" to "0",
            
            "init.svc.mdnsd_netbpfload" to "stopped",
            
            "ro.hardware.sound_trigger.primary" to "aemu",
            
            "dalvik.vm.dex2oat-threads" to "2", "dalvik.vm.background-dex2oat-threads" to "1", "dalvik.vm.boot-dex2oat-threads" to "2"),
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n",
            
            "config.gz" to "CONFIG_ARM64=y\nCONFIG_64BIT=y\nCONFIG_VMAP_STACK=y\n"),
        
        
        
        
        propDefaults = mapOf("ro.vndk.version" to "34", "ro.vendor.build.security_patch" to "2023-12-05"),
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n",
            
            "fs/bpf/netd_shared/mainline_done" to ""),
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024, cgroup2Root = true, partitionsAtRoot = true, bpfMapNotes = true, linkerConfigTool = "apex/com.android.runtime/bin/linkerconfig", mediaProfilesFallback = true, manifestFragmentsKept = listOf("health", "aemu-"), oneFingerprint = true, vendorHeapProps = true, ownVendorUnderGsi = true, odmManifestFragmentsAside = true, lazyAidlServices = true, ramBudget = true, gpuBridge64 = true, syncLog = true, cgroupRcDepth = true, apexStandIn = true, webView64 = true, ownLibDirs = mapOf("vendor#bin#hw#android.hardware.audio.service" to "vendor/lib64/aemu-audio:vendor/lib64/aemu-vndk"), bootHardwareProp = true,
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A16("36", 36..36, volumeAtUserStart = true, derivedIdentity = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27", "debug.stagefright.c2inputsurface" to "1",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            "persist.zygote.app_data_isolation" to "false",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            "debug.renderengine.backend" to "gles",
            
            "service.sf.prime_shader_cache" to "0",
            
            
            
            
            "ro.vendor.api_level" to "27",
            
            "ro.cp_system_other_odex" to "0",
            
            "init.svc.mdnsd_netbpfload" to "stopped",
            
            "ro.hardware.sound_trigger.primary" to "aemu",
            
            "dalvik.vm.dex2oat-threads" to "2", "dalvik.vm.background-dex2oat-threads" to "1", "dalvik.vm.boot-dex2oat-threads" to "2"),
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n",
            
            "config.gz" to "CONFIG_ARM64=y\nCONFIG_64BIT=y\nCONFIG_VMAP_STACK=y\n"),
        
        
        
        
        propDefaults = mapOf("ro.vndk.version" to "34", "ro.vendor.build.security_patch" to "2023-12-05"),
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n",
            
            "fs/bpf/netd_shared/mainline_done" to ""),
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024, cgroup2Root = true, partitionsAtRoot = true, bpfMapNotes = true, linkerConfigTool = "apex/com.android.runtime/bin/linkerconfig", mediaProfilesFallback = true, manifestFragmentsKept = listOf("health", "aemu-"), oneFingerprint = true, vendorHeapProps = true, ownVendorUnderGsi = true, odmManifestFragmentsAside = true, lazyAidlServices = true, ramBudget = true, syncLog = true, cgroupRcDepth = true, standInServices = mapOf("android.hardware.power.IPower/default" to "system/lib64/libpowermanager.so#android.hardware.power-V"), aconfigStorage = true, gpuBridge64 = true, cgroupUidParents = listOf("system", "apps"), apexStandIn = true, webView64 = true, ownLibDirs = mapOf("vendor#bin#hw#android.hardware.audio.service" to "vendor/lib64/aemu-audio:vendor/lib64/aemu-vndk"), bootHardwareProp = true,
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so")),
    
    A17("37", 37..37, volumeAtUserStart = true, derivedIdentity = true, oneIncludePolicy = true, webViewSingleProcess = true, registeredNetwork = true, treble = true, eglDir = "vendor/lib64/egl", gles = "2",
        absentFeatures = listOf("consumerir", "nfc", "nfc.hce", "nfc.hcef", "bluetooth", "bluetooth_le"), trafficAccounting = true, noQemuPipe = true, binderVold = true,
        
        
        
        props = mapOf("ro.treble.enabled" to "false", "bpf.progs_loaded" to "1", "ro.product.first_api_level" to "27", "debug.stagefright.c2inputsurface" to "1", "ro.vendor.api_level" to "27",
            "ro.apex.updatable" to "false", "dalvik.vm.jdwp-provider" to "none", "ro.property_service.version" to "2",
            "persist.zygote.app_data_isolation" to "false",
            
            "pm.dexopt.first-boot" to "extract", "pm.dexopt.boot" to "extract", "ro.hardware.egl" to "swiftshader", "ro.frp.pst" to "",
            
            
            
            
            
            
            "debug.renderengine.backend" to "skiaglthreaded",
            
            "service.sf.prime_shader_cache" to "0",
            
            "ro.cp_system_other_odex" to "0",
            
            "init.svc.mdnsd_netbpfload" to "stopped",
            
            "ro.hardware.sound_trigger.primary" to "aemu",
            
            "dalvik.vm.dex2oat-threads" to "2", "dalvik.vm.background-dex2oat-threads" to "1", "dalvik.vm.boot-dex2oat-threads" to "2"),
        procFiles = mapOf("tp_lockdown_info" to "4636320131320200\n",
            
            "config.gz" to "CONFIG_ARM64=y\nCONFIG_64BIT=y\nCONFIG_VMAP_STACK=y\n"),
        
        
        
        
        propDefaults = mapOf("ro.vndk.version" to "34", "ro.vendor.build.security_patch" to "2023-12-05"),
        sysFiles = mapOf("android_lcd/lcd_name" to "aemu\n",
            
            "fs/bpf/netd_shared/mainline_done" to ""),
        ownVndkDir = "vendor/lib64/aemu-vndk", manifestFragmentsAside = true, trebleLinkerConfig = true, propAreaSize = 512 * 1024, cgroup2Root = true, partitionsAtRoot = true, bpfMapNotes = true, linkerConfigTool = "apex/com.android.runtime/bin/linkerconfig", mediaProfilesFallback = true, manifestFragmentsKept = listOf("health", "aemu-"), oneFingerprint = true, vendorHeapProps = true, ownVendorUnderGsi = true, odmManifestFragmentsAside = true, lazyAidlServices = true, ramBudget = true, syncLog = true, cgroupRcDepth = true, standInServices = mapOf("android.hardware.power.IPower/default" to "system/lib64/libpowermanager.so#android.hardware.power-V"), aconfigStorage = true, kernelPolicyUnread = true, gpuBridge64 = true, cgroupUidParents = listOf("system", "apps"), apexStandIn = true, webView64 = true, ownLibDirs = mapOf("vendor#bin#hw#android.hardware.audio.service" to "vendor/lib64/aemu-audio:vendor/lib64/aemu-vndk"), bootHardwareProp = true,
        firmwareOwn = listOf("vendor#bin#hw#android.hardware.media.omx@1.0-service", "vendor#etc#init#android.hardware.media.omx@1.0-service.rc",
            "vendor#lib#libavservices_minijail_vendor.so", "vendor#lib#libmediacodecservice.so", "vendor#lib#libminijail_vendor.so",
            
            
            "vendor#lib64#libhidltransport.so", "vendor#lib64#libhwbinder.so", "vendor#lib#libhidltransport.so", "vendor#lib#libhwbinder.so"),
        firmwareAside = listOf("vendor#lib64#hw#android.hardware.keymaster@3.0-impl-qti.so", "vendor#lib64#hw#android.hardware.gatekeeper@1.0-impl-qti.so"));

    val runner: String get() = "libaemu64_$tag.so"
    val binderd: String get() = "libbinderd64_$tag.so"
    
    
    val eglDir32: String get() = eglDir.replace("/lib64/", "/lib/")
    val emulationDir32: String get() = (emulationDir ?: eglDir).replace("/lib64/", "/lib/")
    val eglLib: String get() = "$eglDir/libEGL_swiftshader.so"
    
    fun isOwnSuffix(name: String) = name.endsWith("_swiftshader.so") || name.endsWith("_$eglName.so") || name == "libGLES_aemu.so" ||
        name == "libGLESv1_CM_ss.so"     
    
    val assets: List<String> get() = listOf("engines/n64-gl", "engines/n64-$tag")
    
    val ownVendorAssets: String get() = "engines/n64-$tag-own"
    
    val gsiAssets: String get() = "engines/n64-$tag-gsi"
    
    val gralloc5Assets: String get() = "engines/n64-$tag-gralloc5"

    companion object {
        
        fun forApi(api: Int): Kernel64 = entries.firstOrNull { api in it.apis } ?: entries.lastOrNull { api > it.apis.last } ?: A6
    }
}
