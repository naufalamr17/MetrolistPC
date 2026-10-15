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

class MetrolistEngine {
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

    suspend fun home(): List<Shelf> = withContext(Dispatchers.IO) {
        withTimeout(45000) {
            YouTube.home().getOrThrow().sections.map { section ->
                Shelf(section.title, section.items.mapNotNull { it.toTrack() }.distinctBy { it.kind to it.id })
            }.filter { it.tracks.isNotEmpty() }
        }
    }

    suspend fun search(query: String, type: String = "song"): List<Track> = withContext(Dispatchers.IO) {
        require(query.isNotBlank() && query.length <= 200)
        val filter = when (type) {
            "album" -> YouTube.SearchFilter.FILTER_ALBUM
            "artist" -> YouTube.SearchFilter.FILTER_ARTIST
            "playlist" -> YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST
            "video" -> YouTube.SearchFilter.FILTER_VIDEO
            else -> YouTube.SearchFilter.FILTER_SONG
        }
        withTimeout(45000) { YouTube.search(query.trim(), filter).getOrThrow().items.mapNotNull { it.toTrack() } }
    }

    suspend fun radio(id: String): List<Track> = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        withTimeout(45000) {
            YouTube.next(WatchEndpoint(videoId = id, playlistId = "RDAMVM$id")).getOrThrow().items.map { it.asTrack() }.distinctBy { it.id }
        }
    }

    suspend fun mix(data: LibraryData, shelves: List<Shelf>): List<Track> {
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
        withTimeout(45000) {
            when (item.kind) {
                "album" -> YouTube.album(item.id).getOrThrow().songs.map { it.asTrack() }
                "artist" -> YouTube.artist(item.id).getOrThrow().sections.flatMap { it.items }.filterIsInstance<SongItem>().map { it.asTrack() }
                "playlist" -> YouTube.playlist(item.id.removePrefix("VL")).getOrThrow().songs.map { it.asTrack() }
                else -> listOf(item)
            }.distinctBy { it.id }
        }
    }

    suspend fun lyrics(track: Track): Lyrics = withContext(Dispatchers.IO) {
        withTimeout(30000) {
            val result = LrcLib.getLyrics(track.title, track.artist, track.duration.takeIf { it > 0 } ?: -1, track.album)
            val text = result.getOrNull() ?: if (track.localPath == null) {
                val endpoint = YouTube.next(WatchEndpoint(videoId = track.id)).getOrThrow().lyricsEndpoint
                endpoint?.let { YouTube.lyrics(it).getOrNull() }.orEmpty()
            } else ""
            Lyrics(text, parseLyrics(text))
        }
    }

    suspend fun stream(id: String): ExtractedStream = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        withTimeout(90000) {
            requireNotNull(extractor.extract(
                videoId = id,
                hints = ContentHints(wantVideo = false).withStreamCapabilities(allowSabr = false, allowHls = true, allowBoundedRange = true),
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
