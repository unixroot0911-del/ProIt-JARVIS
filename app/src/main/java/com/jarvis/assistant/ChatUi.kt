package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Chat bubbles shared by the full chat screen and the floating window. */
object ChatUi {

    private fun round(fill: String, stroke: String?, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = radius
            if (stroke != null) setStroke(2, Color.parseColor(stroke))
        }

    /** [role] is "user", "jarvis" or "system" (small grey progress lines). */
    fun bubble(ctx: Context, role: String, text: String, maxWidthPx: Int): View {
        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 15f
        tv.maxWidth = maxWidthPx
        tv.setTextIsSelectable(true)
        when (role) {
            "user" -> {
                tv.setTextColor(Color.WHITE)
                tv.background = round("#1B4F8A", null, 36f)
                tv.setPadding(30, 20, 30, 20)
            }
            "jarvis" -> {
                tv.setTextColor(Color.parseColor("#CFE8FF"))
                tv.background = round("#0E1B2B", "#1F4B6E", 36f)
                tv.setPadding(30, 20, 30, 20)
            }
            else -> {
                tv.setTextColor(Color.parseColor("#6F8CA8"))
                tv.textSize = 12f
                tv.gravity = Gravity.CENTER
            }
        }
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = when (role) {
            "user" -> Gravity.END
            "jarvis" -> Gravity.START
            else -> Gravity.CENTER_HORIZONTAL
        }
        row.setPadding(0, 8, 0, 8)
        row.addView(tv)
        return row
    }

    fun typing(ctx: Context): View = bubble(ctx, "jarvis", "...", 400)
}
