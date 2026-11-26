package com.livepets3d.app.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.livepets3d.app.anim.Anim
import com.livepets3d.app.anim.PetController
import com.livepets3d.app.data.Accessory
import com.livepets3d.app.data.BodyType
import com.livepets3d.app.data.EarType
import com.livepets3d.app.data.Look
import com.livepets3d.app.data.PetSpecies
import com.livepets3d.app.data.TailType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Pose parameters of the procedural rig (degrees / unit fractions). */
class Pose {
    var yaw = 0f; var bodyY = 0f; var pitch = 0f; var roll = 0f; var squash = 1f
    var hYaw = 0f; var hPitch = 0f; var hRoll = 0f
    val legs = FloatArray(4)          // FL, FR, BL, BR; positive = forward
    var sit = 0f; var lie = 0f; var wag = 0f; var tailUp = 0.5f
    var earL = 0f; var earR = 0f; var blink = 0f; var lookX = 0f; var lookY = 0f
    var mouth = 0f; var wing = 0f; var eyeBig = 1f; var climb = 0f; var brow = 0f

    fun blendTo(t: Pose, a: Float, aFast: Float) {
        yaw += (t.yaw - yaw) * a * 0.8f
        bodyY += (t.bodyY - bodyY) * aFast; pitch += (t.pitch - pitch) * a; roll += (t.roll - roll) * a
        squash += (t.squash - squash) * aFast
        hYaw += (t.hYaw - hYaw) * a; hPitch += (t.hPitch - hPitch) * a; hRoll += (t.hRoll - hRoll) * a
        for (i in 0..3) legs[i] += (t.legs[i] - legs[i]) * aFast
        sit += (t.sit - sit) * a; lie += (t.lie - lie) * a * 0.7f
        wag += (t.wag - wag) * aFast; tailUp += (t.tailUp - tailUp) * a
        earL += (t.earL - earL) * aFast; earR += (t.earR - earR) * aFast
        blink += (t.blink - blink) * aFast; lookX += (t.lookX - lookX) * a; lookY += (t.lookY - lookY) * a
        mouth += (t.mouth - mouth) * aFast; wing += (t.wing - wing) * aFast; eyeBig += (t.eyeBig - eyeBig) * aFast
        climb += (t.climb - climb) * a * 0.9f
        brow += (t.brow - brow) * a
    }
}

class PetRenderer(private val ctl: PetController) {
    private lateinit var furP: Program
    private lateinit var solidP: Program
    private lateinit var sphere: Mesh
    private lateinit var earMesh: Mesh
    private lateinit var longEar: Mesh
    private lateinit var horn: Mesh
    private lateinit var torus: Mesh
    private lateinit var quad: Mesh
    private lateinit var wingMesh: Mesh
    private var noiseTex = 0

    private val ms = MatStack()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val nm = FloatArray(16)
    private val tmp = FloatArray(16)
    private val vec = FloatArray(4)
    private val out4 = FloatArray(4)
    private val cam = floatArrayOf(0f, 1.95f, 7.6f)
    private val look = floatArrayOf(0f, 1.42f, 0f)
    private val light = floatArrayOf(-0.55f, 0.9f, 0.8f)
    private val force = FloatArray(3)

    private var w = 1; private var h = 1
    private val pose = Pose()
    private val tgt = Pose()
    private var time = 0f
    private var animT = 0f
    private var serial = -1
    private var gait = 0f
    private var blinkTimer = 2f
    private var blinkT = -1f
    private var twitchTimer = 3f
    private var twitchT = -1f
    private var twitchSide = 1
    private var spinY = 0f
    private var spinZ = 0f
    private var shells = 18

    // ------------------------------------------------------------- colours (cached per species)
    private var cachedSpecies: PetSpecies? = null
    private lateinit var lk: Look
    private var cPrim = FloatArray(3); private var cSec = FloatArray(3); private var cAcc = FloatArray(3)
    private var cInner = FloatArray(3); private var cEye = FloatArray(3); private var cNose = FloatArray(3)
    private var cLeg = FloatArray(3); private var cPaw = FloatArray(3); private var cTip = FloatArray(3)
    private val cMouth = rgb(0x5A1824); private val cTongue = rgb(0xF0627C); private val cGold = rgb(0xFFC83D)
    private val cHorn = rgb(0xFFE7B0); private val cSclera = floatArrayOf(0.05f, 0.04f, 0.06f)
    private var cCollar = rgb(0xE0283C)

    fun init() {
        furP = Program(Shaders.COMMON_VS, Shaders.FUR_FS)
        solidP = Program(Shaders.COMMON_VS, Shaders.SOLID_FS)
        sphere = Mesh.sphere(30, 20)
        earMesh = Mesh.taper(0.92f, 0.9f)
        longEar = Mesh.taper(0.55f, 1.6f)
        horn = Mesh.taper(0.97f, 0.7f, 16, 12)
        torus = Mesh.torus(0.22f)
        quad = Mesh.quad()
        wingMesh = Mesh.wing()
        noiseTex = makeNoise()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
    }

    fun resize(width: Int, height: Int) {
        w = max(1, width); h = max(1, height)
        GLES30.glViewport(0, 0, w, h)
    }

    private var cachedSkin = -2
    private fun ensureLook() {
        val sp = ctl.species
        val skinKey = ctl.skinPrimary xor (ctl.skinAccent shl 3) xor ctl.skinLeg
        if (sp == cachedSpecies && skinKey == cachedSkin) return
        cachedSpecies = sp; cachedSkin = skinKey
        lk = sp.look ?: PetSpecies.KITTEN.look!!
        val prim = if (ctl.skinPrimary >= 0) ctl.skinPrimary else lk.primary
        val sec = if (ctl.skinSecondary >= 0) ctl.skinSecondary else lk.secondary
        val acc = if (ctl.skinAccent >= 0) ctl.skinAccent else lk.accent
        cPrim = rgb(prim); cSec = rgb(sec); cAcc = rgb(acc); cInner = rgb(lk.inner)
        cEye = rgb(lk.eye); cNose = rgb(lk.nose)
        val legDefault = if (lk.legColor == lk.primary) prim else lk.legColor
        cLeg = rgb(if (ctl.skinLeg >= 0) ctl.skinLeg else legDefault)
        cPaw = rgb(if (lk.pawColor == lk.secondary) sec else if (ctl.skinLeg >= 0) ctl.skinLeg else lk.pawColor)
        cTip = rgb(if (lk.tailTipColor >= 0) (if (ctl.skinSecondary >= 0) sec else lk.tailTipColor) else prim)
    }
    private val cWhite = floatArrayOf(0.97f, 0.96f, 0.95f)
    private val cBrow: FloatArray get() = rgb(if (lk.brows >= 0) lk.brows else 0)
    private val cBeak = rgb(0xFF8A24)

