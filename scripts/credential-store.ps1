$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FishCredentials {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct Credential {
        public uint Flags, Type;
        public string TargetName, Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist, AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias, UserName;
    }
    [DllImport("advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern bool Write(ref Credential credential, uint flags);
    [DllImport("advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern bool Read(string target, uint type, uint flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint="CredDeleteW", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern bool Delete(string target, uint type, uint flags);
    [DllImport("advapi32.dll")] public static extern void CredFree(IntPtr credential);
}
'@
    $operation = [Console]::ReadLine()
    $target = [Console]::ReadLine()
    $userName = [Console]::ReadLine()
    $encoded = [Console]::ReadLine()
    if (-not $target.StartsWith('Fish/')) { throw 'Invalid credential target.' }
    if ($operation -eq 'read') {
        $ptr = [IntPtr]::Zero
        if ([FishCredentials]::Read($target, 1, 0, [ref]$ptr)) {
            try {
                $credential = [Runtime.InteropServices.Marshal]::PtrToStructure($ptr, [type][FishCredentials+Credential])
                $secret = New-Object byte[] $credential.CredentialBlobSize
                [Runtime.InteropServices.Marshal]::Copy($credential.CredentialBlob, $secret, 0, $secret.Length)
                [Console]::WriteLine('FOUND ' + [Convert]::ToBase64String($secret))
                [Array]::Clear($secret, 0, $secret.Length)
            } finally { [FishCredentials]::CredFree($ptr) }
        } else {
            $code = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            if ($code -ne 1168) { throw "CredRead failed (Windows error $code)." }
            [Console]::WriteLine('MISSING')
        }
    } elseif ($operation -eq 'write') {
        $secret = [Convert]::FromBase64String($encoded)
        if ($secret.Length -gt 2560) { throw 'Password exceeds Credential Manager limit.' }
        $ptr = [Runtime.InteropServices.Marshal]::AllocHGlobal($secret.Length)
        try {
            [Runtime.InteropServices.Marshal]::Copy($secret, 0, $ptr, $secret.Length)
            $credential = New-Object 'FishCredentials+Credential'
            $credential.Type = 1
            $credential.TargetName = $target
            $credential.UserName = $userName
            $credential.CredentialBlobSize = $secret.Length
            $credential.CredentialBlob = $ptr
            $credential.Persist = 2
            if (-not [FishCredentials]::Write([ref]$credential, 0)) {
                $code = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
                throw "CredWrite failed (Windows error $code)."
            }
            [Console]::WriteLine('OK')
        } finally {
            [Array]::Clear($secret, 0, $secret.Length)
            [Runtime.InteropServices.Marshal]::Copy($secret, 0, $ptr, $secret.Length)
            [Runtime.InteropServices.Marshal]::FreeHGlobal($ptr)
        }
    } elseif ($operation -eq 'delete') {
        if (-not [FishCredentials]::Delete($target, 1, 0)) {
            $code = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            if ($code -ne 1168) { throw "CredDelete failed (Windows error $code)." }
        }
        [Console]::WriteLine('OK')
    } else { throw 'Unknown credential operation.' }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
