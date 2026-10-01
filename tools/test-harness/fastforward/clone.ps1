# Clones save <Base><From> to <Base><To>, refills the player fleet's supplies and fuel, makes the clone
# self-contained (its saveDirName, so F5 writes to the clone - docs/testing-harness.md) and points
# Continue at it.
param([Parameter(Mandatory = $true)][string]$Base, [string]$From = "", [Parameter(Mandatory = $true)][string]$To,
  [double]$Supplies = 600000, [double]$Fuel = 60000)
$saves = "C:\Program Files (x86)\Fractal Softworks\Starsector\saves"
if ($Base -notlike "save_*" -or $To -eq "" -or $To -eq $From) { "Base must be a save folder name and To a new suffix"; exit 1 }
$src = "$saves\$Base$From"; $dst = "$saves\$Base$To"
if (-not (Test-Path $src)) { "missing: $src"; exit 1 }
if (Test-Path $dst) { "exists: $dst"; exit 1 }
Copy-Item $src $dst -Recurse
Get-ChildItem $dst -Filter *.bak | ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
$enc = New-Object System.Text.UTF8Encoding($false)
foreach ($n in "descriptor.xml", "campaign.xml") {
  $f = "$dst\$n"; $s = [System.IO.File]::ReadAllText($f)
  $s = $s.Replace("$Base$From", "$Base$To")
  if ($n -eq "campaign.xml") {
    $pf = [regex]::Match($s, '<playerFleet ref="(\d+)">').Groups[1].Value
    $at = $s.IndexOf("<CampaignFleet z=`"$pf`"")
    if ($at -lt 0) { $at = $s.IndexOf("z=`"$pf`"") }
    foreach ($kind in @(@("supplies", $Supplies), @("fuel", $Fuel))) {
      $m = [regex]::Match($s.Substring($at), '<CIStack z="\d+"[^>]*>\s*<d cl="st">' + $kind[0] + '</d>')
      if (-not $m.Success) { "no $($kind[0]) stack found"; continue }
      $tag = [regex]::Match($m.Value, '<CIStack[^>]*>').Value
      $new = [regex]::Replace($tag, ' s="[\d\.E]+"', ' s="' + ([double]$kind[1]).ToString('0.0', [System.Globalization.CultureInfo]::InvariantCulture) + '"')
      $idx = $at + $m.Index
      $s = $s.Substring(0, $idx) + $new + $s.Substring($idx + $tag.Length)
      "$($kind[0]): $tag -> $new"
    }
  }
  [System.IO.File]::WriteAllText($f, $s, $enc)
}
# the prefs escape each capital with a slash
$esc = -join ("$Base$To".ToCharArray() | ForEach-Object { if ([char]::IsUpper($_)) { "/$_" } else { "$_" } })
Set-ItemProperty 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer' -Name continue -Value "..\saves\$esc"
"continue -> " + (Get-ItemProperty 'HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer').continue
