package app.aemu.core

import android.os.Environment
import java.io.File


object FirmwareFolder {
    private val EXT = Regex("""(?i).*\.(zip|7z|rar|tar|md5|tgz|gz|xz|bz2|tbz2?|lz4|br|img|win|ext4|pac|ftf|sin|bin|iso|yaffs2|rfs|simg|ozip|ofp|kdz|dz)$""")

    val dir: File get() = File(Environment.getExternalStorageDirectory(), "aemulator/firmware")

    
    fun list(): List<File> = runCatching {
        if (!Environment.isExternalStorageManager()) return emptyList()
        dir.mkdirs()
        dir.listFiles().orEmpty().filter { it.isFile && it.length() > 1_000_000 && EXT.matches(it.name) }
            .sortedByDescending { it.lastModified() }
    }.getOrDefault(emptyList())
}
