package com.livepets3d.app.anim.engine

import kotlin.random.Random

/** Species personality: how much an animal likes each kind of behaviour (1 = neutral). */
class Profile(
    val walk: Float = 1f, val run: Float = 1f, val explore: Float = 1f, val stroll: Float = 1f,
    val play: Float = 1f, val rest: Float = 1f, val sleep: Float = 1f, val vocal: Float = 1f,
    val jump: Float = 1f, val climb: Float = 1f, val drink: Float = 1f, val idle: Float = 1f,
    /** Species-specific signature actions and their weights (only used when the clip exists). */
    val signature: Map<String, Float> = emptyMap(),
    /** Vocal clip name used for "barking": howl, bark, meow, roar... */
    val voice: String = "bark",
    /** Global speed feel (lion confident, elephant slow, fox quick). */
    val pace: Float = 1f,
) {
    companion object {
        val PROFILES = mapOf(
            "husky" to Profile(walk = 1f, run = 1.6f, explore = 1.4f, stroll = 1.1f, play = 1.5f, rest = 0.7f, sleep = 0.7f,
                vocal = 1.1f, jump = 1.3f, climb = 0.8f, signature = mapOf("howl" to 1f), voice = "howl", pace = 1.05f),
            "kitten" to Profile(run = 1.1f, explore = 1.3f, play = 1.3f, rest = 1.2f, vocal = 0.8f, jump = 1.4f, climb = 1.5f,
                signature = mapOf("groom" to 1.5f, "stretch" to 1.2f, "purr" to 0.8f), voice = "meow"),
            "puppy" to Profile(run = 1.4f, play = 1.6f, vocal = 1.1f, jump = 1.2f, signature = mapOf("tail_wag" to 1.4f), voice = "bark"),
            "penguin" to Profile(walk = 1.2f, run = 0.4f, climb = 0f, jump = 0.6f, signature = mapOf("slide" to 1.6f, "flap" to 1.3f), voice = "call", pace = 0.75f),
            "panda" to Profile(walk = 0.6f, run = 0.3f, rest = 1.6f, sleep = 1.7f, play = 0.9f, signature = mapOf("eat" to 1.5f, "roll" to 1.4f), voice = "panda", pace = 0.7f),
            "fox" to Profile(run = 1.4f, explore = 1.6f, jump = 1.4f, signature = mapOf("curious" to 1.6f), voice = "bark", pace = 1.15f),
            "lion" to Profile(walk = 1.2f, run = 0.8f, rest = 1.3f, vocal = 0.9f, signature = mapOf("stretch" to 1.5f, "roar" to 1f), voice = "roar", pace = 0.95f),
            "elephant" to Profile(walk = 1.3f, run = 0.2f, jump = 0f, climb = 0f, signature = mapOf("ears" to 1.4f, "trunk" to 1.4f), voice = "trumpet", pace = 0.6f),
        )
        fun of(name: String) = PROFILES[name] ?: Profile()
    }
}

/** Needs, 0..100 (hunger: 100 = starving). */
class Needs(val energy: Float, val happiness: Float, val hunger: Float)

/** What the brain sees when deciding. */
class BrainContext(
    val time: Float, val needs: Needs, val x: Float, val minX: Float, val maxX: Float, val win: Float,
    val others: List<Float>, val canClimb: Boolean, val gravity: Boolean, val restX: Float,
)

/** A behaviour plan: a short sequence of engine steps. */
sealed class Step {
    class Move(val x: Float, val z: Float, val gait: String = "walk") : Step()
    class Pose(val action: String, val seconds: Float = 0f, val cycles: Int = 0, val sound: String? = null, val fx: String? = null) : Step()
    class Sleep(val seconds: Float) : Step()
    class Jump(val forward: Float, val height: Float) : Step()
    class Climb : Step()
    class Face(val dir: Int) : Step()
    class Call(val f: () -> Unit) : Step()
}

/**
 * Weighted, need-driven behaviour selection with cooldowns, repetition penalty and personality.
 * Only behaviours whose animation clips exist for this animal are ever chosen, so every animal
 * shows its own movements instead of a shared generic set.
 */
