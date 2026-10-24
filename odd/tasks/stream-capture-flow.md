# Feature: stream capture flow

El sniffer de `WebView` captura streams, pero sólo después de cuatro interacciones dentro de
iframes anidados, en un recuadro de 420dp embebido en un `Column` que también scrollea. Esta
feature convierte la captura en una pantalla propia, guarda el `Referer` real del request de media
y deja de exigir una extensión al final del path para clasificar un stream.

## Specs

**S1 — Captura a pantalla completa (punto 1 verbatim):**

> **`SnifferWebView` mide 420dp dentro de un `Column` con scroll** (`DownloadScreen.kt:110-118`). Tenés que scrollear el contenedor *y* la página de adentro, con iframes y publicidad encima: prácticamente no se llega a la lista de servidores. Es la causa #1 de fricción real.

**S2 — Referer real del request de media (punto 2 verbatim):**

> **Nunca se captura el `Referer`** del request de media (`StreamSniffer.offer` solo guarda cookie): hoy no hizo falta, pero muchos CDN rechazan sin él.

**S3 — Clasificación sin extensión (punto 3 verbatim):**

> `classify()` solo acepta extensión al final del path: frágil para CDNs con `/playlist?token=`.

**S4 — Bloquear ventanas emergentes (implementada en `feature/popup-guard`):**

> como puedo probar en el tv?, también anota como un pendiente o posible feature el bloquear ventanas emergentes

Alcance medido antes de anotarlo (emulador API 29, `:app:assembleDebug`, CDP sobre el `WebView` de
la app):

- `window.open('…','_blank')` y un `<a target="_blank">` clickeado por script **no** abren nada:
  devuelven `null`, no crean target y la página no cambia (`Page.windowOpen` se emite y se descarta).
  Es el comportamiento por defecto (sin `WebChromeClient`, `supportMultipleWindows=false`).
- Lo que **sí** rompe la captura es una navegación del frame principal: `location.href='…'`
  reemplaza el reproductor porque `SnifferWebView` no implementa `shouldOverrideUrlLoading`.
- Propuesta: guard de navegación que sólo permita la cadena del reproductor (mismo host de la
  página cargada más hosts de media/embed ya vistos) y descarte el resto, con contador de bloqueos
  visible en la pantalla de captura.

## Tasks

| ID | Spec | Qué hace | Ruta | Commit |
|---|---|---|---|---|
| T1 | S1 | Pantalla de captura a tamaño completo; `DownloadScreen` deja de hospedar el `WebView`. Lista de capturas en panel inferior y `Usar` vuelve al formulario | inline | `e58c303` |
| T2 | S3 | `classify()` acepta pistas de HLS/DASH/progresivo cuando el path no trae extensión, sin falsos positivos de assets | test-first | `b58a459` |
| T3 | S2 | `StreamSniffer.offer` recibe el `Referer` real del request, lo persiste y `Usar` lo precarga en el formulario | test-first | `456683c` |
| T4 | S1-S3 | Docs y textos de ayuda (README `Uso`), build y verificación en el dispositivo | inline | `7010247` |
| T5 | S4 | Bloqueo de ventanas emergentes y de navegaciones publicitarias en la captura | ver `odd/tasks/popup-guard.md` | `9ed543d` (rama `feature/popup-guard`) |

## Log

**L1 (pedido original del usuario, verbatim):**

> finaliza esta rama (git flow finish) luego puedes continuar con los puntos 1, 2 y 3

**L2 (contexto del turno anterior, verbatim):** los puntos 1, 2 y 3 son los tres defectos que
quedaron declarados en el informe de diagnóstico sobre `tudorama.com`:

1. WebView de 420dp dentro de un `Column` con scroll.
2. `Referer` del request de media nunca capturado.
3. `classify()` sólo mira la extensión al final del path.

**L3 (evidencia de cierre):**

- Ciclo test-first observado: T2 arrancó en rojo con `StreamSnifferTest` (2 tests fallando sobre 5)
  y T3 en rojo por API inexistente (`No parameter with name 'referer'`), y después verde.
- Suite completa: **41 tests, 0 fallos** (`:app:testDebugUnitTest`).
- Verificación en el Note 8 (`:app:assembleDebug` + `adb install -r`), contra `tudorama.com`,
  servidor `esp earnvids`: la captura ocupa la pantalla, la franja inferior mostró
  `HLS · callistanise.com / master.m3u8 / con Referer del reproductor` y `Usar` cargó
  `https://callistanise.com/stream/…/master.m3u8` con
  `Referer: https://callistanise.com/embed/v7pml7nsxiap`.
- Defecto encontrado durante esa verificación y corregido antes del commit: la tarjeta de la franja
  usaba `fillMaxWidth(0.82f)` dentro de un `LazyRow` y colapsaba al ancho del botón, dejando el
  candidato sin texto.

**L4 (pedido del usuario, verbatim):**

> como puedo probar en el tv?, también anota como un pendiente o posible feature el bloquear ventanas emergentes

Se anota como S4/T5 (pendiente). No se implementa en esta rama: el pedido fue registrarlo.

**L5 (cierre de T5, 2026-10-23):** T5 se implementó en su propia feature,
`odd/tasks/popup-guard.md` (rama `feature/popup-guard`: `9ed543d` + `d45e58e` + el commit de docs).
Verificado en emulador API 29 (AOSP): un click en `target=_blank` no abre ventana y queda contado, una
redirección a otro dominio se rechaza, y el contador muestra el último host bloqueado. La tabla de
arriba y S4 ya apuntan ahí; esta feature queda cerrada.
