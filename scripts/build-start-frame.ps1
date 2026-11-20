param([Parameter(Mandatory=$true)][string]$Zig,
      [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$src=Join-Path (Split-Path $PSScriptRoot -Parent) 'src\native'
$out=(New-Item -ItemType Directory -Force $OutputDirectory).FullName
$generator=Join-Path $out 'start-frame.exe'
& $Zig cc -O2 -Wall -Wextra -Werror -municode -target x86_64-windows-gnu `
    (Join-Path $src 'fish-start-frame.c') -lgdi32 -luser32 -o $generator
if ($LASTEXITCODE) { throw 'Start frame generator compilation failed.' }
& $generator (Join-Path $out 'start-frame.bmp')
if ($LASTEXITCODE) { throw 'Could not render the opening coastline.' }
& $Zig rc "/I$out" "/fo$(Join-Path $out 'start-frame.res')" (Join-Path $src 'fish-start-frame.rc')
if ($LASTEXITCODE) { throw 'Start frame resource compilation failed.' }
