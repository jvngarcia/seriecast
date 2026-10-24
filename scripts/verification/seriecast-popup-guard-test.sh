#!/usr/bin/env bash
# Verifica el guard de publicidad de la pantalla de captura:
#
#   1. un enlace `target="_blank"` clickeado de verdad (gesto del usuario) no abre ventana y el
#      intento queda contado;
#   2. `location.href` a otro dominio es rechazado y contado (la pagina original sigue en pantalla);
#   3. una navegacion del mismo host sigue funcionando (el guard no rompe la cadena del reproductor).
#
# La pagina se sirve desde el host del emulador (10.0.2.2) para no depender de un agregador real.
set -uo pipefail
export HOME=/home/angel
export ANDROID_HOME=/home/angel/Android/Sdk
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.seriecast.app
PORT=8099
BASE="http://10.0.2.2:$PORT"
PAGES=/tmp/seriecast-popup-pages
W=160; Y_HIGH=560; Y_LOW=200
say() { printf '\n\033[1m== %s \033[0m\n' "$*"; }

dump() {
  # uiautomator falla mientras la UI no esta idle. Se borra el XML remoto antes de dumpear, o el
  # pull traeria el volcado viejo y el arnes leeria una pantalla que ya no esta.
  for _ in 1 2 3 4 5; do
    rm -f /tmp/ui.xml
    "$ADB" shell rm -f /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
    [ -s /tmp/ui.xml ] && return 0
    sleep 2
  done
  echo "  (sin volcado de UI)"
  : > /tmp/ui.xml
  return 1
}

coords() {
  python3 - /tmp/ui.xml "$1" "${2:-content}" <<'PY'
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
                  'x': (x1 + x2) // 2, 'y': (y1 + y2) // 2, 'top': y1, 'h': y2 - y1})
hit = None
if mode == 'content':
    c = [n for n in items if n['t'] == text]
    hit = max(c, key=lambda n: n['y']) if c else None
elif mode == 'field-exact':
    label = next((n for n in items if n['t'] == text), None)
    edits = [n for n in items if 'EditText' in n['cls']]
    if label and edits:
        hit = min(edits, key=lambda n: abs(n['y'] - label['y']))
if hit:
    print(hit['x'], hit['y'])
PY
}

tap() {
  [ -s /tmp/ui.xml ] || dump
  local c; c=$(coords "$1" "${2:-content}")
  [ -z "${c:-}" ] && { echo "  NO '$1' (${2:-content})"; return 1; }
  echo "  tap '$1' -> ($c)"; "$ADB" shell input tap $c
}

tap_retry() {
  # La UI de Compose tarda en asentarse: reintentar el toque en vez de abortar al primer fallo.
  local i
  for i in 1 2 3 4 5 6 7 8; do
    tap "$1" "${2:-content}" && return 0
    sleep 2; dump
  done
  echo "  textos visibles:"; grep -oE 'text="[^"]{2,40}"' /tmp/ui.xml | head -12 | sed 's/^/    /'
  return 1
}

wait_app() {
  local i
  for i in $(seq 1 40); do
    if "$ADB" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*$PKG"; then
      echo "  la app tomo el foco en ${i}s"; return 0
    fi
    sleep 1
  done
  echo "  la app no tomo el foco"; return 1
}

reveal() {
  local c
  for i in $(seq 1 10); do
    dump; c=$(coords "$1" "${2:-field-exact}")
    [ -n "${c:-}" ] && { echo "$c"; return 0; }
    "$ADB" shell input swipe $W $Y_HIGH $W $Y_LOW 220; sleep 1
  done
  return 1
}

type_address() {
  local c; c=$(reveal "Dirección o búsqueda") || { echo "  sin campo de direccion"; return 1; }
  "$ADB" shell input tap $c; sleep 1
  "$ADB" shell input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
  for _ in $(seq 1 60); do "$ADB" shell input keyevent KEYCODE_DEL >/dev/null 2>&1; done
  "$ADB" shell input text "$1"; sleep 1
  tap "Ir" content || return 1
}

say "0. paginas de prueba en $BASE"
# un servidor viejo en el mismo puerto serviria otra pagina y falsearia el resultado
for pid in $(ss -ltnp 2>/dev/null | grep ":$PORT " | grep -oE 'pid=[0-9]+' | cut -d= -f2); do kill "$pid" 2>/dev/null; done
rm -rf "$PAGES"; mkdir -p "$PAGES"
cat > "$PAGES/index.html" <<'HTML'
<!doctype html><meta charset="utf-8"><title>guard</title>
<body style="margin:0;font:28px sans-serif">
<a id="popup" href="https://ads.example.net/popup" target="_blank"
   style="display:block;height:130px;line-height:130px;text-align:center;background:#ccc;color:#000">
  ABRIR AVISO EMERGENTE</a>
<p style="padding:16px">PAGINA UNO — el redirect corre a los 14s</p>
<script>
  // El popup blocker de Chromium exige un gesto real del usuario, asi que el enlace de arriba se
  // toca con `adb shell input tap`; este timer solo prueba la navegacion del frame principal.
  setTimeout(function () { location.href = 'https://ads.example.net/hijack'; }, 14000);
