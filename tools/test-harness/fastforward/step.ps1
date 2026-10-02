# One step of driving the game by hand (the new-game screens): optional click (-X -Y), key (-Key,
# SendKeys syntax) or text (-Text), then a wait and a half-scale shot to step-<Shot>.png in the
# output dir ($env:THREATINC_TEST_OUT, default %TEMP%\threatinc-tests). Prints the window rect.
# -Game takes the shot with gameshot.ps1 (the game's own Print Screen, which also leaves a PNG in
# Starsector\screenshots): for displays where a screen grab of the window comes back white.
param([int]$X = -1, [int]$Y = -1, [string]$Key = "", [string]$Text = "", [double]$Wait = 2, [string]$Shot = "s", [double]$Scale = 0.5, [switch]$Game)
$h = Split-Path $PSScriptRoot -Parent
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
New-Item -ItemType Directory -Force $out | Out-Null
function UI { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\ui.ps1" @args }
powershell -NoProfile -ExecutionPolicy Bypass -File "$h\place.ps1" -X 0 -Y 0 | Out-Null
if ($X -ge 0 -and $Y -ge 0) { UI -Action click -X $X -Y $Y | Out-Null }
if ($Key -ne "") { UI -Action key -Text $Key | Out-Null }
if ($Text -ne "") { foreach ($c in $Text.ToCharArray()) { UI -Action key -Text ([string]$c) | Out-Null } }
Start-Sleep -Milliseconds ([int]($Wait * 1000))
if ($Game) { powershell -NoProfile -ExecutionPolicy Bypass -File "$h\gameshot.ps1" -Out "$out\step-$Shot.png" -Scale $Scale }
else { UI -Action shot -Out "$out\step-$Shot.png" -Scale $Scale | Out-Null }
UI -Action rect
