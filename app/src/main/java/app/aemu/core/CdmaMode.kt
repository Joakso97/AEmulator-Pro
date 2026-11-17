
package app.aemu.core

import java.io.File
import java.security.MessageDigest


object CdmaMode {
    const val LTE_ON_CDMA_PROP = "telephony.lteOnCdmaDevice"
    
    const val FIRST_API = 9
    const val LAST_API = 25

    fun supported(api: Int) = api in FIRST_API..LAST_API

    
    data class Plan(
        
        val cdma: Boolean,
        
        val lteOnCdma: Boolean,
        
        val firmwareLteOnCdma: Boolean,
    )

    val OFF = Plan(cdma = false, lteOnCdma = false, firmwareLteOnCdma = false)

    
    fun pinnedProps(plan: Plan, api: Int): Set<String> = if (supported(api) && !plan.cdma) setOf(LTE_ON_CDMA_PROP) else emptySet()

    fun plan(s: VmSettings, api: Int, paths: VmPaths): Plan {
        if (!supported(api)) return OFF
        val fw = firmwareLteOnCdma(paths)
        if (!s.radio) return Plan(cdma = false, lteOnCdma = false, firmwareLteOnCdma = fw)
        val on = s.cdma ?: fw
        return Plan(cdma = on, lteOnCdma = on && fw, firmwareLteOnCdma = fw)
    }

    private val PROP_FILES = listOf(
        "default.prop", "system/default.prop", "system/build.prop", "system/vendor/build.prop", "vendor/build.prop",
        "odm/etc/build.prop", "product/build.prop",
    )

    
    fun firmwareProp(paths: VmPaths, key: String): String? {
        runCatching {
            if (paths.propsTemplate.isFile) PropArea.open(paths.propsTemplate)?.use { a -> a.get(key)?.let { return it } }
        }
        var found: String? = null
        for (n in PROP_FILES) runCatching {
            val f = File(paths.root, n)
            if (f.isFile) PropArea.parseProps(f.readText(Charsets.UTF_8))[key]?.let { found = it }
        }
        return found
    }

    
    fun firmwareLteOnCdma(paths: VmPaths): Boolean = firmwareProp(paths, LTE_ON_CDMA_PROP)?.trim() == "1"

    
    fun propOverrides(plan: Plan, api: Int, homeNumeric: String, homeName: String, fw: (String) -> String?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (!supported(api)) return m
        if (!plan.cdma) {
            
            m[LTE_ON_CDMA_PROP] = "0"
            return m
        }
        if (plan.lteOnCdma) return m
        m["ro.telephony.default_network"] = "4"        
        m["ro.telephony.default_cdma_sub"] = "1"       
        if (fw("ro.cdma.home.operator.numeric").isNullOrBlank()) m["ro.cdma.home.operator.numeric"] = homeNumeric
        if (fw("ro.cdma.home.operator.alpha").isNullOrBlank()) m["ro.cdma.home.operator.alpha"] = homeName
        return m
    }

    
    fun meid(imei: String): String = "A0" + imei.filter(Char::isDigit).padEnd(12, '0').take(12)

    
    fun esn(meid: String): String {
        val raw = ByteArray(meid.length / 2) { meid.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val h = MessageDigest.getInstance("SHA-1").digest(raw)
        return "80" + h.takeLast(3).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }
}
