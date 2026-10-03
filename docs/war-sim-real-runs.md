# Real runs against the simulator

What the long in-game runs showed that the offline simulator (`war-sim.md`) is checked against, run by run (sections 1-6, tr1 to hw4c, are in `war-sim-real-runs-oct2.md`). The
dumps are in `tools/warsim/validation/<run>`; the logs are machine-local. Start here before asking "does the game do
what the simulator says" - then `war-sim-swarm.md` 11 (pd9a, pd10a) and `war-council.md` 16 (pd4a-pd8a).

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

## 16. hw4m (2026-10-03 morning): 1 troop a broken-up FP

The user asked for 1:1 (2026-10-03): `threatinc_fabricateTroopsPerFP` 1 instead of 10, guards and beachheads alike,
everything else as built, on a fourteenth clone (`...ng15`, the 0387fd8 jar), to month 132. Dumps
`tools/warsim/validation/hw4m`, log `ti-hw4m.txt`.

| | m72 | m96 | m108 | m120 | m132 |
|---|---|---|---|---|---|
| human worlds lost | 0 | 3 | 9 | 11 | 33 (26 left) |
| defenders' marines | 102k | 115k | 97k | 63k | 34k |
| hives / killed | 38 / 1 | 112 / 1 | 154 / 1 | 208 / 1 | 253 / 1 |
| swarm strikes / landings | 42 / 15 | 161 / 49 | 225 / 72 | 295 / 93 | 346 / 122 |
| NPC sieges sailed | 3 | 5 | 8 | 9 | 9 |

**In the Defend-off band.** Worlds lost by the log's ground victories: 9 at month 108, 12 at 120, 26 at 126, 32 at 130.
At month 126 that is the Defend-off runs' range (21, 20, 26), against as built's 52, 39, 21, 40. It still lost 21
worlds in its last year.

**The swarm pays for its troops in hulls.** Guards broke up 15,835 FP into 15,835 troops in 109 break-ups (hw4l, at 10:
2,867 FP into 28,670), and beachheads 12,425 FP in 48 landings (hw4l 1,244 FP in 40): 28,260 FP of hulls, five to seven
times any as-built run's, for about half the troops. 72 strikes held back short of their beachhead (hw4l none; the
no-break-up runs 100-137). The swarm's garrisons stood at 103k FP at month 120 (hw4l 179k). Fronts: 55 overrun, 25
won, 28 open at the end; first counter-attacks overran 5 of 82, later ones 50 of 463 (ratio p50 1.57, p90 2.06).
Guards: 221 placed, 105 stood down with their front standing.

