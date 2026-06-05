#!/usr/bin/env python3
"""
שרת mock ל"צבע שחור" — לבדיקת אפליקציית הבדיקה בלי התראת אמת בשרת.

מחקה את נקודות הקצה הציבוריות שהאפליקציה צורכת, ומאפשר *לדחוף התראת בדיקה מהמחשב*:
  GET /notifications        → האירועים הפעילים שנדחפו (מסונן לפי TTL)
  GET /lists-versions       → {"cities":N,"polygons":1}
  GET /alerts-history       → היסטוריית האירועים שנדחפו
  GET /static/cities.json   → cities.json (מהקובץ שליד הסקריפט)
  GET /push?city=..&type=3&note=..&address=..&lat=..&lng=..&ttl=120  → דחיפת אירוע בדיקה
  GET /close?id=..          → סגירת אירוע (status=closed)
  GET /                     → טופס דחיפה נוח בדפדפן

הפעלה:
  python3 mock_server.py                 # מאזין על 0.0.0.0:8000
  python3 mock_server.py --port 8080

באפליקציית הבדיקה: הגדרות → מתקדם — מקור נתונים → הזן  http://<IP-של-המחשב>:8000
(שני המכשירים באותה רשת; ב-Windows ודא שה-Firewall מאפשר את הפורט).
ואז פתח  http://localhost:8000/  ולחץ "שלח התראת בדיקה".
"""
import argparse, json, os, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
LOCK = threading.Lock()
EVENTS = []          # אירועים פעילים/שנדחפו
HISTORY = []         # כל מה שנדחף אי-פעם
SEQ = [0]

CITIES_VERSION = 2

FORM = """<!doctype html><html lang=he><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>צבע שחור — mock</title>
<body style="font-family:sans-serif;direction:rtl;max-width:520px;margin:24px auto;background:#121216;color:#e7e7ec">
<h2 style="color:#ffd500">שליחת התראת בדיקה</h2>
<form action=/push>
 עיר: <input name=city value="בני ברק" style="width:100%"><br><br>
 סוג: <select name=type><option value=3>נסיון הסגרה</option><option value=0>מעצר מ.צ.</option>
 <option value=2>מחסומים</option><option value=8>כללי</option></select><br><br>
 כתובת: <input name=address value="רחוב רבי עקיבא" style="width:100%"><br><br>
 הערה: <input name=note value="התראת בדיקה מהמחשב" style="width:100%"><br><br>
 lat: <input name=lat value="32.0874"> lng: <input name=lng value="34.8324"><br><br>
 TTL (שנ'): <input name=ttl value="120"><br><br>
 <button style="background:#ffd500;border:0;padding:12px 20px;font-size:16px;border-radius:8px">שלח התראת בדיקה</button>
</form>
<p>פעילים כעת: <a style="color:#ffd500" href=/notifications>/notifications</a></p>
</body></html>"""


def now():
    return int(time.time())


def active_events():
    t = now()
    return [e for e in EVENTS if e.get("status") != "closed" and e.get("expireAt", 0) > t]


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if "json" in ctype or "html" in ctype else ""))
        self.send_header("Cache-Control", "public, max-age=5")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *a):
        import sys
        sys.stderr.write("REQ %s - %s\n" % (self.address_string(), fmt % a))

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        p = u.path

        if p == "/" :
            return self._send(200, FORM, "text/html")

        if p == "/notifications":
            return self._send(200, json.dumps(active_events(), ensure_ascii=False))

        if p == "/lists-versions":
            return self._send(200, json.dumps({"cities": CITIES_VERSION, "polygons": 1}))

        if p == "/alerts-history":
            return self._send(200, json.dumps(list(reversed(HISTORY)), ensure_ascii=False))

        if p == "/static/cities.json":
            path = os.path.join(HERE, "cities.json")
            if os.path.exists(path):
                with open(path, "rb") as f:
                    return self._send(200, f.read())
            return self._send(200, json.dumps({"cities": {}, "areas": {}}))

        if p == "/push":
            return self.push(q)

        if p == "/close":
            return self.close(q)

        return self._send(404, json.dumps({"error": "not found"}))

    def push(self, q):
        def g(k, d=""):
            return q.get(k, [d])[0]
        with LOCK:
            SEQ[0] += 1
            t = now()
            ttl = int(g("ttl", "120") or 120)
            ev = {
                "notificationId": "test-%d-%d" % (t, SEQ[0]),
                "cities": [g("city", "בני ברק")],
                "eventType": int(g("type", "3") or 3),
                "time": t,
                "expireAt": t + ttl,
                "version": 1,
                "note": g("note", ""),
                "address": g("address", ""),
            }
            lat, lng = g("lat"), g("lng")
            if lat and lng:
                ev["lat"] = float(lat); ev["lng"] = float(lng)
            EVENTS.append(ev)
            HISTORY.append(dict(ev))
        return self._send(200, json.dumps({"ok": True, "pushed": ev}, ensure_ascii=False))

    def close(self, q):
        eid = q.get("id", [""])[0]
        with LOCK:
            for e in EVENTS:
                if e["notificationId"] == eid:
                    e["status"] = "closed"; e["version"] = e.get("version", 1) + 1
        return self._send(200, json.dumps({"ok": True, "closed": eid}))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    srv = ThreadingHTTPServer((args.host, args.port), H)
    print("Mock 'צבע שחור' server on http://%s:%d  (open / in a browser to push test alerts)" % (args.host, args.port))
    srv.serve_forever()


if __name__ == "__main__":
    main()
