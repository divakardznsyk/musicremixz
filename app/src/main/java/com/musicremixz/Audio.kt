package com.musicremixz

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

const val SR = 11025 // analysis/waveform sample rate (mono)

class Track(val title: String, val artist: String, val cover: String, val file: File, val pcm: FloatArray, val bpm: Double, val camelot: String) {
    val dur get() = pcm.size.toDouble() / SR
}

// Decode any Android-supported audio file to mono float at ~SR. shortcut: capped at 6 min, box-filter downsample.
fun decodeMono(file: File, maxSec: Int = 360): FloatArray {
    val ex = MediaExtractor()
    ex.setDataSource(file.absolutePath)
    val idx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
    ex.selectTrack(idx)
    val fmt = ex.getTrackFormat(idx)
    val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(fmt, null, null, 0)
    codec.start()
    var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var out = FloatArray(SR * 40)
    var n = 0
    var acc = 0f
    var cnt = 0
    var t = 0.0
    var emitAt = rate.toDouble() / SR
    val info = MediaCodec.BufferInfo()
    var inDone = false
    var outDone = false
    try {
        while (!outDone && n < SR * maxSec) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val sz = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (o >= 0) {
                val bb = codec.getOutputBuffer(o)!!
                bb.position(info.offset)
                bb.limit(info.offset + info.size)
                val sb = bb.order(ByteOrder.nativeOrder()).asShortBuffer()
                val step = rate.toDouble() / SR
                while (sb.remaining() >= ch) {
                    var v = 0f
                    for (c in 0 until ch) v += sb.get()
                    acc += v / ch / 32768f
                    cnt++
                    t += 1.0
                    if (t >= emitAt) {
                        if (n >= out.size) out = out.copyOf(out.size * 2)
                        out[n++] = acc / cnt
                        acc = 0f
                        cnt = 0
                        emitAt += step
                    }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
    } finally {
        codec.stop(); codec.release(); ex.release()
    }
    require(n > SR * 8) { "song too short to analyze" }
    return out.copyOf(n)
}

// Tempo via onset-envelope autocorrelation on the middle minute, folded into 80..175.
fun detectBpm(x: FloatArray): Double {
    val hop = 128
    val from = (x.size / 2 - SR * 30).coerceAtLeast(0)
    val m = min(x.size - from, SR * 60) / hop
    val env = DoubleArray(m) { i ->
        var s = 0.0
        val o = from + i * hop
        for (j in 0 until hop) s += x[o + j] * x[o + j]
        sqrt(s / hop)
    }
    val on = DoubleArray(m) { if (it == 0) 0.0 else max(0.0, env[it] - env[it - 1]) }
    val mean = on.average()
    for (i in on.indices) on[i] -= mean
    val fps = SR.toDouble() / hop
    val maxLag = (fps * 60 / 70 * 3).toInt() + 2
    val ac = DoubleArray(maxLag + 1) { l ->
        var s = 0.0
        for (i in 0 until m - l) s += on[i] * on[i + l]
        s / (m - l)
    }
    fun at(l: Double): Double {
        val i = l.toInt()
        if (i + 1 >= ac.size) return 0.0
        val f = l - i
        return ac[i] * (1 - f) + ac[i + 1] * f
    }
    var best = 120.0
    var bs = -1e18
    var bpm = 70.0
    while (bpm <= 180.0) {
        val l = fps * 60 / bpm
        val s = at(l) + 0.5 * at(2 * l) + 0.25 * at(3 * l)
        if (s > bs) { bs = s; best = bpm }
        bpm += 0.5
    }
    while (best < 80) best *= 2
    while (best > 175) best /= 2
    return best
}

private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

private fun corr(a: DoubleArray, b: DoubleArray): Double {
    val ma = a.average()
    val mb = b.average()
    var num = 0.0
    var da = 0.0
    var db = 0.0
    for (i in a.indices) {
        num += (a[i] - ma) * (b[i] - mb)
        da += (a[i] - ma) * (a[i] - ma)
        db += (b[i] - mb) * (b[i] - mb)
    }
    return num / (sqrt(da * db) + 1e-12)
}

// Key via Goertzel chroma (C3..B6) + Krumhansl profiles, returned as a Camelot code like "8A".
fun detectCamelot(x: FloatArray): String {
    val n = 8192
    val from = (x.size / 2 - SR * 20).coerceAtLeast(0)
    val to = min(x.size, from + SR * 40)
    val hann = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
    val chroma = DoubleArray(12)
    var s = from
    while (s + n <= to) {
        for (midi in 48..95) {
            val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val coef = 2 * cos(2 * PI * f / SR)
            var s1 = 0.0
            var s2 = 0.0
            for (j in 0 until n) {
                val s0 = x[s + j] * hann[j] + coef * s1 - s2
                s2 = s1
                s1 = s0
            }
            chroma[midi % 12] += sqrt(max(0.0, s1 * s1 + s2 * s2 - coef * s1 * s2))
        }
        s += n * 2
    }
    var bestScore = -2.0
    var bestPc = 0
    var minor = false
    for (t in 0 until 12) {
        val rot = DoubleArray(12) { chroma[(t + it) % 12] }
        val cMaj = corr(rot, MAJOR)
        val cMin = corr(rot, MINOR)
        if (cMaj > bestScore) { bestScore = cMaj; bestPc = t; minor = false }
        if (cMin > bestScore) { bestScore = cMin; bestPc = t; minor = true }
    }
    val major = if (minor) (bestPc + 3) % 12 else bestPc // relative major shares the Camelot number
    val num = (8 + 7 * major - 1) % 12 + 1
    return "$num${if (minor) "A" else "B"}"
}

fun peaks(x: FloatArray, fromSec: Double, toSec: Double, n: Int): FloatArray = FloatArray(n) { i ->
    val s = ((fromSec + (toSec - fromSec) * i / n) * SR).toInt()
    val e = ((fromSec + (toSec - fromSec) * (i + 1) / n) * SR).toInt()
    var mx = 0f
    for (k in max(s, 0) until min(e, x.size)) mx = max(mx, abs(x[k]))
    mx
}
