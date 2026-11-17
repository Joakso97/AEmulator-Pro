package app.aemu.update


object UpdateAssetPolicy {
    const val PACKAGE = "app.aemu.plus"

    fun matches(name: String, applicationId: String): Boolean =
        applicationId == PACKAGE && name.endsWith("-$PACKAGE.apk", ignoreCase = true)
}
