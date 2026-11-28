package com.livepets3d.app.anim.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/** Everything the engine needs from its environment (overlay window, in-app stage or a JVM test). */
interface EngineHost {
    /** Window-left x range and the window-top y when standing on the ground (screen px). */
    fun minX(): Float
    fun maxX(): Float
    fun groundY(): Float
    fun topY(): Float
    fun win(): Float
    /** Screen px per sprite px at depth 0 (how big the artwork is drawn). */
    fun spriteScale(): Float
    fun userSpeed(): Float = 1f
    /** 0.5 calm, 1 normal, 1.7 lively. */
    fun activity(): Float = 1f
    fun gravity(): Boolean = true
    fun climbing(): Boolean = true
    fun needs(): Needs
    fun modify(dHappy: Float = 0f, dHunger: Float = 0f, dEnergy: Float = 0f) {}
    fun sound(event: String, volume: Float) {}
    fun fx(kind: String, count: Int) {}
    fun others(): List<Float> = emptyList()
}

/** What the renderer draws this frame: current clip frame, optional cross-fade source, transform. */
class RenderPose {
    var file = ""; var frame = 0; var mirror = false
    var prevFile = ""; var prevFrame = 0; var prevMirror = false
    /** 0..1 weight of the current clip (1 = no cross-fade). */
    var blend = 1f
    /** Perspective scale from depth (1 near, smaller when walking away). */
    var depthScale = 1f
    /** Rotation around the window centre in degrees (wall climbing). */
    var rotation = 0f
    var shadow = 1f
    var action = "idle"
    var dir = Dir.E
}

/**
 * The animation + behaviour + locomotion core of one animal.
 *
 * Why the old movement looked unnatural and what this engine does instead:
 *  - translation speed was a fixed px/s unrelated to the walk cycle -> paws slid. Here every
 *    locomotion clip carries its ground speed, and position and clip time advance from the
 *    same playback rate, so the planted paws stay planted at every speed/size setting;
 *  - direction flips were instant mirror flips -> now the animal slows down, turns through its
 *    front view (when the artwork has one) and accelerates again;
 *  - walking "away" only shrank the picture in place -> now depth is a real coordinate:
 *    the animal moves up the screen and shrinks with perspective while the back view plays;
 *  - jumps restarted the jump clip at the apex -> takeoff / air / landing phases of the clip are
 *    mapped to the real ballistic trajectory;
 *  - clips cut hard -> 160 ms cross-fades between clips;
 *  - every animal shared the same behaviour list mapped to "idle" -> the brain only picks
 *    behaviours this animal has animations for, weighted by its personality and needs.
 */
