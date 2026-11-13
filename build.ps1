param(
    [ValidateSet('Build', 'Maintenance', 'Smoke', 'Check', 'Test', 'Package', 'Installer', 'Run', 'Demo')]
    [string]$Task = 'Build',
    [string]$Jdk = $env:JAVA_HOME,
    [string]$ImageDirectory = 'dist\Fish'
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$version = '0.8.0'
$image = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ImageDirectory)
if ((Split-Path -Leaf $image) -ne 'Fish') { throw 'ImageDirectory must end in Fish (the jpackage application name).' }
if (-not $Jdk) {
    $manifest = Import-PowerShellDataFile .\tools.psd1
    $candidate = Join-Path $PSScriptRoot ('.tools\' + $manifest.Jdk.Directory)
    if (Test-Path "$candidate\bin\javac.exe") { $Jdk = $candidate }
}
if (-not $Jdk -or -not (Test-Path "$Jdk\bin\javac.exe")) {
    throw 'JDK 21 required. Set JAVA_HOME or run .\scripts\bootstrap.ps1.'
}
if ($Task -eq 'Installer') {
    $executable=Join-Path $image 'Fish.exe'
    if (-not (Test-Path -LiteralPath $executable)) { throw 'Build the application first: .\build.ps1 Package' }
    if ((Get-Item -LiteralPath $executable).VersionInfo.ProductVersion -ne $version) {
        throw "Application image is not Fish $version. Package first, or select its matching -ImageDirectory."
    }
    & "$PSScriptRoot\scripts\build-installer.ps1" -Jdk $Jdk -Image $image -Version $version
    Compress-Archive -Path $image -DestinationPath "dist\Fish-$version-windows-x64.zip" -Force
    $checksums = foreach ($file in @("dist\Fish-$version-setup.exe","dist\Fish-$version-windows-x64.zip",'dist\Fish-source.zip')) {
        (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($file)
    }
    [IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'dist\SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
    Write-Host "Fish $version user-wide installer and portable ZIP ready."
    return
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
& "$PSScriptRoot\scripts\build-native.ps1" -Jdk $Jdk -Test:($Task -in @('Smoke','Check','Test'))
$sources = @(
    Get-ChildItem src -Recurse -Filter *.java
    Get-ChildItem build\generated -Recurse -Filter *.java
) | ForEach-Object { '"' + $_.FullName.Replace('\','\\') + '"' }
$sourceList = Join-Path $PSScriptRoot 'build\sources.txt'
[IO.File]::WriteAllLines($sourceList, $sources, [Text.UTF8Encoding]::new($false))
& "$Jdk\bin\javac.exe" --release 21 -encoding UTF-8 -Xlint:unchecked -d build\classes "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
Copy-Item MANUAL.md,AI-CONFIG.md build\classes
& "$Jdk\bin\java.exe" -cp build\classes pane5250.Main --config schema --file build\classes\CONFIG-SCHEMA.json
if ($LASTEXITCODE -ne 0) { throw 'Configuration catalog generation failed.' }
Copy-Item build\classes\CONFIG-SCHEMA.json CONFIG-SCHEMA.json -Force
& "$Jdk\bin\jar.exe" --create --file dist\Fish.jar --main-class pane5250.Main -C build\classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
Copy-Item scripts\credential-store.ps1 dist\credential-store.ps1 -Force
Copy-Item build\fish-window.dll dist\fish-window.dll -Force
Copy-Item vendor\tn5250j\LICENSE dist\COPYING-TN5250J -Force
if ($Task -eq 'Maintenance' -or $Task -eq 'Smoke' -or $Task -eq 'Test' -or $Task -eq 'Check') {
    $testFiles = if ($Task -eq 'Maintenance') { Get-Item tests\pane5250\Tests.java,tests\pane5250\MaintenanceSmoke.java,tests\pane5250\MaintenanceUiSmoke.java,tests\pane5250\NoticeSmoke.java,tests\pane5250\NativeUpdateSmoke.java,tests\pane5250\KeyboardPreferencesSmoke.java,tests\pane5250\SettingsTutorialSmoke.java,tests\pane5250\LaunchBrokerSmoke.java,tests\pane5250\WindowRevisionSmoke.java,tests\pane5250\LayoutFeatureSmoke.java,tests\pane5250\ShortcutRefinementSmoke.java,tests\pane5250\UsabilityIterationSmoke.java,tests\pane5250\SimplePaneSmoke.java,tests\pane5250\CursorRenderingSmoke.java,tests\pane5250\BetaSettingsSmoke.java,tests\pane5250\BetaRecoverySmoke.java,tests\pane5250\BetaUiSmoke.java }
        elseif ($Task -eq 'Smoke') { Get-Item tests\pane5250\Tests.java,tests\pane5250\FishSmoke.java,tests\pane5250\StreamliningSmoke.java,tests\pane5250\CustomizationSmoke.java,tests\pane5250\ChromeConsistencySmoke.java,tests\pane5250\RefinementSmoke.java,tests\pane5250\UsabilitySmoke.java,tests\pane5250\KerberosSmoke.java } else { Get-ChildItem tests -Recurse -Filter *.java }
    $testSources = $testFiles | ForEach-Object { '"' + $_.FullName.Replace('\','\\') + '"' }
    [IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'build\tests.txt'), $testSources, [Text.UTF8Encoding]::new($false))
    & "$Jdk\bin\javac.exe" --release 21 -encoding UTF-8 -cp build\classes -d build\test-classes '@build\tests.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    if ($Task -eq 'Maintenance') {
        foreach ($fixture in @('pane5250.Tests','pane5250.update.MaintenanceSmoke','pane5250.ui.NoticeSmoke','pane5250.ui.MaintenanceUiSmoke','pane5250.ui.KeyboardPreferencesSmoke','pane5250.ui.SettingsTutorialSmoke','pane5250.LaunchBrokerSmoke','pane5250.ui.WindowRevisionSmoke','pane5250.ui.LayoutFeatureSmoke','pane5250.ui.ShortcutRefinementSmoke','pane5250.ui.UsabilityIterationSmoke','pane5250.ui.SimplePaneSmoke','pane5250.ui.CursorRenderingSmoke','pane5250.ui.BetaSettingsSmoke','pane5250.ui.BetaRecoverySmoke','pane5250.ui.BetaUiSmoke')) {
            $fixtureArgs=@()
            if ($fixture -eq 'pane5250.Tests') { $fixtureArgs+='--protocol-only' }
            & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' $fixture @fixtureArgs
            if ($LASTEXITCODE -ne 0) { throw "Noninteractive maintenance fixture failed: $fixture" }
        }
        Write-Host 'Maintenance checks complete; no visible windows, clipboard, credentials, real-host connections or installer execution.'
        return
    }
    if ($Task -ne 'Smoke') {
        $testArgs=@()
        if ($Task -eq 'Check') { $testArgs+='--core-only' }
        & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.Tests @testArgs
        if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
    } else {
        & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.Tests --protocol-only
        if ($LASTEXITCODE -ne 0) { throw 'Protocol/model fixtures failed.' }
    }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.ui.KerberosSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Kerberos fixture checks failed.' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.ui.UsabilitySmoke
    if ($LASTEXITCODE -ne 0) { throw 'Usability smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.FishSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Fish smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.StreamliningSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Streamlining smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.CustomizationSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Customization smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.ui.ChromeConsistencySmoke
    if ($LASTEXITCODE -ne 0) { throw 'Chrome consistency smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.ui.RefinementSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Interaction refinement smoke checks failed.' }
    if ($Task -eq 'Test') {
        & "$Jdk\bin\java.exe" -ea -cp 'build\classes;build\test-classes' pane5250.DesktopRendering
        if ($LASTEXITCODE -ne 0) { throw 'Desktop rendering/window tests failed.' }
        & "$Jdk\bin\java.exe" -ea '-Dpane5250.effect=Soft glow' -cp 'build\classes;build\test-classes' pane5250.DesktopRendering
        if ($LASTEXITCODE -ne 0) { throw 'Opt-in effect resize/window tests failed.' }
    }
}
if ($Task -eq 'Package') {
    if (Test-Path -LiteralPath $image) { throw 'Application image exists. Close/preserve it first, or use -ImageDirectory dist\Fish-VERSION\Fish for a side-by-side build.' }
    New-Item -ItemType Directory -Force build\package-input | Out-Null
    Copy-Item dist\Fish.jar,dist\fish-window.dll,dist\credential-store.ps1,dist\COPYING-TN5250J,README.md,MANUAL.md,AI-CONFIG.md,CONFIG-SCHEMA.json,COMPATIBILITY.md,LICENSE,NOTICE build\package-input -Force
    & "$Jdk\bin\java.exe" -cp build\classes pane5250.ui.FishIcon build\Fish.ico
    if ($LASTEXITCODE -ne 0) { throw 'Fish icon generation failed.' }
    New-Item -ItemType Directory -Force (Split-Path -Parent $image) | Out-Null
    & "$Jdk\bin\jpackage.exe" --type app-image --name Fish --app-version $version --icon build\Fish.ico `
        --input build\package-input --main-jar Fish.jar --main-class pane5250.Main `
        --dest (Split-Path -Parent $image) --vendor Fish --java-options '-Xmx192m' `
        --add-modules java.base,java.desktop,java.xml,java.logging,java.net.http,jdk.crypto.ec,jdk.charsets
    if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    & "$PSScriptRoot\scripts\set-launcher-encoding.ps1" -Executable (Join-Path $image 'Fish.exe')
    Copy-Item scripts\Fish-config.cmd (Join-Path $image 'Fish-config.cmd')
    $source = Join-Path $image 'app\source'
    New-Item -ItemType Directory -Force $source | Out-Null
    Copy-Item src,scripts,tests $source -Recurse
    Copy-Item build.ps1,Setup-Fish.cmd,Setup-Fish.ps1,tools.psd1,README.md,MANUAL.md,AI-CONFIG.md,CONFIG-SCHEMA.json,COMPATIBILITY.md,LICENSE,NOTICE,PRODUCT.md,DESIGN.md $source
    $coreSource = Join-Path $source 'vendor\tn5250j'
    New-Item -ItemType Directory -Force $coreSource | Out-Null
    Copy-Item vendor\tn5250j\src $coreSource -Recurse
    Copy-Item vendor\tn5250j\LICENSE $coreSource
    [IO.File]::WriteAllText((Join-Path $coreSource 'REVISION'), $pin + "`n", [Text.UTF8Encoding]::new($false))
    Compress-Archive -Path "$source\*" -DestinationPath dist\Fish-source.zip -Force
}
if ($Task -eq 'Run' -or $Task -eq 'Demo') {
    $appArgs = @()
    if ($Task -eq 'Demo') { $appArgs += '--demo' }
    & "$Jdk\bin\java.exe" -Xmx192m -jar dist\Fish.jar @appArgs
    if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
}
Write-Host "Fish $Task complete."
