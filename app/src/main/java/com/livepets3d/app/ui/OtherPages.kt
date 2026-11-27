package com.livepets3d.app.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.livepets3d.app.BuildConfig
import com.livepets3d.app.data.Quest
import com.livepets3d.app.monetization.Monetization
import com.livepets3d.app.overlay.OverlayService
import com.livepets3d.app.ui.Ui.glass

private fun scrollColumn(a: MainActivity): Pair<ScrollView, LinearLayout> {
    val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), a.dp(6f), a.dp(12f), a.dp(16f)) }
    scroll.addView(col)
    col.addView(Header.topRow(a, { a.openPremium() }, { a.openPremium() }).apply { setPadding(0, 0, 0, a.dp(6f)) })
    return scroll to col
}

// =====================================================================================  QUESTS
class QuestsPage(private val a: MainActivity) : Page {
    override val view: View
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    init {
        val (scroll, col) = scrollColumn(a)
        val daily = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.gradient(a, 24f, 0xFFF59E0B.toInt(), 0xFFEA580C.toInt(), stroke = 0x99FFF3B0.toInt(), strokeW = 1.5f)
            setPadding(a.dp(16f), a.dp(14f), a.dp(14f), a.dp(14f))
        }
        daily.addView(Ui.icon(a, G.GIFT, Pal.WHITE, 0xFFFFE4B5.toInt(), 42f))
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), 0, a.dp(8f), 0) }
        t.addView(Ui.text(a, "Daily reward", 18f, Pal.WHITE, 2)); t.addView(Ui.text(a, "50 coins + 2 gems every day", 12.5f, 0xEEFFFFFF.toInt(), 0))
        daily.addView(t, lp(0, WRAP, 1f))
        val avail = a.repo.dailyAvailable()
        daily.addView(Ui.pill(a, null, if (avail) "Claim" else "Claimed", if (avail) Pal.PURPLE else 0x55FFFFFF, if (avail) Pal.PINK else 0x33FFFFFF, 0x88FFFFFF.toInt()) {
            if (a.repo.claimDaily()) { a.app.sound.play("chime", 0.7f); a.toast("+50 coins · +2 gems"); a.select(3) }
        })
        col.addView(daily, lp(MATCH, WRAP).apply { topMargin = a.dp(6f) })
        col.addView(Ui.sectionTitle(a, "Today's quests"))
        col.addView(list)
        val coins = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, a.dp(16f), 0, 0) }
        coins.addView(Ui.icon(a, G.COIN, 0xFFFFE08A.toInt(), 0xFFF59E0B.toInt(), 22f))
        coins.addView(Ui.text(a, "  ${"%,d".format(a.repo.coins)} coins saved", 14f, Pal.WHITE, 1))
        col.addView(coins)
        view = scroll
        build()
    }

    private fun build() {
        list.removeAllViews()
        val repo = a.repo
        for (q in Quest.values()) {
            val prog = repo.questProgress(q); val done = prog >= q.goal; val claimed = repo.questClaimed(q)
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = glass(a, 20f, if (done && !claimed) 0x447C3AED else 0x2A2E1F6B, if (done && !claimed) Pal.GOLD else 0x55A78BFA)
                setPadding(a.dp(14f), a.dp(12f), a.dp(12f), a.dp(12f))
            }
            val g = when (q) { Quest.FEED -> G.BOWL; Quest.PLAY -> G.BALL; Quest.PET -> G.HAND; Quest.SCREEN -> G.PHONE; Quest.SLEEP -> G.MOON }
            r.addView(Ui.icon(a, g, 0xFFC4B5FD.toInt(), Pal.PURPLE, 30f))
            val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), 0, a.dp(10f), 0) }
            c.addView(Ui.text(a, q.title, 14.5f, Pal.WHITE, 2))
            c.addView(BarView(a, Pal.PURPLE, Pal.PINK).apply { progress = prog / q.goal.toFloat() }, lp(MATCH, a.dp(7f)).apply { topMargin = a.dp(6f) })
            c.addView(Ui.text(a, "$prog / ${q.goal}   ·   +${q.coins} coins" + (if (q.gems > 0) "  +${q.gems} gems" else ""), 11.5f, Pal.TEXT_DIM, 0).apply { setPadding(0, a.dp(4f), 0, 0) })
            r.addView(c, lp(0, WRAP, 1f))
            val label = when { claimed -> "Done"; done -> "Claim"; else -> "Go" }
            r.addView(Ui.pill(a, null, label, if (done && !claimed) Pal.GOLD else 0x44FFFFFF, if (done && !claimed) 0xFFF59E0B.toInt() else 0x22FFFFFF, 0x66FFFFFF, if (done && !claimed) 0xFF3B1D00.toInt() else Pal.WHITE) {
                when {
                    claimed -> {}
                    done -> if (repo.claimQuest(q)) { a.app.sound.play("chime", 0.7f); a.toast("+${q.coins} coins"); build(); a.refreshNav() }
                    q == Quest.SCREEN -> a.select(0)
                    else -> a.select(2)
                }
            })
            list.addView(r, lp(MATCH, WRAP).apply { bottomMargin = a.dp(8f) })
        }
    }

    override fun refresh() = build()
}

// =====================================================================================  SETTINGS
class SettingsPage(private val a: MainActivity) : Page {
    override val view: View

