# Calibrating the simulator against the real runs (round 23 on)

`war-sim-rounds.md` holds rounds 1-22; this doc holds what the long in-game runs (`war-sim-real-runs.md`) changed in
the simulator and in how `check` reads a log. Start here before trusting a `check` row or ranking a human knob.

## 1. Round 23 (2026-10-02 night): the counters were not counting the same things

`war-sim-real-runs.md` 1-3 read the game's humans as a fifth as effective as the simulator's and proposed finding
"where the simulator's sieges land too easily". Most of that gap was the instrument and one price.

**`check` counted two things under one name** (`Main.EVENTS`):

- `siegesSailed` counted every `Expedition draw at` line. A STARVE play's saturation expedition draws the same way
  (`IncursionManager.launchSiegeExpedition` with a raze set): hw4's 41 "sieges" were 12 sieges and 29 saturations, and
  the "one siege in seven lands" was 5 of 12. The row now takes the draws with `razing 0)`; `saturationsSailed` is its
  own row (`STARVE: saturation expedition of N FP sails`).
- `huntsSailed` counted `Hunting force from` - the bounty hunts. The simulator's counter is every HUNT parcel, a HAMMER
  play's forces included; the game logs those as `Play <id> force <faction> goes in over`. hw4: 37 bounty hunts and 33
  play forces, not 28.
- New rows, same names both sides: `plays.RECON|HAMMER|STARVE|BOMBERS` (the `start ->` line), `phase.HAMMER.strike`,
  `playsEnded.success|failure|neutral`, `siege.beaten`, `siege.called off`, `siege.landed (ready)`,
  `siege.landed (dry)` (the `Daily siege of X: N d, .. fight days, <end>` line), `squadronsArrived` (`Raid arrived:`).
  The simulator counts `phase.<TYPE>.<phase>`, `playsEnded.<outcome>`, `squadronsArrived`, `saturationsCalledOff`
  and `saturationWorlds` for them.

**The saturation expedition was ten times too cheap** (`HumanCouncil.saturate`). The simulator paid a tactical stay,
`siegeOrbitDays` x `bombardFuelPerFPDay` x FP (4.8 fuel a fleet point: 4k for 875 FP), over the play's first world.
The game sets aside each world's whole saturation - a squadron pouring `satFuelPerFPDay` to the commander's stop
(`IncursionManager.razingFuel` -> `ThreatGroundFronts.squadronPlan`) - for every world of the play
(`expeditionFuel`), or the expedition does not sail. hw4's `Saturate or siege of` lines: 47,475 fuel at size 4
(166 FP bombing 111 d, 255 d down), 59,344-75,290 at size 5, 150,323-187,563 at size 7 - about 3,000 x size squared.
Built: `warsim_saturationFuelSize2` (3000; 0 is the old price), summed over the play's live worlds, poured over the
stay world by world (`HumanOrder.razeNext`, `HumanSide.station`).

| 60 seeds, new game, month 104 | old price | game's price | hw4 / tr1 (month 108) |
|---|---|---|---|
| saturations sailed | 56.5 [28-90] | 17 [10-30] | 24 / 20 |
| STARVE plays | 62 [28-97] | 22 [13-39] | 27 / 22 |
| sieges sailed | 27.5 [7-77] | 21 [5-48] | 12 / 8 |
| hives killed | 8 [1-24] | 7 [2-21] | 1 / 3 |
| humanScore | 29.5 [19.8-42.1] | 36.5 [19.9-54] | |
| threatScore | 1,701 [731-2,200] | 1,675 [827-2,222] | |

The scores do not move clearly (humanScore ahead in 64% of seeds, threatScore 50%); the play mix does, onto the game's.
`check` with both fixes: hw4a 204 of 330 figures inside p10-p90 (163 before the price), tr1a 241 of 330 (170).

