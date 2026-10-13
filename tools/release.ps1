<#
.SYNOPSIS
    Compila y publica un release en GitHub desde esta PC (sin GitHub Actions).

.DESCRIPTION
    1. Corre las pruebas de usbip-core.
    2. Compila el APK firmado (release.jks + keystore.properties) y el exe de Windows con la misma versión.
    3. Deja todo en dist\vX.Y.Z\ con SHA256SUMS.txt.
    4. Crea el tag vX.Y.Z, lo sube y publica el release con los archivos (gh CLI).

.EXAMPLE
    .\tools\release.ps1 -Version 0.1.0
    .\tools\release.ps1 -Version 0.2.0 -Draft          # release en borrador para revisarlo antes
    .\tools\release.ps1 -Version 0.2.0 -NoPublish      # solo compila en dist\
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,
    [switch]$Draft,
    [switch]$NoPublish
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$tag = "v$Version"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what falló (código $LASTEXITCODE)" } }

# --- Comprobaciones -----------------------------------------------------------
if (-not (Test-Path keystore.properties) -or -not (Test-Path release.jks)) {
    throw "Faltan release.jks / keystore.properties: sin ellos el APK no se puede firmar (ver docs/pruebas-en-hardware.md)."
}
if (-not $NoPublish) {
    if (git status --porcelain) { throw "Hay cambios sin commit. Haz commit antes de publicar un release." }
    gh auth status 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "gh no tiene sesión. Ejecuta: gh auth login" }
    git rev-parse -q --verify "refs/tags/$tag" | Out-Null
    if ($LASTEXITCODE -eq 0) { throw "El tag $tag ya existe." }
}

# --- Compilación --------------------------------------------------------------
Step "Pruebas y APK firmado ($Version)"
& .\gradlew.bat --no-daemon -q ":usbip-core:test" ":app:assembleRelease" "-PappVersion=$Version"
Check "Gradle"

Step "Exe de Windows ($Version)"
dotnet build pc\VolanteUsbIp -c Release -nologo -v q "-p:Version=$Version"
Check "dotnet build"

# --- dist ---------------------------------------------------------------------
Step "Empaquetando en dist\$tag"
$dist = Join-Path $root "dist\$tag"
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory $dist | Out-Null
$apk = Join-Path $dist "VolanteUsbIp-TV-$Version.apk"
$exe = Join-Path $dist "VolanteUsbIp-PC-$Version.exe"
Copy-Item app\build\outputs\apk\release\app-release.apk $apk
Copy-Item pc\VolanteUsbIp\bin\Release\VolanteUsbIp.exe $exe
Copy-Item pc\attach-wheel.ps1 (Join-Path $dist "attach-wheel.ps1")

# Verifica la firma del APK con apksigner del SDK.
$sdk = ((Get-Content local.properties | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '') -replace '\\:', ':'
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter apksigner.bat | Sort-Object FullName -Descending | Select-Object -First 1
if ($apksigner) {
    & $apksigner.FullName verify $apk
    Check "apksigner verify"
}

$sums = Get-ChildItem $dist -File | ForEach-Object { "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name }
$sums | Set-Content -Encoding ascii (Join-Path $dist 'SHA256SUMS.txt')
Get-ChildItem $dist | Format-Table Name, Length -AutoSize

if ($NoPublish) { Write-Host "Listo (sin publicar): $dist"; return }

# --- Publicación --------------------------------------------------------------
Step "Publicando $tag en GitHub"
$notes = @"
## Instalación

**Google TV (servidor):** instala ``VolanteUsbIp-TV-$Version.apk`` con ``adb install -r`` o con la app *Downloader*.
Conecta el volante, acepta el permiso USB y marca *usar de forma predeterminada*.

**PC con Windows (cliente):** ejecuta ``VolanteUsbIp-PC-$Version.exe`` (no necesita instalación; usa .NET Framework 4.8, que ya trae Windows 10/11).
Si falta usbip-win2, el programa ofrece instalarlo con winget. Escribe la IP que muestra la TV y pulsa *Conectar*.

``attach-wheel.ps1`` es la alternativa en línea de comandos.

Sumas SHA-256 en ``SHA256SUMS.txt``.
"@
$notesFile = Join-Path $dist 'NOTES.md'
$notes | Set-Content -Encoding utf8 $notesFile

git tag -a $tag -m "Volante USB/IP $Version"; Check "git tag"
git push origin $tag; Check "git push del tag"

$ghArgs = @('release', 'create', $tag, '--title', "Volante USB/IP $Version", '--notes-file', $notesFile)
if ($Draft) { $ghArgs += '--draft' }
$ghArgs += (Get-ChildItem $dist -File | Where-Object Name -ne 'NOTES.md' | ForEach-Object FullName)
gh @ghArgs; Check "gh release create"
Write-Host "`nRelease publicado: https://github.com/jairsl2206/remote-usb/releases/tag/$tag" -ForegroundColor Green
