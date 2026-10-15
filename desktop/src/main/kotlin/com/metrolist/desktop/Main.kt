package com.metrolist.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.FileDialog
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun main(args: Array<String>) {
    // A non-directory makes Java use TCP pipes; AF_UNIX connect fails on this Windows host.
    System.setProperty("jdk.net.unixdomain.tmpdir", File(System.getProperty("java.home"), "lib/modules").absolutePath)
    val cache = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "MetrolistDesktop/cache").apply { mkdirs() }
    System.setProperty("java.io.tmpdir", cache.absolutePath)
    if (args.firstOrNull() == "--verify-installation") {
        val report = File(requireNotNull(args.getOrNull(1)))
        report.parentFile.mkdirs()
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(report), true, "UTF-8"))
        System.setProperty("metrolist.live.output", report.parentFile.absolutePath)
        val resources = File(requireNotNull(System.getProperty("compose.application.resources.dir")))
        check(File(resources, "ffmpeg.exe").isFile)
        Class.forName("com.sun.net.httpserver.HttpServer")
        println("PACKAGED_RUNTIME java=${System.getProperty("java.version")} audioDecoder=bundled")
        runVerification()
        kotlin.system.exitProcess(0)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    val state = DesktopState(scope)
    application(exitProcessOnExit = true) {
        Window(
            onCloseRequest = { state.close(); exitApplication() },
            title = s("desktop_title"),
            icon = androidx.compose.ui.res.painterResource("metrolist-icon.png"),
            state = rememberWindowState(width = 1380.dp, height = 900.dp),
        ) {
            window.minimumSize = java.awt.Dimension(1024, 680)
            LaunchedEffect(Unit) { state.start() }
            DesktopApp(state)
        }
    }
    runBlocking { withTimeoutOrNull(3000) { runCatching { state.engine.close() } } }
}

private val darkScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF241B38),
    background = Color(0xFF0D0D10), surface = Color(0xFF151519),
    surfaceVariant = Color(0xFF25242C), onSurface = Color(0xFFF4F0FA), onSurfaceVariant = Color(0xFFB9B3C3),
)

