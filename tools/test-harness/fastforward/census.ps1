# Month-by-month table of the hive census in ti-<Tag>.txt (from extract.ps1); -Every N prints every
# Nth month and the last.
param([string]$Tag = "x", [int]$Every = 1)
$out = if ($env:THREATINC_TEST_OUT) { $env:THREATINC_TEST_OUT } else { Join-Path $env:TEMP "threatinc-tests" }
$L = [IO.File]::ReadAllLines("$out\ti-$Tag.txt")
$m = 0; $rows = @()
foreach ($l in $L) {
  if ($l -match '^Census: threat hives (\d+) \(size (\d+)\), found (\d+), fleets (\d+) FP, income (\d+) FP/mo.*banked (\d+) FP; fuel (\d+) \(\+(\d+)/mo, spent (\d+)\); supplies (\d+) \(\+(\d+)/mo') {
    $m++
    $rows += "m{0,-3} hives {1,-3} size {2,-4} fleets {3,-6} inc {4,-5} bank {5,-6} fuel {6,-7} +{7,-6} spent {8,-7} sup {9,-6} +{10}" -f $m, $matches[1], $matches[2], $matches[4], $matches[5], $matches[6], $matches[7], $matches[8], $matches[9], $matches[10], $matches[11]
  }
}
for ($i = 0; $i -lt $rows.Count; $i++) { if ($i % $Every -eq 0 -or $i -eq $rows.Count - 1) { $rows[$i] } }
