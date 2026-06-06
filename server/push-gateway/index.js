/*
  Push Gateway — "צבע שחור"

  קורא אירועים ממקור (כרגע GET /notifications; בעתיד אפשר להחליף ב-DB/queue),
  ומפזר כל אירוע *חדש או שהשתנה* לשני ערוצים:
    • FCM  — למכשירים עם Google Play (data-only, high priority)
    • MQTT — למכשירים ללא Google Play (חיבור מתמשך)

  backend-agnostic: שינוי מקור = שינוי fetchEvents() בלבד. שאר הצינור זהה.
  הגדרה דרך משתני סביבה (ראו .env.example).
*/
const mqtt = require("mqtt");

const SOURCE_URL = process.env.SOURCE_URL || "https://black-alert.com";
const POLL_MS = parseInt(process.env.POLL_MS || "5000", 10);
const FCM_TOPIC = process.env.FCM_TOPIC || "alerts";
const MQTT_URL = process.env.MQTT_URL || "";
const MQTT_TOPIC = process.env.MQTT_TOPIC || "alerts";

// --- FCM (אופציונלי — רק אם יש service account) ---
let messaging = null;
try {
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS || process.env.FIREBASE_CONFIG) {
    const admin = require("firebase-admin");
    admin.initializeApp();
    messaging = admin.messaging();
    console.log("[gateway] FCM enabled");
  } else {
    console.log("[gateway] FCM disabled (no credentials)");
  }
} catch (e) {
  console.warn("[gateway] FCM init failed:", e.message);
}

// --- MQTT (אופציונלי) ---
let mqttClient = null;
if (MQTT_URL) {
  mqttClient = mqtt.connect(MQTT_URL, {
    username: process.env.MQTT_USERNAME || undefined,
    password: process.env.MQTT_PASSWORD || undefined,
    reconnectPeriod: 2000,
  });
  mqttClient.on("connect", () => console.log("[gateway] MQTT connected:", MQTT_URL));
  mqttClient.on("error", (e) => console.warn("[gateway] MQTT error:", e.message));
} else {
  console.log("[gateway] MQTT disabled (no MQTT_URL)");
}

const seen = new Set();
function key(ev) { return ev.notificationId + ":" + (ev.version || 1); }

async function fetchEvents() {
  const r = await fetch(`${SOURCE_URL}/notifications`, { signal: AbortSignal.timeout(8000) });
  if (!r.ok) throw new Error("HTTP " + r.status);
  const j = await r.json();
  return Array.isArray(j) ? j : [];
}

function toData(ev) {
  // data-only — כל הערכים מחרוזות (דרישת FCM). האפליקציה בונה את ההתראה.
  const d = {
    notificationId: String(ev.notificationId),
    cities: JSON.stringify(Array.isArray(ev.cities) ? ev.cities : []),
    eventType: String(ev.eventType ?? 8),
    time: String(ev.time ?? Math.floor(Date.now() / 1000)),
    version: String(ev.version ?? 1),
    note: String(ev.note ?? ""),
    address: String(ev.address ?? ""),
  };
  if (ev.status) d.status = String(ev.status);
  if (ev.expireAt) d.expireAt = String(ev.expireAt);
  if (typeof ev.lat === "number") d.lat = String(ev.lat);
  if (typeof ev.lng === "number") d.lng = String(ev.lng);
  return d;
}

async function fanout(ev) {
  const data = toData(ev);
  // FCM
  if (messaging) {
    try {
      await messaging.send({ topic: FCM_TOPIC, data, android: { priority: "high" } });
    } catch (e) { console.warn("[fcm] send failed:", e.message); }
  }
  // MQTT — payload JSON מלא (האפליקציה מפענחת כמו /notifications)
  if (mqttClient && mqttClient.connected) {
    mqttClient.publish(MQTT_TOPIC, JSON.stringify(ev), { qos: 1, retain: false });
  }
}

async function tick() {
  try {
    const events = await fetchEvents();
    for (const ev of events) {
      if (!ev.notificationId) continue;
      const k = key(ev);
      if (seen.has(k)) continue;        // dedup לפי notificationId:version (עריכה = version חדש = נשלח שוב)
      seen.add(k);
      if (seen.size > 1000) seen.delete(seen.values().next().value);
      await fanout(ev);
      console.log(`[fanout] ${k} cities=${JSON.stringify(ev.cities)} status=${ev.status || "active"}`);
    }
  } catch (e) {
    console.warn("[tick] error:", e.message);
  }
}

console.log(`[gateway] source=${SOURCE_URL} poll=${POLL_MS}ms fcmTopic=${FCM_TOPIC} mqttTopic=${MQTT_TOPIC}`);
setInterval(tick, POLL_MS);
tick();