**Trial: raze what the pools pay** (`warsim_saturateAffordable`: the worlds of the play the pools pay, first to last,
instead of all or none - the proposal of `war-sim-real-runs.md` 2). Noise: saturations 17 -> 18, humanScore 36.5 ->
36.5 (57% of seeds), hives killed 7 -> 6.5. Not built in the game.

**What is still out, both runs** (real | simulator at month 108, hw4 then tr1):

| | hw4 | tr1 |
|---|---|---|
| human worlds lost | 2 against 13 [11-15] | 7 against 14 [10-16] |
| forward bases destroyed | 43 against 23 [18-30] | 35 against 23 [16-27] |
| RECON plays | 166 against 327 [262-509] | 219 against 349 [246-465] |
| first strike launched | day 1,279 against about 820 | day 1,156 |
| first mobilisation | month 46 against about 33 | month 47 |
| hives killed | 1 against 11 [3-20] | 3 against 8.5 [2-25] |

Sections 2 and 3 are what was found behind these rows.

## 2. The strike gate reads the orbit; the simulator read the ground

Known since round 4 (`war-sim-swarm.md` 9, facts.md "What does the strike gate read?") and never fitted for want of
the figure: the game's gate (`IncursionManager.strikeOutweighed`) weighs a strike against the system's hostile fleets
and the world's station in vanilla units (`liveTargetDefence`), one figure for every world of a system; the
simulator's `SwarmOps.defenceOf` read the dump's `defence`, the ground figure. hw4 at day 1,279: Sindria, Volturn,
Cruor, Nortia and Umbra all 1,456 at the gate; on the ground 3,120 / 1,200 / .. / 558. Asharu 1,111 at the gate, 78 on
the ground.

Built: the monthly dump carries `gate` for every human world (`ThreatSimDump.worlds`, in the jar from run hw4b on),
`Start` reads it into `World.gate`, and `defenceOf` uses it when the dump has it (older dumps: the ground figure, as
before; `warsim_orbitGate=false` reads the ground figure whatever the dump has). The new-game start with the figures
is `tools/warsim/start/pd9a-newgame-gate` (hw4b's day-5 gate by market id; the core worlds are the same in every
sector).

- Day 5, by system: Aztlan 2,357, Askonia 2,280, Thule 2,100, Hybrasil 1,288, Eos Exodus 982, Samarra 779, Tyle 633,
  Corvus 621, Valhalla 533; Kumari Kandam 20, Al Gebbar 9, Magec 58, Mayasura 98. A pirate world in a strong system
  is covered by it (Umbra 2,280, Donn 1,288, Garnir 621); a backwater's world is open whatever its ground defence.
- The figure holds over a run: hw4b's Askonia 2,280 / 1,244 / 1,620 / 1,660 / 1,563 / 1,590 / 1,329 at days 0 / 240 /
  .. / 1,440, Corvus 620-1,068, Thule 1,361-2,590 - the day-5 figure is a fair fixed one.
- By itself it moves nothing (60 seeds, month 104, ground against orbit): worldsLost 13 [8-15.1] -> 13 [8.9-15],
  strikes launched 245 -> 248, threatScore 1,675 -> 1,760 (60% of seeds). The gate was not the cause of section 1's
  rows; section 3's faults were.

## 3. Round 24: three places the simulator's swarm was not the game's

Found by setting a traced seed beside the game's log from the same home (`run -home <system id>`, new: a real run's
home for a single seed, as `check` takes it). Each has its old behaviour behind a switch (`SwarmKnobs`).

1. **Every faction's first invaded colony fell on the pirates' clock** (`warsim_landingRace=true` is the bug). A
   landing's fate - a colony with no armed reserve falls after `SwarmFit.groundDays`, one at war overruns the beachhead
   - was decided the day after the landing, before `HumanSide.daily` had mobilised the struck faction (the swarm's day
   runs first). So the first world the swarm landed on of each faction always fell: 9 worlds a run. In the game the
   strike mobilises the faction and its garrison overruns the beachhead (hw4: Mazalot, Mairaath, Athulf, Yama).
   `SwarmOps.fronts` now decides a day later. The clock for "a faction not yet mobilised" was a real thing in the
   game's logs, but a rarer one, and a bug there (section 4, "A swept world's owner did not mobilise").
