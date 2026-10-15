package com.metrolist.desktop

import kotlin.test.*
import java.nio.file.Files

class DesktopTest {
    private val a = Track("abcdefghijk", "First", "Artist")
    private val b = Track("lmnopqrstuv", "Second", "Artist")

    @Test fun `line lyrics support fractional seconds multiple stamps and offset`() {
        val lines = parseLyrics("[offset:+125]\n[00:01.5][00:03.050]Original fixture\n[00:05.01]Next fixture")
        assertEquals(listOf(1625L, 3175L, 5135L), lines.map { it.timeMs })
        assertEquals(listOf("Original fixture", "Original fixture", "Next fixture"), lines.map { it.text })
        assertEquals(1, activeLyric(lines, 4000))
        assertEquals(-1, activeLyric(lines, 0))
    }

    @Test fun `plain lyrics stay plain and word stamps do not enter displayed line`() {
        assertTrue(parseLyrics("Original plain fixture").isEmpty())
        assertEquals("First second", parseLyrics("[00:01.00]<00:01.00>First <00:01.50>second").single().text)
    }

    @Test fun `personal mix uses liked songs then recent history without search text`() {
        assertEquals(a, chooseMixSeed(LibraryData(tracks = listOf(a, b), likes = listOf(a.id)), emptyList()))
        assertEquals(b, chooseMixSeed(LibraryData(tracks = listOf(a, b), history = listOf(b.id, a.id)), emptyList()))
        assertEquals(a, chooseMixSeed(LibraryData(), listOf(a)))
        assertNull(chooseMixSeed(LibraryData(), emptyList()))
    }

    @Test fun `library atomically round trips and merges without dropping current tracks`() {
        val directory = Files.createTempDirectory("metrolist-store-test")
        val file = directory.resolve("library.json")
        val store = LibraryStore(file)
        try {
            val first = LibraryData(tracks = listOf(a), likes = listOf(a.id), playlists = listOf(SavedPlaylist(name = "Fixture", tracks = listOf(a.id))))
            store.save(first)
            assertEquals(first, store.load())
            val merged = store.merge(first, LibraryData(tracks = listOf(b), likes = listOf(b.id)))
            store.save(merged)
            assertEquals(listOf(a.id, b.id), store.load().tracks.map { it.id })
            assertEquals(2, store.load().likes.size)
            assertEquals(1, store.load().playlists.size)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `corrupt library is preserved and cannot be silently overwritten`() {
        val directory = Files.createTempDirectory("metrolist-corrupt-test")
        val file = directory.resolve("library.json")
        try {
            Files.writeString(file, "not valid JSON")
            val store = LibraryStore(file)
            store.load()
            assertNotNull(store.loadError)
            assertFailsWith<IllegalStateException> { store.save(LibraryData()) }
            assertEquals("not valid JSON", Files.readString(file))
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }

    @Test fun `import rejects malformed records and network local paths`() {
        assertFails { LibraryStore.validate(LibraryData(tracks = listOf(a.copy(id = "invalid")))) }
        assertFails { LibraryStore.validate(LibraryData(tracks = listOf(a.copy(id = "local:bad", localPath = "https://example.org/track.mp3")))) }
        assertFails { LibraryStore.validate(LibraryData(tracks = listOf(a.copy(id = "local:bad", localPath = "\\\\server\\share\\track.mp3")))) }
        assertFails { LibraryStore.validate(LibraryData(volume = Float.NaN)) }
    }

    @Test fun `dangling playlist and like IDs are removed on import`() {
        val checked = LibraryStore.validate(LibraryData(tracks = listOf(a, a), likes = listOf(a.id, b.id), playlists = listOf(SavedPlaylist(name = "Fixture", tracks = listOf(b.id, a.id)))))
        assertEquals(listOf(a), checked.tracks)
        assertEquals(listOf(a.id), checked.likes)
        assertEquals(listOf(a.id), checked.playlists.single().tracks)
    }

    @Test fun `media accepts only secure upstream hosts`() {
        assertTrue(allowedMediaUrl("https://r1.googlevideo.com/videoplayback?test=1"))
        assertFalse(allowedMediaUrl("http://r1.googlevideo.com/videoplayback"))
        assertFalse(allowedMediaUrl("https://googlevideo.com.attacker.test/x"))
        assertFalse(allowedMediaUrl("https://user:password@r1.googlevideo.com/x"))
        assertFalse(allowedMediaUrl("file:///C:/secret.txt"))
    }

    @Test fun `byte range preserves inclusive bounds and rejects multiple or oversized starts`() {
        assertEquals(ByteRange(0, 99), mediaRange(null, 100))
        assertEquals(ByteRange(5, 99), mediaRange("bytes=5-", 100))
        assertEquals(ByteRange(5, 10), mediaRange("bytes=5-10", 100))
        assertEquals(ByteRange(5, 99), mediaRange("bytes=5-1000", 100))
        assertFails { mediaRange("bytes=100-", 100) }
        assertFails { mediaRange("bytes=1-5,10-20", 100) }
        assertFails { mediaRange("bytes=7-4", 100) }
    }

    @Test fun `PCM volume maintains sign and does not clip`() {
        val pcm = byteArrayOf(0, 64, 0, -64)
        scalePcm(pcm, pcm.size, 0.5f)
        assertContentEquals(byteArrayOf(0, 32, 0, -32), pcm)
        scalePcm(pcm, pcm.size, 0f)
        assertTrue(pcm.all { it == 0.toByte() })
        assertFails { scalePcm(pcm, 3, 1f) }
    }

    @Test fun `English resources are packaged and XML is valid`() {
        assertEquals("Metrolist Desktop", s("desktop_title"))
        assertEquals("Play my mix", s("desktop_mix"))
        assertEquals("1:05", clock(65.0))
        assertEquals("0:00", clock(Double.NaN))
    }
}
