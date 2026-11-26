package com.livepets3d.app.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.livepets3d.app.R

object Pal {
    const val NAVY = 0xFF10152D.toInt()
    const val PURPLE = 0xFF8B5CF6.toInt()
    const val GOLD = 0xFFFFC83D.toInt()
    const val SKY = 0xFF38BDF8.toInt()
    const val PINK = 0xFFF472B6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GREEN = 0xFF34D399.toInt()
    const val ORANGE = 0xFFFB923C.toInt()
    const val RED = 0xFFF43F5E.toInt()
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
}

object Fonts {
    private var bold: Typeface? = null; private var medium: Typeface? = null; private var regular: Typeface? = null
    fun bold(c: Context): Typeface = bold ?: c.resources.getFont(R.font.poppins_bold).also { bold = it }
    fun medium(c: Context): Typeface = medium ?: c.resources.getFont(R.font.poppins_medium).also { medium = it }
    fun regular(c: Context): Typeface = regular ?: c.resources.getFont(R.font.poppins_regular).also { regular = it }
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Float): Int = context.dp(v)

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Ui {
    fun glass(c: Context, radius: Float = 22f, fill: Int = 0x2EFFFFFF, stroke: Int = 0x40FFFFFF, strokeW: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = c.dpf(radius)
            colors = intArrayOf(fill, (fill and 0x00FFFFFF) or ((Color.alpha(fill) * 0.45f).toInt() shl 24))
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke(c.dp(strokeW), stroke)
        }

    fun gradient(c: Context, radius: Float, vararg cols: Int, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR, stroke: Int = 0, strokeW: Float = 1.5f) =
        GradientDrawable(orientation, cols).apply { cornerRadius = c.dpf(radius); if (stroke != 0) setStroke(c.dp(strokeW), stroke) }

    fun text(c: Context, s: CharSequence, sizeSp: Float, color: Int = Pal.WHITE, weight: Int = 1): TextView = TextView(c).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        typeface = when (weight) { 2 -> Fonts.bold(c); 1 -> Fonts.medium(c); else -> Fonts.regular(c) }
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    fun icon(c: Context, g: G, c1: Int, c2: Int = c1, sizeDp: Float = 24f): ImageView = ImageView(c).apply {
        setImageDrawable(GlyphDrawable(g, c1, c2))
        layoutParams = LinearLayout.LayoutParams(c.dp(sizeDp), c.dp(sizeDp))
    }

    fun glow(v: View, color: Int, elevationDp: Float = 10f) {
        v.elevation = v.context.dpf(elevationDp)
        if (Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = color; v.outlineAmbientShadowColor = color }
    }

    /** Bouncy press feedback for any clickable view. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(260).start()
                    if (e.x >= 0 && e.y >= 0 && e.x <= view.width && e.y <= view.height) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        onClick()
                    }
                }
            }
            true
        }
    }

    /** Rounded glass tile with a glyph above a label (side menus in the reference design). */
    fun sideButton(c: Context, g: G, label: String, c1: Int, c2: Int, badge: Boolean = false, active: Boolean = false, onClick: () -> Unit): View {
        val box = FrameLayout(c)
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = if (active) gradient(c, 20f, 0x664B2A99, 0x33FFC83D, stroke = Pal.GOLD, strokeW = 1.5f) else glass(c, 20f, 0x2A8B5CF6, 0x55A78BFA)
            setPadding(c.dp(4f), c.dp(8f), c.dp(4f), c.dp(6f))
        }
        col.addView(icon(c, g, c1, c2, 30f))
        col.addView(text(c, label, 11f, Pal.WHITE, 1).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(2f), 0, 0); maxLines = 1 })
        box.addView(col, FrameLayout.LayoutParams(c.dp(68f), c.dp(66f)))
        if (badge) box.addView(badgeDot(c), FrameLayout.LayoutParams(c.dp(18f), c.dp(18f), Gravity.END or Gravity.TOP))
        glow(col, Pal.PURPLE, 6f)
        pressable(box, onClick)
        return box
    }

    fun badgeDot(c: Context): TextView = text(c, "!", 10f, Pal.WHITE, 2).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Pal.RED); setStroke(c.dp(1.5f), Pal.WHITE) }
    }

    /** Big round glowing action button (Feed / Play / Sleep / Care). */
    fun orbButton(c: Context, g: G, label: String, ring: Int, fill1: Int, fill2: Int, onClick: () -> Unit): View {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val orb = FrameLayout(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = c.dpf(40f)
                colors = intArrayOf(fill1, fill2)
                setGradientCenter(0.45f, 0.3f)
                setStroke(c.dp(2.5f), ring)
            }
        }
        orb.addView(ImageView(c).apply { setImageDrawable(GlyphDrawable(g, Color.WHITE, 0xFFE9E3FF.toInt())) }, FrameLayout.LayoutParams(c.dp(34f), c.dp(34f), Gravity.CENTER))
        glow(orb, ring, 14f)
        col.addView(orb, LinearLayout.LayoutParams(c.dp(60f), c.dp(60f)))
        col.addView(text(c, label, 13f, Pal.WHITE, 2).apply { setPadding(0, c.dp(5f), 0, 0); setShadowLayer(8f, 0f, 2f, 0x88000000.toInt()) })
        pressable(col, onClick)
        return col
    }

    fun pill(c: Context, g: G?, label: String, c1: Int, c2: Int, stroke: Int, textColor: Int = Pal.WHITE, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = gradient(c, 30f, c1, c2, stroke = stroke, strokeW = 1.5f)
            setPadding(c.dp(10f), c.dp(6f), c.dp(12f), c.dp(6f))
        }
        if (g != null) row.addView(icon(c, g, Pal.GOLD, 0xFFF59E0B.toInt(), 20f).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = c.dp(6f) })
        row.addView(text(c, label, 14f, textColor, 2))
        if (onClick != null) pressable(row, onClick)
        return row
    }

    fun sectionTitle(c: Context, s: String): TextView = text(c, s, 18f, Pal.WHITE, 2).apply { setPadding(c.dp(4f), c.dp(14f), 0, c.dp(8f)) }
}

