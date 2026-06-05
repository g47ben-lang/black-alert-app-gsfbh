package com.blackalert.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blackalert.app.service.NavTarget

/**
 * פותח ניווט ליעד דרך *בורר אפליקציות המערכת* (geo: intent) —
 * Waze / Google Maps / OsmAnd / כל אפליקציית ניווט שמותקנת תופיע בבורר.
 * ללא תלות ב-Google Play Services.
 */
object NavigationLauncher {

    /**
     * ניווט ליעד — **Waze כברירת מחדל**. אם Waze לא מותקן, נפילה לבורר המערכת (geo:).
     * זו נקודת הכניסה היחידה שהאפליקציה משתמשת בה.
     */
    fun launch(context: Context, target: NavTarget) {
        try {
            context.startActivity(buildWaze(target))
        } catch (_: Exception) {
            try {
                context.startActivity(buildChooser(target))
            } catch (_: Exception) { }
        }
    }

    /** Intent ניווט לבורר המערכת. geo:lat,lng?q=lat,lng(label) — נתמך ע"י כל אפליקציות הניווט. */
    fun buildChooser(target: NavTarget): Intent {
        val label = Uri.encode(target.label.ifBlank { "יעד" })
        val lat = target.lat
        val lng = target.lng
        // q=lat,lng(label) נועץ סיכה עם שם; geo:lat,lng ממקד את המפה
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(view, "ניווט באמצעות").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Intent ישיר ל-Waze (אם המשתמש בוחר זאת); נופל חזרה ל-geo: דרך resolve. */
    fun buildWaze(target: NavTarget): Intent {
        val uri = Uri.parse("https://waze.com/ul?ll=${target.lat},${target.lng}&navigate=yes")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.waze")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
