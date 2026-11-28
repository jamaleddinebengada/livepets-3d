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
            "husky" to Profile(walk = 1f, run = 1.15f, explore = 1.1f, stroll = 1f, play = 1.3f, rest = 0.9f, sleep = 0.9f,
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
    /** Current floor depth (0 bottom of the screen .. 1 top) and z units per on-screen depth unit. */
    val z: Float = 0f, val depthRate: Float = 1f,
    /** User setting: 0.5 calm, 1 normal, 1.7 lively. Scales how often the animal does active things. */
    val activity: Float = 1f,
)

/** Behaviour state shown by the animal; changes the weights of everything it does. */
enum class Mood { CALM, HAPPY, CURIOUS, TIRED, PLAYFUL }

/** A behaviour plan: a short sequence of engine steps. */
sealed class Step {
    class Move(val x: Float, val z: Float, val gait: String = "walk") : Step()
    class Pose(val action: String, val seconds: Float = 0f, val cycles: Int = 0, val sound: String? = null, val fx: String? = null) : Step()
    class Sleep(val seconds: Float) : Step()
    class Jump(val forward: Float, val height: Float) : Step()
    /** Jump from here to another spot of the floor (x, depth z). */
    class Hop(val x: Float, val z: Float, val height: Float) : Step()
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
    var mood = Mood.CALM; private set
    private var happyUntil = -1f; private var playfulUntil = -1f; private var curiousUntil = -1f

    /** Something happened to the animal (petted, fed, played) - colours its mood for a while. */
    fun onEvent(e: String, t: Float) {
        when (e) { "petted", "fed" -> happyUntil = t + 45f; "played" -> playfulUntil = t + 60f }
    }

    private fun updateMood(t: Float, n: Needs) {
        mood = when {
            n.energy < 35f -> Mood.TIRED
            t < happyUntil -> Mood.HAPPY
            t < playfulUntil -> Mood.PLAYFUL
            t < curiousUntil -> Mood.CURIOUS
            else -> {
                val r = rnd.nextFloat()
                when {
                    r < 0.12f -> { curiousUntil = t + 40f + rnd.nextFloat() * 40f; Mood.CURIOUS }
                    r < 0.20f && n.energy > 55f -> { playfulUntil = t + 30f + rnd.nextFloat() * 30f; Mood.PLAYFUL }
                    n.happiness > 75f && r < 0.3f -> { happyUntil = t + 30f; Mood.HAPPY }
                    else -> Mood.CALM
                }
            }
        }
    }

    private fun moodWeight(name: String): Float = when (mood) {
        Mood.PLAYFUL -> when (name) { "play" -> 2.2f; "zoomies" -> 1.8f; "hop", "jump" -> 1.6f; "happy" -> 1.3f; "rest", "sleep" -> 0.4f; else -> 1f }
        Mood.CURIOUS -> when (name) { "explore" -> 2.2f; "roam" -> 1.8f; "stroll" -> 1.5f; "idle" -> 1.4f; "vocal" -> 1.3f; "sleep" -> 0.3f; else -> 1f }
        Mood.HAPPY -> when (name) { "happy" -> 2.2f; "vocal" -> 1.4f; "wander" -> 1.2f; "play" -> 1.3f; "sad", "ask_food" -> 0.5f; else -> 1f }
        Mood.TIRED -> when (name) { "rest" -> 2f; "sleep" -> 2.2f; "zoomies", "hop", "jump", "climb", "play" -> 0.3f; else -> 1f }
        Mood.CALM -> 1f
    }

    private fun pickZ(c: BrainContext, spread: Float) = (c.z + (rnd.nextFloat() * 2f - 1f) * spread).coerceIn(0f, 1f)

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
        updateMood(t, n)
        val tired = n.energy < 25f; val sleepy = n.energy < 50f
        val hungry = n.hunger > 70f; val bored = n.happiness < 45f; val sad = n.happiness < 25f
        val opts = ArrayList<Triple<String, Float, () -> List<Step>>>()
        fun opt(name: String, w: Float, vararg needs: String, plan: () -> List<Step>) {
            if (w <= 0f || !ready(name, t) || needs.any { !lib.has(it) }) return
            var ww = w
            if (history.lastOrNull() == name) ww *= 0.25f else if (name in history) ww *= 0.6f
            ww *= moodWeight(name)
            opts.add(Triple(name, ww, plan))
        }
        // a real dog spends most of its time standing, looking, resting: long calm pauses between moves
        val act = c.activity.coerceIn(0.3f, 2f)
        val idleSec = { (4f + rnd.nextFloat() * 6f) / act }
        val A = act                      // multiplies active behaviours
        val Q = 1f / act                 // multiplies quiet behaviours
        val energyF = if (tired) 0.2f else if (sleepy) 0.6f else 1f

