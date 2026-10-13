# Volante USB/IP para Google TV

App de Android TV que exporta un volante Logitech conectado a la TV por **USB/IP** (TCP 3240), para
que la PC lo monte con **usbip-win2** como si estuviera conectado por cable. Los drivers de Logitech
y el force feedback corren en la PC; Steam Link solo transmite video y audio.

Implementa el documento `Roadmap volante Logitech en Google TV vía USB remoto.md`.

```
 Volante ──USB──▶ Google TV (esta app, servidor USB/IP) ──TCP 3240──▶ PC (usbip-win2 → G HUB → juego)
                         ▲                                                         │
                         └──────────────── Steam Link (video/audio) ◀──────────────┘
```

## Estructura

| Ruta | Qué es |
| --- | --- |
| `usbip-core/` | Protocolo USB/IP y servidor en Kotlin/JVM puro, sin Android. Tiene pruebas con un cliente simulado. |
| `app/` | App de Android TV: backend USB (`UsbManager`, `UsbRequest`), servicio en primer plano y pantalla de estado. |
| `pc/attach-wheel.ps1` | Monta el volante en Windows con usbip-win2; con `-Watch` lo vuelve a montar solo. |
| `tools/linux-baseline.sh` | Fase 0: exporta el volante desde una Raspberry Pi/Linux para tener una línea base. |
| `docs/` | Guías de la Fase 0, del lado PC y de las pruebas en hardware (F4/F5). |

La app se escribió desde cero, **sin copiar código de USBIPServerForAndroid** (GPL-3.0); solo usa la
especificación del protocolo. Así no queda atada a la GPL: elige tú la licencia antes de publicarla.

## Compilar

Requisitos: JDK 17+ y Android SDK (platform 36). `local.properties` debe apuntar al SDK:

```
sdk.dir=C:/Users/<tu usuario>/AppData/Local/Android/Sdk
```

```powershell
.\gradlew :usbip-core:test          # pruebas del protocolo (no necesitan Android)
.\gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
.\gradlew :app:assembleRelease      # APK firmado si existe keystore.properties (ver docs/pruebas-en-hardware.md)
```

## Instalar en la Google TV

1. En la TV: *Ajustes → Sistema → Acerca de → Compilación* (pulsa 7 veces) y luego, en *Opciones de
   desarrollador*, activa la **depuración por USB** y la **depuración inalámbrica**.
2. Desde la PC:
   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb connect <IP-de-la-TV>:5555      # en Android 11+ usa el puerto de "Depuración inalámbrica"
   & $adb install -r app\build\outputs\apk\debug\app-debug.apk
   & $adb logcat -s UsbIp                  # registro de la app
   ```
3. Conecta el volante a la TV (hub USB‑C con PD si la TV tiene un solo puerto). Android pregunta
   si *Volante USB/IP* puede usar el dispositivo: marca **usar de forma predeterminada** y acepta.
   Con eso el permiso vuelve a ser automático cuando el volante se re-enumera.
4. Abre la app: muestra la IP de la TV, el volante con su **busid** (p. ej. `1-1`) y el registro.

## Usar desde la PC

```powershell
winget install --id vadimgrn.usbip-win2 --exact
.\pc\attach-wheel.ps1 -TvIp <IP-de-la-TV> -Watch
```

Luego abre G HUB, calibra y abre Steam Link en la TV. Detalles en [docs/pc-windows.md](docs/pc-windows.md).

## Estado del roadmap

| Fase | Estado | Dónde |
| --- | --- | --- |
| F0 · Validación | Requiere tu hardware | [docs/fase0-validacion.md](docs/fase0-validacion.md), `tools/linux-baseline.sh` |
| F1 · Base | Hecha | Gradle 8.13 / AGP 8.13 / `targetSdk 36`; manifest con `usb.host`, `LEANBACK_LAUNCHER`, banner, filtro `vendor-id 1133`; servicio `connectedDevice`; `OP_REQ_DEVLIST` / `OP_REQ_IMPORT` |
| F2 · Ruta de datos | Hecha | EP0 → `controlTransfer`; interrupt/bulk IN y OUT → `UsbRequest.queue` + `requestWait`; `SET_CONFIGURATION` / `SET_INTERFACE` interceptados; `CMD_UNLINK` → `UsbRequest.cancel`; `claimInterface(intf, true)` |
| F3 · Robustez | Hecha | Re-exportación con el **mismo busid** cuando el volante vuelve con otro product ID; cierre limpio de la sesión TCP al desconectarse; registro visible en la app |
| F4 · Latencia y TV | Hecha (falta medir) | `TCP_NODELAY`, buffers preasignados, cada respuesta en un solo `write`; pantalla navegable con D-pad; arranque con `BOOT_COMPLETED`; métricas de latencia en pantalla |
| F5 · Steam Link | Requiere tu hardware | Checklist y firma del APK en [docs/pruebas-en-hardware.md](docs/pruebas-en-hardware.md) |

### Diferencias con el roadmap

- **No es un fork:** reescrito en Kotlin. Evita heredar código de 2016 y la GPL-3.0 (es la
  mitigación que el propio roadmap propone).
- **"Un hilo por endpoint"** se cambió por transferencias asíncronas: los endpoints interrupt/bulk se
  encolan con `UsbRequest.queue` y un único hilo de alta prioridad las recoge con `requestWait`.
  Nunca hay un hilo bloqueado por endpoint, y se pueden tener varias URB en vuelo por endpoint, como
  hace Windows con los HID. EP0 tiene su propio hilo porque `controlTransfer` es bloqueante.
- **Reset USB:** cuando la PC pide `SET_FEATURE(PORT_RESET)`, la app responde OK sin resetear (Android
  no lo permite) y lo deja en el registro. Si el volante no funciona tras ese aviso, ese es el riesgo
  principal del roadmap: revisa la sección correspondiente en `docs/fase0-validacion.md`.
- **Auto-reconexión en la PC:** el README de usbip-win2 no documenta una opción *Auto* por línea de
  comandos, así que `pc/attach-wheel.ps1 -Watch` hace de vigilante. Funciona porque la app conserva
  el busid cuando el volante se re-enumera.
