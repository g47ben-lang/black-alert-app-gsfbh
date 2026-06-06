# 📡 מערכת ה-Push — צבע שחור

push דו-ערוצי עם failover, חסכוני בסוללה ומיידי:

```
מקור אירועים ──► Push Gateway ──┬──► FCM   ──► מכשירים עם Google Play   (חסכוני + מיידי)
(/notifications)   (dedup,       └──► MQTT  ──► מכשירים ללא Google Play  (חיבור מתמשך, עדין בסוללה)
                    fan-out)
                     אפליקציה: כל ערוץ → אותו AlertProcessor → צלצול/מפה/ניווט
                     + safety-poll נדיר כרשת ביטחון (מניעת כשל)
```

> **backend-agnostic:** ה-gateway קורא היום מ-`/notifications`. כשתחליטו על backend משלכם,
> מחליפים רק את `fetchEvents()` ב-`push-gateway/index.js` — שאר הצינור והאפליקציה זהים.

## למה זה חסכוני ומיידי
- **FCM** (Play): מערכת ההפעלה מחזיקה ערוץ אחד משותף לכל האפליקציות — אנחנו לא מחזיקים כלום.
  data message ב-`priority:high` מגיע מיידית. ~אפס סוללה.
- **MQTT** (ללא Play): חיבור TCP מתמשך יחיד שמוחזק ע"י ה-Foreground Service, עם `keepAlive=180s`.
  ההודעה מיידית כל עוד החיבור פתוח; ה-keepalive רק מזהה ניתוק. session מתמשך + QoS 1 →
  הודעה שנשלחה בניתוק קצר נמסרת בחיבור מחדש. הרבה יותר חסכוני מ-polling תכוף.

## הרצה מקומית (בדיקה)
```bash
cd server
cp push-gateway/.env.example push-gateway/.env     # ערכו לפי הצורך
docker compose up --build
```
זה מרים **Mosquitto** (broker) + **gateway**. בדיקה מהירה שה-MQTT עובד:
```bash
# האזנה
docker exec -it server-mosquitto-1 mosquitto_sub -t alerts
# פרסום אירוע בדיקה ידני
docker exec -it server-mosquitto-1 mosquitto_pub -t alerts \
  -m '{"notificationId":"test-1","cities":["בני ברק"],"eventType":3,"time":1780000000,"lat":32.0874,"lng":34.8324,"note":"בדיקה"}'
```
באפליקציה (גרסת בדיקה) → הגדרות → **מתקדם — MQTT** → broker `tcp://<IP>:1883`, topic `alerts`.
(USB: `adb reverse tcp:1883 tcp:1883` ואז `tcp://127.0.0.1:1883`.)

## פרודקשן (ענן)
1. **Broker מנוהל** — HiveMQ Cloud / EMQX Cloud / Mosquitto על VPS. **חובה TLS (8883)**, אימות,
   ו-**ACL**: מכשירי קצה רק *מאזינים* ל-`alerts`; רק ה-gateway *מפרסם*.
2. **FCM** — פרויקט Firebase, service-account JSON ל-gateway, ומילוי `firebase_config.xml` באפליקציה.
3. **Gateway** — Cloud Run / Render / קונטיינר. משתני סביבה לפי `.env.example`.
4. **whitelist** — לוודא מול ספקי הסינון שה-host של ה-broker וה-FCM מאושרים.

## הגדרת האפליקציה
| ערוץ | מתי נבחר | הגדרה |
|---|---|---|
| FCM | יש Play Services + `firebase_config.xml` מלא | אוטומטי |
| MQTT | אין Play אך broker מוגדר | הגדרות → מתקדם — MQTT |
| Polling | אין אף ערוץ push | ברירת מחדל (גם safety-net) |

`PushManager.effectiveMode()` בוחר אוטומטית: FCM → MQTT → Polling.
