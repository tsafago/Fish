param([double]$Scale=1.5)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$tools=Import-PowerShellDataFile "$root\tools.psd1"
$jdk="$root\.tools\$($tools.Jdk.Directory)\bin"
$modules=@('--module-path',"$root\.tools\$($tools.JavafxSdk.Directory)\lib",'--add-modules','javafx.controls,javafx.swing')
$out=New-Item -ItemType Directory "$root\build\popup-smoke-$([guid]::NewGuid().ToString('N'))"
Push-Location $out.FullName
try {
    # Read the prepared emulator core; never clean/rebuild shared output.
    if (!(Test-Path "$root\build\generated\org\tn5250j")) { throw 'Prepared build\generated emulator sources required.' }
    $sources=@(Get-ChildItem "$root\src","$root\build\generated" -Recurse -Filter *.java
               Get-Item "$root\tests\fish\FxTestSupport.java","$root\tests\fish\WorkspacePopupSmoke.java")
    [IO.File]::WriteAllLines("$out\sources.txt",($sources | ForEach-Object { '"'+$_.FullName.Replace('\','\\')+'"' }),
        [Text.UTF8Encoding]::new($false))
    & "$jdk\javac.exe" --release 25 -encoding UTF-8 @modules -d classes '@sources.txt'
    if ($LASTEXITCODE) { throw 'Fixture compile failed' }
    & "$jdk\java.exe" @modules '--enable-native-access=javafx.graphics,ALL-UNNAMED' `
        '--add-exports=javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED' `
        '--add-exports=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED' `
        "-Dglass.win.uiScale=$($Scale.ToString([Globalization.CultureInfo]::InvariantCulture))" `
        "-Djava.io.tmpdir=$out" -cp classes fish.ui.WorkspacePopupSmoke $Scale
    if ($LASTEXITCODE) { throw 'Workspace popup smoke failed' }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $out.FullName -Recurse -Force
}
