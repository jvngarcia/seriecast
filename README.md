# SerieCast

Descarga un episodio desde una página de streaming, lo normaliza a **MP4 (H.264 + AAC)** y lo publica
como **servidor DLNA/UPnP** para que un televisor antiguo lo reproduzca desde la red local, sin
internet y sin Google Play Services.

Teléfono objetivo: Infinix Note 8 (Helio G80, Android 10). El televisor solo necesita soportar
DLNA/UPnP: no se instala nada en él.

## Cómo funciona

```
┌──────────────── teléfono (SerieCast) ────────────────┐        ┌──── televisor ────┐
│                                                      │        │                   │
│  WebView sniffer ──► .m3u8/.mp4 ──► yt-dlp (Python)  │        │                   │
│         │                            │               │        │                   │
│         │                            ▼               │        │                   │
│         │                    MediaExtractor/Muxer    │        │                   │
│         │                    (remux TS/MKV → MP4)    │        │                   │
│         ▼                            │               │        │                   │
│   Referer/Cookie                     ▼               │        │                   │
│                              Movies/Doramas/*.mp4   │        │                   │
│                                      │               │        │                   │
│   SSDP (239.255.255.250:1900) ◄──────┴──► HTTP :8080 │◄──SSDP─┤  descubre         │
│   ContentDirectory:1 (SOAP)                          │◄──SOAP─┤  navega           │
│   MediaServer:1 (DIDL-Lite)                          │◄──HTTP─┤  reproduce (Range)│
└──────────────────────────────────────────────────────┘        └───────────────────┘
```

Decisiones que no son obvias desde el pedido original:

| Tema | Decisión | Por qué |
|---|---|---|
| Rol DLNA | El teléfono publica `MediaServer:1` (DMS), no un renderer | El televisor antiguo no tiene controlador: navega y reproduce solo. Un `MediaRenderer:1` no le sirve de nada |
| Librería DLNA | UPnP mínimo propio (SSDP + SOAP + HTTP) en vez de Cling/jUPnP | Cling 2.1.2 es de 2014 y arrastra `javax.servlet` (rompe el manifest merge de AGP 8); jUPnP 3.x exige Jakarta y no corre limpio en Android |
| Remux | `MediaExtractor` + `MediaMuxer` del framework | `ffmpeg-kit` fue retirado y sus artefactos Maven ya no existen. Cero APK extra |
| Captura de stream | WebView con `shouldInterceptRequest` | Playwright no tiene wheel para Android arm64. El hook del WebView sí ve los XHR de `hls.js` |
| Storage | `Android/data/<pkg>/files/Movies/Doramas` | Ruta real (Python escribe directo), sin permisos en ninguna versión, sobrevive reinicios. La copia a `Movies/Doramas` público es opt-in |

## Mapa del código

```
app/src/main/
├── python/
│   ├── downloader.py          # puente yt-dlp: download(), probe(), version()
│   └── requirements.txt       # deps de Python (espejo de app/build.gradle.kts)
└── java/com/seriecast/app/
    ├── SerieCastApp.kt        # arranque de Chaquopy + canal de notificaciones
    ├── data/
    │   ├── Storage.kt         # carpetas, nombres legibles, MediaStore
    │   ├── MediaLibrary.kt    # escaneo de la biblioteca local
    │   ├── VideoMetadata.kt   # duración y miniatura
    │   └── NetworkStatus.kt   # ¿hay internet para descargar?
    ├── dlna/
    │   ├── LanInfo.kt         # IP + NetworkInterface del Wi-Fi activo
    │   ├── SsdpResponder.kt   # M-SEARCH + ssdp:alive/byebye
    │   ├── UpnpHttpServer.kt  # HTTP/1.1 con Range (206/416)
    │   ├── Descriptions.kt    # device description + SCPDs + landing page
    │   ├── DlnaServer.kt      # SOAP: ContentDirectory + ConnectionManager, DIDL-Lite
    │   └── MediaEntry.kt      # modelo publicado
    ├── download/
    │   ├── YtDlpEngine.kt     # DownloadEngine vía Chaquopy
    │   ├── downloader.py ────► (arriba)
    │   ├── ContainerSniffer.kt# detecta MP4/TS/MKV por magic bytes
    │   ├── Mp4Remuxer.kt      # TS/MKV → MP4 sin ffmpeg
    │   └── StreamSniffer.kt   # URLs de media observadas por el WebView
    ├── service/
    │   ├── DlnaController.kt  # dueño del servidor, estado observable
    │   └── DlnaService.kt     # foreground service + notificación
    └── ui/                    # Compose: biblioteca, captura, descarga, guía
```

