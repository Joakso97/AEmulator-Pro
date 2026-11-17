
package app.aemu.core

import app.aemu.importer.tools.FirmwareToolset
import app.aemu.importer.tools.SquashLayoutTool
import java.io.File


object SquashLayout {
    const val NAME = "system.squash.layout"

    
    const val PREFIX = "system/"

    fun file(vmDir: File) = File(vmDir, NAME)

    
    fun load(f: File): SquashLayoutTool.Tables = SquashLayoutTool.load(f)

    
    fun capture(
        image: File,
        out: File,
        log: (String) -> Unit,
        cancelled: () -> Boolean,
        visitor: FirmwareToolset.SquashVisitor? = null,
    ): SquashLayoutTool.Stats = SquashLayoutTool.capture(image, out, { log(it) }, { cancelled() }, visitor)

    
    fun rebuild(
        layout: File,
        out: File,
        content: (String) -> ByteArray?,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String> = SquashLayoutTool.rebuild(layout, out, { content(it) }, { d, t -> progress(d, t) })
}
