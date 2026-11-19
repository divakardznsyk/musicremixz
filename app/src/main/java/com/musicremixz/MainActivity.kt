package com.musicremixz

import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Hit(val title: String, val artist: String, val preview: String)

class Geom(val lead: Double, val ov: Double, val total: Double, val rate: Double, val aStart: Double)

// Gains (A, B) at crossfade progress p in 0..1.
fun gains(curve: String, p: Double): Pair<Double, Double> = when (curve) {
    "Rise" -> (if (p < 0.7) 1.0 else (1 - p) / 0.3) to min(1.0, p / 0.7)
    "Cut" -> (if (p < 0.5) 1.0 else 0.0) to (if (p < 0.5) 0.0 else 1.0)
    else -> cos(p * Math.PI / 2) to sin(p * Math.PI / 2)
}

// Deezer's public search API gives 30s previews, no key. shortcut: previews only, use "From device" for full songs.
suspend fun search(q: String): List<Hit> = withContext(Dispatchers.IO) {
    val js = JSONObject(URL("https://api.deezer.com/search?limit=25&q=" + URLEncoder.encode(q, "UTF-8")).readText())
    val arr = js.getJSONArray("data")
    List(arr.length()) {
        val o = arr.getJSONObject(it)
        Hit(o.getString("title"), o.getJSONObject("artist").getString("name"), o.optString("preview"))
    }.filter { it.preview.isNotEmpty() }
}

class Mixer(private val dir: File) {
    var a by mutableStateOf<Track?>(null)
    var b by mutableStateOf<Track?>(null)
    var bars by mutableStateOf(8)
    var curve by mutableStateOf("Fade")
    var playing by mutableStateOf(false)
    var pos by mutableStateOf(0.0)
    var status by mutableStateOf("")
    private var job: Job? = null
    private var gen = 0

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
        try { block(); status = "" } catch (e: Exception) { status = "Couldn't load: ${e.message}" }
    }

    private suspend fun set(slot: Int, title: String, artist: String, f: File) {
        status = "Analyzing tempo & key…"
        val t = withContext(Dispatchers.Default) {
            val pcm = decodeMono(f)
            Track(title, artist, f, pcm, detectBpm(pcm), detectCamelot(pcm))
        }
        stop()
        if (slot == 0) a = t else b = t
    }

    suspend fun loadHit(slot: Int, h: Hit) = guard {
        status = "Downloading…"
        val f = File(dir, "p${h.preview.hashCode()}.mp3")
        withContext(Dispatchers.IO) {
            if (!f.exists()) URL(h.preview).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        set(slot, h.title, h.artist, f)
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
        set(slot, name.substringBeforeLast('.'), "On device", f)
    }

    fun stop() {
        gen++
        job?.cancel()
        playing = false
        pos = 0.0
    }

    // Plays the transition: A's tail, crossfade into B (tempo-matched via time-stretch).
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
                    val (va, vb) = if (t < g.lead) 1.0 to 0.0 else gains(curve, ((t - g.lead) / g.ov).coerceIn(0.0, 1.0))
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

private val BG = Color(0xFF111114)
private val CARD = Color(0xFF1B1B1F)

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
        Text("Mix", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        TrackRow("A", m.a) { onPick(0) }
        Timeline(m)
        val g = m.geom()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (m.playing) "■ Stop" else "▶ Play mix", true) { if (m.playing) m.stop() else m.play(scope) }
            Spacer(Modifier.weight(1f))
            for (n in listOf(4, 8, 16)) Pill("$n bars", m.bars == n) { m.bars = n; m.stop() }
        }
        TrackRow("B", m.b) { onPick(1) }
        val a = m.a
        val b = m.b
        if (a != null && b != null && g != null) {
            val (txt, ok) = keyNote(a.camelot, b.camelot)
            Text(txt, color = if (ok) Color(0xFF30D158) else Color(0xFFFF9F0A), fontWeight = FontWeight.Medium)
            val tempo = if (g.rate == 1.0 && abs(a.bpm - b.bpm) > 1) "Tempos are far apart, no stretch applied" else "B is stretched to ${"%.0f".format(a.bpm)} bpm"
            Text(tempo, color = Color(0xFF9A9AA0), fontSize = 13.sp)
        }
        Text("Presets", color = Color(0xFF9A9AA0), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf("Fade", "Rise", "Cut")) Pill(c, m.curve == c) { m.curve = c }
        }
        if (m.status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!m.status.startsWith("Couldn't")) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(m.status, color = Color.White)
        }
    }
}

