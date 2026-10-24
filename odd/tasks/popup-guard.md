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
| T1 | S1-S2 | `NavigationGuard`: decisión pura y testeable (hosts confiables, dominio registrable, popups, historial de bloqueos) con 12 tests JVM | test-first | `9ed543d` |
| T2 | S1-S2 | Cableado en `SnifferWebView` (`shouldOverrideUrlLoading` sólo frame principal, `onCreateWindow` denegado), `StreamSniffer.offer` devuelve el candidato aceptado | inline | `9ed543d` |
| T3 | S3 | Contador y último host bloqueado en `CaptureScreen` | inline | `9ed543d` |
| T4 | S1-S4 | Arnés `scripts/verification/seriecast-popup-guard-test.sh` | inline | `d45e58e` |
| T5 | S1-S4 | Docs (README, README del arnés) y cierre de la feature | inline | `docs` (este PR) |

## Log

**L1 (pedido original del usuario, verbatim):**

> Necesito que finalices la feature, luego quiero que es una nueva feature implementes lo de evitar ventanas emergentes (para que páginas eviten abrir ventanas con publicidad o virus). No tengo el dispositivo disponible, por lo que si puedes hacerlo en un emulador estaría bien, sino cuando tenga el dispositivo físico lo pruebo

**L2 (evidencia previa, no re-medida en esta rama):** `window.open('…','_blank')` y un
`<a target="_blank">` clickeado por script devuelven `null` y no crean target con el default
(`supportMultipleWindows = false`). El daño real medido es la navegación del frame principal.

**L3 (evidencia del ciclo test-first):**

- RED: `NavigationGuardTest` no compilaba (`Unresolved reference 'NavigationGuard'`/`'BlockedKind'`).
- Un test falló sobre la implementación y expuso una decisión, no un bug: con `clear()` olvidando
  todo, el guard quedaba en modo "permitir todo" con la página todavía en pantalla. Se ajustó el
  contrato (`clear()` olvida los hosts de streams y los contadores, **no** la página cargada), el
  guard y el test.
- GREEN: 12/12 en `NavigationGuardTest`. Suite completa: **53 tests, 0 fallos**; `:app:assembleDebug`
  en verde.

**L4 (verificación en emulador API 29, AOSP, salida literal del arnés):**

```
3. pagina lista tras 2s, enlace en (160 132)
   --- textos del guard tras el click:
     text="Bloqueado: 1 ventana(s) · 0 redirección(es)"
4. redirect detectado tras 3s
     text="Bloqueado: 1 ventana(s) · 1 redirección(es) · último: ads.example.net"
   foco actual:  mCurrentFocus=Window{74543b7 u0 com.seriecast.app/com.seriecast.app.ui.MainActivity}
6. OK: el click en target=_blank no abrio ventana y quedo contado
   OK: la redireccion a otro dominio fue rechazada y contada
   OK: el ultimo bloqueo es el dominio del anuncio
   OK: la app sigue al frente (no se abrio otra ventana)
```

Capturas revisadas: `1.png` con la página intacta y el contador de ventanas, `3.png` con `CADENA OK`
y `1 ventana(s) · 1 redirección(es) · último: ads.example.net`.

**L5 (decisiones y límites declarados):**

- `setSupportMultipleWindows(true)` se activa **sólo** para que el pedido de ventana llegue a
  `onCreateWindow`; ese callback devuelve siempre `false` y nunca entrega un `WebView` de transporte,
  así que no se crea ninguna ventana. Con el default no había forma de contar el bloqueo.
- Un `window.open` sin gesto del usuario ni siquiera llega al callback (lo corta el popup blocker de
  Chromium) y no se cuenta: los popups de publicidad reales nacen de un click, y así se prueba.
- Un popup bloqueado no expone su URL, porque `onCreateWindow` no la entrega: el contador dice
  cuántas ventanas se bloquearon, y el "último host" aparece cuando el bloqueo es una redirección.
- Las navegaciones de subframes no se filtran a propósito: los reproductores y sus overlays navegan
  sus propios iframes todo el tiempo y bloquearlos rompe el play sin frenar el anuncio.
