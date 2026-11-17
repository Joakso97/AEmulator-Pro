
package app.aemu.core

import android.app.Activity
import android.app.ActivityManager
import android.app.Service
import android.content.Context
import app.aemu.ui.VmActivity
import app.aemu.ui.VmActivity1
import app.aemu.ui.VmActivity2
import app.aemu.ui.VmActivity3
import app.aemu.ui.VmOverlayActivity
import app.aemu.ui.VmOverlayActivity1
import app.aemu.ui.VmOverlayActivity2
import app.aemu.ui.VmOverlayActivity3
import dev.lk.dhd.BinderSlot
import dev.lk.dhd.BinderSlot1
import dev.lk.dhd.BinderSlot2
import dev.lk.dhd.BinderSlot3
import dev.lk.dhd.GlSlot
import dev.lk.dhd.GlSlot1
import dev.lk.dhd.GlSlot2
import dev.lk.dhd.GlSlot3
import dev.lk.dhd.Slot
import java.io.File
import java.io.RandomAccessFile


object VmSlots {
    const val COUNT = 4
    private const val CLAIM_MS = 20_000L

    fun suffix(slot: Int) = if (slot <= 0) "" else slot.toString()
    fun processName(ctx: Context, slot: Int) = ctx.packageName + ":vm" + suffix(slot)
    fun binderProcessName(ctx: Context, slot: Int) = ctx.packageName + ":binder" + suffix(slot)
    fun glProcessName(ctx: Context, slot: Int) = ctx.packageName + ":gl" + suffix(slot)

    
    private val self: Int by lazy {
        val name = runCatching { File("/proc/self/cmdline").readBytes().toString(Charsets.ISO_8859_1).substringBefore('\u0000') }.getOrDefault("")
        Regex(":vm(\\d*)$").find(name)?.let { it.groupValues[1].toIntOrNull() ?: 0 } ?: -1
    }

    
    fun current(): Int = self.coerceIn(0, COUNT - 1)

    
    fun activityClass(slot: Int, overlay: Boolean = false): Class<out Activity> = if (overlay) when (slot) {
        1 -> VmOverlayActivity1::class.java
        2 -> VmOverlayActivity2::class.java
        3 -> VmOverlayActivity3::class.java
        else -> VmOverlayActivity::class.java
    } else when (slot) {
        1 -> VmActivity1::class.java
        2 -> VmActivity2::class.java
        3 -> VmActivity3::class.java
        else -> VmActivity::class.java
    }

    fun keeperClass(slot: Int): Class<out Service> = when (slot) {
        1 -> Keeper1::class.java
        2 -> Keeper2::class.java
        3 -> Keeper3::class.java
        else -> Keeper::class.java
    }

    fun binderSlotClass(slot: Int): Class<out Slot> = when (slot) {
        1 -> BinderSlot1::class.java
        2 -> BinderSlot2::class.java
        3 -> BinderSlot3::class.java
        else -> BinderSlot::class.java
    }

    fun glSlotClass(slot: Int): Class<out Slot> = when (slot) {
        1 -> GlSlot1::class.java
        2 -> GlSlot2::class.java
        3 -> GlSlot3::class.java
        else -> GlSlot::class.java
    }

    
    class Snapshot(val occupied: Set<Int>, val owner: Map<Int, String>, val reliable: Boolean) {
        fun slotOf(id: String): Int? = owner.entries.firstOrNull { it.value == id }?.key
        val ids: Set<String> get() = owner.values.toSet()
    }

    private fun slotDir(ctx: Context) = File(ctx.filesDir, "slots").apply { mkdirs() }
    private fun ownerFile(ctx: Context, slot: Int) = File(slotDir(ctx), "slot$slot.owner")

    private fun runningProcesses(ctx: Context): Set<String>? = runCatching {
        ctx.getSystemService(ActivityManager::class.java).runningAppProcesses?.map { it.processName }?.toSet()
    }.getOrNull()

    fun snapshot(ctx: Context): Snapshot {
        val running = runningProcesses(ctx)
        val now = System.currentTimeMillis()
        val occupied = HashSet<Int>()
        val owner = HashMap<Int, String>()
        for (n in 0 until COUNT) {
            val f = ownerFile(ctx, n)
            val alive = running?.contains(processName(ctx, n)) ?: f.isFile
            val fresh = f.isFile && now - f.lastModified() < CLAIM_MS
            if (!alive && !fresh) continue
            occupied += n
            runCatching { f.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { owner[n] = it }
        }
        return Snapshot(occupied, owner, running != null)
    }

    fun slotOf(ctx: Context, id: String): Int? = snapshot(ctx).slotOf(id)

    
    fun anyAlive(ctx: Context): Boolean = snapshot(ctx).occupied.isNotEmpty()

    
    @Synchronized fun allocate(ctx: Context, id: String): Int {
        RandomAccessFile(File(slotDir(ctx), "alloc.lock"), "rw").use { raf ->
            raf.channel.lock().use {
                val snap = snapshot(ctx)
                snap.slotOf(id)?.let { return it }
                val free = (0 until COUNT).firstOrNull { it !in snap.occupied } ?: return -1
                ownerFile(ctx, free).writeText(id)
                return free
            }
        }
    }

    
    fun adopt(ctx: Context, id: String) {
        runCatching {
            val f = ownerFile(ctx, current())
            if (!f.isFile || f.readText().trim() != id) f.writeText(id) else f.setLastModified(System.currentTimeMillis())
        }
    }
}
