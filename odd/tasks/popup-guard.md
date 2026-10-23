# Feature: popup guard en la captura

La pantalla de captura carga agregadores con publicidad agresiva. Cualquier `window.open` o
`target="_blank"` de un anuncio abre (o intenta abrir) una ventana fuera de la app, y una
navegación del frame principal disparada por script (`location.href = …`) reemplaza al reproductor y
deja la captura sin stream. Esta feature bloquea las dos cosas, lleva contador visible y mantiene
intacta la cadena legítima del reproductor.

## Specs

**S1 — Bloquear ventanas emergentes (pedido verbatim):**

> quiero que en una nueva feature implementes lo de evitar ventanas emergentes (para que páginas eviten abrir ventanas con publicidad o virus)

Registro previo del pedido (verbatim, de `odd/tasks/stream-capture-flow.md` L4):

> como puedo probar en el tv?, también anota como un pendiente o posible feature el bloquear ventanas emergentes

Regla: ningún `window.open`, `<a target="_blank">` ni `onCreateWindow` abre algo desde la captura.
El bloqueo es explícito y contado, no un efecto colateral del default de `WebView`
(`supportMultipleWindows = false`).

**S2 — No romper la cadena del reproductor.** Una navegación del frame principal se permite sólo
si pertenece a la cadena ya conocida del reproductor: mismo host o mismo dominio registrable que la
página que el usuario cargó, o un host (o su dominio registrable) que ya sirvió un stream capturado.
Todo lo demás se descarta y se cuenta. Base medida, verbatim de `stream-capture-flow.md`:

> Lo que **sí** rompe la captura es una navegación del frame principal: `location.href='…'`
> reemplaza el reproductor porque `SnifferWebView` no implementa `shouldOverrideUrlLoading`.
>
> Propuesta: guard de navegación que sólo permita la cadena del reproductor (mismo host de la
> página cargada más hosts de media/embed ya vistos) y descarte el resto, con contador de bloqueos
> visible en la pantalla de captura.

**S3 — Contador visible.** La pantalla de captura muestra cuántas ventanas y cuántas navegaciones
se bloquearon, y el host del último intento bloqueado.

**S4 — Escape hatch explícito.** Si el guard se equivoca con un host legítimo, el usuario puede
pegar esa URL en la barra de direcciones y tocar `Ir`: cargar por la barra fija la página confiable y
no pasa por el guard.

## Tasks

| ID | Spec | Qué hace | Ruta | Commit |
|---|---|---|---|---|
| T1 | S1-S2 | `NavigationGuard`: decisión pura y testeable (hosts confiables, dominio registrable, popups, historial de bloqueos) con tests JVM | test-first | — |
| T2 | S1-S2 | Cableado en `SnifferWebView` (`shouldOverrideUrlLoading` sólo frame principal, `onCreateWindow` denegado), `StreamSniffer.offer` devuelve el candidato aceptado | inline | — |
| T3 | S3 | Contador y último host bloqueado en `CaptureScreen` | inline | — |
| T4 | S1-S4 | Docs (README) + verificación end-to-end en emulador API 29 con página de prueba local | inline | — |

## Log

**L1 (pedido original del usuario, verbatim):**

> Necesito que finalices la feature, luego quiero que es una nueva feature implementes lo de evitar ventanas emergentes (para que páginas eviten abrir ventanas con publicidad o virus). No tengo el dispositivo disponible, por lo que si puedes hacerlo en un emulador estaría bien, sino cuando tenga el dispositivo físico lo pruebo

**L2 (evidencia previa, no re-medida en esta rama):** `window.open('…','_blank')` y un
`<a target="_blank">` clickeado por script devuelven `null` y no crean target con el default
(`supportMultipleWindows = false`). El daño real medido es la navegación del frame principal.
