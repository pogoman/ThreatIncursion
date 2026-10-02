# War simulator: the swarm side

The swarm half of the offline simulator (`war-sim.md`): `tools/warsim/src/warsim/SwarmSide`
and its helpers. Built 2026-10-02 against the dated run pd9a and the probe dumps. Everything
here is named by symbol; the numbers are as last run.

Build `tools/warsim/build.ps1`; run `tools/warsim/warsim.ps1 run -start <dump folder> -months 36 [-v]`,
`check -dumps <folder> -seeds 50`. A dump folder's earliest dump is the start.

## 1. Files

| File | What it holds |
|---|---|
| `SwarmSide` | `init` (new game or war under way), the three clocks in `daily`, `arrive` |
| `SwarmEconomy` | banks, garrisons, the two stocks and their trailing demand, size upkeep, growth, the planner, nanoforges, the military tier |
| `SwarmPosture` | the posture poll, stance, surplus recycling, reinforcement |
| `SwarmOps` | the 30-day tick: opening chain, claims, Seeding Swarms, in-system expansion, strikes, scouts; arrivals |
| `SwarmKnobs` | every knob the swarm side reads, once a run (a missing key throws) |
| `SwarmFit` | every constant that is not a knob: the mod's own constants copied, and the fitted ones with their log line |
| `Hive`, `Swarm` | state: the dump's fields, then the swarm side's own |

Clocks: `SwarmEconomy.daily` and `SwarmOps.daily` every day; `SwarmPosture.poll` every
`postureDays`; `SwarmOps.tick` every `tickDays` + `SwarmFit.TICK_OVERRUN_DAYS`.

## 2. Rules shared with the mod (`src/threatinc/rules/`)

Pure static functions on primitives; the mod calls each one, so a change there changes both.
No behaviour change was intended by the lift; the in-game regression run has not been done.

