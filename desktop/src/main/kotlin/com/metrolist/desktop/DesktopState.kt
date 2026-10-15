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
    var homeFilters by mutableStateOf<List<HomeFilter>>(emptyList())
    var homeFilter by mutableStateOf<HomeFilter?>(null)
    var homeContinuation by mutableStateOf<String?>(null)
    var moods by mutableStateOf<List<MoodChoice>>(emptyList())
    var quickPicks by mutableStateOf<List<Track>>(emptyList())
    var quickPicksBusy by mutableStateOf(false)
    private var quickPicksJob: Job? = null
    private var discoveryJob: Job? = null
    private var discovery = emptyList<Shelf>()
    var resultsContinuation by mutableStateOf<String?>(null)
    var loadingMore by mutableStateOf(false)
    var panelVisible by mutableStateOf(true)
    var volume by mutableStateOf(data.volume)
    private var unmutedVolume = data.volume.takeIf { it > 0 } ?: 0.7f
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
    var extendingQueue by mutableStateOf(false)
    val canNext: Boolean get() = now != null && !extendingQueue && (index < queue.lastIndex || repeat == 1 || now?.localPath == null)
    private var originalQueue = emptyList<Track>()
    private var playbackGeneration = 0L
    private var recoveryAttempts = 0
    private var stream: ExtractedStream? = null
    private var playJob: Job? = null
    private var lyricJob: Job? = null
    private var relatedJob: Job? = null
    private var browseJob: Job? = null
    private var mixJob: Job? = null
    private var nextJob: Job? = null
    private var queueRequest = 0L
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

    fun refreshHome(filter: HomeFilter? = homeFilter) = browse("home") {
        val feed = engine.homeFeed(params = filter?.params)
        homeFilter = filter
        home = if (filter == null) mergeShelves(feed.shelves, discovery) else feed.shelves
        homeContinuation = feed.continuation
        if (filter == null) homeFilters = feed.filters
        if (filter == null) {
            quickPicksJob?.cancel()
            quickPicksJob = scope.launch {
                quickPicksBusy = true
                try { quickPicks = engine.mix(data, feed.shelves).filter { it.kind == "song" }.distinctBy { it.id }.take(18) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Recommendations must not hide the available playlist shelves. */ }
                finally { quickPicksBusy = false }
            }
        }
        if (discoveryJob == null) discoveryJob = scope.launch {
            try {
                val loaded = engine.discover()
                discovery = loaded.shelves
                moods = loaded.moods
                if (homeFilter == null) home = mergeShelves(home, discovery)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Optional discovery must not hide a working Home feed. */ }
        }
    }

    fun openMood(choice: MoodChoice) = browse("collection") { collectionTitle = choice.title; results = engine.mood(choice) }

    fun loadMoreHome() {
        val continuation = homeContinuation ?: return
        if (loadingMore || busy) return
        loadingMore = true
        browseJob = scope.launch {
            try {
                val feed = engine.homeFeed(continuation, homeFilter?.params)
                home = mergeShelves(home, feed.shelves)
                homeContinuation = feed.continuation.takeUnless { it == continuation }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_network_error" }
            finally { loadingMore = false }
        }
    }

    fun loadMoreResults() {
        val continuation = resultsContinuation ?: return
        if (loadingMore || busy) return
        loadingMore = true
        browseJob = scope.launch {
            try {
                val page = engine.searchPage(query, searchType, continuation)
                results = (results + page.tracks).distinctBy { it.kind to it.id }.take(10000)
                resultsContinuation = page.continuation.takeUnless { it == continuation }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_network_error" }
            finally { loadingMore = false }
        }
    }
    fun search() {
        if (query.isBlank()) { navigate("search"); return }
        browse("search") { val page = engine.searchPage(query, searchType); results = page.tracks; resultsContinuation = page.continuation }
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
        repeatShuffle = false
        originalQueue = emptyList()
        if (queue.isNotEmpty()) playAt(start.coerceIn(queue.indices))
    }

    fun playAt(target: Int) {
        val track = queue.getOrNull(target) ?: return
        playJob?.cancel()
        lyricJob?.cancel()
        relatedJob?.cancel()
        player.stop()
        nextJob?.cancel()
        queueRequest++
        extendingQueue = false
        val generation = ++playbackGeneration
        recoveryAttempts = 0
        index = target
        position = 0.0
        stream = null
        preparing = true
        lyrics = null
        related = emptyList()
        error = ""
        player.onStarted = {
            scope.launch {
                if (generation == playbackGeneration && now?.id == track.id) {
                    error = ""
                    persist(data.copy(
                    tracks = (data.tracks + track).distinctBy { it.id },
                    history = (listOf(track.id) + data.history).distinct().take(500),
                    ))
                }
            }
        }
        player.onEnded = { scope.launch { if (generation == playbackGeneration) next(automatic = true) } }
        player.onFailure = {
            val resumeAt = player.position
            scope.launch {
                if (generation == playbackGeneration && !closed) {
                    if (track.localPath == null && recoveryAttempts < 2) {
                        recoveryAttempts++
                        error = "desktop_reconnecting"
                        preparePlayback(track, generation, resumeAt)
                    } else error = "desktop_playback_error"
                }
            }
        }
        preparePlayback(track, generation)
        lyricJob = scope.launch {
            lyricsBusy = true
            try { lyrics = engine.lyrics(track) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { lyrics = Lyrics("", emptyList()) }
            finally { if (generation == playbackGeneration) lyricsBusy = false }
        }
        if (track.localPath == null) relatedJob = scope.launch {
            try { val loaded = engine.radio(track.id).filter { it.id != track.id }; if (generation == playbackGeneration) related = loaded }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (generation == playbackGeneration) related = emptyList() }
        }
    }

    private fun preparePlayback(track: Track, generation: Long, offset: Double = 0.0) {
        playJob?.cancel()
        preparing = true
        playJob = scope.launch {
            try {
                val resolved = if (track.localPath == null) engine.stream(track.id) else null
                ensureActive()
                if (generation != playbackGeneration || closed) return@launch
                stream = resolved
                player.play(track, resolved, offset)
                if (track.duration <= 0) {
                    val duration = resolved?.mediaMetadata?.durationSeconds?.toInt() ?: 0
                    if (duration > 0) queue = queue.mapIndexed { i, item -> if (i == index) item.copy(duration = duration) else item }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (generation == playbackGeneration) error = "desktop_playback_error" }
            finally { if (generation == playbackGeneration) preparing = false }
        }
    }

    fun next(automatic: Boolean = false) {
        if (queue.isEmpty()) return
        if (automatic && repeat == 2) { playAt(index); return }
        if (index < queue.lastIndex) { playAt(index + 1); return }
        if (repeat == 1) { playAt(0); return }
        if (extendingQueue) return
        val seed = now?.takeIf { it.localPath == null } ?: return
        val generation = playbackGeneration
        val request = ++queueRequest
        extendingQueue = true
        nextJob = scope.launch {
            try {
                val ids = queue.mapTo(HashSet()) { it.id }
                val extra = engine.radio(seed.id).filter { it.id !in ids }
                if (generation == playbackGeneration) {
                    if (extra.isNotEmpty()) {
                        val added = extra.take(500)
                        val retained = queue.takeLast(1000 - added.size)
                        queue = retained + added
                        playAt(retained.size)
                    } else error = "desktop_no_more"
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "desktop_network_error" }
            finally { if (request == queueRequest) extendingQueue = false }
        }
    }

    fun togglePlayback() {
        if (player.status.value in listOf("playing", "paused")) player.togglePause()
        else if (!preparing) { if (now != null) playAt(index) else if (queue.isNotEmpty()) playAt(0) else myMix() }
    }

    fun setPlayerVolume(value: Float) { volume = value.coerceIn(0f, 1f); player.volume = volume }
    fun toggleMute() {
        if (volume > 0) { unmutedVolume = volume; setPlayerVolume(0f) } else setPlayerVolume(unmutedVolume)
        persist(data.copy(volume = volume))
    }

    fun previous() { if (position > 3) seek(0.0) else if (index > 0) playAt(index - 1) else if (repeat == 1 && queue.isNotEmpty()) playAt(queue.lastIndex) else if (index == 0) seek(0.0) }
    fun seek(seconds: Double) {
        val track = now ?: return
        if (track.localPath == null && stream == null) return
        position = seconds.coerceIn(0.0, track.duration.coerceAtLeast(1).toDouble())
        player.play(track, stream, position, startPaused = player.status.value == "paused")
    }

    fun shuffle() {
        if (index !in queue.indices) return
        val current = queue[index]
        if (!repeatShuffle) {
            originalQueue = queue
            queue = listOf(current) + queue.filterIndexed { i, _ -> i != index }.shuffled()
        } else queue = originalQueue.filter { it in queue } + queue.filter { it !in originalQueue }
        index = queue.indexOf(current).coerceAtLeast(0)
        repeatShuffle = !repeatShuffle
    }

    fun enqueue(track: Track, next: Boolean = false) {
        if (track.kind != "song") return
        if (queue.size >= 1000) { error = "desktop_queue_limit"; return }
        queue = queue.toMutableList().apply { add(if (next && index >= 0) (index + 1).coerceAtMost(size) else size, track) }
    }

    fun clearQueue() {
        playbackGeneration++
        queueRequest++; nextJob?.cancel(); extendingQueue = false
        playJob?.cancel(); lyricJob?.cancel(); relatedJob?.cancel(); mixJob?.cancel()
        player.stop()
        queue = emptyList(); index = -1; position = 0.0; stream = null; lyrics = null
        repeatShuffle = false; originalQueue = emptyList()
        preparing = false; lyricsBusy = false
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
        if (queue.isEmpty()) clearQueue()
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

fun mergeShelves(current: List<Shelf>, extra: List<Shelf>): List<Shelf> = (current + extra).groupBy { it.title }.map { (title, shelves) ->
    Shelf(title, shelves.flatMap { it.tracks }.distinctBy { it.kind to it.id }.take(100))
}.take(100)