        opt("wander", 14f * p.walk * (0.5f + 0.5f * A), "walk") {
            listOf(Step.Move(pickX(c, c.win * 0.5f, c.win * 2f), pickZ(c, c.depthRate * 2.5f)), Step.Pose("idle", idleSec()))
        }
        opt("explore", 4f * p.explore * energyF * A, "walk") {
            cool("explore", t, 25f)
            val far = pickX(c, c.win * 1.2f, c.win * 3f)
            val l = mutableListOf<Step>(Step.Move(far, pickZ(c, c.depthRate * 3f)), Step.Pose("idle", 1.2f + rnd.nextFloat() * 1.5f))
            if (lib.has(p.voice) && rnd.nextFloat() < 0.25f * p.vocal) l.add(Step.Pose(p.voice, cycles = 1, sound = p.voice))
            l
        }
        opt("zoomies", 2.5f * p.run * energyF * A * A, "run") {
            cool("zoomies", t, 75f)
            val a = pickX(c, c.win * 2f)
            listOf(Step.Move(a, c.z, "run"), Step.Move(pickX(c.copyAt(a), c.win * 1.2f), c.z, "run"),
                Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1, sound = "pant"))
        }
        if (lib.resolve("walk", Dir.N) != null && lib.resolve("walk", Dir.S) != null)
            opt("stroll", 4f * p.stroll, "walk") {
                cool("stroll", t, 40f)
                val back = (c.z + (1f + rnd.nextFloat() * 1.5f) * c.depthRate).coerceAtMost(1f)
                val gait = if (rnd.nextFloat() < 0.4f * p.run && lib.resolve("run", Dir.S) != null) "run" else "walk"
                listOf(Step.Move(c.x, back), Step.Pose("idle", 0.6f + rnd.nextFloat()), Step.Move(c.x, c.z, gait),
                    Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1))
            }
        if (lib.resolve("walk", Dir.NE)?.clip?.dir?.let { !it.horizontal && !it.depth } == true)
            opt("roam", 3.5f * p.explore * A, "walk") {
                // diagonal walk into the scene and back out somewhere else (uses the 3/4 views)
                cool("roam", t, 30f)
                val a = pickX(c, c.win * 0.8f, c.win * 2.5f); val depth = pickZ(c, c.depthRate * 3f)
                listOf(Step.Move(a, depth), Step.Pose("idle", 0.8f + rnd.nextFloat()), Step.Move(pickX(c.copyAt(a), c.win * 0.6f), pickZ(c, 0.15f)), Step.Pose("idle", 1f + rnd.nextFloat()))
            }
        opt("idle", 30f * p.idle * Q, "idle") { listOf(Step.Pose("idle", idleSec())) }
        opt("play", (if (bored) 10f else 4f) * p.play * energyF * A, "play") {
            cool("play", t, 50f); listOf(Step.Pose("play", cycles = 2, fx = "note"), Step.Pose(if (lib.has("happy")) "happy" else "idle", cycles = 1))
        }
        opt("happy", 2.5f * (n.happiness / 60f).coerceIn(0.3f, 1.5f), "happy") { cool("happy", t, 10f); listOf(Step.Pose("happy", cycles = 2, fx = "heart")) }
        opt("vocal", 2.5f * p.vocal, p.voice) { cool("vocal", t, 120f); listOf(Step.Pose(p.voice, cycles = 1, sound = p.voice)) }
        opt("drink", 2.5f * p.drink, "drink") { cool("drink", t, 120f); listOf(Step.Pose("drink", cycles = 2, sound = "drink")) }
        if (c.gravity) opt("jump", 1.5f * p.jump * energyF * A, "jump") {
            cool("jump", t, 45f); listOf(Step.Jump(0.5f + rnd.nextFloat() * 0.6f, 0.35f + rnd.nextFloat() * 0.25f), Step.Pose("idle", 1f))
        }
        if (c.gravity) opt("hop", 2f * p.jump * energyF * A, "jump") {
            // jump to another spot nearby (up or down the screen, left or right)
            cool("hop", t, 40f)
            val dx = (0.5f + rnd.nextFloat() * 0.6f) * c.win * (if (rnd.nextBoolean()) 1 else -1)
            val tx = (c.x + dx).coerceIn(c.minX, c.maxX)
            listOf(Step.Hop(tx, pickZ(c, 0.1f), 0.3f + rnd.nextFloat() * 0.3f), Step.Pose("idle", 0.8f + rnd.nextFloat()))
        }
        if (c.canClimb) opt("climb", 1.2f * p.climb * energyF * A, "climb", "run") {
            cool("climb", t, 150f)
            val side = if (rnd.nextBoolean()) c.minX else c.maxX
            listOf(Step.Move(side, c.z, "run"), Step.Climb())
        }
        val restAction = if (lib.has("rest")) "rest" else if (lib.has("lie_down")) "lie_down" else null
        if (restAction != null) opt("rest", (if (sleepy) 16f else 7f) * p.rest * Q, "walk") {
            cool("rest", t, 40f); listOf(Step.Move(pickX(c, 0f, c.win * 1.5f), c.z), Step.Pose(restAction, 12f + rnd.nextFloat() * 18f))
        }
        opt("sleep", (if (tired) 60f else if (sleepy) 8f else 1f) * p.sleep, "sleep", "walk") {
            cool("sleep", t, 90f); listOf(Step.Move(c.restX, c.z), Step.Sleep(15f + rnd.nextFloat() * 20f))
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

    private fun BrainContext.copyAt(nx: Float) = BrainContext(time, needs, nx, minX, maxX, win, others, canClimb, gravity, restX, z, depthRate)
}
