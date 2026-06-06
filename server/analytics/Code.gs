/*
  ספירת התקנות/פעילים ל"צבע שחור" — Google Apps Script → Google Sheet.

  מקבל heartbeat אנונימי מהאפליקציה ומתחזק שורה לכל מזהה התקנה (firstSeen/lastSeen/version).
  כך רואים בגיליון: סך התקנות = מספר השורות; פעילים = lastSeen בטווח האחרון.

  פריסה (5 דקות):
   1. sheets.google.com → צור גיליון חדש.
   2. Extensions → Apps Script → הדבק את הקובץ הזה → Save.
   3. Deploy → New deployment → type: Web app →
        Execute as: Me · Who has access: Anyone → Deploy → אשר הרשאות.
   4. העתק את ה-Web app URL והדבק אותו ב-Heartbeat.kt (ENDPOINT) באפליקציה.
   5. הגיליון יתמלא אוטומטית. גיליון "Summary" מציג סיכום חי.
*/

function doPost(e) {
  try {
    var data = JSON.parse(e.postData.contents);
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sh = ss.getSheetByName('devices') || createDevicesSheet(ss);
    var now = new Date();

    var ids = sh.getRange(2, 1, Math.max(sh.getLastRow() - 1, 1), 1).getValues();
    var rowIdx = -1;
    for (var i = 0; i < ids.length; i++) {
      if (ids[i][0] === data.id) { rowIdx = i + 2; break; }
    }

    if (rowIdx === -1) {
      sh.appendRow([data.id, now, now, data.v || '', data.vc || '', data.sdk || '', data.pkg || '', 1]);
    } else {
      sh.getRange(rowIdx, 3).setValue(now);                 // lastSeen
      sh.getRange(rowIdx, 4).setValue(data.v || '');         // version
      sh.getRange(rowIdx, 5).setValue(data.vc || '');
      sh.getRange(rowIdx, 6).setValue(data.sdk || '');
      sh.getRange(rowIdx, 7).setValue(data.pkg || '');
      var c = sh.getRange(rowIdx, 8);
      c.setValue((Number(c.getValue()) || 0) + 1);           // pings
    }
    return ContentService.createTextOutput(JSON.stringify({ ok: true }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function createDevicesSheet(ss) {
  var sh = ss.insertSheet('devices');
  sh.appendRow(['installId', 'firstSeen', 'lastSeen', 'version', 'versionCode', 'sdk', 'package', 'pings']);
  sh.setFrozenRows(1);
  buildSummary(ss);
  return sh;
}

// גיליון סיכום חי: סך התקנות + פעילים ב-7/30 ימים.
function buildSummary(ss) {
  var s = ss.getSheetByName('Summary') || ss.insertSheet('Summary');
  s.clear();
  s.getRange('A1').setValue('סך התקנות (מזהים ייחודיים)');
  s.getRange('B1').setFormula('=COUNTA(devices!A2:A)');
  s.getRange('A2').setValue('פעילים ב-7 ימים');
  s.getRange('B2').setFormula('=COUNTIFS(devices!C2:C,">="&(NOW()-7))');
  s.getRange('A3').setValue('פעילים ב-30 ימים');
  s.getRange('B3').setFormula('=COUNTIFS(devices!C2:C,">="&(NOW()-30))');
  s.getRange('A1:A3').setFontWeight('bold');
}

// הרצה ידנית פעם אחת אם רוצים ליצור את הגיליונות מראש.
function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss.getSheetByName('devices')) createDevicesSheet(ss);
  else buildSummary(ss);
}
