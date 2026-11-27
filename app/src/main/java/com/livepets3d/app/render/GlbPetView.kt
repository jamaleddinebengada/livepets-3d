package com.livepets3d.app.render

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import com.livepets3d.app.anim.Anim
import com.livepets3d.app.anim.PetController
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Real-time skeletal 3D pet rendered with Google Filament (glTF 2.0 / GLB).
 *
 * Assets: assets/models/<id>.glb (+ optional <id>.json):
 *   { "clips": { "walk": "Walk_F", "run": "Run", ... }, "forward": "+z", "yawOffset": 0 }
 * Without a json, clips are matched by name (idle, walk, run, jump, sit, sleep, eat, groom, ...).
 * Behaviour states from [PetController] are cross-faded smoothly; the model turns through
 * full 360° to face its direction of travel and rotates onto screen edges while climbing.
 */
class GlbPetView(context: Context, val controller: PetController, private val modelId: String) : TextureView(context), Choreographer.FrameCallback {
    companion object {
        @Volatile private var inited = false
        fun ensureInit() { if (!inited) { Utils.init(); inited = true } }
        fun available(context: Context, id: String): Boolean =
            (context.assets.list("models") ?: emptyArray()).contains("$id.glb")
    }

    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    private val cameraEntity: Int
    private val sun: Int
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private val materials: UbershaderProvider
    private val assetLoader: AssetLoader
    private val resourceLoader: ResourceLoader
    private var asset: FilamentAsset? = null
    private var animator: Animator? = null
    private var looping = false
    private var lastNanos = 0L

    // clip blending
    private val clipIndex = HashMap<String, Int>()
    private var curClip = -1; private var curTime = 0f
    private var prevClip = -1; private var prevTime = 0f
    private var fade = 1f
    private var lastSerial = -1
    private var yaw = 0f
    private var climb = 0f
    private var yawOffset = 0f
    private val baseTransform = FloatArray(16)
    private val tmp = FloatArray(16); private val out = FloatArray(16)
    var targetFps = 60

    init {
        ensureInit()
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = floatArrayOf(0f, 0f, 0f, 0f) }

        sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN).color(1f, 0.96f, 0.9f).intensity(100_000f)
            .direction(0.35f, -1f, -0.55f).castShadows(false).build(engine, sun)
        scene.addEntity(sun)
        scene.indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 0.97f, 1.02f)).intensity(35_000f).build(engine)

        materials = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materials, EntityManager.get())
        resourceLoader = ResourceLoader(engine)
        loadModel()

        isOpaque = false
        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }
            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }
            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(28.0, width.toDouble() / max(1, height), 0.05, 50.0, Camera.Fov.VERTICAL)
            }
        }
        uiHelper.attachTo(this)
    }

    private fun loadModel() {
        try {
            val bytes = context.assets.open("models/$modelId.glb").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buf.flip()
            val a = assetLoader.createAsset(buf) ?: return
            resourceLoader.loadResources(a)
            a.releaseSourceData()
            scene.addEntities(a.entities)
            asset = a
            val anim = a.instance.animator
            animator = anim
            // clip name mapping: json override, else fuzzy match on clip names
            val names = (0 until anim.animationCount).map { anim.getAnimationName(it).lowercase() }
            val cfg = try { JSONObject(context.assets.open("models/$modelId.json").bufferedReader().readText()) } catch (_: Exception) { null }
            yawOffset = cfg?.optDouble("yawOffset", 0.0)?.toFloat() ?: 0f
            val explicit = cfg?.optJSONObject("clips")
            val keys = mapOf(
                "idle" to listOf("idle", "breath", "stand"), "walk" to listOf("walk"), "run" to listOf("run", "trot", "gallop"),
                "sprint" to listOf("sprint", "gallop", "run"), "jump" to listOf("jump"), "land" to listOf("land"),
                "sit" to listOf("sit"), "lie" to listOf("lie", "lay"), "sleep" to listOf("sleep"), "wake" to listOf("wake", "getup", "get_up"),
                "stretch" to listOf("stretch"), "groom" to listOf("groom", "lick", "clean"), "scratch" to listOf("scratch"),
                "eat" to listOf("eat"), "drink" to listOf("drink"), "play" to listOf("play", "pounce", "attack"),
                "climb" to listOf("climb"), "turnl" to listOf("turn_l", "turnleft", "turn left"), "turnr" to listOf("turn_r", "turnright", "turn right"),
                "back" to listOf("back"), "side" to listOf("strafe", "side"),
            )
            for ((k, cands) in keys) {
                val ex = explicit?.optString(k, "")?.lowercase().orEmpty()
                val idx = if (ex.isNotEmpty()) names.indexOf(ex) else names.indexOfFirst { n -> cands.any { n.contains(it) } }
                if (idx >= 0) clipIndex[k] = idx
            }
            // normalise size: model height -> 1.6 units, feet on y = 0
            val box = a.boundingBox
            val c = box.center; val h = box.halfExtent
            val s = 1.6f / max(1e-3f, 2f * h[1])
            Matrix.setIdentityM(baseTransform, 0)
            Matrix.scaleM(baseTransform, 0, s, s, s)
            Matrix.translateM(baseTransform, 0, -c[0], -(c[1] - h[1]), -c[2])
        } catch (e: Exception) {
            Log.e("LivePetsGLB", "model load failed", e)
        }
    }

    private fun clipFor(a: Anim): Int {
        fun pick(vararg k: String): Int { for (x in k) clipIndex[x]?.let { return it }; return clipIndex["idle"] ?: 0 }
        return when (a) {
            Anim.WALK, Anim.CHASE -> pick("walk")
            Anim.RUN, Anim.SLIDE -> pick("run", "walk")
            Anim.TAILCHASE -> pick("sprint", "run")
            Anim.JUMP -> pick("jump")
            Anim.FALL, Anim.HELD -> pick("jump", "idle")
            Anim.LAND -> pick("land", "idle")
            Anim.SIT, Anim.WAVE, Anim.SAD -> pick("sit")
            Anim.SLEEP -> pick("sleep", "lie")
            Anim.REST -> pick("lie", "sleep", "sit")
            Anim.WAKE -> pick("wake", "stretch")
            Anim.STRETCH, Anim.YAWN -> pick("stretch")
            Anim.SCRATCH -> pick("scratch", "groom")
            Anim.PETTED -> pick("groom", "sit")
            Anim.EAT -> pick("eat")
            Anim.DRINK -> pick("drink", "eat")
            Anim.PLAY, Anim.HAPPY, Anim.CELEBRATE, Anim.DANCE, Anim.ROLL -> pick("play", "jump")
            Anim.CLIMB, Anim.CLING -> pick("climb", "walk")
            else -> pick("idle")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        looping = true; lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
        destroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return
        Choreographer.getInstance().postFrameCallback(this)
        val ctl = controller
        if (ctl.paused) { lastNanos = 0L; return }
        val minGap = 1_000_000_000L / (if (ctl.batterySaver) 30 else targetFps) - 2_000_000L
        if (lastNanos != 0L && frameTimeNanos - lastNanos < minGap) return
        val dt = if (lastNanos == 0L) 1f / 60f else ((frameTimeNanos - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = frameTimeNanos
        val a = asset ?: return
        val anim = animator ?: return

        // ---- animation state + cross-fade
        if (ctl.animSerial != lastSerial) {
            lastSerial = ctl.animSerial
            val next = clipFor(ctl.anim)
            if (next != curClip) { prevClip = curClip; prevTime = curTime; curClip = next; curTime = 0f; fade = 0f }
        }
        val speedMul = when (ctl.anim) { Anim.WALK, Anim.RUN, Anim.CHASE -> 0.8f + 0.6f * ctl.moveSpeed; else -> 1f }
        curTime += dt * speedMul; prevTime += dt
        fade = (fade + dt / 0.3f).coerceAtMost(1f)
        if (curClip >= 0 && anim.animationCount > 0) {
            val d = anim.getAnimationDuration(curClip).coerceAtLeast(0.01f)
            anim.applyAnimation(curClip, curTime % d)
            if (fade < 1f && prevClip >= 0) {
                val pd = anim.getAnimationDuration(prevClip).coerceAtLeast(0.01f)
                anim.applyCrossFade(prevClip, prevTime % pd, 1f - fade)
            }
            anim.updateBoneMatrices()
        }

        // ---- orientation: smooth 360° turning toward travel direction, wall rotation
        val targetYaw = when {
            ctl.climbSide != 0 -> 90f * (if (ctl.facing >= 0) 1f else -1f)
            ctl.facing > 0.1f -> 90f
            ctl.facing < -0.1f -> -90f
            else -> 20f
        }
        var dy = targetYaw - yaw
        while (dy > 180f) dy -= 360f
        while (dy < -180f) dy += 360f
        yaw += dy * (1f - kotlin.math.exp(-dt * 6f))
        climb += (ctl.climbSide * 90f - climb) * (1f - kotlin.math.exp(-dt * 6f))

        val tm = engine.transformManager
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, 0f, 0.8f, 0f); Matrix.rotateM(tmp, 0, climb, 0f, 0f, 1f); Matrix.translateM(tmp, 0, 0f, -0.8f, 0f)
        Matrix.rotateM(tmp, 0, yaw + yawOffset, 0f, 1f, 0f)
        Matrix.multiplyMM(out, 0, tmp, 0, baseTransform, 0)
        tm.setTransform(tm.getInstance(a.root), out)

        camera.lookAt(0.0, 1.2, 5.2, 0.0, 0.75, 0.0, 0.0, 1.0, 0.0)
        ctl.footFrac = 0.9f; ctl.wallFrac = 0.8f; ctl.headFrac = 0.3f

        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) { renderer.render(view); renderer.endFrame() }
    }

    private var destroyed = false
    private fun destroy() {
        if (destroyed) return
        destroyed = true
        uiHelper.detach()
        asset?.let { scene.removeEntities(it.entities); assetLoader.destroyAsset(it) }
        materials.destroyMaterials(); materials.destroy()
        assetLoader.destroy(); resourceLoader.destroy()
        engine.destroyEntity(sun)
        engine.destroyRenderer(renderer); engine.destroyView(view); engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

}
