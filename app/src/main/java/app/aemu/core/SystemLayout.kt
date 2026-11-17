
package app.aemu.core

import app.aemu.R
import app.aemu.importer.Ext4Reader
import app.aemu.importer.RandomSource
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream


class SystemLayout private constructor(
    val blockSize: Int,
    val blockCount: Long,
    val files: Map<String, LFile>,
    
    val links: Map<String, String>,
    
    val dirs: Map<String, Int>,
    
    val prefix: String = DEFAULT_PREFIX,
) {
    
    class LFile(val path: String, val size: Long, val sha1: String, val perm: Int, val extents: LongArray)

    
    class Capture(private val fs: Ext4Reader, private val src: RandomSource, private val prefix: String = DEFAULT_PREFIX) {
        private val files = ArrayList<LFile>()
        private val links = ArrayList<Pair<String, String>>()
        private val dirs = ArrayList<Pair<String, Int>>()
        private val seenInodes = HashSet<Int>()

        fun dir(path: String, perm: Int) { dirs.add(path to perm) }
        fun link(path: String, target: String) { links.add(path to target) }

        
        fun file(path: String, node: Ext4Reader.Node, sha1: ByteArray) {
            val bs = fs.blockSize
            var ext = LongArray(0)
            
            if (seenInodes.add(node.ino) && node.flags and SystemLayout.INLINE_DATA == 0) {
                val nb = (node.size + bs - 1) / bs
                val out = ArrayList<Long>()
                for (e in fs.extents(node).sortedBy { it[0] }) {
                    val n = minOf(e[2], nb - e[0])
                    if (n <= 0) continue
                    out.add(e[0]); out.add(e[1]); out.add(n)
                }
                ext = out.toLongArray()
            }
            files.add(LFile(path, node.size, SystemLayout.hex(sha1), node.perm, ext))
        }

        
        fun finish(out: File, maxRaw: Long = 96L shl 20) {
            val bs = fs.blockSize
            val total = (src.size + bs - 1) / bs
            if (total <= 0 || total > Int.MAX_VALUE - 64) throw IOException("image size not supported")
            val claimed = BitSet(total.toInt())
            val one = ByteBuffer.allocate(bs)
            for (f in files) {
                val nb = (f.size + bs - 1) / bs
                var k = 0
                while (k < f.extents.size) {
                    val l = f.extents[k]; val p = f.extents[k + 1]; val n = f.extents[k + 2]
                    k += 3
                    if (p < 0 || p + n > total) throw IOException("a file extent lies outside the image")
                    claimed.set(p.toInt(), (p + n).toInt())
                    
                    
                    if (l + n == nb && f.size % bs != 0L) {
                        val last = p + n - 1
                        one.clear()
                        src.read(last * bs, one)
                        var dirty = false
                        for (x in (f.size % bs).toInt() until bs) if (one.get(x) != 0.toByte()) { dirty = true; break }
                        if (dirty) claimed.clear(last.toInt())
                    }
                }
            }

            val tmp = File(out.path + ".tmp")
            val def = Deflater(Deflater.BEST_SPEED)
            try {
                DataOutputStream(DeflaterOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16), def)).use { o ->
                    o.writeUTF(SystemLayout.MAGIC)
                    o.writeUTF(prefix)
                    o.writeInt(bs)
                    o.writeLong(total)
                    o.writeInt(files.size)
                    for (f in files) {
                        o.writeUTF(f.path)
                        o.writeLong(f.size)
                        o.write(SystemLayout.unhex(f.sha1))
                        o.writeInt(f.perm)
                        o.writeInt(f.extents.size / 3)
                        for (v in f.extents) o.writeLong(v)
                    }
                    o.writeInt(links.size)
                    for ((p, t) in links) { o.writeUTF(p); o.writeUTF(t) }
                    o.writeInt(dirs.size)
                    for ((p, m) in dirs) { o.writeUTF(p); o.writeInt(m) }

                    
                    val chunk = 256
                    val buf = ByteBuffer.allocate(chunk * bs)
                    val arr = buf.array()
                    val run = ByteArrayOutputStream()
                    var runStart = -1L
                    var runLen = 0
                    var raw = 0L
                    fun flush() {
                        if (runLen > 0) { o.writeLong(runStart); o.writeInt(runLen); run.writeTo(o) }
                        run.reset(); runStart = -1; runLen = 0
                    }
                    var b = claimed.nextClearBit(0).toLong()
                    while (b < total) {
                        var e = claimed.nextSetBit(b.toInt()).toLong()
                        if (e < 0 || e > total) e = total
                        var c = b
                        while (c < e) {
                            val n = minOf(chunk.toLong(), e - c).toInt()
                            buf.clear(); buf.limit(n * bs)
                            src.read(c * bs, buf)
                            for (i in 0 until n) {
                                val off = i * bs
                                if (isZero(arr, off, bs)) continue
                                val blk = c + i
                                if (!(runLen > 0 && runStart + runLen == blk && runLen < 1024)) { flush(); runStart = blk }
                                run.write(arr, off, bs)
                                runLen++
                                raw += bs
                                if (raw > maxRaw) throw IOException("the image holds more than ${maxRaw shr 20} MB that is not file data")
                            }
                            c += n
                        }
                        b = claimed.nextClearBit(e.toInt()).toLong()
                    }
                    flush()
                    o.writeLong(-1L)
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            } finally { def.end() }
            out.delete()
            if (!tmp.renameTo(out)) { tmp.delete(); throw IOException("cannot save the system layout") }
        }
    }

    companion object {
        const val NAME = "system.layout"
        
        internal const val MAGIC = "AEL3"
        internal const val MAGIC_V2 = "AEL2"
        internal const val DEFAULT_PREFIX = "system/"
        internal const val INLINE_DATA = 0x10000000

        
        val PARTITIONS = listOf("system", "vendor", "product", "system_ext", "odm")
        val ALL_NAMES: List<String> get() = PARTITIONS.map { nameOf(it) }

        fun nameOf(partition: String) = if (partition == "system") NAME else "$partition.layout"
        fun file(vmDir: File, partition: String = "system") = File(vmDir, nameOf(partition))

        
        fun partitionOf(mount: String): String? = when (mount) {
            "system", "vendor", "odm" -> mount
            "system/product" -> "product"
            "system/system_ext" -> "system_ext"
            else -> null
        }

        
        fun prefixOf(mount: String, asRoot: Boolean) = if (asRoot) "" else "$mount/"

        
        fun load(f: File): SystemLayout =
            DataInputStream(InflaterInputStream(FileInputStream(f).buffered(1 shl 16))).use { readTables(it) }

        private fun readTables(i: DataInputStream): SystemLayout {
            val prefix = when (i.readUTF()) {
                MAGIC_V2 -> DEFAULT_PREFIX
                MAGIC -> i.readUTF()
                else -> throw IOException("not a system layout")
            }
            if (prefix.isNotEmpty() && (!prefix.endsWith("/") || prefix.startsWith("/") || prefix.split('/').any { it == ".." || it == "." }))
                throw IOException("corrupt system layout")
            val bs = i.readInt()
            val bc = i.readLong()
            if (bs < 1024 || bs > 65536 || bc <= 0) throw IOException("corrupt system layout")
            val nf = i.readInt()
            if (nf < 0 || nf > 10_000_000) throw IOException("corrupt system layout")
            val files = LinkedHashMap<String, LFile>(nf * 2)
            repeat(nf) {
                val path = i.readUTF()
                val size = i.readLong()
                val sha = ByteArray(20).also { i.readFully(it) }
                val perm = i.readInt()
                val ne = i.readInt()
                if (ne < 0 || ne > 100_000_000) throw IOException("corrupt system layout")
                val ext = LongArray(ne * 3)
                for (k in ext.indices) ext[k] = i.readLong()
                files[path] = LFile(path, size, hex(sha), perm, ext)
            }
            val nl = i.readInt()
            val links = LinkedHashMap<String, String>()
            repeat(nl) { val p = i.readUTF(); links[p] = i.readUTF() }
            val nd = i.readInt()
            val dirs = LinkedHashMap<String, Int>()
            repeat(nd) { val p = i.readUTF(); dirs[p] = i.readInt() }
            return SystemLayout(bs, bc, files, links, dirs, prefix)
        }

        
        fun rebuild(layout: File, out: File, content: (LFile) -> ByteArray?, progress: (Msg) -> Unit = {}): List<String> {
            val bad = ArrayList<String>()
            DataInputStream(InflaterInputStream(FileInputStream(layout).buffered(1 shl 16))).use { inp ->
                val l = readTables(inp)
                val bs = l.blockSize
                RandomAccessFile(out, "rw").use { raf ->
                    raf.setLength(l.blockCount * bs)
                    val chunk = ByteArray(1 shl 16)
                    while (true) {
                        val start = inp.readLong()
                        if (start < 0) break
                        val n = inp.readInt()
                        if (n <= 0 || start + n > l.blockCount) throw IOException("corrupt system layout")
                        raf.seek(start * bs)
                        var left = n.toLong() * bs
                        while (left > 0) {
                            val k = minOf(left, chunk.size.toLong()).toInt()
                            inp.readFully(chunk, 0, k)
                            raf.write(chunk, 0, k)
                            left -= k
                        }
                    }
                    var done = 0
                    val withData = l.files.values.filter { it.extents.isNotEmpty() }
                    for (f in withData) {
                        if (done++ % 64 == 0) progress(Msg("Rebuilding the stock system image ${done}/${withData.size}", R.string.ota_rebuilding_the_stock_system_image_2, done, withData.size))
                        val data = content(f)
                        if (data == null || data.size.toLong() != f.size) { bad.add(f.path); continue }
                        var k = 0
                        while (k < f.extents.size) {
                            val lb = f.extents[k]; val pb = f.extents[k + 1]; val n = f.extents[k + 2]
                            k += 3
                            val off = lb * bs
                            val len = minOf(n * bs, f.size - off)
                            if (len <= 0) continue
                            raf.seek(pb * bs)
                            raf.write(data, off.toInt(), len.toInt())
                        }
                    }
                }
            }
            return bad
        }

        private fun isZero(a: ByteArray, off: Int, len: Int): Boolean {
            for (i in off until off + len) if (a[i] != 0.toByte()) return false
            return true
        }

        private val HEX = "0123456789abcdef".toCharArray()
        internal fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) { sb.append(HEX[(x.toInt() shr 4) and 15]); sb.append(HEX[x.toInt() and 15]) }
            return sb.toString()
        }
        internal fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
