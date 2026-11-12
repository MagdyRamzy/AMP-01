package com.example.headunit

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Winamp-style visualizers. Fed by android.media.audiofx.Visualizer; falls back to a demo animation. */
class VisualizerView(ctx: Context) : View(ctx) {
    companion object {
        val NAMES = listOf("Spectrum", "LED stack", "Oscilloscope", "Mirror", "Radial", "Mountain", "Dot matrix", "Rings", "VU meters")
        const val N = 32
    }

    var accent = Color.MAGENTA
    var mode = 0
    @Volatile var simulated = true

    private val bands = FloatArray(N)
    private val peaks = FloatArray(N)
    private val wave = FloatArray(128)
    private var vuL = 0f
    private var vuR = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val path2 = Path()
    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }
    private fun themeAt(t: Float) = mix(accent, mix(accent, Color.WHITE, 0.6f), t)
    private fun classicAt(t: Float) =
        if (t < 0.6f) mix(Color.rgb(0, 220, 60), Color.rgb(255, 220, 0), t / 0.6f)
        else mix(Color.rgb(255, 220, 0), Color.rgb(255, 40, 30), (t - 0.6f) / 0.4f)
    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun avg(a: Int, b: Int): Float { var s = 0f; for (i in a until b) s += bands[i]; return s / (b - a) }

    // ------------------------------------------------------------ data in
    fun feedFft(fft: ByteArray) {
        val n = fft.size / 2
        for (b in 0 until N) {
            val lo = max(1, n.toDouble().pow(b / N.toDouble()).toInt())
            val hi = max(lo + 1, n.toDouble().pow((b + 1) / N.toDouble()).toInt())
            var m = 0f
            for (k in lo until min(hi, n)) m = max(m, hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
            val v = ((log10(1.0 + m) / 2.1).toFloat() * (0.75f + 0.5f * b / N)).coerceIn(0f, 1f)
            bands[b] = max(v, bands[b] * 0.82f)
        }
    }

    fun feedWave(w: ByteArray) {
        for (i in wave.indices) wave[i] = ((w[i * w.size / wave.size].toInt() and 0xFF) - 128) / 128f
    }

    private fun simulate() {
        val t = System.currentTimeMillis() / 1000.0
        for (b in 0 until N) {
            val v = (0.5 + 0.5 * sin(t * (1.6 + b * 0.23) + b * 0.9)) * (0.9 - b * 0.018) * (0.65 + 0.35 * sin(t * 1.7 + b * 0.1))
            bands[b] = v.toFloat().coerceIn(0f, 1f)
        }
        val amp = 0.25f + bands[1] * 0.6f
        for (i in wave.indices) wave[i] = (amp * sin(i * 0.22 + t * 6) * (0.6 + 0.4 * sin(i * 0.07 + t * 2))).toFloat()
    }

    // ------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (simulated) simulate()
        for (i in 0 until N) peaks[i] = max(bands[i], peaks[i] - 0.012f)
        p.style = Paint.Style.FILL; p.shader = null; p.strokeCap = Paint.Cap.BUTT; p.strokeJoin = Paint.Join.ROUND
        when (mode) {
            0 -> bars(c, w, h); 1 -> led(c, w, h); 2 -> scope(c, w, h); 3 -> mirror(c, w, h)
            4 -> radial(c, w, h); 5 -> mountain(c, w, h); 6 -> dots(c, w, h); 7 -> rings(c, w, h)
            else -> vu(c, w, h)
        }
        postInvalidateOnAnimation()
    }

    private fun bars(c: Canvas, w: Float, h: Float) {
        val pad = dp(12f); val bw = (w - 2 * pad) / N; val gap = bw * 0.25f; val bot = h - pad; val mh = h - 2 * pad
        p.shader = LinearGradient(0f, pad, 0f, bot, themeAt(1f), themeAt(0f), Shader.TileMode.CLAMP)
        for (i in 0 until N) { val x = pad + i * bw; c.drawRoundRect(x, bot - bands[i] * mh, x + bw - gap, bot, 3f, 3f, p) }
        p.shader = null; p.color = Color.WHITE
        for (i in 0 until N) { val x = pad + i * bw; val y = bot - peaks[i] * mh; c.drawRect(x, y - dp(3f), x + bw - gap, y, p) }
    }

    private fun led(c: Canvas, w: Float, h: Float) {
        val pad = dp(12f); val rows = 14; val bw = (w - 2 * pad) / N; val sh = (h - 2 * pad) / rows
        for (i in 0 until N) {
            val lit = (bands[i] * rows).toInt(); val pk = (peaks[i] * rows).toInt().coerceAtMost(rows - 1)
            for (r in 0 until rows) {
                p.color = if (r < lit || (r == pk && peaks[i] > 0.04f)) classicAt(r / (rows - 1f)) else 0x1FFFFFFF
                val x = pad + i * bw; val y = h - pad - (r + 1) * sh
                c.drawRect(x + 1.5f, y + 1.5f, x + bw - 1.5f, y + sh - 1.5f, p)
            }
        }
    }

    private fun scope(c: Canvas, w: Float, h: Float) {
        val pad = dp(12f); val mid = h / 2; val amp = h / 2 - pad
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = 0x33FFFFFF
        c.drawLine(pad, mid, w - pad, mid, p)
        path.reset()
        for (i in wave.indices) {
            val x = pad + (w - 2 * pad) * i / (wave.size - 1f)
            val y = mid - (wave[i] * amp * 1.6f).coerceIn(-amp, amp)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.strokeWidth = dp(8f); p.color = alpha(themeAt(0.4f), 0x44); c.drawPath(path, p)
        p.strokeWidth = dp(2.5f); p.color = themeAt(1f); c.drawPath(path, p)
    }

    private fun mirror(c: Canvas, w: Float, h: Float) {
        val pad = dp(12f); val bw = (w - 2 * pad) / N; val gap = bw * 0.3f; val mid = h / 2; val half = h / 2 - pad
        for (i in 0 until N) {
            val x = pad + i * bw; val bh = max(bands[i] * half, dp(2f)); p.color = themeAt(bands[i])
            c.drawRoundRect(x, mid - bh, x + bw - gap, mid + bh, 4f, 4f, p)
        }
    }

    private fun radial(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r0 = min(w, h) * 0.2f; val maxL = min(w, h) / 2 - r0 - dp(10f)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(4f); p.strokeCap = Paint.Cap.ROUND
        val spokes = 64
        for (i in 0 until spokes) {
            val v = bands[if (i < N) i else spokes - 1 - i]
            val a = i / spokes.toFloat() * 2 * PI.toFloat() - PI.toFloat() / 2
            val r1 = r0 + dp(4f) + v * maxL
            p.color = themeAt(v)
            c.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r1, cy + sin(a) * r1, p)
        }
        p.strokeWidth = dp(2f); p.color = alpha(Color.WHITE, 0x88); c.drawCircle(cx, cy, r0 - dp(5f), p)
    }

    private fun mountain(c: Canvas, w: Float, h: Float) {
        val pad = dp(12f); val x0 = pad; val st = (w - 2 * pad) / (N - 1f); val bot = h - pad; val mh = h - 2 * pad
        path.reset(); path2.reset()
        path.moveTo(x0, bot); path.lineTo(x0, bot - bands[0] * mh); path2.moveTo(x0, bot - bands[0] * mh)
        for (i in 1 until N) {
            val x = x0 + i * st; val px = x - st; val y = bot - bands[i] * mh; val py = bot - bands[i - 1] * mh
            path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y); path2.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
        }
        path.lineTo(x0 + (N - 1) * st, bot); path.close()
        p.shader = LinearGradient(0f, pad, 0f, bot, alpha(themeAt(0.8f), 0xDD), alpha(accent, 0x22), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2.5f); p.color = themeAt(1f); c.drawPath(path2, p)
    }

    private fun dots(c: Canvas, w: Float, h: Float) {
        val pad = dp(12f); val rows = 10; val cw = (w - 2 * pad) / N; val ch = (h - 2 * pad) / rows; val r = min(cw, ch) * 0.36f
        for (i in 0 until N) {
            val lit = (bands[i] * rows).toInt()
            for (j in 0 until rows) {
                p.color = if (j < lit) themeAt(j / (rows - 1f)) else 0x1FFFFFFF
                c.drawCircle(pad + i * cw + cw / 2, h - pad - j * ch - ch / 2, r, p)
            }
        }
    }

    private fun rings(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val maxR = min(w, h) / 2 - dp(10f)
        val lv = floatArrayOf(avg(0, 4), avg(4, 10), avg(10, 20), avg(20, 32))
        p.color = alpha(themeAt(0f), 0x66); c.drawCircle(cx, cy, maxR * (0.15f + 0.3f * lv[0]), p)
        p.style = Paint.Style.STROKE
        for (k in 0 until 4) {
            p.strokeWidth = dp(3f) + lv[k] * dp(10f); p.color = alpha(themeAt(k / 3f), 0xDD)
            c.drawCircle(cx, cy, maxR * (0.4f + 0.14f * k) + lv[k] * maxR * 0.12f, p)
        }
    }

    private fun vu(c: Canvas, w: Float, h: Float) {
        vuL = max((avg(0, 16) * 1.8f).coerceAtMost(1f), vuL * 0.9f)
        vuR = max((avg(10, N) * 2.4f).coerceAtMost(1f), vuR * 0.9f)
        val pad = dp(16f); val label = dp(26f); val seg = 28; val sw = (w - 2 * pad - label) / seg; val bh = h * 0.2f
        p.textSize = dp(15f)
        for ((idx, v) in listOf(vuL, vuR).withIndex()) {
            val y = h * (if (idx == 0) 0.28f else 0.58f)
            p.color = Color.WHITE; c.drawText(if (idx == 0) "L" else "R", pad, y + bh * 0.7f, p)
            for (s in 0 until seg) {
                p.color = if (s < (v * seg).toInt()) classicAt(s / (seg - 1f)) else 0x1FFFFFFF
                val x = pad + label + s * sw
                c.drawRect(x + 1.5f, y, x + sw - 1.5f, y + bh, p)
            }
        }
    }
}
