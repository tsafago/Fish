param([Parameter(Mandatory=$true)][string]$Jdk,[switch]$Test)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$manifest = Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
$zig = Join-Path $root ('.tools\' + $manifest.Native.Directory + '\zig.exe')
if (-not (Test-Path -LiteralPath $zig)) { & "$PSScriptRoot\prepare-tools.ps1" -Kind Native }
$env:ZIG_GLOBAL_CACHE_DIR = Join-Path $root '.tools\zig-cache'
& $zig cc -shared -O2 -Wall -Wextra -Werror -target x86_64-windows-gnu `
    ("-I" + (Join-Path $Jdk 'include')) ("-I" + (Join-Path $Jdk 'include\win32')) `
    (Join-Path $root 'src\native\fish-window.c') (Join-Path $root 'src\native\fish-kerberos.c') (Join-Path $Jdk 'lib\jawt.lib') `
    -luser32 -lgdi32 -ldwmapi -lsecur32 -o (Join-Path $root 'build\fish-window.dll')
if ($LASTEXITCODE -ne 0) { throw 'Windows integration compilation failed.' }
if ($Test) {
    $fixture=Join-Path $root 'build\kerberos-native-smoke.exe'
    & $zig cc -O2 -Wall -Wextra -Werror -target x86_64-windows-gnu `
        ("-I" + (Join-Path $Jdk 'include')) ("-I" + (Join-Path $Jdk 'include\win32')) `
        ("-I" + (Join-Path $root 'src\native')) (Join-Path $root 'tests\native\kerberos.c') -lsecur32 -o $fixture
    if ($LASTEXITCODE -ne 0) { throw 'Kerberos native fixture compilation failed.' }
    & $fixture
    if ($LASTEXITCODE -ne 0) { throw 'Kerberos native provider fixtures failed.' }
}
