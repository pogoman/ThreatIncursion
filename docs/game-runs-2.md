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

## 17. hw20a-hw20c (2026-10-05): staged strikes paid from the hive's banks, the recall gone

Build 2e00823: `strikeStaged` true, `strikeStagedMargin` 1.5 (facts, Decisions). Strikes are built from the pooled banks
of the hive, sized to 1.5 x the defence last seen, closest known target first.

| | hw20a | hw20b | hw20c | hw19 (garrison strikes, recall) |
|---|---|---|---|---|
| First strike, war day | 915 | 916 | 946 | 1246-1362 |
| Found, month | 38 | 36 | 37 | 42-50 |
| Hives at m120 (peak) | 9 (27) | 0, extinct m108 (17) | 0, extinct m68 (17) | 17 / 4 / 119 |
| Strikes (FP) | 45 (44.9k) | 12 (10.9k) | 4 (4.7k) | 70 / 37 / 247 |
| Forward bases founded / destroyed by a strike | 47 / 17 | 29 / 3 | 13 / 0 | 52 / 1, 55 / 1, 116 / 24 |
| Threat landings / human worlds lost | 10 / 1 | 5 / 2 | 4 / 2 | 9 / 2, 8 / 1, 25 / 2 |
| Human landings / overrun by the hive | 73 / 1 | 33 / 2 | 20 / 2 | 69 / 13, 48 / 4, 60 / 10 |

- The banks are no store: the pool read 22-1,400 FP after most launches (production goes into the garrisons as it is
  banked), so every strike after the first few is the two-swarm minimum, 650-1,000 FP - the size of the old strikes.
- The defence the swarm holds for a forward base is often its founding figure (120), so 1.5 x it asks for nothing.
- A strike no longer waits on a full garrison, so the war opens 300-450 days earlier and the hive is found at m36-38.
- The strikes it does send land: 17 bases destroyed in hw20a (1 in hw19a). Not enough to hold: extinct at m68 and m108.
- Human landings are overrun by the hive 1-2 times a game against 4-13: the banks that regrow a besieged garrison are
  the banks the strikes spend.

## 18. hw21a-hw21c (2026-10-05): staged strikes take the hive's spare garrison swarms first (a trial)

Build 1005e77: `strikeStagedGarrisons` true on the hw20 build (`IncursionManager.stagedPlan`, `stagedSpares`).

| | hw21a | hw21b | hw21c |
|---|---|---|---|
| First strike / first hive found, war day | 976 / 1165 | 916 / 1101 | 915 / 1123 |
| Found, month | 37 | 36 | 36 |
| Hives at found (peak) | 17 | 14 | 16 |
| Extinct, month | 96 | 79 | 112 |
| Strikes (mean FP, max) | 21 (852, 2,012) | 13 (669, 1,686) | 22 (838, 1,632) |
| Forward bases founded / destroyed by a strike | 19 / 5 | 21 / 4 | 34 / 9 |
| Threat landings / human worlds lost | 12 / 1 | 5 / 2 | 9 / 2 |

- The hive has no spare: 35-39 garrison swarms taken in the first 60 months of a game, then "spares 0 FP of swarms".
  A garrison settles where the colony's income meets its upkeep (4% a month of its fleets), so production stands as
  garrison within the reserve; nothing is above it.
- Strikes are the size of hw20's; all three games extinct. Six of six staged games (hw20, hw21) end worse than hw19:
  the war opens at day 915-976 (hw19: 1246-1362), the hive is found at m36-38 with 14-17 hives.

## 19. hw22a-hw22c (2026-10-06): a hive-wide strike fund pays staged strikes (a trial)

Build f464a23: `strikeFundShare` 0.3 of every colony's fabrication set aside (`ThreatColonyManager.strikeFund`), staged
strikes paid from it alone, at least `strikeStagedMinFP` 3,000; `strikeStagedGarrisons` false.

| | hw22a | hw22b | hw22c |
|---|---|---|---|
| First strike / first hive found, war day | 1218 / 1338 | 1129 / 1263 | 1156 / 1279 |
| Found, month | 43 | 41 | 41 |
| Hives at m120 (peak) | 6 (25) | 43 (65) | 0, extinct m92 (21) |
| Strikes (mean FP, max) | 32 (3,404, 3,726) | 62 (3,459, 3,831) | 16 (3,588, 4,694) |
| Forward bases founded / destroyed by a strike | 49 / 19 | 82 / 36 | 30 / 6 |
| Threat landings / human worlds lost | 6 / 1 | 12 / 1 | 5 / 1 |
| Human landings / hives eradicated | 98 / 88 | 125 / 117 | 38 / 35 |

- Strikes are 3,400-3,600 FP, five times hw20's, and kill 20-44% of the forward bases founded (hw19: 2%, 2%, 21%).
- The fund takes months to fill, so the war opens at day 1129-1218 (hw20: 915) and the hive is found at m41-43.
- Hive survival is back in hw19's range (hw19: 17 / 4 / 119 at m120); human worlds lost still 1 a game: the strikes go
  to the closest targets, which are forward bases.

## 20. hw23a-hw23c (2026-10-06): the strike fund with strikes of at least 8,000 FP (a trial)

Build 1c814ff: as hw22 with `strikeStagedMinFP` 8,000.

| | hw23a | hw23b | hw23c |
|---|---|---|---|
| First strike / first hive found, war day | 1492 / 1605 | 1401 / 1531 | 1490 / 1599 |
| Found, month (hives then) | 52 (25) | 49 (17) | 52 (21) |
| Hives at m120 (peak) | 91 (97) | 185 (191) | 105 (110) |
| Strikes (mean FP, max) | 32 (8,306, 11,104) | 34 (8,600, 12,190) | 31 (8,495, 11,899) |
| Strikes at colonies / Threat landings | 15 / 28 | 26 / 37 | 17 / 24 |
| Human worlds lost | 13 | 23 | 14 |
| Forward bases founded / destroyed by a strike | 70 / 16 | 35 / 7 | 69 / 13 |
| Human landings / hives eradicated | 56 / 51 | 44 / 36 | 54 / 43 |

- The swarm wins all three: 13-23 human worlds lost (every batch before: 0-2), 91-185 hives at m120.
- Two things changed at once: strikes of 8-12k FP take colonies (28-37 landings), and the fund takes until day
  1401-1492 to fill, so the hive is found at m49-52 (hw22: m41-43; hw19: m42-50) - 1-2 years more of growth. hw24
  separates them (the fund share 0.5, the war opening sooner).

## 21. hw24a-hw24c (2026-10-06): the strike fund at 0.5, strikes of at least 8,000 FP (a trial)

Build 8ad3875: as hw23 with `strikeFundShare` 0.5.

| | hw24a | hw24b | hw24c |
|---|---|---|---|
| First strike / first hive found, war day | 1310 / 1445 | 1310 / 1435 | 1308 / 1416 |
| Found, month (hives then) | 47 (20) | 46 (22) | 46 (18) |
| Hives at m120 (peak) | 1, extinct m107 (22) | 104 (109) | 51 (53) |
| Strikes (mean FP) | 12 (8,186) | 46 (8,300) | 41 (8,373) |
| Threat landings / human worlds lost | 4 / 1 | 27 / 21 | 20 / 6 |
| Forward bases founded / destroyed by a strike | 22 / 9 | 63 / 21 | 63 / 22 |
| Human landings / hives eradicated | 33 / 25 | 48 / 37 | 66 / 51 |

- The fund fills sooner (war day 1308-1310, found m46-47, hw23: 1401-1492, m49-52) and half the production never
  becomes garrison: one game extinct, two won. Outcomes spread again where hw23's did not.

## 22. hw25a-hw25c (2026-10-06): hw23 repeated (fund 0.3, strikes of at least 8,000 FP)

Build 1c090f1, the hw23 setting again.

| | hw25a | hw25b | hw25c |
|---|---|---|---|
| First strike / first hive found, war day | 1463 / 1565 | 1460 / 1590 | 1493 / 1601 |
| Found, month (hives then) | 51 (22) | 51 (22) | 52 (25) |
| Hives at m120 (peak) | 139 (141) | 127 (128) | 64 (65) |
| Strikes (mean FP) | 32 (8,267) | 33 (8,334) | 31 (8,173) |
| Threat landings / human worlds lost | 31 / 19 | 25 / 11 | 17 / 10 |
| Forward bases founded / destroyed by a strike | 44 / 14 | 41 / 10 | 43 / 15 |
| Human landings / hives eradicated | 39 / 28 | 50 / 40 | 59 / 49 |

