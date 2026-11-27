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

    /** Still artwork + rig.json for puppet-warp animation, if present. */
    fun rig(): WarpRig? = try {
        if ("rig.json" in files) {
            val json = JSONObject(context.assets.open("sprites/$id/rig.json").bufferedReader().readText())
            val bmp = context.assets.open("sprites/$id/" + json.optString("image", "still.png")).use { BitmapFactory.decodeStream(it) }
            if (bmp == null) null else WarpRig(bmp, json)
        } else null
    } catch (e: Exception) { null }

    fun avatar(): Bitmap? = try {
        if ("avatar.png" in files) context.assets.open("sprites/$id/avatar.png").use { BitmapFactory.decodeStream(it) } else null
    } catch (e: Exception) { null }

    fun release() { cache.values.forEach { it?.release() }; cache.clear() }

    companion object {
        /** All clip names the behaviour engine can ask for; missing ones fall back gracefully. */
        val CLIPS = listOf("idle", "walk", "run", "jump", "sleep", "eat", "happy", "sit", "climb")
    }
}

/** Puppet-warp rig for a single still artwork: bones bend the original pixels via drawBitmapMesh. */
class WarpRig(val bmp: Bitmap, json: JSONObject) {
    class Bone(val kind: String, val px: Float, val py: Float, val cx: Float, val cy: Float, val rx: Float, val ry: Float)
    class Eye(val x: Float, val y: Float, val rx: Float, val ry: Float)
    val width = bmp.width; val height = bmp.height
    val facing: Float = when (json.optString("facing")) { "left" -> -1f; else -> 1f }
    val front: Boolean = json.optString("facing") == "front"
    val bones = ArrayList<Bone>(); val eyes = ArrayList<Eye>()
    init {
        val b = json.getJSONArray("bones")
        for (i in 0 until b.length()) b.getJSONObject(i).run {
            bones.add(Bone(getString("kind"), getDouble("px").toFloat(), getDouble("py").toFloat(), getDouble("cx").toFloat(), getDouble("cy").toFloat(), getDouble("rx").toFloat(), getDouble("ry").toFloat()))
        }
        val e = json.getJSONArray("eyes")
        for (i in 0 until e.length()) e.getJSONObject(i).run { eyes.add(Eye(getDouble("x").toFloat(), getDouble("y").toFloat(), getDouble("rx").toFloat(), getDouble("ry").toFloat())) }
    }
    val mw = 18; val mh = 22
    val verts = FloatArray((mw + 1) * (mh + 1) * 2)
    /** Bone pose: rotation (deg), dx (fraction of bone rx), dy (fraction of bone ry, or of image height for the head). */
    val ang = HashMap<String, Float>(); val tx = HashMap<String, Float>(); val ty = HashMap<String, Float>()
    private val out = FloatArray(2)

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    fun displace(x: Float, y: Float, o: FloatArray) {
        var dx = 0f; var dy = 0f
        for (b in bones) {
            val qx = (x - b.cx) / b.rx; val qy = (y - b.cy) / b.ry
            val q = kotlin.math.sqrt(qx * qx + qy * qy)
            if (q >= 1f) continue
            val w = 1f - smooth(0.25f, 1f, q)
            val a = Math.toRadians(((ang[b.kind] ?: 0f) * w).toDouble())
            val rx = x - b.px; val ry = y - b.py
            val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
            dx += (b.px + rx * ca - ry * sa) - x + (tx[b.kind] ?: 0f) * b.rx * w
            dy += (b.py + rx * sa + ry * ca) - y + (ty[b.kind] ?: 0f) * (if (b.kind == "head") height.toFloat() else b.ry) * w
        }
        o[0] = x + dx; o[1] = y + dy
    }

    fun buildMesh() {
        var k = 0
        for (j in 0..mh) for (i in 0..mw) {
            displace(width * i / mw.toFloat(), height * j / mh.toFloat(), out)
            verts[k++] = out[0]; verts[k++] = out[1]
        }
    }

