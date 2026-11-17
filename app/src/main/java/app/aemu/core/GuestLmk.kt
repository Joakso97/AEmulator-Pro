package app.aemu.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap



class GuestLmk(
    private val root: File,
    private val lowRam: Boolean,
    private val log: (String) -> Unit,
    private val ramMb: Int = 0,
    
    private val mode: MemoryMode = MemoryMode.DEFAULT,
) {
    private val adj = ConcurrentHashMap<Int, Int>()
    @Volatile private var running = false
    private var server: LocalServerSocket? = null
    private var bound: LocalSocket? = null
    private val fifo = File(root, "dev/aemu_oom")
    @Volatile var kills = 0
        private set

    fun start() {
        running = true
        runCatching {
            fifo.delete(); fifo.parentFile?.mkdirs()
            Os.mkfifo(fifo.absolutePath, "666".toInt(8))
            Os.chmod(fifo.absolutePath, "666".toInt(8))
            Thread({ readFifo() }, "lmk-fifo").apply { isDaemon = true; start() }
        }.onFailure { log("lmk: FIFO not created: $it") }
        runCatching { serveLmkd() }.onFailure { log("lmk: lmkd socket failed: $it") }
        Thread({ loop() }, "lmk").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }; runCatching { bound?.close() }
        
        UnixServer.poke(File(fifo.absolutePath), "\n")
        adj.clear()
    }

    private fun readFifo() {
        
        val fd = Os.open(fifo.absolutePath, OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        val buf = ByteArray(4096)
        val line = StringBuilder()
        FileInputStream(fd).use { input ->
            while (running) {
                val n = try { input.read(buf) } catch (_: Exception) { break }
                if (n <= 0) break
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '\n') { parseLine(line.toString()); line.setLength(0) } else if (line.length < 64) line.append(c)
                }
                
            }
        }
    }

    private fun parseLine(s: String) {
        val p = s.trim().split(' ').filter { it.isNotEmpty() }
        if (p.size < 3) return
        val pid = p[0].toIntOrNull() ?: return
        val v = p[2].toIntOrNull() ?: return
        adj[pid] = if (p[1] == "s") scoreToAdj(v) else v
    }

    
    private fun lmkdAdj(v: Int): Int = when {
        v in -17..16 -> v
        v < 0 -> -17
        v >= 900 -> CACHED + (v - 900) / 12
        else -> CACHED - 1
    }

    private fun scoreToAdj(score: Int) = if (score >= 1000) 15 else score * 17 / 1000

    private fun serveLmkd() {
        val sock = File(root, "dev/socket/lmkd")
        sock.parentFile?.mkdirs(); sock.delete()
        val ls = LocalSocket(LocalSocket.SOCKET_SEQPACKET)
        ls.bind(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        runCatching { Os.chmod(sock.absolutePath, "666".toInt(8)) }
        bound = ls
        val s = LocalServerSocket(ls.fileDescriptor)
        server = s
        Thread({
            while (running) {
                val c = try { s.accept() } catch (_: Exception) { break }
                Thread({ lmkdClient(c) }, "lmkd-client").apply { isDaemon = true; start() }
            }
        }, "lmkd-accept").apply { isDaemon = true; start() }
    }

    private fun lmkdClient(c: LocalSocket) {
        val buf = ByteArray(256)
        try {
            val input = c.inputStream
            while (running) {
                val n = input.read(buf)
                if (n <= 0) break
                val bb = java.nio.ByteBuffer.wrap(buf, 0, n)
                if (n < 8) continue
                when (bb.int) {
                    1 -> if (n >= 16) { val pid = bb.int; bb.int; adj[pid] = lmkdAdj(bb.int) }
                    2 -> adj.remove(bb.int)
                }
            }
        } catch (_: Exception) {
        } finally { runCatching { c.close() } }
    }

    

    private fun memAvailableMb(): Long = runCatching {
        File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemAvailable:") } }
            .split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrDefault(Long.MAX_VALUE)

    private fun rssMb(pid: Int): Long = runCatching {
        File("/proc/$pid/statm").readText().split(' ')[1].toLong() * 4 / 1024
    }.getOrDefault(-1)

    private fun loop() {
        
        
        
        log("memory: management ${mode.id}${if (lowRam) ", low memory mode" else ""}")
        while (running) {
            Thread.sleep(mode.intervalMs)
            adj.keys.removeAll { !File("/proc/$it").exists() }
            val cached = adj.filter { it.value >= CACHED }.keys
            if (cached.isEmpty()) continue
            val avail = memAvailableMb()
            
            val rssSum = if (ramMb > 0) adj.keys.sumOf { rssMb(it).coerceAtLeast(0) } else 0L
            if (!mode.shouldKill(lowRam, ramMb, avail, cached.size, rssSum)) continue
            val sizes = cached.associateWith { rssMb(it) }
            
            val victim = cached.maxWithOrNull(compareBy<Int> { adj[it] ?: 0 }.thenBy { sizes[it] ?: 0 }) ?: continue
            val a = adj[victim]
            runCatching { Os.kill(victim, OsConstants.SIGKILL) }
            adj.remove(victim)
            kills++
            log("memory: killed background process $victim (adj $a, ${sizes[victim]} MB; cached ${cached.size}, host free $avail MB)")
            Thread.sleep(mode.pauseMs)
        }
    }

    companion object {
        
        const val CACHED = 9
    }
}
