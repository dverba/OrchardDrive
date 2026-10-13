package com.aaosmusic.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aaosmusic.BuildConfig
import com.aaosmusic.R
import com.aaosmusic.providers.apple.AppleMusicTokenStore
import com.aaosmusic.providers.apple.AppleSignInRelay
import com.aaosmusic.providers.apple.AppleSignInRelay.PollResult
import com.aaosmusic.ui.SettingsUi.ASPHALT
import com.aaosmusic.ui.SettingsUi.DUSK
import com.aaosmusic.ui.SettingsUi.INK
import com.aaosmusic.ui.SettingsUi.INK_SECONDARY
import com.aaosmusic.ui.SettingsUi.NIGHT
import com.aaosmusic.ui.SettingsUi.SUNSET
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
 * media service refresh AAOS). Signed in, it offers sign-out. [stacked] puts the QR code above the
 * text, for portrait screens; [compact] shrinks it for narrow landscape ones.
 */
class AppleAccountView(
    context: Context,
    private val tokens: AppleMusicTokenStore,
    private val scope: CoroutineScope,
    stacked: Boolean = false,
    private val compact: Boolean = false,
) : LinearLayout(context) {

    private val relay = AppleSignInRelay(BuildConfig.SIGNIN_RELAY_URL, BuildConfig.SIGNIN_PAGE_URL)
    private val dp = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val qrSize = px(if (compact) 200 else 260)

    // QR column: the code on an Ink tile (the quiet zone scanners need), a countdown bar and status.
    private val qr = ImageView(context).apply { layoutParams = LayoutParams(qrSize, qrSize) }
    private val countdown = View(context).apply { background = SettingsUi.rounded(context, SUNSET, 3) }
    private val countdownTrack = LinearLayout(context).apply {
        background = SettingsUi.rounded(context, ASPHALT, 3)
        weightSum = 1f
        addView(countdown, LayoutParams(0, px(6)))
    }
    private val statusText = TextView(context).apply { textSize = 15f; setTextColor(INK_SECONDARY); gravity = Gravity.CENTER }
    private val qrColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(LinearLayout(context).apply {
            setPadding(px(16), px(16), px(16), px(16))
            background = SettingsUi.rounded(context, INK, 22)
            addView(qr)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(countdownTrack, LayoutParams(qrSize + px(32), px(6)).apply { topMargin = px(18) })
        addView(statusText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(18) })
    }

    private val connectedIcon = ImageView(context).apply { setImageResource(R.drawable.ic_check_circle) }
    private val dot = View(context).apply { background = SettingsUi.rounded(context, SUNSET, 5) }
    private val heading = TextView(context).apply { setTextColor(INK) }
    private val headingRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LayoutParams(px(10), px(10)).apply { marginEnd = px(12) })
        addView(heading)
    }
    private val body = TextView(context).apply { textSize = 17f; maxWidth = px(560); setTextColor(INK_SECONDARY); setPadding(0, px(8), 0, 0) }
    private val steps = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, px(22), 0, 0)
        listOf("Scan the code", "Sign in on phone", "Music appears").forEachIndexed { i, label ->
            addView(step(i + 1, label), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(10) })
        }
    }
    private val altLink = TextView(context).apply {
        textSize = 15f
        setTextColor(INK_SECONDARY)
        text = "No camera? Visit ${BuildConfig.SIGNIN_PAGE_URL.removePrefix("https://").trimEnd('/')} and enter"
        setPadding(0, px(22), 0, px(10))
    }
    private val code = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val primary = SettingsUi.pillButton(context, filled = true)
    private val secondary = SettingsUi.pillButton(context, filled = false)
    private val buttons = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, px(22), 0, 0)
        addView(primary, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = px(12) })
        addView(secondary)
    }

    private val text = LinearLayout(context).apply {
        orientation = VERTICAL
        listOf(headingRow, body, steps, altLink, code, buttons).forEach { v ->
            addView(v, LayoutParams(if (v == steps) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private var job: Job? = null
    private val onTokenChanged: () -> Unit = { post { if (!isWaiting()) render() } }

    init {
        orientation = if (stacked) VERTICAL else HORIZONTAL
        gravity = if (stacked) Gravity.CENTER else Gravity.CENTER_VERTICAL
        if (compact) setPadding(px(32), px(28), px(32), px(28)) else setPadding(px(48), px(36), px(48), px(36))
        addView(qrColumn)
        addView(connectedIcon, LayoutParams(px(54), px(54)).apply { marginEnd = px(24) })
        addView(text, if (stacked) {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(32) }
        } else {
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(if (compact) 32 else 48) }
        })
    }

    /** One visual state of the card; everything not mentioned is hidden. */
    private fun show(
        title: String,
        message: String? = null,
        qrCode: Boolean = false,
        connected: Boolean = false,
        error: Boolean = false,
        statusLine: String? = null,
        primaryAction: Pair<String, () -> Unit>? = null,
        secondaryAction: Pair<String, () -> Unit>? = null,
    ) {
        heading.text = title
        // The QR step gets the big serif heading; the short status cards a bold sans one.
        if (qrCode) {
            heading.textSize = if (compact) 32f else 40f
            heading.typeface = SettingsUi.serif(context)
        } else {
            heading.textSize = 24f
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        background = SettingsUi.rounded(context, DUSK, if (qrCode) 28 else 18, if (error) 0x73FF5A45 else null)
        dot.visibility = if (error) VISIBLE else GONE
        body.text = message
        body.visibility = if (message == null) GONE else VISIBLE
        qrColumn.visibility = if (qrCode || statusLine != null) VISIBLE else GONE
        (qr.parent as View).visibility = if (qrCode) VISIBLE else GONE
        countdownTrack.visibility = if (qrCode) VISIBLE else GONE
        connectedIcon.visibility = if (connected) VISIBLE else GONE
        listOf(steps, altLink, code).forEach { it.visibility = if (qrCode) VISIBLE else GONE }
        statusText.visibility = if (statusLine == null) GONE else VISIBLE
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
            val rejected = tokens.userTokenRejected
            val title = if (rejected) "Sign in again" else "Connect Apple Music"
            val pitch = (if (rejected) "Your Apple Music sign-in has expired. " else "") +
                "Scan with your phone and sign in with your Apple ID. Your library, playlists and recommendations then show up in Media."
            try {
                show(title, statusLine = "Getting a sign-in code…")

                val session = relay.start(tokens.developerToken)
                qr.setImageBitmap(qrBitmap(relay.pageUrlFor(session), qrSize, dark = NIGHT, light = INK))
                showCode(session.displayCode)
                val total = Duration.between(Instant.now(), session.expiresAt).toMillis().coerceAtLeast(1)
                val newCode = "Get a new code" to ::startSignIn

                var nextPoll = 0L
                while (true) {
                    val left = Duration.between(Instant.now(), session.expiresAt)
                    if (left.isNegative) break
                    show(if (rejected) title else "Bring your library along", pitch, qrCode = true, secondaryAction = newCode,
                        statusLine = "Waiting for your phone · %d:%02d left".format(left.toMinutes(), left.seconds % 60))
                    (countdown.layoutParams as LayoutParams).weight = left.toMillis().toFloat() / total
                    countdown.requestLayout()
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
                show("Sign in again", "That code expired before it was used.", primaryAction = "Get a new code" to ::startSignIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(AUTH, "QR sign-in failed", e)
                show(title, "Couldn't reach the sign-in service. Check the car's internet connection.", error = true,
                    primaryAction = "Try again" to ::startSignIn)
            }
        }
    }

    /** The typed code as Ink letter tiles; the dash between groups becomes a short rule. */
    private fun showCode(displayCode: String) {
        code.removeAllViews()
        displayCode.forEach { c ->
            if (c == '-') {
                code.addView(View(context).apply { setBackgroundColor(0x66F4F1EC) },
                    LayoutParams(px(14), px(3)).apply { marginStart = px(4); marginEnd = px(12) })
            } else {
                code.addView(TextView(context).apply {
                    text = c.toString()
                    textSize = if (compact) 22f else 28f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(NIGHT)
                    background = SettingsUi.rounded(context, INK, 10)
                }, LayoutParams(px(if (compact) 34 else 44), px(if (compact) 46 else 58)).apply { marginEnd = px(if (compact) 6 else 8) })
            }
        }
    }

    private fun step(n: Int, label: String) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(px(16), px(14), px(16), px(14))
        background = SettingsUi.rounded(context, ASPHALT, 14)
        addView(TextView(context).apply {
            text = "%02d".format(n)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(SUNSET)
        })
        addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(INK); setPadding(0, px(6), 0, 0) })
    }

    private fun TextView.bind(action: Pair<String, () -> Unit>?) {
        visibility = if (action == null) GONE else VISIBLE
        action?.let { (label, onClick) -> text = label; setOnClickListener { onClick() } }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
