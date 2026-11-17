[CmdletBinding()]
param(
    [Alias('Destination')]
    [string]$InstallDir = '',
    [switch]$AllUsers,
    [switch]$NoRegistry,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'

$hkcuRegPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
$hklmRegPath = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Fish'
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $InstallDir) {
    if ($AllUsers) {
        if (Test-Path -LiteralPath $hklmRegPath) {
            $InstallDir = (Get-ItemProperty -Path $hklmRegPath -Name 'InstallLocation' -ErrorAction SilentlyContinue).InstallLocation
        }
        if (-not $InstallDir) { $InstallDir = Join-Path $env:ProgramFiles 'Fish' }
    } else {
        if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'Fish.exe')) {
            $InstallDir = $PSScriptRoot
        } elseif (Test-Path -LiteralPath $hkcuRegPath) {
            $InstallDir = (Get-ItemProperty -Path $hkcuRegPath -Name 'InstallLocation' -ErrorAction SilentlyContinue).InstallLocation
        } elseif (Test-Path -LiteralPath $hklmRegPath) {
            $InstallDir = (Get-ItemProperty -Path $hklmRegPath -Name 'InstallLocation' -ErrorAction SilentlyContinue).InstallLocation
            $AllUsers = $true
        }
        if (-not $InstallDir) {
            $InstallDir = Join-Path $env:LOCALAPPDATA 'Programs\Fish'
        }
    }
}

