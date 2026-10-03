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
size (Sindria 105 against the game's 3,120). With break-up, the real garrison, the beachhead rule and holding
fronts suppressing the garrison (`war-sim-calibration-r29.md`) it puts worlds lost at 13.5 / 35.5 / 53 at months
84 / 108 / 125 against the game's 7 / 25 / 51, and the defenders' marines (`humanMarines`) inside the band at every
month: 85k at month 84 to 9k at 125 in the game. 351 of 433 figures inside.

## 8. hw4e (2026-10-03 overnight): the fuel fix, and the collapse a year later

A sixth clone (`...ng7`), the jar of cbc5b2e: a waiting strike books only the stock's shortfall of its passage
(`ThreatFuel.heldShort`), a guard's share is weighted by `estimateFP`. To war day 3,814 (month 127). Dumps
`tools/warsim/validation/hw4e`, log `ti-hw4e.txt`. No mod exception.

| | hw4d m108 | hw4e m108 | hw4d m125 | hw4e m126 |
|---|---|---|---|---|
| hives | 143 | 108 | 243 | 224 |
| garrison FP | 129k | 90k | 299k | 258k |
| human worlds lost | 25 | 3 | 51 | 37 (17 left) |
| defenders' marines | 34k | 75k | 9k | 14k |
| hives killed | 5 | 3 | 5 | 3 |
| sieges sailed / landed | 13 / 5 | 12 / 5 | 13 / 5 | 12 / 5 |
| swarm strikes / landings | 252 / 111 | 222 / 73 | 295 / 125 | 323 / 128 |
| fuel plants / forges | 50 / 66 | 35 / 75 | 41 / 155 | 46 / 132 |
| swarm fuel in stock | 447k | 108k | 3,522k | 1,101k |

**The fuel fix worked.** Stock 24-36k at months 48-72 (hw4d 72-228k), plants 4-9 (9-10), and every check row of
fuel inside the band to month 72. The late stock still climbs (1.1M at month 126) because the hives that make it
outgrow what the guards burn; the simulator's does the same (3.0M median).

**The collapse came a year later, by the same road.** Phase 3 on day 1,067, all seven factions. 187 guards (built
against their share 1.00, was 1.35), 232 break-ups turned 4,996 FP into 50,089 troops. Three worlds fell to day
1,650, none to day 3,240, then 37 in 570 days, as the defenders' marines went from 103k at month 96 to 14k at 126.
Guarded landings ended 5 taken, 7 ground down, 27 overrun before day 2,900 and 31 taken, 17 ground down, 16 overrun
after. Without the guards' troops the fronts are overrun; with them they outlast the marines.

**Why a year later: the swarm consolidated.** Its garrisons were short of want (day 1,714: held 15.6k, want
22.7k), so `ThreatStance` held CONSOLIDATE for 13 months by month 72 (the simulator 4.6, p90 14.5). Under it only
forward bases are struck: 42 of the 72 strikes launched in months 60-84 went at forward bases, which nothing lands
on, and 10 of the 72 landed (the simulator lands about half). Hives grew to 25 at month 72 against 51 in hw4d. The
simulator, which mostly presses, loses worlds a year early: 32.5 at month 108 against 3, inside again from month
120 (47.5 against 21, 52.5 against 37). 344 of 433 figures inside. Strikes per launch year by target, and landings
per strike, come from `sl.pl` (machine-local, below the handover).

## 9. hw4f (2026-10-03 overnight): no hull break-up at all

A seventh clone (`...ng8`), hw4e's jar, `threatinc_fabricateEnabled` false: no guard breaks hulls, and a strike
short of its beachhead holds back. To month 133. Dumps `tools/warsim/validation/hw4f`, log `ti-hw4f.txt`.

| | m72 | m96 | m108 | m120 | m133 |
|---|---|---|---|---|---|
| human worlds lost | 4 | 7 | 8 | 8 | 26 |
| defenders' marines | 114k | 139k | 149k | 105k | 63k |
| hives / killed | 36 / 5 | 72 / 8 | 84 / 9 | 115 / 9 | 164 / 9 |
| swarm strikes / landings | 46 / 16 | 127 / 34 | 181 / 46 | 263 / 74 | 332 / 99 |
| NPC sieges sailed | 7 | 16 | 17 | 17 | 17 |

**Two-sided until month 120, then the same decline, slower.** Eight worlds lost by month 120, 18 more in the last
13 months as the marines fell from 149k to 63k. 137 strikes held back (one a world and five days), none of them
landing on that world within 45 days; nine hives killed, the most of any hw4 run.

**What it showed the simulator.** It held back about twice as often as the game, which sent the hunt for the
cause to the beachhead's need (`war-sim-calibration-r29.md`, "Beachheads and landing troops"): the game sizes it
after bombardment, and its strikes carry about half the simulator's troops. The NPC sieges stay out: 17 in the
game, 41-52 in the simulator, not traced.

## 10. hw4g (2026-10-03 overnight): break-up for beachheads only

An eighth clone (`...ng9`), the jar of 0387fd8 with `threatinc_fabricateDefendEnabled` false: a Defend fleet holds
orbit and bombards but never breaks hulls; a strike short of its beachhead still does. To month 134. Dumps
`tools/warsim/validation/hw4g`, log `ti-hw4g.txt`.

| | m72 | m96 | m108 | m120 | m134 |
|---|---|---|---|---|---|
| human worlds lost | 3 | 5 | 7 | 16 | 22 (36 left) |
| defenders' marines | 105k | 112k | 94k | 77k | 71k |
| hives / killed | 35 / 3 | 79 / 3 | 107 / 3 | 135 / 3 | 167 / 3 |
| swarm strikes / landings | 37 / 10 | 123 / 44 | 188 / 74 | 239 / 90 | 298 / 109 |
| NPC sieges sailed | 8 | 11 | 11 | 11 | 11 |

**The fewest worlds lost of the four.** 42 beachheads broke up 1,443 FP into 14,602 troops (hw4e's guards: 4,996
FP into 50,089); two landings held back. The marines end at 71k, the most of any run, and the sector is still
two-sided at month 134.

**The simulator cannot judge this lever.** For hw4g's settings it loses 50.5 worlds by month 134 and lands 246
times against 109: its strikes muster 1.5-2 times the game's swarms late in the war, and it lands on 84% of its
colony strikes against the game's about half (the game's 212 colony strikes ran 110 abstract sieges; 41 of 270
arrival fights were lost outright, and the rest of the gap was not traced). The game runs, not the simulator, are
the evidence for the break-up decision.

