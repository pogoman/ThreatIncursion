# Testing handover - 2026-10-05: batch hw11 on another computer

For the session (or person) running the next game test on a machine that has not run this harness. Read the
repo's `CLAUDE.md` first. Session memory does not travel between machines, so the user's standing rules are in
section 8. Everything measured so far is in `game-runs.md` 8; this file is how to run and read the next batch.

## 1. What is being tested

One change, on top of the build hw10 tested (`eb7a1f1`): **the system defence now rallies against the fleet
left guarding a landed army** (`e5ce9b9`, `ThreatPosture.poll` adds `ThreatGroundFronts.coverOver(c)`;
`swarm-defence.md`, last paragraph of "The system defends as one").

Why: a hive takes 1-5 months to fall once an army is down (median 10 / 42 / 62 / 83 / 87 / 121 / 139 days from
the landing, by size 1-7). Through those months an unspawned landing's flotilla holds the orbit as an abstract
figure on the front (`GroundFront.coverFP`). The rally read real fleets and fighting sieges only, so it stopped
the day the army landed: in hw10, 1 / 1 / 0 fleets were rallied to a world while its cover stood. A swarm that
outweighs the cover takes the orbit back (`swarmOrbitContested`, log `Orbit cover over X lost`) and bombards
the army (`tickSwarmBombard`).

Settings: shipped defaults. `systemDefence` true, `systemDefenceMargin` 1.25, `strikeSeenBySystem` true,
`postureMass` false - all settings.json only, no LunaLib rows. No knob is set for the batch.

