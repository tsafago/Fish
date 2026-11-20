[CmdletBinding()]
param(
    [Alias('Destination')]
    [string]$InstallDir = '',
    [switch]$AllUsers,
    [switch]$NoRegistry,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'
$useRegistry = -not $NoRegistry -and $env:FISH_TEST_SKIP_REGISTRY -ne '1'
$userKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
$machineKey = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
$inventoryName = '.fish-script-image'

function Assert-NoReparseParent([IO.DirectoryInfo]$Dir) {
    while ($null -ne $Dir) {
        # FileSystemInfo reports -1 (all bits set) for an absent path.
        if ([int]$Dir.Attributes -ne -1 -and ($Dir.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to follow a link at '$($Dir.FullName)'."
        }
        $Dir = $Dir.Parent
    }
}

if (-not $InstallDir) {
    if ([IO.File]::Exists((Join-Path $PSScriptRoot $inventoryName)) -or
            [IO.File]::Exists((Join-Path $PSScriptRoot 'Fish.exe'))) {
        $InstallDir = $PSScriptRoot
    } elseif ($useRegistry) {
        $key = if ($AllUsers) { $machineKey } else { $userKey }
        $InstallDir = (Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue).InstallLocation
    }
    if (-not $InstallDir) {
        $InstallDir = if ($AllUsers) { Join-Path $env:ProgramFiles Fish } else { Join-Path $env:LOCALAPPDATA 'Programs\Fish' }
    }
}
$targetDir = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
if ($targetDir -notmatch '^[A-Za-z]:\\.+') { throw 'Choose a local installation directory, not a drive root.' }
Assert-NoReparseParent $targetDir
$targetExe = Join-Path $targetDir 'Fish.exe'
$inventory = [IO.FileInfo]::new((Join-Path $targetDir $inventoryName))
$owned = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
if ($inventory.Exists) {
    if ($inventory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing a linked Fish inventory.' }
    $lines = [IO.File]::ReadAllLines($inventory.FullName)
    if ($lines.Length -lt 4 -or $lines[0] -cne 'Fish script image 1' -or $lines[1] -ine $targetDir -or
            $lines[2] -notin @('user','machine')) { throw 'Fish inventory does not identify this installation.' }
    if ($AllUsers -and $lines[2] -ne 'machine') { throw 'This is a per-user script installation.' }
    $AllUsers = $lines[2] -eq 'machine'
    for ($i = 3; $i -lt $lines.Length; $i++) { [void]$owned.Add($lines[$i]) }
}

$key = if ($AllUsers) { $machineKey } else { $userKey }
$uninstaller = Join-Path $targetDir 'Uninstall-Fish.ps1'
$registered = if ($useRegistry) { Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue }
$ourKey = $registered -and $registered.InstallLocation -ieq $targetDir -and
    $registered.UninstallString -match ([regex]::Escape(('-File "{0}"' -f $uninstaller)) + '(?:\s|$)')
if (-not $inventory.Exists) {
    if ($ourKey) {
        # Older script installs have no inventory. Retire only known launcher files, never guess at a tree.
        foreach ($name in @('Fish.exe','Install-Fish.ps1','Uninstall-Fish.ps1','Fish-config.cmd')) { [void]$owned.Add($name) }
        Write-Warning "Legacy script installation has no file inventory; unlisted files in '$targetDir' will be retained."
    } elseif ([IO.File]::Exists($targetExe)) {
        throw 'Not an identified Fish script installation. For MSI installations use Windows Installed apps.'
    } else { return }
}

if ($AllUsers -and $env:FISH_TEST_NO_ELEVATE -ne '1' -and
        -not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $PSCommandPath), '-AllUsers', '-InstallDir', ('"{0}"' -f $targetDir))
    if ($NoRegistry) { $argList += '-NoRegistry' }
    if ($Quiet) { $argList += '-Quiet' }
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList $argList -Verb RunAs -PassThru
    $proc.WaitForExit()
    exit $proc.ExitCode
}
if ([IO.File]::Exists($targetExe) -and (Get-Process -Name Fish -ErrorAction SilentlyContinue | Where-Object {
    try { $_.Path -ieq $targetExe } catch { $false }
})) { throw "Close Fish running from '$targetDir' and retry." }

function Remove-OwnedFile([IO.FileInfo]$File) {
    if ($File.IsReadOnly) { $File.IsReadOnly = $false }
    $File.Delete()
}

# Match the inventory against directory entries, not the other way round: no paths from the
# inventory are ever opened, missing files cost no probes, and unknown folders are never traversed.
function Remove-ImageContents([IO.DirectoryInfo]$Dir, [string]$Prefix) {
    $empty = $true
    foreach ($entry in $Dir.EnumerateFileSystemInfos()) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { $empty = $false; continue }
        $rel = $Prefix + $entry.Name
        if ($entry -is [IO.DirectoryInfo]) {
            if ($owned.Contains($rel + '\') -and (Remove-ImageContents $entry ($rel + '\'))) {
                if ($entry.Attributes -band [IO.FileAttributes]::ReadOnly) { $entry.Attributes = $entry.Attributes -bxor [IO.FileAttributes]::ReadOnly }
                $entry.Delete()
            } else { $empty = $false }
        } elseif ($owned.Contains($rel) -and $rel -notin @('Uninstall-Fish.ps1',$inventoryName)) {
            Remove-OwnedFile $entry
        } else { $empty = $false }
    }
    return $empty
}
if ([IO.Directory]::Exists($targetDir)) { [void](Remove-ImageContents $targetDir '') }

# Do not scan Desktop/Startup or resolve arbitrary user layout links. Only the script installer's
# two fixed application links for this scope are candidates.
$programs = if ($AllUsers) { if ($env:FISH_TEST_COMMON_PROGRAMS) { $env:FISH_TEST_COMMON_PROGRAMS } else { [Environment]::GetFolderPath('CommonPrograms','DoNotVerify') } }
           else { if ($env:FISH_TEST_USER_PROGRAMS) { $env:FISH_TEST_USER_PROGRAMS } else { [Environment]::GetFolderPath('Programs','DoNotVerify') } }
$desktop = if ($AllUsers) { if ($env:FISH_TEST_COMMON_DESKTOP) { $env:FISH_TEST_COMMON_DESKTOP } else { [Environment]::GetFolderPath('CommonDesktopDirectory','DoNotVerify') } }
           else { if ($env:FISH_TEST_USER_DESKTOP) { $env:FISH_TEST_USER_DESKTOP } else { [Environment]::GetFolderPath('Desktop','DoNotVerify') } }
foreach ($dir in @($programs,$desktop)) {
    if (-not $dir) { continue }
    if ($dir -notmatch '^[A-Za-z]:\\' -or [IO.DriveInfo]::new($dir.Substring(0,1)).DriveType -eq 'Network') {
        Write-Warning "Leaving redirected shortcut folder '$dir' untouched."
        continue
    }
    try {
        Assert-NoReparseParent $dir
        $file = [IO.FileInfo]::new((Join-Path $dir 'Fish.lnk'))
        if (-not $file.Exists -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
        if (-not $shell) { $shell = New-Object -ComObject WScript.Shell }
        $link = $shell.CreateShortcut($file.FullName)
        if ($link.TargetPath -ieq $targetExe -and -not $link.Arguments -and $link.Description -ceq 'Fish 5250 Workspace') {
            Remove-OwnedFile $file
        }
    } catch { Write-Warning "Leaving shortcut in '$dir': $($_.Exception.Message)" }
}
if ($ourKey) {
    # Nonrecursive and noninteractive even if something else added a child key.
    $hive = if ($AllUsers) { [Microsoft.Win32.Registry]::LocalMachine } else { [Microsoft.Win32.Registry]::CurrentUser }
    $hive.DeleteSubKey('Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish',$false)
}
$scriptFile = [IO.FileInfo]::new($uninstaller)
if ($owned.Contains('Uninstall-Fish.ps1') -and $scriptFile.Exists -and -not ($scriptFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    Remove-OwnedFile $scriptFile
}
if ($inventory.Exists) { Remove-OwnedFile $inventory }
$root = [IO.DirectoryInfo]::new($targetDir)
if ($root.Exists -and $root.GetFileSystemInfos().Length -eq 0) {
    try { $root.Delete() }
    catch [IO.IOException] { Write-Warning "Fish files removed; '$targetDir' is still in use." }
}
if (-not $Quiet) { Write-Host "Fish uninstalled from $targetDir. User settings, layouts and credentials were kept." }
