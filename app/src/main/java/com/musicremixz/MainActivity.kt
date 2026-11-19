package com.musicremixz

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

// ---------- theme ----------
private val BG = Color(0xFF121212)
private val CARD = Color(0xFF1E1E1E)
private val ELEV = Color(0xFF2A2A2A)
private val GREEN = Color(0xFF1DB954)
private val GRAY = Color(0xFFB3B3B3)
private val WAVE_A = Color(0xFF1DB954)
private val WAVE_B = Color(0xFF8C9EFF)
private val LINE_A = Color(0xFF4FC3F7)
private val LINE_B = Color.White

// ---------- search (Deezer, falls back to iTunes) ----------
class Hit(val title: String, val artist: String, val preview: String, val cover: String)

private fun open(u: String): HttpURLConnection {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 15000
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MusicRemixz")
    return c
}

private fun get(u: String): String = open(u).inputStream.bufferedReader().use { it.readText() }

suspend fun search(q: String): List<Hit> = withContext(Dispatchers.IO) {
    val e = URLEncoder.encode(q, "UTF-8")
    val out = mutableListOf<Hit>()
    try {
        val arr = JSONObject(get("https://api.deezer.com/search?limit=25&q=$e")).optJSONArray("data")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Hit(
                o.optString("title"), o.optJSONObject("artist")?.optString("name") ?: "",
                o.optString("preview"), o.optJSONObject("album")?.optString("cover_medium") ?: "",
            )
        }
    } catch (ex: Exception) { /* fall through to iTunes */ }
    if (out.none { it.preview.isNotEmpty() }) {
        out.clear()
        val arr = JSONObject(get("https://itunes.apple.com/search?media=music&entity=song&limit=25&term=$e")).getJSONArray("results")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Hit(
                o.optString("trackName"), o.optString("artistName"), o.optString("previewUrl"),
                o.optString("artworkUrl100").replace("100x100", "300x300"),
            )
        }
    }
    out.filter { it.preview.isNotEmpty() }
}

// ---------- crossfade envelopes ----------
// Each line is a list of (progress 0..1, gain 0..1) points, linearly interpolated. First/last x are pinned to 0 and 1.
fun gainAt(pts: List<Offset>, p: Double): Double {
    if (p <= pts.first().x) return pts.first().y.toDouble()
    for (i in 1 until pts.size) {
        if (p <= pts[i].x) {
            val a = pts[i - 1]
            val b = pts[i]
            val f = if (b.x == a.x) 1.0 else (p - a.x) / (b.x - a.x)
            return a.y + (b.y - a.y) * f
        }
    }
    return pts.last().y.toDouble()
}

class Geom(val lead: Double, val ov: Double, val total: Double, val rate: Double, val aStart: Double)

class Mixer(private val dir: File) {
    var a by mutableStateOf<Track?>(null)
    var b by mutableStateOf<Track?>(null)
    var bars by mutableStateOf(8)
    var curve by mutableStateOf("Fade")
    var ptsA by mutableStateOf(listOf(Offset(0f, 1f), Offset(0.5f, 0.7f), Offset(1f, 0f)))
    var ptsB by mutableStateOf(listOf(Offset(0f, 0f), Offset(0.5f, 0.7f), Offset(1f, 1f)))
    var playing by mutableStateOf(false)
    var pos by mutableStateOf(0.0)
    var status by mutableStateOf("")
    private var job: Job? = null
    private var gen = 0

    fun preset(name: String) {
        curve = name
        when (name) {
            "Rise" -> {
                ptsA = listOf(Offset(0f, 1f), Offset(0.7f, 1f), Offset(1f, 0f))
                ptsB = listOf(Offset(0f, 0f), Offset(0.7f, 1f), Offset(1f, 1f))
            }
            "Cut" -> {
                ptsA = listOf(Offset(0f, 1f), Offset(0.49f, 1f), Offset(0.51f, 0f), Offset(1f, 0f))
                ptsB = listOf(Offset(0f, 0f), Offset(0.49f, 0f), Offset(0.51f, 1f), Offset(1f, 1f))
            }
            else -> {
                ptsA = listOf(Offset(0f, 1f), Offset(0.5f, 0.7f), Offset(1f, 0f))
                ptsB = listOf(Offset(0f, 0f), Offset(0.5f, 0.7f), Offset(1f, 1f))
            }
        }
    }