The build: `jars/ThreatInc.jar`, 972,029 bytes, built 2026-10-05 09:35, is committed. `git pull` is enough; do
not rebuild unless the source changes (then `compile.ps1` out of process with the game closed, and check the
jar's timestamp).

## 2. Setup, once on the machine

The harness has its paths written in: the game at `C:\Program Files (x86)\Fractal Softworks\Starsector`, this
repo at `mods\ThreatIncursion` under it.

1. Starsector 0.98a-RC8, and these nine mods enabled, no others (`mods\enabled_mods.json`) - the pristine
   save was made with them, and a missing-mod prompt at the load makes the harness's clicks miss:
   `commwars` (Commerce Wars 0.3.2), `lw_lazylib` (LazyLib), `lunalib` (LunaLib 2.0.4), `piratepat` (Piracy
   Reworked 0.2.0), `remret` (Remnant Retribution 0.3.1), `shiftspeed` (Shift Speed 0.1.0), `speedUp`
   (SpeedUp 1.2.2), `threatinc` (this mod), `ups` (Useful Planetary Shield 0.1.4).
2. The pristine new game: copy the folder `tools\test-harness\saves\save_AmaruDugas_2921423183749615243` into
   `Starsector\saves\`. Never load it and save by hand; every run clones it.
3. Start the game once by hand to the main menu and quit, so the launcher prefs and the LunaLib stores exist
   (`saves\common\LunaSettings\threatinc.json.data` and `shiftspeed.json.data`; if one is missing, open the mod
   settings with F2 at the main menu and save). Not tried on a clean machine. A LunaLib store left by an older
   version of the mod shadows changed defaults on the knobs that have rows; the knobs under test have none.
4. With the game closed, BEFORE the first run:
   `powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-harness\fastforward\backup-settings.ps1`.
   It saves the user's launcher prefs and Shift multiplier to `%TEMP%\threatinc-tests\backup-20261002`, where
   `sbs.ps1` looks for `restore.ps1` at the end of every batch. Without it the run settings stay on (1600x900
   windowed, autosave off, Shift at 48x, debug logging, dumps and Fleets Ignore You).
5. Git for Windows (its Perl and Bash run the digests). `python` is not used.
6. The screen must stay unlocked and awake for an hour, and show a 1600x900 window. Each game takes about
   2.8 GB of memory: three need 9 GB free; with less, run two tags.

## 3. Run it

Ask the user before launching (their rule), with no game open (the runner kills any) and the screen unlocked
(it refuses on a lock screen: do not work around that).

```powershell
Start-Process powershell -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File "C:\Program Files (x86)\Fractal Softworks\Starsector\mods\ThreatIncursion\tools\test-harness\fastforward\sbs.ps1" -Tags hw11a,hw11b,hw11c -Days 3750' -WindowStyle Hidden
```

- Three new games on the same sector, fast-forwarded together to war day 3750 (month 125): 40-55 minutes.
- It holds Left Shift system-wide. Touching the keyboard or mouse drops the games to normal speed until the
  machine has been quiet for 60 seconds. Leave it alone.
- Progress: `%TEMP%\threatinc-tests\sbs-status.txt`. Finished: `sbs-go.done` appears beside it. The logs are
  `ti-hw11a.txt` etc. there, the exceptions `exc-<tag>.txt`, and the monthly dumps are copied to
  `tools\warsim\validation\<tag>`. It never saves a game, deletes its clones and restores the user's settings.
- Wait with a background `until [ -f sbs-go.done ]` loop, not a foreground sleep.
- Lines in the status file that mean a game did not start: `LOCKED - nothing run`, `CLONE FAILED` (the pristine
  save is not in `saves\`), `NO GAME WINDOW`, `NOT LOADED (Continue missed, or the save was not found)`,
  `clock NOT RUNNING`. The launcher's Play click scales with the display; the Continue click is at client
  pixel (1290, 256) of the 1600x900 window. Other screens and scalings: `testing-harness.md`, "Side-by-side
  runs" and the sections on display scaling.
- Month N of a run is its Nth `Census: threat` line. Three games reach month 60 about 18 minutes in.

## 4. Read it

One command prints every digest; write one findings file a checkpoint (month 60, then the end), so a stopped
batch still leaves results:

```bash
bash tools/test-harness/digest/batch.sh 60 hw11a hw11b hw11c > "$TEMP/threatinc-tests/hw11-findings-m60.txt"
bash tools/test-harness/digest/batch.sh 123 hw11a hw11b hw11c > "$TEMP/threatinc-tests/hw11-findings-m123.txt"
```

The digests are listed at the top of `game-runs.md`. The ones this batch turns on: `cover.pl` (the cover over
each landed army: how many the swarm outweighed and when, what was rallied to the world meanwhile, how each
landing ended), `swarm.pl`, `calloff.pl`, `fall.pl`, `months.pl <tag> [from] [to]` for one run month by month.

The hw8 and hw10 logs exist only on the first machine. Their figures are below and in `game-runs.md` 6 and 8; a
new counter that needs a baseline has to be run there.

## 5. The baseline: hw10 (same build without the change) and hw8 (no system defence)

| | hw10a | hw10b | hw10c | hw8a / b / c |
|---|---|---|---|---|
| extinct | never (175 hives at m123) | m93 | m74 | never (150) / 3 hives at m123 / m89 |
| hives m60, m84, m108 | 25, 64, 124 | 25, 3, 0 | 7, 0, 0 | 27, 70, 107 / 17, 27, 8 / 9, 2, 0 |
| hives the humans had found at m48 | 9 of 14 | 15 of 17 | 13 of 15 | 10 of 16 / 7 of 17 / 10 of 13 |
| landings left under cover (median cover) | 16 (924 FP) | 31 (1,493 FP) | 21 (1,312 FP) | 30 (992) / 43 (1,036) / 17 (1,486) |
| covers the swarm outweighed (median days after the landing) | 7 (27) | 3 (38) | 0 | 18 (23) / 8 (38) / 2 (31) |
| rallied to a world while its cover stood | 1 fleet (115 FP) | 1 (100 FP) | 0 | - |
| other transfers to it meanwhile | 139 (16.4k FP) | 36 (5.1k FP) | 15 (1.7k FP) | 465 (62.5k) / 197 (31.0k) / 26 (5.9k) |
| landings ended: hive eradicated, army overrun | 14, 2 | 30, 1 | 16, 5 | 22, 8 / 38, 5 / 14, 3 |
| of the covers lost: hive eradicated anyway, army overrun | 6, 1 | 3, 0 | - | 10, 8 / 6, 2 / 1, 1 |
| sieges down, called off | 432, 244 | 146, 34 | 60, 12 | 373, 98 / 162, 16 / 66, 8 |
| fleets rallied in all (FP) | 2,902 (501k) | 603 (126k) | 164 (31k) | - |
| Threat FP lost a human FP | 1.25 | 1.35 | 1.31 | 1.34 / 1.42 / 1.45 |
| seeding swarms, strikes, since found | 221, 173 | 26, 20 | 1, 2 | 190, 184 / 33, 26 / 1, 1 |
| human worlds lost | 2 | 0 | 1 | 0 / 0 / 1 |
| exceptions file | 270 bytes | 270 bytes | 270 bytes | 270 bytes each |

hw10 had no council error. The exceptions file's 270 bytes is vanilla's own noise on the first machine; on
another, read the file.

## 6. How to judge it

1. **Does the rally reach a covered world?** `cover.pl`: "rallied N fleets" while the cover stood should be far
   above 1, and `Posture: X rallied N FP to Y` lines should name worlds with an army on them.
2. **Does the swarm take the orbit back?** Covers outweighed, of landings: 7 of 16 / 3 of 31 / 0 of 21 before,
   a median 27-38 days after the landing. More of them and sooner is the change working.
3. **Does that save the hive?** Armies overrun against hives eradicated: 2 and 14 / 1 and 30 / 5 and 16 before.
   Mind the row "of the covers lost": in hw10 a hive whose cover was outweighed fell anyway in 9 of 10, in hw8a
   in 10 of 18. If covers now fall and the hives still die, the short link is what the swarm does with the
   orbit (`tickSwarmBombard`: `swarmFrontBombardPer30Days` 0.60, scaled by the swarm's weight against the
   army's defence) and its counter-attacks. Report that with its figures; changing them is the user's call.
4. **The war.** Extinct month and hives at m60 / m84 / m108 against the table. Three runs do not separate a
   change from the spread between runs: hw8 and hw10 each gave one runaway and two deaths, and the outcome
   followed how many hives the humans had found by m48 (41-64%: alive at m123; 77-88%: extinct by m74-m93).
   Report the found share beside any outcome.
5. **Side effects.** The share of sieges called off (56% / 23% / 20%): a system that sends its spare swarms to a
   covered world has less for the next siege. Seeding and strikes since found. The humans' answer: more sieges
   ending `front` (sent to a world that already has an army) and their FP sent.
6. **No exception, no council error.**

Every finding goes to the user with a proposed fix; small faults are fixed, not listed.

## 7. Record it and bring it back

- `game-runs.md`: a section 9 in the shape of section 8 (the doc is 32 KB; past about 40 KB, split it by
  topic and repoint `README.md` and `facts.md`).
- `swarm-defence.md` ("built after hw10, untested" becomes the result) and the `facts.md` line "How long does a
  hive world take to fall", plus a new line for anything learned.
- Commit the dumps in `tools\warsim\validation\hw11*` with the docs. Commit messages end with
  `Co-Authored-By: Claude <noreply@anthropic.com>` and name no model. Push when the user says so.

## 8. The user's standing rules (memory does not travel)

- Never launch the game or the harness without asking. Never save on a cloned save. Never delete
  `Starsector\screenshots`.
- The game is the test. The simulator in `tools\warsim` is frozen: never calibrated again, no simulator-only
  copies of mod logic.
- Both sides play to win. A rule that stops a side using means it has is a fault to remove; a shortage is
  traced and questioned. The Threat may win the NPC war: no tuning toward parity. The humans are to be left
  alone for now.
- Balance values and new systems for the swarm's AI are the user's call. Build what is asked with its defaults
  as knobs; no unasked caps.
- A bug is investigated and fixed in one go, including the mechanic it hinges on; no unrelated rebalancing.
- Test runs report as they go. Issues come with solutions.
- Player-facing text: current values, one line a fact, never fluoro green; notifications through
  `ThreatNotice`; no progress notices (`CLAUDE.md`).
- Decisions the user makes go into `facts.md` Decisions the same turn; findings into `facts.md` or the topic doc.

## 9. Waiting on the user - do not build

- A called-off siege paying one day of the exchange as it turns away (`game-runs.md` 8: a massed defence
  saves the world and kills nothing). Proposed 2026-10-05; the user's question about how long a world takes to
  fall led to the cover change first.
- Whether `systemDefence` and `strikeSeenBySystem` stay on (recommended: yes).
- Sending only the fleets that cover the gap when a strike is recalled; why hw8a's offence took no world; the
  swarm's means (the ground cost of a hive, forge output, a defender's edge in orbit); the Hive Mind design
  (`swarm-strategy.md` 3); the 10-day contact memory (`swarmContactDays`); a neighbour giving only above its
  own want.
