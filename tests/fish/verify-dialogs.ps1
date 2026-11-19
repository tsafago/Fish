# Interactive Windows x64 desktop. No build, download, install, Robot, SendKeys or input injection.
param([string]$ClassDirectory, [switch]$DarkOnly, [ValidateSet('1.0','1.5','2.0')][string]$PaperScale='2.0')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifest=Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
$jdk=Join-Path $root ('.tools\'+$manifest.Jdk.Directory+'\bin')
$fx=Join-Path $root ('.tools\'+$manifest.JavafxSdk.Directory+'\lib')
if (!$ClassDirectory) { $ClassDirectory=Join-Path $root 'build\classes' }
$prod=(Resolve-Path $ClassDirectory).Path
$dll=Join-Path $prod 'fish-native.dll'
if (!(Test-Path $dll)) { $dll=Join-Path $root 'build\fish-native.dll' }
if (!(Test-Path "$prod\fish\ui\Workspace.class") -or !(Test-Path $dll)) {
    throw 'Need matching compiled Fish classes/resources and fish-native.dll. Ask the build owner; this fixture does not build Fish.'
}
$run=(New-Item -ItemType Directory -Path (Join-Path $root ('build\dialog-ui-'+[guid]::NewGuid().ToString('N')))).FullName
Write-Output "Fixture scratch / logs: $run"
$classes=Join-Path $run 'production'
Copy-Item -LiteralPath $prod -Destination $classes -Recurse
Copy-Item -LiteralPath $dll -Destination (Join-Path $classes 'fish-native.dll') -Force
$tests=(New-Item -ItemType Directory -Path "$run\fixture").FullName
$modules=@('--module-path',$fx,'--add-modules','javafx.base,javafx.controls,javafx.graphics,javafx.swing')
$sources=@((Join-Path $PSScriptRoot 'FxTestSupport.java'))+@(Get-ChildItem "$PSScriptRoot\dialog\*.java" | ForEach-Object FullName)
Push-Location $run
try {
    & "$jdk\javac.exe" --release 25 -encoding UTF-8 @modules -cp $classes -d $tests @sources
    if ($LASTEXITCODE) { throw 'Fixture compile failed' }
    $cases=@(@{theme='Charcoal'; scale='native'; full='true'})
    if (!$DarkOnly) { $cases+=@{theme='Paper'; scale=$PaperScale; full='false'} }
    $failed=@()
    foreach ($case in $cases) {
        $name="$($case.theme)-$($case.scale)"
        $cwd=(New-Item -ItemType Directory -Path "$run\$name").FullName
        $tmp=(New-Item -ItemType Directory -Path "$cwd\temp").FullName
        $homeDir=(New-Item -ItemType Directory -Path "$cwd\home").FullName
        $argv=@('-ea','-Dfish.testMode=true','-Dfish.test.kiosk=false',"-Djava.io.tmpdir=$tmp","-Duser.home=$homeDir",
            '-Djavafx.enablePreview=true','-Djavafx.suppressPreviewWarning=true','--enable-native-access=javafx.graphics,ALL-UNNAMED')
        if ($case.scale -ne 'native') { $argv+=("-Dglass.win.uiScale="+$case.scale) }
        $argv+=$modules+@('-cp',"$classes;$tests",
            'fish.ui.DialogPathsSmoke',$case.theme,$case.scale,$case.full)
        $quoted=($argv | ForEach-Object { '"'+$_+'"' }) -join ' '
        $proc=Start-Process -FilePath "$jdk\java.exe" -WorkingDirectory $cwd -ArgumentList $quoted -PassThru -NoNewWindow `
            -RedirectStandardOutput "$cwd\result.log" -RedirectStandardError "$cwd\stderr.log"
        $null=$proc.Handle
        if (!$proc.WaitForExit(115000)) { Stop-Process -Id $proc.Id -Force; throw "$name timed out" }
        Get-Content "$cwd\result.log"
        Get-Content "$cwd\stderr.log"
        if ($proc.ExitCode -ne 0) { $failed+=$name }
    }
    if ($failed.Count) { throw "Dialog verification failed: $($failed -join ', '). Logs: $run" }
} finally { Pop-Location }
