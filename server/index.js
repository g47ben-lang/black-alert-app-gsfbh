/*
  Relay ל-push (Firebase Cloud Functions, v1).

  עושה polling *מרכזי אחד* ל-black-alert.com ושולח FCM ל-topic "alerts" — כך שכל
  המכשירים התומכים (Play Services) מקבלים push כמעט-מיידי במקום ש-כל מכשיר יבדוק בעצמו.
  מכשירים שאינם תומכים ממשיכים ב-polling ישיר באפליקציה (failover).

  פריסה:
    cd server && npm install
    firebase deploy --only functions
  (צריך פרויקט Firebase עם Blaze; ה-topic ב-firebase_config.xml באפליקציה חייב להיות "alerts".)
*/
const functions = require("firebase-functions");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const REGION = "europe-west1";
const BLACK = "https://black-alert.com";
const TOPIC = "alerts";

// dedup לפי notificationId:version בין הרצות (best-effort בזיכרון המופע)
const seen = [];

exports.pushAlerts = functions
  .region(REGION)
  .runWith({ memory: "256MB", timeoutSeconds: 55 })
  .pubsub.schedule("every 1 minutes")
  .onRun(async () => {
    let events;
    try {
      const r = await fetch(`${BLACK}/notifications`, { signal: AbortSignal.timeout(8000) });
      if (!r.ok) { console.warn("poll http", r.status); return null; }
      events = await r.json();
    } catch (e) { console.error("fetch", e.message); return null; }
    if (!Array.isArray(events)) return null;

    let sent = 0;
    for (const ev of events) {
      if (!ev.notificationId) continue;
      const key = ev.notificationId + ":" + (ev.version || 1);
      if (seen.includes(key)) continue;
      seen.push(key);
      if (seen.length > 500) seen.shift();

      // data-only message → האפליקציה בונה את ההתראה (צלצול/ניווט) באותו צינור כמו ב-polling.
      const data = {
        notificationId: String(ev.notificationId),
        cities: JSON.stringify(Array.isArray(ev.cities) ? ev.cities : []),
        eventType: String(ev.eventType ?? 8),
        time: String(ev.time ?? Math.floor(Date.now() / 1000)),
        version: String(ev.version ?? 1),
        note: String(ev.note ?? ""),
        address: String(ev.address ?? ""),
      };
      if (ev.status) data.status = String(ev.status);
      if (ev.expireAt) data.expireAt = String(ev.expireAt);
      if (typeof ev.lat === "number") data.lat = String(ev.lat);
      if (typeof ev.lng === "number") data.lng = String(ev.lng);

      try {
        await getMessaging().send({
          topic: TOPIC,
          data,
          android: { priority: "high" },
        });
        sent++;
      } catch (e) { console.error("send", e.message); }
    }
    if (sent) console.log(`pushed ${sent} alert(s) to topic ${TOPIC}`);
    return null;
  });