## 11. hw4h (2026-10-03 overnight): beachheads only, again

A repeat of hw4g's setting on a ninth clone (`...ng10`, same jar, `threatinc_fabricateDefendEnabled` false), to
month 134. Dumps `tools/warsim/validation/hw4h`, log `ti-hw4h.txt`.

| | m72 | m96 | m108 | m120 | m134 |
|---|---|---|---|---|---|
| human worlds lost | 2 | 3 | 3 | 7 | 33 (26 left) |
| defenders' marines | 94k | 133k | 136k | 117k | 61k |
| hives / killed | 34 / 3 | 85 / 4 | 121 / 4 | 190 / 4 | 305 / 4 |
| swarm strikes / landings | 63 / 20 | 133 / 35 | 179 / 45 | 251 / 78 | 319 / 105 |
| NPC sieges sailed | 5 | 11 | 11 | 11 | 11 |

**The same setting, a different war.** hw4h held longer than hw4g (7 worlds lost at month 120 against 16), then lost
26 in the last 14 months. Its swarm did not stop growing as hw4g's did: at month 134 305 hives against 167, hive size
1,319 against 450, a 366k garrison against 59k, 360k supplies in stock against 7k. 42 beachheads broke up 1,955 FP
into 19,707 troops (hw4g 1,443 FP).

