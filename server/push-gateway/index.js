/*
  Push Gateway — "צבע שחור"
  מקורות: שרת התראות ראשי + שרת בדיקות טכנאים
  הערה: ההיסטוריה (alerts-history) נסרקת ישירות באפליקציה - לא כאן
*/
const mqtt = require("mqtt");
const http = require('http');

const SOURCE_URLS = [
  "https://black-alert.com/notifications",
  "https://script.google.com/macros/s/AKfycbxTdA_sh1UZYYpU0DxU0OJZIUEnc5aCb_myPRryT822CKxT2uvDfAMQASzYFPKI5aSEHw/exec"
];

const POLL_MS  = parseInt(process.env.POLL_MS || "5000", 10);
const FCM_TOPIC = process.env.FCM_TOPIC || "alerts";
const MQTT_URL  = process.env.MQTT_URL  || "";
const MQTT_TOPIC = process.env.MQTT_TOPIC || "alerts";

// --- Firebase FCM ---
let messaging = null;
try {
  if (process.env.FIREBASE_KEY) {
    const admin = require("firebase-admin");
    const serviceAccount = JSON.parse(process.env.FIREBASE_KEY);
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    messaging = admin.messaging();
    console.log("[gateway] FCM מחובר בהצלחה!");
  } else {
    console.warn("[gateway] FCM כבוי - חסר FIREBASE_KEY");
  }
} catch (e) {
  console.warn("[gateway] שגיאה בחיבור Firebase:", e.message);
}

// --- MQTT (אופציונלי - למכשירים בלי Google) ---
let mqttClient = null;
if (MQTT_URL) {
  mqttClient = mqtt.connect(MQTT_URL, { reconnectPeriod: 2000 });
  mqttClient.on("connect", () => console.log("[gateway] MQTT מחובר"));
  mqttClient.on("error",   (e) => console.warn("[gateway] MQTT שגיאה:", e.message));
}

// זיכרון - מה כבר נשלח (מונע כפילויות)
const seen = new Set();
function dedupKey(ev) {
  return `${ev.notificationId}:${ev.version || 1}`;
}

// סריקת כל המקורות
async function fetchAllEvents() {
  let allEvents = [];
  for (const url of SOURCE_URLS) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(10000) });
      if (r.ok) {
        const j = await r.json();
        if (Array.isArray(j)) allEvents = allEvents.concat(j);
      }
    } catch (e) {
      console.warn(`[gateway] שגיאה בסריקת ${url}:`, e.message);
    }
  }
  return allEvents;
}

// המרה לפורמט שה-FCM מצפה לו (הכל מחרוזות)
function toFcmData(ev) {
  return {
    notificationId: String(ev.notificationId || Date.now()),
    cities:         JSON.stringify(Array.isArray(ev.cities) ? ev.cities : []),
    eventType:      String(ev.eventType ?? 8),
    time:           String(ev.time ?? Math.floor(Date.now() / 1000)),
    version:        String(ev.version ?? 1),
    note:           String(ev.note    ?? ""),
    address:        String(ev.address ?? ""),
    silent:         String(ev.silent  ?? false),
    ...(ev.status   ? { status:   String(ev.status)  } : {}),
    ...(ev.expireAt ? { expireAt: String(ev.expireAt) } : {}),
    ...(typeof ev.lat === "number" ? { lat: String(ev.lat) } : {}),
    ...(typeof ev.lng === "number" ? { lng: String(ev.lng) } : {}),
  };
}

// שליחה לכל הערוצים
async function fanout(ev) {
  const data = toFcmData(ev);

  if (messaging) {
    try {
      await messaging.send({
        topic:   FCM_TOPIC,
        data,
        android: { priority: "high" }
      });
    } catch (e) {
      console.warn("[fcm] שליחה נכשלה:", e.message);
    }
  }

  if (mqttClient?.connected) {
    mqttClient.publish(MQTT_TOPIC, JSON.stringify(ev), { qos: 1 });
  }
}

// הלולאה הראשית
async function tick() {
  const events = await fetchAllEvents();
  for (const ev of events) {
    if (!ev.notificationId) continue;

    const k = dedupKey(ev);
    if (seen.has(k)) continue;

    seen.add(k);
    if (seen.size > 1000) {
      seen.delete(seen.values().next().value);
    }

    await fanout(ev);
    console.log(`[fanout] נשלח: ${k} | ערים: ${ev.cities} | הערה: ${ev.note || "-"}`);
  }
}

// שרת Keep-Alive (נדרש ע"י Render)
http.createServer((req, res) => {
  res.writeHead(200);
  res.end("Black Alert Gateway is alive!");
}).listen(process.env.PORT || 3000, () => {
  console.log(`[http] שרת Keep-Alive פועל על פורט ${process.env.PORT || 3000}`);
});

// הפעלה
console.log(`[gateway] מתחיל | מקורות: ${SOURCE_URLS.length} | סריקה כל ${POLL_MS}ms`);
setInterval(tick, POLL_MS);
tick();
