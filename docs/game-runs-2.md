# Game test runs, from hw11 (2026-10-05)

Continues `game-runs.md` (sections 1-8: how a run is made, the digests, hw6-hw10). Section numbers carry on.

## 9. hw11a-hw11c (2026-10-05, on a second machine): the rally reads the cover over a landed army

Build `e5ce9b9` on shipped defaults (`systemDefence` on, margin 1.25, `strikeSeenBySystem` on, `postureMass`
off). 33 minutes of fast-forward, no exception, no council error. **The rally now reaches a covered world, but
to one landing in five and with too little: the covers fall no more often than in hw10 and the hives die as
before.** Two runs extinct, one at 3 hives and falling; all three had 78-93% of their hives found at m48, the
band that died in hw8 and hw10.

| | hw11a | hw11b | hw11c | hw10a / b / c |
|---|---|---|---|---|
| extinct | m81 | never (3 hives at m123) | m78 | never (175) / m93 / m74 |
| hives m60, m84, m108 | 11, 0, 0 | 14, 25, 18 | 6, 0, 0 | 25, 64, 124 / 25, 3, 0 / 7, 0, 0 |
| hives the humans had found at m48 | 14 of 16 | 14 of 18 | 13 of 14 | 9 of 14 / 15 of 17 / 13 of 15 |
| landings left under cover (median cover) | 19 (1,362 FP) | 45 (1,137 FP) | 21 (688 FP) | 16 (924) / 31 (1,493) / 21 (1,312) |
| landings a rally reached while the cover stood | 3 | 11 | 0 | - |
| rallied to a world while its cover stood | 11 fleets (3.2k FP) | 32 (4.7k FP) | 0 | 1 (115 FP) / 1 (100 FP) / 0 |
| other transfers to it meanwhile | 19 (2.9k FP) | 256 (27.6k FP) | 8 (1.0k FP) | 139 (16.4k) / 36 (5.1k) / 15 (1.7k) |
| covers the swarm outweighed (median days after the landing) | 1 (57) | 8 (28) | 3 (25) | 7 (27) / 3 (38) / 0 |
| landings ended: hive eradicated, army overrun | 18, 1 | 42, 2 | 17, 4 | 14, 2 / 30, 1 / 16, 5 |
| of the covers lost: hive eradicated anyway, army overrun | 0, 1 | 8, 0 | 1, 2 | 6, 1 / 3, 0 / - |
| sieges down, called off | 75, 7 | 263, 61 | 55, 4 | 432, 244 / 146, 34 / 60, 12 |
| sieges ended `front` (FP down) | 22 (43.1k) | 31 (45.7k) | 7 (9.5k) | not read |
| fleets rallied in all (FP) | 153 (33.8k) | 1,333 (253.6k) | 76 (14.6k) | 2,902 (501k) / 603 (126k) / 164 (31k) |
| Threat FP lost a human FP | 1.15 | 1.31 | 1.40 | 1.25 / 1.35 / 1.31 |
| seeding swarms, strikes, since found | 3, 4 | 41, 27 | 0, 1 | 221, 173 / 26, 20 / 1, 2 |
| human worlds lost | 1 | 0 | 1 | 2 / 0 / 1 |

Against the handover's six questions (`handover-2026-10-05-testing.md` 6):

1. **The rally reaches a covered world, rarely.** 11 / 32 / 0 fleets against 1 / 1 / 0, and the `Posture: X
   rallied` lines name worlds with an army on them - but 3 of 19 / 11 of 45 / 0 of 21 landings drew any, and
   the ordinary transfers still carry more (hw11b 27.6k FP against 4.7k). Read from `rally`, not yet measured
   in a log: a donor with a siege over it, an army on it or a hostile fleet near it gives nothing, and the
   humans land on a system's hives together (hw11a, Alpha Laphirial: nine landings between day 1578 and day
   2158, each standing 81-750 days), so the worlds that could give are the ones excluded. hw11c had 734 FP of
   fleets left at m60 and nothing to send.
2. **The swarm does not take the orbit back more often.** 1 of 19 / 8 of 45 / 3 of 21 covers outweighed, a
   median 57 / 28 / 25 days after the landing (hw10: 7 of 16 / 3 of 31 / 0 of 21 at 27-38 days).