2. **The Path was struck as pirates are** (`warsim_pathNeverMobilises=true`). `SwarmFit.neverMobilises` named pirates
   and the Path as factions the swarm strikes before phase 3 and whose worlds fall on the clock. The game excludes
   `threatinc_warExcludedFactions` alone (pirates; `IncursionManager.warOpen`): the Path mobilises when struck (hw4:
   `War mode: luddic_path mobilised (struck at Chalcedon)`) and its worlds hold - 10 of its 12 ended landings in the
   three runs were ground down. `SwarmOps.neverMobilises(State, faction)` reads the knob.
3. **A strike sailed with whatever part of the muster the fuel paid, gated as the whole muster**
   (`warsim_strikeWholeMuster=false`). The game (`IncursionManager.pickStrikeTarget`) takes the swarms the spare
   supplies keep away, weighs that muster at the gate, and passes over any world whose passage the fuel does not pay
   for all of it - no smaller strike. So the simulator struck from day 700-800 with one or two swarms (38 strikes a
   run sailing under the gate to break off on arrival), where the game's swarm waits for the fuel: its first strikes
   came in the months from day 1,140 to 1,410 in the three runs, with 17-32k fuel banked. `SwarmOps.strikeFrom` now does the game's
   walk; a strike with every world in reach waiting on fuel books the demand and counts `strikesHeldForFuel`.

**Each alone** (60 seeds, new game with the orbit's gate, month 104; old -> fixed):

| | landing day | the Path | whole muster |
|---|---|---|---|
| human worlds lost | 16 [13-17] -> 7 [4.9-9] | 7 -> 7 | 7 -> 7 |
| forward bases destroyed | 51 -> 49 | 43 -> 49 | 22 [16-33] -> 49 [35-68] |
| strikes broken off | | | 38 -> 6 |
| hives | 104 -> 87.5 | 84.5 -> 87.5 | 115 [44-156] -> 87.5 [62-107] |
| garrison FP | 74k -> 60k | 52k -> 60k | 115k -> 60k |
| hives killed | 3 -> 3 | 4 -> 3 | 6 -> 3 |
| sieges sailed | 7 -> 7 | 10 -> 7 | 16 -> 7 |
| hunts sailed | 38.5 -> 46 | 55 -> 46 | 78.5 -> 46 |
| threatScore | 937 -> 768 | 698 -> 768 | 1,516 -> 768 |
| humanScore | 25 -> 27 | 27.5 -> 27 | 32.5 -> 27 |

**All three against the real runs** (`check`, 30 seeds, month 108: real | simulator before | after):

| | hw4 | tr1 | hw3 |
|---|---|---|---|
| figures inside p10-p90, of 330 | 204 -> 223 | 241 -> 247 | 188 -> 216 |
| human worlds lost | 2 \| 13 \| 10 [8.8-10] | 7 \| 14 \| 8 [5-10] | 6 \| 13 \| 8 [5-10] |
| forward bases destroyed | 43 \| 23 \| 58.5 [38-69] | 35 \| 23 \| 32 [20-46] | 21 \| 18.5 \| 38.5 [22-53] |
| sieges sailed | 12 \| 27 \| 9 [3-25] | 8 \| 20 \| 29 [6-74] | 9 \| 33 \| 24.5 [7-53] |
| sieges landed | 5 \| 15 \| 5 [2-14] | 4 \| 12.5 \| 15 [5-35] | 4 \| 18.5 \| 12.5 [3-30] |
| hunts sailed | 59 \| 98 \| 60.5 [21-108] | 36 \| 81.5 \| 82.5 [38-180] | 54 \| 98.5 \| 68.5 [37-122] |
| hives killed | 1 \| 11 \| 4 [1.9-10] | 3 \| 8.5 \| 9.5 [3-20] | 2 \| 12 \| 7 [1-22] |
| RECON plays | 166 \| 327 \| 215 [125-379] | 219 \| 349 \| 287 [175-443] | 90 \| 300 \| 227 [121-318] |
| strikes launched by month 48 | 6 \| 16.5 \| 14 [12-17] | 8 \| 17 \| 17 [15-20] | 3 \| 15.5 \| 10.5 [7-13] |

With the whole muster off and the other two fixed the checks read 210 / 234 / 176: the game's rule fits better on all
three runs and stays.

**Tried, not kept: the front engine for a colony at war** (`warsim_coloniesFall=true`, round 8's hypothesis, now with
the landing day fixed): worlds lost 7 -> 29.5 [18-44]. The game's garrisons overrun the beachheads (section 4); the
overrun clock is the fit.

## 4. What the game's logs showed on the way (2026-10-02, runs tr1, hw4, hw3)

Scripts in section 6. Each is a fact of the game, not of the simulator.

**What a Threat landing comes to.** Landings that ended, by the world's owner:

| | hw4 | hw3 | tr1 |
|---|---|---|---|
| pirate world | 4 taken, 1 ground down | 4 taken | 5 taken |
| Path world | 5 ground down | 1 taken, 5 ground down | 1 taken |
| faction world | 43 overrun, 1 ground down | 3 taken, 41 overrun | 2 taken, 52 overrun, 4 ground down |

13 of 14 on pirate worlds took the world; 5 of 146 on a faction's did (Salamanca, Nomios and Agreus in hw3 on 240-300
troops, before their owners were at war - below; Sindria and Tartessus in tr1 at the end). The landing is the beachhead's size
(`ThreatStrikeFGI.beachheadLanding`: 300 troops is the commonest, 115-900), split over the worlds of the sweep; it
outlasts the first counter-attack as built on 2026-09-29 and is overrun by a later one. 21-38 reinforcing passes a
run against 65-85 landings.

