# Compilación y despliegue en el Infinix Note 8

## 1. Requisitos en la máquina de build

| Herramienta | Versión | Nota |
|---|---|---|
| JDK | **17** | AGP 8.7 no acepta JDK 21 para `compileJava`/`kotlin` con este target |
| Android SDK | Platform **35**, Build-Tools **35.x** | `sdkmanager "platforms;android-35" "build-tools;35.0.0"` |
| Gradle | **8.9** (wrapper) | Ya declarado en `gradle/wrapper/gradle-wrapper.properties` |
| CPython | **3.9 – 3.13** | Chaquopy lo usa en *build time* para correr `pip`. Debe ser un Python de escritorio, no el del teléfono |
| NDK | 26.x (sólo si Chaquopy compila `pycryptodomex`) | Ver §5 |

`local.properties` (no se commitea):

```properties
sdk.dir=/ruta/a/Android/Sdk
# Opcional: intérprete de Python para Chaquopy
# chaquopy.buildPython=/usr/bin/python3.11
```

## 2. Generar el wrapper

El repo trae `gradle-wrapper.properties` pero no el JAR. Con Gradle instalado:

```bash
gradle wrapper --gradle-version 8.9
```

O desde Android Studio: *File → Sync Project with Gradle Files* (regenera el wrapper solo).

## 3. Compilar

```bash
# Debug (instalable, sin firmar a mano)
./gradlew :app:assembleDebug

# Si querés forzar el intérprete de Python para pip
./gradlew :app:assembleDebug -Pchaquopy.buildPython=/usr/bin/python3.11
```

Salida: `app/build/outputs/apk/debug/app-debug.apk` (~25–40 MB: CPython + yt-dlp + Compose).

La primera compilación tarda porque Chaquopy descarga CPython y construye wheels para
`arm64-v8a` y `armeabi-v7a`.

## 4. Firmar y desplegar en el Note 8

```bash
# 1) Keystore propio (una sola vez)
keytool -genkeypair -v -keystore seriecast.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias seriecast

# 2) Build de release firmado (agregar signingConfigs al módulo si querés automatizarlo)
./gradlew :app:assembleRelease

# 3) Instalar por USB con depuración activada
adb devices                       # autorizar el fingerprint en el teléfono
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

Con `apksigner` si el APK queda sin firmar:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks seriecast.jks \
    --out seriecast.apk app/build/outputs/apk/release/app-release-unsigned.apk
adb install -r seriecast.apk
```

Primer arranque en el Note 8:

1. Aceptar el permiso de notificaciones (Android 10 no lo pide, pero el código lo solicita en 13+).
2. **Videos → Iniciar servidor DLNA**. Si dice que no hay red local, conectarse a Wi-Fi (no datos).
3. Verificar la IP: debe ser `192.168.x.x` / `10.x.x.x`, nunca `10.8.x.x` de datos móviles.
4. **Ayuda → Ejecutar autodiagnóstico**: los tres checks tienen que dar ✓.

## 5. Riesgos de build a tener presentes

Estos son los puntos donde la compilación puede fallar **antes** de llegar al código Kotlin. Ninguno
está verificado en este repo porque no había toolchain disponible al escribirlo.

**a) DSL de Chaquopy en Kotlin DSL.** La configuración vive en `android.defaultConfig.python { }`.
Si tu versión de Chaquopy no expone ese accessor, Gradle falla al configurar con
`Unresolved reference: python`. Alternativa equivalente en Groovy (`app/build.gradle`):

```groovy
android {
    defaultConfig {
        python {
            version "3.11"
            buildPython "/usr/bin/python3.11"
            pip { install "yt-dlp==2025.1.15" }
        }
    }
}
```

**b) `pycryptodomex` con extensiones C.** yt-dlp lo declara como dependencia. Chaquopy puede
construirlo con NDK instalado. Si falla:

```bash
sdkmanager "ndk;26.1.10909125"
```

o, si tu sitio no usa HLS con AES-128, evitá la dependencia instalando yt-dlp sin deps
(`pip { options "--no-deps"; install "yt-dlp==2025.1.15" }`) y agregando sólo los paquetes puros que
necesites.

**c) Versión de Python.** `version = "3.11"` es el valor más seguro para wheels precompilados. Si tu
Chaquopy es más nuevo y no soporta 3.11, subilo a `3.12` o `3.13` (yt-dlp 2025.x requiere ≥3.9).

**d) Wheel de `yt-dlp`.** Es Python puro, no debería fallar; la traba real es siempre (b).

**e) Versiones de Compose/Kotlin.** Kotlin 2.0.21 + Compose BOM 2024.10.01 + AGP 8.7.3 es una
combinación coherente. Si cambiás Kotlin, mantené el plugin `org.jetbrains.kotlin.plugin.compose` en
la misma versión (Kotlin 2.x ya no usa `composeOptions.kotlinCompilerExtensionVersion`).

## 6. Qué verificar en el teléfono, en orden

| # | Check | Cómo |
|---|---|---|
| 1 | Chaquopy arrancó | En **Descargar** se lee `yt-dlp <versión>` bajo el título. Si dice "Python no disponible", ver §5 |
| 2 | Servidor arriba | **Videos** muestra `http://IP:PUERTO` |
| 3 | HTTP responde | Desde una PC: `curl -s http://IP:8080/description.xml \| head` |
| 4 | SSDP responde | Ver `docs/TV-TROUBLESHOOTING.md` (§ sonda M-SEARCH) |
| 5 | TV descubre | El nombre aparece como `SerieCast (<modelo>)` |
| 6 | Reproducción + seek | Pausar y adelantar: si el seek falla, el televisor está ignorando `Range` |

## 7. Logs

```bash
adb logcat -s SerieCastApp YtDlpEngine Mp4Remuxer DlnaController
adb logcat -s python.stdout python.stderr     # salida de yt-dlp
```
