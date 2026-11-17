
package app.aemu.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.LocalSocket
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap


class MicBridge(private val ctx: Context, paths: VmPaths, private val enabled: () -> Boolean,
                private val log: (String) -> Unit) {
    
    private val captureRate: Int = runCatching {
        listOf(48000, 44100, 16000).first {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        }
    }.getOrDefault(48000)

    private inner class Client(val format: MicProtocol.Format, val socket: LocalSocket) {
        val resampler = MicResampler(captureRate, format)
        
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
    }

    @Volatile private var active = false
    @Volatile private var foreground = false
    private val lock = Any()
    private val clients = ConcurrentHashMap.newKeySet<Client>()
    private var pump: Thread? = null
    private val server = UnixServer(File(paths.root, "dev/aemu_mic"), "microphone") { client ->
        serveClient(client)
    }

    private fun permitted() = foreground && active && enabled() &&
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun serve(): Boolean {
        active = true
        val ok = server.start(log)
        if (!ok) active = false
        log("microphone: bridge ${if (ok) "ready" else "unavailable"}; " +
            "permission ${ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED}; " +
            "enabled ${enabled()}; capture $captureRate Hz")
        return ok
    }

    fun visible(value: Boolean) {
        foreground = value
        if (!value) closeAll()
    }

    fun stop() {
        active = false
        closeAll()
        server.stop()
    }

    
    private fun closeAll() {
        clients.toList().forEach { drop(it, "closed") }
    }

    private fun drop(client: Client, reason: String) {
        if (clients.remove(client)) {
            client.queue.clear()
            client.queue.offer(STOP)              
            runCatching { client.socket.close() } 
            log("microphone: guest detached ($reason)")
        }
    }

    private fun serveClient(socket: LocalSocket) {
        socket.soTimeout = 5000
        val header = ByteArray(MicProtocol.HEADER_BYTES)
        DataInputStream(socket.inputStream).readFully(header)
        val format = MicProtocol.format(header) ?: run {
            log("microphone: guest sent a bad header")
            runCatching { socket.close() }
            return
        }
        if (!permitted()) {
            log("microphone: host input refused; guest keeps recording silence")
            runCatching { socket.close() }
            return
        }
        if (clients.size >= MAX_CLIENTS) {
            log("microphone: too many guest streams")
            runCatching { socket.close() }
            return
        }
        val client = Client(format, socket)
        val output = socket.outputStream
        val writer = Thread({
            try {
                while (true) {
                    val chunk = client.queue.take()
                    if (chunk === STOP) break
                    output.write(chunk)
                    output.flush()
                }
            } catch (_: Throwable) {
                
            } finally {
                drop(client, "stream ended")
                runCatching { socket.close() }
            }
        }, "microphone-writer")
        writer.isDaemon = true
        clients.add(client)
        log("microphone: guest attached, ${format.rate} Hz ${if (format.channels == 2) "stereo" else "mono"}")
        startPump()
        writer.start()
    }

    
    private fun startPump() {
        synchronized(lock) {
            if (pump != null || clients.isEmpty()) return
            val thread = Thread({ pump() }, "microphone-capture")
            thread.isDaemon = true
            pump = thread
            thread.start()
        }
    }

    private fun pump() {
        var record: AudioRecord? = null
        try {
            val minBytes = AudioRecord.getMinBufferSize(captureRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, captureRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes, BLOCK_FRAMES * 2 * 4))
            record = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                log("microphone: AudioRecord is not available")
                return
            }
            r.startRecording()
            val bytes = ByteArray(BLOCK_FRAMES * 2)
            val samples = ShortArray(BLOCK_FRAMES)
            while (!Thread.currentThread().isInterrupted) {
                if (clients.isEmpty()) break
                val n = r.read(bytes, 0, bytes.size)
                if (n <= 0) continue
                var k = 0
                while (k + 1 < n) {
                    samples[k / 2] = (bytes[k].toInt() and 0xff or (bytes[k + 1].toInt() shl 8)).toShort()
                    k += 2
                }
                for (client in clients) {
                    val pcm = client.resampler.process(samples, n / 2)
                    if (pcm.isNotEmpty() && !client.queue.offer(pcm)) client.queue.poll()
                }
            }
        } catch (e: Throwable) {
            if (active) log("microphone: capture failed: ${e.message}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            synchronized(lock) { pump = null }
            
            if (clients.isNotEmpty() && active && foreground) startPump()
        }
    }

    private companion object {
        const val MAX_CLIENTS = 4
        const val QUEUE_CHUNKS = 16              
        const val BLOCK_FRAMES = 960             
        val STOP = ByteArray(0)                  
    }
}