@Composable
fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color.White else Color(0xFF2A2A2E)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) { Text(text, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Medium) }
}

@Composable
fun TrackRow(label: String, t: Track?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t == null) {
            Text("Tap to choose song $label", color = Color(0xFF9A9AA0))
        } else {
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(t.artist, color = Color(0xFF9A9AA0), fontSize = 13.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.0f".format(t.bpm)} bpm", color = Color.White, fontSize = 13.sp)
                val hue = (t.camelot.dropLast(1).toInt() - 1) * 30f
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.hsv(hue, 0.45f, 0.95f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(t.camelot, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun Timeline(m: Mixer) {
    val a = m.a
    val b = m.b
    val g = m.geom()
    val pk = remember(a, b, m.bars) {
        if (g == null || a == null || b == null) null
        else peaks(a.pcm, g.aStart, g.aStart + g.total, 240) to peaks(b.pcm, -g.lead * g.rate, (g.total - g.lead) * g.rate, 240)
    }
    Canvas(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(16.dp)).background(CARD)) {
        if (g == null || pk == null) return@Canvas
        val w = size.width
        val h = size.height
        fun x(t: Double) = (t / g.total * w).toFloat()
        drawRect(Color(0x22FFB74D), Offset(x(g.lead), 0f), androidx.compose.ui.geometry.Size(x(g.lead + g.ov) - x(g.lead), h))
        val cyA = h * 0.27f
        val cyB = h * 0.73f
        val amp = h * 0.22f
        val mxA = max(pk.first.max(), 1e-6f)
        val mxB = max(pk.second.max(), 1e-6f)
        val n = pk.first.size
        val dx = w / n
        for (i in 0 until n) {
            val xx = (i + 0.5f) * dx
            val pa = pk.first[i] / mxA * amp
            val pb = pk.second[i] / mxB * amp
            drawLine(Color(0xFFE8913A), Offset(xx, cyA - pa), Offset(xx, cyA + pa), strokeWidth = dx * 0.7f, cap = StrokeCap.Round)
            drawLine(Color(0xFF4C6EF5), Offset(xx, cyB - pb), Offset(xx, cyB + pb), strokeWidth = dx * 0.7f, cap = StrokeCap.Round)
        }
        val pa = Path()
        val pb = Path()
        fun yA(v: Double) = (cyA + amp - 2 * amp * v).toFloat()
        fun yB(v: Double) = (cyB + amp - 2 * amp * v).toFloat()
        pa.moveTo(0f, yA(1.0)); pa.lineTo(x(g.lead), yA(1.0))
        pb.moveTo(0f, yB(0.0)); pb.lineTo(x(g.lead), yB(0.0))
        for (s in 0..40) {
            val p = s / 40.0
            val (va, vb) = gains(m.curve, p)
            pa.lineTo(x(g.lead + g.ov * p), yA(va))
            pb.lineTo(x(g.lead + g.ov * p), yB(vb))
        }
        pa.lineTo(w, yA(0.0))
        pb.lineTo(w, yB(1.0))
        drawPath(pa, Color(0xFF4FC3F7), style = Stroke(width = 4f))
        drawPath(pb, Color.White, style = Stroke(width = 4f))
        drawLine(Color(0xFF30D158), Offset(x(m.pos), 0f), Offset(x(m.pos), h), strokeWidth = 5f)
    }
}

@Composable
fun Picker(slot: Int, onClose: () -> Unit, onHit: (Hit) -> Unit, onDevice: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    fun go() {
        if (q.isBlank()) return
        scope.launch {
            busy = true
            err = ""
            try { hits = search(q) } catch (e: Exception) { err = "Search failed: ${e.message}" }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Song ${"AB"[slot]}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search any song") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { go() }) { Text("Search") }
            TextButton(onClick = onDevice) { Text("From device (full song)") }
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF9F0A))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(hits) { h ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).clickable { onHit(h) }.padding(12.dp)) {
                    Text(h.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(h.artist, color = Color(0xFF9A9AA0), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}
