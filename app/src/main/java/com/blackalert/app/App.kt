package com.blackalert.app

import android.app.Application
import com.blackalert.app.data.Prefs
import com.blackalert.app.service.PollingService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        // מקור הנתונים (ישיר / proxy לעקיפת סינון) — נטען לפני הפעלת השירות
        com.blackalert.app.net.BlackAlertApi.base = prefs.sourceBaseUrl
        // failover: הפעלת push אם המכשיר תומך (Play Services + Firebase מוגדר)
        com.blackalert.app.service.PushManager.applyDelivery(this)
        // ספירת התקנות/פעילים (אנונימי, פעם ביום; כבוי אם אין endpoint)
        com.blackalert.app.net.Heartbeat.maybeSend(this)
        // הפעלת השירות בעליית האפליקציה אם המשתמש לא כיבה אותו ידנית
        if (prefs.serviceEnabled) {
            PollingService.start(this)
        }
    }
}
