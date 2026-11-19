# Interactive Windows desktop only. Uses its own workspace, never global input.
param([Parameter(Mandatory=$true)][string]$ImageFolder,
    [ValidateSet('Charcoal','Midnight','Paper')][string]$Theme='Charcoal',
    [ValidateSet('Settings','Saved layouts / shortcuts','Fish manual','Keyboard reference')][string]$Surface='Settings')
$ErrorActionPreference='Stop'
$exe=(Resolve-Path (Join-Path $ImageFolder 'Fish.exe')).Path
$data=Join-Path (Resolve-Path build) ('first-paint-'+[guid]::NewGuid().ToString('N'))
if (-not ('FishFrame' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Threading;
using System.Runtime.InteropServices;
using System.Text;
public class FishFrame {
  public struct Rect { public int l,t,r,b; }
  public struct Pt { public int x,y; }
  public delegate bool Each(IntPtr h,IntPtr p);
  [DllImport("user32.dll")] static extern bool EnumWindows(Each f,IntPtr p);
  [DllImport("user32.dll")] static extern int GetWindowThreadProcessId(IntPtr h,out int pid);
  [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h,out Rect r);
  [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr h);
  [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr h, IntPtr dc);
  [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(Pt p);
  [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr h,int which);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h,int msg,IntPtr w,IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h,IntPtr after,int x,int y,int w,int ht,uint flags);
  [DllImport("gdi32.dll")] static extern uint GetPixel(IntPtr dc,int x,int y);
  [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c);
  [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h,int a,out int val,int n);
  [DllImport("user32.dll")] static extern uint GetDpiForWindow(IntPtr h);
  public IntPtr Dialog; public int Frames, White, First=-1, Last=-1;
  public string Geometry() { Rect r; int c; GetWindowRect(Dialog,out r); DwmGetWindowAttribute(Dialog,14,out c,4); return String.Format("hwnd={0} [{1},{2}..{3},{4}] cloak={5} dpi={6}",Dialog,r.l,r.t,r.r,r.b,c,GetDpiForWindow(Dialog)); }
  volatile bool done; Thread sampler;
  public FishFrame(int pid,string surface) {
    sampler=new Thread(delegate() {
      SetThreadDpiAwarenessContext(new IntPtr(-4));
      var dc=GetDC(IntPtr.Zero);
      try { while(!done) {
        if (Dialog==IntPtr.Zero) {
          EnumWindows(delegate(IntPtr h,IntPtr unused) {
            int id; GetWindowThreadProcessId(h,out id);
            if(id!=pid || !IsWindowVisible(h)) return true;
            var s=new StringBuilder(160); GetWindowText(h,s,s.Capacity);
            if(s.ToString()!=surface) return true;
            Dialog=h; return false;
          },IntPtr.Zero);
        }
        Rect r; if(Dialog!=IntPtr.Zero && GetWindowRect(Dialog,out r)) {
          // Blank left side of the integrated caption, away from text and buttons.
          var p=new Pt{x=r.l+36,y=r.t+20};
          if(GetAncestor(WindowFromPoint(p),2)==Dialog) {
            uint c=GetPixel(dc,p.x,p.y);
            if(c!=0xffffffff) {
              int v=(int)((c&255)+(c>>8&255)+(c>>16&255))/3;
              if(First<0) First=v; Last=v; if(v>=225) White++; Frames++;
            }
          }
        }
        Thread.Sleep(1);
      }} finally {ReleaseDC(IntPtr.Zero,dc);}
    }); sampler.IsBackground=true; sampler.Start();
  }
  public void Stop(){done=true;sampler.Join(2500);}
}
'@
}
[void][FishFrame]::SetThreadDpiAwarenessContext([IntPtr](-4))
[void](New-Item $data -ItemType Directory)
$image=Split-Path -Parent $exe
$config=Join-Path $data 'candidate.xml'
$action=@{'Settings'='Settings...'; 'Fish manual'='Full manual / Getting Started'; 'Saved layouts / shortcuts'='Saved layouts / shortcuts...'; 'Keyboard reference'='Keyboard reference'}[$Surface]
& "$image\runtime\bin\java.exe" '--enable-native-access=ALL-UNNAMED' -cp "$image\app\Fish.jar" fish.Main --config export --data-dir $data --file $config
if ($LASTEXITCODE) {throw 'Isolated fixture export failed.'}
$settings=[xml](Get-Content -LiteralPath $config -Raw)
foreach ($key in @('updates.startup','connectOnStartup','binding.Host F9',"binding.$action",'appTheme')) {
    $entry=$settings.SelectSingleNode("/properties/entry[@key='$key']")
    if (!$entry) { $entry=$settings.CreateElement('entry'); $entry.SetAttribute('key',$key); [void]$settings.DocumentElement.AppendChild($entry) }
    $entry.InnerText=$(if ($key -eq "binding.$action") {'F9'} elseif ($key -eq 'binding.Host F9') {''} elseif ($key -eq 'appTheme') {$Theme} else {'false'})
}
[IO.File]::WriteAllText($config,$settings.OuterXml.Replace('properties.dtd"[]>','properties.dtd">'),[Text.UTF8Encoding]::new($false))
& "$image\runtime\bin\java.exe" '--enable-native-access=ALL-UNNAMED' -cp "$image\app\Fish.jar" fish.Main --config apply --data-dir $data --file $config
if ($LASTEXITCODE) {throw 'Isolated fixture setup failed.'}
$launcher=Start-Process -FilePath $exe -ArgumentList "--data-dir `"$data`"" -PassThru
$child=$null
try {
    $deadline=(Get-Date).AddSeconds(25)
    do {
        $childId=Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launcher.Id)" |
            Where-Object ExecutablePath -eq $exe | Select-Object -First 1 -ExpandProperty ProcessId
        if ($childId) { $child=Get-Process -Id $childId; if ($child.MainWindowTitle -eq 'Fish') {break} }
        Start-Sleep -Milliseconds 120
    } while ((Get-Date) -lt $deadline)
    if (!$child -or !$child.Responding -or $child.MainWindowTitle -ne 'Fish') { throw "Packaged fixture not responsive: $($child.MainWindowTitle)" }
    Write-Output "Responsive packaged fixture PID $($child.Id)"
    $handle=$child.MainWindowHandle
    # Keep only our disposable fixture on screen; never sample obscuring windows.
    [void][FishFrame]::SetWindowPos($handle,[IntPtr](-1),0,0,0,0,0x13)
    Start-Sleep -Milliseconds 1400
    foreach($n in 1..3) {
        $sample=[FishFrame]::new($child.Id,$Surface)
        try {
            [void][FishFrame]::PostMessage($handle,256,[IntPtr]0x78,[IntPtr]0x430001)
            [void][FishFrame]::PostMessage($handle,257,[IntPtr]0x78,[IntPtr](-1069350911))
            $deadline=(Get-Date).AddSeconds(5)
            do { Start-Sleep -Milliseconds 60 } while ($sample.Frames -lt 30 -and (Get-Date) -lt $deadline)
        } finally { $sample.Stop() }
        Write-Output "$Surface open ${n}: $($sample.Geometry()); samples=$($sample.Frames); first=$($sample.First); last=$($sample.Last); white=$($sample.White)"
        if ($sample.Dialog -ne [IntPtr]::Zero) {
            # Escape exercises the real Settings keyboard close path, not WM_CLOSE.
            [void][FishFrame]::PostMessage($sample.Dialog,256,[IntPtr]27,[IntPtr]0x10001)
            [void][FishFrame]::PostMessage($sample.Dialog,257,[IntPtr]27,[IntPtr](-1073676287))
            Start-Sleep -Milliseconds 300
            if ([FishFrame]::IsWindowVisible($sample.Dialog)) { throw 'Escape did not dismiss Settings.' }
        }
        if ($sample.Frames -lt 8) {throw 'Could not sample the actual desktop.'}
        if ($Theme -eq 'Paper') {
            if ($sample.Last -lt 200) {throw 'Light Settings did not render its light background.'}
        } elseif ($sample.White -gt 0 -or $sample.Last -gt 90) { throw "White or unthemed Settings frame: $Theme." }
    }
} finally {
    if ($handle -and $handle -ne [IntPtr]::Zero) { [void][FishFrame]::PostMessage($handle,16,[IntPtr]::Zero,[IntPtr]::Zero) }
    elseif ($child -and !$child.HasExited) { [void]$child.CloseMainWindow() }
    if (!$launcher.WaitForExit(12000)) { if ($child -and !$child.HasExited) { $child.Kill() }; throw 'Packaged fixture failed to close.' }
    Write-Output "Launcher exit: $($launcher.ExitCode); saved workspace: $(Test-Path (Join-Path $data 'workspace.xml'))"
    if ($launcher.ExitCode -ne 0 -or !(Test-Path (Join-Path $data 'workspace.xml'))) { throw 'Packaged fixture did not exit normally.' }
    Remove-Item -LiteralPath $data -Recurse -Force
}
