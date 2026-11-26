package com.livepets3d.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.livepets3d.app.data.EarType
import com.livepets3d.app.data.PetSpecies

/** Shaded 2D portrait of a species, used for avatars and the collection carousel. */
class AnimalFaceView(context: Context, species: PetSpecies, var ring: Int = 0, var highlighted: Boolean = false) : View(context) {
    var species: PetSpecies = species
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun shade(c: Int, k: Float): Int {
        val r = ((Color.red(c) * k).toInt()).coerceIn(0, 255); val g = ((Color.green(c) * k).toInt()).coerceIn(0, 255); val b = ((Color.blue(c) * k).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h)
        val cx = w / 2f; val cy = h / 2f
        // background disc
        p.shader = RadialGradient(cx, cy - s * 0.2f, s * 0.7f, Color.rgb(120, 92, 220), Color.rgb(36, 26, 92), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.48f, p)
        p.shader = null
        if (ring != 0 || highlighted) {
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.045f
            p.shader = LinearGradient(0f, 0f, w, h, if (highlighted) Color.rgb(255, 200, 61) else ring, if (highlighted) Color.rgb(244, 114, 182) else ring, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, s * 0.47f, p)
            p.shader = null; p.style = Paint.Style.FILL
        }
        val look = species.look
        if (look == null) { drawLocked(c, cx, cy, s); return }

        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, s * 0.455f, Path.Direction.CW) })
        val prim = look.primary or 0xFF000000.toInt(); val sec = look.secondary or 0xFF000000.toInt(); val acc = look.accent or 0xFF000000.toInt()
        val inner = look.inner or 0xFF000000.toInt()
        val hr = s * 0.3f
        val hy = cy + s * 0.08f
        // ears
        when (look.ear) {
            EarType.POINTY -> for (side in intArrayOf(-1, 1)) {
                val big = if (species == PetSpecies.FOX) 1.25f else 1f
                val ear = Path().apply { moveTo(cx + side * hr * 0.25f, hy - hr * 0.7f); lineTo(cx + side * hr * 0.95f * big, hy - hr * 1.35f * big); lineTo(cx + side * hr * 0.95f, hy - hr * 0.3f); close() }
                p.color = prim; c.drawPath(ear, p)
                val inn = Path().apply { moveTo(cx + side * hr * 0.42f, hy - hr * 0.68f); lineTo(cx + side * hr * 0.88f * big, hy - hr * 1.18f * big); lineTo(cx + side * hr * 0.85f, hy - hr * 0.45f); close() }
                p.color = if (species == PetSpecies.FOX) acc else inner; c.drawPath(inn, p)
            }
            EarType.FLOPPY -> for (side in intArrayOf(-1, 1)) { p.color = shade(acc, 1f); c.drawOval(RectF(cx + side * hr * 0.75f - hr * 0.32f, hy - hr * 0.6f, cx + side * hr * 0.75f + hr * 0.32f, hy + hr * 0.45f), p) }
            EarType.ROUND -> for (side in intArrayOf(-1, 1)) { p.color = acc; c.drawCircle(cx + side * hr * 0.72f, hy - hr * 0.72f, hr * 0.32f, p) }
            EarType.LONG -> for (side in intArrayOf(-1, 1)) {
                p.color = prim; c.drawOval(RectF(cx + side * hr * 0.35f - hr * 0.2f, hy - hr * 2.0f, cx + side * hr * 0.35f + hr * 0.2f, hy - hr * 0.5f), p)
                p.color = inner; c.drawOval(RectF(cx + side * hr * 0.35f - hr * 0.1f, hy - hr * 1.85f, cx + side * hr * 0.35f + hr * 0.1f, hy - hr * 0.65f), p)
            }
            EarType.HORNS -> for (side in intArrayOf(-1, 1)) {
                p.color = Color.rgb(255, 231, 176)
                c.drawPath(Path().apply { moveTo(cx + side * hr * 0.3f, hy - hr * 0.75f); lineTo(cx + side * hr * 0.55f, hy - hr * 1.45f); lineTo(cx + side * hr * 0.65f, hy - hr * 0.65f); close() }, p)
            }
        }
        // head
        p.shader = RadialGradient(cx - hr * 0.3f, hy - hr * 0.45f, hr * 1.5f, shade(prim, 1.15f), shade(prim, 0.72f), Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - hr * 1.05f, hy - hr * 0.95f, cx + hr * 1.05f, hy + hr * 0.92f), p)
        p.shader = null
        // lower face / muzzle
        p.color = sec
        if (look.ear == EarType.POINTY) c.drawOval(RectF(cx - hr * 0.85f, hy + hr * 0.0f, cx + hr * 0.85f, hy + hr * 0.9f), p)
        else c.drawOval(RectF(cx - hr * 0.45f, hy + hr * 0.15f, cx + hr * 0.45f, hy + hr * 0.72f), p)
        // patches
        if (look.eyePatches) for (side in intArrayOf(-1, 1)) {
            c.save(); c.rotate(side * -28f, cx + side * hr * 0.42f, hy - hr * 0.02f)
            p.color = acc; c.drawOval(RectF(cx + side * hr * 0.42f - hr * 0.26f, hy - hr * 0.35f, cx + side * hr * 0.42f + hr * 0.26f, hy + hr * 0.3f), p)
            c.restore()
        }
        // eyes
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * hr * 0.42f; val ey = hy - hr * 0.05f; val er = hr * 0.24f
            p.color = Color.rgb(18, 14, 24); c.drawOval(RectF(ex - er, ey - er * 1.12f, ex + er, ey + er * 1.12f), p)
            p.shader = RadialGradient(ex, ey + er * 0.3f, er, shade(look.eye or 0xFF000000.toInt(), 1.3f), shade(look.eye or 0xFF000000.toInt(), 0.6f), Shader.TileMode.CLAMP)
            c.drawCircle(ex, ey, er * 0.82f, p); p.shader = null
            p.color = Color.rgb(8, 6, 10); c.drawCircle(ex, ey, er * 0.45f, p)
            p.color = Color.WHITE; c.drawCircle(ex - er * 0.35f, ey - er * 0.4f, er * 0.28f, p); c.drawCircle(ex + er * 0.3f, ey + er * 0.32f, er * 0.12f, p)
        }
        // nose + mouth
        p.color = look.nose or 0xFF000000.toInt()
        c.drawOval(RectF(cx - hr * 0.12f, hy + hr * 0.25f, cx + hr * 0.12f, hy + hr * 0.4f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = hr * 0.06f; p.strokeCap = Paint.Cap.ROUND; p.color = Color.rgb(70, 30, 40)
        c.drawArc(RectF(cx - hr * 0.2f, hy + hr * 0.3f, cx, hy + hr * 0.52f), 10f, 160f, false, p)
        c.drawArc(RectF(cx, hy + hr * 0.3f, cx + hr * 0.2f, hy + hr * 0.52f), 10f, 160f, false, p)
        p.style = Paint.Style.FILL
        // cheeks blush
        p.color = Color.argb(70, 255, 120, 160)
        c.drawCircle(cx - hr * 0.68f, hy + hr * 0.28f, hr * 0.15f, p); c.drawCircle(cx + hr * 0.68f, hy + hr * 0.28f, hr * 0.15f, p)
        c.restore()
    }

    private fun drawLocked(c: Canvas, cx: Float, cy: Float, s: Float) {
        val g = GlyphDrawable(G.PAW, Color.argb(120, 200, 190, 255), Color.argb(80, 140, 120, 220), gloss = false)
        g.setBounds((cx - s * 0.26f).toInt(), (cy - s * 0.26f).toInt(), (cx + s * 0.26f).toInt(), (cy + s * 0.26f).toInt())
        g.draw(c)
        val l = GlyphDrawable(G.LOCK, Color.rgb(255, 224, 130), Color.rgb(230, 160, 20))
        l.setBounds((cx + s * 0.08f).toInt(), (cy + s * 0.08f).toInt(), (cx + s * 0.36f).toInt(), (cy + s * 0.36f).toInt())
        l.draw(c)
    }
}
