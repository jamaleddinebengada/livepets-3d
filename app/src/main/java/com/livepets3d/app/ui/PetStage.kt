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
    private var spritePack: com.livepets3d.app.render.SpritePack? = null
    private var assets: com.livepets3d.app.render.AnimalAssetManager? = null
    private var engine: com.livepets3d.app.anim.engine.AnimalEngine? = null
    private var gl: View = makeView(species)

    /** The in-app stage runs the same animation engine as the overlay, in a small world around the platform. */
    private val stageHost = object : com.livepets3d.app.anim.engine.EngineHost {
        override fun minX() = -width * 0.3f
        override fun maxX() = width * 0.3f
        override fun groundY() = 0f
        override fun topY() = -height * 0.6f
        override fun win() = maxOf(1, height).toFloat()
        override fun spriteScale() = assets?.scaleFor(width.toFloat(), height.toFloat()) ?: 1f
        override fun userSpeed() = app.repo.petSpeed
        override fun climbing() = false
        override fun needs() = app.repo.stats(ctl.species).let { com.livepets3d.app.anim.engine.Needs(it.energy, it.happiness, it.hunger) }
        override fun modify(dHappy: Float, dHunger: Float, dEnergy: Float) { app.repo.modify(ctl.species, dHappy = dHappy, dHunger = dHunger, dEnergy = dEnergy); onStatsChanged?.invoke() }
        override fun sound(event: String, volume: Float) = app.sound.animal(ctl.species.sprite ?: "", event, volume, overlay = false)
        override fun fx(kind: String, count: Int) {
            when (kind) {
                "heart" -> fx.burst(EffectsView.Kind.HEART, count); "star" -> fx.burst(EffectsView.Kind.STAR, count)
                "zzz" -> fx.burst(EffectsView.Kind.ZZZ, count); "note" -> fx.burst(EffectsView.Kind.NOTE, count)
                "crumb" -> fx.burst(EffectsView.Kind.CRUMB, count); "food" -> fx.burst(EffectsView.Kind.SPARKLE, 3)
            }
        }
    }
    private var lastNs = 0L
    private val ticker = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            val e = engine ?: return
            val dt = if (lastNs == 0L) 0.016f else ((t - lastNs) / 1e9f).coerceIn(0f, 0.05f)
            lastNs = t
            if (width > 0 && !ctl.paused) {
                e.update(dt)
                ctl.pose = e.pose
                gl.translationX = e.x; gl.translationY = e.y
                fx.translationX = e.x; fx.translationY = e.y
            }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun makeView(s: PetSpecies): View {
        spritePack?.release(); spritePack = null
        assets?.release(); assets = null; engine = null; ctl.pose = null
        s.model?.takeIf { com.livepets3d.app.render.GlbPetView.available(context, it) }?.let { return com.livepets3d.app.render.GlbPetView(context, ctl, it) }
        val sp = s.sprite ?: return PetSurfaceView(context, ctl)
        val pack = com.livepets3d.app.render.SpritePack(context, sp)
        spritePack = pack
        val am = com.livepets3d.app.render.AnimalAssetManager.load(context, sp)
        if (am != null) {
            assets = am
            engine = com.livepets3d.app.anim.engine.AnimalEngine(am.library, stageHost).also { it.place(0f) }
            ctl.pose = engine!!.pose
            am.prefetch("idle", "walk", "walk_left", "happy")
        }
        return com.livepets3d.app.render.SpritePetView(context, ctl, pack, am)
    }
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
            if (engine == null && System.currentTimeMillis() >= busyUntil) {
                val sp = ctl.species
                val st = app.repo.stats(sp)
                val special = sp.personality.special
                val r = Random.nextInt(100)
                fx.showBamboo = false; fx.showBowl = false; fx.water = false
                val a = when {
                    st.happiness < 30f || st.hunger > 85f -> if (r < 45) Anim.SAD else Anim.IDLE
                    r < 14 && "tailchase" in special -> Anim.TAILCHASE
                    r < 14 && "waddle" in special -> Anim.WALK
                    r < 14 && "bamboo" in special -> { fx.showBamboo = true; Anim.EAT }
                    r < 14 && "stretch" in special -> Anim.STRETCH
                    r < 14 && "wag" in special -> Anim.HAPPY
                    r in 18..25 && spritePack?.has("away") == true -> {
                        handler.postDelayed({ ctl.restart(Anim.TOWARD) }, 2400); busy(5000); Anim.AWAY
                    }
                    r < 18 -> { fx.showBowl = spritePack?.has("eat") != true; fx.water = true; Anim.DRINK }
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
        app.repo.dress(species, ctl)
        if (platform) addView(platformView, LayoutParams(-1, -1))
        addView(gl, LayoutParams(-1, -1))
        addView(fx, LayoutParams(-1, -1))
        attachTouch()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch() {
        var downX = 0f; var downY = 0f; var moved = false
        gl.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false; v.parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.x - downX) + kotlin.math.abs(e.y - downY) > context.dpf(12f)) moved = true
                    if (moved && engine != null) { engine?.follow(e.x - v.width / 2f) }   // walks / runs after your finger
                    else if (moved) {   // the pet follows your finger with its head and eyes
                        ctl.tracking = true
                        ctl.lookX = ((e.x / v.width) * 2f - 1f).coerceIn(-1f, 1f)
                        ctl.lookY = (1f - (e.y / v.height) * 2f).coerceIn(-1f, 1f)
                        busy(1500)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ctl.tracking = false; ctl.lookX = 0f; ctl.lookY = 0f
                    if (!moved && e.actionMasked == MotionEvent.ACTION_UP) tapped()
                }
            }
            true
        }
    }

    fun setSpecies(s: PetSpecies) {
        val needSprite = s.sprite != null || s.model != null
        if (needSprite != (gl is com.livepets3d.app.render.SpritePetView) || needSprite) {
            removeView(gl); ctl.species = s; gl = makeView(s); addView(gl, indexOfChild(fx), LayoutParams(-1, -1)); attachTouch()
        }
        ctl.species = s
        app.repo.dress(s, ctl)
        ctl.restart(Anim.SURPRISED)
        if (engine != null) { lastNs = 0L; android.view.Choreographer.getInstance().removeFrameCallback(ticker); if (isAttachedToWindow) android.view.Choreographer.getInstance().postFrameCallback(ticker) }
        else { fx.translationX = 0f; fx.translationY = 0f }
        fx.burst(EffectsView.Kind.SPARKLE, 7)
        busy(900)
    }

    private fun busy(ms: Long) { busyUntil = System.currentTimeMillis() + ms }

    fun tapped() {
        engine?.let { it.tap(); app.sound.haptic(); app.repo.addXp(ctl.species, 1); onStatsChanged?.invoke(); return }
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
        engine?.let { it.feed(); app.sound.play("crunch", 0.6f); app.repo.addXp(ctl.species, 6); app.repo.bumpQuest(Quest.FEED); onStatsChanged?.invoke(); return }
        ctl.facing = 0f; ctl.restart(Anim.EAT); fx.water = false; fx.showBowl = spritePack?.has("eat") != true
        fx.burst(EffectsView.Kind.CRUMB, 6)
        app.sound.play("crunch", 0.7f)
        handler.postDelayed({ fx.showBowl = false; ctl.restart(Anim.HAPPY); fx.burst(EffectsView.Kind.HEART, 3) }, 3200)
        app.repo.modify(ctl.species, dHappy = 6f, dHunger = -35f); app.repo.addXp(ctl.species, 6); app.repo.bumpQuest(Quest.FEED)
        busy(4600); onStatsChanged?.invoke()
    }

    fun play() {
        engine?.let { it.playToy(); app.repo.addXp(ctl.species, 8); app.repo.bumpQuest(Quest.PLAY); onStatsChanged?.invoke(); return }
        ctl.facing = 0.6f; ctl.restart(Anim.PLAY); fx.showBall = spritePack?.has("happy") != true
        app.sound.play("boing", 0.6f)
        handler.postDelayed({ fx.showBall = false; ctl.facing = 0f; ctl.restart(Anim.CELEBRATE); fx.burst(EffectsView.Kind.STAR, 8); app.sound.play("chime", 0.5f) }, 3800)
        app.repo.modify(ctl.species, dHappy = 14f, dEnergy = -8f, dHunger = 4f); app.repo.addXp(ctl.species, 8); app.repo.bumpQuest(Quest.PLAY)
        busy(6200); onStatsChanged?.invoke()
    }

    fun sleep() {
        engine?.let { it.nap(); app.repo.modify(ctl.species, dEnergy = 30f); app.repo.bumpQuest(Quest.SLEEP); onStatsChanged?.invoke(); return }
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
        engine?.let { it.petted(); app.repo.modify(ctl.species, dHappy = 5f); app.repo.addXp(ctl.species, 3); app.repo.bumpQuest(Quest.PET); onStatsChanged?.invoke(); return }
        ctl.facing = 0f; ctl.restart(Anim.PETTED); fx.burst(EffectsView.Kind.HEART, 6)
        app.sound.play(if (ctl.species.sound == "meow") "purr" else ctl.species.sound, 0.6f)
        app.repo.modify(ctl.species, dHappy = 10f); app.repo.addXp(ctl.species, 3); app.repo.bumpQuest(Quest.PET)
        busy(3000); onStatsChanged?.invoke()
    }

    fun celebrate() { engine?.let { it.doubleTap(); return }; ctl.restart(Anim.CELEBRATE); fx.burst(EffectsView.Kind.STAR, 10); busy(2300) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ctl.paused = false
        handler.postDelayed(idleLoop, 1500)
        if (engine != null) { lastNs = 0L; android.view.Choreographer.getInstance().postFrameCallback(ticker) }
        platformView.postDelayed({ platformView.invalidate() }, 400)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        android.view.Choreographer.getInstance().removeFrameCallback(ticker)
        ctl.paused = true
        super.onDetachedFromWindow()
    }
}
