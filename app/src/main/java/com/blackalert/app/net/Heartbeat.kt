package com.blackalert.app.net

import android.content.Context
import android.os.Build
import com.blackalert.app.BuildConfig
import com.blackalert.app.data.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ספירת התקנות ומכשירים פעילים — heartbeat אנונימי.
 *
 * שולח פעם ביום מזהה התקנה *אקראי* (ללא PII, ללא מזהה חומרה), גרסה ו-SDK, ל-endpoint שאתה
 * שולט בו (Google Apps Script → Sheet, או backend עצמי). שם רואים: סך התקנות = מזהים ייחודיים;
 * פעילים = מי ש-lastSeen בטווח האחרון. עובד על טלפונים כשרים (HTTPS פשוט, ללא Google Play).
 *
 * הגדרה: הצב את כתובת ה-Apps Script ב-ENDPOINT (ראה server/analytics/README.md). ריק = כבוי.
 */
object Heartbeat {
    // ⬇️ הדבק כאן את כתובת ה-Web App של Apps Script (או backend). ריק = ספירה כבויה.
    private const val ENDPOINT = ""
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun maybeSend(context: Context) {
        val prefs = Prefs(context)
        val url = prefs.analyticsUrl.ifEmpty { ENDPOINT }
        if (url.isBlank()) return
        if (System.currentTimeMillis() - prefs.lastHeartbeatMs < DAY_MS) return
        prefs.lastHeartbeatMs = System.currentTimeMillis() // מונע ריבוי שליחות גם אם נכשל
        thread { runCatching { post(url, prefs.installId) } }
    }

    private fun post(url: String, installId: String) {
        val body = JSONObject().apply {
            put("id", installId)
            put("v", BuildConfig.VERSION_NAME)
            put("vc", BuildConfig.VERSION_CODE)
            put("sdk", Build.VERSION.SDK_INT)
            put("pkg", BuildConfig.APPLICATION_ID)
        }.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000; readTimeout = 12000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "BlackAlertApp")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode // טריגר לשליחה
        } finally {
            conn.disconnect()
        }
    }
}
