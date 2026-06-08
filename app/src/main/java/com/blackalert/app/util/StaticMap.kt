package com.blackalert.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * בונה תמונת-מפה סטטית (PNG) סביב נקודה, מתוך אריחי OpenStreetMap — לשיבוץ בתוך באנר ההתראה
 * (BigPictureStyle), שם אי-אפשר להריץ WebView. best-effort: נכשל בשקט (מחזיר null) אם אין רשת
 * או שהאריחים חסומים, ואז ההתראה נופלת חזרה לטקסט בלבד.
 *
 * מרכיב פסיפס 3x3 אריחים (768x768) סביב האריח המכיל את הנקודה, מצייר סמן עיגול פועם במרכז,
 * ומחזיר את התוצאה. צריכה להיקרא מ-thread ברקע.
 */
object StaticMap {

    private const val TILE = 256
    private const val GRID = 3                 // 3x3 אריחים
    private const val ZOOM = 15
    private const val UA = "BlackAlertApp/1.0 (Android; rocket-alert)"

    /** @return ביטמאפ המפה עם סמן, או null אם נכשל. type קובע את צבע הסמן. */
    fun build(lat: Double, lng: Double, eventType: Int): Bitmap? = runCatching {
        val n = 2.0.pow(ZOOM)
        val xf = (lng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val yf = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val cx = xf.toInt(); val cy = yf.toInt()
        val half = GRID / 2

        val size = TILE * GRID
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF17181D.toInt())   // רקע כהה לאריחים שלא נטענו

        // הורדת האריחים במקביל (תקרת timeout קצרה — best-effort)
        val pool = Executors.newFixedThreadPool(GRID * GRID)
        val tiles = arrayOfNulls<Bitmap>(GRID * GRID)
        for (dy in 0 until GRID) for (dx in 0 until GRID) {
            val idx = dy * GRID + dx
            val tx = cx - half + dx
            val ty = cy - half + dy
            pool.execute { tiles[idx] = fetchTile(ZOOM, tx, ty) }
        }
        pool.shutdown()
        pool.awaitTermination(6, TimeUnit.SECONDS)

        var loaded = 0
        for (dy in 0 until GRID) for (dx in 0 until GRID) {
            tiles[dy * GRID + dx]?.let {
                canvas.drawBitmap(it, (dx * TILE).toFloat(), (dy * TILE).toFloat(), null)
                loaded++
            }
        }
        if (loaded == 0) return null   // שום אריח לא נטען — אין מפה להציג

        // מיקום הנקודה: ההיסט בתוך האריח המרכזי, ועוד אריח אחד (half) של שוליים.
        val px = ((xf - cx) + half) * TILE
        val py = ((yf - cy) + half) * TILE
        drawMarker(canvas, px.toFloat(), py.toFloat(), markerColor(eventType))

        out
    }.getOrNull()

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? = runCatching {
        val max = 1 shl z
        if (x < 0 || y < 0 || x >= max || y >= max) return null
        val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", UA)
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, color: Int) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 60; style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 34f, ring)              // הילה
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 16f, outline)           // טבעת לבנה
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, 12f, dot)               // נקודת מרכז
    }

    private fun markerColor(type: Int): Int = when (type) {
        3 -> 0xFFFF8000.toInt()        // נסיון הסגרה
        0, 2 -> 0xFFFFD500.toInt()     // מעצר מ.צ / מחסומים
        else -> 0xFFD32F2F.toInt()     // כללי — אדום
    }

    // מסומן לשימוש עתידי (פענוח y→lat); נשמר לשלמות הנוסחה.
    @Suppress("unused")
    private fun tile2lat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / 2.0.pow(z)
        return Math.toDegrees(atan(sinh(n)))
    }
}
