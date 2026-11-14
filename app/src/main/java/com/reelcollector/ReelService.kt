package com.reelcollector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast

class ReelService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ReelService? = null
        const val IG = "com.instagram.android"
    }

    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var intervalMs = 3000L
    private var maxLinks = 200
    private var added = 0
    private var bad = 0
    private var misses = 0
    private var overlay: Button? = null

    private val idRegex =
        Regex("instagram\\.com/(?:[A-Za-z0-9_.]+/)?(?:reels?|p)/([A-Za-z0-9_-]+)")

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopCollecting("Service band ho gayi")
        instance = null
        return super.onUnbind(intent)
    }

    // Volume Down dabao = turant stop
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (running && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            stopCollecting("Volume Down se roka")
            return true
        }
        return false
    }

    fun isRunning() = running

    fun startCollecting(intervalSec: Int, max: Int) {
        intervalMs = (intervalSec.coerceAtLeast(2)) * 1000L
        maxLinks = max.coerceAtLeast(1)
        added = 0; bad = 0; misses = 0
        running = true
        showOverlay()
        setOverlayText("STOP (6 sec me shuru)")
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ step() }, 6000)
    }

    fun stopCollecting(reason: String) {
        if (running) Toast.makeText(this, "Ruka: $reason ($added naye links)", Toast.LENGTH_LONG).show()
        running = false
        h.removeCallbacksAndMessages(null)
        hideOverlay()
    }

    // ---------- main loop ----------
    private fun step() {
        if (!running) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG) {
            misses++
            if (misses > 10) { stopCollecting("Instagram screen nahi mili"); return }
            h.postDelayed({ step() }, 1500)
            return
        }
        misses = 0
        val share = walk(root) { isShare(it) }
        if (share == null) {          // shayad reel nahi hai; bas scroll karo
            bad++
            if (bad >= 8) { stopCollecting("Share button nahi mila"); return }
            swipe()
            h.postDelayed({ step() }, intervalMs)
            return
        }
        clickUp(share)
        h.postDelayed({ afterShare() }, 1200)
    }

    private fun afterShare() {
        if (!running) return
        val root = rootInActiveWindow
        val copy = walk(root) { isCopy(it) }
        if (copy == null) {
            performGlobalAction(GLOBAL_ACTION_BACK)   // share sheet band karo
            bad++
        } else {
            clickUp(copy)
        }
        h.postDelayed({ afterCopy() }, 700)
    }

    private fun afterCopy() {
        if (!running) return
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: Exception) { null }

        val m = if (text != null) idRegex.find(text) else null
        if (m == null) {
            bad++
        } else {
            val link = "https://www.instagram.com/reel/" + m.groupValues[1] + "/"
            if (Store.add(this, link)) { added++; bad = 0 } else bad++
        }
        setOverlayText("STOP  •  $added links")

        if (added >= maxLinks) { stopCollecting("Target pura ho gaya"); return }
        if (bad >= 8) { stopCollecting("Links nahi aa rahe / sab repeat"); return }
        swipe()
        h.postDelayed({ step() }, intervalMs)
    }

    // ---------- helpers ----------
    private fun swipe() {
        val w = resources.displayMetrics.widthPixels
        val hh = resources.displayMetrics.heightPixels
        val p = Path()
        p.moveTo(w * 0.5f, hh * 0.75f)
        p.lineTo(w * 0.5f, hh * 0.25f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        dispatchGesture(g, null, null)
    }

    private fun walk(n: AccessibilityNodeInfo?, f: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (f(n)) return n
        for (i in 0 until n.childCount) {
            val r = walk(n.getChild(i), f)
            if (r != null) return r
        }
        return null
    }

    private fun clickUp(n: AccessibilityNodeInfo?): Boolean {
        var x = n
        var d = 0
        while (x != null && d < 6) {
            if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent
            d++
        }
        return false
    }

    private fun isShare(n: AccessibilityNodeInfo): Boolean {
        val d = (n.contentDescription ?: "").toString().lowercase()
        if (d.isEmpty()) return false
        val ok = d == "send post" || d == "share" || d == "send" ||
                d.startsWith("send ") || d.startsWith("share") ||
                d.contains("भेजें") || d.contains("शेयर")
        if (!ok) return false
        val r = Rect()
        n.getBoundsInScreen(r)
        return r.centerX() > resources.displayMetrics.widthPixels / 2
    }

    private fun isCopy(n: AccessibilityNodeInfo): Boolean {
        val t = ((n.text ?: "").toString() + " " + (n.contentDescription ?: "").toString()).lowercase()
        if (t.isBlank()) return false
        return t.contains("copy link") || t.contains("link copy") ||
                (t.contains("लिंक") && t.contains("कॉपी"))
    }

    // ---------- floating STOP button ----------
    private fun showOverlay() {
        if (overlay != null) return
        val b = Button(this)
        b.setOnClickListener { stopCollecting("Stop dabaya") }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.y = 220
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(b, lp)
            overlay = b
        } catch (e: Exception) { overlay = null }
    }

    private fun setOverlayText(t: String) { overlay?.text = t }

    private fun hideOverlay() {
        val b = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(b)
        } catch (e: Exception) { }
        overlay = null
    }
}
