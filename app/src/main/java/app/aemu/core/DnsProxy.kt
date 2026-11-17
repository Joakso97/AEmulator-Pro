package app.aemu.core

import android.net.LocalSocket
import android.system.Os
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress


class DnsProxy(private val paths: VmPaths, private val api: Int, private val log: (String) -> Unit, private val probe: Boolean = false) {
    
    @Volatile private var block: Boolean? = if (probe) null else false
    private val server = UnixServer(paths.socket("dnsproxyd"), "dnsproxy") { c -> serve(c) }
    private var inode = -1L
    private var failures = 0
    @Volatile var queries = 0L
        private set
    
    @Volatile var debugger: app.aemu.netdebug.NetDebugger? = null

    private fun lookup(host: String): List<InetAddress> {
        val d = debugger
        if (d == null || host.all { it.isDigit() || it == '.' || it == ':' }) return InetAddress.getAllByName(host).toList()
        return d.answer(host) ?: throw java.net.UnknownHostException(host)
    }

    @Synchronized
    fun ensure() {
        val f = paths.socket("dnsproxyd")
        val ino = runCatching { Os.stat(f.absolutePath).st_ino }.getOrDefault(-2L)
        if (server.running && ino == inode) return
        server.stop()
        if (server.start(log)) inode = runCatching { Os.stat(f.absolutePath).st_ino }.getOrDefault(-1L)
    }

    fun stop() { server.stop(); inode = -1 }

    private fun serve(c: LocalSocket) {
        val req = ByteArrayOutputStream()
        val inp = c.inputStream
        while (req.size() < 1024) {
            val b = inp.read()
            if (b <= 0) break
            req.write(b)
        }
        val t = req.toString("UTF-8").trim().split(' ').filter { it.isNotEmpty() }
        if (t.isEmpty()) return
        queries++
        if (queries <= 12) log("dns: " + t.joinToString(" "))
        val sock = c.outputStream
        val out = ByteArrayOutputStream()
        val t0 = System.currentTimeMillis()
        try {
            when (t[0]) {
                "getaddrinfo" -> if (t.size >= 6) getaddrinfo(out, t) else fail(out)
                "gethostbyname" -> hostByName(out, t)
                else -> fail(out)
            }
        } catch (e: Throwable) {
            if (queries <= 40 || failures++ < 200) log("dns: " + t.joinToString(" ") + " failed: " + e)
            runCatching { fail(out) }
        }
        val bytes = out.toByteArray()
        var sent = 0
        try {
            if (block == null && t[0] == "getaddrinfo" && bytes.size > 12) {
                sock.write(bytes, 0, 8); sock.flush(); sent = 8
                c.soTimeout = 300
                val hungUp = try { inp.read() < 0 } catch (e: java.io.IOException) { false }
                block = hungUp
                log("dns: struct addrinfo goes " + if (hungUp) "as one block" else "field by field")
                if (hungUp) return
            }
            while (sent < bytes.size) { val n = minOf(4, bytes.size - sent); sock.write(bytes, sent, n); sock.flush(); sent += n }
        } catch (e: java.io.IOException) { log("dns: client closed after $sent of ${bytes.size} bytes") }
        if (queries <= 6) log("dns: reply " + out.toByteArray().take(96).joinToString("") { "%02x".format(it) })
        if (queries <= 40) log("dns: " + t.getOrElse(1) { "" } + " answered in " + (System.currentTimeMillis() - t0) + " ms")
    }

    private fun fail(o: java.io.OutputStream) {
        o.write(byteArrayOf('4'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 0))
        o.write(be(4)); o.write(be(EAI_NODATA))
    }

    private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun ok(o: java.io.OutputStream) = o.write(byteArrayOf('2'.code.toByte(), '2'.code.toByte(), '2'.code.toByte(), 0))

