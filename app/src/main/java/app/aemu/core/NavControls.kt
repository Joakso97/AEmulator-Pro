package app.aemu.core


enum class NavButton(val id: String, val scanCode: Int) {
    BACK("back", 158), HOME("home", 102), RECENTS("recents", 580), MENU("menu", 139),
    SEARCH("search", 217), POWER("power", 116), VOLUME_DOWN("volume_down", 114),
    VOLUME_UP("volume_up", 115), UP("up", 103), DOWN("down", 108),
    LEFT("left", 105), RIGHT("right", 106), CENTER("center", 353);
}

object NavControls {
    const val DEFAULT_BUTTONS = "back,home,recents,menu"
    
    const val MMI_BUTTONS = "volume_up,volume_down,power,home,back"
    
    const val RECOVERY_BUTTONS = "volume_up,volume_down,power,back"
    fun parse(value: String): List<NavButton> = value.split(',').mapNotNull { id ->
        NavButton.entries.firstOrNull { it.id == id.trim() }
    }.distinct()
    fun encode(buttons: List<NavButton>): String = buttons.distinct().joinToString(",") { it.id }
}


class TrackballMotion(private val dpPerStep: Float) {
    private var x = 0f
    private var y = 0f
    init { require(dpPerStep.isFinite() && dpPerStep > 0) }
    fun move(dxDp: Float, dyDp: Float, dpad: Boolean): Pair<Int, Int> {
        if (!dxDp.isFinite() || !dyDp.isFinite()) return 0 to 0
        
        
        val scale = (if (dpad) 1f else 6f) / dpPerStep
        x += dxDp * scale
        y += dyDp * scale
        val dx = x.toInt(); val dy = y.toInt()
        x -= dx; y -= dy
        return dx to dy
    }
}

object TrackballEvents {
    fun frame(dx: Int = 0, dy: Int = 0, button: Boolean? = null, nanos: Long): ByteArray {
        val count = (if (dx != 0) 1 else 0) + (if (dy != 0) 1 else 0) + (if (button != null) 1 else 0)
        if (count == 0) return byteArrayOf()
        val b = java.nio.ByteBuffer.allocate((count + 1) * 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, code: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        }
        if (dx != 0) put(2, 0, dx) 
        if (dy != 0) put(2, 1, dy) 
        button?.let { put(1, 272, if (it) 1 else 0) } 
        put(0, 0, 0) 
        return b.array()
    }
}
