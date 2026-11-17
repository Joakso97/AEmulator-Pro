package app.aemu.core

import android.system.Os
import java.io.File


object AppProcess {
    const val DEFAULT = "/system/bin/app_process"
    private const val ALT = "/system/bin/app_process32"

    
    private fun has(root: File, p: String) = File(root, p.removePrefix("/")).isFile

    
    fun path(root: File): String = when {
        has(root, DEFAULT) -> DEFAULT
        has(root, ALT) -> ALT
        else -> DEFAULT
    }

    
    fun ensureLink(root: File): Boolean {
        if (has(root, DEFAULT) || !has(root, ALT)) return false
        val link = File(root, DEFAULT.removePrefix("/"))
        return runCatching {
            
            if (runCatching { Os.lstat(link.absolutePath) }.isSuccess) link.delete()
            Os.symlink("app_process32", link.absolutePath)
            true
        }.getOrDefault(false)
    }

    private val EXEC = Regex("(?m)^(\\s*)exec (?:/system/bin/)?app_process\\b")

    
    fun fixExec(script: String, ap: String): String = EXEC.replace(script) { it.groupValues[1] + "exec " + ap }
}
