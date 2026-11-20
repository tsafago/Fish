# Full setup is written next to -Image unless -OutputDirectory is supplied.
# -BootstrapperOnly builds/tests the UI in build\ without creating an MSI or touching dist\.
param([string]$Jdk,[string]$Image,[Parameter(Mandatory=$true)][string]$Version,
    [string]$OutputDirectory,[switch]$BootstrapperOnly,[string]$LegacySetup)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Installer version must be numeric X.Y.Z.' }
if (!$BootstrapperOnly) {
    if (!$Jdk -or !$Image) { throw 'MSI packaging requires -Jdk and -Image.' }
    $Image=(Resolve-Path -LiteralPath $Image).Path
    if (!$OutputDirectory) { $OutputDirectory=Split-Path -Parent $Image }
    & "$PSScriptRoot\prepare-tools.ps1" -Kind Installer
}
$tools=Import-PowerShellDataFile (Join-Path $root 'tools.psd1')
$env:PATH=(Join-Path $root ('.tools\'+$tools.Installer.Directory))+';'+$env:PATH
$env:ZIG_GLOBAL_CACHE_DIR=Join-Path $root '.tools\zig-cache'
$zig=Join-Path $root ('.tools\'+$tools.Native.Directory+'\zig.exe')
if (-not (Test-Path -LiteralPath $zig)) { throw 'Build the application/native bridge before packaging setup.' }
$output=Join-Path $root ('build\installer-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $output | Out-Null
& "$PSScriptRoot\setup-image.ps1" -Image $Image -Output (Join-Path $output 'fish-image.generated.h')
if (!$BootstrapperOnly) {
$checker=Join-Path $output 'fish-msi-check.dll'
& $zig cc -shared -O2 -target x86_64-windows-gnu ("-I"+$output) (Join-Path $root 'src\native\fish-msi-check.c') -lmsi -lbcrypt -o $checker
if ($LASTEXITCODE) { throw 'MSI startup verifier compilation failed.' }
$wixTemp=Join-Path $output 'wix-temp'
& "$Jdk\bin\jpackage.exe" --type msi --name Fish --app-version $Version --app-image $Image `
    --dest $output --temp $wixTemp --vendor Fish --win-per-user-install --install-dir Fish `
    --win-menu --win-shortcut --win-menu-group Fish --win-upgrade-uuid '55b0674d-e226-46cb-852f-6beddd35a74b'
if ($LASTEXITCODE -ne 0) { throw 'Base MSI staging failed.' }
$msi=Join-Path $output "Fish-$Version.msi"

# Upgrade the staged WiX definition to dual-scope (per-user default + all-users MSI support via ALLUSERS=2/MSIINSTALLPERUSER=1)
# Uninstall only MSI-owned components. Do not start Fish or recursively purge INSTALLDIR.
$wixDir=Join-Path $root ('.tools\'+$tools.Installer.Directory)
$configDir=Join-Path $wixTemp 'config'
$bundleWxf=Join-Path $configDir 'bundle.wxf'
$mainWxs=Join-Path $configDir 'main.wxs'
if ((Test-Path -LiteralPath $bundleWxf) -and (Test-Path -LiteralPath $mainWxs)) {
    $bundleXml=[IO.File]::ReadAllText($bundleWxf,[Text.Encoding]::UTF8)
    $bundleXml=$bundleXml.Replace('<RegistryKey Root="HKCU"','<RegistryKey Root="HKMU"')
    $bundleXml=[regex]::Replace($bundleXml,
        '(<RegistrySearch\s+Id="([^"]+)"\s+Root="HKCU"([^>]*></RegistrySearch>))',
        '$1<RegistrySearch Id="$2_lm" Root="HKLM"$3')
    $bundleDoc=[xml]$bundleXml
    foreach ($action in @($bundleDoc.SelectNodes("//*[local-name()='RemoveFolderEx']"))) {
        if ($action.GetAttribute('On') -ne 'uninstall') { throw 'Unexpected jpackage recursive folder action.' }
        [void]$action.ParentNode.RemoveChild($action)
    }
    [IO.File]::WriteAllText($bundleWxf,$bundleDoc.OuterXml,[Text.UTF8Encoding]::new($false))

    $mainXml=[IO.File]::ReadAllText($mainWxs,[Text.Encoding]::UTF8)
    $mainXml=$mainXml.Replace('<?define JpInstallerVersion=200 ?>','<?define JpInstallerVersion=500 ?>')
    $mainXml=$mainXml.Replace('InstallScope="$(var.JpInstallScope)" ','')
    $dualProps=@'
    <Property Id="ALLUSERS" Value="2"/>
    <Property Id="MSIINSTALLPERUSER" Value="1"/>
    <Property Id="MSIRESTARTMANAGERCONTROL" Value="DisableShutdown"/>
'@
    $mainXml=$mainXml.Replace('<Media Id="1"',($dualProps+"`r`n    <Media Id=`"1`""))
    # Keep removal and replacement in one rollback boundary, including different builds of the same version.
    $mainXml=$mainXml.Replace('<RemoveExistingProducts Before="CostInitialize"/>','<RemoveExistingProducts After="InstallInitialize"/>').
        Replace('IncludeMaximum="$(var.JpUpgradeVersionOnlyDetectUpgrade)"','IncludeMaximum="yes"').
        Replace('IncludeMinimum="$(var.JpUpgradeVersionOnlyDetectDowngrade)"','IncludeMinimum="no"')
    $verifyActions=@'
    <Condition Message="Windows Installer rollback must be enabled to replace Fish safely.">Installed OR NOT RollbackDisabled</Condition>
    <Binary Id="FishVerifier" SourceFile="$(var.FishVerifier)"/>
    <CustomAction Id="SetFishCheckDir" Property="CheckFishImage" Value="[INSTALLDIR]"/>
    <CustomAction Id="CheckFishImage" BinaryKey="FishVerifier" DllEntry="CheckFishImage" Execute="deferred" Impersonate="yes" Return="check"/>
'@
    $mainXml=$mainXml.Replace('<InstallExecuteSequence>',($verifyActions+"`r`n    <InstallExecuteSequence>"+@'

      <Custom Action="SetFishCheckDir" After="InstallFiles">NOT (REMOVE="ALL")</Custom>
      <Custom Action="CheckFishImage" Before="InstallFinalize">NOT (REMOVE="ALL")</Custom>
'@))
    # Standard FindRelatedProducts is scoped to the invoking user/machine; the
    # JDK extension also searches other installation contexts.
    $mainXml=$mainXml.Replace('<Custom Action="JpFindRelatedProducts" After="FindRelatedProducts"/>','').
        Replace('After="JpFindRelatedProducts"','After="FindRelatedProducts"')
    [IO.File]::WriteAllText($mainWxs,$mainXml,[Text.UTF8Encoding]::new($false))

    # Re-compile and link the patched dual-scope MSI using the exact candle/light flags jpackage prepared.
    $wixObjOut=Join-Path $output 'wix-dual-obj'
    New-Item -ItemType Directory -Force -Path $wixObjOut | Out-Null
    $candleArgs=@('-nologo','-arch','x64','-ext','WixUtilExtension',"-dJpAppName=Fish","-dJpAppVersion=$Version",
        "-dJpProductCode=$([guid]::NewGuid().ToString('B').ToUpperInvariant())",
        '-dJpProductUpgradeCode={55B0674D-E226-46CB-852F-6BEDDD35A74B}',
        '-dJpAppVendor=Fish','-dJpAppDescription=Fish',"-dJpConfigDir=$configDir",
        '-dJpAllowUpgrades=yes',"-dFishVerifier=$checker",'-dJpExecutableOSVersion=600')
    $appIcon=Get-ChildItem -LiteralPath $configDir -Filter '*.ico' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($appIcon) { $candleArgs += "-dJpIcon=$($appIcon.FullName)" }
    $sizeKb=[int][Math]::Ceiling(((Get-ChildItem -LiteralPath $Image -Recurse -File | Measure-Object -Property Length -Sum).Sum)/1024.0)
    $candleArgs += "-dJpAppSizeKb=$sizeKb"
    $wxsInputs=@(Get-ChildItem -LiteralPath $configDir -File | Where-Object { $_.Extension -in @('.wxs','.wxf') } | ForEach-Object { $_.FullName })
    foreach ($srcFile in $wxsInputs) {
        $objTarget=Join-Path $wixObjOut (([IO.Path]::GetFileNameWithoutExtension($srcFile))+'.wixobj')
        & "$wixDir\candle.exe" @candleArgs -out $objTarget $srcFile
        if ($LASTEXITCODE -ne 0) { throw "Dual-scope WiX compile failed on $srcFile." }
    }
    $locArgs=@('-cultures:en-us')
    $enWxl=Join-Path $configDir 'MsiInstallerStrings_en.wxl'
    if (Test-Path -LiteralPath $enWxl) { $locArgs += @('-loc',$enWxl) }
    $objFiles=@(Get-ChildItem -LiteralPath $wixObjOut -Filter '*.wixobj' | ForEach-Object { $_.FullName })
    Remove-Item -LiteralPath $msi -Force -ErrorAction SilentlyContinue
    & "$wixDir\light.exe" -nologo -spdb -sval -ext WixUtilExtension -ext WixUIExtension -b $configDir -b $Image @locArgs -out $msi @objFiles
    if ($LASTEXITCODE -ne 0) { throw 'Dual-scope MSI linking failed.' }
} else { throw 'Cannot produce a dual-scope installer: missing staged WiX sources.' }
}
$parts=$Version.Split('.')
$header="#define FISH_VERSION L`"$Version`"`n#define FISH_VERSION_A `"$Version`"`n"
[IO.File]::WriteAllText((Join-Path $output 'fish-setup-version.h'),$header,[Text.UTF8Encoding]::new($false))
$icon=Join-Path $root 'build\Fish.ico'
if ($BootstrapperOnly) {
    # Sample the current compiled FishIcon, not a stale ICO left by an earlier release.
    # This renders one asset into the private staging folder; it does not build or launch Fish.
    if (!$Jdk) { $Jdk=Join-Path $root ('.tools\'+$tools.Jdk.Directory) }
    $fx=Join-Path $root ('.tools\'+$tools.JavafxSdk.Directory+'\lib')
    $icon=Join-Path $output 'Fish.ico'
    & "$Jdk\bin\java.exe" '-Djava.awt.headless=true' --module-path $fx --add-modules 'javafx.controls,javafx.swing' `
        --enable-native-access=javafx.graphics -cp (Join-Path $root 'build\classes') fish.ui.FishIcon $icon
    if ($LASTEXITCODE -ne 0) { throw 'Preview icon requires the compiled FishIcon class and local JavaFX modules.' }
}
$icon=$icon.Replace('\','\\')
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
   VALUE "FileDescription", "Fish setup"
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
    -luser32 -lgdi32 -lshell32 -lole32 -loleaut32 -luuid -lmsi -lversion -lbcrypt -ldwmapi -lcomctl32 -ladvapi32 -o $bootstrapper
if ($LASTEXITCODE -ne 0) { throw 'Native setup compilation failed.' }
$check=Start-Process -FilePath $bootstrapper -ArgumentList '--self-test' -Wait -PassThru
if ($check.ExitCode -ne 0) { throw 'Native setup contract checks failed.' }
$check=Start-Process -FilePath $bootstrapper -ArgumentList '--test-controls' -Wait -PassThru
if ($check.ExitCode -ne 0) { throw 'Never-shown native checkbox paint/state/contrast checks failed.' }
if ($BootstrapperOnly) { Write-Output $bootstrapper; return }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$destination=Join-Path $OutputDirectory "Fish-$Version-setup.exe"
Copy-Item -LiteralPath $bootstrapper -Destination $destination -Force
$stream=[IO.File]::Open($destination,[IO.FileMode]::Append,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    # Optional exact offline source for the public 0.10.4 package. It precedes
    # the normal footer-indexed payload; the bootstrapper pins its MSI hash too.
    if (!$LegacySetup) {
        $candidate=Join-Path $root 'dist\Fish-0.10.4-light-release\Fish-0.10.4-setup.exe'
        if (Test-Path -LiteralPath $candidate) { $LegacySetup=$candidate }
    }
    if ($LegacySetup) {
        if ((Get-FileHash -LiteralPath $LegacySetup).Hash -ne '002c72c4c9e844e6ea5962c50f77513425e5c94a58cd537033ef11d44863f9d9') { throw 'Not the released Fish 0.10.4 setup.' }
        $old=[IO.File]::OpenRead((Resolve-Path -LiteralPath $LegacySetup).Path)
        try {
            $remaining=61710948; $null=$old.Seek(-($remaining+56),[IO.SeekOrigin]::End); $buf=New-Object byte[] 65536
            while ($remaining -gt 0) { $n=$old.Read($buf,0,[Math]::Min($remaining,$buf.Length)); if (!$n) {throw 'Truncated legacy MSI'}; $stream.Write($buf,0,$n); $remaining-=$n }
        } finally { $old.Dispose() }
    }
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
