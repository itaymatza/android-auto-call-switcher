# Source-only bootstrap. No downloaded program is executed until SHA-256 is verified.
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
$Cache = Join-Path $Root '.tools'
$Version = '9.4.1'
$Expected = '2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'
$Exe = Join-Path $Cache "gradle-$Version\bin\gradle.bat"
if (Test-Path $Exe) { exit 0 }
New-Item -ItemType Directory -Force -Path $Cache | Out-Null
$Zip = Join-Path $Cache "gradle-$Version-bin.zip"
$Staging = Join-Path $Cache ('unpack-' + [Guid]::NewGuid().ToString('N'))
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$Version-bin.zip" -OutFile $Zip
    $Actual = (Get-FileHash -Algorithm SHA256 -Path $Zip).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected) { throw 'Gradle SHA-256 mismatch. Refusing to execute.' }
    Expand-Archive -LiteralPath $Zip -DestinationPath $Staging
    Move-Item -LiteralPath (Join-Path $Staging "gradle-$Version") -Destination $Cache
    Write-Host "Installed checksum-verified Gradle $Version under .tools/"
} finally {
    if (Test-Path $Zip) { Remove-Item -LiteralPath $Zip -Force }
    if (Test-Path $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
}
