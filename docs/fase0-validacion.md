# Fase 0: validar el camino antes de usar la app

Objetivo: en 1 o 2 tardes saber si tu volante sobrevive al viaje por la red y si tu TV lo expone bien.
Llena la tabla de resultados a medida que avances.

**Antes de empezar, anota:**

- Modelo del volante: ______ (G29 / G920 / G923 PS / G923 Xbox…)
- Dispositivo Google TV y versión de Android: ______
- Conexión: TV por Ethernet / Wi-Fi · PC por Ethernet / Wi-Fi
- Hub USB-C (con PD) usado: ______

## Pruebas

### 1. Steam Link directo (sin USB/IP)

Conecta el volante a la TV, abre Steam Link y revisa si el juego lo reconoce.

- Si funciona **con force feedback**, no necesitas nada más.
- Lo esperable: llega como gamepad genérico o no llega.

### 2. Línea base con Linux (opcional, muy útil)

En una Raspberry Pi o equipo Linux con el volante conectado:

```bash
sudo ./tools/linux-baseline.sh
```

En la PC: `.\pc\attach-wheel.ps1 -TvIp <IP-de-la-Pi>`. Si G HUB ve el volante y el force feedback
funciona, el protocolo USB/IP funciona con tu modelo.

### 3. VirtualHere Android Server en la TV

Instala VirtualHere USB Server en la Google TV y el cliente VirtualHere en la PC, y monta el volante.
Si funciona, tu TV y su puerto USB son viables.

### 4. Esta app (sustituye a la prueba con el APK original de USBIPServerForAndroid)

Instala la app (ver README) y monta con `pc\attach-wheel.ps1`. Si falla, anota **en qué paso**:

| Paso | Cómo se ve si falla | Dónde mirar |
| --- | --- | --- |
| Lista | `usbip.exe list` no muestra el volante | App: "sin permiso" → botón *Pedir permiso USB* |
| Attach | `attach` da error | Registro de la app: "No se pudieron reclamar las interfaces" |
| Datos | Windows lo ve pero G HUB no, o sin force feedback | Registro con *Registro detallado: sí*, `adb logcat -s UsbIp`, Wireshark |

## Resultados

| Prueba | ¿Lo ve G HUB? | ¿Ejes/pedales? | ¿Force feedback? | ¿Se re-enumeró? | Notas |
| --- | --- | --- | --- | --- | --- |
| 1 · Steam Link directo | n/a | | | | |
| 2 · Linux | | | | | |
| 3 · VirtualHere | | | | | |
| 4 · Esta app | | | | | |

**Criterio de salida:** el volante funciona con force feedback en la prueba 2 o en la 3. Si falla en
ambas, el problema es el volante o el hardware de la TV, no la app: detente y revisa los riesgos.

**Atajo de decisión:** si VirtualHere funciona bien y el costo de su licencia no te importa, ya tienes
la solución.

## El riesgo principal: re-enumeración y reset USB

Algunos volantes Logitech cambian de modo (otro product ID) o piden un reset USB cuando el driver de
la PC los toma. Cómo reconocerlo en la app:

- **"Desconectado … / Conectado …"** con otro product ID → el volante cambió de modo. La app lo
  re-exporta con el **mismo busid** y `attach-wheel.ps1 -Watch` lo vuelve a montar. Es el caso
  esperado y debería resolverse solo en unos segundos.
- **"El cliente pidió reset USB … se responde OK sin reset"** seguido de un volante que no responde →
  el driver necesitaba un reset real, que Android no permite a una app normal. Opciones: usar el
  volante en el modo que no requiera cambio (p. ej. el interruptor PS3/PS4 del G29), o compararlo
  con la prueba 2 (Linux sí hace el reset).
