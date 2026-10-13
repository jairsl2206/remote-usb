# Pruebas en hardware: latencia (F4) e integración con Steam Link (F5)

Estas fases solo se cierran con tu TV, tu volante y tu PC. La app ya trae lo necesario para medir.

## F4 · Latencia

### Métricas en la app

En *PC conectada*, la pantalla de la app muestra por sesión (se actualiza cada segundo):

- **URBs** enviadas/completadas, unlinks y errores. Con el volante en reposo, Windows mantiene
  URB de interrupción IN en espera; al mover el volante las completadas deben subir de forma continua.
- **Control prom/máx**: cuánto tarda el volante en responder a transferencias de EP0 (descriptores,
  comandos HID de G HUB). Valores típicos: 1–5 ms.
- **Envío TCP máx**: lo que tarda el `write` del socket. Si pasa de unos pocos ms, la red está
  congestionada (Wi-Fi): usa Ethernet.

### Wireshark en la PC

1. Captura en la interfaz que va a la TV con el filtro `tcp.port == 3240`.
2. Wireshark trae disector USB/IP: *Analyze → Decode As… → TCP port 3240 → USBIP* si no lo
   reconoce solo.
3. Mide la diferencia de tiempo entre un `USBIP_CMD_SUBMIT` y su `USBIP_RET_SUBMIT` (mismo seqnum) en
   transferencias de **control** (latencia de ida y vuelta real). Para interrupt IN el tiempo incluye
   la espera hasta que mueves el volante, así que no sirve como medida de latencia.
4. Objetivo: ida y vuelta de control < 5 ms por Ethernet; en Wi-Fi vigila los picos.

### Prueba en juego

Gira el volante rápido de tope a tope en un juego con indicador de dirección (o en la ventana de
prueba de G HUB) y compara con el volante conectado directo a la PC. Anota si percibes retraso
o tirones.

## F5 · Integración con Steam Link

Checklist:

- [ ] Con el volante **exportado** (montado en la PC), Steam Link en la TV **no** lo ve como mando.
      La app reclama las interfaces con `force = true`, lo que desconecta el driver HID de Android.
- [ ] Al abrir Steam Link, la app sigue viva: la notificación "Servidor USB/IP activo" sigue presente
      y la sesión no se cae (servicio en primer plano + wake lock + Wi-Fi lock).
- [ ] Probar 2 o 3 juegos con force feedback. Anotar configuración:

| Juego | Ejes/pedales | Force feedback | Steam Input | Notas |
| --- | --- | --- | --- | --- |
| | | | | |
| | | | | |
| | | | | |

- [ ] Reiniciar la TV con *Iniciar al encender: sí* y comprobar que el servidor arranca solo.
- [ ] Desconectar y reconectar el volante a mitad de partida: el vigilante de la PC debe re-montarlo.

## APK firmado

El keystore ya existe (`release.jks` + `keystore.properties` en la raíz, fuera de git) y
`tools/release.ps1` lo usa para compilar y publicar. Respáldalo. Solo si lo pierdes (en ese caso, hay que
desinstalar la app de la TV antes de instalar versiones nuevas):

1. Crear un keystore nuevo:
   ```powershell
   keytool -genkeypair -v -keystore release.jks -alias usbip -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Crear `keystore.properties` en la raíz del proyecto (ignorado por git):
   ```properties
   storeFile=release.jks
   storePassword=...
   keyAlias=usbip
   keyPassword=...
   ```
3. `.\gradlew :app:assembleRelease` → `app\build\outputs\apk\release\app-release.apk`.
4. Instalar con `adb install -r app-release.apk`, o subirlo a un servidor/Drive y bajarlo en la TV con
   la app *Downloader*.
