package com.metrolist.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.EventQueue
import java.nio.file.Files
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

class ControlUiTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun `proper playback buttons are labelled clickable and fit wide and narrow windows`() {
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            val directory = Files.createTempDirectory("metrolist-button-ui")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing.immediate)
            val state = DesktopState(scope, LibraryStore(directory.resolve("library.json")), PlaybackTest.FakeEngine(), PlaybackTest.FakePlayer())
            val tracks = listOf(Track("local:a", "First", localPath = "C:/fixture/a.wav", duration = 120), Track("local:b", "Second", localPath = "C:/fixture/b.wav", duration = 120))
            state.queue = tracks; state.index = 0
            state.quickPicks = tracks
            try {
                for (width in listOf(1380, 1024)) {
                    val scene = ImageComposeScene(width, 900, Density(1f), coroutineContext = Dispatchers.Swing) { DesktopApp(state) }
                    try {
                        fun render() { scene.render().close() }
                        fun nodes(): List<SemanticsNode> {
                            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                            return scene.semanticsOwners.flatMap { walk(it.rootSemanticsNode) }
                        }
                        fun button(label: String): SemanticsNode = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.getOrNull(SemanticsActions.OnClick) != null }
                        fun click(label: String) { assertTrue(button(label).config[SemanticsActions.OnClick].action!!.invoke()); render() }
                        render(); render()
                        assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == s("desktop_quick_picks") } == true })
                        assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == s("desktop_subtitle") } == true }, "The old hero banner must not replace the mobile-style Home sections")
                        val brand = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Metrolist" } == true }
                        assertTrue(brand.boundsInRoot.height <= 36, "App name must not wrap at narrow widths")
                        val next = button(s("desktop_next"))
                        assertTrue(next.boundsInRoot.width >= 36f)
                        assertTrue(next.boundsInRoot.left >= 0 && next.boundsInRoot.right <= width)
                        click(s("desktop_next")); assertEquals(1, state.index)
                        click(s("desktop_pause")); assertEquals("paused", state.player.status.value)
                        click(s("desktop_play")); assertEquals("playing", state.player.status.value)
                        click(s("desktop_previous")); assertEquals(0, state.index)
                        click(s("desktop_repeat_off")); assertEquals(1, state.repeat)
                        click(s("desktop_repeat_all")); assertEquals(2, state.repeat)
                        click(s("desktop_repeat_one")); assertEquals(0, state.repeat)
                        click(s("desktop_mute")); assertEquals(0f, state.volume)
                        click(s("desktop_unmute")); assertTrue(state.volume > 0)
                        for (label in listOf("desktop_previous", "desktop_next", "desktop_shuffle", "desktop_repeat_off", "desktop_mute", "desktop_toggle_panel")) {
                            val bounds = button(s(label)).boundsInRoot
                            assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.bottom <= 900, "Control outside viewport: $label")
                        }
                        val image = scene.render()
                        try { File(System.getProperty("metrolist.test.output"), "Metrolist-Controls-$width.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes) }
                        finally { image.close() }
                    } finally { scene.close() }
                }
            } catch (error: Throwable) { failure = error }
            finally { state.close(); Files.deleteIfExists(directory.resolve("library.json")); Files.deleteIfExists(directory) }
        }
        failure?.let { throw it }
    }
}
