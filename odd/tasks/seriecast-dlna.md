# SerieCast — descarga de episodios + servidor DLNA/UPnP para TV antiguo

App Android (Kotlin, sin Google Play Services) que descarga un episodio a almacenamiento local
y lo publica como `MediaServer:1` UPnP para que un televisor de la LAN lo reproduzca sin internet.

## Specs

**S1 — Sin dependencia de Google Play Services.**
> "**Sin soporte de Google Cast SDK** (los servicios de Google pueden estar incompletos en este dispositivo). La app debe funcionar **sin depender de Google Play Services**."

Ningún artefacto `com.google.android.gms` / Cast SDK en las dependencias. Descubrimiento y
transporte por SSDP + HTTP propios.

**S2 — Salida MP4 H.264 + AAC.**
> "Formato de salida: **MP4 (H.264 + AAC)**, para máxima compatibilidad con televisores antiguos. Usar ffmpeg para remux si es necesario."

El contenedor final es MP4 con pista de video AVC y audio AAC. El remux usa `MediaExtractor` +
`MediaMuxer` del framework (no `ffmpeg-kit`, retirado de Maven Central). Si la fuente ya es MP4
progresivo con AVC/AAC, no se remuxea.

**S3 — Nombre de archivo legible.**
> "Almacenar en una carpeta dedicada visible (ej. `/Movies/Doramas/`), con nombre de archivo legible (serie + temporada + episodio)."

Nombre normalizado `Serie - SxxExx - Titulo.mp4` y carpeta dedicada bajo el directorio de películas
externo de la app (`Android/data/<pkg>/files/Movies/Doramas`), con publicación opcional a
`MediaStore` (`Movies/Doramas`) para que sea visible en el explorador de archivos y la galería.

**S4 — Captura de la URL del stream.**
> "implementar un **WebView oculto** o **Playwright for Android** (si es viable) que cargue la página del episodio, capture el request de red del stream `.m3u8` o `.mp4` y lo pase a yt-dlp."

Implementado con `WebViewClient.shouldInterceptRequest` + `onLoadResource`. Se capturan candidatos
`.m3u8`, `.mp4`, `.mkv`, `.ts` y `.mpd`, junto con el `Referer` y la cookie de sesión de la página.
Playwright queda descartado: no existe wheel para Android arm64.

**S5 — Descarga manual como fallback siempre disponible.**
> "Alternativa más simple (si Playwright es inviable): permitir al usuario **pegar manualmente la URL del stream** (obtenida por el usuario vía otras herramientas), y la app solo se encarga de la descarga con yt-dlp."

Campo de texto para pegar la URL, con `Referer`/`User-Agent`/`Cookie` opcionales.

**S6 — Servidor DLNA con descubrimiento SSDP.**
> "Implementar un **servidor HTTP local** que sirva los archivos de video almacenados. Implementar **descubrimiento SSDP** para que el televisor detecte el teléfono como un renderer DLNA. **No usar Google Cast SDK**. Usar una librería DLNA/UPnP pura para Android (ej. **Cling** o **jUPnP**)."

El teléfono publica `urn:schemas-upnp-org:device:MediaServer:1` (no un renderer: el TV es el player),
con `ContentDirectory:1` + `ConnectionManager:1`, responder SSDP a `M-SEARCH` y `NOTIFY ssdp:alive`.
Cling y jUPnP quedan descartados por peso/binarios abandonados y choque de `javax.servlet` en AGP 8;
la implementación es UPnP mínima propia (SSDP + descripción + SOAP + HTTP con `Range`).

**S7 — Servidor controlable y visible.**
> "El servidor debe iniciarse con un botón en la app, mostrar la IP local del teléfono y el puerto, y mantenerse activo mientras la app esté en primer plano o con un foreground service."

Botón de inicio/parada, IP:puerto en pantalla, `foreground service` con notificación persistente.

**S8 — Seek y compatibilidad de reproducción.**
El servidor HTTP soporta `GET`, `HEAD` y `Range: bytes=` (206 Partial Content, 416 fuera de rango)
y anuncia `DLNA.ORG_OP=01` para que el TV pueda pausar y buscar dentro del episodio.

**S9 — Pantalla principal con la biblioteca.**
> "Pantalla principal: lista de videos descargados (con miniatura, título, tamaño)."

Lista con miniatura (frame extraído con `MediaMetadataRetriever`), título y tamaño.

**S10 — Instrucciones en pantalla.**
> "Instrucciones claras para el usuario: "En tu televisor, abre la sección DLNA/Red y busca el nombre de tu teléfono"."

Texto exacto presente en la pantalla de guía y en la pantalla del servidor.

