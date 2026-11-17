package app.aemu.core

import android.view.KeyEvent


object LegacyKeys {
    private const val LETTERS_ROW1 = "qwertyuiop"   
    private const val LETTERS_ROW2 = "asdfghjkl"    
    private const val LETTERS_ROW3 = "zxcvbnm"      

    const val DEL = 14; const val TAB = 15; const val NEWLINE = 28; const val SHIFT = 42; const val SHIFT_RIGHT = 54
    const val ALT = 56; const val SPACE = 57; const val SYM = 127; const val AT = 215
    
    const val EXPLORER = 150; const val ENVELOPE = 155; const val CAMERA = 212
    const val COMMA = 51; const val PERIOD = 52; const val SLASH = 53

    
    fun letter(c: Char): Int? = LETTERS_ROW1.indexOf(c).takeIf { it >= 0 }?.let { 16 + it }
        ?: LETTERS_ROW2.indexOf(c).takeIf { it >= 0 }?.let { 30 + it }
        ?: LETTERS_ROW3.indexOf(c).takeIf { it >= 0 }?.let { 44 + it }

    
    fun fromAndroid(k: Int): Int? = when (k) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> letter('a' + (k - KeyEvent.KEYCODE_A))
        KeyEvent.KEYCODE_0 -> 11
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 2 + (k - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> NEWLINE
        KeyEvent.KEYCODE_DEL -> DEL
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_COMMA -> COMMA
        KeyEvent.KEYCODE_PERIOD -> PERIOD
        KeyEvent.KEYCODE_SLASH -> SLASH
        KeyEvent.KEYCODE_AT -> AT
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_RIGHT
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> ALT
        KeyEvent.KEYCODE_SYM -> SYM
        KeyEvent.KEYCODE_STAR -> VirtualKeys.STAR       
        KeyEvent.KEYCODE_POUND -> VirtualKeys.POUND
        KeyEvent.KEYCODE_DPAD_UP -> InputService.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> InputService.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> InputService.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> InputService.KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> InputService.KEY_CENTER
        KeyEvent.KEYCODE_ESCAPE -> InputService.KEY_BACK
        KeyEvent.KEYCODE_CALL -> VirtualKeys.SEND
        KeyEvent.KEYCODE_ENDCALL -> VirtualKeys.ENDCALL
        KeyEvent.KEYCODE_MENU -> InputService.KEY_MENU
        KeyEvent.KEYCODE_VOLUME_UP -> InputService.KEY_VOLUMEUP
        KeyEvent.KEYCODE_VOLUME_DOWN -> InputService.KEY_VOLUMEDOWN
        else -> null
    }

    
    val rows: List<List<VKey>> = run {
        fun k(label: String, code: Int, w: Float = 1f, shifted: String? = null) = VKey(label, code, w, shifted)
        fun letters(chars: String) = chars.map { k(it.toString(), letter(it)!!, 1f, it.uppercase()) }
        listOf(
            listOf(k("◀ Menu", InputService.KEY_MENU, 1.4f), k("▲", InputService.KEY_UP), k("Back ▶", InputService.KEY_BACK, 1.4f)),
            listOf(k("◀", InputService.KEY_LEFT), k("OK", InputService.KEY_CENTER), k("▶", InputService.KEY_RIGHT)),
            listOf(k("Call", VirtualKeys.SEND, 1.4f), k("▼", InputService.KEY_DOWN), k("End", VirtualKeys.ENDCALL, 1.4f)),
            listOf(k("Home", InputService.KEY_HOME), k("Explorer", EXPLORER, 1.3f), k("Envelope", ENVELOPE, 1.3f), k("Camera", CAMERA, 1.3f)),
            "1234567890".mapIndexed { i, c -> k(c.toString(), if (i == 9) 11 else i + 2) } +
                listOf(k("*", VirtualKeys.STAR), k("#", VirtualKeys.POUND)),
            letters(LETTERS_ROW1),
            letters(LETTERS_ROW2) + k("⌫", DEL, 1.5f),
            listOf(k("⇧", SHIFT, 1.6f)) + letters(LETTERS_ROW3) + listOf(k(",", COMMA), k(".", PERIOD), k("⏎", NEWLINE, 1.6f)),
            listOf(k("Alt", ALT, 1.4f), k("Sym", SYM, 1.4f), k("@", AT), k("␣", SPACE, 4f), k("/", SLASH), k("Tab", TAB, 1.3f)),
        )
    }
}