**What it says about the simulator.** The runaway swarm of `war-sim-calibration-r29.md` ("Fronts, the gate and the
runaway swarm") is inside the game's own spread: hw4h's month-134 swarm (hives 305, size 1,319, garrison 366k) sits
on the simulator's medians (293, 1,482, 389k), hw4g's far under them. Landings and overruns stay out on both runs:
the simulator lands 246 times by month 134 against 105-109 and overruns 187 beachheads against 42-62. Check: 337 of
514 inside.

## 12. hw4i (2026-10-03 overnight): no break-up, again

A repeat of hw4f's setting on a tenth clone (`...ng11`, the 0387fd8 jar, `threatinc_fabricateEnabled` false), to
month 130. Dumps `tools/warsim/validation/hw4i`, log `ti-hw4i.txt`.

| | m72 | m96 | m108 | m120 | m130 |
|---|---|---|---|---|---|
| human worlds lost | 4 | 9 | 15 | 31 | 42 (17 left) |
| defenders' marines | 96k | 126k | 106k | 41k | 35k |
| hives / killed | 40 / 3 | 84 / 4 | 116 / 4 | 164 / 4 | 264 / 4 |
| swarm strikes / landings | 55 / 21 | 154 / 44 | 225 / 72 | 296 / 104 | 317 / 112 |
| NPC sieges sailed | 5 | 7 | 8 | 8 | 8 |

**No break-up is no safer.** hw4f lost 8 worlds by month 120 and 26 by month 133 on this setting; hw4i lost 31 by
month 120 and 42 by month 130, more than either beachheads-only run. Its swarm grew as hw4h's did (264 hives, a 300k
garrison, 143 forges at month 130), the defenders' marines fell from 126k at month 96 to 35k, and a strike short of
its beachhead held back 100 times (hw4f 137). Fronts behaved as in hw4f: first counter-attacks overran 2 of 72,
overrun fronts took a median 6 counter-attacks; it won 32 fronts against hw4f's 21.

**What it says about the simulator.** Check 307 of 466 inside. Its worlds lost at month 130 (42) sit just above the
simulator's p90 (34 [19.3 - 41.3]), beachheads overrun under its p10 (36 against 81 [52 - 109]), NPC sieges far
under (8 against 35.5). With hw4f the two runs span 26-42 worlds lost on one setting.

## 13. hw4j (2026-10-03 overnight): as built, again

A third run of the default setting on an eleventh clone (`...ng12`, the 0387fd8 jar, both break-ups on), to month
133. Dumps `tools/warsim/validation/hw4j`, log `ti-hw4j.txt`.

| | m72 | m96 | m108 | m120 | m133 |
|---|---|---|---|---|---|
| human worlds lost | 1 | 3 | 5 | 10 | 37 (22 left) |
| defenders' marines | 114k | 159k | 136k | 108k | 35k |
| hives / killed | 32 / 1 | 72 / 3 | 110 / 3 | 179 / 3 | 268 / 3 |
| swarm strikes / landings | 53 / 18 | 121 / 40 | 197 / 55 | 283 / 91 | 355 / 129 |
| NPC sieges sailed | 4 | 6 | 6 | 6 | 6 |

**As built is not doomed by month 120 either.** hw4d had lost 49 worlds by month 120, hw4e 21, hw4j 10; counted
from the ground victories in the logs, by month 126 the three stood at 52, 39 and 21 - and the beachheads-only runs
(hw4g, hw4h) at 21 and 20, no break-up (hw4f, hw4i) at 21 and 42. hw4j then lost 27 worlds in its last 13 months,
as hw4h lost 25 in its last 14: on every setting the sector can go late. Its guards broke up 4,754 FP of hulls into
47,540 troops in 183 break-ups (hw4d 3,226 FP in 171), beachheads 1,870 FP in 52 landings; 4
strikes held back. Fronts as in every run: first counter-attacks overran 3 of 88, later ones 53 of 527 (ratio p50
1.60, p90 2.03); 56 fronts overrun, 29 won.

