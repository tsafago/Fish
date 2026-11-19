# Exercises the real windows with simulated work only; never invokes an install command line.
param([Parameter(Mandatory=$true)][string]$Bootstrapper,
    [ValidateSet('light','dark')][string]$Theme='light',
    [string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$exe=(Resolve-Path -LiteralPath $Bootstrapper).Path
if (-not ('FishPreviewWindow' -as [type])) {
    Add-Type -ReferencedAssemblies Accessibility,System.Drawing -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using Accessibility;
public static class FishPreviewWindow {
    [DllImport("user32.dll")] public static extern IntPtr GetDlgItem(IntPtr hwnd,int id);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd,System.Text.StringBuilder text,int length);
    public static string Caption(IntPtr hwnd) {
        var text=new System.Text.StringBuilder(256); GetWindowText(hwnd,text,text.Capacity); return text.ToString();
    }
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr hwnd,int msg,IntPtr wp,IntPtr lp);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr FindWindowEx(IntPtr root,IntPtr after,string cls,string text);
    [DllImport("oleacc.dll")] static extern int AccessibleObjectFromWindow(IntPtr wnd,uint id,ref Guid iid,[MarshalAs(UnmanagedType.Interface)] out IAccessible acc);
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int l,t,r,b; }
    [StructLayout(LayoutKind.Sequential)] struct Monitor { public int size; public Rect bounds,work; public int flags; }
    delegate bool EachMonitor(IntPtr m,IntPtr dc,ref Rect r,IntPtr data);
    [DllImport("user32")] static extern bool EnumDisplayMonitors(IntPtr dc,IntPtr clip,EachMonitor callback,IntPtr data);
    [DllImport("user32")] static extern IntPtr MonitorFromWindow(IntPtr h,int fallback);
    [DllImport("user32")] static extern bool GetMonitorInfoW(IntPtr m,ref Monitor info);
    [DllImport("shcore")] static extern int GetDpiForMonitor(IntPtr m,int type,out int x,out int y);
    [DllImport("user32")] static extern int GetDpiForWindow(IntPtr h);
    [DllImport("user32")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32")] static extern IntPtr GetWindowDpiAwarenessContext(IntPtr h);
    [DllImport("user32")] static extern bool AreDpiAwarenessContextsEqual(IntPtr a,IntPtr b);
    [DllImport("user32")] static extern bool GetWindowRect(IntPtr h,out Rect r);
    [DllImport("user32")] static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int w,int height,uint flags);
    [DllImport("user32")] static extern bool PrintWindow(IntPtr h,IntPtr dc,uint flags);
    [DllImport("user32",EntryPoint="SystemParametersInfoW")] static extern bool Setting(uint action,uint arg,out int value,uint flags);
    [StructLayout(LayoutKind.Sequential)] struct HighContrast { public uint size, flags; public IntPtr scheme; }
    [DllImport("user32",EntryPoint="SystemParametersInfoW")] static extern bool Contrast(uint action,uint arg,ref HighContrast value,uint flags);
    [DllImport("gdi32")] static extern int GetObjectW(IntPtr obj,int bytes,[Out] byte[] value);
    static int FontSize(IntPtr h) {
        var font=SendMessageW(h,0x31,IntPtr.Zero,IntPtr.Zero); var lf=new byte[92];
        if (GetObjectW(font,lf.Length,lf)!=lf.Length) throw new Exception("Missing live child font.");
        return -BitConverter.ToInt32(lf,0);
    }
    static int Px(int units,int dpi) { return (units*dpi+48)/96; }
    static Bitmap Frame(IntPtr root) {
        Rect r; GetWindowRect(root,out r);
        var image=new Bitmap(r.r-r.l,r.b-r.t);
        using (var g=Graphics.FromImage(image)) {
            var dc=g.GetHdc(); bool ok=PrintWindow(root,dc,2); g.ReleaseHdc(dc);
            if (!ok) { image.Dispose(); throw new Exception("Could not capture preview HWND."); }
        }
        return image;
    }
    public static void CheckCoast(IntPtr root,bool installer,string file) {
        var original=SetThreadDpiAwarenessContext(new IntPtr(-4));
        try {
            using (var a=Frame(root)) {
                var sea=a.GetPixel(a.Width/8,a.Height/8);
                var sand=a.GetPixel(a.Width*19/20,a.Height*3/4);
                if (Math.Min(sea.R,Math.Min(sea.G,sea.B))<150 || Math.Min(sand.R,Math.Min(sand.G,sand.B))<150)
                    throw new Exception("Setup and updates must always use the light palette.");
                if (!String.IsNullOrEmpty(file)) a.Save(file,ImageFormat.Png);
                if (!installer) return;
                System.Threading.Thread.Sleep(540);
                using (var b=Frame(root)) {
                    int left=0,right=0; double scale=a.Width/680.0;
                    for(int y=(int)(36*scale);y<(int)(203*scale);y+=2)
                        for(int x=0;x<a.Width;x+=2) {
                            if(a.GetPixel(x,y)==b.GetPixel(x,y)) continue;
                            if(x<a.Width/2) left++; else right++;
                        }
                    int enabled;
                    var hc=new HighContrast(); hc.size=(uint)Marshal.SizeOf(typeof(HighContrast));
                    bool animate=Setting(0x1042,0,out enabled,0) && enabled!=0 &&
                        Contrast(0x42,hc.size,ref hc,0) && (hc.flags&1)==0;
                    Console.WriteLine("Shore pixels changed (2px samples, left/right): {0}/{1}",left,right);
                    if (animate && left<8)
                        throw new Exception("The live tide did not move on the water side of the installer.");
                    if (!animate && (left!=0 || right!=0)) throw new Exception("Reduced-motion tide moved.");
                }
            }
        } finally { SetThreadDpiAwarenessContext(original); }
    }
    public static void CheckLiveDpi(IntPtr root,bool installer) {
        var pmv2=new IntPtr(-4); var original=SetThreadDpiAwarenessContext(pmv2);
        if(original==IntPtr.Zero) throw new Exception("Cannot measure physical window coordinates.");
        try {
            if (!AreDpiAwarenessContextsEqual(GetWindowDpiAwarenessContext(root),pmv2))
                throw new Exception("Setup did not start per-monitor DPI aware (v2).");
            var launch=MonitorFromWindow(root,2); var route=new List<IntPtr>{launch};
            if (installer) {
                EnumDisplayMonitors(IntPtr.Zero,IntPtr.Zero,delegate(IntPtr m,IntPtr dc,ref Rect r,IntPtr d) {
                    if(m!=launch) route.Add(m); return true;
                },IntPtr.Zero);
                if(route.Count>1) route.Add(launch);
            }
            for(int i=0;i<route.Count;i++) {
                var m=route[i]; var mi=new Monitor(); mi.size=Marshal.SizeOf(typeof(Monitor));
                int expected,y; if(!GetMonitorInfoW(m,ref mi) || GetDpiForMonitor(m,0,out expected,out y)!=0)
                    throw new Exception("Cannot read effective monitor DPI.");
                var w=mi.work;
                if(i!=0 && !SetWindowPos(root,IntPtr.Zero,(w.l+w.r)/2-200,(w.t+w.b)/2-160,0,0,0x15))
                    throw new Exception("Failed to move the preview window.");
                Rect r; GetWindowRect(root,out r); int dpi=GetDpiForWindow(root);
                int scale=Math.Min(dpi,Math.Min((w.r-w.l)*96/680,(w.b-w.t)*96/308));
                if(dpi!=expected || MonitorFromWindow(root,2)!=m || r.r-r.l!=Px(680,scale) || r.b-r.t!=Px(216,scale))
                    throw new Exception("Incorrect window dimensions after first show or display move: "+(r.r-r.l)+"x"+(r.b-r.t)+" @"+dpi);
                if(i==0 && (Math.Abs(r.l+r.r-w.l-w.r)>2 || Math.Abs(r.t+r.b-w.t-w.b)>2))
                    throw new Exception("First-show window was not centered using its physical size.");
                var action=GetDlgItem(root,101); Rect button; GetWindowRect(action,out button);
                int body=FontSize(GetDlgItem(root,104)),buttonFont=FontSize(action);
                if(GetDpiForWindow(action)!=expected || button.r-button.l!=Px(94,scale) || button.b-button.t!=Px(28,scale) ||
                    button.l-r.l!=Px(575,scale) || button.t-r.t!=Px(174,scale) ||
                    body!=Px(13,scale) || buttonFont!=Px(13,scale)) throw new Exception("Child button/font/anchor mismatch.");
                Console.WriteLine("{0}: {1} DPI, window {2}x{3}, button {4}x{5}, body/button font {6}/{7} px",
                    i==0?"First show":"Monitor move",dpi,r.r-r.l,r.b-r.t,button.r-button.l,button.b-button.t,body,buttonFont);
            }
        } finally { SetThreadDpiAwarenessContext(original); }
    }
    public static string AccessibleProgress(IntPtr root) {
        var fish=FindWindowEx(root,IntPtr.Zero,"msctls_progress32",null);
        var iid=typeof(IAccessible).GUID; IAccessible acc;
        if (!IsWindowVisible(fish) || AccessibleObjectFromWindow(fish,0xfffffffc,ref iid,out acc)!=0)
            throw new Exception("No accessible progress fish.");
        try {
            if ((int)acc.get_accRole(0)!=0x30 || String.IsNullOrEmpty(acc.get_accName(0)))
                throw new Exception("Expected a named native progress indicator.");
            return acc.get_accValue(0);
        } finally { Marshal.ReleaseComObject(acc); }
    }
}
'@
}
function Button([int]$id) { [FishPreviewWindow]::GetDlgItem($wnd,$id) }
function Visible([int]$id) { [FishPreviewWindow]::IsWindowVisible((Button $id)) }
function Click([int]$id) {
    if (!(Visible $id)) { throw "Button $id is not visible." }
    [void][FishPreviewWindow]::SendMessageW((Button $id),0xf5,[IntPtr]::Zero,[IntPtr]::Zero)
}
foreach ($scenario in @('installer','update','update-error','launch-error')) {
    $proc=Start-Process -FilePath $exe -ArgumentList "--preview-$scenario $Theme" -PassThru
    try {
        $deadline=[DateTime]::UtcNow.AddSeconds(12)
        do {
            $proc.Refresh(); $wnd=$proc.MainWindowHandle
            if ($wnd -ne [IntPtr]::Zero -or $proc.HasExited) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($wnd -eq [IntPtr]::Zero) { throw "No window for $scenario." }
        [FishPreviewWindow]::CheckLiveDpi($wnd,($scenario -eq 'installer'))
        $file=$null
        if ($EvidenceDirectory) {
            $file=Join-Path (Resolve-Path -LiteralPath $EvidenceDirectory) "$Theme-$scenario.png"
        }
        [FishPreviewWindow]::CheckCoast($wnd,($scenario -eq 'installer'),$file)
        if ($scenario -eq 'installer') {
            if ((Visible 103) -or (Visible 105)) { throw 'Settings were not collapsed.' }
            Click 106; Click 105; Click 103; Click 104; Click 106
            if ((Visible 103) -or (Visible 105)) { throw 'Settings did not collapse again.' }
            Click 101
            foreach ($id in 101,104,106) {
                if (!(Visible $id) -or [FishPreviewWindow]::IsWindowEnabled((Button $id))) {
                    throw "Busy install must retain disabled control $id."
                }
            }
            if ([FishPreviewWindow]::Caption((Button 101)) -ne "Installing$([char]0x2026)") {
                throw 'Expected the installing state on the original action.'
            }
            if ($file) {
                [FishPreviewWindow]::CheckCoast($wnd,$false,(Join-Path $EvidenceDirectory "$Theme-installing.png"))
            }
        } else {
            if ((Visible 104) -or (Visible 106) -or (Visible 101)) { throw 'Update exposed installer settings.' }
        }
        # Preparing reports bytes; MSI and final verification have no measured range.
        $last=-1; $changes=0; $indeterminate=$false
        do {
            $value=[FishPreviewWindow]::AccessibleProgress($wnd)
            if ($value -match '^(\d+)%$') {
                $n=[int]$Matches[1]
                if ($n -lt $last -or $n -ge 100 -or ($n % 4) -ne 0) {
                    throw "Accessible value is not a measured preparation step: $value (after $last%)."
                }
                if ($n -gt $last) { $changes++ }; $last=$n
            } elseif ($value -eq 'In progress' -and $changes -ge 2) {
                $indeterminate=$true; break
            }
            Start-Sleep -Milliseconds 65
        } while ([DateTime]::UtcNow -lt $deadline)
        if (!$indeterminate) { throw 'Missing measured progress or transition to indeterminate MSI work.' }
        if ($file -and $scenario -eq 'update') {
            Start-Sleep -Milliseconds 360
            [FishPreviewWindow]::CheckCoast($wnd,$false,$file)
        }
        if ($scenario -in @('installer','update','launch-error')) {
            $clock=[Diagnostics.Stopwatch]::StartNew()
            $found=$false
            while ($clock.ElapsedMilliseconds -lt 9000 -and !$proc.HasExited) {
                if ([FishPreviewWindow]::AccessibleProgress($wnd) -eq '100%') { $found=$true; break }
                Start-Sleep -Milliseconds 50
            }
            if (!$found) { throw 'No verified completion frame before closing or handing off launch.' }
            Start-Sleep -Milliseconds 1100
            if ($proc.HasExited -or [FishPreviewWindow]::AccessibleProgress($wnd) -ne '100%') {
                throw 'The verified completion frame disappeared too soon.'
            }
        }
        if ($scenario -in @('update-error','launch-error')) {
            do {
                if ((Visible 101) -or $proc.HasExited) { break }
                Start-Sleep -Milliseconds 100
            } while ([DateTime]::UtcNow -lt $deadline)
            if ($proc.HasExited -or !(Visible 101)) { throw 'Error should leave a recovery action.' }
            $expected=if ($scenario -eq 'launch-error') {'Open Fish'} else {'Retry'}
            if ([FishPreviewWindow]::Caption((Button 101)) -ne $expected) { throw "Expected $expected after $scenario." }
            Click 101
        }
        if (!$proc.WaitForExit(12000) -or $proc.ExitCode -ne 0) { throw "Preview did not close on success: $scenario" }
        Write-Host "PASS: $scenario"
    } finally {
        if (!$proc.HasExited) { $proc.Kill(); $proc.WaitForExit() }
        $proc.Dispose()
    }
}
