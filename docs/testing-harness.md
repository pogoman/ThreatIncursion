# Automated in-game check

Two PowerShell scripts in `tools/test-harness/` launch Starsector, reach The Abyssal War
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

## Laptop panel (1920x1080, hybrid GPU) - added 2026-09-04

On the laptop's own panel two things break the recipe above: **screen grabs return a
stale frame** of the game window (CopyFromScreen and Win+PrintScreen alike show the
loading bar long after the menu is up), and the launcher renders at **597x373** instead
of 805x503, so Play is at (298,254). Four extra scripts cover it:

- `gameshot.ps1 -Out file.png [-Scale 0.5]` - the only reliable capture there: posts
  `VK_SNAPSHOT` straight to the game window (`PostMessage`, which bypasses the Windows 11
  Snipping Tool hotkey) so the game writes its own framebuffer to
  `Starsector\screenshots\` (one level ABOVE starsector-core), then copies the newest file
  downscaled. `NOSHOT` means the key was not seen - the game window must have focus.
- `lap-cycle.ps1 [-Tag x] [-Faction hegemony|player] [-NoCapture]` - the whole cycle for
  that panel: kill, launch, Play at the small launcher, wait for the menu by the
  "Reading save data" log line, click Continue at (1392,372) and retry until
  "Loading stage 39 - last" appears, press E (the war board stays selected across
  launches so E opens it directly), optionally click a selector button (Hegemony at
  (730,194), Your faction at (862,194)), then `gameshot`.
- `hold.ps1 -Key shift -Seconds N` - holds Shift (campaign fast-forward while unpaused):
  after the board capture, `{ESC}`, space, hold, space, then E again. About 0.6-0.7
  campaign days per real second.
- `place.ps1 -X -Y` - forces the game window on-screen (useful when a window is off
  a disconnected monitor).

Things learned the hard way: with the external monitor powered off the vanilla launcher
crashes on Play (array index error reading the display list) and nothing paints - keep
the display on. LunaLib keeps STORED values in
`saves\common\LunaSettings\threatinc.json.data`; changed defaults in LunaSettings.csv do
not apply until edited there. Injected persistent-data objects use `_-` for `$` in the
element name (`threatinc.ThreatGroundFronts_-GroundFront`); a `lastCounterAttack` of 0
means the front is counter-attacked on the first poll.

## Coordinates (client pixels, windowed)

| Target | 3440x1440 | 1920x1080 |
| --- | --- | --- |
| Launcher "Play Starsector" (launcher is 805x503 at any resolution) | 402,343 | 402,343 |
| Main menu Continue | 2250,492 | 1486,314 |
| Intel: Major events tab | 1357,1155 | 734,824 |
| The Abyssal War entry in that list | 800,692 | 200,672 |
| Mouse park (off the table) | 3300,1400 | 1700,1000 |

The tab strip re-flows when tab labels change (e.g. the Abyssal War count), and the entry's
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
- Posture (2026-09-29, docs/hive-garrison-and-upkeep.md "Posture", debug logging on): grep `Posture:` for a
  system's mode change (`Posture: <system> A->B pressure ...`), a fleet passed to a pressed sibling
  (`Posture: <donor> sent N FP to ...`) and a surplus fleet recycled (`Posture: <colony> recycled a
  ...`); grep `Posture sector:` for the monthly totals (held, want, surplus, quiet/pressed
  systems, appetite, claims, sent, consumed, recycled).

## Manual equivalent

Open the game, Continue, press E, Major events, click The Abyssal War. UI scaling means
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

## External monitor again, and saving from a clone (2026-09-05)

With the 3440x1440 monitor back as primary (laptop panel secondary) the game still ran at
the 1920x1080 pref, windowed, on the primary. `desk-cycle.ps1` is the whole recipe below
in one script (launch, Continue with retries, optional unpaused seconds, intel, a
selector click, a hovered row, captures, log lines). Four things bit:

- **Clicks need the window in front.** `SetForegroundWindow` from a background script fails
  silently, so the launcher's Play click and the menu's Continue click landed on whatever
  window covered the game. `place.ps1 -X 300 -Y 300` (topmost) before the first click fixes
  it; do it again once the game window replaces the launcher. With the window topmost the
  plain `ui.ps1 -Action shot` screen grab works on this monitor; `gameshot.ps1` works too
  and needs no focus tricks beyond that.
- **Continue at 1920x1080 is (1486,314)**, as the older note said; the laptop-session value
  (1392,372) is wrong here. The menu shows "Preloading..." for a while - click and retry
  until "Loading stage 39 - last" appears.
- **Hover needs real motion events.** `SetCursorPos` alone shows no row tooltip; after
  placing the cursor, send a few relative `mouse_event(MOUSEEVENTF_MOVE, dx, dy)` nudges
  and wait ~1.5 s. Same for parking the cursor off the table.
- **Pause state.** The user's gameplay prefs have `pauseAfterMap: true`, so the campaign is
  ALWAYS paused after leaving intel; one space unpauses, the next pauses. Do not toggle
  blindly - a stray space leaves the game paused through a Shift hold and nothing advances.
  Verify with the campaign screen's own date (a `gameshot` of the map shows the date and a
  "Game paused" label), not the log. ~50 s of `hold.ps1 -Key shift` covered 33 days.

**Saving from a clone writes to the ORIGINAL folder.** The save carries its own folder
name (`<saveDirName>save_SaturnHadean_...</saveDirName>` in campaign.xml); a copied folder
with a suffix loads fine but F5/autosave writes back into the folder named inside it, and
Starsector first renames the original's campaign.xml/descriptor.xml to `.bak`. Recovered
2026-09-05 by moving the `.bak` pair back and refilling the backup slot from the clone's
copies. When cloning, edit that one `saveDirName` line to the clone folder's name and the
clone becomes self-contained (done for `...182493833221313174zz`).

## Instant war (debug setting, 2026-09-05)

`Debug & Testing > Instant War` (`threatinc_debugInstantWar`, `ThreatDebugWar`) stands the
whole war up on a fresh save so the balance can be watched from day one instead of year
three. Untested in-game at the time of writing. It fires once per toggle-on (latched in
persistent data like RESET; switch it off again afterward or every new game gets one), on
the first incursion poll after the incursion starts - the switch is itself a start trigger,
so about half a day into a new game:

1. **Placement.** Picks `SystemsMin..SystemsMax` (5-10) uninhabited systems by greedy
   growth: a home able to host the full chain, chosen on the fringe but within
   `CoreLY + hops x LinkLY` of the core so the quota is reachable; then, step by step, a
   candidate within `LinkLY` (20) of the network - heading for the core until
   `CoreMin..CoreMax` (2-4) systems sit within `CoreLY` (15) of a size 6+ world, then
   stretching outward at random, staying out of the core zone once the ceiling is met.
   Twelve homes are tried; the plan meeting the most of its targets wins. "Core" is
   `ThreatWarBoard.distanceToCore` (nearest size 6+ non-player colony). Systems a live
   Remnant Nexus defends are skipped when `remnantResists` is on. Hive systems already
   founded count toward the totals; bare seeds (the normal start's home included) are
   dropped and re-placed.
2. **Founding.** `ThreatColonyManager.foundColony` at `HomeSize` (6) on every
   `pickChainPlanets` world of the home, `ColonySize` (4) on `pickColonyPlanet` elsewhere,
   with the planetfall bookkeeping (`STAGE_COLONY`, colony list, growth time, discovered).
   `planHiveEconomy` is then run round-robin with economy recomputes until nothing changes,
   so the chain (mining, refining, forge, fuel, defenses) stands at once, and
   `fillGarrisonNow` fabricates each colony's full nominal garrison.
3. **Mobilisation.** `ThreatWarState.mobilise` for every faction owning a non-hidden
   colony (pirates and Pathers included; the player excepted - the board's Mobilise button
   stays theirs). With `warModeStandDownDays` at 0 nobody stands down again.

One campaign message reports colonies, systems, near-core count, swarms and factions;
`starsector.log` (`[ThreatInc] Instant war:`) lists each system with its core distance,
always, without Verbose Logging. Phase is capability, so with the defaults expect phase 3
as soon as the home forge's hull supply reads stable, and first strikes on the first tick.

To verify: new game with the switch on, wait a day, open the war board - expect 5-10 hive
rows, 2-4 with a core distance under 15 LY, every faction on the selector, garrisons in
orbit in any hive system, and strikes mustering within the first month.

## New laptop, 125% display scaling (2026-09-29)

The game is DPI-aware; a script that is not gets virtualised coordinates (the launcher read
477x298 instead of 597x373) and its grabs and clicks land off target. `ui.ps1` and `place.ps1`
now call `SetProcessDPIAware`, so coordinates are physical pixels again. Run them in a fresh
process (`powershell -NoProfile -File ...`): a session that already loaded the old `Add-Type`
class keeps it. At the `1600x900` pref: launcher Play (298,254), main menu Continue (1190,282),
board selector The Threat (446,167), Hegemony (713,167). The locale uses a decimal comma, and
`-Crop` does not parse through `-File`; capture whole and scale instead. Debug logging was off in
this machine's LunaLib store, so no `[ThreatInc]` lines were written; read the state from a
quicksaved clone instead (`threatinc_frontlines` lists every link, its faction, hive and guards).

**The same laptop docked (state read 2026-10-02, nothing launched).** One display, the 3440x1440
monitor at 100%; game pref `resolution` 3440x1440. `fastforward\launch.ps1` and `run.ps1` are
written for the 1600x900 pref (Play 298,254, Continue 1190,282, the "client 1600x900" check) and
do not fit this state as they stand; `cycle.ps1`'s defaults are the 3440x1440 set. ShiftSpeed is
at 6 here: `%TEMP%\ng\on29\long-N1.txt` logged 90 game days for 150 s of held Shift, 0.6 days a
second, so 5 minutes of hold is about 6 months. No test save here (`ng9`, `ng10` are on the first
machine): the saves are the user's own campaigns (two `StarLord`, 37 hives, Hegemony mobilised,
saved 2026-09-29, older than fog, the attack planner and the council; six older `SaturnHadean`
with a mod no longer enabled). A check here means `clone.ps1` on a `StarLord` save, never F5, and
restoring the prefs `continue` and `resolution` and the store's `debugLogging` /
`debugPlayerIgnored` afterwards. The LunaLib marker here is 7 and the store holds no feed-share
keys, so the marker-12 bump is a no-op on this machine (the CSV default applies).

**The docked laptop, first runs (2026-10-02 evening).** The fast-forward scripts work docked once
the `resolution` pref is `1600x900` (restore 3440x1440 after). What differs from the first machine:

- The launcher is 805x503 at 100% scaling; `launch.ps1` now clicks Play at its share of whatever
  size the launcher has and retries while the launcher is still up.
- The main menu sits elsewhere: Continue (1290,256), New Game (1298,387) - pass
  `launch.ps1 -ContinueX 1290 -ContinueY 256`. The new-game screens too: Generate (1106,237)
  when the name field is empty (run hw4: Continue does nothing without a name), character Continue
  (330,645), the mercenary start (700,744), Normal (350,645), Skip it (340,678), then a skill
  screen with Start game (344,382). Generation takes under a minute.
- A screen grab of the game window is white (`ui.ps1 -Action shot`, and so `run.ps1`'s per-chunk
  shots); `step.ps1 -Game` shoots with `gameshot.ps1` instead (one PNG a shot lands in
  `Starsector\screenshots`).
- The first launch moved the LunaLib marker 7 -> 12 ("LunaLib settings moved" logged) and LunaLib
  added the missing keys: all 370 live keys then equal the CSV defaults (about a hundred retired
  keys linger in the store, unread).
- For a long run: `shiftspeed_mult` 48 in `saves\common\LunaSettings\shiftspeed.json.data` (the
  user plays at 6 here), vanilla autosave off (`"autosave/On"` in the prefs value
  `gameplay/Settings`; a machine that never changed its gameplay settings has no such value and is left without -
  an empty one stops the launcher with `Error loading settings`), `debugLogging`, `debugSimDump` and Fleets Ignore You on. The originals are
  in `%TEMP%\threatinc-tests\backup-20261002`, with a `restore.ps1` that puts them back.
- `tail-ti.ps1 -Tag t` (started before `launch.ps1`, detached) follows `starsector.log` and
  appends the `[ThreatInc]` lines to `ti-<Tag>.txt` and exceptions to `exc-<Tag>.txt` as they are
  written, across rollovers - no walk back through `.log.N` afterwards. `runto.ps1 -Days 3300
  -Tag t` (detached; tool background tasks die at 10 minutes here) runs rounds of `run.ps1` until
  the `Clock:` lines show that many game days, appends to `run-<Tag>.txt` and leaves
  `run-<Tag>.done` with the reason it ended. `guard-digest.ps1 -Tag t` counts the guard calls and
  the siege postponements by make.
- Pristine new game: `save_TerrellRamsey_2380916986271647281` (2026-10-02, the round-20 build,
  mercenary, Normal); `...ng1` is its first clone, run as `tr1a`.

**The harness yields the machine (2026-10-02).** The first long run died an hour in: the user
connected by Chrome Remote Desktop and joined a call, and the game process was gone ten seconds
later (the log ends on "Error initializing music source - AL error 40964", no crash report) - a
topmost game window that keeps taking the foreground is in the way of anyone at the laptop. Since
then `hold.ps1` lets the key go and prints `FOREGROUND LOST` when another window holds the
foreground for a second and `run.ps1` stops on it. `runto.ps1` then waits: a toast or a chat popup
took the foreground nine minutes into run `tr1b` with nobody at the laptop, so it carries on once
the game has the foreground back or nobody has touched keyboard or mouse for `-IdleSeconds` (90),
and stops with `STOPPED: FOREGROUND LOST`, the game window sent behind the others and left running
at normal speed, only after `-WaitMinutes` (20) of someone at work. Start `runto.ps1` again with
the same `-Tag` when the machine is free (the day count is from the tag's first `Clock:` line).
Whether a remote session is on: the last `chromoting` event in the Application log (id 1 connect,
2 disconnect). Do not start a run while the user is at the laptop or on a call - ask first.
`run-settings.ps1 -Save <save>` sets everything a long run needs on this laptop (1600x900,
autosave off, Shift 48x, the three debug switches, Continue at the save); the user's own values
are put back by `%TEMP%\threatinc-tests\backup-20261002\restore.ps1`.

**A locked laptop takes no input (2026-10-02 late).** Left idle for about 45 minutes after a run, the laptop locks
(`LogonUI.exe` running, `Screen.AllScreens` empty): the launcher comes up but the Play click never lands, and
`launch.ps1` waits out its deadlines. Check `Get-Process LogonUI` before launching. Run hw4d was started by a
detached watcher (`%TEMP%\threatinc-tests\hw4d-go.ps1`) that waits for the lock to clear and two idle minutes,
then launches, runs `runto.ps1`, wraps up and runs `restore.ps1` - a pattern for a run the user starts from afar.

## Fast-forward runs and a new game (2026-10-01)

`tools/test-harness/fastforward/` holds the scripts every balance run since 2026-09-29 used (they
lived in a session scratchpad, and the first new-game recipe was lost with one). Outputs go to
`$env:THREATINC_TEST_OUT`, default `%TEMP%\threatinc-tests`. At the `1600x900` pref:

- `clone.ps1 -Base save_X_123 -From lt -To h40`: copy a save, refill the player fleet's supplies
  and fuel (it runs dry and its CR falls over a long run), make the copy self-contained
  (`saveDirName`) and point Continue at it.
- `launch.ps1 [-MenuOnly]`: kill, launch, Play, wait for the menu, Continue until "Loading stage 39"
  appears. The game rolls `starsector.log` over at ~50 MB, more than once in a 100-month run; the waits survive it
  and `extract.ps1` walks back `.log.1`, `.log.2`, ... to the load (since 2026-10-02; before, only `.1`).
- `run.ps1 -Chunks 3 -Seconds 110 -SaveEvery 3 -Tag h40a`: hold Shift (64x with SpeedUp) in chunks,
  report ThreatInc lines per chunk, quicksave. Three chunks are ~12-15 months of a developed save
  and ~2 years of a new one. A chunk that writes nothing is a stopped clock (a dialog): one
  Enter/Esc is tried, then the run stops with a shot to look at.
- `extract.ps1 -Tag h40a`: the `[ThreatInc]` lines since the load, to `ti-h40a.txt`;
  `census.ps1 -Tag h40a -Every 4` tabulates the hive's monthly census.
- `simdump.ps1 -Save save_X_123ng9 -Name mid-war`: a start state for the offline simulator from any save
  (`war-sim.md` 12): loads it with `threatinc_debugSimDump` on, waits for the dump, kills the game unsaved,
  fills `tools/warsim/start/<Name>`, restores the knob and the Continue pref. About a minute.

**A new game**, as run for ng1-ng4 (a mercenary start, Normal, tutorial skipped):
`launch.ps1 -MenuOnly`, then `step.ps1` a click at a time, reading each shot: New Game (1194,396);
Name GENERATE (1078,263); 1. Continue (390,620); 4. mercenary (800,707); 1. Normal (405,620);
2. Skip it (400,649); Start game (402,392), then ~90 s of generation. F5 once, quit, and
`newgame-prep.ps1 -Base save_<Name>_<id> -To ng1` (a clone with supplies refilled, pirates and Pathers
neutral to the player). The Threat is pinned hostile to everyone (`enforceThreatHostility`), so for
an unattended run turn on **Debug & Testing > Fleets Ignore You** (`threatinc_debugPlayerIgnored`;
`player-ignored.ps1 on`): a strike caught the test fleet in Corvus and held ng3a's clock. The LunaLib
store is global - `player-ignored.ps1 off` afterwards. Keep the pristine save and clone a fresh `ngN`
for each build, so runs compare from the same sector.

Pristine new games: `save_AresDaniels_5500560879992783035` (ng1-ng7, generated before the swarm's
fog) and `save_PoseidonDeimos_8594169485512677365` (2026-10-01, generated by the fog build; ng1 a
probe; the long fog-on run ng2a and its fog-off twin ng3a are called pd2a and pd3a in the docs, since ng2a and ng3a already name AresDaniels runs). `luna-set.ps1 -Key <knob> -Value <v>` sets
any knob in the LunaLib store with the game closed (`threatinc_swarmFogOfWar false` for a fog-off
twin); set it back afterwards, the store is global.
**New-seed sector, 2026-10-09 (the user: "a brand new test with new seed from game start"):** `save_OceanPena_1024894203100914406`, generated 17:13 on the belt-guard build (17ae3681) by the recipe above, laptop panel only at 125% - every ng1-ng4 coordinate held, no `-Game` needed, the map up 20 s after Start game. Run from day 0 with `sbs.ps1 -Tags hw132a,hw132b,hw132c -Base save_OceanPena_1024894203100914406 -Days 4700` (`-Base` gives each game `newgame-prep.ps1`; `-Bases` would clone as is).

## Laptop panel only, game pref 2560x1440 (2026-09-23)

With only the 1920x1080 panel connected, set the `resolution` pref to `1920x1080` for the run
(restore it after). The launcher is 597x373 (Play 298,254) and must be `place.ps1`'d first or the
click misses. Main menu Continue is at **(1350,372)** there, not (1392,372). Screen grabs of the
game work, but `gameshot.ps1` is what was used. Clicking a planet from ~200 su docks in a few
seconds; the clock runs meanwhile (two days passed on one approach).


## Side-by-side runs (2026-10-04)

`fastforward\sbs.ps1 -Tags hw6a,hw6b,hw6c -Days 3750` runs several new games at once and fast-forwards them
together. **Check a batch mid-run (the user, 2026-10-05):** the monthly dumps are readable while the games
play (`saves_sbs<tag>savesmmon	hreatinc_simdump_*`; copy them to a scratch `<tag>` folder and run
`organs.pl <tag>` there). Sieges arrive around m45-m47, so a look at m55-m60, 20 minutes in, shows whether a
change took; run to m125 only when the question is the end state (extinction, runaway). Trial `sa`/`sb`: two games ran 210 and 212 days a minute each, against 215 for one game alone, so the
batch time is one run's (about 40 minutes) whatever the count, up to what memory holds (2.8 GB a game).
**An old save instead of the new game:** `-Bases "sat=save_SaturnHadean_8807243588242812142;sl=save_StarLord_1669224817518795825"`
clones that save for the tag as it is (`clone.ps1`, no relation edit) - the upgrade test of 2026-10-06. Enable every mod the
save's `descriptor.xml` lists first (the missing-mod prompt would block the Continue click). `savefields.pl campaign.xml` lists
the mod classes and fields a save holds, for the static check against `javap -p` of the jar (facts: "Will a 0.6 / 0.7 save load").
Batch hw6a-hw6c (2026-10-04): three games ran 123-142 days a minute each to war day 2,340-2,680, about the same
days a minute in total as two, so a third game buys a replicate, not time. The wrap-up works (dumps copied,
settings restored), and closing the games by hand ends a batch cleanly ("GAME GONE"). Untested so far: the
stall nudges and the pause for a user.

- **Each game has its own folders**, `saves\_sbs\<tag>\saves` and `...\logs`, given on its command line
  (`-Dcom.fs.starfarer.settings.paths.saves` / `.logs`; `start.bat` there is `starsector.bat`'s java line with
  the two paths changed). `saves` is the one place under the game a script may create a folder; the Starsector
  folder itself refuses. Its `saves\common` is a copy taken after `run-settings.ps1`, so each game has its own
  LunaLib store (`-Knobs "tag:k=v;k=v|tag2:k=v"` edits one game's only, nothing to put back) and its own
  `threatinc_sim*` dumps. `tail-ti.ps1 -Log` follows its own `starsector.log` into `ti-<tag>.txt`.
- **The launcher prefs are shared**, so the games are launched in turn, about 70 seconds each: `continue` is
  set to `..\saves\_sbs\<tag>\saves\<clone>` (the game takes a path into another saves folder), the window is
  found by its process (`ui.ps1 -Hwnd`, `place.ps1 -Hwnd`), Play and Continue are clicked, and a Space POSTED
  to the window starts the clock. From then on no window needs the foreground.
- **Fast-forward needs Shift held system-wide.** A Shift posted to the window alone does nothing (trial
  `sbs1`: 7 days a minute, the same as no key): LWJGL's `WindowsKeyboard.poll` lets go of Left Shift whenever
  `GetAsyncKeyState` says the key is not physically down. That test is system-wide and not tied to the
  foreground, so the script holds Left Shift with `keybd_event` and posts the key-down to every game window four
  times a second. While it is held, typing on the machine is shifted: the script lets go the moment keyboard or
  mouse is touched (`GetLastInputInfo`) and takes it up again after `-IdleSeconds` 60 of quiet; the games run on
  at normal speed meanwhile.
- It never saves a game, kills each game at its day, copies the dumps to `tools\warsim\validation\<tag>`,
  deletes the clone and restores the user's settings. Status in `%TEMP%\threatinc-tests\sbs-status.txt`.
  Never `tail -f`/`-F` it (or hold it open): Windows locks the file, every status line of the batch fails
  with an IOException, and Git Bash's `tail` outlives a stopped Monitor (hw48, 2026-10-07). Read it with
  `cat` when needed, or watch the game logs `ti-<tag>.txt` instead.
- **On a machine that has not run it** (2026-10-05): the pristine save is in the repo
  (`tools\test-harness\saves\save_AmaruDugas_2921423183749615243`, to copy into `Starsector\saves\`), and
  `fastforward\backup-settings.ps1` is run once before the first batch - it writes the settings backup and
  the `restore.ps1` that the wrap-up runs (`%TEMP%\threatinc-tests\backup-20261002`). The whole checklist:
  `handover-2026-10-05-testing.md` 2.
- `launch.ps1` leaves a console at `pause` for every game it has started (42 had piled up by 2026-10-04);
  `sbs.ps1` starts the java line alone and leaves none.

## Watch a batch from its first minutes (the user, 2026-10-07)

"Why do you let these runs go for so long when they probably reach evident fail conditions within 5
minutes of starting?" - hw52 ran 25 minutes past an exception visible at minute 19. Launch the batch
and in the same turn start `tools/test-harness/fastforward/watch.sh <hwNN> [maxMinutes]` in the
background (Git Bash): it polls every 3 minutes, prints one progress line a game (war day, launches,
held prongs sailed / refused, exceptions bar vanilla's loading noise, hull convoys sailed / landed)
and exits on the first fail signal so the background task notifies at once: a new exception (with its
first threatinc frames), a held prong refused, and - the user, 2026-10-07: "watcher should notify if
the game balance has a problem too, like obvious one" - the swarm wiped or under half its peak, swarms
lost to the navy charge, the fund hoarded 500 days, forward bases lost at twice the founding rate, the
humans out of hulls with no hull convoy, no war by day 1500 (the rules and thresholds are listed at
the top of the script, a first cut for the user to tune). A third argument lists signals to ignore
(`ref,hoard`) to read on past known ones. On a fail: stop the batch task, `taskkill
//F //IM java.exe`, fix, rebuild, relaunch. Never one long `sleep` before the first read.