- Holds: six of six games at this setting end with the swarm at 64-185 hives and 10-23 human worlds lost. The
  opening is the same to the month (war day 1401-1493, found m49-52).

## 23. hw26a-hw26c (2026-10-06): the strike fund with strikes of at least 5,000 FP (a trial)

Build 389aeec: as hw23 with `strikeStagedMinFP` 5,000.

| | hw26a | hw26b | hw26c |
|---|---|---|---|
| First strike / first hive found, war day | 1280 / 1388 | 1279 / 1415 | 1280 / 1379 |
| Found, month (hives then) | 45 (20) | 46 (22) | 44 (25) |
| Hives at m120 (peak) | 58 (63) | 0, extinct m115 (30) | 58 (59) |
| Strikes (mean FP) | 49 (5,456) | 25 (5,445) | 47 (5,424) |
| Threat landings / human worlds lost | 26 / 4 | 9 / 2 | 32 / 13 |
| Forward bases founded / destroyed by a strike | 86 / 31 | 52 / 12 | 63 / 15 |
| Human landings / hives eradicated | 63 / 54 | 60 / 57 | 53 / 46 |

- Between hw22 (3,000) and hw23/25 (8,000) on every count: the opening (day 1279-1280), the found month (44-46),
  the outcome (two won, one extinct), the worlds lost (2-13).

## 24. hw27a-hw27c (2026-10-06): 3,000 FP strikes, the war opened only once the fund holds 8,000 FP (a trial)

Build b2e7e79: as hw22 (`strikeStagedMinFP` 3,000) with `strikeStagedOpenFP` 8,000 - the war's first strike waits for
the fund the 8,000 FP setting waits for, then the strikes are hw22's size.

| | hw27a | hw27b | hw27c |
|---|---|---|---|
| First strike / first hive found, war day | 1401 / 1529 | 1371 / 1512 | 1311 / 1414 |
| Found, month (hives then) | 49 (24) | 49 (21) | 46 (21) |
| Hives at m120 (peak) | 15 (33) | 82 (82) | 40 (48) |
| Strikes (mean FP) | 45 (3,244) | 64 (3,424) | 58 (3,404) |
| Threat landings / human worlds lost | 14 / 2 | 19 / 1 | 26 / 1 |
| Forward bases founded / destroyed by a strike | 74 / 25 | 82 / 42 | 66 / 28 |
| Human landings / hives eradicated | 64 / 54 | 152 / 137 | 82 / 75 |

- Size and timing separate cleanly. The later opening alone keeps the hive alive (three of three survive, hw22: one
  extinct) but takes no worlds (1-2 lost, as every batch before hw23); 8,000 FP strikes take them (10-23).

## 25. The night of 2026-10-05/06 in one table, and what was left on

Every batch three games from the same save, 3,750 days. Columns: hives at m120 (extinct month), human worlds lost.

| Batch | Build | a | b | c |
|---|---|---|---|---|
| hw19 | garrison strikes, recall, information by ship | 17 / 2 | ext m121 / 1 | 119 / 2 |
| hw20 | staged strikes from the banks, no recall | 9 / 1 | ext m108 / 2 | ext m68 / 2 |
| hw21 | + the hive's spare garrison swarms | ext m96 / 1 | ext m79 / 2 | ext m112 / 2 |
| hw22 | strike fund 0.3, strikes >= 3,000 FP | 6 / 1 | 43 / 1 | ext m92 / 1 |
| hw23 | fund 0.3, >= 8,000 | 91 / 13 | 185 / 23 | 105 / 14 |
| hw24 | fund 0.5, >= 8,000 | ext m107 / 1 | 104 / 21 | 51 / 6 |
| hw25 | hw23 again | 139 / 19 | 127 / 11 | 64 / 10 |
| hw26 | fund 0.3, >= 5,000 | 58 / 4 | ext m115 / 2 | 58 / 13 |
| hw27 | fund 0.3, >= 3,000, opened at an 8,000 fund | 15 / 2 | 82 / 1 | 40 / 1 |
| hw28 | fund 0.3, >= 5,000, opened at an 8,000 fund | 108 / 16 | 129 / 14 | ext m119 / 1 |

- Ranked by the swarm's own score (colonies and worlds destroyed, `run-scoring`): hw23/hw25's setting wins outright,
  six of six. Left on in `settings.json` as the defaults: `strikeStaged` true, `strikeFundShare` 0.3,
  `strikeStagedMinFP` 8000, `strikeStagedOpenFP` 0, `strikeStagedGarrisons` false, `strikeStagedMargin` 1.5. Whether
  the swarm should win this often is the user's call; `strikeStagedMinFP` 3000-5000 gives the contested range.
- Two mechanisms, separately measured: how late the war opens decides whether the hive survives; how big its strikes
  are decides whether it takes worlds.

## 26. hw28a-hw28c (2026-10-06): 5,000 FP strikes, the war opened at an 8,000 FP fund (a trial)

Build 79599c1 with `strikeStagedMinFP` 5,000 and `strikeStagedOpenFP` 8,000.

| | hw28a | hw28b | hw28c |
|---|---|---|---|
| First strike / first hive found, war day | 1310 / 1416 | 1400 / 1528 | 1430 / 1516 |
| Hives at m120 (peak) | 108 (118) | 129 (135) | 1, extinct m119 (29) |
| Strikes (mean FP) | 58 (5,753) | 56 (5,546) | 19 (5,406) |
| Threat landings / human worlds lost | 40 / 16 | 39 / 14 | 7 / 1 |
| Forward bases founded / destroyed by a strike | 53 / 17 | 58 / 19 | 60 / 9 |
| Human landings / hives eradicated | 49 / 41 | 45 / 35 | 57 / 46 |

- Two games as decisive as the 8,000 setting, one lost late: the contested range is narrow and the outcome still
  turns on the first years of the war. Added to the §25 table.

## 27. sat / sl - old saves on the current build (2026-10-06)

The upgrade test the user asked for: the latest Saturn save (`save_SaturnHadean_8807243588242812142`, mod 0.6.0, 2026-09-07, war
day 2361) and StarLord save (`save_StarLord_1669224817518795825`, a 0.7.0 dev build of 2026-09-30, war day 5420), cloned as they
are (`sbs.ps1 -Bases`) and run 100-120 days each.

- Both load in about 3 minutes and run with no exception (`exc-*` at the clean 270 bytes). Static check first: every mod class and
  field the saves hold still exists in the jar (`tools/test-harness/savefields.pl` vs `javap -p`).
- On load: planetary shields migrated, marine arming seeded (45 colonies), the hive ledger opened with the 180-day endowment (51k FP
  over 29 colonies on sat), the swarm intel seeded from charted systems on sl.
- The strike fund starts at 0 and reached 7,729 FP (sat) / 5,317 FP (sl) by day 120: an upgraded campaign sees its first staged
  strike 4-6 months in, none before. 37 / 28 "waits: the strike fund holds" lines, the nearest known target each pass.
- Patrols fly from the first poll: sat 21 Patrol Swarms out at the first census (546 FP), sl 24 out + 18 human patrols; patrols
  gave up on 7 / 12 unreachable stops. The new logging worked (arrivals, battles, home lines); the home line said "back with 0"
  because a despawned fleet reports 0 FP - fixed to the last live reading the same day.
- Not a balance run: an old save carries its drift (facts, "Does loading an old save behave like a new game?").

## 28. sl2 - the StarLord campaign played out alone (2026-10-06)

The user: "run it until one side wins i want to see what happens without my intervention". `save_StarLord_1669224817518795825`
(war day 5420, Jan c221, 61 hives, Hegemony and the League mobilised) cloned as it was, Fleets Ignore You on, run 5,770 days.

**The humans win, ten years in.** Hives 61 -> 75 (Aug c222, the peak) -> 24 (Nov c229) -> 0; the last hive, Vassago, fell on
6 Apr c231 (war day 9165) to a League front. The war board then hid itself (`ThreatIncursionIntel.isHidden`: no known system
infested), which is what the user saw as "the Abyssal War tab was gone" in Major Events. The game ran five more years of
nothing until the player fleet ran out of supplies and vanilla's accident report paused it.

