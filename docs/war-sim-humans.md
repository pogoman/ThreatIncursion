# War simulator: the human factions

Built 2026-10-02. The human side of the offline simulator (`tools/warsim/`, spec `war-sim.md`):
`warsim.HumanSide` and the `Human*` classes beside it. It runs the attack planner; with
`threatinc_warCouncil` on it still runs the planner and counts `councilNotModelled` once.

Run: `tools\warsim\warsim.ps1 run -start <dump folder> -months 60 [-seed N] [-v] [-set key=value]`.
Test double for the swarm (calibration only): `java -Dwarsim.humanTestSwarm=true
-Dwarsim.startWarMonth=36 -cp tools\warsim\out warsim.Main run ...`.

## 1. Rules lifted into `src/threatinc/rules/` (the mod calls them; no behaviour change)

| Rule | The mod's call site |
|---|---|
| `BattleRules.lossShare`, `defenderLoss`, `MAX_LOSS`, `LOSS_PER_RATIO` | `ThreatAbstractBattle` (off-screen fight shares) |
| `BattleRules.dayShare`, `callsOff`, `orbitContested` | `ThreatPurgeFGI` daily siege (the exchange, the call-off, the contest) |
| `BattleRules.suppressionRate`, `conditionAfterDay`, `bombardFuelPerDay`, `bombardDaysFor`, `returnFirePerDay`, `gunDefence`, `GROUP_ABORT_FRACTION` | `ThreatGroundFronts` (`bombardPlan`, the day's slice, the guns) |
| `BattleRules.raidLossLine` | `ThreatFleetOrders` (`raidOver`) |
| `BattleRules.overrunOdds`, `beachheadNeeded`, `raidStrNeeded`, `condition` | simulator only so far; `IncursionManager` keeps its own copies (section 6) |
| `ReachRules.voyageCost` | `ThreatFleetOrders.sortieWants`, `ThreatFrontlines` (garrison voyage), `ThreatConvoys` |
| `ReachRules.payablePoints` | `ThreatFleetOrders.payableFP`, `ThreatSoftening.payableFP` |
| `ReachRules.haulPerUnit`, `netOfHaul` | `ThreatConvoys` |
| `ReachRules.passageFuel`, `tripSupplies`, `siegeSuppliesPerPoint`, `siegeTripDays`, `siegeArrivalDays`, `baseRangeLY`, `haulFuel` | simulator only; the mod's are in `IncursionManager` / `ThreatReach` (section 6) |
| `ReserveRules.monthsBasis`, `floorBasis`, `basisAtFullShare`, `available`, `spendable`, `seeded`, `accrualPer` | `ThreatReserves` |
| `PlannerRules.trust`, `moved`, `twoFigures` | `ThreatIntel` |
| `PlannerRules.miss`, `planDue`, `orbitToContest`, `raidFP`, `raidDays`, `raidScore`, `leastForGain`, `Gain`, `RAID_SLACK_DAYS` | `ThreatAttackPlanner` |
| `CouncilRules.scores`, `draw`, `jitter`, `learn`, `ratio`, `band`, `focusWeight` | `ThreatWarCouncil` |

## 2. What is modelled (simulator classes, and the mod symbol each stands for)

- `HumanPools` - `ThreatReserves` and the pooled draw: uncapped accrual, floor, `available`
  for a siege, `spendable` for everything else, donors nearest first with `ReachRules.haulPerUnit`
  fuel, mobilisation seeding (`reserveInitialMonths`). `drain` is `ThreatUpkeep`'s payer.
- `HumanIntel` - `ThreatIntel` + `ThreatHiveIntel`: a hive system must be found (scout lead from
  `Faction.lastStrikeFrom`, interval sweeps), reports per system that age (`PlannerRules.trust`),
  radar from bases within `radarRangeLY`, news on `PlannerRules.moved`. Mobilised factions share reports.
- `HumanStance` - `ThreatFactionStance`: read every 7 days; CONSOLIDATE founds no link and
  besieges only systems facing the faction, PRESS founds on `stanceSecondaryShare` of passes.
  `faced` is the nearest world's owner (the mod's `ThreatReach.faced` also applies the strike gate).
- `HumanBases` - `ThreatFrontlines`: one link a pass (`frontlinePlanDays`) toward the nearest
  found hive system out of `frontlineReachLY`, paid `outpostSupplies + LINK_KIT_SUPPLIES` and
  `outpostFuel`; founded only with a garrison (`cannotGuard` = `garrisonBase`: the garrisons'
  upkeep within `upkeepBudget`, the voyage payable); `guardWanted` = strike in reach x
  `frontlineGarrisonMargin`, at least `frontlineGarrisonFP`; given up after `frontlineAbandonDays`
  unguarded ("no garrison to hold it") or with no found hive within `frontlineKeepLY`.