**What it says about the simulator.** With the 1:1 setting the check gets 280 of 466 inside; with the as-built setting
318. The simulator over-kills fronts the guards cannot feed (month 120: overruns 110 against 47, landings 163 against
93, worlds lost 31.5 against 11), as it does on Defend off (`war-sim-calibration-r29.md` "Why the Defend-off fronts
died young"). Its prediction for 1:1 (48 lost by month 132) overshot the game's 33.

## 17. hw4n (2026-10-03 midday): 5 troops a broken-up FP - a stalled swarm

The user's middle step (2026-10-03): `threatinc_fabricateTroopsPerFP` 5, everything else as built, on a fifteenth clone
(`...ng16`, the 0387fd8 jar), to month 127. Dumps `tools/warsim/validation/hw4n`, log `ti-hw4n.txt`.

| | m72 | m96 | m108 | m120 | m126 |
|---|---|---|---|---|---|
| human worlds lost | 2 | 2 | 3 | 5 | 5 (54 left) |
| defenders' marines | 100k | 141k | 139k | 119k | 118k |
| hives / killed | 21 / 2 | 89 / 2 | 125 / 2 | 166 / 2 | 175 / 2 |
| swarm strikes / landings | 40 / 16 | 96 / 16 | 135 / 36 | 178 / 50 | 194 / 56 |
| swarm forges | 16 | 21 | 21 | 21 | 21 |

**It says little about the ratio: the swarm stalled before break-up came into play.** From month 60 to 72 every
other run grew from about 20 hives to 32-39; hw4n went 18 -> 21, its fuel short (4-6k against hw4l's 19-64k; the hive
planner's `fuelprod (fuel short)` lines), while 64k-209k supplies piled up unspent and forges stopped at 21 (hw4l 39-66
by month 96-108). With few staging hives its strikes were too small for the gate: from month 72 to 96 they were
`passed over` again and again (Chicomoztoc, Eochu Bres and the Thulian Raider Base 17 times each) and ended unspawned,
and there was no landing for two years. Guards broke up 2,237 FP into 11,185 troops in 39 break-ups (hw4l 119); 1
strike held back. A stall like hw4g's late one, earlier and deeper - the swarm economy noise of `war-sim-calibration-r29.md`
"Why hw4g's swarm stalled and hw4h's did not", this time on fuel. The cause was a planner flaw, fixed the same day: fuel was tight
but not dry, so no plant came, and the invest step filled every free slot with a forge (`hive-reach-and-stock.md`
"Idle stock", `threatinc_investFuelWhenTight`).

**The simulator** (`chk-hw4n-x`, the 5:1 setting): 337 of 466 inside, every war row far above the game from month 84 (its
swarm does not stall). Its own prediction for 5 is nearly as built (`facts.md` "And 5 troops a broken-up FP?").

## 18. hw4o (2026-10-03 afternoon): 5 troops a broken-up FP and the fuel-tight plant - an early human win

5 troops a broken-up FP and `threatinc_investFuelWhenTight` on (3eb2212), on a sixteenth clone (`...ng17`), to month
140. Dumps `tools/warsim/validation/hw4o`, log `ti-hw4o.txt`.

| | m72 | m96 | m108 | m120 | m132 |
|---|---|---|---|---|---|
| human worlds lost | 1 | 1 | 1 | 1 | 1 |
| defenders' marines | 111k | 175k | 216k | 234k | 236k |
| hives / killed | 13 / 5 | 27 / 7 | 46 / 7 | 67 / 7 | 85 / 7 |
| swarm strikes / landings | 47 / 18 | 80 / 22 | 97 / 24 | 121 / 29 | 170 / 38 |
| swarm forges | 10 | 11 | 11 | 23 | 29 |

**The fuel stall is gone.** From month 30 to 120 the fuel plan reads `holds` (13k-122k in stock); the step built 8 plants
(`fuel tight`), and no strike or wave waited on fuel before month 110 (`fuel.pl`). The humans won the early war: they
eradicated seven hives by month 85, four of them in the home region: Qaras (month 48), Epsilon
Laphirial II (64), Delta Laphirial I (66), and Alpha Laphirial VI (67), the main forge and the source of most waves, which
no earlier run lost. The hive sieges came early: 99 siege slices on hives in war year 3, against 0 (hw4l) and 26 (hw4n).
The swarm founded one wave a year in years 3-5 (hw4n 1 / 1 / 18), sat on 112k-176k supplies, and lost one human world in
140 months. The run tests neither the break-up ratio (24 guard break-ups, 29 landings by month 120) nor the fix's effect
on the war.

**But the step cost a home forge.** At day 741 the step put a fuel plant on Alpha Laphirial I-B where hw4n, from the same
save, built a forge on Alpha Laphirial III (day 764). With one plant, losing the biggest always runs fuel dry, so
`wantsSpare` was no test of tightness, and the shortage answer came at month 29 in both runs anyway. The home system
ran three forges against hw4n's four until Alpha Laphirial II got one at month 42 (hw4n's fourth, on Alpha Laphirial
III, had orbital works by month 39). The swarm was already a hive behind before the step (7 against 8 at month 24, one
wave fewer in war year 2) and three behind at month 36 (14 against 17, 6.1k garrison FP against 8.0k-9.5k). How much of
that the missing forge explains is not shown; run-to-run noise is larger than one forge (hw4 against hw4b, 152 against
209 hives). Fixed the same day: `threatinc_investFuelMinPlants` 2. hw4n's stall ran on 4-6 plants against 6 hive
systems (`plants.pl` on its dumps), so the step still covers it. Simulator (`chk-hw4*-t5`, `-t5f`, `-t5g`): the one-plant
version cut the median forges at month 36 from 4.5 to 4; the two-plant rule keeps them and is otherwise as without the
step (`hive-reach-and-stock.md` "Idle stock").

