package com.aaosmusic.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aaosmusic.R

/** Shared building blocks for the parked-mode screens: back-arrow header, section titles, rows. */
internal object SettingsUi {

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Header with a back arrow (the car may have no system back button) above a scrolling [content] column. */
    fun screen(activity: Activity, title: String, content: LinearLayout): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(8), activity.dp(8), activity.dp(24), activity.dp(8))
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                contentDescription = "Back"
                background = TypedValue().let { tv ->
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                    activity.getDrawable(tv.resourceId)
                }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(activity.dp(18), activity.dp(18), activity.dp(18), activity.dp(18))
                layoutParams = LinearLayout.LayoutParams(activity.dp(76), activity.dp(76))
                setOnClickListener { activity.finish() }
            })
            addView(TextView(activity).apply {
                text = title
                textSize = 32f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(activity.dp(8), 0, 0, 0)
            })
        })
        addView(ScrollView(activity).apply {
            content.orientation = LinearLayout.VERTICAL
            content.setPadding(activity.dp(48), 0, activity.dp(48), activity.dp(32))
            addView(content)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun section(context: Context, title: String) = TextView(context).apply {
        text = title.uppercase()
        textSize = 18f
        letterSpacing = 0.08f
        setTextColor(Color.parseColor("#FA2D48"))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, context.dp(32), 0, context.dp(8))
    }

    /** A tappable (or plain, if [onClick] is null) two-line row, at least car touch-target height. */
    fun row(context: Context, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(76)
            setPadding(0, context.dp(12), 0, context.dp(12))
            addView(TextView(context).apply { text = title; textSize = 24f; setTextColor(Color.WHITE) })
            subtitle?.let { addView(subtitleText(context, it)) }
            onClick?.let { click ->
                background = TypedValue().let { tv ->
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    context.getDrawable(tv.resourceId)
                }
                setOnClickListener { click() }
            }
        }

    fun subtitleText(context: Context, text: String) = TextView(context).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.parseColor("#B3FFFFFF"))
        setPadding(0, context.dp(4), 0, 0)
    }
}
