
package app.aemu.core

import android.net.LocalSocket
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections


class RilStub(
    private val paths: VmPaths,
    private val log: (String) -> Unit,
    private val imei: String = VmSettings.DEFAULT_IMEI,
    private val api: Int = 19,
    private val baseband: String = VmSettings.DEFAULT_BASEBAND,
    networkType: Int = NetworkType.DEFAULT,
    private val sim: SimCard = SimCard(),
    
    val cdma: Boolean = false,
    
    val lteOnCdma: Boolean = false,
) {
    @Volatile var answered = 0L
        private set

    private val server = UnixServer(paths.socket("rild"), "rild") { c -> serveOne(c) }
    
    private val card: SimCards.Resolved by lazy { SimCards.resolve(sim, pickOperator()) }
    private val operator: Pair<String, String> get() = card.mcc to card.mnc
    private val simFiles: LegacySimIo.Files by lazy {
        LegacySimIo.Files(SimCards.efIccid(card.iccid), SimCards.efSpn(card.operator), SimCards.efMsisdn(card.number), SimCards.efAd(card.mnc))
    }
    
    private val samsung: Boolean by lazy {
        runCatching { File(paths.root, "system/build.prop").readText().lowercase().contains("ro.product.manufacturer=samsung") }
            .getOrDefault(false)
    }

    
    @Volatile var networkType: Int = NetworkType.sanitize(networkType, api, cdma, lteOnCdma)
        private set

    
    fun setNetworkType(tech: Int): Boolean {
        networkType = NetworkType.sanitize(tech, api, cdma, lteOnCdma)
        val o = out ?: return false
        unsol(o, UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED, ByteArray(0))
        return true
    }

    
    private val voiceTech: Int get() = if (cdma && lteOnCdma) NetworkType.ONE_X_RTT else networkType

    
    private val registered: Boolean get() = sim.present || (cdma && !lteOnCdma)

    
    val operatorNumeric: String get() = card.mcc + card.mnc
    val operatorName: String get() = card.operator

    private val meid: String by lazy { CdmaMode.meid(imei) }
    private val esn: String by lazy { CdmaMode.esn(meid) }
    
    private val cdmaSubscriptionSource: Int get() = if (lteOnCdma) 0 else 1
    @Volatile private var preferredNetwork: Int = if (this.networkType == NetworkType.LTE) 8 else 4   
    private val smsIds = java.util.concurrent.atomic.AtomicInteger((System.nanoTime() and 0x7fff).toInt())
    
    private val alertIds = java.util.concurrent.atomic.AtomicInteger((System.nanoTime() and 0x3ff).toInt())

    fun serve() = server.start(log)
    fun stop() = server.stop()

    
    private val radioOnState: Int get() = if (api < 14 || (samsung && api <= 15)) 4 else 10

    @Volatile private var out: OutputStream? = null
    
    @Volatile private var call: Pair<String, Int>? = null

    
    fun ring(number: String): Boolean {
        val o = out ?: return false
        call = number to CALL_INCOMING
        unsol(o, UNSOL_RESPONSE_CALL_STATE_CHANGED, ByteArray(0))
        unsol(o, UNSOL_CALL_RING, ByteArray(0))
        return true
    }

    
    fun deliverSms(from: String, body: String): Boolean {
        val o = out ?: return false
        if (cdma) unsol(o, UNSOL_RESPONSE_CDMA_NEW_SMS, CdmaSms.deliverParcel(from, body, smsIds.incrementAndGet()))
        else unsol(o, UNSOL_RESPONSE_NEW_SMS, str(smsDeliverPdu(from, body)))
        return true
    }

    
    fun deliverAlert(type: AlertType, text: String): Boolean {
        val o = out ?: return false
        val id = alertIds.incrementAndGet()
        if (cdma) unsol(o, UNSOL_RESPONSE_CDMA_NEW_SMS, CdmaSms.cmasParcel(type, text, id))
        else for (page in WirelessAlert.gsmPages(type, text, id)) unsol(o, UNSOL_RESPONSE_NEW_BROADCAST_SMS, raw(page))
        log("radio: wireless alert ${type.name.lowercase()} (${text.length} chars)")
        return true
    }

    
    private fun smsDeliverPdu(from: String, body: String): String {
        val sb = StringBuilder("00")                                  
        sb.append("04")                                               
        val num = from.trim()
        val digits = num.filter { it.isDigit() }
        if (digits.isNotEmpty() && num.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            sb.append("%02X".format(digits.length.coerceAtMost(20)))
            sb.append(if (num.startsWith("+")) "91" else "81")
            sb.append(bcd(digits.take(20)))
        } else {                                                      
            val name = num.take(11).map { if (it in GSM7_SAFE) it else '?' }.joinToString("")
            val packed = gsm7Pack(name)
            sb.append("%02X".format((name.length * 7 + 3) / 4)).append("D0").append(hex(packed))
        }
        sb.append("00")                                               
        val text = body.ifEmpty { " " }
        val gsm = text.all { it in GSM7_SAFE }
        sb.append(if (gsm) "00" else "08")                            
        val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        sb.append(bcd("%02d%02d%02d%02d%02d%02d".format(now.get(java.util.Calendar.YEAR) % 100,
            now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH),
            now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), now.get(java.util.Calendar.SECOND))))
        sb.append("00")                                               
        if (gsm) {
            val t = text.take(160)
            sb.append("%02X".format(t.length)).append(hex(gsm7Pack(t)))
        } else {
            val ud = text.take(70).toByteArray(Charsets.UTF_16BE)
            sb.append("%02X".format(ud.size)).append(hex(ud))
        }
        return sb.toString()
    }

    
    private fun bcd(d: String): String {
        val s = if (d.length % 2 == 1) d + "F" else d
        return s.chunked(2).joinToString("") { it.reversed() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun gsm7Pack(s: String): ByteArray {
        val out = ByteArray((s.length * 7 + 7) / 8)
        s.forEachIndexed { i, c ->
            val bit = i * 7
            val v = c.code and 0x7f
            val idx = bit / 8
            val sh = bit % 8
            out[idx] = (out[idx].toInt() or (v shl sh)).toByte()
            if (sh > 1) out[idx + 1] = (out[idx + 1].toInt() or (v shr (8 - sh))).toByte()
        }
        return out
    }

    private val connects = ArrayDeque<Long>()
    
    @Volatile private var safeMode = false

    private fun serveOne(c: LocalSocket) {
        val o = c.outputStream
        val now = System.currentTimeMillis()
        synchronized(connects) {
            connects.addLast(now)
            while (connects.isNotEmpty() && now - connects.first() > 60_000) connects.removeFirst()
            if (!safeMode && connects.size > 5) {
                safeMode = true
                log("radio: telephony keeps restarting, modem switched to \"radio unavailable\"")
            }
        }
        if (safeMode) { serveUnavailable(c); return }
        out = o
        if (api >= 14) unsol(o, UNSOL_RIL_CONNECTED, ints(RIL_VERSION))
        unsol(o, UNSOL_RADIO_STATE_CHANGED, le(0))
        if (cdma) log("radio: CDMA modem (${if (lteOnCdma) "LTE on CDMA" else "CDMA only"}), ${NetworkType.label(networkType)}, MEID $meid")
        log(if (sim.present) "radio: telephony connected, SIM \"${card.operator}\" ${card.mcc}-${card.mnc}" +
            (if (card.number.isNotEmpty()) ", number ${card.number}" else "") else "radio: telephony connected, no SIM card")
        val din = DataInputStream(c.inputStream)
        while (true) {
            val len = try { din.readInt() } catch (e: Exception) { break }
            if (len <= 0 || len > 1 shl 20) break
            val body = ByteArray(len)
            din.readFully(body)
            if (len < 8) continue
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val request = b.getInt()
            val serial = b.getInt()
            
            
            
            val (err, payload) = runCatching {
                if (request == RIL_REQUEST_OEM_HOOK_RAW && isQcomHook(body)) E_GENERIC_FAILURE to ByteArray(0) else handle(request, b)
            }.getOrElse { E_GENERIC_FAILURE to ByteArray(0) }
            val resp = ByteArrayOutputStream()
            resp.write(le(0)); resp.write(le(serial)); resp.write(le(err)); resp.write(payload)
            frame(o, resp.toByteArray())
            answered++
            if (request == RIL_REQUEST_RADIO_POWER) afterPower(o, b)
            if (request == RIL_REQUEST_ANSWER || request in HANGUPS) unsol(o, UNSOL_RESPONSE_CALL_STATE_CHANGED, ByteArray(0))
        }
        if (out === o) { out = null; call = null }
    }

    
    private fun serveUnavailable(c: LocalSocket) {
        val o = c.outputStream
        unsol(o, UNSOL_RADIO_STATE_CHANGED, le(0))
        val din = DataInputStream(c.inputStream)
        while (true) {
            val len = try { din.readInt() } catch (e: Exception) { break }
            if (len <= 0 || len > 1 shl 20) break
            val body = ByteArray(len)
            din.readFully(body)
            if (len < 8) continue
            val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            val request = b.getInt()
            val serial = b.getInt()
            val data = when (request) {
                RIL_REQUEST_GET_IMEI -> str(imei)
                RIL_REQUEST_GET_IMEISV -> str(IMEISV)
                RIL_REQUEST_BASEBAND_VERSION -> str(baseband)
                RIL_REQUEST_DEVICE_IDENTITY -> deviceIdentity()
                else -> null
            }
            val err = if (request == RIL_REQUEST_RADIO_POWER || data != null) 0 else E_RADIO_NOT_AVAILABLE
            frame(o, le(0) + le(serial) + le(err) + (data ?: ByteArray(0)))
        }
    }

    private fun isQcomHook(body: ByteArray): Boolean {
        val tag = "QOEMHOOK".toByteArray()
        return body.size >= 12 + tag.size && tag.indices.all { body[12 + it] == tag[it] }
    }

    private fun handle(req: Int, args: ByteBuffer): Pair<Int, ByteArray> {
        val (mcc, mnc) = operator
        val present = sim.present
        val up = registered
        if (cdma) handleCdma(req, args)?.let { return it }
        return when (req) {
            RIL_REQUEST_GET_SIM_STATUS -> 0 to simStatus()
            RIL_REQUEST_GET_CURRENT_CALLS -> 0 to callList()
            RIL_REQUEST_ANSWER -> { call = call?.let { it.first to CALL_ACTIVE }; 0 to ByteArray(0) }
            in HANGUPS -> { call = null; 0 to ByteArray(0) }
            RIL_REQUEST_LAST_CALL_FAIL_CAUSE -> 0 to ints(16)   
            RIL_REQUEST_GET_IMSI -> if (present) 0 to str(card.imsi) else E_GENERIC_FAILURE to ByteArray(0)
            RIL_REQUEST_SIGNAL_STRENGTH -> 0 to (if (cdma) cdmaSignal() else ints(20, 99, -1, -1, -1, -1, -1, 99, -1, -1, -1, -1, -1))
            
            RIL_REQUEST_VOICE_REGISTRATION_STATE -> 0 to (if (cdma) cdmaVoiceRegistration(up) else strs(if (up) "1" else "0", "0001", "00000001", voiceTech.toString()))
            RIL_REQUEST_DATA_REGISTRATION_STATE -> 0 to strs(if (up) "1" else "0", "0001", "00000001", networkType.toString(), "0", "1")
            RIL_REQUEST_OPERATOR -> 0 to (if (up) strs(card.operator, card.operator.take(8), mcc + mnc) else nullStrs(3))
            RIL_REQUEST_SETUP_DATA_CALL -> if (up) 0 to dataCall(single = true) else E_GENERIC_FAILURE to ByteArray(0)
            RIL_REQUEST_DATA_CALL_LIST -> 0 to (if (up) dataCall(single = false) else noDataCalls())
            RIL_REQUEST_SIM_IO -> if (!present) E_GENERIC_FAILURE to ByteArray(0)
                else LegacySimIo.handle(args, simFiles).let { 0 to (le(it.sw1) + le(it.sw2) + str(it.hex)) }
            RIL_REQUEST_GET_IMEI -> 0 to str(imei)
            RIL_REQUEST_GET_IMEISV -> 0 to str(IMEISV)
            RIL_REQUEST_BASEBAND_VERSION -> 0 to str(baseband)
            RIL_REQUEST_DEVICE_IDENTITY -> 0 to deviceIdentity()
            RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE -> 0 to ints(0)
            RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE -> 0 to ints(0)
            RIL_REQUEST_GET_NEIGHBORING_CELL_IDS -> 0 to le(0)
            RIL_REQUEST_VOICE_RADIO_TECH -> 0 to ints(voiceTech)
            RIL_REQUEST_SMS_ACKNOWLEDGE -> 0 to ByteArray(0)
            RIL_REQUEST_GET_SMSC_ADDRESS -> 0 to str("+10000000000")
            RIL_REQUEST_QUERY_FACILITY_LOCK -> 0 to ints(0)
            RIL_REQUEST_SEND_SMS, RIL_REQUEST_SEND_SMS_EXPECT_MORE -> 0 to (le(1) + str(null) + le(-1))
            RIL_REQUEST_DIAL -> E_GENERIC_FAILURE to ByteArray(0)
            
            in 114..117 -> E_GENERIC_FAILURE to ByteArray(0)
            
            
            else -> { log("radio: unhandled request $req"); 0 to (le(4) + ByteArray(32)) }
        }
    }

    
    private fun deviceIdentity(): ByteArray = if (cdma) strs(imei, IMEISV, esn, meid) else strs(imei, IMEISV, "", "")

    
    private fun handleCdma(req: Int, args: ByteBuffer): Pair<Int, ByteArray>? = when (req) {
        RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE -> 0 to ByteArray(0)           
        RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE -> 0 to ints(cdmaSubscriptionSource)
        RIL_REQUEST_CDMA_SUBSCRIPTION -> 0 to cdmaSubscription()
        RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE -> 0 to ByteArray(0)
        RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE -> 0 to ints(2)               
        RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE -> 0 to ByteArray(0)
        RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE -> 0 to ints(0)
        RIL_REQUEST_CDMA_FLASH, RIL_REQUEST_CDMA_BURST_DTMF, RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE -> 0 to ByteArray(0)
        RIL_REQUEST_CDMA_SEND_SMS -> 0 to (le(1) + str(null) + le(-1))
        RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE -> 0 to ByteArray(0)
        RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE -> {
            runCatching { args.position(12); preferredNetwork = args.getInt() }
            0 to ByteArray(0)
        }
        RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE -> 0 to ints(preferredNetwork)
        else -> null
    }

    
    private fun cdmaVoiceRegistration(up: Boolean): ByteArray = strs(
        if (up) "1" else "0", "0", "0", voiceTech.toString(), "1", "0", "0", "0", CDMA_SID, CDMA_NID, "1", "1", "1", "0", "0")

    
    private fun cdmaSubscription(): ByteArray {
        val min = card.imsi.filter(Char::isDigit).takeLast(10).takeIf { it.length == 10 && !it.startsWith("000000") && it != "1111110111" } ?: "5555550100"
        return strs(card.number.filter(Char::isDigit), CDMA_SID, CDMA_NID, min, "1")
    }

    
    private fun cdmaSignal(): ByteArray {
        val t = networkType
        val evdo = t == NetworkType.EVDO_0 || t == NetworkType.EVDO_A || t == NetworkType.EVDO_B || t == NetworkType.EHRPD || t == NetworkType.LTE
        val e = if (evdo) intArrayOf(85, 100, 6) else intArrayOf(-1, -1, -1)
        return if (t == NetworkType.LTE) ints(99, -1, 85, 100, e[0], e[1], e[2], 20, 90, 10, 100, 10, -1)
        else ints(99, -1, 85, 100, e[0], e[1], e[2], 99, -1, -1, -1, -1, -1)
    }

    private fun afterPower(o: OutputStream, args: ByteBuffer) {
        val on = runCatching { args.position(12); args.getInt() != 0 }.getOrDefault(true)
        
        unsol(o, UNSOL_RADIO_STATE_CHANGED, le(if (!on) 0 else radioOnState))
        if (on) {
            if (api > 4) unsol(o, UNSOL_RESPONSE_SIM_STATUS_CHANGED, ByteArray(0))   
            unsol(o, UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED, ByteArray(0))
        }
    }

    private fun callList(): ByteArray {
        val (number, state) = call ?: return le(0)
        val b = ByteArrayOutputStream()
        b.write(le(1))
        if (api <= 4) {   
            b.write(le(state)); b.write(le(1)); b.write(le(129))
            b.write(le(0)); b.write(le(1)); b.write(le(0)); b.write(le(1))
            b.write(str(number)); b.write(le(0))
            return b.toByteArray()
        }
        b.write(le(state)); b.write(le(1)); b.write(le(129))   
        b.write(le(0)); b.write(le(1)); b.write(le(0))         
        b.write(le(1))                                         
        b.write(le(0))                                         
        if (samsung) b.write(le(0))                            
        b.write(str(number)); b.write(le(0))                   
        b.write(str(null)); b.write(le(2))                     
        b.write(le(0))                                         
        return b.toByteArray()
    }

    private fun simStatus(): ByteArray {
        
        if (api <= 4) return ints(if (sim.present) 2 else 0)
        val b = ByteArrayOutputStream()
        if (!sim.present) {                               
            b.write(le(0)); b.write(le(0))                
            b.write(le(-1)); b.write(le(-1))              
            if (api >= 16) b.write(le(-1))                
            b.write(le(0))                                
            return b.toByteArray()
        }
        b.write(le(1)); b.write(le(0))                    
        
        
        
        
        b.write(le(0)); b.write(le(if (cdma) 1 else -1))
        if (api >= 16) b.write(le(-1))                    
        b.write(le(if (cdma) 2 else 1))                   
        b.write(le(if (api >= 14) 2 else 1))              
        b.write(le(5))                                    
        b.write(le(0))                                    
        b.write(str(null)); b.write(str(null))            
        b.write(le(0)); b.write(le(0)); b.write(le(0))    
        if (cdma) {
            b.write(le(if (api >= 14) 4 else 3))          
            b.write(le(5)); b.write(le(0))                
            b.write(str(CSIM_AID)); b.write(str(null))
            b.write(le(0)); b.write(le(0)); b.write(le(0))
        }
        return b.toByteArray()
    }

    
    private fun noDataCalls(): ByteArray = if (api < 14) le(0) else le(RIL_VERSION) + le(0)

    private fun nullStrs(n: Int) = le(n) + (1..n).fold(ByteArray(0)) { acc, _ -> acc + str(null) }

    private fun dataCall(single: Boolean): ByteArray {
        if (api < 14) {   
            if (!single && api <= 4) return le(1) + le(1) + le(2) + str("IP") + str("internet") + str("10.0.2.15")   
            return if (single) strs("1", IFACE, "10.0.2.15") else (le(1) + le(1) + le(2) + str("IP") + str("10.0.2.15"))
        }
        val b = ByteArrayOutputStream()
        b.write(le(RIL_VERSION)); b.write(le(1))
        b.write(le(0)); b.write(le(-1))                   
        b.write(le(1)); b.write(le(2))                    
        b.write(str("IP")); b.write(str(IFACE)); b.write(str("10.0.2.15/24"))
        b.write(str("8.8.8.8 1.1.1.1")); b.write(str("10.0.2.2"))
        return b.toByteArray()
    }

    
    private fun pickOperator(): Pair<String, String> = runCatching {
        val xml = File(paths.root, "system/etc/apns-conf.xml").readText()
        for (m in Regex("<apn\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val a = m.groupValues[1]
            fun attr(n: String) = Regex("\\b$n\\s*=\\s*\"([^\"]*)\"").find(a)?.groupValues?.get(1)
            val mcc = attr("mcc") ?: continue
            val mnc = attr("mnc") ?: continue
            val type = attr("type")
            if (attr("mvno_type") != null) continue
            if (type == null || type.contains("default") || type == "*") return@runCatching mcc to mnc
        }
        null
    }.getOrNull() ?: ("310" to "260")

    private fun unsol(o: OutputStream, code: Int, data: ByteArray) = runCatching { frame(o, le(1) + le(code) + data) }

    @Synchronized
    private fun frame(out: OutputStream, payload: ByteArray) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
        out.write(payload)
        out.flush()
    }

    private fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun ints(vararg v: Int) = le(v.size) + v.fold(ByteArray(0)) { acc, x -> acc + le(x) }
    private fun strs(vararg v: String) = le(v.size) + v.fold(ByteArray(0)) { acc, x -> acc + str(x) }

    
    private fun raw(b: ByteArray): ByteArray = le(b.size) + b + ByteArray((4 - b.size % 4) % 4)

    
    private fun str(s: String?): ByteArray {
        if (s == null) return le(-1)
        val chars = (s.length + 1) * 2
        val b = ByteBuffer.allocate(4 + (chars + 3) / 4 * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(s.length)
        s.forEach { b.putChar(it) }
        return b.array()
    }

    companion object {
        private const val RIL_VERSION = 6
        private const val IFACE = "rmnet0"
        private const val UNSOL_RADIO_STATE_CHANGED = 1000
        private const val UNSOL_RESPONSE_CALL_STATE_CHANGED = 1001
        private const val UNSOL_RESPONSE_NEW_SMS = 1003
        private const val UNSOL_CALL_RING = 1018
        private const val RIL_REQUEST_SMS_ACKNOWLEDGE = 37
        
        private val GSM7_SAFE: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + " \n\r!\"#%&'()*+,-./:;<=>?".toList()).toSet()
        private const val CALL_ACTIVE = 0
        private const val CALL_INCOMING = 4
        private const val RIL_REQUEST_LAST_CALL_FAIL_CAUSE = 18
        private const val RIL_REQUEST_ANSWER = 40
        private val HANGUPS = setOf(12, 13, 14, 17)   
        private const val UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED = 1002
        private const val UNSOL_RESPONSE_SIM_STATUS_CHANGED = 1019
        private const val UNSOL_RESPONSE_CDMA_NEW_SMS = 1020
        private const val UNSOL_RESPONSE_NEW_BROADCAST_SMS = 1021
        private const val CSIM_AID = "A0000003431002F310FFFF89020000FF"
        private const val CDMA_SID = "1"
        private const val CDMA_NID = "65535"
        private const val UNSOL_RIL_CONNECTED = 1034
        private const val E_GENERIC_FAILURE = 2
        private const val E_RADIO_NOT_AVAILABLE = 1
        private const val RIL_REQUEST_GET_SIM_STATUS = 1
        private const val RIL_REQUEST_GET_CURRENT_CALLS = 9
        private const val RIL_REQUEST_DIAL = 10
        private const val RIL_REQUEST_GET_IMSI = 11
        private const val RIL_REQUEST_SIGNAL_STRENGTH = 19
        private const val RIL_REQUEST_VOICE_REGISTRATION_STATE = 20
        private const val RIL_REQUEST_DATA_REGISTRATION_STATE = 21
        private const val RIL_REQUEST_OPERATOR = 22
        private const val RIL_REQUEST_RADIO_POWER = 23
        private const val RIL_REQUEST_SEND_SMS = 25
        private const val RIL_REQUEST_SEND_SMS_EXPECT_MORE = 26
        private const val RIL_REQUEST_SETUP_DATA_CALL = 27
        private const val RIL_REQUEST_SIM_IO = 28
        private const val RIL_REQUEST_OEM_HOOK_RAW = 59
        private const val RIL_REQUEST_GET_IMEI = 38
        private const val RIL_REQUEST_GET_IMEISV = 39
        private const val RIL_REQUEST_QUERY_FACILITY_LOCK = 42
        private const val RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE = 45
        private const val RIL_REQUEST_BASEBAND_VERSION = 51
        private const val RIL_REQUEST_DATA_CALL_LIST = 57
        private const val RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE = 73
        private const val RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE = 74
        private const val RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE = 77
        private const val RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE = 78
        private const val RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE = 79
        private const val RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE = 82
        private const val RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE = 83
        private const val RIL_REQUEST_CDMA_FLASH = 84
        private const val RIL_REQUEST_CDMA_BURST_DTMF = 85
        private const val RIL_REQUEST_CDMA_SEND_SMS = 87
        private const val RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE = 88
        private const val RIL_REQUEST_CDMA_SUBSCRIPTION = 95
        private const val RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE = 99
        private const val RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE = 104
        private const val RIL_REQUEST_GET_NEIGHBORING_CELL_IDS = 75
        private const val RIL_REQUEST_DEVICE_IDENTITY = 98
        private const val RIL_REQUEST_GET_SMSC_ADDRESS = 100
        private const val RIL_REQUEST_VOICE_RADIO_TECH = 108
        private const val IMEISV = "01"
    }
}


class VoldStub(
    paths: VmPaths,
    private val mountPoint: String,
    private val log: (String) -> Unit,
    sockName: String = "vold",
    
    private val others: List<String> = emptyList(),
    private val api: Int = 19,
    
    private val onStorageState: ((String) -> Unit)? = null,
) {
    
    private val legacy = api in 1..7
    private val legacyMountd = legacy && sockName == "mountd"
    private val evtPrefix = if (legacyMountd) "media_" else "volume_"
    @Volatile var state = MOUNTED
    private val label = mountPoint.substringAfterLast('/').ifEmpty { "sdcard" }
    private val clients = Collections.synchronizedList(ArrayList<LocalSocket>())

    private val server = UnixServer(paths.socket(sockName), sockName) { c -> serveOne(c) }

    fun serve() = server.start(log)
    fun stop() { server.stop(); clients.clear() }

    private fun serveOne(c: LocalSocket) {
        clients.add(c)
        try {
            
            
            if (legacyMountd && state == MOUNTED) {
                publish(MOUNTED_PROP)
                runCatching { send(c.outputStream, listOf("${evtPrefix}mounted:$mountPoint")) }
            }
            val out = c.outputStream
            val ins = c.inputStream
            val buf = ByteArray(4096)
            val acc = StringBuilder()
            while (true) {
                val n = try { ins.read(buf) } catch (e: Exception) { -1 }
                if (n <= 0) break
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xff
                    if (b == 0) {
                        val cmd = acc.toString(); acc.setLength(0)
                        if (cmd.isNotEmpty()) answer(cmd, out)
                    } else acc.append(b.toChar())
                }
            }
        } finally {
            clients.remove(c)
        }
    }

    
    private fun answerLegacy(cmd: String, out: OutputStream) {
        val name = cmd.substringBefore(':').trim()
        when (name) {
            "mount_volume", "mount_media" -> {
                state = MOUNTED
                publish(MOUNTED_PROP)
                send(out, listOf("${evtPrefix}mounted:$mountPoint"))
            }
            "eject_media" -> {
                state = IDLE
                publish(UNMOUNTED_PROP)
                send(out, listOf("${evtPrefix}unmounted:$mountPoint"))
            }
            
            "send_ums_status", "send_status" -> send(out, listOf("ums_disconnected", "ums_disabled"))
            "enable_ums", "disable_ums" -> send(out, listOf("ums_disabled"))
            "format_media" -> {
                
                state = MOUNTED
                publish(MOUNTED_PROP)
                send(out, listOf("${evtPrefix}unmounted:$mountPoint", "${evtPrefix}mounted:$mountPoint"))
            }
            else -> log("vold: legacy command ignored: $cmd")
        }
    }

    private fun publish(envState: String) { runCatching { onStorageState?.invoke(envState) } }

    private fun answer(cmd: String, out: OutputStream) {
        if (legacy) { answerLegacy(cmd, out); return }
        val parts = cmd.trim().split(Regex("\\s+"))
        val seq = parts.getOrNull(0)?.toIntOrNull()
        val args = if (seq != null) parts.drop(1) else parts
        val head = args.getOrElse(0) { "" }
        val sub = args.getOrElse(1) { "" }
        var after: (() -> Unit)? = null
        val reply: List<String> = when {
            
            
            api >= 23 && head == "volume" && sub == "reset" -> {
                after = { event("650 emulated 2 \"\" \"\"", "651 emulated 0") }
                listOf("200 Command succeeded")
            }
            api >= 23 && head == "volume" && sub == "mount" -> {
                val id = args.getOrElse(2) { "emulated" }
                after = { event("651 $id 1", "656 $id /data/media", "655 $id /storage/emulated", "651 $id 2") }
                listOf("200 Command succeeded")
            }
            api >= 23 && head == "volume" && sub == "unmount" -> {
                val id = args.getOrElse(2) { "emulated" }
                after = { event("651 $id 5", "651 $id 0") }
                listOf("200 Command succeeded")
            }
            
            
            head == "volume" && sub == "list" -> (if (others.isEmpty()) listOf("110 $label $mountPoint $state")
                else others.mapIndexed { i, v -> "110 ${v.substringAfterLast('/')} $v ${if (i == 0 && v == mountPoint) state else 0}" }) +
                "200 Volumes listed."
            head == "volume" && sub == "mount" -> { state = MOUNTED; after = { announce(IDLE, MOUNTED) }; listOf("200 Volume mounted.") }
            head == "volume" && sub == "unmount" -> { state = IDLE; after = { announce(MOUNTED, IDLE) }; listOf("200 Volume unmounted.") }
            head == "volume" && sub == "format" -> listOf("200 Volume formatted.")
            head == "share" && sub == "status" -> listOf("210 ${args.getOrElse(2) { "ums" }} unavailable")
            head == "share" || head == "unshare" -> listOf("200 Share operation succeeded.")
            head == "storage" && sub == "users" -> listOf("200 Storage user list.")
            head == "asec" && sub == "list" -> listOf("200 Asec listed.")
            head == "asec" && sub == "path" -> listOf("211 /mnt/asec/${args.getOrElse(2) { "" }}")
            head == "obb" && sub == "list" -> listOf("200 Obb listed.")
            
            
            
            head == "cryptfs" -> { log("vold: cryptfs ${args.drop(1).joinToString(" ")}"); if (sub == "getfield") listOf("200 0 0") else listOf("200 0") }
            else -> listOf("200 ok")
        }
        send(out, reply.map { withSeq(it, seq) })
        after?.invoke()
    }

    private fun withSeq(line: String, seq: Int?): String {
        if (seq == null) return line
        val sp = line.indexOf(' ')
        if (sp <= 0) return line
        val code = line.substring(0, sp).toIntOrNull() ?: return line
        if (code in 600..699) return line
        return "$code $seq${line.substring(sp)}"
    }

    private fun event(vararg msgs: String) {
        val list = synchronized(clients) { ArrayList(clients) }
        for (c in list) runCatching { send(c.outputStream, msgs.toList()) }
    }

    fun announce(from: Int, to: Int) {
        if (api >= 23) return
        val msg = "605 Volume $label $mountPoint state changed from $from (${name(from)}) to $to (${name(to)})"
        val list = synchronized(clients) { ArrayList(clients) }
        for (c in list) runCatching { send(c.outputStream, listOf(msg)) }
    }

    fun rescan() {
        state = MOUNTED
        if (legacy) {
            publish(MOUNTED_PROP)
            val list = synchronized(clients) { ArrayList(clients) }
            for (c in list) runCatching { send(c.outputStream, listOf("${evtPrefix}mounted:$mountPoint")) }
            return
        }
        announce(IDLE, MOUNTED)
    }

    private fun name(s: Int) = when (s) {
        0 -> "No-Media"; 1 -> "Idle-Unmounted"; 2 -> "Pending"; 3 -> "Checking"; 4 -> "Mounted"
        5 -> "Unmounting"; 6 -> "Formatting"; 7 -> "Shared-Unmounted"; else -> "Unknown"
    }

    @Synchronized
    private fun send(out: OutputStream, msgs: List<String>) {
        val b = ByteArrayOutputStream()
        for (s in msgs) { b.write(s.toByteArray()); b.write(0) }
        out.write(b.toByteArray())
        out.flush()
    }

    companion object {
        const val IDLE = 1
        const val MOUNTED = 4
        const val MOUNTED_PROP = "mounted"
        const val UNMOUNTED_PROP = "unmounted"
        
        const val STATE_PROP = "EXTERNAL_STORAGE_STATE"
    }
}


