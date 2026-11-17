
package app.aemu.core

import app.aemu.R
import org.json.JSONObject
import java.io.File


data class SimCard(
    
    val present: Boolean = true,
    
    val operator: String = "",
    
    val mcc: String = "",
    
    val mnc: String = "",
    
    val number: String = "",
    
    val iccid: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("present", present).put("operator", operator).put("mcc", mcc)
        .put("mnc", mnc).put("number", number).put("iccid", iccid)

    companion object {
        const val DEFAULT_OPERATOR = "AEmulator"
        const val OPERATOR_MAX = 16          

        fun fromJson(o: JSONObject?): SimCard = if (o == null) SimCard() else SimCard(
            present = o.optBoolean("present", true),
            operator = cleanOperator(o.optString("operator", "")),
            mcc = cleanMcc(o.optString("mcc", "")),
            mnc = cleanMnc(o.optString("mnc", "")),
            number = cleanNumber(o.optString("number", "")),
            iccid = cleanIccid(o.optString("iccid", "")),
        )

        
        fun cleanOperator(v: String) = v.filter { !it.isISOControl() }.take(OPERATOR_MAX)
        fun cleanMcc(v: String) = v.filter(Char::isDigit).take(3)
        fun cleanMnc(v: String) = v.filter(Char::isDigit).take(3)
        fun cleanNumber(v: String): String {
            val digits = v.filter(Char::isDigit).take(15)
            return if (v.trimStart().startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
        }
        fun cleanIccid(v: String) = v.filter(Char::isDigit).take(20)
        fun codeValid(mcc: String, mnc: String) = mcc.length == 3 && mnc.length in 2..3
    }
}


data class OperatorPreset(val country: String, val name: String, val mcc: String, val mnc: String)

object SimCards {
    private val COUNTRY_ISO = mapOf(
        "Portugal" to "PT", "Brasil" to "BR", "USA" to "US", "Canada" to "CA", "México" to "MX", "UK" to "GB", "España" to "ES",
        "France" to "FR", "Deutschland" to "DE", "Italia" to "IT", "Polska" to "PL", "Türkiye" to "TR", "Россия" to "RU",
        "Україна" to "UA", "India" to "IN", "Indonesia" to "ID", "日本" to "JP",
    )

    
    fun countryLabel(ctx: android.content.Context, p: OperatorPreset): String {
        if (p.country == "Emulator") return ctx.getString(R.string.sim_country_emulator)
        val iso = COUNTRY_ISO[p.country] ?: return p.country
        val locale = ctx.resources.configuration.locales[0]
        return java.util.Locale("", iso).getDisplayCountry(locale).ifBlank { p.country }
    }

    val PRESETS = listOf(
        OperatorPreset("Portugal", "MEO", "268", "06"), OperatorPreset("Portugal", "Vodafone P", "268", "01"),
        OperatorPreset("Portugal", "NOS", "268", "03"),
        OperatorPreset("Brasil", "Vivo", "724", "06"), OperatorPreset("Brasil", "Claro BR", "724", "05"),
        OperatorPreset("Brasil", "TIM BRASIL", "724", "02"), OperatorPreset("Brasil", "Oi", "724", "31"),
        OperatorPreset("USA", "AT&T", "310", "410"), OperatorPreset("USA", "T-Mobile", "310", "260"),
        OperatorPreset("USA", "Verizon", "311", "480"),
        OperatorPreset("Canada", "Rogers", "302", "720"), OperatorPreset("Canada", "Bell", "302", "610"),
        OperatorPreset("Canada", "TELUS", "302", "220"),
        OperatorPreset("México", "Telcel", "334", "020"),
        OperatorPreset("UK", "EE", "234", "30"), OperatorPreset("UK", "Vodafone UK", "234", "15"),
        OperatorPreset("UK", "O2 - UK", "234", "10"), OperatorPreset("UK", "3", "234", "20"),
        OperatorPreset("España", "Movistar", "214", "07"), OperatorPreset("España", "Vodafone ES", "214", "01"),
        OperatorPreset("España", "Orange", "214", "03"),
        OperatorPreset("France", "Orange F", "208", "01"), OperatorPreset("France", "SFR", "208", "10"),
        OperatorPreset("France", "Bouygues", "208", "20"), OperatorPreset("France", "Free", "208", "15"),
        OperatorPreset("Deutschland", "Telekom.de", "262", "01"), OperatorPreset("Deutschland", "Vodafone.de", "262", "02"),
        OperatorPreset("Deutschland", "o2 - de", "262", "03"),
        OperatorPreset("Italia", "TIM", "222", "01"), OperatorPreset("Italia", "Vodafone IT", "222", "10"),
        OperatorPreset("Italia", "WINDTRE", "222", "88"),
        OperatorPreset("Polska", "Orange PL", "260", "03"), OperatorPreset("Polska", "T-Mobile PL", "260", "02"),
        OperatorPreset("Polska", "Play", "260", "06"), OperatorPreset("Polska", "Plus", "260", "01"),
        OperatorPreset("Türkiye", "Turkcell", "286", "01"), OperatorPreset("Türkiye", "Vodafone TR", "286", "02"),
        OperatorPreset("Россия", "MTS RUS", "250", "01"), OperatorPreset("Россия", "MegaFon", "250", "02"),
        OperatorPreset("Россия", "Beeline", "250", "99"),
        OperatorPreset("Україна", "Kyivstar", "255", "03"), OperatorPreset("Україна", "Vodafone UA", "255", "01"),
        OperatorPreset("India", "Airtel", "404", "10"), OperatorPreset("India", "Jio", "405", "857"),
        OperatorPreset("Indonesia", "Telkomsel", "510", "10"),
        OperatorPreset("日本", "NTT DOCOMO", "440", "10"), OperatorPreset("日本", "SoftBank", "440", "20"),
        OperatorPreset("Emulator", "Android", "310", "260"),
    )

    
    data class Resolved(val operator: String, val mcc: String, val mnc: String, val number: String, val iccid: String, val imsi: String)

    fun resolve(sim: SimCard, fallback: Pair<String, String>, slot: Int = 0): Resolved {
        val (mcc, mnc) = if (SimCard.codeValid(sim.mcc, sim.mnc)) sim.mcc to sim.mnc else fallback
        val operator = sim.operator.ifBlank {
            if (SimCard.codeValid(sim.mcc, sim.mnc)) PRESETS.firstOrNull { it.mcc == mcc && it.mnc == mnc }?.name ?: SimCard.DEFAULT_OPERATOR
            else SimCard.DEFAULT_OPERATOR
        }
        
        val msinLen = 15 - mcc.length - mnc.length
        val digits = sim.number.filter(Char::isDigit)
        
        val legacy = slot == 0 && digits.isEmpty() && !SimCard.codeValid(sim.mcc, sim.mnc)
        val msin = when {
            digits.isNotEmpty() -> digits.takeLast(msinLen).padStart(msinLen, '0')
            else -> ("$slot" + "123456789").take(msinLen).let { if (slot == 0) "0123456789".take(msinLen) else it }
        }
        val iccid = sim.iccid.ifBlank { if (legacy) LegacySimIo.ICCID else iccidFor(mcc, mnc, msin) }
        return Resolved(operator, mcc, mnc, sim.number, iccid, mcc + mnc + msin)
    }

    
    fun iccidFor(mcc: String, mnc: String, msin: String): String {
        val body = ("89" + mcc + mnc.padStart(3, '0') + msin).take(18).padEnd(18, '0')
        return body + luhn(body)
    }

    fun luhn(digits: String): Int {
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 0) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return (10 - sum % 10) % 10
    }

    
    fun apnOperators(root: File): Set<Pair<String, String>> = runCatching {
        val xml = File(root, "system/etc/apns-conf.xml").readText()
        Regex("<apn\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val a = m.groupValues[1]
            fun attr(n: String) = Regex("\\b$n\\s*=\\s*\"([^\"]*)\"").find(a)?.groupValues?.get(1)
            val type = attr("type")
            val mcc = attr("mcc"); val mnc = attr("mnc")
            if (mcc == null || mnc == null || attr("mvno_type") != null) null
            else if (type == null || type.contains("default") || type == "*") mcc to mnc else null
        }.toSet()
    }.getOrDefault(emptySet())

    

    
    fun efSpn(name: String): ByteArray {
        val out = ByteArray(17) { 0xff.toByte() }
        out[0] = 0                                        
        val gsm = name.all { it.code in 0x20..0x7e && it !in "[]{}\\^~|`" }
        if (gsm) name.take(16).forEachIndexed { i, c -> out[1 + i] = (if (c == '@') 0 else if (c == '$') 2 else if (c == '_') 0x11 else c.code).toByte() }
        else {
            out[1] = 0x80.toByte()                        
            val b = name.take(7).toByteArray(Charsets.UTF_16BE)
            b.copyInto(out, 2)
        }
        return out
    }

    const val MSISDN_RECORD = 28                          

    
    fun efMsisdn(number: String): ByteArray {
        val r = ByteArray(MSISDN_RECORD) { 0xff.toByte() }
        "Mobile".forEachIndexed { i, c -> r[i] = c.code.toByte() }
        val digits = number.filter(Char::isDigit).take(20)
        if (digits.isEmpty()) return r
        val bcd = bcd(digits)
        r[14] = (1 + bcd.size).toByte()                   
        r[15] = if (number.startsWith("+")) 0x91.toByte() else 0x81.toByte()
        bcd.copyInto(r, 16)
        return r
    }

    
    fun efAd(mnc: String) = byteArrayOf(0, 0, 0, mnc.length.toByte())

    
    fun efIccid(iccid: String): ByteArray = bcd(iccid.padEnd(20, 'F').take(20)).copyOf(10)

    
    private fun bcd(d: String): ByteArray {
        val s = if (d.length % 2 == 1) d + "F" else d
        return ByteArray(s.length / 2) { i ->
            val lo = s[2 * i].digitToInt(16); val hi = s[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
