package app.aemu.update

object UpdateAssetPolicy {
    const val PACKAGE = "app.aemu.pro"
    const val PLUS_PACKAGE = "app.aemu.plus"

    fun matches(name: String, applicationId: String): Boolean {
        if (!name.endsWith(".apk", ignoreCase = true)) return false
        val app = applicationId.lowercase()
        if (app != PACKAGE && app != PLUS_PACKAGE) return false
        val n = name.lowercase()
        if (n.endsWith("-$app.apk")) return true
        if (n == "aemulatorpro.apk") return true
        if (n.startsWith("aemulatorpro-") || n.startsWith("aemulator-pro-") || n.startsWith("aemulator_pro_")) return true
        return false
    }
}
