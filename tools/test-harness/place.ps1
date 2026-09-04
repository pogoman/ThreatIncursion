# Force the Starsector window on-screen at X,Y (topmost) and print its client rect.
param([int]$X = 0, [int]$Y = 0, [switch]$NoTopmost)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class WP {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@ -ErrorAction SilentlyContinue
$p = Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" } | Select-Object -First 1
if (-not $p) { Write-Output "NOWINDOW"; exit 0 }
$h = $p.MainWindowHandle
[WP]::ShowWindow($h, 9) | Out-Null
$after = if ($NoTopmost) { [IntPtr](-2) } else { [IntPtr](-1) }
[WP]::SetWindowPos($h, $after, $X, $Y, 0, 0, 0x41) | Out-Null
Start-Sleep -Milliseconds 500
$cr = New-Object WP+RECT; [WP]::GetClientRect($h, [ref]$cr) | Out-Null
$o = New-Object WP+POINT; [WP]::ClientToScreen($h, [ref]$o) | Out-Null
Write-Output ("PLACED pid {0} client {1}x{2} at {3},{4}" -f $p.Id, $cr.Right, $cr.Bottom, $o.X, $o.Y)
