package app.aemu.core

import java.io.File
import java.security.MessageDigest


object BinderGuard {
    const val ENV = "AEMU_GUARD"
    const val ENV_LOG = "AEMU_GUARD_LOG"
    const val LIB = "/system/lib/libaemubinderguard.so"
    private const val FILE = "binder.guard"
    const val LOG = "binderguard.log"

    
    private val BARE_REPLY = "ОТВЕТ ГОЛЫМ ОБЪЕКТОМ"
    private val HUNG = "ЗАВИСШАЯ ТРАНЗАКЦИЯ"
    
    private val BARE_COUNT = Regex("""ответов голым объектом\s+(\d+)""")

    data class State(val armed: Boolean, val ineffective: Boolean, val binderd: String, val since: String, val bare: Int)

    
    fun detect(binderdRun: String): Boolean = binderdRun.contains(BARE_REPLY) && binderdRun.contains(HUNG)

    
    fun bareCount(binderdRun: String): Int = BARE_COUNT.findAll(binderdRun).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0

    fun state(bin: File): State? = runCatching {
        val m = File(bin, FILE).readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        State(m["armed"] == "1", m["ineffective"] == "1", m["binderd"].orEmpty(), m["since"].orEmpty(), m["bare"]?.toIntOrNull() ?: 0)
    }.getOrNull()

    private fun write(bin: File, s: State) {
        runCatching { File(bin, FILE).writeText("armed=${if (s.armed) 1 else 0}\nineffective=${if (s.ineffective) 1 else 0}\nbinderd=${s.binderd}\nsince=${s.since}\nbare=${s.bare}\n") }
    }

    
    fun armed(bin: File, binderdId: String): Boolean = state(bin)?.let { it.armed && !it.ineffective && it.binderd == binderdId } == true

    
    fun known(bin: File, binderdId: String): State? = state(bin)?.takeIf { it.binderd == binderdId }

    fun arm(bin: File, binderdId: String, since: String, bare: Int) = write(bin, State(true, false, binderdId, since, bare))

    fun markIneffective(bin: File, binderdId: String, since: String, bare: Int) = write(bin, State(false, true, binderdId, since, bare))

    fun forget(bin: File) { runCatching { File(bin, FILE).delete() }; runCatching { File(bin, LOG).delete() } }

    
    fun events(bin: File): Int = runCatching {
        File(bin, LOG).useLines { ls -> ls.count { it.startsWith("[guard") && (it.contains("] rewrote foreign") || it.contains("] saw foreign")) } }
    }.getOrDefault(0)

    fun resetLog(bin: File) { runCatching { File(bin, LOG).delete() } }

    
    fun binderdId(lib: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        lib.inputStream().use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        "${lib.length()}-" + md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("unknown")
}
