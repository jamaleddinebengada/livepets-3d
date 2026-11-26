package com.livepets3d.app.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.livepets3d.app.data.PetSpecies
import com.livepets3d.app.overlay.OverlayService
import com.livepets3d.app.ui.Ui.glass

/** Shared header pieces (logo, premium, currencies, profile card). */
object Header {
    fun topRow(a: MainActivity, onPremium: () -> Unit, onGems: () -> Unit): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(10f), a.dp(4f), a.dp(10f), 0) }
        row.addView(LogoView(a, 25f), lp(0, WRAP, 1f))
        row.addView(Ui.pill(a, G.CROWN, "Premium", 0x553B2A12, 0x332A1A08, Pal.GOLD, Pal.GOLD, onPremium).apply { setPadding(a.dp(8f), a.dp(5f), a.dp(10f), a.dp(5f)) }, lp(WRAP, WRAP).apply { rightMargin = a.dp(6f) })
        val gems = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.gradient(a, 30f, 0x664C1D95, 0x442E1065, stroke = 0xAAA78BFA.toInt(), strokeW = 1.5f)
            setPadding(a.dp(8f), a.dp(4f), a.dp(4f), a.dp(4f))
        }
        gems.addView(Ui.icon(a, G.GEM, 0xFFF0ABFC.toInt(), 0xFFA21CAF.toInt(), 20f))
        gems.addView(Ui.text(a, "%,d".format(a.repo.gems), 14f, Pal.WHITE, 2).apply { setPadding(a.dp(5f), 0, a.dp(5f), 0); tag = "gems" })
        gems.addView(Ui.icon(a, G.PLUS, Pal.WHITE, 0xFFE9D5FF.toInt(), 22f).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; colors = intArrayOf(Pal.PURPLE, 0xFF6D28D9.toInt()) }
            setPadding(a.dp(4f), a.dp(4f), a.dp(4f), a.dp(4f))
        })
        Ui.pressable(gems, onGems)
        row.addView(gems)
        return row
    }

    fun profileCard(a: MainActivity, s: PetSpecies): LinearLayout {
        val repo = a.repo
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(a, 40f, 0x3A2E1F6B, 0x66A78BFA)
            setPadding(a.dp(4f), a.dp(4f), a.dp(16f), a.dp(4f))
        }
        card.addView(AnimalFaceView(a, s, highlighted = true), LinearLayout.LayoutParams(a.dp(58f), a.dp(58f)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(8f), 0, 0, 0) }
        val female = repo.isFemale(s)
        val nameRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(Ui.text(a, repo.nameOf(s), 18f, Pal.WHITE, 2).apply { maxLines = 1 })
        nameRow.addView(Ui.text(a, if (female) " ♀" else " ♂", 18f, if (female) Pal.PINK else Pal.SKY, 2))
        col.addView(nameRow)
        col.addView(Ui.text(a, "Lv. ${repo.levelOf(s)}", 13f, 0xDDFFFFFF.toInt(), 1))
        col.addView(BarView(a, Pal.PINK, 0xFFD946EF.toInt()).apply { progress = repo.levelProgress(s) }, LinearLayout.LayoutParams(a.dp(96f), a.dp(7f)).apply { topMargin = a.dp(3f) })
        card.addView(col)
        return card
    }
}

class HomePage(private val a: MainActivity) : Page {
    override val view: View
    private val stage: PetStage
    private val bars = HashMap<String, Pair<BarView, TextView>>()
    private val profileHolder = FrameLayout(a)
    private lateinit var overlaySwitch: PillSwitch
    private lateinit var chrome: List<View>
    private var photoMode = false

