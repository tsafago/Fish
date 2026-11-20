# Compile the startup contract into setup and its in-transaction MSI verifier.
param([string]$Image,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
$lines=[Collections.Generic.List[string]]::new()
$lines.Add('static const struct { const WCHAR *path; const BYTE digest[32]; } startup_files[]={')
if ($Image) {
    foreach ($rel in @('Fish.exe','app\Fish.cfg','app\Fish.jar','app\fish-native.dll','runtime\lib\modules','runtime\bin\jli.dll','runtime\bin\server\jvm.dll')) {
        $digest=(Get-FileHash -LiteralPath (Join-Path $Image $rel)).Hash -replace '(..)','0x$1,'
        $lines.Add(('{L"'+$rel.Replace('\','\\')+'",{'+$digest+'}},'))
    }
}
$lines.Add('{NULL,{0}}};')
$lines.Add('static const WCHAR *const image_entries[]={')
if ($Image) {
    foreach ($file in (Get-ChildItem -LiteralPath $Image -Recurse -Force)) {
        $rel=$file.FullName.Substring($Image.Length+1)
        if ($rel -notmatch '^[A-Za-z0-9_ .()+\\-]+$') { throw "Unexpected image filename: $rel" }
        $lines.Add('L"'+$rel.Replace('\','\\')+'",')
    }
}
$lines.Add('L"app\\.package",NULL};')
[IO.File]::WriteAllLines($Output,$lines,[Text.UTF8Encoding]::new($false))