    fun geom(): Geom? {
        val x = a ?: return null
        val y = b ?: return null
        val ov = min(bars * 4 * 60.0 / x.bpm, x.dur * 0.9)
        val lead = min(4.0, x.dur - ov)
        var r = x.bpm / y.bpm
        r = listOf(r, r * 2, r / 2).minBy { abs(it - 1) }
        if (abs(r - 1) > 0.12) r = 1.0
        return Geom(lead, ov, lead + ov + 4.0, r, x.dur - ov - lead)
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try { block(); status = "" } catch (e: CancellationException) { throw e } catch (e: Exception) { status = "Couldn't load: ${e.message}" }
    }

    private suspend fun set(slot: Int, title: String, artist: String, cover: String, f: File) {
        status = "Analyzing tempo & key…"
        val t = withContext(Dispatchers.Default) {
            val pcm = decodeMono(f)
            Track(title, artist, cover, f, pcm, detectBpm(pcm), detectCamelot(pcm))
        }
        stop()
        if (slot == 0) a = t else b = t
    }

    suspend fun loadHit(slot: Int, h: Hit) = guard {
        status = "Downloading…"
        val f = File(dir, "p${h.preview.hashCode()}")
        withContext(Dispatchers.IO) {
            if (!f.exists()) open(h.preview).inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        set(slot, h.title, h.artist, h.cover, f)
    }

    suspend fun loadUri(ctx: Context, slot: Int, uri: Uri) = guard {
        status = "Loading…"
        val f = File(dir, "d${System.nanoTime()}")
        val name = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            ctx.contentResolver.query(uri, null, null, null, null)?.use {
                val c = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (it.moveToFirst() && c >= 0) it.getString(c) else null
            } ?: "Local track"
        }
        set(slot, name.substringBeforeLast('.'), "On device", "", f)
    }

    fun stop() {
        gen++
        job?.cancel()
        playing = false
        pos = 0.0
    }

    // Plays the transition: A's tail, crossfade into B (time-stretched to A's tempo). Envelope edits apply live.
    fun play(scope: CoroutineScope) {
        stop()
        val g = geom() ?: return
        val ta = a!!
        val tb = b!!
        val my = gen
        playing = true
        job = scope.launch {
            var x: MediaPlayer? = null
            var y: MediaPlayer? = null
            try {
                val pair = withContext(Dispatchers.IO) {
                    val p = MediaPlayer().apply { setDataSource(ta.file.path); prepare() }
                    val q = MediaPlayer().apply { setDataSource(tb.file.path); prepare() }
                    p to q
                }
                x = pair.first
                y = pair.second
                x.seekTo((g.aStart * 1000).toInt())
                x.start()
                y.setVolume(0f, 0f)
                val t0 = SystemClock.elapsedRealtime()
                var started = false
                while (true) {
                    val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    if (t >= g.total) break
                    pos = t
                    if (!started && t >= g.lead) {
                        y.start()
                        y.playbackParams = PlaybackParams().setSpeed(g.rate.toFloat())
                        started = true
                    }
                    val p = ((t - g.lead) / g.ov).coerceIn(0.0, 1.0)
                    val va = if (t < g.lead) 1.0 else gainAt(ptsA, p)
                    val vb = if (t < g.lead) 0.0 else gainAt(ptsB, p)
                    x.setVolume(va.toFloat(), va.toFloat())
                    y.setVolume(vb.toFloat(), vb.toFloat())
                    delay(25)
                }
            } finally {
                x?.release()
                y?.release()
                if (my == gen) { playing = false; pos = 0.0 }
            }
        }
    }
}

fun keyNote(a: String, b: String): Pair<String, Boolean> {
    val na = a.dropLast(1).toInt()
    val nb = b.dropLast(1).toInt()
    val raw = ((na - nb) % 12 + 12) % 12
    val d = min(raw, 12 - raw)
    val same = a.last() == b.last()
    return when {
        same && d == 0 -> "Same key" to true
        same && d == 1 -> "Harmonic match" to true
        !same && d == 0 -> "Relative keys, works" to true
        else -> "Keys clash, try another song" to false
    }
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = GREEN, background = BG, surface = CARD)) { App() }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = remember { Mixer(ctx.cacheDir) }
    var pick by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) { onDispose { m.stop() } }
    BackHandler(enabled = pick != null) { pick = null }
    val dev = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val s = pick
        pick = null
        if (uri != null && s != null) scope.launch { m.loadUri(ctx, s, uri) }
    }
    val slot = pick
    Box(Modifier.fillMaxSize().background(BG)) {
        if (slot != null) {
            Picker(
                slot = slot,
                onClose = { pick = null },
                onHit = { h -> pick = null; scope.launch { m.loadHit(slot, h) } },
                onDevice = { dev.launch(arrayOf("audio/*")) },
            )
        } else {
            Main(m, scope) { m.stop(); pick = it }
        }
    }
}

