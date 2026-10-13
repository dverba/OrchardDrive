package com.aaosmusic.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.aaosmusic.R

/**
 * Shared building blocks and the Orchard Drive palette for the parked-mode screens. Sunset is the
 * only color in the UI (toggles, primary buttons, status dots); everything else is Night, Dusk,
 * Asphalt and Ink.
 */
internal object SettingsUi {
    const val NIGHT = 0xFF0A0B0F.toInt()
    const val DUSK = 0xFF161B2E.toInt()
    const val ASPHALT = 0xFF2D303C.toInt()
    const val SUNSET = 0xFFFF5A45.toInt()
    const val INK = 0xFFF4F1EC.toInt()
    const val INK_SECONDARY = 0xB8F4F1EC.toInt()
    const val INK_MUTED = 0x99F4F1EC.toInt()
    const val INK_OUTLINE = 0x4DF4F1EC.toInt()

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** DM Serif Display: the wordmark and big headings only. */
    fun serif(context: Context): Typeface = context.resources.getFont(R.font.dm_serif_display)

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
        strokeColor?.let { setStroke(context.dp(2), it) }
    }

    /** Header with a back arrow (the car may have no system back button) above a scrolling [content] column. */
    fun screen(activity: Activity, title: String, content: LinearLayout): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(NIGHT)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(8), activity.dp(8), activity.dp(24), activity.dp(8))
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                imageTintList = ColorStateList.valueOf(INK)
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
                textSize = 34f
                setTextColor(INK)
                typeface = serif(activity)
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
        textSize = 16f
        letterSpacing = 0.1f
        setTextColor(INK_MUTED)
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
            addView(TextView(context).apply { text = title; textSize = 22f; setTextColor(INK) })
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
        textSize = 17f
        setTextColor(INK_SECONDARY)
        setPadding(0, context.dp(4), 0, 0)
    }

    /** A labelled switch row with a Sunset track. */
    fun switch(context: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(context).apply {
        text = label
        textSize = 22f
        setTextColor(INK)
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(INK, 0xFFB9B6B0.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(SUNSET, ASPHALT))
        minimumHeight = context.dp(76)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** Pill button: [filled] is Sunset with Night text (the primary action), otherwise an Ink outline. */
    // A TextView, not a Button: the platform button style measures its label short at low densities,
    // clipping the last letter.
    fun pillButton(context: Context, filled: Boolean, heightDp: Int = 56) = TextView(context).apply {
        textSize = 17f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setTextColor(if (filled) NIGHT else INK)
        setTypeface(typeface, if (filled) Typeface.BOLD else Typeface.NORMAL)
        minHeight = context.dp(heightDp)
        minimumHeight = context.dp(heightDp)
        minWidth = context.dp(140)
        setPadding(context.dp(28), 0, context.dp(28), 0)
        stateListAnimator = null
        val shape = rounded(context, if (filled) SUNSET else Color.TRANSPARENT, heightDp / 2, if (filled) null else INK_OUTLINE)
        background = RippleDrawable(ColorStateList.valueOf(0x33F4F1EC), shape, null)
    }
}
