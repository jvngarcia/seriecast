# El televisor no encuentra el teléfono

El 90% de los casos es red, no código. Diagnosticá en este orden: cada paso descarta la mitad de las
causas posibles.

## 0. Antes que nada

- Teléfono en **Wi-Fi**, no en datos móviles (la app lo rechaza con un mensaje explícito).
- Televisor y teléfono en la **misma subred** (`192.168.1.x` vs `192.168.1.x`, no `192.168.0.x` vs
  `192.168.1.x`).
- Servidor **iniciado** en la app (si está detenido, no hay SSDP que responder).

## 1. ¿Responde el HTTP? (descarta el servidor)

Desde una PC en la misma red, con la IP:puerto que muestra la app:

```bash
curl -s -m 3 http://192.168.1.105:8080/description.xml | head -20
```

- **Responde XML** → el servidor anda. Saltá al paso 3.
- **`Connection refused`** → el servidor no está escuchando: revisá que el servicio siga vivo
  (notificación presente) y que el puerto mostrado sea el mismo que probás.
- **Timeout** → problema de red o firewall (paso 3).

Prueba adicional, la página de estado:

```bash
curl -s -m 3 http://192.168.1.105:8080/ | sed -n '1,20p'
```

## 2. ¿Responde el SSDP? (descarta el descubrimiento)

El televisor manda `M-SEARCH` a `239.255.255.250:1900` y espera una respuesta **unicast** con
`LOCATION`. Una sonda desde la PC:

```python
# msearch.py — python3 msearch.py                → multicast a toda la LAN
#              python3 msearch.py 192.168.1.105  → unicast directo al teléfono
import socket, sys

target = sys.argv[1] if len(sys.argv) > 1 else "239.255.255.250"
msg = (
    "M-SEARCH * HTTP/1.1\r\n"
    "HOST: 239.255.255.250:1900\r\n"
    'MAN: "ssdp:discover"\r\n'
    "MX: 2\r\n"
    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n"
    "\r\n"
).encode()

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.settimeout(4)
s.sendto(msg, (target, 1900))
try:
    while True:
        data, addr = s.recvfrom(2048)
        print("RESPUESTA de", addr)
        print(data.decode(errors="replace"))
except socket.timeout:
    print("sin respuesta SSDP")
```

Interpretación:

| Resultado | Significado | Acción |
|---|---|---|
| Respuesta con `LOCATION: http://IP:8080/description.xml` | SSDP correcto | El problema está en el televisor (paso 4) |
| Sin respuesta, pero `curl` del paso 1 funcionaba | El teléfono no está recibiendo multicast | Aislamiento de AP, firewall del router o red de invitados (paso 3) |
| Sin respuesta y `curl` también fallaba | El servidor no está corriendo | Volver al paso 1 |

Si corrés la sonda desde WSL, un contenedor o cualquier equipo detrás de NAT, el multicast al grupo
no va a salir: pasale la IP del teléfono como argumento y la sonda manda el `M-SEARCH` por unicast
directo a `IP:1900`. La app responde igual, y así el resultado sólo depende de que el puerto 1900
llegue al teléfono.

La app implementa lo mismo internamente: **Ayuda → Ejecutar autodiagnóstico** verifica HTTP y que el
socket SSDP haya quedado escuchando en `239.255.255.250:1900`.

## 3. Causas de red (las que más veces son la causa)

- **Aislamiento de AP / client isolation**: el router bloquea tráfico entre clientes Wi-Fi. Los
  dispositivos se ven por ping a veces, pero el multicast no pasa. Desactivalo en la config del
  router (suele estar en *Wireless → Advanced*).
- **Red de invitados**: siempre aislada. Usá la red principal.
- **Bandas separadas con aislamiento** (2.4 GHz vs 5 GHz con "AP isolation" por banda): poné ambos
  equipos en la misma banda.
- **Firewall del router / "multicast filtering" / IGMP snooping agresivo**: desactivá el filtrado de
  multicast o poné el teléfono en la lista de confianza.
- **VPN activa en el teléfono**: el tráfico del servidor sale por el túnel y la LAN deja de verlo.
- **Ahorro de batería de Infinix (XOS)**: `Ajustes → Batería → Optimización`; excluí SerieCast o el
  servicio puede morir con la pantalla apagada. La app mantiene un `MulticastLock` y un foreground
  service, pero el OEM puede matarlo igual.

## 4. Causas del lado del televisor

- **Sólo busca al encenderse**: apagalo y prendelo de nuevo con el servidor ya activo.
- **Menú equivocado**: buscá en *Fuente → DLNA*, *Red → Dispositivos multimedia*, *AllShare* (Samsung),
  *Smart Share* (LG), o el botón de "Media" del control.
- **Modo de compatibilidad DLNA del TV**: algunos modelos viejos exigen que el servidor anuncie
  perfiles `DLNA.ORG_PN` concretos. Esta app publica `DLNA.ORG_OP=01` (seek) sin `PN`, que es lo más
  compatible. Si tu TV es de los estrictos, el síntoma es: aparece el dispositivo pero la lista está
  vacía o el ítem no reproduce.
- **Sólo H.264/AAC**: si el archivo tiene otro codec, el TV muestra "formato no compatible". Ver §5.

## 5. El televisor lo ve pero no reproduce

1. Verificá el contenedor real del archivo (no la extensión):

   ```bash
   adb shell "head -c 16 '/sdcard/Android/data/com.seriecast.app/files/Movies/Doramas/ARCHIVO.mp4' | xxd"
   ```

   `....ftyp` → MP4 real. `47` seguido de `47` cada 188 bytes → sigue siendo MPEG-TS (el remux falló:
   mirá `adb logcat -s Mp4Remuxer`).

2. Codecs:

   ```bash
   adb shell dumpsys media.extractor | head -40
   ```

   Lo práctico: si el audio es AC3/DTS/Opus, `MediaMuxer` no puede meterlo en MP4 y el TV no lo va a
   decodificar. Solución: bajar una fuente con audio AAC.

3. Seek que no funciona: el TV está haciendo `GET` sin `Range` y no puede adelantar. Es del firmware;
   lo único que se puede hacer es servir el archivo desde el principio.

## 6. El servidor se corta solo

- Con la pantalla apagada, el OEM puede matar el proceso. Revisá:
  `adb shell dumpsys activity services com.seriecast.app`
- `adb logcat | grep -E "DlnaController|DlnaService|lowmemorykiller"` para ver si lo mató el sistema.
- Confirmá el `MulticastLock`: `adb shell dumpsys wifi | grep -i multicast` (debe aparecer
  `seriecast-ssdp`).

## 7. Nada de lo anterior

Corré el autodiagnóstico de la app y mandá:

```bash
adb logcat -s SerieCastApp YtDlpEngine Mp4Remuxer DlnaController DlnaService
```

Con eso y la salida de `curl`/`msearch.py` se sabe exactamente en cuál de las cuatro capas
(descarga, archivo, SSDP, HTTP) está el problema.
