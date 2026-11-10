package net.k1lllagt.andraxng.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny helpers so every screen is built in code (no XML layouts to keep in sync). */
object Ui {
    class Page(val scroll: ScrollView, val col: LinearLayout)

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun page(ctx: Context): Page {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 16)
        col.setPadding(p, p, p, p)
        val sv = ScrollView(ctx)
        sv.isFillViewport = true
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Page(sv, col)
    }

    fun title(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = 24f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, 0, 0, dp(ctx, 8))
        return t
    }

    fun heading(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = 17f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
        return t
    }

    fun body(ctx: Context, text: String): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = 15f
        t.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        return t
    }

    fun mono(ctx: Context, text: String): TextView {
        val t = body(ctx, text)
        t.typeface = Typeface.MONOSPACE
        t.textSize = 12.5f
        t.setTextIsSelectable(true)
        return t
    }

    fun button(ctx: Context, text: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(ctx, 6)
        b.layoutParams = lp
        return b
    }

    fun gap(ctx: Context, dp: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, dp))
        return v
    }
}
