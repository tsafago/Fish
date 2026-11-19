param(
    [string]$Source = '',
    [string]$Destination = '',
    [switch]$AllUsers,
    [switch]$DesktopShortcut,
    [switch]$NoStartMenuShortcut,
    [switch]$NoRegistry,
    [switch]$Launch,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $Destination) {
    $Destination = if ($AllUsers) { Join-Path $env:ProgramFiles 'Fish' } else { Join-Path $env:LOCALAPPDATA 'Programs\Fish' }
}

if ($AllUsers -and -not $isAdmin -and $env:FISH_TEST_NO_ELEVATE -ne '1') {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $PSCommandPath), '-AllUsers')
    if ($Source) { $argList += @('-Source', ('"{0}"' -f ([IO.Path]::GetFullPath($Source)))) }
    if ($Destination) { $argList += @('-Destination', ('"{0}"' -f ([IO.Path]::GetFullPath($Destination)))) }
    if ($DesktopShortcut) { $argList += '-DesktopShortcut' }
    if ($NoStartMenuShortcut) { $argList += '-NoStartMenuShortcut' }
    if ($NoRegistry) { $argList += '-NoRegistry' }
    if ($Launch) { $argList += '-Launch' }
    if ($Quiet) { $argList += '-Quiet' }
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList ($argList -join ' ') -Verb RunAs -Wait -PassThru
    exit $proc.ExitCode
}

function Initialize-ShellNotify {
    if ('FishShell.Notify' -as [type]) { return }
    try {
        Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
namespace FishShell {
    public static class Notify {
        private const int SHCNE_CREATE = 0x00000002;
        private const int SHCNE_DELETE = 0x00000004;
        private const int SHCNE_UPDATEDIR = 0x00001000;
        private const int SHCNE_UPDATEITEM = 0x00002000;
        private const uint SHCNF_PATHW = 0x0005;
        private const uint SHCNF_FLUSHNOWAIT = 0x2000;
        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        private static extern void SHChangeNotify(int wEventId, uint uFlags, string dwItem1, string dwItem2);
        public static void Saved(string path, bool existed) {
            if (string.IsNullOrEmpty(path)) return;
            SHChangeNotify(existed ? SHCNE_UPDATEITEM : SHCNE_CREATE, SHCNF_PATHW | SHCNF_FLUSHNOWAIT, path, null);
            SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW | SHCNF_FLUSHNOWAIT, path, null);
            string dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir)) SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_PATHW | SHCNF_FLUSHNOWAIT, dir, null);
        }
        public static void Deleted(string path) {
            if (string.IsNullOrEmpty(path)) return;
            SHChangeNotify(SHCNE_DELETE, SHCNF_PATHW | SHCNF_FLUSHNOWAIT, path, null);
            string dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir)) SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_PATHW | SHCNF_FLUSHNOWAIT, dir, null);
        }
    }
}
'@ -ErrorAction SilentlyContinue
    } catch {}
}

function Remove-TreeForce([string]$Path) {
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return }
    Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            if ($_.Attributes -band [IO.FileAttributes]::ReadOnly) {
                $_.Attributes = ($_.Attributes -bxor [IO.FileAttributes]::ReadOnly)
            }
        } catch {}
    }
    try {
        $rootItem = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        if ($rootItem -and ($rootItem.Attributes -band [IO.FileAttributes]::ReadOnly)) {
            $rootItem.Attributes = ($rootItem.Attributes -bxor [IO.FileAttributes]::ReadOnly)
        }
    } catch {}
    Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue
}

function Remove-ZoneIdentifier([string]$Root) {
    Get-ChildItem -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            if ($_.Attributes -band [IO.FileAttributes]::ReadOnly) {
                $_.Attributes = ($_.Attributes -bxor [IO.FileAttributes]::ReadOnly)
            }
        } catch {}
        try { Unblock-File -LiteralPath $_.FullName -ErrorAction SilentlyContinue } catch {}
        try { Remove-Item -LiteralPath ($_.FullName + ':Zone.Identifier') -Force -ErrorAction SilentlyContinue } catch {}
    }
}

