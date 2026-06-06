# ✅ תאימות Google Play — גרסת `play`

הפרויקט בנוי בשני flavors:
- **`sideload`** — הפצה דרך GitHub (כולל עדכון-עצמי). מה שמשתמשים כיום.
- **`play`** — תואם מדיניות Google Play.

בנייה ל-Play:
```bash
./gradlew assemblePlayRelease     # APK
./gradlew bundlePlayRelease       # AAB (הפורמט להעלאה ל-Play Console)
```

## מה כבר טופל בקוד (flavor `play`)
| חסם Play | הטיפול |
|---|---|
| **עדכון-עצמי** (אסור) | הוסר: ההרשאה `REQUEST_INSTALL_PACKAGES` וכל מנגנון ה-`ApkUpdater`/בדיקת-עדכון מגודרים ב-`BuildConfig.PLAY_STORE`. העדכונים דרך החנות. |
| **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** (מוגבל) | ההרשאה הוסרה; כפתור הסוללה פותח את מסך ההגדרות (מותר). |
| **`ACCESS_BACKGROUND_LOCATION`** (דורש סקירה) | הוסר. סינון קרבה יעבוד **בחזית בלבד**. |
| **`CALL_PHONE`** | הוסר; "דיווח על מעצר" משתמש ב-`ACTION_DIAL` (מחייגן מוכן, ללא הרשאה). |
| `QUERY_ALL_PACKAGES` | לא בשימוש (יש `<queries>` ממוקד). ✓ |
| targetSdk 34 | ✓ |

## מה נותר — שלבי Play Console / מדיניות (לא קוד)
1. **מדיניות פרטיות** — חובה. השתמש ב-[PRIVACY.md](PRIVACY.md), ארח ב-URL ציבורי, והכנס ב-Play Console.
2. **טופס Data safety** — הצהר: מזהה התקנה אנונימי (אנליטיקה), מיקום (אופציונלי) שנשלח לשירות
   ניתוב (OpenStreetMap/Valhalla) לחישוב זמני הגעה. אין שיתוף עם צד ג' לפרסום.
3. **Foreground Service (`dataSync`)** — Play דורש הצדקה ל-FGS מתמשך. שתי אפשרויות:
   - להצדיק כשירות התראות-חירום, **או** (מומלץ ל-Play) לעבור ל-**FCM בלבד** בגרסת ה-Play
     ולוותר על ה-polling המתמשך. (תשתית ה-FCM כבר קיימת — `FcmService`/`PushManager`.)
4. **`USE_FULL_SCREEN_INTENT`** (Android 14) — להצהיר ב-Console שהאפליקציה היא התראה/אזעקה.
   מותר לאפליקציות התראה; ייתכן שתידרש הצהרה.
5. **חתימה** — Play App Signing (מפתח העלאה יכול להיות המפתח הקיים או חדש).
6. **אייקון/צילומי מסך/תיאור** — נכסי חנות.

## הערה על מיקום ופרטיות
חישוב זמני ההגעה שולח את הקואורדינטות של המשתמש והאירוע ל-`valhalla1.openstreetmap.de`
(שירות ניתוב חיצוני). יש לתעד זאת ב-Data safety ובמדיניות הפרטיות. אפשר גם לארח Valhalla
עצמי כדי שהמיקום לא יעבור לצד ג'.

## המלצה
לגרסת Play הראשונה: לשקול **FCM בלבד** (ללא polling-FGS) + ויתור על מיקום-רקע — כך עוברים
את רוב הסקירות בקלות. גרסת ה-`sideload` (טלפונים כשרים) נשארת עם כל היכולות.
