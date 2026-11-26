package com.livepets3d.app.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Lightweight 2D particle layer drawn above the 3D pet: hearts, Zzz, sparkles, notes, food, ball, butterfly. */
class EffectsView(context: Context) : View(context) {
    enum class Kind { HEART, ZZZ, SPARKLE, NOTE, CRUMB, STAR }

    private class P(var kind: Kind, var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val size: Float, var rot: Float)

    private val parts = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private var last = 0L
    private val heartPath = Path()
    private val starPath = Path()

    /** Props that persist while an action runs. */
    var showBowl = false
        set(v) { field = v; kick() }
    var showBall = false
        set(v) { field = v; kick() }
    var showButterfly = false
        set(v) { field = v; kick() }
    var showBamboo = false
        set(v) { field = v; kick() }
    /** When true the bowl holds water instead of kibble. */
    var water = false
    /** Where the pet's head / feet are, as fractions of the view height. */
    var headFrac = 0.35f
    var footFrac = 0.88f
    var facing = 0f
    private var t = 0f

    init { setWillNotDraw(false) }

    fun burst(kind: Kind, count: Int = 5) {
        val w = width.toFloat().coerceAtLeast(1f); val h = height.toFloat().coerceAtLeast(1f)
        val cx = w * (0.5f + 0.12f * facing); val cy = h * headFrac
        repeat(count) {
            val s = w * when (kind) { Kind.HEART -> 0.07f + Random.nextFloat() * 0.04f; Kind.ZZZ -> 0.09f; Kind.NOTE -> 0.08f; Kind.CRUMB -> 0.015f; else -> 0.04f + Random.nextFloat() * 0.03f }
            val life = when (kind) { Kind.ZZZ -> 2.4f; Kind.CRUMB -> 0.7f; else -> 1.3f + Random.nextFloat() * 0.6f }
            val px = cx + (Random.nextFloat() - 0.5f) * w * (if (kind == Kind.SPARKLE || kind == Kind.STAR) 0.6f else 0.3f)
            val py = if (kind == Kind.CRUMB) h * (footFrac - 0.06f) else cy + (Random.nextFloat() - 0.3f) * h * 0.12f
            val vx = (Random.nextFloat() - 0.5f) * w * (if (kind == Kind.CRUMB) 0.6f else 0.15f) + (if (kind == Kind.ZZZ) w * 0.08f else 0f)
            val vy = if (kind == Kind.CRUMB) -h * 0.25f else -h * (0.12f + Random.nextFloat() * 0.1f)
            parts.add(P(kind, px, py, vx, vy, 0f, life, s, (Random.nextFloat() - 0.5f) * 30f))
        }
        kick()
    }

