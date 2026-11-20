package app.aemu.update


object UpdateAssetPolicy {
    const val PACKAGE = "app.aemu.pro"

    fun matches(name: String, applicationId: String): Boolean =
        applicationId == PACKAGE && (
            name.endsWith("-$PACKAGE.apk", ignoreCase = true) ||
                name.equals("AEmulatorPro.apk", ignoreCase = true)
            )
}