| | sl2 |
|---|---|
| Hives found / eradicated | 101 / 411 |
| Human landings / sieges called off | 424 / 93 |
| Forward bases founded / destroyed by a strike | 118 / 24 |
| Staged strikes (mean FP) | 35 (8,207) |
| Threat landings / human colonies lost | 1 (Mazalot) / 0 |
| Factions mobilised during the run | 1 (Independent) |

- The strike fund started at 0 on the old save and the first staged strike flew ~5 months in; 35 strikes in ten years, one
  landing, no colony taken - against 424 human landings. Strikes went mostly at forward bases (24 of 118 destroyed), the
  nearest known target. An established human war machine (two factions mobilised for 15 years) outproduces the fund 10:1.
- Mid-war upgrade note: the hive count still rose for 18 months after the load (61 -> 75), then fell 7-10 a year.
- **Bug found and fixed:** 15 Patrol Swarms (448 FP) stood "returning" from the last hive's death to the end, and ~15 human
  parties likewise - a party whose home world is gone (hive eradicated, forward base dismantled) never despawns.
  `ThreatScoutRoute.checkReturn` dismisses a returning party whose world is gone, that has no orders left, or that has been
  returning twice `scoutLegMaxDays`; counted home, not lost (no level raised). Dismissals log "<party> dismissed: why".
