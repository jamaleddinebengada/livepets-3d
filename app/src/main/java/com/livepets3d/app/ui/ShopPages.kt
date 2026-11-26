package com.livepets3d.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.livepets3d.app.data.Accessory
import com.livepets3d.app.data.Slot
import com.livepets3d.app.monetization.Monetization
import com.livepets3d.app.monetization.Product
import com.livepets3d.app.monetization.ProductKind
import com.livepets3d.app.overlay.OverlayService
import com.livepets3d.app.ui.Ui.glass

private fun priceLabel(price: Int): Pair<G?, String> = when {
    price == 0 -> null to "Free"
    price > 0 -> G.COIN to "$price"
    else -> G.GEM to "${-price}"
}

// =====================================================================================  SHOP / WARDROBE
class ShopPage(private val a: MainActivity) : Page {
    override val view: View
    private val preview: PetStage
    private val grid = GridLayout(a).apply { columnCount = 3 }
    private val tabs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private var tab = 0
    private val tabNames = listOf("Skins", "Hats", "Glasses", "Clothes", "Collars")

    init {
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), a.dp(6f), a.dp(12f), a.dp(16f)) }
        scroll.addView(col)
        col.addView(Header.topRow(a, { a.openPremium() }, { a.openPremium() }).apply { setPadding(0, 0, 0, a.dp(6f)) })

        // premium banner
        val banner = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.gradient(a, 22f, 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFFF59E0B.toInt(), stroke = 0x99FFE08A.toInt(), strokeW = 1.5f)
            setPadding(a.dp(14f), a.dp(12f), a.dp(12f), a.dp(12f))
        }
        banner.addView(Ui.icon(a, G.CROWN, 0xFFFFF3B0.toInt(), Pal.GOLD, 34f))
        val bt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(10f), 0, 0, 0) }
        bt.addView(Ui.text(a, if (Monetization.billing.isPremium) "Premium active" else "Go Premium", 16f, Pal.WHITE, 2))
        bt.addView(Ui.text(a, "All animals · 3 pets on screen · gems", 12f, 0xEEFFFFFF.toInt(), 0))
        banner.addView(bt, lp(0, WRAP, 1f))
        banner.addView(Ui.icon(a, G.PLUS, Pal.WHITE, Pal.WHITE, 18f))
        Ui.pressable(banner) { a.openPremium() }
        col.addView(banner, lp(MATCH, WRAP).apply { topMargin = a.dp(4f) })

        col.addView(Ui.sectionTitle(a, "Wardrobe · ${a.repo.nameOf(a.repo.species)}"))
        preview = PetStage(a, a.repo.species, zoom = 1.35f)
        val pv = FrameLayout(a).apply { background = glass(a, 24f, 0x26FFFFFF, 0x55C4B5FD) }
        pv.addView(preview, FrameLayout.LayoutParams(MATCH, a.dp(230f)))
        col.addView(pv, lp(MATCH, a.dp(230f)))
        val hs = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(tabs)
        col.addView(hs, lp(MATCH, WRAP).apply { topMargin = a.dp(10f) })
        col.addView(grid, lp(MATCH, WRAP).apply { topMargin = a.dp(8f) })
        col.addView(Ui.text(a, "Earn coins from quests, level-ups and the daily reward. Outfits are saved per animal.", 12f, Pal.TEXT_DIM, 0).apply {
            gravity = Gravity.CENTER; setPadding(0, a.dp(12f), 0, 0); maxLines = 3
        }, lp(MATCH, WRAP))
        view = scroll
        buildTabs(); buildGrid()
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        for ((i, n) in tabNames.withIndex()) {
            val t = Ui.text(a, n, 13.5f, Pal.WHITE, 2).apply {
                gravity = Gravity.CENTER; setPadding(a.dp(16f), a.dp(8f), a.dp(16f), a.dp(8f))
                background = if (i == tab) Ui.gradient(a, 20f, Pal.PURPLE, 0xFFC026D3.toInt()) else glass(a, 20f, 0x22FFFFFF, 0x44FFFFFF)
            }
            Ui.pressable(t) { tab = i; buildTabs(); buildGrid() }
            tabs.addView(t, lp(WRAP, WRAP).apply { rightMargin = a.dp(6f) })
        }
    }

    private fun cell(title: String, swatch: Int?, glyph: G?, price: Int, owned: Boolean, equipped: Boolean, onTap: () -> Unit): View {
        val c = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = if (equipped) Ui.gradient(a, 18f, 0x557C3AED, 0x33DB2777, stroke = Pal.GOLD, strokeW = 1.8f) else glass(a, 18f, 0x262E1F6B, 0x44A78BFA)
            setPadding(a.dp(6f), a.dp(10f), a.dp(6f), a.dp(8f))
        }
        val top = FrameLayout(a)
        if (swatch != null) top.addView(View(a).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(swatch or 0xFF000000.toInt()); setStroke(a.dp(2f), 0x88FFFFFF.toInt()) }
        }, FrameLayout.LayoutParams(a.dp(40f), a.dp(40f), Gravity.CENTER))
        if (glyph != null) top.addView(Ui.icon(a, glyph, 0xFFF0ABFC.toInt(), Pal.PURPLE, 34f), FrameLayout.LayoutParams(a.dp(36f), a.dp(36f), Gravity.CENTER))
        c.addView(top, lp(a.dp(44f), a.dp(44f)))
        c.addView(Ui.text(a, title, 11.5f, Pal.WHITE, 1).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(4f), 0, a.dp(2f)) })
        val tag = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        when {
            equipped -> tag.addView(Ui.text(a, "Equipped", 10.5f, Pal.GOLD, 2))
            owned -> tag.addView(Ui.text(a, "Owned", 10.5f, 0xFFC4B5FD.toInt(), 2))
            else -> {
                val (g, label) = priceLabel(price)
                if (g != null) tag.addView(Ui.icon(a, g, if (g == G.COIN) 0xFFFFE08A.toInt() else 0xFFF0ABFC.toInt(), if (g == G.COIN) 0xFFF59E0B.toInt() else 0xFFA21CAF.toInt(), 14f))
                tag.addView(Ui.text(a, " $label", 11f, Pal.WHITE, 2))
            }
        }
        c.addView(tag)
        Ui.pressable(c, onTap)
        return c
    }

    private fun addCell(v: View) = grid.addView(v, GridLayout.LayoutParams().apply {
        width = 0; height = WRAP; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(a.dp(3f), a.dp(3f), a.dp(3f), a.dp(3f))
    })

    private fun tryBuy(key: String, price: Int, title: String, then: () -> Unit) {
        val repo = a.repo
        if (price == 0 || repo.owns(key)) { repo.grant(key); then(); return }
        val (_, label) = priceLabel(price)
        val currency = if (price > 0) "coins" else "gems"
        a.showDialog(G.SHIRT, "Unlock $title?", "Price: $label $currency\nYou have ${repo.coins} coins · ${repo.gems} gems.", "Unlock", {
            if (repo.buy(key, price)) { a.app.sound.play("coin", 0.7f); then() }
            else a.showDialog(G.GEM, "Not enough $currency", "Complete quests and claim daily rewards to earn more, or get gems in Premium.", "OK")
        }, "Cancel")
    }

    private fun applyAndRefresh() {
        a.repo.dress(a.repo.species, preview.ctl)
        preview.celebrate()
        OverlayService.refresh(a)
        buildGrid()
    }

    private fun buildGrid() {
        grid.removeAllViews()
        val repo = a.repo
        val sp = repo.species
        if (sp.sprite != null) {
            tabs.visibility = View.GONE
            grid.addView(Ui.text(a, "${repo.nameOf(sp)} uses your original artwork, so its look is kept exactly as designed. Outfits and colour skins apply to the 3D animals (Panda, Rabbit, Dragon).", 13f, Pal.TEXT_DIM, 0).apply { maxLines = 6; setPadding(a.dp(6f), a.dp(6f), a.dp(6f), a.dp(6f)) },
                GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, 3, 1f); width = 0 })
            return
        }
        tabs.visibility = View.VISIBLE
        when (tab) {
            0 -> {
                if (sp.skins.isEmpty()) addCell(cell("Original", sp.look?.primary, null, 0, true, true) {})
                for (sk in sp.skins) {
                    val key = "skin_${sp.name}_${sk.id}"
                    val owned = sk.price == 0 || repo.owns(key)
                    addCell(cell(sk.name, sk.primary, null, sk.price, owned, repo.skinOf(sp) == sk.id) {
                        tryBuy(key, sk.price, sk.name) { repo.setSkin(sp, sk.id); applyAndRefresh() }
                    })
                }
            }
            1, 2, 3 -> {
                val slot = when (tab) { 1 -> Slot.HAT; 2 -> Slot.GLASSES; else -> Slot.CLOTHES }
                val current = repo.equipped(sp, slot)
                addCell(cell("None", null, G.CLOSE, 0, true, current == null) { repo.equip(sp, slot, null); applyAndRefresh() })
                for (acc in Accessory.values().filter { it.slot == slot }) {
                    val key = "acc_${acc.name}"
                    val g = when (slot) { Slot.HAT -> G.CROWN; Slot.GLASSES -> G.EYE_OFF; Slot.CLOTHES -> G.SHIRT }
                    addCell(cell(acc.title, acc.color, if (slot == Slot.GLASSES) null else g, acc.price, acc.price == 0 || repo.owns(key), current == acc) {
                        tryBuy(key, acc.price, acc.title) { repo.equip(sp, slot, acc); applyAndRefresh() }
                    })
                }
            }
            else -> {
                val colors = listOf(0xE0283C to "Ruby", 0x8B5CF6 to "Violet", 0x38BDF8 to "Sky", 0xFFC83D to "Gold", 0xF472B6 to "Bubblegum", 0x22C55E to "Mint")
                addCell(cell("No collar", null, G.CLOSE, 0, true, repo.collarColor == 0) { repo.collarColor = 0; applyAndRefresh() })
                for ((c, n) in colors) addCell(cell(n, c, null, 0, true, repo.collarColor == c) { repo.collarColor = c; applyAndRefresh() })
            }
        }
    }

    override fun refresh() { buildGrid() }
}