function New-FishShortcut([string]$ShortcutPath, [string]$TargetExe) {
    $parent = Split-Path -Parent $ShortcutPath
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $existed = Test-Path -LiteralPath $ShortcutPath
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($ShortcutPath)
    $link.TargetPath = $TargetExe
    $link.WorkingDirectory = (Split-Path -Parent $TargetExe)
    $link.IconLocation = "$TargetExe,0"
    $link.Description = 'Fish 5250 Workspace'
    $link.Save()
    try { [IO.File]::SetAttributes($ShortcutPath, [IO.FileAttributes]::Archive) } catch {}
    Initialize-ShellNotify
    if ('FishShell.Notify' -as [type]) { [FishShell.Notify]::Saved($ShortcutPath, [bool]$existed) }
}

function Remove-FishShortcut([string]$ShortcutPath) {
    if (-not $ShortcutPath -or -not (Test-Path -LiteralPath $ShortcutPath)) { return }
    try {
        $item = Get-Item -LiteralPath $ShortcutPath -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReadOnly)) {
            $item.Attributes = ($item.Attributes -bxor [IO.FileAttributes]::ReadOnly)
        }
    } catch {}
    Remove-Item -LiteralPath $ShortcutPath -Force -ErrorAction SilentlyContinue
    Initialize-ShellNotify
    if ('FishShell.Notify' -as [type]) { [FishShell.Notify]::Deleted($ShortcutPath) }
}