class FrameBell(paths: VmPaths, private val log: (String) -> Unit, private val onFrame: () -> Unit) {
    @Volatile var rings = 0L
        private set
    private val server = UnixServer(paths.frameSock, "frame") { c ->
        val ins = c.inputStream
        val b = ByteArray(64)
        while (true) {
            val n = try { ins.read(b) } catch (e: Exception) { -1 }
            if (n <= 0) break
            rings += n
            onFrame()
        }
    }
    fun serve() = server.start(log)
    fun stop() = server.stop()
}


class EventsSink(paths: VmPaths, private val log: (String) -> Unit) {
    private val fifo = File(paths.root, "dev/log/events")
    @Volatile private var raf: java.io.RandomAccessFile? = null

    fun start() {
        runCatching {
            val mode = runCatching { android.system.Os.stat(fifo.absolutePath).st_mode and android.system.OsConstants.S_IFMT }.getOrDefault(0)
            if (mode != android.system.OsConstants.S_IFIFO) {
                fifo.parentFile?.mkdirs(); fifo.delete()
                android.system.Os.mkfifo(fifo.absolutePath, "666".toInt(8))
            }
            
            val r = java.io.RandomAccessFile(fifo, "rw").also { raf = it }
            Thread({
                val buf = ByteArray(1 shl 16)
                while (true) { if (runCatching { r.read(buf) }.getOrDefault(-1) < 0) break }
            }, "aemu-events").apply { isDaemon = true; start() }
        }.onFailure { log("event log: channel failed: ${it.message}") }
    }

