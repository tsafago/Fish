$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$name = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.8_9.zip'
$url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/$name"
New-Item -ItemType Directory -Force .tools | Out-Null
if (-not (Test-Path '.tools\jdk.zip')) {
    & curl.exe --fail --location --silent --show-error $url --output .tools\jdk.zip
    if ($LASTEXITCODE -ne 0) { throw 'JDK download failed.' }
}
$expected = '5dbb74a4edda94c2aae97a1e30f448db0ad9023a9466fe31a8c68377d9dfbac4'
if ((Get-FileHash .tools\jdk.zip -Algorithm SHA256).Hash -ne $expected) {
    throw 'JDK checksum mismatch. Remove .tools\jdk.zip and retry from a trusted network.'
}
if (-not (Test-Path '.tools\jdk-21.0.8+9\bin\javac.exe')) {
    Expand-Archive .tools\jdk.zip .tools -Force
}
Write-Host 'Portable JDK ready. Run .\build.ps1 Test, then .\build.ps1 Package.'
