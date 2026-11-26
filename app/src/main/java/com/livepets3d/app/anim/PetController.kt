package com.livepets3d.app.anim

import com.livepets3d.app.data.PetSpecies

/** Every animation clip the procedural rig can perform. */
enum class Anim(val loops: Boolean) {
    IDLE(true), LOOK(true), WALK(true), RUN(true), JUMP(true), FALL(true), HELD(true), LAND(false),
    SIT(true), SLEEP(true), WAKE(false), STRETCH(false), YAWN(false), EAT(true), DANCE(true),
    PLAY(true), ROLL(false), CLIMB(true), CLING(true), HAPPY(false), SURPRISED(false), HIDE(true),
    SCRATCH(false), WAVE(false), PETTED(true), CELEBRATE(false), REST(true), CHASE(true)
}

/**
 * Thread-safe bridge between the behaviour engine (main thread) and the GL render thread.
 * The behaviour engine writes intentions, the renderer turns them into smoothly blended poses.
 */
class PetController(species: PetSpecies) {
    @Volatile var species: PetSpecies = species
    @Volatile var anim: Anim = Anim.IDLE
        set(v) { if (field != v) { field = v; animSerial++ } }
    /** Increments every time the clip changes (or [restart] is called) so the renderer resets clip time. */
    @Volatile var animSerial: Int = 0
    /** -1 = facing left, 0 = facing the viewer, 1 = facing right. Can be fractional. */
    @Volatile var facing: Float = 0f
    /** 0 = on the floor, -1 = clinging to the left wall, +1 = right wall. */
    @Volatile var climbSide: Int = 0
    /** Normalised horizontal speed 0..1 (drives gait frequency). */
    @Volatile var moveSpeed: Float = 0f
    /** 0..1 opacity of the contact shadow (fades when airborne). */
    @Volatile var shadow: Float = 1f
    @Volatile var lookX: Float = 0f
    @Volatile var lookY: Float = 0f
    @Volatile var collarColor: Int = 0xE0283C
    @Volatile var batterySaver: Boolean = false
    @Volatile var paused: Boolean = false
    /** In-app preview uses a slightly larger framing than the overlay. */
    @Volatile var zoom: Float = 1f

    /** Published by the renderer: vertical position of the feet / head as a fraction of view height. */
    @Volatile var footFrac: Float = 0.88f
    @Volatile var headFrac: Float = 0.35f
    @Volatile var wallFrac: Float = 0.74f

    fun restart(a: Anim) { anim = a; animSerial++ }
}
