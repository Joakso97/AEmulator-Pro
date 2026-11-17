
package app.aemu.core


internal class OneTimeVmNote {
    private var reportedAt: Long? = null
    @Synchronized fun reported(now: Long) { if (reportedAt == null) reportedAt = now }
    @Synchronized fun interrupted() { reportedAt = null }
    @Synchronized fun ready(now: Long, running: Boolean, healthy: Boolean): Boolean =
        running && healthy && reportedAt?.let { now >= it && now - it >= 10_000 } == true

    companion object {
        const val MAX_LENGTH = 2000
        fun normalize(text: String): String = text.filter { it >= ' ' || it == '\n' || it == '\t' }
            .trim().take(MAX_LENGTH)
    }
}
