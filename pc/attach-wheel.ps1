<#
.SYNOPSIS
    Monta el volante Logitech exportado por la app de Google TV usando usbip-win2,
    y opcionalmente lo vuelve a montar solo cuando se cae (re-enumeración, Wi-Fi, reinicio de la TV).

.EXAMPLE
    .\attach-wheel.ps1 -TvIp 192.168.1.50
    .\attach-wheel.ps1 -TvIp 192.168.1.50 -Watch          # vigilante: re-monta automáticamente
    .\attach-wheel.ps1 -TvIp 192.168.1.50 -BusId 1-2      # otro dispositivo
    .\attach-wheel.ps1 -Detach                            # desmonta todo
#>
param(
    [string]$TvIp,
    [string]$BusId,
    [switch]$Watch,
    [int]$IntervalSeconds = 3,
    [switch]$Detach,
    [string]$UsbIp = "$env:ProgramFiles\USBip\usbip.exe"
)

$ErrorActionPreference = 'Stop'
$LogitechVid = '046d'

if (-not (Test-Path $UsbIp)) {
    $cmd = Get-Command usbip.exe -ErrorAction SilentlyContinue
    if ($cmd) { $UsbIp = $cmd.Source }
    else { throw "No encuentro usbip.exe. Instala usbip-win2: winget install --id vadimgrn.usbip-win2 --exact" }
}

if ($Detach) {
    & $UsbIp detach --all
    return
}
if (-not $TvIp) { throw "Falta -TvIp (la IP que muestra la app en la TV)" }

function Find-Wheel {
    # Ejemplo de salida:
    #         1-1: Logitech, Inc. : G29 Driving Force Racing Wheel [PS4] (046d:c24f)
    $lines = & $UsbIp list -r $TvIp 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    foreach ($line in $lines) {
        if ("$line" -match '^\s+(\d+-[\d.]+):.*\(([0-9a-f]{4}):([0-9a-f]{4})\)') {
            if ($BusId) { if ($Matches[1] -eq $BusId) { return $Matches[1] } }
            elseif ($Matches[2] -eq $LogitechVid) { return $Matches[1] }
        }
    }
    return $null
}

function Test-Attached([string]$id) {
    # usbip port lista los dispositivos montados como usbip://<ip>:3240/<busid>
    $ports = & $UsbIp port 2>&1 | Out-String
    return $ports -match [regex]::Escape("$TvIp") -and $ports -match ("/" + [regex]::Escape($id) + '\b')
}

# Devuelve el busid montado, o $null. (Un dispositivo ya montado no aparece en 'list'.)
function Mount-Wheel {
    $id = Find-Wheel
    if (-not $id) { return $null }
    Write-Host "$(Get-Date -Format HH:mm:ss) Montando $id desde $TvIp..."
    & $UsbIp attach -r $TvIp -b $id | Write-Host
    if ($LASTEXITCODE -eq 0) { return $id }
    return $null
}

if (-not $Watch) {
    if (-not (Mount-Wheel)) {
        & $UsbIp list -r $TvIp
        throw "No se encontró el volante en $TvIp (¿app iniciada, permiso USB concedido, mismo segmento de red?)"
    }
    return
}

# Vigilante: la app conserva el busid cuando el volante se re-enumera, así que basta con
# volver a montarlo en cuanto deja de aparecer en 'usbip port'.
Write-Host "Vigilando el volante en $TvIp cada $IntervalSeconds s (Ctrl+C para salir)"
$current = $BusId
while ($true) {
    try {
        if (-not $current -or -not (Test-Attached $current)) {
            $mounted = Mount-Wheel
            if ($mounted) { $current = $mounted }
        }
    } catch {
        Write-Warning $_
    }
    Start-Sleep -Seconds $IntervalSeconds
}