    private val clip = android.graphics.Path(); private val src = Rect(); private val dstR = RectF()
    private val lash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = android.graphics.Color.rgb(55, 30, 22) }

    /** Draws the warped artwork (in bitmap coordinates) and fur eyelids for [blink] 0..1. */
    fun draw(c: Canvas, paint: Paint, blink: Float) {
        buildMesh()
        c.drawBitmapMesh(bmp, mw, mh, verts, 0, null, 0, paint)
        if (blink <= 0.02f) return
        for (e in eyes) {
            displace(e.x, e.y, out)
            val cx = out[0]; val cy = out[1]; val rx = e.rx * 1.22f; val ry = e.ry * 1.22f
            val top = cy - ry; val edge = top + 2f * ry * blink
            src.set((e.x - rx).toInt(), (e.y - ry - ry * 0.9f).toInt().coerceAtLeast(0), (e.x + rx).toInt(), (e.y - ry - ry * 0.1f).toInt().coerceAtLeast(1))
            dstR.set(cx - rx, top, cx + rx, edge)
            c.save()
            clip.reset(); clip.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), android.graphics.Path.Direction.CW)
            c.clipPath(clip)
            c.drawBitmap(bmp, src, dstR, paint)
            c.restore()
            if (blink > 0.6f) {
                lash.strokeWidth = maxOf(2f, ry * 0.1f)
                c.drawArc(RectF(cx - rx * 0.92f, edge - ry * 0.5f, cx + rx * 0.92f, edge + ry * 0.25f), 15f, 150f, false, lash)
            }
        }
    }
}

/**
 * Draws a sprite pet from the user's own artwork (never re-modelled) and turns behaviour
 * states into motion: a frame-animated clip when one exists (e.g. the puppy walk GIF),
 * otherwise the still artwork bent by its [WarpRig] (legs, ears, tail, head, flippers,
 * breathing, blinking) plus whole-body motion (hops, squash, lean, wall rotation).
 */
