# Hold a key down in the Starsector window for N seconds (Shift = fast-forward).
param([string]$Key = "shift", [double]$Seconds = 10)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class KH {
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
}
"@ -ErrorAction SilentlyContinue
$p = Get-Process -Name java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Starsector*" } | Select-Object -First 1
if (-not $p) { Write-Output "NOWINDOW"; exit 0 }
[KH]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 200
$vk = switch ($Key) { "shift" { 0x10 } "space" { 0x20 } "escape" { 0x1B } default { 0x10 } }
[KH]::keybd_event([byte]$vk, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Seconds $Seconds
[KH]::keybd_event([byte]$vk, 0, 2, [UIntPtr]::Zero)
Write-Output ("HELD {0} for {1}s" -f $Key, $Seconds)