@Composable
fun DesktopApp(state: DesktopState) {
    MaterialTheme(colorScheme = if (state.data.lightTheme) lightColorScheme(primary = Color(0xFF7147A7)) else darkScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var createPlaylist by remember { mutableStateOf(false) }
            var addTrack by remember { mutableStateOf<Track?>(null) }
            var importPlaylist by remember { mutableStateOf(false) }
            var rename by remember { mutableStateOf<SavedPlaylist?>(null) }
            var delete by remember { mutableStateOf<SavedPlaylist?>(null) }
            Column {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(204.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.padding(vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 30.sp)
                            Spacer(Modifier.width(10.dp))
                            Text("Metrolist", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                        listOf("home" to "desktop_home", "search" to "desktop_search", "library" to "desktop_library", "likes" to "desktop_likes", "history" to "desktop_history").forEach { (view, label) ->
                            SidebarButton(s(label), state.view == view) { state.navigate(view) }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(s("desktop_playlists"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.sp)
                        TextButton(onClick = { createPlaylist = true }) { Text("+  ${s("desktop_new_playlist")}") }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(state.data.playlists, key = { it.id }) { playlist ->
                                SidebarButton(playlist.name, state.view == "playlist:${playlist.id}") { state.navigate("playlist:${playlist.id}") }
                            }
                        }
                        SidebarButton(s("desktop_settings"), state.view == "settings") { state.navigate("settings") }
                        Text(s("desktop_guest"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(24.dp, 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(state.query, { state.query = it.take(200) }, Modifier.weight(1f).onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { state.search(); true } else false }, placeholder = { Text(s("desktop_search_hint"), fontSize = 13.sp) }, singleLine = true, shape = RoundedCornerShape(28.dp))
                            FilledTonalButton(onClick = { state.search() }, enabled = !state.busy) { Text(s("desktop_search")) }
                        }
                        if (state.busy || state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.error.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s(state.error), Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                                TextButton(onClick = { state.error = "" }) { Text("×") }
                            }
                        }
                        Row(Modifier.weight(1f)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                val playlist = state.data.playlists.find { state.view == "playlist:${it.id}" }
                                when {
                                    state.view == "home" -> Home(state, { addTrack = it })
                                    state.view == "settings" -> Settings(state)
                                    else -> {
                                        val tracks = when {
                                            playlist != null -> state.playlistTracks(playlist.id)
                                            state.view == "library" -> state.data.tracks
                                            state.view == "likes" -> state.data.likes.mapNotNull { id -> state.data.tracks.find { it.id == id } }
                                            state.view == "history" -> state.data.history.mapNotNull { id -> state.data.tracks.find { it.id == id } }
                                            else -> state.results
                                        }
                                        val title = when {
                                            playlist != null -> playlist.name
                                            state.view == "collection" -> state.collectionTitle
                                            state.view == "library" -> s("desktop_library")
                                            state.view == "likes" -> s("desktop_likes")
                                            state.view == "history" -> s("desktop_history")
                                            else -> s("desktop_results")
                                        }
                                        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                                            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (state.view == "search") Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                listOf("song" to "desktop_songs", "video" to "desktop_videos", "album" to "desktop_albums", "artist" to "desktop_artists", "playlist" to "desktop_playlists").forEach { (kind, label) ->
                                                    FilterChip(state.searchType == kind, onClick = { state.searchType = kind; if (state.query.isNotBlank()) state.search() }, label = { Text(s(label)) })
                                                }
                                            }
                                            Row(Modifier.padding(vertical = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (tracks.any { it.kind == "song" }) Button(onClick = { state.playList(tracks) }) { Text("▶  ${s("desktop_play_all")}") }
                                                if (state.view == "library") {
                                                    OutlinedButton(onClick = { pickFiles("desktop_import_audio", true) { state.importAudio(it) } }) { Text(s("desktop_import_audio")) }
                                                    OutlinedButton(onClick = { importPlaylist = true }) { Text(s("desktop_import_playlist")) }
                                                }
                                                if (playlist != null) {
                                                    OutlinedButton(onClick = { rename = playlist }) { Text(s("desktop_rename")) }
                                                    TextButton(onClick = { delete = playlist }) { Text(s("desktop_delete")) }
                                                }
                                            }
                                            if (state.view == "collection") Text(s("desktop_collection_limit"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (tracks.isEmpty() && !state.busy) Text(s(if (state.view == "library") "desktop_empty_library" else "desktop_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 30.dp))
                                            LazyColumn(Modifier.fillMaxSize()) {
                                                itemsIndexed(tracks, key = { _, track -> "${track.kind}:${track.id}" }) { i, track ->
                                                    TrackRow(track, state.now?.id == track.id,
                                                        play = { if (track.kind == "song") state.playList(tracks.filter { it.kind == "song" }, tracks.take(i).count { it.kind == "song" }) else state.open(track) },
                                                        liked = track.id in state.data.likes,
                                                        like = { state.like(track) },
                                                        add = { addTrack = track },
                                                        enqueue = { state.queue = state.queue + track },
                                                        trailing = {
                                                            if (playlist != null) {
                                                                TinyButton("↑") { val ids = playlist.tracks.toMutableList(); if (i > 0) { java.util.Collections.swap(ids, i, i - 1); state.persist(state.data.copy(playlists = state.data.playlists.map { if (it.id == playlist.id) it.copy(tracks = ids) else it })) } }
                                                                TinyButton("×") { state.persist(state.data.copy(playlists = state.data.playlists.map { if (it.id == playlist.id) it.copy(tracks = it.tracks - track.id) else it })) }
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            SidePanel(state, { addTrack = it })
                        }
                    }
                }
                PlayerBar(state)
            }
            if (createPlaylist) NameDialog("desktop_new_playlist", "", { createPlaylist = false }) { state.newPlaylist(it); createPlaylist = false }
            rename?.let { playlist -> NameDialog("desktop_rename", playlist.name, { rename = null }) { name -> state.persist(state.data.copy(playlists = state.data.playlists.map { if (it.id == playlist.id) it.copy(name = name) else it })); rename = null } }
            delete?.let { playlist -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(s("desktop_delete")) }, text = { Text(s("desktop_delete_confirm")) }, confirmButton = { TextButton(onClick = { state.persist(state.data.copy(playlists = state.data.playlists - playlist)); state.navigate("library"); delete = null }) { Text(s("desktop_delete")) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text(s("desktop_cancel")) } }) }
            addTrack?.let { track -> AlertDialog(onDismissRequest = { addTrack = null }, title = { Text(s("desktop_add_playlist")) }, text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    state.data.playlists.forEach { playlist -> TextButton(onClick = { state.addToPlaylist(track, playlist.id); addTrack = null }) { Text(playlist.name) } }
                    TextButton(onClick = { addTrack = null; createPlaylist = true }) { Text("+  ${s("desktop_new_playlist")}") }
                }
            }, confirmButton = { TextButton(onClick = { addTrack = null }) { Text(s("desktop_cancel")) } }) }
            if (importPlaylist) NameDialog("desktop_playlist_url", "", { importPlaylist = false }) { state.importPlaylist(it); importPlaylist = false }
        }
    }
}

@Composable private fun SidebarButton(label: String, selected: Boolean, action: () -> Unit) {
    Surface(onClick = action, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(13.dp, 11.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun Home(state: DesktopState, add: (Track) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(25.dp)) {
                Text(s("desktop_subtitle"), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(s("desktop_mix_hint"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(vertical = 13.dp))
                Button(onClick = { state.myMix() }, enabled = !state.busy) { Text("▶  ${s("desktop_mix")}") }
            }
        }
        if (state.home.isEmpty() && !state.busy) item { OutlinedButton(onClick = { state.refreshHome() }) { Text(s("desktop_retry")) } }
        items(state.home) { shelf ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(shelf.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(shelf.tracks, key = { "${it.kind}:${it.id}" }) { track ->
                        Column(Modifier.width(150.dp)) {
                            Box(Modifier.clickable { state.open(track) }) {
                                Cover(track.thumbnail, Modifier.size(150.dp).clip(RoundedCornerShape(15.dp)))
                                Surface(Modifier.align(Alignment.BottomEnd).padding(9.dp), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.7f)) { Text("▶", color = Color.White, modifier = Modifier.padding(11.dp), fontSize = 20.sp) }
                            }
                            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)
                            Text(track.artist.ifEmpty { track.kind }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable private fun TrackRow(track: Track, current: Boolean, play: () -> Unit, liked: Boolean, like: () -> Unit, add: () -> Unit, enqueue: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(onClick = play)) { Cover(track.thumbnail, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))) }
        Column(Modifier.weight(1f).clickable(onClick = play).padding(vertical = 6.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (track.kind == "song") {
            Text(clock(track.duration.toDouble()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TinyButton(if (liked) "♥" else "♡", action = like)
            TinyButton("+", action = enqueue)
            TinyButton("⋯", action = add)
        }
        trailing()
    }
}

@Composable private fun SidePanel(state: DesktopState, add: (Track) -> Unit) {
    Column(Modifier.width(306.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("queue" to "desktop_queue", "lyrics" to "desktop_lyrics", "related" to "desktop_related").forEach { (tab, label) ->
                TextButton(onClick = { state.panel = tab }, contentPadding = PaddingValues(6.dp)) { Text(s(label), color = if (state.panel == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            }
        }
        HorizontalDivider(Modifier.padding(bottom = 10.dp))
        if (state.panel == "lyrics") {
            if (state.lyricsBusy) Text(s("desktop_loading_lyrics"), fontSize = 13.sp)
            else if (state.lyrics?.text.isNullOrBlank()) Text(s("desktop_no_lyrics"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (state.lyrics!!.lines.isEmpty()) Text(state.lyrics!!.text, Modifier.verticalScroll(rememberScrollState()), fontSize = 18.sp, lineHeight = 30.sp)
            else {
                val lines = state.lyrics!!.lines
                val active = activeLyric(lines, (state.position * 1000 + state.lyricOffset * 1000).toLong())
                val list = rememberLazyListState()
                LaunchedEffect(active) { if (active >= 0 && !list.isScrollInProgress) list.animateScrollToItem((active - 2).coerceAtLeast(0)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s("desktop_lyrics_offset"), fontSize = 10.sp, modifier = Modifier.weight(1f))
                    TinyButton("−") { state.lyricOffset -= 0.5f }
                    Text("${state.lyricOffset}s", fontSize = 11.sp)
                    TinyButton("+") { state.lyricOffset += 0.5f }
                }
                LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                    itemsIndexed(lines) { index, line -> Text(line.text, color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f), fontSize = if (index == active) 23.sp else 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().clickable { state.seek((line.timeMs / 1000.0 - state.lyricOffset).coerceAtLeast(0.0)) }) }
                }
            }
        } else {
            val tracks = if (state.panel == "queue") state.queue else state.related
            if (tracks.isEmpty()) Text(s("desktop_empty"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks) { index, track ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(track.thumbnail, Modifier.size(37.dp).clip(RoundedCornerShape(6.dp)).clickable { if (state.panel == "queue") state.playAt(index) else state.radio(track) })
                        Column(Modifier.weight(1f).padding(horizontal = 9.dp).clickable { if (state.panel == "queue") state.playAt(index) else state.radio(track) }) {
                            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (state.now?.id == track.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.panel == "queue") {
                            TinyButton("↑") { state.moveQueue(index, -1) }
                            TinyButton("×") { state.removeQueue(index) }
                        } else TinyButton("+") { state.queue = state.queue + track }
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerBar(state: DesktopState) {
    val playbackStatus by state.player.status.collectAsState()
    val status = if (state.preparing) "loading" else playbackStatus
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().height(108.dp).padding(18.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Cover(state.now?.thumbnail.orEmpty(), Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.width(190.dp)) {
                Text(state.now?.title ?: s("desktop_nothing_playing"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(state.now?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.now?.let { track -> TinyButton(if (track.id in state.data.likes) "♥" else "♡") { state.like(track) } }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextButton(onClick = { state.shuffle() }, enabled = state.queue.isNotEmpty()) { Text("⤨", color = if (state.repeatShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp) }
                    TextButton(onClick = { state.previous() }, enabled = state.now != null) { Text("⏮", fontSize = 20.sp) }
                    FilledTonalButton(onClick = { if (status == "playing" || status == "paused") state.player.togglePause() else if (state.index >= 0) state.playAt(state.index) else state.myMix() }, enabled = status != "loading", modifier = Modifier.semantics { contentDescription = s(if (status == "playing") "desktop_pause" else "desktop_play") }, contentPadding = PaddingValues(18.dp, 8.dp)) {
                        Text(if (status == "playing") "❚❚" else "▶", fontSize = 22.sp)
                    }
                    TextButton(onClick = { state.next() }, enabled = state.now != null) { Text("⏭", fontSize = 20.sp) }
                    TextButton(onClick = { state.repeat = (state.repeat + 1) % 3 }) { Text(if (state.repeat == 2) "↻¹" else "↻", color = if (state.repeat > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(clock(state.position), fontSize = 10.sp)
                    var drag by remember(state.now?.id) { mutableStateOf<Float?>(null) }
                    Slider(value = drag ?: state.position.toFloat().coerceIn(0f, (state.now?.duration ?: 0).coerceAtLeast(1).toFloat()), onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { state.seek(it.toDouble()) }; drag = null }, valueRange = 0f..(state.now?.duration ?: 0).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f).height(20.dp), enabled = (state.now?.duration ?: 0) > 0)
                    Text(clock((state.now?.duration ?: 0).toDouble()), fontSize = 10.sp)
                }
            }
            Column(Modifier.width(145.dp)) {
                Text(if (status == "loading") s("desktop_loading") else s("desktop_volume"), maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(state.player.volume, onValueChange = { state.player.volume = it }, onValueChangeFinished = { state.persist(state.data.copy(volume = state.player.volume)) }, modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable private fun Settings(state: DesktopState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(s("desktop_settings"), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(s("desktop_about"), color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) { Text(s("desktop_theme"), Modifier.weight(1f)); Switch(state.data.lightTheme, { state.persist(state.data.copy(lightTheme = it)) }) }
        Text(s("desktop_timer"), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(0, 15, 30, 60).forEach { minutes -> FilterChip(selected = if (minutes == 0) state.sleepUntil == 0L else state.sleepUntil > 0 && ((state.sleepUntil - System.currentTimeMillis()) / 60000 + 1).toInt() == minutes, onClick = { state.sleepUntil = if (minutes == 0) 0 else System.currentTimeMillis() + minutes * 60000L }, label = { Text(if (minutes == 0) s("desktop_timer_off") else "$minutes ${s("desktop_minutes")}") }) }
        }
        Text(s("desktop_speed"), fontWeight = FontWeight.Bold)
        var speed by remember { mutableStateOf(state.player.speed.toFloat()) }
        Slider(speed, { speed = it }, onValueChangeFinished = { state.player.speed = speed.toDouble(); if (state.now != null) state.seek(state.position) }, valueRange = 0.5f..2f, steps = 5)
        Text("%.2f×".format(speed), fontSize = 12.sp)
        OutlinedButton(onClick = { pickFiles("desktop_import_audio", true) { state.importAudio(it) } }) { Text(s("desktop_import_audio")) }
        OutlinedButton(onClick = { pickFiles("desktop_export", false, true) { it.firstOrNull()?.let(state::backup) } }) { Text(s("desktop_export")) }
        OutlinedButton(onClick = { pickFiles("desktop_restore", false) { it.firstOrNull()?.let(state::restore) } }) { Text(s("desktop_restore")) }
        HorizontalDivider()
        Text(s("desktop_local_notice"), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(s("desktop_port_notice"), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun TinyButton(text: String, description: String = when (text) { "♥", "♡" -> s("desktop_likes"); "+" -> s("desktop_add_queue"); "⋯" -> s("desktop_add_playlist"); "×" -> s("desktop_remove"); else -> text }, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.size(29.dp).semantics { contentDescription = description }, contentPadding = PaddingValues(0.dp)) { Text(text, fontSize = 17.sp) }
}

@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(s(title)) }, text = { OutlinedTextField(value, { value = it.take(if (title == "desktop_playlist_url") 2048 else 200) }, singleLine = true) }, confirmButton = { TextButton(onClick = { save(value.trim()) }, enabled = value.isNotBlank()) { Text(s("desktop_save")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(s("desktop_cancel")) } })
}

private fun pickFiles(title: String, multiple: Boolean, save: Boolean = false, action: (List<File>) -> Unit) {
    val dialog = FileDialog(null as java.awt.Frame?, s(title), if (save) FileDialog.SAVE else FileDialog.LOAD)
    dialog.isMultipleMode = multiple
    if (save) dialog.file = "Metrolist-library.json"
    dialog.isVisible = true
    val files = dialog.files.toList()
    dialog.dispose()
    if (files.isNotEmpty()) action(files)
}

private val artworkClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build() }
private val artworkCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 60 }

@Composable fun Cover(url: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            synchronized(artworkCache) { artworkCache[url] } ?: runCatching {
                val uri = URI(url)
                require(uri.scheme == "https" && uri.rawUserInfo == null && listOf("ytimg.com", "ggpht.com", "googleusercontent.com").any { uri.host == it || uri.host.endsWith(".$it") })
                val response = artworkClient.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { input ->
                    require(response.statusCode() == 200)
                    val bytes = input.readNBytes(1_500_001)
                    require(bytes.size <= 1_500_000)
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { image -> synchronized(artworkCache) { artworkCache[url] = image } }
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("♫", fontSize = 27.sp, color = MaterialTheme.colorScheme.primary)
    }
}
