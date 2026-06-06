package com.blackalert.app.net

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * עדכון מתוך היישום: הורדת ה-APK מ-GitHub והפעלת מתקין המערכת.
 * התקנה דורשת אישור משתמש אחד (התקנה שקטה לחלוטין דורשת הרשאות מערכת/MDM).
 */
object ApkUpdater {

    /** מוריד את ה-APK לקובץ מקומי. onProgress(percent 0..100, -1 אם הגודל לא ידוע). מחזיר את הקובץ או null. */
    fun download(context: Context, url: String, onProgress: (Int) -> Unit): File? {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        if (out.exists()) out.delete()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "BlackAlertApp")
            }
            if (conn.responseCode != 200) return null
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { fout ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        fout.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt()) else onProgress(-1)
                    }
                    fout.flush()
                }
            }
            out
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** האם מותר להתקין APK-ים (Android 8+: הרשאת "מקורות לא ידועים" לאפליקציה). */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
        else true

    /** מפנה את המשתמש לאשר התקנה ממקור זה. */
    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
        }
    }

    /** מפעיל את מתקין המערכת על הקובץ שהורד (דרך FileProvider). */
    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
