package app.aemu.core

import android.content.Context


class TlsBridge(private val ctx: Context, private val paths: VmPaths, private val log: (String) -> Unit) {
    fun start(): Int = 0
    fun stop() {}
}
