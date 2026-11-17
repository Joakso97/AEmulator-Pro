package app.aemu.importer

import java.io.InputStream




interface TreeSource {
    
    fun walk(visit: (path: String, isDir: Boolean, link: String?, mode: Int, size: Long, data: (() -> InputStream)?) -> Unit)
}
