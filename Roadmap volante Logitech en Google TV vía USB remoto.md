# Roadmap: volante Logitech en Google TV vía USB remoto

Oct 12, 2026 · @Jair Saldaña Lopez

## Decisión: la TV como servidor USB/IP y la PC como cliente

La ruta más fácil es que la app de Google TV sea un **servidor USB/IP** que exporte el volante por la red, y que la PC lo monte con **usbip-win2** como si estuviera conectado localmente. Así los drivers de Logitech y el force feedback corren en la PC, y Steam Link solo transmite video y audio.

Por qué esta ruta:

- **No hay que escribir drivers.** USB/IP es un protocolo estándar (TCP, puerto 3240) y ya existe un cliente libre para Windows: usbip-win2, licencia BSD-2.
- **Ya hay una base abierta para Android.** USBIPServerForAndroid de Cameron Gutman (GPL-3.0) implementa el servidor sin root, usando la API USB Host de Android. Su última versión es de 2016, así que el trabajo es modernizarla, no partir de cero.
- **Se puede validar antes de programar.** VirtualHere vende un servidor USB para Android que hace lo mismo; sirve para probar que tu TV y tu volante funcionan por esta vía.
- **Steam Link no resuelve el volante por sí solo.** Su soporte de volantes en Android es irregular y el force feedback no está garantizado; con USB/IP el volante llega a la PC tal cual.

Nota de roles: en USB/IP el *servidor* es quien tiene el dispositivo físico (la TV) y el *cliente* es quien lo usa (la PC). Es al revés de como lo planteaste, pero es lo que permite que los drivers de la PC controlen el volante.

Preguntas abiertas: modelo exacto del volante (G29, G920, G923…) y del dispositivo Google TV. Ambos cambian el riesgo principal (re-enumeración del volante) y el hub USB que necesitas.

## Cómo funciona

Hay dos flujos independientes: el del volante viaja por USB/IP de la TV a la PC (y el force feedback de regreso), y el del juego viaja por Steam Remote Play de la PC a la TV.

&#91;embedded content: arquitectura · TV como servidor USB/IP, PC como cliente\]

Tu app es solo la caja resaltada. Todo lo demás ya existe: usbip-win2, los drivers de Logitech y Steam Link.

## Fase 0: validar el camino antes de escribir código

Esta fase decide si vale la pena desarrollar la app: en 1 o 2 tardes sabrás si tu volante sobrevive al viaje por la red y si tu TV lo expone bien.

1. **Prueba directa con Steam Link.** Conecta el volante a la TV, abre Steam Link y revisa si el juego lo reconoce. Si funciona con force feedback, no necesitas nada más. Lo esperable es que llegue como gamepad genérico o no llegue.
2. **Línea base con Linux** (opcional pero muy útil). Conecta el volante a una Raspberry Pi o cualquier equipo Linux, exporta con `usbip bind`, y en la PC haz `usbip.exe attach`. Si aquí G HUB ve el volante con force feedback, el protocolo funciona con tu modelo.
3. **Prueba en la TV con VirtualHere Android Server.** Instálalo en la Google TV, el cliente VirtualHere en la PC, y monta el volante. Si funciona, tu TV y su puerto USB son viables.
4. **Prueba en la TV con USBIPServerForAndroid** (APK original). Hay reportes de que falla en Android reciente; si falla, anota en qué paso (lista, attach o datos), porque eso marca el trabajo de la Fase 1.

**Criterio de salida:** el volante funciona con force feedback en al menos una de las pruebas 2 o 3. Si falla en ambas, el problema es el volante o el hardware de la TV, no tu app; detente y revisa riesgos antes de invertir más.

**Atajo de decisión:** si VirtualHere funciona bien y el costo de su licencia no te importa, ya tienes la solución. Desarrollar tu propia app tiene sentido si quieres control total, una herramienta para JSL Systems o aprender el stack.