| Rule | Mod call site |
|---|---|
| `HiveRules.fabricationRatePerDay` | `ThreatColonyManager.fabricationRatePerDay(market, hiveOutput, hiveDraw)` |
| `HiveRules.upkeepPerDay` | `ThreatColonyManager.upkeepPerDay` |
| `HiveRules.seedEndowment`, `ENDOWMENT_DAYS` | `ThreatColonyManager.endowSeed` |
| `HiveRules.desiredGarrison` | `ThreatColonyManager.desiredGarrison` |
| `HiveRules.swarmCostFallback` | `ThreatColonyManager.swarmCostEstimate` (the unlearned table) |
| `HiveRules.sizeUpkeepPerMonth`, `breakEven`, `growthRate` | `ThreatColonyUpkeep.perMonth`, `breakEven`, `growthRate` |
| `HiveRules.daysPerLevel` | simulator only so far (the mod's `advanceFedGrowth` computes it inline) |
| `PostureRules.decay`, `claimCap`, `releasableFP`, `breakEvenDays` | `ThreatPosture.decay`, `claimCap`, `releasableFP`, `breakEvenDays` |
| `StanceRules.expansionShare`, `spreadMult` | `ThreatStance.expansionShare`, `spreadMult` |
| `StanceRules.feedShare` | `ThreatColonyUpkeep.feedShare` |
| `SpreadRules.passageFuel` | `ThreatFuel.passage` |
| `SpreadRules.days`, `daysAway` | `ThreatReach.days`, `daysAway` |
| `SpreadRules.billedWeight` | `IncursionManager.pickSpreadTarget` |
| `StrikeRules.canSustain`, `sustainableFP` | `ThreatReach.canSustain`, `sustainableFP` |
| `StrikeRules.sizeValue` | `IncursionManager.strikeValue` |
| `BattleRules.lossShare` (not ours) | called by `SwarmOps.strike` |

## 3. Modelled, not shared

Re-implemented from the mod's code, by the symbol it follows. A change to the mod here must
be made twice.

- **Bank and garrison** (`maintainColonyGarrisons`): income by draw, `min(best forge's units, size)`
  per colony with core and nexus up; upkeep; `recycleForUpkeep`; up to two swarms a day toward
  the want, the size table's next row. The garrison is a list of fleet FP (`Hive.swarms`);
  `SwarmEconomy.sync` reconciles it with `garrisonFP`, which the human side lowers, and books
  the loss into the posture and the stance's ledger.
- **Swarm costs**: `SwarmEconomy.estimate` is the learned mean per "fabricators:tier", else the
  shared fallback; what a swarm really weighs is `SwarmFit.builtFP`.
- **Wants** (`ThreatPosture`): `baseFP` = the reserve's cheapest rows plus, on a forge colony of
  size 3, `LAUNCH_STOCK` rows; `regrowing`, `available`, the system pool, `takeSpare`.
- **Stocks** (`ThreatFuel`): 750 supplies a forge unit and 1500 fuel a plant unit a month; the
  trailing demand (tau 120 d), `runsDryWithout`, `wanted`, `mayAnswer`, `wantsSpare`; fleets away
  burn `SUPPLIES_PER_FP_AWAY`, a month owed recalls outbound strikes (`SwarmOps.starveAway`).
- **Size upkeep and growth**: `SwarmEconomy.feed` follows `ThreatColonyUpkeep.feed` (sustain
  order, pool, growth budget by stance, the seed colony's share); `grow` follows `advanceFedGrowth`.
- **Planner** (`planHiveEconomy`): `SwarmEconomy.choose` in the mod's order - batteries, ground
  defences, the slot cap, mining, each link's first copy, shortage answers, the invested forge,
  orbital works, spares by `spreadAllows`. One build a call, bought when the supplies pay
  (`buyWaiting`). Deposits are not modelled: every world can mine.
- **Nanoforges**: the pristine one on the first built forge, a corrupted one on each later forge
  by `forgeNanoforgeChance`, placed at the tick.
- **Military tier** (`maintainMilitaryTier`, `militaryIdle`): as the mod, in fleet points.
- **Posture** (`ThreatPosture.poll`): pressure = the larger of attacks (human parcels holding in
  the system of kinds SIEGE, SATURATION, HUNT, SQUADRON) and staged (MUSTER parcels whose
  `against` is the system), plus decayed losses; the mod's forward and hostile terms are 0.
  Need, the hopeless test, wants, modes with hysteresis, appetite as the mod. Wounded = a front.
- **Surplus** (`recycleSurplus`): after the break-even days one fleet a colony, the largest that
  fits the surplus; the clock then starts again. **Reinforcement** (`redistributeByPressure`):
  a spare swarm from the nearest donor, else `fabricatorFor`; across systems a REINFORCEMENT parcel.
- **Stance** (`ThreatStance.evaluate`): trend ledger, hive window, pressed share, dwell and
  hysteresis as the mod. The weak-target test is simplified: a seen, strikeable world of a
  faction the whole swarm outweighs by `stancePressRatio`, at odds within `stanceWeakOdds` of
  the system's spare swarms. The pressing colony's extra want (`extraWantFP`) is not modelled.
- **Opening** (`pickOGSystem`): an empty system with room for the chain, at least half as far
  from any world as the farthest; `Swarm.ogSystemId` overrides. Claimed on day 0, five free
  waves `seedToColonyDays` later, each landing with a structure of the chain.
- **Spread** (`trySpread`, `launchColonizationWave`): one claim a tick under `claimCap(free forges)`,
  the first gated on fuel; the weight is the shared one with the need score a random draw and
  the hold share 1; a claim matures in `seedToColonyDays` and launches at a tick from
  `pickSource`, paying 5000 supplies, `outpostFuel` and the passage, and the bank the wave's FP
  beyond the swarm that left. In-system expansion needs no claim; a held system's spare planets
  are rolled once (`rollExpandable`).
- **Strikes** (`tryStrikes`, `launchStrike`): each hive system, shuffled, one a tick; at least two
  spare swarms; every spare swarm sent, fewer while fuel or supplies cannot carry them; the
  gate reads the strike at 300 a swarm against the defence last seen (`strikeFrom`). On arrival
  `BattleRules.lossShare` both ways (the fleets pay, the world's `defence` is not worn); a
  forward base is destroyed at once; at a colony the troops land and the hulls fly home to be banked.
- **Landings** (`SwarmOps.land`, `fronts`; `Swarm.Landing` per world): troops = `TROOPS_PER_FP` x
  the FP that reached the orbit, none under `LANDING_MIN_TROOPS`. Decided the day after: a colony
  of a mobilised faction counter-attacks and overruns the beachhead after `overrunDays`
  (`beachheadsOverrun`); one with no reserve (pirates, the Path, a faction not at war) falls after
  `groundDays`. A later strike on an invaded world reinforces (`threatReinforcePasses`,
  `reinforcedDays` more). The front itself (strata, counter-attack odds) is not modelled.
- **Target weights** (`pickStrikeTarget`): `ThreatAlarm`'s grudge (`alarm`, `targetMult`,
  `alarmDecay`; the human side calls `stratumTaken` and `hiveLost`), relief first with
  `strikeReinforceWeight` for a front on a colony at war, passed by the gate on
  `INVADED_GATE_SHARE` of ticks (`Landing.gateOpen`).
- **Retaliation** (`IncursionManager.retaliate` -> `SwarmOps.hiveLost`): a hive eradicated draws
  a strike at once at the winner from the nearest system that can muster one (`retaliations`).
- **Fog** (`ThreatSwarmIntel`, `ThreatSwarmScouts`), the simple model chosen: a world is seen
  while the swarm is in its system (a hive, a wave, a strike, a scout; `SwarmOps.radar`), by the old Bastion radar within
  `warsim_swarmRadarLY` only while that simulator switch is > 0 (the mod's knob is gone since 262ac76), and when a Scouting Swarm reaches its
  system. Each hive system may send one scout a tick to a random inhabited system unseen or
  stale. A strike needs a sighting and reads its defence, however old. Contacts are not modelled.
- **War open**: before a hive reaches size 6 only mobilised factions, pirates and the Path are
  struck, and no world of size 6.

## 4. Fitted constants (`SwarmFit`)

| Constant | Value | Fitted from (pd9a, war day) |
|---|---|---|
| `TICK_OVERRUN_DAYS` | 0.35 | "Spread to" lines w368..w1066, 23 ticks in 698 days |
| `OG_CHAIN` | 5 | 5 hives at month 5 in both runs |
| `bootstrapTravelDays` | 12-22 | waves w126, landings w138-w148 |
| `bootstrapSwarmFP` | 700-745 | 3,618 FP in 5 fleets at month 5 |
| `waveLandingDays` | 8-13 beyond the passage | launch to "Colony founded" 11-18 days at 1-4 ly |
| `WAVE_FP` | 600 | "Seeding Swarm from ..: 600 FP of hulls, 0 FP of structures" |
| `builtFP` | 90 / 140 / 340 / 458 +40 a fabricator, x0.78-1.22 | "Garrison fleet fabricated" lines |
| `SUPPLIES_PER_FP_AWAY` | 0.5 | "Reach: .. fleets away N/mo, 0.22-0.65 a FP" |
| `EXPANSION_PLANET_SHARE` | 0.17 | 2 in-system waves over 12 spare planets by w1100 |
| `STRIKE_UNITS_PER_SWARM`, `_PER_FP` | 300, 2.1 | strength 916/1200/900/1500 for 3/4/3/5 swarms |
| `strikePrepDays` | 7-14 | `launchStrike` |
| `groundDays` | 50-96 | Kanni 50, Kanta's Den 71, Qaras 96; all 7 falls were pirate or Path worlds |
| `TROOPS_PER_FP` | 0.8 | "Abstract siege of Salamanca: .. 700 FP" then "560 troops"; 0.80 in 47 of 60 landings |
| `LANDING_MIN_TROOPS` | 50 | "Strike landing at X aborted: only N troops left aboard" |
| `overrunDays` | 40-239 | 44 unreinforced fronts overrun after 19-248 days, p10 50, median 136, p90 228; 0 of 56 took the colony |
| `reinforcedDays` | 100-199 | 12 reinforced fronts ran 187-751 days, median 405 |
| `INVADED_GATE_SHARE` | 0.1 | 28 reinforcing passes for 86 landings; stands in for a colony at war's defence (dump: Salamanca 440 -> 5058) |
| `CONQUEST_HIVE_SHARE` | 0.67 | Kanni and Qaras became hives, Kanta's Den did not |
| `SCOUT_SHARE_PER_SYSTEM` | 0.6 | "Reach: .. scouts N": 1-2 with 2-3 systems, 2-9 with 8-10 |
| `STALE_DAYS` | 30 | census "oldest 46 d; stale systems 2", none at 16 d |
| `needScore` | 0-50 | stands in for `systemNeedScore` (deposits) |
| `Hive.outputSize` | fuel output follows size at the next tick | probe dumps: `fuelPerMonth` 1500 ten days into size 4 |
| build prices, `FOUNDING_KIT`, `BASTION_FP`, `COMMAND_FP` | see file | "Build cost:" lines w318-w1066; Bastion 3,749 FP w974 |

## 5. Gate 1 (swarm alone), as last run

`check -dumps simdump-pd9a -seeds 50`, months 12-36: real | simulator median [p10 - p90].

| | month 12 | month 24 | month 36 |
|---|---|---|---|
| hives | 5 \| 5 [5-5] in | 10 \| 9 [7-10] in | 16 \| 17 [14-19] in |
| hiveSize | 15 \| 15 [15-15] in | 29 \| 29 [25-31] in | 65 \| 66 [57-72] in |
| garrisonFP | 4049 \| 3960 [3855-4062] in | 3749 \| 3856 [3601-4014] in | 10509 \| 8975 [6567-10454] OUT |
| bank | 4288 \| 4401 [4272-4470] in | 5223 \| 6105 [1032-6897] in | 5622 \| 4105 [2783-5207] OUT |
| swarmFuel | 1391 \| 1250 [1250-1250] OUT | 3925 \| 8671 [2406-16093] in | 3294 \| 6692 [3639-13601] OUT |
| swarmSupplies | 11209 \| 11307 [11250-11442] OUT | 5836 \| 7308 [2973-14997] in | 2630 \| 1367 [293-2763] in |

13 of 18 inside (19 of 24 with worlds and bases). Against `simdump-probe` (months 12, 24, 26):
21 of 24; out are garrisonFP at month 26 (6137 against 5198 [4164-5781]) and the two month-12
stocks. The month-12 misses are a run with no spread at all: fuel 10% low, supplies 1% high.
After month 37 the real run is at war and the simulator's human side is a placeholder: hives
25 against 16 at month 48.

Also at month 36: strikes launched 11 [7-14] against 7 real; worlds taken 5.5 [2-8] against 3.

## 6. Timing, real against simulated

War days (the simulator's day 0 is war day 4); 50 seeds, median [p10 - p90].

| Event | pd9a | Simulator |
|---|---|---|
| First hive | 138 | 138 [138-142] |
| First forge (the seed forge lands with the chain) | 142 | 138-148, one of the five landings |
| Pristine nanoforge on it | 156 | 156 [156-156] |
| First "Spread to" claim | 368 | 369 [369-399] |
| First Seeding Swarm | 489 | 490 [369-490] |
| Sixth hive founded (first spread lands) | 500 | 504 [379-508] |
| First strike | 641 | 642 [582-703] |
| First size 6 | 1041 | 1039 [1038-1043] |
| Swarm Bastion | 974 | 794 [521-885] |
| Swarm Command | 1156 | 946 [642-1007], in 45 of 50 |
| Days from a hive's founding to its first Seeding Swarm, to month 37 | 347 and 412 (two sources) | 360 [226-474] over 94 hives |
| Days between foundings after the sixth, to month 37 | 62, 62, 60, 33, 26, 68, 27, 212 (median 61) | 35 [8-122], conquests included |

The early p10s (369, 379) are openings in a system with more than five planets: the sixth
planet takes an in-system wave as soon as the fuel is there. Over the whole pd9a run only 13
colonies ever sent a wave, the later ones 700-1700 days after their founding.

## 7. Starting from a war under way

`SwarmSide.init` takes whatever the state holds. With hives present it infers what the dump
lacks: the fleet list from `garrisonFP` / `garrisonFleets`; mining, ground defences, batteries
and orbital works from size; one refinery; the pristine nanoforge on the largest forge and a
rolled corrupted one on the others; spare planets; sightings by radar; the posture from
`Swarm.posture`; a random tick phase. Tested from the probe's month-26 dump: ten months on,
hives 16 [15-18], hiveSize 64 [62-67] against the real 16 and 65.

A parcel loaded in flight with `order == null` is handled by kind in `SwarmOps.arrive`: WAVE
founds a size-1 hive where it lands; STRIKE takes `targetId`, else the largest world there
(`orderOf`), and a strike already holding gets a fresh ground clock; REINFORCEMENT joins
`targetId`, else the largest hive there, else flies on to the nearest; SWARM_SCOUT sees the
system; RAIDER is banked at the nearest hive.

`Start` may set, before `init`: `Swarm.ogSystemId`, `Swarm.claims` (`Claim.sys`, `day`),
`Swarm.seen` (world id -> {day, defence}), `Swarm.nextTickDay`, `Hive.swarms`, and the
`Hive` structure flags. Fields wanted in the dump are listed in the build report.

## 8. Known gaps

- Joined with the human side (2026-10-02, `check` on pd9a, 30 seeds, 133 of 184 figures inside
  p10-p90): strikes 160 [57-309] against 163, landings 92 against 86, beachheads overrun 65
  against 56, hives 52 [19-91] against 56. Out: worlds lost 13 against 7 (pirate and Path worlds
  fall to every landing; Chalcedon held one), supplies stock 273k against 16k and fuel 40k against
  6k at the end (the sinks are missing, or the strikes held for supplies should have sailed).
- A colony at war never falls and its defence is the start dump's: no model of armed marines,
  relief convoys, strata or counter-attack odds (`ThreatGroundFronts` D against E, overrun at 2:1).
  Hull break-up into troops (`beachheadLanding`) and "landing waits: bombardment" are not modelled.

- The Bastion comes about 180 days early and garrisons at month 36 run about 1500 FP low: the
  simulated bank is 800-900 FP richer than the real one over months 17-24, the sink not found.
- Strikes run about half again as often as pd9a's from month 30; fuel at month 36 is high.
- No deposits: `systemNeedScore`, `canSupportFullChain` and the planner's mining choice are
  stand-ins, so where the swarm spreads is noisier than the mod's pick.
- Colonies stop at `colonyMaxSize`; pd9a showed none above 6 by month 46.
- Contacts, the alarm's grudge, `extraWantFP`, relief strikes, sweeps of a whole system and
  the hold share are not modelled. `ThreatHiveMind` is a design, not built, and not modelled.
- A garrison's fight with a siege is the human side's; the swarm side only reads the result.

## 9. Round 3 against pd9a: where the supplies go

- **Defend stations** (`SwarmOps.defend`, `StrikeOrder.defending`): a strike that landed or reinforced a Threat front
  stays over the world until the front ends (`ThreatSwarmDefend`), burning supplies as a fleet away
  (`SwarmOps.burns`); it no longer goes home on landing. pd9a's "Reach: .. fleets away" reads 30-41k FP away in years
  7-9 (20-23k supplies a month) against 11-15k FP of reinforcements and raiders (dump `ownedFP - garrisonFP`); the
  simulator had 17-26k FP and 8-9k supplies a month. Now 16-28k a month (counters `defendStations`, `defendFPDays`).
  Not mirrored: `defendFabricates` (hulls broken up for troops) and relief forces fighting the station.
- **The supplies ledger by outlet**: counters `swarmSupplies.away`, `.sustenance`, `.growth`, `.structures`,
  `.founding` (the split of `swarmSuppliesSpent`). pd9a at month 100: made 77-84k, sustenance 47k of a 93k bill
  ("Colony upkeep: bill"), growth 0-6k, away 20k.
- **What is still off, and why**: sustenance. The simulated swarm pays 18-19k a month at total size 120-200 where
  pd9a paid 47k at 172-185, because its hives are many and small (mean size 3.2-3.9 against 5.4; the upkeep curve
  is convex). pd9a's `ThreatStance` sat in CONSOLIDATE 53 of the 72 months of years 3-8 (feed share 0.9, no founding;
  hives 25 to 32 over months 60-96, then 36 to 56 in the last 7 months of EXPAND); the simulator's leaves CONSOLIDATE
  a month after entering (seed 2: 31 changes against 22). Its pressure reads 4-8k where
  pd9a's read 12-20k with 5 of 10 systems pressed. So it founds through the war, spreads over more systems, each
  less pressed, and the supplies the bigger hives would eat pile up (month 115: 149k [5k-526k] against 16k).
- **The strike gate** reads vanilla's fleet strength in the whole system plus the world's station
  (`IncursionManager.strikeOutweighed`: `WarSimScript.getEnemyStrength`), not the ground defence the dumps carry:
  "Strike gate: X passed over" gives one figure for every world of a system (Umbra, Sindria, Cruor, Volturn and
  Nortia 987-6,332; Thulian Raider Base with Kazeron 1,412-1,866; Donn with Culann 994-2,078; Garnir 1,034-1,327).
  `SwarmOps.defenceOf` reads the dump's `defence` (Donn 55, Garnir 55, Umbra 558), so the simulator takes pirate
  worlds inside patrolled systems that pd9a's gate passed over (worldsLost 13 against 7). Needs the gate's figure in
  the dumps; not fitted.

## 10. Round 4 against pd9a: the pressure the stance reads

`SwarmPosture.poll` read only attack parcels holding in the system and forces mustering against it. It now reads
`ThreatPosture.read`'s five terms, raw = max(attacks, staged) + losses + hostiles + forward:

- **attacks** (`SwarmPosture.sight`, `Swarm.contacts`): a siege, hunt or squadron bound for a hive system is a contact
  once it is there (or inside `warsim_swarmRadarLY` of it while that switch is > 0), and counts for `swarmContactDays` after it was last seen
  (`ThreatSwarmIntel.contactsOn`). Simplified: every hive system has radar, as `SwarmOps.radar` already takes it.
- **staged** (`SwarmPosture.stagingHive`): per faction, the most any one seen base staging for the system could pay a
  siege there from its own stock, by the sighting's trust (`ThreatPosture.stagedBy`, `siegeCapacityFP`,
  `ThreatConvoys.stagingHive`); the mustering forces stay as a floor under it.
- **hostiles**: human fleets in the system that are no attack (guards, relief, convoys, scouts).
- **forward**: a seen forward base's guards, toward its nearest found hive system inside `frontlineKeepLY`
  (`ThreatFrontlines.hiveNear`), by trust.
- A system is attacked on attacks or hostiles. Verbose runs count the terms (`pressure.attacks` and so on); seeds 2
  and 5 read attacks and staged about equal, forward a fifth of either, hostiles next to nothing.

Check rows added: `meanHiveSize`, and the swarm's stance in months (`monthsExpand`, `monthsPress`,
`monthsConsolidate`; real from the monthly "Colony upkeep: .. stance X" line). The `stanceDays.*` counters are the
human factions' stances (`HumanStance`), not the swarm's.

Result, 30 seeds: 143 of the 184 earlier figures inside p10-p90 (126 after round 3, 135 after round 2), 168 of 218
with the new rows. Month 115, real | median [p10-p90]: monthsConsolidate 53 | 36 [13-51] (in through month 72: 28 |
24 [12-31]); hives 56 | 41 [20-80]; hiveSize 222 | 141 [97-234]; meanHiveSize in at 7 of 10 months; garrisonFP 65.7k |
36.6k [21.6k-51.3k], still OUT from month 84; swarmSupplies 15.9k | 94k [5k-305k]; strikes 163 | 189 [90-292];
hivesKilled 23 | 23.5 [17-30]; huntsSailed 183 | 244 [118-364]. Stance changes 11-12 a run against 22. The low
branch remains: the p10 seeds leave CONSOLIDATE near month 60 and do not return (13 months).

## 11. Round 5: two real runs, two branches

pd9a and pd10a (one recipe) agree to month 36 and part at month 45. Both entered CONSOLIDATE at w1253-1254 with
5-6 of 10 systems pressed. pd9a's first enemy was the Persean League (mobilised w1126): 13 sieges and 4 landings over
w1260-1440 kept 5 of 10 systems pressed and the stance held to w1510, founding nothing. pd10a's was the Hegemony
(w1121): 8 sieges and 1 landing over the same days, "pressed 3/9" at w1348, and the stance went to EXPAND for 356
days: 17 hives to 31, 10 systems to 16. After that 7 pressed systems were under the `stanceConsolidateShare` of 16,
so sieges at twice pd9a's rate (10-14 a quarter) only brushed CONSOLIDATE. The loop: EXPAND feeds growth 0.5 of the
net, the supplies left over pay Seeding Swarms (5,000 each; pd10a sent 78 in years 6-8 with none held, pd9a held 52
of 72), more systems dilute the pressed share, and the stance cannot return. CONSOLIDATE feeds 0.9, the Seeding
Swarms wait on supplies, the system count stays, and the same sieges keep the share.

The simulator had the loop but not the EXPAND branch's pace: `SwarmOps.expandInSystem` sent one in-system wave a
pass, where `ThreatColonyManager.tryExpandInSystem` sends a wave for every unclaimed resource planet of every held
system, as far as the forges have swarms and the stock pays (pd10a: up to 7 Seeding Swarms a tick, 5 of them into
held systems). Mirrored (counter `wavesInSystem`); `SwarmFit.EXPANSION_PLANET_SHARE` 0.17 to 0.88, the share of
spare planets the swarm did colonise in systems held since month 60 (25 of 33 and 43 of 44).

`check -dumps A -dumps2 B [-log2 ..]` (`Main.both`) runs each real against the simulator from its own start and
reports, per row and month, whether p10-p90 covers both and whether both lie on one side of the median.
Result, 30 seeds: pd9a 155 of 218, pd10a 130 of 193 (106 before); 93 of 168 cells cover both. Month 96, pd9a |
pd10a | median [p10-p90]: hives 32 | 90 | 70 [30-120]; garrisonFP 54k | 79k | 44k [31k-92k]; hivesFounded 45 | 118 |
96 [49-133]; hivesKilled 16 | 35 | 19 [11-26]; monthsConsolidate 47 | 10 | 22 [15-36]; swarmSupplies 303 | 349k |
10k [4k-174k].

## 12. Round 8: two structural hypotheses (2026-10-02)

Both real runs end with the humans holding 0-3 forward bases and the swarm having taken only undefended pirate or
Path worlds, so no ground changes hands twice and `reversals` cannot rise. Two switches, off by default and with
no mod symbol behind them, test whether that is the whole story (`SwarmKnobs.basesHold`, `coloniesFall`):

- `warsim_basesHold`: a strike that outmatches a forward base sinks its guard but not the station
  (`SwarmOps.strike`); it holds the orbit as a station siege (`StrikeOrder.besieging`, `stationSiegeDay`) and the
  base falls after `SwarmFit.STATION_SIEGE_DAYS` 30 with no guard back. A guard the faction sends meanwhile
  (`HumanBases.garrison`, sized to the wanted guard as always) fights the besiegers as a strike is fought: one that
  outweighs them lifts the siege, one that does not is sunk. Counters `stationSieges`, `stationSiegesLifted`,
  `stationReliefsSunk`, `stationSiegeDays`.
- `warsim_coloniesFall`: a landing on a colony at war fights `SwarmOps.threatFrontDay`, `HumanSiege.frontDay`
  mirrored for a Threat-owned front (no armaments, pushing losses x `threatPushLossMult`, a district per
  `frontPushBaseDays` at the pace, counter-attacks on the hive clock, overrun at 2:1) against
  `SwarmOps.colonyDefence`: `SwarmFit.COLONY_GROUND_PER_SIZE` 15 a size plus the reserve marines of `World.stock`
  (x `reserveDefenseMult`), whole when holding and at `MARINE_COUNTER_ATTACK_MULT` 0.25 when counter-attacking;
  the defenders bleed `defenderLossPer30Days` of the engaged and `defenderCounterAttackLossFraction` a
  counter-attack. A reinforcing pass adds troops (`SwarmOps.land`). Worlds with no reserve keep the `groundDays`
  clock. Counters `threatFrontsEngine`, `coloniesFallen`, `threatFrontsCollapsed`, `colonyCounterAttacks`,
  `threatStrataTaken`. `Main.outcome` also classes a run back-and-forth on `reversals` at
  `BACK_AND_FORTH_REVERSALS_PER_YEAR` 1 (9 in 104 months, 4 in 48).

Result, 30 seeds, median (clear = outside the seed noise). New game 104 months, council: A moves only bases
destroyed (47.5 -> 33.5): 44.5 station sieges, 4 lifted, 5 reliefs sunk - the wanted guard never matches a
strike (`strikesHeldOff` 0), and the relief is the same guard again. B: 154 engine fronts, 26.5 colonies fallen,
worlds lost 14.5 -> 42, bases held 11.5 -> 2, Threat score 875 -> 1627; reversals 5 -> 4, contested 18 -> 13.5.
A+B as B. Planner: A nothing; B worlds lost 14 -> 39.5, turnover 22 -> 30, hives 86 -> 149 (90%), reversals 3 -> 4,
contested 15. Month 114 + 48, council: A nothing (32.5 sieges); B and A+B one-sided - worlds lost 3 -> 33,
hives 145 -> 219, bases held 9.5 -> 3, reversals 3 -> 0, contested 13 -> 6, deadYears 0.5 -> 0.3, all clear.
Planner: A nothing (0.5 sieges: the planner founds no bases there); B and A+B worlds lost 3 -> 33, hives 143 -> 222, turnover
27 -> 53, reversals 1 -> 0, deadYears 1.0 -> 0.4, contested 4 unchanged, all clear.
Reading: ground that can change hands twice does not make the war back-and-forth on its own. A alone changes
nothing because nothing the faction sends can lift a siege; B alone hands the swarm the colonies and the
measures fall. The reversal needs the other side to answer in the same place - a relief sized to the besiegers,
or a counter-siege of the conquered hive - which neither the planner nor the council does. The colony-defence
figure (15 a size plus marines) is a guess against one number (Donn 55), and the humans' own navy over an
invaded colony and their relief convoys are not modelled, so B overstates how fast a defended colony falls
(the real runs: 0 of 56 at war).

## 13. Round 9: speed (2026-10-02)

A 104-month run had slowed to about 10 s alone (17-24 s under load) and a 30-seed batch to 69 s since rounds 6-7.
Sampled with `jcmd Thread.print` on one run, the costs were, in order: `SwarmPosture.poll` calling `stagingHive`
(two `HumanPools.payable` sorts and a `nearestBase` scan) once per hive system per seen base instead of once per
base, and `SwarmEconomy.held` scanning the parcels per hive per comparison in `redistribute`; `HumanIntel.sweep`
re-scanning the parcels, hives and worlds per faction per system per day; `SwarmEconomy.spreadAllows` recounting
every system per link; `StarSys.ly` a sqrt per call; `hivesIn(..).isEmpty()` allocating a list per test. Fixed
without changing a figure (the 30-seed batch tables for council and planner are byte-identical before and after):
per-poll maps of each base's staging hive and nearest hive system and of the factions' bases, `inboundMap` once
per poll and after each dispatch, `sweep` from per-faction eyes sets and radar lists built once a day, one-pass
link counts, a distance table built in `Start.fill` (`StarSys.index`, `dist`), `State.hasHive`, `strikeAt` with
the faction's worlds listed once, `payable` taking a donors list (`rangeLY`, `canPay`, `payableFP`), `hunts`
caching each base's range until a hunt sails. Seeds already ran in parallel (`Main.runs`, a parallel stream; the
only mutable static is `Main.SHOWN`, set before the runs). After: one run 1.9-2.5 s, a 30-seed batch 12-17 s, a
30-seed compare 19 s. What is left, by the same profile: `HumanPlanner.size` (a bombardment plan per fleet step
per option, rebuilt from scratch by every planner pass and again by `hunts`), `HumanPools.donors` sorts, and the
posture poll's remaining per-system scans of contacts, parcels and worlds.