class AnimalEngine(val lib: ClipLibrary, private val host: EngineHost, seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)
    val brain = AnimalBrain(lib, rnd)
    val pose = RenderPose()

    enum class Mode { GROUND, AIR, DRAG, CLIMB, CLING, SLEEP }

    var mode = Mode.GROUND; private set
    var x = 0f; var y = 0f
    /** 0 = front of the scene (full size), 1 = far back. */
    var z = 0f; private set
    var facing = 1; private set
    var time = 0f; private set
    private var vx = 0f; private var vy = 0f
    var wall = 0; private set
    private var restX = Float.NaN

    // ------------------------------------------------------------- clip playback
    private var cur: Resolved? = null
    private var clipMs = 0f
    private var fadeT = 1f
    private var lastFrame = -1
    private var phaseRange: IntRange? = null
    var cyclesDone = 0; private set

    /** Diagnostics: per clip, screen distance travelled and clip time played while moving (sliding test). */
    val travel = HashMap<String, FloatArray>()

    private val queue = ArrayDeque<Step>()
    private var step: Step? = null
    private var stepT = 0f
    // move state
    private var v = 0f
    private var turnT = -1f
    private var moveClip: Resolved? = null
    private var variant = 0
    // jump state
    private var jumpPhase = 0; private var airT = 0f; private var airDur = 1f
    private var fallOnly = false
    private var sleepFx = 0f
    private var climbTargetY = 0f; private var climbDir = -1; private var clingT = 0f

    val g get() = 2600f * (host.win() / 390f)
    /** The whole free screen height is the floor: z = 0 is the bottom edge, z = 1 the top. */
    private fun lift() = max(host.win() * 0.34f, host.groundY() - host.topY())
    /** Mild perspective so the animal stays recognisable everywhere on screen. */
    fun depthScale(zz: Float = z) = 1f - FAR_SHRINK * zz
    /** Depth speeds in the manifest are tuned in on-screen distance; convert to z units for this screen. */
    private fun depthRate() = host.win() * 0.5f / lift()
    /** Floor depth whose ground line puts the window top at [windowY]. */
    fun zAtWindowY(windowY: Float) = ((host.groundY() - windowY) / lift()).coerceIn(0f, 1f)
    fun groundYAt(zz: Float = z) = host.groundY() - zz * lift()

    fun currentAction(): String = (step as? Step.Pose)?.action ?: when (step) {
        is Step.Move -> "move"; is Step.Sleep -> "sleep"; is Step.Jump, is Step.Hop -> "jump"; is Step.Climb -> "climb"; else -> pose.action
    }
    fun stepName(): String = step?.javaClass?.simpleName ?: "none"

    /** Replaces the current plan (scripted demos and tests). */
    fun script(steps: List<Step>) { queue.clear(); step = null; queue.addAll(steps) }
    val pendingSteps get() = queue.size

    fun place(px: Float) { x = px.coerceIn(host.minX(), host.maxX()); y = groundYAt(); z = 0f; play(lib.pose("idle", facing)) }

    // ================================================================= clip helpers
    private fun play(r: Resolved?, keepTime: Boolean = false, fade: Float = 0.16f) {
        if (r == null) return
        val same = cur?.clip === r.clip && cur?.mirror == r.mirror
        if (same) return
        if (cur != null) {
            pose.prevFile = pose.file; pose.prevFrame = pose.frame; pose.prevMirror = pose.mirror
            fadeT = if (fade <= 0f) 1f else 0f
            fadeDur = fade
        }
        cur = r
        if (!keepTime) { clipMs = 0f; cyclesDone = 0 }
        phaseRange = null
        lastFrame = -1
    }
    private var fadeDur = 0.16f

    private fun frameOf(): Int {
        val c = cur?.clip ?: return 0
        val pr = phaseRange
        if (pr != null) {
            val n = pr.last - pr.first + 1
            val f = (clipMs / c.frameMs).toInt()
            return pr.first + if (c.loop || phaseLoop) Math.floorMod(f, n) else min(f, n - 1)
        }
        val f = (clipMs / c.frameMs).toInt()
        return if (c.loop) Math.floorMod(f, c.frames) else min(f, c.frames - 1)
    }
    private var phaseLoop = false

    private fun advance(dtMs: Float) {
        val c = cur?.clip ?: return
        val before = (clipMs / (c.frames * c.frameMs)).toInt()
        clipMs += dtMs
        val after = (clipMs / (c.frames * c.frameMs)).toInt()
        if (after > before) cyclesDone += after - before
    }

    // ================================================================= public events
    fun tap() {
        if (mode == Mode.SLEEP) { wake(); return }
        if (mode == Mode.CLING || mode == Mode.CLIMB) { jumpOffWall(); return }
        if (mode == Mode.DRAG) return
        host.fx("heart", 3)
        interrupt(listOf(Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1, sound = brain.profile.voice.takeIf { rnd.nextFloat() < 0.5f } ?: "bark")))
        host.modify(dHappy = 3f)
    }
    fun doubleTap() {
        if (mode != Mode.GROUND && mode != Mode.AIR) return
        host.fx("star", 8)
        if (host.gravity() && lib.has("jump")) interrupt(listOf(Step.Jump(0f, 0.55f), Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1)))
        else interrupt(listOf(Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 2)))
    }
    /** Long press / gentle petting. */
    fun petted() {
        if (mode == Mode.SLEEP) { host.fx("heart", 3); host.sound("content", 0.3f); return }
        if (mode != Mode.GROUND && mode != Mode.AIR) return
        host.fx("heart", 5); host.sound("content", 0.45f)
        interrupt(listOf(Step.Pose(pettingAction(), 2.6f)))
        host.modify(dHappy = 5f); brain.onEvent("petted", time)
    }
    fun feed() {
        if (mode == Mode.CLIMB || mode == Mode.CLING) jumpOffWall()
        if (mode == Mode.SLEEP) wake()
        host.fx("crumb", 6); host.modify(dHappy = 6f, dHunger = -35f); brain.onEvent("fed", time)
        interrupt(listOf(Step.Pose(if (lib.has("eat")) "eat" else "idle", cycles = 2, sound = "eat"),
            Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1, fx = "heart")))
    }
    fun playToy() {
        if (mode == Mode.CLIMB || mode == Mode.CLING) jumpOffWall()
        if (mode == Mode.SLEEP) wake()
        host.modify(dHappy = 14f, dEnergy = -8f, dHunger = 4f); brain.onEvent("played", time)
        interrupt(listOf(Step.Pose(if (lib.has("play")) "play" else "happy", cycles = 3, fx = "note"),
            Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1)))
    }
    fun nap() {
        if (mode == Mode.CLIMB || mode == Mode.CLING) jumpOffWall()
        interrupt(listOf(Step.Sleep(14f)))
    }
    /** Walk/run toward a point (finger follow on the in-app stage). */
    fun follow(targetX: Float, targetZ: Float = z) {
        if (mode == Mode.SLEEP) wake()
        if (mode != Mode.GROUND) return
        val far = abs(targetX - x) > host.win() * 1.2f
        val gait = if (far && abs(targetZ - z) < 0.1f) "run" else "walk"
        val s = step
        if (s is Step.Move && following) { step = Step.Move(targetX, targetZ, gait); return }
        following = true
        interrupt(listOf(Step.Move(targetX, targetZ, gait), Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1)))
    }
    /** Finger lifted: finish the current walk, then back to its own life. */
    fun stopFollowing() { following = false }
    private var following = false

    /** Slow stroking over the animal: keeps the petting reaction going instead of restarting it. */
    fun stroke() {
        val s = step
        if (mode == Mode.GROUND && s is Step.Pose && s.action == pettingAction() && stepT < s.seconds) {
            stepT = max(0f, stepT - 0.8f); host.fx("heart", 2); host.modify(dHappy = 1f); return
        }
        petted()
    }
    private fun pettingAction() = if (lib.has("touch_reaction")) "touch_reaction" else if (lib.has("happy")) "happy" else "idle"

    fun dragStart() {
        mode = Mode.DRAG; wall = 0; queue.clear(); step = null; z = 0f
        host.sound("whoosh", 0.25f)
        playPhase("jump", "air", loop = true)
    }
    fun dragTo(nx: Float, ny: Float, vxHint: Float) {
        x = nx; y = ny
        if (abs(vxHint) > host.win() * 0.6f) facing = if (vxHint > 0) 1 else -1
        val c = cur ?: return
        if (c.mirror != (facing < 0) && c.clip.dir.horizontal) {
            val r = lib.pose("jump", facing) ?: return
            play(r, keepTime = true, fade = 0.1f); phaseRange = r.clip.phases["air"]; phaseLoop = true
        }
    }

    fun release(velX: Float, velY: Float) {
        val nearL = x < host.minX() + host.win() * 0.2f; val nearR = x > host.maxX() - host.win() * 0.2f
        if (host.climbing() && lib.has("climb") && (nearL || nearR) && y < groundYAt(0f) - host.win() * 0.6f) {
            wall = if (nearR) 1 else -1; x = if (wall > 0) host.maxX() else host.minX()
            facing = wall; mode = Mode.CLING; clingT = 3f; play(lib.pose("climb", facing)); return
        }
        x = x.coerceIn(host.minX(), host.maxX())
        y = y.coerceAtMost(groundYAt(0f))
        if (host.gravity()) {
            // lands on the floor a little below where it was let go (short, natural drop)
            z = zAtWindowY(y + host.win() * 0.14f)
            vx = (velX * 0.3f).coerceIn(-host.win() * 2.5f, host.win() * 2.5f); vy = min(velY * 0.3f, host.win() * 1.2f)
            if (abs(vx) > host.win() * 0.15f) facing = if (vx > 0) 1 else -1
            startFall()
        } else { z = zAtWindowY(y); land() }
    }

    private var pending: List<Step>? = null
    private fun interrupt(steps: List<Step>) {
        if (mode == Mode.AIR || mode == Mode.DRAG) { pending = steps; return }   // react right after landing
        queue.clear(); step = null
        if (mode == Mode.SLEEP) mode = Mode.GROUND
        queue.addAll(steps)
    }

    private fun wake() {
        mode = Mode.GROUND; queue.clear(); step = null
        host.modify(dEnergy = 10f)
        // no wake-up artwork yet: a slow 0.5 s cross-fade from sleep into idle instead of a cut
        val a = if (lib.has("wakeup")) "wakeup" else "idle"
        play(lib.pose(a, facing), fade = 0.5f)
        queue.add(Step.Pose(a, if (a == "idle") 1.2f else 0f, cycles = if (a == "idle") 0 else 1))
    }

    // ================================================================= main update
    fun update(dt0: Float) {
        val dt = min(dt0, 0.05f)
        time += dt
        if (fadeT < 1f) fadeT = min(1f, fadeT + dt / fadeDur)
        if (restX.isNaN()) restX = if (rnd.nextBoolean()) host.minX() + host.win() * 0.1f else host.maxX() - host.win() * 0.1f
        when (mode) {
            Mode.AIR -> updateAir(dt)
            Mode.DRAG -> advance(dt * 1000f * 0.6f)
            Mode.CLIMB -> updateClimb(dt)
            Mode.CLING -> { clingT -= dt; if (clingT <= 0f) { if (rnd.nextFloat() < 0.5f) jumpOffWall() else { climbDir = 1; mode = Mode.CLIMB; facing = -wall; play(lib.pose("climb", facing)) } } }
            Mode.SLEEP, Mode.GROUND -> updateSteps(dt)
        }
        writePose()
    }

    private fun nextStep() {
        if (queue.isEmpty()) {
            val n = host.needs()
            val ctx = BrainContext(time, n, x, host.minX(), host.maxX(), host.win(), host.others(),
                host.climbing() && lib.has("climb"), host.gravity(), restX, z, depthRate(), host.activity())
            val m0 = brain.mood
            queue.addAll(brain.decide(ctx))
            if (brain.mood != m0) host.fx(when (brain.mood) { Mood.HAPPY -> "heart"; Mood.PLAYFUL -> "note"; Mood.CURIOUS -> "sparkle"; Mood.TIRED -> "zzz"; Mood.CALM -> "" }, 2)
        }
        step = queue.removeFirstOrNull()
        stepT = 0f
        when (val s = step) {
            is Step.Pose -> if (s.action != "__land") {
                val r = lib.pose(s.action, facing, rnd.nextInt(4)) ?: lib.pose("idle", facing)
                play(r)
                s.sound?.let { host.sound(it, 0.5f) }
                s.fx?.let { host.fx(it, 3) }
            }
            is Step.Move -> { v = max(v, 0f); turnT = -1f; moveClip = null; variant = rnd.nextInt(6) }
            is Step.Sleep -> { mode = Mode.SLEEP; play(lib.pose("sleep", facing), fade = 0.45f); sleepFx = 0.6f; host.sound("sleep", 0.15f) }
            is Step.Jump -> startJump(s)
            is Step.Hop -> startHop(s)
            is Step.Climb -> startClimb()
            is Step.Face -> { facing = s.dir; step = null }
            is Step.Call -> { s.f(); step = null }
            null -> {}
        }
    }

    private fun updateSteps(dt: Float) {
        if (step == null) nextStep()
        val s = step ?: return
        stepT += dt
        when (s) {
            is Step.Pose -> {
                advance(dt * 1000f)
                y = groundYAt()
                val done = if (s.cycles > 0) cyclesDone >= s.cycles else stepT >= s.seconds
                if (done) step = null
            }
            is Step.Move -> if (updateMove(s, dt)) { step = null; v *= 0.5f }
            is Step.Sleep -> {
                advance(dt * 1000f); y = groundYAt()
                sleepFx -= dt; if (sleepFx <= 0f) { host.fx("zzz", 1); sleepFx = 1.6f }
                if (stepT >= s.seconds) { host.modify(dEnergy = 25f); wake() }
            }
            is Step.Jump, is Step.Hop -> updateJumpGround(dt)
            else -> step = null
        }
    }

    // ================================================================= locomotion
    private fun gaitClip(gait: String, d: Dir): Resolved? {
        val pref = if (gait == "run") listOf("run", "walk") else listOf("walk")
        for (a in pref) lib.resolve(a, d, variant)?.let { return it }
        return null
    }

    /** @return true when arrived. */
    private fun updateMove(s: Step.Move, dt: Float): Boolean {
        val tx = s.x.coerceIn(host.minX(), host.maxX()); val tz = s.z.coerceIn(0f, 1f)
        val dx = tx - x; val dz = tz - z
        val win = host.win()
        if (abs(dx) < 1.5f && abs(dz) < 0.004f) { x = tx; z = tz; y = groundYAt(); return true }

        // --- turning: slow down, turn through the front view, then set off the other way
        if (turnT >= 0f) {
            turnT += dt
            advance(dt * 1000f * turnRate)   // turn artwork (or the front view) keeps moving while the body turns
            if (turnT >= turnDur) { turnT = -1f; facing = -facing; moveClip = null }
            y = groundYAt()
            return false
        }
        val d = Dir.of(dx / win, dz * lift() / win)   // compare both axes in screen units
        val wantSide = d.dx
        if (wantSide != 0 && wantSide != facing) {
            if (v > 0.12f && moveClip != null) { v = max(0f, v - dt / 0.22f); stepMove(dt, tx, tz, braking = true); return false }
            startTurn(); return false
        }
        val r = moveClip?.takeIf { it.clip.dir == d || (it.mirror && it.clip.dir == d.mirrored()) || dirCompatible(it, d) } ?: gaitClip(s.gait, d)
        if (r == null) {
            // this animal has no artwork for that direction: go straight to the target instead
            if (d.depth) { z = tz; y = groundYAt(); return true }
            x = tx; return true
        }
        if (r !== moveClip) {
            // keep the leg phase when switching between variants of the same gait
            play(r, keepTime = moveClip != null && moveClip!!.clip.gait == r.clip.gait && moveClip!!.clip.dir.horizontal == r.clip.dir.horizontal)
            moveClip = r
        }
        // target speed: full speed, easing out when close (no sudden stop)
        val c = r.clip
        val hs = horizontalSpeed(c, 1f)
        val tLeft = max(if (hs > 1e-3f) abs(dx) / hs else 0f, if (c.depthSpeed > 1e-4f) abs(dz) / (c.depthSpeed * depthRate() * host.userSpeed()) else 0f)
        val vt = min(1f, max(0.3f, tLeft / 0.55f))
        v = if (v < vt) min(vt, v + dt / ACCEL_TIME) else max(vt, v - dt / 0.3f)
        stepMove(dt, tx, tz, braking = false)
        return false
    }

    private fun dirCompatible(r: Resolved, d: Dir): Boolean {
        val cd = if (r.mirror) r.clip.dir.mirrored() else r.clip.dir
        if (cd == d) return true
        // a side clip serving a diagonal, or a depth clip serving a diagonal: keep it while still valid
        if (!d.horizontal && !d.depth) return (cd.horizontal && cd.dx == d.dx) || (cd.depth && cd.dz == d.dz)
        return false
    }

    /** Screen px/s at playback rate [rate], including size, depth perspective and user speed. */
    private fun horizontalSpeed(c: ClipInfo, rate: Float) = c.groundSpeed * host.spriteScale() * depthScale() * rate * host.userSpeed() * brain.profile.pace.coerceIn(0.85f, 1.15f)

    private fun stepMove(dt: Float, tx: Float, tz: Float, braking: Boolean) {
        val r = moveClip ?: return
        val c = r.clip
        val rate = v * host.userSpeed() * brain.profile.pace.coerceIn(0.85f, 1.15f)
        advance(dt * 1000f * rate)               // clip time and position share the same rate
        val cd = if (r.mirror) c.dir.mirrored() else c.dir
        val x0 = x; val z0 = z
        try { moveBody(c, cd, dt, rate, tx, tz, braking) } finally {
            val t = travel.getOrPut(c.file) { FloatArray(3) }
            t[0] += abs(x - x0) / (host.spriteScale() * depthScale()); t[1] += dt * 1000f * rate; t[2] += abs(z - z0)
        }
        x = x.coerceIn(host.minX(), host.maxX())
        y = groundYAt()
        val f = frameOf()
        if (f != lastFrame) { if (f in c.steps) host.sound("step", 0.12f); lastFrame = f }
    }

    private fun moveBody(c: ClipInfo, cd: Dir, dt: Float, rate: Float, tx: Float, tz: Float, braking: Boolean) {
        if (!cd.horizontal && !cd.depth) {
            // true diagonal artwork: both components follow the clip's own stride
            val dxs = c.groundSpeed * host.spriteScale() * depthScale() * rate * dt
            x = if (braking) x + cd.dx * dxs else approach(x, tx, dxs)
            if (!braking) z = approach(z, tz, c.depthSpeed * depthRate() * rate * dt)
            return
        }
        if (c.groundSpeed > 0f) {
            val dxs = c.groundSpeed * host.spriteScale() * depthScale() * rate * dt
            val dir = if (cd.dx != 0) cd.dx.toFloat() else facing.toFloat()
            x = if (braking) x + dir * dxs else approach(x, tx, dxs)
            // diagonal served by a side view: drift slowly in depth
            if (!braking && abs(tz - z) > 0.004f) z = approach(z, tz, DIAG_DRIFT * 0.34f * depthRate() * rate * dt)
        }
        if (c.depthSpeed > 0f) {
            z = approach(z, tz, c.depthSpeed * depthRate() * rate * dt)
            // diagonal served by a front/back view: limited sideways drift
            if (abs(tx - x) > 1f) x = approach(x, tx, DIAG_DRIFT * host.win() * 0.3f * rate * dt)
        }
    }

    private var turnDur = TURN_TIME
    private var turnRate = 0.8f

    private fun startTurn() {
        v = 0f; turnT = 0f
        val turn = lib.clips.firstOrNull { it.action == "turn" }
        if (turn != null) {
            // real turning artwork: authored right -> front -> left; mirrored it turns left -> right
            val mirror = (facing < 0) == (turn.dir == Dir.E)
            play(Resolved(turn, mirror), fade = 0.1f); clipMs = 0f
            turnRate = TURN_CLIP_RATE * host.userSpeed().coerceIn(0.6f, 1.6f)
            turnDur = turn.durationMs / 1000f / turnRate
        } else {
            // turn through the front view if the artwork has one, else a soft cross-fade
            val front = lib.resolve("walk", Dir.S)
            if (front != null) { play(front, fade = 0.12f); clipMs = 0f } else play(lib.pose("idle", -facing), fade = TURN_TIME)
            turnRate = 0.8f; turnDur = TURN_TIME
        }
        moveClip = null
    }

    // ================================================================= jumping & falling
    private fun playPhase(action: String, phase: String, loop: Boolean) {
        val r = lib.pose(action, facing) ?: return
        play(r, fade = 0.08f)
        phaseRange = r.clip.phases[phase]; phaseLoop = loop; clipMs = 0f
    }

    private fun startJump(s: Step.Jump) {
        if (!lib.has("jump")) { step = null; return }
        hop = null
        jumpH = s.height; jumpFwd = s.forward
        jumpPhase = 0
        playPhase("jump", "takeoff", loop = false)
        host.sound("jump", 0.2f)
    }
    private var jumpH = 0.5f; private var jumpFwd = 0.5f
    private var hop: Step.Hop? = null; private var hopZ0 = 0f

    private fun startHop(s: Step.Hop) {
        if (!lib.has("jump") || !host.gravity()) { queue.addFirst(Step.Move(s.x, s.z)); step = null; return }
        val dir = if (s.x >= x) 1 else -1
        if (dir != facing && abs(s.x - x) > 4f) {
            // turn first (with the turn artwork), then jump
            queue.addFirst(s); step = Step.Move(x + dir * 3f, z); turnT = -1f; moveClip = null; return
        }
        hop = s; jumpH = s.height; jumpFwd = 0f
        playPhase("jump", "takeoff", loop = false)
        host.sound("jump", 0.2f)
    }

    private fun updateJumpGround(dt: Float) {
        advance(dt * 1000f)
        val c = cur?.clip ?: run { step = null; return }
        val take = c.phases["takeoff"]
        if (take == null || clipMs >= (take.last - take.first + 1) * c.frameMs) {
            val hp = hop
            if (hp != null) {
                // ballistic arc from here to another spot of the floor
                val tx = hp.x.coerceIn(host.minX(), host.maxX()); val tz = hp.z.coerceIn(0f, 1f)
                val y1 = groundYAt(tz); val apex = max(min(y, y1) - host.win() * jumpH, host.topY())
                vy = -sqrt(2f * g * (y - apex))
                airDur = -vy / g + sqrt(2f * (y1 - apex) / g)
                vx = (tx - x) / airDur; hopZ0 = z; hopZ1 = tz
            } else {
                val h = min(host.win() * jumpH, max(host.win() * 0.05f, y - host.topY()))   // never above the screen
                vy = -sqrt(2f * g * h)
                airDur = 2f * -vy / g
                vx = facing * jumpFwd * host.win() / airDur
            }
            airT = 0f; fallOnly = false
            mode = Mode.AIR
            phaseRange = c.phases["air"]; phaseLoop = false; clipMs = 0f
        }
    }

    private fun startFall() {
        hop = null
        mode = Mode.AIR; fallOnly = true; airT = 0f
        playPhase("jump", "fall", loop = true)
        clipMs = 0f
    }

    private var hopZ1 = 0f
    private fun updateAir(dt: Float) {
        airT += dt
        if (hop != null && !fallOnly) z = hopZ0 + (hopZ1 - hopZ0) * (airT / airDur).coerceIn(0f, 1f)
        vy += g * dt
        x = (x + vx * dt).coerceIn(host.minX(), host.maxX())
        y += vy * dt
        if (y < host.topY()) { y = host.topY(); if (vy < 0f) vy = 0f }   // the top of the screen is a ceiling
        val gy = groundYAt()
        val c = cur?.clip
        if (c != null) {
            if (!fallOnly) {
                // map the clip's air frames onto the real flight time
                val air = c.phases["air"]
                if (air != null) { val n = air.last - air.first + 1; clipMs = (airT / airDur).coerceIn(0f, 0.999f) * n * c.frameMs }
            } else advance(dt * 1000f * 0.7f)
        }
        pose.shadow = (1f - (gy - y) / (host.win() * 1.5f)).coerceIn(0f, 1f)
        if (y >= gy && vy > 0f) {
            y = gy
            host.sound(if (vy > host.win() * 3.5f) "land_hard" else "land", 0.3f)
            land()
        }
    }

    private fun land() {
        hop?.let { h -> x = h.x.coerceIn(host.minX(), host.maxX()); z = h.z.coerceIn(0f, 1f) }   // sub-frame correction
        hop = null
        vx = 0f; vy = 0f; mode = Mode.GROUND; pose.shadow = 1f; y = groundYAt()
        val r = lib.pose("jump", facing)
        val landR = r?.clip?.phases?.get("land")
        step = null
        if (r != null && landR != null) {
            play(r, fade = 0.06f); phaseRange = landR; phaseLoop = false; clipMs = 0f
            val ms = (landR.last - landR.first + 1) * r.clip.frameMs / 1000f
            // hold the landing frames, then continue the plan
            queue.addFirst(Step.Pose("__land", ms))
        }
        pending?.let { p -> pending = null; val land = queue.firstOrNull()?.takeIf { it is Step.Pose && it.action == "__land" }; queue.clear(); if (land != null) queue.add(land); queue.addAll(p) }
    }

    // ================================================================= climbing
    private fun startClimb() {
        if (!lib.has("climb") || !host.climbing()) { step = null; return }
        val gy = groundYAt()
        val side = if (x - host.minX() < host.maxX() - x) -1 else 1
        val edge = if (side > 0) host.maxX() else host.minX()
        // only from the wall itself (no teleport), facing the wall, with room above
        if (gy - host.topY() < host.win() * 1.2f || abs(x - edge) > host.win() * 0.1f || facing != side) { step = null; return }
        wall = side; x = edge
        mode = Mode.CLIMB; climbDir = -1
        climbTargetY = host.topY() + (gy - host.topY()) * (0.15f + rnd.nextFloat() * 0.45f)
        play(lib.pose("climb", facing)); pose.shadow = 0f; step = null
    }

    private fun updateClimb(dt: Float) {
        val c = cur?.clip ?: return
        val rate = host.userSpeed()
        advance(dt * 1000f * rate)
        y += climbDir * c.groundSpeed * host.spriteScale() * rate * dt
        if (climbDir < 0 && y <= climbTargetY) { y = climbTargetY; mode = Mode.CLING; clingT = 2f + rnd.nextFloat() * 4f }
        else if (climbDir > 0 && y >= groundYAt() - host.win() * 0.1f) jumpOffWall()
    }

    private fun jumpOffWall() {
        val side = wall; wall = 0
        vx = -side * host.win() * 1.1f; vy = -host.win() * 0.8f
        facing = if (side != 0) -side else facing
        startFall()
    }

    // ================================================================= output
    private fun writePose() {
        val r = cur ?: return
        pose.file = r.clip.file
        pose.frame = frameOf()
        pose.mirror = r.mirror
        pose.blend = fadeT
        pose.depthScale = depthScale()
        pose.rotation = if (mode == Mode.CLIMB || mode == Mode.CLING) -90f * wall else 0f
        if (mode == Mode.GROUND || mode == Mode.SLEEP) pose.shadow = 1f
        if (mode == Mode.CLIMB || mode == Mode.CLING) pose.shadow = 0f
        pose.action = r.clip.action
        pose.dir = if (r.mirror) r.clip.dir.mirrored() else r.clip.dir
    }

    companion object {
        const val TURN_TIME = 0.32f
        const val FAR_SHRINK = 0.22f
        /** The 12-frame turn clip is played 1.5x faster (0.8 s), close to a real puppy turning on the spot. */
        const val TURN_CLIP_RATE = 1.5f
        const val ACCEL_TIME = 0.45f
        const val DIAG_DRIFT = 0.35f
        private fun approach(a: Float, b: Float, step: Float): Float = if (abs(b - a) <= step) b else a + sign(b - a) * step
    }
}