## Fases de desarrollo de la app

Son seis fases con una puerta cada una; no se avanza sin pasarla. El núcleo técnico está en las fases 2 y 3.

&#91;embedded content: roadmap · 6 fases, 6 puertas\]

Estimado a tiempo parcial: F0, 1–2 tardes · F1, 1 semana · F2, 1–2 semanas · F3, 1–2 semanas · F4, 1 semana · F5, 3–4 días.

### Tareas técnicas por fase

1. **F1 · Base modernizada**
   - Fork del repo; actualizar Gradle, AGP y `targetSdk` al nivel vigente.
   - Manifest: `uses-feature android.hardware.usb.host`, categoría `LEANBACK_LAUNCHER` y banner de TV.
   - Filtro `USB_DEVICE_ATTACHED` con `vendor-id 1133` (0x046D, Logitech) para que el permiso USB sea automático.
   - Foreground service tipo `connectedDevice`, con su permiso y notificación.
   - Servidor TCP en el puerto 3240 con `OP_REQ_DEVLIST` y `OP_REQ_IMPORT`.
2. **F2 · Ruta de datos**
   - `USBIP_CMD_SUBMIT` en EP0 → `controlTransfer`; interrupt IN → `UsbRequest.queue` + `requestWait`; interrupt OUT → `UsbRequest` o `bulkTransfer`.
   - Interceptar `SET_CONFIGURATION` y `SET_INTERFACE` y llamar `setConfiguration` / `setInterface`.
   - `USBIP_CMD_UNLINK` → `UsbRequest.cancel` de la URB pendiente.
   - `claimInterface(intf, true)` para quitarle el volante al driver HID de Android.
3. **F3 · Robustez**
   - Escuchar `USB_DEVICE_DETACHED` / `ATTACHED`; si el volante vuelve con otro product ID, re-exportarlo (Android puede pedir permiso otra vez).
   - Cerrar la sesión TCP limpiamente para que la PC re-monte con *Auto*.
   - Registro de eventos visible en la app para diagnóstico.
4. **F4 · Latencia y experiencia en TV**
   - `TCP_NODELAY`, un hilo por endpoint y buffers preasignados.
   - Medir con Wireshark en la PC y con prueba en juego.
   - Pantalla de estado navegable con D-pad (dispositivo, IP, cliente conectado) y arranque con `BOOT_COMPLETED`.
5. **F5 · Integración con Steam Link**
   - Confirmar que Steam Link no ve el volante mientras está exportado.
   - Probar 2 o 3 juegos con force feedback y documentar la configuración.
   - APK firmado para instalar por ADB o Downloader.

## Lado PC (Windows): cliente USB/IP, drivers y Steam

En la PC no programas nada: instalas el cliente, montas el volante y lo usas como si estuviera conectado por cable.

1. **Instala usbip-win2.** Con winget: `winget install --id vadimgrn.usbip-win2 --exact`. Requiere Windows 10 1809 o posterior, x64. Usa la versión más reciente: una anterior podía tirar Windows (pantalla azul) bajo carga.
2. **Verifica la firma del driver.** Algunas versiones de usbip-win2 piden activar *Test Signing Mode*; revisa la guía de la versión que instales antes de cambiar nada en el arranque.
3. **Lista y monta el volante:**
   - `usbip.exe list -r <IP-de-la-TV>`
   - `usbip.exe attach -r <IP-de-la-TV> -b <busid>`
4. **Auto-reconexión.** usbip-win2 tiene la opción *Auto* (equivalente a `usbip port --stash`) para que el volante se vuelva a montar solo. Es clave para cuando el volante se re-enumera.
5. **Drivers Logitech.** Con el volante montado, G HUB (o Logitech Gaming Software, según modelo) debe detectarlo y permitir calibrar y probar force feedback.
6. **Steam.** El volante ya es un dispositivo local de la PC, así que Steam Link solo transporta video. Si el juego lo detecta raro, desactiva Steam Input para ese juego.

