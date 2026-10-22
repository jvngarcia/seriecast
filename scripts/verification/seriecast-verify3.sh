#!/usr/bin/env bash
# Verificacion end-to-end de SerieCast en el emulador/dispositivo ya conectado.
set -uo pipefail
export HOME=/home/angel
export ANDROID_HOME=/home/angel/Android/Sdk
ADB="$ANDROID_HOME/platform-tools/adb"
APK=/home/angel/dev/sideprojects/series/app/build/outputs/apk/debug/app-debug.apk
PKG=com.seriecast.app
say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

findtap() {  # $1 = texto (exacto primero, substring como respaldo)
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  python3 - /tmp/ui.xml "$1" <<'PY'
import re, sys
text = sys.argv[2]
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
bounds = r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
for pat in (r'text="' + re.escape(text) + r'"[^>]*' + bounds,
            r'text="[^"]*' + re.escape(text) + r'[^"]*"[^>]*' + bounds,
            bounds + r'[^>]*text="[^"]*' + re.escape(text) + r'[^"]*"'):
    m = re.search(pat, xml)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
}

tap() {
  local coords
  coords=$(findtap "$1")
  if [ -z "${coords:-}" ]; then echo "  NO encontre '$1'"; return 1; fi
  echo "  tap '$1' en ($coords)"
  "$ADB" shell input tap $coords
}

say "1. instalar APK corregido"
"$ADB" install -r "$APK" 2>&1 | tail -2

say "2. relanzar limpio"
"$ADB" logcat -c
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/.ui.MainActivity" 2>&1 | tail -2
sleep 7

say "3. Chaquopy (bug 1: sys.version como atributo)"
"$ADB" logcat -d -s SerieCastApp | grep -E 'Chaquopy' | tail -4
echo "  pythonAvailable segun la app: $("$ADB" logcat -d -s SerieCastApp | grep -c 'Chaquopy started')"

say "4. puente Kotlin <-> Python: version de yt-dlp leida en el dispositivo"
tap "Descargar" && sleep 5
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" pull /sdcard/ui.xml /tmp/ui_dl.xml >/dev/null 2>&1
grep -oE 'text="[^"]*yt-dlp[^"]*"' /tmp/ui_dl.xml | head -3

say "5. arrancar el servidor DLNA"
tap "Videos" && sleep 2
tap "Iniciar servidor DLNA" && sleep 6
"$ADB" logcat -d -s DlnaController | tail -3

say "6. HTTP estricto (bug 2: header EXT sin dos puntos)"
"$ADB" forward --remove tcp:18080 >/dev/null 2>&1
"$ADB" forward tcp:18080 tcp:8080 >/dev/null
curl -sS -D /tmp/hdr.txt -m 6 -o /tmp/body.xml \
  -w '  http_code=%{http_code} bytes=%{size_download}\n' http://127.0.0.1:18080/description.xml
echo "  --- headers:"; sed 's/^/  /' /tmp/hdr.txt
echo "  --- body (300 bytes):"; head -c 300 /tmp/body.xml | sed 's/^/  /'; echo

say "7. SOAP Browse contra el servidor del telefono"
curl -sS -m 6 -X POST http://127.0.0.1:18080/control/contentdirectory \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  --data '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>' \
  -w '\n  http_code=%{http_code}\n' | head -c 800 | sed 's/^/  /'

say "8. foreground service"
"$ADB" shell dumpsys activity services "$PKG" 2>/dev/null | grep -E 'isForeground|foregroundId' | head -3 | sed 's/^/  /'
