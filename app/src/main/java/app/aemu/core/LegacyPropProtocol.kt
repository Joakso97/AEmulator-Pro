
package app.aemu.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


object LegacyPropProtocol {
    const val CMD_GET = 2
    const val CMD_SET = 3
    const val CMD_LIST = 6

    
    const val HEADER = 12

    
    const val MAX_KEY = 128
    const val MAX_VALUE = 256

    
    const val OK = 0
    const val NOT_FOUND = 1
    const val BAD_REQUEST = 2

    
    fun isLegacyCmd(cmd: Int): Boolean = cmd == CMD_GET || cmd == CMD_SET || cmd == CMD_LIST

    class Request(val cmd: Int, val key: String, val value: String)

    private fun le(i: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()

    private fun readInt(inp: InputStream): Int {
        val b = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = inp.read(b, n, 4 - n)
            if (r < 0) { if (n == 0) throw EOFException(); throw IOException("property_service: request cut short") }
            n += r
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    private fun readBytes(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = inp.read(b, at, n - at)
            if (r < 0) throw IOException("property_service: request cut short")
            at += r
        }
        return b
    }

    private fun cstr(b: ByteArray, from: Int, len: Int): String {
        var n = 0
        while (n < len && b[from + n] != 0.toByte()) n++
        return String(b, from, n, Charsets.UTF_8)
    }

    
    fun readRequest(inp: InputStream, cmd: Int): Request {
        val keyLen = readInt(inp)
        val valueLen = readInt(inp)
        if (keyLen < 0 || keyLen > MAX_KEY || valueLen < 0 || valueLen > MAX_VALUE)
            throw IOException("property_service: bad lengths $keyLen/$valueLen for cmd $cmd")
        val data = readBytes(inp, keyLen + valueLen)
        return Request(cmd, cstr(data, 0, keyLen), cstr(data, keyLen, valueLen))
    }

    
    fun reply(status: Int, value: String? = null): ByteArray {
        val v = value?.let { it.toByteArray(Charsets.UTF_8).let { b -> if (b.size > MAX_VALUE - 1) b.copyOf(MAX_VALUE - 1) else b } }
        val out = ByteBuffer.allocate(HEADER + (if (v == null) 0 else v.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(status).putInt(0).putInt(if (v == null) 0 else v.size + 1)
        if (v != null) { out.put(v); out.put(0) }
        return out.array()
    }

    
    fun answer(req: Request, get: (String) -> String?, set: (String, String) -> Unit): ByteArray = when (req.cmd) {
        CMD_GET -> get(req.key)?.let { reply(OK, it) } ?: reply(NOT_FOUND)
        CMD_SET -> if (req.key.isEmpty()) reply(BAD_REQUEST) else { set(req.key, req.value); reply(OK) }
        CMD_LIST -> reply(OK)   
        else -> reply(BAD_REQUEST)
    }

    
    fun serve(inp: InputStream, out: OutputStream, firstCmd: Int, get: (String) -> String?, set: (String, String) -> Unit,
              log: (String) -> Unit = {}) {
        var cmd = firstCmd
        while (true) {
            if (!isLegacyCmd(cmd)) { log("props: unknown pre-1.0 command $cmd"); return }
            val req = readRequest(inp, cmd)
            out.write(answer(req, get, set))
            out.flush()
            cmd = try { readInt(inp) } catch (_: EOFException) { return }
        }
    }
}
