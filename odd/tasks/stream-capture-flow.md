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

## Tasks

| ID | Spec | Qué hace | Ruta | Commit |
|---|---|---|---|---|
| T1 | S1 | Pantalla de captura a tamaño completo; `DownloadScreen` deja de hospedar el `WebView`. Lista de capturas en panel inferior y `Usar` vuelve al formulario | inline | — |
| T2 | S3 | `classify()` acepta pistas de HLS/DASH/progresivo cuando el path no trae extensión, sin falsos positivos de assets | test-first | — |
| T3 | S2 | `StreamSniffer.offer` recibe el `Referer` real del request, lo persiste y `Usar` lo precarga en el formulario | test-first | — |
| T4 | S1-S3 | Docs y textos de ayuda (README `Uso`, `GuideScreen`), build y verificación en el dispositivo | inline | — |

## Log

**L1 (pedido original del usuario, verbatim):**

> finaliza esta rama (git flow finish) luego puedes continuar con los puntos 1, 2 y 3

**L2 (contexto del turno anterior, verbatim):** los puntos 1, 2 y 3 son los tres defectos que
quedaron declarados en el informe de diagnóstico sobre `tudorama.com`:

1. WebView de 420dp dentro de un `Column` con scroll.
2. `Referer` del request de media nunca capturado.
3. `classify()` sólo mira la extensión al final del path.