/** Gradient progress bar with glossy highlight. */
class BarView(c: Context, var c1: Int, var c2: Int) : View(c) {
    var progress = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val r = h / 2f
        p.shader = null; p.color = 0x55000000; cv.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        if (progress > 0.01f) {
            val pw = maxOf(h, w * progress)
            p.shader = LinearGradient(0f, 0f, pw, 0f, c1, c2, Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(0f, 0f, pw, h), r, r, p)
            p.shader = LinearGradient(0f, 0f, 0f, h, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(h * 0.2f, h * 0.12f, pw - h * 0.2f, h * 0.55f), r, r, p)
        }
    }
    fun animateTo(v: Float) {
        ValueAnimator.ofFloat(progress, v).apply { duration = 500; addUpdateListener { progress = it.animatedValue as Float }; start() }
    }
}

/** iOS-style glowing toggle. */
class PillSwitch(c: Context, var checked: Boolean, val onChange: (Boolean) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pos = if (checked) 1f else 0f
    init { Ui.pressable(this) { set(!checked, true) } }
    fun set(v: Boolean, notify: Boolean) {
        checked = v
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply { duration = 180; addUpdateListener { pos = it.animatedValue as Float; invalidate() }; start() }
        if (notify) onChange(v)
    }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        p.shader = if (pos > 0.5f) LinearGradient(0f, 0f, w, 0f, Pal.PURPLE, Pal.PINK, Shader.TileMode.CLAMP) else null
        if (pos <= 0.5f) p.color = 0x44FFFFFF
        cv.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        p.shader = null; p.color = Color.WHITE
        val cx = r + (w - 2 * r) * pos
        p.setShadowLayer(6f, 0f, 2f, 0x55000000)
        cv.drawCircle(cx, r, r * 0.78f, p)
        p.clearShadowLayer()
    }
}