    private fun lenData(o: java.io.OutputStream, d: ByteArray) { o.write(be(d.size)); if (d.isNotEmpty()) o.write(d) }

    
    private fun getaddrinfo(o: java.io.OutputStream, t: List<String>) {
        val host = t[1].takeIf { it != "^" }
        val serv = t[2].takeIf { it != "^" }
        val flags = t[3].toIntOrNull() ?: 0
        val family = t[4].toIntOrNull() ?: 0
        val socktype = t[5].toIntOrNull() ?: 0
        val port = when (serv) {
            null -> 0
            "http" -> 80; "https" -> 443; "ftp" -> 21; "domain" -> 53
            else -> serv.toIntOrNull() ?: 0
        }
        val addrs: List<InetAddress> = when {
            host == null -> listOf(InetAddress.getByAddress(if (flags and AI_PASSIVE != 0) byteArrayOf(0, 0, 0, 0) else byteArrayOf(127, 0, 0, 1)))
            else -> {
                val all = lookup(host)
                val v4 = all.filterIsInstance<Inet4Address>()
                when (family) {
                    AF_INET -> v4
                    AF_INET6 -> all.filterIsInstance<Inet6Address>()
                    else -> v4.ifEmpty { all }   
                }
            }
        }
        if (addrs.isEmpty()) { log("dns: no addresses for $host"); return fail(o) }
        if (queries <= 40) log("dns: $host -> " + addrs.joinToString { it.hostAddress ?: "?" })
        val types = if (socktype != 0) listOf(socktype) else listOf(1, 2, 3)
        ok(o)
        var first = true
        for (a in addrs) for (st in types) {
            val fam = if (a is Inet6Address) AF_INET6 else AF_INET
            val proto = when (st) { 1 -> 6; 2 -> 17; else -> 0 }
            val sa = sockaddr(a, port)
            val canon = if (first && flags and AI_CANONNAME != 0 && host != null) (host + "\u0000").toByteArray() else ByteArray(0)
            if (block == true) {
                
                
                val st64 = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                st64.putInt(flags).putInt(fam).putInt(st).putInt(proto).putInt(sa.size)
                lenData(o, st64.array()); lenData(o, sa); lenData(o, canon)
            } else if (api <= 21) {
                
                val st32 = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                st32.putInt(flags).putInt(fam).putInt(st).putInt(proto).putInt(sa.size)
                lenData(o, st32.array()); lenData(o, sa); lenData(o, canon)
            } else {
                o.write(be(1))
                o.write(be(flags)); o.write(be(fam)); o.write(be(st)); o.write(be(proto))
                lenData(o, sa); lenData(o, canon)
            }
            first = false
        }
        o.write(be(0))
    }

    private fun sockaddr(a: InetAddress, port: Int): ByteArray {
        val raw = a.address
        return if (a is Inet6Address) ByteArray(28).also {
            it[0] = AF_INET6.toByte(); it[2] = (port ushr 8).toByte(); it[3] = port.toByte()
            System.arraycopy(raw, 0, it, 8, 16)
        } else ByteArray(16).also {
            it[0] = AF_INET.toByte(); it[2] = (port ushr 8).toByte(); it[3] = port.toByte()
            System.arraycopy(raw, 0, it, 4, 4)
        }
    }

    
    private fun hostByName(o: java.io.OutputStream, t: List<String>) {
        val args = t.drop(1)
        val name = args.firstOrNull { !it.all { ch -> ch.isDigit() } } ?: args.firstOrNull() ?: return fail(o)
        val af = args.filter { it != name }.mapNotNull { it.toIntOrNull() }.firstOrNull { it == AF_INET || it == AF_INET6 } ?: AF_INET
        if (af != AF_INET) return fail(o)
        val v4 = lookup(name).filterIsInstance<Inet4Address>()
        if (v4.isEmpty()) return fail(o)
        ok(o)
        lenData(o, (name + "\u0000").toByteArray())
        lenData(o, ByteArray(0))                    
        o.write(be(AF_INET)); o.write(be(4))
        for (a in v4) lenData(o, a.address.copyOf(16))
        lenData(o, ByteArray(0))
    }

    private companion object {
        const val AF_INET = 2
        const val AF_INET6 = 10
        const val AI_PASSIVE = 1
        const val AI_CANONNAME = 2
        const val EAI_NODATA = 7
    }
}
