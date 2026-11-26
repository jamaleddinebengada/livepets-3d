package com.livepets3d.app.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.livepets3d.app.data.PetCategory
import com.livepets3d.app.data.PetSpecies
import com.livepets3d.app.monetization.Monetization
import com.livepets3d.app.overlay.OverlayService
import com.livepets3d.app.ui.Ui.glass

/** "My Pets": collection, naming, companions and the floating overlay controls. */
class PetsPage(private val a: MainActivity) : Page {
    override val view: View
    private var viewing: PetSpecies = a.repo.species
    private val stage: PetStage
    private val nameText: TextView
    private val genderText: TextView
    private val levelText: TextView
    private val actionRow = LinearLayout(a)
    private val cards = HashMap<PetSpecies, AnimalFaceView>()
    private lateinit var overlaySwitch: PillSwitch

    init {
        val repo = a.repo
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), a.dp(6f), a.dp(12f), a.dp(16f)) }
        scroll.addView(col)
        col.addView(Header.topRow(a, { a.select(1) }, { a.select(1) }).apply { setPadding(0, 0, 0, a.dp(6f)) })

        // ---- hero preview
        val hero = FrameLayout(a).apply { background = glass(a, 28f, 0x30FFFFFF, 0x66C4B5FD) }
        stage = PetStage(a, viewing, zoom = 1.3f)
        hero.addView(stage, FrameLayout.LayoutParams(MATCH, a.dp(250f)))
        val nameRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(14f), a.dp(12f), a.dp(14f), 0) }
        nameText = Ui.text(a, "", 22f, Pal.WHITE, 2).apply { maxLines = 1 }
        genderText = Ui.text(a, "", 22f, Pal.PINK, 2)
        nameRow.addView(nameText); nameRow.addView(genderText)
        nameRow.addView(Ui.icon(a, G.PEN, 0xFFE9D5FF.toInt(), 0xFFA78BFA.toInt(), 22f).apply { setPadding(a.dp(6f), 0, 0, 0); Ui.pressable(this) { rename() } })
        hero.addView(nameRow, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        levelText = Ui.text(a, "", 13f, Pal.TEXT_DIM, 1).apply { setPadding(a.dp(14f), a.dp(46f), 0, 0) }
        hero.addView(levelText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP))
        col.addView(hero, lp(MATCH, a.dp(250f)))
        actionRow.orientation = LinearLayout.HORIZONTAL
        col.addView(actionRow, lp(MATCH, WRAP).apply { topMargin = a.dp(10f) })

        // ---- overlay panel
        col.addView(overlayPanel(), lp(MATCH, WRAP).apply { topMargin = a.dp(14f) })

        // ---- collection
        for (cat in PetCategory.values()) {
            val title = when (cat) { PetCategory.DOMESTIC -> "Domestic friends"; PetCategory.WILD -> "Wild babies"; PetCategory.FANTASY -> "Fantasy creatures" }
            col.addView(Ui.sectionTitle(a, title))
            val grid = GridLayout(a).apply { columnCount = 4 }
            for (s in PetSpecies.values().filter { it.category == cat }) grid.addView(card(s), GridLayout.LayoutParams().apply {
                width = 0; height = WRAP; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(a.dp(3f), a.dp(3f), a.dp(3f), a.dp(3f))
            })
            col.addView(grid, lp(MATCH, WRAP))
        }
        view = scroll
        show(viewing)
    }

    private fun card(s: PetSpecies): View {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = glass(a, 18f, 0x262E1F6B, 0x44A78BFA); setPadding(a.dp(4f), a.dp(8f), a.dp(4f), a.dp(8f))
        }
        val face = AnimalFaceView(a, s, highlighted = s == viewing)
        cards[s] = face
        c.addView(face, LinearLayout.LayoutParams(a.dp(56f), a.dp(56f)))
        c.addView(Ui.text(a, s.displayName, 10.5f, if (s.available) Pal.WHITE else Pal.TEXT_DIM, 1).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(4f), 0, 0); maxLines = 2 })
        val tag = when {
            !s.available -> "Soon"
            s == a.repo.species -> "Main"
            s in a.repo.companions -> "Buddy"
            s.premium -> "Premium"
            else -> null
        }
        if (tag != null) c.addView(Ui.text(a, tag, 9.5f, if (tag == "Premium") Pal.GOLD else 0xFFC4B5FD.toInt(), 2).apply { gravity = Gravity.CENTER })
        Ui.pressable(c) {
            if (!s.available) a.showDialog(G.LOCK, s.displayName, "${s.displayName} is being sculpted and animated by our team. It will join the collection in an upcoming update!", "OK")
            else show(s)
        }
        return c
    }

    private fun show(s: PetSpecies) {
        val repo = a.repo
        cards[viewing]?.let { it.highlighted = false; it.invalidate() }
        viewing = s
        cards[s]?.let { it.highlighted = true; it.invalidate() }
        if (stage.ctl.species != s) stage.setSpecies(s)
        nameText.text = repo.nameOf(s)
        val f = repo.isFemale(s)
        genderText.text = if (f) " ♀" else " ♂"
        genderText.setTextColor(if (f) Pal.PINK else Pal.SKY)
        Ui.pressable(genderText) { repo.setFemale(s, !repo.isFemale(s)); show(s) }
        levelText.text = "${s.displayName} · Lv. ${repo.levelOf(s)}" + if (s.premium) " · Premium (free in beta)" else ""
        buildActions()
    }

    private fun buildActions() {
        val repo = a.repo
        actionRow.removeAllViews()
        val s = viewing
        if (s == repo.species) {
            actionRow.addView(Ui.pill(a, G.CHECK, "Your main pet", 0x4434D399, 0x2210B981, Pal.GREEN).apply { gravity = Gravity.CENTER }, lp(0, a.dp(48f), 1f))
        } else {
            actionRow.addView(Ui.pill(a, G.PAW, "Choose ${repo.nameOf(s)}", Pal.PURPLE, Pal.PINK, 0x66FFFFFF) {
                if (!Monetization.isUnlocked(s)) { a.select(1); return@pill }
                repo.species = s
                repo.companions = repo.companions - s
                a.app.sound.play(s.sound, 0.8f); stage.celebrate()
                OverlayService.refresh(a); a.refreshNav(); show(s); rebuildTags()
            }.apply { gravity = Gravity.CENTER }, lp(0, a.dp(48f), 1f))
            val isBuddy = s in repo.companions
            actionRow.addView(Ui.pill(a, if (isBuddy) G.CLOSE else G.PLUS, if (isBuddy) "Remove buddy" else "Add buddy", 0x33FFFFFF, 0x1AFFFFFF, 0x66FFFFFF) {
                val cur = repo.companions
                if (isBuddy) repo.companions = cur - s
                else {
                    val max = Monetization.maxPetsOnScreen - 1
                    if (cur.size >= max) { a.toast("Up to ${Monetization.maxPetsOnScreen} animals can be on screen together"); return@pill }
                    repo.companions = cur + s
                    a.toast("${repo.nameOf(s)} will join ${repo.nameOf(repo.species)} on your screen")
                }
                OverlayService.refresh(a); buildActions(); rebuildTags()
            }.apply { gravity = Gravity.CENTER }, lp(0, a.dp(48f), 1f).apply { leftMargin = a.dp(8f) })
        }
    }

    private fun rebuildTags() { a.select(0) }

    private fun rename() {
        val e = EditText(a).apply { setText(a.repo.nameOf(viewing)); inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS; setSelectAllOnFocus(true) }
        AlertDialog.Builder(a).setTitle("Name your ${viewing.displayName}").setView(e)
            .setPositiveButton("Save") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) { a.repo.setName(viewing, n); show(viewing); OverlayService.refresh(a) }
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ overlay panel (reference screen 3)
    private fun overlayPanel(): View {
        val repo = a.repo
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.gradient(a, 26f, 0xCC2A1F5C.toInt(), 0xCC191338.toInt(), stroke = 0x99A78BFA.toInt(), strokeW = 1.5f)
            setPadding(a.dp(14f), a.dp(12f), a.dp(14f), a.dp(12f))
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.icon(a, G.PHONE, 0xFFC4B5FD.toInt(), Pal.PURPLE, 24f))
        head.addView(Ui.text(a, "  Overlay Pet ", 18f, Pal.WHITE, 2))
        head.addView(Ui.text(a, "(on screen)", 13f, Pal.TEXT_DIM, 0), lp(0, WRAP, 1f))
        overlaySwitch = PillSwitch(a, a.overlayOn) { on -> a.setOverlay(on) }
        head.addView(overlaySwitch, lp(a.dp(52f), a.dp(30f)))
        panel.addView(head)

        // tabs
        val tabs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val body = FrameLayout(a)
        val tabViews = ArrayList<View>()
        fun tab(g: G, label: String, idx: Int, build: () -> View) {
            val t = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(a.dp(6f), a.dp(8f), a.dp(6f), a.dp(8f)) }
            t.addView(Ui.icon(a, g, Pal.WHITE, 0xFFE9D5FF.toInt(), 18f))
            t.addView(Ui.text(a, " $label", 13.5f, Pal.WHITE, 2))
            tabViews.add(t)
            Ui.pressable(t) {
                for ((i, v) in tabViews.withIndex()) v.background = if (i == idx) Ui.gradient(a, 20f, Pal.PURPLE, 0xFFC026D3.toInt()) else glass(a, 20f, 0x22FFFFFF, 0x44FFFFFF)
                body.removeAllViews(); body.addView(build())
            }
            tabs.addView(t, lp(0, WRAP, 1f).apply { setMargins(a.dp(3f), 0, a.dp(3f), 0) })
        }
        fun sliderRow(g: G, value: Float, onChange: (Float) -> Unit): View {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(10f), 0, a.dp(4f)) }
            r.addView(Ui.icon(a, g, Pal.WHITE, 0xFFC4B5FD.toInt(), 18f))
            r.addView(SliderView(a, value, onChange), lp(0, a.dp(36f), 1f).apply { setMargins(a.dp(8f), 0, a.dp(8f), 0) })
            r.addView(Ui.icon(a, g, Pal.WHITE, 0xFFC4B5FD.toInt(), 28f))
            return r
        }
        tab(G.EXPAND, "Size", 0) { sliderRow(G.PAW, (repo.petSize - 0.6f) / 1.1f) { v -> repo.petSize = 0.6f + v * 1.1f; OverlayService.refresh(a) } }
        tab(G.MOVE, "Position", 1) {
            val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(10f), 0, a.dp(4f)) }
            c.addView(Ui.text(a, "Drag your pet anywhere on the screen. With Gravity on it falls back to the floor; with Gravity off it stays where you drop it. Drop it near an edge to make it climb!", 12.5f, Pal.TEXT_DIM, 0).apply { maxLines = 6 })
            c.addView(Ui.pill(a, G.PAW, "Call my pet back", 0x55FFFFFF, 0x22FFFFFF, 0x66FFFFFF) {
                if (OverlayService.running) { OverlayService.send(a, OverlayService.ACTION_RECALL); a.toast("${repo.nameOf(repo.species)} is coming!") }
                else a.toast("Turn on the overlay first")
            }.apply { gravity = Gravity.CENTER }, lp(MATCH, a.dp(44f)).apply { topMargin = a.dp(8f) })
            c
        }
        tab(G.RUN, "Speed", 2) { sliderRow(G.RUN, (repo.petSpeed - 0.4f) / 1.8f) { v -> repo.petSpeed = 0.4f + v * 1.8f; OverlayService.refresh(a) } }
        panel.addView(tabs, lp(MATCH, WRAP).apply { topMargin = a.dp(10f) })
        panel.addView(body, lp(MATCH, WRAP))
        tabViews[0].background = Ui.gradient(a, 20f, Pal.PURPLE, 0xFFC026D3.toInt())
        for (i in 1 until tabViews.size) tabViews[i].background = glass(a, 20f, 0x22FFFFFF, 0x44FFFFFF)
        body.addView(sliderRow(G.PAW, (repo.petSize - 0.6f) / 1.1f) { v -> repo.petSize = 0.6f + v * 1.1f; OverlayService.refresh(a) })

        // options
        val opts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = glass(a, 18f, 0x1AFFFFFF, 0x33FFFFFF); setPadding(a.dp(12f), a.dp(4f), a.dp(12f), a.dp(4f)) }
        fun opt(g: G, label: String, sub: String, value: Boolean, set: (Boolean) -> Unit) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(8f), 0, a.dp(8f)) }
            r.addView(Ui.icon(a, g, Pal.WHITE, 0xFFC4B5FD.toInt(), 20f))
            val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(10f), 0, a.dp(6f), 0) }
            t.addView(Ui.text(a, label, 14.5f, Pal.WHITE, 1)); t.addView(Ui.text(a, sub, 11f, Pal.TEXT_DIM, 0))
            r.addView(t, lp(0, WRAP, 1f))
            r.addView(CheckBoxView(a, value) { v -> set(v); OverlayService.refresh(a) }, lp(a.dp(26f), a.dp(26f)))
            opts.addView(r)
        }
        opt(G.PHONE, "Always on screen", "Bring your pet back each time you open the app", repo.overlayWanted) { repo.overlayWanted = it }
        opt(G.HAND, "Tap interaction", "Off = pet is fully click-through", repo.tapInteraction) { repo.tapInteraction = it }
        opt(G.GRAVITY, "Gravity mode", "Falls to the bottom of the screen when dropped", repo.gravity) { repo.gravity = it }
        opt(G.CLIMB, "Wall climbing", "Cats, pandas, foxes & more climb the screen edges", repo.climbing) { repo.climbing = it }
        opt(G.BOLT, "Battery saver", "Lower frame rate and lighter fur rendering", repo.batterySaver) { repo.batterySaver = it }
        panel.addView(opts, lp(MATCH, WRAP).apply { topMargin = a.dp(10f) })
        return panel
    }

    override fun refresh() {
        overlaySwitch.set(a.overlayOn, false)
    }
}
