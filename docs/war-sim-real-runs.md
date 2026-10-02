# Real runs against the simulator

What the long in-game runs showed that the offline simulator (`war-sim.md`) is checked against, run by run. The
dumps are in `tools/warsim/validation/<run>`; the logs are machine-local. Start here before asking "does the game do
what the simulator says" - then `war-sim-swarm.md` 11 (pd9a, pd10a) and `war-council.md` 16 (pd4a-pd8a).

## 1. tr1 (2026-10-02, the docked laptop): 113 months from a three-planet home, council on

A new game on the round-20 build (shipped defaults, LunaLib marker 12), run in two legs: `tr1a` to month 50 and `tr1b`
from its quicksave to month 113 (war day 3,436). Dumps `tools/warsim/validation/tr1a` (116 monthly dumps and the map); logs
`%TEMP%\threatinc-tests\ti-tr1a.txt`, `ti-tr1b.txt`, joined as `ti-tr1.txt`. No mod exception in either leg.
`check -dumps tools\warsim\validation\tr1a -seeds 30 -log <ti-tr1.txt> -set threatinc_warCouncil=true`: 134 of 218
figures inside the simulator's p10-p90.

**The built feed shares (round 20's owed game check, done).** `Colony upkeep: ... stance <name> share <f>` reads 0.5 in
EXPAND (75 months), 0.7 in PRESS (35) and 0.5 in CONSOLIDATE (2): `feedShareConsolidate` 0.5 is what the game runs.

**The swarm.** From three worlds (section 17 of `war-sim-rounds.md`) to 23 hives at month 60, 48 at month 84, 131 at
month 108 and 168 (size 665, 204k FP of fleets) at month 113 - the upper half of the simulator's band (68.5 [8-137] at
month 108). It struck 176 times by month 108 (simulator 109 [45-251]) and spent 2 months in CONSOLIDATE where the
simulator's swarm spends 32 [11-57]: nothing the humans did made it draw in.

**The humans.** First mobilisation month 47. Cumulative at months 60 / 84 / 108 (recounted 2026-10-02 night: the first
reading took the STARVE plays' saturation expeditions for sieges and left the HAMMER plays' forces out of the hunts,
`war-sim-calibration.md` 1):

| | real |
|---|---|
| sieges sailed | 5 / 7 / 8 |
| saturation expeditions sailed | 3 / 12 / 20 |
| sieges landed | 1 / 4 / 4 |
| hunts sailed (bounty hunts and play forces) | 6 / 19 / 36 |
| hives killed | 1 / 2 / 3 |
| forward bases founded | 8 / 32 / 50 |
| forward bases destroyed | 4 / 18 / 35 |
| forward bases held | 4 / 10 / 6 |

A year, since the first mobilisation: the Threat destroys 8.2 (7.2 of them forward bases), the humans 0.5. Half the
sieges that sail land (4 of 8); of the expeditions that ended, 17 were aborted, 8 destroyed, 5 completed, and 11 sieges
were called off over the orbit. The first reading - a simulator whose humans were "five times too effective" - was the
miscount, the simulator's saturation price and three faults in its swarm; where the simulator stands against this run
now is `war-sim-calibration.md` 3.

**Guards called against a seen strike** (`guard-digest.ps1 -Tag tr1b`, months 50-113; the question of
`war-sim-rounds.md` 16a): 73 calls to a front link (`strike in reach`), 19 to a rear link (`strike on its way`), 6
guards sent behind the front, 11 guards sailing home turned back; 21 links faced a strike with no guard called, all
"cannot pay the voyage" (600-4,800 FP), and 20 more could not be guarded on later polls. About 1.4 calls a war-month;
the simulator's `warsim_reliefToBesiegers` asks 209 a run, about 2.5 a war-month, and its base has no call at all.
With the call in the game, 35 of 50 links still fell.

