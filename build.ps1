param(
    [ValidateSet('Build', 'Check', 'Test', 'Package', 'Run', 'Demo')]
    [string]$Task = 'Build',
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $Jdk -and (Test-Path '.tools\jdk-21.0.8+9\bin\javac.exe')) {
    $Jdk = Join-Path $PSScriptRoot '.tools\jdk-21.0.8+9'
}
if (-not $Jdk -or -not (Test-Path "$Jdk\bin\javac.exe")) {
    throw 'JDK 21 required. Set JAVA_HOME or run .\scripts\bootstrap.ps1.'
}
if (-not (Test-Path 'vendor\tn5250j\src')) {
    & git submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) { throw 'Could not retrieve pinned TN5250J source.' }
}
$pin = if (Test-Path 'vendor\tn5250j\.git') { & git -C vendor\tn5250j rev-parse HEAD }
       elseif (Test-Path 'vendor\tn5250j\REVISION') { (Get-Content vendor\tn5250j\REVISION -Raw).Trim() }
       else { throw 'Core provenance missing: initialize the submodule or use the complete source distribution.' }
if ($pin -ne 'd160492db8adec5d7cf309c6f4feefa162e6565d') { throw 'Unexpected emulator core revision.' }
foreach ($directory in @('build\classes','build\generated','build\test-classes','build\package-input')) {
    $resolved = Join-Path $PSScriptRoot $directory
    if (Test-Path $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
New-Item -ItemType Directory -Force build\classes,build\generated,dist | Out-Null
& "$PSScriptRoot\scripts\prepare-core.ps1"
$sources = @(
    Get-ChildItem src -Recurse -Filter *.java
    Get-ChildItem build\generated -Recurse -Filter *.java
) | ForEach-Object { '"' + $_.FullName.Replace('\','\\') + '"' }
$sourceList = Join-Path $PSScriptRoot 'build\sources.txt'
[IO.File]::WriteAllLines($sourceList, $sources, [Text.UTF8Encoding]::new($false))
& "$Jdk\bin\javac.exe" --release 21 -encoding UTF-8 -Xlint:unchecked -d build\classes "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
& "$Jdk\bin\jar.exe" --create --file dist\Pane5250.jar --main-class pane5250.Main -C build\classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
Copy-Item scripts\credential-store.ps1 dist\credential-store.ps1 -Force
Copy-Item vendor\tn5250j\LICENSE dist\COPYING-TN5250J -Force
if ($Task -eq 'Test' -or $Task -eq 'Check') {
    $testSources = Get-ChildItem tests -Recurse -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\','\\') + '"' }
    [IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'build\tests.txt'), $testSources, [Text.UTF8Encoding]::new($false))
    & "$Jdk\bin\javac.exe" --release 21 -encoding UTF-8 -cp build\classes -d build\test-classes '@build\tests.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    $testArgs=@()
    if ($Task -eq 'Check') { $testArgs+='--core-only' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.Tests @testArgs
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
    if ($Task -eq 'Test') {
        & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.DesktopRendering
        if ($LASTEXITCODE -ne 0) { throw 'Desktop rendering/window tests failed.' }
        & "$Jdk\bin\java.exe" -ea '-Dpane5250.effect=Neon' -cp 'build\classes;build\test-classes' pane5250.DesktopRendering
        if ($LASTEXITCODE -ne 0) { throw 'Opt-in effect resize/window tests failed.' }
        & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.UiRevisions
        if ($LASTEXITCODE -ne 0) { throw 'Revised interaction/theme/dialog tests failed.' }
    }
}
if ($Task -eq 'Package') {
    if (Test-Path dist\Pane5250) { throw 'dist\Pane5250 exists. Move it aside before packaging again.' }
    New-Item -ItemType Directory -Force build\package-input | Out-Null
    Copy-Item dist\Pane5250.jar,dist\credential-store.ps1,dist\COPYING-TN5250J,README.md,COMPATIBILITY.md,LICENSE,NOTICE build\package-input -Force
    & "$Jdk\bin\jpackage.exe" --type app-image --name Pane5250 --app-version 0.2.0 `
        --input build\package-input --main-jar Pane5250.jar --main-class pane5250.Main `
        --dest dist --vendor Pane5250 --java-options '-Xmx192m' `
        --add-modules java.base,java.desktop,java.xml,java.logging,jdk.charsets
    if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    $source = Join-Path $PSScriptRoot 'dist\Pane5250\app\source'
    New-Item -ItemType Directory -Force $source | Out-Null
    Copy-Item src,scripts,tests $source -Recurse
    Copy-Item build.ps1,README.md,COMPATIBILITY.md,LICENSE,NOTICE,PRODUCT.md,DESIGN.md $source
    $coreSource = Join-Path $source 'vendor\tn5250j'
    New-Item -ItemType Directory -Force $coreSource | Out-Null
    Copy-Item vendor\tn5250j\src $coreSource -Recurse
    Copy-Item vendor\tn5250j\LICENSE $coreSource
    [IO.File]::WriteAllText((Join-Path $coreSource 'REVISION'), $pin + "`n", [Text.UTF8Encoding]::new($false))
    Compress-Archive -Path "$source\*" -DestinationPath dist\Pane5250-source.zip -Force
}
if ($Task -eq 'Run' -or $Task -eq 'Demo') {
    $appArgs = @()
    if ($Task -eq 'Demo') { $appArgs += '--demo' }
    & "$Jdk\bin\java.exe" -Xmx192m -jar dist\Pane5250.jar @appArgs
    if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
}
Write-Host "Pane5250 $Task complete."
