package com.shieldblock.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the filter lists that uBlock Origin enables by default.
 * The list catalogue is uBlock's own assets/assets.json, so lists added, removed or moved
 * upstream are picked up automatically.
 */
object Updater {
    private val ASSETS_URLS = listOf(
        "https://raw.githubusercontent.com/gorhill/uBlock/master/assets/assets.json",
        "https://cdn.jsdelivr.net/gh/gorhill/uBlock@master/assets/assets.json",
        "https://ublockorigin.github.io/uAssetsCDN/ublock/assets.json"
    )
    private const val DAY_MS = 86_400_000L

    // Optional community list (off by default). Hostnames of YouTube ad servers; refreshed daily.
    private const val YT_KEY = "youtube-ads"
    private const val YT_URL =
        "https://raw.githubusercontent.com/kboghdady/youTube_ads_4_pi-hole/refs/heads/master/youtubelist.txt"

    class Result(val updated: Int, val unchanged: Int, val failed: Int, val error: String? = null)

    fun listsDir(ctx: Context): File = File(ctx.filesDir, "lists").apply { mkdirs() }

    fun hasLists(ctx: Context): Boolean =
        listsDir(ctx).listFiles { f -> f.name.endsWith(".txt") }?.isNotEmpty() == true

    @Synchronized
    fun update(ctx: Context, force: Boolean): Result {
        val prefs = Prefs(ctx)
        val json = fetchText(ASSETS_URLS) ?: return Result(0, 0, 0, "Could not download uBlock assets.json")
        val root = try { JSONObject(json) } catch (e: Exception) { return Result(0, 0, 0, "Invalid assets.json") }
        val dir = listsDir(ctx)
        val keep = HashSet<String>()
        var updated = 0
        var unchanged = 0
        var failed = 0

        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = root.optJSONObject(key) ?: continue
            if (o.optString("content") != "filters") continue
            if (o.optBoolean("off", false)) continue // lists uBlock ships disabled stay disabled
            val urls = urlsOf(o)
            if (urls.isEmpty()) continue

            val name = key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt"
            keep.add(name)
            val file = File(dir, name)
            val maxAge = (o.optDouble("updateAfter", 2.0) * DAY_MS).toLong()
            val fresh = file.exists() && System.currentTimeMillis() - prefs.fetchedAt(key) < maxAge
            if (fresh && !force) { unchanged++; continue }

            val (state, etag) = download(urls, file, prefs.etag(key))
            when (state) {
                1 -> { updated++; prefs.saveFetch(key, etag, System.currentTimeMillis()) }
                0 -> { unchanged++; prefs.saveFetch(key, etag, System.currentTimeMillis()) }
                else -> failed++
            }
        }

        if (prefs.youtubeAds) {
            val file = File(dir, "$YT_KEY.txt")
            keep.add(file.name)
            val fresh = file.exists() && System.currentTimeMillis() - prefs.fetchedAt(YT_KEY) < DAY_MS
            if (fresh && !force) {
                unchanged++
            } else {
                val (state, etag) = download(listOf(YT_URL), file, prefs.etag(YT_KEY))
                when (state) {
                    1 -> { updated++; prefs.saveFetch(YT_KEY, etag, System.currentTimeMillis()) }
                    0 -> { unchanged++; prefs.saveFetch(YT_KEY, etag, System.currentTimeMillis()) }
                    else -> failed++
                }
            }
        }

        if (keep.isNotEmpty()) {
            dir.listFiles()?.forEach { if (it.name.endsWith(".txt") && it.name !in keep) it.delete() }
            prefs.listCount = keep.size
        }
        if (updated > 0 || failed == 0) prefs.lastUpdate = System.currentTimeMillis()
        return Result(updated, unchanged, failed)
    }

    private fun urlsOf(o: JSONObject): List<String> {
        val out = ArrayList<String>()
        for (field in arrayOf("contentURL", "cdnURLs")) {
            when (val v = o.opt(field)) {
                is String -> if (v.startsWith("https://")) out.add(v)
                is JSONArray -> for (i in 0 until v.length()) {
                    val s = v.optString(i)
                    if (s.startsWith("https://")) out.add(s)
                }
            }
        }
        return out
    }

    /** Returns (1 = downloaded, 0 = not modified, -1 = failed) and the new ETag. */
    private fun download(urls: List<String>, dest: File, etag: String?): Pair<Int, String?> {
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "ShieldBlockMobile/1.0")
                if (etag != null && dest.exists()) c.setRequestProperty("If-None-Match", etag)
                val code = c.responseCode
                if (code == 304) { c.disconnect(); return Pair(0, etag) }
                if (code != 200) { c.disconnect(); continue }
                val tmp = File(dest.path + ".tmp")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val newEtag = c.getHeaderField("ETag")
                c.disconnect()
                if (tmp.length() < 64 || looksLikeHtml(tmp)) { tmp.delete(); continue }
                dest.delete()
                if (!tmp.renameTo(dest)) { tmp.delete(); continue }
                return Pair(1, newEtag)
            } catch (_: Exception) {
            }
        }
        return Pair(-1, null)
    }

    private fun looksLikeHtml(f: File): Boolean {
        val head = f.inputStream().use { i ->
            val b = ByteArray(256)
            String(b, 0, maxOf(0, i.read(b)), Charsets.UTF_8)
        }.trimStart().lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html")
    }

    private fun fetchText(urls: List<String>): String? {
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "ShieldBlockMobile/1.0")
                if (c.responseCode == 200) {
                    val t = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    return t
                }
                c.disconnect()
            } catch (_: Exception) {
            }
        }
        return null
    }
}

object Lists {
    /** Parses all downloaded lists plus the user's allowlist into a fresh engine. */
    fun load(ctx: Context): FilterEngine {
        val prefs = Prefs(ctx)
        val b = FilterBuilder()
        Updater.listsDir(ctx).listFiles()?.filter { it.name.endsWith(".txt") }?.forEach { f ->
            f.bufferedReader().use { b.addList(it) }
        }
        prefs.allowlist.lineSequence().forEach { b.allow(it) }
        if (prefs.youtubeAds) b.allow("s.youtube.com") // blocking it breaks YouTube playback
        val e = b.build()
        prefs.ruleCount = e.blockCount
        return e
    }
}