    init {
        val repo = a.repo
        val sp = repo.species
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val header = Header.topRow(a, { a.select(1) }, { a.select(1) })
        root.addView(header)

        // profile + stats
        val info = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(10f), a.dp(8f), a.dp(10f), 0); gravity = Gravity.TOP }
        profileHolder.addView(Header.profileCard(a, sp))
        info.addView(profileHolder, lp(0, WRAP, 1.05f))
        val stats = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun statRow(key: String, g: G, c1: Int, c2: Int, label: String) {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = glass(a, 18f, 0x332E1F6B, 0x55A78BFA); setPadding(a.dp(6f), a.dp(3f), a.dp(8f), a.dp(4f))
            }
            r.addView(Ui.icon(a, g, c1, c2, 22f))
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(5f), 0, 0, 0) }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(Ui.text(a, label, 11f, Pal.WHITE, 1), lp(0, WRAP, 1f))
            val pct = Ui.text(a, "0%", 11f, Pal.WHITE, 2)
            top.addView(pct)
            col.addView(top)
            val bar = BarView(a, c1, c2)
            col.addView(bar, lp(MATCH, a.dp(6f)).apply { topMargin = a.dp(2f) })
            r.addView(col, lp(0, WRAP, 1f))
            stats.addView(r, lp(MATCH, WRAP).apply { bottomMargin = a.dp(4f) })
            bars[key] = bar to pct
        }
        statRow("hap", G.HEART, 0xFFFF6B8B.toInt(), 0xFFE11D48.toInt(), "Happiness")
        statRow("hun", G.BOWL, 0xFFFFC83D.toInt(), 0xFFF97316.toInt(), "Hunger")
        statRow("ene", G.MOON, 0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt(), "Energy")
        info.addView(stats, lp(0, WRAP, 0.95f).apply { leftMargin = a.dp(8f) })
        root.addView(info)

        // stage with side columns
        val mid = FrameLayout(a)
        stage = PetStage(a, sp, zoom = 1.22f)
        stage.onStatsChanged = { refreshStats() }
        mid.addView(stage, FrameLayout.LayoutParams(MATCH, MATCH).apply { leftMargin = a.dp(56f); rightMargin = a.dp(30f) })
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun side(g: G, label: String, c1: Int, c2: Int, badge: Boolean = false, f: () -> Unit) =
            left.addView(Ui.sideButton(a, g, label, c1, c2, badge, onClick = f), lp(WRAP, WRAP).apply { bottomMargin = a.dp(7f) })
        side(G.PAW, "Pets", Pal.GOLD, 0xFFF59E0B.toInt()) { a.select(0) }
        side(G.SHIRT, "Outfits", 0xFFE879F9.toInt(), 0xFF9333EA.toInt()) { a.select(1) }
        side(G.SHOP, "Room", 0xFFFB7185.toInt(), 0xFFE11D48.toInt()) {
            a.showDialog(G.HOUSE, "Pet Room", "Decorate a cozy room for ${repo.nameOf(stage.ctl.species)} — beds, toys and wallpapers. Arriving in the next update!", "Can't wait")
        }
        side(G.GIFT, "Rewards", 0xFFFFB86B.toInt(), 0xFFF97316.toInt(), badge = repo.dailyAvailable()) { daily() }
        side(G.TASKS, "Quests", 0xFFC4B5FD.toInt(), 0xFF8B5CF6.toInt(), badge = repo.pendingQuestRewards > 0) { a.select(3) }
        mid.addView(left, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = a.dp(6f) })

        val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun round(g: G, f: () -> Unit) {
            val v = FrameLayout(a).apply { background = glass(a, 30f, 0x40FFFFFF, 0x66FFFFFF) }
            v.addView(Ui.icon(a, g, 0xFFF1F5F9.toInt(), 0xFFCBD5E1.toInt(), 22f), FrameLayout.LayoutParams(a.dp(22f), a.dp(22f), Gravity.CENTER))
            Ui.pressable(v, f)
            right.addView(v, lp(a.dp(46f), a.dp(46f)).apply { bottomMargin = a.dp(10f) })
        }
        round(G.CAMERA) { togglePhotoMode() }
        round(G.GEAR) { a.select(4) }
        round(G.MOVE) { a.select(0) }
        mid.addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = a.dp(10f) })
        root.addView(mid, lp(MATCH, 0, 1f))

        // on-screen toggle
        val onScreen = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(a, 22f, 0x40302268, 0x88A78BFA.toInt()); setPadding(a.dp(12f), a.dp(8f), a.dp(12f), a.dp(8f))
        }
        onScreen.addView(Ui.icon(a, G.PHONE, 0xFFC4B5FD.toInt(), Pal.PURPLE, 26f))
        val txt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(10f), 0, 0, 0) }
        txt.addView(Ui.text(a, "Show on my screen", 15f, Pal.WHITE, 2))
        txt.addView(Ui.text(a, "Lives over your home screen & apps", 11.5f, Pal.TEXT_DIM, 0))
        onScreen.addView(txt, lp(0, WRAP, 1f))
        overlaySwitch = PillSwitch(a, a.overlayOn) { on -> a.setOverlay(on) }
        onScreen.addView(overlaySwitch, lp(a.dp(52f), a.dp(30f)))
        root.addView(onScreen, lp(MATCH, WRAP).apply { setMargins(a.dp(12f), 0, a.dp(12f), a.dp(8f)) })

        // action dock
        val dock = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = Ui.gradient(a, 34f, 0x553B2A8A, 0x33201646, stroke = 0x99F0ABFC.toInt(), strokeW = 1.5f)
            setPadding(a.dp(6f), a.dp(10f), a.dp(6f), a.dp(8f))
        }
        dock.addView(Ui.orbButton(a, G.BOWL, "Feed", 0xFFFFC83D.toInt(), 0xFFFF7A7A.toInt(), 0xFFC81E4A.toInt()) { stage.feed() }, lp(0, WRAP, 1f))
        dock.addView(Ui.orbButton(a, G.BALL, "Play", 0xFFC084FC.toInt(), 0xFFA78BFA.toInt(), 0xFF6D28D9.toInt()) { stage.play() }, lp(0, WRAP, 1f))
        dock.addView(Ui.orbButton(a, G.MOON, "Sleep", 0xFF38BDF8.toInt(), 0xFF818CF8.toInt(), 0xFF3730A3.toInt()) { stage.sleep() }, lp(0, WRAP, 1f))
        dock.addView(Ui.orbButton(a, G.HEART, "Care", 0xFFF472B6.toInt(), 0xFFF9A8D4.toInt(), 0xFFDB2777.toInt()) { stage.care() }, lp(0, WRAP, 1f))
        root.addView(dock, lp(MATCH, WRAP).apply { setMargins(a.dp(10f), 0, a.dp(10f), a.dp(8f)) })

        chrome = listOf(header, info, left, right, onScreen, dock)
        view = root
        refreshStats()
    }

    private fun togglePhotoMode() {
        photoMode = !photoMode
        for (v in chrome) if (v !== chrome[3]) v.animate().alpha(if (photoMode) 0f else 1f).setDuration(250).start()
        a.toast(if (photoMode) "Photo mode — take a screenshot! Tap the camera again to exit." else "Photo mode off")
    }

    private fun daily() {
        val repo = a.repo
        if (repo.claimDaily()) {
            a.app.sound.play("chime", 0.7f); stage.celebrate()
            a.showDialog(G.GIFT, "Daily reward!", "+50 coins and +2 gems.\nCome back tomorrow for more treats.", "Yay!")
            a.select(2)
        } else a.showDialog(G.GIFT, "Already claimed", "Your next daily reward unlocks tomorrow.", "OK")
    }

    private fun refreshStats() {
        val st = a.repo.stats(stage.ctl.species)
        bars["hap"]?.let { it.first.animateTo(st.happiness / 100f); it.second.text = "${st.happiness.toInt()}%" }
        bars["hun"]?.let { it.first.animateTo(st.hunger / 100f); it.second.text = "${st.hunger.toInt()}%" }
        bars["ene"]?.let { it.first.animateTo(st.energy / 100f); it.second.text = "${st.energy.toInt()}%" }
        profileHolder.removeAllViews(); profileHolder.addView(Header.profileCard(a, stage.ctl.species))
    }

    override fun refresh() {
        refreshStats()
        overlaySwitch.set(a.overlayOn || (a.repo.overlayWanted && OverlayService.running), false)
    }
}
