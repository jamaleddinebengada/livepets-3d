package com.livepets3d.app.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Local persistence for pets, stats, currencies, settings and daily quests. */
class PetRepository(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("livepets", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ selection
    var species: PetSpecies
        get() = PetSpecies.byName(sp.getString("species", PetSpecies.KITTEN.name))
        set(v) = sp.edit().putString("species", v.name).apply()

    /** Additional animals shown on screen together with the main pet (max 2). */
    var companions: Set<PetSpecies>
        get() = (sp.getStringSet("companions", emptySet()) ?: emptySet())
            .mapNotNull { n -> PetSpecies.values().firstOrNull { it.name == n && it.available } }.toSet()
        set(v) = sp.edit().putStringSet("companions", v.map { it.name }.toSet()).apply()

    fun nameOf(s: PetSpecies): String = sp.getString("name_${s.name}", s.defaultName) ?: s.defaultName
    fun setName(s: PetSpecies, n: String) = sp.edit().putString("name_${s.name}", n.take(14)).apply()
    fun isFemale(s: PetSpecies): Boolean = sp.getBoolean("female_${s.name}", s == PetSpecies.KITTEN || s == PetSpecies.RABBIT)
    fun setFemale(s: PetSpecies, f: Boolean) = sp.edit().putBoolean("female_${s.name}", f).apply()

    // ------------------------------------------------------------------ progression
    fun xpOf(s: PetSpecies): Int = sp.getInt("xp_${s.name}", 0)
    fun levelOf(s: PetSpecies): Int = 1 + xpOf(s) / 100
    fun levelProgress(s: PetSpecies): Float = (xpOf(s) % 100) / 100f
    /** Called when a pet gains a level (sound + celebration hooks). */
    var onLevelUp: ((PetSpecies, Int) -> Unit)? = null

    fun addXp(s: PetSpecies, amount: Int): Boolean {
        val before = levelOf(s)
        sp.edit().putInt("xp_${s.name}", xpOf(s) + amount).apply()
        val up = levelOf(s) > before
        if (up) { coins += 25; onLevelUp?.invoke(s, levelOf(s)) }
        return up
    }

    var coins: Int
        get() = sp.getInt("coins", 250)
        set(v) = sp.edit().putInt("coins", max(0, v)).apply()
    var gems: Int
        get() = sp.getInt("gems", 20)
        set(v) = sp.edit().putInt("gems", max(0, v)).apply()

    // ------------------------------------------------------------------ needs (0..100)
    private fun raw(key: String, def: Float) = sp.getFloat(key, def)

    /** Applies time based decay since the last update, then returns the current stats. */
    fun stats(s: PetSpecies): Stats {
        val now = System.currentTimeMillis()
        val last = sp.getLong("stats_t_${s.name}", now)
        val hours = ((now - last).coerceAtLeast(0L)) / 3_600_000f
        var st = Stats(raw("hap_${s.name}", 82f), raw("hun_${s.name}", 25f), raw("ene_${s.name}", 80f))
        if (hours > 0.01f) {
            st = Stats(
                clamp(st.happiness - hours * 3f),
                clamp(st.hunger + hours * 6f),
                clamp(st.energy - hours * 4f + (if (st.energy < 20) hours * 8f else 0f)),
            )
            saveStats(s, st, now)
        } else if (!sp.contains("stats_t_${s.name}")) saveStats(s, st, now)
        return st
    }

    fun saveStats(s: PetSpecies, st: Stats, now: Long = System.currentTimeMillis()) {
        sp.edit().putFloat("hap_${s.name}", st.happiness).putFloat("hun_${s.name}", st.hunger)
            .putFloat("ene_${s.name}", st.energy).putLong("stats_t_${s.name}", now).apply()
    }

    fun modify(s: PetSpecies, dHappy: Float = 0f, dHunger: Float = 0f, dEnergy: Float = 0f): Stats {
        val st = stats(s)
        val n = Stats(clamp(st.happiness + dHappy), clamp(st.hunger + dHunger), clamp(st.energy + dEnergy))
        saveStats(s, n)
        return n
    }

    // ------------------------------------------------------------------ settings
    var petSize: Float
        get() = sp.getFloat("size", 1f)
        set(v) = sp.edit().putFloat("size", v.coerceIn(0.6f, 1.7f)).apply()
    var petSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v.coerceIn(0.4f, 2.2f)).apply()
    var gravity: Boolean
        get() = sp.getBoolean("gravity", true)
        set(v) = sp.edit().putBoolean("gravity", v).apply()
    var climbing: Boolean
        get() = sp.getBoolean("climb", true)
        set(v) = sp.edit().putBoolean("climb", v).apply()
    var tapInteraction: Boolean
        get() = sp.getBoolean("tap", true)
        set(v) = sp.edit().putBoolean("tap", v).apply()
    var sounds: Boolean
        get() = sp.getBoolean("sounds", true)
        set(v) = sp.edit().putBoolean("sounds", v).apply()
    /** No animal voices between [quietStart] and [quietEnd] (local hours). */
    var quietHours: Boolean
        get() = sp.getBoolean("quiet", true)
        set(v) = sp.edit().putBoolean("quiet", v).apply()
    var quietStart: Int
        get() = sp.getInt("quiet_s", 22)
        set(v) = sp.edit().putInt("quiet_s", v).apply()
    var quietEnd: Int
        get() = sp.getInt("quiet_e", 7)
        set(v) = sp.edit().putInt("quiet_e", v).apply()
    /** Animal voices over other apps (barks, howls, meows). Effects and UI sounds are separate. */
    /** Volume of animal voices (barks, howls...), independent from effects and music. */
    var voiceVolume: Float
        get() = sp.getFloat("voice_vol", 0.8f)
        set(v) = sp.edit().putFloat("voice_vol", v).apply()
    var animalVoices: Boolean
        get() = sp.getBoolean("voices", true)
        set(v) = sp.edit().putBoolean("voices", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()
    var batterySaver: Boolean
        get() = sp.getBoolean("saver", false)
        set(v) = sp.edit().putBoolean("saver", v).apply()
    var darkMode: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) = sp.edit().putBoolean("dark", v).apply()
    var overlayWanted: Boolean
        get() = sp.getBoolean("overlay_on", false)
        set(v) = sp.edit().putBoolean("overlay_on", v).apply()
    var collarColor: Int
        get() = sp.getInt("collar", 0xE0283C) // 0 = no collar
        set(v) = sp.edit().putInt("collar", v).apply()
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var sfxVolume: Float
        get() = sp.getFloat("sfx_vol", 0.8f)
        set(v) = sp.edit().putFloat("sfx_vol", v.coerceIn(0f, 1f)).apply()
    var musicVolume: Float
        get() = sp.getFloat("music_vol", 0.35f)
        set(v) = sp.edit().putFloat("music_vol", v.coerceIn(0f, 1f)).apply()
    var music: Boolean
        get() = sp.getBoolean("music", true)
        set(v) = sp.edit().putBoolean("music", v).apply()
    /** Opacity of the floating pet, 0.3..1 (click-through mode is capped at 0.8 by Android). */
    var overlayAlpha: Float
        get() = sp.getFloat("ov_alpha", 1f)
        set(v) = sp.edit().putFloat("ov_alpha", v.coerceIn(0.3f, 1f)).apply()

    // ------------------------------------------------------------------ customisation
    fun owns(itemKey: String): Boolean = sp.getBoolean("own_$itemKey", false)
    fun grant(itemKey: String) = sp.edit().putBoolean("own_$itemKey", true).apply()

    /** Buys an item priced in coins (>0) or gems (<0). Free items are always owned. */
    fun buy(itemKey: String, price: Int): Boolean {
        if (price == 0 || owns(itemKey)) { grant(itemKey); return true }
        if (price > 0) { if (coins < price) return false; coins -= price }
        else { if (gems < -price) return false; gems += price }
        grant(itemKey); return true
    }

    fun skinOf(s: PetSpecies): String = sp.getString("skin_${s.name}", s.skins.firstOrNull()?.id ?: "") ?: ""
    fun setSkin(s: PetSpecies, id: String) = sp.edit().putString("skin_${s.name}", id).apply()
    fun equipped(s: PetSpecies, slot: Slot): Accessory? {
        val n = sp.getString("eq_${s.name}_${slot.name}", null) ?: return null
        return Accessory.values().firstOrNull { it.name == n }
    }
    fun equip(s: PetSpecies, slot: Slot, a: Accessory?) {
        sp.edit().apply { if (a == null) remove("eq_${s.name}_${slot.name}") else putString("eq_${s.name}_${slot.name}", a.name) }.apply()
    }

    /** Copies the species' saved look (skin, outfit, collar) onto a renderer controller. */
    fun dress(s: PetSpecies, ctl: com.livepets3d.app.anim.PetController) {
        val skin = s.skins.firstOrNull { it.id == skinOf(s) }
        ctl.skinPrimary = skin?.primary ?: -1; ctl.skinSecondary = skin?.secondary ?: -1
        ctl.skinAccent = skin?.accent ?: -1; ctl.skinLeg = skin?.leg ?: -1
        ctl.hat = equipped(s, Slot.HAT)?.let { it.ordinal + 1 } ?: 0
        ctl.glasses = equipped(s, Slot.GLASSES)?.let { it.ordinal + 1 } ?: 0
        ctl.clothes = equipped(s, Slot.CLOTHES)?.let { it.ordinal + 1 } ?: 0
        ctl.collarColor = collarColor
    }

    // ------------------------------------------------------------------ premium entitlement (written by billing)
    var premiumEntitled: Boolean
        get() = sp.getBoolean("premium_entitled", false)
        set(v) = sp.edit().putBoolean("premium_entitled", v).apply()

    // ------------------------------------------------------------------ daily quests
    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    fun questProgress(q: Quest): Int = sp.getInt("q_${today()}_${q.name}", 0)
    fun questClaimed(q: Quest): Boolean = sp.getBoolean("qc_${today()}_${q.name}", false)
    fun bumpQuest(q: Quest, by: Int = 1) {
        val k = "q_${today()}_${q.name}"
        sp.edit().putInt(k, min(q.goal, sp.getInt(k, 0) + by)).apply()
    }
    fun claimQuest(q: Quest): Boolean {
        if (questClaimed(q) || questProgress(q) < q.goal) return false
        sp.edit().putBoolean("qc_${today()}_${q.name}", true).apply()
        coins += q.coins; gems += q.gems
        return true
    }
    fun dailyAvailable(): Boolean = sp.getString("daily", "") != today()
    fun claimDaily(): Boolean {
        if (!dailyAvailable()) return false
        sp.edit().putString("daily", today()).apply()
        coins += 50; gems += 2
        return true
    }
    val pendingQuestRewards: Int get() = Quest.values().count { questProgress(it) >= it.goal && !questClaimed(it) }

    private fun clamp(v: Float) = v.coerceIn(0f, 100f)
}

data class Stats(val happiness: Float, val hunger: Float, val energy: Float)

enum class Quest(val title: String, val goal: Int, val coins: Int, val gems: Int) {
    FEED("Feed your pet", 2, 40, 0),
    PLAY("Play together 3 times", 3, 50, 1),
    PET("Pet your companion 5 times", 5, 30, 1),
    SCREEN("Keep your pet on screen 10 min", 10, 60, 2),
    SLEEP("Tuck your pet in for a nap", 1, 25, 0),
}
