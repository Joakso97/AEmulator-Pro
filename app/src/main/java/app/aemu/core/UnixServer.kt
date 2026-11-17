package app.aemu.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor


class UnixServer(
    val file: File,
    private val name: String,
    private val sockType: Int = LocalSocket.SOCKET_STREAM,
    private val onClient: (LocalSocket) -> Unit,
) {
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var bound: LocalSocket? = null
    @Volatile var clients = 0
        private set

    val running: Boolean get() = server != null && file.exists()

    @Synchronized
    fun start(log: (String) -> Unit): Boolean {
        if (running) return true
        stop()
        file.parentFile?.mkdirs()
        file.delete()
        return try {
            val ls = LocalSocket(sockType)
            ls.bind(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            cloexec(ls.fileDescriptor)
            runCatching { Os.chmod(file.absolutePath, "666".toInt(8)) }
            bound = ls
            val s = LocalServerSocket(ls.fileDescriptor)
            server = s
            Thread({
                while (true) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    cloexec(c.fileDescriptor)
                    clients++
                    Thread({
                        try { onClient(c) } catch (_: Throwable) {
                        } finally {
                            clients--
                            runCatching { c.close() }
                        }
                    }, "$name-client").apply { isDaemon = true; start() }
                }
            }, "$name-accept").apply { isDaemon = true; start() }
            true
        } catch (e: Throwable) {
            log("$name: socket failed: $e")
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        runCatching { bound?.close() }
        server = null
        bound = null
        clients = 0
    }

    companion object {
        
        fun poke(file: File, text: String) {
            runCatching {
                val fd = Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                try {
                    val b = text.toByteArray()
                    Os.write(fd, b, 0, b.size)
                } finally { runCatching { Os.close(fd) } }
            }
        }

        fun cloexec(fd: FileDescriptor?) {
            if (fd == null) return
            runCatching { Os.fcntlInt(fd, OsConstants.F_SETFD, OsConstants.FD_CLOEXEC) }
        }
    }
}
