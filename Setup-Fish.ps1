param([switch]$Install)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
try {
    if (-not [Environment]::Is64BitOperatingSystem -or -not [Environment]::Is64BitProcess) {
        throw 'Fish requires Windows 10/11 x64 and 64-bit Windows PowerShell.'
    }
    Write-Host 'Fish build setup - no administrator installation or system PATH changes.'
    $manifest = Import-PowerShellDataFile .\tools.psd1
    .\scripts\prepare-tools.ps1 -Kind Jdk
    .\scripts\prepare-tools.ps1 -Kind Native
    .\scripts\prepare-tools.ps1 -Kind Installer
    if (-not (Test-Path vendor\tn5250j\src)) {
        if ((Test-Path vendor\tn5250j) -and (Get-ChildItem vendor\tn5250j -Force | Measure-Object).Count -gt 0) {
            throw 'The core source directory is incomplete but not empty. Preserve it and use a fresh source extraction.'
        }
        .\scripts\prepare-tools.ps1 -Kind Core
        $core = Join-Path $PSScriptRoot ('.tools\' + $manifest.Core.Directory)
        New-Item -ItemType Directory -Force vendor\tn5250j | Out-Null
        Copy-Item (Join-Path $core 'src'),(Join-Path $core 'LICENSE') vendor\tn5250j -Recurse
        [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'vendor\tn5250j\REVISION'),
            "d160492db8adec5d7cf309c6f4feefa162e6565d`n",[Text.UTF8Encoding]::new($false))
    }
    if (Test-Path dist\Fish) {
        $backup = 'dist\Fish-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
        Move-Item -LiteralPath dist\Fish -Destination $backup
        if (Test-Path dist\Fish-source.zip) { Move-Item -LiteralPath dist\Fish-source.zip -Destination ($backup+'-source.zip') }
        Write-Host "Previous application preserved in $backup"
    }
    $jdk = Join-Path $PSScriptRoot ('.tools\' + $manifest.Jdk.Directory)
    .\build.ps1 Package -Jdk $jdk
    .\build.ps1 Installer -Jdk $jdk
    Write-Host ''
    Write-Host 'Ready: dist\Fish\Fish.exe'
    Write-Host 'Installer: dist\Fish-0.4.0-setup.exe (current user; no administrator installation)'
    Write-Host 'Portable ZIP: dist\Fish-0.4.0-windows-x64.zip'
    if ($Install) { Start-Process -FilePath (Join-Path $PSScriptRoot 'dist\Fish-0.4.0-setup.exe') -Wait }
} catch {
    Write-Error -ErrorAction Continue $_.Exception.Message
    exit 1
}
