package com.metrolist.desktop

import com.metrolist.innertubex.extraction.ExtractedStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import com.sun.net.httpserver.HttpServer

fun allowedMediaUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
        (uri.host == "googlevideo.com" || uri.host.endsWith(".googlevideo.com") || uri.host == "youtube.com" || uri.host.endsWith(".youtube.com"))
}.getOrDefault(false)

data class ByteRange(val first: Long, val last: Long)

fun mediaRange(header: String?, length: Long): ByteRange {
    require(length > 0)
    if (header == null) return ByteRange(0, length - 1)
    val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(header) ?: throw IllegalArgumentException("Unsupported byte range")
    val start = match.groupValues[1].toLong()
    val end = match.groupValues[2].toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
    require(start >= 0 && start < length && end >= start)
    return ByteRange(start, end)
}

// Android's bounded-range data source is replaced with a private loopback source for FFmpeg's decoder.
class BoundedMediaSource(private val stream: ExtractedStream) : Closeable {
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val key = UUID.randomUUID().toString()
    val url: String get() = "http://127.0.0.1:${server.address.port}/$key"

    init {
        require(allowedMediaUrl(stream.audioUrl))
        requireNotNull(stream.contentLengthBytes).also { require(it > 0) }
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (exchange.requestURI.path != "/$key" || exchange.requestMethod !in listOf("GET", "HEAD")) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val length = requireNotNull(stream.contentLengthBytes)
                    val range = try { mediaRange(exchange.requestHeaders.getFirst("Range"), length) } catch (_: Exception) {
                        exchange.responseHeaders.set("Content-Range", "bytes */$length")
                        exchange.sendResponseHeaders(416, -1)
                        return@createContext
                    }
                    val partial = exchange.requestHeaders.containsKey("Range")
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    exchange.responseHeaders.set("Content-Type", stream.mimeType ?: "application/octet-stream")
                    exchange.responseHeaders.set("Content-Length", (range.last - range.first + 1).toString())
                    if (partial) exchange.responseHeaders.set("Content-Range", "bytes ${range.first}-${range.last}/$length")
                    exchange.sendResponseHeaders(if (partial) 206 else 200, if (exchange.requestMethod == "HEAD") -1 else range.last - range.first + 1)
                    if (exchange.requestMethod == "GET") {
                        exchange.responseBody.use { output ->
                            var position = range.first
                            val chunk = stream.rangeChunkSizeBytes.coerceIn(64 * 1024L, 1024 * 1024L)
                            while (!stopped.get() && position <= range.last) {
                                val end = minOf(position + chunk - 1, range.last)
                                val request = HttpRequest.newBuilder(URI(stream.audioUrl)).timeout(Duration.ofSeconds(30))
                                stream.headers.forEach { (name, value) ->
                                    if (name.lowercase() !in setOf("range", "host", "connection", "content-length")) request.header(name, value)
                                }
                                request.header("Range", "bytes=$position-$end")
                                val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                                response.body().use { input ->
                                    check(response.statusCode() == 206 || (position == 0L && response.statusCode() == 200))
                                    if (response.statusCode() == 206) {
                                        val contentRange = response.headers().firstValue("Content-Range").orElse("")
                                        check(contentRange.startsWith("bytes $position-"))
                                    }
                                    var remaining = end - position + 1
                                    val buffer = ByteArray(32768)
                                    while (!stopped.get() && remaining > 0) {
                                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        if (count < 0) throw EOFException()
                                        output.write(buffer, 0, count)
                                        remaining -= count
                                    }
                                }
                                position = end + 1
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Stopping/ seeking closes the decoder's connection. Signed media URLs must never enter logs.
            } finally { exchange.close() }
        }
        server.start()
    }

    override fun close() {
        stopped.set(true)
        server.stop(0)
        executor.shutdownNow()
        client.shutdownNow()
    }
}

fun scalePcm(buffer: ByteArray, count: Int, volume: Float) {
    require(count in 0..buffer.size && count % 2 == 0 && volume.isFinite() && volume in 0f..1f)
    for (index in 0 until count step 2) {
        val sample = ((buffer[index].toInt() and 255) or (buffer[index + 1].toInt() shl 8)).toShort()
        val scaled = (sample * volume).toInt().coerceIn(-32768, 32767)
        buffer[index] = scaled.toByte()
        buffer[index + 1] = (scaled shr 8).toByte()
    }
}

class AudioPlayer : Closeable {
    private val state = MutableStateFlow("stopped")
    val status = state.asStateFlow()
    private val generation = AtomicLong()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "metrolist-audio").apply { isDaemon = true } }
    @Volatile private var process: Process? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var proxy: BoundedMediaSource? = null
    @Volatile private var paused = false
    @Volatile private var startSeconds = 0.0
    @Volatile var volume = 0.7f
    @Volatile var speed = 1.0
    var onEnded: () -> Unit = {}
    var onStarted: () -> Unit = {}
    var onFailure: () -> Unit = {}

    val position: Double get() = startSeconds + (line?.let { runCatching { it.longFramePosition / 44100.0 * speed }.getOrDefault(0.0) } ?: 0.0)

    fun play(track: Track, stream: ExtractedStream?, offset: Double = 0.0) {
        stop()
        val token = generation.get()
        startSeconds = offset.coerceAtLeast(0.0)
        paused = false
        state.value = "loading"
        workers.submit {
            var ownedProcess: Process? = null
            var ownedLine: SourceDataLine? = null
            var ownedProxy: BoundedMediaSource? = null
            try {
                val input = track.localPath ?: run {
                    val resolved = requireNotNull(stream)
                    require(allowedMediaUrl(resolved.audioUrl))
                    if (resolved.contentLengthBytes != null && resolved.mimeType != "application/x-mpegURL") {
                        ownedProxy = BoundedMediaSource(resolved)
                        ownedProxy!!.url
                    } else {
                        check(!resolved.requireBoundedRange)
                        resolved.audioUrl
                    }
                }
                if (token != generation.get()) return@submit
                val bundled = System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "ffmpeg.exe") }
                val decoder = bundled?.takeIf { it.isFile }?.absolutePath ?: "C:\\ProgramData\\chocolatey\\lib\\ffmpeg\\tools\\ffmpeg\\bin\\ffmpeg.exe"
                require(java.io.File(decoder).isFile)
                val command = mutableListOf(decoder, "-nostdin", "-hide_banner", "-loglevel", "quiet", "-threads", "1")
                if (ownedProxy == null && stream != null && stream.headers.isNotEmpty()) {
                    command += listOf("-headers", stream.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
                }
                command += listOf("-ss", startSeconds.toString(), "-i", input, "-vn", "-sn", "-dn")
                if (speed != 1.0) command += listOf("-af", "atempo=${speed.coerceIn(0.5, 2.0)}")
                command += listOf("-f", "s16le", "-ac", "2", "-ar", "44100", "pipe:1")
                ownedProcess = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                ownedLine = AudioSystem.getSourceDataLine(AudioFormat(44100f, 16, 2, true, false))
                ownedLine!!.open(AudioFormat(44100f, 16, 2, true, false), 44100)
                synchronized(this) {
                    if (token != generation.get()) return@submit
                    process = ownedProcess
                    line = ownedLine
                    proxy = ownedProxy
                }
                ownedLine!!.start()
                val buffer = ByteArray(8192)
                var started = false
                ownedProcess!!.inputStream.use { pcm ->
                    while (token == generation.get()) {
                        while (paused && token == generation.get()) Thread.sleep(30)
                        if (token != generation.get()) break
                        val count = pcm.readNBytes(buffer, 0, buffer.size)
                        if (count == 0) break
                        scalePcm(buffer, count - count % 2, volume.coerceIn(0f, 1f))
                        if (!started) {
                            started = true
                            state.value = "playing"
                            onStarted()
                        }
                        var written = 0
                        while (written < count && token == generation.get()) written += ownedLine!!.write(buffer, written, count - written)
                    }
                }
                if (token == generation.get()) {
                    ownedLine!!.drain()
                    check(started && ownedProcess!!.waitFor() == 0)
                    state.value = "stopped"
                    onEnded()
                }
            } catch (_: Exception) {
                if (token == generation.get()) {
                    state.value = "error"
                    onFailure()
                }
            } finally {
                ownedProcess?.destroyForcibly()
                runCatching { ownedLine?.close() }
                runCatching { ownedProxy?.close() }
                synchronized(this) {
                    if (process === ownedProcess) process = null
                    if (line === ownedLine) line = null
                    if (proxy === ownedProxy) proxy = null
                }
            }
        }
    }

    fun togglePause() {
        if (state.value !in listOf("playing", "paused")) return
        paused = !paused
        if (paused) line?.stop() else line?.start()
        state.value = if (paused) "paused" else "playing"
    }

    @Synchronized fun stop() {
        generation.incrementAndGet()
        process?.destroyForcibly()
        process = null
        runCatching { line?.stop(); line?.close() }
        line = null
        runCatching { proxy?.close() }
        proxy = null
        state.value = "stopped"
    }

    override fun close() { stop(); workers.shutdownNow() }
}
