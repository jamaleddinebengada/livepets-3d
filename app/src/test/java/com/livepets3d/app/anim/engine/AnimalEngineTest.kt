package com.livepets3d.app.anim.engine

import java.io.File
import kotlin.math.hypot
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
    check("manifest loads all 25 clips", l.clips.size == 25, "${l.clips.size}")
    check("every manifest clip has a sheet image", l.clips.all { File("$ASSETS${it.file}.webp").exists() })

    // ---- direction resolution
    check("walk right uses a side view", l.resolve("walk", Dir.E)!!.clip.dir == Dir.E)
    check("walk left uses the native left clip (no mirror)", l.resolve("walk", Dir.W)!!.let { it.clip.file == "walk_left" && !it.mirror })
    check("walk away uses the back view", l.resolve("walk", Dir.N)!!.clip.dir == Dir.N)
    check("walk toward uses the front view", l.resolve("walk", Dir.S)!!.clip.dir == Dir.S)
    for (d in listOf(Dir.NE, Dir.NW, Dir.SE, Dir.SW)) {
        val r = l.resolve("walk", d)!!
        val shown = if (r.mirror) r.clip.dir.mirrored() else r.clip.dir
        check("diagonal $d uses real 3/4 artwork (mirrored for left)", shown == d && r.mirror == (d.dx < 0), "${r.clip.file} mirror=${r.mirror}")
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
    var sawFrontDuringTurn = false; val turnFrames = ArrayList<Int>(); val turnMirrors = HashSet<Boolean>()
    var lastX = e.x; var wrongView = 0
    run {
        e.script(listOf(Step.Move(600f, 0f), Step.Move(80f, 0f), Step.Move(80f, 0.12f), Step.Move(80f, 0f, "run"),
            Step.Move(650f, 0f, "run"), Step.Jump(0.6f, 0.45f), Step.Move(300f, 0.1f), Step.Move(500f, 0f), Step.Move(150f, 0.12f), Step.Move(450f, 0f), Step.Pose("idle", 999f)))
        var t = 0f
        while (t < 90f) {
            e.update(dt); log(); t += dt
            val p = e.pose
            val dx = e.x - lastX; lastX = e.x
            if (e.mode == AnimalEngine.Mode.GROUND && abs(dx) > 0.5f && p.blend >= 1f && !(!p.dir.horizontal && !p.dir.depth)) {
                // moving sideways: never a front/back view, and the side view must face the movement
                if (p.dir.depth && abs(dx) > 1.6f) wrongView++
                if (p.dir.horizontal && p.dir.dx.toFloat() * dx < 0) wrongView++
            }
            if (e.currentAction() == "move" && p.file == "turn" && abs(dx) < 0.01f) { sawFrontDuringTurn = true; turnFrames.add(p.frame); turnMirrors.add(p.mirror) }
            if (e.pendingSteps == 0 && e.currentAction() == "idle") break
        }
    }
    check("no sideways move ever shows a front/back view or faces backwards", wrongView == 0, "violations=$wrongView")
    check("direction change plays the turn artwork (right->front->left)", sawFrontDuringTurn && turnFrames.contains(0) && turnFrames.contains(11), turnFrames.distinct().toString())
    check("turn artwork is mirrored for left->right turns", turnMirrors.size == 2, turnMirrors.toString())

    // sliding: distance per clip time must equal the clip's ground speed
    for ((file, t) in e.travel) {
        val c = l.clips.first { it.file == file }
        if (c.groundSpeed <= 0f || c.depthSpeed > 0f || t[1] < 500f) continue
        val measured = t[0] / t[1] * 1000f
        check("no foot sliding on '$file'", abs(measured - c.groundSpeed) / c.groundSpeed < 0.04f, "travel %.1f px/s vs stride %.1f px/s".format(measured, c.groundSpeed))
    }
    val diag = listOf("walk_front_right", "walk_front_right_2", "walk_back_right", "walk_back_right_2").filter { e.travel.containsKey(it) }
    check("diagonal walks were used on the scripted route", diag.size >= 2, diag.toString())
    for (file in diag) {
        val t = e.travel[file]!!; val c = l.clips.first { it.file == file }
        if (t[1] < 400f) continue
        val measured = t[0] / t[1] * 1000f
        check("diagonal '$file': sideways stride synced and moves in depth", abs(measured - c.groundSpeed) / c.groundSpeed < 0.04f && t[2] > 0.01f,
            "%.1f vs %.1f px/s, dz=%.2f".format(measured, c.groundSpeed, t[2]))
    }
    for ((file, t) in e.travel) {
        val c = l.clips.first { it.file == file }
        if (c.depthSpeed <= 0f || c.groundSpeed > 0f || t[1] < 500f) continue
        check("depth walk '$file' moves in depth", t[2] > 0.05f, "dz=%.2f".format(t[2]))
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
        check("autonomous diagonal roaming is chosen", (counts["roam"] ?: 0) > 0)
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
        e4.tap(); var w = 0
        while (e4.currentAction() != "happy" && w < 300) { e4.update(dt); w++ }
        repeat(5) { e4.update(dt) }
        check("tap -> happy reaction with hearts (after landing if it was mid-jump)", e4.pose.file == "happy" && "heart" in ht.fxs, "${e4.pose.file}")
        e4.dragStart(); e4.dragTo(300f, 900f, 0f); repeat(5) { e4.update(dt) }
        check("drag holds the airborne pose", e4.mode == AnimalEngine.Mode.DRAG && e4.pose.file == "jump")
        e4.release(0f, 0f); var t = 0
        while (e4.mode != AnimalEngine.Mode.GROUND && t < 300) { e4.update(dt); t++ }
        check("release -> short fall, lands on the floor where it was dropped", e4.mode == AnimalEngine.Mode.GROUND && abs(e4.y - e4.groundYAt()) < 1f && e4.y > 900f && e4.y < 900f + 390f * 0.4f, "y=%.0f frames=$t".format(e4.y))
        repeat(3) { e4.update(dt) }
        check("landing frames play after a fall", e4.pose.file == "jump" && e4.pose.frame in 12..15, "frame=${e4.pose.frame}")
        e4.nap(); repeat(120) { e4.update(dt) }
        check("nap -> sleeping in bed clip", e4.mode == AnimalEngine.Mode.SLEEP && e4.pose.file == "sleep")
        e4.tap(); repeat(3) { e4.update(dt) }
        check("tap wakes it with a soft cross-fade", e4.mode == AnimalEngine.Mode.GROUND && e4.pose.blend < 1f)
    }

    // ---- whole-screen life: bounds, coverage, no teleport, no hard cuts, moods
    run {
        val hh = Host(energy = 85f, happy = 70f, hunger = 30f)
        val e5 = AnimalEngine(l, hh, 99); e5.place(300f)
        var minY = 1e9f; var maxY = -1e9f; var outside = 0; var jumps = 0; var cuts = 0; var lastFile = e5.pose.file
        var px = e5.x; var py = e5.y; val moods = HashSet<Mood>(); val choices = HashSet<String>()
        for (i in 0 until 60 * 60 * 12) {           // 12 simulated minutes at 60 fps
            e5.update(dt)
            val p = e5.pose
            if (e5.mode == AnimalEngine.Mode.GROUND) { minY = minOf(minY, e5.y); maxY = maxOf(maxY, e5.y) }
            if (e5.x < hh.minX() - 0.5f || e5.x > hh.maxX() + 0.5f || e5.y < hh.topY() - 1f || e5.y > hh.groundY() + 0.5f) outside++
            if (hypot(e5.x - px, e5.y - py) > 390f * 0.25f) jumps++        // more than a quarter window in one frame
            if (p.file != lastFile && p.blend >= 1f) cuts++
            lastFile = p.file; px = e5.x; py = e5.y
            moods.add(e5.brain.mood); choices.add(e5.brain.lastChoice)
            if (i % 600 == 0) hh.energy = (hh.energy - 1.2f).coerceAtLeast(10f)
        }
        val cover = (maxY - minY) / (hh.groundY() - hh.topY())
        println("   12 min: vertical coverage %.0f%%, behaviours=%s, moods=%s".format(cover * 100, choices.sorted(), moods))
        check("explores the whole screen height, not just the bottom", cover > 0.5f, "%.0f%%".format(cover * 100))
        check("never leaves the visible area", outside == 0, "frames outside=$outside")
        check("no teleporting (max step per frame < 1/4 window)", jumps == 0, "jumps=$jumps")
        check("every animation change is cross-faded (no hard cuts)", cuts == 0, "cuts=$cuts")
        check("moods change over time (calm/curious/playful/tired...)", moods.size >= 3, moods.toString())
        check("hops between spots happen", "hop" in choices)
    }
    // ---- hop lands exactly on its target spot
    run {
        val e6 = AnimalEngine(l, Host(), 4); e6.place(200f)
        e6.script(listOf(Step.Hop(420f, 0.08f, 0.4f), Step.Pose("idle", 99f)))
        var air = false
        for (i in 0 until 400) { e6.update(dt); if (e6.mode == AnimalEngine.Mode.AIR) air = true }
        check("hop: flies and lands on the chosen spot", air && abs(e6.x - 420f) < 2f && abs(e6.z - 0.08f) < 0.01f && abs(e6.y - e6.groundYAt()) < 1f, "x=%.0f z=%.2f".format(e6.x, e6.z))
    }
    // ---- finger follow and stroking
    run {
        val hf = Host(); val e7 = AnimalEngine(l, hf, 6); e7.place(100f)
        repeat(20) { e7.update(dt) }
        for (i in 0 until 600) { e7.follow(500f, 0.1f); e7.update(dt) }
        check("follows the finger across the floor", abs(e7.x - 500f) < 3f && abs(e7.z - 0.1f) < 0.01f, "x=%.0f z=%.2f".format(e7.x, e7.z))
        e7.stopFollowing(); repeat(60) { e7.update(dt) }
        hf.fxs.clear(); e7.stroke(); repeat(10) { e7.update(dt) }; e7.stroke(); e7.stroke()
        check("stroking keeps the petting reaction going with hearts", e7.pose.file == "happy" && hf.fxs.count { it == "heart" } >= 3, hf.fxs.toString())
    }

    // ---- calm, natural pace: most of the time standing / resting, like a real dog
    for ((name, act, maxMoving) in listOf(Triple("calm", 0.55f, 0.28f), Triple("normal", 1f, 0.45f))) {
        val h = object : EngineHost by Host() { override fun activity() = act }
        val e = AnimalEngine(l, h, 31); e.place(300f)
        var moving = 0; var n = 0; var px = e.x; var py = e.y
        while (e.time < 600f) { e.update(1f / 30f); n++; if (hypot(e.x - px, e.y - py) > 0.3f) moving++; px = e.x; py = e.y }
        check("'$name' activity: moving at most ${(maxMoving * 100).toInt()}% of the time", moving.toFloat() / n <= maxMoving, "%.0f%%".format(100f * moving / n))
    }

    println("\nMissing standard animations for husky:\n  " + l.missingReport().joinToString("\n  "))
    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures TEST(S) FAILED")
    return failures
}

fun main(args: Array<String>) { if (args.size > 1) ASSETS = args[1]; if (runEngineTests(args) > 0) System.exit(1) }

class AnimalEngineTest {
    @org.junit.Test fun huskyEngine() { org.junit.Assert.assertEquals("engine checks failed (see log)", 0, runEngineTests(emptyArray())) }
}