## 19. hw4p (2026-10-03 afternoon): 5 troops a broken-up FP, the two-plant fuel rule - as built

The defaults since 3adaa16 (5 troops a broken-up FP, `investFuelMinPlants` 2), seventeenth clone (`...ng18`), to month
133. Dumps `tools/warsim/validation/hw4p`, log `ti-hw4p.txt`.

| | m72 | m96 | m108 | m120 | m126 | m133 |
|---|---|---|---|---|---|---|
| human worlds lost (log count) | 2 | 5 | 8 | 24 | 35 | 40 |
| defenders' marines | 84k | 109k | 90k | 51k | - | 19k |
| hives / killed | 49 / 1 | 86 / 2 | 125 / 2 | 163 / 2 | - | 202 / 2 |
| swarm strikes / landings | 72 / 31 | 172 / 57 | 233 / 91 | 296 / 120 | - | 338 / 139 |

**At month 126, 35 worlds lost: inside the as-built range** (52, 39, 21, 40 at 10 troops a point), not the Defend-off
band (21, 20, 26). That fits the simulator's prediction that 5 is close to 10. Guards broke up 8,959 FP into 44,795
troops in 215 break-ups, against 2,867-4,754 FP into 28,670-47,540 troops in 119-199 at 10. They spend two to three
times the hulls for about the same troops, and their fronts still outlast the defenders' marines (66 overrun).

**The fuel rule.** The home forges came on hw4n's days (Alpha Laphirial VI day 738, III day 765), and the fuel-tight
step fired 5 times, all from month 54 on. There was no stall: 49 hives at month 72, the most of any hw4 run, with
supplies spent (350 in stock). Fuel ran short at times and got its plants (19 `fuel short` answers). Late in the war it
piled up (593k-739k from month 126) as demand fell from 193k to 88k a month: plants built for the war's peak
outlived it.

## 20. hw4q (2026-10-03 evening): relief that holds and bombards

Today's defaults plus dfe57e6: relief stays until the Threat army is gone (`threatinc_reliefStays`) and bombards it
while the defenders are losing (`threatinc_reliefBombardPer30Days` 0.60). Everything else as hw4p (5 troops a broken-up
FP, the two-plant fuel rule), on an eighteenth clone (`...ng19`), to war day 3947 (month 131). Dumps in
`tools/warsim/validation/hw4q`; the check puts 355 of 466 figures inside. Log counts to month 126, hw4p in brackets
(`relieflife.pl`, `guardwin.pl` in `%TEMP%\threatinc-tests`):

- Worlds lost: 20 by month 120, 34 by month 126 (24, 35). Inside the as-built runs' spread (10-49 at month 120).
- Relief: 232 task forces sent, 130k FP (365, 163k). Fewer, because each one stays. Of 30 worlds taken, 11 got
  none (14 of 32); `no base in reach can provision it` is still the reason.
- How long relief stays: a median 134 days (p25 55, p75 185, p90 227, longest 417), against the old 90-day term.
  Stand-downs: 116 after the garrison overran the beachhead, 10 after the army wore away on the ground, 7 after
  the world fell, 2 after the landing was bombarded out, and 104 with the front still standing (worn below
  `defendMinStrength` and outweighed). None of those 104 worlds fell within 60 days; 27 got a new relief within 30.
- Relief bombardment: 2,522 relief-days over 40 worlds, about 12,900 troops killed for 73,500 fuel, and 2 landings
  bombarded out. One example: relief over Fikenhild took the army from 757 to 260 before the garrison overran it. As
  the army shrinks the fire falls with it: 6 fleets at 2,757 FP over Athulf killed 1.8 troops a day for 110 fuel.
- The trade-off the user asked for: the swarm's guards broke up 3,803 FP into 19,015 troops (7,593 FP into 37,965),
  half as much, because relief over the world contests the orbit for longer.
- The swarm still grew larger late: garrison 193k FP at month 120 (62k), hive size 739 (480), hives 175 (163). The
  defenders kept more marines (69k against 51k).

The simulator saw the same direction but far less bombardment (0.7-2k troops a run,
`war-sim-calibration-r29.md` "Relief that holds and bombards"). Its relief fights the guard only on arrival and runs
a fifth of its landings on the overrun clock, where nothing reaches the army.
