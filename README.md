# צבע שחור — Black Alert (Android)

אפליקציית אנדרואיד להתראות בזמן אמת ממערכת **"צבע שחור"** (black-alert.com). כשמגיעה
התראה — קופץ **חלון מסך-מלא שמצלצל ומדליק את המסך גם כשהוא נעול**, ומציע **ניווט למיקום**
דרך אפליקציות הניווט המותקנות במכשיר (Waze / גוגל מפות / כל אפליקציה התומכת ב-`geo:`).

נבנתה מהבנת הלוגיקה של תוסף הדפדפן הרשמי, עם התאמות לנייד.

## יכולות

- 🔴 **חלון התראה מלא שמצלצל** — מוצג מעל המסך הנעול, צליל לולאתי + רטט, עם עצירה אוטומטית.
- 🧭 **ניווט בלחיצה** — בורר אפליקציות הניווט של המערכת, עם מיקום מדויק (`lat/lng`) מהאירוע.
- 📡 **רקע אמין** — שירות Foreground עם polling (כל ~10ש', jitter + backoff + watchdog),
  שורד הריגת מערכת ומופעל מחדש אחרי אתחול/עדכון. ללא תלות ב-Google Play Services / FCM.
- 🔎 **סינון** לפי ערים/אזורים, סוגי אירוע, שעות שקטות, ומצב "התראה לפי קרבה" (GPS).
- 📜 **היסטוריה מקומית** — תיעוד append-only של **כל גרסה** של אירוע (כולל עריכות וסגירות
  שהשרת דורס), עם מסך שמציג את שרשרת העריכות.
- 🎯 **מקור נתונים מתצורה** — לעקיפת סינון תוכן במכשירים מנוהלים (הפניה ל-proxy/Cloud Function).

## ארכיטקטורה

```
PollingService (Foreground)
   └─ BlackAlertApi  ──GET──▶  black-alert.com/notifications   (פיד ציבורי, ללא אימות)
   └─ AlertProcessor ── dedup(notificationId:version) · סינון · TTL · closed
        ├─▶ NotificationHelper ─ full-screen intent + צליל ─▶ AlertActivity (צלצול + ניווט)
        └─▶ HistoryStore (JSONL append-only — שומר עריכות)
```

מקור הנתונים מתבסס על הפיד הציבורי הקיים בלבד (`/notifications`, `/alerts-history`,
`/lists-versions`, `/static/cities.json`). אין שימוש בנקודות קצה מאומתות.

## בנייה

```bash
./gradlew assembleDebug      # APK לבדיקה
./gradlew assembleRelease    # release (לא חתום — ראה Releases ל-APK חתום)
```

דרישות: JDK 17, Android SDK (platform 34, build-tools 34+).

## התקנה

הורד את ה-APK החתום מ-[Releases](../../releases), ואז:
```bash
adb install black-alert-vX.Y.Z.apk
```
מומלץ לאשר את הרשאת ההתראות ולבטל אופטימיזציית סוללה (כפתור באפליקציה) לאמינות רקע.

## רישיון

MIT — ראה [LICENSE](LICENSE).

## כתב ויתור

פרויקט קהילתי עצמאי הצורך פיד ציבורי. אינו מסונף רשמית למפעילי "צבע שחור".
