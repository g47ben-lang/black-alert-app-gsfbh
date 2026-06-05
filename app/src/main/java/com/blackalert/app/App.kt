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
        // הפעלת השירות בעליית האפליקציה אם המשתמש לא כיבה אותו ידנית
        if (prefs.serviceEnabled) {
            PollingService.start(this)
        }
    }
}
