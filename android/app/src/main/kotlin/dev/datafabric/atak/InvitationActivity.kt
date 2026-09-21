package dev.arachne.atak

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Receives untrusted external links; never admits a member or persists a capability. */
class InvitationActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var link: String? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArachneStyle.initializeActivity(this)
        status = ArachneComponents.notice(this, "Opening invitation…").apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val body = ArachneComponents.stack(this, ArachneStyle.SECTION_GAP,
            ArachneComponents.label(this, "Arachne invitation", ArachneStyle.Type.TITLE), status,
            ArachneComponents.action(this, "Close") { finish() }).apply {
                val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
                setPadding(inset, inset, inset, inset)
                background = ArachneStyle.background(this)
            }
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            background = ArachneStyle.background(this)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
            addView(body)
        })
        val candidate = intent.dataString
        if (intent.action != Intent.ACTION_VIEW || candidate == null ||
            runCatching { WorkspaceInvitation.decode(candidate) }.isFailure) {
            status.text = "This invitation link is invalid. Ask for a new invitation."
            return
        }
        link = candidate
        // Avoid retaining the capability in the Activity's own intent state.
        intent = Intent()
        val launch = packageManager.getLaunchIntentForPackage("com.atakmap.app.civ")
        if (launch == null) {
            status.text = "Install ATAK-CIV and enable Arachne, then open the invitation again."
            link = null
            return
        }
        status.text = "Opening the invitation in ATAK…"
        startActivity(launch)
        deliver()
    }

    private fun deliver() {
        val value = link ?: return
        if (isFinishing || isDestroyed) return
        sendOrderedBroadcast(Intent(ACTION).setPackage("com.atakmap.app.civ")
            .putExtra(Intent.EXTRA_TEXT, value), null, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (resultCode == RESULT_OK) {
                    link = null
                    finish()
                } else if (++attempts < MAX_ATTEMPTS) {
                    // Back off instead of resending the broadcast every
                    // second forever (FUT-30): attempts-1 is 0-based.
                    handler.postDelayed({ deliver() }, JoinRetryBackoff.intervalMs(attempts - 1))
                } else {
                    link = null
                    status.text = "Arachne did not open the invitation. Enable the plugin in ATAK and open the link again."
                    android.widget.Toast.makeText(this@InvitationActivity, status.text, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, handler, RESULT_CANCELED, null, null)
    }

    override fun onDestroy() {
        link = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION = "dev.arachne.atak.OPEN_INVITATION"
        const val PERMISSION = "dev.arachne.atak.permission.OPEN_INVITATION"
        // With backoff (5,10,20,40,60,60,...s) 8 attempts spans ~5 minutes,
        // the same window as the joiner admission retry (FUT-30). The prior
        // 30-attempt/1 s-fixed schedule only spanned 30 s in total.
        private const val MAX_ATTEMPTS = 8
    }
}
