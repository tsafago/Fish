param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
if (-not ('FishBuildResources' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FishBuildResources {
    public delegate bool Language(IntPtr module,IntPtr type,IntPtr name,ushort language,IntPtr parameter);
    [DllImport("kernel32",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr LoadLibraryEx(string path,IntPtr file,uint flags);
    [DllImport("kernel32",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr FindResource(IntPtr module,IntPtr name,IntPtr type);
    [DllImport("kernel32",SetLastError=true)] public static extern uint SizeofResource(IntPtr module,IntPtr resource);
    [DllImport("kernel32",SetLastError=true)] public static extern IntPtr LoadResource(IntPtr module,IntPtr resource);
    [DllImport("kernel32")] public static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32")] public static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32",CharSet=CharSet.Unicode,SetLastError=true)] public static extern bool EnumResourceLanguages(IntPtr module,IntPtr type,IntPtr name,Language callback,IntPtr parameter);
    [DllImport("kernel32",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr BeginUpdateResource(string path,bool delete);
    [DllImport("kernel32",CharSet=CharSet.Unicode,SetLastError=true)] public static extern bool UpdateResource(IntPtr update,IntPtr type,IntPtr name,ushort language,byte[] data,uint size);
    [DllImport("kernel32",SetLastError=true)] public static extern bool EndUpdateResource(IntPtr update,bool discard);
}
'@
}
$path=(Resolve-Path -LiteralPath $Executable).Path
$module=[FishBuildResources]::LoadLibraryEx($path,[IntPtr]::Zero,2)
if ($module -eq [IntPtr]::Zero) { throw 'Cannot inspect packaged launcher resources.' }
$languages=[System.Collections.Generic.List[System.UInt16]]::new()
try {
    $callback=[FishBuildResources+Language]{param($module,$type,$name,$language,$parameter) $languages.Add($language); return $true}
    if (-not [FishBuildResources]::EnumResourceLanguages($module,[IntPtr]24,[IntPtr]1,$callback,[IntPtr]::Zero) -or $languages.Count -ne 1) {
        throw 'Expected exactly one packaged launcher manifest.'
    }
    $resource=[FishBuildResources]::FindResource($module,[IntPtr]1,[IntPtr]24)
    $size=[FishBuildResources]::SizeofResource($module,$resource)
    if ($size -lt 1 -or $size -gt 100000) { throw 'Invalid packaged launcher manifest size.' }
    $bytes=[byte[]]::new($size)
    [Runtime.InteropServices.Marshal]::Copy([FishBuildResources]::LockResource([FishBuildResources]::LoadResource($module,$resource)),$bytes,0,$size)
} finally { [void][FishBuildResources]::FreeLibrary($module) }
$document=[xml]::new()
$document.PreserveWhitespace=$true
$document.LoadXml([Text.Encoding]::UTF8.GetString($bytes).TrimEnd([char]0))
$application=$document.SelectSingleNode('/*[local-name()="assembly"]/*[local-name()="application"]')
if (-not $application) {
    $application=$document.CreateElement('application','urn:schemas-microsoft-com:asm.v3')
    [void]$document.DocumentElement.AppendChild($application)
}
$settings=$application.SelectSingleNode('./*[local-name()="windowsSettings"]')
if (-not $settings) {
    $settings=$document.CreateElement('windowsSettings','urn:schemas-microsoft-com:asm.v3')
    [void]$application.AppendChild($settings)
}
$codePage=$settings.SelectSingleNode('./*[local-name()="activeCodePage"]')
if (-not $codePage) {
    $codePage=$document.CreateElement('activeCodePage','http://schemas.microsoft.com/SMI/2019/WindowsSettings')
    [void]$settings.AppendChild($codePage)
}
$codePage.InnerText='UTF-8'
$payload=[Text.UTF8Encoding]::new($false).GetBytes($document.OuterXml)
$item=Get-Item -LiteralPath $path
$readOnly=$item.IsReadOnly
$item.IsReadOnly=$false
$update=[IntPtr]::Zero
try {
    $update=[FishBuildResources]::BeginUpdateResource($path,$false)
    if ($update -eq [IntPtr]::Zero) { throw 'Cannot update packaged launcher manifest.' }
    if (-not [FishBuildResources]::UpdateResource($update,[IntPtr]24,[IntPtr]1,$languages[0],$payload,$payload.Length)) {
        throw 'Cannot write UTF-8 launcher activation.'
    }
    if (-not [FishBuildResources]::EndUpdateResource($update,$false)) { $update=[IntPtr]::Zero; throw 'Cannot commit launcher manifest.' }
    $update=[IntPtr]::Zero
} finally {
    if ($update -ne [IntPtr]::Zero) { [void][FishBuildResources]::EndUpdateResource($update,$true) }
    (Get-Item -LiteralPath $path).IsReadOnly=$readOnly
}
