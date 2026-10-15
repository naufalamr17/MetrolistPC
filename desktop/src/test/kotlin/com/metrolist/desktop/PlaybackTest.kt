package com.metrolist.desktop

import com.metrolist.innertubex.extraction.ExtractedStream
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.EventQueue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class PlaybackTest {
    @Test fun `dropped media range resumes at exact byte without duplicates`() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val starts = mutableListOf<Long>()
        var first = true
        copyResumableRange(output, ByteRange(0, 999), 400, { false }) { start, end ->
            starts += start
            val stop = if (first) { first = false; 150 } else end.toInt() + 1
            ByteArrayInputStream(bytes.copyOfRange(start.toInt(), stop))
        }
        assertContentEquals(bytes, output.toByteArray())
        assertEquals(listOf(0L, 150L, 550L, 950L), starts)
    }

    @Test fun `persistent media failures have a finite retry limit`() {
        var attempts = 0
        assertFailsWith<IOException> {
            copyResumableRange(ByteArrayOutputStream(), ByteRange(0, 9), 10, { false }) { _, _ -> attempts++; throw IOException("Fixture disconnect") }
        }
        assertEquals(3, attempts)
    }

    internal class FakePlayer : AudioPlayer() {
        val played = mutableListOf<String>()
        var offset = 0.0
        var pausedSeek = false
        override val position: Double get() = offset
        override fun play(track: Track, stream: ExtractedStream?, offset: Double, startPaused: Boolean) {
            played += track.id; this.offset = offset; pausedSeek = startPaused
            state.value = if (startPaused) "paused" else "playing"
        }
        override fun stop() { state.value = "stopped" }
        fun pause() { state.value = "paused" }
    }

    internal class FakeEngine : MetrolistEngine() {
        override suspend fun radio(id: String) = emptyList<Track>()
        override suspend fun lyrics(track: Track) = Lyrics("", emptyList())
        override suspend fun stream(id: String) = ExtractedStream(
            videoId = id, audioUrl = "https://r1.googlevideo.com/fixture", headers = emptyMap(), loudnessDb = null,
            expiresAt = null, contentLengthBytes = 1000, itag = 140, mimeType = "audio/mp4", codecs = null,
            bitrate = null, sampleRate = null, clientName = "fixture", profileId = "fixture", requireBoundedRange = true, rangeChunkSizeBytes = 1000,
        )
    }

    @Test fun `next previous repeat shuffle paused seek and queue clearing preserve selection`() {
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            val directory = Files.createTempDirectory("metrolist-controls")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing.immediate)
            val player = FakePlayer()
            val state = DesktopState(scope, LibraryStore(directory.resolve("library.json")), FakeEngine(), player)
            val tracks = (1..4).map { Track("local:$it", "Fixture $it", localPath = "C:/fixtures/$it.wav", duration = 30) }
            try {
                state.playList(tracks)
                val staleEnded = player.onEnded
                state.next(); assertEquals(tracks[1], state.now)
                staleEnded(); assertEquals(tracks[1], state.now)
                state.previous(); assertEquals(tracks[0], state.now)
                state.repeat = 2; state.next(automatic = true); assertEquals(tracks[0], state.now)
                state.next(); assertEquals(tracks[1], state.now)
                state.shuffle(); assertEquals(tracks[1], state.now)
                state.shuffle(); assertEquals(tracks, state.queue); assertEquals(1, state.index)
                player.pause(); state.seek(12.0); assertTrue(player.pausedSeek); assertEquals(12.0, player.offset)
                state.enqueue(tracks[3], next = true); assertEquals(tracks[3], state.queue[state.index + 1])
                state.clearQueue(); assertTrue(state.queue.isEmpty()); assertNull(state.now)
                staleEnded(); assertTrue(state.queue.isEmpty())
            } catch (error: Throwable) { failure = error }
            finally { state.close(); Files.deleteIfExists(directory) }
        }
        failure?.let { throw it }
    }

    @Test fun `home pagination merges shelves and removes duplicate tracks`() {
        val a = Track("abcdefghijk", "First")
        val b = Track("lmnopqrstuv", "Second")
        val merged = mergeShelves(listOf(Shelf("Home", listOf(a))), listOf(Shelf("Home", listOf(a, b)), Shelf("New", listOf(b))))
        assertEquals(listOf(a, b), merged.first().tracks)
        assertEquals(2, merged.size)
    }

    @Test fun `home quick picks load real mix results without search and remain bounded`() {
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            val directory = Files.createTempDirectory("metrolist-quick-picks")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing.immediate)
            val tracks = (1..30).map { Track("fixture:$it", "Track $it") }
            var mixCalls = 0
            val engine = object : MetrolistEngine() {
                override suspend fun homeFeed(continuation: String?, params: String?) = HomeFeed(listOf(Shelf("From provider", tracks)), emptyList(), null)
                override suspend fun discover() = Discovery(emptyList(), emptyList())
                override suspend fun mix(data: LibraryData, shelves: List<Shelf>): List<Track> { mixCalls++; assertEquals(tracks, shelves.first().tracks); return tracks + tracks }
            }
            val state = DesktopState(scope, LibraryStore(directory.resolve("library.json")), engine, FakePlayer())
            try {
                state.refreshHome()
                assertEquals(1, mixCalls)
                assertEquals(tracks.take(18), state.quickPicks)
                assertFalse(state.quickPicksBusy)
                assertEquals("", state.query)
            } catch (error: Throwable) { failure = error }
            finally { state.close(); Files.deleteIfExists(directory) }
        }
        failure?.let { throw it }
    }

    @Test fun `stream recovery resumes twice and stale failure cannot restart a skipped track`() {
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            val directory = Files.createTempDirectory("metrolist-recovery")
            val player = FakePlayer()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing.immediate)
            val state = DesktopState(scope, LibraryStore(directory.resolve("library.json")), FakeEngine(), player)
            val a = Track("abcdefghijk", "First", duration = 120)
            val b = Track("lmnopqrstuv", "Second", duration = 120)
            try {
                state.playList(listOf(a, b))
                val stale = player.onFailure
                player.offset = 23.0
                player.onFailure(); assertEquals(23.0, player.offset); assertEquals(2, player.played.size)
                player.onFailure(); assertEquals(3, player.played.size)
                player.onFailure(); assertEquals(3, player.played.size); assertEquals("desktop_playback_error", state.error)
                state.next(); assertEquals(b, state.now)
                stale(); assertEquals(b, state.now); assertEquals(4, player.played.size)
            } catch (error: Throwable) { failure = error }
            finally { state.close(); Files.deleteIfExists(directory) }
        }
        failure?.let { throw it }
    }
}
