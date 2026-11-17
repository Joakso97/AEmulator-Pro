package app.aemu.core

import android.content.Context


class NetProxy(private val ctx: Context, private val paths: VmPaths, private val log: (String) -> Unit) {
    private var tls: TlsBridge? = null

    fun start(): GuestRunner.NetConfig {
        val t = TlsBridge(ctx, paths, log)
        val port = t.start()
        tls = t
        return GuestRunner.NetConfig(https443 = port)
    }

    fun stop() {
        tls?.stop()
        tls = null
    }
}
