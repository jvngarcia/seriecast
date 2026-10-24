#!/usr/bin/env bash
# Pipeline completo en el emulador 320x640: pegar URL en el campo CORRECTO -> Descargar
# -> archivo en disco -> publicado por Browse -> servido con Range.
set -uo pipefail
export HOME=/home/angel
export ANDROID_HOME=/home/angel/Android/Sdk
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.seriecast.app
DIR=/sdcard/Android/data/com.seriecast.app/files/Movies/Doramas
URL='https://download.samplelib.com/mp4/sample-5s.mp4'
W=160; Y_HIGH=500; Y_LOW=120        # 320x640: swipe dentro de pantalla
say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }

coords() {  # $1 texto  $2 modo: nav | content | field-exact | field-after
  python3 - /tmp/ui.xml "$1" "$2" <<'PY'
import re, sys
text, mode = sys.argv[2], sys.argv[3]
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
B = r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
items = []
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag)
    c = re.search(r'class="([^"]*)"', tag)
    b = re.search(B, tag)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    items.append({'text': t.group(1) if t else '', 'cls': c.group(1) if c else '',
                  'x': (x1 + x2) // 2, 'y': (y1 + y2) // 2, 'y1': y1, 'y2': y2})

nav_y = next((n['y'] for n in items if n['text'] == 'Ayuda'), None)
hit = None
if mode == 'nav':
    hit = next((n for n in items if n['text'] == text), None)
elif mode == 'content':
    cands = [n for n in items if n['text'] == text]
    if nav_y is not None:
        cands = [n for n in cands if abs(n['y'] - nav_y) > 60]
    hit = max(cands, key=lambda n: n['y']) if cands else None
elif mode == 'field-exact':
    # Etiqueta EXACTA dentro de un OutlinedTextField (no el titulo de la tarjeta).
    label = next((n for n in items if n['text'] == text), None)
    edits = [n for n in items if 'EditText' in n['cls']]
    if label and edits:
        hit = min(edits, key=lambda n: abs(n['y'] - label['y']))
elif mode == 'field-after':
    # Primer EditText DEBAJO de un titulo de seccion.
    head = next((n for n in items if n['text'] == text), None)
    edits = sorted([n for n in items if 'EditText' in n['cls']], key=lambda n: n['y1'])
    if head:
        hit = next((n for n in edits if n['y1'] >= head['y1']), None)
if hit:
    print(hit['x'], hit['y'])
PY
}

tap() { local c; c=$(coords "$1" "${2:-content}"); if [ -z "${c:-}" ]; then echo "  NO encontre '$1' (${2:-content})"; return 1; fi; echo "  tap '$1' -> ($c)"; "$ADB" shell input tap $c; }
scroll() { "$ADB" shell input swipe $W $Y_HIGH $W $Y_LOW 220; sleep 1; }

say "0. estado limpio"
for ime in $("$ADB" shell ime list -s 2>/dev/null | tr -d '\r'); do "$ADB" shell ime disable "$ime" >/dev/null 2>&1; done
"$ADB" shell am force-stop "$PKG"
"$ADB" logcat -c
"$ADB" shell rm -f "$DIR"/*.mp4 >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
sleep 6
echo "  app reiniciada; archivos previos: $("$ADB" shell ls "$DIR" 2>/dev/null | tr -d '\r' | wc -l)"

say "1. pestaña Descargar"
tap "Descargar" nav || exit 1
sleep 3

say "2. escribir la URL en el campo del STREAM (label exacto)"
dump
c=$(coords "URL del stream (m3u8 / mp4)" field-exact)
if [ -z "${c:-}" ]; then
  echo "  campo no visible; bajo un poco"
  scroll; dump; c=$(coords "URL del stream (m3u8 / mp4)" field-exact)
fi
[ -z "${c:-}" ] && { echo "  NO encontre el campo del stream"; grep -oE 'text="[^"]{2,60}"' /tmp/ui.xml | head -15; exit 1; }
echo "  foco en ($c)"
"$ADB" shell input tap $c
sleep 1
"$ADB" shell input text "$URL"
sleep 1
dump
echo "  verificacion del campo:"
python3 - /tmp/ui.xml <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
B = r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
rows = []
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag); c = re.search(r'class="([^"]*)"', tag); b = re.search(B, tag)
    if not b: continue
    x1, y1, x2, y2 = map(int, b.groups())
    rows.append(((t.group(1) if t else ''), (c.group(1) if c else ''), y1))
rows.sort(key=lambda r: r[2])
for text, cls, y in rows:
    if 'samplelib' in text or 'URL del stream' in text or 'Dirección o búsqueda' in text:
        print(f"    y={y:4d} class={cls.split('.')[-1]:12s} text={text[:70]}")
PY

say "3. bajar hasta los botones"
found=""
for i in $(seq 1 10); do
  dump
  if [ -n "$(coords 'Probar URL' content)" ]; then found=1; echo "  botones visibles tras $i scroll(s)"; break; fi
  scroll
done
[ -z "$found" ] && { echo "  no llegue a los botones"; grep -oE 'text="[^"]{2,50}"' /tmp/ui.xml | tail -12; exit 1; }
tap "Descargar" content || exit 1

say "4. esperar la descarga"
for i in $(seq 1 60); do
  n=$("$ADB" shell ls "$DIR" 2>/dev/null | tr -d '\r' | grep -c mp4 || true)
  if [ "${n:-0}" -gt 0 ]; then echo "  archivo tras ~$((i*2))s"; break; fi
  sleep 2
done
echo "  --- carpeta:"; "$ADB" shell ls -la "$DIR" 2>&1 | tail -3 | sed 's/^/  /'
echo "  --- logcat motor:"; "$ADB" logcat -d -s YtDlpEngine Mp4Remuxer 2>/dev/null | tail -5 | sed 's/^/  /'
dump
echo "  --- estado en pantalla:"; grep -oE 'text="[^"]{3,70}"' /tmp/ui.xml | tail -8 | sed 's/^/  /'

say "5. asegurar servidor DLNA activo"
tap "Videos" nav || true
sleep 2
dump
if [ -n "$(coords 'Iniciar servidor DLNA' content)" ]; then tap "Iniciar servidor DLNA" content; sleep 5; else echo "  ya estaba activo"; fi
"$ADB" logcat -d -s DlnaController | tail -2 | sed 's/^/  /'

say "6. Browse: la biblioteca publica el archivo?"
"$ADB" forward --remove tcp:18083 >/dev/null 2>&1; "$ADB" forward tcp:18083 tcp:8080 >/dev/null
BODY=$(curl -sS -m 8 -X POST http://127.0.0.1:18083/control/contentdirectory \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  --data '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>')
echo "  $(printf '%s' "$BODY" | grep -oE '<NumberReturned>[0-9]+</NumberReturned>|<TotalMatches>[0-9]+' | tr '\n' ' ')"
echo "  title: $(printf '%s' "$BODY" | grep -oE '&lt;dc:title&gt;[^&]*' | head -1)"
MEDIA=$(printf '%s' "$BODY" | grep -oE 'http://[0-9.]+:8080/media/[^&<]*' | head -1 | sed 's|.*/media/|/media/|')
echo "  res URL: ${MEDIA:-NINGUNA}"

say "7. servir el archivo como lo haria el televisor"
if [ -n "${MEDIA:-}" ]; then
  curl -sS -o /dev/null -m 10 -w '  GET completo: http_code=%{http_code} bytes=%{size_download}\n' "http://127.0.0.1:18083$MEDIA"
  curl -sS -o /dev/null -m 10 -r 0-99 -D /tmp/rhdr.txt -w '  Range 0-99:   http_code=%{http_code} bytes=%{size_download}\n' "http://127.0.0.1:18083$MEDIA"
  grep -iE 'content-range|accept-ranges' /tmp/rhdr.txt | sed 's/^/    /'
  curl -sS -o /dev/null -m 10 -r 99999999- -w '  Range absurdo: http_code=%{http_code} (esperado 416)\n' "http://127.0.0.1:18083$MEDIA"
else
  echo "  sin item publicado"
fi
