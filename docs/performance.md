# Performance - where the late war's frame time goes

**Measured 2026-10-27 22:35-22:48** with Java Flight Recorder (`jcmd <pid> JFR.start settings=profile`, 90 s,
6,490 samples of the game thread) on ONE game alone from the hw297a snapshot at war day 2,795 (281 hives,
328 campaign fleets), and the same seed at war day 315 (56 fleets). Scripts: scratchpad `occ/profile.sh`,
`occ/under.awk`, `occ/entry.awk`; the JDK's `jcmd` / `jfr` attach to the game's Zulu 17 fine.

| | war day 315 | war day 2,795 |
| --- | --- | --- |
| speed alone, 64x | 196 days a minute | 22 days a minute |
| speed in a batch of three | ~90 at d1,400 | ~20 |
| campaign fleets in the save | 56 | 328 (Patrol Swarm 142, Second Strike 60, Infestation Swarm 22, Task Force 19) |

Running alone is no faster than in the batch, so the slowdown is the war's size, not the machine. The
machine's own limit is memory: each game is 3.6 GB private (vmparams `-Xmx2048m` + `AlwaysPreTouch`),
three at once leave 585 MB of 16 GB and the pagefile fills - run two at a time while the machine is in use.

**Where the 6,490 samples at war day 2,795 go (one count per sample):**

| share | frames | driven by |
| --- | --- | --- |
| 60% | `ModularFleetAI.advance` -> `TacticalModule.advance` -> `pickEncounterOption` 47% (`Misc.getMemberStrength` 24%, of it `DModManager.getNumDMods` 17%; `FleetMember.canBeDeployedForCombat` 16%) | vanilla's fleet AI weighing every fleet it can see, every frame: fleets x fleets in range x ships per fleet |
| 15% | `Economy.advance` -> `Market.advance` (`MutableStatWithTempMods.advance`, `CommodityOnMarket.reapplyEventMod`) | vanilla's per-market work, one market per hive (281 + the humans') |
| 7% | `IncursionManager.advance` (fronts poll 1.1%, tick 1%, scouts 0.9%, leash 0.6%, patrols 0.5%, budding 0.4%) | the mod's own per-frame work - all of it |
| 5% | `ThreatStrikeFGI.advanceImpl`, terrain, hyperspace, intel | |

The mod's code is 7-8% of the frame; the rest is vanilla working on what the mod spawns. The fleet AI
cost is quadratic in fleets that can see each other and linear in their ships, so the levers are the
NUMBER of campaign fleets in play and how many sit in the same systems as human fleets:

- **Swarm patrols** (`ThreatSwarmPatrols.launchAll`): four 30-FP patrols per hive system, continuously -
  ~120 one- or two-ship fleets roaming human space at once (census "59 out, 62 returning, 3,493 FP").
- **Strikes** spawn as 5-39 fleets each (`fleets are at most difficulty 10 (250 FP)`, ~15 ships), and 60-90
  of them sit over sieged and occupied worlds for years, each running full fleet AI against the human
  fleets there.
- **Infestation Swarms** budded by the occupation pool: 22 standing.
- Garrison swarms (`ThreatSwarmDefend`, 471k FP) are abstract until needed and cost nothing.

**DECIDED 23:00:** the user took 1 (as reabsorption: a quiet orbit absorbs parked fleets into the pool, the overflow goes home - `ThreatOccupation.absorb` / `standDown`), 2 (`swarmPatrolFP` 120) and 3 (`maxShipsInAIFleet` 60 - vanilla's cap, not ours; "small fleets are super annoying, especially as the player they all arrive separate and die one at a time"). Jar 23:05, round 66 hw298.

**DECIDED 2026-10-29 (the user): ONE FLEET.** "its super annoying having 8 300FP fleets and as i understand it it causes more lag" - every force the mod builds sails as one fleet, however many ships (`threatinc_oneFleet`, true): an expedition's fleets fold at the spawn (`ThreatPurgeFGI.createFleet`), a strike packs whole (`ThreatColonyManager.maxShipsPerFleet`), garrisons, reliefs, hunts and the player's forces fold with no `softenMergeMaxShips` stop. The fleet AI's cost above is fleets x fleets in range x ships, so one fleet of 300 ships weighs its neighbours once where eight did it eight times over. Battles join as one body. `facts.md` has the symbol list; untested as of the jar.

**Options (2026-10-27 22:50, the user's call):**
1. Sleep quiet swarm fleets: `CampaignFleetAPI.setDoNotAdvanceAI(true)` for a strike / infestation
   fleet parked at a world with no hostile fleet in its system, woken by the mod's own tick when one
   enters (`hostileFleetsNear`). No balance change; removes most of the 60-90 parked fleets' AI cost.
2. Patrols as one 120-FP fleet per hive system instead of four of 30: a quarter of the patrol fleets,
   the same FP; routes cover a quarter as many stops at once (scouting breadth drops).
3. Fewer, bigger strike fleets: raise the per-fleet cap from 250 FP toward vanilla's 30-ship limit
   (~420 FP at 14 FP a ship) - ~40% fewer strike fleets; battles join in bigger lumps.
4. Hives out of vanilla's economy tick: not possible as built - the hive economy reads vanilla's
   supply/demand (`ThreatColonyManager.planHiveEconomy`); 15% ceiling anyway.

A player's own game at this war size pays the same frame cost at 1x speed (the AI runs per frame, not per
game day), so this is a player-facing problem, not a harness one.
