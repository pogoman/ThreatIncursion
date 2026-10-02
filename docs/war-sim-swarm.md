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
  gate reads the strike at 300 a swarm against the defence last seen. On arrival
  `BattleRules.lossShare` both ways; a forward base is destroyed at once; a world falls after
  `groundDays` unless the human side kills the parcel; survivors fly home and are banked.
- **Fog** (`ThreatSwarmIntel`, `ThreatSwarmScouts`), the simple model chosen: a world is seen
  while within `swarmRadarRangeLY` of a hive system, and when a Scouting Swarm reaches its
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
| `groundDays` | 50-96 | Kanni 50, Kanta's Den 71, Qaras 96 |
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

- The Bastion comes about 180 days early and garrisons at month 36 run about 1500 FP low: the
  simulated bank is 800-900 FP richer than the real one over months 17-24, the sink not found.
- Strikes run about half again as often as pd9a's from month 30; fuel at month 36 is high.
- No deposits: `systemNeedScore`, `canSupportFullChain` and the planner's mining choice are
  stand-ins, so where the swarm spreads is noisier than the mod's pick.
- Colonies stop at `colonyMaxSize`; pd9a showed none above 6 by month 46.
- Contacts, the alarm's grudge, `extraWantFP`, relief strikes, sweeps of a whole system and
  the hold share are not modelled. `ThreatHiveMind` is a design, not built, and not modelled.
- A garrison's fight with a siege is the human side's; the swarm side only reads the result.