    fun stop() { runCatching { raf?.close() }; raf = null }
}


class LogdSink(paths: VmPaths, private val log: (String) -> Unit) {
    private val sock = File(paths.root, "dev/socket/logdw")
    private val dir = File(paths.root, "dev/log")
    @Volatile private var s: android.net.LocalSocket? = null

    fun start() {
        runCatching {
            sock.parentFile?.mkdirs(); sock.delete()
            val ls = android.net.LocalSocket(android.net.LocalSocket.SOCKET_DGRAM)
            ls.bind(android.net.LocalSocketAddress(sock.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
            sock.setWritable(true, false)
            s = ls
            val outs = HashMap<Int, java.io.FileOutputStream>()
            Thread({
                val buf = ByteArray(5 * 1024 + 64)
                val inp = ls.inputStream
                while (true) {
                    val n = runCatching { inp.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    if (n <= 12) continue
                    val id = buf[0].toInt() and 0xff
                    
                    
                    
                    val name = when (id) { 0 -> "main"; 1 -> "radio"; 2 -> "events"; 3 -> "system"; 4 -> "main"; 5 -> "stats"; 6 -> "security"; 7 -> "kernel"; else -> continue }
                    val o = outs.getOrPut(id) { java.io.FileOutputStream(File(dir, name), true) }
                    runCatching { o.write(buf, 11, n - 11); if (buf[n - 1].toInt() != 0) o.write(0) }
                }
                outs.values.forEach { runCatching { it.close() } }
            }, "aemu-logd").apply { isDaemon = true; start() }
        }.onFailure { log("logd: socket failed: ${it.message}") }
    }

    fun stop() { runCatching { s?.close() }; s = null; sock.delete() }
}
