package com.metrolist.desktop

import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.swing.Swing
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat

fun main() = runVerification()

fun runVerification() = runBlocking {
    val engine = MetrolistEngine()
    val player = AudioPlayer()
    var failed = false
    try {
        val feed = engine.homeFeed()
        val shelves = feed.shelves
        check(shelves.isNotEmpty())
        println("LIVE_HOME shelves=${shelves.size} items=${shelves.sumOf { it.tracks.size }}")
        val mix = engine.mix(LibraryData(), shelves)
        check(mix.isNotEmpty())
        println("LIVE_NO_INPUT_RADIO tracks=${mix.size}")
        val lyrics = runCatching { engine.lyrics(Track("yKNxeF4KMsY", "Yellow", "Coldplay", duration = 269)) }.getOrNull()
        println("LIVE_LYRICS available=${!lyrics?.text.isNullOrBlank()} syncedLines=${lyrics?.lines?.size ?: 0} characters=${lyrics?.text?.length ?: 0}")
        val firstSearch = engine.searchPage("Elektronomia Sky High")
        val found = firstSearch.tracks
        val song = found.first { it.kind == "song" }
        println("LIVE_SEARCH results=${found.size}")
        firstSearch.continuation?.let { continuation ->
            val extra = engine.searchPage("Elektronomia Sky High", continuation = continuation)
            check(extra.tracks.isNotEmpty())
            println("LIVE_SEARCH_MORE results=${extra.tracks.size}")
        }
        val stream = engine.stream(song.id)
        check(allowedMediaUrl(stream.audioUrl))
        check(!stream.mimeType.orEmpty().contains("mpegurl", ignoreCase = true))
        println("LIVE_STREAM mime=${stream.mimeType} bytes=${stream.contentLengthBytes} client=${stream.clientName} rangeChunk=${stream.rangeChunkSizeBytes} bounded=${stream.requireBoundedRange}")
        val started = CompletableFuture<Boolean>()
        player.onStarted = { started.complete(true) }
        player.onFailure = { started.complete(false) }
        player.volume = 0.08f
        player.play(song, stream)
        check(withContext(Dispatchers.IO) { started.get(45, TimeUnit.SECONDS) })
        delay(2500)
        check(player.position > 1)
        println("LIVE_AUDIO position=${player.position.toInt()} status=${player.status.value}")
        player.togglePause()
        check(player.status.value == "paused")
        player.togglePause()
        check(player.status.value == "playing")
        println("LIVE_PLAY_PAUSE passed")
        val sustain = System.getenv("METROLIST_VERIFY_SECONDS")?.toIntOrNull()?.coerceIn(10, 120) ?: 45
        repeat(sustain / 5) {
            delay(5000)
            check(player.status.value == "playing")
            check(player.position > (it + 1) * 4)
            println("LIVE_SUSTAIN elapsed=${(it + 1) * 5}s position=${player.position.toInt()} status=${player.status.value}")
        }
        val seekTo = minOf(60.0, song.duration / 2.0).coerceAtLeast(10.0)
        player.play(song, stream, seekTo, startPaused = true)
        withTimeout(45000) { while (player.status.value == "loading") delay(100) }
        check(player.status.value == "paused")
        delay(1500)
        check(player.position <= seekTo + 0.2)
        player.togglePause()
        withTimeout(45000) { while (player.position <= seekTo + 1 && player.status.value != "error") delay(100) }
        check(player.status.value == "playing" && player.position > seekTo + 1)
        println("LIVE_PAUSED_SEEK position=${player.position.toInt()}")
        val following = found.first { it.kind == "song" && it.id != song.id }
        player.play(following, engine.stream(following.id))
        withTimeout(45000) { while (player.position <= 1 && player.status.value != "error") delay(100) }
        check(player.status.value == "playing" && player.position > 1)
        println("LIVE_NEXT_TRACK position=${player.position.toInt()} status=${player.status.value}")
        val discovery = runCatching { engine.discover() }.getOrNull()
        println("LIVE_DISCOVERY shelves=${discovery?.shelves?.size ?: 0} moods=${discovery?.moods?.size ?: 0}")
        withContext(Dispatchers.Swing) { nativeSnapshot(mergeShelves(shelves, discovery?.shelves.orEmpty()), listOf(following) + mix.filter { it.id != following.id }, engine, player, discovery?.moods.orEmpty()) }
    } catch (error: Exception) {
        failed = true
        val location = error.stackTrace.firstOrNull { it.className.startsWith("com.metrolist.desktop") }
        println("LIVE_FAILED type=${error.javaClass.simpleName} at=${location?.fileName}:${location?.lineNumber}")
    } finally {
        player.close()
        withTimeoutOrNull(5000) { engine.close() }
    }
    if (failed) kotlin.system.exitProcess(1)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private suspend fun nativeSnapshot(shelves: List<Shelf>, queue: List<Track>, engine: MetrolistEngine, player: AudioPlayer, moods: List<MoodChoice>) {
    val temporary = Files.createTempDirectory("metrolist-live-ui")
    val store = LibraryStore(temporary.resolve("library.json"))
    store.save(LibraryData(volume = 0.15f))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val state = DesktopState(scope, store, engine, player)
    state.home = shelves
    state.quickPicks = queue.take(18)
    state.moods = moods
    state.queue = queue
    state.index = 0
    val scene = ImageComposeScene(1380, 900, Density(1f), coroutineContext = Dispatchers.Swing) { DesktopApp(state) }
    try {
        scene.render().close()
        delay(2500)
        state.position = player.position
        val image = scene.render()
        try {
            val output = File(System.getProperty("metrolist.live.output")).apply { mkdirs() }
            File(output, "Metrolist-Native-Home.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            println("LIVE_NATIVE_UI rendered=1380x900")
        } finally { image.close() }
    } finally {
        scene.close()
        scope.cancel()
        Files.deleteIfExists(temporary.resolve("library.json"))
        Files.deleteIfExists(temporary)
    }
}
