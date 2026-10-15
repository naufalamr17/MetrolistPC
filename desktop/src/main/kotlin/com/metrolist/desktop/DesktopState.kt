package com.metrolist.desktop

import androidx.compose.runtime.*
import com.metrolist.innertubex.extraction.ExtractedStream
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class DesktopState(
    val scope: CoroutineScope,
    val store: LibraryStore = LibraryStore(),
    val engine: MetrolistEngine = MetrolistEngine(),
    val player: AudioPlayer = AudioPlayer(),
) {
    var data by mutableStateOf(store.load())
    var view by mutableStateOf("home")
    var panel by mutableStateOf("queue")
    var home by mutableStateOf<List<Shelf>>(emptyList())
    var results by mutableStateOf<List<Track>>(emptyList())
    var collectionTitle by mutableStateOf("")
    var query by mutableStateOf("")
    var searchType by mutableStateOf("song")
    var busy by mutableStateOf(false)
    var preparing by mutableStateOf(false)
    var error by mutableStateOf(if (store.loadError != null) "desktop_library_error" else "")
    var queue by mutableStateOf<List<Track>>(emptyList())
    var index by mutableStateOf(-1)
    val now: Track? get() = queue.getOrNull(index)
    var lyrics by mutableStateOf<Lyrics?>(null)
    var lyricsBusy by mutableStateOf(false)
    var related by mutableStateOf<List<Track>>(emptyList())
    var position by mutableStateOf(0.0)
    var lyricOffset by mutableStateOf(0.0f)
    var repeat by mutableStateOf(0)
    var sleepUntil by mutableStateOf(0L)
    var repeatShuffle by mutableStateOf(false)
    private var stream: ExtractedStream? = null
    private var playJob: Job? = null
    private var lyricJob: Job? = null
    private var relatedJob: Job? = null
    private var browseJob: Job? = null
    private var mixJob: Job? = null
    private var closed = false

    init { player.volume = data.volume }

    fun start() {
        refreshHome()
        scope.launch {
            while (isActive) {
                position = player.position
                if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) {
                    player.stop()
                    sleepUntil = 0
                }
                delay(150)
            }
        }
    }

    fun persist(value: LibraryData) {
        try {
            store.save(value)
            data = value
        } catch (_: Exception) { error = if (store.loadError != null) "desktop_library_error" else "desktop_save_error" }
    }

    fun navigate(target: String) {
        browseJob?.cancel()
        busy = false
        error = if (store.loadError != null) "desktop_library_error" else ""
        view = target
    }

    private fun browse(target: String, action: suspend () -> Unit) {
        navigate(target)
        browseJob = scope.launch {
            busy = true
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_network_error" }
            finally { busy = false }
        }
    }

    fun refreshHome() = browse("home") { home = engine.home() }
    fun search() {
        if (query.isBlank()) { navigate("search"); return }
        browse("search") { results = engine.search(query, searchType) }
    }

    fun open(item: Track) {
        if (item.kind == "song") { radio(item); return }
        browse("collection") {
            collectionTitle = item.title
            results = engine.collection(item)
        }
    }

    fun radio(track: Track) {
        playList(listOf(track))
        if (track.localPath != null) return
        relatedJob?.cancel()
        relatedJob = scope.launch {
            try {
                val songs = engine.radio(track.id).filter { it.id != track.id }
                if (now?.id == track.id && queue.size == 1) queue = listOf(track) + songs
                if (now?.id == track.id) related = songs
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Playback can continue even when radio is temporarily unavailable. */ }
        }
    }

    fun myMix() {
        mixJob?.cancel()
        mixJob = scope.launch {
            busy = true
            try {
                if (home.isEmpty()) home = engine.home()
                val tracks = engine.mix(data, home)
                if (tracks.isNotEmpty()) playList(tracks) else error = "desktop_empty"
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_network_error" }
            finally { busy = false }
        }
    }

    fun playList(tracks: List<Track>, start: Int = 0) {
        if (tracks.isEmpty()) return
        queue = tracks.filter { it.kind == "song" }.take(1000)
        if (queue.isNotEmpty()) playAt(start.coerceIn(queue.indices))
    }

    fun playAt(target: Int) {
        val track = queue.getOrNull(target) ?: return
        playJob?.cancel()
        lyricJob?.cancel()
        relatedJob?.cancel()
        player.stop()
        index = target
        position = 0.0
        stream = null
        preparing = true
        lyrics = null
        related = emptyList()
        error = ""
        player.onStarted = {
            scope.launch {
                if (now?.id == track.id) persist(data.copy(
                    tracks = (data.tracks + track).distinctBy { it.id },
                    history = (listOf(track.id) + data.history).distinct().take(500),
                ))
            }
        }
        player.onEnded = { scope.launch { if (now?.id == track.id) next(automatic = true) } }
        player.onFailure = { scope.launch { if (now?.id == track.id) error = "desktop_playback_error" } }
        playJob = scope.launch {
            try {
                val resolved = if (track.localPath == null) engine.stream(track.id) else null
                ensureActive()
                stream = resolved
                player.play(track, resolved)
                if (track.duration <= 0) {
                    val duration = resolved?.mediaMetadata?.durationSeconds?.toInt() ?: 0
                    if (duration > 0) queue = queue.mapIndexed { i, item -> if (i == index) item.copy(duration = duration) else item }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_playback_error" }
            finally { if (now?.id == track.id) preparing = false }
        }
        lyricJob = scope.launch {
            lyricsBusy = true
            try { lyrics = engine.lyrics(track) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { lyrics = Lyrics("", emptyList()) }
            finally { lyricsBusy = false }
        }
        if (track.localPath == null) relatedJob = scope.launch {
            try { related = engine.radio(track.id).filter { it.id != track.id } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { related = emptyList() }
        }
    }

    fun next(automatic: Boolean = false) {
        if (queue.isEmpty()) return
        if (automatic && repeat == 2) { playAt(index); return }
        if (index < queue.lastIndex) { playAt(index + 1); return }
        if (repeat == 1) { playAt(0); return }
        val seed = now?.takeIf { it.localPath == null } ?: return
        scope.launch {
            try {
                val ids = queue.mapTo(HashSet()) { it.id }
                val extra = engine.radio(seed.id).filter { it.id !in ids }
                if (extra.isNotEmpty() && now?.id == seed.id) {
                    val start = queue.size
                    queue = (queue + extra).takeLast(1000)
                    playAt(minOf(start, queue.lastIndex))
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_network_error" }
        }
    }

    fun previous() { if (position > 4) seek(0.0) else playAt((index - 1).coerceAtLeast(0)) }
    fun seek(seconds: Double) {
        val track = now ?: return
        if (track.localPath == null && stream == null) return
        position = seconds.coerceIn(0.0, track.duration.coerceAtLeast(1).toDouble())
        player.play(track, stream, position)
    }

    fun shuffle() {
        if (index !in queue.indices) return
        val current = queue[index]
        queue = listOf(current) + queue.filterIndexed { i, _ -> i != index }.shuffled()
        index = 0
        repeatShuffle = !repeatShuffle
    }

    fun moveQueue(from: Int, by: Int) {
        val to = from + by
        if (from !in queue.indices || to !in queue.indices) return
        val changed = queue.toMutableList()
        java.util.Collections.swap(changed, from, to)
        index = when (index) { from -> to; to -> from; else -> index }
        queue = changed
    }

    fun removeQueue(at: Int) {
        if (at !in queue.indices) return
        val wasCurrent = at == index
        queue = queue.filterIndexed { i, _ -> i != at }
        if (queue.isEmpty()) { player.stop(); index = -1 }
        else if (wasCurrent) playAt(at.coerceAtMost(queue.lastIndex))
        else if (at < index) index--
    }

    fun like(track: Track) {
        val likes = if (track.id in data.likes) data.likes - track.id else data.likes + track.id
        persist(data.copy(tracks = (data.tracks + track).distinctBy { it.id }, likes = likes))
    }

    fun newPlaylist(name: String) {
        if (name.isBlank()) return
        persist(data.copy(playlists = data.playlists + SavedPlaylist(name = name.trim().take(200))))
    }

    fun addToPlaylist(track: Track, id: String) = persist(data.copy(
        tracks = (data.tracks + track).distinctBy { it.id },
        playlists = data.playlists.map { if (it.id == id) it.copy(tracks = (it.tracks + track.id).distinct()) else it },
    ))

    fun playlistTracks(id: String): List<Track> = data.playlists.find { it.id == id }?.tracks.orEmpty().mapNotNull { trackId -> data.tracks.find { it.id == trackId } }

    fun importAudio(files: List<File>) {
        val imported = files.filter { it.isFile && it.extension.lowercase() in setOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "wma") }.map { file ->
            val path = file.canonicalPath
            val id = "local:" + MessageDigest.getInstance("SHA-256").digest(path.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
            Track(id, file.nameWithoutExtension, localPath = path)
        }
        persist(data.copy(tracks = (data.tracks + imported).distinctBy { it.id }))
        navigate("library")
    }

    fun importPlaylist(value: String) {
        browse("collection") {
            val uri = java.net.URI(value.trim())
            require(uri.scheme == "https" && uri.host in setOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com"))
            val id = uri.rawQuery.orEmpty().split("&").find { it.startsWith("list=") }?.substringAfter("=") ?: error("Missing playlist")
            require(Regex("[A-Za-z0-9_-]{2,120}").matches(id))
            val tracks = engine.collection(Track(id, "", kind = "playlist"))
            val name = "YouTube · ${id.take(20)}"
            persist(data.copy(tracks = (data.tracks + tracks).distinctBy { it.id }, playlists = data.playlists + SavedPlaylist(name = name, tracks = tracks.map { it.id })))
            results = tracks
            collectionTitle = name
        }
    }

    fun backup(file: File) {
        runCatching { file.writeText(libraryJson.encodeToString(data)) }.onFailure { error = "desktop_save_error" }
    }

    fun restore(file: File) {
        try {
            require(file.length() <= 20_000_000)
            val imported = libraryJson.decodeFromString<LibraryData>(file.readText())
            persist(store.merge(data, imported))
        } catch (_: Exception) { error = "desktop_backup_error" }
    }

    fun close() {
        if (closed) return
        closed = true
        player.close()
        scope.cancel()
    }
}
