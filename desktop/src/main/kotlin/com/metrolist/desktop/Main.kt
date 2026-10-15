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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.painterResource
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
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when {
                event.isCtrlPressed && event.key == Key.Spacebar -> { state.togglePlayback(); true }
                event.isCtrlPressed && event.isShiftPressed && event.key == Key.DirectionRight -> { if (state.canNext) state.next(); true }
                event.isCtrlPressed && event.isShiftPressed && event.key == Key.DirectionLeft -> { state.previous(); true }
                else -> false
            }
        }, color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1260.dp
            val roomy = maxWidth >= 1100.dp
            LaunchedEffect(wide) { state.panelVisible = wide }
            var createPlaylist by remember { mutableStateOf(false) }
            var addTrack by remember { mutableStateOf<Track?>(null) }
            var importPlaylist by remember { mutableStateOf(false) }
            var rename by remember { mutableStateOf<SavedPlaylist?>(null) }
            var delete by remember { mutableStateOf<SavedPlaylist?>(null) }
            Column {
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(if (roomy) 204.dp else 176.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.padding(vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource("metrolist-icon.png"), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Text("Metrolist", fontWeight = FontWeight.Bold, fontSize = if (roomy) 24.sp else 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                                if (state.error == "desktop_playback_error") TextButton(onClick = { if (state.index >= 0) state.playAt(state.index) }) { Text(s("desktop_retry")) }
                                ControlButton(Symbol.Close, s("desktop_dismiss")) { state.error = "" }
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
                                                if (tracks.any { it.kind == "song" }) Button(onClick = { state.playList(tracks) }) { ControlIcon(Symbol.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(s("desktop_play_all")) }
                                                if (state.view == "library") {
                                                    OutlinedButton(onClick = { pickFiles("desktop_import_audio", true) { state.importAudio(it) } }) { Text(s("desktop_import_audio")) }
                                                    OutlinedButton(onClick = { importPlaylist = true }) { Text(s("desktop_import_playlist")) }
                                                }
                                                if (playlist != null) {
                                                    OutlinedButton(onClick = { rename = playlist }) { Text(s("desktop_rename")) }
                                                    TextButton(onClick = { delete = playlist }) { Text(s("desktop_delete")) }
                                                }
                                            }
                                            if (tracks.isEmpty() && !state.busy) Text(s(if (state.view == "library") "desktop_empty_library" else "desktop_empty"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 30.dp))
                                            LazyColumn(Modifier.fillMaxSize()) {
                                                itemsIndexed(tracks, key = { _, track -> "${track.kind}:${track.id}" }) { i, track ->
                                                    TrackRow(track, state.now?.id == track.id,
                                                        play = { if (track.kind == "song") state.playList(tracks.filter { it.kind == "song" }, tracks.take(i).count { it.kind == "song" }) else state.open(track) },
                                                        liked = track.id in state.data.likes,
                                                        like = { state.like(track) },
                                                        add = { addTrack = track },
                                                        enqueue = { state.enqueue(track) },
                                                        playNext = { state.enqueue(track, next = true) },
                                                        trailing = {
                                                            if (playlist != null) {
                                                                ControlButton(Symbol.Up, s("desktop_move_up"), enabled = i > 0) { val ids = playlist.tracks.toMutableList(); java.util.Collections.swap(ids, i, i - 1); state.persist(state.data.copy(playlists = state.data.playlists.map { if (it.id == playlist.id) it.copy(tracks = ids) else it })) }
                                                                ControlButton(Symbol.Down, s("desktop_move_down"), enabled = i < playlist.tracks.lastIndex) { val ids = playlist.tracks.toMutableList(); java.util.Collections.swap(ids, i, i + 1); state.persist(state.data.copy(playlists = state.data.playlists.map { if (it.id == playlist.id) it.copy(tracks = ids) else it })) }
                                                                ControlButton(Symbol.Close, s("desktop_remove")) { state.persist(state.data.copy(playlists = state.data.playlists.map { if (it.id == playlist.id) it.copy(tracks = it.tracks - track.id) else it })) }
                                                            }
                                                        },
                                                    )
                                                }
                                                if (state.view == "search" && state.resultsContinuation != null) item { OutlinedButton(onClick = state::loadMoreResults, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) { Text(s(if (state.loadingMore) "desktop_loading" else "desktop_load_more")) } }
                                            }
                                        }
                                    }
                                }
                            }
                            if (wide && state.panelVisible) SidePanel(state, { addTrack = it })
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
            if (!wide && state.panelVisible) Dialog(onDismissRequest = { state.panelVisible = false }) { Surface(Modifier.size(370.dp, 640.dp), shape = RoundedCornerShape(20.dp)) { SidePanel(state, { addTrack = it }) } }
            }
        }
    }
}