**A swept world's owner did not mobilise (a bug, fixed 2026-10-02).** A strike sweeps every open world of its
target's system (`IncursionManager.launchStrike`), and `onStrikeDetected` recorded the strike for the first target
alone. From phase 3 (`warOpen`: every faction open) the sweep landed on the neighbours' worlds with their owners at
peace and no reserve to answer: hw3's strike at Qaras (pirates) took Salamanca on 299 troops 60 days before the
League mobilised (for Madeira), its strike at Citadel Arcadia (the Hegemony, at war) took Nomios and Agreus 900 days
before the independents did; tr1's strike at Kanni (pirates) took Chalcedon 2,000 days before the Path did. The phase
reads 3 from day 1,050-1,085 in all three runs (`phases.pl`) and flips between 2 and 3 with the fuel stock after
(`coreWorldInReach`). Over the three runs (`unmob.pl`) all 4 landings on a world whose
owner was not at war took it; 3 of 154 did where the owner was at war (hw4b after them: Skathi, 200 troops, with
Tri-Tachyon at peace - 1 of 1 - and 5 of 45 at war, all in its last year). `onStrikeDetected` now records the strike for
every faction with a world in the sweep, once each, and gives its scouts the lead. The simulator strikes one world a
strike, so each of its landings already mobilised the owner: after the fix its landing rule (3.1) is the game's.

**The swarm's first three years are silent.** Phase 2 from day 635, most worlds scouted by day 720-810, the first
strike in the month from day 1,265 (hw4), 1,380 (hw3), 1,140 (tr1); the first mobilisation in months 46-49. The
strike needs the fuel for its whole muster (section 3.3) and the stock is spent as it comes.

**Where the swarm's fuel goes: its own garrison traffic.** `Posture: X sent N FP to Y` (the posture's transfers,
`ThreatPosture`): 1,246 in hw4's year 8 and 1,148 in year 9 (tr1: 1,075 and 699), a mean 190 FP over 12-15 ly. A
year's traffic is 220-230k FP against a standing garrison of 100-140k; 33-42k of it is newly fabricated for the
receiver, and 154-161k passes through colonies that both received and sent within the year (141-154 of them). At
`expeditionFuelPerPointLY` 10 a one-way send of 190 FP over 14.5 ly is about 550 fuel: some 50k fuel a month of an
income of 60-126k. hw4's stock sat at 0.15-17k from month 50 to 87 on an income rising from 18k to 63k a month,
then at 6-61k.

