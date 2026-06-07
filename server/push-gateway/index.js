/*
  Push Gateway — "צבע שחור"
  משודרג: תמיכה במספר מקורות במקביל (שרת ראשי + שרת בדיקות).
*/
const mqtt = require("mqtt");
const http = require('http');

// כאן המקורות המתוקנים והנקיים
const SOURCE_URLS = [
  "https://black-alert.com/notifications",
  "https://script.google.com/macros/s/AKfycbxTdA_sh1UZYYpU0DxU0OJZIUEnc5aCb_myPRryT822CKxT2uvDfAMQASzYFPKI5aSEHw/exec"
];

const POLL_MS = parseInt(process.env.POLL_MS || "5000", 10); // העליתי ל-5 שניות כדי למנוע חסימות
const FCM_TOPIC = process.env.FCM_TOPIC || "alerts";
const MQTT_URL = process.env.MQTT_URL || "";
const MQTT_TOPIC = process.env.MQTT_TOPIC || "alerts";

// --- FCM ---
let messaging = null;
try {
  if (process.env.FIREBASE_KEY) {
    const admin = require("firebase-admin");
    const serviceAccount = JSON.parse(process.env.FIREBASE_KEY);
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    messaging = admin.messaging();
    console.log("[gateway] FCM enabled!");
  }
} catch (e) { console.warn("[gateway] שגיאה בפיירבייס:", e.message); }

// --- MQTT ---
let mqttClient = null;
if (MQTT_URL) {
  mqttClient = mqtt.connect(MQTT_URL, { reconnectPeriod: 2000 });
}

const seen = new Set();
function key(ev) { return ev.notificationId + ":" + (ev.version || 1); }

async function fetchEvents() {
  let allEvents = [];
  for (const url of SOURCE_URLS) {
    try {
      // הוספתי הגדרות מתקדמות ל-fetch למנוע Timeout
      const r = await fetch(url, { signal: AbortSignal.timeout(10000) });
      if (r.ok) {
        const j = await r.json();
        allEvents = allEvents.concat(Array.isArray(j) ? j : []);
      }
    } catch (e) {
      console.warn(`[gateway] שגיאה בסריקת ${url}: ${e.message}`);
    }
  }
  return allEvents;
}

function toData(ev) {
  const d = {
    notificationId: String(ev.notificationId || Date.now()),
    cities: JSON.stringify(Array.isArray(ev.cities) ? ev.cities : []),
    eventType: String(ev.eventType ?? 8),
    time: String(ev.time ?? Math.floor(Date.now() / 1000)),
    version: String(ev.version ?? 1),
    note: String(ev.note ?? ""),
    address: String(ev.address ?? ""),
    silent: String(ev.silent ?? false)
  };
  return d;
}

async function fanout(ev) {
  const data = toData(ev);
  if (messaging) {
    try { await messaging.send({ topic: FCM_TOPIC, data, android: { priority: "high" } }); }
    catch (e) { console.warn("[fcm] send failed:", e.message); }
  }
  if (mqttClient && mqttClient.connected) {
    mqttClient.publish(MQTT_TOPIC, JSON.stringify(ev), { qos: 1 });
  }
}

async function tick() {
  try {
    const events = await fetchEvents();
    for (const ev of events) {
      if (!ev.notificationId) continue;
      const k = key(ev);
      if (seen.has(k)) continue;
      seen.add(k);
      if (seen.size > 1000) seen.delete(seen.values().next().value);
      await fanout(ev);
      console.log(`[fanout] נשלחה התראה: ${ev.note || "אין טקסט"}`);
    }
  } catch (e) { console.warn("[tick] שגיאה:", e.message); }
}

// שרת Keep-Alive
http.createServer((req, res) => res.end('Black Alert Server is Alive!')).listen(process.env.PORT || 3000);

// הפעלה
console.log(`[gateway] מתחיל סריקה...`);
setInterval(tick, POLL_MS);
tick();