class SpritePetView(context: Context, val controller: PetController, private val pack: SpritePack) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val m = Matrix()
    private val start = System.nanoTime()
    private var lastSerial = -1
    private var clipStart = 0L
    private var climbAngle = 0f
    private var face = 1f
    private val rig: WarpRig? = pack.rig()
    private var nextBlink = 2f
    private var blinkAt = -10f
    var targetFps = 60
    private val footFrac = 0.93f

    init {
        controller.footFrac = footFrac
        controller.wallFrac = footFrac
        controller.headFrac = 0.3f
    }

    /** Uses the frame clip for locomotion when available (puppy walk cycle). */
    private fun frameClip(a: Anim): Pair<SpriteClip, Float>? {
        val walk = pack.get("walk") ?: return null
        val r = frameClipOrNull(a, walk)
        return r ?: if (rig == null) walk to 0f else null
    }

    private fun frameClipOrNull(a: Anim, walk: SpriteClip): Pair<SpriteClip, Float>? {
        fun c(vararg names: String): Pair<SpriteClip, Float>? { for (n in names) pack.get(n)?.let { return it to 1f }; return null }
        return when (a) {
            Anim.WALK, Anim.CHASE -> walk to 1f
            Anim.CLIMB, Anim.CLING -> c("climb") ?: (walk to 1f)
            Anim.RUN, Anim.SLIDE, Anim.TAILCHASE -> c("run") ?: (walk to 1.9f)
            Anim.JUMP, Anim.FALL, Anim.HELD, Anim.SURPRISED -> c("jump")
            Anim.SLEEP, Anim.REST, Anim.WAKE -> c("sleep")
            Anim.EAT -> c("eat")
            Anim.DRINK -> c("drink", "eat")
            Anim.PLAY -> c("play", "happy")
            Anim.HAPPY, Anim.CELEBRATE, Anim.DANCE, Anim.ROLL -> c("happy", "play")
            Anim.SAD, Anim.HIDE -> c("sad")
            Anim.LOOK, Anim.YAWN -> c("howl", "idle")
            Anim.IDLE, Anim.SIT, Anim.WAVE, Anim.PETTED, Anim.STRETCH, Anim.SCRATCH, Anim.LAND -> c("idle")
            else -> c("idle")
        }
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
        val fc = frameClip(a)
        val srcW: Int; val srcH: Int; val srcFoot: Int
        if (fc != null) { srcW = fc.first.width; srcH = fc.first.height; srcFoot = fc.first.footY }
        else if (rig != null) { srcW = rig.width; srcH = rig.height; srcFoot = rig.height }
        else return
        val scale = minOf(w * 0.96f / srcW, h * 0.9f / srcH)
        val fw = srcW * scale; val fh = srcH * scale
        val footY = h * footFrac
        dst.set(w / 2f - fw / 2f, footY - srcFoot * scale, w / 2f + fw / 2f, footY - srcFoot * scale + fh)
        ctl.headFrac = (dst.top + fh * 0.15f) / h

        // ---------------------------------------------------------- whole-body motion
        var bob = 0f; var sx = 1f; var sy = 1f; var rot = 0f
        val ph = t * 2f * PI.toFloat() * (if (a == Anim.RUN || a == Anim.CHASE) 3f else 1.8f)
        // a dedicated clip already contains the motion: don't add procedural bounce/squash on top
        val dedicated = fc != null && fc.second == 1f && fc.first !== pack.get("walk")
        if (dedicated) { if (a == Anim.HELD) rot = 8f * sin(t * 5f) } else when (a) {
            Anim.IDLE, Anim.LOOK, Anim.SIT, Anim.WAVE -> sy = 1f + 0.012f * sin(t * 2.4f)
            Anim.WALK, Anim.RUN, Anim.CHASE, Anim.CLIMB -> if (fc == null) { bob = -abs(sin(ph)) * h * 0.02f; rot = if (rig?.front == true) 4f * sin(ph) else 1.5f * sin(ph) }
            Anim.HAPPY, Anim.CELEBRATE, Anim.DANCE, Anim.PLAY -> bob = -abs(sin(ct * 8f)) * h * 0.06f
            Anim.JUMP -> { sy = 1.06f; sx = 0.96f; rot = -6f }
            Anim.FALL -> { sy = 1.04f; rot = 4f * sin(t * 6f) }
            Anim.HELD -> rot = 8f * sin(t * 5f)
            Anim.LAND -> { val k = sin((ct / 0.3f).coerceIn(0f, 1f) * PI.toFloat()); sy = 1f - 0.14f * k; sx = 1f + 0.08f * k }
            Anim.SLEEP, Anim.REST -> { sy = 0.97f + 0.03f * sin(t * 1.4f); sx = 1.02f }
            Anim.SAD -> sy = 0.97f
            Anim.SURPRISED -> { val k = sin((ct / 0.5f).coerceIn(0f, 1f) * PI.toFloat()); bob = -k * h * 0.08f; sy = 1f + 0.06f * k }
            Anim.ROLL, Anim.TAILCHASE -> bob = -abs(sin(ct * 6f)) * h * 0.05f
            Anim.STRETCH, Anim.YAWN -> { sx = 1f + 0.05f * sin((ct / 2f).coerceIn(0f, 1f) * PI.toFloat()); sy = 2f - sx }
            else -> {}
        }
        if (abs(ctl.facing) > 0.05f) face = if (ctl.facing > 0) 1f else -1f
        climbAngle += (-90f * ctl.climbSide - climbAngle) * 0.2f

        // contact shadow
        val sh = ctl.shadow * (1f - abs(climbAngle) / 90f)
        if (sh > 0.02f) {
            shadowPaint.shader = RadialGradient(w / 2f, footY, fw * 0.38f, android.graphics.Color.argb((110 * sh).toInt().coerceIn(0, 255), 0, 0, 0), 0, Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, 0.18f, w / 2f, footY); c.drawCircle(w / 2f, footY, fw * 0.38f, shadowPaint); c.restore()
        }

        val natural = if (fc != null) 1f else (rig?.facing ?: 1f)
        c.save()
        m.reset()
        m.setScale(face * natural * sx, sy, w / 2f, footY)
        m.postRotate(rot * face, w / 2f, footY)
        m.postTranslate(0f, bob)
        m.postRotate(climbAngle, w / 2f, h / 2f)
        c.concat(m)
        if (fc != null) {
            val (clip, speed) = fc
            val tm = ((now - clipStart) / 1_000_000L * speed).toLong()
            if (speed == 0f && clip is SheetClip) clip.drawFrame(c, 0, dst, paint) else clip.draw(c, tm, dst, paint)
        } else if (rig != null) {
            val blink = poseRig(rig, a, t, ct, ph)
            c.translate(dst.left, dst.top); c.scale(scale, scale)
            rig.draw(c, paint, blink)
        }
        c.restore()

        val frameBudget = 1000L / (if (ctl.batterySaver) 30 else targetFps)
        val elapsed = (System.nanoTime() - now) / 1_000_000L
        postInvalidateDelayed((frameBudget - elapsed).coerceAtLeast(0L))
    }

    /** Sets bone angles/offsets for the current behaviour and returns the eyelid closure. */
    private fun poseRig(r: WarpRig, a: Anim, t: Float, ct: Float, ph: Float): Float {
        val ang = r.ang; val tx = r.tx; val ty = r.ty
        ang.clear(); tx.clear(); ty.clear()
        ang["head"] = 2.5f * sin(0.6f * t); ang["tail"] = 12f * sin(2.2f * t)
        ang["flipL"] = -4f * sin(1.8f * t); ang["flipR"] = 4f * sin(1.8f * t)
        // ear twitches every few seconds
        val tw = (t % 4.3f); if (tw < 0.25f) ang["earL"] = -10f * sin(tw / 0.25f * PI.toFloat())
        val tw2 = ((t + 1.9f) % 5.1f); if (tw2 < 0.25f) ang["earR"] = 10f * sin(tw2 / 0.25f * PI.toFloat())
        var blink = 0f
        // natural blinking
        if (t > nextBlink) { blinkAt = t; nextBlink = t + 2f + (t * 7.13f % 3.5f) }
        val bt = t - blinkAt; if (bt in 0f..0.18f) blink = sin(bt / 0.18f * PI.toFloat())
        when (a) {
            Anim.WALK, Anim.RUN, Anim.CHASE, Anim.CLIMB, Anim.SLIDE -> {
                val s = sin(ph); val amp = if (a == Anim.WALK || a == Anim.CLIMB) 0.35f else 0.5f
                for ((k, sg) in listOf("legFL" to 1f, "legBR" to 1f, "legFR" to -1f, "legBL" to -1f)) {
                    val v = s * sg; tx[k] = v * amp; ty[k] = -maxOf(0f, v) * 0.3f
                }
                ang["head"] = 3f * sin(ph); ang["tail"] = 10f * sin(ph)
                ang["flipL"] = -15f * sin(ph); ang["flipR"] = 15f * sin(ph)
            }
            Anim.HAPPY, Anim.CELEBRATE, Anim.DANCE, Anim.PLAY, Anim.TAILCHASE -> {
                ang["tail"] = 16f * sin(12f * t); ang["head"] = 7f * sin(5f * t); ang["earL"] = 8f; ang["earR"] = -8f
                ang["flipL"] = -30f * abs(sin(8f * t)); ang["flipR"] = 30f * abs(sin(8f * t))
            }
            Anim.SLEEP, Anim.REST -> { blink = if (a == Anim.SLEEP) 1f else 0.7f; ang["head"] = 5f; ty["head"] = 0.015f; ang["earL"] = -10f; ang["earR"] = 10f; ang["tail"] = 0f }
            Anim.SAD -> { blink = maxOf(blink, 0.35f); ang["head"] = -4f; ty["head"] = 0.02f; ang["earL"] = -9f; ang["earR"] = 9f; ang["tail"] = 0f }
            Anim.EAT, Anim.DRINK -> { ty["head"] = 0.025f + 0.012f * sin(12f * ct); blink = maxOf(blink, 0.3f) }
            Anim.PETTED -> { blink = 1f; ang["head"] = 8f * sin(1.6f * ct); ang["tail"] = 14f * sin(4f * t); ang["earL"] = -8f; ang["earR"] = 8f }
            Anim.LOOK -> ang["head"] = 7f * sin(1.2f * ct)
            Anim.JUMP -> { for (k in listOf("legFL", "legFR", "legBL", "legBR")) ty[k] = -0.3f; ang["earL"] = 10f; ang["earR"] = -10f; ang["tail"] = 20f }
            Anim.FALL, Anim.HELD -> { for ((i, k) in listOf("legFL", "legFR", "legBL", "legBR").withIndex()) tx[k] = 0.15f * sin(t * 9f + i); ang["flipL"] = -35f * abs(sin(t * 14f)); ang["flipR"] = 35f * abs(sin(t * 14f)) }
            Anim.YAWN, Anim.STRETCH -> blink = maxOf(blink, sin((ct / 1.6f).coerceIn(0f, 1f) * PI.toFloat()))
            Anim.WAKE -> blink = 1f - (ct / 1.2f).coerceIn(0f, 1f)
            else -> {}
        }
        return blink
    }
}
