# AGENTS.md

Reglas obligatorias para cualquier agente —humano o IA— que trabaje en este repositorio.
Se leen antes de escribir la primera línea y no son negociables. Si una instrucción posterior
las contradice, **pedí confirmación explícita** antes de violarlas.

---

## 1. Regla absoluta

> **Nunca se commitea directo en `main` ni en `develop`.**

Las dos son ramas de integración: sólo reciben merges desde otras ramas. Un commit directo en
cualquiera de las dos rompe la trazabilidad de release y no se puede revertir limpio.

Si te encontrás haciendo `git commit` con `main` o `develop` como rama actual, **pará**: estás
violando la regla. Ver §6 para salir del estado sin perder trabajo.

**Este repositorio sigue Git Flow. Siempre.**

---

## 2. Modelo de ramas

| Rama | Sale de | Mergea en | Commits directos |
|---|---|---|---|
| `main` | — (permanente) | — | **Prohibido** |
| `develop` | `main` (una vez, al inicio) | — | **Prohibido** |
| `feature/<slug>` | `develop` | `develop` | Sí, es tu rama |
| `release/<version>` | `develop` | `main` **y** `develop` | Sí, sólo fixes de release |
| `hotfix/<slug>` | `main` | `main` **y** `develop` | Sí |

Reglas de nombre:

- `feature/` — funcionalidad nueva o cambio de comportamiento. Ej: `feature/dlna-soap-browse`
- `release/` — preparación de versión. Ej: `release/0.2.0`
- `hotfix/` — corrección urgente sobre producción. Ej: `hotfix/ssdp-multicast-lock`
- `slug`: minúsculas, números y guiones. Sin acentos, sin espacios, sin `_`, sin MAYÚSCULAS.
- Una rama = un tema. Si el nombre necesita un "y", son dos ramas.

No se inventan prefijos nuevos. `docs/`, `chore/` o `refactor/` **no** son Git Flow: ese trabajo
entra por una `feature/` (el tipo se refleja en el commit, no en la rama).

---

## 3. Flujo obligatorio para cada cambio

```bash
# 1. Partir siempre de develop actualizado
git switch develop
git pull --ff-only origin develop

# 2. Crear la rama de trabajo
git switch -c feature/<slug>

# 3. Trabajar y commitear en unidades revisables
git add -A
git commit -m "feat(dlna): add ContentDirectory Browse action"

# 4. Publicar la rama
git push -u origin feature/<slug>

# 5. Abrir PR contra develop (nunca contra main)
gh pr create --base develop --fill

# 6. Después del merge, limpiar
git switch develop && git pull --ff-only origin develop
git branch -d feature/<slug>
```

Merges:

- A `develop`: PR desde `feature/*` o `hotfix/*`. Usar `--no-ff` para que el merge quede visible
  como unidad en el historial.
- A `main`: **sólo** desde `release/*` o `hotfix/*`, con PR propio.
- Un `release/*` que llegó a `main` **también** vuelve a `develop`. Si no, el próximo release
  reaplica o pierde el fix.
- No se mergea una rama sin PR, ni con el PR sin revisar, ni con la CI en rojo.

---

## 4. Commits: Conventional Commits

Formato: `<tipo>(<alcance opcional>): <descripción en imperativo, minúsculas>`

| Tipo | Cuándo |
|---|---|
| `feat` | Funcionalidad nueva o cambio de comportamiento observable |
| `fix` | Corrección de un defecto |
| `refactor` | Cambio interno sin cambio de comportamiento |
| `docs` | Sólo documentación |
| `test` | Sólo tests |
| `build` | Gradle, dependencias, toolchain, Chaquopy |
| `perf` | Mejora de rendimiento medible |
| `chore` | Mantenimiento sin impacto en producto |

Reglas:

- El mensaje va en **inglés**, sin punto final, sin mayúscula inicial.
- Un commit = una unidad de review. Si el mensaje necesita "y", son dos commits.
- Los tests y los docs del cambio van **en el mismo commit** que el comportamiento. Nunca en uno
  aparte "después".