@Composable
fun Main(m: Mixer, scope: CoroutineScope, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Mix", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        TrackRow("A", m.a) { onPick(0) }
        Timeline(m)
        Text("Drag the dots · double-tap a line to add a point · long-press a dot to remove", color = GRAY, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in listOf(4, 8, 16)) Pill("$n bars", m.bars == n) { m.bars = n; m.stop() }
            }
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(GREEN).clickable { if (m.playing) m.stop() else m.play(scope) },
                contentAlignment = Alignment.Center,
            ) { Text(if (m.playing) "■" else "▶", color = Color.Black, fontSize = 24.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in listOf("Custom", "Fade", "Rise", "Cut")) Pill(c, m.curve == c) { m.preset(c) }
        }
        TrackRow("B", m.b) { onPick(1) }
        val a = m.a
        val b = m.b
        val g = m.geom()
        if (a != null && b != null && g != null) {
            val (txt, ok) = keyNote(a.camelot, b.camelot)
            Text(txt, color = if (ok) GREEN else Color(0xFFFF9F0A), fontWeight = FontWeight.SemiBold)
            val tempo = if (g.rate == 1.0 && abs(a.bpm - b.bpm) > 1) "Tempos are far apart, no stretch applied" else "B is stretched to ${"%.0f".format(a.bpm)} bpm"
            Text(tempo, color = GRAY, fontSize = 13.sp)
        }
        if (m.status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!m.status.startsWith("Couldn't")) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = GREEN)
            Text(m.status, color = Color.White)
        }
    }
}