Round 10 (same day): `HumanPlanner.size`'s sizing loop memoised on everything it reads (`State.sizeMemo`: hive id,
size, siege clock, strata held, tier, nexus, our front's marines, the starting fleet; the knobs are a run's
constants, so the memo holds for the run), `donors` listed once per `size`, `pay` and `canPay`, the posture poll's
contacts, parcels and worlds grouped per system in one pass each (`SwarmPosture.sysSums`, summed in the old order;
a muster staged elsewhere stays a hostile where it musters). Tables still byte-identical. After: one run 1.5-2.0 s,
a 30-seed batch 12-14 s, a 30-seed compare 19 s (JVM start and load 0.15 s) - short of the 0.5 s / 10 s targets.
Left, by the profile: `size` still misses whenever a hive's clock or the report moves (a bombardment plan per fleet
step, `HumanSiege.bombardPlan`), `SwarmEconomy.held` scanning parcels per hive per day in `regrowing` and
`buildSwarm`, and the 30 seeds on 12 cores finishing in waves.

## 14. Round 11: destruction as the lead measure (2026-10-02)

The user changed the lead measure: ownership swings and reversals were the wrong lens, what matters is successful
colony destruction by both sides. `Main.measures` now puts in every row the rates a year since the first
mobilisation (the first month with `mobilised` > 0; a mid-war start counts from month 0, as `deadYears` does):
`threatKills` = worlds + forward bases destroyed by the swarm (`threatKills.worlds`, `threatKills.bases`),
`humanKills` = hives killed, `mutual` = min of the two. `Main.outcome` keys on them (`MUTUAL_PER_YEAR` 1.0,
`QUIET_PER_YEAR` 0.25): "both sides" when mutual >= 1 a year, "one-sided" when one rate is >= 1 and the other
< 0.25, "quiet" when both are < 0.25, else "other"; the decided classes stay first. The swings/reversals class is
gone; `swings`, `reversals`, `contested`, `deadYears` stay as columns. The score's cumulative kill counts are
`threatKillCount` / `humanKillCount` (`Sim.row`). `check` ends with the real run's rates since its first "War
footing: ... mobilised" line (`EVENTS` `factionsMobilised`, not compared as a counter) against the simulator's.
`-set` now also takes `k=v;k=v`.

