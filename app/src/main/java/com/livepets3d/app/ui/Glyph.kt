package com.livepets3d.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Vector icon set with glossy gradient fills (premium "3D" look, no bitmaps / emoji). */
enum class G { PAW, SHIRT, HOUSE, GIFT, TASKS, GEAR, CAMERA, MOVE, BOWL, BALL, MOON, HEART, CROWN, GEM, COIN, PLUS,
    GAMEPAD, CLOSE, LOCK, BOLT, GRAVITY, HAND, NOTE, RUN, EXPAND, SHOP, STAR, CHECK, SOUND, VIBRATE, CLIMB, PHONE, SPARK, EYE_OFF, PEN }

class GlyphDrawable(private val g: G, private val c1: Int, private val c2: Int = c1, private val gloss: Boolean = true) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        c.scale(s, s)
        path.reset()
        build(path)
        // soft drop shadow
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.argb(70, 10, 5, 30)
        c.save(); c.translate(0.35f, 0.7f); c.drawPath(path, p); c.restore()
        p.shader = LinearGradient(0f, 2f, 0f, 22f, c1, c2, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        // rim light: darker outline gives the glossy "3D badge" look
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 0.9f
        p.color = Color.argb(150, (Color.red(c2) * 0.55f).toInt(), (Color.green(c2) * 0.55f).toInt(), (Color.blue(c2) * 0.55f).toInt())
        c.drawPath(path, p); p.style = Paint.Style.FILL
        if (gloss) {
            c.save(); c.clipPath(path)
            p.shader = RadialGradient(8f, 6f, 9f, Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawCircle(8f, 6f, 9f, p)
            p.shader = LinearGradient(0f, 1f, 0f, 12f, Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 24f, 11f, p)
            c.restore()
        }
        p.shader = null
        detail(c)
        c.restore()
    }

    private fun circle(pa: Path, x: Float, y: Float, r: Float) = pa.addCircle(x, y, r, Path.Direction.CW)
    private fun rr(pa: Path, l: Float, t: Float, r: Float, b: Float, rad: Float) = pa.addRoundRect(RectF(l, t, r, b), rad, rad, Path.Direction.CW)

    private fun build(pa: Path) {
        when (g) {
            G.PAW -> {
                pa.addOval(RectF(6.5f, 11.5f, 17.5f, 20.5f), Path.Direction.CW)
                pa.addOval(RectF(3f, 8f, 7.4f, 13.2f), Path.Direction.CW); pa.addOval(RectF(7.2f, 3.6f, 11.4f, 9.4f), Path.Direction.CW)
                pa.addOval(RectF(12.6f, 3.6f, 16.8f, 9.4f), Path.Direction.CW); pa.addOval(RectF(16.6f, 8f, 21f, 13.2f), Path.Direction.CW)
            }
            G.SHIRT -> {
                pa.moveTo(8f, 3.5f); pa.lineTo(3f, 6.5f); pa.lineTo(4.8f, 11f); pa.lineTo(7f, 10f); pa.lineTo(7f, 20.5f)
                pa.lineTo(17f, 20.5f); pa.lineTo(17f, 10f); pa.lineTo(19.2f, 11f); pa.lineTo(21f, 6.5f); pa.lineTo(16f, 3.5f)
                pa.quadTo(12f, 7.5f, 8f, 3.5f); pa.close()
            }
            G.HOUSE -> {
                pa.moveTo(12f, 3f); pa.lineTo(21.5f, 11f); pa.lineTo(19f, 11f); pa.lineTo(19f, 20.5f); pa.lineTo(5f, 20.5f)
                pa.lineTo(5f, 11f); pa.lineTo(2.5f, 11f); pa.close()
            }
            G.GIFT -> { rr(pa, 4f, 10f, 20f, 21f, 1.5f); rr(pa, 3f, 7f, 21f, 11f, 1.2f); pa.addOval(RectF(6.5f, 2.5f, 12f, 7.5f), Path.Direction.CW); pa.addOval(RectF(12f, 2.5f, 17.5f, 7.5f), Path.Direction.CW) }
            G.TASKS -> { rr(pa, 4.5f, 4f, 19.5f, 21.5f, 2.2f); rr(pa, 8.5f, 2.5f, 15.5f, 6f, 1.2f) }
            G.GEAR -> {
                for (i in 0 until 8) {
                    val tooth = Path(); tooth.addRoundRect(RectF(10.3f, 1.6f, 13.7f, 6.5f), 1f, 1f, Path.Direction.CW)
                    val m = android.graphics.Matrix(); m.setRotate(i * 45f, 12f, 12f); tooth.transform(m)
                    pa.addPath(tooth)
                }
                circle(pa, 12f, 12f, 7f)
            }
            G.CAMERA -> { rr(pa, 2.5f, 7f, 21.5f, 19.5f, 3f); rr(pa, 8f, 4.5f, 16f, 8.5f, 1.5f) }
            G.MOVE -> {
                rr(pa, 11f, 4f, 13f, 20f, 1f); rr(pa, 4f, 11f, 20f, 13f, 1f)
                tri(pa, 12f, 1.5f, 8.8f, 5.5f, 15.2f, 5.5f); tri(pa, 12f, 22.5f, 8.8f, 18.5f, 15.2f, 18.5f)
                tri(pa, 1.5f, 12f, 5.5f, 8.8f, 5.5f, 15.2f); tri(pa, 22.5f, 12f, 18.5f, 8.8f, 18.5f, 15.2f)
            }
            G.BOWL -> {
                pa.moveTo(2.5f, 12f); pa.lineTo(21.5f, 12f); pa.quadTo(21f, 20.5f, 12f, 20.5f); pa.quadTo(3f, 20.5f, 2.5f, 12f); pa.close()
                circle(pa, 8f, 10.5f, 2.6f); circle(pa, 12f, 9f, 2.8f); circle(pa, 16f, 10.5f, 2.6f)
            }
            G.BALL -> circle(pa, 12f, 12f, 9.5f)
            G.MOON -> {
                val outer = Path().apply { addCircle(12f, 12f, 9f, Path.Direction.CW) }
                val cut = Path().apply { addCircle(16.5f, 8f, 7.5f, Path.Direction.CW) }
                outer.op(cut, Path.Op.DIFFERENCE); pa.addPath(outer)
            }
            G.HEART -> { pa.moveTo(12f, 20.5f); pa.cubicTo(1f, 13f, 2f, 3.5f, 8f, 4f); pa.cubicTo(10.5f, 4.2f, 12f, 6.5f, 12f, 7f); pa.cubicTo(12f, 6.5f, 13.5f, 4.2f, 16f, 4f); pa.cubicTo(22f, 3.5f, 23f, 13f, 12f, 20.5f); pa.close() }
            G.CROWN -> { pa.moveTo(3f, 7.5f); pa.lineTo(7.5f, 12f); pa.lineTo(12f, 4.5f); pa.lineTo(16.5f, 12f); pa.lineTo(21f, 7.5f); pa.lineTo(19.5f, 18f); pa.lineTo(4.5f, 18f); pa.close(); rr(pa, 4.5f, 18.6f, 19.5f, 20.6f, 1f) }
            G.GEM -> { pa.moveTo(7f, 4f); pa.lineTo(17f, 4f); pa.lineTo(21.5f, 9f); pa.lineTo(12f, 21f); pa.lineTo(2.5f, 9f); pa.close() }
            G.COIN -> circle(pa, 12f, 12f, 9.5f)
            G.PLUS -> { rr(pa, 10.2f, 4f, 13.8f, 20f, 1.8f); rr(pa, 4f, 10.2f, 20f, 13.8f, 1.8f) }
            G.GAMEPAD -> { rr(pa, 2f, 7f, 22f, 18f, 5.5f) }
            G.CLOSE -> {
                val m = android.graphics.Matrix(); m.setRotate(45f, 12f, 12f)
                val a = Path(); rr(a, 10.4f, 3.5f, 13.6f, 20.5f, 1.6f); rr(a, 3.5f, 10.4f, 20.5f, 13.6f, 1.6f); a.transform(m); pa.addPath(a)
            }
            G.LOCK -> { rr(pa, 5f, 10.5f, 19f, 21f, 2.5f); val arc = Path(); arc.addRoundRect(RectF(7.5f, 3f, 16.5f, 14f), 4.5f, 4.5f, Path.Direction.CW); arc.op(Path().apply { addRoundRect(RectF(9.6f, 5.2f, 14.4f, 14f), 2.4f, 2.4f, Path.Direction.CW) }, Path.Op.DIFFERENCE); pa.addPath(arc) }
            G.BOLT -> { pa.moveTo(13.5f, 2f); pa.lineTo(5f, 13.5f); pa.lineTo(11f, 13.5f); pa.lineTo(9.5f, 22f); pa.lineTo(19f, 9.5f); pa.lineTo(13f, 9.5f); pa.close() }
            G.GRAVITY -> { rr(pa, 10.5f, 2.5f, 13.5f, 13f, 1.5f); tri(pa, 12f, 17.5f, 6.5f, 11f, 17.5f, 11f); rr(pa, 4f, 19f, 20f, 21.5f, 1.2f) }
            G.HAND -> { rr(pa, 9f, 2.5f, 12.4f, 14f, 1.7f); rr(pa, 12.6f, 6f, 15.6f, 14f, 1.5f); rr(pa, 15.8f, 7.5f, 18.8f, 15f, 1.5f); rr(pa, 7f, 11f, 19f, 21.5f, 4.5f); rr(pa, 4.5f, 10f, 8f, 15.5f, 1.7f) }
            G.NOTE -> { circle(pa, 7.5f, 17.5f, 3.5f); circle(pa, 17f, 15.5f, 3.5f); rr(pa, 9.4f, 4.5f, 11f, 17.5f, 0.6f); rr(pa, 18.9f, 3f, 20.5f, 15.5f, 0.6f); pa.moveTo(9.4f, 4.5f); pa.lineTo(20.5f, 2.5f); pa.lineTo(20.5f, 6f); pa.lineTo(9.4f, 8f); pa.close() }
            G.RUN -> { circle(pa, 15f, 4.5f, 2.4f); pa.moveTo(11f, 8f); pa.lineTo(16.5f, 8.5f); pa.lineTo(19.5f, 12.5f); pa.lineTo(17.5f, 13.5f); pa.lineTo(15f, 11.2f); pa.lineTo(13.5f, 14.5f); pa.lineTo(16.5f, 17f); pa.lineTo(15.5f, 21.5f); pa.lineTo(13f, 21f); pa.lineTo(13.5f, 18f); pa.lineTo(10f, 16f); pa.lineTo(7f, 21f); pa.lineTo(4.5f, 19.5f); pa.lineTo(9.5f, 11.5f); pa.lineTo(7.5f, 12.5f); pa.lineTo(5f, 11f); pa.close() }
            G.EXPAND -> { tri(pa, 3f, 3f, 10f, 3f, 3f, 10f); tri(pa, 21f, 3f, 14f, 3f, 21f, 10f); tri(pa, 3f, 21f, 10f, 21f, 3f, 14f); tri(pa, 21f, 21f, 14f, 21f, 21f, 14f); rr(pa, 8f, 8f, 16f, 16f, 2f) }
            G.SHOP -> { rr(pa, 4f, 10.5f, 20f, 21f, 2f); pa.moveTo(3f, 5f); pa.lineTo(21f, 5f); pa.lineTo(22f, 10f); pa.quadTo(12f, 13.5f, 2f, 10f); pa.close() }
            G.STAR -> star(pa, 12f, 12.5f, 10f, 4.4f, 5)
            G.CHECK -> { pa.moveTo(3f, 12.5f); pa.lineTo(6.5f, 9f); pa.lineTo(10f, 12.5f); pa.lineTo(17.5f, 5f); pa.lineTo(21f, 8.5f); pa.lineTo(10f, 19.5f); pa.close() }
            G.SOUND -> { rr(pa, 3f, 9f, 8f, 15f, 1f); pa.moveTo(7f, 9f); pa.lineTo(13f, 4f); pa.lineTo(13f, 20f); pa.lineTo(7f, 15f); pa.close(); rr(pa, 15.5f, 9f, 17.3f, 15f, 0.9f); rr(pa, 19f, 6.5f, 20.8f, 17.5f, 0.9f) }
            G.VIBRATE -> { rr(pa, 7f, 3f, 17f, 21f, 2.5f); rr(pa, 2.5f, 8f, 4.5f, 16f, 1f); rr(pa, 19.5f, 8f, 21.5f, 16f, 1f) }
            G.CLIMB -> { rr(pa, 18f, 2f, 21f, 22f, 1f); tri(pa, 11f, 3f, 5f, 10f, 17f, 10f); rr(pa, 9.5f, 9f, 12.5f, 20f, 1.4f) }
            G.PHONE -> rr(pa, 6f, 2f, 18f, 22f, 3f)
            G.SPARK -> { star(pa, 10f, 13f, 8f, 2.2f, 4); star(pa, 18f, 6f, 4f, 1.2f, 4) }
            G.EYE_OFF -> { pa.addOval(RectF(2f, 6.5f, 22f, 17.5f), Path.Direction.CW) }
            G.PEN -> { pa.moveTo(15.5f, 3.5f); pa.lineTo(20.5f, 8.5f); pa.lineTo(9f, 20f); pa.lineTo(3.5f, 20.5f); pa.lineTo(4f, 15f); pa.close() }
        }
    }

    /** Secondary contrasting details on top of the base shape. */
    private fun detail(c: Canvas) {
        p.style = Paint.Style.FILL
        when (g) {
            G.TASKS -> {
                p.color = Color.WHITE
                c.drawRoundRect(RectF(7f, 7.5f, 17f, 19f), 1.2f, 1.2f, p)
                p.color = Color.rgb(34, 197, 94); p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f; p.strokeCap = Paint.Cap.ROUND
                val ck = Path().apply { moveTo(8.8f, 13.5f); lineTo(11.2f, 16f); lineTo(15.5f, 10.5f) }
                c.drawPath(ck, p); p.style = Paint.Style.FILL
            }
            G.GIFT -> { p.color = Color.argb(230, 255, 220, 80); c.drawRect(10.8f, 7f, 13.2f, 21f, p) }
            G.CAMERA -> { p.color = Color.argb(240, 30, 25, 60); c.drawCircle(12f, 13.2f, 4.2f, p); p.color = Color.argb(200, 140, 200, 255); c.drawCircle(12f, 13.2f, 2.4f, p); p.color = Color.WHITE; c.drawCircle(11f, 12.2f, 0.8f, p) }
            G.BALL -> {
                val cols = intArrayOf(Color.rgb(255, 77, 109), Color.rgb(255, 200, 61), Color.rgb(56, 189, 248), Color.rgb(34, 197, 94))
                for (i in 0 until 4) { p.color = cols[i]; c.drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), i * 90f - 20f, 90f, true, p) }
                p.shader = RadialGradient(8.5f, 7.5f, 13f, Color.argb(170, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
                c.drawCircle(12f, 12f, 9.5f, p); p.shader = null
            }
            G.COIN -> { p.color = Color.argb(160, 190, 110, 0); p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f; c.drawCircle(12f, 12f, 6.8f, p); p.style = Paint.Style.FILL; p.color = Color.argb(200, 200, 120, 0); c.drawRect(11f, 8.5f, 13f, 15.5f, p) }
            G.GEM -> { p.color = Color.argb(90, 255, 255, 255); val f = Path().apply { moveTo(7f, 4f); lineTo(9.5f, 9f); lineTo(12f, 21f); lineTo(2.5f, 9f); close() }; c.drawPath(f, p); p.color = Color.argb(70, 0, 0, 40); c.drawRect(2.5f, 8.6f, 21.5f, 9.4f, p) }
            G.GEAR -> { p.color = Color.argb(255, 40, 30, 80); c.drawCircle(12f, 12f, 3f, p) }
            G.GAMEPAD -> {
                p.color = Color.WHITE; c.drawRect(5.5f, 11.6f, 10.5f, 13.4f, p); c.drawRect(7.1f, 10f, 8.9f, 15f, p)
                p.color = Color.rgb(255, 200, 61); c.drawCircle(15.5f, 11f, 1.4f, p); p.color = Color.rgb(56, 189, 248); c.drawCircle(18f, 13.5f, 1.4f, p)
            }
            G.LOCK -> { p.color = Color.argb(220, 40, 25, 70); c.drawCircle(12f, 15f, 1.8f, p); c.drawRect(11.3f, 15f, 12.7f, 18.3f, p) }
            G.HOUSE -> { p.color = Color.argb(230, 50, 30, 90); c.drawRoundRect(RectF(10f, 14f, 14f, 20.5f), 1.4f, 1.4f, p) }
            G.SHOP -> {
                p.color = Color.argb(220, 255, 255, 255)
                for (i in 0 until 4) c.drawRect(4.5f + i * 4.5f, 5f, 6.75f + i * 4.5f, 10.5f, p)
                p.color = Color.argb(230, 56, 189, 248); c.drawRoundRect(RectF(7f, 13f, 17f, 19f), 1f, 1f, p)
            }
            G.EYE_OFF -> { p.color = Color.argb(230, 30, 20, 60); c.drawCircle(12f, 12f, 3.5f, p); p.color = Color.WHITE; p.strokeWidth = 2.2f; p.strokeCap = Paint.Cap.ROUND; c.drawLine(4f, 20f, 20f, 4f, p) }
            G.PHONE -> { p.color = Color.argb(200, 20, 20, 50); c.drawRoundRect(RectF(7.5f, 4.5f, 16.5f, 18f), 1.4f, 1.4f, p) }
            else -> {}
        }
    }

    private fun tri(pa: Path, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) { pa.moveTo(ax, ay); pa.lineTo(bx, by); pa.lineTo(cx, cy); pa.close() }
    private fun star(pa: Path, cx: Float, cy: Float, r: Float, ri: Float, n: Int) {
        for (i in 0 until n * 2) {
            val rr = if (i % 2 == 0) r else ri
            val a = -PI.toFloat() / 2f + i * PI.toFloat() / n
            val x = cx + cos(a) * rr; val y = cy + sin(a) * rr
            if (i == 0) pa.moveTo(x, y) else pa.lineTo(x, y)
        }
        pa.close()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
