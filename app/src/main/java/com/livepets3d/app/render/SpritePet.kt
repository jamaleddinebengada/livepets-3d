package com.livepets3d.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.view.View
import com.livepets3d.app.anim.Anim
import com.livepets3d.app.anim.PetController
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * One animation clip. Three source formats are supported, looked up in assets/sprites/<pet>/:
 *  - <clip>.png + <clip>.json : sprite sheet (preferred: exact pixels, frame-accurate, all Android versions)
 *  - <clip>.gif               : animated GIF (decoded with android.graphics.Movie, time controlled)
 *  - <clip>.webp              : animated WebP (AnimatedImageDrawable, Android 9+)
 */
abstract class SpriteClip(val width: Int, val height: Int, val footY: Int, val durationMs: Int) {
    /** Draws the frame for [timeMs] into [dst]. */
    abstract fun draw(c: Canvas, timeMs: Long, dst: RectF, paint: Paint)
    open fun release() {}
}

private class SheetClip(private val bmp: Bitmap, private val frames: Int, private val cols: Int, w: Int, h: Int, foot: Int, private val frameMs: Int) :
    SpriteClip(w, h, foot, frames * frameMs) {
    private val src = Rect()
    fun frameAt(timeMs: Long) = ((timeMs / frameMs) % frames).toInt()
    override fun draw(c: Canvas, timeMs: Long, dst: RectF, paint: Paint) = drawFrame(c, frameAt(timeMs), dst, paint)
    fun drawFrame(c: Canvas, f: Int, dst: RectF, paint: Paint) {
        val x = (f % cols) * width; val y = (f / cols) * height
        src.set(x, y, x + width, y + height)
        c.drawBitmap(bmp, src, dst, paint)
    }
    override fun release() = bmp.recycle()
}

@Suppress("DEPRECATION")
private class GifClip(private val movie: Movie) : SpriteClip(movie.width(), movie.height(), movie.height(), maxOf(1, movie.duration())) {
    override fun draw(c: Canvas, timeMs: Long, dst: RectF, paint: Paint) {
        movie.setTime((timeMs % durationMs).toInt())
        c.save(); c.translate(dst.left, dst.top); c.scale(dst.width() / width, dst.height() / height)
        movie.draw(c, 0f, 0f, paint); c.restore()
    }
}

private class WebpClip(private val d: android.graphics.drawable.Drawable) : SpriteClip(d.intrinsicWidth, d.intrinsicHeight, d.intrinsicHeight, 1000) {
    init { if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) d.start() }
    override fun draw(c: Canvas, timeMs: Long, dst: RectF, paint: Paint) {
        d.setBounds(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt()); d.draw(c)
    }
}

/** Loads and caches the clips of one sprite pet. */
class SpritePack(private val context: Context, val id: String) {
    private val cache = HashMap<String, SpriteClip?>()
    private val files: Set<String> = (context.assets.list("sprites/$id") ?: emptyArray()).toSet()

    fun has(clip: String) = get(clip) != null

    fun get(clip: String): SpriteClip? = cache.getOrPut(clip) { load(clip) }

    private fun load(clip: String): SpriteClip? { return try {
        when {
            "$clip.png" in files && "$clip.json" in files -> {
                val meta = JSONObject(context.assets.open("sprites/$id/$clip.json").bufferedReader().readText())
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = context.assets.open("sprites/$id/$clip.png").use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
                SheetClip(bmp, meta.getInt("frames"), meta.getInt("cols"), meta.getInt("frameWidth"), meta.getInt("frameHeight"),
                    meta.optInt("footY", meta.getInt("frameHeight")), meta.optInt("frameMs", 100))
            }
            "$clip.gif" in files -> {
                @Suppress("DEPRECATION")
                val m = context.assets.open("sprites/$id/$clip.gif").use { Movie.decodeStream(it) }
                if (m == null) null else GifClip(m)
            }
            "$clip.webp" in files && Build.VERSION.SDK_INT >= 28 -> {
                val src = android.graphics.ImageDecoder.createSource(context.assets, "sprites/$id/$clip.webp")
                WebpClip(android.graphics.ImageDecoder.decodeDrawable(src))
            }
            else -> null
        }
    } catch (e: Exception) { null } }

    fun avatar(): Bitmap? = try {
        if ("avatar.png" in files) context.assets.open("sprites/$id/avatar.png").use { BitmapFactory.decodeStream(it) } else null
    } catch (e: Exception) { null }

    fun release() { cache.values.forEach { it?.release() }; cache.clear() }

    companion object {
        /** All clip names the behaviour engine can ask for; missing ones fall back gracefully. */
        val CLIPS = listOf("idle", "walk", "run", "jump", "sleep", "eat", "happy", "sit", "climb")
    }
}

/**
 * Draws a sprite pet (exact artwork, no re-modelling) and turns behaviour states into clips.
 * When a dedicated clip is missing, a clearly simpler fallback is used (e.g. the walk cycle
 * played faster for running) so every behaviour still works with only the walk cycle.
 */