## A checkpoint save from before the war (the user, 2026-10-07)

"Why do we have to wait 15 mins every time, why not just have a save from right before the threat
attack? Most of the issues we are tracking happen after then anyway." The war opens at war day
1466-1953 on this sector (the fund past the opening floor and a target found), 40-55 minutes into a
run. `sbs.ps1 -Tags cka -Checkpoint ck1 -Base save_X` runs ONE game from the base, presses F5 every
`-SaveEvery` (60) war days into its own clone - the clone is self-contained (`clone.ps1` rewrites
`saveDirName`), so F5 writes there and never into the base - and stops at the first "Offensive
launched" / "Strike launched" line. The last save from before that line (a save renames the one
before it to `.bak`; when the last F5 landed after the launch the `.bak` pair is used) becomes
`<Starsector>\saves\save_Xck1` through `clone.ps1`. Batches then start from it:
`sbs.ps1 -Tags hw57a,hw57b,hw57c -Bases "hw57a=save_Xck1;hw57b=save_Xck1;hw57c=save_Xck1" -Days 2000`.
A checkpoint freezes the pre-war sector (economy, hull pool, the hives founded) at the jar it was made
with, so remake it (`ck2`, ...) after a change to anything before the war; the games from it differ
only by the campaign's randomness from there. The "never save on a cloned save" rule stands for every
other run (`sbs.ps1` otherwise never saves): it was written when clones were not self-contained and
F5 wrote back into the original's folder (above, 2026-09-05).
The first checkpoint, `ck1` on the Aphelion sector (2026-10-07 19:42): the war opened at war day 1545,
the last save before it at 1490. The one game alone fast-forwarded at 216 days a minute - a batch of
three runs at ~36 a game (hw56: 2,720 days in 75 minutes), so one game at a time moves twice the days
an hour that three side by side do; the checkpoint itself took 8 minutes of fast-forward.
**One game at a time** (the user, 2026-10-07, on the speed figures above: "do only one sequential run
then"): batches run as a Bash loop over single `sbs.ps1` calls from the checkpoint, e.g.
`for g in a b c; do powershell ... sbs.ps1 -Tags hw57$g -Bases "hw57$g=save_Xck1" -Days 2300 -MaxMinutes 45; done`,
each game ~11 minutes of fast-forward plus a 3-minute load. `watch.sh` ends only after two quiet polls
without a game, so the gap between games does not stop it.
**Correction (2026-10-07, later):** the single-game figure above was the EMPTY pre-war phase. At war
a game runs 90-130 days a minute whether alone (hw58a: 1490 -> 3802 in 25 minutes) or one of three
(hw56: ~130 a game), each on its own cores - so three at once give three times the results an hour,
and a batch from the checkpoint to war day 3800 takes about 30 minutes. The user: "no yeah do 3 at
once" - `sbs.ps1 -Tags hw59a,hw59b,hw59c -Bases "hw59a=save_Xck1;hw59b=save_Xck1;hw59c=save_Xck1"
-Days 2300`. The Bash loop of single runs stays as an option when one answer is wanted soonest.
**ck2 (2026-10-07, 22:14):** made on the exposure build (b21a12d0): with no human world known every hive holds
one swarm a colony and banks the rest, so the war opened at war day 2231 (ck1: 1545; last save 2179) with
40k FP left in the fund after an 8k opener. Batches from `save_AphelionDysnomia_4103534775338436064ck2`.
**ck3 (2026-10-08, 11:10):** the other sector - `save_AmaruDugas_2921423183749615243ck3` from the hw4 pristine save (the harness default), made on the sustenance-first build (5cc098df) in 13 minutes; its war opens at about war day 2160 with the opener from Beta Laphirial at Jangala (Hegemony, 31 ly, 3,042 FP + 5,145 from the bank). Batches hw70+ alternate the two sectors (the user, 2026-10-08: "run another test with a new seed so they come from different part of map").
**ck4 and ck5 (2026-10-08, 12:58 and 13:15):** the same sector remade on the chest fix (a6818b7b, then 29929c06 - a full chest must be fieldable, the fuel in stock sailing the prong): ck4 (first cut) opened at war day 2240 with 18 hives, the chest full from day 1209 with no fuel; `save_AmaruDugas_2921423183749615243ck5` opened at 2425 with 32 hives (size 140, fleets 21k FP), the chest spent until day 1830. Batches from ck5 (hw72+); ck3 and ck4 are superseded. The user (2026-10-08): both sectors run in one batch (hw71 from ck2 + hw72 from ck5, meant as two games each; four 2 GB heaps left 70 MB free and the clocks crawling, so hw71b was killed and three games is the ceiling).

**Never hold `sbs-status.txt` open while a batch runs (2026-10-08, hw71/hw72):** a `tail -f` on it (a
monitor) locked the file on Windows, every `Say` in `sbs.ps1` failed with "used by another process",
and the batch's REACHED / dump lines were lost - the games and dumps were fine (`sbs-go.done` and the
82 dumps a game say so). Poll it with `tail -n` from a loop, never `tail -f`.

## A failing run becomes the checkpoint (the user, 2026-10-10)

"We should always switch to specific checkpoint when we have a failing run so we can try different
strategies until threat win. Then if they win we go back to random seed general testing." The loop:

1. Batches run with `-Snapshots 300`: every game quicksaves into its own clone every 300 war days and the
   save is copied to `<Starsector>\saves\save_X<tag>d<war>` (21 MB each; delete a batch's snapshots once
   its reads are recorded and no game of it is the test bed).
2. A game the swarm loses is the test bed. The per-150-day table (`game-runs-2.md`, navy / hives / strikes /
   sieges) says when it turned; the next trials run `-Bases "hw147a=save_Xhw146bd2700;hw147b=...;..."` from
   the snapshot before the turn, three games on the strategy under test, `-Days` to reach war day 3,800.
3. When the Threat wins that situation, testing returns to day-0 random seeds (`-Base`, or the seed's pre-war
   checkpoint `save_Xck1` for speed - remade after any pre-war change).

The pre-war checkpoint (above) is for speed on a seed; the snapshot is the lost war itself. On the OceanPena
seed (2026-10-10) the games are identical to day 1,500, open the war at 2,000-2,100 and the losing ones read by
3,800, so a snapshot batch from ck1 is `-Days 1917 -Snapshots 300`, about 32 minutes: the war runs ~68 game days a minute with three games side by side (hw146, 19:10-19:42), not the ~100 of a whole run, which the cheap pre-war years lift.
