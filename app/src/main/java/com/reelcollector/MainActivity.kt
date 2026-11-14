package com.reelcollector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var intervalE: EditText
    private lateinit var maxE: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(40, 80, 40, 40)
        sv.addView(l)

        fun label(t: String) {
            val x = TextView(this); x.text = t; x.textSize = 15f
            x.setPadding(0, 24, 0, 4); l.addView(x)
        }
        fun btn(t: String, a: () -> Unit) {
            val x = Button(this); x.text = t
            x.setOnClickListener { a() }; l.addView(x)
        }

        val title = TextView(this)
        title.text = "Reel Link Collector"; title.textSize = 22f
        l.addView(title)

        status = TextView(this); status.textSize = 16f
        status.setPadding(0, 20, 0, 20); l.addView(status)

        btn("1) Accessibility settings kholo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        label("Har reel ke beech kitne second rukna hai (2 ya zyada)")
        intervalE = EditText(this)
        intervalE.inputType = InputType.TYPE_CLASS_NUMBER
        intervalE.setText("3"); l.addView(intervalE)

        label("Kitne links collect karne hain (itne par khud ruk jayega)")
        maxE = EditText(this)
        maxE.inputType = InputType.TYPE_CLASS_NUMBER
        maxE.setText("200"); l.addView(maxE)

        btn("2) START (Instagram khulega)") { start() }
        btn("Saare links copy karo") { copyAll() }
        btn("Links share / save karo") { shareAll() }
        btn("List saaf karo") {
            Store.clear(this); refresh()
        }

        val tip = TextView(this)
        tip.text = "\nTip: Start dabane ke baad Instagram me Reels tab kholo. " +
                "6 second baad khud chalega. Rokne ke liye screen ka STOP button " +
                "ya Volume Down dabao."
        tip.textSize = 14f; l.addView(tip)

        setContentView(sv)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return s != null && s.contains(packageName)
    }

    private fun refresh() {
        status.text = "Service: " + (if (serviceOn()) "ON ✅" else "OFF ❌ (pehle step 1)") +
                "\nSave hue links: " + Store.all(this).size
    }

    private fun start() {
        val inst = ReelService.instance
        if (inst == null) {
            Toast.makeText(this, "Pehle Accessibility me service ON karo", Toast.LENGTH_LONG).show()
            return
        }
        val sec = intervalE.text.toString().toIntOrNull() ?: 3
        val max = maxE.text.toString().toIntOrNull() ?: 200
        inst.startCollecting(sec, max)
        val i = packageManager.getLaunchIntentForPackage(ReelService.IG)
        if (i == null) Toast.makeText(this, "Instagram app nahi mila", Toast.LENGTH_LONG).show()
        else startActivity(i)
    }

    private fun copyAll() {
        val t = Store.all(this).joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("links", t))
        Toast.makeText(this, "${Store.all(this).size} links copy ho gaye", Toast.LENGTH_SHORT).show()
    }

    private fun shareAll() {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, Store.all(this).joinToString("\n"))
        startActivity(Intent.createChooser(i, "Links bhejo"))
    }
}
