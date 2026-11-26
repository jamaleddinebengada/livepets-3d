package com.livepets3d.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.livepets3d.app.R
import com.livepets3d.app.data.PetRepository
import kotlin.random.Random

/** Animal voices + UI sounds (SoundPool) and haptics, respecting the user's toggles. */
class SoundManager(context: Context, private val repo: PetRepository) {
    private val pool: SoundPool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = HashMap<String, Int>()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var lastPlay = 0L

    init {
        val res = mapOf(
            "meow" to R.raw.meow, "bark" to R.raw.bark, "panda" to R.raw.panda, "bunny" to R.raw.bunny,
            "fox" to R.raw.fox, "dragon" to R.raw.dragon, "purr" to R.raw.purr, "crunch" to R.raw.crunch,
            "pop" to R.raw.pop, "chime" to R.raw.chime, "yawn" to R.raw.yawn, "boing" to R.raw.boing, "whoosh" to R.raw.whoosh,
            "penguin" to R.raw.penguin, "drink" to R.raw.drink, "step" to R.raw.step, "coin" to R.raw.coin, "levelup" to R.raw.levelup,
        )
        for ((k, v) in res) ids[k] = pool.load(context, v, 1)
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /** [overlay] sounds are skipped when the phone is on silent / vibrate. */
    fun play(name: String, volume: Float = 0.8f, pitchVar: Boolean = true, overlay: Boolean = false) {
        if (!repo.sounds || repo.sfxVolume <= 0.01f) return
        if (overlay && audio.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[name] ?: ids["pop"] ?: return
        val now = System.currentTimeMillis()
        if (now - lastPlay < 60) return
        lastPlay = now
        val rate = if (pitchVar) 0.92f + Random.nextFloat() * 0.16f else 1f
        val v = (volume * repo.sfxVolume).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, rate)
    }

    fun haptic(strong: Boolean = false) {
        if (!repo.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(if (strong) 30 else 12, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

/** Gentle looping background music for the main app only (never in the overlay). */
class MusicPlayer(private val context: Context, private val repo: PetRepository) {
    private var mp: android.media.MediaPlayer? = null

    fun start() {
        if (!repo.music || !repo.sounds || repo.musicVolume <= 0.01f) { stop(); return }
        if (mp == null) {
            mp = android.media.MediaPlayer.create(context, R.raw.music)?.apply {
                isLooping = true
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            }
        }
        val v = repo.musicVolume * 0.6f
        mp?.setVolume(v, v)
        if (mp?.isPlaying == false) mp?.start()
    }

    fun pause() { try { mp?.pause() } catch (_: Exception) {} }
    fun stop() { try { mp?.release() } catch (_: Exception) {}; mp = null }
}