$targetDir = [IO.Path]::GetFullPath($Destination.TrimEnd('\'))
if ($targetDir.Length -lt 4 -or $targetDir[1] -ne ':') {
    throw 'Destination must be a full local Windows path.'
}
$targetExe = Join-Path $targetDir 'Fish.exe'

$running = Get-Process -Name 'Fish' -ErrorAction SilentlyContinue | Where-Object {
    try { $_.MainModule.FileName -ieq $targetExe } catch { $false }
}
if ($running) {
    throw "Fish is currently running from $targetDir. Close all Fish windows before updating this installation."
}

if ((Test-Path -LiteralPath $targetDir) -and -not (Test-Path -LiteralPath $targetExe)) {
    $entries = @(Get-ChildItem -LiteralPath $targetDir -Force)
    if ($entries.Count -gt 0) {
        throw "Destination folder '$targetDir' is not empty and is not an existing Fish installation."
    }
}

$tempExtract = $null
try {
    $resolvedSource = $null
    if ($Source) {
        $candidate = [IO.Path]::GetFullPath($Source)
        if (-not (Test-Path -LiteralPath $candidate)) { throw "Source path not found: $candidate" }
        if ((Get-Item -LiteralPath $candidate).PSIsContainer) {
            if (Test-Path -LiteralPath (Join-Path $candidate 'Fish.exe')) { $resolvedSource = $candidate }
            elseif (Test-Path -LiteralPath (Join-Path $candidate 'Fish\Fish.exe')) { $resolvedSource = Join-Path $candidate 'Fish' }
            else { throw "Folder '$candidate' does not contain Fish.exe." }
        } elseif ($candidate.EndsWith('.zip', [StringComparison]::OrdinalIgnoreCase)) {
            $tempExtract = Join-Path ([IO.Path]::GetTempPath()) ('Fish-install-' + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
            try { Unblock-File -LiteralPath $candidate -ErrorAction SilentlyContinue } catch {}
            Expand-Archive -LiteralPath $candidate -DestinationPath $tempExtract -Force
            if (Test-Path -LiteralPath (Join-Path $tempExtract 'Fish.exe')) { $resolvedSource = $tempExtract }
            elseif (Test-Path -LiteralPath (Join-Path $tempExtract 'Fish\Fish.exe')) { $resolvedSource = Join-Path $tempExtract 'Fish' }
            else { throw "Archive '$candidate' does not contain a Fish application image." }
        } else {
            throw 'Source must be a Fish app-image directory or portable .zip archive.'
        }
    } elseif (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'Fish.exe')) {
        $resolvedSource = $PSScriptRoot
    } elseif (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'dist\Fish\Fish.exe')) {
        $resolvedSource = Join-Path $PSScriptRoot 'dist\Fish'
    } else {
        $localZip = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'dist') -Filter 'Fish-*-windows-x64.zip' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($localZip) {
            $tempExtract = Join-Path ([IO.Path]::GetTempPath()) ('Fish-install-' + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
            try { Unblock-File -LiteralPath $localZip.FullName -ErrorAction SilentlyContinue } catch {}
            Expand-Archive -LiteralPath $localZip.FullName -DestinationPath $tempExtract -Force
            $resolvedSource = if (Test-Path -LiteralPath (Join-Path $tempExtract 'Fish\Fish.exe')) { Join-Path $tempExtract 'Fish' } else { $tempExtract }
        } else {
            if (-not $Quiet) { Write-Host 'Downloading latest verified Fish portable release from GitHub...' }
            $tempExtract = Join-Path ([IO.Path]::GetTempPath()) ('Fish-install-' + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
            $releasesJson = & curl.exe --fail --location --silent --show-error 'https://api.github.com/repos/tsafago/Fish/releases?per_page=10' | Out-String
            if ($LASTEXITCODE -ne 0 -or -not $releasesJson) { throw 'Could not query GitHub releases. Build locally with .\Setup-Fish.ps1 or pass -Source.' }
            $release = @($releasesJson | ConvertFrom-Json) | Where-Object { -not $_.draft } | Select-Object -First 1
            if (-not $release) { throw 'No published GitHub release found for tsafago/Fish.' }
            $zipAsset = $release.assets | Where-Object { $_.name -match '^Fish-.*-windows-x64\.zip$' } | Select-Object -First 1
            $sumAsset = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS.txt' } | Select-Object -First 1
            if (-not $zipAsset -or -not $sumAsset) { throw 'Latest GitHub release is missing portable ZIP or SHA256SUMS.txt.' }
            $zipPath = Join-Path $tempExtract $zipAsset.name
            $sumPath = Join-Path $tempExtract 'SHA256SUMS.txt'
            & curl.exe --fail --location --silent --show-error $sumAsset.browser_download_url --output $sumPath
            if ($LASTEXITCODE -ne 0) { throw 'Could not download SHA256SUMS.txt.' }
            & curl.exe --fail --location --silent --show-error $zipAsset.browser_download_url --output $zipPath
            if ($LASTEXITCODE -ne 0) { throw 'Could not download portable Fish archive.' }
            $expectedLine = Get-Content -LiteralPath $sumPath | Where-Object { $_ -match ([regex]::Escape($zipAsset.name) + '$') } | Select-Object -First 1
            if (-not $expectedLine) { throw 'SHA256SUMS.txt does not list the portable ZIP archive.' }
            $expectedHash = ($expectedLine -split '\s+')[0].ToLowerInvariant()
            $actualHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualHash -ne $expectedHash) { throw "SHA-256 mismatch for $($zipAsset.name): expected $expectedHash, got $actualHash." }
            $expanded = Join-Path $tempExtract 'expanded'
            Expand-Archive -LiteralPath $zipPath -DestinationPath $expanded -Force
            $resolvedSource = if (Test-Path -LiteralPath (Join-Path $expanded 'Fish\Fish.exe')) { Join-Path $expanded 'Fish' } else { $expanded }
        }
    }

    $sourceFull = [IO.Path]::GetFullPath($resolvedSource.TrimEnd('\'))
    if ($sourceFull -ieq $targetDir) {
        throw 'Source and destination are the same directory.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $sourceFull 'Fish.exe'))) {
        throw "Invalid Fish image at '$sourceFull' (Fish.exe missing)."
    }

    $staging = $targetDir + '.staging-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetDir) | Out-Null
    Copy-Item -LiteralPath $sourceFull -Destination $staging -Recurse -Force
    $uninstallerSource = Join-Path $PSScriptRoot 'Uninstall-Fish.ps1'
    if (Test-Path -LiteralPath $uninstallerSource) {
        Copy-Item -LiteralPath $uninstallerSource -Destination (Join-Path $staging 'Uninstall-Fish.ps1') -Force
    }
    Remove-ZoneIdentifier -Root $staging

    if ($env:FISH_TEST_SKIP_REGISTRY -ne '1') {
        try {
            $msi = New-Object -ComObject WindowsInstaller.Installer
            $products = @($msi.RelatedProducts('{55B0674D-E226-46CB-852F-6BEDDD35A74B}'))
            foreach ($code in $products) {
                $assign = ''
                try { $assign = [string]$msi.ProductInfo($code, 'AssignmentType') } catch {}
                if ($assign -eq '1' -and -not $isAdmin) { continue }
                $msiArgs = "/x $code /qn /norestart MSIRESTARTMANAGERCONTROL=Disable UPGRADINGPRODUCTCODE={55B0674D-E226-46CB-852F-6BEDDD35A74B}"
                Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Wait -WindowStyle Hidden | Out-Null
            }
        } catch {}
    }

    $backup = $null
    if (Test-Path -LiteralPath $targetDir) {
        $backup = $targetDir + '.old-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
        Move-Item -LiteralPath $targetDir -Destination $backup
    }
    try {
        Move-Item -LiteralPath $staging -Destination $targetDir
        if ($backup -and (Test-Path -LiteralPath $backup)) {
            Remove-TreeForce -Path $backup
        }
    } catch {
        if (-not (Test-Path -LiteralPath $targetDir) -and $backup -and (Test-Path -LiteralPath $backup)) {
            Move-Item -LiteralPath $backup -Destination $targetDir
        }
        throw
    }

    $localAppBase = if ($env:FISH_TEST_LOCALAPPDATA) {
        $env:FISH_TEST_LOCALAPPDATA
    } elseif ($env:FISH_TEST_SKIP_REGISTRY -ne '1') {
        $env:LOCALAPPDATA
    } else {
        ''
    }
    if ($localAppBase) {
        foreach ($alt in @((Join-Path $localAppBase 'Programs\Fish'), (Join-Path $localAppBase 'Fish'))) {
            $altFull = [IO.Path]::GetFullPath($alt.TrimEnd('\'))
            if ($altFull -ieq $targetDir) { continue }
            $altExe = Join-Path $altFull 'Fish.exe'
            if (-not (Test-Path -LiteralPath $altExe)) { continue }
            $altRunning = Get-Process -Name 'Fish' -ErrorAction SilentlyContinue | Where-Object {
                try { $_.MainModule.FileName -ieq $altExe } catch { $false }
            }
            if (-not $altRunning) {
                Remove-TreeForce -Path $altFull
            }
        }
    }

    $version = (Get-Item -LiteralPath $targetExe).VersionInfo.ProductVersion
    if (-not $version) { $version = '0.10.2' }
    $sizeBytes = (Get-ChildItem -LiteralPath $targetDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
    $sizeKb = [int][Math]::Ceiling($sizeBytes / 1024.0)
    $uninstallScript = Join-Path $targetDir 'Uninstall-Fish.ps1'
    $uninstallCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallScript`""
    if ($AllUsers) { $uninstallCmd += ' -AllUsers' }

    if (-not $NoRegistry -and $env:FISH_TEST_SKIP_REGISTRY -ne '1') {
        $regPath = if ($AllUsers) {
            'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
        } else {
            'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
        }
        New-Item -Path $regPath -Force | Out-Null
        Set-ItemProperty -Path $regPath -Name 'DisplayName' -Value 'Fish'
        Set-ItemProperty -Path $regPath -Name 'DisplayVersion' -Value $version
        Set-ItemProperty -Path $regPath -Name 'Publisher' -Value 'Fish'
        Set-ItemProperty -Path $regPath -Name 'InstallLocation' -Value $targetDir
        Set-ItemProperty -Path $regPath -Name 'DisplayIcon' -Value "$targetExe,0"
        Set-ItemProperty -Path $regPath -Name 'UninstallString' -Value $uninstallCmd
        Set-ItemProperty -Path $regPath -Name 'QuietUninstallString' -Value ($uninstallCmd + ' -Quiet')
        Set-ItemProperty -Path $regPath -Name 'NoModify' -Value 1 -Type DWord
        Set-ItemProperty -Path $regPath -Name 'NoRepair' -Value 1 -Type DWord
        Set-ItemProperty -Path $regPath -Name 'EstimatedSize' -Value $sizeKb -Type DWord
    }

    $programsDir = if ($AllUsers) {
        if ($env:FISH_TEST_COMMON_PROGRAMS) { $env:FISH_TEST_COMMON_PROGRAMS } else { [Environment]::GetFolderPath('CommonPrograms') }
    } else {
        if ($env:FISH_TEST_USER_PROGRAMS) { $env:FISH_TEST_USER_PROGRAMS } else { [Environment]::GetFolderPath('Programs') }
    }
    $desktopDir = if ($AllUsers) {
        if ($env:FISH_TEST_COMMON_DESKTOP) { $env:FISH_TEST_COMMON_DESKTOP } else { [Environment]::GetFolderPath('CommonDesktopDirectory') }
    } else {
        if ($env:FISH_TEST_USER_DESKTOP) { $env:FISH_TEST_USER_DESKTOP } else { [Environment]::GetFolderPath('Desktop') }
    }

    if ($programsDir) {
        $msiSubDir = Join-Path $programsDir 'Fish'
        $msiSubShortcut = Join-Path $msiSubDir 'Fish.lnk'
        if (Test-Path -LiteralPath $msiSubShortcut) {
            Remove-FishShortcut -ShortcutPath $msiSubShortcut
            if ((Test-Path -LiteralPath $msiSubDir) -and @(Get-ChildItem -LiteralPath $msiSubDir -Force -ErrorAction SilentlyContinue).Count -eq 0) {
                Remove-Item -LiteralPath $msiSubDir -Force -ErrorAction SilentlyContinue
            }
        }
        if (-not $NoStartMenuShortcut) {
            $startMenu = Join-Path $programsDir 'Fish.lnk'
            New-FishShortcut -ShortcutPath $startMenu -TargetExe $targetExe
        }
    }
    if ($desktopDir) {
        $desktop = Join-Path $desktopDir 'Fish.lnk'
        if ($DesktopShortcut -or (Test-Path -LiteralPath $desktop)) {
            New-FishShortcut -ShortcutPath $desktop -TargetExe $targetExe
        }
    }

    if (-not $Quiet) {
        if ($AllUsers) {
            Write-Host "Fish $version installed system-wide for all users at: $targetDir"
        } else {
            Write-Host "Fish $version installed for current user at: $targetDir"
            Write-Host 'No administrator privileges required; Mark-of-the-Web cleared to prevent SmartScreen prompts.'
        }
    }
    if ($Launch) {
        Start-Process -FilePath $targetExe -WorkingDirectory $targetDir
    }
} finally {
    if ($tempExtract -and (Test-Path -LiteralPath $tempExtract)) {
        Remove-Item -LiteralPath $tempExtract -Recurse -Force -ErrorAction SilentlyContinue
    }
}