$targetDir = [IO.Path]::GetFullPath($InstallDir.TrimEnd('\'))
$programFilesPrefix = if ($env:ProgramFiles) { [IO.Path]::GetFullPath($env:ProgramFiles.TrimEnd('\')) + '\' } else { 'C:\Program Files\' }
if (-not $AllUsers -and $targetDir.StartsWith($programFilesPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    $AllUsers = $true
}

if ($AllUsers -and -not $isAdmin -and $env:FISH_TEST_NO_ELEVATE -ne '1') {
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $PSCommandPath), '-AllUsers', '-InstallDir', ('"{0}"' -f $targetDir))
    if ($NoRegistry) { $argList += '-NoRegistry' }
    if ($Quiet) { $argList += '-Quiet' }
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList ($argList -join ' ') -Verb RunAs -Wait -PassThru
    exit $proc.ExitCode
}

$targetExe = Join-Path $targetDir 'Fish.exe'

$running = Get-Process -Name 'Fish' -ErrorAction SilentlyContinue | Where-Object {
    try { $_.MainModule.FileName -ieq $targetExe } catch { $false }
}
if ($running) {
    throw "Fish is currently running from $targetDir. Close all Fish windows before uninstalling."
}

$shortcutDirs = @(
    $(if ($env:FISH_TEST_USER_PROGRAMS) { $env:FISH_TEST_USER_PROGRAMS } else { [Environment]::GetFolderPath('Programs') }),
    $(if ($env:FISH_TEST_USER_DESKTOP) { $env:FISH_TEST_USER_DESKTOP } else { [Environment]::GetFolderPath('Desktop') })
)
if ($AllUsers) {
    $shortcutDirs += @(
        $(if ($env:FISH_TEST_COMMON_PROGRAMS) { $env:FISH_TEST_COMMON_PROGRAMS } else { [Environment]::GetFolderPath('CommonPrograms') }),
        $(if ($env:FISH_TEST_COMMON_DESKTOP) { $env:FISH_TEST_COMMON_DESKTOP } else { [Environment]::GetFolderPath('CommonDesktopDirectory') })
    )
}
foreach ($folder in $shortcutDirs) {
    if (-not $folder -or -not (Test-Path -LiteralPath $folder)) { continue }
    $shortcut = Join-Path $folder 'Fish.lnk'
    if (Test-Path -LiteralPath $shortcut) {
        try {
            $shell = New-Object -ComObject WScript.Shell
            $link = $shell.CreateShortcut($shortcut)
            if ($link.TargetPath -ieq $targetExe -or ($link.TargetPath -and $link.TargetPath.EndsWith('\Fish.exe', [StringComparison]::OrdinalIgnoreCase))) {
                Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
}

# Remove any Fish autostart layout shortcuts in Startup folder
$startupDir = if ($env:FISH_TEST_STARTUP) { $env:FISH_TEST_STARTUP } else { [Environment]::GetFolderPath('Startup') }
if ($startupDir -and (Test-Path -LiteralPath $startupDir)) {
    Get-ChildItem -LiteralPath $startupDir -Filter '*.lnk' -File -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            $shell = New-Object -ComObject WScript.Shell
            $link = $shell.CreateShortcut($_.FullName)
            $isFishExe = $link.TargetPath -and ($link.TargetPath -ieq $targetExe -or $link.TargetPath.EndsWith('\Fish.exe', [StringComparison]::OrdinalIgnoreCase))
            $hasLayout = $link.Arguments -and $link.Arguments.IndexOf('--layout', [StringComparison]::OrdinalIgnoreCase) -ge 0
            if ($isFishExe -or ($hasLayout -and ($_.Name -like 'Fish*'))) {
                Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
            }
        } catch {}
    }
}

# Delete Fish configuration directory (%APPDATA%\Fish, plus %ProgramData%\Fish when -AllUsers)
$appDataBase = if ($env:FISH_TEST_APPDATA) { $env:FISH_TEST_APPDATA } else { $env:APPDATA }
if ($appDataBase) {
    $fishConfigDir = Join-Path $appDataBase 'Fish'
    if (Test-Path -LiteralPath $fishConfigDir) {
        Remove-Item -LiteralPath $fishConfigDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
if ($AllUsers) {
    $programDataBase = if ($env:FISH_TEST_PROGRAMDATA) { $env:FISH_TEST_PROGRAMDATA } else { $env:ProgramData }
    if ($programDataBase) {
        $systemConfigDir = Join-Path $programDataBase 'Fish'
        if (Test-Path -LiteralPath $systemConfigDir) {
            Remove-Item -LiteralPath $systemConfigDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# Delete stored Fish/* Windows Credential Manager entries
if ($env:FISH_TEST_SKIP_CREDENTIALS -ne '1') {
    try {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace FishUninstall {
    public static class CredCleanup {
        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CredEnumerateW(string filter, int flags, out int count, out IntPtr pCredentials);
        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CredDeleteW(string target, int type, int flags);
        [DllImport("advapi32.dll", SetLastError = true)]
        private static extern void CredFree(IntPtr buffer);
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct CREDENTIALW {
            public int Flags;
            public int Type;
            public string TargetName;
        }
        public static void PurgePrefix(string filter) {
            int count;
            IntPtr ptr;
            if (!CredEnumerateW(filter, 0, out count, out ptr) || ptr == IntPtr.Zero) return;
            try {
                for (int i = 0; i < count; i++) {
                    IntPtr itemPtr = Marshal.ReadIntPtr(ptr, i * IntPtr.Size);
                    if (itemPtr == IntPtr.Zero) continue;
                    CREDENTIALW cred = Marshal.PtrToStructure<CREDENTIALW>(itemPtr);
                    if (!string.IsNullOrEmpty(cred.TargetName)) {
                        CredDeleteW(cred.TargetName, cred.Type, 0);
                    }
                }
            } finally {
                CredFree(ptr);
            }
        }
    }
}
'@ -ErrorAction SilentlyContinue
        $credFilter = if ($env:FISH_TEST_CREDENTIAL_FILTER) { $env:FISH_TEST_CREDENTIAL_FILTER } else { 'Fish/*' }
        [FishUninstall.CredCleanup]::PurgePrefix($credFilter)
    } catch {}
}

if (-not $NoRegistry -and $env:FISH_TEST_SKIP_REGISTRY -ne '1') {
    if ($AllUsers) {
        if (Test-Path -LiteralPath $hklmRegPath) {
            Remove-Item -Path $hklmRegPath -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath 'HKLM:\SOFTWARE\Fish') {
            Remove-Item -Path 'HKLM:\SOFTWARE\Fish' -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    if (Test-Path -LiteralPath $hkcuRegPath) {
        Remove-Item -Path $hkcuRegPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ((Test-Path -LiteralPath $targetDir) -and (Test-Path -LiteralPath $targetExe)) {
    $scriptInsideTarget = $PSCommandPath -and ([IO.Path]::GetFullPath($PSCommandPath)).StartsWith(($targetDir + '\'), [StringComparison]::OrdinalIgnoreCase)
    if ($scriptInsideTarget) {
        $cleanupCmd = "/c ping 127.0.0.1 -n 2 >nul & rmdir /s /q `"$targetDir`""
        Start-Process -FilePath $env:ComSpec -ArgumentList $cleanupCmd -WindowStyle Hidden
    } else {
        Remove-Item -LiteralPath $targetDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not $Quiet) {
    Write-Host "Fish uninstalled from $targetDir and saved configuration removed."
}