Real runs (planner check, pd9a start, 30 seeds): pd9a since month 38 to 115: threatKills 1.9 (0.5 worlds / 1.4
bases), humanKills 3.6, mutual 1.9; pd10a to month 112: 9.4 (2.4 / 7.0), 7.3, 7.3. Simulator median 4.2 (1.7 /
2.5), 3.5, 3.5 [2.5 - 4.4]. The simulated planner sits between the two real runs on every rate; the simulated
council kills 0-0.3 hives a year against the game's 3.6-7.3 (the known gap, war-council.md s16).

Grid, 30 seeds, last month medians, a lever marked * where its median lies outside the baseline's p10-p90:

| cell | threatKills (worlds / bases) | humanKills | mutual | classes |
|---|---|---|---|---|
| new council base | 9.8 (2.1 / 7.5) [7.7 - 11.8] | 0.3 [0 - 0.6] | 0.3 | one-sided 47, other 53 |
| new council consolidate 0.33 / 0.25 | 8.9 / 8.6 | 0.3 / 0.3 | 0.3 / 0.3 | both 3 / 0 |
| new council feed 0.7 | 8.3 | 0.2 | 0.2 | one-sided 70 |
| new council seed x2 | 8.2 | 0.3 | 0.3 | one-sided 30, other 70 |
| new council two plays | 8.7 | 0.3 | 0.3 | both 7 |
| new council A / B / A+B | 7.6* / 13.4* (6.5 / 6.7) / 11.5 | 0.3 / 0.2 / 0.3 | 0.3 / 0.2 / 0.3 | both 0 / 0 / 3 |
| new planner base | 4.7 (1.9 / 2.9) [3.7 - 5.4] | 3.9 [2.7 - 5.0] | 3.9 [2.7 - 4.6] | both 100 |
| new planner consolidate 0.33 / 0.25 | 4.6 / 4.5 | 2.8 / 2.0* | 2.8 / 2.0* | both 100 / 93 |
| new planner feed 0.7 / seed x2 | 4.7 / 4.9 | 3.3 / 3.1 | 3.3 / 3.1 | both 100 |
| new planner A / B / A+B | 4.0 / 8.4* (6.2 / 2.2) / 8.2* | 3.4 / 2.8 / 2.7 | 2.9 / 2.8 / 2.7 | both 100 |
| mid council base | 8.8 (0.8 / 8) [6.2 - 11.1] | 0 | 0 | one-sided 93 |
| mid council every lever but B | 7.4 - 8.5 | 0 | 0 | one-sided 90 - 97 |
| mid council B / A+B | 13* (8.3 / 4.5) / 11.8* | 0 | 0 | one-sided 93 / 90 |
| mid planner base | 0.9 (0.8 / 0.1) [0.8 - 1.3] | 1.8 [1.2 - 2.5] | 0.9 [0.8 - 1.3] | both 50, other 50 |
| mid planner consolidate / feed / seed / A | 0.9 / 0.8 / 0.9 / 0.8 | 1.8 - 2.3 / 1.5 / 2.3 / 1.8 | 0.9 / 0.8 / 0.9 / 0.8 | both 50 / 30 / 50 / 37 |
| mid planner B / A+B | 8.4* (8.3 / 0) / 8.3* | 1.4 / 1.3 | 1.4* / 1.3 | both 83 / 83 |