- Nunca mezclar refactor masivo con cambio de comportamiento: imposible de revisar.
- `BREAKING CHANGE:` en el footer si rompe contrato (API pública, formato de archivo, protocolo).

Ejemplos válidos:

```
feat(download): capture hls playlists from the embedded webview
fix(dlna): answer ssdp m-search on the source port
build(chaquopy): pin python 3.11 for wheel compatibility
docs: explain ap isolation in tv troubleshooting
```

---

## 5. Prohibiciones

- ❌ `git commit` en `main` o `develop`.
- ❌ `git push --force` (ni `--force-with-lease`) a `main`, `develop`, o a una rama con PR abierto
  de otra persona.
- ❌ Mergear sin PR o auto-aprobarte un PR sin revisión.
- ❌ Rebase o amend sobre commits ya pusheados en una rama compartida.
- ❌ Commitear secretos: `*.jks`, `*.keystore`, `local.properties`, tokens, cookies de sesión.
- ❌ Commitear artefactos de build: `build/`, `*.apk`, `*.aab`, `.gradle/`.
- ❌ Subir archivos de video de prueba (`.mp4`, `.mkv`, `.ts`): van en `.gitignore` o fuera del repo.
- ❌ Resolver un conflicto "eligiendo lo mío" sin entender ambos lados.

---

## 6. Si ya estás en `main` o `develop` con cambios

No borres nada. Movés los cambios a una rama:

```bash
# Con cambios sin commitear
git switch -c feature/<slug>     # los cambios viajan con vos
git add -A && git commit -m "..."

# Con un commit ya hecho en develop (y todavía sin push)
git branch feature/<slug>        # la rama apunta al commit
git reset --hard origin/develop  # develop vuelve a su lugar
git switch feature/<slug>
```

Si el commit **ya está pusheado** en `develop`: no lo reescribas. Avisá y se revierte con un
`git revert` en su propia rama + PR.

---

## 7. Antes de abrir el PR

1. La rama sale de `develop` actualizado (`git rebase origin/develop` si quedó atrás).
2. `./gradlew :app:assembleDebug` compila.
3. El cambio respeta las reglas de §8.
4. El cuerpo del PR dice **qué** cambia, **por qué**, y **cómo se verificó**. Si algo no se pudo
   verificar, se declara explícitamente. No se escribe "funciona" sin evidencia.
5. Sin `TODO` sin dueño ni código comentado. Sin `println` de debug.

---

## 8. Contexto del proyecto que un agente no puede romper

SerieCast: app Android (Kotlin) que descarga episodios y los publica como servidor DLNA para
televisores antiguos. Antes de cambiar algo de estas áreas, leé el spec en `odd/tasks/`.

- **Sin Google Play Services.** Cero dependencias `com.google.android.gms` o Cast SDK. El
  descubrimiento y el transporte son SSDP + HTTP propios.
- **Salida MP4 (H.264 + AAC).** El televisor objetivo no decodifica otra cosa. No introduzcas
  `ffmpeg-kit` (retirado de Maven Central): el remux usa `MediaExtractor`/`MediaMuxer`.
- **El teléfono es `MediaServer:1` (DMS), no un renderer.** El televisor navega y reproduce solo.
- **El contenedor se detecta por magic bytes**, nunca por extensión (`ContainerSniffer`). yt-dlp
  sin ffmpeg deja MPEG-TS dentro de archivos `.mp4`.
- **`MediaMetadataRetriever.close()` es API 29+**; con `minSdk 26` se usa `release()`.
- **`MulticastLock` + `CHANGE_WIFI_MULTICAST_STATE`** son obligatorios o el SSDP muere con la
  pantalla apagada.
- **Los comentarios de código y los mensajes de commit van en inglés.** La documentación de
  producto (`README.md`, `docs/`) y los textos de UI van en español.

---

## 9. Excepción histórica

El commit inicial `8a11d34` se hizo directo en `main`, antes de existir esta regla. No se
reescribe: queda como origen del repositorio. Desde ese commit en adelante, Git Flow es
obligatorio sin excepciones.
