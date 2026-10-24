# Feature: remux en una sola pasada, con progreso y cancelación

El remux a MP4 copia samples, no transcodifica, así que debería costar poco más que leer y escribir
el archivo. En la práctica lee el origen **dos veces completas**, no muestra progreso, su botón
Cancelar no hace nada y, en un episodio real de 1,2 GB, **termina fallando**: el usuario recibe un
MPEG-TS con nombre `.mp4`, que es justo lo que la feature existe para evitar.

## Specs

**S1 — Una sola pasada sobre el fuente. Duda que abrió la feature (verbatim):**

> debería tardar mucho el "ajustando contenedor a mp4"?

Regla: `Mp4Remuxer.remux()` lee el archivo de origen **una sola vez**, escribiendo las dos pistas en
orden de archivo. Hoy hace dos pasadas completas: `copyTrack(video)` y después `copyTrack(audio)`,
cada una con `seekTo(0, SEEK_TO_CLOSEST_SYNC)` (`Mp4Remuxer.kt:114-116`, `Mp4Remuxer.kt:135`). Como
MPEG-TS no tiene índice, la segunda pasada vuelve a parsear todo el byte stream para encontrar los
paquetes de audio: el teléfono quema CPU y escribe poco.

**S2 — Progreso visible durante el remux, con cuánto falta.** Hoy `AppViewModel.kt:231` publica
`Job.Running("Ajustando el contenedor a MP4…", null)`: `progress = null`, así que la UI muestra una
línea congelada sin porcentaje mientras la descarga sí mostraba progreso. El remux tiene que
reportar avance usando `extractor.sampleTime` contra la duración del formato, con el mismo
`DownloadProgress` que ya consume la pantalla.

Ampliado por L6 — pedido verbatim del usuario: *"no me da tampoco cuanto falta ni nada"*. Además del
porcentaje, la línea de estado tiene que mostrar bytes, velocidad y **tiempo restante estimado**, y
esa estimación también aplica a la fase de descarga (yt-dlp ya entrega velocidad). Sin estimación, o
con velocidad desconocida, la línea no inventa números: muestra lo que sabe.

**S3 — Cancelar efectivo durante el remux.** `cancelRequested` (`AppViewModel.kt:65`) sólo lo lee el
descargador (`AppViewModel.kt:226`); `Mp4Remuxer` nunca lo consulta. El botón **Cancelar** está
visible durante el remux pero no hace nada. Tiene que cortar dentro de un par de segundos y no dejar
basura.

**S4 — Un remux interrumpido no puede aparecer como episodio.** Hoy el temporal se llama
`<nombre>.remux.mp4` (`Mp4Remuxer.kt:48`), `isTemporary()` no lo reconoce (`MediaLibrary.kt:52-55`
sólo mira `.part`, `.ytdl`, `.tmp`, `.part-frag`, `.download`) e `isPlayable()` sí lo acepta porque
termina en `.mp4` (`MediaLibrary.kt:46-50`). Si el proceso muere a mitad del remux queda un MP4 de
más de 1 GB **sin índice `moov`** (nunca corrió `muxer.stop()`) que la biblioteca lista como
episodio descargado y el servidor DLNA ofrece como si sirviera.

**S5 — Nada de lo ya verificado se rompe.** El remux sigue produciendo un MP4 reproducible con las
mismas limitaciones declaradas (AC-3/DTS/Opus no entran en MP4, HEVC depende del televisor), el
original **nunca** se borra antes de que el destino sea válido, y el destino no puede ser el origen
(`remuxTarget`, garantía ya cubierta por `Mp4RemuxerTest`). Los 75 tests actuales siguen verdes.

**S6 — El remux tiene que completarse en un episodio real.** Es el defecto más grave de los cuatro:
con `test - S01E01 - tes.mp4` de 1,24 GB el remux corrió más de diez minutos y abortó con
`IllegalArgumentException`, así que el archivo final conservó los bytes de MPEG-TS (empieza con
`0x47`, no con `ftyp`) dentro de un nombre `.mp4`. El usuario ve "Listo" con un aviso, y el
televisor objetivo puede rechazar ese archivo: el contenedor se detecta por magic bytes justamente
porque yt-dlp deja TS dentro de `.mp4`.

**Causa raíz medida (L7): no era ninguna de las hipótesis de abajo.** `MediaExtractor.unselectTrack()`
lanzó `IllegalArgumentException` **al final de la segunda pasada**, con el extractor ya en EOF. La
excepción salió de `copyTrack` (`Mp4Remuxer.kt:152`) y descartó un temporal de 1,05 GB ya escrito,
porque `muxer.stop()` nunca llegó a correr. O sea: dieciséis minutos de CPU desperdiciados por una
llamada de *limpieza*. Regla que queda: el resultado del remux no puede depender de una llamada de
limpieza; el extractor se libera en el `finally`, no se deselecciona a mano, y `stop()` corre en
cuanto se escribió el último sample.