class AnimalBrain(private val lib: ClipLibrary, private val rnd: Random) {
    val profile = Profile.of(lib.personality)
    private val cooldown = HashMap<String, Float>()
    private val history = ArrayDeque<String>()
    var lastChoice: String = ""; private set
    private var lastFoodRequest = -999f

    private fun ready(name: String, t: Float) = (cooldown[name] ?: -1f) <= t
    private fun cool(name: String, t: Float, sec: Float) { cooldown[name] = t + sec * (0.8f + rnd.nextFloat() * 0.4f) }

    private fun pickX(c: BrainContext, minDist: Float, maxDist: Float = Float.MAX_VALUE): Float {
        var best = c.x; var bestScore = -1f
        repeat(8) {
            val tx = c.minX + rnd.nextFloat() * (c.maxX - c.minX)
            val d = kotlin.math.abs(tx - c.x)
            if (d < minDist || d > maxDist) return@repeat
            // keep away from other pets (they are obstacles), prefer free space
            val crowd = c.others.minOfOrNull { kotlin.math.abs(it - tx) } ?: Float.MAX_VALUE
            val score = minOf(crowd, c.win * 2f) + rnd.nextFloat() * c.win * 0.5f
            if (score > bestScore) { bestScore = score; best = tx }
        }
        if (bestScore < 0f) best = if (c.x - c.minX > c.maxX - c.x) c.minX + (c.x - c.minX) * rnd.nextFloat() * 0.5f else c.maxX - (c.maxX - c.x) * rnd.nextFloat() * 0.5f
        return best
    }

