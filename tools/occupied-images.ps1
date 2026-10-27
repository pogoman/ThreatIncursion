# The occupied industry images (docs/occupation.md fixes 4-5): vanilla's producing-industry images
# at 2x, shaded dark green, with the swarm icon (graphics/threatinc/icons/threat_swarm.png) in a green
# highlight box top-right. Vanilla swaps some images by condition (farmland, ore, orbital), so every
# variant gets a copy; a name added here goes in ThreatOccupation.IMAGES too.
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\occupied-images.ps1
Add-Type -AssemblyName System.Drawing
$core = "C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core\graphics"
$mod = Join-Path $PSScriptRoot "..\graphics\threatinc"
New-Item -ItemType Directory -Force "$mod\industry\occupied" | Out-Null

function Resample($src, $w, $h) {
    $out = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($out)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $w, $h))
    $g.Dispose()
    return $out
}

$names = @("farming", "farming_low", "farming_med", "farming_high", "aquaculture",
    "mining", "mining_low", "gas_giant_mining", "techmining", "refining",
    "light_industry", "light_industry_low", "light_industry_high",
    "light_industry_orbital", "light_industry_orbital_low", "light_industry_orbital_high",
    "heavy_industry", "orbital_works", "fuel_production", "advanced_fuel_production")

$icon = [System.Drawing.Bitmap]::FromFile((Resolve-Path "$mod\icons\threat_swarm.png"))
$box = 52; $pad = 5; $margin = 8
$boxIcon = Resample $icon ($box - 2 * $pad) ($box - 2 * $pad)
$fill = [System.Drawing.Color]::FromArgb(190, 18, 58, 24)
$edge = [System.Drawing.Color]::FromArgb(255, 86, 190, 96)
foreach ($n in $names) {
    $bmp = [System.Drawing.Bitmap]::FromFile("$core\icons\industry\$n.png")
    $w = $bmp.Width * 2; $h = $bmp.Height * 2
    $big = Resample $bmp $w $h
    $out = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $h; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            $p = $big.GetPixel($x, $y)
            $gray = 0.3 * $p.R + 0.59 * $p.G + 0.11 * $p.B
            $r = [int][Math]::Min(255, $gray * 0.22)
            $gg = [int][Math]::Min(255, $gray * 0.55)
            $b = [int][Math]::Min(255, $gray * 0.24)
            $out.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($p.A, $r, $gg, $b))
        }
    }
    $g = [System.Drawing.Graphics]::FromImage($out)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $bx = $w - $margin - $box; $by = $margin
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $fill), $bx, $by, $box, $box)
    $g.DrawRectangle((New-Object System.Drawing.Pen $edge, 2), $bx + 1, $by + 1, $box - 2, $box - 2)
    $g.DrawImage($boxIcon, $bx + $pad, $by + $pad, $box - 2 * $pad, $box - 2 * $pad)
    $g.Dispose()
    $out.Save((Join-Path "$mod\industry\occupied" "$n.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose(); $big.Dispose(); $out.Dispose()
    "$n.png ${w}x${h}"
}
$icon.Dispose(); $boxIcon.Dispose()
