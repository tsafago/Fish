param([Parameter(Mandatory=$true)][string]$Jdk,[Parameter(Mandatory=$true)][string]$Image,[Parameter(Mandatory=$true)][string]$Version)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Installer version must be numeric X.Y.Z.' }
& "$PSScriptRoot\prepare-tools.ps1" -Kind Installer
$tools=Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
$env:PATH=(Join-Path $root ('.tools\'+$tools.Installer.Directory))+';'+$env:PATH
$env:ZIG_GLOBAL_CACHE_DIR=Join-Path $root '.tools\zig-cache'
$zig=Join-Path $root ('.tools\'+$tools.Native.Directory+'\zig.exe')
if (-not (Test-Path -LiteralPath $zig)) { throw 'Build the application/native bridge before packaging setup.' }
$output=Join-Path $root ('build\installer-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $output | Out-Null
& "$Jdk\bin\jpackage.exe" --type msi --name Fish --app-version $Version --app-image $Image `
    --dest $output --vendor Fish --win-per-user-install --install-dir Fish `
    --win-menu --win-shortcut --win-menu-group Fish --win-upgrade-uuid '55b0674d-e226-46cb-852f-6beddd35a74b'
if ($LASTEXITCODE -ne 0) { throw 'Current-user MSI creation failed.' }
$msi=Join-Path $output "Fish-$Version.msi"
$parts=$Version.Split('.')
$header="#define FISH_VERSION L`"$Version`"`n#define FISH_VERSION_A `"$Version`"`n"
[IO.File]::WriteAllText((Join-Path $output 'fish-setup-version.h'),$header,[Text.UTF8Encoding]::new($false))
$icon=(Join-Path $root 'build\Fish.ico').Replace('\','\\')
$manifest=(Join-Path $PSScriptRoot 'fish-setup.manifest').Replace('\','\\')
$resource=@"
#include <windows.h>
1 ICON "$icon"
1 RT_MANIFEST "$manifest"
1 VERSIONINFO
FILEVERSION $($parts[0]),$($parts[1]),$($parts[2]),0
PRODUCTVERSION $($parts[0]),$($parts[1]),$($parts[2]),0
FILEOS VOS_NT_WINDOWS32
FILETYPE VFT_APP
BEGIN
 BLOCK "StringFileInfo"
 BEGIN
  BLOCK "040904b0"
  BEGIN
   VALUE "CompanyName", "Fish"
   VALUE "FileDescription", "Fish current-user setup"
   VALUE "FileVersion", "$Version"
   VALUE "ProductName", "Fish"
   VALUE "ProductVersion", "$Version"
  END
 END
 BLOCK "VarFileInfo"
 BEGIN
  VALUE "Translation", 0x409, 1200
 END
END
"@
[IO.File]::WriteAllText((Join-Path $output 'setup.rc'),$resource,[Text.UTF8Encoding]::new($false))
& $zig rc "/fo$(Join-Path $output 'setup.res')" (Join-Path $output 'setup.rc')
if ($LASTEXITCODE -ne 0) { throw 'Setup resource compilation failed.' }
$bootstrapper=Join-Path $output 'Fish-setup.exe'
& $zig cc -O2 -Wall -Wextra -Werror -municode '-Wl,--subsystem,windows' -target x86_64-windows-gnu `
    ("-I"+$output) (Join-Path $root 'src\native\fish-setup.c') (Join-Path $output 'setup.res') `
    -luser32 -lgdi32 -lshell32 -lole32 -luuid -lmsi -lversion -lbcrypt -ldwmapi -lcomctl32 -o $bootstrapper
if ($LASTEXITCODE -ne 0) { throw 'Native setup compilation failed.' }
$check=Start-Process -FilePath $bootstrapper -ArgumentList '--self-test' -Wait -PassThru
if ($check.ExitCode -ne 0) { throw 'Native setup contract checks failed.' }
$check=Start-Process -FilePath $bootstrapper -ArgumentList '--test-controls' -Wait -PassThru
if ($check.ExitCode -ne 0) { throw 'Never-shown native checkbox paint/state/contrast checks failed.' }
$destination=Join-Path $root "dist\Fish-$Version-setup.exe"
Copy-Item -LiteralPath $bootstrapper -Destination $destination -Force
$stream=[IO.File]::Open($destination,[IO.FileMode]::Append,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    $payload=[IO.File]::OpenRead($msi)
    try { $payload.CopyTo($stream) } finally { $payload.Dispose() }
    $stream.Write([Text.Encoding]::ASCII.GetBytes("FishMsiPayload1`0"),0,16)
    $length=[BitConverter]::GetBytes([int64](Get-Item -LiteralPath $msi).Length)
    $stream.Write($length,0,$length.Length)
    $hash=(Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash
    for ($i=0;$i -lt 64;$i+=2) { $stream.WriteByte([Convert]::ToByte($hash.Substring($i,2),16)) }
    $stream.Flush($true)
} finally { $stream.Dispose() }
$check=Start-Process -FilePath $destination -ArgumentList '--verify-payload' -Wait -PassThru
if ($check.ExitCode -ne 0) { throw 'Final installer payload verification failed.' }
$preview=Join-Path $output 'setup-preview.bmp'
$check=Start-Process -FilePath $destination -ArgumentList ('--render-preview "'+$preview+'"') -Wait -PassThru
if ($check.ExitCode -ne 0) { throw 'Offscreen setup design rendering failed.' }
Write-Host "Branded native current-user installer ready: $destination (MSI SHA256 $hash)"