@Composable private fun SidebarButton(label: String, selected: Boolean, action: () -> Unit) {
    Surface(onClick = action, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(13.dp, 11.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun Home(state: DesktopState, add: (Track) -> Unit) {
    val recent = state.data.history.take(12).mapNotNull { id -> state.data.tracks.find { it.id == id } }
    var allMoods by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(state.homeFilter == null, onClick = { state.refreshHome(null) }, label = { Text(s("desktop_all")) }, enabled = !state.busy)
                state.homeFilters.forEach { filter -> FilterChip(state.homeFilter == filter, onClick = { state.refreshHome(filter) }, label = { Text(filter.title) }, enabled = !state.busy) }
                ControlButton(Symbol.Refresh, s("desktop_refresh"), enabled = !state.busy) { state.refreshHome() }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s("desktop_quick_picks"), Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = state::myMix, enabled = !state.busy) {
                    ControlIcon(Symbol.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(s("desktop_mix"))
                }
            }
        }
        if (state.homeFilter == null && state.quickPicks.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.quickPicks.chunked(3)) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        group.forEach { track ->
                            Box(Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)).clickable { state.radio(track) }) {
                                Cover(track.thumbnail, Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
                                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(9.dp)) {
                                    Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(track.artist, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else if (state.quickPicksBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (recent.isNotEmpty()) item { MusicShelf(Shelf(s("desktop_continue_listening"), recent), state, add, compactSongs = false) }
        if (state.moods.isNotEmpty() && state.homeFilter == null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s("desktop_moods"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                (if (allMoods) state.moods else state.moods.take(12)).chunked(2).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        group.forEach { mood -> Surface(onClick = { state.openMood(mood) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f)) { Text(mood.title, Modifier.padding(15.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        if (group.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (!allMoods && state.moods.size > 12) TextButton(onClick = { allMoods = true }) { Text(s("desktop_load_more")) }
            }
        }
        if (state.home.isEmpty() && !state.busy) item { OutlinedButton(onClick = { state.refreshHome() }) { Text(s("desktop_retry")) } }
        items(state.home, key = { it.title }) { shelf -> MusicShelf(shelf, state, add) }
        if (state.homeContinuation != null) item {
            OutlinedButton(onClick = state::loadMoreHome, enabled = !state.loadingMore && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(s(if (state.loadingMore) "desktop_loading" else "desktop_load_more")) }
        }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable private fun MusicShelf(shelf: Shelf, state: DesktopState, add: (Track) -> Unit, compactSongs: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shelf.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            if (shelf.tracks.any { it.kind == "song" }) TextButton(onClick = { state.playList(shelf.tracks) }) { Text(s("desktop_play_all")) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (compactSongs && shelf.tracks.all { it.kind == "song" }) {
                items(shelf.tracks.chunked(3)) { group ->
                    Column(Modifier.width(310.dp)) {
                        group.forEach { track ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { state.radio(track) }.padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(track.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TrackActions(track, { state.enqueue(track, true) }, { state.enqueue(track) }, { add(track) })
                            }
                        }
                    }
                }
            } else items(shelf.tracks, key = { "${it.kind}:${it.id}" }) { track ->
                Column(Modifier.width(156.dp)) {
                    Box(Modifier.clickable { state.open(track) }) {
                        Cover(track.thumbnail, Modifier.size(156.dp).clip(RoundedCornerShape(8.dp)))
                        Surface(Modifier.align(Alignment.BottomEnd).padding(9.dp).size(38.dp), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.78f)) {
                            Box(contentAlignment = Alignment.Center) { ControlIcon(Symbol.Play, null, Modifier.size(22.dp), Color.White) }
                        }
                    }
                    Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)
                    Text(track.artist.ifEmpty { track.kind }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable private fun TrackActions(track: Track, next: () -> Unit, enqueue: () -> Unit, add: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ControlButton(Symbol.Menu, s("desktop_more")) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(s("desktop_play_next")) }, onClick = { expanded = false; next() }, leadingIcon = { ControlIcon(Symbol.Next, null) })
            DropdownMenuItem(text = { Text(s("desktop_add_queue")) }, onClick = { expanded = false; enqueue() }, leadingIcon = { ControlIcon(Symbol.Queue, null) })
            DropdownMenuItem(text = { Text(s("desktop_add_playlist")) }, onClick = { expanded = false; add() }, leadingIcon = { ControlIcon(Symbol.Plus, null) })
        }
    }
}

@Composable private fun TrackRow(track: Track, current: Boolean, play: () -> Unit, liked: Boolean, like: () -> Unit, add: () -> Unit, enqueue: () -> Unit, playNext: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(onClick = play)) { Cover(track.thumbnail, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp))) }
        Column(Modifier.weight(1f).clickable(onClick = play).padding(vertical = 6.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (track.kind == "song") {
            Text(clock(track.duration.toDouble()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ControlButton(Symbol.Heart, s(if (liked) "desktop_unlike" else "desktop_like"), active = liked, action = like)
            TrackActions(track, playNext, enqueue, add)
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
            ControlButton(Symbol.Close, s("desktop_close_panel"), size = 32.dp) { state.panelVisible = false }
        }
        HorizontalDivider(Modifier.padding(bottom = 10.dp))
        if (state.panel == "queue") Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.queue.size} ${s("desktop_songs_count")}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = state::clearQueue, enabled = state.queue.isNotEmpty()) { Text(s("desktop_clear_queue"), style = MaterialTheme.typography.labelSmall) }
        }
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
                    ControlButton(Symbol.Minus, s("desktop_lyrics_earlier")) { state.lyricOffset -= 0.5f }
                    Text("${state.lyricOffset}s", fontSize = 11.sp)
                    ControlButton(Symbol.Plus, s("desktop_lyrics_later")) { state.lyricOffset += 0.5f }
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
                            ControlButton(Symbol.Up, s("desktop_move_up"), enabled = index > 0, size = 32.dp) { state.moveQueue(index, -1) }
                            ControlButton(Symbol.Down, s("desktop_move_down"), enabled = index < state.queue.lastIndex, size = 32.dp) { state.moveQueue(index, 1) }
                            ControlButton(Symbol.Close, s("desktop_remove"), size = 32.dp) { state.removeQueue(index) }
                        } else ControlButton(Symbol.Plus, s("desktop_add_queue"), size = 32.dp) { state.enqueue(track) }
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerBar(state: DesktopState) {
    val playbackStatus by state.player.status.collectAsState()
    val loading = state.preparing || playbackStatus == "loading"
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(0.32f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cover(state.now?.thumbnail.orEmpty(), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(state.now?.title ?: s("desktop_nothing_playing"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(state.now?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.now?.let { track -> ControlButton(Symbol.Heart, s(if (track.id in state.data.likes) "desktop_unlike" else "desktop_like"), active = track.id in state.data.likes) { state.like(track) } }
                }
                Row(Modifier.weight(0.43f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
                    ControlButton(Symbol.Shuffle, s("desktop_shuffle"), enabled = state.queue.size > 1, active = state.repeatShuffle) { state.shuffle() }
                    ControlButton(Symbol.Previous, s("desktop_previous"), enabled = state.now != null) { state.previous() }
                    Box(contentAlignment = Alignment.Center) {
                        ControlButton(if (playbackStatus == "playing") Symbol.Pause else Symbol.Play, s(if (playbackStatus == "playing") "desktop_pause" else "desktop_play"), enabled = !loading, filled = true, size = 52.dp) { state.togglePlayback() }
                        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    ControlButton(Symbol.Next, s("desktop_next"), enabled = state.canNext) { state.next() }
                    ControlButton(if (state.repeat == 2) Symbol.RepeatOne else Symbol.Repeat, s(when (state.repeat) { 1 -> "desktop_repeat_all"; 2 -> "desktop_repeat_one"; else -> "desktop_repeat_off" }), active = state.repeat > 0) { state.repeat = (state.repeat + 1) % 3 }
                }
                Row(Modifier.weight(0.25f), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(if (state.volume == 0f) Symbol.Muted else Symbol.Volume, s(if (state.volume == 0f) "desktop_unmute" else "desktop_mute")) { state.toggleMute() }
                    PlayerSlider(state.volume, state::setPlayerVolume, { state.persist(state.data.copy(volume = state.volume)) }, 0f..1f, Modifier.weight(1f), s("desktop_volume"))
                    ControlButton(Symbol.Queue, s("desktop_toggle_panel"), active = state.panelVisible) { state.panelVisible = !state.panelVisible }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var drag by remember(state.now?.id) { mutableStateOf<Float?>(null) }
                val duration = (state.now?.duration ?: 0).coerceAtLeast(1).toFloat()
                Text(clock((drag ?: state.position.toFloat()).toDouble()), fontSize = 11.sp, modifier = Modifier.width(40.dp))
                PlayerSlider(drag ?: state.position.toFloat().coerceIn(0f, duration), { drag = it }, { drag?.let { state.seek(it.toDouble()) }; drag = null }, 0f..duration, Modifier.weight(1f), s("desktop_seek"), enabled = state.now != null && (state.now?.duration ?: 0) > 0 && !state.preparing)
                Text(clock((state.now?.duration ?: 0).toDouble()), fontSize = 11.sp, modifier = Modifier.width(40.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerSlider(value: Float, change: (Float) -> Unit, finish: () -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier, description: String, enabled: Boolean = true) {
    Slider(value, change, onValueChangeFinished = finish, valueRange = range, enabled = enabled,
        modifier = modifier.height(36.dp).semantics { contentDescription = description },
        thumb = { Box(Modifier.size(4.dp, 16.dp).background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp))) },
        track = { SliderDefaults.Track(sliderState = it, modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) })
}

@Composable private fun Settings(state: DesktopState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(s("desktop_settings"), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(s("desktop_about"), color = MaterialTheme.colorScheme.primary)
        Text(s("desktop_shortcuts"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