3. **It does not save the hive.** Of the 12 covers lost, 9 hives were eradicated anyway and 3 armies overrun;
   hw11b lost 8 covers and all 8 hives. Armies overrun 1 / 2 / 4 of the landings (hw10 2 / 1 / 5). So both
   links are short: the rally rarely arrives, and a lost cover rarely turns the ground (`tickSwarmBombard`,
   `swarmFrontBombardPer30Days` 0.60).
4. **The war** follows the found share, as in hw8 and hw10: 88% / 78% / 93% found at m48, extinct m81 / 3
   hives at m123 / extinct m78. No run fell in the 41-64% band that survived before, so the batch says nothing
   about a runaway. hw11b recovered to 38 hives at m89 with 18-23 of 25-31 found, then fell.
5. **No side effect seen.** Sieges called off 9% / 23% / 7% (hw10 56% / 23% / 20%): the cover rally did not
   starve the defence against the next siege - it sent 3.2k and 4.7k FP in whole runs.
6. **No exception, no council error** (exceptions file 270 bytes, vanilla's noise, on this machine too).

**Proposed, the user's call (nothing built).**

- Let a world with an army on it or a siege over it still give its spare to a neighbour whose cover it can
  outweigh, or count the system's covers together and mass on the lightest first. As built the rule that
  protects a donor empties the pool exactly when the system is being taken world by world.
- What a regained orbit is worth: 9 of 12 hives fell after the cover was lost. The bombardment rate and the
  hive's counter-attack are the figures to look at before more fleets are sent to win an orbit that does not
  save the world.
- A log line when `rally` finds the world short and the pool empty (which donors were excluded and why), so
  the first proposal is measured rather than read from the code. This one is instrumentation only.

**The harness on a second machine.** The first launch failed at the launcher (`Error loading settings`):
`run-settings.ps1` wrote the prefs value `gameplay/Settings` empty on a machine that had never had one. Fixed:
it is edited only where it exists, and `restore-settings.ps1` removes it when the backup has none. On such a
machine vanilla autosave is left as it is; a clone is deleted at the end either way. Three games ran in 6.4 GB
of free memory at 113 days a minute each.

## 10. hw12a-hw12c (2026-10-05): rally only if enough; troops a broken-up FP 10; forge output doubled

Build `72724b4` (`systemDefenceOnlyIfEnough` on) in all three; hw12b `fabricateTroopsPerFP` 10 (set 10.01:
a store at version 11 migrates an exact 10 back to 5), hw12c `fabFPPerShipUnit` 200. No exception, no council
error. **None of the three saves the swarm.**

| | hw12a (rule) | hw12b (+ troops 10) | hw12c (+ forges x2) | hw11a / b / c |
|---|---|---|---|---|
| extinct | m95 | never (2 hives at m120) | never (5 unfound at m120) | m81 / never (3) / m78 |
| hives m60, m84, m108 | 16, 6, 0 | 14, 17, 12 | 23, 22, 5 | 11, 0, 0 / 14, 25, 18 / 6, 0, 0 |
| found at m48 | 14 of 18 | 15 of 17 | 11 of 18 | 14 of 16 / 14 of 18 / 13 of 14 |
| swarm income since found | 95k FP | 282k | 369k | 47k / 318k / 27k |
| sieges arrived (FP), escorts (FP) | 114 (170k), 53 (152k) | 263 (425k), 96 (229k) | 254 (553k), 107 (284k) | 75 (106k), 36 (167k) / 263 (355k), 109 (509k) / 55 (56k), 28 (62k) |
| sieges called off | 17 | 110 | 135 | 7 / 61 / 4 |
| Threat FP lost a human FP | 1.37 | 1.36 | 1.29 | 1.15 / 1.31 / 1.40 |
| rallies sent, declined (`no rally`) | 222, 526 | 589, 1,245 | 743, 398 | 153 / 1,333 / 76, - |
| covers lost, of landings; those hives eradicated anyway | 5 of 32; 1 | 4 of 45; 3 | 11 of 37; 9 | 1 of 19; 0 / 8 of 45; 8 / 3 of 21; 1 |
| seeding swarms, strikes since found (recalled, all) | 12, 7 (9) | 42, 39 (35) | 43, 51 (48) | 3, 4 (2) / 41, 27 (25) / 0, 1 (1) |
| Threat landings, human worlds lost | 6, 0 | 5, 1 | 7, 0 | 6, 1 / 4, 0 / 6, 1 |

- The rule declines 398-1,245 rallies a run and changes no outcome that three runs can show.
- Troops at 10 has nothing to act on: the swarm lands 5-7 times a run, and 35 of 40 strikes are called home.
- Doubled forges gave 70k FP of fleets at the peak and four times hw12a's income; the humans sent 553k FP of
  sieges, 135 of 254 turned away with 97% of their FP (section 8, "a massed defence saves the world and kills
  nothing"), the trade stayed 1.29, and 48 of 54 strikes were recalled (108k of 127k FP). It fell from 22
  hives at m84 to 5 at m108.
- Common to all nine runs of hw10-hw12: the swarm loses 1.15-1.40 FP a human FP over its own worlds, an
  outweighed siege leaves free, and the strikes turn home within a median 2-5 days.

## 11. hw13a-hw13c (2026-10-05): the strike recall off

Build `72724b4`, `postureRecallLY` 0 in all three, nothing else changed (5 troops a broken-up FP, forges 100).
No exception, no council error. **Worse or the same: extinct m74, m63, and 1 hive from m84.**

| | hw13a | hw13b | hw13c | hw12a, recall on |
|---|---|---|---|---|
| extinct | m74 | m63 | never (1 hive from m84) | m95 |
| hives m48, m60, m72 | 19, 3, 1 | 12, 1, 0 | 17, 10, 2 | 18, 16, 17 |
| found at m48 | 17 of 19 | 12 of 12 | 15 of 17 | 14 of 18 |
| strikes since found (FP), recalled | 4 (8.6k), 0 | 4 (7.5k), 0 | 7 (7.2k), 0 | 7 (6.1k), 9 |
| Threat landings, overrun or destroyed | 12, 10 | 6, 5 | 7, 6 | 6, 6 |
| human worlds lost | 2 | 1 | 1 | 0 |
| sieges arrived (FP) | 45 (68k) | 56 (66k) | 73 (84k) | 114 (170k) |
| Threat FP lost a human FP | 1.43 | 1.14 | 1.31 | 1.37 |
| seeding swarms since found | 1 | 3 | 2 | 12 |

- The strikes sail and take 1-2 worlds a run; 21 of 25 landings are overrun or destroyed, and the 7-9k FP
  sent is not at home when the sieges come: 3 hives at m60 in hw13a (16 in hw12a), 1 in hw13b.
- It launches no more strikes than with the recall on (4 / 4 / 7 since found): the gate passes over most
  targets (`Strike gate: X passed over, defence N against a strike of N`).
- The humans' sieges are not drawn off: 45-73 arrive in the 20-40 months the swarm lasts.
- Found 88-100% at m48, the dying band, so three runs do not show the recall saves a swarm; they show that
  removing it does not.

## 12. hw14a-hw14c (2026-10-05): the Nexus off the tactical target list, its defence bonus gone

**Build `d53ecf9` did not do what the heading says.** Its `nexusBombardable` gate went into HIVE `keyStructures`
(what a holding ground front suppresses), not `fortifications` (what tactical bombardment wears). So this batch
tested: no Nexus defence bonus, a holding army no longer suppresses the Nexus, and **tactical bombardment still
puts it down** (hw14a: 15-20 days after the first bombing day, e.g. Epsilon Laphirial IV bombed day 1367, Nexus
down day 1384, landed day 3188). The gate was moved to `fortifications` afterwards; that build has no run yet.
The rest shipped defaults, rally only if enough on. No council error; the exceptions files are vanilla's two hull-spec lines. **No run extinct: 4 hives
and fading, 29, and 52 and growing at m120** - the first batch with a swarm alive in all three. The found share at
m48 is lower than in hw11-hw13 (67% / 72% / 25% against 78-100%): hw14c sits in the band that ran away before
(hw10a 64%), hw14a and hw14b between the bands, so the batch does not separate the change from the share.

| | hw14a | hw14b | hw14c | hw11a / b / c |
|---|---|---|---|---|
| hives m60, m84, m108, m120 | 30, 21, 7, 4 | 19, 29, 35, 29 | 20, 41, 44, 52 | 11, 0, 0, 0 / 14, 25, 18, 6 / 6, 0, 0, 0 |
| found at m48 | 14 of 21 | 13 of 18 | 4 of 16 | 14 of 16 / 14 of 18 / 13 of 14 |
| hives able to earn, m52 and m60 | 25 of 25, 21 of 30 | 16 of 21, 11 of 19 | 12 of 16, 18 of 19 | 6 of 16, 2 of 10 (hw11a) |
| swarm income since found | 268k FP | 372k | 705k | 47k / 318k / 27k |
| sieges arrived (FP) | 287 (329k) | 301 (400k) | 458 (698k) | 75 (106k) / 263 (355k) / 55 (56k) |
| sieges called off | 73 | 92 | 215 | 7 / 61 / 4 |
| human landings, hives eradicated, armies overrun | 52, 39, 11 | 49, 39, 9 | 46, 32, 13 | 19, 18, 1 / 45, 42, 2 / 21, 17, 4 |
| Threat FP lost a human FP | 1.37 | 1.32 | 1.33 | 1.15 / 1.31 / 1.40 |
| seeding swarms, strikes since found | 28, 42 | 57, 41 | 110, 91 | 3, 4 / 41, 27 / 0, 1 |
| Threat landings, human worlds lost | 12, 2 | 5, 0 | 2, 0 | 6, 1 / 4, 0 / 6, 1 |

- More hives can earn at m52-m60 than in hw11a, but not because the Nexus is spared: bombardment still downs it
  (`organs.pl`; longest read 277-286 days, the 300 bombardment cap, no longer the front's 360). The cause is not
  established: fewer hives were found by m48, and most of hw14a's were first bombed late (around day 1699).
- The swarm rebuilds what it loses: 1,512 / 2,165 / 3,418 swarms built since found (hw11: 186 / 1,822 / 137),
  28 / 57 / 110 seeding swarms, and the hives overrun 9-13 armies a run (hw11: 1-4) although their ground
  defence is a third lower; why is not established.
- It still trades at 1.32-1.37 over its own worlds and takes 0-2 human worlds: it survives by out-seeding its
  losses, not by winning fights. hw14a's income failed from m96 (8 of 15 able to earn, 0 of 6 at m112).
- hw14c is growing at m120 (52 hives, 54k FP of fleets, 458 sieges absorbed): whether that is a runaway needs
  a longer run.

## 13. hw15a-hw15c (2026-10-05): the Nexus really off the tactical target list

Build `c16e063` (the gate in HIVE `fortifications`; `nexusDefenseBonus` 0; a holding front suppresses the Nexus
again). No council error; the exceptions files are vanilla's two hull-spec lines. `organs.pl` confirms the
change: a bombed hive's Nexus stays up until an army lands (hw15a Epsilon Laphirial IV bombed day 1397, landed
2320, Nexus down 2345), and hives bombed since days 1500-1800 still earn at m90. **No run extinct; two of three
run away: 19, 100 and 120 hives at m120.**

| | hw15a | hw15b | hw15c | hw14a / b / c |
|---|---|---|---|---|
| hives m60, m84, m108, m120 | 27, 37, 24, 19 | 20, 50, 70, 100 | 20, 46, 105, 120 | 30, 21, 7, 4 / 19, 29, 35, 29 / 20, 41, 44, 52 |
| found at m48 | 7 of 21 | 13 of 19 | 13 of 18 | 14 of 21 / 13 of 18 / 4 of 16 |
| fleets FP at m120 | 27.8k | 114.2k | 177.3k | |
| swarm income since found | 540k FP | 887k | 1,050k | 268k / 372k / 705k |
| sieges arrived (FP) | 415 (604k) | 474 (856k) | 521 (773k) | 287 (329k) / 301 (400k) / 458 (698k) |
| sieges called off | 151 | 273 | 322 | 73 / 92 / 215 |
| human landings, hives eradicated, armies overrun | 66, 58, 7 | 48, 22, 24 | 43, 34, 7 | 52, 39, 11 / 49, 39, 9 / 46, 32, 13 |
| Threat FP lost a human FP | 1.42 | 1.30 | 1.28 | 1.37 / 1.32 / 1.33 |
| seeding swarms, strikes since found | 93, 76 | 159, 124 | 198, 215 | 28, 42 / 57, 41 / 110, 91 |
| Threat landings, human worlds lost | 5, 0 | 4, 0 | 5, 1 | 12, 2 / 5, 0 / 2, 0 |

- The found share no longer decides it: hw15b and hw15c were 68-72% found at m48, the band that died in
  hw11-hw13, and both grow without pause from m60 (EXPAND 75-77 of 82-83 months since found).
- Income since found is two to four times hw14's on like shares, and the humans answer with more sieges
  (415-521) of which 36-62% are called off.
- The swarm still does not win fights (1.28-1.42 over its own worlds) and takes 0-1 human worlds in ten years
  with 76-215 strikes: it grows by seeding (93-198 swarms), not by conquest.
- hw15a, the least found at m48, is the one in decline (39 hives at m83, 19 at m120, 58 eradicated): the humans
  there landed 66 times and lost 7 armies.
- A check at m55-m60 already showed the change (Nexus up under bombardment); only the end state needed m120.

## 14. hw16a-hw16c (2026-10-05): the strike recall off on the hw15 build

Build `c16e063`, `postureRecallLY` 0 (checked in each game's LunaLib store). No council error. **The swarm dies
again: 2 hives at m120, extinct m99, extinct m67** (hw15: 19, 100, 120 hives), and takes 2, 2 and 1 human worlds
(Qaras and Kanta's Den twice, Kapteyn Starworks).

| | hw16a | hw16b | hw16c | hw15a / b / c |
|---|---|---|---|---|
| hives m48, m60, m72, m84 | 16, 16, 8, 2 | 17, 15, 8, 3 | 16, 5, 0, 0 | 21, 27, 35, 37 / 19, 20, 30, 50 / 18, 20, 44, 46 |
| found at m48 | 12 of 16 | 14 of 17 | 14 of 16 | 7 of 21 / 13 of 19 / 13 of 18 |
| strikes since found (FP) | 12 (15k) | 17 (13k) | 6 (5k) | 76 / 124 / 215, 90-94% recalled |
| Threat landings, overrun, human worlds lost | 17, 14, 2 | 14, 12, 2 | 9, 6, 1 | 5, 5, 0 / 4, 4, 0 / 5, 4, 1 |
| guards left over landings (FP), hulls broken up | 31 (12.8k), 60 FP | 29 (9.9k), 72 FP | 28 (10.9k), 0 | 16, 0 / 10, 0 / 18, 51 FP |
| relief forces (FP) | 21 (33.6k) | 18 (19.8k) | 8 (16.5k) | 9 (10.7k) / 5 (10.6k) / 5 (12.9k) |
| fleets rallied to the defence | 207 (50k FP) | 184 (44k) | 119 (23k) | 834 (197k) / 3,191 (606k) / 2,207 (416k) |
| seeding swarms since found | 6 | 7 | 2 | 93 / 159 / 198 |
| sieges arrived, human landings, hives eradicated | 133, 20, 18 | 109, 24, 22 | 66, 17, 17 | 415, 66, 58 / 474, 48, 22 / 521, 43, 34 |
| Threat FP lost a human FP | 1.36 | 1.42 | 1.33 | 1.42 / 1.30 / 1.28 |

- Every strike arrives and three times as many landings are made, but 32 of 40 are overrun: relief outweighs the
  guards about 2 to 1 (33.6k FP of relief against 12.8k of guards in hw16a) and the guards break up 0-72 FP.
- What sails does not come back to defend: the hives halve between m60 and m72 in all three, with a fifth of
  hw15's rallies. This is hw13's result on a build whose Nexus is safe from bombardment.
- hw15's 76-215 strikes were the same swarms launched and recalled over and over; let go, a strike is made 6-17
  times a run. Most are 270-800 FP at 27-41 ly; three in hw16a were 4.6-5.0k FP.
- The found share at m48 is 75-88%, above hw15b and hw15c (68-72%), so part of the gap may be the share.

## 15. hw17a-hw17c (2026-10-05): a recalled strike sends home only what the system is short

Build `1a535b2` (`postureRecallPartial` true; the rest as hw15). No council error. **Extinct m92, m109, m96.** The
change works as built and changes little: the shortfall is at least the strike in 29 of 38 recalls at m71-m83
(715 FP called to a system 5,487 FP short), so the strike comes home whole; the rest sailed on after 1, 3 and 3
recalls. In hw15c only 40 of 203 recalls had a shortfall below the strike; the 3,353 FP for 218 FP that prompted
the change was not typical.

| | hw17a | hw17b | hw17c | hw15a / b / c |
|---|---|---|---|---|
| hives m48, m60, m72, m84 | 12, 17, 13, 5 | 14, 8, 5, 2 | 16, 8, 3, 2 | 21, 27, 35, 37 / 19, 20, 30, 50 / 18, 20, 44, 46 |
| found (month), found at m48 | m36, 11 of 12 | m36, 14 of 14 | m41, 13 of 16 | m42, 7 of 21 / m40, 13 of 19 / m40, 13 of 18 |
| strikes launched, recalls, sailed on after | 22, 21, 1 | 10, 10, 3 | 14, 10, 3 | 76-215, 90-94% recalled |
| Threat landings, overrun, human worlds lost | 4, 2, 1 | 2, 3, 1 | 9, 10, 1 | 5, 5, 0 / 4, 4, 0 / 5, 4, 1 |
| sieges arrived, human landings, hives eradicated | 139, 28, 27 | 115, 23, 21 | 62, 19, 17 | 415, 66, 58 / 474, 48, 22 / 521, 43, 34 |
| Threat FP lost a human FP | 1.34 | 1.38 | 1.42 | 1.42 / 1.30 / 1.28 |

- The batch does not test the change against hw15: these swarms were found earlier (m36 twice) and 81-100% found
  at m48, the share that died in hw11-hw13; hw15's were 33-72%. Three games a batch do not separate a change from
  the found share.
- The swarm's shortfalls are thousands of FP against strikes of hundreds: defence takes any strike in range,
  whole or in part.

## 16. hw19a-hw19c (2026-10-05): information by ship - no strike leads, carried sightings, patrols, no phase mobilisation

Build c67316e: `strikeLeads` false, `carriedIntel` true, `patrolsEnabled` true, `threatinc_mobiliseAtPhase` 0
(`information-by-ship.md`). hw18 (the same without the mobilisation change) was stopped at about month 65: every
faction mobilised at phase 3 on war day 1066 and patrols found the first hive 50-105 days later, before any strike.
The user: "They only mobilise when they are attacked."

| | hw19a | hw19b | hw19c | hw15 (picket, leads, phase 3) |
|---|---|---|---|---|
| First strike detected = first mobilisation, war day | 1356 | 1362 | 1246 | - |
| First hive known, days after it | 71 | 175 | 58 | - |
| Found, month | 46 | 50 | 42 | 42 / 40 / 40 |
| Hives at m120 (peak) | 17 (32) | 4 (27), extinct m121 | 119 (121) | 19 / 100 / 120 |
| Sieges arrived | 326 | 237 | 462 | - |
| Sieges seen coming / unseen | 107 / 219 | 44 / 193 | 203 / 259 | 415-521 / 0 |
| Sieges first seen by eyes / by patrol | 224 / 160 | 199 / 63 | 231 / 381 | - |
| Sieges ended beaten / landed / called off | 127 / 92 / 79 | 105 / 58 / 32 | 150 / 97 / 200 | - |
| Strikes (recalled) | 70 (75) | 37 (45) | 247 (303) | - |
| Threat landings / human worlds lost | 9 / 2 | 8 / 1 | 25 / 2 | 5 / 0, 4 / 0, 5 / 1 |
| Human landings / hives eradicated | 69 / 52 | 48 / 44 | 60 / 49 | - |

- No exceptions (270 bytes each). Nobody mobilised before a strike began over a human world.
- The search alone finds the first hive 2-6 months after the first strike; the month it is found moved from 40-42 to 42-50.
- The swarm sees 19-44% of sieges before they arrive (the picket saw every one in hw15); the rest are first seen in
  its own system.
- Patrol size followed losses on both sides (humans to 160 FP, swarm to 240 FP in hw18) and came back down.
  No patrol was destroyed by a force flying as a route (`meetAbstract` never returned DESTROYED): every meeting ended
  with the patrol running home.
- Outcomes spread as wide as in hw15 (extinct, held at 17, runaway at 119), so this batch does not show what the
  change did to the balance.
