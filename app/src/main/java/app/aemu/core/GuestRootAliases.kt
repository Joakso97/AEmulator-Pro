
package app.aemu.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths


internal object GuestRootAliases {
    fun ensureEtc(root: File): Boolean {
        if (!File(root, "system/etc").isDirectory) return false
        val alias = File(root, "etc").toPath()
        if (Files.exists(alias, NOFOLLOW_LINKS)) {
            if (!Files.isSymbolicLink(alias) || Files.readSymbolicLink(alias).toString() != "/system/etc") return false
            Files.delete(alias) 
        }
        Files.createSymbolicLink(alias, Paths.get("system/etc"))
        return true
    }
}
