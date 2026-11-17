
package app.aemu.core


internal class HeldNavKeys(private val send: (Int, Boolean) -> Unit) {
    private val held = mutableSetOf<Int>()
    fun down(code: Int) { if (held.add(code)) send(code, true) }
    fun up(code: Int) { if (held.remove(code)) send(code, false) }
    fun releaseAll() { held.toList().forEach(::up) }
}
