# Arnés de verificación en runtime

Scripts usados para verificar SerieCast en un emulador Android real. No son tests automatizados del
proyecto (`app/src/test/` cubre el core con JUnit); son verificación end-to-end por `adb`, con
`uiautomator` para manejar la UI.

## Prerrequisitos

```bash
export HOME=/home/angel
export JAVA_HOME="$HOME/.local/opt/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug -Pchaquopy.buildPython="$HOME/.local/opt/py311/bin/python3"

# AVD con imagen AOSP (sin Google APIs), API 29
"$ANDROID_HOME/emulator/emulator" -avd seriecast29 -no-window -no-audio -no-boot-anim \
  -no-snapshot -no-metrics -gpu swiftshader_indirect &
```

## Orden de uso

| Script | Qué verifica |
|---|---|
| `seriecast-verify3.sh` | Instala, lanza, comprueba el arranque de Chaquopy, la versión de yt-dlp leída del APK, el `foreground service` y `description.xml` + SOAP `Browse` por `adb forward`. |
| `seriecast-download-test2.sh` | Pipeline de descarga: pega una URL en el campo del stream, toca "Descargar", espera el archivo y lo sirve con `Range`. |
| `seriecast-remux-test.sh` | Descarga un master HLS (que yt-dlp baja como MPEG-TS) y verifica que `Mp4Remuxer` lo convierta a un MP4 real (`ftyp`) sin perder el archivo. |
| `seriecast-sniffer-test.sh` | §S4: que `shouldInterceptRequest` capture el `.m3u8`, aparezca en "Streams detectados" y `Usar` copie URL y Referer. |
| `seriecast-popup-guard-test.sh` | §S1-S2: un `target="_blank"` clickeado de verdad no abre ventana y queda contado; `location.href` a otro dominio se rechaza y se cuenta; una navegación del mismo host sigue funcionando. Sirve las páginas de prueba desde el host del emulador. |

## Trampas del entorno (ya resueltas en los scripts)

- La pantalla del AVD por defecto es **320x640**: hay que bajar la densidad para que entre el
  contenido y los `swipe` tienen que caer dentro de la pantalla.
  `adb shell wm density 100` y revertir con `adb shell wm density reset`.
- `uiautomator` matchea por **substring**, así que buscar `"URL del stream"` enfoca el campo del
  navegador porque el título `"1. Capturar la URL del stream"` también matchea. Usar el label exacto.
- La barra inferior tiene que anclarse con `"Ayuda"`: en la pantalla de descarga también hay un botón
  con el texto exacto `"Descargar"`.
- Un `WebView` consume los gestos: los `swipe` que empiezan dentro de él no scrollean el contenedor
  padre.
- El **popup blocker de Chromium ignora `window.open` sin gesto del usuario**: para probar el
  bloqueo hay que tocar un enlace real. Un `window.open` desde un `setTimeout` devuelve `null` sin
  llegar nunca a `onCreateWindow`, así que no cuenta.
- **`10.0.2.2` es el alias del host sólo dentro del emulador.** Para controlar el servidor de prueba
  desde el host se usa `127.0.0.1`; `curl http://10.0.2.2:…` desde WSL no conecta.
- `uiautomator dump` falla mientras la UI no está idle y deja el XML anterior. Borrar
  `/sdcard/ui.xml` antes de dumpear, o el arnés lee una pantalla que ya no está.
- Deshabilitar los IME antes de manejar campos:
  `for i in $(adb shell ime list -s); do adb shell ime disable $i; done`.

## Dispositivo físico

**No funciona desde WSL2**: no expone USB (`/sys/bus/usb/devices` no existe), así que `adb` no ve el
teléfono. Para el Note 8 hace falta `usbipd-win` o correr el servidor `adb` en Windows y apuntar
`ADB_SERVER_SOCKET` a él. Detalle en `odd/tasks/seriecast-dlna.md`, entrada L5.
