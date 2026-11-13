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
$manifest = Import-PowerShellDataFile .\tools.psd1
if (-not $Jdk) {
    $candidate = Join-Path $PSScriptRoot ('.tools\' + $manifest.Jdk.Directory)
    if (Test-Path "$candidate\bin\javac.exe") { $Jdk = $candidate }
}
if (-not $Jdk -or -not (Test-Path "$Jdk\bin\javac.exe")) {
    throw 'JDK 25 required. Set JAVA_HOME or run .\scripts\bootstrap.ps1.'
}
$fxLib = Join-Path $PSScriptRoot ('.tools\' + $manifest.JavafxSdk.Directory + '\lib')
if (-not (Test-Path "$fxLib\javafx.controls.jar")) {
    & "$PSScriptRoot\scripts\prepare-tools.ps1" -Kind JavafxSdk
}
$fxJmods = Join-Path $PSScriptRoot ('.tools\' + $manifest.JavafxJmods.Directory)
if ($Task -eq 'Package' -and -not (Test-Path "$fxJmods\javafx.controls.jmod")) {
    & "$PSScriptRoot\scripts\prepare-tools.ps1" -Kind JavafxJmods
}
$fxModules = 'javafx.base,javafx.controls,javafx.graphics,javafx.swing'
$fxJvmArgs = @(
    '--enable-native-access=javafx.graphics,ALL-UNNAMED',
    '-Djavafx.enablePreview=true',
    '-Djavafx.suppressPreviewWarning=true',
    '--module-path', $fxLib,
    '--add-modules', $fxModules
)
if ($Task -eq 'Installer') {
    $executable=Join-Path $image 'Fish.exe'
    if (-not (Test-Path -LiteralPath $executable)) { throw 'Build the application first: .\build.ps1 Package' }
    if ((Get-Item -LiteralPath $executable).VersionInfo.ProductVersion -ne $version) {
        throw "Application image is not Fish $version. Package first, or select its matching -ImageDirectory."
    }
    & "$PSScriptRoot\scripts\build-installer.ps1" -Jdk $Jdk -Image $image -Version $version
    Copy-Item Install-Fish.ps1,Uninstall-Fish.ps1 dist -Force
    Compress-Archive -Path $image -DestinationPath "dist\Fish-$version-windows-x64.zip" -Force
    $checksums = foreach ($file in @("dist\Fish-$version-setup.exe","dist\Fish-$version-windows-x64.zip",'dist\Fish-source.zip','dist\Install-Fish.ps1','dist\Uninstall-Fish.ps1')) {
        (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($file)
    }
    [IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'dist\SHA256SUMS.txt'),$checksums,[Text.UTF8Encoding]::new($false))
    Write-Host "Fish $version user-wide installer, SmartScreen-free Install-Fish.ps1, and portable ZIP ready."
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
& "$Jdk\bin\javac.exe" --release 25 -encoding UTF-8 -Xlint:unchecked,-deprecation,-removal --module-path $fxLib --add-modules $fxModules -d build\classes "@$sourceList"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
Copy-Item MANUAL.md,AI-CONFIG.md build\classes
& "$Jdk\bin\java.exe" @fxJvmArgs -cp build\classes fish.Main --config schema --file build\classes\CONFIG-SCHEMA.json
if ($LASTEXITCODE -ne 0) { throw 'Configuration catalog generation failed.' }
Copy-Item build\classes\CONFIG-SCHEMA.json CONFIG-SCHEMA.json -Force
& "$Jdk\bin\jar.exe" --create --file dist\Fish.jar --main-class fish.Main -C build\classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
Copy-Item scripts\credential-store.ps1 dist\credential-store.ps1 -Force
Copy-Item build\fish-native.dll dist\fish-native.dll -Force
Copy-Item vendor\tn5250j\LICENSE dist\COPYING-TN5250J -Force
if ($Task -eq 'Maintenance' -or $Task -eq 'Smoke' -or $Task -eq 'Test' -or $Task -eq 'Check') {
    $testFiles = if ($Task -eq 'Maintenance') { Get-Item tests\fish\FxTestSupport.java,tests\fish\Tests.java,tests\fish\MaintenanceSmoke.java,tests\fish\MaintenanceUiSmoke.java,tests\fish\NoticeSmoke.java,tests\fish\NativeUpdateSmoke.java,tests\fish\KeyboardPreferencesSmoke.java,tests\fish\SettingsTutorialSmoke.java,tests\fish\LaunchBrokerSmoke.java,tests\fish\WindowRevisionSmoke.java,tests\fish\LayoutFeatureSmoke.java,tests\fish\ShortcutRefinementSmoke.java,tests\fish\UsabilityIterationSmoke.java,tests\fish\SimplePaneSmoke.java,tests\fish\CursorRenderingSmoke.java,tests\fish\BetaSettingsSmoke.java,tests\fish\BetaRecoverySmoke.java,tests\fish\BetaUiSmoke.java }
        elseif ($Task -eq 'Smoke') { Get-Item tests\fish\FxTestSupport.java,tests\fish\Tests.java,tests\fish\FishSmoke.java,tests\fish\StreamliningSmoke.java,tests\fish\CustomizationSmoke.java,tests\fish\ChromeConsistencySmoke.java,tests\fish\RefinementSmoke.java,tests\fish\UsabilitySmoke.java,tests\fish\KerberosSmoke.java } else { Get-ChildItem tests -Recurse -Filter *.java }
    $testSources = $testFiles | ForEach-Object { '"' + $_.FullName.Replace('\','\\') + '"' }
    [IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'build\tests.txt'), $testSources, [Text.UTF8Encoding]::new($false))
    & "$Jdk\bin\javac.exe" --release 25 -encoding UTF-8 -Xlint:-deprecation,-removal --module-path $fxLib --add-modules $fxModules -cp build\classes -d build\test-classes '@build\tests.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    if ($Task -eq 'Maintenance') {
        foreach ($fixture in @('fish.Tests','fish.update.MaintenanceSmoke','fish.ui.NoticeSmoke','fish.ui.MaintenanceUiSmoke','fish.ui.KeyboardPreferencesSmoke','fish.ui.SettingsTutorialSmoke','fish.LaunchBrokerSmoke','fish.ui.WindowRevisionSmoke','fish.ui.LayoutFeatureSmoke','fish.ui.ShortcutRefinementSmoke','fish.ui.UsabilityIterationSmoke','fish.ui.SimplePaneSmoke','fish.ui.CursorRenderingSmoke','fish.ui.BetaSettingsSmoke','fish.ui.BetaRecoverySmoke','fish.ui.BetaUiSmoke')) {
            $fixtureArgs=@()
            if ($fixture -eq 'fish.Tests') { $fixtureArgs+='--protocol-only' }
            & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' $fixture @fixtureArgs
            if ($LASTEXITCODE -ne 0) { throw "Noninteractive maintenance fixture failed: $fixture" }
        }
        Write-Host 'Maintenance checks complete; no visible windows, clipboard, credentials, real-host connections or installer execution.'
        return
    }
    if ($Task -ne 'Smoke') {
        $testArgs=@()
        if ($Task -eq 'Check') { $testArgs+='--core-only' }
        & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.Tests @testArgs
        if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
    } else {
        & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.Tests --protocol-only
        if ($LASTEXITCODE -ne 0) { throw 'Protocol/model fixtures failed.' }
    }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.ui.KerberosSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Kerberos fixture checks failed.' }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.ui.UsabilitySmoke
    if ($LASTEXITCODE -ne 0) { throw 'Usability smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.FishSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Fish smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.StreamliningSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Streamlining smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.CustomizationSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Customization smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.ui.ChromeConsistencySmoke
    if ($LASTEXITCODE -ne 0) { throw 'Chrome consistency smoke checks failed.' }
    & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.ui.RefinementSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Interaction refinement smoke checks failed.' }
    if ($Task -eq 'Test') {
        & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.DesktopRendering
        if ($LASTEXITCODE -ne 0) { throw 'Desktop rendering/window tests failed.' }
        & "$Jdk\bin\java.exe" -ea '-Dfish.testMode=true' '-Dfish.effect=Soft glow' @fxJvmArgs -cp 'build\classes;build\test-classes' fish.DesktopRendering
        if ($LASTEXITCODE -ne 0) { throw 'Opt-in effect resize/window tests failed.' }
    }
}
if ($Task -eq 'Package') {
    if (Test-Path -LiteralPath $image) { throw 'Application image exists. Close/preserve it first, or use -ImageDirectory dist\Fish-VERSION\Fish for a side-by-side build.' }
    New-Item -ItemType Directory -Force build\package-input | Out-Null
    Copy-Item dist\Fish.jar,dist\fish-native.dll,dist\credential-store.ps1,dist\COPYING-TN5250J,Install-Fish.ps1,Uninstall-Fish.ps1,README.md,MANUAL.md,AI-CONFIG.md,CONFIG-SCHEMA.json,COMPATIBILITY.md,LICENSE,NOTICE build\package-input -Force
    & "$Jdk\bin\java.exe" @fxJvmArgs -cp build\classes fish.ui.FishIcon build\Fish.ico
    if ($LASTEXITCODE -ne 0) { throw 'Fish icon generation failed.' }
    New-Item -ItemType Directory -Force (Split-Path -Parent $image) | Out-Null
    $jmodPath = "$Jdk\jmods;$fxJmods"
    & "$Jdk\bin\jpackage.exe" --type app-image --name Fish --app-version $version --icon build\Fish.ico `
        --input build\package-input --main-jar Fish.jar --main-class fish.Main `
        --dest (Split-Path -Parent $image) --vendor Fish `
        --module-path $jmodPath `
        --add-modules java.base,java.desktop,java.xml,java.logging,java.net.http,jdk.crypto.ec,jdk.charsets,javafx.base,javafx.controls,javafx.graphics,javafx.swing `
        --java-options '-Xmx256m' `
        --java-options '--enable-native-access=javafx.graphics,ALL-UNNAMED' `
        --java-options '-Djavafx.enablePreview=true' `
        --java-options '-Djavafx.suppressPreviewWarning=true' `
        --java-options '--add-modules=javafx.base,javafx.controls,javafx.graphics,javafx.swing'
    if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    & "$PSScriptRoot\scripts\set-launcher-encoding.ps1" -Executable (Join-Path $image 'Fish.exe')
    Copy-Item scripts\Fish-config.cmd,Install-Fish.ps1,Uninstall-Fish.ps1 $image -Force
    $source = Join-Path $image 'app\source'
    New-Item -ItemType Directory -Force $source | Out-Null
    Copy-Item src,scripts,tests $source -Recurse
    Copy-Item build.ps1,Setup-Fish.cmd,Setup-Fish.ps1,Install-Fish.ps1,Uninstall-Fish.ps1,tools.psd1,README.md,MANUAL.md,AI-CONFIG.md,CONFIG-SCHEMA.json,COMPATIBILITY.md,LICENSE,NOTICE,PRODUCT.md,DESIGN.md $source
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
    & "$Jdk\bin\java.exe" -Xmx256m @fxJvmArgs -jar dist\Fish.jar @appArgs
    if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
}
Write-Host "Fish $Task complete."
