param([ValidateSet('Jdk','Native','Installer','Core')][string]$Kind = 'Jdk')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.tools'
New-Item -ItemType Directory -Force $tools | Out-Null
$manifest = Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
$item = $manifest[$Kind]
if (-not $item) { throw "No pinned download configured for $Kind." }
$archive = Join-Path $tools $item.Archive
if (-not (Test-Path -LiteralPath $archive)) {
    Write-Host "Downloading the checksum-pinned, user-local $Kind build tool..."
    & curl.exe --fail --location --silent --show-error --retry 2 $item.Url --output "$archive.partial"
    if ($LASTEXITCODE -ne 0) { throw "$Kind download failed. Check your internet connection and retry." }
    if ((Get-FileHash -LiteralPath "$archive.partial" -Algorithm SHA256).Hash -ne $item.Sha256) {
        throw "$Kind download checksum mismatch. The .partial file was not executed or extracted."
    }
    Move-Item -LiteralPath "$archive.partial" -Destination $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $item.Sha256) {
    throw "$Kind cached archive checksum mismatch: $archive. Remove that file and retry."
}
$destination = Join-Path $tools $item.Directory
if (-not (Test-Path -LiteralPath $destination)) {
    if ($item.ContainsKey('Flat') -and $item.Flat) {
        New-Item -ItemType Directory -Path $destination | Out-Null
        Expand-Archive -LiteralPath $archive -DestinationPath $destination
    } else {
        Expand-Archive -LiteralPath $archive -DestinationPath $tools
    }
}
Write-Host "$Kind ready in $destination"
