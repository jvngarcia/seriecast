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
    └── ui/                    # Compose: biblioteca, descarga, guía
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
2. **Descargar.** Abrí la página del episodio en el navegador integrado, tocá play y elegí el
   `.m3u8` capturado (o pegá la URL a mano). Completá serie/temporada/episodio y descargá.
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
- **Captura de segmentos**: si el reproductor usa MSE puro con `blob:`, sólo se ven los `.m3u8` y
  segmentos; nunca la URL del "video completo", que no existe.
- **AP isolation**: es la causa #1 de "el televisor no lo ve"; no se puede arreglar desde la app.
  Ver [`docs/TV-TROUBLESHOOTING.md`](docs/TV-TROUBLESHOOTING.md).

## Estado de verificación

Este árbol se escribió sin cadena de build disponible (no había JDK, Android SDK ni Gradle en la
máquina de origen), así que **no hay compilación ni ejecución verificada**. Los puntos de riesgo
concretos están listados al final de `docs/BUILD.md`.
