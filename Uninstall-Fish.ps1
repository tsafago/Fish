param(
    [string]$InstallDir = '',
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'

$regPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
if (-not $InstallDir) {
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'Fish.exe')) {
        $InstallDir = $PSScriptRoot
    } elseif (Test-Path -LiteralPath $regPath) {
        $InstallDir = (Get-ItemProperty -Path $regPath -Name 'InstallLocation' -ErrorAction SilentlyContinue).InstallLocation
    }
    if (-not $InstallDir) {
        $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\Fish'
    }
}
$targetDir = [IO.Path]::GetFullPath($InstallDir.TrimEnd('\'))
$targetExe = Join-Path $targetDir 'Fish.exe'

$running = Get-Process -Name 'Fish' -ErrorAction SilentlyContinue | Where-Object {
    try { $_.MainModule.FileName -ieq $targetExe } catch { $false }
}
if ($running) {
    throw "Fish is currently running from $targetDir. Close all Fish windows before uninstalling."
}

foreach ($folder in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
    if (-not $folder) { continue }
    $shortcut = Join-Path $folder 'Fish.lnk'
    if (Test-Path -LiteralPath $shortcut) {
        try {
            $shell = New-Object -ComObject WScript.Shell
            $link = $shell.CreateShortcut($shortcut)
            if ($link.TargetPath -ieq $targetExe) {
                Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
}

if (Test-Path -LiteralPath $regPath) {
    Remove-Item -Path $regPath -Recurse -Force -ErrorAction SilentlyContinue
}

if ((Test-Path -LiteralPath $targetDir) -and (Test-Path -LiteralPath $targetExe)) {
    $cleanupCmd = "/c ping 127.0.0.1 -n 2 >nul & rmdir /s /q `"$targetDir`""
    Start-Process -FilePath $env:ComSpec -ArgumentList $cleanupCmd -WindowStyle Hidden
}

if (-not $Quiet) {
    Write-Host "Fish uninstalled from $targetDir. Workspace profiles and Windows Credential Manager entries were preserved."
}
