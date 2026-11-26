package com.livepets3d.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import com.livepets3d.app.LivePetsApp
import com.livepets3d.app.anim.Anim
import com.livepets3d.app.anim.PetController
import com.livepets3d.app.data.BodyType
import com.livepets3d.app.data.PetSpecies
import com.livepets3d.app.data.Quest
import com.livepets3d.app.fx.EffectsView
import com.livepets3d.app.render.PetSurfaceView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random

/**
 * One animal living on the screen: its own small transparent overlay window + local behaviour engine.
 * Only the window around the pet is touchable, so the rest of the phone keeps working normally.
 */
class FloatingPet(
    private val ctx: Context,
    val species: PetSpecies,
    private val wm: WindowManager,
    private val host: Host,
    slot: Int,
) {
    interface Host {
        /** Usable screen area in px (excludes status / navigation bars). */
        fun area(): Rect
        fun screenWidth(): Int
        fun showQuickMenu(pet: FloatingPet)
        fun hideAll()
    }

    private enum class B { IDLE, WALK, RUN, ACTION, SLEEP, AIR, DRAG, CLIMB, CLING }

    private val app = LivePetsApp.instance
    private val repo = app.repo
    private val sound = app.sound
    private val density = ctx.resources.displayMetrics.density
    val ctl = PetController(species)
    private val root = FrameLayout(ctx)
    private val gl = PetSurfaceView(ctx, ctl)
    private val fx = EffectsView(ctx)
    private val lp: WindowManager.LayoutParams
    private var attached = false

    private var win = 0                // window size (square) px
    private var x = 0f; private var y = 0f
    private var vx = 0f; private var vy = 0f
    private var floorY = -1f            // used when gravity is off
    private var state = B.IDLE
    private var timer = 1.5f
    private var targetX = 0f
    private var targetY = 0f
    private var wall = 0
    private var climbDir = -1
    private var afterAction: (() -> Unit)? = null
    private var zzzTimer = 0f

    init {
        ctl.zoom = 1.12f
        repo.dress(species, ctl)
        root.addView(gl, FrameLayout.LayoutParams(-1, -1))
        root.addView(fx, FrameLayout.LayoutParams(-1, -1))
        win = sizePx()
        lp = WindowManager.LayoutParams(
            win, win,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "LivePets:${species.name}" }
        val a = host.area()
        x = a.left + a.width() * (0.2f + 0.25f * slot) - win / 2f
        y = groundY()
        setupTouch()
        applySettings()
    }

    private fun sizePx(): Int = (150f * density * repo.petSize).toInt()

    fun attach() {
        if (attached) return
        lp.x = x.toInt(); lp.y = y.toInt()
        wm.addView(root, lp)
        attached = true
        start(Anim.SURPRISED, 0.6f) { land() }
        state = B.AIR; vy = -900f * density / 3f; vx = 0f
    }

    fun detach() {
        if (!attached) return
        try { wm.removeView(root) } catch (_: Exception) {}
        attached = false
    }

    fun setPaused(p: Boolean) {
        ctl.paused = p
        root.visibility = if (p) View.INVISIBLE else View.VISIBLE
    }

    fun applySettings() {
        ctl.batterySaver = repo.batterySaver
        repo.dress(species, ctl)
        gl.targetFps = if (repo.batterySaver) 30 else 60
        val ns = sizePx()
        if (ns != win) {
            val cx = x + win / 2f; val footY = y + win * ctl.footFrac
            win = ns; lp.width = win; lp.height = win
            x = cx - win / 2f; y = footY - win * ctl.footFrac
        }
        val touchable = repo.tapInteraction
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Android 12+: pass-through overlays must be <= 0.8 opacity to let touches reach apps below.
        lp.alpha = if (touchable) repo.overlayAlpha else minOf(0.8f, repo.overlayAlpha)
        if (!repo.gravity && floorY < 0f) floorY = y
        if (repo.gravity) floorY = -1f
        if (!repo.climbing && (state == B.CLIMB || state == B.CLING)) jumpOffWall()
        push()
    }

    // ================================================================== geometry
    private fun groundY(): Float {
        val a = host.area()
        val g = a.bottom - 2f * density - win * ctl.footFrac
        return if (!repo.gravity && floorY >= 0f) min(floorY, g) else g
    }
    private fun minX() = host.area().left - win * 0.25f
    private fun maxX() = host.area().right - win * 0.75f
    private fun wallX(side: Int): Float {
        val reach = ctl.wallFrac // where the paws touch when the rig is rotated onto a wall (published by the renderer)
        return if (side > 0) host.area().right - win * reach else host.area().left - win * (1f - reach)
    }
    private fun canClimb() = repo.climbing && (species.look?.canClimb ?: false)

    private fun push() {
        if (!attached) return
        lp.x = x.toInt(); lp.y = y.toInt()
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    // ================================================================== behaviour
    private fun start(a: Anim, seconds: Float, then: (() -> Unit)? = null) {
        ctl.restart(a)
        state = B.ACTION
        timer = seconds
        afterAction = then
    }

    private fun speed(): Float = repo.petSpeed * species.personality.walkSpeed

    private fun pickNext() {
        fx.showBall = false; fx.showBowl = false; fx.showButterfly = false; fx.showBamboo = false; fx.water = false
        ctl.moveSpeed = 0f
        val st = repo.stats(species)
        val pers = species.personality
        val tired = st.energy < 25f
        val sad = st.happiness < 30f || st.hunger > 85f
        val a = host.area()
        val penguin = species.look?.body == BodyType.PENGUIN
        fun rx() = a.left + Random.nextFloat() * (a.width() - win * 0.5f)
        val options = ArrayList<Pair<Float, () -> Unit>>()
        fun opt(w: Float, f: () -> Unit) { if (w > 0f) options.add(w to f) }
        opt(30f) { walkTo(rx(), run = false) }
        opt(8f * pers.energy) { walkTo(rx(), run = true) }
        if (canClimb() && floorY < 0f) opt(8f) { walkTo(if (Random.nextBoolean()) minX() else maxX(), run = true) { startClimb(if (x <= minX() + 4f) -1 else 1) } }
        opt(9f) { ctl.facing = 0f; start(Anim.LOOK, 2.5f + Random.nextFloat() * 2f) { pickNext() } }
        opt(9f) { ctl.facing = 0f; start(Anim.SIT, 3f + Random.nextFloat() * 4f) { pickNext() } }
        opt(if ("stretch" in pers.special) 9f else 4f) { start(Anim.STRETCH, 2.3f) { pickNext() } }
        opt(3f * pers.sleepy) { start(Anim.YAWN, 1.8f) { pickNext() }; sound.play("yawn", 0.25f, overlay = true) }
        if (!penguin) opt(3f) { start(Anim.SCRATCH, 2f) { pickNext() } }
        opt(3f * pers.social) { fx.burst(EffectsView.Kind.NOTE, 3); start(Anim.DANCE, 4f) { pickNext() } }
        opt(if ("chase" in pers.special) 7f else 3f * pers.energy) { chaseButterfly() }
        opt(3f * pers.social) { ctl.facing = 0f; start(Anim.WAVE, 2f) { pickNext() } }
        opt(4f * pers.energy) { hop() }
        opt(if ("roll" in pers.special) 8f else 3f) { start(Anim.ROLL, 1.6f) { pickNext() } }
        opt(4f) { drink() }
        opt((if (tired) 40f else 5f) * pers.sleepy) { goNap(10f + Random.nextFloat() * 10f) }
        if (sad) opt(18f) { ctl.facing = 0f; start(Anim.SAD, 3.5f) { pickNext() }; sound.play(species.sound, 0.25f, overlay = true) }
        if ("tailchase" in pers.special) opt(6f) { ctl.facing = 0f; start(Anim.TAILCHASE, 2.5f) { start(Anim.HAPPY, 1.2f) { pickNext() } } }
        if ("slide" in pers.special) opt(9f) { slide() }
        if ("bamboo" in pers.special) opt(9f) { bamboo() }
        if ("explore" in pers.special) opt(7f) { walkTo(rx(), run = true) { ctl.facing = 0f; start(Anim.LOOK, 2.5f) { pickNext() } } }
        if ("wag" in pers.special) opt(7f) { ctl.facing = 0f; start(Anim.HAPPY, 1.6f) { start(Anim.SIT, 3f) { pickNext() } }; sound.play("bark", 0.35f, overlay = true) }
        opt(6f) { ctl.facing = 0f; start(Anim.IDLE, 2f + Random.nextFloat() * 3f) { pickNext() } }
        val total = options.sumOf { it.first.toDouble() }.toFloat()
        var r = Random.nextFloat() * total
        for ((w, f) in options) { r -= w; if (r <= 0f) { f(); return } }
        options.last().second()
    }

    private var afterWalk: (() -> Unit)? = null
    private var moveSpeedPx = 72f
    private fun walkTo(tx: Float, run: Boolean, anim: Anim? = null, pxPerSec: Float = 0f, then: (() -> Unit)? = null) {
        targetX = tx.coerceIn(minX(), maxX())
        if (abs(targetX - x) < 20f * density) { if (then != null) then() else pickNext(); return }
        afterWalk = then
        state = if (run) B.RUN else B.WALK
        ctl.anim = anim ?: if (run) Anim.RUN else Anim.WALK
        moveSpeedPx = if (pxPerSec > 0f) pxPerSec else if (run) 190f else 72f
        ctl.facing = sign(targetX - x)
        ctl.moveSpeed = if (run) 0.8f else 0.4f
    }

    private fun slide() {
        val a = host.area()
        sound.play("whoosh", 0.3f, overlay = true)
        walkTo(a.left + Random.nextFloat() * (a.width() - win * 0.5f), run = true, anim = Anim.SLIDE, pxPerSec = 240f) {
            start(Anim.HAPPY, 1.2f) { pickNext() }; sound.play("penguin", 0.35f, overlay = true)
        }
    }

    private fun bamboo() {
        ctl.facing = 0f; fx.showBamboo = true
        sound.play("crunch", 0.35f, overlay = true)
        start(Anim.EAT, 5f) { fx.showBamboo = false; start(Anim.ROLL, 1.6f) { pickNext() } }
        repo.modify(species, dHunger = -10f, dHappy = 3f)
    }

    private fun drink() {
        ctl.facing = 0f; fx.showBowl = true; fx.water = true
        sound.play("drink", 0.4f, overlay = true)
        start(Anim.DRINK, 3f) { fx.showBowl = false; fx.water = false; pickNext() }
    }

    /** Naps, often after wandering to a corner first. */
    private fun goNap(sec: Float) {
        if (Random.nextFloat() < 0.6f && floorY < 0f) walkTo(if (Random.nextBoolean()) minX() else maxX(), run = false) { sleep(sec) }
        else sleep(sec)
    }

    private fun hop() {
        state = B.AIR
        ctl.anim = Anim.JUMP
        vy = -(700f + Random.nextFloat() * 300f) * density / 2.2f
        vx = ctl.facing * 60f * density
        sound.play("boing", 0.25f, overlay = true)
    }

    private fun sleep(sec: Float) {
        ctl.facing = 0f
        ctl.anim = Anim.SLEEP
        state = B.SLEEP
        timer = sec
        zzzTimer = 0.5f
    }

    private fun chaseButterfly() {
        fx.showButterfly = true
        ctl.anim = Anim.CHASE
        state = B.RUN
        val a = host.area()
        targetX = (x + (if (Random.nextBoolean()) 1 else -1) * a.width() * 0.35f).coerceIn(minX(), maxX())
        ctl.facing = sign(targetX - x).let { if (it == 0f) 1f else it }
        ctl.moveSpeed = 0.9f
    }

    private fun land() {
        vx = 0f; vy = 0f
        ctl.shadow = 1f
        start(Anim.LAND, 0.35f) { pickNext() }
    }

    private fun startClimb(side: Int) {
        wall = side
        ctl.climbSide = side
        x = wallX(side)
        climbDir = -1
        val a = host.area()
        targetY = a.top + a.height() * (0.15f + Random.nextFloat() * 0.45f)
        state = B.CLIMB
        ctl.anim = Anim.CLIMB
        ctl.facing = side.toFloat()   // see PetRenderer: on a wall, +facing on the right wall = upwards
        ctl.shadow = 0f
    }

    private fun jumpOffWall() {
        val side = wall
        wall = 0
        ctl.climbSide = 0
        state = B.AIR
        ctl.anim = Anim.FALL
        vx = -side * 160f * density
        vy = -120f * density
        ctl.facing = -side.toFloat()
    }

    /** Called every display frame by the service. */
    fun frame(dt: Float) {
        if (!attached || ctl.paused) return
        val g = 2600f * density
        when (state) {
            B.WALK, B.RUN -> {
                val step = moveSpeedPx * density * speed() * dt
                val d = targetX - x
                if (abs(d) <= step) {
                    x = targetX
                    val next = afterWalk; afterWalk = null
                    if (next != null) next() else pickNext()
                } else x += sign(d) * step
                y = groundY()
            }
            B.AIR -> {
                vy += g * dt
                x = (x + vx * dt).coerceIn(minX(), maxX())
                y += vy * dt
                val gy = groundY()
                val h = gy - y
                ctl.shadow = (1f - h / (win * 1.5f)).coerceIn(0f, 1f)
                if (vy > 0f && ctl.anim == Anim.JUMP) ctl.anim = Anim.FALL
                if (y >= gy && vy > 0f) {
                    y = gy
                    if (vy > 1400f * density) sound.play("pop", 0.2f, overlay = true) else sound.play("step", 0.3f, overlay = true)
                    land()
                }
            }
            B.CLIMB -> {
                val step = 85f * density * speed() * dt
                y += climbDir * step
                val a = host.area()
                if (climbDir < 0 && y <= targetY) {
                    y = targetY; state = B.CLING; ctl.anim = Anim.CLING; timer = 2f + Random.nextFloat() * 4f
                } else if (climbDir > 0 && y >= groundY() - win * 0.1f) {
                    jumpOffWall()
                }
                y = y.coerceIn(a.top - win * 0.2f, groundY())
            }
            B.CLING -> {
                timer -= dt
                if (timer <= 0f) {
                    if (Random.nextFloat() < 0.5f) jumpOffWall()
                    else { climbDir = 1; state = B.CLIMB; ctl.anim = Anim.CLIMB; ctl.facing = -wall.toFloat() }
                }
            }
            B.SLEEP -> {
                timer -= dt
                zzzTimer -= dt
                if (zzzTimer <= 0f) { fx.burst(EffectsView.Kind.ZZZ, 1); zzzTimer = 1.6f }
                if (timer <= 0f) {
                    repo.modify(species, dEnergy = 25f)
                    start(Anim.WAKE, 1.7f) { pickNext() }
                }
                y = groundY()
            }
            B.ACTION, B.IDLE -> {
                timer -= dt
                if (wall == 0 && state != B.DRAG) {
                    val gy = groundY()
                    if (y < gy - 2f && ctl.anim != Anim.SURPRISED) { state = B.AIR; ctl.anim = Anim.FALL; vy = 0f }
                    else if (y > gy) y = gy
                }
                if (timer <= 0f && state == B.ACTION) {
                    val next = afterAction; afterAction = null
                    if (next != null) next() else pickNext()
                }
            }
            B.DRAG -> {}
        }
        fx.facing = if (wall != 0) 0f else ctl.facing
        fx.footFrac = ctl.footFrac; fx.headFrac = ctl.headFrac
        push()
    }

    /** Brings the pet back to the middle of the screen, dropping in from above. */
    fun recall() {
        wall = 0; ctl.climbSide = 0; floorY = -1f
        if (!repo.gravity) floorY = host.area().exactCenterY()
        val a = host.area()
        x = a.exactCenterX() - win / 2f
        y = a.top.toFloat()
        vx = 0f; vy = 0f
        state = B.AIR; ctl.anim = Anim.FALL; ctl.facing = 0f
        sound.play(species.sound, 0.6f)
        push()
    }

    /** Window x, y and size, used to anchor the quick menu. */
    fun anchor(): IntArray = intArrayOf(x.toInt(), (y + win * ctl.headFrac * 0.6f).toInt(), win)

    fun onAreaChanged() {
        if (wall != 0) x = wallX(wall)
        x = x.coerceIn(minX(), maxX())
        if (state != B.AIR && state != B.CLIMB && state != B.CLING) y = groundY()
        push()
    }

    // ================================================================== interactions (also used by the quick menu)
    fun feed() {
        if (wall != 0) jumpOffWall()
        ctl.facing = 0f
        fx.showBowl = true
        sound.play("crunch", 0.6f)
        start(Anim.EAT, 3.6f) { fx.showBowl = false; fx.burst(EffectsView.Kind.HEART, 3); pickNext() }
        fx.burst(EffectsView.Kind.CRUMB, 6)
        repo.modify(species, dHappy = 6f, dHunger = -35f)
        repo.addXp(species, 6); repo.bumpQuest(Quest.FEED)
    }

    fun play() {
        if (wall != 0) jumpOffWall()
        fx.showBall = true
        if (ctl.facing == 0f) ctl.facing = 1f
        sound.play("boing", 0.4f)
        start(Anim.PLAY, 4.2f) { fx.showBall = false; start(Anim.HAPPY, 1.4f) { pickNext() } }
        repo.modify(species, dHappy = 14f, dEnergy = -8f, dHunger = 4f)
        repo.addXp(species, 8); repo.bumpQuest(Quest.PLAY)
    }

    fun nap() {
        if (wall != 0) jumpOffWall()
        sound.play("yawn", 0.35f)
        start(Anim.YAWN, 1.7f) { sleep(14f) }
        repo.bumpQuest(Quest.SLEEP)
    }

    private fun petted() {
        ctl.facing = 0f
        start(Anim.PETTED, 2.6f) { pickNext() }
        fx.burst(EffectsView.Kind.HEART, 4)
        sound.play(if (species.sound == "meow" || species.sound == "bark") "purr" else species.sound, 0.5f)
        sound.haptic()
        repo.modify(species, dHappy = 5f); repo.addXp(species, 2); repo.bumpQuest(Quest.PET)
    }

    private fun tapped() {
        sound.haptic()
        if (state == B.SLEEP) { start(Anim.WAKE, 1.7f) { pickNext() }; return }
        if (wall != 0) { fx.burst(EffectsView.Kind.HEART, 2); jumpOffWall(); return }
        ctl.facing = 0f
        fx.burst(EffectsView.Kind.HEART, 3)
        sound.play(species.sound, 0.7f)
        start(Anim.HAPPY, 1.5f) { pickNext() }
        repo.modify(species, dHappy = 3f); repo.addXp(species, 1)
    }

    private fun doubleTapped() {
        sound.haptic(true)
        fx.burst(EffectsView.Kind.STAR, 8)
        sound.play("chime", 0.5f)
        start(Anim.CELEBRATE, 2.2f) { pickNext() }
    }

    // ================================================================== touch
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        val handler = Handler(Looper.getMainLooper())
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
        var dragging = false; var longFired = false
        var lastUp = 0L
        var lastMoveT = 0L; var velX = 0f; var velY = 0f; var lastRawX = 0f; var lastRawY = 0f
        val longPress = Runnable {
            longFired = true
            petted()
            host.showQuickMenu(this)
        }
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = x; startY = y
                    lastRawX = e.rawX; lastRawY = e.rawY; lastMoveT = System.nanoTime(); velX = 0f; velY = 0f
                    dragging = false; longFired = false
                    handler.postDelayed(longPress, 520)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop * 1.5f) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                        state = B.DRAG; wall = 0; ctl.climbSide = 0
                        fx.showBall = false; fx.showBowl = false
                        ctl.anim = Anim.HELD; ctl.facing = 0f; ctl.shadow = 0.3f
                        sound.play("whoosh", 0.25f)
                    }
                    if (dragging) {
                        val now = System.nanoTime()
                        val dts = ((now - lastMoveT) / 1e9f).coerceAtLeast(0.001f)
                        velX = velX * 0.6f + 0.4f * (e.rawX - lastRawX) / dts
                        velY = velY * 0.6f + 0.4f * (e.rawY - lastRawY) / dts
                        lastRawX = e.rawX; lastRawY = e.rawY; lastMoveT = now
                        ctl.lookX = (velX / (1500f * density)).coerceIn(-1f, 1f)   // looks where it is carried
                        x = startX + (e.rawX - downX)
                        y = startY + (e.rawY - downY)
                        push()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        dragging = false
                        released(velX, velY)
                    } else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                        val now = System.currentTimeMillis()
                        if (now - lastUp < 320) doubleTapped() else tapped()
                        lastUp = now
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun released(velX: Float, velY: Float) {
        val a = host.area()
        val nearLeft = x + win * 0.5f < a.left + win * 0.45f
        val nearRight = x + win * 0.5f > a.right - win * 0.45f
        if (canClimb() && (nearLeft || nearRight) && y < groundY() - win * 0.3f) {
            val side = if (nearRight) 1 else -1
            wall = side; ctl.climbSide = side; x = wallX(side)
            state = B.CLING; ctl.anim = Anim.CLING; timer = 3f; ctl.shadow = 0f
            sound.haptic()
            return
        }
        if (repo.gravity) {
            state = B.AIR; ctl.anim = Anim.FALL
            vx = (velX * 0.35f).coerceIn(-1500f * density, 1500f * density)
            vy = min(velY * 0.35f, 600f * density)
            if (abs(vx) > 50f * density) ctl.facing = sign(vx)
        } else {
            floorY = y.coerceIn(a.top.toFloat(), a.bottom - win * ctl.footFrac)
            y = floorY
            land()
        }
        x = x.coerceIn(minX(), maxX())
    }
}