    // =================================================================== frame
    fun frame(dt0: Float) {
        val dt = min(dt0, 0.05f)
        ensureLook()
        time += dt
        if (ctl.animSerial != serial) { serial = ctl.animSerial; animT = 0f }
        animT += dt
        shells = if (ctl.batterySaver) 10 else 18
        cCollar = rgb(ctl.collarColor)

        solvePose(dt)

        // camera
        val aspect = w.toFloat() / h
        val fov = 28f / ctl.zoom
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.5f, 40f)
        Matrix.setLookAtM(view, 0, cam[0], cam[1], cam[2], look[0], look[1], look[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        ms.reset()
        publishFoot()

        // contact shadow (floor only)
        val shadowA = ctl.shadow * (1f - abs(pose.climb)).coerceIn(0f, 1f)
        if (shadowA > 0.02f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
            ms.push(); ms.t(0f, 0.005f, 0f); ms.s(1.05f, 1f, 0.75f)
            solid(quad, 4, cPrim, alpha = shadowA)
            ms.pop()
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // climbing: rotate whole pet so its feet face the wall
        ms.push()
        ms.t(0f, 0.75f, 0f); ms.rz(pose.climb * 90f); ms.t(0f, -0.75f, 0f)
        if (lk.body == BodyType.PENGUIN) drawPenguin() else drawPet()
        ms.pop()
    }

    private fun publishFoot() {
        vec[0] = 0f; vec[1] = 0f; vec[2] = 0f; vec[3] = 1f
        Matrix.multiplyMV(out4, 0, vp, 0, vec, 0)
        ctl.footFrac = 0.5f - 0.5f * (out4[1] / out4[3])
        vec[1] = 1.55f
        Matrix.multiplyMV(out4, 0, vp, 0, vec, 0)
        ctl.headFrac = 0.5f - 0.5f * (out4[1] / out4[3])
        vec[0] = 0.78f; vec[1] = 0.75f
        Matrix.multiplyMV(out4, 0, vp, 0, vec, 0)
        ctl.wallFrac = 0.5f + 0.5f * (out4[0] / out4[3])
    }

    // =================================================================== animation
    private fun bump(t: Float, a: Float, b: Float): Float {
        if (t <= a || t >= b) return 0f
        return sin(((t - a) / (b - a)) * PI.toFloat())
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    private fun solvePose(dt: Float) {
        val g = time; val t = animT
        val a = ctl.anim
        val speed = ctl.moveSpeed
        // defaults
        val climbSide = ctl.climbSide
        tgt.climb = climbSide.toFloat()
        tgt.yaw = if (climbSide != 0) ctl.facing * 82f else ctl.facing * 64f
        tgt.bodyY = 0f; tgt.pitch = 0f; tgt.roll = 0f
        tgt.squash = 1f + 0.014f * sin(g * 2.3f)
        tgt.hYaw = if (abs(ctl.facing) > 0.1f) -ctl.facing * 22f else 12f * sin(g * 0.41f) + 6f * sin(g * 1.07f)
        tgt.hPitch = 3f * sin(g * 0.63f); tgt.hRoll = 0f
        for (i in 0..3) tgt.legs[i] = 0f
        tgt.sit = 0f; tgt.lie = 0f; tgt.wag = 12f * sin(g * 1.7f); tgt.tailUp = 0.55f
        tgt.earL = 0f; tgt.earR = 0f; tgt.blink = 0f
        tgt.lookX = ctl.lookX; tgt.lookY = ctl.lookY
        tgt.mouth = lk.smile; tgt.wing = 8f * sin(g * 2.2f); tgt.eyeBig = 1f; tgt.brow = 0f
        spinY = 0f; spinZ = 0f
        if (ctl.tracking) { tgt.hYaw = ctl.lookX * 38f; tgt.hPitch = -ctl.lookY * 22f; tgt.yaw = ctl.lookX * 18f }

        when (a) {
            Anim.IDLE -> {}
            Anim.LOOK -> {
                tgt.hYaw = 48f * sin(t * 1.25f); tgt.hPitch = -8f + 6f * sin(t * 0.9f)
                tgt.lookX = sin(t * 1.25f); tgt.earL = -6f; tgt.earR = 6f * sin(t * 2f)
            }
            Anim.WALK, Anim.CLIMB -> {
                val f = (if (a == Anim.CLIMB) 2.4f else 1.6f) + 1.4f * speed
                gait += dt * f * 2f * PI.toFloat()
                val s = sin(gait)
                val amp = if (a == Anim.CLIMB) 34f else 26f
                tgt.legs[0] = amp * s; tgt.legs[1] = -amp * s; tgt.legs[2] = -amp * s; tgt.legs[3] = amp * s
                tgt.bodyY = 0.035f * abs(sin(gait + 1.57f)); tgt.hPitch = 3f * sin(2f * gait)
                tgt.wag = 16f * s; tgt.tailUp = 0.72f; tgt.roll = 2.5f * s
            }
            Anim.RUN, Anim.CHASE -> {
                val f = 3.0f + 1.3f * speed
                gait += dt * f * 2f * PI.toFloat()
                val s = sin(gait); val s2 = sin(gait + 0.45f)
                tgt.legs[0] = 50f * s; tgt.legs[1] = 50f * s2; tgt.legs[2] = -50f * s; tgt.legs[3] = -50f * s2
                tgt.pitch = 7f * sin(gait + 1.2f); tgt.bodyY = 0.11f * abs(sin(gait))
                tgt.earL = -20f; tgt.earR = -20f; tgt.tailUp = 0.9f; tgt.mouth = 0.35f; tgt.wag = 10f * s
                tgt.wing = 30f * sin(g * 12f)
                if (a == Anim.CHASE) { tgt.hPitch = -20f; tgt.lookY = 0.8f; tgt.mouth = 0.5f }
            }
            Anim.JUMP -> {
                tgt.legs[0] = 55f; tgt.legs[1] = 50f; tgt.legs[2] = -50f; tgt.legs[3] = -55f
                tgt.pitch = -12f; tgt.squash = 1.07f; tgt.tailUp = 1f; tgt.earL = 14f; tgt.earR = 14f
                tgt.mouth = 0.35f; tgt.wing = 45f * sin(g * 16f)
            }
            Anim.FALL, Anim.HELD -> {
                for (i in 0..3) tgt.legs[i] = 14f * sin(g * 9f + i * 1.7f)
                tgt.squash = 1.05f; tgt.eyeBig = 1.14f; tgt.mouth = 0.45f; tgt.tailUp = 0.12f
                tgt.hPitch = -12f; tgt.wag = 26f * sin(g * 7f); tgt.earL = 16f; tgt.earR = 16f
                tgt.wing = 50f * sin(g * 18f); tgt.lookY = 0.4f
            }
            Anim.LAND -> {
                val b = bump(t, 0f, 0.3f)
                tgt.squash = 1f - 0.18f * b; tgt.bodyY = -0.06f * b; tgt.earL = -12f * b; tgt.earR = -12f * b
            }
            Anim.SIT -> { tgt.sit = 1f; tgt.wag = 9f * sin(g * 1.4f); tgt.tailUp = 0.3f }
            Anim.SLEEP -> {
                tgt.lie = 1f; tgt.blink = 1f; tgt.squash = 1f + 0.03f * sin(g * 1.35f)
                tgt.hPitch = 16f; tgt.hYaw = 24f; tgt.hRoll = 10f; tgt.earL = -14f; tgt.earR = -14f
                tgt.tailUp = 0.0f; tgt.wag = 62f; tgt.wing = 0f; tgt.mouth = 0f
            }
            Anim.REST -> {
                tgt.lie = 1f; tgt.blink = 0.62f; tgt.hPitch = 10f; tgt.mouth = 0f; tgt.wag = 8f * sin(g * 0.9f); tgt.tailUp = 0.1f
                tgt.squash = 1f + 0.025f * sin(g * 1.6f)
            }
            Anim.WAKE -> {
                tgt.lie = 1f - smooth((t - 0.2f) / 1.1f)
                val y = bump(t, 0.35f, 1.45f)
                tgt.blink = max(1f - smooth((t - 0.2f) / 0.5f), y)
                tgt.mouth = 0.95f * y; tgt.hPitch = -24f * y; tgt.earL = -12f * y; tgt.earR = -12f * y
            }
            Anim.STRETCH -> {
                val b = bump(t, 0.1f, 2.1f)
                tgt.pitch = 24f * b; tgt.legs[0] = 58f * b; tgt.legs[1] = 54f * b; tgt.legs[2] = -10f * b; tgt.legs[3] = -12f * b
                tgt.bodyY = -0.1f * b; tgt.tailUp = 0.55f + 0.45f * b; tgt.hPitch = -18f * b
                tgt.mouth = 0.6f * bump(t, 0.6f, 1.8f); tgt.blink = 0.85f * bump(t, 0.5f, 1.9f)
            }
            Anim.YAWN -> {
                val b = bump(t, 0.15f, 1.65f)
                tgt.sit = 1f; tgt.hPitch = -28f * b; tgt.mouth = 0.98f * b; tgt.blink = b; tgt.earL = -12f * b; tgt.earR = -12f * b
            }
            Anim.EAT -> {
                tgt.pitch = 10f; tgt.hPitch = 36f; tgt.hYaw = 0f; tgt.mouth = 0.45f + 0.45f * sin(t * 13f)
                tgt.sit = 0.25f; tgt.wag = 20f * sin(g * 3f); tgt.tailUp = 0.75f; tgt.blink = 0.3f
            }
            Anim.DANCE -> {
                val s = sin(t * 6.2f)
                tgt.roll = 13f * s; tgt.bodyY = 0.1f * abs(s); tgt.hRoll = -15f * s
                tgt.legs[0] = 38f * s; tgt.legs[1] = -38f * s; tgt.legs[2] = -12f * s; tgt.legs[3] = 12f * s
                tgt.wag = 32f * sin(t * 12f); tgt.tailUp = 0.95f; tgt.mouth = 0.6f
                val cyc = t % 4f
                if (cyc > 2.4f && cyc < 3.4f) spinY = 360f * smooth((cyc - 2.4f) / 1f)
            }
            Anim.PLAY -> {
                val s = sin(t * 7f)
                tgt.legs[0] = 48f * max(0f, s); tgt.legs[1] = 48f * max(0f, -s); tgt.legs[2] = -14f; tgt.legs[3] = -14f
                tgt.pitch = 14f + 5f * s; tgt.bodyY = 0.06f * abs(s); tgt.wag = 36f * sin(t * 10f); tgt.tailUp = 0.95f
                tgt.mouth = 0.4f; tgt.hPitch = 8f; tgt.earL = 10f; tgt.earR = 10f
            }
            Anim.ROLL -> {
                spinZ = 360f * smooth(t / 1.4f); tgt.lie = 0.5f; tgt.mouth = 0.6f
                for (i in 0..3) tgt.legs[i] = 20f * sin(g * 10f + i)
            }
            Anim.CLING -> {
                tgt.legs[0] = 30f; tgt.legs[1] = 22f; tgt.legs[2] = -30f; tgt.legs[3] = -22f
                tgt.yaw = climbSide * 40f; tgt.hYaw = -climbSide * 30f + 10f * sin(g * 0.6f)
                tgt.wag = 12f * sin(g * 2f); tgt.tailUp = 0.3f
            }
            Anim.HAPPY -> {
                val fade = 1f - (t / 1.6f).coerceIn(0f, 1f)
                tgt.bodyY = 0.17f * abs(sin(t * 8.5f)) * fade; tgt.wag = 42f * sin(t * 16f); tgt.tailUp = 1f
                tgt.mouth = 0.75f; tgt.eyeBig = 1.08f; tgt.earL = 12f; tgt.earR = 12f; tgt.hRoll = 9f * sin(t * 5f)
                tgt.legs[0] = 20f * abs(sin(t * 8.5f)); tgt.legs[1] = tgt.legs[0]
            }
            Anim.SURPRISED -> {
                tgt.eyeBig = 1.22f; tgt.squash = 1f + 0.12f * bump(t, 0f, 0.3f); tgt.bodyY = 0.13f * bump(t, 0f, 0.42f)
                tgt.earL = 20f; tgt.earR = 20f; tgt.mouth = 0.6f; tgt.tailUp = 1f
            }
            Anim.HIDE -> {
                tgt.squash = 0.84f; tgt.bodyY = -0.05f; tgt.earL = -35f; tgt.earR = -35f; tgt.hPitch = 22f
                tgt.blink = 0.35f; tgt.tailUp = 0f; tgt.sit = 0.4f
            }
            Anim.SCRATCH -> {
                tgt.sit = 1f; tgt.legs[3] = 70f + 25f * sin(t * 24f); tgt.hRoll = 18f; tgt.hYaw = 25f; tgt.blink = 0.75f
            }
            Anim.WAVE -> {
                tgt.sit = 1f; tgt.legs[1] = 118f + 18f * sin(t * 10f); tgt.mouth = 0.55f; tgt.hRoll = 10f * sin(t * 3f)
                tgt.earL = 8f; tgt.earR = 8f
            }
            Anim.PETTED -> {
                tgt.blink = 1f; tgt.hRoll = 14f * sin(t * 1.6f); tgt.squash = 1f + 0.02f * sin(t * 3f)
                tgt.wag = 12f * sin(t * 2f); tgt.mouth = 0.15f; tgt.earL = -12f; tgt.earR = -12f; tgt.sit = 0.6f
            }
            Anim.SAD -> {
                tgt.hPitch = 24f; tgt.earL = -30f; tgt.earR = -30f; tgt.tailUp = 0f; tgt.wag = 4f * sin(g)
                tgt.mouth = 0f; tgt.brow = 1f; tgt.blink = 0.35f; tgt.sit = 0.7f; tgt.squash = 0.95f; tgt.lookY = -0.5f
            }
            Anim.DRINK -> {
                tgt.pitch = 10f; tgt.hPitch = 38f; tgt.mouth = 0.35f + 0.25f * sin(t * 18f); tgt.sit = 0.25f
                tgt.blink = 0.5f; tgt.wag = 10f * sin(g * 2f)
            }
            Anim.SLIDE -> {
                tgt.pitch = 80f; tgt.wing = -45f; tgt.mouth = 0.6f; tgt.eyeBig = 1.1f; tgt.hPitch = -70f
                for (i in 0..3) tgt.legs[i] = -60f
            }
            Anim.TAILCHASE -> {
                spinY = -720f * smooth(t / 2.4f); tgt.hYaw = -55f; tgt.hPitch = 10f; tgt.mouth = 0.5f
                gait += dt * 3.2f * 2f * PI.toFloat(); val s = sin(gait)
                tgt.legs[0] = 30f * s; tgt.legs[1] = -30f * s; tgt.legs[2] = -30f * s; tgt.legs[3] = 30f * s
                tgt.tailUp = 0.9f; tgt.wag = 30f * sin(t * 9f)
            }
            Anim.CELEBRATE -> {
                if (t < 1.1f) spinY = 360f * smooth(t / 1.1f)
                tgt.bodyY = 0.18f * abs(sin(t * 7f)); tgt.mouth = 0.8f; tgt.wag = 40f * sin(t * 14f)
                tgt.tailUp = 1f; tgt.eyeBig = 1.1f; tgt.wing = 45f * sin(g * 16f)
            }
        }

        // independent micro-life: blinks, ear twitches
        blinkTimer -= dt
        if (blinkTimer < 0f && blinkT < 0f) { blinkT = 0f; blinkTimer = 1.8f + Random.nextFloat() * 3.8f }
        var autoBlink = 0f
        if (blinkT >= 0f) {
            blinkT += dt
            autoBlink = bump(blinkT, 0f, 0.17f)
            if (blinkT > 0.17f) blinkT = -1f
        }
        twitchTimer -= dt
        if (twitchTimer < 0f) { twitchT = 0f; twitchTimer = 2.5f + Random.nextFloat() * 5f; twitchSide = if (Random.nextBoolean()) 1 else -1 }
        if (twitchT >= 0f) {
            twitchT += dt
            val tw = bump(twitchT, 0f, 0.28f) * 18f
            if (twitchSide > 0) tgt.earR += tw else tgt.earL += tw
            if (twitchT > 0.28f) twitchT = -1f
        }

        val k = 1f - exp(-dt * 7f)
        val kf = 1f - exp(-dt * 16f)
        pose.blendTo(tgt, k, kf)
        pose.blink = max(pose.blink, autoBlink)

        // fur dynamics: gravity + inertia from movement
        force[0] = -ctl.facing * speed * 0.6f + 0.06f * sin(g * 1.3f)
        force[1] = -0.45f
        force[2] = 0f
    }

    // =================================================================== model
    private fun drawPet() {
        val p = pose
        val bs = lk.bodyScale
        ms.push()
        ms.ry(p.yaw + spinY)
        ms.t(0f, p.bodyY - 0.15f * p.sit - 0.30f * p.lie, 0f)
        ms.rz(p.roll)
        if (spinZ != 0f) { ms.t(0f, 0.6f, 0f); ms.rz(spinZ); ms.t(0f, -0.6f, 0f) }
        val sq = p.squash
        ms.s(1f / sqrt(sq), sq, 1f / sqrt(sq))
        // sit pivots around the back hips, pitch around the body centre
        ms.t(0f, 0.45f, -0.42f); ms.rx(-28f * p.sit); ms.t(0f, -0.45f, 0.42f)
        ms.t(0f, 0.6f, 0f); ms.rx(p.pitch); ms.t(0f, -0.6f, 0f)

        // ---- legs
        val legC = cLeg; val pawC = cPaw
        val sitComp = -28f * p.sit
        leg(-0.24f * bs, 0.5f, 0.3f, p.legs[0] + sitComp + 75f * p.lie, 1f + 0.3f * p.sit - 0.12f * p.lie, legC, pawC, false)
        leg(0.24f * bs, 0.5f, 0.3f, p.legs[1] + sitComp + 75f * p.lie, 1f + 0.3f * p.sit - 0.12f * p.lie, legC, pawC, false)
        leg(-0.27f * bs, 0.5f, -0.36f, p.legs[2] + 62f * p.sit - 20f * p.lie, 1f - 0.35f * p.sit - 0.55f * p.lie, legC, pawC, true)
        leg(0.27f * bs, 0.5f, -0.36f, p.legs[3] + 62f * p.sit - 20f * p.lie, 1f - 0.35f * p.sit - 0.55f * p.lie, legC, pawC, true)

        // ---- body
        ms.push(); ms.t(0f, 0.66f, -0.04f); ms.s(0.5f * bs, 0.47f * bs, 0.64f * bs)
        part(sphere, cPrim, cSec, lenK = 1f, du = 60f, dv = 34f, mx = 0f, my = -0.55f, mz = 0.85f, mw = if (lk.eyePatches) 0f else 1f, e0 = 0.2f, e1 = 0.7f, stripes = lk.stripes)
        ms.pop()
        // panda shoulder band
        if (lk.eyePatches) {
            ms.push(); ms.t(0f, 0.8f, 0.12f); ms.s(0.52f * bs, 0.36f, 0.3f)
            part(sphere, cAcc, cAcc, lenK = 0.9f, du = 50f, dv = 26f)
            ms.pop()
        }

        // ---- tail
        drawTail()
        // ---- wings & spikes (dragon)
        if (lk.wings) {
            for (side in intArrayOf(-1, 1)) {
                ms.push(); ms.t(side * 0.26f, 0.98f, -0.12f)
                ms.ry(side * -32f); ms.rz(side * (22f + p.wing)); ms.s(side * 0.85f, 0.78f, 0.6f)
                solid(wingMesh, 5, cInner)
                ms.pop()
            }
            for (i in 0..2) {
                ms.push(); ms.t(0f, 1.1f - i * 0.03f, 0.12f - i * 0.25f); ms.rx(-18f); ms.s(0.055f, 0.11f, 0.035f)
                solid(horn, 0, cSec)
                ms.pop()
            }
        }
        // ---- collar
        if (ctl.clothes > 0) drawClothes(0.84f, 0.36f, 0.46f)
        if (lk.collar && ctl.collarColor != 0 && ctl.clothes == 0) {
            ms.push(); ms.t(0f, 0.9f, 0.3f); ms.rx(-55f); ms.s(0.37f, 0.5f, 0.37f)
            solid(torus, 0, cCollar)
            ms.pop()
            ms.push(); ms.t(0f, 0.74f, 0.62f); ms.rx(-20f); ms.s(0.085f, 0.085f, 0.035f)
            solid(sphere, 0, cGold)
            ms.pop()
        }

        // ---- head
        ms.push()
        ms.t(0f, 0.92f - 0.24f * p.lie, 0.28f + 0.1f * p.lie)
        ms.ry(p.hYaw); ms.rx(p.hPitch + 22f * p.sit); ms.rz(p.hRoll)
        ms.t(0f, 0.42f, 0.12f)
        drawHead()
        ms.pop()

        ms.pop()
    }

    private fun leg(x: Float, y: Float, z: Float, swing: Float, len: Float, c: FloatArray, paw: FloatArray, back: Boolean) {
        ms.push(); ms.t(x, y, z); ms.rx(-swing)
        if (back) { ms.push(); ms.t(0f, -0.04f, 0f); ms.s(0.2f, 0.22f, 0.25f); part(sphere, cPrim, cPrim, lenK = 0.9f, du = 34f, dv = 22f); ms.pop() }
        ms.push(); ms.t(0f, -0.2f * len, 0f); ms.s(0.145f, 0.24f * len, 0.145f)
        part(sphere, c, c, lenK = 0.6f, du = 30f, dv = 22f)
        ms.pop()
        ms.push(); ms.t(0f, -0.42f * len, 0.045f); ms.s(0.15f, 0.1f, 0.18f)
        part(sphere, paw, paw, lenK = 0.45f, du = 24f, dv = 14f)
        ms.pop()
        ms.pop()
    }

    private fun drawTail() {
        val p = pose
        ms.push()
        when (lk.tail) {
            TailType.POM -> {
                ms.t(0f, 0.68f, -0.68f); ms.ry(p.wag * 0.2f); ms.s(0.15f)
                part(sphere, cSec, cSec, lenK = 1.7f, du = 26f, dv = 16f)
            }
            else -> {
                val n: Int; val seg: Float; val base: Float; val curl: Float
                when (lk.tail) {
                    TailType.THIN_LONG -> { n = 8; seg = 0.115f; base = -12f; curl = 6f + 12f * p.tailUp }
                    TailType.FLUFFY -> { n = 6; seg = 0.1f; base = 5f; curl = 8f + 12f * p.tailUp }
                    TailType.BUSHY -> { n = 7; seg = 0.13f; base = -30f; curl = 3f + 7f * p.tailUp }
                    else -> { n = 8; seg = 0.13f; base = -32f; curl = 2f + 5f * p.tailUp }
                }
                ms.t(0f, 0.78f, -0.6f)
                ms.rx(base + 30f * p.tailUp - 20f * p.lie)
                for (i in 0 until n) {
                    val f = i.toFloat() / (n - 1)
                    ms.ry(p.wag / n * (0.6f + f * 1.4f))
                    ms.rx(curl)
                    ms.t(0f, 0f, -seg)
                    val r = when (lk.tail) {
                        TailType.THIN_LONG -> 0.085f - 0.025f * f
                        TailType.FLUFFY -> 0.09f + 0.05f * sin(f * 3.1f)
                        TailType.BUSHY -> 0.12f + 0.13f * sin(f * 2.9f + 0.15f)
                        else -> 0.13f - 0.085f * f
                    }
                    ms.push(); ms.s(r, r, r * 1.35f)
                    val col = if (lk.tail == TailType.BUSHY && i >= n - 2) cTip else cPrim
                    val lenK = when (lk.tail) { TailType.BUSHY -> 1.6f; TailType.FLUFFY -> 1.35f; else -> 0.8f }
                    part(sphere, col, col, lenK = lenK, du = 26f, dv = 16f)
                    ms.pop()
                }
                if (lk.tail == TailType.DRAGON) {
                    ms.t(0f, 0f, -0.06f); ms.rx(-90f); ms.s(0.08f, 0.15f, 0.025f)
                    solid(horn, 0, cAcc)
                }
            }
        }
        ms.pop()
    }

    private fun drawHead() {
        val p = pose
        val hs = lk.headScale
        val isCatLike = lk.ear == EarType.POINTY
        // skull
        ms.push(); ms.s(0.62f * hs, 0.56f * hs, 0.55f * hs)
        val faceMask = if (isCatLike || lk.scales) 1f else 0f
        part(sphere, cPrim, cSec, lenK = 0.85f, du = 64f, dv = 34f, mx = 0f, my = -0.55f, mz = 0.83f, mw = faceMask, e0 = 0.55f, e1 = 0.85f, stripes = lk.stripes * 0.7f, sx = 1f, sy = 0f, sz = 0f, bald = true)
        ms.pop()
        // cheek fluff
        if (isCatLike) for (side in intArrayOf(-1, 1)) {
            ms.push(); ms.t(side * 0.33f * hs, -0.2f * hs, 0.26f * hs); ms.s(0.2f, 0.15f, 0.16f)
            part(sphere, cSec, cSec, lenK = 1.15f, du = 22f, dv = 14f)
            ms.pop()
        }
        // muzzle
        ms.push(); ms.t(0f, -0.17f * hs, 0.42f * hs); ms.s(0.27f, 0.19f, 0.2f)
        part(sphere, cSec, cSec, lenK = 0.45f, du = 30f, dv = 18f)
        ms.pop()
        // nose
        ms.push(); ms.t(0f, -0.07f * hs, 0.605f * hs); ms.s(0.075f, 0.055f, 0.05f)
        solid(sphere, 0, cNose)
        ms.pop()
        // mouth + tongue
        ms.push(); ms.t(0f, (-0.27f - 0.02f * p.mouth) * hs, 0.585f * hs); ms.s(0.105f + 0.03f * p.mouth, 0.016f + 0.085f * p.mouth, 0.055f)
        solid(sphere, 3, cMouth)
        ms.pop()
        if (lk.tongue && p.mouth > 0.18f) {
            ms.push(); ms.t(0f, (-0.3f - 0.045f * p.mouth) * hs, 0.6f * hs); ms.rx(18f); ms.s(0.075f, 0.032f, 0.045f + 0.035f * p.mouth)
            solid(sphere, 3, cTongue)
            ms.pop()
        }
        // eyes
        val es = lk.eyeScale * p.eyeBig
        val open = max(0.07f, 1f - p.blink)
        for (side in intArrayOf(-1, 1)) {
            if (lk.eyePatches) {
                ms.push(); ms.t(side * 0.25f * hs, 0.0f, 0.45f * hs); ms.rz(side * -30f); ms.s(0.2f, 0.26f, 0.1f)
                part(sphere, cAcc, cAcc, lenK = 0.3f, du = 26f, dv = 18f)
                ms.pop()
            }
            ms.push(); ms.t(side * 0.235f * hs, 0.035f * hs, 0.475f * hs)
            ms.ry(side * 14f + p.lookX * 14f); ms.rx(-p.lookY * 12f)
            ms.s(0.165f * es, 0.19f * es * open, 0.105f)
            if (lk.sclera) solid(sphere, 1, cEye, c2 = cWhite, iris = 0.6f) else solid(sphere, 1, cEye, c2 = cSclera)
            ms.pop()
            if (lk.brows >= 0) {
                ms.push(); ms.t(side * 0.23f * hs, (0.25f + 0.03f * p.brow) * hs * es, 0.5f * hs)
                ms.ry(side * 16f); ms.rz(side * (-p.brow * 30f)); ms.s(0.1f, 0.022f, 0.035f)
                solid(sphere, 3, cBrow)
                ms.pop()
            }
        }
        // ears
        for (side in intArrayOf(-1, 1)) {
            val twitch = if (side < 0) p.earL else p.earR
            ms.push()
            when (lk.ear) {
                EarType.POINTY -> {
                    val big = lk.earScale
                    ms.t(side * 0.32f * hs, 0.38f * hs, -0.04f); ms.rz(-side * (22f - twitch * 0.4f)); ms.rx(-8f + twitch * 0.5f)
                    ms.push(); ms.s(0.2f * big, 0.3f * big, 0.1f * big)
                    part(earMesh, cPrim, cAcc, lenK = 0.45f, du = 26f, dv = 18f, mx = 0f, my = 1f, mz = 0f, mw = if (lk.tail == TailType.BUSHY) 1f else 0f, e0 = 0.55f, e1 = 0.75f)
                    ms.pop()
                    ms.push(); ms.t(0f, -0.04f, 0.055f * big); ms.s(0.12f * big, 0.2f * big, 0.045f)
                    solid(earMesh, 3, cInner)
                    ms.pop()
                }
                EarType.FLOPPY -> {
                    ms.t(side * 0.52f * hs, 0.16f * hs, -0.04f); ms.rz(side * (14f + twitch * 0.6f)); ms.rx(twitch * 0.3f)
                    ms.t(0f, -0.22f * lk.earScale, 0f); ms.s(0.17f * lk.earScale, 0.35f * lk.earScale, 0.12f)
                    part(sphere, cAcc, cAcc, lenK = 0.9f, du = 26f, dv = 20f)
                }
                EarType.ROUND -> {
                    ms.t(side * 0.39f * hs, 0.42f * hs, -0.05f); ms.rz(-side * twitch * 0.3f); ms.s(0.19f, 0.19f, 0.1f)
                    part(sphere, cAcc, cAcc, lenK = 0.7f, du = 24f, dv = 16f)
                }
                EarType.LONG -> {
                    ms.t(side * 0.17f * hs, 0.46f * hs, -0.08f); ms.rz(-side * (9f - twitch * 0.5f)); ms.rx(-12f + twitch)
                    ms.t(0f, 0.42f, 0f)
                    ms.push(); ms.s(0.13f, 0.46f, 0.08f)
                    part(longEar, cPrim, cPrim, lenK = 0.5f, du = 22f, dv = 26f)
                    ms.pop()
                    ms.push(); ms.t(0f, -0.02f, 0.05f); ms.s(0.075f, 0.37f, 0.04f)
                    solid(longEar, 3, cInner)
                    ms.pop()
                }
                EarType.NONE -> {}
                EarType.HORNS -> {
                    ms.push(); ms.t(side * 0.22f * hs, 0.42f * hs, -0.12f); ms.rz(-side * 16f); ms.rx(-28f)
                    ms.t(0f, 0.14f, 0f); ms.s(0.075f, 0.2f, 0.075f)
                    solid(horn, 0, cHorn)
                    ms.pop()
                    ms.t(side * 0.52f * hs, 0.12f * hs, -0.06f); ms.rz(side * (-62f + twitch)); ms.s(0.13f, 0.21f, 0.04f)
                    solid(earMesh, 5, cInner)
                }
            }
            ms.pop()
        }
        drawAccessories(0.5f * hs, 0.475f * hs, 0.035f * hs, 0.235f * hs)
    }

    // =================================================================== penguin (biped rig)
    private fun drawPenguin() {
        val p = pose
        ms.push()
        ms.ry(p.yaw + spinY)
        // waddle: roll from side to side with the gait, slide: lie on the belly
        val waddle = (p.legs[0] - p.legs[1]) * 0.22f
        val slideK = (p.pitch / 80f).coerceIn(0f, 1f)
        ms.t(0f, p.bodyY - 0.12f * p.sit - 0.25f * p.lie - 0.16f * slideK, 0f)
        ms.rz(p.roll + waddle)
        if (spinZ != 0f) { ms.t(0f, 0.7f, 0f); ms.rz(spinZ); ms.t(0f, -0.7f, 0f) }
        val sq = p.squash
        ms.s(1f / sqrt(sq), sq, 1f / sqrt(sq))
        ms.t(0f, 0.72f, 0f); ms.rx(p.pitch); ms.t(0f, -0.72f, 0f)
        // feet
        for (side in intArrayOf(-1, 1)) {
            val lift = max(0f, (if (side < 0) p.legs[0] else p.legs[1])) * 0.004f
            ms.push(); ms.t(side * 0.24f, 0.05f + lift, 0.16f); ms.ry(side * 12f); ms.s(0.17f, 0.07f, 0.24f)
            solid(sphere, 6, cBeak)
            ms.pop()
            for (toe in -1..1) {
                ms.push(); ms.t(side * 0.24f + toe * 0.085f, 0.045f + lift, 0.36f); ms.s(0.06f, 0.055f, 0.08f)
                solid(sphere, 6, cBeak); ms.pop()
            }
        }
        // body egg + cream belly
        ms.push(); ms.t(0f, 0.72f, 0f); ms.s(0.62f, 0.66f, 0.56f)
        part(sphere, cPrim, cPrim, lenK = 1f, du = 50f, dv = 30f)
        ms.pop()
        ms.push(); ms.t(0f, 0.66f, 0.12f); ms.s(0.5f, 0.55f, 0.47f)
        part(sphere, cSec, cSec, lenK = 1f, du = 46f, dv = 28f)
        ms.pop()
        // flippers
        for (side in intArrayOf(-1, 1)) {
            ms.push(); ms.t(side * 0.6f, 0.92f, 0f)
            ms.rz(side * (14f + p.wing * 0.8f + 10f * p.lie)); ms.t(0f, -0.26f, 0f); ms.s(0.12f, 0.34f, 0.2f)
            part(sphere, cPrim, cPrim, lenK = 0.8f, du = 24f, dv = 18f)
            ms.pop()
        }
        if (ctl.clothes > 0) drawClothes(1.24f, 0.04f, 0.5f)
        // head
        ms.push()
        ms.t(0f, 1.22f - 0.15f * p.lie, 0.05f)
        ms.ry(p.hYaw); ms.rx(p.hPitch); ms.rz(p.hRoll)
        ms.t(0f, 0.36f, 0.02f)
        val hs = 1f
        ms.push(); ms.s(0.6f, 0.55f, 0.55f)
        part(sphere, cPrim, cPrim, lenK = 1f, du = 56f, dv = 32f, bald = true)
        ms.pop()
        // cream face mask (two cheeks joined under the beak)
        for (side in intArrayOf(-1, 1)) {
            ms.push(); ms.t(side * 0.2f, -0.04f, 0.3f); ms.s(0.27f, 0.31f, 0.26f)
            part(sphere, cSec, cSec, lenK = 0.8f, du = 26f, dv = 18f)
            ms.pop()
        }
        ms.push(); ms.t(0f, -0.19f, 0.3f); ms.s(0.36f, 0.22f, 0.24f)
        part(sphere, cSec, cSec, lenK = 0.8f, du = 26f, dv = 18f)
        ms.pop()
        // hair tuft
        for (i in -1..1) {
            ms.push(); ms.t(i * 0.07f, 0.52f, -0.02f); ms.rz(-i * 25f); ms.s(0.04f, 0.1f, 0.04f)
            solid(horn, 6, cAcc); ms.pop()
        }
        // eyes + brows
        val es = lk.eyeScale * p.eyeBig
        val open = max(0.07f, 1f - p.blink)
        for (side in intArrayOf(-1, 1)) {
            ms.push(); ms.t(side * 0.2f, 0.02f, 0.53f)
            ms.ry(side * 12f + p.lookX * 14f); ms.rx(-p.lookY * 12f)
            ms.s(0.13f * es, 0.15f * es * open, 0.09f)
            solid(sphere, 1, cEye, c2 = cWhite, iris = 0.62f)
            ms.pop()
            ms.push(); ms.t(side * 0.2f, (0.2f + 0.03f * p.brow) * es, 0.53f); ms.ry(side * 16f); ms.rz(side * (-p.brow * 30f)); ms.s(0.08f, 0.018f, 0.03f)
            solid(sphere, 3, cBrow); ms.pop()
        }
        // beak (opens with mouth)
        ms.push(); ms.t(0f, -0.12f + 0.015f * p.mouth, 0.6f); ms.rx(-90f + 6f * p.mouth); ms.s(0.13f, 0.13f, 0.07f)
        solid(horn, 6, cBeak); ms.pop()
        ms.push(); ms.t(0f, -0.17f - 0.04f * p.mouth, 0.58f); ms.rx(-95f - 18f * p.mouth); ms.s(0.1f, 0.09f, 0.05f)
        solid(horn, 6, cBeak); ms.pop()
        drawAccessories(0.5f, 0.53f, 0.02f, 0.2f)
        ms.pop()
        ms.pop()
    }

    // =================================================================== accessories
    /** Draws hat / glasses in the current head frame. */
    private fun drawAccessories(top: Float, faceZ: Float, eyeY: Float, eyeX: Float) {
        val acc = Accessory.values()
        if (ctl.hat > 0) {
            val a = acc[ctl.hat - 1]; val col = rgb(a.color)
            ms.push(); ms.t(0f, top, -0.02f)
            when (a) {
                Accessory.PARTY_HAT -> {
                    ms.rz(-12f); ms.push(); ms.t(0f, 0.2f, 0f); ms.s(0.2f, 0.28f, 0.2f); solid(horn, 0, col); ms.pop()
                    ms.push(); ms.t(0f, 0.47f, 0f); ms.s(0.07f); solid(sphere, 6, rgb(0xFFC83D)); ms.pop()
                }
                Accessory.BEANIE -> {
                    ms.push(); ms.t(0f, -0.04f, 0f); ms.s(0.5f, 0.3f, 0.48f); solid(sphere, 6, col); ms.pop()
                    ms.push(); ms.t(0f, -0.1f, 0f); ms.rx(0f); ms.s(0.47f, 0.35f, 0.47f); solid(torus, 6, rgb(0xFFFFFF)); ms.pop()
                    ms.push(); ms.t(0f, 0.28f, 0f); ms.s(0.1f); solid(sphere, 6, rgb(0xFFFFFF)); ms.pop()
                }
                Accessory.CROWN -> {
                    ms.push(); ms.s(0.26f, 0.5f, 0.26f); solid(torus, 0, col); ms.pop()
                    for (i in 0 until 5) {
                        ms.push(); ms.ry(i * 72f); ms.t(0f, 0.1f, 0.25f); ms.s(0.05f, 0.11f, 0.03f); solid(horn, 0, col); ms.pop()
                    }
                    ms.push(); ms.t(0f, 0.04f, 0.29f); ms.s(0.04f); solid(sphere, 0, rgb(0xEF4444)); ms.pop()
                }
                Accessory.BOW -> {
                    ms.t(0.3f, 0.0f, 0.14f); ms.rz(-20f); ms.s(1.35f)
                    for (side in intArrayOf(-1, 1)) { ms.push(); ms.t(side * 0.11f, 0f, 0f); ms.rz(side * 20f); ms.s(0.11f, 0.08f, 0.05f); solid(sphere, 0, col); ms.pop() }
                    ms.push(); ms.s(0.05f); solid(sphere, 0, col); ms.pop()
                }
                else -> {}
            }
            ms.pop()
        }
        if (ctl.glasses > 0) {
            val a = acc[ctl.glasses - 1]; val col = rgb(a.color)
            for (side in intArrayOf(-1, 1)) {
                ms.push(); ms.t(side * eyeX, eyeY, faceZ + 0.1f); ms.rx(90f); ms.s(0.14f, 0.3f, 0.14f)
                solid(torus, 0, col); ms.pop()
                if (a == Accessory.SUNGLASSES) {
                    ms.push(); ms.t(side * eyeX, eyeY, faceZ + 0.1f); ms.s(0.13f, 0.13f, 0.02f)
                    solid(sphere, 0, rgb(0x241A3A)); ms.pop()
                }
            }
            ms.push(); ms.t(0f, eyeY + 0.03f, faceZ + 0.12f); ms.s(0.06f, 0.015f, 0.015f); solid(sphere, 0, col); ms.pop()
        }
    }

    /** Neck / chest wear, drawn in the body frame. [neckY], [neckZ] = neck centre, [r] = radius. */
    private fun drawClothes(neckY: Float, neckZ: Float, r: Float) {
        val a = Accessory.values()[ctl.clothes - 1]; val col = rgb(a.color)
        when (a) {
            Accessory.SCARF -> {
                ms.push(); ms.t(0f, neckY, neckZ); ms.rx(-15f); ms.s(r, 0.9f, r); solid(torus, 6, col); ms.pop()
                ms.push(); ms.t(r * 0.55f, neckY - 0.2f, neckZ + r * 0.75f); ms.rz(-10f); ms.s(0.09f, 0.22f, 0.04f); solid(sphere, 6, col); ms.pop()
            }
            Accessory.BANDANA -> {
                ms.push(); ms.t(0f, neckY, neckZ); ms.rx(-15f); ms.s(r * 0.95f, 0.6f, r * 0.95f); solid(torus, 6, col); ms.pop()
                ms.push(); ms.t(0f, neckY - 0.14f, neckZ + r * 0.92f); ms.rx(-10f); ms.rz(180f); ms.s(0.18f, 0.18f, 0.03f); solid(horn, 6, col); ms.pop()
            }
            Accessory.SWEATER -> {
                ms.push(); ms.t(0f, neckY - 0.33f, neckZ - 0.1f); ms.s(r * 1.12f, 0.5f, r * 1.1f); solid(sphere, 6, col); ms.pop()
                ms.push(); ms.t(0f, neckY - 0.02f, neckZ); ms.rx(-15f); ms.s(r * 0.85f, 0.9f, r * 0.85f); solid(torus, 6, rgb(0xFFFFFF)); ms.pop()
            }
            else -> {}
        }
    }

    // =================================================================== draw helpers
    /** Draws a furry part, or a scaly one for furless species. */
    private fun part(
        mesh: Mesh, c: FloatArray, c2: FloatArray, lenK: Float, du: Float, dv: Float,
        mx: Float = 0f, my: Float = 1f, mz: Float = 0f, mw: Float = 0f, e0: Float = 0.3f, e1: Float = 0.8f,
        stripes: Float = 0f, sx: Float = 0f, sy: Float = 0f, sz: Float = 1f, bald: Boolean = false,
    ) {
        if (lk.body == BodyType.PENGUIN) { solid(mesh, 6, c); return }
        if (lk.furLength <= 0f) {
            solid(mesh, 2, c, c2 = cSec, mx = mx, my = my, mz = mz, mw = mw, e0 = e0, e1 = e1, du = du * 0.55f, dv = dv * 0.55f)
            return
        }
        val prg = furP
        prg.use()
        common(prg)
        GLES30.glUniform1f(prg.u("uFurLen"), lk.furLength * lenK)
        GLES30.glUniform1f(prg.u("uShells"), shells.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glUniform1i(prg.u("uNoise"), 0)
        GLES30.glUniform2f(prg.u("uDensity"), du / 256f * 3f, dv / 256f * 3f)
        GLES30.glUniform3fv(prg.u("uCol"), 1, c, 0)
        GLES30.glUniform3fv(prg.u("uCol2"), 1, c2, 0)
        GLES30.glUniform3fv(prg.u("uCol3"), 1, cAcc, 0)
        GLES30.glUniform4f(prg.u("uMask"), mx, my, mz, mw)
        GLES30.glUniform2f(prg.u("uMaskEdge"), e0, e1)
        GLES30.glUniform1f(prg.u("uStripes"), stripes)
        GLES30.glUniform3f(prg.u("uStripeDir"), sx, sy, sz)
        if (bald) {
            GLES30.glUniform4f(prg.u("uBald0"), -0.38f, 0.06f, 0.86f, 0.4f)
            GLES30.glUniform4f(prg.u("uBald1"), 0.38f, 0.06f, 0.86f, 0.4f)
        } else {
            GLES30.glUniform4f(prg.u("uBald0"), 0f, 0f, 0f, 0f)
            GLES30.glUniform4f(prg.u("uBald1"), 0f, 0f, 0f, 0f)
        }
        GLES30.glUniform1f(prg.u("uSoft"), if (lenK > 1.2f) 0.06f else 0f)
        mesh.draw(shells + 1)
    }

    private fun solid(
        mesh: Mesh, kind: Int, c: FloatArray, c2: FloatArray = c, alpha: Float = 1f,
        mx: Float = 0f, my: Float = -1f, mz: Float = 0f, mw: Float = 0f, e0: Float = 0.3f, e1: Float = 0.8f,
        du: Float = 30f, dv: Float = 18f, iris: Float = 0f,
    ) {
        val prg = solidP
        prg.use()
        common(prg)
        GLES30.glUniform1f(prg.u("uFurLen"), 0f)
        GLES30.glUniform1f(prg.u("uShells"), 0f)
        GLES30.glUniform1i(prg.u("uKind"), kind)
        GLES30.glUniform3fv(prg.u("uCol"), 1, c, 0)
        GLES30.glUniform3fv(prg.u("uCol2"), 1, c2, 0)
        GLES30.glUniform4f(prg.u("uMask"), mx, my, mz, mw)
        GLES30.glUniform2f(prg.u("uMaskEdge"), e0, e1)
        GLES30.glUniform2f(prg.u("uDensity"), du, dv)
        GLES30.glUniform1f(prg.u("uAlpha"), alpha)
        GLES30.glUniform1f(prg.u("uIris"), iris)
        mesh.draw(1)
    }

    private fun common(prg: Program) {
        GLES30.glUniformMatrix4fv(prg.u("uModel"), 1, false, ms.array, ms.offset)
        Matrix.invertM(tmp, 0, ms.array, ms.offset)
        Matrix.transposeM(nm, 0, tmp, 0)
        GLES30.glUniformMatrix4fv(prg.u("uNormalM"), 1, false, nm, 0)
        GLES30.glUniformMatrix4fv(prg.u("uVP"), 1, false, vp, 0)
        GLES30.glUniform3fv(prg.u("uForce"), 1, force, 0)
        GLES30.glUniform3fv(prg.u("uCam"), 1, cam, 0)
        GLES30.glUniform3fv(prg.u("uLight"), 1, light, 0)
    }

    private fun makeNoise(): Int {
        val n = 256
        val rnd = Random(1234)
        val grid = 16
        val g = Array(grid * grid) { rnd.nextFloat() }
        val bb = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until n) for (x in 0 until n) {
            // strand height: random per texel, biased so most strands are long
            val r = 1f - rnd.nextFloat() * rnd.nextFloat()
            // smooth colour variation (bilinear value noise, tiling)
            val fx = x.toFloat() / n * grid; val fy = y.toFloat() / n * grid
            val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
            val tx = fx - ix; val ty = fy - iy
            fun gv(a: Int, b: Int) = g[((b % grid + grid) % grid) * grid + ((a % grid + grid) % grid)]
            val v = (gv(ix, iy) * (1 - tx) + gv(ix + 1, iy) * tx) * (1 - ty) + (gv(ix, iy + 1) * (1 - tx) + gv(ix + 1, iy + 1) * tx) * ty
            bb.put((r * 255).toInt().toByte()); bb.put((v * 255).toInt().toByte()); bb.put((rnd.nextFloat() * 255).toInt().toByte()); bb.put(255.toByte())
        }
        bb.position(0)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, n, n, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return tex[0]
    }
}
