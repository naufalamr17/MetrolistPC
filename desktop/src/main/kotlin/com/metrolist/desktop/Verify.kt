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
        val shelves = engine.home()
        check(shelves.isNotEmpty())
        println("LIVE_HOME shelves=${shelves.size} items=${shelves.sumOf { it.tracks.size }}")
        val mix = engine.mix(LibraryData(), shelves)
        check(mix.isNotEmpty())
        println("LIVE_NO_INPUT_RADIO tracks=${mix.size}")
        val lyrics = engine.lyrics(Track("yKNxeF4KMsY", "Yellow", "Coldplay", duration = 269))
        check(lyrics.text.isNotBlank())
        println("LIVE_LYRICS syncedLines=${lyrics.lines.size} characters=${lyrics.text.length}")
        val found = engine.search("Elektronomia Sky High")
        val song = found.first { it.kind == "song" }
        println("LIVE_SEARCH results=${found.size}")
        val stream = engine.stream(song.id)
        check(allowedMediaUrl(stream.audioUrl))
        println("LIVE_STREAM mime=${stream.mimeType} bytes=${stream.contentLengthBytes} client=${stream.clientName}")
        val started = CompletableFuture<Boolean>()
        player.onStarted = { started.complete(true) }
        player.onFailure = { started.complete(false) }
        player.volume = 0.15f
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
        withContext(Dispatchers.Swing) { nativeSnapshot(shelves, listOf(song) + mix.filter { it.id != song.id }, engine, player) }
    } catch (error: Exception) {
        failed = true
        println("LIVE_FAILED type=${error.javaClass.simpleName}")
    } finally {
        player.close()
        withTimeoutOrNull(5000) { engine.close() }
    }
    if (failed) kotlin.system.exitProcess(1)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private suspend fun nativeSnapshot(shelves: List<Shelf>, queue: List<Track>, engine: MetrolistEngine, player: AudioPlayer) {
    val temporary = Files.createTempDirectory("metrolist-live-ui")
    val store = LibraryStore(temporary.resolve("library.json"))
    store.save(LibraryData(volume = 0.15f))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val state = DesktopState(scope, store, engine, player)
    state.home = shelves
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