class SpritePetView(context: Context, val controller: PetController, private val pack: SpritePack) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val m = Matrix()
    private var start = System.nanoTime()
    private var lastSerial = -1
    private var clipStart = 0L
    private var lastFrameAt = 0L
    private var climbAngle = 0f
    private var face = 1f
    var targetFps = 60

    /** Feet sit at this fraction of the view height (also published to the controller). */
    private val footFrac = 0.93f

    init {
        controller.footFrac = footFrac
        controller.wallFrac = footFrac
        controller.headFrac = 0.3f
    }

    private fun clipFor(a: Anim): Pair<String, Float> = when (a) {
        Anim.WALK, Anim.CHASE, Anim.CLIMB -> (if (a == Anim.CLIMB && pack.has("climb")) "climb" else "walk") to 1f
        Anim.RUN, Anim.SLIDE, Anim.TAILCHASE -> if (pack.has("run")) "run" to 1f else "walk" to 1.9f
        Anim.JUMP, Anim.FALL, Anim.HELD -> if (pack.has("jump")) "jump" to 1f else "walk" to 0f
        Anim.SLEEP, Anim.REST -> if (pack.has("sleep")) "sleep" to 1f else "idle" to 1f
        Anim.EAT, Anim.DRINK -> if (pack.has("eat")) "eat" to 1f else "idle" to 1f
        Anim.HAPPY, Anim.CELEBRATE, Anim.DANCE, Anim.PLAY -> if (pack.has("happy")) "happy" to 1f else "walk" to 1.5f
        Anim.SIT, Anim.WAVE, Anim.PETTED, Anim.SAD -> if (pack.has("sit")) "sit" to 1f else "idle" to 1f
        else -> "idle" to 1f
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val ctl = controller
        if (ctl.paused) { postInvalidateDelayed(250); return }
        val t = (now - start) / 1e9f
        if (ctl.animSerial != lastSerial) { lastSerial = ctl.animSerial; clipStart = now }
        val ct = (now - clipStart) / 1e9f
        val a = ctl.anim
        val w = width.toFloat(); val h = height.toFloat()

        var (name, speed) = clipFor(a)
        var clip = pack.get(name)
        var frozenFrame = -1
        if (clip == null && name == "idle") { clip = pack.get("walk"); frozenFrame = 0; speed = 0f }
        if (clip == null) clip = pack.get("walk") ?: return
        if (speed == 0f && frozenFrame < 0) frozenFrame = 4

        // fit the frame: width-limited, feet on the foot line
        val scale = minOf(w * 0.96f / clip.width, h * 0.9f / clip.height)
        val fw = clip.width * scale; val fh = clip.height * scale
        val footY = h * footFrac
        dst.set(w / 2f - fw / 2f, footY - clip.footY * scale, w / 2f + fw / 2f, footY - clip.footY * scale + fh)
        ctl.headFrac = (dst.top + fh * 0.15f) / h

        // procedural motion layered on top of the exact artwork
        var bob = 0f; var sx = 1f; var sy = 1f; var rot = 0f
        when (a) {
            Anim.IDLE, Anim.LOOK, Anim.SIT, Anim.WAVE -> sy = 1f + 0.012f * sin(t * 2.4f)
            Anim.HAPPY, Anim.CELEBRATE, Anim.DANCE, Anim.PLAY -> bob = -abs(sin(ct * 8f)) * h * 0.06f
            Anim.JUMP -> { sy = 1.06f; sx = 0.96f; rot = -6f }
            Anim.FALL -> { sy = 1.04f; rot = 4f * sin(t * 6f) }
            Anim.HELD -> rot = 8f * sin(t * 5f)
            Anim.LAND -> { val k = sin((ct / 0.3f).coerceIn(0f, 1f) * PI.toFloat()); sy = 1f - 0.14f * k; sx = 1f + 0.08f * k }
            Anim.SLEEP, Anim.REST -> { sy = 0.94f + 0.015f * sin(t * 1.4f); sx = 1.03f }
            Anim.EAT, Anim.DRINK -> rot = 4f + 3f * sin(ct * 12f)
            Anim.SAD -> { sy = 0.95f; rot = 3f }
            Anim.SURPRISED -> { val k = sin((ct / 0.5f).coerceIn(0f, 1f) * PI.toFloat()); bob = -k * h * 0.08f; sy = 1f + 0.06f * k }
            Anim.ROLL -> bob = -abs(sin(ct * 6f)) * h * 0.05f
            Anim.STRETCH, Anim.YAWN -> { sx = 1f + 0.06f * sin((ct / 2f).coerceIn(0f, 1f) * PI.toFloat()); sy = 2f - sx }
            else -> {}
        }
        if (abs(ctl.facing) > 0.05f) face = if (ctl.facing > 0) 1f else -1f
        val targetAngle = -90f * ctl.climbSide
        climbAngle += (targetAngle - climbAngle) * 0.2f

        // shadow
        val sh = ctl.shadow * (1f - abs(climbAngle) / 90f)
        if (sh > 0.02f) {
            shadowPaint.shader = RadialGradient(w / 2f, footY, fw * 0.38f, android.graphics.Color.argb((110 * sh).toInt().coerceIn(0, 255), 0, 0, 0), 0, Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, 0.18f, w / 2f, footY); c.drawCircle(w / 2f, footY, fw * 0.38f, shadowPaint); c.restore()
        }

        c.save()
        m.reset()
        m.setScale(face * sx, sy, w / 2f, footY)
        m.postRotate(rot * face, w / 2f, footY)
        m.postTranslate(0f, bob)
        m.postRotate(climbAngle, w / 2f, h / 2f)
        c.concat(m)
        val tm = ((now - clipStart) / 1_000_000L * speed).toLong()
        if (frozenFrame >= 0 && clip is SheetClip) clip.drawFrame(c, frozenFrame, dst, paint)
        else clip.draw(c, tm, dst, paint)
        c.restore()

        val frameBudget = 1000L / (if (ctl.batterySaver) 30 else targetFps)
        val elapsed = (System.nanoTime() - now) / 1_000_000L
        postInvalidateDelayed((frameBudget - elapsed).coerceAtLeast(0L))
    }
}