/** Gradient slider with a glowing thumb; value in 0..1. */
class SliderView(c: Context, var value: Float, val onChange: (Float) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = height / 2f
        value = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            onChange(value)
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        return true
    }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        val th = h * 0.22f; val cy = h / 2f
        p.shader = null; p.color = 0x40FFFFFF
        cv.drawRoundRect(RectF(r, cy - th / 2, w - r, cy + th / 2), th, th, p)
        val x = r + (w - 2 * r) * value
        p.shader = LinearGradient(r, 0f, x, 0f, Pal.PURPLE, Pal.PINK, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(r, cy - th / 2, x, cy + th / 2), th, th, p)
        p.shader = null
        p.color = 0x668B5CF6; cv.drawCircle(x, cy, r * 0.95f, p)
        p.color = Color.WHITE; cv.drawCircle(x, cy, r * 0.62f, p)
        p.color = Pal.PURPLE; cv.drawCircle(x, cy, r * 0.32f, p)
    }
}

/** Rounded check box matching the overlay panel in the reference. */
class CheckBoxView(c: Context, var checked: Boolean, val onChange: (Boolean) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { Ui.pressable(this) { checked = !checked; invalidate(); onChange(checked) } }
    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height).toFloat(); val r = s * 0.28f
        if (checked) {
            p.shader = LinearGradient(0f, 0f, s, s, Pal.PURPLE, Pal.PINK, Shader.TileMode.CLAMP); cv.drawRoundRect(RectF(0f, 0f, s, s), r, r, p)
            p.shader = null; p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.12f; p.strokeCap = Paint.Cap.ROUND
            val path = android.graphics.Path().apply { moveTo(s * 0.25f, s * 0.52f); lineTo(s * 0.43f, s * 0.7f); lineTo(s * 0.76f, s * 0.32f) }
            cv.drawPath(path, p); p.style = Paint.Style.FILL
        } else {
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.08f; p.color = 0x88FFFFFF.toInt()
            cv.drawRoundRect(RectF(s * 0.05f, s * 0.05f, s * 0.95f, s * 0.95f), r, r, p); p.style = Paint.Style.FILL
        }
    }
}

/** Glossy gradient logo "LivePets 3D" with a paw crown. */
class LogoView(c: Context, private val sizeSp: Float = 30f) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold(c); textSize = c.dpf(sizeSp) }
    private val paw = GlyphDrawable(G.PAW, Pal.GOLD, 0xFFF59E0B.toInt())
    override fun onMeasure(wm: Int, hm: Int) {
        val w = p.measureText("LivePets 3D") + context.dpf(10f)
        setMeasuredDimension(w.toInt(), (p.textSize * 1.55f).toInt())
    }
    override fun onDraw(cv: Canvas) {
        val base = p.textSize * 1.32f
        val x = context.dpf(4f)
        val s1 = "LivePets "; val w1 = p.measureText(s1)
        // outline
        p.style = Paint.Style.STROKE; p.strokeWidth = p.textSize * 0.16f; p.strokeJoin = Paint.Join.ROUND
        p.shader = null; p.color = 0xFF4C1D95.toInt()
        cv.drawText(s1, x, base, p); cv.drawText("3D", x + w1, base, p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, base - p.textSize, 0f, base, 0xFFFFF3B0.toInt(), 0xFFFFB020.toInt(), Shader.TileMode.CLAMP)
        cv.drawText(s1, x, base, p)
        p.shader = LinearGradient(0f, base - p.textSize, 0f, base, 0xFFFFFFFF.toInt(), Pal.SKY, Shader.TileMode.CLAMP)
        cv.drawText("3D", x + w1, base, p)
        p.shader = null
        val ps = (p.textSize * 0.62f).toInt()
        paw.setBounds((x + p.textSize * 1.2f).toInt(), (base - p.textSize * 1.42f).toInt(), (x + p.textSize * 1.2f).toInt() + ps, (base - p.textSize * 1.42f).toInt() + ps)
        paw.draw(cv)
    }
}
