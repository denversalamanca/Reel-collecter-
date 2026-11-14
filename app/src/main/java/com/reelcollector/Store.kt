package com.reelcollector

import android.content.Context

object Store {
    private const val P = "reel_links"
    private const val K = "list"

    fun all(c: Context): List<String> =
        (c.getSharedPreferences(P, 0).getString(K, "") ?: "")
            .split("\n").filter { it.isNotBlank() }

    /** true = naya link add hua, false = pehle se tha */
    fun add(c: Context, link: String): Boolean {
        val l = all(c)
        if (l.contains(link)) return false
        c.getSharedPreferences(P, 0).edit()
            .putString(K, (l + link).joinToString("\n")).apply()
        return true
    }

    fun clear(c: Context) {
        c.getSharedPreferences(P, 0).edit().putString(K, "").apply()
    }
}