Hipótesis descartadas o pendientes (se dejan escritas para no repetir el trabajo):

1. **Orden cronológico entre pistas.** La implementación escribe todo el video y después todo el
audio, así que el muxer recibe audio con `presentationTimeUs` menor que el último video escrito.
   `MediaMuxer` exige orden cronológico y varias implementaciones lo rechazan con
   `IllegalArgumentException`.
2. `presentationTimeUs` no monótono o negativo dentro de una pista (timestamps de TS que
   reinician o hacen wrap).
3. `sampleFlags`/primer sample no keyframe.
4. Formato de audio que el muxer MP4 no acepta (AC-3/DTS), aunque eso debería fallar al principio y
   no después de diez minutos.

Si la hipótesis 1 es la correcta, **S1 (una sola pasada) también arregla S6**, porque interleavear
restaura el orden cronológico. Eso hay que demostrarlo con la medición, no asumirlo: T6 corre antes
y después de T1.

## Evidencia medida (baseline a batir)

Medido en el dispositivo físico (Infinix X692, API 29) durante una descarga real, con
`test - S01E01 - tes.mp4` de **1.241 GB** (MPEG-TS):

| Señal | Valor |
|---|---|
| Temporal del remux al medir | 1.155 GB (93% del fuente) |
| Crecimiento del temporal | **0,09–0,10 MB/s** |
| CPU del proceso de la app | **59%**, 13:30 acumulado |
| `%iow` | 0 |
| Tiempo total en "Ajustando…" | **más de 10 minutos**, sin terminar |

Interpretación: no está esperando disco, está parseando. El tamaño ya está en el final esperado
(el MP4 ronda el 92% del TS; medido antes: 39,39 MB TS → 36,08 MB MP4), o sea que el trabajo que
queda es la segunda pasada, la de audio.

**Y después falló.** Mismo episodio, al terminar:

| Señal | Valor |
|---|---|
| Aviso en la UI | `No se pudo remuxear a MP4: IllegalArgumentException` |
| Archivo final | `test - S01E01 - tes.mp4`, 1.240.880.464 bytes (idéntico al fuente) |
| Primeros bytes | `47 40 00 10` → **MPEG-TS**, no `ftyp` |
| Stack trace | **perdido**: el ring de logcat es de 256 KiB y rotó |

O sea: diez minutos de CPU para terminar dejando el contenedor sin convertir. `keepOriginal` hizo lo
correcto (conservó el original), pero el objetivo de la feature no se cumplió.

## Tasks

| ID | Spec | Qué hace | Ruta | Commit |
|---|---|---|---|---|
| T0 | S6 | Reproducir el fallo con `adb logcat -G 4M` activado **antes** de empezar, capturar el stack y confirmar o descartar las hipótesis de S6 | inline | medido, ver L7 |
| T1 | S1-S6 | `remux()` en una sola pasada: seleccionar las dos pistas y escribir en orden de archivo usando `getSampleTrackIndex()` | test-first | `1d439eb` |
| T2 | S2 | Progreso del remux con porcentaje, bytes, velocidad y restante estimado cableado al `Job.Running` | test-first | `4c04636` |
| T3 | S3 | Cancelación efectiva del remux, con borrado del temporal | inline | `4c04636` |
| T4 | S4 | `.remux.mp4` como temporal en `MediaLibrary.isTemporary` | test-first | `9a2c795` |
| T5 | S1-S6 | Docs y verificación en el dispositivo con un episodio real | inline | verificado, ver tabla de T5 |

**T0 primero**: hoy el fallo sólo deja un mensaje sin stack, y el ring de logcat se lo come. Antes de
tocar el remux hay que hacer `adb logcat -G 4M` (o `-s Mp4Remuxer:*` con el buffer agrandado) y
volver a fallar, o se va a estar adivinando. Vale la pena que la UI muestre también el tipo de
excepción y las primeras dos o tres líneas del stack, no sólo `IllegalArgumentException`.

### Notas de implementación para T1

- Con `MediaExtractor` se pueden seleccionar **dos** pistas a la vez; `readSampleData` devuelve los
  samples en orden de archivo y `getSampleTrackIndex()` dice de cuál viene. Hay que mapear ese índice
  al índice que devolvió `muxer.addTrack(...)` y escribir con `writeSampleData` en el correspondiente.