**What the sieges wait for** (the open thread of round 20, closed). 587 postponements in leg b, all for provisions.
577 carry a razing reserve - `Expedition postponed ... 0/22374 fuel past 240322 for razing`: they are the STARVE play's
saturation expedition (`ThreatPlays.saturate`), which razes every hive of the play in the system and so has the fuel
of each one's whole saturation set aside (`IncursionManager.expeditionFuel`, the sum of `razingFuel` over the raze set):
a median 240k fuel (34k-745k), ten times its passage and ordnance, for a flotilla of a median 3,336 FP (232-11,367).
24 saturation expeditions sailed all the same - a late pool does hold it (the League: 291k fuel at month 113) - and 123
times the play logged "no saturation expedition the pools pay". The other 10 postponements were short of supplies.
So the game's sieges are short of fuel as the simulator's are; the supplies-short postponements of pd10a were a planner
run's. The simulator prices a saturation at one world's stay (`HumanCouncil.saturate`), the game at every world of the
play.

## 2. What was done about the gap

All three proposals of the first reading were followed up the same night (`war-sim-calibration.md`):

1. *Where the simulator's humans get their kills.* The landing rate was a miscount (half the game's sieges land), and
   the simulator's swarm had three faults that fed its humans targets and time (calibration 3).
2. *The saturation's price.* The simulator now sets aside every world's whole saturation, as the game does
   (`warsim_saturationFuelSize2`); paying for the worlds the pools afford instead is noise on the humans' score, so the
   game keeps its rule (calibration 1).
3. *The hunts.* The rule is mirrored; the counter was not (36 hunts by month 108, not 18).

## 3. hw4 (2026-10-02 evening): 115 months on the new default, four home worlds, council on

