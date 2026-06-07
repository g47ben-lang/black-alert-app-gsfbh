/*
  Push Gateway — "צבע שחור"
  משודרג: כולל קריאת מפתח סודי כסות והודעת אתחול ("שלום לכולם").
*/
const mqtt = require("mqtt");

const SOURCE_URL = process.env.SOURCE_URL || "https://black-alert.com";
const POLL_MS = parseInt(process.env.POLL_MS || "3000", 10);
const FCM_TOPIC = process.env.FCM_TOPIC || "alerts";
const MQTT_URL = process.env.MQTT_URL || "";
const MQTT_TOPIC = process.env.MQTT_TOPIC || "alerts";

// --- FCM (חיבור לפיירבייס דרך מפתח סודי) ---
let messaging = null;
try {
  if (process.env.FIREBASE_KEY) {
    const admin = require("firebase-admin");
    const serviceAccount = JSON.parse(process.env.FIREBASE_KEY);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    messaging = admin.messaging();
    console.log("[gateway] FCM enabled! מחובר לפיירבייס בהצלחה.");
  } else {
    console.log("[gateway] FCM disabled (חסר משתנה FIREBASE_KEY)");
  }
} catch (e) {
  console.warn("[gateway] שגיאה בהתחברות לפיירבייס:", e.message);
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
  // data-only — כל הערכים חייבים להיות מחרוזות (טקסט)
  const d = {
    notificationId: String(ev.notificationId),
    cities: JSON.stringify(Array.isArray(ev.cities) ? ev.cities : []),
    eventType: String(ev.eventType ?? 8),
    time: String(ev.time ?? Math.floor(Date.now() / 1000)),
    version: String(ev.version ?? 1),
    note: String(ev.note ?? ""),
    address: String(ev.address ?? ""),
    silent: String(ev.silent ?? false)
  };
  if (ev.status) d.status = String(ev.status);
  if (ev.expireAt) d.expireAt = String(ev.expireAt);
  if (typeof ev.lat === "number") d.lat = String(ev.lat);
  if (typeof ev.lng === "number") d.lng = String(ev.lng);
  return d;
}

async function fanout(ev) {
  const data = toData(ev);
  // שליחה דרך פיירבייס
  if (messaging) {
    try {
      await messaging.send({ topic: FCM_TOPIC, data, android: { priority: "high" } });
    } catch (e) { console.warn("[fcm] send failed:", e.message); }
  }
  // שליחה דרך MQTT
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
      if (seen.has(k)) continue;        
      seen.add(k);
      if (seen.size > 1000) seen.delete(seen.values().next().value);
      
      await fanout(ev);
      console.log(`[fanout] נשלחה התראה: ${k} - הערה: ${ev.note || "אין טקסט"}`);
    }
  } catch (e) {
    console.warn("[tick] שגיאה בסריקה:", e.message);
  }
}

// ==========================================
// פונקציית בדיקה: שולחת "שלום לכולם" בהפעלה
// ==========================================
async function sendTestMessage() {
  if (!messaging) return;
  const testEvent = {
    notificationId: "test-" + Date.now(),
    cities: ["הודעת מערכת"],
    eventType: 8, // 8 = התראה כללית שאפשר לכתוב בה
    time: Math.floor(Date.now() / 1000),
    version: 1,
    note: "שלום לכולם! זה השרת החדש שלנו מדבר. האפליקציה מחוברת!",
    address: ""
  };
  
  try {
    console.log("משגר הודעת בדיקה ראשונית...");
    await fanout(testEvent);
  } catch (e) {
    console.error("שגיאה בהודעת הבדיקה:", e);
  }
}

// הפעלה!
console.log(`[gateway] מתחיל סריקה מול ${SOURCE_URL} כל ${POLL_MS} מילישניות.`);

// מחכים 2 שניות כדי לתת לפיירבייס להתחבר, ואז שולחים את ה"שלום לכולם"
setTimeout(sendTestMessage, 2000);

// מתחילים את הלולאה הקבועה
setInterval(tick, POLL_MS);
tick();
