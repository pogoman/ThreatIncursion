# Ask Starsector for its own screenshot (GL framebuffer -> starsector-core\screenshots)
# by posting Print Screen straight to the game window, which bypasses the Windows 11
# Snipping Tool global hotkey. Copies the new PNG to -Out (downscaled by -Scale).
param([string]$Out = "", [double]$Scale = 0.5)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class PK { [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l); }
"@ -ErrorAction SilentlyContinue
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core"
$shots = "C:\Program Files (x86)\Fractal Softworks\Starsector\screenshots"
$before = @(Get-ChildItem $shots -Filter *.png -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
$p = Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" } | Select-Object -First 1
if (-not $p) { Write-Output "NOWINDOW"; exit 0 }
$h = $p.MainWindowHandle
$vk = [IntPtr]0x2C                       # VK_SNAPSHOT
$down = [IntPtr](([long]0x37 -shl 16) -bor (1 -shl 24) -bor 1)
$up = [IntPtr](([long]0x37 -shl 16) -bor (1 -shl 24) -bor 1 -bor 0xC0000000)
[PK]::PostMessage($h, 0x100, $vk, $down) | Out-Null
Start-Sleep -Milliseconds 80
[PK]::PostMessage($h, 0x101, $vk, $up) | Out-Null
$deadline = (Get-Date).AddSeconds(6)
do {
  Start-Sleep -Milliseconds 400
  $new = Get-ChildItem $shots -Filter *.png -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Name } | Select-Object -First 1
} while (-not $new -and (Get-Date) -lt $deadline)
if (-not $new) { Write-Output "NOSHOT"; exit 0 }
Start-Sleep -Milliseconds 400
if ($Out -ne "") {
  $bmp = [System.Drawing.Bitmap]::FromFile($new.FullName)
  $small = New-Object System.Drawing.Bitmap $bmp, ([int]($bmp.Width * $Scale)), ([int]($bmp.Height * $Scale))
  $small.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $small.Dispose(); $bmp.Dispose()
  Write-Output ("GAMESHOT {0} -> {1}" -f $new.Name, $Out)
} else { Write-Output ("GAMESHOT {0}" -f $new.FullName) }
