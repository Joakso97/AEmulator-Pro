package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder


object VirtualKeys {
    const val ESC = 1; const val BACKSPACE = 14; const val TAB = 15; const val ENTER = 28
    const val CTRL = 29; const val SHIFT = 42; const val ALT = 56; const val SPACE = 57; const val CAPS = 58
    const val HOME = 102; const val UP = 103; const val PAGE_UP = 104; const val LEFT = 105; const val RIGHT = 106
    const val END = 107; const val DOWN = 108; const val PAGE_DOWN = 109; const val INSERT = 110; const val DELETE = 111
    const val KPASTERISK = 55; const val SEND = 231; const val ENDCALL = 107
    
    const val STAR = 522; const val POUND = 523
    const val SELECT = 232
}


data class VKey(val label: String, val code: Int, val weight: Float = 1f, val shifted: String? = null) {
    val isModifier: Boolean get() = code == VirtualKeys.SHIFT || code == VirtualKeys.CTRL || code == VirtualKeys.ALT
}

object VirtualKeyboardLayout {
    private fun k(label: String, code: Int, w: Float = 1f, shifted: String? = null) = VKey(label, code, w, shifted)
    private val numbers = "1234567890"
    private val numShift = "!@#$%^&*()"
    val rows: List<List<VKey>> = listOf(
        listOf(k("Esc", 1, 1.4f)) + numbers.mapIndexed { i, c -> k(c.toString(), if (i == 9) 11 else i + 2, 1f, numShift[i].toString()) } +
            listOf(k("-", 12, 1f, "_"), k("=", 13, 1f, "+"), k("⌫", VirtualKeys.BACKSPACE, 1.5f)),
        listOf(k("Tab", VirtualKeys.TAB, 1.4f)) + "qwertyuiop".mapIndexed { i, c -> k(c.toString(), 16 + i, 1f, c.uppercase()) } +
            listOf(k("[", 26, 1f, "{"), k("]", 27, 1f, "}"), k("\\", 43, 1f, "|")),
        listOf(k("Caps", VirtualKeys.CAPS, 1.7f)) + "asdfghjkl".mapIndexed { i, c -> k(c.toString(), 30 + i, 1f, c.uppercase()) } +
            listOf(k(";", 39, 1f, ":"), k("'", 40, 1f, "\""), k("⏎", VirtualKeys.ENTER, 1.9f)),
        listOf(k("⇧", VirtualKeys.SHIFT, 2.2f)) + "zxcvbnm".mapIndexed { i, c -> k(c.toString(), 44 + i, 1f, c.uppercase()) } +
            listOf(k(",", 51, 1f, "<"), k(".", 52, 1f, ">"), k("/", 53, 1f, "?"), k("↑", VirtualKeys.UP, 1f), k("`", 41, 1f, "~")),
        listOf(k("Ctrl", VirtualKeys.CTRL, 1.6f), k("Alt", VirtualKeys.ALT, 1.4f), k("Home", VirtualKeys.HOME, 1.4f),
            k("␣", VirtualKeys.SPACE, 5f), k("Del", VirtualKeys.DELETE, 1.4f), k("←", VirtualKeys.LEFT), k("↓", VirtualKeys.DOWN), k("→", VirtualKeys.RIGHT)),
    )
}


internal class KeyboardLatch(private val send: (Int, Boolean) -> Unit) {
    private val latched = linkedSetOf<Int>()
    val latchedModifiers: Set<Int> get() = latched
    private val pressed = mutableSetOf<Int>()

    fun tapModifier(code: Int) {
        if (latched.remove(code)) send(code, false) else { latched.add(code); send(code, true) }
    }

    fun down(code: Int) { if (pressed.add(code)) send(code, true) }

    fun up(code: Int) {
        if (!pressed.remove(code)) return
        send(code, false)
        if (latched.isNotEmpty()) { latched.toList().forEach { send(it, false) }; latched.clear() }
    }

    
    fun releaseAll() {
        pressed.toList().forEach { send(it, false) }; pressed.clear()
        latched.toList().forEach { send(it, false) }; latched.clear()
    }
}