## Compilar

Ver [`docs/BUILD.md`](docs/BUILD.md). Resumen:

```bash
# Requiere JDK 17, Android SDK 35 y un CPython 3.9+ para que Chaquopy corra pip
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Uso

1. **Videos → Iniciar servidor DLNA.** Aparece `http://192.168.x.x:8080`.
2. **Descargar → Capturar la URL del stream.** Si no sabés la dirección del episodio, escribí el
   nombre de la serie en **Dirección o búsqueda** y tocá **Abrir navegador**: esa barra funciona como
   la de un navegador, así que lo que no es una dirección se busca en Google dentro de la app y
   podés tocar un resultado sin salir a otro lado. La captura ocupa toda la pantalla, así que el
   reproductor se puede usar. Dentro de la página tocá el servidor y después play; cuando el stream
   aparezca en la franja de abajo, tocá **Usar** y volvés al formulario con la URL, el Referer del
   reproductor y la cookie ya cargados. La captura bloquea las ventanas emergentes y las
   redirecciones a otro dominio de los anuncios, y muestra cuántas bloqueó; mientras la página en
   pantalla es el buscador, tocar un resultado sí se permite. Si el bloqueo se comió un servidor que
   sí querías, pegá su dirección en la barra de arriba y tocá **Ir**. También podés pegar la URL del
   `.m3u8` a mano. Completá serie/temporada/episodio y descargá.
3. En el televisor: **Fuente / Red → DLNA** y buscá `SerieCast (<modelo del teléfono>)`.
   La frase textual que muestra la app es: *"En tu televisor, abre la sección DLNA/Red y busca el
   nombre de tu teléfono"*.

## Limitaciones conocidas (declaradas, no ocultas)

- **Codecs**: el televisor tiene que decodificar H.264 + AAC. El remux no convierte AC3/DTS/Opus a
  AAC porque `MediaMuxer` no transcodifica; si la fuente no es MP4-compatible se conserva el archivo
  original y la app lo avisa.
- **Progreso de descarga**: vive en el `ViewModel`. Si el sistema mata el proceso, la descarga se
  pierde (los `.part` se limpian al siguiente arranque).
- **Sitios con DRM (Widevine)**: imposible. Ni yt-dlp ni esta app pueden descargar contenido
  protegido.
- **User-Agent del descargador**: la app le manda a yt-dlp el mismo User-Agent del navegador
  integrado. No es cosmético: algunos CDN responden `404` a cualquier UA que no sea de navegador
  móvil (medido en SprintCDN: el mismo `.m3u8` firmado da `200` con UA de Android y `404` con el UA
  de escritorio que yt-dlp usa por defecto).
- **Captura de segmentos**: si el reproductor usa MSE puro con `blob:`, sólo se ven los `.m3u8` y
  segmentos; nunca la URL del "video completo", que no existe.
- **AP isolation**: es la causa #1 de "el televisor no lo ve"; no se puede arreglar desde la app.
  Ver [`docs/TV-TROUBLESHOOTING.md`](docs/TV-TROUBLESHOOTING.md).
- **Popups bloqueados sin destino**: el `WebView` no entrega la URL del `onCreateWindow`, así que el
  contador dice cuántas ventanas se bloquearon pero no a dónde apuntaban. Las redirecciones sí
  muestran el host.

## Estado de verificación

Verificado en un emulador **Android 10 (API 29), imagen AOSP sin Google Play Services** (0 paquetes
`com.google.android.gms`), que es el entorno objetivo de la spec.