A new game on the `threatinc_homeWorlds` build (4; `strikeMinSize` 4, the user's "let's see how it goes without"), one
sitting to war day 3,467. Dumps `tools/warsim/validation/hw4a` (117 months and the map); log
`%TEMP%\threatinc-tests\ti-hw4.txt`, digest `digest-hw4.txt`. No mod exception.
`check -dumps tools\warsim\validation\hw4a -seeds 30 -log <ti-hw4.txt> -set threatinc_warCouncil=true`: 115 of 218
figures inside p10-p90.

**The setting works** (`war-sim-rounds.md` 18): an 8-planet home, four landings on days 133-144, the size-4 builds on
days 495-505 as the simulator has them, the home's other four planets on days 671-710, the first claim outside on day 732.

**Four worlds against three, one run each: no difference to see.** Hives at months 12 / 24 / 36 / 48 / 60 / 84 / 108:
hw4 4 / 8 / 17 / 19 / 23 / 49 / 152, tr1 3 / 3 / 13 / 22 / 23 / 48 / 131; 171 at month 115 against 165 at month 114.
The simulator's medians are 171 [137-205] from four worlds and 68.5 [8-137] from three at month 108: tr1 ran at the top
of its band, hw4 in the lower half of its own. Two runs cannot tell the two settings apart, and nor can hw3 and hw4b
on hw4's own sector (sections 4 and 5); that three is the easier war rests on the simulator alone.

**The humans.** First mobilisation month 46. Cumulative at months 60 / 84 / 108 (recounted as section 1's):

| | real |
|---|---|
| sieges sailed | 3 / 11 / 12 |
| saturation expeditions sailed | 4 / 16 / 24 |
| sieges landed | 0 / 4 / 5 |
| hunts sailed (bounty hunts and play forces) | 5 / 33 / 59 |
| hives killed | 0 / 1 / 1 |
| forward bases founded | 13 / 36 / 57 |
| forward bases destroyed | 6 / 27 / 43 |
| forward bases held | 7 / 7 / 6 |
| human worlds lost | 1 / 1 / 2 |
| swarm strikes launched | 23 / 67 / 138 |
| swarm landings | 17 / 29 / 52 |

5 of the 12 sieges landed (27 expeditions of every kind aborted, 5 completed, 21 sieges called off over the orbit -
225-2,700 FP sailed against 605-6,499 FP of swarms). The swarm took 2 worlds by month 108 (4 by month 115, 55 of 59
standing); what it destroys is forward bases, 43 of 57. Of its 54 landings that ended, 43 were overrun by the garrison,
7 ground down and 4 took the world, all four pirate (`war-sim-calibration.md` 4). Stances: EXPAND 83 months, PRESS 27, CONSOLIDATE 4
(feed shares 0.5 / 0.7 / 0.5).

**Guards** (`guard-digest.ps1 -Tag hw4`): 57 calls to a front link, 22 to a rear link, 1 guard sent behind the front, 9
turned back; 19 links faced a strike with no guard called, all "cannot pay the voyage" (157-6,462 FP), 20 could not be
guarded on later polls. About 1.1 calls a war-month (tr1: 1.4).

**Postponements**: 428, all provisions; 406 carry a razing reserve (the STARVE play's saturation, as in tr1 - one asks
1,191k fuel past an 88k passage). Unlike tr1, supplies are short as well in 389 of them (a median 18% of the need; fuel
alone 39). 29 saturation expeditions sailed, 103 times "no saturation expedition the pools pay". Plays: 396 begun, 28
successes, 55 failures, 198 neutral.

**What it added.** The simulator's swarm took 14 worlds where the game's took 2: the question that found the three
faults of `war-sim-calibration.md` 3.

## 4. hw3 (2026-10-02 night): 120 months from three home worlds, the same sector as hw4

`threatinc_homeWorlds` 3 on a second clone of hw4's save (`...ng3`), everything else as hw4, one sitting to war day
3,605. Dumps `tools/warsim/validation/hw3a` (121 months and the map); log `%TEMP%\threatinc-tests\ti-hw3.txt`. No mod
exception. `check` (round 24's simulator): 216 of 330 figures inside p10-p90 (hw4a 223, tr1a 247).

**Three worlds against four on one sector: still nothing to see.** Hives at months 12 / 24 / 36 / 48 / 60 / 84 / 108:
hw3 3 / 3 / 14 / 20 / 30 / 54 / 113 (198 at month 120), hw4 4 / 8 / 17 / 19 / 23 / 49 / 152. The fourth world shows
for two years and is gone by the third.

**The humans.** First mobilisation month 48 (the Hegemony, struck at Eventide). Cumulative at months 60 / 84 / 108:

| | real |
|---|---|
| sieges sailed | 2 / 7 / 9 |
| saturation expeditions sailed | 0 / 5 / 12 |
| sieges landed | 0 / 3 / 4 |
| hunts sailed (bounty hunts and play forces) | 2 / 19 / 54 |
| hives killed | 0 / 1 / 2 |
| forward bases founded | 3 / 15 / 43 |
| forward bases destroyed | 1 / 4 / 21 |
| forward bases held | 2 / 5 / 10 |
| human worlds lost | 4 / 6 / 6 |
| swarm strikes launched | 13 / 41 / 92 |
| swarm landings | 14 / 29 / 45 |

The humans came later and thinner than in hw4 (the independents and Tri-Tachyon mobilised only in month 88), and the
swarm lost less to them: 21 bases destroyed for 43 founded.

**What it added: the six worlds lost.** Two were pirate and one the Path's (Epiphany, 800 troops); Salamanca, Nomios
and Agreus fell to 240-300 troops because their owners were not at war when a strike aimed at a neighbour swept them -
a bug, fixed (`war-sim-calibration.md` 4, "A swept world's owner did not mobilise").

## 5. hw4b (2026-10-02 night): hw4 again - what two runs of one setting on one sector differ by

A third clone of the same save (`...ng4`), `threatinc_homeWorlds` 4, the build that writes `gate` into the dumps, to
war day 3,408. Dumps `tools/warsim/validation/hw4b` (114 months and the map); log `ti-hw4b.txt`, digest
`digest-hw4b.txt`. No mod exception. `check`: 178 of 338 figures inside p10-p90.

| month 108 | hw4 | hw4b | hw3 (three worlds) |
|---|---|---|---|
| hives | 152 | 209 | 113 |
| garrison FP | 140k | 241k | 151k |
| human worlds lost | 2 | 6 | 6 |
| hives killed | 1 | 0 | 2 |
| sieges sailed / landed | 12 / 5 | 15 / 3 | 9 / 4 |
| saturation expeditions sailed | 24 | 22 | 12 |
| hunts sailed | 59 | 86 | 54 |
| forward bases founded / destroyed | 57 / 43 | 40 / 26 | 43 / 21 |
| swarm strikes / landings | 138 / 52 | 146 / 60 | 92 / 45 |
| first mobilisation | month 46 | month 44 | month 48 |

The same save and setting ended 171 hives and 4 worlds lost at month 115 (hw4) and 253 hives and 10 worlds lost at
month 113 (hw4b, Chicomoztoc among them, no hive killed in the whole run, 35 of 41 forward bases lost). One run of a
setting says little: the two four-world runs differ by more than either does from the three-world one. Guards
(`digest-hw4b.txt`): 45 calls to a front link, 8 to a rear link, 14 links faced a strike with no guard called
("cannot pay the voyage"). Postponements: 514, all provisions, supplies short in 510. The swarm never entered
CONSOLIDATE. Skathi fell to 200 troops with Tri-Tachyon not at war - section 4's bug again.

## 6. hw4c (2026-10-02 night): the first run with the sweep fix

A fourth clone of the save (`...ng5`), `threatinc_homeWorlds` 4, the jar of 20:00 with
`IncursionManager.onStrikeDetected` mobilising every faction in a strike's sweep, to war day 3,282 (stopped there
for time; month 109). Dumps `tools/warsim/validation/hw4c` (110 months and the map); log `ti-hw4c.txt`, digest
`digest-hw4c.txt`. No mod exception. `check`: 269 of 368 figures inside p10-p90 (hw4 256, hw4b 261).

| month 108 | hw4 | hw4b | hw4c (sweep fix) |
|---|---|---|---|
| hives | 152 | 209 | 179 |
| garrison FP | 140k | 241k | 189k |
| human worlds lost | 2 | 6 | 8 |
| hives killed | 1 | 0 | 0 |
| sieges sailed / landed | 12 / 5 | 15 / 3 | 4 / 1 |
| saturation expeditions sailed | 24 | 22 | 32 |
| hunts sailed | 59 | 86 | 69 |
| forward bases founded / destroyed | 57 / 43 | 40 / 26 | 49 / 32 |
| swarm strikes / landings | 138 / 52 | 146 / 60 | 127 / 56 |
| fuel plants / forges | 18 / 84 | 23 / 144 | 25 / 104 |
| first mobilisation | month 46 | month 44 | month 44 |

**The fix works.** The first strike that swept a second faction's world mobilised it: a strike at Kanni (pirates)
logged `luddic_path mobilised (struck at Chalcedon)` and `persean mobilised (struck at Olinadu)` on day 1,320; five
factions were at war by day 1,380 (hw4: day 1,475; hw4b: day 1,650 for four, the Path at 2,940), Tri-Tachyon on day
2,400 and the Diktat on day 3,000. No Threat landing came down on a world whose owner was not at war (`unmob.pl`: at
war, 4 taken, 5 ground down, 24 overrun; hw4b 1 of 1 not at war taken, hw3 3 of 3).

**It does not save the world the mobilising strike lands on.** The Diktat was struck for the first time on day
3,000 (a strike at Nortia sweeping five worlds of Askonia), mobilised that day with its reserve seed, and 1,419
troops landed on Sindria within the month: its counter-attack battered the beachhead once (1,065 against 824), the
next was repelled (199 against 333), and the capital (size 7) fell a stratum a month, the last on about day 3,240. A
faction the swarm leaves alone until phase 3 has a month between its first alarm and the landing. The other faction
world lost was Laicaille Habitat (Persean, at war since day 1,320) to 2,126 troops and a reinforcing pass, in the
swarm's 180-hive year; the rest were six pirate worlds and the Path's Chalcedon and Epiphany.

The run sits between hw4 and hw4b on every swarm figure - inside the noise of section 5, so it says nothing of
what the fix does to the war's course. Guards (`digest-hw4c.txt`): 61 calls to a front link, 14 to a rear link, 10
links faced a strike with no guard called. Postponements: 417, all provisions. 106 strikes ended unspawned and
re-banked.

## 7. hw4d (2026-10-02 late, overnight): the user's three rules, and the swarm wins

A fifth clone (`...ng6`), the jar of 49ca0e2: every faction mobilises at phase 3, a strike waiting on fuel books
its passage, an unspawned strike guards its landing. To war day 3,793 (month 125; the first leg stopped at 3,469 and
a second ran on from its quicksave). Dumps `tools/warsim/validation/hw4d`, log `ti-hw4d.txt`. No mod exception.

| | hw4c m108 | hw4d m108 | hw4d m125 |
|---|---|---|---|
| hives | 179 | 143 | 243 |
| garrison FP | 189k | 129k | 299k |
| human worlds lost | 8 | 25 | 51 (5 left) |
| hives killed | 0 | 5 | 5 |
| sieges sailed / landed | 4 / 1 | 13 / 5 | 13 / 5 |
| swarm strikes / landings | 127 / 56 | 252 / 111 | 295 / 125 |
| fuel plants / forges | 25 / 104 | 50 / 66 | 41 / 155 |
| swarm fuel in stock | 71k | 447k | 3,522k |
| first mobilisation | month 44 | month 35, all seven | |

**All three rules fired.** Phase 3 came on day 1,066 and mobilised all seven factions in one notice (hw4c: day
1,320 for five, the Diktat at 3,000). 196 strike guards (first on day 1,361), 264 strikes ended unspawned, 30
spawned. 143 fuel holds booked from 25 hives.

**The humans held every colony to day 3,030, then lost 38 in 700 days.** Falls by notice: six to day 1,890, none
to 3,000, then one to five a month. The Hegemony went from 12 colonies to 2, the Persean League and Tri-Tachyon to
none. What changed is the guard: it bombards, then breaks its hulls into troops while its front cannot hold
(`ThreatGroundFronts.fabricateTroops`, 10 troops a point). 217 break-ups turned 4,153 FP into 41,718 troops (hw4c:
31, 720 FP, 7,296 troops), 145 of them after day 2,900. Guarded landings ended 16 taken, 7 ground down, 41 overrun
before day 2,900, and 30 taken, 9 ground down, 2 overrun after. The fronts the guards keep alive bleed the
defenders' marines (Persean 19k on day 2,300 to 3k on 3,032, Hegemony 31k to 12k by 3,275), and once those are
gone the garrisons cannot overrun anything.

**The fuel booking overshot.** It booked the whole muster's passage, which grows with the garrison, once a
`SHORT_DAYS` per staging hive. The planner answered with plants: production 42k a month at month 48 (hw4c 12k),
stock 271k at month 60 and 3.5M at month 125, while strikes still waited. The stock also pays every guard's
bombardment (`ordnanceAvailable` reads it), so nothing ran them dry. Fixed the next morning: book only the
shortfall, the passage less the stock (`ThreatFuel.heldShort`).

**The simulator missed it, then caught it.** It had no hull break-up and its fronts used a guess of 15 garrison a
size (Sindria 105 against the game's 3,120). With both fixed (`war-sim-calibration.md` 12) it puts worlds lost at
9 / 20 / 40 at months 84 / 108 / 125 against the game's 7 / 25 / 51.