- `HumanPlanner` - `ThreatAttackPlanner` + `IncursionManager` sizing: every `planIntervalDays`
  and on news; a siege sized per hive (orbit = report x `npcSiegeOrbitMargin`; the least
  flotilla that carries the landing its own bombardment leaves, `needAndWear`); sieges added
  easiest first until P(one lands) >= `planConfidence`; `onCooldown` = `onPurgeCooldown`
  (`purgeCooldownDays`, doubled for a defended world over `purgePreemptMaxSize`,
  `purgeFollowUpDays` with an organ down); only the deposit is drawn at launch; a raid
  (SQUADRON) when nothing sailed; front runs (CONVOY) to a front a counter-attack could overrun;
  hunts (`ThreatSoftening`): one force a faction a system, from the nearest base not resting
  (`softenIntervalDays`) and with no siege it could sail (`hasSiegeableHive`).
- `HumanSiege` - `ThreatPurgeFGI.advanceDaily` + `ThreatGroundFronts`: the day in orbit (call
  off, fight, contest, bombard, guns, land), the landing's stance, the front's day (attrition,
  wear, push, counter-attack, victory -> `State.killHive`).
- `HumanSide` - the daily order, musters, hunt battles, returns (`ThreatReturns.settle`),
  upkeep every 5 days (`ThreatUpkeep`: aboard, then home, then the pool; a month owed stands
  the force down), default orders for parcels a dump loaded without one, the timing marks.

Counters: `siegesSailed`, `siegesLanded`, `huntsSailed`, `squadronsSailed`, `siege.<outcome>`,
`postponed.<commodity>`, `linkCannotPay`, `linkCannotGuard`, `guardOverBudget`, `upkeepWanted`,
`upkeepOwed`, `stoodDownUnpaid`, `nexusDownDays`, `stanceDays.<stance>`, `day.<faction>.<mark>`.

## 3. Modelled in the simulator, not shared (mod files this job could not edit)

`IncursionManager` (`needAndWear`, `raidStrNeededAt`, `beachheadNeeded`, `expeditionWants`,
`siegeCanPay`, `onPurgeCooldown`, `hasSiegeableHive`, `siegeTripDays`), `ThreatReach`
(`tripSupplies`, `rangeOn`, `days`, `faced`), `ThreatUpkeep`, `ThreatFactionStance`,
`ThreatColonyUpkeep` (link size upkeep), `ThreatReturns`, `ThreatOutposts.linkCost`, and from
files this job could edit but whose game-typed bodies do not lift: `ThreatFrontlines.strikeAt`,
`guardNeed`, `garrisonBase`, `upkeepBudget`, `planFor`; `ThreatSoftening.send`, `huntBases`.

## 4. Fitted constants (`HumanFit`, each with its source line there)

