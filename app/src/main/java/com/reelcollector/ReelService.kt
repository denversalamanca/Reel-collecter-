package com.reelcollector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
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
import android.view.View
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
    private var intervalMs = 2000L
    private var maxLinks = 200
    private var added = 0
    private var bad = 0
    private var misses = 0
    private var lastId: String? = null
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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (running && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            stopCollecting("Volume Down se roka")
            return true
        }
        return false
    }

    fun startCollecting(intervalSec: Int, max: Int) {
        intervalMs = intervalSec.coerceAtLeast(1) * 1000L
        maxLinks = max.coerceAtLeast(1)
        added = 0; bad = 0; misses = 0; lastId = null
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

    private fun fail(reason: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("debug", dump()))
        } catch (e: Exception) { }
        stopCollecting("$reason - debug text clipboard me hai")
    }

    // condition sach hone tak ruko (har 250ms check), timeout par no() chalao
    private fun waitFor(timeout: Long, cond: () -> Boolean, ok: () -> Unit, no: () -> Unit) {
        val t0 = System.currentTimeMillis()
        fun poll() {
            if (!running) return
            if (cond()) { ok(); return }
            if (System.currentTimeMillis() - t0 > timeout) { no(); return }
            h.postDelayed({ poll() }, 250)
        }
        poll()
    }

    // ================= ORDER =================
    // Share -> (Copy link dikhe) -> Copy link -> (naya link aaye) -> Back
    // -> (share list band dikhe) -> Scroll -> wait -> Share ...

    // 1) Share dabao
    private fun step() {
        if (!running) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != IG) {
            misses++
            if (misses > 10) { stopCollecting("Instagram screen nahi mili"); return }
            h.postDelayed({ step() }, 1000)
            return
        }
        misses = 0
        // pichhli share list khuli reh gayi ho to pehle Back
        if (find { isCopy(it) } != null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            h.postDelayed({ step() }, 1200)
            return
        }
        val share = find { isShare(it) }
        if (share == null) {
            bad++
            if (bad >= 6) { fail("Share button nahi mila (Reels screen par ho?)"); return }
            h.postDelayed({ step() }, 1200)
            return
        }
        if (!clickUp(share)) tapNode(share)
        // Copy link dikhne tak ruko (dheeme phone par zyada time lagta hai)
        waitFor(6000, { find { isCopy(it) } != null },
            { h.postDelayed({ tryCopy() }, 600) },
            {
                bad++
                if (bad >= 6) fail("Share list khul nahi rahi") else step()
            })
    }

    // 2) Copy link dabao
    private fun tryCopy() {
        if (!running) return
        val copy = find { isCopy(it) }
        if (copy == null) {
            fail("Copy link button nahi mila")
            return
        }
        if (!clickUp(copy)) tapNode(copy)
        h.postDelayed({ afterCopy(0) }, 1000)
    }

    // 3) naya link save karo, phir Back
    private fun afterCopy(attempt: Int) {
        if (!running) return
        readClipboard { text ->
            if (running) {
                val m = if (text != null) idRegex.find(text) else null
                val id = m?.groupValues?.get(1)
                // link nahi mila ya purana hi hai: thoda aur ruk ke dobara padho
                if ((m == null || id == lastId) && attempt < 2) {
                    h.postDelayed({ afterCopy(attempt + 1) }, 900)
                } else {
                    if (m == null || id == null) {
                        bad++
                    } else {
                        val link = "https://www.instagram.com/reel/$id/"
                        if (Store.add(this, link)) { added++; bad = 0 } else bad++
                        lastId = id
                    }
                    setOverlayText("STOP  •  $added links")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    h.postDelayed({ closeAndScroll() }, 300)
                }
            }
        }
    }

    // 4) list band hone tak ruko (zarurat ho to Back dobara), phir scroll
    private fun closeAndScroll() {
        val t0 = System.currentTimeMillis()
        var backs = 1
        fun poll() {
            if (!running) return
            val share = find { isShare(it) } != null
            val copy = find { isCopy(it) } != null
            if (share && !copy) {
                h.postDelayed({ finishReel() }, 500)
                return
            }
            val el = System.currentTimeMillis() - t0
            if (el > 9000) { fail("Share list band nahi hui"); return }
            if (copy && el > backs * 2500L) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                backs++
            }
            h.postDelayed({ poll() }, 300)
        }
        poll()
    }

    // 5) Scroll, phir agli reel ke liye wait
    private fun finishReel() {
        if (!running) return
        if (added >= maxLinks) { stopCollecting("Target pura ho gaya"); return }
        if (bad >= 6) { fail("Links nahi ban rahe ya sab repeat"); return }
        swipe()
        h.postDelayed({ step() }, intervalMs + 600)
    }

    // ---------- clipboard ----------
    private fun clipText(): String? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (e: Exception) { null }

    private fun readClipboard(done: (String?) -> Unit) {
        val t = clipText()
        if (t != null && idRegex.containsMatchIn(t)) { done(t); return }
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { wm.addView(v, lp) } catch (e: Exception) { done(t); return }
        h.postDelayed({
            val t2 = clipText()
            try { wm.removeView(v) } catch (e: Exception) { }
            done(t2 ?: t)
        }, 300)
    }

    // ---------- gestures ----------
    // lamba aur dheema swipe: neeche se upar lagbhag poori screen
    private fun swipe() {
        val w = resources.displayMetrics.widthPixels
        val hh = resources.displayMetrics.heightPixels
        val p = Path()
        p.moveTo(w * 0.5f, hh * 0.85f)
        p.lineTo(w * 0.5f, hh * 0.15f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 450)).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                scrollByAction()
            }
        }
        dispatchGesture(g, cb, null)
    }

    // gesture cancel ho jaye to Android ke scroll command se koshish
    private fun scrollByAction() {
        if (!running) return
        val root = rootInActiveWindow ?: return
        val hh = resources.displayMetrics.heightPixels
        val n = walk(root) { x ->
            val r = Rect()
            x.getBoundsInScreen(r)
            x.isScrollable && r.height() > hh * 0.5
        }
        n?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun tapNode(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val p = Path()
        p.moveTo(r.centerX().toFloat(), r.centerY().toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        dispatchGesture(g, null, null)
    }

    // ---------- screen helpers ----------
    private fun find(f: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != IG) return null
        return walk(root, f)
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

    private fun dump(): String {
        val sb = StringBuilder()
        var count = 0
        fun rec(x: AccessibilityNodeInfo?) {
            if (x == null || count > 80) return
            val t = (x.text ?: "").toString()
            val c = (x.contentDescription ?: "").toString()
            if (t.isNotEmpty() || c.isNotEmpty()) {
                sb.append("t=").append(t.take(30)).append(" | d=").append(c.take(30))
                    .append(" | click=").append(x.isClickable).append("\n")
                count++
            }
            for (i in 0 until x.childCount) rec(x.getChild(i))
        }
        rec(rootInActiveWindow)
        return sb.toString()
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