    fun decide(c: BrainContext): List<Step> {
        val t = c.time; val n = c.needs; val p = profile
        val tired = n.energy < 25f; val sleepy = n.energy < 50f
        val hungry = n.hunger > 70f; val bored = n.happiness < 45f; val sad = n.happiness < 25f
        val opts = ArrayList<Triple<String, Float, () -> List<Step>>>()
        fun opt(name: String, w: Float, vararg needs: String, plan: () -> List<Step>) {
            if (w <= 0f || !ready(name, t) || needs.any { !lib.has(it) }) return
            var ww = w
            if (history.lastOrNull() == name) ww *= 0.25f else if (name in history) ww *= 0.6f
            opts.add(Triple(name, ww, plan))
        }
        val idleSec = { 1.5f + rnd.nextFloat() * 3f }
        val energyF = if (tired) 0.2f else if (sleepy) 0.6f else 1f

        opt("wander", 26f * p.walk, "walk") {
            listOf(Step.Move(pickX(c, c.win * 0.6f), 0f), Step.Pose("idle", idleSec()))
        }
        opt("explore", 14f * p.explore * energyF, "walk") {
            val far = pickX(c, c.win * 1.5f)
            val l = mutableListOf<Step>(Step.Move(far, 0f), Step.Pose("idle", 1.2f + rnd.nextFloat() * 1.5f))
            if (lib.has(p.voice) && rnd.nextFloat() < 0.25f * p.vocal) l.add(Step.Pose(p.voice, cycles = 1, sound = p.voice))
            l
        }
        opt("zoomies", 9f * p.run * energyF, "run") {
            cool("zoomies", t, 14f)
            val a = pickX(c, c.win * 2f)
            listOf(Step.Move(a, 0f, "run"), Step.Move(pickX(c.copyAt(a), c.win * 1.2f), 0f, "run"),
                Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1, sound = "pant"))
        }
        if (lib.resolve("walk", Dir.N) != null && lib.resolve("walk", Dir.S) != null)
            opt("stroll", 10f * p.stroll, "walk") {
                cool("stroll", t, 18f)
                val back = 0.55f + rnd.nextFloat() * 0.3f
                val gait = if (rnd.nextFloat() < 0.4f * p.run && lib.resolve("run", Dir.S) != null) "run" else "walk"
                listOf(Step.Move(c.x, back), Step.Pose("idle", 0.6f + rnd.nextFloat()), Step.Move(c.x, 0f, gait),
                    Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1))
            }
        if (lib.resolve("walk", Dir.NE)?.clip?.dir?.let { !it.horizontal && !it.depth } == true)
            opt("roam", 12f * p.explore, "walk") {
                // diagonal walk into the scene and back out somewhere else (uses the 3/4 views)
                cool("roam", t, 12f)
                val a = pickX(c, c.win * 0.8f); val depth = 0.35f + rnd.nextFloat() * 0.35f
                listOf(Step.Move(a, depth), Step.Pose("idle", 0.8f + rnd.nextFloat()), Step.Move(pickX(c.copyAt(a), c.win * 0.6f), 0f), Step.Pose("idle", 1f + rnd.nextFloat()))
            }
        opt("idle", 8f * p.idle, "idle") { listOf(Step.Pose("idle", idleSec())) }
        opt("play", (if (bored) 16f else 6f) * p.play * energyF, "play") {
            cool("play", t, 25f); listOf(Step.Pose("play", cycles = 2, fx = "note"), Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1))
        }
        opt("happy", 4f * (n.happiness / 60f).coerceIn(0.3f, 1.5f), "happy") { cool("happy", t, 10f); listOf(Step.Pose("happy", cycles = 2, fx = "heart")) }
        opt("vocal", 4f * p.vocal, p.voice) { cool("vocal", t, 50f); listOf(Step.Pose(p.voice, cycles = 1, sound = p.voice)) }
        opt("drink", 4f * p.drink, "drink") { cool("drink", t, 60f); listOf(Step.Pose("drink", cycles = 2, sound = "drink")) }
        if (c.gravity) opt("jump", 5f * p.jump * energyF, "jump") {
            cool("jump", t, 8f); listOf(Step.Jump(0.5f + rnd.nextFloat() * 0.6f, 0.35f + rnd.nextFloat() * 0.25f), Step.Pose("idle", 1f))
        }
        if (c.canClimb) opt("climb", 6f * p.climb * energyF, "climb", "run") {
            cool("climb", t, 40f)
            val side = if (rnd.nextBoolean()) c.minX else c.maxX
            listOf(Step.Move(side, 0f, "run"), Step.Climb())
        }
        val restAction = if (lib.has("rest")) "rest" else if (lib.has("lie_down")) "lie_down" else null
        if (restAction != null) opt("rest", (if (sleepy) 14f else 4f) * p.rest, "walk") {
            cool("rest", t, 30f); listOf(Step.Move(c.restX, 0f), Step.Pose(restAction, 6f + rnd.nextFloat() * 8f))
        }
        opt("sleep", (if (tired) 60f else if (sleepy) 8f else 1f) * p.sleep, "sleep", "walk") {
            cool("sleep", t, 90f); listOf(Step.Move(c.restX, 0f), Step.Sleep(15f + rnd.nextFloat() * 20f))
        }
        if (hungry && t - lastFoodRequest > 75f) opt("ask_food", 30f, "sad") {
            lastFoodRequest = t; listOf(Step.Pose("sad", cycles = 2, sound = "whine", fx = "food"))
        }
        if (sad) opt("sad", 12f, "sad") { cool("sad", t, 30f); listOf(Step.Pose("sad", cycles = 2, sound = "whine")) }
        for ((action, w) in p.signature) if (action != p.voice) opt("sig_$action", 6f * w, action) {
            cool("sig_$action", t, 20f); listOf(Step.Pose(action, cycles = 2))
        }

        if (tired) for (i in opts.indices) { val o = opts[i]; if (o.first !in CALM) opts[i] = Triple(o.first, o.second * 0.2f, o.third) }
        if (opts.isEmpty()) return listOf(Step.Pose("idle", 2f))
        val total = opts.sumOf { it.second.toDouble() }.toFloat()
        var r = rnd.nextFloat() * total
        var chosen = opts.last()
        for (o in opts) { r -= o.second; if (r <= 0f) { chosen = o; break } }
        lastChoice = chosen.first
        history.addLast(chosen.first); while (history.size > 4) history.removeFirst()
        return chosen.third()
    }

    companion object { private val CALM = setOf("sleep", "rest", "ask_food", "sad", "idle") }

    private fun BrainContext.copyAt(nx: Float) = BrainContext(time, needs, nx, minX, maxX, win, others, canClimb, gravity, restX)
}
