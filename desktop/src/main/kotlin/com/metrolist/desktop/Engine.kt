package com.metrolist.desktop

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.*
import com.metrolist.lrclib.LrcLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException

data class Shelf(val title: String, val tracks: List<Track>)
data class Lyrics(val text: String, val lines: List<LyricLine>)
data class TrackPage(val tracks: List<Track>, val continuation: String?)
data class HomeFilter(val title: String, val params: String)
data class HomeFeed(val shelves: List<Shelf>, val filters: List<HomeFilter>, val continuation: String?)
data class MoodChoice(val title: String, val browseId: String, val params: String?)
data class Discovery(val shelves: List<Shelf>, val moods: List<MoodChoice>)

open class MetrolistEngine {
    private val transport by lazy { YouTube.extractionTransport() }
    private val config by lazy { RemotePlayerConfigStore(transport.httpClient, PlayerConfigRepository.disabled()) }
    private val cipher by lazy { YouTubeCipherService(transport.httpClient, config) }
    private val extractor by lazy {
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(transport.httpClient, transport.innerTube, config),
            cipherService = cipher,
            innerTube = transport.innerTube,
        )
    }

    suspend fun home(): List<Shelf> = homeFeed().shelves

    open suspend fun homeFeed(continuation: String? = null, params: String? = null): HomeFeed = withContext(Dispatchers.IO) {
        withTimeout(45000) {
            val page = YouTube.home(continuation, params).getOrThrow()
            val shelves = page.sections.map { section ->
                Shelf(section.title, section.items.mapNotNull { it.toTrack() }.distinctBy { it.kind to it.id })
            }.filter { it.tracks.isNotEmpty() }
            HomeFeed(mergeShelves(emptyList(), shelves), page.chips.orEmpty().mapNotNull { chip -> chip.endpoint?.params?.let { HomeFilter(chip.title, it) } }, page.continuation)
        }
    }

    suspend fun search(query: String, type: String = "song"): List<Track> = searchPage(query, type).tracks

    open suspend fun discover(): Discovery = withContext(Dispatchers.IO) {
        withTimeout(30000) {
            val page = YouTube.explore().getOrThrow()
            Discovery(
                listOf(Shelf(s("desktop_new_releases"), page.newReleaseAlbums.mapNotNull { it.toTrack() })).filter { it.tracks.isNotEmpty() },
                page.moodAndGenres.map { MoodChoice(it.title, it.endpoint.browseId, it.endpoint.params) },
            )
        }
    }

    suspend fun mood(choice: MoodChoice): List<Track> = withContext(Dispatchers.IO) {
        withTimeout(45000) { YouTube.browse(choice.browseId, choice.params).getOrThrow().items.flatMap { it.items }.mapNotNull { it.toTrack() }.distinctBy { it.kind to it.id } }
    }

    suspend fun searchPage(query: String, type: String = "song", continuation: String? = null): TrackPage = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 200)
        val filter = when (type) {
            "album" -> YouTube.SearchFilter.FILTER_ALBUM
            "artist" -> YouTube.SearchFilter.FILTER_ARTIST
            "playlist" -> YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST
            "video" -> YouTube.SearchFilter.FILTER_VIDEO
            else -> YouTube.SearchFilter.FILTER_SONG
        }
        withTimeout(45000) {
            val page = if (continuation == null) YouTube.search(query.trim(), filter).getOrThrow() else YouTube.searchContinuation(continuation).getOrThrow()
            TrackPage(page.items.mapNotNull { it.toTrack() }, page.continuation)
        }
    }

    open suspend fun radio(id: String): List<Track> = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        withTimeout(45000) {
            YouTube.next(WatchEndpoint(videoId = id, playlistId = "RDAMVM$id")).getOrThrow().items.map { it.asTrack() }.distinctBy { it.id }
        }
    }

    open suspend fun mix(data: LibraryData, shelves: List<Shelf>): List<Track> {
        val available = shelves.flatMap { it.tracks }
        var seed = chooseMixSeed(data, available)
        if (seed == null) {
            val firstCollection = available.firstOrNull { it.kind in listOf("album", "playlist") }
            seed = firstCollection?.let { collection(it).firstOrNull() }
        }
        val local = data.tracks.filter { it.localPath != null }.shuffled().take(5)
        if (seed == null) return local
        val online = (listOf(seed) + radio(seed.id)).distinctBy { it.id }.toMutableList()
        local.forEachIndexed { offset, track -> online.add(minOf((offset + 1) * 5, online.size), track) }
        return online
    }

    suspend fun collection(item: Track): List<Track> = withContext(Dispatchers.IO) {
        withTimeout(180000) {
            when (item.kind) {
                "album" -> YouTube.album(item.id).getOrThrow().songs.map { it.asTrack() }
                "artist" -> YouTube.artist(item.id).getOrThrow().sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.asTrack() }
                "playlist" -> {
                    val page = YouTube.playlist(item.id.removePrefix("VL")).getOrThrow()
                    val songs = page.songs.toMutableList()
                    var continuation = page.songsContinuation ?: page.continuation
                    val seen = HashSet<String>()
                    while (continuation != null && seen.add(continuation)) {
                        check(seen.size <= 100 && songs.size < 10000) { "Playlist exceeds the desktop library limit" }
                        val more = YouTube.playlistContinuation(continuation).getOrThrow()
                        songs += more.songs
                        continuation = more.continuation
                    }
                    check(songs.size <= 10000) { "Playlist exceeds the desktop library limit" }
                    songs.map { it.asTrack() }
                }
                else -> listOf(item)
            }.distinctBy { it.id }
        }
    }

    open suspend fun lyrics(track: Track): Lyrics = withContext(Dispatchers.IO) {
        withTimeout(30000) {
            val result = LrcLib.getLyrics(track.title, track.artist, track.duration.takeIf { it > 0 } ?: -1, track.album)
            val text = result.getOrNull()?.takeIf { it.isNotBlank() } ?: if (track.localPath == null) {
                val endpoint = YouTube.next(WatchEndpoint(videoId = track.id)).getOrThrow().lyricsEndpoint
                endpoint?.let { YouTube.lyrics(it).getOrNull() }.orEmpty()
            } else ""
            Lyrics(text, parseLyrics(text))
        }
    }

    open suspend fun stream(id: String): ExtractedStream = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        withTimeout(90000) {
            requireNotNull(extractor.extract(
                videoId = id,
                // Remote HLS needs a validated segment adapter; FFmpeg only receives direct media here.
                hints = ContentHints(wantVideo = false).withStreamCapabilities(allowSabr = false, allowHls = false, allowBoundedRange = true),
                audioQuality = AudioQuality.HIGH,
            ))
        }
    }

    suspend fun close() {
        cipher.dispose()
        transport.innerTube.close()
        transport.httpClient.close()
    }
}

fun SongItem.asTrack() = Track(id, title, artists.joinToString { it.name }, thumbnail, duration ?: 0, album = album?.name)

fun YTItem.toTrack(): Track? = when (this) {
    is SongItem -> asTrack()
    is AlbumItem -> Track(id, title, artists.orEmpty().joinToString { it.name }, thumbnail, kind = "album")
    is ArtistItem -> Track(id, title, thumbnail = thumbnail.orEmpty(), kind = "artist")
    is PlaylistItem -> Track(id, title, author?.name.orEmpty(), thumbnail.orEmpty(), kind = "playlist")
    is EpisodeItem -> asSongItem().asTrack()
    else -> null
}
