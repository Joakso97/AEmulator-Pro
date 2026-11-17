package app.aemu.core


object GlassImePolicy {
    const val REMOTE_IME = "com.google.glass.remoteime/.RemoteImeService"
    const val FAILURE_MARK = "BOOT FAILURE starting Input Manager Service"

    
    fun valueToSeed(current: String?, remoteImeInstalled: Boolean): String? =
        if (current.isNullOrBlank() && remoteImeInstalled) REMOTE_IME else null

    
    fun enabledWith(current: String?, ime: String): String {
        val parts = current.orEmpty().split(':').filter { it.isNotBlank() }
        return if (ime in parts) parts.joinToString(":") else (parts + ime).joinToString(":")
    }
}
