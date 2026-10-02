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

Scripts in section 10. Each is a fact of the game, not of the simulator.

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

- **The posture's traffic** (section 4): half the game's sends, the fuel stock five times the game's. Round 25
  (section 6) put the pass on the game's loop: 70% of the game's sends, the fuel stock unmoved. Rounds 25-26
  (sections 6-7) closed it: strikes go home after their landing, and a held send books the game's fuel demand.
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

## 6. Round 25: the posture's transfers on the game's loop

`ThreatColonyManager.redistributeByPressure` runs every half day (`IncursionManager.advance`), takes any colony below
its want as a receiver, and sends the neediest one a donor can serve one swarm - the largest within its deficit, else
the donor's smallest, capped by the donor's spare and what the receiver accepts - from the nearest donor, again and
again until nothing goes (hw4: 4.1 sends on a day with sends, 801 receiver-days with two or more). The simulator ran
its pass every `postureDays` and sent each receiver one swarm, the donor's largest. Built:
`SwarmPosture.redistributeLoop`, on by default (`warsim_postureLoop=false` is the old pass); counters
`reinforcementFP`, `reinforcementsCross`, `reinforcementLY`; monthly figures `wantFP` and `heldOfWant`.

- 60 seeds, new game, month 104, old -> loop: transfers 1,519 [1,108-1,965] -> 2,215 [1,680-2,734] (clear, 93% of
  seeds); nothing else clear - fuel spent 3,972k -> 3,975k, strikes 235 -> 219, hives 87.5 -> 86, bases destroyed
  49 -> 42, threatScore 768 -> 775, humanScore 27 -> 27.5.
- `check`, figures inside p10-p90 of 338: hw4a 224 -> 235, hw4b 178 -> 199, tr1a 247 -> 249, hw3a 216 -> 200.
- Transfers at month 108, real | simulator: hw4 3,334 | 2,375 [1,864-2,769], hw4b 4,746 | 2,585, tr1 3,207 | 1,850,
  hw3 2,748 | 1,961; inside the band to month 60 on all four, about 70% of the game's after.

