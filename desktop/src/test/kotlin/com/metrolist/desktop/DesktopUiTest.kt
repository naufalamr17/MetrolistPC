package com.metrolist.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopUiTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun `native desktop renders home library lyrics and settings without Android`() {
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            val temporary = Files.createTempDirectory("metrolist-ui-test")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
            val state = DesktopState(scope, LibraryStore(temporary.resolve("library.json")))
            val tracks = listOf(Track("abcdefghijk", "Original test track", "Fixture artist", duration = 180), Track("lmnopqrstuv", "Second original fixture", "Fixture artist", duration = 200))
            state.home = listOf(Shelf(s("desktop_snapshot_mode"), tracks))
            state.data = LibraryData(tracks = tracks, likes = listOf(tracks.first().id))
            val scene = ImageComposeScene(1380, 900, Density(1f), coroutineContext = Dispatchers.Swing) { DesktopApp(state) }
            try {
                val output = File(System.getProperty("metrolist.test.output")).apply { mkdirs() }
                fun render(name: String) {
                    val image = scene.render()
                    assertEquals(1380, image.width)
                    assertEquals(900, image.height)
                    File(output, name).writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
                    image.close()
                }
                fun nodes(): List<SemanticsNode> {
                    fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
                    return scene.semanticsOwners.flatMap { all(it.rootSemanticsNode) }
                }
                fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains(value) } == true }
                render("Metrolist-Native-Home-Test.png")
                assertTrue(hasText(s("desktop_mix")))
                assertTrue(hasText(s("desktop_snapshot_mode")))
                assertTrue(hasText(s("desktop_nothing_playing")))
                val library = nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == s("desktop_library") } == true && node.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(library.config[SemanticsActions.OnClick].action!!.invoke())
                render("Metrolist-Native-Library-Test.png")
                assertEquals("library", state.view)
                assertTrue(hasText(tracks.first().title))
                state.queue = tracks
                state.index = 0
                state.panel = "lyrics"
                state.lyrics = Lyrics("[00:01.00]Original first line\n[00:04.00]Original second line", listOf(LyricLine(1000, "Original first line"), LyricLine(4000, "Original second line")))
                state.position = 5.0
                render("Metrolist-Native-Lyrics-Test.png")
                assertTrue(hasText("Original second line"))
                state.navigate("settings")
                render("Metrolist-Native-Settings-Test.png")
                assertTrue(hasText(s("desktop_theme")))
                state.moveQueue(0, 1)
                assertEquals(1, state.index)
                state.removeQueue(0)
                assertEquals(0, state.index)
                assertEquals(tracks.first().id, state.now?.id)
            } catch (error: Throwable) { failure = error }
            finally {
                scene.close()
                state.close()
                Files.deleteIfExists(temporary.resolve("library.json"))
                Files.deleteIfExists(temporary)
            }
        }
        failure?.let { throw it }
    }
}
