# Handover - 2026-10-02 night (after the flight session)

Written at the end of session 31c5f83d (simulator rounds 23-27, in-game runs hw3, hw4b, hw4c). Everything is on
`main` at origin (`22d5927` and this file's commit); the working tree is clean but for two `*.stackdump` files that
are never committed. The game is closed and the laptop's own settings are restored. Read this, then the `facts.md`
Decisions list, then the sections named below. `handover-2026-10-02.md` is the day's earlier history and is not
needed to continue.

## 1. Where it stands in five lines

- **The jar** (`jars/ThreatInc.jar`, 2026-10-02 20:00) has one change of tonight: a strike mobilises every faction
  with a world in its sweep (`IncursionManager.onStrikeDetected`, `d086cdb`). Verified in the game (run hw4c).
- **The simulator** (`tools/warsim`) was fitted to the game through rounds 23-26. `check` has about 72% of the
  real runs' figures inside its p10-p90 band; a perfect model would score about 80% (that is what p10-p90 holds).
- **What it gets right and wrong** is section 2. In short: the swarm's economy and the humans' operations are in; the swarm's strike and landing volume (a few months early, then too many late), forward bases held late and the
  stance's months are out.
- **The game is itself noisy**: one save, one setting, twice - 152 and 209 hives at month 108 (hw4, hw4b). One
  in-game run confirms a bug fix or a log line; it does not settle a balance question.
- **Four things wait on the user** (section 3). Nothing else is owed.

## 2. How far to trust the simulator (the user's question on landing)

Per row of `check` over hw4a, hw4b and hw4c (30 seeds each, months inside the band; `fitrows.pl`):

| | to month 60 | after | rows |
|---|---|---|---|
| in | 93-100% | 80-100% | `fuelPlants`, `forges`, `huntsSailed`, `siegesSailed`, `siegesLanded`, `saturationsSailed`, `plays.HAMMER`, `plays.STARVE`, `siege.beaten`, `frontsOverrun`, `basesAbandoned` |
| mostly in | 56-100% | 53-80% | `hives`, `hivesKilled`, `swarmFuelPerMonth`, `mobilised`, `basesFounded`, `hiveSize`, `swarmFuel`, `basesDestroyed`, `reinforcementsSent`, `garrisonFP`, `worldsLost`, `swarmSupplies` |
| in early, out late | 78-100% | 7-20% | `basesHeld` (month 108: the game 1-7, the simulator 19-21), `beachheadsOverrun`, `plays.RECON`, `playsEnded.neutral` |
| out | 0-67% | 20-47% | `strikesLaunched` and `threatLandings` (early by a few months - month 48: 4-6 strikes against 10-11 - in at month 60, then 1.6 and 2.5 times the game's by 108), `monthsExpand`, `monthsPress` (ten months more PRESS by month 48) |

Use it for: ranking a rule or a knob by each side's own score, the swarm's economy, the humans' sieges, hunts
and plays, large effects (a compare of 60 seeds over 104 months takes under a minute). Do not read off it: late-war strike or
landing counts, worlds lost in absolute terms, forward bases held after month 60, months in a stance. A cell that
wins only through those rows needs the fault fixed first or a game run.

**Why the fit moved tonight** (each a place the simulator's rule was not the game's, found by putting a counter on
both sides; `war-sim-calibration.md` 1, 3, 6, 7): `check` counted different things on the two sides; saturation ten
times too cheap; a landing decided before the struck faction mobilised; partial strike musters; the posture's
transfers on the wrong cadence; strike fleets parked over their landing (they go home in a player-less war); every
held poll booked as fuel demand (twice the game's fuel plants). 906 -> 1,082 of 1,472 figures inside over rounds
25-26 alone.

**The method that worked**, for the next fault: pick the worst row, add the figure to `Sim.row` and to `check`'s
`cols` (the real side is computed from the dump by the same code), find the first month it leaves the band, then
grep the game's log for the lines that produce it and read the one method (`docs/symbols.md`). Twenty minutes a
fault. A new counter in the monthly dump (`ThreatSimDump`) needs a game run; a log line already there does not.

## 3. Waiting on the user (offered, not built)

1. **Mobilise every faction when the swarm reaches phase 3.** hw4c: the Diktat, first struck on day 3,000,
   mobilised that day and lost Sindria (size 7) to 1,419 troops landed within the month. Simulator trial
   `warsim_mobiliseAtPhase=3`, 60 seeds: worlds lost 9 -> 5 (clear), landings 155 -> 74 (clear), two-sided
   outcomes 28% -> 83%, the humans' p10 15 -> 25; neither score clear of the noise; phase 2 is too early.
   Recommendation: build it as a knob, default on. `war-sim-calibration.md` 9.
2. **A strike that waits on fuel books no fuel demand**, so the planner builds no plant for it (12-15% of hives
   hold one). Booking it doubles the plants and adds half again to the strikes without moving the swarm's own
   score. An NPC-offence lever (`facts.md` Decisions: those are the user's call). `war-sim-calibration.md` 7.
3. **Only a strike near the player holds the orbit over its landing** (`ThreatSwarmDefend` needs a spawned fleet;
   an unspawned strike ends and re-banks). Intended? `war-sim-calibration.md` 6.
4. **Whether the humans' p10 floor may go** as a guard on ranking (open since the afternoon).

## 4. Next, in order

1. **Late-war strikes and landings** (`war-sim-calibration.md` 8). After month 84 the simulator strikes about 1.6
   times and lands 2.5 times as often as the game. Leads, none read strike by strike yet:
   - `ThreatStrikeFGI.doCustomRaidAction` lands a pass only when `ThreatGroundFronts.readyToLand` says so
     (`abstractOrbitDone`, or the troops hold as they are) and `beachheadLanding` holds back a landing the first
     counter-attack would overrun; `SwarmOps.strike` lands every strike not broken off.
   - Fuel the game's swarm burns and the simulator's does not: raiders (`ThreatRaiders`), bombardment ordnance and
     razing (`ThreatGroundFronts.payOrdnance`, `ThreatStrikeFGI.saturationPass`). The monthly `Census: threat
     hives ..` line carries `fuel N (+made/mo, spent N)` - a direct target for a `swarmFuelSpent` row.
   - The game's strikes sweep 1.5-1.7 worlds; the simulator's hit one.
2. **Forward bases held late** (`basesHeld`: 15 of 15 months in to month 60, 1 of 15 after). Not looked at.
3. **The supplies stock piles up late** (hw4 month 108: 12k | 703k). The game's `convertSurplus` /
   `retireMilitary` are not in the simulator.
4. **Months in EXPAND / PRESS**: the stance clock is off from the start (0 of 9 months in to month 60).
5. Then rank human knobs again (`war-sim-rounds.md` 13-16 are the earlier rankings, taken before the fit).

## 5. Commands and base figures

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\warsim\build.ps1
java -cp tools\warsim\out warsim.Main compare -a "warsim_noop=0" -b "<k=v;k=v>" -start tools\warsim\start\pd9a-newgame-gate -seeds 60 -months 104 -killWeight 1 -show a,b,c
java -cp tools\warsim\out warsim.Main check -dumps tools\warsim\validation\hw4c -seeds 30 -log <ti-hw4c.txt> -set threatinc_warCouncil=true
java -cp tools\warsim\out warsim.Main run -start tools\warsim\start\pd9a-newgame-gate -seed 2 -months 108 -v
```

Base, 60 seeds, new game, month 104, round 26's defaults (median [p10-p90]): hives 139 [106-173], garrison 149k,
fuel plants 19 [17-23], strikes 255 [176-311], landings 155, sieges sailed 9, hunts 48, hives killed 2, worlds lost
9, forward bases held 20, destroyed 31, threatScore 1,995 [1,470-2,576], humanScore 25 [15-53.5]; outcomes both
sides 28%, one-sided 45%, none decided. `check` inside, of 378: hw4a 264, hw4b 265, hw4c 278.

The switches of tonight, all in `SwarmKnobs` with the old behaviour on the other value: `warsim_landingRace`,
`warsim_pathNeverMobilises`, `warsim_strikeWholeMuster`, `warsim_orbitGate`, `warsim_postureLoop`,
`warsim_strikeDefends`, `warsim_holdsBookMonthly`; the trial `warsim_mobiliseAtPhase` (`HumanSide.daily`).

Real runs in the repo (`tools/warsim/validation/`): pd9a, pd10a (planner), tr1a (three-planet home), hw4a, hw4b,
hw4c (four home worlds; hw4c with the sweep fix), hw3a (three). Their logs are machine-local (below); `check`'s
event rows need the log.

## 6. Machine-local (this laptop, not in git)

- `%TEMP%\threatinc-tests`: the runs' logs `ti-<tag>.txt` (tr1, hw4, hw4b, hw3, hw4c), digests, every `chk*.txt`
  and `r2*.txt` compare, and the scratch scripts (`war-sim-calibration.md` 10; `fitrows.pl <check outputs>` gives
  section 2's table; `chk.ps1` / `r24.ps1` run a detached `check` / `compare` to a `.done` file).
- `%TEMP%\threatinc-tests\backup-20261002\restore.ps1`: puts the laptop's game settings back after a harness run.
  It was run at 20:53; the Continue button, resolution, autosave and Shift speed are the user's again.
- Saves: the clones `...ng3`, `...ng4`, `...ng5` (new-game clones the runs used) are still in `saves\`.

## 7. Traps met tonight

- A tool-run background job dies at 10 minutes: start long jobs with `Start-Process powershell ... -WindowStyle
  Hidden` and poll a `.done` file. Never rebuild `tools\warsim\out` while a detached `check` is running.
- A simulator batch slows a running game by a third; hold them while a game run is timed.
- `cd` in the Bash tool moves the session's directory; `sed -i` strips carriage returns (most docs and sources are
  CRLF in the working copy - patch with a Perl script that normalises, as `facts-r26.pl` does); Perl `glob` fails on
  the mod path (use `opendir`); a capture group does not survive a second match in the same expression.
- `check`'s denominator changes when a row is added (338 -> 368 -> 378 tonight): compare counts only at one size.
- Never start an in-game run while the user is at the laptop, and run `restore.ps1` when the last one ends.
