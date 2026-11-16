param(
    [string]$Source = '',
    [string]$Destination = (Join-Path $env:LOCALAPPDATA 'Programs\Fish'),
    [switch]$DesktopShortcut,
    [switch]$NoStartMenuShortcut,
    [switch]$NoRegistry,
    [switch]$Launch,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'

function Remove-ZoneIdentifier([string]$Root) {
    Get-ChildItem -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {
        try { Unblock-File -LiteralPath $_.FullName -ErrorAction SilentlyContinue } catch {}
        try { Remove-Item -LiteralPath ($_.FullName + ':Zone.Identifier') -Force -ErrorAction SilentlyContinue } catch {}
    }
}

function New-FishShortcut([string]$ShortcutPath, [string]$TargetExe) {
    $parent = Split-Path -Parent $ShortcutPath
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($ShortcutPath)
    $link.TargetPath = $TargetExe
    $link.WorkingDirectory = (Split-Path -Parent $TargetExe)
    $link.IconLocation = "$TargetExe,0"
    $link.Description = 'Fish 5250 Workspace'
    $link.Save()
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

    $backup = $null
    if (Test-Path -LiteralPath $targetDir) {
        $backup = $targetDir + '.old-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
        Move-Item -LiteralPath $targetDir -Destination $backup
    }
    try {
        Move-Item -LiteralPath $staging -Destination $targetDir
        if ($backup -and (Test-Path -LiteralPath $backup)) {
            Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
        }
    } catch {
        if (-not (Test-Path -LiteralPath $targetDir) -and $backup -and (Test-Path -LiteralPath $backup)) {
            Move-Item -LiteralPath $backup -Destination $targetDir
        }
        throw
    }

    $version = (Get-Item -LiteralPath $targetExe).VersionInfo.ProductVersion
    if (-not $version) { $version = '0.9.0' }
    $sizeBytes = (Get-ChildItem -LiteralPath $targetDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
    $sizeKb = [int][Math]::Ceiling($sizeBytes / 1024.0)
    $uninstallScript = Join-Path $targetDir 'Uninstall-Fish.ps1'
    $uninstallCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallScript`""

    if (-not $NoRegistry) {
        $regPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
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

    if (-not $NoStartMenuShortcut) {
        $startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Fish.lnk'
        New-FishShortcut -ShortcutPath $startMenu -TargetExe $targetExe
    }
    if ($DesktopShortcut) {
        $desktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Fish.lnk'
        New-FishShortcut -ShortcutPath $desktop -TargetExe $targetExe
    }

    if (-not $Quiet) {
        Write-Host "Fish $version installed for current user at: $targetDir"
        Write-Host 'No administrator privileges required; Mark-of-the-Web cleared to prevent SmartScreen prompts.'
    }
    if ($Launch) {
        Start-Process -FilePath $targetExe -WorkingDirectory $targetDir
    }
} finally {
    if ($tempExtract -and (Test-Path -LiteralPath $tempExtract)) {
        Remove-Item -LiteralPath $tempExtract -Recurse -Force -ErrorAction SilentlyContinue
    }
}
