#!/usr/bin/env bash
# Verifica el sniffer de WebView: cargar una URL de media y comprobar que la captura aparece
# en la lista de "Streams detectados" y que el boton "Usar" la copia al formulario.
set -uo pipefail
export HOME=/home/angel
export ANDROID_HOME=/home/angel/Android/Sdk
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.seriecast.app
HLS='https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8'
W=160; Y_HIGH=500; Y_LOW=120
say() { printf '\n\033[1m== %s \033[0m\n' "$*"; }

dump() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
coords() {
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
                  'x': (x1 + x2) // 2, 'y': (y1 + y2) // 2})
nav = next((n['y'] for n in items if n['t'] == 'Ayuda'), None)
hit = None
if mode == 'nav':
    c = [n for n in items if n['t'] == text]
    if nav is not None:
        c = [n for n in c if abs(n['y'] - nav) <= 30] or c
    hit = c[0] if c else None
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
tap() { local c; c=$(coords "$1" "${2:-content}"); [ -z "${c:-}" ] && { echo "  NO '$1' (${2:-content})"; return 1; }; echo "  tap '$1' -> ($c)"; "$ADB" shell input tap $c; }
reveal() { local c; for i in $(seq 1 10); do dump; c=$(coords "$1" "${2:-field-exact}"); [ -n "${c:-}" ] && { echo "$c"; return 0; }; "$ADB" shell input swipe $W $Y_HIGH $W $Y_LOW 220; sleep 1; done; return 1; }

say "0. app limpia"
for ime in $("$ADB" shell ime list -s 2>/dev/null | tr -d '\r'); do "$ADB" shell ime disable "$ime" >/dev/null 2>&1; done
"$ADB" shell am force-stop "$PKG"; "$ADB" logcat -c
"$ADB" shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null 2>&1; sleep 6

say "1. pantalla de descarga"
tap "Descargar" nav || tap "Ir a Descargar" content || exit 1
sleep 3; dump
grep -q 'Descarga de episodios' /tmp/ui.xml || { echo "  no llegue"; grep -oE 'text="[^"]{3,50}"' /tmp/ui.xml | head; exit 1; }

say "2. escribir la URL de media en 'Dirección o búsqueda'"
c=$(reveal "Dirección o búsqueda") || { echo "  sin campo de direccion"; exit 1; }
echo "  campo en ($c)"; "$ADB" shell input tap $c; sleep 1
"$ADB" shell input text "$HLS"; sleep 1
dump; echo "  campo: $(grep -oE 'text="https://test-streams[^"]*"' /tmp/ui.xml | head -1)"

say "3. abrir el navegador integrado"
tap "Abrir navegador" content || exit 1
sleep 10

say "4. buscar la captura en la lista de streams"
found=""
for i in $(seq 1 12); do
  dump
  if grep -q 'Streams detectados' /tmp/ui.xml; then found=1; echo "  'Streams detectados' visible tras $i scroll(s)"; break; fi
  "$ADB" shell input swipe $W $Y_HIGH $W $Y_LOW 220; sleep 1
done
if [ -z "$found" ]; then
  echo "  NO aparecio la lista de capturas"
  echo "  --- pistas (m3u8 / http en pantalla):"
  grep -oE 'text="[^"]*(m3u8|\.mp4|Streams)[^"]*"' /tmp/ui.xml | head -6
  grep -oE 'text="[^"]{3,60}"' /tmp/ui.xml | tail -12
  exit 1
fi
echo "  --- candidatos en pantalla:"
grep -oE 'text="[^"]{3,80}"' /tmp/ui.xml | sed -n '/Streams detectados/,$p' | head -6 | sed 's/^/    /'

say "5. 'Usar' copia la URL y el Referer al formulario"
if tap "Usar" content; then
  sleep 2; dump
  echo "  --- campo URL del stream ahora:"
  grep -oE 'text="https://test-streams[^"]*"' /tmp/ui.xml | head -2 | sed 's/^/    /'
  echo "  --- Referer ahora:"
  grep -oE 'text="https?://[^"]*(mux\.dev|test-streams)[^"]*"' /tmp/ui.xml | head -3 | sed 's/^/    /'
else
  echo "  no encontre el boton Usar"
fi
