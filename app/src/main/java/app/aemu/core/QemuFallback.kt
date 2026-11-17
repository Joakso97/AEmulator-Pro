package app.aemu.core

import java.io.File


object QemuFallback {
    const val ERROR = "no vspace available"
    private const val MARKER = "qemu.xperiaplay"

    private fun marker(paths: VmPaths) = File(paths.dir, MARKER)

    fun enabled(paths: VmPaths): Boolean = marker(paths).exists()

    fun enable(paths: VmPaths): Boolean = runCatching { marker(paths).writeText("1"); true }.getOrDefault(false)

    
    fun isLinkFailure(text: String): Boolean = text.contains(ERROR) && text.contains("cannot map library")

    
    fun lastRun(logText: String, name: String): String = logText.substringAfterLast("=== $name ", logText)
}
