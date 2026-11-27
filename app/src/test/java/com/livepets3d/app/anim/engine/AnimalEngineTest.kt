package com.livepets3d.app.anim.engine

import java.io.File
import kotlin.math.abs

var ASSETS = "src/main/assets/sprites/husky/"
var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS " else "FAIL ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

class Host(var energy: Float = 80f, var happy: Float = 70f, var hunger: Float = 30f) : EngineHost {
    val sounds = ArrayList<String>(); val fxs = ArrayList<String>()
    override fun minX() = 0f
    override fun maxX() = 1080f - 390f
    override fun groundY() = 2000f
    override fun topY() = 100f
    override fun win() = 390f
    override fun spriteScale() = 390f * 0.9f / 340f
    override fun needs() = Needs(energy, happy, hunger)
    override fun modify(dHappy: Float, dHunger: Float, dEnergy: Float) { happy = (happy + dHappy).coerceIn(0f, 100f); hunger = (hunger + dHunger).coerceIn(0f, 100f); energy = (energy + dEnergy).coerceIn(0f, 100f) }
    override fun sound(event: String, volume: Float) { sounds.add(event) }
    override fun fx(kind: String, count: Int) { fxs.add(kind) }
}

fun lib(): ClipLibrary = ClipLibrary.parse(File(ASSETS + "manifest.json").readText()) { f -> File("$ASSETS$f.json").takeIf { it.exists() }?.readText() }

