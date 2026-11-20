# Package acceptance, without opening anything on the user's desktop or using their Fish data.
param([Parameter(Mandatory=$true)][string]$ImageFolder,[string]$EvidenceBitmap)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$exe=(Resolve-Path (Join-Path $ImageFolder 'Fish.exe')).Path
$tools=Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
$zig=Join-Path $root ('.tools\'+$tools.Native.Directory+'\zig.exe')
$env:ZIG_GLOBAL_CACHE_DIR=Join-Path $root '.tools\zig-cache'
$out=(New-Item -ItemType Directory (Join-Path $root ('build\start-check-'+[guid]::NewGuid().ToString('N')))).FullName
$prior=$env:JAVA_TOOL_OPTIONS
try {
    $probe=Join-Path $out 'start-check.exe'
    & $zig cc -Wall -Wextra -Werror -O2 -municode -target x86_64-windows-gnu `
        ("-I"+(Join-Path $root 'src\native')) (Join-Path $root 'tests\native\start-banner.c') -luser32 -lgdi32 -o $probe
    if ($LASTEXITCODE) { throw 'Start banner probe compilation failed.' }
    $bad=(New-Item -ItemType Directory (Join-Path $out 'invalid')).FullName
    Set-Content -LiteralPath (Join-Path $bad 'workspace.xml') -Value 'not XML'
    $env:JAVA_TOOL_OPTIONS="-Dfish.testMode=true -Dfish.test.kiosk=false `"-Djava.io.tmpdir=$out`""
    # The fixture needs only a single executable token here; it quotes the path internally.
    $probeArgs=@($exe,(Join-Path $out 'demo'),$bad)
    if ($EvidenceBitmap) { $probeArgs+=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($EvidenceBitmap) }
    & $probe @probeArgs
    if ($LASTEXITCODE) { throw 'Start banner acceptance failed.' }
} finally {
    $env:JAVA_TOOL_OPTIONS=$prior
    Remove-Item -LiteralPath $out -Recurse -Force
}
