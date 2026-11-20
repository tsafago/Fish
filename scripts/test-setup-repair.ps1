param([Parameter(Mandatory=$true)][string]$Distribution)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$dist=(Resolve-Path -LiteralPath $Distribution).Path
$image=Join-Path $dist 'Fish'
$setup=(Get-Item (Join-Path $dist 'Fish-*-setup.exe')).FullName
$version=(Get-Item $setup).VersionInfo.ProductVersion
$temp=New-Item -ItemType Directory -Path (Join-Path $root ('build\repair-fixture-'+[guid]::NewGuid().ToString('N').Substring(0,8)))
try {
    & "$PSScriptRoot\setup-image.ps1" -Image $image -Output (Join-Path $temp 'fish-image.generated.h')
    [IO.File]::WriteAllText((Join-Path $temp 'fish-setup-version.h'),"#define FISH_VERSION L`"$version`"`n#define FISH_VERSION_A `"$version`"`n")
    $tools=Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
    $zig=Join-Path $root ('.tools\'+$tools.Native.Directory+'\zig.exe')
    $env:ZIG_GLOBAL_CACHE_DIR=Join-Path $root '.tools\zig-cache'
    $probe=Join-Path $temp 'repair-test.exe'
    & $zig cc -Wall -Wextra -Werror -O2 -municode -target x86_64-windows-gnu ("-I"+$temp.FullName) `
        (Join-Path $root 'tests\native\setup-repair.c') -luser32 -lgdi32 -lshell32 -lole32 -loleaut32 -luuid -lmsi -lversion -lbcrypt -ldwmapi -lcomctl32 -ladvapi32 -o $probe
    if ($LASTEXITCODE) {throw 'Could not build repair fixture'}
    Copy-Item -LiteralPath $image -Destination (Join-Path $temp 'image') -Recurse
    $link=Join-Path $temp 'link'
    $linkTarget=New-Item -ItemType Directory -Path (Join-Path $temp 'untouched')
    $marker=Join-Path $linkTarget.FullName 'marker.txt'
    [IO.File]::WriteAllText($marker,'unchanged')
    New-Item -ItemType Junction -Path $link -Target $linkTarget.FullName | Out-Null
    & $probe $setup (Join-Path $temp 'cache') (Join-Path $temp 'image') $link
    if ($LASTEXITCODE) {throw 'Repair fixture failed'}
    if ([IO.File]::ReadAllText($marker) -ne 'unchanged') {throw 'Junction target was modified'}
} finally {
    foreach ($candidate in @((Join-Path $temp 'link'),(Join-Path $temp 'cache\Fish\app\linked'))) {
        $entry=Get-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        if ($entry -and ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            [IO.Directory]::Delete($candidate)
        }
    }
    Remove-Item -LiteralPath $temp.FullName -Recurse -Force
}
