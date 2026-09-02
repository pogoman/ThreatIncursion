# Drive the Starsector window: locate it, capture it, click and type into it.
# Usage: ui.ps1 -Action shot -Out path [-Scale 0.5] [-Crop x,y,w,h]
#        ui.ps1 -Action click -X 100 -Y 200      (window-relative client coords)
#        ui.ps1 -Action key -Text "e"            (SendKeys syntax)
#        ui.ps1 -Action rect
param(
  [string]$Action = "rect",
  [string]$Out = "",
  [double]$Scale = 1.0,
  [int[]]$Crop = @(),
  [int]$X = 0,
  [int]$Y = 0,
  [string]$Text = ""
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  public static IntPtr Find(string needle) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new System.Text.StringBuilder(256); GetWindowText(h, sb, 256);
      if (sb.ToString().IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@ -ErrorAction SilentlyContinue

function Get-GameWindow {
  $h = [Win]::Find("Starsector")
  if ($h -eq [IntPtr]::Zero) { $h = [Win]::Find("Starfarer") }
  return $h
}

$h = Get-GameWindow
if ($h -eq [IntPtr]::Zero) { Write-Output "NOWINDOW"; exit 0 }
$cr = New-Object Win+RECT
[Win]::GetClientRect($h, [ref]$cr) | Out-Null
$origin = New-Object Win+POINT
[Win]::ClientToScreen($h, [ref]$origin) | Out-Null
$cw = $cr.Right - $cr.Left; $ch = $cr.Bottom - $cr.Top
$sb = New-Object System.Text.StringBuilder 256; [Win]::GetWindowText($h, $sb, 256) | Out-Null

switch ($Action) {
  "rect" { Write-Output ("WINDOW '{0}' client {1}x{2} at {3},{4}" -f $sb.ToString(), $cw, $ch, $origin.X, $origin.Y) }
  "shot" {
    $x = $origin.X; $y = $origin.Y; $w = $cw; $hh = $ch
    if ($Crop.Count -eq 4) { $x += $Crop[0]; $y += $Crop[1]; $w = $Crop[2]; $hh = $Crop[3] }
    $bmp = New-Object System.Drawing.Bitmap $w, $hh
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($x, $y, 0, 0, $bmp.Size)
    $g.Dispose()
    if ($Scale -ne 1.0) {
      $sw = [int]($w * $Scale); $sh = [int]($hh * $Scale)
      $small = New-Object System.Drawing.Bitmap $sw, $sh
      $g2 = [System.Drawing.Graphics]::FromImage($small)
      $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $g2.DrawImage($bmp, 0, 0, $sw, $sh); $g2.Dispose(); $bmp.Dispose(); $bmp = $small
    }
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    Write-Output ("SHOT {0} ({1}x{2} client, scale {3})" -f $Out, $cw, $ch, $Scale)
  }
  "click" {
    [Win]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 150
    $sx = $origin.X + $X; $sy = $origin.Y + $Y
    [Win]::SetCursorPos($sx, $sy) | Out-Null; Start-Sleep -Milliseconds 120
    [Win]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60
    [Win]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    Write-Output ("CLICK {0},{1} (screen {2},{3})" -f $X, $Y, $sx, $sy)
  }
  "pixel" {
    $bmp = New-Object System.Drawing.Bitmap 1, 1
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($origin.X + $X, $origin.Y + $Y, 0, 0, $bmp.Size); $g.Dispose()
    $p = $bmp.GetPixel(0, 0); $bmp.Dispose()
    Write-Output ("PIXEL {0} {1} {2}" -f $p.R, $p.G, $p.B)
  }
  "move" {
    [Win]::SetCursorPos($origin.X + $X, $origin.Y + $Y) | Out-Null
    Write-Output ("MOVE {0},{1}" -f $X, $Y)
  }
  "key" {
    [Win]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 150
    [System.Windows.Forms.SendKeys]::SendWait($Text)
    Write-Output ("KEY {0}" -f $Text)
  }
}
