package com.livepets3d.app.anim.engine

/*
 * Pure Kotlin (no Android imports): the animation library of one animal, built from
 * assets/sprites/<animal>/manifest.json. Kept platform-free so the whole engine can be
 * unit-tested and previewed on a plain JVM.
 */

/** Eight movement directions. dx: +1 right. dz: +1 away from the viewer (into the screen). */
enum class Dir(val dx: Int, val dz: Int, val key: String) {
    E(1, 0, "right"), W(-1, 0, "left"), N(0, 1, "back"), S(0, -1, "front"),
    NE(1, 1, "back_right"), NW(-1, 1, "back_left"), SE(1, -1, "front_right"), SW(-1, -1, "front_left");

    val horizontal get() = dz == 0
    val depth get() = dx == 0
    fun mirrored(): Dir = values().first { it.dx == -dx && it.dz == dz }

    companion object {
        fun parse(s: String): Dir = values().firstOrNull { it.name.equals(s, true) || it.key == s } ?: E

        /** Closest of the 8 directions for a movement vector (screen dx, depth dz). */
        fun of(dx: Float, dz: Float): Dir {
            val ax = kotlin.math.abs(dx); val az = kotlin.math.abs(dz)
            if (ax < 1e-6f && az < 1e-6f) return E
            val sx = if (dx >= 0) 1 else -1; val sz = if (dz >= 0) 1 else -1
            return when {
                az < ax * 0.41f -> if (sx > 0) E else W            // within 22.5 deg of horizontal
                ax < az * 0.41f -> if (sz > 0) N else S
                else -> values().first { it.dx == sx && it.dz == sz }
            }
        }
    }
}

class ClipInfo(
    val id: String,
    /** Sprite sheet base name (sheet image + <file>.json) in the animal's asset folder. */
    val file: String,
    val action: String,
    /** Direction the artwork faces / moves in. Side views can be mirrored, front/back views never. */
    val dir: Dir,
    val frames: Int,
    val frameMs: Int,
    val loop: Boolean,
    /** Ground speed in sprite pixels per second at playback rate 1 (keeps paws planted). */
    val groundSpeed: Float,
    /** Depth units (0 = near, 1 = far) per second at playback rate 1, for front/back walks. */
    val depthSpeed: Float,
    val gait: String,
    /** Named frame ranges, e.g. jump: takeoff / air / land. */
    val phases: Map<String, IntRange>,
    /** Frames on which a paw touches the ground (footstep sound sync). */
    val steps: IntArray,
) {
    val durationMs get() = frames * frameMs
}

class Resolved(val clip: ClipInfo, val mirror: Boolean)

class ClipLibrary(val animal: String, val clips: List<ClipInfo>, val bodyLengthPx: Float, val personality: String) {
    private val byAction = clips.groupBy { it.action }

    fun has(action: String) = byAction.containsKey(action)
    fun actions(): Set<String> = byAction.keys

    /**
     * Picks the clip for [action] moving/facing [dir].
     *  1. exact direction            2. mirrored side view (left <-> right only)
     *  3. diagonal -> its dominant component (side view if [preferSide], else front/back), never a
     *     front/back view for a purely sideways move.
     * Non-directional actions (idle, eat...) are authored facing right and mirrored when facing left.
     */
    fun resolve(action: String, dir: Dir, variant: Int = 0, preferSide: Boolean = true): Resolved? {
        val list = byAction[action] ?: return null
        fun pick(d: Dir): ClipInfo? { val l = list.filter { it.dir == d }; return if (l.isEmpty()) null else l[Math.floorMod(variant, l.size)] }
        pick(dir)?.let { return Resolved(it, false) }
        if (dir.horizontal || !dir.depth) pick(dir.mirrored())?.let { if (it.dir.horizontal || !it.dir.depth) return Resolved(it, true) }
        if (!dir.horizontal && !dir.depth) {
            val side = if (dir.dx > 0) Dir.E else Dir.W
            val dep = if (dir.dz > 0) Dir.N else Dir.S
            val order = if (preferSide) listOf(side, dep) else listOf(dep, side)
            for (d in order) {
                pick(d)?.let { return Resolved(it, false) }
                if (d.horizontal) pick(d.mirrored())?.let { return Resolved(it, true) }
            }
        }
        if (dir.horizontal) return null    // never fake a side walk with a front/back view
        if (dir.depth) return null
        return null
    }

    /** Non-directional clip (idle, eat...), mirrored to face [facing] (+1 right, -1 left). */
    fun pose(action: String, facing: Int, variant: Int = 0): Resolved? {
        val list = byAction[action] ?: return null
        val side = list.filter { it.dir.horizontal }
        if (side.isEmpty()) return Resolved(list[Math.floorMod(variant, list.size)], false)
        val want = if (facing >= 0) Dir.E else Dir.W
        side.filter { it.dir == want }.let { if (it.isNotEmpty()) return Resolved(it[Math.floorMod(variant, it.size)], false) }
        return Resolved(side[Math.floorMod(variant, side.size)], true)
    }

    fun variants(action: String, dir: Dir) = byAction[action]?.count { it.dir == dir } ?: 0

    /** Standard animation names of the spec, checked against what this animal has. */
    fun missingReport(): List<String> {
        val out = ArrayList<String>()
        for ((name, test) in REQUIRED) if (!test(this)) out.add("${animal}_$name")
        return out
    }

