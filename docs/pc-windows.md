# Lado PC (Windows): cliente USB/IP, drivers y Steam

En la PC no programas nada: instalas el cliente, montas el volante y lo usas como si estuviera
conectado por cable.

## 1. Instalar usbip-win2

```powershell
winget install --id vadimgrn.usbip-win2 --exact
```

- Requiere Windows 10 1809 o posterior, x64.
- Usa **la versión más reciente**: versiones anteriores podían causar pantalla azul bajo carga.
- El instalador reinicia los hubs USB 3.0 (todos los USB se cortan un momento). Crea antes un
  punto de restauración.

## 2. Firma del driver

Si la versión que instalas no tiene drivers firmados por Microsoft, su guía pide activar
*Test Signing Mode* (`bcdedit /set testsigning on` y reiniciar). Revisa las notas de la versión antes
de cambiar el arranque, y **no lo desactives mientras el driver esté instalado**.

## 3. Montar el volante

**Con el programa de Windows (recomendado):** abre `VolanteUsbIp-PC-X.Y.Z.exe` (de Releases), escribe
la IP que muestra la TV y pulsa *Conectar*. Con *Reconectar automáticamente* vuelve a montar el
volante si se cae, e *Iniciar con Windows* lo deja en la bandeja al arrancar.

**Con PowerShell:**

```powershell
.\pc\attach-wheel.ps1 -TvIp 192.168.1.50            # una vez
.\pc\attach-wheel.ps1 -TvIp 192.168.1.50 -Watch     # vigilante: re-monta si se cae
.\pc\attach-wheel.ps1 -Detach                       # desmontar todo
```

Equivale a:

```powershell
usbip.exe list -r 192.168.1.50
usbip.exe attach -r 192.168.1.50 -b 1-1
```

**Auto-reconexión:** `usbip.exe attach` ya reintenta en segundo plano cuando no puede conectar. Con
el script, deja `-Watch` corriendo mientras juegas. Si el volante se re-enumera, la TV se
reinicia o se corta la red, el script lo vuelve a montar con el mismo busid. Para que arranque con
Windows, crea una tarea programada que ejecute
`powershell -WindowStyle Hidden -File <ruta>\pc\attach-wheel.ps1 -TvIp <IP> -Watch` al iniciar sesión.
Conviene darle a la TV una IP fija (reserva DHCP en el router).

## 4. Drivers de Logitech

Con el volante montado, G HUB (o Logitech Gaming Software para modelos viejos) debe detectarlo:
calibra y usa la prueba de force feedback.

## 5. Steam

El volante ya es un dispositivo local de la PC: Steam Link solo transporta video. Si un juego lo
detecta raro, desactiva *Steam Input* para ese juego (Propiedades → Controlador).

## Firewall y red

- La PC se conecta **hacia** la TV (puerto 3240 de la TV), así que normalmente no hay que abrir nada
  en el firewall de Windows.
- Ethernet en la TV y en la PC reduce la latencia y los cortes; es la principal mitigación de riesgo
  por red.