| Qué | Estado | Evidencia |
|---|---|---|
| Compilación | ✅ | `:app:assembleDebug` verde; APK de 66.383.143 bytes con `libpython3.11.so` en `arm64-v8a`, `armeabi-v7a` y `x86_64` |
| Tests JVM | ✅ | **53/53**, 0 fallos — SSDP, SOAP `Browse`, DIDL, `Range` 206/416 sobre HTTP real, detección de contenedor, guardas del remux y el guard de publicidad (`NavigationGuard`) |
| Python embebido | ✅ | `Chaquopy started: 3.11.10`; la UI muestra `yt-dlp 2025.01.15` leído del APK; `yt_dlp/`, `certifi/`, `Cryptodome/` dentro de `requirements-common.imy` |
| Servidor DLNA | ✅ | `description.xml` 200 con `MediaServer:1` y header `EXT:`; `Browse` 200 con DIDL; `isForeground=true foregroundId=4242` |
| Descarga real desde la UI | ✅ | `Listo: sample 5s.mp4`, 2.848.208 bytes, magic `ftypisom` |
| Remux TS → MP4 | ✅ | HLS real: TS de 39.392.016 bytes (`0x47`) → MP4 de 36.083.089 bytes (`ftypmp42`), duración `00:10:34.600` leída por `MediaMetadataRetriever` |
| Servir a un televisor | ✅ | `GET` 200 con el tamaño exacto; `Range 0-99`, `1000-`, sufijo `-16` y `1000000-1000099` → 206 con `Content-Range` correcto; `416` con `bytes */N`; `HEAD`; `404` |
| Sniffer de WebView (§S4) | ✅ | `shouldInterceptRequest` capturó el `.m3u8`, apareció como `HLS · test-streams.mux.dev`, y `Usar` copió URL y Referer al formulario. Verificado de nuevo en el Note 8 contra un agregador real (`tudorama.com`, servidor earnvids): captura a pantalla completa, `HLS · callistanise.com · master.m3u8` y `Usar` cargó `Referer: https://callistanise.com/embed/v7pml7nsxiap` (el reproductor, no la página del agregador) |
| Guard de publicidad en la captura | ✅ | En el emulador API 29, con una página servida desde el host: un `target="_blank"` clickeado no abrió ventana y quedó contado (`Bloqueado: 1 ventana(s)`), `location.href` a otro dominio fue rechazado (`1 redirección(es) · último: ads.example.net`) con la app todavía al frente, y una navegación del mismo host siguió funcionando (`CADENA OK`). Arnés: `scripts/verification/seriecast-popup-guard-test.sh` |
| Omnibox: buscar sin salir de la app | ✅ | En el Note 8: `el flash temporada 3` en **Dirección o búsqueda** abrió resultados de Google dentro de la captura; tocar un resultado cargó HobbyConsolas **sin ningún bloqueo contado**; `127.0.0.1:8099` se resolvió a `https://127.0.0.1:8099/` (`ERR_SSL_PROTOCOL_ERROR` contra un servidor HTTP, o sea que antepuso el esquema); y desde una página que no es el buscador el popup y el redirect siguieron rechazados y contados (`1 ventana(s) · 1 redirección(es) · último: ads.example.net`). 71 tests JVM en verde |
| **Descubrimiento por un televisor físico** | ❌ **sin verificar** | requiere el TV en la misma LAN. Depende del router y del firmware; usar el autodiagnóstico y la sonda `M-SEARCH` de `docs/TV-TROUBLESHOOTING.md` |

Cadena de build usada: JDK 17.0.20.1 + Gradle 8.9 + Android SDK 35 + CPython 3.11.17.

### Defectos que solo aparecieron al ejecutar

Compilar en verde no probaba nada: la primera corrida en el dispositivo encontró siete defectos,
todos corregidos y con test de regresión donde era posible.

| # | Defecto | Cómo se manifiesta |
|---|---|---|
| 1 | `python {}` no existe en el DSL de Chaquopy para Kotlin DSL | build falla con 4 `Unresolved reference` |
| 2 | `org.gradle.parallel=true` + KGP 2.0.21 | `compileDebugKotlin` muere con *state lock* |
| 3 | Chaquopy instala con `--no-deps` y yt-dlp declara sus deps en el extra `default` | el APK sale sin `certifi` ni `pycryptodomex`: HLS con AES-128 roto |
| 4 | `callAttr("version")` sobre `sys.version`, que es un atributo | `pythonAvailable=false` aunque Python arrancó |
| 5 | `PyObject.getAttr` no existe | error de compilación (se usó `platform.python_version()`) |
| 6 | Header `EXT` escrito sin los dos puntos | `curl` aborta la respuesta entera (*Header without colon*): el servidor parecía no responder |
| 7 | Destino del remux igual al archivo de origen | **pérdida de datos**: `MediaMuxer` truncaba el archivo que se estaba leyendo y el `catch` borraba la única copia |