    companion object {
        private fun dirOk(a: String, d: Dir): (ClipLibrary) -> Boolean = { lib -> lib.byAction[a]?.any { it.dir == d } == true }
        val REQUIRED: List<Pair<String, (ClipLibrary) -> Boolean>> = listOf(
            "idle" to { l -> l.has("idle") },
            "walk_right" to { l -> l.byAction["walk"]?.any { it.dir == Dir.E || it.dir == Dir.W } == true },
            "walk_left" to dirOk("walk", Dir.W),
            "walk_front" to dirOk("walk", Dir.S),
            "walk_back" to dirOk("walk", Dir.N),
            // a 3/4 view mirrored is the opposite 3/4 view, so one side covers both
            "walk_front_left" to { l -> dirOk("walk", Dir.SW)(l) || dirOk("walk", Dir.SE)(l) },
            "walk_front_right" to { l -> dirOk("walk", Dir.SE)(l) || dirOk("walk", Dir.SW)(l) },
            "walk_back_left" to { l -> dirOk("walk", Dir.NW)(l) || dirOk("walk", Dir.NE)(l) },
            "walk_back_right" to { l -> dirOk("walk", Dir.NE)(l) || dirOk("walk", Dir.NW)(l) },
            "walk_slow" to { l -> l.has("walk_slow") },
            "run" to { l -> l.has("run") },
            "sprint" to { l -> l.has("sprint") },
            "jump" to { l -> l.has("jump") },
            "land" to { l -> l.has("land") || l.byAction["jump"]?.any { "land" in it.phases } == true },
            "sit" to { l -> l.has("sit") },
            "lie_down" to { l -> l.has("lie_down") },
            "sleep" to { l -> l.has("sleep") },
            "wakeup" to { l -> l.has("wakeup") },
            "stretch" to { l -> l.has("stretch") },
            "eat" to { l -> l.has("eat") },
            "drink" to { l -> l.has("drink") },
            "play" to { l -> l.has("play") },
            "scratch" to { l -> l.has("scratch") },
            "groom" to { l -> l.has("groom") },
            "shake" to { l -> l.has("shake") },
            "bark" to { l -> l.has("bark") },
            "howl" to { l -> l.has("howl") },
            "happy" to { l -> l.has("happy") },
            "sad" to { l -> l.has("sad") },
            "curious" to { l -> l.has("curious") },
            "excited" to { l -> l.has("excited") },
            "climb" to { l -> l.has("climb") },
            "roll" to { l -> l.has("roll") },
            "tail_wag" to { l -> l.has("tail_wag") },
            "touch_reaction" to { l -> l.has("touch_reaction") },
            "turn" to { l -> l.has("turn") },
        )

        /**
         * Builds the library from manifest.json. [sheetMeta] returns the sprite-sheet json text
         * (frames, frameMs...) for a file name, or null when that sheet is missing.
         */
        fun parse(manifest: String, sheetMeta: (String) -> String?): ClipLibrary {
            val root = MiniJson.parse(manifest) as Map<*, *>
            val clips = ArrayList<ClipInfo>()
            val cm = root["clips"] as? Map<*, *> ?: emptyMap<String, Any>()
            for ((k, v) in cm) {
                val c = v as? Map<*, *> ?: continue
                val file = c["file"] as? String ?: k as String
                val metaTxt = sheetMeta(file) ?: continue
                val meta = MiniJson.parse(metaTxt) as Map<*, *>
                val phases = HashMap<String, IntRange>()
                (c["phases"] as? Map<*, *>)?.forEach { (pk, pv) ->
                    val l = pv as List<*>; phases[pk as String] = (l[0] as Number).toInt()..(l[1] as Number).toInt()
                }
                clips.add(ClipInfo(
                    id = k as String, file = file,
                    action = c["action"] as? String ?: k,
                    dir = Dir.parse(c["dir"] as? String ?: (meta["facing"] as? String ?: "right")),
                    frames = (meta["frames"] as Number).toInt(),
                    frameMs = ((c["frameMs"] ?: meta["frameMs"] ?: 100) as Number).toInt(),
                    loop = c["loop"] as? Boolean ?: true,
                    groundSpeed = ((c["groundSpeed"] ?: 0) as Number).toFloat(),
                    depthSpeed = ((c["depthSpeed"] ?: 0) as Number).toFloat(),
                    gait = c["gait"] as? String ?: "walk",
                    phases = phases,
                    steps = (c["steps"] as? List<*>)?.map { (it as Number).toInt() }?.toIntArray() ?: IntArray(0),
                ))
            }
            return ClipLibrary(
                root["animal"] as? String ?: "animal", clips,
                ((root["bodyLengthPx"] ?: 220) as Number).toFloat(),
                root["personality"] as? String ?: (root["animal"] as? String ?: "default"),
            )
        }
    }
}

/** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null). */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { val v = value(); v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }; i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l.add(value()); ws(); if (s[i] == ',') { i++; continue }; i++; return l }
        }
        fun str(): String {
            val b = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; b.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; 'u' -> { val h = s.substring(i + 1, i + 5); i += 4; h.toInt(16).toChar() }; else -> s[i] }) }
                else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
        fun num(): Number {
            val st = i; i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return if ('.' in t || 'e' in t || 'E' in t) t.toDouble() else t.toLong()
        }
    }
}