fun runEngineTests(args: Array<String>): Int {
    failures = 0
    val l = lib()
    check("manifest loads all 20 clips", l.clips.size == 20, "${l.clips.size}")
    check("every manifest clip has a sheet image", l.clips.all { File("$ASSETS${it.file}.webp").exists() })

    // ---- direction resolution
    check("walk right uses a side view", l.resolve("walk", Dir.E)!!.clip.dir == Dir.E)
    check("walk left uses the native left clip (no mirror)", l.resolve("walk", Dir.W)!!.let { it.clip.file == "walk_left" && !it.mirror })
    check("walk away uses the back view", l.resolve("walk", Dir.N)!!.clip.dir == Dir.N)
    check("walk toward uses the front view", l.resolve("walk", Dir.S)!!.clip.dir == Dir.S)
    for (d in listOf(Dir.NE, Dir.NW, Dir.SE, Dir.SW)) {
        val r = l.resolve("walk", d)!!
        val shown = if (r.mirror) r.clip.dir.mirrored() else r.clip.dir
        check("diagonal $d falls back to a compatible view", (shown.horizontal && shown.dx == d.dx) || (shown.depth && shown.dz == d.dz), "${r.clip.file} mirror=${r.mirror}")
    }
    check("run left mirrors the side run (side view, never front)", l.resolve("run", Dir.W)!!.let { it.mirror && it.clip.dir == Dir.E })

    // ---- scripted walk right then left: turning + no sliding
    val h = Host(); val e = AnimalEngine(l, h, 7)
    e.place(100f)
    val trace = File(args.getOrElse(0) { System.getProperty("java.io.tmpdir") + "/trace_test.csv" }).printWriter()
    trace.println("t,x,y,z,file,frame,mirror,prev,prevFrame,prevMirror,blend,scale,rot,shadow,mode,action")
    fun log() { val p = e.pose; trace.println("%.3f,%.1f,%.1f,%.3f,%s,%d,%b,%s,%d,%b,%.3f,%.3f,%.1f,%.2f,%s,%s".format(e.time, e.x, e.y, e.z, p.file, p.frame, p.mirror, p.prevFile, p.prevFrame, p.prevMirror, p.blend, p.depthScale, p.rotation, p.shadow, e.mode, e.currentAction())) }
    val dt = 1f / 60f
    fun runPlan(steps: List<Step>, maxSec: Float): Float {
        e.script(steps + Step.Pose("idle", 999f))
        var t = 0f
        while (t < maxSec) { e.update(dt); log(); t += dt; if (e.pendingSteps == 0 && e.currentAction() == "idle") break }
        return t
    }
    var sawFrontDuringTurn = false; var sideWhileHorizontal = true
    var lastX = e.x; var wrongView = 0
    run {
        e.script(listOf(Step.Move(600f, 0f), Step.Move(80f, 0f), Step.Move(80f, 0.75f), Step.Move(80f, 0f, "run"),
            Step.Move(650f, 0f, "run"), Step.Jump(0.6f, 0.45f), Step.Move(300f, 0.5f), Step.Move(500f, 0f), Step.Pose("idle", 999f)))
        var t = 0f
        while (t < 40f) {
            e.update(dt); log(); t += dt
            val p = e.pose
            val dx = e.x - lastX; lastX = e.x
            if (e.mode == AnimalEngine.Mode.GROUND && abs(dx) > 0.5f && p.blend >= 1f) {
                // moving sideways: never a front/back view, and the side view must face the movement
                if (p.dir.depth && abs(dx) > 1.6f) wrongView++
                if (p.dir.horizontal && p.dir.dx.toFloat() * dx < 0) wrongView++
            }
            if (e.currentAction() == "move" && p.dir == Dir.S && abs(dx) < 0.01f && e.z < 0.01f) sawFrontDuringTurn = true
            if (e.pendingSteps == 0 && e.currentAction() == "idle") break
        }
    }
    check("no sideways move ever shows a front/back view or faces backwards", wrongView == 0, "violations=$wrongView")
    check("direction change turns through the front view", sawFrontDuringTurn)

    // sliding: distance per clip time must equal the clip's ground speed
    for ((file, t) in e.travel) {
        val c = l.clips.first { it.file == file }
        if (c.groundSpeed <= 0f || t[1] < 500f) continue
        val measured = t[0] / t[1] * 1000f
        check("no foot sliding on '$file'", abs(measured - c.groundSpeed) / c.groundSpeed < 0.04f, "travel %.1f px/s vs stride %.1f px/s".format(measured, c.groundSpeed))
    }
    for ((file, t) in e.travel) {
        val c = l.clips.first { it.file == file }
        if (c.depthSpeed <= 0f || t[1] < 500f) continue
        check("depth walk '$file' moves in depth", t[2] > 0.2f, "dz=%.2f".format(t[2]))
    }
    trace.close()

    // ---- jump: air frames follow the trajectory, landing frames play
    run {
        val e2 = AnimalEngine(l, Host(), 3); e2.place(300f)
        e2.script(listOf(Step.Jump(0.5f, 0.5f), Step.Pose("idle", 99f)))
        val frames = ArrayList<Int>(); var minY = 1e9f; var landed = false; val modes = ArrayList<String>()
        for (i in 0 until 240) { e2.update(dt); if (e2.pose.file == "jump") frames.add(e2.pose.frame); minY = minOf(minY, e2.y); modes.add(e2.mode.name) }
        val air = frames.filter { it in 4..11 }
        check("jump plays takeoff -> air -> land frames in order", frames.isNotEmpty() && frames.first() in 0..3 && frames.contains(12) && frames.contains(15), frames.distinct().toString())
        check("air frames advance monotonically with the flight", air.zipWithNext().all { (a, b) -> b >= a })
        check("the animal actually leaves the ground", minY < 2000f - 100f, "apex=%.0f".format(2000f - minY))
    }

    // ---- brain: variety, personality, needs
    run {
        val counts = HashMap<String, Int>(); var repeats = 0; var n = 0
        // count decisions via a fresh brain with direct calls (deterministic & fast)
        val brain = AnimalBrain(l, kotlin.random.Random(5))
        var t = 0f; var lastC = ""
        for (i in 0 until 400) {
            brain.decide(BrainContext(t, Needs(80f, 70f, 20f), 300f, 0f, 690f, 390f, emptyList(), true, true, 20f))
            val c = brain.lastChoice; counts[c] = (counts[c] ?: 0) + 1; if (c == lastC) repeats++; lastC = c; n++; t += 8f
        }
        println("   behaviour mix (400 decisions, rested husky): " + counts.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        check("varied behaviour (no behaviour above 35%)", counts.values.maxOrNull()!! < n * 0.35f)
        check("rarely repeats the same behaviour twice in a row", repeats < n * 0.12f, "repeats=$repeats")
        check("husky personality: runs/plays/howls", (counts["zoomies"] ?: 0) > 0 && (counts["play"] ?: 0) > 0 && (counts["vocal"] ?: 0) > 0)
        val tiredBrain = AnimalBrain(l, kotlin.random.Random(9)); val tc = HashMap<String, Int>()
        for (i in 0 until 100) { tiredBrain.decide(BrainContext(i * 200f, Needs(12f, 70f, 20f), 300f, 0f, 690f, 390f, emptyList(), true, true, 20f)); tc[tiredBrain.lastChoice] = (tc[tiredBrain.lastChoice] ?: 0) + 1 }
        check("tired husky mostly sleeps or rests", (tc["sleep"] ?: 0) + (tc["rest"] ?: 0) > 40, tc.toString())
        val hungryBrain = AnimalBrain(l, kotlin.random.Random(4))
        val plan = hungryBrain.decide(BrainContext(100f, Needs(80f, 60f, 95f), 300f, 0f, 690f, 390f, emptyList(), true, true, 20f))
        var asked = hungryBrain.lastChoice == "ask_food"
        repeat(6) { if (!asked) { hungryBrain.decide(BrainContext(110f + it, Needs(80f, 60f, 95f), 300f, 0f, 690f, 390f, emptyList(), true, true, 20f)); asked = hungryBrain.lastChoice == "ask_food" } }
        check("hungry husky asks for food", asked)
        check("brain never picks a behaviour without artwork", counts.keys.none { it.startsWith("sig_") && !l.has(it.removePrefix("sig_")) })
    }

    // ---- touch
    run {
        val ht = Host(); val e4 = AnimalEngine(l, ht, 2); e4.place(300f)
        repeat(30) { e4.update(dt) }
        e4.tap(); repeat(5) { e4.update(dt) }
        check("tap -> happy reaction with hearts", e4.pose.file == "happy" && "heart" in ht.fxs)
        e4.dragStart(); e4.dragTo(300f, 900f, 0f); repeat(5) { e4.update(dt) }
        check("drag holds the airborne pose", e4.mode == AnimalEngine.Mode.DRAG && e4.pose.file == "jump")
        e4.release(0f, 0f); var t = 0
        while (e4.mode != AnimalEngine.Mode.GROUND && t < 300) { e4.update(dt); t++ }
        check("release -> falls with gravity and lands", e4.mode == AnimalEngine.Mode.GROUND && abs(e4.y - 2000f) < 1f, "frames=$t")
        repeat(3) { e4.update(dt) }
        check("landing frames play after a fall", e4.pose.file == "jump" && e4.pose.frame in 12..15, "frame=${e4.pose.frame}")
        e4.nap(); repeat(120) { e4.update(dt) }
        check("nap -> sleeping in bed clip", e4.mode == AnimalEngine.Mode.SLEEP && e4.pose.file == "sleep")
        e4.tap(); repeat(3) { e4.update(dt) }
        check("tap wakes it with a soft cross-fade", e4.mode == AnimalEngine.Mode.GROUND && e4.pose.blend < 1f)
    }

    println("\nMissing standard animations for husky:\n  " + l.missingReport().joinToString("\n  "))
    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures TEST(S) FAILED")
    return failures
}

fun main(args: Array<String>) { if (args.size > 1) ASSETS = args[1]; if (runEngineTests(args) > 0) System.exit(1) }

class AnimalEngineTest {
    @org.junit.Test fun huskyEngine() { org.junit.Assert.assertEquals("engine checks failed (see log)", 0, runEngineTests(emptyArray())) }
}