**What it says about the simulator.** Check 392 of 514 inside, its best this round. Worlds lost sit under the
simulator's p10 at every month (month 120: 10 against 46 [28.9 - 52]; month 133: 37 against 54 [41.8 - 56.1]), and
the defenders' marines over its p90 (35k at month 133 against 3.5k [1.9k - 19.5k]); strikes, overruns, hives and NPC
sieges inside. With hw4d (on the simulator's median) and hw4e (under it), the game's spread on one setting is wider
than the simulator's.

## 14. hw4k (2026-10-03 overnight): beachheads only, a third time

A third run of the Defend-off setting on a twelfth clone (`...ng13`, the 0387fd8 jar, `threatinc_fabricateDefendEnabled`
false), to month 127. Dumps `tools/warsim/validation/hw4k`, log `ti-hw4k.txt`.

| | m72 | m96 | m108 | m120 | m127 |
|---|---|---|---|---|---|
| human worlds lost | 3 | 4 | 6 | 11 | 26 (33 left) |
| defenders' marines | 104k | 99k | 96k | 74k | 39k |
| hives / killed | 39 / 1 | 75 / 4 | 107 / 4 | 195 / 4 | 239 / 4 |
| swarm strikes / landings | 58 / 22 | 144 / 52 | 209 / 72 | 281 / 114 | 325 / 136 |
| NPC sieges sailed | 5 | 19 | 21 | 21 | 21 |

**The same shape as hw4h.** It held to 11 worlds lost at month 120, then lost 15 in seven months as the defenders'
marines fell from 74k to 39k. At month 126 (the logs' ground victories) the three Defend-off runs stood at 21, 20 and
26, against 52, 39 and 21 as built and 21 and 42 with no break-up. Beachheads broke up 1,633 FP of hulls in 54 landings
and 12 strikes held back. Fronts: first counter-attacks overran 7 of 96, later ones 55 of 457 (ratio p50 1.63, p90
2.09); 62 fronts overrun, 20 won. NPC sieges sailed 21, more than any other run (hw4g 11).

**What it says about the simulator.** Check 343 of 466 inside (the Defend-off setting). Worlds lost at month 127 sit
inside (26 against 37.5 [12.9 - 49]), as do landings (136 against 210 [108 - 269]), hives and marines; beachheads overrun
stay under the p10 (62 against 156 [88 - 212]): the Defend-off gap of `war-sim-calibration-r29.md`.

## 15. hw4l (2026-10-03 overnight): as built, a fourth time

A fourth as-built run on a thirteenth clone (`...ng14`, the 0387fd8 jar, every knob as shipped), to month 132.
Dumps `tools/warsim/validation/hw4l`, log `ti-hw4l.txt`.

| | m72 | m96 | m108 | m120 | m132 |
|---|---|---|---|---|---|
| human worlds lost | 2 | 2 | 6 | 21 | 43 (16 left) |
| defenders' marines | 87k | 94k | 78k | 48k | 7k |
| hives / killed | 34 / 1 | 74 / 1 | 105 / 1 | 144 / 1 | 272 / 1 |
| swarm strikes / landings | 67 / 25 | 151 / 48 | 220 / 72 | 285 / 102 | 315 / 118 |
| NPC sieges sailed | 3 | 4 | 4 | 5 | 5 |

**The as-built shape again, late.** Two worlds lost at month 96, 21 at month 120, 40 at month 126 (the log's ground
victories) and 43 at month 132, as the defenders' marines fell from 78k to 7k in two years. At month 126 the four
as-built runs stand at 52, 39, 21 and 40 (median 39.5), the three Defend-off runs at 21, 20 and 26. Guards broke up
2,867 FP of hulls into 28,670 troops in 119 break-ups, beachheads 1,244 FP in 40 landings; no strike held back. Fronts:
first counter-attacks overran 1 of 83, later ones 49 of 508 (ratio p50 1.56, p90 2.07); 50 fronts overrun, 35 won.
NPC sieges sailed 5, the fewest of any run, and the humans killed one hive. Guards: 200 placed, 117 stood down with
their front standing, a median 26 days after placement; of the fronts overrun, 31 had lost their guard over a month
before and 12 never had one (`war-sim-calibration-r29.md` "The guards go home").

**What it says about the simulator.** Check 338 of 466 inside. The simulator loses worlds a year early here (the game sits under
its p10 from month 84 to 120: month 96 2 against 21.5 [12 - 28], month 120 21 against 46 [28.9 - 52]) and is level by the end (43 against 53 [39.9 - 56.1]);
its marines fall sooner (month 108: 78k against 37k). Landings and overruns sit inside to month 132 (118 against 131,
50 against 68.5).
