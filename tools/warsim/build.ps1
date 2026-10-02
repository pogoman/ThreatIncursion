# Builds the offline war simulator into tools/warsim/out (docs/war-sim.md). Needs a JDK on PATH
# or $env:JAVA_HOME. Compiles the mod's pure rules (src/threatinc/rules) with it; no game jars.
$ErrorActionPreference = "Stop"
$javac = "javac"
if ($env:JAVA_HOME) { $javac = Join-Path $env:JAVA_HOME "bin\javac.exe" }
$root = Split-Path (Split-Path $PSScriptRoot)
$out = Join-Path $PSScriptRoot "out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$sources = @(Get-ChildItem -Recurse (Join-Path $PSScriptRoot "src") -Filter *.java | ForEach-Object FullName)
$rules = Join-Path $root "src\threatinc\rules"
if (Test-Path $rules) { $sources += @(Get-ChildItem -Recurse $rules -Filter *.java | ForEach-Object FullName) }
$argfile = Join-Path $out "sources.txt"
$sources | ForEach-Object { '"' + $_.Replace([char]92, [char]47) + '"' } | Set-Content -Encoding ascii $argfile
& $javac -encoding UTF-8 -Xlint:-options -d $out "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Write-Host "Built tools\warsim\out ($($sources.Count) sources)"
