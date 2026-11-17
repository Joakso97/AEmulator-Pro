
package app.aemu.ui


internal object LibraryLabels {
    fun compactSkin(skin: String): String {
        val match = Regex("^CyanogenMod(?:\\s+([0-9]+(?:\\.[0-9]+)*))?$").matchEntire(skin.trim())
        return if (match != null) "CM${match.groupValues[1]}" else skin
    }
}
