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
      const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
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

// ==========================================
// Express: dashboard ניהול + קבלת דיווחי "הגעתי לזירה"
// ==========================================
const express = require('express');
const app = express();
app.use(express.json());

const ADMIN_PASS = process.env.ADMIN_PASS || "blackalert2025";
const EVENT_NAMES = { 0: "נסיון מעצר מ.צ.", 2: "התרעת מחסומים", 3: "נסיון הסגרה", 8: "התראה כללית" };
let arrivalReports = [];
const startTime = Date.now();

// health-check ל-UptimeRobot
app.get('/health', (req, res) => {
  res.json({ ok: true, uptime: Math.floor((Date.now() - startTime) / 1000), seen: seen.size });
});

// POST /api/arrived — מהאפליקציה
app.post('/api/arrived', (req, res) => {
  try {
    const r = { ...req.body, id: Date.now(), serverTime: new Date().toLocaleString('he-IL', { timeZone: 'Asia/Jerusalem' }) };
    arrivalReports.unshift(r);
    if (arrivalReports.length > 200) arrivalReports.pop();
    console.log(`[arrived] ${EVENT_NAMES[r.event_type] || "?"} | ${(r.cities || []).join(', ')} | ${r.address || ""}`);
    res.json({ success: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// GET /api/reports — לדשבורד (בדיקת סיסמה ב-header)
app.get('/api/reports', (req, res) => {
  if (req.headers['x-pass'] !== ADMIN_PASS) return res.status(403).json({ error: "שגיאת הרשאה" });
  res.json(arrivalReports);
});

// POST /api/clear — ניקוי
app.post('/api/clear', (req, res) => {
  const p = req.headers['x-pass'] || req.body?.pass;
  if (p !== ADMIN_PASS) return res.status(403).json({ error: "שגיאת הרשאה" });
  arrivalReports = [];
  res.json({ success: true });
});

// GET /dashboard — אתר הניהול
app.get('/dashboard', (req, res) => {
  res.send(`<!DOCTYPE html>
<html dir="rtl" lang="he">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>צבע שחור — חמ"ל</title>
  <style>
    *{box-sizing:border-box}
    body{font-family:Arial,sans-serif;background:#121212;color:#eee;margin:0;padding:16px}
    h1{color:#ff4444;margin-top:0}
    #status{font-size:13px;color:#888;margin-bottom:12px}
    .toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:16px}
    select,button{padding:8px 14px;background:#2a2a2a;color:#eee;border:1px solid #444;border-radius:6px;font-size:14px;cursor:pointer}
    button.danger{background:#6b1111;color:#fff;border-color:#ff4444}
    button.danger:hover{background:#8b1111}
    table{width:100%;border-collapse:collapse;background:#1a1a1a;border-radius:8px;overflow:hidden}
    th{background:#2a2a2a;color:#aaa;padding:10px 12px;text-align:right;font-size:13px}
    td{padding:10px 12px;border-top:1px solid #2a2a2a;font-size:14px;vertical-align:top}
    tr:hover td{background:#222}
    .badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;font-weight:bold}
    .t3{background:#5a2a00;color:#ff8c00}
    .t0,.t2{background:#4a3a00;color:#ffd500}
    .tx{background:#2a2a2a;color:#aaa}
    a.map{color:#4da6ff;text-decoration:none}
    .empty{text-align:center;padding:40px;color:#555}
    #loginBox{position:fixed;inset:0;background:#000;display:flex;align-items:center;justify-content:center;z-index:9}
    .loginCard{background:#1a1a1a;padding:32px;border-radius:12px;text-align:center;width:280px}
    .loginCard h2{color:#ff4444;margin-top:0}
    .loginCard input{width:100%;padding:10px;background:#2a2a2a;border:1px solid #444;color:#eee;border-radius:6px;font-size:16px;text-align:center;margin:12px 0}
    .loginCard button{width:100%;padding:10px;background:#ff4444;color:#fff;border:none;border-radius:6px;font-size:16px;cursor:pointer}
  </style>
</head>
<body>
  <div id="loginBox">
    <div class="loginCard">
      <h2>⚫ צבע שחור</h2>
      <p style="color:#888">חמ"ל — דיווחי הגעה לזירה</p>
      <input id="passInput" type="password" placeholder="סיסמה" onkeydown="if(event.key==='Enter')login()">
      <button onclick="login()">כניסה</button>
      <p id="loginErr" style="color:#ff4444;font-size:13px;min-height:18px"></p>
    </div>
  </div>

  <h1>⚫ צבע שחור — דיווחי הגעה לזירה</h1>
  <div id="status">טוען…</div>
  <div class="toolbar">
    <label>סינון: <select id="flt" onchange="render()">
      <option value="">הכל</option>
      <option value="3">נסיון הסגרה</option>
      <option value="0">נסיון מעצר מ.צ.</option>
      <option value="2">התרעת מחסומים</option>
      <option value="8">התראה כללית</option>
    </select></label>
    <button class="danger" onclick="clearAll()">🗑 נקה הכל</button>
  </div>
  <table>
    <thead><tr><th>שעה</th><th>סוג אירוע</th><th>יישובים</th><th>כתובת</th><th>מפה</th></tr></thead>
    <tbody id="tb"></tbody>
  </table>

<script>
const PASS_KEY = 'ba_dash_pass';
const NAMES = {0:'נסיון מעצר מ.צ.',2:'התרעת מחסומים',3:'נסיון הסגרה',8:'התראה כללית'};
const CLS   = {3:'t3',0:'t0',2:'t2'};
let pass = sessionStorage.getItem(PASS_KEY) || '';
let data = [];

function login() {
  const v = document.getElementById('passInput').value;
  fetch('/api/reports', { headers: { 'x-pass': v } })
    .then(r => { if (r.status === 403) throw new Error(); return r.json(); })
    .then(() => { pass = v; sessionStorage.setItem(PASS_KEY, v); document.getElementById('loginBox').remove(); load(); setInterval(load, 10000); })
    .catch(() => { document.getElementById('loginErr').textContent = 'סיסמה שגויה'; });
}

function load() {
  fetch('/api/reports', { headers: { 'x-pass': pass } })
    .then(r => r.json()).then(d => { data = d; render(); document.getElementById('status').textContent = 'עדכון אחרון: ' + new Date().toLocaleTimeString('he-IL') + ' · ' + d.length + ' דיווחים'; })
    .catch(() => document.getElementById('status').textContent = 'שגיאת חיבור');
}

function render() {
  const f = document.getElementById('flt').value;
  const rows = data.filter(r => !f || String(r.event_type) === f);
  const tb = document.getElementById('tb');
  if (!rows.length) { tb.innerHTML = '<tr><td colspan=5 class="empty">אין דיווחים עדיין</td></tr>'; return; }
  tb.innerHTML = rows.map(r => {
    const cl = CLS[r.event_type] || 'tx';
    const map = (r.lat && r.lng)
      ? \`<a class="map" href="https://waze.com/ul?ll=\${r.lat},\${r.lng}&navigate=yes" target="_blank">📍 Waze</a>\`
      : '—';
    return \`<tr><td>\${r.serverTime}</td><td><span class="badge \${cl}">\${NAMES[r.event_type]||'?'}</span></td><td>\${(r.cities||[]).join(', ')}</td><td>\${r.address||'—'}</td><td>\${map}</td></tr>\`;
  }).join('');
}

async function clearAll() {
  if (!confirm('למחוק את כל הדיווחים?')) return;
  await fetch('/api/clear', { method:'POST', headers:{'Content-Type':'application/json','x-pass':pass}, body:JSON.stringify({pass}) });
  load();
}

// בדוק אם יש סיסמה שמורה
if (pass) { fetch('/api/reports',{headers:{'x-pass':pass}}).then(r=>{ if(r.ok){document.getElementById('loginBox').remove();load();setInterval(load,10000);} }); }
</script>
</body></html>`);
});

// הפעלת השרת
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`[server] פועל על פורט ${PORT} | dashboard: /dashboard`);
  console.log(`[dashboard] סיסמה: ${ADMIN_PASS}`);
});

// הפעלה
console.log(`[gateway] מתחיל | מקורות: ${SOURCE_URLS.length} | סריקה כל ${POLL_MS}ms`);
setInterval(tick, POLL_MS);
tick();
