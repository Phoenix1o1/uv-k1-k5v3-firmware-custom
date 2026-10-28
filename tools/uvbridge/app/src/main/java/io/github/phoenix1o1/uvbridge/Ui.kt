package io.github.phoenix1o1.uvbridge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The few building blocks the screens share, drawn in code with the colours
 * of res/values(-night)/colors.xml: cards, headings, pill buttons.
 */
object Ui {

    fun dp(c: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun color(c: Context, id: Int) = c.getColor(id)

    fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    /** A rounded surface with a hairline border; children stack vertically. */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color(c, R.color.surface), dp(c, 20).toFloat(), color(c, R.color.outline), dp(c, 1))
        val p = dp(c, 16)
        setPadding(p, p, p, p)
    }

    fun text(c: Context, s: CharSequence, sp: Float = 14f, colorId: Int = R.color.on_surface, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color(c, colorId))
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    /** Icon in a soft accent circle, title and description: the head of a card. */
    fun cardHead(c: Context, icon: Int, title: String, description: String?): LinearLayout {
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val size = dp(c, 40)
        row.addView(ImageView(c).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(c, R.color.on_accent_soft))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(c, R.color.accent_soft)) }
            val p = dp(c, 8); setPadding(p, p, p, p)
        }, LinearLayout.LayoutParams(size, size))
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 14), 0, 0, 0) }
        texts.addView(text(c, title, 17f, bold = true))
        if (description != null) texts.addView(text(c, description, 13f, R.color.muted).apply { setPadding(0, dp(c, 2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** A pill button: filled for the main action of a card, tonal for the others. */
    fun button(c: Context, label: String, filled: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color(c, if (filled) R.color.on_accent else R.color.on_accent_soft))
        val fill = rounded(color(c, if (filled) R.color.accent else R.color.accent_soft), dp(c, 24).toFloat())
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), fill, null)
        minHeight = dp(c, 44)
        // one line, shrunk to fit a narrow button rather than broken
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, TypedValue.COMPLEX_UNIT_SP)
        setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Buttons side by side, sharing the width. */
    fun buttonRow(c: Context, vararg buttons: View) = LinearLayout(c).apply {
        setPadding(0, dp(c, 12), 0, 0)
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(c, 8)
            })
        }
    }

    /** A hairline across a card. */
    fun divider(c: Context) = View(c).apply {
        setBackgroundColor(color(c, R.color.outline))
        minimumHeight = dp(c, 1)
    }

    /** Disabled buttons fade. */
    fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.45f }

    /** Pads [root] off the status bar, navigation bar, cutout and keyboard, plus [side]. */
    fun keepClearOfBars(root: View, side: Int, bottom: Int = side) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left + side, bars.top + side, bars.right + side, bars.bottom + bottom)
            WindowInsets.CONSUMED
        }
    }

    /** One row of a pick list: a title, a line under it, and an optional tag on the right. */
    class Row(val title: String, val subtitle: String, val tag: String? = null)

    /** Rows for AlertDialog.setAdapter, all laid out alike whatever their length. */
    fun rows(c: Context, rows: List<Row>) = object : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val r = rows[p]
            val line = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(c, 64)
                setPadding(dp(c, 24), dp(c, 10), dp(c, 24), dp(c, 10))
            }
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(c, r.title, 16f))
            texts.addView(text(c, r.subtitle, 13f, R.color.muted).apply { setPadding(0, dp(c, 2), 0, 0) })
            line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (r.tag != null) line.addView(text(c, r.tag, 12f, R.color.on_accent_soft, bold = true).apply {
                background = rounded(color(c, R.color.accent_soft), dp(c, 12).toFloat())
                setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(c, 12) })
            return line
        }
    }

    fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = top }
}
