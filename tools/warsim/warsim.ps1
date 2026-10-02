# Runs the offline war simulator: warsim.ps1 run|batch|compare|check ... (docs/war-sim.md 7a)
$java = "java"
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
& $java -cp (Join-Path $PSScriptRoot "out") warsim.Main @args
exit $LASTEXITCODE