data class PadKey(val label: String, val code: Int, val sub: String = "")

object Keypad {
    val softLeft = PadKey("◀ Menu", InputService.KEY_MENU)
    val softRight = PadKey("Back ▶", InputService.KEY_BACK)
    val call = PadKey("Call", VirtualKeys.SEND)
    val end = PadKey("End", VirtualKeys.ENDCALL)
    val up = PadKey("▲", VirtualKeys.UP)
    val down = PadKey("▼", VirtualKeys.DOWN)
    val left = PadKey("◀", VirtualKeys.LEFT)
    val right = PadKey("▶", VirtualKeys.RIGHT)
    val ok = PadKey("OK", VirtualKeys.SELECT)
    
    val digits: List<PadKey> = listOf(
        PadKey("1", 2), PadKey("2", 3, "abc"), PadKey("3", 4, "def"),
        PadKey("4", 5, "ghi"), PadKey("5", 6, "jkl"), PadKey("6", 7, "mno"),
        PadKey("7", 8, "pqrs"), PadKey("8", 9, "tuv"), PadKey("9", 10, "wxyz"),
        PadKey("*", VirtualKeys.STAR), PadKey("0", 11, "+"), PadKey("#", VirtualKeys.POUND),
    )
}


class TouchpadProtocol {
    data class Contact(val id: Int, val x: Int, val y: Int)

    private var down = false
    private var last: List<Contact> = emptyList()

    
    fun frame(now: List<Contact>, nanos: Long): ByteArray {
        if (now.isEmpty() && !down) return ByteArray(0)
        if (now.isNotEmpty() && now == last) return ByteArray(0)
        val b = ByteBuffer.allocate((now.size * 6 + 6) * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, code: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        }
        for (c in now) {
            put(EV_ABS, ABS_MT_TRACKING_ID, c.id.coerceIn(0, 31))
            put(EV_ABS, ABS_MT_POSITION_X, c.x.coerceIn(0, PAD_MAX))
            put(EV_ABS, ABS_MT_POSITION_Y, c.y.coerceIn(0, PAD_MAX))
            put(EV_ABS, ABS_MT_TOUCH_MAJOR, 40)
            put(EV_ABS, ABS_MT_PRESSURE, 64)
            put(EV_SYN, SYN_MT_REPORT, 0)
        }
        val isDown = now.isNotEmpty()
        if (isDown != down) { put(EV_KEY, BTN_TOUCH, if (isDown) 1 else 0); down = isDown }
        if (!isDown) put(EV_SYN, SYN_MT_REPORT, 0)
        put(EV_SYN, SYN_REPORT, 0)
        last = now
        return b.array().copyOf(b.position())
    }

    
    fun button(code: Int, pressed: Boolean, nanos: Long): ByteArray {
        val b = ByteBuffer.allocate(2 * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, c: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(c.toShort()).putInt(value)
        }
        put(EV_KEY, code, if (pressed) 1 else 0); put(EV_SYN, SYN_REPORT, 0)
        return b.array()
    }

    companion object {
        const val PAD_MAX = 4095
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        private const val EV = 16
        private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_ABS = 3
        private const val SYN_REPORT = 0; private const val SYN_MT_REPORT = 2
        private const val ABS_MT_TOUCH_MAJOR = 48; private const val ABS_MT_POSITION_X = 53
        private const val ABS_MT_POSITION_Y = 54; private const val ABS_MT_TRACKING_ID = 57; private const val ABS_MT_PRESSURE = 58
        private const val BTN_TOUCH = 330

        fun pad(v: Int, size: Int): Int = if (size <= 1) 0 else (v.toLong() * PAD_MAX / (size - 1)).toInt().coerceIn(0, PAD_MAX)
    }
}


object VirtualInputPolicy {
    
    @Suppress("UNUSED_PARAMETER")
    fun mouseKeyboardSupported(engine: Engine, arch: GuestArch, recovery: Boolean, nativeUi: Boolean, preRelease: Boolean = false): Boolean =
        !recovery && !nativeUi && !preRelease   
}