    init {
        val (scroll, col) = scrollColumn(a)
        val repo = a.repo
        fun group(title: String): LinearLayout {
            col.addView(Ui.sectionTitle(a, title))
            val g = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = glass(a, 22f, 0x2A2E1F6B, 0x55A78BFA); setPadding(a.dp(14f), a.dp(4f), a.dp(14f), a.dp(4f)) }
            col.addView(g, lp(MATCH, WRAP))
            return g
        }
        fun row(parent: LinearLayout, g: G, title: String, sub: String, trailing: View?, onClick: (() -> Unit)? = null) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(10f), 0, a.dp(10f)) }
            r.addView(Ui.icon(a, g, 0xFFE9D5FF.toInt(), Pal.PURPLE, 24f))
            val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), 0, a.dp(8f), 0) }
            t.addView(Ui.text(a, title, 15f, Pal.WHITE, 1)); t.addView(Ui.text(a, sub, 11.5f, Pal.TEXT_DIM, 0).apply { maxLines = 3 })
            r.addView(t, lp(0, WRAP, 1f))
            if (trailing != null) r.addView(trailing, lp(a.dp(52f), a.dp(30f)))
            if (onClick != null) Ui.pressable(r, onClick)
            parent.addView(r)
        }
        fun sw(v: Boolean, f: (Boolean) -> Unit) = PillSwitch(a, v) { f(it); OverlayService.refresh(a) }

        val g1 = group("Your pet on screen")
        row(g1, G.PHONE, "Show on my screen", "Floating companion over home screen & apps", sw(a.overlayOn) { a.setOverlay(it) })
        row(g1, G.BOLT, "Battery saver", "30 fps and lighter fur while floating", sw(repo.batterySaver) { repo.batterySaver = it })
        row(g1, G.GRAVITY, "Gravity mode", "Pet falls to the bottom of the screen", sw(repo.gravity) { repo.gravity = it })
        row(g1, G.HAND, "Tap interaction", "Off makes the pet click-through", sw(repo.tapInteraction) { repo.tapInteraction = it })

        val g2 = group("Sound & experience")
        row(g2, G.SOUND, "Sounds", "Off = silent mode (no voices, effects or music)", sw(repo.sounds) { repo.sounds = it; if (it) a.app.music.start() else a.app.music.stop() })
        fun slider(parent: LinearLayout, g: G, title: String, value: Float, set: (Float) -> Unit) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(6f), 0, a.dp(6f)) }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(Ui.icon(a, g, 0xFFE9D5FF.toInt(), Pal.PURPLE, 22f)); top.addView(Ui.text(a, "  $title", 14.5f, Pal.WHITE, 1))
            r.addView(top); r.addView(SliderView(a, value, set), lp(MATCH, a.dp(34f)))
            parent.addView(r)
        }
        slider(g2, G.SOUND, "Effects volume", repo.sfxVolume) { repo.sfxVolume = it; a.app.sound.play("pop", 0.9f) }
        row(g2, G.NOTE, "Music", "Calm background music in the app (never over other apps)", sw(repo.music) { repo.music = it; if (it) a.app.music.start() else a.app.music.stop() })
        slider(g2, G.NOTE, "Music volume", repo.musicVolume) { repo.musicVolume = it; a.app.music.start() }
        row(g2, G.SOUND, "Animal voices", "Barks, howls and meows (only real licensed recordings)", sw(repo.animalVoices) { repo.animalVoices = it })
        row(g2, G.MOON, "Quiet hours", "No animal sounds from ${repo.quietStart}:00 to ${repo.quietEnd}:00, during calls or while other audio plays", sw(repo.quietHours) { repo.quietHours = it })
        row(g2, G.VIBRATE, "Haptics", "Gentle vibration on interactions", sw(repo.haptics) { repo.haptics = it })
        row(g2, G.MOON, "Dark mode", "Off = brighter dawn theme", sw(repo.darkMode) { repo.darkMode = it; a.applyTheme() })

        val g3 = group("Permissions")
        val overlayOk = Settings.canDrawOverlays(a)
        row(g3, G.LOCK, "Display over other apps", if (overlayOk) "Granted ✓" else "Required to show your pet outside the app", null) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
        }
        row(g3, G.SPARK, "Notifications", "Pause / resume controls for the floating pet", null) {
            val i = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))
            a.startActivity(i)
        }
        row(g3, G.BOLT, "Battery optimisation", "If your phone stops the pet in the background, allow LivePets to run unrestricted", null) {
            a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        val g4 = group("About")
        row(g4, G.PAW, "LivePets 3D", "Version ${BuildConfig.VERSION_NAME}" + if (BuildConfig.TEST_MODE) " · TESTER BUILD (simulated purchases)" else "", null)
        row(g4, G.CROWN, "Premium & purchases", "Plans, gems and restore purchases", null) { a.openPremium() }
        row(g4, G.LOCK, "Privacy", "No account, no tracking. Your pet never reads other apps' content. All progress stays on this device.", null)
        row(g4, G.CLOSE, "Reset progress", "Clear names, levels, coins and quests", null) {
            a.showDialog(G.CLOSE, "Reset everything?", "This clears all names, levels, coins, gems and quests on this device.", "Reset", {
                OverlayService.stop(a); repo.sp.edit().clear().apply(); a.recreate()
            }, "Cancel")
        }
        view = scroll
    }
}
