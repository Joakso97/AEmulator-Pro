
package app.aemu.core

internal object VmUiPolicy {
    enum class BackAction { CLOSE_LOG, OPEN_MENU, GUEST_BACK }
    fun back(logVisible: Boolean, hideMenu: Boolean) = when {
        logVisible -> BackAction.CLOSE_LOG
        hideMenu -> BackAction.OPEN_MENU
        else -> BackAction.GUEST_BACK
    }
    fun active(state: String) = state in setOf("PREPARING", "BOOTING", "RUNNING")

    
    fun adbAvailable(state: String, adbdUp: Boolean, recovery: Boolean) =
        adbdUp && !recovery && (state == "BOOTING" || state == "RUNNING")

    
    fun clampMenuOffset(x: Float, y: Float, areaWidth: Int, areaHeight: Int, buttonWidth: Int, buttonHeight: Int): Pair<Float, Float> {
        val maxLeft = (areaWidth - buttonWidth).coerceAtLeast(0).toFloat()
        val maxDown = (areaHeight - buttonHeight).coerceAtLeast(0).toFloat()
        
        val sx = if (x.isFinite()) x else 0f
        val sy = if (y.isFinite()) y else 0f
        return sx.coerceIn(0f - maxLeft, 0f) to sy.coerceIn(0f, maxDown) 
    }
}
