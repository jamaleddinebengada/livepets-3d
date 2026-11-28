package com.livepets3d.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.livepets3d.app.ui.G
import com.livepets3d.app.ui.GlyphDrawable
import com.livepets3d.app.ui.Ui
import com.livepets3d.app.ui.dp

/** Temporary interaction controls shown next to a pet after a long-press; disappears automatically. */
class QuickMenu(private val ctx: Context, private val wm: WindowManager) {
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    fun show(anchorX: Int, anchorY: Int, anchorW: Int, pet: FloatingPet, onHideAll: () -> Unit) {
        hide()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE62A1F5C.toInt(), 0xE6181433.toInt())).apply {
                cornerRadius = ctx.dp(30f).toFloat(); setStroke(ctx.dp(1.5f), 0x99A78BFA.toInt())
            }
            setPadding(ctx.dp(8f), ctx.dp(6f), ctx.dp(8f), ctx.dp(6f))
            elevation = ctx.dp(8f).toFloat()
        }
        fun btn(g: G, c1: Int, c2: Int, action: () -> Unit) {
            val f = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; colors = intArrayOf(c1, c2); setStroke(ctx.dp(1.5f), 0x66FFFFFF) }
            }
            f.addView(ImageView(ctx).apply { setImageDrawable(GlyphDrawable(g, Color.WHITE, 0xFFEDE9FE.toInt())) }, FrameLayout.LayoutParams(ctx.dp(24f), ctx.dp(24f), Gravity.CENTER))
            Ui.pressable(f) { action(); hide() }
            row.addView(f, LinearLayout.LayoutParams(ctx.dp(44f), ctx.dp(44f)).apply { setMargins(ctx.dp(4f), 0, ctx.dp(4f), 0) })
        }
        btn(G.BOWL, 0xFFFF6B6B.toInt(), 0xFFC2185B.toInt()) { pet.feed() }
        btn(G.BALL, 0xFFA78BFA.toInt(), 0xFF6D28D9.toInt()) { pet.play() }
        btn(G.MOON, 0xFF60A5FA.toInt(), 0xFF1D4ED8.toInt()) { pet.nap() }
        if (pet.canFollow) btn(G.HAND, 0xFF34D399.toInt(), 0xFF047857.toInt()) { pet.startFollowMode() }
        btn(G.EYE_OFF, 0xFF64748B.toInt(), 0xFF334155.toInt()) { onHideAll() }

        val w = ctx.dp(232f); val h = ctx.dp(58f)
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sw = ctx.resources.displayMetrics.widthPixels
            x = (anchorX + anchorW / 2 - w / 2).coerceIn(ctx.dp(6f), sw - w - ctx.dp(6f))
            y = (anchorY - h + ctx.dp(18f)).coerceAtLeast(ctx.dp(40f))
        }
        row.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { hide(); true } else false }
        try {
            wm.addView(row, lp)
            view = row
            row.alpha = 0f; row.scaleX = 0.7f; row.scaleY = 0.7f
            row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        } catch (_: Exception) {}
        handler.postDelayed(hideRunnable, 4500)
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

}
