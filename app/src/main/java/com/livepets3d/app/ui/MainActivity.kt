package com.livepets3d.app.ui

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.livepets3d.app.LivePetsApp
import com.livepets3d.app.R
import com.livepets3d.app.overlay.OverlayService

interface Page {
    val view: View
    fun refresh() {}
    fun onHide() {}
}

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    val app: LivePetsApp get() = LivePetsApp.instance
    val repo get() = app.repo
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var bgTint: View
    private var current: Page? = null
    private var currentTab = 2
    private var pendingActivate = false
    private val navItems = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this)
        root.addView(ImageView(this).apply { setImageResource(R.drawable.bg_scene); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH, MATCH))
        bgTint = View(this)
        root.addView(bgTint, FrameLayout.LayoutParams(MATCH, MATCH))
        applyTheme()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageHost = FrameLayout(this)
        content.addView(pageHost, lp(MATCH, 0, 1f))
        nav = buildNav()
        content.addView(nav, lp(MATCH, WRAP).apply { setMargins(dp(10f), 0, dp(10f), dp(6f)) })
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars()); top = i.top; bottom = i.bottom
            } else { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom }
            content.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)
        select(2)
        if (!repo.onboarded) root.post { showWelcome() }
        else if (repo.overlayWanted && Settings.canDrawOverlays(this) && !OverlayService.running) OverlayService.start(this)
    }

    fun applyTheme() {
        bgTint.background = if (repo.darkMode) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66101530, 0x22101530, 0x88101530.toInt()))
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22FFFFFF, 0x10FFE4F2, 0x33FFFFFF))
    }

    override fun onPause() {
        super.onPause()
        app.music.pause()
    }

    override fun onResume() {
        super.onResume()
        app.music.start()
        if (pendingActivate && Settings.canDrawOverlays(this)) { pendingActivate = false; activateOverlay() }
        current?.refresh()
    }

    // ------------------------------------------------------------------ navigation
    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.gradient(this@MainActivity, 30f, 0xD9241B54.toInt(), 0xD9171233.toInt(), stroke = 0x88A78BFA.toInt(), strokeW = 1.5f)
            setPadding(dp(4f), dp(5f), dp(4f), dp(5f))
        }
        val items = listOf(
            Triple(G.PAW, "My Pets", 0), Triple(G.GIFT, "Shop", 1), Triple(G.HOUSE, "Home", 2), Triple(G.TASKS, "Quests", 3), Triple(G.GEAR, "Settings", 4),
        )
        for ((g, label, idx) in items) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(5f), 0, dp(5f)) }
            val iconView: View = if (idx == 0) AnimalFaceView(this, repo.species).also { it.tag = "face" }
            else ImageView(this).apply {
                setImageDrawable(when (g) {
                    G.GIFT -> GlyphDrawable(g, 0xFFFFB86B.toInt(), 0xFFF97316.toInt())
                    G.HOUSE -> GlyphDrawable(g, 0xFFFFE08A.toInt(), 0xFFF59E0B.toInt())
                    G.TASKS -> GlyphDrawable(g, 0xFFC4B5FD.toInt(), 0xFF8B5CF6.toInt())
                    else -> GlyphDrawable(g, 0xFFE2E8F0.toInt(), 0xFF94A3B8.toInt())
                })
            }
            cell.addView(iconView, LinearLayout.LayoutParams(dp(27f), dp(27f)))
            cell.addView(Ui.text(this, label, 10.5f, Pal.WHITE, 1).apply {
                setPadding(0, dp(3f), 0, 0); maxLines = 1; gravity = Gravity.CENTER
                ellipsize = null; setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val wrap = FrameLayout(this)
            wrap.addView(cell, FrameLayout.LayoutParams(MATCH, MATCH))
            if (idx == 3 && repo.pendingQuestRewards > 0) wrap.addView(Ui.text(this, "${repo.pendingQuestRewards}", 9f, Pal.WHITE, 2).apply {
                gravity = Gravity.CENTER; tag = "badge"
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.RED) }
            }, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { leftMargin = dp(16f) })
            Ui.pressable(wrap) { select(idx) }
            navItems.add(wrap)
            bar.addView(wrap, lp(0, dp(64f), 1f).apply { setMargins(dp(2f), 0, dp(2f), 0) })
        }
        return bar
    }

    fun select(idx: Int) {
        currentTab = idx
        for ((i, v) in navItems.withIndex()) {
            v.background = if (i == idx) Ui.gradient(this, 20f, 0x553B2A8A, 0x33FFC83D, stroke = Pal.GOLD, strokeW = 1.5f) else null
            if (i == idx) Ui.glow(v, Pal.GOLD, 8f) else v.elevation = 0f
        }
        current?.onHide()
        val page: Page = when (idx) {
            0 -> PetsPage(this)
            1 -> ShopPage(this)
            3 -> QuestsPage(this)
            4 -> SettingsPage(this)
            5 -> PremiumPage(this).also { it.attach() }
            else -> HomePage(this)
        }
        pageHost.removeAllViews()
        pageHost.addView(page.view, FrameLayout.LayoutParams(MATCH, MATCH))
        page.view.alpha = 0f; page.view.translationY = dpf(14f)
        page.view.animate().alpha(1f).translationY(0f).setDuration(240).start()
        current = page
        refreshNav()
    }

    fun openPremium() = select(5)

    fun refreshNav() {
        for (v in navItems) (v as ViewGroup).let { w ->
            val cell = w.getChildAt(0) as ViewGroup
            (cell.getChildAt(0) as? AnimalFaceView)?.species = repo.species
        }
        val q = navItems.getOrNull(3) as? FrameLayout ?: return
        q.findViewWithTag<View>("badge")?.visibility = if (repo.pendingQuestRewards > 0) View.VISIBLE else View.GONE
        (q.findViewWithTag<View>("badge") as? TextView)?.text = "${repo.pendingQuestRewards}"
    }

    // ------------------------------------------------------------------ overlay control
    fun setOverlay(on: Boolean) {
        if (!on) {
            repo.overlayWanted = false
            OverlayService.stop(this)
            toast("${repo.nameOf(repo.species)} came back inside the app")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        if (Settings.canDrawOverlays(this)) activateOverlay()
        else showDialog(
            G.PHONE, "Let your pet out!",
            "To live on your home screen and over your apps, LivePets needs the Android permission “Display over other apps”.\n\nYour pet only shows itself — we never read the content of other apps.",
            "Open settings", {
                pendingActivate = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }, "Not now",
        )
    }

    private fun activateOverlay() {
        repo.overlayWanted = true
        OverlayService.start(this)
        app.sound.play("chime", 0.6f)
        current?.refresh()
        showDialog(
            G.SPARK, "${repo.nameOf(repo.species)} is on your screen!",
            "Go to your home screen or open any app to see your pet.\n\n• Tap: happy reaction\n• Double-tap: celebration\n• Drag: move it around\n• Long-press: Feed · Play · Sleep · Hide\n\nTo hide it anytime: add the “Show pet” tile to your Quick Settings, or tap Pause in the notification.",
            "Go to home screen", { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, "Stay here",
        )
    }

    val overlayOn: Boolean get() = OverlayService.running && repo.overlayWanted

    // ------------------------------------------------------------------ dialogs
    fun showDialog(g: G, title: String, msg: String, primary: String, onPrimary: () -> Unit = {}, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
        val d = Dialog(this)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.gradient(this@MainActivity, 28f, 0xF2302268.toInt(), 0xF2161233.toInt(), stroke = 0xAAA78BFA.toInt(), strokeW = 1.5f)
            setPadding(dp(22f), dp(22f), dp(22f), dp(18f))
        }
        box.addView(Ui.icon(this, g, Pal.GOLD, 0xFFF59E0B.toInt(), 54f))
        box.addView(Ui.text(this, title, 20f, Pal.WHITE, 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, dp(8f)); maxLines = 3 })
        box.addView(Ui.text(this, msg, 14f, 0xDDFFFFFF.toInt(), 0).apply { gravity = Gravity.START; maxLines = 20; setLineSpacing(0f, 1.15f) })
        val btn = Ui.pill(this, null, primary, Pal.PURPLE, Pal.PINK, 0x66FFFFFF) { d.dismiss(); onPrimary() }
        btn.gravity = Gravity.CENTER
        box.addView(btn, lp(MATCH, dp(50f)).apply { topMargin = dp(18f) })
        if (secondary != null) box.addView(Ui.text(this, secondary, 14f, Pal.TEXT_DIM, 1).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, dp(2f))
            Ui.pressable(this) { d.dismiss(); onSecondary?.invoke() }
        }, lp(MATCH, WRAP))
        d.setContentView(box)
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun showWelcome() {
        showDialog(
            G.PAW, "Welcome to LivePets 3D",
            "Your little friend lives on your screen.\n\nChoose a companion, give it a name, then let it explore your phone — it walks, climbs, naps and plays over your apps.",
            "Choose my pet", { repo.onboarded = true; select(0) },
        )
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
