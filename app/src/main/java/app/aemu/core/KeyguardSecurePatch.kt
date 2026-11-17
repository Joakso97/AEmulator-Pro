
package app.aemu.core


object KeyguardSecurePatch {
    const val CLASS_DESC = "Lcom/android/internal/policy/impl/keyguard/KeyguardServiceWrapper;"
    const val METHOD = "isSecure"

    
    val OPTIONAL_METHODS = listOf("isShowing", "isShowingAndNotHidden")

    private const val CONST4: Byte = 0x12
    private const val RETURN: Byte = 0x0f

    private fun isDex(d: ByteArray) = d.size >= 112 && d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() &&
        d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte()

    
    private fun patchMethod(d: ByteArray, x: DexTools.Dex, name: String, required: Boolean): WatchdogPatch.Result {
        val codes = try { DexTools.methodCode(x, name, CLASS_DESC, returns = "Z") } catch (e: RuntimeException) {
            return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "unreadable class data: ${e.message}")
        } ?: return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT)
        if (codes.isEmpty() && !required) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT)
        if (codes.size != 1) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "${codes.size} boolean $name() methods with code in the class")
        val code = codes[0]
        val registers = DexTools.u2(d, code)
        val insnsSize = DexTools.u4(d, code + 12)
        val start = code + 16
        if (registers < 1) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "$name(): no register for the result")
        if (insnsSize < 2 || start + 2L * insnsSize > d.size) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "$name(): code item too short or out of range")
        if (d[start] == CONST4 && d[start + 1] == 0.toByte() && d[start + 2] == RETURN && d[start + 3] == 0.toByte())
            return WatchdogPatch.Result(WatchdogPatch.Outcome.ALREADY)
        
        
        var units = 0
        while (units < 2) {
            val len = DexTools.insnLength(d[start + 2 * units].toInt() and 0xff)
            if (units + len > insnsSize) return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "$name(): first instructions run past the code item")
            units += len
        }
        d[start] = CONST4; d[start + 1] = 0; d[start + 2] = RETURN; d[start + 3] = 0
        for (k in 4 until 2 * units) d[start + k] = 0
        DexTools.fixHeader(d)
        return WatchdogPatch.Result(WatchdogPatch.Outcome.PATCHED)
    }

    
    fun patchDex(d: ByteArray): WatchdogPatch.Result {
        if (!isDex(d)) return WatchdogPatch.Result(WatchdogPatch.Outcome.ABSENT, "not a dex")
        val x = try { DexTools.Dex(d) } catch (e: RuntimeException) {
            return WatchdogPatch.Result(WatchdogPatch.Outcome.UNEXPECTED, "unreadable header: ${e.message}")
        }
        val first = patchMethod(d, x, METHOD, required = true)
        if (first.outcome == WatchdogPatch.Outcome.ABSENT || first.outcome == WatchdogPatch.Outcome.UNEXPECTED) return first
        val rs = listOf(first) + OPTIONAL_METHODS.map { patchMethod(d, x, it, required = false) }
        for (o in listOf(WatchdogPatch.Outcome.PATCHED, WatchdogPatch.Outcome.ALREADY, WatchdogPatch.Outcome.UNEXPECTED))
            rs.firstOrNull { it.outcome == o }?.let { return it }
        return first
    }

    
    fun patchJar(jar: java.io.File, backup: java.io.File?): WatchdogPatch.Result =
        WatchdogPatch.patchJar(jar, backup) { patchDex(it) }

    
    fun isStockPlusPatch(jar: java.io.File, stock: java.io.File): Boolean {
        val tmp = java.io.File(jar.parentFile, jar.name + ".aemu-chk")
        return try {
            stock.copyTo(tmp, overwrite = true)
            patchJar(tmp, null)
            tmp.readBytes().contentEquals(jar.readBytes())
        } catch (e: Exception) {
            true
        } finally {
            tmp.delete()
        }
    }
}
