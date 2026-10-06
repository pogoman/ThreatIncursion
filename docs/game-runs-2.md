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
