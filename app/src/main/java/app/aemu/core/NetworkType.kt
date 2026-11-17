
package app.aemu.core


object NetworkType {
    const val GPRS = 1
    const val EDGE = 2
    const val UMTS = 3
    const val ONE_X_RTT = 6
    const val EVDO_0 = 7
    const val EVDO_A = 8
    const val HSDPA = 9
    const val HSUPA = 10
    const val HSPA = 11
    const val EVDO_B = 12
    const val EHRPD = 13
    const val LTE = 14
    const val HSPAP = 15
    const val DEFAULT = EDGE
    const val DEFAULT_CDMA = ONE_X_RTT

    
    data class Entry(val tech: Int, val label: String, val minApi: Int, val gsm: Boolean = true, val cdma: Boolean = false)

    val ALL = listOf(
        Entry(GPRS, "2G · GPRS", 9),
        Entry(EDGE, "2G · EDGE", 9),
        Entry(UMTS, "3G · UMTS", 9),
        Entry(HSDPA, "3G · HSDPA", 9),
        Entry(HSUPA, "3G · HSUPA", 9),
        Entry(HSPA, "3G · HSPA", 9),
        Entry(HSPAP, "3.5G · HSPA+", 14),
        Entry(ONE_X_RTT, "2G · 1xRTT", 9, gsm = false, cdma = true),
        Entry(EVDO_0, "3G · EV-DO rev. 0", 9, gsm = false, cdma = true),
        Entry(EVDO_A, "3G · EV-DO rev. A", 9, gsm = false, cdma = true),
        Entry(EVDO_B, "3G · EV-DO rev. B", 9, gsm = false, cdma = true),
        Entry(EHRPD, "3.5G · eHRPD", 14, gsm = false, cdma = true),
        Entry(LTE, "4G · LTE", 14, gsm = true, cdma = true),
    )

    
    fun available(api: Int, cdma: Boolean = false, lteOnCdma: Boolean = false): List<Entry> =
        ALL.filter { api >= it.minApi && if (cdma) it.cdma && (it.tech != LTE || lteOnCdma) else it.gsm }

    
    fun normalize(tech: Int): Int = if (ALL.any { it.tech == tech }) tech else DEFAULT

    
    fun sanitize(tech: Int, api: Int = Int.MAX_VALUE, cdma: Boolean = false, lteOnCdma: Boolean = false): Int {
        val e = ALL.firstOrNull { it.tech == tech }
        if (!cdma) return when {
            e == null || !e.gsm -> DEFAULT
            available(api).any { it.tech == tech } -> tech
            else -> HSPA
        }
        return when {
            e == null || !e.cdma -> DEFAULT_CDMA
            available(api, true, lteOnCdma).any { it.tech == tech } -> tech
            tech == LTE && api >= 14 -> EHRPD
            else -> EVDO_A
        }
    }

    fun label(tech: Int): String = ALL.firstOrNull { it.tech == tech }?.label ?: tech.toString()
}