    private fun kick() { last = 0L; postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        t += dt
        val w = width.toFloat(); val h = height.toFloat()

        if (showBowl) drawBowl(c, w * (0.5f + 0.18f * facing), h * footFrac - w * 0.02f, w * 0.2f)
        if (showBamboo) drawBamboo(c, w * 0.62f, h * footFrac - w * 0.05f, w * 0.06f)
        if (showBall) {
            val bx = w * (0.5f + 0.32f * (if (facing == 0f) 1f else facing)) + sin(t * 3f) * w * 0.04f
            val by = h * footFrac - w * 0.06f - kotlin.math.abs(sin(t * 5f)) * h * 0.12f
            drawBall(c, bx, by, w * 0.065f, t * 400f)
        }
        if (showButterfly) {
            val bx = w * (0.5f + 0.3f * cos(t * 0.9f)) ; val by = h * (headFrac - 0.12f) + sin(t * 1.7f) * h * 0.08f
            drawButterfly(c, bx, by, w * 0.06f, sin(t * 22f))
        }

        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life >= p.max) { it.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt
            if (p.kind == Kind.CRUMB) p.vy += h * 1.2f * dt
            if (p.kind == Kind.HEART || p.kind == Kind.ZZZ || p.kind == Kind.NOTE) p.x += sin((p.life + p.size) * 5f) * w * 0.003f
            val k = p.life / p.max
            val alpha = ((if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f) * 255).toInt().coerceIn(0, 255)
            val scale = if (k < 0.2f) 0.5f + k * 2.5f else 1f
            c.save(); c.translate(p.x, p.y); c.rotate(p.rot); c.scale(scale, scale)
            when (p.kind) {
                Kind.HEART -> drawHeart(c, p.size, alpha)
                Kind.ZZZ -> { textPaint.textSize = p.size; textPaint.color = Color.argb(alpha, 210, 220, 255); c.drawText("z", 0f, 0f, textPaint) }
                Kind.NOTE -> { textPaint.textSize = p.size; textPaint.color = Color.argb(alpha, 255, 214, 90); c.drawText("♪", 0f, 0f, textPaint) }
                Kind.SPARKLE, Kind.STAR -> drawStar(c, p.size, alpha)
                Kind.CRUMB -> { paint.shader = null; paint.color = Color.argb(alpha, 196, 120, 52); c.drawCircle(0f, 0f, p.size, paint) }
            }
            c.restore()
        }
        if (parts.isNotEmpty() || showBall || showBowl || showButterfly || showBamboo) postInvalidateOnAnimation() else last = 0L
    }

    private fun drawHeart(c: Canvas, s: Float, a: Int) {
        heartPath.reset()
        heartPath.moveTo(0f, s * 0.35f)
        heartPath.cubicTo(-s * 0.9f, -s * 0.25f, -s * 0.45f, -s * 0.95f, 0f, -s * 0.45f)
        heartPath.cubicTo(s * 0.45f, -s * 0.95f, s * 0.9f, -s * 0.25f, 0f, s * 0.35f)
        paint.shader = LinearGradient(0f, -s, 0f, s * 0.4f, Color.argb(a, 255, 140, 200), Color.argb(a, 236, 72, 153), Shader.TileMode.CLAMP)
        c.drawPath(heartPath, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.07f; paint.color = Color.argb(a * 3 / 4, 255, 230, 245)
        c.drawPath(heartPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawStar(c: Canvas, s: Float, a: Int) {
        starPath.reset()
        for (i in 0 until 8) {
            val r = if (i % 2 == 0) s else s * 0.28f
            val ang = i * PI.toFloat() / 4f
            val x = cos(ang) * r; val y = sin(ang) * r
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        paint.shader = RadialGradient(0f, 0f, s, Color.argb(a, 255, 255, 255), Color.argb(a, 255, 200, 61), Shader.TileMode.CLAMP)
        c.drawPath(starPath, paint)
        paint.shader = null
    }

    private fun drawBowl(c: Canvas, cx: Float, cy: Float, s: Float) {
        val r = RectF(cx - s, cy - s * 0.35f, cx + s, cy + s * 0.35f)
        paint.shader = LinearGradient(0f, cy - s * 0.4f, 0f, cy + s * 0.4f, Color.rgb(255, 92, 92), Color.rgb(170, 20, 40), Shader.TileMode.CLAMP)
        val body = Path().apply {
            moveTo(cx - s, cy - s * 0.1f); quadTo(cx - s * 0.9f, cy + s * 0.45f, cx, cy + s * 0.45f)
            quadTo(cx + s * 0.9f, cy + s * 0.45f, cx + s, cy - s * 0.1f); close()
        }
        c.drawPath(body, paint)
        paint.shader = null
        if (water) {
            paint.shader = LinearGradient(0f, cy - s * 0.3f, 0f, cy, Color.rgb(125, 211, 252), Color.rgb(14, 116, 204), Shader.TileMode.CLAMP)
            c.drawOval(RectF(cx - s * 0.88f, cy - s * 0.3f, cx + s * 0.88f, cy + s * 0.08f), paint); paint.shader = null
            paint.color = Color.argb(160, 255, 255, 255); c.drawOval(RectF(cx - s * 0.5f, cy - s * 0.24f, cx - s * 0.1f, cy - s * 0.16f), paint)
        }
        if (!water) paint.color = Color.rgb(140, 78, 30)
        if (!water) c.drawOval(RectF(cx - s * 0.88f, cy - s * 0.3f, cx + s * 0.88f, cy + s * 0.08f), paint)
        val rnd = Random(3)
        if (!water) for (i in 0 until 14) {
            paint.color = if (i % 3 == 0) Color.rgb(220, 150, 70) else Color.rgb(176, 100, 42)
            c.drawCircle(cx + (rnd.nextFloat() - 0.5f) * s * 1.5f, cy - s * 0.12f + (rnd.nextFloat() - 0.5f) * s * 0.25f, s * 0.11f, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.06f; paint.color = Color.argb(160, 255, 255, 255)
        c.drawOval(r.apply { inset(s * 0.02f, s * 0.05f) }.let { RectF(it.left, cy - s * 0.32f, it.right, cy + s * 0.1f) }, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBamboo(c: Canvas, x: Float, y: Float, s: Float) {
        c.save(); c.translate(x, y); c.rotate(-28f + sin(t * 6f) * 4f)
        for (i in 0 until 4) {
            paint.shader = LinearGradient(-s * 0.5f, 0f, s * 0.5f, 0f, Color.rgb(132, 204, 22), Color.rgb(63, 98, 18), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(-s * 0.45f, -s * (i + 1) * 1.6f, s * 0.45f, -s * i * 1.6f - s * 0.12f), s * 0.2f, s * 0.2f, paint)
        }
        paint.shader = null; paint.color = Color.rgb(77, 124, 15)
        for (i in 1..3) c.drawRect(-s * 0.5f, -s * i * 1.6f - s * 0.1f, s * 0.5f, -s * i * 1.6f + s * 0.02f, paint)
        paint.color = Color.rgb(101, 163, 13)
        c.drawOval(RectF(s * 0.2f, -s * 5.6f, s * 2.0f, -s * 5.0f), paint)
        c.restore()
    }

    private fun drawBall(c: Canvas, x: Float, y: Float, r: Float, rot: Float) {
        c.save(); c.translate(x, y); c.rotate(rot)
        val cols = intArrayOf(Color.rgb(255, 77, 109), Color.rgb(255, 200, 61), Color.rgb(56, 189, 248), Color.rgb(139, 92, 246))
        for (i in 0 until 4) { paint.color = cols[i]; c.drawArc(RectF(-r, -r, r, r), i * 90f, 90f, true, paint) }
        paint.shader = RadialGradient(-r * 0.35f, -r * 0.35f, r * 1.2f, Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, r, paint); paint.shader = null
        c.restore()
    }

    private fun drawButterfly(c: Canvas, x: Float, y: Float, s: Float, flap: Float) {
        c.save(); c.translate(x, y)
        val sx = 0.35f + 0.65f * kotlin.math.abs(flap)
        for (side in intArrayOf(-1, 1)) {
            c.save(); c.scale(side * sx, 1f)
            paint.shader = RadialGradient(s * 0.5f, -s * 0.3f, s, Color.rgb(56, 189, 248), Color.rgb(139, 92, 246), Shader.TileMode.CLAMP)
            c.drawOval(RectF(0f, -s * 0.9f, s * 1.1f, s * 0.1f), paint)
            paint.shader = RadialGradient(s * 0.4f, s * 0.4f, s * 0.6f, Color.rgb(244, 114, 182), Color.rgb(139, 92, 246), Shader.TileMode.CLAMP)
            c.drawOval(RectF(0f, -s * 0.05f, s * 0.75f, s * 0.6f), paint)
            c.restore()
        }
        paint.shader = null; paint.color = Color.rgb(40, 30, 60)
        c.drawRoundRect(RectF(-s * 0.08f, -s * 0.6f, s * 0.08f, s * 0.55f), s * 0.08f, s * 0.08f, paint)
        c.restore()
    }
}