`ACCRUAL_BY_SIZE` and `BASE_RATE` (pd7a reserve lines, medians by colony size),
`LINK_KIT_SUPPLIES` 4000 ("Frontline: paid 5500 supplies and 800 fuel from Salamanca"),
`STRIKE_SEND_SHARE` 0.4 and `STRENGTH_PER_FP` 1.4 ("garrisons ... 1502 FP, strength 2553 of
2130 (front; strike in reach 1800)"), `GUARD_UPKEEP_PER_FP` 1.2 (`ThreatFrontlines.UPKEEP_PER_FP`),
`MIN_SIEGE_FP` 225 and `MAX_SIEGE_FP` 6000 (pd7a sent FP: min 225, p90 1525), `HUNT_BATTLE_DAYS`
20 (pd7a: 464 hunt battles over 248 forces), `PREP_MIN_DAYS`/`PREP_SPAN_DAYS` 7/7,
`RAID_STAY_DAYS` 10, `MIN_GUARD_FP` 30, `GUARD_RETRY_DAYS` 30, `BASE_MAX_SIZE` 6, hive defence
(`MILITARY_MIN_SIZE`, `GROUND_DEFENCES_MIN_SIZE`, `HEAVY_BATTERIES_MIN_SIZE`, `TIER_MULT`,
`UNREST_DEFENCE_FLOOR`), `START_KNOWS_HIVES` (a mid-war dump carries no found list).
No constant is a balance fudge; where the sim misses, section 5 says so.

## 5. Test results (2026-10-02)

Start: pd9a's war-month-36 dump (`simdump-pd9a`, 16 hives, nobody mobilised), 78 months, the
test double `HumanTestSwarm` for the swarm (strike months per faction taken from pd9a, garrisons
drifting to a size target, a relief trickle, hive founding at pd9a's rate, a 0.25/month strike
on each forward base). Three seeds run in 3-4 s each. Cumulative, seed 1 (seeds 2, 3 at the end):

| War month | 48 | 60 | 72 | 84 | 96 | 108 | 114 |
|---|---|---|---|---|---|---|---|
| Sieges sailed, pd9a | 24 | 50 | 75 | 112 | 143 | 170 | 201 |
| sim | 17 | 44 | 79 | 124 | 154 | 193 | 220 (208, 260) |
| Landings, pd9a | 4 | 12 | 17 | 21 | 26 | 33 | 41 |
| sim | 9 | 24 | 38 | 53 | 71 | 91 | 109 (106, 100) |
| Hives killed, pd9a | 1 | 6 | 10 | 13 | 16 | 20 | 23 |
| sim | 1 | 7 | 15 | 23 | 28 | 37 | 44 (40, 39) |
| Bases founded, pd9a | 7 | 8 | 8 | 14 | 14 | 14 | 15 |
| sim | 8 | 12 | 15 | 21 | 31 | 35 | 37 (37, 50) |
| Hunting forces, pd9a | 10 | 32 | 51 | 73 | 114 | 156 | 182 |
| sim | 67 | 184 | 291 | 395 | 504 | 633 | 726 |
| Live hives, pd9a | 17 | 27 | 28 | 30 | 32 | 41 | - |
| sim | 18 | 28 | 25 | 24 | 24 | 29 | 29 |

pd7a (planner, months 48/72/97): sieges 23/115/202, landed 6/21/43, kills 3/12/27, bases 25
founded, 16 destroyed, 9 given up, 0 held. Sim bases: 37 founded, 28 destroyed, 9 given up, 0 held.

Per-faction timing, war day (sim day + 1084). Mobilisation is the test double's input, not a result.

| Faction | Mobilised | First base | First siege | First landing | First kill |
|---|---|---|---|---|---|
| persean, pd9a | 1126 | 1193 | 1186 | 1333 | 1455 |
| sim | 1114 | 1204 | 1123 | 1238 | 1441 |
| hegemony, pd9a | 1183 | 1286 | 1244 | 1425 | 1604 |
| sim | 1174 | 1204 | 1217 | 1337 | 1529 |
| tritachyon, pd9a | 2355 | 2430 | 2480 | 2717 | 2761 |
| sim | 2344 | 2344 | 2359 | 2461 | 3046 |
| independent, pd9a | 2390 | 2461 | 2454 | 2865 | 3003 |
| sim | 2374 | 2374 | 2404 | 2448 | 2752 |
| sindrian_diktat, pd9a | 3185 | - | 3245 | - | - |
| sim | 3184 | - | 3251 | 3344 | 3375 |
| luddic_church, pd9a | 3214 | 3303 | 3274 | - | - |
| sim | 3214 | - | - | - | - |

Mid-war start (pd9a war month 60, two factions mobilised, sieges and fronts in flight): runs
on, 212 sieges and 11 kills in 54 months with the test double; with the skeleton's swarm
(`warsim.ps1 run -start ... -months 60`) 117 sieges, 107 landings, 11 kills, 5 bases held.

Where it misses, largest first:

1. **Landings twice pd9a's, kills twice.** (Fixed in the joined run, see the end of this section.) pd9a ended 150 daily sieges: called off 53, beaten
   33, landed 40, front 13, nothing to land 8. Sim, of 179: called off 18, beaten 52, landed 94.
   The call-off is the swarm answering a muster, which the test double barely does; expect the
   real `SwarmSide` to move this. pd9a's landings came on day 1 (median); the sim's after a
   median 24 days of bombardment, 80 of them out of ordnance and dug in.
2. **Supplies too plentiful.** pd9a Hegemony: ~18 fleets out, 7,400 supplies a month of
   upkeep on 6,900 income, stock 10-16k. Sim: 3-5k FP out a faction, stock 17-40k. Missing sinks:
   link structures (Patrol HQ 3,000, battlestation 5,000...), staging convoys as fleets, hunts
   that stay until starved (252 of pd9a's 945 hunt orders ended "out of supplies").
3. **Hunts four times too many and small** (median 50 FP against pd7a's 858): the sim sends one
   for any report over 1 FP; the mod's `musterFloorFP` and its wait on `payableFP` are not modelled.
4. **Links founded the day a late faction mobilises** (pd9a: 70-90 days later): intel is shared
   at once and no build time is modelled.
5. **Bases founded 37-50 against 15** (pd4a-pd8a ranged 9-50): the count follows the test
   double's strike rate; "cannot pay for a link" (the mod's main brake, 260+ lines in pd9a)
   binds less with supplies plentiful.

Joined with `SwarmSide` (2026-10-02, round 2, `check` on pd9a, 30 seeds, month 115, real | median
[p10-p90]; 135 of 184 figures in): sieges sailed 201 | 199 [120-273], landed 41 | 48.5 [34-60],
fronts overrun by the hive 11 | 16 [11-22], hives killed 23 | 31 [21-39], hunts 183 | 478 [302-619].
What round 2 changed, each a mod rule the simulator lacked:

- `HumanSiege.enemyAt` / `fight`: the orbit fight weighs and wears every garrison in the system
  (vanilla's autoresolve; "Off-screen fight over Gamma Vucub-Came I-L5 ... 2851 FP (14 fleets)").
- `HumanPlanner.size`: the landing is sized on `HumanSiege.anchored` (`nexusAnchoredDefense`) when
  that is over the defence as it stands, worn by the plan's share (`raidStrNeededAt`). pd9a landed
  482-934 marines on size-2 hives and 1,282-1,585 on size 5; the simulator had sent 840 at size 5.
- `Front.coverFP` (`GroundFront.coverFP`): the flotilla that landed covers the front from the
  swarm's bombardment until the garrison at the planet outweighs it (`coverLost`; pd9a 59 set, 9 lost).
- `HumanSiege.frontDay`: the NPC stance AI (`shouldBrace`, dig in when dry, push again when it can
  hold), cover dug only while not assaulting (`cover`), a stratum lost digs the front in, the
  counter-attack clock `counterRate` (strata left over size x tempo clamped by `counterAttackRatioClamp`).

Still out: hunts 2.6 times pd9a's and small (the force is `margin` x the report as in
`musterFloorFP`, but the simulator's reports of a hunted system are a few FP - its garrisons there
are sunk and not refilled as the mod's posture refills them; pd9a's forces were 202-3,201 FP, median
1,217). Hives killed run a third high from month 84. The hive's fed share in `counterRate` is taken as 1.

## 6. What the next jobs need

One-liners for files this job does not own:
- `Start.fill`: end with `HumanSide.load(s, dump);` (optional fields below; absent is fine).
- `IncursionManager.beachheadNeeded` / `raidStrNeededAt` / `expeditionPassage` /
  `siegeSuppliesPerPoint` / `siegeTripDays` and `ThreatReach.tripSupplies` / `rangeOn` / `days`
  should delegate to `BattleRules` / `ReachRules` so the sizing is shared, not mirrored.

Dump fields for a mid-war start. `HumanSide.load` reads these when present: per world
`military` (`IncursionManager.hasMilitary`) and `guardFP`; per faction `mobilisedDay` and
`lastStruckDay`; `foundHiveSystems`; `reports [{faction, system, day, loose, hives: [{id, fp}]}]`.
Wanted and not read yet: `lastPurgeTimes` per hive, a link's unguarded/idle/healthy days, a
faction's stance and trend, per siege in flight its trust, deposit and upkeep owed, per front
its armaments, entrenchment, stance and push progress. Without them a mid-war start assumes
every hive system found, no siege cooldown running and every faction in EXPAND.

Expected of the swarm side: raise `Faction.strikesSuffered` and set `lastStruckDay` and
`lastStrikeFrom` when a strike is detected; read and reduce `World.guardFP`, destroy a base with
`s.loseWorld(w, true, ...)`; answer MUSTER parcels (`against` is the hive system); decrement
the organ clocks (`nexusDown`, `forgeDown`, `coreDown`) - the human side runs `Hive.siegeClock`
and `fortification` down and writes `nexusDown`; humans reduce `Hive.garrisonFP` and the fp of
Threat parcels holding in the system.

The council job needs: `HumanPlanner.size` / `launch` / `muster` (a play's siege, with
`CouncilRules` for the strategy draw), `HumanPlanner.hunt` for a play's held force (hold = a MUSTER
that does not sail until released), `HumanSiege.friendsOf` already counts hunts beside a siege,
and `HumanStance` must stand aside when a council sets the stance.

Not modelled at all: guard and defend orders (`ThreatFleetOrders`), saturation as its own
order, per-faction intel (mobilised factions share), the coalition call, contracts, the
player, hive unrest in the defence, the siege leash, link structures and build time.