Reading: `mutual` is bounded by `humanKills` everywhere, so it is the humans' hive killing that sets the class. The
council's rate (0-0.3 a year) is under the quiet line in every cell and no swarm-side lever (stance, feed, seed
price, plays) moves it; the planner's is 1.8-3.9, which is why the planner start is "both sides". Of the swarm
levers only B (colonies fall) is clear of seed noise and it raises `threatKills` alone (worlds 2 -> 6 a year
new, 0.8 -> 8.3 mid); it raises `mutual` only mid-war planner (0.9 -> 1.4, because the baseline sat at the
line). Consolidate 0.25 lowers the planner's `humanKills` (3.9 -> 2.0, clear). A (bases hold) lowers
`threatKills.bases` by about a quarter, inside noise. Nothing raised `humanKills`: the lever for `mutual` is on the
human side (the council's siege rate), not among the swarm knobs tried.

## 15. Round 12: no radar, planner-sized plays (2026-10-02)

Two user decisions, put in as switches. (1) No radar on either side: `HumanIntel.sweep` gives a faction eyes in a
hive system where any of its fleets holds, its front stands or its own world lies (its station and guard), exact,
and the report stands and ages from the day the last leaves; radar from military worlds and bases only while
`warsim_radarLY` > 0. `SwarmOps.radar` sees a world as it stands while the swarm is in its system (a hive
system, or a wave, strike or scout holding or arrived there); Bastion radar only while `warsim_swarmRadarLY`
> 0 (the mod dropped both radar knobs in 262ac76, so these are the simulator's own switches, 0 unless set); `-set
"warsim_radarLY=10;warsim_swarmRadarLY=10"` is the old behaviour. (2) `warsim_councilPlannerSizing`
(default on): the council still chooses where and when, but `HumanCouncil.strike` sizes the siege as the planner
does (`HumanPlanner.size` on the faction's report at `npcSiegeOrbitMargin`, no trimming below it, the bounty when
the orbit is unpaid); a hammer with no report of its system runs the recon in force first (`reconFirst`,
`reconInForce`, RECON phase, `reconCheck`; counter `playsReconFirst`). Neither the mod's planner nor the simulator's
widens the orbit as trust decays - both spread prongs instead (attack-planner.md, "Spread before size") - so no
widening was added.

Grid, 30 seeds, last-month medians (* outside the old cell's p10-p90):

| cell | threatKills (w / b) | humanKills | mutual | scores T / H | hives | bases held | classes |
|---|---|---|---|---|---|---|---|
| new council old | 9.4 (2.1 / 7.3) | 0.2 | 0.2 | 874 / 11 | 135 | 11 | one-sided 60, both 3 |
| new council radar off | 7.3* (2.1 / 5.1*) | 0.2 | 0.2 | 1084 / 12.5 | 147 | 12.5 | one-sided 63 |
| new council sizing | 8.4 (2.1 / 6.1) | 0.2 | 0.2 | 861 / 11 | 128 | 11 | one-sided 63 |
| new council both | 7.1* (2.2 / 4.7*) | 0* | 0* | 967 / 13 | 144 | 13 | one-sided 73 |
| new planner old | 4.4 (1.9 / 2.9) | 3.3 | 3.3 | 758 / 0 | 90 | 0 | both 100 |
| new planner radar off | 3.9 (1.9 / 1.9) | 3.0 | 2.9 | 1033 / 0 | 129 | 0 | both 97 |
| mid council old | 8.8 (0.8 / 8) | 0 | 0 | 285 / 9.5 | 145 | 9.5 | one-sided 93 |
| mid council radar off | 5.6* (0.8 / 4.9*) | 0 | 0 | 284 / 12 | 144 | 12 | one-sided 93 |
| mid council sizing | 8.6 (0.8 / 7.9) | 0 | 0 | 294 / 10 | 148 | 10 | one-sided 97 |
| mid council both | 5.6* (0.8 / 4.9*) | 0 | 0 | 283 / 11.5 | 145 | 11.5 | one-sided 100 |
| mid planner old | 0.8 (0.8 / 0) | 1.8 | 0.8 | 311 / 0 | 145 | 0 | both 43 |
| mid planner radar off | 0.8 (0.8 / 0) | 1.0 | 0.8 | 312 / 0 | 151 | 0 | both 13 |

Reading: radar off is the clear change. The swarm destroys a third fewer forward bases (7.3 -> 5.1 new, 8 -> 4.9
mid; a strike now needs a sighting of the base from a hive system or a swarm there), the humans scout three times
as much (`scoutsSailed` 561 -> 1611) and kill fewer hives (planner 3.3 -> 3.0 new, 1.8 -> 1.0 mid, both-sides
43% -> 13%), so more hives stand at the end (new game 135 -> 147 council, 90 -> 129 planner). Planner sizing of
plays is inside seed noise everywhere: the council sailed 3-4 play sieges a run before and after (relief owed
pauses its plays, `council.heldPlayDays`; its recon plays outnumber hammers 30 to 1), so how a siege is sized
changes nothing until it sails more of them; `playsReconFirst` stayed 0 because the council's own old-picture gate
already runs a recon before a hammer. `mutual` is still bounded by `humanKills` in every cell.

Check with radar off (the real runs had radar): pd9a 130 -> 115 of 218 figures inside p10-p90, pd10a 130 -> 143.
Simulated destruction since the first mobilisation 4.2 -> 3.4 threatKills a year (bases 2.5 -> 1.7), humanKills
3.5 -> 2.9, mutual 3.5 -> 2.7; pd9a's real mutual 1.9 is now inside the band [1.9 - 3.5], pd10a's 7.3 further out.
