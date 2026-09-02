# Automated in-game check

Two PowerShell scripts in `tools/test-harness/` launch Starsector, reach The Threat War Effort
board and screenshot it, so a build can be verified without a person clicking through. A full
cycle takes about 50 seconds at 3440x1440.

## Scripts

- `ui.ps1` - drives the game window (found by its title containing "Starsector") through
  user32 and System.Drawing:
  - `-Action rect` prints the client size and screen origin.
  - `-Action shot -Out file.png [-Scale 0.5] [-Crop x,y,w,h]` captures the client area (or a
    window-relative crop), optionally downscaled. Keep captures cropped and at 0.5-0.7 scale
    to limit token cost when an AI reads them.
  - `-Action click -X -Y`, `-Action move -X -Y` (window-relative client coordinates),
    `-Action key -Text "e"` (SendKeys syntax), `-Action pixel -X -Y` (RGB at a point).
- `cycle.ps1` - kill any running game, run `starsector-core\starsector.bat`, click Play in the
  launcher, wait for the window to reach game resolution, poll the Continue button's pixel until
  the menu is lit, click Continue, poll `starsector.log` for a new "Loading stage 39 - last" line
  (save loaded), press E, click the Major events tab, click the board entry, park the mouse off
  the table, capture. Parameters: `-Tag`, `-Scale`, `-OutDir`, `-ContinueX/Y`, `-TabX/Y`,
  `-EntryX/Y`, `-ParkX/Y`, `-StopAtMenu`, `-StopAtIntel`.

## Coordinates (client pixels, windowed)

| Target | 3440x1440 | 1920x1080 |
| --- | --- | --- |
| Launcher "Play Starsector" (launcher is 805x503 at any resolution) | 402,343 | 402,343 |
| Main menu Continue | 2250,492 | 1486,314 |
| Intel: Major events tab | 1357,1155 | 734,824 |
| The Threat War Effort entry in that list | 800,692 | 200,672 |
| Mouse park (off the table) | 3300,1400 | 1700,1000 |

The tab strip re-flows when tab labels change (e.g. the Threat Incursion count), and the entry's
position depends on what else is in Major events for the loaded save. Re-find both with a
`-StopAtIntel` run and a cropped capture whenever the click lands wrong: a capture of the sector
map instead of the board means the entry click missed.

## Which save loads

Continue loads the save named in the Java preferences key `continue` under
`HKCU:\Software\JavaSoft\Prefs\com\fs\starfarer` (values are `/`-escaped paths). `resolution`
and `fullscreen` live there too; set `resolution` to `3440x1440` or `1920x1080` before launching
rather than through the launcher. Captures need windowed mode (exclusive fullscreen gives black
frames). Another session playing a different save changes what Continue loads - check the key.

## Reading results

- The board logs `war board render width=... narrow=... stack=...` via `ThreatIncConfig.log`
  (debug logging on) when it draws; absence after a cycle means the entry was not opened.
- `starsector.log` is appended across launches; take the last matching line. A fatal UI error
  (e.g. "May only anchor on siblings") leaves a small dialog titled "Starsector 0.98a-RC8" -
  `ui.ps1 -Action rect` then reports a ~221x114 client, which is how to detect a crash.
- The jar is locked while the game runs: kill the game (cycle does) before `compile.ps1`.

## Manual equivalent

Open the game, Continue, press E, Major events, click The Threat War Effort. UI scaling means
the board gets 1195 logical px at 3440x1440 (the full layout) and 997 at 1080p (the narrow
fold), so check both when changing column budgets.

## Forcing a game state: clone the save and edit its XML

To test a rule that needs a state the current save lacks (a disrupted hive port, say), do not wait
for it to happen in-game. `saves/<save>/campaign.xml` is plain XStream XML (~13 MB, one element
per line). Copy the save folder under a new name, edit the copy, point the Java prefs `continue`
key at it (append the suffix to the escaped path), run `cycle.ps1`, then restore the key and delete
the clone. Done Sept 2026 to prove `applyPortDisruption`: the log showed
"Port disrupted at Jannow: accessibility 63% -> -15%, shipping 3 units" on the first poll.

Industry disruption lives in market memory, not on the industry: `$core_disrupted_<IndustryClass>`
(`Spaceport`, `Megaport`, `SwarmNexus`, `FabricationCore`...) as `<e><st>key</st><bp>true</bp></e>`
in the memory map plus `<MExp k="key" t="days"></MExp>` in the expiry list next to it. Mirror an
existing entry of the same market; leave the `z` id off new elements (nothing references them).
Find a market by its `<name>` line and confirm anchors by content before editing - line numbers
shift between saves.

The poll that applies hive accessibility runs only while the clock runs: after the board capture,
`{ESC}` closes intel, a space unpauses; 15-25 s at 1x covers several days and one 30-day tick.
