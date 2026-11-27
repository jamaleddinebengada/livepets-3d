package com.livepets3d.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.livepets3d.app.data.PetRepository
import com.livepets3d.app.anim.engine.MiniJson
import java.util.Calendar
import kotlin.random.Random

/**
 * Real animal recordings only. Every file must be listed in assets/sounds/manifest.json together
 * with its licence, source and author (the commercial-use licence manifest). Events without a
 * licensed recording stay silent: no synthesized beeps are used as animal voices.
 *
 * Rules: master sound switch, "Animal voices" switch, effects volume, quiet hours, no sounds during
 * phone/VoIP calls, no overlay sounds while the phone is on silent/vibrate or while another app is
 * playing audio, per-event cooldowns and random variations (never the same file twice in a row).
 */
class AnimalSoundManager(private val context: Context, private val repo: PetRepository) {
    class Entry(val animal: String, val event: String, val files: List<String>, val license: String, val source: String, val author: String)

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pool: SoundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    val entries = ArrayList<Entry>()
    private val ids = HashMap<String, Int>()
    private val lastFile = HashMap<String, String>()
    private val lastTime = HashMap<String, Long>()

    init {
        try {
            val txt = context.assets.open("sounds/manifest.json").bufferedReader().readText()
            val root = MiniJson.parse(txt) as Map<*, *>
            for (o in (root["sounds"] as? List<*>).orEmpty()) {
                val m = o as? Map<*, *> ?: continue
                val lic = m["license"] as? String ?: continue
                if (lic.isBlank()) continue          // never play a file without a licence record
                val files = (m["files"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                entries.add(Entry(m["animal"] as? String ?: "*", m["event"] as? String ?: continue, files, lic,
                    m["source"] as? String ?: "", m["author"] as? String ?: ""))
            }
            for (e in entries) for (f in e.files) if (f !in ids) try {
                context.assets.openFd("sounds/$f").use { fd -> ids[f] = pool.load(fd, 1) }
            } catch (_: Exception) {}
        } catch (_: Exception) { /* no manifest yet */ }
    }

    fun has(animal: String, event: String) = find(animal, event) != null

    private fun find(animal: String, event: String) =
        entries.firstOrNull { it.animal == animal && it.event == event && it.files.any { f -> f in ids } }
            ?: entries.firstOrNull { it.animal == "*" && it.event == event && it.files.any { f -> f in ids } }

    private fun quietNow(): Boolean {
        if (!repo.quietHours) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = repo.quietStart; val e = repo.quietEnd
        return if (s <= e) h in s until e else h >= s || h < e
    }

    private fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audio.mode == AudioManager.MODE_RINGTONE

    /** @return true if a licensed recording was played. */
    fun play(animal: String, event: String, volume: Float, overlay: Boolean): Boolean {
        if (!repo.sounds || repo.sfxVolume <= 0.01f) return false
        val voice = event in VOICE_EVENTS
        if (voice && !repo.animalVoices) return false
        if (inCall()) return false
        if (overlay) {
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
            if (audio.isMusicActive) return false      // never talk over the user's music / videos
            if (voice && quietNow()) return false
        }
        val e = find(animal, event) ?: return false
        val key = "$animal/$event"
        val now = System.currentTimeMillis()
        val cd = COOLDOWN_MS[event] ?: (if (voice) 6000L else 250L)
        if (now - (lastTime[key] ?: 0L) < cd) return false
        val avail = e.files.filter { it in ids }
        val choices = if (avail.size > 1) avail.filter { it != lastFile[key] } else avail
        val f = choices.randomOrNull() ?: return false
        lastFile[key] = f; lastTime[key] = now
        val v = (volume * repo.sfxVolume * (if (overlay) 0.8f else 1f)).coerceIn(0f, 1f)
        pool.play(ids[f]!!, v, v, 1, 0, 0.96f + Random.nextFloat() * 0.08f)
        return true
    }

    companion object {
        /** Vocalizations (need the "Animal voices" switch, muted in quiet hours). */
        val VOICE_EVENTS = setOf("bark", "howl", "whine", "meow", "purr", "content", "call", "roar", "growl", "trumpet",
            "chirp", "panda", "squeak", "yawn", "sleep", "pant")
        private val COOLDOWN_MS = mapOf("howl" to 45_000L, "roar" to 45_000L, "trumpet" to 45_000L, "bark" to 8_000L,
            "whine" to 20_000L, "meow" to 8_000L, "content" to 6_000L, "purr" to 6_000L, "sleep" to 12_000L, "step" to 120L)

        /** Every sound event the engine can emit, for the missing-asset report. */
        val ENGINE_EVENTS = listOf("bark", "howl", "whine", "content", "pant", "sleep", "step", "jump", "land", "land_hard", "eat", "drink", "whoosh")
    }
}
