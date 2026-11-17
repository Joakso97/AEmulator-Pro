
package app.aemu.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties


object WifiEmu {
    const val DEFAULT_SSID = "AEmulator-WiFi"
    const val SSID_MAX_BYTES = 32
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 63

    
    const val FILE = "data/system/aemu-wifi.properties"
    const val JAR_NAME = "aemu-wifi.jar"
    const val JAR_PATH = "system/framework/$JAR_NAME"

    
    const val IP = "10.0.2.15"
    const val GATEWAY = "10.0.2.2"
    const val DNS1 = "8.8.8.8"
    const val DNS2 = "1.1.1.1"
    const val PREFIX = 24
    const val MAC = "02:15:5d:ae:00:01"

    
    const val MIN_API = 14
    const val MAX_API = 25

    
    const val GINGERBREAD_MIN_API = 9
    const val GINGERBREAD_MAX_API = 10

    
    const val TREBLE_MIN_API = 26
    const val TREBLE_MAX_API = 37

    
    fun supported(engine: Engine, api: Int, recovery: Boolean, nativeUi: Boolean) =
        ((engine == Engine.KK && (api in MIN_API..MAX_API || api in GINGERBREAD_MIN_API..GINGERBREAD_MAX_API)) ||
            (engine == Engine.N64 && api in TREBLE_MIN_API..TREBLE_MAX_API)) && !recovery && !nativeUi

    
    fun offered(img: GuestImage, binder64: Boolean = false) =
        supported(img.effective(binder64).engine, img.api, recovery = false, nativeUi = false)

    
    fun cleanSsid(v: String): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < v.length) {
            val cp = v.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x20 || cp == 0x7f || Character.isISOControl(cp)) continue
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > SSID_MAX_BYTES) break
            sb.appendCodePoint(cp); bytes += n
        }
        return sb.toString()
    }

    
    fun cleanPassword(v: String): String = v.filter { it in ' '..'~' }.take(PASSWORD_MAX)

    
    fun passwordValid(v: String) = v.isEmpty() || v.length in PASSWORD_MIN..PASSWORD_MAX
    fun secured(s: VmSettings) = passwordValid(s.wifiPassword) && s.wifiPassword.isNotEmpty()

    
    fun bssid(ssid: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(ssid.toByteArray(Charsets.UTF_8))
        val b = IntArray(6) { h[it].toInt() and 0xff }
        b[0] = (b[0] and 0xfc) or 0x02 
        return b.joinToString(":") { "%02x".format(it) }
    }

    
    fun properties(s: VmSettings): Properties {
        val ssid = cleanSsid(s.wifiSsid).ifEmpty { DEFAULT_SSID }
        return Properties().apply {
            setProperty("ssid", ssid)
            setProperty("secured", secured(s).toString())
            setProperty("psk", if (secured(s)) s.wifiPassword else "")
            setProperty("bssid", bssid(ssid))
            setProperty("mac", MAC)
            setProperty("ip", IP)
            setProperty("gateway", GATEWAY)
            setProperty("prefix", PREFIX.toString())
            setProperty("dns1", DNS1)
            setProperty("dns2", DNS2)
            setProperty("rssi", rssiFor(s.wifiSignal).toString())
            setProperty("speed", "72")
            setProperty("frequency", "2437")
            if (s.wifiNeighbors) setProperty("neighbors", neighbors(ssid).joinToString("\n"))
        }
    }

    const val MAX_BARS = 4

    
    fun rssiFor(bars: Int): Int = when (bars.coerceIn(1, MAX_BARS)) { 1 -> -84; 2 -> -74; 3 -> -63; else -> -48 }

    private val NEIGHBOR_NAMES = listOf(
        "TP-Link_3F2A", "NETGEAR57", "HUAWEI-B315-9C11", "Vodafone-2A1B", "MEO-48F0C3", "NOS-77D2", "Linksys04821",
        "DIRECT-7b-HP OfficeJet", "Casa", "FRITZ!Box 7530 QX", "Livebox-61E0", "Guest WiFi",
    )

    
    fun neighbors(ownSsid: String): List<String> {
        val seed = MessageDigest.getInstance("SHA-1").digest(ownSsid.toByteArray(Charsets.UTF_8)).map { it.toInt() and 0xff }
        val names = NEIGHBOR_NAMES.filter { it != ownSsid }.sortedBy { n -> (n.hashCode() xor seed[0] * 31 xor seed[1]) }
        val count = 4 + seed[2] % 2
        val freqs = listOf(2412, 2437, 2462, 5180, 5240, 5745)
        return names.take(count).mapIndexed { i, n ->
            val rssi = -58 - (seed[3 + i] % 30)
            val caps = if (seed[8 + i] % 3 == 0) "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][WPS][ESS]" else "[WPA2-PSK-CCMP][ESS]"
            "$n|${bssid(n)}|$caps|$rssi|${freqs[seed[13 + i] % freqs.size]}"
        }
    }

    fun encode(p: Properties): ByteArray = ByteArrayOutputStream().also { p.store(it, "AEmulator Wi-Fi") }.toByteArray()

    
    fun write(root: File, s: VmSettings, active: Boolean) {
        val f = File(root, FILE)
        if (!active) { f.delete(); return }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(encode(properties(s)))
        tmp.setReadable(true, false)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