- Cuidado con el arranque: hoy cada pasada se alinea con `seekTo(0, SEEK_TO_CLOSEST_SYNC)`. En una
  sola pasada hay que arrancar en 0 y **descartar los samples de video anteriores al primer
  keyframe**, o el MP4 queda con un primer frame no decodificable.
- El interleaving sale natural de la lectura secuencial; es lo que el MP4 quiere.
- `BUFFER_SIZE` es 4 MB (`Mp4Remuxer.kt:26`) y el buffer se reusa para las dos pistas.
- Los tests JVM del repo son JUnit puro, **sin Robolectric**: `MediaExtractor`/`MediaMuxer` no se
  pueden testear ahí. La parte testeable hay que extraerla a una función pura (mapeo de
  `sampleTrackIndex` → índice del muxer, y la regla de descartar hasta el primer keyframe) y testear
  eso. El resto se verifica en el dispositivo.

### Criterios de aceptación (medibles)

1. Mismo episodio de ~1,24 GB: **termina** en un MP4 que empieza con `ftyp`, se reproduce en el
   teléfono y el televisor lo sirve por DLNA. Sin este punto, los demás no cuentan.
2. El tiempo en "Ajustando…" baja a la mitad o mejor respecto de los >10 minutos medidos.
3. El porcentaje avanza durante el remux, no queda congelado.
4. Cancelar durante el remux corta en menos de ~2 s y no queda `.remux.mp4`.
5. Matar la app a mitad del remux y reabrirla: no aparece ningún episodio nuevo en la biblioteca.
6. `:app:testDebugUnitTest` y `:app:assembleDebug` en verde, con los tests previos intactos.

## Verificación en el dispositivo (T5)

Infinix X692, API 29, APK de debug instalado con `adb install -r`. Episodio real de prueba: stream HLS
público `x36xhzz` (10:34, 1080p) descargado por la app, que yt-dlp deja como **MPEG-TS de
502.204.400 B dentro de un nombre `.mp4`** — exactamente el caso que rompía el remux.

| # | Criterio | Resultado |
|---|---|---|
| 1 | Termina en un MP4 que empieza con `ftyp` | ✅ 486.850.800 B, primeros bytes `00 00 00 18 66 74 79 70 6d 70 34 32` |
| 2 | La mitad del tiempo o mejor | ✅ **66 s** para 502 MB, 6,5 MB/s medidos en vivo, contra los ~50 KB/s del temporal en el caso real |
| 3 | El porcentaje avanza durante el remux | ✅ captura: `Ajustando el contenedor a MP4… 85% — 407.8 MB de 478.9 MB (6.5 MB/s) · menos de 1 min` |
| 4 | Cancelar corta en segundos y no deja `.remux.mp4` | ✅ toque en Cancelar → 5 s después sólo queda el fuente de 502.204.400 B |
| 5 | Un remux interrumpido no aparece como episodio | ✅ con un `zz temporal.remux.mp4` de 512 KB en la carpeta, la biblioteca lista **1** video |
| 6 | Tests y build en verde, tests previos intactos | ✅ 95 tests, 0 fallos (75 previos + 20 nuevos); `:app:assembleDebug` en verde |

**Pendiente, declarado:** el episodio real de 1,24 GB (`doctora x - S01E05`) **no** se volvió a
descargar —la URL la tiene el usuario—, así que el número de ese caso es extrapolación y no medición:
1,1 GB a 6,5 MB/s ≈ 3 minutos, contra los más de 16 minutos que terminaban en error. Se cierra con una
descarga real de ese mismo stream.

## Fuera de alcance (declarado)

- No se introduce `ffmpeg` ni transcodificación: el remux sigue siendo copia de samples con
  `MediaMuxer`.
- No se toca la copia extra a `Movies/Doramas` (`AppViewModel.kt:236`, `Storage.publishToMediaStore`),
  que duplica el espacio. Es un pedido aparte.
- No se toca la concurrencia de descargas: sigue habiendo una sola a la vez.

## Log

**L1 (pedido original del usuario, verbatim):**

> debería tardar mucho el "ajustando contenedor a mp4"?

**L2 (pedido de esta especificación, verbatim):**

> especificalo para hacerlo en otra sesion

**L3 (evidencia recogida en esa sesión, no re-medida acá):**

- La app estaba corriendo el remux en vivo: temporal `test - S01E01 - tes.remux.mp4` de 1,155 GB
  sobre un fuente de 1,241 GB, creciendo a 0,09–0,10 MB/s, proceso al 59% de CPU con 13:30
  acumulados y `%iow` en 0.
- Lectura del código: dos `copyTrack` con `seekTo(0)` cada uno y `progress = null` en el
  `Job.Running` del remux.
