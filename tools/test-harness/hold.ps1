# Hold a key down in the Starsector window for N seconds (Shift = fast-forward). The key is let go
# at once, and "FOREGROUND LOST" printed, if another window takes the foreground for a second -
# someone is using the machine, and a held Shift would land in whatever they type.
param([string]$Key = "shift", [double]$Seconds = 10)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class KH {
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@ -ErrorAction SilentlyContinue
$p = Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" } | Select-Object -First 1
if (-not $p) { Write-Output "NOWINDOW"; exit 0 }
[KH]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 200
$vk = switch ($Key) { "shift" { 0x10 } "space" { 0x20 } "escape" { 0x1B } default { 0x10 } }
[KH]::keybd_event([byte]$vk, 0, 0, [UIntPtr]::Zero)
$t0 = Get-Date; $away = 0; $lost = $false
while (((Get-Date) - $t0).TotalSeconds -lt $Seconds) {
  Start-Sleep -Milliseconds 500
  if ([KH]::GetForegroundWindow() -ne $p.MainWindowHandle) { $away++ } else { $away = 0 }
  if ($away -ge 2) { $lost = $true; break }
}
[KH]::keybd_event([byte]$vk, 0, 2, [UIntPtr]::Zero)
if ($lost) { Write-Output ("FOREGROUND LOST after {0:0}s of {1}s" -f ((Get-Date) - $t0).TotalSeconds, $Seconds) }
else { Write-Output ("HELD {0} for {1}s" -f $Key, $Seconds) }
