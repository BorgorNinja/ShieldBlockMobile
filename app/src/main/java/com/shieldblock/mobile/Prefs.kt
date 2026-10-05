package com.shieldblock.mobile

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("shield", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var autostart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", true)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    var youtubeAds: Boolean
        get() = sp.getBoolean("youtubeAds", false)
        set(v) = sp.edit().putBoolean("youtubeAds", v).apply()

    var lastUpdate: Long
        get() = sp.getLong("lastUpdate", 0L)
        set(v) = sp.edit().putLong("lastUpdate", v).apply()

    var ruleCount: Int
        get() = sp.getInt("ruleCount", 0)
        set(v) = sp.edit().putInt("ruleCount", v).apply()

    var listCount: Int
        get() = sp.getInt("listCount", 0)
        set(v) = sp.edit().putInt("listCount", v).apply()

    var allowlist: String
        get() = sp.getString("allowlist", "") ?: ""
        set(v) = sp.edit().putString("allowlist", v).apply()

    fun etag(key: String): String? = sp.getString("etag_$key", null)
    fun fetchedAt(key: String): Long = sp.getLong("ts_$key", 0L)
    fun saveFetch(key: String, etag: String?, time: Long) {
        sp.edit().putString("etag_$key", etag).putLong("ts_$key", time).apply()
    }
}