@Composable
fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) GREEN else ELEV).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Text(text, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
fun Cover(url: String, size: Dp, seed: Int) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
        value = if (url.isEmpty()) null else withContext(Dispatchers.IO) {
            try { open(url).inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap() } catch (e: Exception) { null }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Color.hsv((seed % 12) * 30f, 0.5f, 0.45f))) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun TrackRow(label: String, t: Track?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (t == null) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(ELEV), contentAlignment = Alignment.Center) { Text("+", color = GREEN, fontSize = 26.sp) }
            Text("Choose song $label", color = Color.White, fontWeight = FontWeight.SemiBold)
        } else {
            Cover(t.cover, 52.dp, t.title.hashCode())
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(t.artist, color = GRAY, fontSize = 13.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${"%.0f".format(t.bpm)} bpm", color = Color.White, fontSize = 13.sp)
                val hue = (t.camelot.dropLast(1).toInt() - 1) * 30f
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.hsv(hue, 0.45f, 0.95f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(t.camelot, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Pixel layout shared by drawing and touch handling.
private class Lay(val g: Geom, val w: Float, val h: Float) {
    val cyA = h * 0.27f
    val cyB = h * 0.73f
    val amp = h * 0.22f
    fun x(t: Double) = (t / g.total * w).toFloat()
    fun px(p: Float) = x(g.lead + g.ov * p)
    fun y(row: Int, v: Float) = (if (row == 0) cyA else cyB) + amp - 2 * amp * v
    fun fromX(xx: Float) = ((xx / w * g.total - g.lead) / g.ov).toFloat().coerceIn(0f, 1f)
    fun fromY(row: Int, yy: Float) = (((if (row == 0) cyA else cyB) + amp - yy) / (2 * amp)).coerceIn(0f, 1f)
}

@Composable
fun Timeline(m: Mixer) {
    val a = m.a
    val b = m.b
    val g = m.geom()
    val gs = rememberUpdatedState(g)
    val pk = remember(a, b, m.bars) {
        if (g == null || a == null || b == null) null
        else peaks(a.pcm, g.aStart, g.aStart + g.total, 240) to peaks(b.pcm, -g.lead * g.rate, (g.total - g.lead) * g.rate, 240)
    }
    fun pts(row: Int) = if (row == 0) m.ptsA else m.ptsB
    fun setPts(row: Int, l: List<Offset>) { if (row == 0) m.ptsA = l else m.ptsB = l; m.curve = "Custom" }

    // Nearest handle within `reach` px: (row, index) or null.
    fun nearest(l: Lay, o: Offset, reach: Float): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bd = reach
        for (row in 0..1) pts(row).forEachIndexed { i, p ->
            val d = hypot(l.px(p.x) - o.x, l.y(row, p.y) - o.y)
            if (d < bd) { bd = d; best = row to i }
        }
        return best
    }

    Canvas(
        Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).background(CARD)
            .pointerInput(Unit) {
                var drag: Pair<Int, Int>? = null
                detectDragGestures(
                    onDragStart = { o ->
                        val gg = gs.value
                        drag = if (gg == null) null else nearest(Lay(gg, size.width.toFloat(), size.height.toFloat()), o, 56.dp.toPx())
                    },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    val gg = gs.value
                    val d = drag
                    if (gg != null && d != null) {
                        change.consume()
                        val l = Lay(gg, size.width.toFloat(), size.height.toFloat())
                        val (row, i) = d
                        val list = pts(row).toMutableList()
                        val x = when (i) {
                            0 -> 0f
                            list.lastIndex -> 1f
                            else -> l.fromX(change.position.x).coerceIn(list[i - 1].x + 0.02f, list[i + 1].x - 0.02f)
                        }
                        list[i] = Offset(x, l.fromY(row, change.position.y))
                        setPts(row, list)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { o ->
                        val gg = gs.value
                        if (gg != null) {
                            val l = Lay(gg, size.width.toFloat(), size.height.toFloat())
                            val row = if (o.y < size.height / 2f) 0 else 1
                            val p = l.fromX(o.x)
                            val list = pts(row).toMutableList()
                            if (p > 0.03f && p < 0.97f) {
                                val at = list.indexOfFirst { it.x > p }
                                list.add(at, Offset(p, gainAt(list, p.toDouble()).toFloat()))
                                setPts(row, list)
                            }
                        }
                    },
                    onLongPress = { o ->
                        val gg = gs.value
                        if (gg != null) {
                            val n = nearest(Lay(gg, size.width.toFloat(), size.height.toFloat()), o, 56.dp.toPx())
                            if (n != null) {
                                val list = pts(n.first).toMutableList()
                                if (n.second in 1 until list.lastIndex) { list.removeAt(n.second); setPts(n.first, list) }
                            }
                        }
                    },
                )
            },
    ) {
        if (g == null || pk == null) return@Canvas
        val l = Lay(g, size.width, size.height)
        drawRect(Color(0x1AFFFFFF), Offset(l.x(g.lead), 0f), Size(l.x(g.lead + g.ov) - l.x(g.lead), size.height))
        val mxA = maxOf(pk.first.max(), 1e-6f)
        val mxB = maxOf(pk.second.max(), 1e-6f)
        val n = pk.first.size
        val dx = size.width / n
        for (i in 0 until n) {
            val xx = (i + 0.5f) * dx
            val ha = pk.first[i] / mxA * l.amp
            val hb = pk.second[i] / mxB * l.amp
            drawLine(WAVE_A, Offset(xx, l.cyA - ha), Offset(xx, l.cyA + ha), strokeWidth = dx * 0.7f, cap = StrokeCap.Round)
            drawLine(WAVE_B, Offset(xx, l.cyB - hb), Offset(xx, l.cyB + hb), strokeWidth = dx * 0.7f, cap = StrokeCap.Round)
        }
        for (row in 0..1) {
            val p = pts(row)
            val col = if (row == 0) LINE_A else LINE_B
            val path = Path()
            path.moveTo(0f, l.y(row, p.first().y))
            for (pt in p) path.lineTo(l.px(pt.x), l.y(row, pt.y))
            path.lineTo(size.width, l.y(row, p.last().y))
            drawPath(path, col, style = Stroke(width = 5f, cap = StrokeCap.Round))
            for (pt in p) {
                val c = Offset(l.px(pt.x), l.y(row, pt.y))
                drawCircle(Color.Black, 15f, c)
                drawCircle(col, 11f, c)
            }
        }
        drawLine(GREEN, Offset(l.x(m.pos), 0f), Offset(l.x(m.pos), size.height), strokeWidth = 5f)
    }
}

@Composable
fun Picker(slot: Int, onClose: () -> Unit, onHit: (Hit) -> Unit, onDevice: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    // Search as you type (debounced).
    LaunchedEffect(q) {
        if (q.isBlank()) { hits = emptyList(); err = ""; return@LaunchedEffect }
        delay(450)
        busy = true
        err = ""
        try {
            hits = search(q)
            if (hits.isEmpty()) err = "No results"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = "Search failed: ${e.message}"
        }
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pick song ${"AB"[slot]}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Pill("Close", false, onClose)
        }
        TextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
            shape = RoundedCornerShape(50),
            placeholder = { Text("Search songs or artists", color = GRAY) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ELEV, unfocusedContainerColor = ELEV,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = GREEN,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Pick from device (full song)", false, onDevice)
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = GREEN)
        }
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF9F0A))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(hits) { h ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onHit(h) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Cover(h.cover, 52.dp, h.title.hashCode())
                    Column {
                        Text(h.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(h.artist, color = GRAY, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
