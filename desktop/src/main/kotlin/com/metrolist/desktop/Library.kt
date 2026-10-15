package com.metrolist.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val thumbnail: String = "",
    val duration: Int = 0,
    val kind: String = "song",
    val localPath: String? = null,
    val album: String? = null,
)

@Serializable
data class SavedPlaylist(val id: String = UUID.randomUUID().toString(), val name: String, val tracks: List<String> = emptyList())

@Serializable
data class LibraryData(
    val schema: Int = 1,
    val tracks: List<Track> = emptyList(),
    val likes: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val playlists: List<SavedPlaylist> = emptyList(),
    val volume: Float = 0.7f,
    val lightTheme: Boolean = false,
)

val libraryJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

class LibraryStore(val file: Path = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "MetrolistDesktop", "library.json")) {
    var loadError: Exception? = null
        private set

    fun load(): LibraryData = try {
        if (Files.exists(file)) {
            require(Files.size(file) <= 20_000_000)
            validate(libraryJson.decodeFromString<LibraryData>(Files.readString(file)))
        } else LibraryData()
    } catch (error: Exception) {
        loadError = error
        LibraryData()
    }

    fun save(data: LibraryData) {
        check(loadError == null) { "The saved library could not be read. Preserve the file before attempting recovery." }
        val checked = validate(data)
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "library-", ".tmp")
        try {
            Files.writeString(temporary, libraryJson.encodeToString(checked))
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    fun merge(current: LibraryData, imported: LibraryData): LibraryData {
        val checked = validate(imported)
        return validate(current.copy(
            tracks = (current.tracks + checked.tracks).distinctBy { it.id },
            likes = (current.likes + checked.likes).distinct(),
            history = (current.history + checked.history).distinct().take(500),
            playlists = (current.playlists + checked.playlists).distinctBy { it.id },
        ))
    }

    companion object {
        fun validate(data: LibraryData): LibraryData {
            require(data.schema == 1 && data.tracks.size <= 10000 && data.playlists.size <= 500)
            require(data.volume.isFinite() && data.volume in 0f..1f)
            data.tracks.forEach {
                require(it.title.length <= 2000 && it.artist.length <= 2000 && it.duration in 0..86400)
                require((it.localPath != null && it.id.startsWith("local:")) || Regex("[A-Za-z0-9_-]{11}").matches(it.id))
                require(it.kind == "song")
                it.localPath?.let { path ->
                    require(Regex("^[A-Za-z]:[\\\\/].+").matches(path))
                    require(java.io.File(path).extension.lowercase() in setOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma"))
                }
            }
            data.playlists.forEach { require(it.name.length in 1..200 && it.tracks.size <= 10000) }
            val tracks = data.tracks.distinctBy { it.id }
            val ids = tracks.mapTo(HashSet()) { it.id }
            return data.copy(
                tracks = tracks,
                likes = data.likes.distinct().filter { it in ids },
                history = data.history.distinct().filter { it in ids }.take(500),
                playlists = data.playlists.map { it.copy(tracks = it.tracks.distinct().filter { id -> id in ids }) },
            )
        }
    }
}

data class LyricLine(val timeMs: Long, val text: String)

fun parseLyrics(text: String): List<LyricLine> {
    val normalized = text.replace("\\n", "\n").replace("\\r", "")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(normalized)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    return normalized.lineSequence().flatMap { line ->
        val matches = stamp.findAll(line).toList()
        val content = line.replace(stamp, "").replace(Regex("<\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?>"), "").trim()
        matches.asSequence().map {
            val fraction = it.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            LyricLine((it.groupValues[1].toLong() * 60000 + it.groupValues[2].toLong() * 1000 + fraction + offset).coerceAtLeast(0), content)
        }
    }.sortedBy { it.timeMs }.toList()
}

fun activeLyric(lines: List<LyricLine>, timeMs: Long): Int = lines.indexOfLast { it.timeMs <= timeMs }

fun chooseMixSeed(data: LibraryData, available: List<Track>): Track? {
    val online = data.tracks.filter { it.localPath == null }
    return online.filter { it.id in data.likes.takeLast(20) }.randomOrNull()
        ?: data.history.firstNotNullOfOrNull { id -> online.find { it.id == id } }
        ?: available.filter { it.kind == "song" && it.localPath == null }.randomOrNull()
}
