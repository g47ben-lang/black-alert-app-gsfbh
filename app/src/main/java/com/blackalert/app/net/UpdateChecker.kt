package com.blackalert.app.net

import com.blackalert.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * בדיקת עדכון מ-GitHub Releases. משווה את הגרסה המותקנת (BuildConfig.VERSION_NAME)
 * ל-tag האחרון ברפו, ומחזיר פרטי עדכון אם קיימת גרסה חדשה יותר.
 */
object UpdateChecker {
    // הרפו הציבורי של הפרויקט
    const val OWNER = "613avi"
    const val REPO = "black-alert-app"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val TIMEOUT_MS = 10000

    data class Update(val tag: String, val name: String, val notes: String, val pageUrl: String, val apkUrl: String?)

    /** מחזיר Update אם יש גרסה חדשה יותר מהמותקנת, אחרת null. */
    fun checkForUpdate(): Update? {
        val json = httpGet(API) ?: return null
        val o = JSONObject(json)
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val tag = o.optString("tag_name", "").ifBlank { return null }
        if (!isNewer(tag, BuildConfig.VERSION_NAME)) return null

        // איתור נכס ה-APK
        var apkUrl: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", true)) {
                    apkUrl = a.optString("browser_download_url"); break
                }
            }
        }
        return Update(
            tag = tag,
            name = o.optString("name", tag),
            notes = o.optString("body", ""),
            pageUrl = o.optString("html_url", "https://github.com/$OWNER/$REPO/releases/latest"),
            apkUrl = apkUrl
        )
    }

    /** השוואת semver: "v1.2.0" מול "1.1.3" → true אם remote > local. */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val r = parse(remoteTag); val l = parse(localVersion)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }; val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BlackAlertApp")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }
}
