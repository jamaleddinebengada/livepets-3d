package com.livepets3d.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.livepets3d.app.LivePetsApp
import com.livepets3d.app.anim.Anim
import com.livepets3d.app.anim.PetController
import com.livepets3d.app.data.PetSpecies
import com.livepets3d.app.data.Quest
import com.livepets3d.app.fx.EffectsView
import com.livepets3d.app.render.PetSurfaceView
import kotlin.random.Random

/** In-app 3D stage: glossy platform + live pet + effects, with its own idle behaviour loop. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PetStage(context: Context, species: PetSpecies, zoom: Float = 1.25f, private val platform: Boolean = true) : FrameLayout(context) {
    val ctl = PetController(species)
    private val gl = PetSurfaceView(context, ctl)
    val fx = EffectsView(context)
    private val handler = Handler(Looper.getMainLooper())
    private val app = LivePetsApp.instance
    private var busyUntil = 0L
    var onStatsChanged: (() -> Unit)? = null

    private val platformView = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cy = h * ctl.footFrac + context.dpf(6f)
            p.shader = RadialGradient(w / 2f, cy, w * 0.42f, intArrayOf(0x88C4B5FD.toInt(), 0x408B5CF6, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.save(); c.scale(1f, 0.22f, w / 2f, cy)
            c.drawCircle(w / 2f, cy, w * 0.42f, p)
            c.restore()
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.5f); p.color = 0x66E9D5FF
            c.drawOval(RectF(w * 0.16f, cy - w * 0.065f, w * 0.84f, cy + w * 0.065f), p)
            p.style = Paint.Style.FILL
        }
    }

    private val idleLoop = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= busyUntil) {
                val r = Random.nextInt(100)
                val a = when {
                    r < 30 -> Anim.IDLE; r < 45 -> Anim.LOOK; r < 55 -> Anim.SIT; r < 62 -> Anim.WAVE
                    r < 69 -> Anim.STRETCH; r < 75 -> Anim.YAWN; r < 82 -> Anim.DANCE; r < 88 -> Anim.SCRATCH
                    r < 94 -> Anim.HAPPY; else -> Anim.PLAY
                }
                ctl.facing = if (a == Anim.IDLE && Random.nextBoolean()) (Random.nextFloat() - 0.5f) * 0.8f else 0f
                ctl.restart(a)
                if (a == Anim.DANCE) fx.burst(EffectsView.Kind.NOTE, 3)
            }
            handler.postDelayed(this, 2600L + Random.nextLong(2600L))
        }
    }

    init {
        ctl.zoom = zoom
        ctl.collarColor = app.repo.collarColor
        if (platform) addView(platformView, LayoutParams(-1, -1))
        addView(gl, LayoutParams(-1, -1))
        addView(fx, LayoutParams(-1, -1))
        gl.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) tapped()
            true
        }
    }

    fun setSpecies(s: PetSpecies) {
        ctl.species = s
        ctl.collarColor = app.repo.collarColor
        ctl.restart(Anim.SURPRISED)
        fx.burst(EffectsView.Kind.SPARKLE, 7)
        busy(900)
    }

    private fun busy(ms: Long) { busyUntil = System.currentTimeMillis() + ms }

    fun tapped() {
        app.sound.play(ctl.species.sound, 0.8f)
        app.sound.haptic()
        ctl.facing = 0f
        ctl.restart(Anim.HAPPY)
        fx.burst(EffectsView.Kind.HEART, 4)
        app.repo.modify(ctl.species, dHappy = 2f)
        app.repo.addXp(ctl.species, 1)
        busy(1600); onStatsChanged?.invoke()
    }

    fun feed() {
        ctl.facing = 0f; ctl.restart(Anim.EAT); fx.showBowl = true
        fx.burst(EffectsView.Kind.CRUMB, 6)
        app.sound.play("crunch", 0.7f)
        handler.postDelayed({ fx.showBowl = false; ctl.restart(Anim.HAPPY); fx.burst(EffectsView.Kind.HEART, 3) }, 3200)
        app.repo.modify(ctl.species, dHappy = 6f, dHunger = -35f); app.repo.addXp(ctl.species, 6); app.repo.bumpQuest(Quest.FEED)
        busy(4600); onStatsChanged?.invoke()
    }

    fun play() {
        ctl.facing = 0.6f; ctl.restart(Anim.PLAY); fx.showBall = true
        app.sound.play("boing", 0.6f)
        handler.postDelayed({ fx.showBall = false; ctl.facing = 0f; ctl.restart(Anim.CELEBRATE); fx.burst(EffectsView.Kind.STAR, 8); app.sound.play("chime", 0.5f) }, 3800)
        app.repo.modify(ctl.species, dHappy = 14f, dEnergy = -8f, dHunger = 4f); app.repo.addXp(ctl.species, 8); app.repo.bumpQuest(Quest.PLAY)
        busy(6200); onStatsChanged?.invoke()
    }

    fun sleep() {
        ctl.facing = 0f; ctl.restart(Anim.YAWN); app.sound.play("yawn", 0.6f)
        handler.postDelayed({ ctl.restart(Anim.SLEEP) }, 1700)
        val zzz = object : Runnable {
            var n = 0
            override fun run() { if (ctl.anim == Anim.SLEEP && n++ < 6) { fx.burst(EffectsView.Kind.ZZZ, 1); handler.postDelayed(this, 1500) } }
        }
        handler.postDelayed(zzz, 1900)
        handler.postDelayed({ ctl.restart(Anim.WAKE) }, 11000)
        app.repo.modify(ctl.species, dEnergy = 30f); app.repo.bumpQuest(Quest.SLEEP)
        busy(13000); onStatsChanged?.invoke()
    }

    fun care() {
        ctl.facing = 0f; ctl.restart(Anim.PETTED); fx.burst(EffectsView.Kind.HEART, 6)
        app.sound.play(if (ctl.species.sound == "meow") "purr" else ctl.species.sound, 0.6f)
        app.repo.modify(ctl.species, dHappy = 10f); app.repo.addXp(ctl.species, 3); app.repo.bumpQuest(Quest.PET)
        busy(3000); onStatsChanged?.invoke()
    }

    fun celebrate() { ctl.restart(Anim.CELEBRATE); fx.burst(EffectsView.Kind.STAR, 10); busy(2300) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ctl.paused = false
        handler.postDelayed(idleLoop, 1500)
        platformView.postDelayed({ platformView.invalidate() }, 400)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        ctl.paused = true
        super.onDetachedFromWindow()
    }
}