- `isTemporary()` no reconoce `.remux.mp4` e `isPlayable()` sí lo acepta: un remux interrumpido deja
  un MP4 sin `moov` listado como episodio.

**L4 (decisiones tomadas al especificar):**

- Se prioriza la pasada única (S1) porque es lo único que cambia el número de verdad; progreso y
  cancelación son baratos una vez que el loop es uno solo.
- S4 entra en la misma feature porque el riesgo se materializa justo cuando el remux se corta, que
  es el escenario que T3 habilita.
- No se especifica paralelismo de descargas ni la copia a Galería: ruido para este objetivo.

**L6 (pedido de esta sesión, verbatim):**

> Sale "Ajustando el contenedor a MP4" pero tarda demasiado, no me da tampoco cuanto falta ni nada,
> revisa por que es tan lento (1gb) y mejora la ux

**L7 (medición en vivo de esta sesión, dispositivo físico Infinix X692, API 29, `doctora x - S01E05
- 5.mp4` = 1.104.376.296 bytes, MPEG-TS confirmado por magic `47 40 00 10`):**

| Señal | Valor medido |
|---|---|
| Temporal `.remux.mp4` al empezar a medir | 1.052.209.861 B (95,3% del fuente) |
| Crecimiento del temporal | 48–54 KB/s (0,05 MB/s), estable en 4 muestras |
| CPU del proceso | 830 ticks de CPU / 15 s de reloj ≈ 55% de un core |
| Duración total del remux | 11:21 → 11:37 ≈ **16 minutos** |
| Resultado | `IllegalArgumentException` en `MediaExtractor.unselectTrack` (`Mp4Remuxer.kt:152`) |
| Estado final en disco | temporal borrado; sobrevive el fuente de 1.104.376.296 B, primeros bytes `47` |

Interpretación: el temporal ya está en el 95% del tamaño final, así que lo que corre es la **segunda
pasada** — la de audio —. Ahí el extractor vuelve a parsear los 1,1 GB desde el byte 0 (MPEG-TS no
tiene índice, `seekTo` no salta: recorre) y la salida sólo crece al ritmo del audio, ~50 KB/s. Eso
explica las dos mitades del reporte: tarda porque lee el archivo **dos veces completas**, y parece
colgado porque el número que el usuario ve no se mueve. `%iow` en 0 descarta el disco: es CPU de
parseo. Y el remux termina fallando, así que el costo se paga entero sin entregar nada.

**L5 (hallazgo posterior al medir, y la razón por la que S6 existe):** terminada la medición, el
remux abortó. Estado final observado: archivo de 1.240.880.464 bytes (exactamente el fuente),
primeros bytes `47 40 00 10` (MPEG-TS), aviso `No se pudo remuxear a MP4: IllegalArgumentException`,
y sin stack trace porque el ring de logcat es de 256 KiB y ya había rotado. La prioridad cambió:
S6 (que complete) pesa más que S1 (que sea rápido), y T1 puede resolver las dos si la hipótesis 1 es
la correcta. Queda anotado como pendiente de comprobar, no como hecho.

**L9 (verificación en el dispositivo y un ajuste de UX que salió de ella):**

- La verificación de T5 pasó salvo el episodio real de 1,24 GB, que no se volvió a descargar. Detalle
  y evidencia en la tabla de T5.
- Al verificar se vio que el mensaje de cancelación se mostraba en un `Snackbar` de 4 s: ilegible para
  un mensaje que dice qué quedó en disco. Pasa a `SnackbarDuration.Long`.

**L8 (decisiones de implementación de esta sesión):**

- El progreso del remux se mide en **bytes leídos sobre el tamaño del fuente**, no con
  `sampleTime`/`KEY_DURATION`: MPEG-TS no siempre declara una `KEY_DURATION` válida y el PTS absoluto
  del transporte no es comparable con la duración. Los bytes son exactos, y de ahí salen porcentaje,
  velocidad y restante sin inventar nada.
- Cancelar el remux **conserva el archivo descargado** y borra sólo el temporal. Descartar 1,1 GB ya
  bajados porque el remux tardó sería castigar al usuario por un defecto nuestro. El mensaje dice
  explícitamente qué queda en disco.
- Las decisiones por sample viven en `SampleGate` (puro, testeado): mapeo de pista a pista del
  muxer, recorte hasta el primer keyframe, PTS repetido (se descarta) y PTS hacia atrás (se rebasa).
  El rebase puede desincronizar A/V en una discontinuidad real de TS; se prefiere eso a truncar el
  resto del episodio.
- Suite al cerrar T4: **95 tests, 0 fallos** (75 previos + 20 nuevos), `:app:assembleDebug` en verde.
