package com.aaosmusic.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aaosmusic.BuildConfig
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
 * Parked-mode Apple Music account panel: shows a QR code that opens the phone sign-in page,
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
    private val qrSize = (300 * dp).toInt()

    private val qr = ImageView(context).apply { layoutParams = LayoutParams(qrSize, qrSize) }
    private val heading = TextView(context).apply { textSize = 30f; setTypeface(typeface, Typeface.BOLD) }
    private val body = TextView(context).apply { textSize = 20f; setPadding(0, (8 * dp).toInt(), 0, 0) }
    private val code = TextView(context).apply {
        textSize = 40f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.1f
        setPadding(0, (16 * dp).toInt(), 0, 0)
    }
    private val status = TextView(context).apply { textSize = 18f; setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt()) }
    private val primary = Button(context).apply { textSize = 20f }

    private var job: Job? = null
    private val onTokenChanged: () -> Unit = { post { if (!isWaiting()) render() } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
        addView(qr)
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding((32 * dp).toInt(), 0, 0, 0)
            listOf(heading, body, code, status, primary).forEach(::addView)
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tokens.addListener(onTokenChanged)
        render()
        // Opened from AAOS's "Sign in" prompt or the settings gear: get straight to the QR code.
        if (tokens.userToken == null) startSignIn()
    }

    override fun onDetachedFromWindow() {
        tokens.removeListener(onTokenChanged)
        job?.cancel()
        super.onDetachedFromWindow()
    }

    private fun isWaiting() = job?.isActive == true

    private fun render() {
        if (tokens.userToken != null) {
            qr.visibility = View.GONE
            code.visibility = View.GONE
            heading.text = "Apple Music connected"
            body.text = "Your library, playlists and recommendations are available in Media."
            status.text = ""
            primary.text = "Sign out"
            primary.setOnClickListener { tokens.userToken = null; startSignIn() }
        } else if (!isWaiting()) {
            qr.visibility = View.GONE
            code.visibility = View.GONE
            heading.text = "Connect Apple Music"
            body.text = "Sign in with your phone to play your Apple Music library in the car."
            primary.text = "Sign in"
            primary.setOnClickListener { startSignIn() }
        }
    }

    private fun startSignIn() {
        job?.cancel()
        job = scope.launch {
            try {
                heading.text = "Connect Apple Music"
                body.text = "Scan with your phone's camera and sign in with your Apple ID. " +
                    "Or open ${BuildConfig.SIGNIN_PAGE_URL.removePrefix("https://")} and enter the code."
                status.text = "Getting a code…"
                primary.text = "New code"
                primary.setOnClickListener { startSignIn() }

                val session = relay.start(tokens.developerToken)
                qr.setImageBitmap(qrBitmap(relay.pageUrlFor(session), qrSize))
                qr.visibility = View.VISIBLE
                code.text = session.displayCode
                code.visibility = View.VISIBLE

                var nextPoll = 0L
                while (true) {
                    val left = Duration.between(Instant.now(), session.expiresAt)
                    if (left.isNegative) break
                    status.text = "Waiting for your phone… code expires in %d:%02d".format(left.toMinutes(), left.seconds % 60)
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
                qr.visibility = View.GONE
                code.visibility = View.GONE
                status.text = "That code expired."
                primary.text = "Get a new code"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(AUTH, "QR sign-in failed", e)
                qr.visibility = View.GONE
                code.visibility = View.GONE
                status.text = "Couldn't reach the sign-in service. Check the car's internet connection."
                primary.text = "Try again"
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