**The simulator's swarm was short of its own want, the game's is over it.** The game's monthly `Posture sector: held
Xk want Yk` reads held 1.0-1.4 times want from year 4 on in all four runs (hw4 1.34 / 0.99 / 1.27 / 1.13 / 1.14 /
1.24 by year); the simulator's `heldOfWant` was 0.8 [0.6-1.1] at month 36 and 0.6-0.8 after. A donor must hold its
want, so the simulator had few donors. It had more fleet points in flight than at home (`threatFleetFP` 79.6k
against a garrison of 66.4k at month 108) and 23-49k of them parked on Defend over beachheads (`defendFPDays` 42M by
month 108).

**The cause: a strike's fleet stayed over its landing; in a player-less war it goes home.** A strike far from the
player never spawns its fleets: `doCustomRaidAction(fleet == null)` -> `stayOnDefend(null)` returns, the strike ends
and logs `Strike ledger: ended unspawned, N of M FP re-banked` (117-174 a run). `Swarm defend` lines appear only at
Asharu, Garnir and Jangala, where the test save's parked player fleet makes strikes spawn - a test-setup artefact,
and the one place the two paths differ in the game (a spawned strike holds the orbit over its landing, an unspawned
one does not). Built: `warsim_strikeDefends` (false; true parks the fleet until the front ends, the old behaviour).
60 seeds, month 104, true -> false:

| | parks (old) | goes home |
|---|---|---|
| hives | 86 | 140 [108-160] (clear) |
| garrison FP | 64k | 139k |
| held of want | 0.8 | 1.0 |
| transfers | 2,215 | 3,080 (clear) |
| hives killed | 3 | 0 |
| sieges sailed, hunts | 9, 58 | 3, 32 |
| strikes, landings | 219, 121 | 385, 219 (clear) |
| fuel spent | 3,975k | 5,661k |
| threatScore, humanScore | 775, 27.5 | 1,889, 19.5 |

Swarm size, transfers and kills came to the game's (month 108: hives 113-209, garrison 101-241k, transfers
2,748-4,746, kills 0-3); strikes and landings went to 2.5-4 times the game's (92-176, 45-60). Round 26 found why.

## 7. Round 26: what a held send books as fuel demand

`check` gained `swarmFuelPerMonth`, `fuelPlants` and `forges` (the dumps carry each hive's industries; a plant's
output is `size - 2` units of 1,500, within 8% of the dump's `swarm.fuelPerMonth`). At month 108 of hw4 the game had
18 fuel plants on 152 hives making 113k a month; the simulator 49 [43-55] on 181 making 259k. In the four runs the
game's plants are 12-15% of its hives, nearly all on size 5 and up (hw4b 23 of 209, hw3 14 of 113, tr1 20 of 131);
forges are 55-69%. hw4's planner log over 115 months: `fuelprod` 1 first, 5 `(fuel short)`, 20 `(spare)`. A traced
seed of the simulator built 12-35 for a shortage and 2-18 spare.

Two differences in `ThreatFuel`'s trailing demand, which the planner answers with a plant (`runsDry`):

- **A held send books once a `SHORT_DAYS` a source** (`ThreatFuel.bookHold`, keyed "strike from X", "a Seeding Swarm
  from X", "a reinforcement from X"). The simulator booked every held poll of a strike and of a Seeding Swarm, and
  no reinforcement. Built: `SwarmEconomy.bookHold`.
- **A muster that waits on fuel books nothing.** `IncursionManager.pickStrikeTarget` passes over a world the whole
  muster's passage is not paid for (`continue`), so `launchStrike` only ever sees an affordable target and its
  `ThreatFuel.held("strike from ..")` (one swarm's passage unpaid) is not reached: hw4 logged no `strike from ..
  held` in 115 months, against 175 Seeding Swarm holds, 136 raider, 24 scout, 1 reinforcement. The simulator booked
  the cheapest world's muster passage each time every world in reach waited on fuel.

Both sit behind `warsim_holdsBookMonthly` (true; false is the old booking). 60 seeds, month 104, old -> new:

| | old booking | the game's |
|---|---|---|
| fuel plants | 44 [22-51] | 19 [17-23] (clear) |
| fuel a month | 227k | 120k (clear) |
| fuel spent | 5,661k | 3,468k (clear) |
| fuel stock | 113k | 54k (clear) |
| strikes, landings | 385, 219 | 255, 155 |
| forward bases destroyed | 61.5 | 31 (clear) |
| sieges sailed, hunts | 3, 32 | 9, 48 |
| hives, garrison FP | 140, 139k | 139, 149k |
| hives killed, worlds lost | 0, 10 | 2, 9 |
| supplies stock | 122k | 586k [72k-1,073k] |
| threatScore, humanScore | 1,889, 19.5 | 1,995, 25 |
| one-sided outcomes | 75% | 45% |

The swarm's own score does not move (B > A in 63% of seeds, not clear): the plants it no longer builds were paying
for strikes on forward bases, and their slots go to forges.

`check`, figures inside p10-p90 of 368 (the three new rows included), old booking -> new: hw4a 196 -> 256, hw4b 201
-> 261, tr1a 273 -> 296, hw3a 236 -> 269 - 906 -> 1,082 of 1,472. To month 60 every swarm row of hw4 and hw4b is
inside or next to it (hw4 month 60: plants 6 | 6.5, fuel a month 31.5k | 30.8k, fuel stock 6.7k | 6.5k, strikes 23 |
22, landings 17 | 16, sieges 3 | 2, hunts 5 | 6; month 108: plants 18 | 20, transfers 3,334 | 3,426, sieges 12 | 21,
hunts 59 | 70.5).

## 8. Still out after round 26

- **Late-war strikes and landings**: hw4 month 108 strikes 138 | 218 [154-305], landings 52 | 136 [90-190]; hw4b
  146 | 241, 60 | 147. Inside to month 60, strikes about 1.6 times and landings 2.5 times the game's after month 84.
  A landing a strike: the game 0.38-0.49, the simulator 0.61. The lead: `ThreatStrikeFGI.doCustomRaidAction` lands
  a pass only when `ThreatGroundFronts.readyToLand` says so - the strike's siege of the world is done
  (`abstractOrbitDone`) or the troops hold as they are - and `beachheadLanding` holds back a landing the first
  counter-attack would overrun; `SwarmOps.strike` lands every strike that is not broken off and has
  `LANDING_MIN_TROOPS`. Not yet read strike by strike (a pass that waits may land on a later one). Pass lines in
  the logs (several a strike): hw4 105 `waits: bombardment still has work to do`, 36 `aborted`, 70 landings, 24 reinforce;
  hw4b 74, 36, 65, 54; hw3 48, 23, 65, 21.
- **What the strikes aim at** (`Strike launched from` lines, whole runs): hw4 57 at forward bases, 103 at colonies,
  6 at pirate worlds; hw4b 35, 139, 6; hw4c 34, 102, 8. A colony strike sweeps 1.5-1.7 worlds and the runs log
  about half a landing a colony strike; a traced simulator seed sent 40, 196 and 10, one world a strike, and
  landed 0.7 a colony strike. The simulator's excess is colony strikes that land.
- **Fuel the game's swarm burns and the simulator's does not**: raiders (`ThreatRaiders`, about 100 detachments
  in hw4, 136 held for fuel) and the strikes' bombardment ordnance and razing (`ThreatGroundFronts.payOrdnance`,
  `ThreatStrikeFGI.saturationPass`). Not sized.
- **The supplies stock piles up late**: month 108 hw4 12k | 703k [427k-1,027k], hw4b 230k | 530k. The game's
  `convertSurplus` / `retireMilitary` and what its late swarm spends supplies on are not checked against the
  simulator's.
- **Worlds lost**: hw4 2 | 10 at month 108 (hw4b 6 | 8.5, inside). The landings above.
- **Forward bases destroyed early**: hw4 month 84 27 | 12 [6-16]; hw4b inside.
- **Fuel stock at month 84**: hw4 746 | 42k, hw4b 5.6k | 37k (inside at 60 and 108).
- **Months in CONSOLIDATE** and **strike travel** (section 5): not revisited.

## 9. Round 27, a rule trial: every faction mobilises when the swarm reaches a phase

Run hw4c (`war-sim-real-runs.md` 6): a faction the swarm leaves alone until phase 3 mobilises on the strike that
lands on it and has a month to arm - the Diktat lost Sindria. The trial, no mod symbol behind it:
`warsim_mobiliseAtPhase` (0 = off; `HumanSide.daily`) mobilises every faction the war does not exclude once
`SwarmPosture.phase` reaches the figure, struck or not. The simulator's strikes do not sweep, so its factions
mobilise one target at a time; its timing is the fixed game's all the same (`check` row `mobilised`, hw4c | simulator:
month 48 5 | 3.5, 60 5 | 5, 84 6 | 6, 108 7 | 7; the runs before the fix were slower, hw4b 2, 4, 4, 6), so the
trial's gain is over the jar as it stands: the first factions nine months sooner, the last two four years. 60 seeds, new game, month 104, round 26's
defaults:

| | struck first (0) | at phase 3 | at phase 2 |
|---|---|---|---|
| human worlds lost | 9 [6-10] | 5 [2-8] (clear) | 2.5 [0-6] (clear) |
| hives | 139 [106-173] | 103 [49-141] | 80 [0-121] (clear) |
| hives killed | 2 | 10 | 8 |
| sieges sailed | 9 | 35.5 (clear) | 35.5 (clear) |
| swarm strikes, landings | 255, 155 | 151, 74 (clear) | 108, 38.5 (clear) |
| threatScore | 1,995 [1,470-2,576] | 1,641 [670-2,171] | 1,275 [285-1,944] (clear) |
| humanScore | 25 [15-53.5] | 35 [25-56] (69% of seeds) | 28 [18-46] |
| contested systems | 19 | 26 | 24 |
| deadYears | 0.9 | 0.6 | 2.2 (clear) |
| outcomes: both sides / one-sided | 28% / 45% | 83% / 3% | 42% / 3%, decided for the humans 12% |

Phase 3 is the cell: worlds lost halve, the humans' p10 rises (15 -> 25), the war is two-sided in 83% of seeds
against 28%, and no seed is decided. Phase 2 is too early: the swarm is beaten in one seed in eight and the dead
years double. Neither score moves clear of the noise at phase 3 (the humans' up in 69% of seeds, the swarm's down
in 72%). A rule change, so the user's call - offered, not built in the mod.

## 11. Round 28: the user's three answers, built (2026-10-02 late)

The handover's section 3, items 1-3, answered: mobilise at phase 3 - yes; book a fuel-waiting strike's passage -
yes; only a spawned strike guarding its landing - "not intended, every faction should guard their siege on screen
or off". Built in the mod (`IncursionManager.mobiliseAtPhase`, `pickStrikeTarget`'s `waitedOn`,
`ThreatStrikeFGI.guardUnspawned`) and in the simulator as defaults (`threatinc_mobiliseAtPhase` 3,
`threatinc_strikeWaitBooksFuel` true, `warsim_strikeDefends` true). The simulator's booking now takes only a world
past the gate, as the mod's does. 60 seeds, new game, month 104, the old rules against each and all:

| | old | phase-3 mobilisation | guards | fuel booking | all three |
|---|---|---|---|---|---|
| hives | 139 [106-173] | 103 | 120 | 110 | 76 [37-100] (clear) |
| garrison FP | 149k | 112k | 107k | 107k | 52k (clear) |
| strikes | 255 | 151 (clear) | 202 | 362 | 193 |
| worlds lost | 9 | 5 (clear) | 8 | 10 | 6 |
| sieges sailed | 9 | 35.5 (clear) | 13 | 4 | 24.5 |
| hives killed | 2 | 10 | 4 | 1 | 9 |
| threatScore | 1,995 [1,470-2,576] | 1,641 | 1,590 | 1,477 | 817 [505-1,184] (clear) |
| humanScore | 25 [15-53.5] | 35 [25-56] | 30 | 24 [12.9-51] | 35.5 [21.8-56.1] |
| outcomes both sides / one-sided | 28% / 45% | 83% / 3% | 37% / 33% | 17% / 58% | 75% / 2% |

The fuel booking alone is not the free change the handover expected (section 7 had the swarm's score unmoved): with
the gate applied the plants it builds take forge slots, the garrison falls by a third, strikes rise 40% and the
humans' p10 falls under the base's. Together the swarm ends at about 40% of its old score and the humans a
third up; the war is two-sided in three seeds in four. `check` against hw4a-c is not re-run: the game runs were on
the old rules, so the new defaults are expected to miss the swarm rows until a run on this jar.

## 12. Round 29: hw4d, the guards' troops, and the colonies' real garrison (2026-10-03 night)

Run hw4d on the round-28 jar (`war-sim-real-runs.md` 7) lost 51 human worlds by month 125, 38 of them after day
3,000; `check` with the round-28 simulator put it at 9 [6-10]. Three faults, all in the simulator, and one
overshoot in the mod:

- **No hull break-up.** A guard that bombarded what it can breaks its hulls into troops while its front cannot hold
  (`ThreatGroundFronts.fabricateTroops`, 10 a point, up to the hold line x 1.05). hw4d: 4,153 FP became 41,718
  troops. Now `SwarmOps.feed`, and a guarded landing on a colony at war fights the front engine
  (`threatFrontDay`) rather than the overrun clock: `warsim_guardFeedsFront` true.
- **A guessed garrison.** The engine's colony was 15 a size plus marines; the game's is vanilla's ground defence
  (`colonyGarrison`), Sindria 3,120 against 105. Now the dump's `defence` less its armed marines (`World.garrison`,
  `Start`), `warsim_colonyGarrison` true. This is why `warsim_coloniesFall` over-predicted losses in round 26.
- **Every strike landed.** The game sizes a first landing to outlast the first counter-attack
  (`ThreatStrikeFGI.beachheadLanding`: `beachheadTroops`, the shortfall broken out of the hulls, held back if
  they cannot make it). Now in `SwarmOps.land`, `warsim_beachheadRule` true.
- **A holding front's garrison stood whole.** A Threat front that holds suppresses the world's key structures, and
  vanilla's ground defence falls with them: under a holding front a colony kept 0.26 of its pre-war garrison in the
  hw4d dumps (0.40 in hw4c; a grinding front about all of it). Now `SwarmOps.suppressed`, `warsim_frontHoldSuppress`
  0.65 off the garrison while `Swarm.Landing.holding`.
- **The guard's size** is its first pack: `warsim_guardSwarmsPerFleet` 1.42 (hw4d: 5.6 fleets a strike); about 0.20
  of a strike's FP stays (game 0.17-0.22).
- **The mod: fuel booked whole.** The waiting muster booked its whole passage each `SHORT_DAYS`; hw4d's plants
  made 63k a month against 31k spent at month 60. Now the shortfall (`ThreatFuel.heldShort`). In the simulator
  the two bookings do not differ (60 seeds, 130 months: worlds lost 42.5 against 44): its fuel piles up only after
  a collapse, with nothing left to strike.

`check`, 30 seeds (hw4d with `warsim_strikeWaitBooksWhole=true`, its jar's booking; hw4c with the round-27 rules):

| | hw4d inside | hw4d worlds lost m84 / m108 / m125 | hw4c inside | hw4c landings m108 |
|---|---|---|---|---|
| game | | 7 / 25 / 51 | | 56 |
| round 28 | 327 of 422 | 4 / 7 / 9 | | |
| + break-up and engine | 311 | 16 / 39.5 / 54 | | |
| + garrison | 304 | 9 / 20 / 40 | 278 of 378 | 143 |
| + beachhead | 340 | 9 / 22 / 41.5 | 288 | 149 |
| + holding suppresses the garrison | 341 | 13.5 / 35.5 / 53 | 288 | 152 |

With the suppression hw4d's landings and overruns fit (month 125: landings 125 | 150 [127-161], was 215; overruns
46 | 90.5, was 160; to month 96 overruns 40 | 45.5). Worlds lost now run ahead of the game from month 84 (the game
lost none from day 1,890 to 3,000, then 38 in 700 days; the simulator loses them steadily).

Still out: the strikes and landings of a run without fuel booking (hw4c month 108: strikes 127 | 241, landings
56 | 149). hw4d's strikes match (252 | 269): the booking raised the game's strikes to the simulator's, so the gap
is the old rule's fuel, not the landing pass. The other lead is the game's 118 `waits: bombardment still has work to
do` in hw4c (50 in hw4d): `doCustomRaidAction` lands only when `readyToLand` passes - the strike's own abstract siege
of the world resolved (`abstractOrbitDone`) or the troops hold as they are - and the simulator has no wait.

Options for the collapse, 60 seeds from the new game to month 130 (`r31`, before the beachhead rule): guards that
do not feed their fronts, worlds lost 10 against 44 (clear), humanScore 24 against 16; the rules before round 28,
10 against 44, humanScore 19.5 against 16, threatScore 4,045 against 4,097. The break-up rate does not matter: 5 or
2.5 troops a point both lose 39 worlds, the guards just spend more hulls.

## 10. Scripts (machine-local, `%TEMP%\threatinc-tests`)

`hivemix.pl <dump dir> <war days>` counts the hives' industries by size at the dumps nearest the days;
`unmob.pl <ti log>` lists the landings on a world whose owner was not at war and tallies both kinds; `phases.pl <dump
dir>` the war days a run changed phase; `churn.pl <ti log> [from] [to]` the posture's transfers - receivers, senders,
what returned and what passed through.
`chkrows.pl <check output> <rows> [months]` prints rows of a `check`; `dated.pl <ti log> <regex>` prints matching
lines with the war day of the last monthly dump; `landings.pl <ti log>` every Threat landing with its troops, strata
and end, and the tally by owner; `strikedays.pl <ti log> [1]` launch-to-first-pass by distance; `swarmfuel.pl <dump
dir> <from> <to>` the swarm's fuel stock and income by war day; `gates.pl <dump dir> [step]` the gate figure by
system over a run (worlds of a faction at war are skipped: their objects nest); `gate-start.pl` copies gate figures
into a start dump by market id; `r24.ps1` / `chk.ps1` run a detached `compare` / `check` to a `.done` file.
