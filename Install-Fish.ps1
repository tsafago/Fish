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
    if ($Quiet) { $argList += '-Quiet' }
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList ($argList -join ' ') -Verb RunAs -PassThru
    $proc.WaitForExit()
    if ($proc.ExitCode -eq 0 -and $Launch) {
        try { Start-Process -FilePath (Join-Path $Destination 'Fish.exe') -WorkingDirectory $Destination }
        catch { Write-Warning "Fish is installed. Open it from the Start menu. Windows could not open it automatically: $($_.Exception.Message)" }
    }
    exit $proc.ExitCode
}

function Assert-NoReparseParent([IO.DirectoryInfo]$Dir) {
    while ($null -ne $Dir) {
        if ([int]$Dir.Attributes -ne -1 -and ($Dir.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to follow a link at '$($Dir.FullName)'."
        }
        $Dir = $Dir.Parent
    }
}

function Read-ImageInventory([IO.FileInfo]$File, [string]$Root) {
    if ($File.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a linked Fish inventory.' }
    $lines = [IO.File]::ReadAllLines($File.FullName)
    if ($lines.Length -lt 4 -or $lines[0] -cne 'Fish script image 1' -or $lines[1] -ine $Root -or $lines[2] -notin @('machine','user')) {
        throw "Inventory does not identify a script installation at '$Root'."
    }
    $files = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    for ($i = 3; $i -lt $lines.Length; $i++) { [void]$files.Add($lines[$i]) }
    [pscustomobject]@{ Scope = $lines[2]; Files = $files }
}

function Move-Image([string]$From,[string]$To) {
    # Newly copied executables may be held briefly by an on-access scanner.
    for ($i=0;;$i++) {
        try { [IO.Directory]::Move($From,$To); return }
        catch {
            $code=$_.Exception.GetBaseException().HResult -band 0xffff
            if ($i -ge 5 -or $code -notin @(5,32,33)) { throw }
            Start-Sleep -Milliseconds (100*($i+1))
        }
    }
}

# Inventory during the copy, preserving Windows file metadata.
function Copy-Image([IO.DirectoryInfo]$Dir, [string]$Prefix) {
    foreach ($entry in $Dir.EnumerateFileSystemInfos()) {
        $rel = $Prefix + $entry.Name
        if ($rel -ieq $inventoryName) { continue }
        $key = if ($entry -is [IO.DirectoryInfo]) { $rel + '\' } else { $rel }
        if ($sourceImage -and -not $sourceImage.Files.Contains($key)) { continue }
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Image contains a link: $rel" }
        $outPath = [IO.Path]::Combine($staging,$rel)
        if ($entry -is [IO.DirectoryInfo]) {
            [void][IO.Directory]::CreateDirectory($outPath)
            $inventory.Add($rel + '\')
            Copy-Image $entry ($rel + '\')
        } else {
            [IO.File]::Copy($entry.FullName,$outPath,$false)
            $inventory.Add($rel)
        }
    }
}

function Visit-OldImage([IO.DirectoryInfo]$Dir, [string]$Prefix, [switch]$Remove) {
    foreach ($entry in $Dir.EnumerateFileSystemInfos()) {
        $rel = $Prefix + $entry.Name
        if ($entry -is [IO.DirectoryInfo]) { $rel += '\' }
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or -not $oldFiles.Contains($rel)) {
            throw "Not replacing '$targetDir': '$rel' is not part of this script installation. Use an empty destination."
        }
        if ($entry -is [IO.DirectoryInfo]) { Visit-OldImage $entry $rel -Remove:$Remove }
        if ($Remove) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReadOnly) { $entry.Attributes = $entry.Attributes -bxor [IO.FileAttributes]::ReadOnly }
            $entry.Delete() # Nonrecursive: do not sweep up newly added files in the backup.
        }
    }
}

function New-FishShortcut([string]$ShortcutPath, [string]$TargetExe) {
    $parent = Split-Path -Parent $ShortcutPath
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $shell = New-Object -ComObject WScript.Shell
    $file = [IO.FileInfo]::new($ShortcutPath)
    if ([int]$file.Attributes -ne -1 -and ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return }
    $link = $shell.CreateShortcut($ShortcutPath)
    if ($file.Exists -and ($link.TargetPath -ine $TargetExe -or $link.Arguments -or $link.Description -cne 'Fish 5250 Workspace')) {
        Write-Warning "Leaving existing shortcut '$ShortcutPath' unchanged."
        return
    }
    $link.TargetPath = $TargetExe
    $link.WorkingDirectory = (Split-Path -Parent $TargetExe)
    $link.IconLocation = "$TargetExe,0"
    $link.Description = 'Fish 5250 Workspace'
    $link.Save()
}

$targetDir = [IO.Path]::GetFullPath($Destination).TrimEnd('\')
if ($targetDir -notmatch '^[A-Za-z]:\\.+') {
    throw 'Destination must be a full local Windows path.'
}
Assert-NoReparseParent $targetDir
$targetExe = Join-Path $targetDir 'Fish.exe'
$inventoryName = '.fish-script-image'
$scope = if ($AllUsers) { 'machine' } else { 'user' }

$running = Get-Process -Name 'Fish' -ErrorAction SilentlyContinue | Where-Object {
    try { $_.MainModule.FileName -ieq $targetExe } catch { $false }
}
if ($running) {
    throw "Fish is currently running from $targetDir. Close all Fish windows before updating this installation."
}

$oldFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
if ([IO.Directory]::Exists($targetDir)) {
    $oldInventory = [IO.FileInfo]::new((Join-Path $targetDir $inventoryName))
    if ($oldInventory.Exists) {
        $oldImage = Read-ImageInventory $oldInventory $targetDir
        if ($oldImage.Scope -ne $scope) { throw 'Existing script installation has a different scope.' }
        $oldFiles = $oldImage.Files
        [void]$oldFiles.Add($inventoryName)
    } elseif ([IO.Directory]::GetFileSystemEntries($targetDir).Length) {
        throw "Not replacing '$targetDir': it has files but no script inventory. Use an empty destination."
    }
}

$useRegistry = -not $NoRegistry -and $env:FISH_TEST_SKIP_REGISTRY -ne '1'
$regPath = if ($AllUsers) { 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish' }
           else { 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish' }
if ($useRegistry -and (Test-Path -LiteralPath $regPath) -and (Get-ItemProperty -LiteralPath $regPath).InstallLocation -ine $targetDir) {
    throw 'Another Fish script installation is registered for this scope. Uninstall it first or use -NoRegistry.'
}
$staging = $null
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

    $sourceFull = [IO.Path]::GetFullPath($resolvedSource).TrimEnd('\')
    Assert-NoReparseParent $sourceFull
    if ($sourceFull -ieq $targetDir -or $targetDir.StartsWith($sourceFull + '\',[StringComparison]::OrdinalIgnoreCase) -or
            $sourceFull.StartsWith($targetDir + '\',[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Source and destination directories must not overlap.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $sourceFull 'Fish.exe'))) {
        throw "Invalid Fish image at '$sourceFull' (Fish.exe missing)."
    }
    # Settings can promote a per-user script install to machine scope. Do not copy its
    # inventory (which belongs to the old location), or data the user added beside it.
    $sourceInventory = [IO.FileInfo]::new((Join-Path $sourceFull $inventoryName))
    $sourceImage = if ($sourceInventory.Exists) { Read-ImageInventory $sourceInventory $sourceFull }

    # Keep temporary sibling paths short enough for Windows PowerShell 5.1.
    $parentDir = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($targetDir))
    $staging = (New-Item -ItemType Directory -Path (Join-Path $parentDir.FullName ([IO.Path]::GetRandomFileName())) -ErrorAction Stop).FullName
    $inventory = [Collections.Generic.List[string]]::new()
    $inventory.Add('Fish script image 1')
    $inventory.Add($targetDir)
    $inventory.Add($scope)
    Copy-Image $sourceFull ''
    foreach ($required in @('Fish.exe','app\Fish.cfg','app\Fish.jar','app\fish-native.dll','runtime\lib\modules','runtime\bin\jli.dll','runtime\bin\server\jvm.dll')) {
        $file = [IO.FileInfo]::new((Join-Path $staging $required))
        if (-not $file.Exists -or !$file.Length) { throw "Incomplete Fish image: '$required' is missing or empty. No installation was replaced." }
    }
    $cfg = [IO.File]::ReadAllLines((Join-Path $staging 'app\Fish.cfg'))
    if ($cfg -cnotcontains 'app.mainclass=fish.Main' -or $cfg -cnotcontains 'app.classpath=$APPDIR\Fish.jar') {
        throw 'Invalid Fish launcher configuration. Use the complete portable download.'
    }
    $uninstallerSource = Join-Path $PSScriptRoot 'Uninstall-Fish.ps1'
    $stagedUninstaller = Join-Path $staging 'Uninstall-Fish.ps1'
    if ([IO.File]::Exists($uninstallerSource)) {
        [IO.File]::Copy($uninstallerSource,$stagedUninstaller,$true)
        if (-not $inventory.Contains('Uninstall-Fish.ps1')) { $inventory.Add('Uninstall-Fish.ps1') }
    }
    if (-not [IO.File]::Exists($stagedUninstaller) -or -not [IO.File]::ReadAllText($stagedUninstaller).Contains('Fish script image 1')) {
        throw 'Place the matching Uninstall-Fish.ps1 beside Install-Fish.ps1 or use a matching portable release.'
    }
    [IO.File]::WriteAllLines((Join-Path $staging $inventoryName),$inventory)

    $backup = $null
    if (Test-Path -LiteralPath $targetDir) {
        Assert-NoReparseParent $targetDir
        Visit-OldImage $targetDir ''
        $backup = Join-Path ([IO.Path]::GetDirectoryName($targetDir)) ([IO.Path]::GetRandomFileName())
        Move-Image $targetDir $backup
    }
    try {
        Move-Image $staging $targetDir
        $staging = $null
    } catch {
        if (-not (Test-Path -LiteralPath $targetDir) -and $backup -and (Test-Path -LiteralPath $backup)) {
            Move-Image $backup $targetDir
        }
        throw
    }
    if ($backup) {
        try {
            Visit-OldImage $backup '' -Remove
            [IO.Directory]::Delete($backup)
        } catch { Write-Warning "Could not finish removing old files. Review '$backup': $($_.Exception.Message)" }
    }

    $version = (Get-Item -LiteralPath $targetExe).VersionInfo.ProductVersion
    if (-not $version) { $version = '0.10.5' }
    $uninstallScript = Join-Path $targetDir 'Uninstall-Fish.ps1'
    $uninstallCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallScript`""
    if ($AllUsers) { $uninstallCmd += ' -AllUsers' }

    if ($useRegistry) {
        $sizeBytes = (Get-ChildItem -LiteralPath $targetDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
        $sizeKb = [int][Math]::Ceiling($sizeBytes / 1024.0)
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

    if ($programsDir -and -not $NoStartMenuShortcut) {
        New-FishShortcut -ShortcutPath (Join-Path $programsDir 'Fish.lnk') -TargetExe $targetExe
    }
    if ($desktopDir -and $DesktopShortcut) {
        New-FishShortcut -ShortcutPath (Join-Path $desktopDir 'Fish.lnk') -TargetExe $targetExe
    }

    if (-not $Quiet) {
        if ($AllUsers) {
            Write-Host "Fish $version installed system-wide for all users at: $targetDir"
        } else {
            Write-Host "Fish $version installed for current user at: $targetDir"
            Write-Host 'No administrator privileges required.'
        }
    }
    if ($Launch) {
        if ($isAdmin) { Write-Warning 'Fish is installed. Open Fish from your normal Windows session.' }
        else {
            try { Start-Process -FilePath $targetExe -WorkingDirectory $targetDir }
            catch { Write-Warning "Fish is installed. Open it from the Start menu. Windows could not open it automatically: $($_.Exception.Message)" }
        }
    }
} finally {
    if ($staging -and [IO.Directory]::Exists($staging)) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
    if ($tempExtract -and (Test-Path -LiteralPath $tempExtract)) {
        Remove-Item -LiteralPath $tempExtract -Recurse -Force -ErrorAction SilentlyContinue
    }
}
