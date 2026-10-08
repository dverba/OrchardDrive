package com.aaosmusic.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aaosmusic.BuildConfig
import com.aaosmusic.R
import com.aaosmusic.providers.apple.AppleMusicTokenStore
import com.aaosmusic.providers.apple.AppleSignInRelay
import com.aaosmusic.providers.apple.AppleSignInRelay.PollResult
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.AUTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Parked-mode Apple Music account card: shows a QR code that opens the phone sign-in page,
 * polls the relay until the phone hands over a Music User Token, then stores it (which makes the
 * media service refresh AAOS). Signed in, it offers sign-out.
 */
class AppleAccountView(
    context: Context,
    private val tokens: AppleMusicTokenStore,
    private val scope: CoroutineScope,
) : LinearLayout(context) {

    private val relay = AppleSignInRelay(BuildConfig.SIGNIN_RELAY_URL, BuildConfig.SIGNIN_PAGE_URL)
    private val dp = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val qrSize = px(240)

    // QR on a white tile: the quiet zone scanners need, and it reads as a deliberate element.
    private val qr = ImageView(context).apply { layoutParams = LayoutParams(qrSize, qrSize) }
    private val qrTile = LinearLayout(context).apply {
        setPadding(px(16), px(16), px(16), px(16))
        background = rounded(Color.WHITE, 20)
        addView(qr)
    }
    private val connectedIcon = ImageView(context).apply {
        setImageResource(R.drawable.ic_check_circle)
        layoutParams = LayoutParams(px(72), px(72))
    }
    private val heading = TextView(context).apply { textSize = 30f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
    private val body = TextView(context).apply { textSize = 20f; setTextColor(SECONDARY); setPadding(0, px(8), 0, 0) }
    private val steps = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, px(12), 0, 0)
        listOf("Scan the code with your phone's camera", "Sign in with your Apple ID", "Your music appears here automatically")
            .forEachIndexed { i, label -> addView(step(i + 1, label)) }
    }
    private val altLink = TextView(context).apply {
        textSize = 18f
        setTextColor(SECONDARY)
        text = "No camera? Go to ${BuildConfig.SIGNIN_PAGE_URL.removePrefix("https://").trimEnd('/')} and enter:"
        setPadding(0, px(20), 0, px(8))
    }
    private val code = TextView(context).apply {
        textSize = 34f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
        setTextColor(Color.WHITE)
        setPadding(px(20), px(8), px(20), px(8))
        background = rounded(PILL, 12)
    }
    private val statusText = TextView(context).apply { textSize = 18f; setTextColor(SECONDARY) }
    private val status = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(16), 0, 0)
        addView(statusText)
    }
    private val primary = pillButton(filled = true)
    private val secondary = pillButton(filled = false)
    private val buttons = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, px(20), 0, 0)
        addView(primary)
        addView(secondary, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = px(12) })
    }

    private val text = LinearLayout(context).apply {
        orientation = VERTICAL
        listOf(heading, body, steps, altLink, code, status, buttons).forEach { v ->
            addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private var job: Job? = null
    private val onTokenChanged: () -> Unit = { post { if (!isWaiting()) render() } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(32), px(28), px(32), px(28))
        background = rounded(CARD, 24)
        addView(qrTile)
        addView(connectedIcon)
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(36) })
    }

    /** One visual state of the card; everything not mentioned is hidden. */
    private fun show(
        title: String,
        message: String? = null,
        qrCode: Boolean = false,
        connected: Boolean = false,
        statusLine: String? = null,
        primaryAction: Pair<String, () -> Unit>? = null,
        secondaryAction: Pair<String, () -> Unit>? = null,
    ) {
        heading.text = title
        body.text = message
        body.visibility = if (message == null) GONE else VISIBLE
        qrTile.visibility = if (qrCode) VISIBLE else GONE
        connectedIcon.visibility = if (connected) VISIBLE else GONE
        listOf(steps, altLink, code).forEach { it.visibility = if (qrCode) VISIBLE else GONE }
        status.visibility = if (statusLine == null) GONE else VISIBLE
        statusText.text = statusLine
        primary.bind(primaryAction)
        secondary.bind(secondaryAction)
        buttons.visibility = if (primaryAction == null && secondaryAction == null) GONE else VISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tokens.addListener(onTokenChanged)
        render()
        // Opened from AAOS's "Sign in" prompt or the settings gear: get straight to the QR code.
        if (!tokens.signedIn) startSignIn()
    }

    override fun onDetachedFromWindow() {
        tokens.removeListener(onTokenChanged)
        job?.cancel()
        super.onDetachedFromWindow()
    }

    private fun isWaiting() = job?.isActive == true

    private fun render() {
        if (tokens.signedIn) {
            show(
                "Connected to Apple Music",
                "Your library, playlists and recommendations are in Media.",
                connected = true,
                secondaryAction = "Sign out" to { tokens.userToken = null; startSignIn() },
            )
        } else if (!isWaiting()) {
            show(
                "Connect Apple Music",
                "Sign in with your phone to play your Apple Music library in the car.",
                primaryAction = "Sign in" to ::startSignIn,
            )
        }
    }

    private fun startSignIn() {
        job?.cancel()
        job = scope.launch {
            val title = if (tokens.userTokenRejected) "Sign in again" else "Connect Apple Music"
            val reason = if (tokens.userTokenRejected) "Your Apple Music sign-in has expired." else null
            try {
                show(title, reason, statusLine = "Getting a sign-in code…")

                val session = relay.start(tokens.developerToken)
                qr.setImageBitmap(qrBitmap(relay.pageUrlFor(session), qrSize))
                code.text = session.displayCode
                val newCode = "Get a new code" to ::startSignIn

                var nextPoll = 0L
                while (true) {
                    val left = Duration.between(Instant.now(), session.expiresAt)
                    if (left.isNegative) break
                    show(title, reason, qrCode = true, secondaryAction = newCode,
                        statusLine = "Waiting for your phone · code expires in %d:%02d".format(left.toMinutes(), left.seconds % 60))
                    if (System.currentTimeMillis() >= nextPoll) {
                        nextPoll = System.currentTimeMillis() + POLL_INTERVAL_MS
                        when (val r = runCatching { relay.poll(session) }.getOrElse { e ->
                            if (e is CancellationException) throw e
                            AppLog.w(AUTH, "Sign-in poll failed: ${e.message}")
                            PollResult.Pending // transient network error; keep trying until expiry
                        }) {
                            is PollResult.Complete -> {
                                AppLog.i(AUTH, "QR sign-in complete")
                                tokens.userToken = r.musicUserToken
                                job = null
                                render()
                                return@launch
                            }
                            PollResult.Expired -> break
                            PollResult.Pending -> Unit
                        }
                    }
                    delay(1_000)
                }
                show(title, "That code expired before it was used.", primaryAction = "Get a new code" to ::startSignIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(AUTH, "QR sign-in failed", e)
                show(title, "Couldn't reach the sign-in service. Check the car's internet connection.",
                    primaryAction = "Try again" to ::startSignIn)
            }
        }
    }

    private fun step(n: Int, label: String) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(6), 0, px(6))
        addView(TextView(context).apply {
            text = n.toString()
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(ACCENT, 14)
            layoutParams = LayoutParams(px(28), px(28)).apply { marginEnd = px(14) }
        })
        addView(TextView(context).apply { text = label; textSize = 21f; setTextColor(Color.WHITE) })
    }

    private fun pillButton(filled: Boolean) = Button(context).apply {
        textSize = 20f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = px(64)
        minWidth = px(160)
        setPadding(px(32), 0, px(32), 0)
        stateListAnimator = null
        val shape = rounded(if (filled) ACCENT else Color.TRANSPARENT, 32).apply {
            if (!filled) setStroke(px(2), OUTLINE)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    private fun Button.bind(action: Pair<String, () -> Unit>?) {
        visibility = if (action == null) GONE else VISIBLE
        action?.let { (label, onClick) -> text = label; setOnClickListener { onClick() } }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * dp
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val ACCENT = 0xFFFA2D48.toInt()
        const val CARD = 0xFF1C1C1E.toInt()
        const val PILL = 0xFF2C2C2E.toInt()
        const val OUTLINE = 0xFF5C5C60.toInt()
        const val SECONDARY = 0xB3FFFFFF.toInt()
    }
}
