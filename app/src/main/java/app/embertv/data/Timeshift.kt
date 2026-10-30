package app.embertv.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pause and rewind for live TV. While a channel is on, this records it from the provider to the
 * device in small files, keeping the last [maxAgeMs] (or less, if space is short), and the player
 * plays from those files. That way pausing just stops the player while recording carries on, and
 * rewinding is reading from earlier in the files. It uses the same single provider connection as
 * watching normally does.
 */
class TimeshiftRecorder(
    private val url: String,
    private val userAgent: String,
    private val dir: File,
    private val maxAgeMs: Long,
    private val maxBytes: Long,
) {
    private class Segment(val file: File, val start: Long, val startWall: Long) {
        @Volatile var size: Long = 0
    }

    private val lock = Any()
    private val segments = ArrayList<Segment>()
    // Once a second: when (wall clock) and where (byte offset) the recording was, for finding times.
    private val sampleWall = ArrayList<Long>()
    private val sampleOffset = ArrayList<Long>()

    /** Total bytes recorded: the live edge. */
    @Volatile var written: Long = 0
        private set
    @Volatile var stopped = false
        private set
    /** Set if the channel couldn't be recorded at all, so the player can play it directly instead. */
    @Volatile var failed: String? = null
        private set
    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null

    fun start(scope: CoroutineScope) {
        dir.deleteRecursively()
        dir.mkdirs()
        job = scope.launch(Dispatchers.IO) {
            val began = System.currentTimeMillis()
            var segment: Segment? = null
            var out: FileOutputStream? = null
            val buf = ByteArray(64 * 1024)
            while (isActive && !stopped) {
                var input: InputStream? = null
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    connection = c
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 12_000
                    c.readTimeout = 15_000
                    c.setRequestProperty("User-Agent", userAgent)
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    input = c.inputStream
                    var lastSample = 0L
                    while (isActive && !stopped) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val now = System.currentTimeMillis()
                        // A new file every 4 MB, so old parts can be thrown away.
                        val cur = segment
                        if (cur == null || cur.size >= SEGMENT_BYTES) {
                            out?.close()
                            val s = Segment(File(dir, "seg_${written}.ts"), written, now)
                            synchronized(lock) { segments.add(s) }
                            segment = s
                            out = FileOutputStream(s.file)
                        }
                        if (now - lastSample >= 1_000) {
                            synchronized(lock) {
                                sampleWall.add(now)
                                sampleOffset.add(written)
                            }
                            lastSample = now
                        }
                        out!!.write(buf, 0, n)
                        segment!!.size += n
                        written += n
                        prune(now)
                    }
                } catch (e: Exception) {
                    // Dropped or refused: try again below.
                } finally {
                    runCatching { input?.close() }
                    runCatching { connection?.disconnect() }
                }
                if (stopped) break
                if (written == 0L && System.currentTimeMillis() - began > 12_000) {
                    failed = "This channel can't be paused"
                    break
                }
                delay(1_000)
            }
            runCatching { out?.close() }
        }
    }

    private fun prune(now: Long) {
        synchronized(lock) {
            var total = segments.sumOf { it.size }
            while (segments.size > 2 && (now - segments[1].startWall > maxAgeMs || total > maxBytes)) {
                val old = segments.removeAt(0)
                total -= old.size
                old.file.delete()
                val keepFrom = segments[0].start
                while (sampleOffset.size > 1 && sampleOffset[0] < keepFrom) {
                    sampleOffset.removeAt(0)
                    sampleWall.removeAt(0)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        runCatching { connection?.disconnect() }
        synchronized(lock) {
            segments.clear()
            sampleWall.clear()
            sampleOffset.clear()
        }
        dir.deleteRecursively()
    }

    /** The earliest point still kept, as a byte offset. */
    fun oldestOffset(): Long = synchronized(lock) { segments.firstOrNull()?.start ?: 0L }

    /** The earliest time you can rewind to (wall clock, ms). */
    fun oldestWall(): Long = synchronized(lock) { sampleWall.firstOrNull() ?: System.currentTimeMillis() }

    /** Where in the recording a moment is: the last sample at or before [wall]. */
    fun offsetAt(wall: Long): Long = synchronized(lock) {
        if (sampleWall.isEmpty()) return 0L
        var lo = 0
        var hi = sampleWall.size - 1
        if (wall <= sampleWall[0]) return sampleOffset[0]
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sampleWall[mid] <= wall) lo = mid else hi = mid - 1
        }
        sampleOffset[lo]
    }

    /** When a byte offset was recorded (wall clock, ms). */
    fun wallAt(offset: Long): Long = synchronized(lock) {
        if (sampleOffset.isEmpty()) return System.currentTimeMillis()
        var i = sampleOffset.binarySearch(offset)
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        sampleWall[i]
    }

    /**
     * Reads recorded bytes at [offset]. Returns how many were read, 0 if that part hasn't arrived
     * yet, or throws if it's already been thrown away.
     */
    internal fun read(offset: Long, target: ByteArray, at: Int, length: Int, reader: Reader): Int {
        val seg = synchronized(lock) {
            if (segments.isEmpty()) return 0
            if (offset < segments[0].start) throw IOException("That part of the recording has gone")
            segments.lastOrNull { it.start <= offset } ?: return 0
        }
        val inSeg = offset - seg.start
        val available = seg.size - inSeg
        if (available <= 0) return 0
        val raf = reader.open(seg.file)
        raf.seek(inSeg)
        val n = raf.read(target, at, minOf(length.toLong(), available).toInt())
        return if (n < 0) 0 else n
    }

    /** Keeps one recording file open per player read, reopening when it moves to the next file. */
    internal class Reader {
        private var file: File? = null
        private var raf: RandomAccessFile? = null
        fun open(f: File): RandomAccessFile {
            val cur = raf
            if (cur != null && file == f) return cur
            close()
            return RandomAccessFile(f, "r").also {
                raf = it
                file = f
            }
        }
        fun close() {
            runCatching { raf?.close() }
            raf = null
            file = null
        }
    }

    companion object {
        private const val SEGMENT_BYTES = 4L * 1024 * 1024
        const val SCHEME = "timeshift"

        /** A player address that starts reading the recording at [offset]. */
        fun uriFor(offset: Long): Uri = Uri.parse("$SCHEME://live/$offset")
    }
}

/** Lets the player read a [TimeshiftRecorder]'s recording as if it were one endless file. */
class TimeshiftDataSource(private val recorder: TimeshiftRecorder) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private val reader = TimeshiftRecorder.Reader()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val start = dataSpec.uri.lastPathSegment?.toLongOrNull() ?: 0L
        position = start + dataSpec.position
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val n = recorder.read(position, buffer, offset, length, reader)
            if (n > 0) {
                position += n
                bytesTransferred(n)
                return n
            }
            if (recorder.stopped) return C.RESULT_END_OF_INPUT
            // Caught up with the live edge: wait for the next bit to arrive.
            try {
                Thread.sleep(30)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader.close()
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    class Factory(private val recorder: TimeshiftRecorder) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(recorder)
    }
}
