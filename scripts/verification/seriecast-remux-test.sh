#!/usr/bin/env bash
# Verifica la ultima ruta sin ejecutar: descarga un master HLS (que yt-dlp baja como TS) y
# comprueba que Mp4Remuxer lo convierta a un MP4 real con MediaExtractor/MediaMuxer.
set -uo pipefail
export HOME=/home/angel
export ANDROID_HOME=/home/angel/Android/Sdk
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.seriecast.app
DIR=/sdcard/Android/data/com.seriecast.app/files/Movies/Doramas
HLS='https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'
HEIGHT=360
W=160; Y_HIGH=500; Y_LOW=120
say() { printf '\n\033[1m== %s \033[0m\n' "$*"; }

dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }

coords() {  # $1 texto  $2 modo: nav|content|field-exact
  python3 - /tmp/ui.xml "$1" "$2" <<'PY'
import re, sys
text, mode = sys.argv[2], sys.argv[3]
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
B = r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
items = []
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    t = re.search(r'text="([^"]*)"', tag); c = re.search(r'class="([^"]*)"', tag); b = re.search(B, tag)
    if not b: continue
    x1, y1, x2, y2 = map(int, b.groups())
    items.append({'t': t.group(1) if t else '', 'cls': c.group(1) if c else '',
                  'x': (x1 + x2) // 2, 'y': (y1 + y2) // 2, 'y1': y1})
nav = next((n['y'] for n in items if n['t'] == 'Ayuda'), None)
hit = None
if mode == 'nav':
    # La barra inferior se ancla con "Ayuda", que solo existe ahi: en la pantalla de descarga
    # tambien hay un boton con el texto exacto "Descargar".
    cands = [n for n in items if n['t'] == text]
    if nav is not None:
        near = [n for n in cands if abs(n['y'] - nav) <= 30]
        cands = near or cands
    hit = cands[0] if cands else None
elif mode == 'content':
    c = [n for n in items if n['t'] == text and (nav is None or abs(n['y'] - nav) > 60)]
    hit = max(c, key=lambda n: n['y']) if c else None
elif mode == 'field-exact':
    label = next((n for n in items if n['t'] == text), None)
    edits = [n for n in items if 'EditText' in n['cls']]
    if label and edits:
        hit = min(edits, key=lambda n: abs(n['y'] - label['y']))
if hit: print(hit['x'], hit['y'])
PY
}

tap() { local c; c=$(coords "$1" "${2:-content}"); [ -z "${c:-}" ] && return 1; echo "  tap '$1' -> ($c)"; "$ADB" shell input tap $c; }
scroll() { "$ADB" shell input swipe $W $Y_HIGH $W $Y_LOW 220; sleep 1; }

# Baja hasta que la etiqueta este visible y devuelve coords del campo.
reveal_field() {  # $1 = label exacto
  local c
  for i in $(seq 1 10); do
    dump
    c=$(coords "$1" field-exact)
    if [ -n "${c:-}" ]; then echo "$c"; return 0; fi
    scroll
  done
  return 1
}

say "0. estado limpio"
for ime in $("$ADB" shell ime list -s 2>/dev/null | tr -d '\r'); do "$ADB" shell ime disable "$ime" >/dev/null 2>&1; done
"$ADB" shell am force-stop "$PKG"
"$ADB" logcat -c
"$ADB" shell rm -f "$DIR"/*.mp4 "$DIR"/*.part >/dev/null 2>&1
"$ADB" shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
sleep 6
echo "  archivos previos: $("$ADB" shell ls "$DIR" 2>/dev/null | tr -d '\r' | wc -l)"

say "1. pestaña Descargar + URL del HLS"
tap "Descargar" nav || exit 1
sleep 3
dump
if ! grep -q 'Descarga de episodios' /tmp/ui.xml; then
  echo "  no estoy en Descargar; pruebo con 'Ir a Descargar'"
  tap "Ir a Descargar" content || exit 1
  sleep 3
  dump
  grep -q 'Descarga de episodios' /tmp/ui.xml || { echo "  no llegue a la pantalla de descarga"; grep -oE 'text="[^"]{3,50}"' /tmp/ui.xml | head -10; exit 1; }
fi
echo "  en la pantalla de descarga"
c=$(reveal_field "URL del stream (m3u8 / mp4)") || { echo "  sin campo URL"; exit 1; }
echo "  campo URL en ($c)"; "$ADB" shell input tap $c; sleep 1
"$ADB" shell input text "$HLS"; sleep 1

say "2. altura maxima = $HEIGHT (prueba el selector de formato sin ffmpeg)"
c=$(reveal_field "Altura máxima en píxeles (vacío = mejor calidad)") || { echo "  sin campo altura"; exit 1; }
echo "  campo altura en ($c)"; "$ADB" shell input tap $c; sleep 1
"$ADB" shell input text "$HEIGHT"; sleep 1
dump
echo "  campo altura ahora: $(grep -oE 'text="360"' /tmp/ui.xml | head -1)"

say "3. descargar"
found=""
for i in $(seq 1 10); do
  dump
  if [ -n "$(coords 'Probar URL' content)" ]; then found=1; break; fi
  scroll
done
[ -z "$found" ] && { echo "  no llegue a los botones"; exit 1; }
tap "Descargar" content || exit 1

say "4. esperar HLS + remux (hasta 240s, se ven los .part crecer)"
prev=""
for i in $(seq 1 120); do
  listing=$("$ADB" shell "ls -la $DIR" 2>/dev/null | tr -d '\r')
  # El remux escribe un temporal .remux.mp4: no cuenta como archivo final.
  final=$(printf '%s' "$listing" | grep -E '\.mp4$' | grep -v '\.part' | grep -v '\.remux\.mp4' | wc -l)
  if [ "$final" -gt 0 ]; then echo "  archivo final tras ~$((i*2))s"; break; fi
  if [ $((i % 10)) -eq 0 ]; then
    echo "  t=$((i*2))s: $(printf '%s' "$listing" | grep -cE 'part|mp4') archivos en curso"
  fi
  sleep 2
done
echo "  --- carpeta final:"
"$ADB" shell "ls -la $DIR" 2>&1 | tail -4 | sed 's/^/  /'

say "5. el remux produjo un MP4 real?"
echo "  --- magic bytes del archivo final:"
"$ADB" shell "for f in $DIR/*.mp4; do echo \"archivo: \$(basename \"\$f\") tamano: \$(stat -c %s \"\$f\")\"; head -c 16 \"\$f\" | od -An -c; done" 2>&1 | sed 's/^/  /'
echo "  --- logcat del remux:"
"$ADB" logcat -d -s Mp4Remuxer YtDlpEngine 2>/dev/null | tail -8 | sed 's/^/  /'
dump
echo "  --- estado en pantalla:"
grep -oE 'text="(Listo|Aviso|La descarga|Descargando|Ajustando)[^"]{0,90}"' /tmp/ui.xml | head -5 | sed 's/^/  /'

say "6. servirlo por DLNA"
tap "Videos" nav || true
sleep 3
"$ADB" forward --remove tcp:18085 >/dev/null 2>&1; "$ADB" forward tcp:18085 tcp:8080 >/dev/null
if tap "Iniciar servidor DLNA" content; then echo "  servidor iniciado"; sleep 6; else echo "  no encontre el boton (quizas ya estaba activo)"; fi
"$ADB" logcat -d -s DlnaController | tail -2 | sed 's/^/  /'
BODY=$(curl -sS -m 8 -X POST http://127.0.0.1:18085/control/contentdirectory \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"' \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  --data '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>')
echo "  $(printf '%s' "$BODY" | grep -oE '<NumberReturned>[0-9]+</NumberReturned>|<TotalMatches>[0-9]+' | tr '\n' ' ')"
M=$(printf '%s' "$BODY" | grep -oE '/media/[^&<"]*' | head -1)
echo "  media path: ${M:-NINGUNO}"
if [ -n "${M:-}" ]; then
  echo "  $(printf '%s' "$BODY" | grep -oE 'protocolInfo=&quot;[^&]*' | head -1 | cut -c1-80)"
  curl -sS -o /dev/null -m 15 -w '  GET: http_code=%{http_code} bytes=%{size_download} tipo=%{content_type}\n' "http://127.0.0.1:18085$M"
  curl -sS -o /dev/null -m 15 -r 0-99 -D /tmp/rh.txt -w '  Range 0-99: http_code=%{http_code} bytes=%{size_download}\n' "http://127.0.0.1:18085$M"
  grep -i content-range /tmp/rh.txt | sed 's/^/    /'
fi