- **Open (design, the user's):** `warModeStandDownDays` is 0, so Hegemony and the League stayed mobilised and flew patrols
  for five years after the swarm was extinct. A stand-down on `ThreatIncursionIntel.isEradicated` would end the war footing.

## 29. hw29a - how the swarm went extinct with the floor on the opening strike only (2026-10-06)

Batch sl3 / hw29a / hw29b (the user's StarLord save and two new games), the first with `strikeStagedMinFP` binding the
opening strike only. Stopped at 3,750 days on the user's word; only hw29a is written up - "what went wrong in that run".
hw29a: 4 -> 24 hives by war day 1840, 0 by day 3462; hw29b 36 hives / 5 colonies lost; sl3 145 hives / 20 colonies lost.

**What went wrong, in order:**

1. **The opening strike woke everyone at once.** The 8,101 FP strike sailed on day 1461 at Yama; the next three, now sized to
   their targets (4,401 / 677 / 2,031 FP), went at Chicomoztoc, Qaras and Culann within 150 days - the closest known targets
   were in four factions' systems. Hegemony, the League, Tri-Tachyon and the Independents all mobilised between days 1533 and
   1658. A 20-hive swarm with 17,000 FP of garrisons faced four war economies from the first year of the war.
2. **Every hive was found inside 18 months.** Found 4 (day 1654) -> 20 (day 1840) -> 24 of 24 (day 2024): four factions'
   patrols and 104 hunting forces walked the whole ring. The fog bought the swarm nothing once the war was open.
3. **It was drowning in the wrong resources.** Stance CONSOLIDATE from day 1840 to extinction, but the stance no longer blocks
   founding (`stanceConsolidateSpreadShare` 1 since 2026-10-04) - I first wrote this run up as "consolidated to death" and
   that was wrong. Swarms are bought with FP and FP comes only from forge output (`facts.md` "How is a Threat fleet paid
   for?"); fuel and supplies buy no ship. Every posture line from day 1840 reads `want 23k, held 10k, surplus 0.0k`: the forges'
   4-5k FP a month went whole into garrison replacement and the hunts ate it, so seeding (650 FP of hulls from the same pool)
   got nothing - 17 colonies in the game, 4 waves in the last 4.5 years, 2 destroyed. More FP needs more forges, every size-3+
   hive had one (dump d2825: 9 forged worlds of 11, the 2 new hives size 1), more forges needs more hives. Meanwhile it banked
   **1.7 million fuel and 450,000 supplies** it could spend on nothing.
4. **The garrisons were ground down faster than the income rebuilt them.** Fleets 26,353 FP (day 1654) -> 11,703 (day 2024)
   -> 15,376 -> 8,556 -> 7,199 -> 3,633 (day 3127): 46 / 103 / 73 / 63 hunts a year and 42 landings (mean 1,248 troops,
   max 4,667) killed 1 / 10 / 6 / 5 / 9 / 2 hives a year. 232 defence rallies in year 4 and 137 in year 5 were sent and lost.
5. **The strike fund bought nothing that mattered.** 30% of production -> 54 strikes, 46 of them at forward bases (27
   destroyed, 58 founded) and 2 colonies taken. The humans' economy never felt it; the swarm's lost a third of its income to it.

Not the cause: fuel or supplies (surplus throughout after day 1654; the dump's `swarm.fuel` reads 0 in many months because it
is a different field from the census ledger), the patrol or stand-down code, exceptions (none).

What the run points at, for the user: (a) the currency - a swarm whose garrisons are being killed faster than its forges replace them cannot turn a
million fuel and half a million supplies into one ship; (b) the opening - the first strikes should perhaps not fan out across four factions'
worlds in 150 days; (c) 30% of production to strikes at forward bases while the hives starve of garrison.

## 30. hw30 - the hull pool's first batch, stopped at war day ~2000 (2026-10-06)

Three new games on the hull pool as first built (2e18c918). Hegemony's pool read 4,430 FP standing and 1,400 FP/mo of yards at
mobilisation (estimate 6,000 / 600); the hive's want 7.6-9.8k for 19-23 hives before anyone woke (hw29a 17k for 20). By war
day 2000: 31 / 32 / 38 hives; human colonies lost 7 / 8 / 13 (Chicomoztoc, Yama, Coatl, Qaras among them - Hegemony's pool
fell to 674 and its yards to 200 FP/mo with its worlds); hives eradicated 2 / 2 / 0; hunts 9 / 2 / 4, sieges 0 / 2 / 0,
sieges postponed 46 / 51 / 71; Threat strikes 56 / 60 / 42 (hw29a: 12 by day 1833) - the garrison want down, the surplus
went to the strike fund. **Defect:** out exceeded standing everywhere (Tri-Tachyon 2,314 FP out of a 197 FP pool): supply
convoys and scouting parties were committed at spawn but never gated at launch, so freighters held the warship hulls and
every hunt and siege read 0 free. Fixed for hw31: convoys draw no hulls, scouts and patrols launch only with hulls free
(`hull-pool.md` 3). Dumps: `tools/warsim/validation/hw30a-c`.

## 31. hw31 - the hull pool, convoys exempt and scouts gated, to war day 3,100-3,750 (2026-10-06)

Three new games on 7d8e6459; c ran to day 3,758, a and b read at ~3,130-3,150. No exceptions. Hives 107 / 94 / 88 (size
471 / 479 / 395), swarm fleets 175k / 232k / 178k FP on 54 / 65 / 52 forges (21-26k FP/mo); eradicated 14 / 0 / 30; Threat
strikes 114 / 105 / 200, human sieges 91 / 34 / 105; the core held (hw30 lost Chicomoztoc by day 2000). c held the swarm at
25-30 hives from day 2000 to 2574 before it regrew. Pools at the end: Hegemony 1.2k / 3.2k / 8.4k standing (c built Military
Bases and starfortresses on its forward bases; yards 200 / 3,600 / 3,800 FP/mo), Persean 2.7-6.7k, Tri-Tachyon 0.3-1.1k,
Independent 0.5-3.2k - every pool out to the last hull, out > standing where a faction's bases died with fleets away.

**Stockpiles.** Swarm: fuel-bound days 600-1000 (first plant ~day 497, producing from ~740), supplies-bound 1000-1900
(stock 1-9k against 28-40k/mo spent; 53-85 sends held, last at 1886-2069), then nothing held: fuel hoarded 230-555k (10-28
months' cover), supplies about one month's spend (b 117k against 172k/mo at the end). Humans: never short of either - the
reserve starts at mobilisation (day 1535) and nothing spends it: 350k-1.1M fuel and 110-520k supplies a faction by day 3000.
All 476 / 437 / 493 "Expedition postponed" lines had both resources over the bill: hull-bound. "Hunting force waits" 815 /
889 / 320. The only resource bind: 75 / 26 / 54 "short of fuel to saturate" at a single base's own fuel.

**Builds.** Hive: fuel plants 11 / 10 / 7 in the shortage window (days 1164-1559), Heavy Industry for supplies 6 / 9 / 4
(1131-1836), spare refineries 18-22, and when fuel overshot it converted 6 / 6 / 3 fuel plants back (to Heavy Industry and
Bastions); neither shortage recurred. Humans: core worlds build only Waystations (26-27); forward bases one fixed sequence
(Patrol HQ +120 d, station +60, fuel plant / Heavy Industry +120 each, then Military Base, starfortress), fuel first 8 / 9 /
16 bases against Heavy Industry first 2 / 4 / 0; the fuel-to-Heavy-Industry swap never fired; most founded bases died within
~120 d (24 / 22 / 37 dismantled, 23 of c's by strikes). The wrong priority under the pool: fuel never binds, yards do.

**Fog.** First hive found 18-50 days after the first mobilisation, always from a same-faction sighting carried home in a
median 14-15 days; no cross-faction sharing (Mam: Hegemony day 1596, Persean 2964); "found" is per system, hence 88/91 by day
3000 on ~25-30 find lines. Tri-Tachyon in b and luddic_path in a stayed blind: hull-starved pools kept their parties home
(the scout gate). Swarm: 550-600 patrols (127-143 d out, 88-90% of stops empty), 1,060-1,270 scouting swarms; seen sieges
rallied within 30 d 11/37, 6/21, 17/41, "no rally (could stand against)" x65. Human parties 36-373 a faction, 5-11% fight.

For the user: hull scale (`hullPoolMult`, `fabFPPerShipUnit`, the forge invest rule - humans 1-8k pools and 0.2-3.8k/mo yards
against 50-65 forges); forward-base build order (Heavy Industry first, the swap keyed on hull debt); the reserve accrual that
buys nothing; scouts gated on hulls (exempt them like convoys?); guards holding hulls for good. Dumps: hw31c
`tools/warsim/validation/hw31c`, a and b when they finish.

## 32. hw32 - the faction stock plan and one banking rate, full runs (2026-10-06)

Three new games on 3b19fa78 (`ThreatFactionStock` driving `ThreatFrontlines.build`, `reserveSurplusMult` 1.0), to war
day 3752-3763, no exceptions. Against hw31 by run: bases founded 96 / 79 / 73 (79 / ~49 / 42); abandoned for no garrison
33 / 65 / 13 (64 / ? / 15) - a halved per base founded, c level, b worse (its Tri-Tachyon never built a yard, 645 FP lost on
a 270 pool, Hegemony's yards stayed at 800); struck 22 / 8 / 17. Heavy Industry built 43 / 23 / 41 (19 / - / 19), fuel
plants 38 / 19 / 39, Military Bases 23 / 9 / 33 (1 / 0 / 12); yards at the end Hegemony 2,900 / 800 / 5,800 FP/mo (200 /
3,600 / 3,800), Persean 2,700 / 1,300 / 1,700, Tri-Tachyon 2,100 / 0 / 2,100, Independent 3,700 / 1,300 / 2,700. The plan
line reads as meant (`hulls 0 free of 2175, 343 to rebuild at 400/mo (short)`); zero conversions fired - Heavy Industry
goes up before any fuel plant, so no surplus plant ever stands to be torn down, and the Patrol HQ path needs hulls in
surplus, never seen. Hegemony supplies banked 25 / 8 / 58 units at 1.0 (hw31a 16 at 1.5): forward-base Heavy Industry now
counts. Every pool 0 free at the end: hunts waiting 2,121 / 1,155 / 1,823, postponed 598 / 775 / 922 - production-bound
now, not rule-bound. Sieges 87 / 86 / 154, hives eradicated 30 / 14 / 36 (14 / 0 / 30). Swarm 153 / 165 / 155 hives,
267k / 378k / 206k FP, 18-45k FP/mo against 2-6k of yards a faction. Dumps: `tools/warsim/validation/hw32a-c`.

## 33. hw33 - yards build a navy, full runs (2026-10-06)

Three new games on 57dc3bb9 (`ThreatHulls.built`: yard output beyond losses adds to the standing hulls), the hw4 sector; a and b to war
day 3797 / 3762, c stopped at 2881 for the first run on a second sector (hw34). No exceptions. The first split result: hw33a the
humans win - the swarm peaks at 34 hives / 24k FP (d1805-2045), Hegemony hulls 6k -> 32k (d2405) -> 69k (d3005), hives 29 -> 4 -> 0
(last eradicated d3379); end navies 117k / 89k / 78k / 78k FP all free, yards still adding 500-1,400 a month, 460k-1.3M fuel and
356-748k supplies; all 47 bases dismantled, 29 for no hive in reach. hw33b the swarm wins on one world: it landed on Chicomoztoc
(Hegemony Orbital Works) at d1627 against 709 FP free (relief 303 of 4,266 owed), took it at the third landing d2481, and Hegemony
eradicated the hive at d2561 - its yards 1,400 -> 0, 6.5k lost on 5.8k, 0 free from d2405; its four forward bases queued Heavy
Industry and none could pay the 5,000 supplies (27k left, 7k/mo). Persean alone (24k, 10.7k free); swarm 40 hives d2405, 60 d3005,
128 d3725, 206k supplies/mo. hw33c to d2881 tracked a (31 -> 24 hives, Hegemony 28.8k / 16k free). Against hw32 (a / b / c):
hunting forces waiting on hulls 57 / 440 / 99 (1,155-2,121), postponed 26 / 251 / 56 (598-922), sieges sailed 164 / 241 / 153,
completed 82 / 75 / 64 (22-39), hives eradicated 44 / 40 / 35 (7-18), founded 41 / 162 / 55 (163-168); bases founded 47 / 91 / 56,
no garrison 2 / 36 / 10, struck 15 / 35 / 31; Heavy Industry 29 / 17 / 17, fuel plants 26 / 11 / 14. Supplies now bind the humans:
b and c end at 15-55k a faction. Dumps: `tools/warsim/validation/hw33a-c`, logs `ti-hw33*-keep.txt`.

## 34. hw34 - the second sector (AphelionDysnomia), two runs on the navy build (2026-10-06)

Two new games on 57dc3bb9 (a16eb264 docs) on `save_AphelionDysnomia_4103534775338436064`, the first sector other than hw4; the
swarm lands in the Ala system (Beszel, Labraxas, Strathcona, Blue), an edge system by the Persean League. hw34a to war day
3759: the opening strike at Cibola (persean, 31 ly, 135 days) on day 1407; all seven factions mobilise over the run (Church,
Path and Diktat too, Hegemony last); the swarm wins, 114 hives, 1.3M fuel / 550k supplies, 153 founded against 47 eradicated,
242 sieges sailed; human navies 9-20k FP a faction with 2-19k lost at the end, 93 bases founded and dismantled. The old ally
aid fired 115 times here (peacetime shortages exist in this sector) against 0 in hw31-33. hw34b never had a war: the bootstrap
wave to Blue stalled alive for ten years (facts, "Why did hw34b's swarm do nothing"), the hive had fuel 0 throughout, three
size-7 hives on 840k supplies and 76k FP banked, 0 strikes, and the game ran 190 days a minute. Logs `ti-hw34a/b-keep.txt`; dumps
kept in `tools/warsim/validation/hw34a-b`.

## 35. hw35 - trade convoys, holds and the wave fix, three runs on the second sector (2026-10-06)

Three new games on 8421ab1a on `save_AphelionDysnomia_4103534775338436064`, to war day 3699 (a, ended by hand) / 3789 / 3759, no
exceptions. The wave fix: every game's bootstrap wave to Blue stalled in the Ala system ("had not arrived in 330 days, in the
system"), was re-steered, stalled again and was moved to its orbit; every game had a war (hw34b had none). Hives at the end 91 /
0 / 48 (b: 32 at d2409, 11 at d2709, 0 by d3789), founded 118 / 47 / 112, eradicated 34 / 48 / 65; sieges sailed 162 / 110 / 311,
completed 69 / 68 / 113. Navies: a Persean 11.7k with 10.3k lost and yards 0, Tri-Tachyon 13k; b Persean 134k, Independent 150k,
Tri-Tachyon 71k, Church 53k all free; c Persean 45k (41k free), Tri-Tachyon 60k, Hegemony 25k. Supplies a faction at the end
19-39k / 32k-817k / 16-51k. Holds: 37 / 12 / 42 bases held supplies for the shortage's producer; Heavy Industry built 19 / 33 / 25,
fuel plants 17 / 33 / 15. Trade convoys 7 / 9 / 16, of which supplies 1 / 2 / 3: it sails across standings (Persean -> Tri-Tachyon
at Suspicious, Church -> Independent at Neutral) but almost only fuel, which everyone hoards (250k-2.9M) - no war faction reads
surplus in supplies (banking >= trailing demand and four months in stock) while the war runs. Bases founded-and-dismantled 68 / 14 /
69 (a: 40 no garrison; c: 47 struck), standing 24 / 45 / 27. Dumps `tools/warsim/validation/hw35a-c`, logs `ti-hw35*-keep.txt`.

## 36. hw36 - standing upkeep in supplies, both sides (2026-10-06)

Three games on b10e4345 (standing upkeep: a faction's free hulls and a hive's garrison pay their side's measured maintenance per FP x `standingUpkeepMult` 1 a month, `ThreatHulls.maintain` / `ThreatColonyManager.payGarrisonSupplies`; unpaid hulls lost) on `save_AphelionDysnomia_4103534775338436064`, to war day 3793 (a) / 3366 (b, ended by hand) / 3731 (c, ended by hand). No exceptions.

**hw36a had no war in 3,750 days.** The swarm's opening strike (8k FP, ~40k supplies for its trip) was never affordable: the garrison charge is paid first every day from the hive stock, and the garrison is grown from forge FP to the posture want whatever the supplies - so the swarm grew to 51 hives and 70k FP, built 43 forges to feed them (76.5k supplies made a month, 76.7k spent, stock 0-10k the whole game, demand 117k), held 30 sends, launched 0 strikes and never lost a swarm to unpaid supplies. Nobody mobilised. The charge took 100% of the swarm's supplies and shrank nothing.

**hw36b/c had wars** (b: 71 strikes, 96 human sieges, 20 of 43 hives destroyed, 22 left at 37k FP; c: 18 strikes, 51 sieges, 14 of 42 destroyed, 29 left at 49k FP). Humans paid their standing upkeep 90-100% (b: Persean 429k of 472k supplies over 57 months, Tri-Tachyon 298k of 330k, Independent 392k of 403k; c: 95-100%) and lost hulls where they could not (b: Persean 33k FP starved, Tri-Tachyon 22k, Church 12.5k, Independent 12k; c: Persean 9k) - their navies settled at 7-14k FP (10-13k built) against hw35's 17k+. The swarm lost 0 swarms in every game: its income refills the stock daily and the garrison is the first thing paid; it recycled 13-31 for FP as before. Trade convoys 17 (b) / 34 (c), holds 83 / 76, 14 / 5 Heavy Industries built; human supplies 15-61k a faction at the end.

**Read:** the charge binds the humans (fixed want: vanilla patrols + yards) and not the swarm (want on FP, fed first), so supplies bound the swarm's *action* instead of its navy - the opposite of the intent. The user's fix (4ac4340c, hw37): growth bound by the supplies surplus on both sides (`suppliesKeepSwarm`, `suppliesKeepFP`), unpaid upkeep booked as demand, a held strike's supplies shortfall booked like its fuel. Rate knob untouched. Dumps in `tools/warsim/validation/hw36a-c`.

## 37. hw37 - navies bound by supplies, both sides (2026-10-06)

Three games on 4ac4340c (hw36's charge plus: a hive grows no swarm and a faction's yards bank no hull their supplies surplus cannot keep; unpaid upkeep booked as demand; a held strike books its supplies shortfall) on `save_AphelionDysnomia_4103534775338436064`, to war day 3785 / 3775 / 3784. No exceptions. Every game had a war (first strike at Cibola in all three, Census 123 of 123 in each).

**The gates fire and both navies sit under the supplies line.** Garrison growth waited on supplies 423 / 218 / 342 times, yards sat idle 46 / 181 / 46; standing upkeep was paid 98-100% (b: Independent 750k of 765k supplies over 68 months, Church 661k of 669k, Persean 724k of 728k; a and c 100%) and the only starvation was b's 4-12k FP a faction. The swarm still grew to 47-57k FP in a and c (each new hive's first swarm is always built, and its forges' output grew with it: made 56-59k a month, spent the same, stock 0-6k all war), and lost swarms to unpaid supplies only once the human sieges took its producers (a: 87 swarms lost, c: 62, b: 0).

**The humans won all three.** a: 106 sieges, 32 of 48 hives eradicated, 17 left at 29k FP (from 39 at 47k); b: 84 sieges, 35 of 38, 3 left at 5k FP by war day ~2900, the Church and Path pressing Ala to the end; c: 88 sieges, 25 of 45, 22 left at 42k FP. Human navies 4-15k FP (Church 14k in b, Independent 15k in c), human supplies 15-51k a faction at the end, swarm supplies 0 with 650-840k fuel. Hunting forces waited on supplies 66-206 times a game; 0 / 20 / 5 trade convoys, 25 / 0 / 78 holds, 8-13 Heavy Industries built.

**Read:** the compounding race is gone - no side's navy outran its supplies, and the sector that split 1-1 in hw33 and went to the swarm's pre-war growth in hw36a now plays the same way three times. What decides it now is the war economy itself: the humans bank supplies on top of their upkeep and besiege; the swarm spends every supply it makes keeping what it has and sits at 0. The rate knob (`standingUpkeepMult`, 1 for both) and the swarm's supplies production are the user's levers. Dumps in `tools/warsim/validation/hw37a-c`.

## 38. hw38 - innate patrols, tangible actions: the cut-back (2026-10-07)

Three games on 5eeb5ff2 (vanilla's patrols uncharged both sides; only the navy a faction's yards built beyond the table pays standing upkeep; the hive's garrison charge and growth gate removed) on `save_AphelionDysnomia_4103534775338436064`, to war day 3777 / 3761 / 3777. No exceptions.

**Both sides fight.** Strikes 80 / 104 / 87 (hw37: 9-24), human sieges 216 / 224 / 242, hives founded 60 / 188 / 85, eradicated 37 / 36 / 70. The swarm's stock is off the floor (4-10k mid-war, b 278k at the end) and its fuel is spent (a 35k at the end from 295k; hw37 sat on 650-840k). Standing upkeep on the built navies was paid 90-100% everywhere; starvation 0.5-3.3k FP a faction. Human navies 2-15k FP, 4-7 factions mobilised a game (hw37: 2-4).

**Outcomes split 1-2.** a: the swarm peaked at 48 hives / 63k FP and was pushed back to 27 / 30k, four factions pressing at the end - open. b: the swarm snowballed - 165 hives, 198k FP, seven factions mobilised and four of their navies broken (Persean 4,495 FP lost of 4,516, yards gone; Independent, Church, Path at 0 free) - a swarm win. c: 70 of 85 hives eradicated, 18 left at 26k FP against six factions pressing - a human win.

**Read:** the hull-compounding race is gone and the war is decided by territory - hives founded against worlds besieged - with real offence on both sides. The one-evening charge on vanilla's figure (hw36-37) was the thing suppressing the swarm's strikes. Next: hw39 on the same build for sample size, then the first sector (hw4) to check the shape holds off the Ala landing. Dumps in `tools/warsim/validation/hw38a-c`.

## 39. hw39 - the cut-back, second batch for sample size (2026-10-07)

Three more games on 5eeb5ff2 on the second sector, to war day 3778 / 3793 / 3768. No exceptions. a: 114 strikes, 244 sieges, 101 founded / 60 eradicated - the swarm holds 46 hives at 60k FP with the Church, Persean and Path navies broken (0 free, 631-3,664 lost); swarm ahead. b: 40 strikes, 227 sieges, 84 / 61 - the swarm pushed from 40 hives to 11 and back to 23, five factions at 5-11k FP; open. c: 25 strikes, 129 sieges, 44 / 45 - the swarm eradicated outright (0 hives by ~war day 3400) with every faction's navy intact and home; human win.

**Six games on the cut-back (hw38-39): swarm 2 (38b won, 39a ahead), humans 2 (38c, 39c), open 2 (38a, 39b).** The sector that went 1-1 in hw33 on hull compounding, swarm-by-default in hw36 and 3-0 humans in hw37 now goes either way on the war fought. Standing upkeep on built navies paid 90-100% throughout, starvation 0.5-3k FP a faction; yards idle 50-220 times a game (a built navy waits on its supplies surplus). Dumps in `tools/warsim/validation/hw39a-c`. Next: the first sector (hw4).

## 40. hw40 - the cut-back on the first sector (2026-10-07)

Three games on 5eeb5ff2 on `save_AmaruDugas_2921423183749615243` (hw4), to war day 3758 / 3759 / 3755. No exceptions. **Swarm 3-0.** Strikes 99 / 109 / 122, human sieges 255 / 271 / 268, founded 129 / 115 / 162 against eradicated 25 / 40 / 54; the swarm ends at 104 / 80 / 117 hives, 218k / 155k / 240k FP, 0.66-1.39M supplies in stock (never a bind). Humans: 4-5 factions mobilised, standing upkeep paid 98-100% (Tri-Tachyon 427k of 437k in b), the navies lost in the field - by the end most factions sit at 0 free with 2-8k FP lost and yards at 100-900 FP/mo (Hegemony 100 in c, its yards gone); hunts waited 600-740 times ("5 bases pay for 219 FP, reported swarms need 1,029").

**Read:** sector-dependent. On the second sector the cut-back goes 2-2-2 (hw38-39); here the swarm runs away on spread - sieges disrupt a hive rather than remove it (razing disrupts only, 2026-10-01), foundings outrun eradications 3-4 to 1, each hive's forges add FP and supplies the swarm never needs. Not an economy question: the levers are the hive's founding pace and cost, what a human siege does to a hive, and human yard output - the user's call. Dumps in `tools/warsim/validation/hw40a-c`.

Addendum (hw40a's log): a hive is eradicated only by a ground front (25 "Hive Eradicated" notices, all fronts; sieges disrupt). 188 of 255 siege expeditions aborted with 0 ground actions - the daily sieges were beaten or called off by the garrisons they met ("Daily siege of Coatl: 825 -> 261 FP, beaten"; "Alpha Laphirial II called off: 2,470 FP against 1,627 + 259 of its hunts"), and the council's plays reported "its siege could not be paid after all" 313 times across three factions. The human bind on this sector is hulls in the field against 100 hives' innate garrisons, not supplies.

## 41. hw41 - the first sector again (2026-10-07)

Three more games on 5eeb5ff2 on the first sector, to war day 3757 / 3759 / 3771. No exceptions. **Swarm 3-0 again - six of six on this sector.** Strikes 122 / 115 / 114, human sieges 322 / 281 / 329, founded 112 / 167 / 144 against eradicated 51 / 54 / 86; the swarm ends at 69 / 122 / 61 hives, 107k / 174k / 80k FP, 0.38-0.68M supplies. Humans 4-6 factions, navies 1-11k FP with most at 0-700 free and 0.4-7.7k lost (Hegemony 7,709 lost of 7,583 in a, yards 200/mo). c was the closest: 86 eradicated, the swarm down to 61 hives, Hegemony intact at 9.7k FP.

**Twelve games on the cut-back:** second sector 2-2-2, first sector swarm 6-0. The economy is the same in both; what differs is the sector - where the swarm lands, how many hives it can seed before the first strike, how many factions border it - and on the first sector spread outruns eradication every time (hives fall only to ground fronts; the daily sieges are beaten by innate garrisons, `hw40` addendum). The levers are design, not knobs: the hive's founding pace, what a siege does to a hive, human yard output. Dumps in `tools/warsim/validation/hw41a-c`.

## 42. hw42-hw47 - the offensive (2026-10-07)

The user, on hearing the swarm launched one strike a month at the closest payable target: "Christ so we have no grand strategy layer at all ... they should go through periods where they are saving and then do a multi pronged attack on all known systems ... if they are losing ... focus on nearby bases only ... It depends on their resources vs the known resources of their enemy." Built as `ThreatOffensive` (`strikes-staged.md` 2) and iterated through six batches on the second sector (`save_AphelionDysnomia`), each abandoned at the first fault:

- **hw42** (7792a9c8 + arrival timing 03baaaf3): the opening came at war day 1772-1913 as two 8,000 prongs - and the held prong could not sail on its day: each prong had been priced for fuel on its own, and the first one's 30-ly passage took the whole stock (~100k). Fixed 13ed59c8: the campaign sums fuel and supplies away, a held prong's fuel is set aside with its bill.
- **hw43**: hw43c did the thing - after its opening it saved two months and launched a 9-prong campaign (one now, eight to follow, "arriving together in ~98 days"), every prong sailing on its day. hw43a/b had no war by day 1800: with no fuel in stock every prong priced as none inside `stagedPlan`, so the campaign saw "0 targets" and never saved for fuel. Fixed f25156ae: pricing skips the live gates, the fuel it saves for is booked as demand.
- **hw44** abandoned at m3 for the sizing change (9a64880a): a prong is priced for the defence seen plus the faction's navy seen elsewhere shared among the campaign's prongs at it, times a learned answer ratio.
- **hw45**: 21 targets known, 34k in the fund, "nothing the fund pays by the deadline" - the hives on this sector bank 0 supplies (all of +21k/mo spent on growth, structures and the navy charge), so no prong passed the supplies-away rule; and hw45b read "losing" from an exchange lost before any war, and with no target within 10 ly could never open. hw45a opened just before the kill: one 12,014 FP strike at Chicomoztoc sized for 8,194 expected (the Hegemony's whole known navy on the one prong). Fixed 445bb134: supplies a budget, losing needs a war and falls back to all known when nothing is near.
- **hw46**: three games at "launch in 0 month(s)" to day 3000 - the supplies budget counted the spare flow to the deadline as if banked, and the hive banks none of it. Fixed daa6bdd7: the budget is what `canSustain` allows (free stock plus the spare flow over the trip), the first prong left out is booked as demand, and a prong's price in the ranking includes the supplies its trip burns.
- **hw47** (daa6bdd7) ran to the end, war day 3765 / 3794 / 3791, no exceptions. **Humans 3-0: the swarm extinct in a and c, 10 hives in b.** Campaigns 7 / 3 / 3, strikes 23 / 8 / 12, held prongs sailed 0 / 1 / 2 (one dropped, target gone); human sieges 116 / 68 / 100, eradicated 19 / 14 / 24 of 18-23 founded. Openings: 8,430 FP at Chicomoztoc for 6,658 expected (a), 8,137 (b, at day ~2650 - it waited on supplies), 10,238 for 7,334 expected (c); each lost (off-screen: 10,000 vs 2,301 defending, half the strike lost), the exchange flipped the stance to losing, and the follow-ups were 1-3 prongs of 1-7k at the Luddic worlds and nearby bases. The swarm held 20-31k FP of fleets and **0 supplies the whole run** (+11-26k/mo made, all spent); the humans' built navies reached 22-26k FP (Hegemony, yards 1,300-1,400/mo, 628k supplies of standing upkeep paid in full). The learned answer ratio never reported (23 strikes ended, no `noteMet`): the report read the target back from vanilla's `allowedTargets` at the end; now from what was captured at launch, with a log when it skips (untested).

**Read:** the offensive works as a mechanism - saving, multi-prong, arriving together, sized for the expected answer - and on this sector it is supplies-sized: a 36k FP campaign at 40 ly needs ~160k supplies away, the spare flow keeps 8-15k FP away at a time, and the swarm pays the same supplies to keep a 20-30k FP navy at home (the navy charge). The lever is the user's: draw the prongs from the spare garrisons (`strikeStagedGarrisons`), hold less (want at the patrol table + launch stock), or accept one strike at a time here. A single lost opening reads as "losing" (8k lost, 0 killed), so the swarm turns cautious after its first blow - whether losing should mean hives falling is a one-line call in `ThreatStance.losing`.

## 43. hw48-hw49 - the spare garrison in the prongs, losing by degree (2026-10-07)

The user's two instructions of the handover (`strikes-staged.md` 3-4), built as 8fe40060; the user confirmed the losing shape ("yes proceed with tests"). Second sector, against hw47's humans 3-0.

- **hw48** (8fe40060) stopped at war day 2486 / 2993 / 2308 (m75-89) for five faults. What worked: openings of 24k FP with 17k of spare swarms (a, b) and 13.8k with 6.9k (c), campaigns of 3, 6 and 8 prongs, the losing pressure moving by degree (0.12-0.67, no hive fallen in a and c; b 7 of a 24-hive peak). State at the stop: a 22 hives 17.6k FP, b 17 hives 4.5k FP (14 eradicated), c 20 hives 27.9k FP; the fund unspent at 16-33k (+0.8-1.1k/mo) - the spare pays most, fuel and supplies bind. The faults: (1) 4-7 held prongs "cannot sail today" - the spare was shared in rank order but each muster takes the first fleets of a system's walk on its day, farthest first, so a held prong met a bigger fleet than it was priced for (1,954-7,390 FP against 674-1,349) and its set-aside fuel did not pay it; (2) launches of 0 strikes logged as launched - the campaign's pricing floors the spare flow at 0, `canSustain` at launch read it negative and refused a trip that burned nothing new; (3) the spare's supplies credit counted the navy charge as paid while it starved (68-88 swarms lost to it; 11-38 "owes supplies for its fleets away" against hw47a's 17 in a whole run); (4) the answer ratio still never learned - 28 strikes "ended with no read": off-screen strikes land through the abstract siege and never stand in the system as fleets, and only `autoresolve` sampled them; (5) the status file: a `tail -F` on `sbs-status.txt` holds it locked and every status line of the batch fails (`testing-harness.md`). Fixed: the spare is shared in sail order (farthest first) in the final pricing; a trip with no new burn is always sustainable; the credit is scaled by last month's paid share of the navy charge (`ThreatColonyManager.navyPaidShare`); an off-screen strike samples from its route's place (`ThreatStrikeFGI.sampleMet`); "none of N planned prong(s) could sail today" keeps the campaign.
- **hw49** (e579f186) ran to the end, war day 3760 / 3769 / 3800. **Humans 2-1 by eradication**: a 23 hives 17.0k FP (1 eradicated of 23 founded, no forward base founded against it), b extinct (28 of 24+ founded), c 12 hives 11.8k FP (16 of 22). Against hw47 (3-0, two extinctions) one game more survives and the swarm's own score moves little: founded 23 / 24 / 22 (hw47 18 / 21 / 23). Launches 12 / 9 / 11 of 1-11 prongs (a: 33k FP with 23k of spare swarms, 14k with 9k; b: 14k with 9k; c: 10k, 9k, 7k), held prongs sailed 7 / 9 / 4, "cannot sail today" 7 / 5 / 10 - every one "the spare supplies do not keep N FP away" (two on fuel in c). The fund still piles up (43k / 37k / 30k at +0.5-1.1k/mo): the spare pays the prongs, supplies gate the launches. Swarms lost to the unpaid navy charge 8.8k / 15.8k / 14.4k FP (hw47 10.4k / 26.0k / 14.9k). Losing pressure ran 0.1-0.96, exchange-driven in a and c with no hive fallen (a ended at 0.96 with 15.2k lost beyond sunk of 15.8k made) - the confirmed shape, `losingExchangeShare` the user's knob. Two faults found: (1) the met read never happened - not the route's place (hw48's fix) but the sentinel: `metSampleDay = -1` for "not yet" when the calendar's day is -642,000 (`facts.md` "Why is 0 a bad never"), so every strike of every batch since the answer ratio was built reported "no read" (18 / 38 / 35 here) and the ratio never left 1; a flag since. (2) The held prongs' supplies: the spare's credit for garrison swarms away was given at launch only; the next monthly feed counted the strike away at its whole burn (`Reach: spare -1562 supplies/mo` the month after a 26k campaign launched) and the held prongs were refused on their day. Fixed for hw50: the feed takes back the navy charge the garrison swarms away no longer pay (`ThreatReach.navyShedPerMonth`), and held prongs reserve their supplies a month in `ThreatReach.spare` (the schedule's 9th field, `ThreatOffensive.heldSuppliesPerMonth`). Logs kept as `ti-hw49*-keep.txt`.
- **hw50** (20a1568a, the Fable review's fixes) ran to the end, war day 3783 / 3770 / 3784. **The swarm survives everywhere**: a 17 hives 37.5k FP (34 founded, 18 eradicated), b 31 hives 49.5k FP (42 / 15), c 31 hives 39.7k FP (48 / 21) - against hw49's 23 / 0 / 12 and hw47's 0 / 10 / 0. The humans' forward bases founded 48 / 28 / 79, lost 32 / 8 / 39. What changed it: the navy charge in the feed's spare - swarms lost to it 77 / 0 / 0 FP (hw49 8.8k / 15.8k / 14.4k) - and the met read: 38 / 21 / 54 strikes reported what they met, none "no read", the answer ratio learned to 1.51x (a, independent) and 2.01x (c, persean). The deadline counts down now (saving lines read "deadline in 10 ... 0"); no campaign launched *at* it yet - the fund paid each before. Launches 10 / 5 / 14, held prongs sailed 7 / 1 / 11, refused 12 / 6 / 14, all on supplies. Traced in c: a 9-prong campaign from one staging system, eight held for 2-10 days; in those days `redistributeGarrisons` sent eight Defense Swarms out of the system (`Reinforcement: Gilead -> Beszel`, 7 remaining to 1) and the upkeep recycle scrapped one more, so every held prong re-planned bigger from the fund and was refused on the spare (-523/mo). The earmarks were honoured by `stagedSpares` alone. Fixed for hw51: a system with an earmark donates no garrison (`redistributeByPressure`, `pickDonor`) and recycles none for upkeep (`recycleForUpkeep`) until its prongs sail. Losing pressure ended at 0.02-0.11 (one hive fallen, exchange far below fabrication). Logs kept as `ti-hw50*-keep.txt`.
- **hw51** (16de1695, earmarks honoured by sends and the recycle) ran to the end, war day 3810 / 3790 / 3784. **Humans 1-2**: a 6 hives 6.8k FP (35 founded, 33 eradicated - the swarm shrinking from a 22-hive peak, losing pressure 0.34-0.75 read right for once), b 35 hives 48.9k FP (61 / 30), c 23 hives 46.3k FP (40 / 21). The humans' forward bases founded 48 / 82 / 69, lost 93 / 47 / 30. Launches 7 / 17 / 11, met reads 38 / 69 / 51 (none "no read"), no navy starvation. Held prongs sailed 3 / 13 / 5, refused 0 / 9 / 19 - still on supplies, with the earmarked fleets now in place: in c a 10-prong campaign (12.5k FP, 12.7k of it spare swarms) launched with the spare at -92 a month and the stock paying; by the held prongs' days (2-15 later) the spare read -1,086 with 21 sends in flight refilling the emptied staging systems and the first prong away, and the stock below the founding kit, so eight of nine were refused ("the spare supplies do not keep 651 FP away 138 days" - the 25% of a spare swarm's burn its home charge does not credit). The flow hold (`heldSuppliesPerMonth` in `ThreatReach.spare`) protected nothing: `canSustain` lets any trip short of flow draw the stock. Fixed for hw52: a held prong's trip supplies leave the stock at launch and return on its day, as its fuel does (`ThreatOffensive.heldSupplies`, schedule field 10); the flow hold is gone from `spare`. Also for hw52: hulls move like any good (`player-aid.md` 4-5) - the humans' shortage in hw50 was hulls, not provisions. Logs kept as `ti-hw51*-keep.txt`.
- **hw52** (cd61450d, prepaid trips and hull aid) stopped at m75 (war day 2444 / 2163 / 2319) for two faults. Held prongs sailed 1 / 3 / 9, refused 8 / 0 / 3 - in a, a 10-prong campaign (9.8k FP, 9.6k of it spare swarms) launched on day 2195 and eight of nine held prongs were refused on their days, "the spare supplies do not keep 648 FP away 149 days", with the trip supplies back in the stock: `poll` returned them and `launchStrike` gated the whole trip again, so each prong alone was charged the flow the campaign as a set had made negative (-283 a month x five months) against a stock under the founding kit. Fixed for hw53: the day's gate charges only what the plan burns beyond the prepaid trip (`ThreatReach.setPrepaid`, read by `canSustain`). Hull aid never showed: every allied convoy's load is 5-long now and `ThreatConvoys.sailed` indexed the escort's burn (fuel, supplies) by the load's index - `ArrayIndexOutOfBoundsException` in b and c on the first allied convoy, so no hull request, convoy or landing can be read from this batch. Fixed (the burn is read for fuel and supplies only). The swarm stood at 25 / 29 / 30 hives when stopped. Logs kept as `ti-hw52*-keep.txt`.
- **hw53** (edc69a0a, the day's gate charging only the burn beyond the prepaid trip, the convoy index fix) stopped at m65-80 (war day 2474 / 1950 / 2439) by the new watcher (`watch.sh`, 3-minute polls) on its first refusal, read, and stopped for good once the cause was clear. Allied convoys sail clean again (4 / 6 / 2, no exceptions); no hull request or hull convoy yet at m65-80 (the humans had not lost hulls they could not rebuild). Held prongs sailed 3 / 4 / 2, refused 1 / 2 / 0 - in b a 6-prong campaign from Hadon Veleset: four sailed, Gilead (priced 1,136 FP, 96 from the fund, 999 expected) planned 1,468 FP on its day and Athulf (priced 565) 995, both refused on the burn beyond the prepaid trip against a stock at the founding kit. The earmark is a count of fleets a system, so a held prong finds on its day the first fleets left in the walk, not the fleets it was priced on - bigger here. Fixed for hw54: a held prong's plan is capped at the size it was priced at (`IncursionManager.setPricedFP`, schedule field 11) and not gated on supplies again (`ThreatReach.setPrepaid` read by `canSustain`): the campaign paid for that size and that trip. The refusal line now prints its figures (`ThreatReach.sustainNote`: spare swarms, new burn, prepaid, flow, free stock). Swarm at 28 / 25 / 20 hives when stopped. Logs kept as `ti-hw53*-keep.txt`.
- **hw54** (the priced-size cap, not gated again) stopped at m72-91 (war day 2172 / 2359 / 2743) by the watcher's balance signals. The cap holds: a launched 4 campaigns, 5 held prongs sailed, 0 refused. b and c launched nothing until day ~2300 / ~2700: "nothing the fund pays by the deadline - 21 targets, fund 36k" 30 / 50 / 47 times, with 7.6-8.5k FP of spare swarms idle - the pricing's two supplies checks had no floor for a prong burning nothing new, so with the spare negative (b: -2.7k a month, sustenance 19.5k + navy charge 12k over a 35-40k output) a campaign needing 0 supplies was refused; `canSustain` has had that floor since hw48. Fixed for hw55. The balance behind it, the user's to weigh: the swarm's supplies stock peaks at 45-75k with 4-7 hives and falls to ~0 as it founds 17-24 (hw53 the same), and from then on the Reach spare is negative - the navy charge alone is 12k of a 35-40k output. c's one refusal, with the new figures: a 3-prong campaign of 25k FP, all spare swarms; on the third prong's day "0 FP of spare swarms, new burn 5794/mo = 22330 for the trip, prepaid 0, flow -6669/mo, free stock 0" - its earmarked fleets were not there, and it re-planned 8.1k FP from the fund. Logs kept as `ti-hw54*-keep.txt`. (hw55 was started on the pricing fix alone and stopped minutes in, once the refusal's figures had shown the earmark fault; no record.) Fixed for hw56: a held prong's own earmarked fleets are offered to it on its day whatever its systems' reserves read (`IncursionManager.setLaunchingEarmark`, read by `stagedSpares`) - `ownAvailableForLaunch` read the colonies that gave the earlier prongs their swarms as "regrowing" and offered the third prong none.
- **hw56** (the launching earmark) flagged by the watcher at m20 (`broke`, all three at war ~1750-1830: the known supplies curve, fund ~20k, stock 29 / 1,461 / 2,325 against 21-24k a month) and at m24 (`ref`), then left to war 2728 / 2592 / 3019 for the record and stopped. Held prongs: a 0 sailed / 12 refused, b 5 / 7, c 8 / 10 - every refusal "0 FP of spare swarms ... prepaid 0, free stock 0" on a prong priced from spare swarms (a's first: 683 FP planned from the fund for a 269-FP prong of 2 earmarked fleets). The launching earmark forced the COUNT offered but not the walk behind it: `musterPool` gated each colony of the system on `ownAvailableForLaunch` too, so every colony that had given the pass's strikes a swarm read "regrowing", gave nothing, and the walk fell back on the staging colony's own garrison; a system whose staging gates (forge, fuel, nexus) had closed since the pass was skipped outright. Fixed for hw57: the earmark's muster takes live fleets from every colony of the system (`IncursionManager.launchingEarmarkCovers`, read by `musterPool` for peek and consume alike), the staging pick is ungated for it, and each earmarked system logs "Earmark at X: N fleet(s) set aside, M found (FP; available, earmarked by others, live)". The pricing floor works: a saved for a 323-FP prong with 8,079 FP of spare swarms and 0 supplies under a negative flow and launched it; b read "nothing the fund pays by the deadline" at 21.7k FP with 20 targets for a while (to read in hw57 with the Earmark lines). Outcomes: a 9 campaigns / 12 strikes, 20 hives, forward bases 35 founded / 3 lost, fund 52k; b 12 / 24, 20 hives, 25 / 8, fund 52k; c 11 / 23, the swarm fell from a 23-hive peak to 2 hives by war 3019 (31 razed/decivilised lines) with 44k in the fund - the humans' first win on this sector; the watcher's `collapse` would have fired had it still been running. Hull aid: no hull convoy in 2,600-3,000 war days, and no case for one - the only faction short of hulls was Luddic Path in c (1,243 FP lost, no yards, 0 free), hostile to every would-be donor; the yard factions rebuilt their losses within the month (Persean League 3,959 built and 6,699 free, Independent 7,788 built and 6,258 free at c's end) and were never short. Exceptions 0. Logs `ti-hw56*.txt`.
- **hw57a** (the earmark's muster ungated; the first run from the pre-war checkpoint `ck1`, one game at a time - testing-harness.md) stopped at war 2077 by the watcher's `ref` at m7: held prongs 4 sailed / 8 refused, still "0 FP of spare swarms". The new Earmark line settled it: "Earmark at Labraxas: 2 fleet(s) set aside, 13 found (2930 FP; 19 available, 6 earmarked by others)" two lines above a refusal - the fleets were found and offered, and `stagedPlan`'s per-fleet `canSustain` refused the FIRST of them: a prong of spare swarms alone prepays 0 supplies (its trip burns nothing new as a whole), `canSustain` skipped its gate only for a prepaid amount above 0, and the first fleet's re-embodied estimate a few FP over its own fleet points is a "new burn" against a negative flow. The plan then built one fleet from the fund (620 FP for a 1,215-FP prong) and was refused for its supplies. Fixed for hw58: the prepaid MARK skips the gate whatever the amount (`ThreatReach.setPrepaid` / `clearPrepaid`, `prepaidSet`), and the pass's prongs sailing now launch under the same mark and the priced-size cap as the held ones (the campaign's supplies gate stands for its prongs). Exceptions 0; 16 hives, forward bases 14 founded / 1 lost. Log `ti-hw57a-keep.txt`.
- **hw58** (the prepaid mark; from the checkpoint `ck1`, one game at a time, ~25 min a game to war day 3800) - the first batch where every held prong sailed: a 6 sailed / 0 refused, b 6 / 0, c 8 / 0 (campaigns 16 / 10 / 11, strikes 71 / 51 / 86, of them relief strikes for the swarm's own fronts 49 / 35 / 66). Exceptions 0. Hull aid works: 9 / 8 / 8 convoys sailed and landed (Hegemony to Luddic Church from Chicomoztoc; Tri-Tachyon to Hegemony, Persean League and Independent from Culann; the ledgers read "losses rebuilt"). Outcomes at war ~3800: a 20 hives at peak -> 11, forward bases 66 founded / 28 lost, fund 1.9k; b 16 -> 7, 56 / 27, "nothing the fund pays" at 15.7k with 135k supplies banked; c 24 hives at its peak at the end, 60 / 30, fund 47k hoarded since war 2952. Against hw56 (prongs refused) at war 2700: hives 18 / 12 / 18 vs 19 / 20; human sieges on the hive 906 / 2,104 / 469 vs 762 / 504; garrison held at the end 6.9k of 19.1k wanted (a), 6.2k of 14.8k (b) vs hw56a's 19.3k of 25.4k; 33 of a's 68 strike ledgers ended "no hive left" (the hive that sent them fell meanwhile); strikes razed nothing, forward bases fall at half the rate they are founded. The swarm trades its defence for strikes at cheap bases and loses the exchange (-17k to -33k FP a year). Where the swarms came from: the staging is the hive system nearest the target, the spare gathered nearest the staging first, each world to its reserve and nothing while regrowing - Labraxas gave 49 fleets in 22 musters, Strathcona 46 in 22 (left with 2), Acorn 30 (2), Kharon 15 (1), while Yesod in the rear gave 1 and kept 11 -> the user's decision (facts.md Decisions, "Where a strike takes its swarms"), built for hw59. c's hoard: "nothing the fund pays by the deadline" with 44-47k FP and 15-28 known targets - the campaign loop drops prongs from the least valuable end until the set is affordable, so a top-valued prong no fuel budget pays (Mairaath, defence 6,722; relief strikes of 12-19k FP "the fuel stock does not pay the passage") empties the campaign; flagged to the user, not changed. Humans' pools: 207 / 221 / 272 STARVE lines, mostly Sindrian Diktat and Hegemony saturation plays the pools cannot pay. Logs `ti-hw58*-keep.txt`.