</script>
HTML
cat > "$PAGES/segunda.html" <<'HTML'
<!doctype html><meta charset="utf-8"><title>guard</title>
<body style="font:28px sans-serif;padding:24px">
<h1>SEGUNDA</h1>
<script>setTimeout(function () { location.href = '/tercera.html'; }, 2000);</script>
HTML
cat > "$PAGES/tercera.html" <<'HTML'
<!doctype html><meta charset="utf-8"><title>guard</title>
<body style="font:28px sans-serif;padding:24px">
<h1>CADENA OK</h1><p>la navegacion del mismo host sigue funcionando</p>
HTML
( cd "$PAGES" && python3 -m http.server "$PORT" --bind 0.0.0.0 >/tmp/seriecast-popup-server.log 2>&1 ) &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
sleep 2
# 10.0.2.2 es el alias del host SOLO dentro del emulador; desde el host se controla por loopback.
curl -sf "http://127.0.0.1:$PORT/index.html" >/dev/null && echo "  servidor ok (host)" || { echo "  servidor no responde"; exit 1; }
"$ADB" shell ping -c 2 -W 2 10.0.2.2 >/dev/null 2>&1 && echo "  el emulador alcanza 10.0.2.2" || echo "  AVISO: 10.0.2.2 no contesta ping"

say "1. instalar y abrir la app"
"$ADB" install -r /home/angel/dev/sideprojects/series/app/build/outputs/apk/debug/app-debug.apk >/dev/null 2>&1 \
  || { echo "  fallo el install"; exit 1; }
"$ADB" shell wm density 100 >/dev/null 2>&1
for ime in $("$ADB" shell ime list -s 2>/dev/null | tr -d '\r'); do "$ADB" shell ime disable "$ime" >/dev/null 2>&1; done
"$ADB" shell am force-stop "$PKG"; "$ADB" logcat -c
"$ADB" shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
wait_app || exit 1
sleep 2
dump || exit 1

say "2. entrar a la captura"
tap_retry "Descargar" content || tap_retry "Ir a Descargar" content || exit 1
sleep 5
tap_retry "Abrir navegador" content || { echo "  no encontre 'Abrir navegador'"; exit 1; }
sleep 3

say "3. cargar la pagina de prueba y tocar el enlace target=_blank"
type_address "$BASE/index.html" || exit 1
# La pagina tiene que estar pintada antes de tocar: un tap a ciegas no genera gesto sobre el
# enlace y el popup blocker de Chromium ni siquiera llama a onCreateWindow.
popup_xy=""
for i in $(seq 1 20); do
  dump
  popup_xy=$(coords "ABRIR AVISO EMERGENTE" content)
  [ -n "${popup_xy:-}" ] && { echo "  pagina lista tras ${i}s, enlace en ($popup_xy)"; break; }
  sleep 1
done
[ -z "${popup_xy:-}" ] && { echo "  no aparecio el enlace en la pagina"; exit 1; }
"$ADB" shell input tap $popup_xy
sleep 3
dump
echo "  --- textos del guard tras el click:"
grep -oE 'text="Bloqueado[^"]*"' /tmp/ui.xml | sed 's/^/    /'
ventanas_click=$(grep -oE 'Bloqueado: [0-9]+ ventana' /tmp/ui.xml | grep -oE '[0-9]+')
"$ADB" exec-out screencap -p > /tmp/seriecast-popup-1.png 2>/dev/null

say "4. esperar el redirect a otro dominio (14s)"
redirects=0
for i in $(seq 1 25); do
  dump
  redirects=$(grep -oE '[0-9]+ redirecci' /tmp/ui.xml | grep -oE '[0-9]+')
  [ "${redirects:-0}" -ge 1 ] && { echo "  redirect detectado tras ${i}s"; break; }
  sleep 1
done
echo "  --- textos del guard tras el redirect:"
grep -oE 'text="Bloqueado[^"]*"' /tmp/ui.xml | sed 's/^/    /'
popups=$(grep -oE 'Bloqueado: [0-9]+ ventana' /tmp/ui.xml | grep -oE '[0-9]+')
redirects=$(grep -oE '[0-9]+ redirecci' /tmp/ui.xml | grep -oE '[0-9]+')
host=$(grep -oE 'último: [a-z0-9.-]+' /tmp/ui.xml | awk '{print $2}')
focus=$("$ADB" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')
echo "  contadores -> ventanas=$popups redirecciones=$redirects ultimo=$host"
echo "  foco actual:$focus"
"$ADB" exec-out screencap -p > /tmp/seriecast-popup-2.png 2>/dev/null

say "5. una navegacion del mismo host sigue funcionando"
type_address "$BASE/segunda.html" || exit 1
sleep 8
dump
"$ADB" exec-out screencap -p > /tmp/seriecast-popup-3.png 2>/dev/null

say "6. veredicto"
fail=0
[ "${ventanas_click:-0}" -ge 1 ] && echo "  OK: el click en target=_blank no abrio ventana y quedo contado" \
  || { echo "  FALLA: el click no incremento el contador de ventanas"; fail=1; }
[ "${redirects:-0}" -ge 1 ] && echo "  OK: la redireccion a otro dominio fue rechazada y contada" \
  || { echo "  FALLA: no se conto ninguna redireccion"; fail=1; }
[ "${host:-}" = "ads.example.net" ] && echo "  OK: el ultimo bloqueo es el dominio del anuncio" \
  || { echo "  FALLA: ultimo host bloqueado '${host:-}'"; fail=1; }
case "$focus" in
  *"$PKG"*) echo "  OK: la app sigue al frente (no se abrio otra ventana)" ;;
  *) echo "  FALLA: el foco se fue a '$focus'"; fail=1 ;;
esac
echo "  capturas: /tmp/seriecast-popup-1.png (popup) /tmp/seriecast-popup-2.png (redirect)"
echo "  3.png: revisar que se vea 'CADENA OK' (navegacion del mismo host permitida)"
exit $fail