## Riesgos técnicos y mitigaciones

El riesgo que puede tumbar el proyecto es el primero: algunos volantes Logitech piden un reinicio USB o cambian de modo al recibir el driver, y Android no deja hacer ese reinicio a una app normal.

| Riesgo | Impacto | Mitigación |
| --- | --- | --- |
| El volante cambia de modo o pide reset USB al cargar el driver y se re-enumera; Android no expone el reset a apps sin privilegios | Alto | Probarlo en la Fase 0. En la app, detectar desconexión y re-exportar el nuevo dispositivo; en la PC, usar *Auto* para re-montar. Si persiste, usar el volante en el modo que no requiera cambio |
| Latencia o cortes por Wi-Fi | Medio | Ethernet en la TV y la PC; `TCP_NODELAY`; un hilo por endpoint |
| Android mata la app al abrir Steam Link | Medio | Foreground service tipo `connectedDevice`, wake lock y Wi-Fi lock |
| Doble entrada: Android y Steam Link también leen el volante | Medio | `claimInterface(..., force = true)` desconecta el driver HID de Android mientras se exporta |
| Driver de usbip-win2 inestable o que pide test signing | Medio | Usar la última versión; validar en Fase 0 |
| Base de código vieja (2016) y GPL-3.0 | Bajo | Reescribir por módulos; si publicas tu app, debe ser GPL-3.0 o reescrita sin copiar código |
| El puerto USB de la TV no da energía o no soporta hub | Bajo | Hub USB-C con alimentación (PD); el volante usa su propia fuente |

Los volantes usan transferencias de interrupción, no isócronas, así que no chocan con la limitación conocida del servidor Android (sin soporte isócrono).

## Hardware, herramientas y fuentes

| Elemento | Para qué | Nota |
| --- | --- | --- |
| Dispositivo Google TV con USB host | Corre la app servidor y Steam Link | Confirma que acepta dispositivos USB externos |
| Hub USB-C con PD y puerto Ethernet | Alimentar la TV, conectar el volante y red cableada | Necesario si la TV tiene un solo USB-C |
| Volante Logitech + su fuente | Dispositivo a exportar | Modelo exacto pendiente |
| PC Windows 10/11 x64 | Cliente USB/IP, G HUB, Steam | Ethernet recomendado |
| Raspberry Pi o equipo Linux | Línea base en Fase 0 | Opcional |
| Android Studio + ADB por red | Desarrollo y depuración en la TV | Activa opciones de desarrollador en la TV |
| Wireshark (disector USB/IP) | Ver el tráfico en el puerto 3240 | Útil en Fase 2 y 3 |

### Fuentes

- [USBIPServerForAndroid (GitHub, GPL-3.0)](https://github.com/cgutman/USBIPServerForAndroid)
- [USB/IP Server en APKPure: descripción, puerto 3240, sin soporte isócrono](https://apkpure.net/usb-ip-server/org.cgutman.usbipserverforandroid)
- [Reporte de incompatibilidad con Android reciente (usbipd-win #920)](https://github.com/dorssel/usbipd-win/discussions/920)
- [usbip-win2 releases](https://github.com/vadimgrn/usbip-win2/releases/latest)
- [usbip-win2 en winget](https://wingetly.io/apps/vadimgrn/usbip-win2)
- [PadSense CE 0.2.4: aviso de bugcheck en versiones viejas de usbip-win2](https://github.com/PadSense/PadSense-CE/releases/tag/v0.2.4)
- [VirtualHere USB Server para Android](https://virtualhere.com/android)
- [Foro VirtualHere: volante Logitech en Shield TV y el reset que Android no permite](https://virtualhere.com/node/2866)
- [Foro VirtualHere: G923 en Android TV con Steam Link](https://www.virtualhere.com/node/3420)
