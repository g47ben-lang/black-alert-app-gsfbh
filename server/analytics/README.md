# 📊 ספירת התקנות ומכשירים פעילים

האפליקציה שולחת **heartbeat אנונימי** פעם ביום (מזהה התקנה אקראי + גרסה + SDK) ל-endpoint
שאתה שולט בו. אין PII, אין מזהה חומרה, אין תלות ב-Google Play — עובד גם על טלפונים כשרים.

## אופציה A — Google Sheet (ללא שרת, מומלץ להתחלה)

1. צור גיליון חדש ב-[sheets.google.com](https://sheets.google.com).
2. **Extensions → Apps Script**, הדבק את [`Code.gs`](Code.gs), שמור.
3. **Deploy → New deployment → Web app**:
   - *Execute as:* **Me**
   - *Who has access:* **Anyone**
   - Deploy → אשר הרשאות → העתק את כתובת ה-**Web app URL**.
4. הדבק את הכתובת ב-`app/src/main/java/com/blackalert/app/net/Heartbeat.kt` בקבוע `ENDPOINT`,
   ובנה גרסה חדשה. (לבדיקה מהירה אפשר גם להזין אותה בהגדרות → מתקדם → analyticsUrl, בגרסת הבדיקה.)

**מה רואים:** גיליון `devices` = שורה לכל התקנה ייחודית (firstSeen/lastSeen/version).
גיליון `Summary` = **סך התקנות** ו**פעילים ב-7/30 ימים** (חי).

## אופציה B — backend עצמי
כל endpoint שמקבל `POST application/json` בגוף:
```json
{ "id": "uuid", "v": "1.3.2", "vc": 6, "sdk": 33, "pkg": "com.black.alert" }
```
שמור upsert לפי `id` עם `lastSeen`. סך התקנות = ids ייחודיים; פעילים = lastSeen בטווח.

## פרטיות
- מזהה אקראי (UUID) שנוצר במכשיר — לא ניתן לשייך לאדם/מספר/חומרה.
- נשלח אחת ליום בלבד. ללא מיקום, ללא תוכן.
- כבוי כברירת מחדל (`ENDPOINT` ריק) עד שמגדירים כתובת.
