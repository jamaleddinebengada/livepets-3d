package com.livepets3d.app

import android.app.Application
import com.livepets3d.app.audio.SoundManager
import com.livepets3d.app.data.PetRepository
import com.livepets3d.app.monetization.Monetization

class LivePetsApp : Application() {
    lateinit var repo: PetRepository
        private set
    lateinit var sound: SoundManager
        private set
    lateinit var music: com.livepets3d.app.audio.MusicPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = PetRepository(this)
        sound = SoundManager(this, repo)
        music = com.livepets3d.app.audio.MusicPlayer(this, repo)
        repo.onLevelUp = { _, _ -> sound.play("levelup", 0.8f, false) }
        Monetization.init(this, repo)
        if (!repo.sp.getBoolean("migr_v6", false)) {
            repo.grant("pet_${repo.species.name}"); repo.companions.forEach { repo.grant("pet_${it.name}") }
            repo.sp.edit().putBoolean("migr_v6", true).apply()
        }
    }

    companion object {
        lateinit var instance: LivePetsApp
            private set
    }
}
