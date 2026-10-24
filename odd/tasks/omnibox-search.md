# Feature: omnibox — buscar dentro de la app

La barra de direcciones de la captura hoy sólo acepta una URL. Para encontrar el episodio, la
persona tiene que salir a un navegador, buscar, copiar el link y volver. Esta feature convierte esa
barra en la de un navegador normal: si lo que se escribe no es una URL, se busca en la web ahí
mismo, y el guard deja pasar el click a un resultado.

## Specs

**S1 — La barra se comporta como la de un navegador (pedido verbatim):**

> Necesito que sea el buscador normal, para que la persona no tenga que salir de la app a buscar la url

Regla: lo que se escribe en la barra de la captura y en el campo "Dirección o búsqueda" de la
pantalla Descargar se interpreta así:

- `http://…` o `https://…` → se carga tal cual;
- una forma de host (`filemon.tv/serie/1`, `127.0.0.1:8099/x`) → se le antepone `https://` y se carga;
- cualquier otra cosa (`el flash temporada 3`) → búsqueda web del texto en el buscador por defecto.

**S2 — Buscador por defecto (respuesta verbatim a la pregunta de diseño):**

> Google

**S3 — El guard no puede bloquear la búsqueda.** Mientras la página en pantalla sea el buscador,
una navegación de frame principal a **cualquier** resultado se permite, como en un navegador, y la
confianza se re-ancla al sitio destino. Desde ahí vuelven las reglas normales de
`odd/tasks/popup-guard.md` S2. Sin esto la feature no funciona: hoy `NavigationGuard` rechaza y
cuenta todo destino fuera de la cadena del reproductor.

**S4 — Sólo `http` y `https` se cargan como URL.** Cualquier otro esquema (`javascript:`, `file:`,
`intent:`, `content:`) se trata como texto de búsqueda: nunca se ejecuta ni se carga.

**S5 — Nada de lo verificado antes se rompe.** Los popups siguen bloqueados y contados; desde una
página que no es el buscador, una navegación de frame principal a otro dominio sigue rechazándose y
contándose; la cadena del reproductor sigue permitida.

## Tasks

| ID | Spec | Qué hace | Ruta | Commit |
|---|---|---|---|---|
| T1 | S1-S2-S4 | `Omnibox`: resolución pura de texto a URL o búsqueda, con tests JVM | test-first | — |
| T2 | S3-S5 | `NavigationGuard`: escape de origen de búsqueda, con tests JVM | test-first | — |
| T3 | S1 | Cableado en `CaptureScreen` (`Ir` y URL inicial), aviso en Descargar y label "Dirección o búsqueda" | inline | — |
| T4 | S1-S5 | Docs, build, tests y verificación en el Infinix X692 | inline | — |

## Log

**L1 (pedido original del usuario, verbatim):**

> uso esta url y el servidor filemon.
>
> Necesito que sea el buscador normal, para que la persona no tenga que salir de la app a buscar la url

**L2 (decisión del usuario, verbatim):** a la pregunta "¿Cuál querés como buscador por defecto del
omnibox?" respondió: `Google`.

**L3 (evidencia del conflicto con el guard, medida en el código):**

- `NavigationGuard.kt:78-81`: una navegación de frame principal fuera de la página cargada y de los
  hosts que sirvieron streams se rechaza y se cuenta.
- `SnifferWebView.kt:75`: `onPageStarted` llama a `guard.onPageLoaded`, así que la confianza ya se
  re-ancla sola en cada página que carga.
- `CaptureScreen.kt:115`: `Ir` con el campo vacío ya caía en `https://www.google.com`.

Conclusión: cargar el buscador y tocar un resultado habría quedado bloqueado y contado. Por eso S3
es parte de la feature y no un efecto colateral.

**L4 (alcance declarado, no pedido):** un resultado con `target="_blank"` no abre nada, porque
`onCreateWindow` no entrega la URL del popup; sólo se cuenta. Los resultados en el mismo tab
funcionan.

**L5 (ciclo test-first):**

- RED T1: `OmniboxTest` no compilaba (`Unresolved reference 'Omnibox'`/`'OmniboxTarget'`).
- RED T2: `NavigationGuardTest` falló con `Unresolved reference 'registerSearchOrigin'` (4 veces).
- GREEN T1: 13/13. GREEN T2: 17/17 (12 previos + 5 nuevos).
- Suite completa: **71 tests, 0 fallos**; `:app:assembleDebug` en verde.
- Falla propia corregida en el camino: faltaba `import com.seriecast.app.download.Omnibox` en
  `AppViewModel.kt`, detectada por `:app:compileDebugKotlin`.

**L6 (verificación en el Note 8, salida literal de la UI):**

```
'137.0.0.1:8099'  ->  captura muestra  https://127.0.0.1:8099/
                       net::ERR_SSL_PROTOCOL_ERROR   (server HTTP, o sea antepuso el esquema)
'el flash temporada 3' -> resultados de Google dentro de la captura
 tap en resultado      -> pagina de HobbyConsolas (Cine / Series), sin ningún 'Bloqueado'
'http://127.0.0.1:8099/' -> pagina de prueba
 tap ABRIR AVISO EMERGENTE + redirect
   'Bloqueado: 1 ventana(s) · 1 redirección(es) · último: ads.example.net'
```

**L7 (cambio de contrato de UI):** el label pasó de "Dirección de la página" a "Dirección o
búsqueda" en las dos pantallas, porque la barra ya no sólo acepta direcciones. Dos arneses buscaban
ese texto y se actualizaron (`seriecast-popup-guard-test.sh`, `seriecast-sniffer-test.sh`, más la
referencia en `seriecast-download-test2.sh`). Los arneses no se re-ejecutaron: están atados a
`10.0.2.2`, que sólo existe en el emulador; la verificación de esta feature se hizo en el dispositivo
físico y queda anotada arriba.

**L8 (estado):** los cambios están **sin commitear**; los commits de unidad de trabajo quedan
pendientes de aprobación explícita del usuario.