// =====================================================================================  PREMIUM
class PremiumPage(private val a: MainActivity) : Page {
    override val view: View
    private val plans = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val status = Ui.text(a, "", 12f, Pal.TEXT_DIM, 0).apply { gravity = Gravity.CENTER; maxLines = 4 }
    private val listener: () -> Unit = { buildPlans() }

    init {
        val billing = Monetization.billing
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), a.dp(6f), a.dp(12f), a.dp(16f)) }
        scroll.addView(col)
        col.addView(Header.topRow(a, {}, {}).apply { setPadding(0, 0, 0, a.dp(6f)) })
        val hero = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.gradient(a, 28f, 0xFF4C1D95.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), stroke = 0x99FFE08A.toInt(), strokeW = 2f)
            setPadding(a.dp(18f), a.dp(18f), a.dp(18f), a.dp(18f))
        }
        hero.addView(Ui.icon(a, G.CROWN, 0xFFFFF3B0.toInt(), Pal.GOLD, 64f))
        hero.addView(Ui.text(a, "LivePets Premium", 24f, Pal.WHITE, 2).apply { setPadding(0, a.dp(6f), 0, a.dp(8f)) })
        for (line in listOf("Every animal, including fantasy creatures", "Up to 3 pets on screen together", "No ads — never over your apps", "Exclusive outfits & bonus gems")) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(4f), 0, 0) }
            r.addView(Ui.icon(a, G.CHECK, Pal.WHITE, 0xFFFDE68A.toInt(), 16f)); r.addView(Ui.text(a, "  $line", 13.5f, Pal.WHITE, 1))
            hero.addView(r, lp(MATCH, WRAP))
        }
        Ui.glow(hero, Pal.PINK, 12f)
        col.addView(hero, lp(MATCH, WRAP))
        col.addView(plans, lp(MATCH, WRAP).apply { topMargin = a.dp(12f) })
        col.addView(Ui.pill(a, G.CHECK, "Restore purchases", 0x33FFFFFF, 0x1AFFFFFF, 0x66FFFFFF) {
            billing.restore { _, msg -> a.showDialog(G.CROWN, "Restore purchases", msg, "OK"); buildPlans() }
        }.apply { gravity = Gravity.CENTER }, lp(MATCH, a.dp(46f)).apply { topMargin = a.dp(6f) })
        col.addView(status, lp(MATCH, WRAP).apply { topMargin = a.dp(10f) })
        col.addView(Ui.text(a, "Subscriptions renew automatically until cancelled in Google Play › Payments & subscriptions. Prices are shown in your local currency by Google Play.", 11f, Pal.TEXT_DIM, 0).apply {
            gravity = Gravity.CENTER; maxLines = 5; setPadding(0, a.dp(8f), 0, 0)
        }, lp(MATCH, WRAP))
        view = scroll
        buildPlans()
    }

    private fun buildPlans() {
        val billing = Monetization.billing
        plans.removeAllViews()
        status.text = (if (billing.isPremium) "Premium is active on this device.\n" else "") + billing.statusMessage
        for (p in billing.products()) plans.addView(planRow(p), lp(MATCH, WRAP).apply { bottomMargin = a.dp(8f) })
    }

    private fun planRow(p: Product): View {
        val gems = p.kind == ProductKind.CONSUMABLE
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (p.highlight) Ui.gradient(a, 20f, 0x557C3AED, 0x33DB2777, stroke = Pal.GOLD, strokeW = 1.8f) else glass(a, 20f, 0x2A2E1F6B, 0x55A78BFA)
            setPadding(a.dp(14f), a.dp(12f), a.dp(12f), a.dp(12f))
        }
        r.addView(Ui.icon(a, if (gems) G.GEM else G.CROWN, if (gems) 0xFFF0ABFC.toInt() else Pal.GOLD, if (gems) 0xFFA21CAF.toInt() else 0xFFF59E0B.toInt(), 30f))
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(12f), 0, a.dp(8f), 0) }
        t.addView(Ui.text(a, p.title, 15f, Pal.WHITE, 2)); t.addView(Ui.text(a, p.subtitle, 12f, Pal.TEXT_DIM, 0))
        r.addView(t, lp(0, WRAP, 1f))
        val owned = !gems && Monetization.billing.isPremium
        r.addView(Ui.pill(a, null, if (owned) "Active" else p.displayPrice, if (owned) 0x4434D399 else Pal.PURPLE, if (owned) 0x2210B981 else Pal.PINK, 0x66FFFFFF) {
            if (owned) return@pill
            Monetization.billing.purchase(a, p) { ok, msg ->
                if (ok) a.app.sound.play("coin", 0.8f)
                a.showDialog(if (gems) G.GEM else G.CROWN, p.title, msg, "OK"); buildPlans(); a.refreshNav()
            }
        })
        return r
    }

    fun attach() = Monetization.billing.addListener(listener)
    override fun onHide() = Monetization.billing.removeListener(listener)
    override fun refresh() = buildPlans()
}