**S11 — Manejo de errores.**
> "Manejo de errores: red no disponible, archivo no encontrado, televisor no descubre el servidor."

(i) sin red o sin Wi-Fi: el arranque del servidor se rechaza con motivo; (ii) archivo ausente en el
momento del `GET`: HTTP 404 y entrada retirada de la biblioteca; (iii) TV no descubre: autodiagnóstico
que consulta la propia `description.xml`, verifica el bind de SSDP y explica aislamiento de AP,
red de invitados y firewall.

**S12 — Instrucciones de compilación y despliegue.**
> "Instrucciones de compilación y despliegue en el Infinix Note 8."

`docs/BUILD.md` con requisitos (JDK 17, Android SDK 35, Python 3.9+ para Chaquopy), comandos Gradle,
firma, `adb install` y checklist de red local.

## Tasks

| ID | Specs | Ruta | Commit |
|----|-------|------|--------|
| T1 | S1, S2, S3 | Gradle + manifest + storage/MediaStore | `feat(build): scaffold Kotlin/Compose app with Chaquopy` |
| T2 | S6, S8 | Paquete `dlna` (SSDP + HTTP + SOAP + DIDL) | `feat(dlna): minimal UPnP MediaServer with SSDP and Range` |
| T3 | S4, S5, S2 | Paquete `download` (sniffer WebView, yt-dlp/Python, remux) | `feat(download): yt-dlp engine, WebView sniffer and MP4 remux` |
| T4 | S7, S9, S10, S11 | UI Compose + ViewModel + foreground service | `feat(ui): library, server control and troubleshooting guide` |
| T5 | S12 | `README.md`, `docs/BUILD.md`, `docs/TV-TROUBLESHOOTING.md` | `docs: build and deploy on Infinix Note 8` |

## Log

**L1 (pedido original, verbatim, recortado a decisiones):**
> "Desarrollar una app Android que permita al usuario descargar un episodio desde una página web de streaming (ej. Doramasflix), almacenarlo localmente, y luego **compartirlo como servidor DLNA** para que un televisor antiguo (...) en la misma red local pueda reproducirlo sin internet."
> "**Teléfono**: Infinix Note 8. SoC MediaTek Helio G80, 6GB RAM, 128GB almacenamiento, Android 10. **Sin soporte de Google Cast SDK**"
> "**Televisor**: Modelo antiguo. **Asumir que no tiene app nativa ni navegador moderno**. El televisor solo puede recibir streams vía **DLNA/UPnP** desde la red local (protocolo SSDP + HTTP). No se desarrollará ninguna app para el televisor."
> "**Lenguaje**: Kotlin." / "**Descarga**: Chaquopy + yt-dlp (Python) + ffmpeg (Android build)." / "**Servidor DLNA**: Cling (org.fourthline.cling) o jUPnP." / "**UI**: Jetpack Compose (o XML si prefieres simplicidad)."

**L2 (decisiones técnicas del 2025, derivadas de L1, no requisitos nuevos):**
- D1: se implementa `MediaServer:1` (DMS) y no un `MediaRenderer:1`, porque el TV antiguo no tiene
  controlador externo: navega y reproduce por sí mismo. "renderer DLNA" en L1 se interpreta como
  "el TV lo ve y lo reproduce".
- D2: Playwright descartado (sin wheel Android arm64). El "WebView oculto" de L1 se implementa con
  `shouldInterceptRequest`, que es el único punto donde el WebView expone las sub-requests de media.
- D3: `ffmpeg-kit` retirado de Maven Central en 2025 → remux con `MediaExtractor`/`MediaMuxer`.
  Se pierde soporte de codecs exóticos (AC3/DTS en MKV); documentado como limitación.
- D4: Cling/jUPnP descartados: Cling 2.1.2 (2014) arrastra `javax.servlet` y rompe el merge de
  manifests con AGP 8; jUPnP 3.x requiere Jakarta, no corre limpio en Android. UPnP mínimo propio
  (~700 líneas) elimina los dos riesgos y el peso.
- D5: la biblioteca vive en `getExternalFilesDir(Movies)/Doramas` (ruta real, sin permisos, amigable
  para Python/yt-dlp). La publicación a `MediaStore`/`Movies/Doramas` (S3) es una acción explícita
  para no duplicar 700 MB por episodio de forma silenciosa.
- D6: sin commits en este entorno: no es un repositorio git y la política es no commitear sin pedido
  explícito. Las rutas de commit quedan registradas arriba para cuando exista el repo.
- D7: sin JDK/Android SDK/Gradle en esta máquina → no hay verificación de compilación. Riesgo
  declarado, no mitigado.