It is relay, not ping-pong (`churn.pl`, the year from day 2,880, four runs): 11-14% of the traffic goes back to the
hive it came from, 62-69% passes through a hive that both receives and sends. The hubs are the hives a human force
is staged against: hw4's Tabiacoud wanted 20.7k FP on day 2,915, 27k on day 3,005 and 4.3k on day 3,155, took in
24k over those months and sent 22k on (5k of it to Qaras) once the want fell. The want follows the staged threat
(`ThreatPosture.poll`), so a base that stages against a hive and does not sail costs the swarm the fuel of gathering
and dispersing its garrison - by design, and the place a player's feint would bite.

**The simulator sends half as many** (`reinforcementsSent`, a `check` row now): hw4 3,334 by month 108 against 1,795
[1,539-2,054], tr1 3,207 against 1,195 [714-1,642], hw4b 4,746 against 1,748 [1,390-2,165]; so its fuel piles up
(60-90k at month 108 against the game's 11-14k) and it strikes about twice as often through month 84.

**Run-to-run noise is as large as the settings** (`war-sim-real-runs.md` 5): hw4 and hw4b, one save and one setting,
ended at 152 and 209 hives at month 108. hw4b sits above the simulator's band from month 48 (209 against 113
[79-127]; 178 of 338 figures inside).

## 5. Still out after round 24

- **The posture's traffic** (section 4): half the game's sends, the fuel stock five times the game's.
- **Strikes and landings about twice the game's through month 84** (hw4 month 60: 23 strikes | 43 [38-51]); fuel
  for them is what the traffic would have burned.
- **The swarm ends smaller than the game's with the whole muster**: hives at month 108, hw4 152 | 119 [93-140], tr1
  131 | 61.5 [35-91], hw3 113 | 90.5 [26-114], hw4b 209 | 113 [79-127]; garrison 140k | 88k, 101k | 37k, 151k | 48k,
  241k | 77k. With partial musters the
  size fits (hw4 152 | 149, 140k | 135k) and everything the humans do is too much - two errors that cancelled.
- **Months in CONSOLIDATE**: the game 0-4, the simulator 9-27.
- **Strike travel.** `State.LY_PER_DAY` 0.5 is the game's own estimate (`ThreatReach.strikeDays`: 36 ly, "~156 days
  away" there and back). Launch to first pass at the named target, single-world sweeps, in monthly buckets: hw3 24 d
  at 4 ly, 47 at 14, 92 at 24, 120 at 35 (about 0.3 ly a day); hw4 and tr1 read longer and noisier. Not fitted.

## 6. Scripts (machine-local, `%TEMP%\threatinc-tests`)

`unmob.pl <ti log>` lists the landings on a world whose owner was not at war and tallies both kinds; `phases.pl <dump
dir>` the war days a run changed phase; `churn.pl <ti log> [from] [to]` the posture's transfers - receivers, senders,
what returned and what passed through.
`chkrows.pl <check output> <rows> [months]` prints rows of a `check`; `dated.pl <ti log> <regex>` prints matching
lines with the war day of the last monthly dump; `landings.pl <ti log>` every Threat landing with its troops, strata
and end, and the tally by owner; `strikedays.pl <ti log> [1]` launch-to-first-pass by distance; `swarmfuel.pl <dump
dir> <from> <to>` the swarm's fuel stock and income by war day; `gates.pl <dump dir> [step]` the gate figure by
system over a run (worlds of a faction at war are skipped: their objects nest); `gate-start.pl` copies gate figures
into a start dump by market id; `r24.ps1` / `chk.ps1` run a detached `compare` / `check` to a `.done` file.
