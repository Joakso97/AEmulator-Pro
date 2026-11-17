
package app.aemu.netdebug

import java.io.File


object GuestTrust {
    const val ALIAS = "aemu-http-debugger"
    private const val BACKUP = ".aemu-orig"

    fun install(root: File, ca: CertAuthority, log: (String) -> Unit = {}): List<String> {
        val done = ArrayList<String>()
        val pem = ca.caPem.toByteArray(Charsets.US_ASCII)
        val dirs = listOf("system/etc/security/cacerts", "apex/com.android.conscrypt/cacerts")
        for (rel in dirs) {
            val d = File(root, rel)
            
            if (d.isDirectory || (rel.startsWith("system") && File(root, "system/etc/security").isDirectory)) runCatching {
                d.mkdirs()
                val f = File(d, ca.trustFileName)
                if (!f.isFile || !f.readBytes().contentEquals(pem)) f.writeBytes(pem)
                done += rel + "/" + f.name
            }.onFailure { log("netdebug: cannot write $rel: $it") }
        }
        val bks = File(root, "system/etc/security/cacerts.bks")
        if (bks.isFile) runCatching {
            val cur = bks.readBytes()
            val bak = File(bks.parentFile, bks.name + BACKUP)
            if (!bak.exists()) bak.writeBytes(cur)
            val have = Bks.read(cur)?.entries?.any { it.alias == ALIAS && it.cert.contentEquals(ca.caDer) } == true
            if (!have) {
                val n = Bks.addCertificate(cur, ALIAS, ca.caDer)
                if (n == null) log("netdebug: cacerts.bks is not a certificate store this tool can rewrite; left alone")
                else { bks.writeBytes(n); done += "system/etc/security/cacerts.bks" }
            } else done += "system/etc/security/cacerts.bks"
        }.onFailure { log("netdebug: cannot update cacerts.bks: $it") }
        return done
    }

    fun uninstall(root: File, ca: CertAuthority) {
        for (rel in listOf("system/etc/security/cacerts", "apex/com.android.conscrypt/cacerts")) {
            runCatching { File(File(root, rel), ca.trustFileName).takeIf { it.isFile }?.delete() }
        }
        val bks = File(root, "system/etc/security/cacerts.bks")
        val bak = File(bks.parentFile, bks.name + BACKUP)
        runCatching {
            if (bak.isFile) { bak.copyTo(bks, overwrite = true); bak.delete() }
        }
    }
}
