# War simulator: the human factions

Built 2026-10-02. The human side of the offline simulator (`tools/warsim/`, spec `war-sim.md`):
`warsim.HumanSide` and the `Human*` classes beside it. It runs the attack planner; with
`threatinc_warCouncil` on (the mod's default) the war council and its plays run instead (`HumanCouncil`, section 7); `check` turns it off, the real
runs it reads (pd9a, pd10a) being planner runs.

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
  eyes where its own ships, fronts or worlds are (no radar since 2026-10-02; `warsim_radarLY` > 0 restores it for
  comparison), news on `PlannerRules.moved`. Mobilised factions share reports.
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

The council job is done: section 7.

Not modelled at all: guard and defend orders (`ThreatFleetOrders`), saturation as its own
order, per-faction intel (mobilised factions share), the coalition call, contracts, the
player, hive unrest in the defence, the siege leash, link structures and build time.

## 6. Round 3 against pd9a: bounties gate the hunts

- **Swarm bounties** (`State.bounties`, `HumanPlanner.postBounty`): one a hive system, `swarmBountyDays` long
  (`ThreatSwarmBountyIntel.post`). Posted where the mod posts it: the planner's sized siege that the pool cannot
  pay the orbit's fleets for (`Option.orbitUnpaid`, the provisions gate of `IncursionManager.launchSiegeExpedition`),
  and a siege that calls off on arrival (`HumanSiege`, `ThreatPurgeFGI.breaksOff`). Counter `bountiesPosted`
  (pd9a: 313 posted; seeds 1-2: 153-185).
- **Hunts** (`HumanPlanner.hunts`) are raised only against a system with a bounty running (`ThreatSoftening.tick`),
  from the base that pays for most (`payableFP`, as `huntBases` ranks them), wanting the system's reported
  swarms by `npcSiegeOrbitMargin`, built at what the pool pays, and waiting under the floor of the strongest
  world's (`ThreatSoftening.send`, `musterFloorFP`; counter `huntWaits`).
- Result, month 115, real | median [p10-p90]: huntsSailed 183 | 267 [105-370] (was 478); force size median 575 FP
  against 1,217. Not mirrored: contributor bases and donors (`contributors`, `huntDonors` - one base's pool pays here),
  `hostileAt` (vanilla's start has only the Path hostile to the other hunters), the go-in check and moving on. The
  simulated reports are honest; the garrisons a hunt meets are about half the real ones (the strongest world's report
  read 749 FP at the median in pd9a), which is the swarm's concentration, below.

## 7. Round 6: the war council and its plays (`HumanCouncil`)

Switched by `threatinc_warCouncil` (`HumanCouncil.on`). With it on, `HumanSide.daily` runs `HumanCouncil.daily`
in place of `HumanStance.evaluate` and `HumanPlanner.plan` (`ThreatAttackPlanner.active`,
`ThreatFactionStance.refresh`); the front runs (`HumanPlanner.frontRuns`) and the bounty hunts
(`HumanPlanner.hunts`, `ThreatSoftening.tick`) run either way.

| Simulator | Mod | What |
|---|---|---|
| `HumanCouncil.assess` | `ThreatWarCouncil.assess` | Monthly `Picture`: a `Cluster` per reported hive system (weight = sizes + 2 x tier, core, frontier, production, stale, threatens, the bases in range nearest first), our weight, partners' at half, strikes in 90 days from the history, Threat fronts, the band (`CouncilRules.ratio`, `band`). |
| `HumanCouncil.review`, `fits`, `focus` | `ThreatWarCouncil.scores`, `review`, `fits`, `focus` | `CouncilRules.scores` x personality x learned weight, the held strategy x (1 + switch margin), `CouncilRules.draw`, the review day jittered; the focus by `CouncilRules.focusWeight`. Early review on a colony lost, a partner more or fewer, a play's decisive end. |
| `HumanCouncil.personality`, `learned`, `learn` | the same names | `threatinc_councilPersonalities`; `CouncilRules.learn` on `TYPE:targetClass` and `strategy:S`. |
| `HumanCouncil.setStance` | `ThreatWarCouncil.setStance` | Hold consolidates, a major play presses, else expand (`Faction.stance`, read by `HumanBases` as before). |
| `HumanCouncil.plan`, `opportunity`, `startRecon`, `startHammer`, `startStarve`, `feintPlan`, `startFeint` | `ThreatPlays`, the same names | One major play at a time on the focus; play weights by strategy x personality x learned; Hold only recons what threatens; bombers of opportunity within the month's fuel share (`oppLeft`), a world that drove one off rested a half-life. |
| `HumanCouncil.advance`, `sample`, `toMuster`, `musterCheck`, `watchCheck`, `strike`, `strikeCheck`, `exploitCheck`, `advanceStarve`, `saturate`, `finish`, `end` | `ThreatPlays`, the same names | The phases and the verdict by damage done: a world taken, a landing (`HumanCouncil.landed`, from `HumanSide.station`), or Nexus-days down >= `councilInvadeNexusDays`. |
| `HumanCouncil.force` | `ThreatSoftening.sendPlay`, `playPayableFP` | The held hunting force: a MUSTER parcel at `councilHammerShare` x `HumanPlanner.payableFP` that sails on the siege's day. |
| `HumanPlanner.size(.., playFP)` | `IncursionManager.playSiegeSizes` | The play's siege under share sizing (`warsim_councilPlannerSizing=false`): no orbit term, grown to the share of `HumanCouncil.capacityFP` (`ThreatPosture.siegeCapacityFP`); `strike` trims it to what the pools pay, down to the fleet that carries the landing (the provisions gate of `launchSiegeExpedition`, "Expedition trimmed"). |
| `HumanCouncil.plannerSizing`, `reconFirst`, `reconInForce`, `reconCheck` | the user's decision of 2026-10-02 (the mod's change is in hand) | Default on: the council still picks where and when, but `strike` sizes the siege as the planner does (`HumanPlanner.size` on the faction's report, `npcSiegeOrbitMargin`, no trimming; unaffordable posts the bounty), and a hammer with no report of its system runs the recon in force first (RECON phase, `playsReconFirst`) and sizes when the report is in. |
| `HumanCouncil.squadron`, `squadronFP`, `squadronBase`, `fuelCost`, `bombable`, `raidsEnded` | `ThreatPlays`, the same names (`raidEnded`) | A play's raid: a SQUADRON parcel with `HumanOrder.play` and `stayDays`; `HumanSide.station` sends it home driven off on a contested orbit (`ThreatFleetOrders.endRaid`), planner raids as before. |

Counters: `council.months.<STRATEGY>` (a faction-month, as the mod's monthly `Council f: picture` line),
`council.band.<band>`, `council.pressedMonths`, `council.switches`, `plays.<TYPE>`, `plays.<TYPE>.<outcome>`,
`playSieges`, `playSiegesTrimmed`, `playForces`, `playSquadrons`, `playSquadronsDrivenOff`, `saturationsSailed`,
`starveInvasions`, `feintsDrew`. `-show a,b` adds any of them to the `run`, `batch` and `compare` tables.

Not mirrored, candidates to lift or model later: the relief itself (round 7 mirrors the gate only); a partner's joint force (`inviteJoint`); the play's staging and
its decoy (`stage`, `ThreatConvoys.stageForPlay`); the muster at a bearing (the held force waits at its base);
one siege a hive, so a hammer besieges its first payable world only; the saturation expedition is a SATURATION
parcel over one world.

**Council against planner, and against the real council runs** (30 seeds from `start/pd9a-newgame`, 104 months,
median [p10-p90]; real: pd4a, pd5a, pd6a, pd8a, 97-105 months, log extracts without dumps or dates):

| | sim planner | sim council | real council runs |
|---|---|---|---|
| hives at the end | 78 [35-140] | 125 [64-168] | 236, 191, 201, 152 |
| hives killed | 20 [12-29] | 8 [5-12] | 1, 2, 1, 0 |
| sieges sailed (+ saturations) | 284 [167-361] | 23 [17-32] + 39 [30-44] | 15, 29, 26, 29 (`Expedition draw at`, both kinds) |
| human worlds lost | 13 [10-16] | 14 [10-16] | 39, 22, 8, 8 |
| forward bases founded / held | 21 / 0 [0-1] | 43 [37-56] / 8 [5-12] | 9/0, 42/0, 20/0, 50/3 |
| faction-months Hold / Starve / Roll back / Decapitate | - | 48% / 41% / 10% / 1% | 52-59% / 36-46% / 3-5% / 0-1% |
| band outmatched, months pressed | - | 62%, 61% | 82-90%, 67-85% |
| hammers (succeeded) | - | 28 [21-42] (75%) | 7, 16, 8, 8 (25-71%) |
| starves (succeeded) | - | 40 [30-44] (29%) | 13, 19, 22, 23 (9-32%) |
| bombers (succeeded) | - | 242 [154-307] (18%) | 34, 98, 27, 35 (26-39%) |
| recons, feints | - | 1, 2 | 0-44, 0 |

The strategy shares, the starve campaigns' verdicts and the direction of every council-against-planner
difference agree. The simulated council is about twice as active as the real one (hammers 3x, starves 2x,
bombers 3-7x), kills 8 hives where the real ones killed 0-2, and leaves the swarm at about half the real size;
it holds 8 forward bases where the real runs held 0-3. The logs cannot validate the plays' sizes, the band's
inputs or anything by date (no `Clock:` lines, no dumps).

**Round 7: why the round-6 council was 2-3x too active, by mechanism.** The table above is round 6's; the fixes:

- **Coalition** (`HumanFit.COALITION_PAIRS`, `HumanCouncil.partner`; `ThreatCoalition.partners`): the real pictures
  name allies only for luddic_church + luddic_path and persean + sindrian_diktat (54-132 of 280-358 pictures a
  run). Round 6 took every faction at war as a partner, at half weight in the band: 62% of months outmatched
  against the real 82-90%.
- **Per-observer intel** (`HumanIntel.sweep`, `file(.., observer)`; `ThreatIntel.advanceDay`): a report is the
  observer's (its own fleet, front or world in the system; a military world or forward base within `warsim_radarLY` only
  while that simulator switch is > 0; the mod's `radarRangeLY` is gone since 262ac76)
  and its partners'. Round 6 filed every sighting with every faction, so each council saw every hive fresh and
  bombers of opportunity always had a target (242 a run against 27-98).
- **Relief owed** (`HumanCouncil.reliefOwed`; `ThreatFleetOrders.reliefOwed`, `ThreatPlays.pausable`): no new play, the
  waiting phases held, Hold +1. The simulator sends no relief, so it is owed while the Threat front stands
  (`Swarm.landings`) and a base pays a `reliefFleetFP` fleet there: 63% of faction-days, surely more than the mod's
  (6-12 "held in .. (relief owed)" lines a run).
- **Threat fronts** are `Swarm.landings`, not `World.front`: the council's `fronts` and `HumanStance.evaluate`'s
  `pressed` read the wrong field and never saw one.

Council after the fixes (30 seeds, 104 months) against the real council runs: hammers 6 [2-11] (7-16), starves
12.5 [9-19] (13-25), bombers 33 [11-57] (27-98), sieges + saturations 5 + 12.5 (15-29), hives killed 2 [0-4] (0-2),
hives at the end 135 [92-178] (152-236), outmatched 96% (82-90%), pressed 76% (67-85%), Hold / Starve / Roll back
51 / 46 / 3% (52-59 / 36-46 / 3-5%). Still off: forward bases founded 70 [58-91] and held 11.5 [7-19] (9-50, 0-3).
The planner gate moved with the intel and stance fixes: pd9a 155 -> 130 of 218 (139 with the intel fix alone), every
month-36 row still inside; the losses are later months, where sieges and strikes were already high.

Experiment switches, simulator only (`Knobs.set` accepts `warsim_*`): `warsim_seedPriceMult` (the Seeding Swarm's
price, `SwarmKnobs.foundSupplies`) and `warsim_councilMajorPlays` (major plays a faction runs at once,
`HumanCouncil.plan`; the mod's `ThreatPlays.major` allows one).

## 8. Round 13: the council docs' open ideas, trialled (2026-10-02)

Switches, all off by default, each mirrored from the doc's own words: `warsim_councilAlwaysSiege` (war-council-runs.md 5 A:
the strategy picks where, not whether - Hold hammers the cluster that threatens it, Starve runs one campaign beside the
hammer slot; `HumanCouncil.plan`), `warsim_councilMajorPerFP` (option B: the major-play limit is the faction's siege
capacity over this many FP, `siegeCapacity` = each base's `capacityFP` against its nearest cluster, counted in
`council.majorLimitDays`), `warsim_councilStarveToHammer` (option C: a starved campaign turns to the hammer in the same
play, straight to the muster; `advanceStarve`, counter `starveToHammer`), `warsim_councilReliefPause=false` (4.5: plays
are not held while relief is owed; `reliefPause`), `warsim_swarmConsolidateOnLosses` (swarm-strategy.md 4 decision 5, the
doc's words: CONSOLIDATE when the hive count falls or a front stands on a hive, never on pressure; `SwarmPosture.stance`).
The feint's own success test is counted at a feint play's end (`feint.drew`, `feint.notDrew`, `.landed`: did A's
reports rise within the watch, did the strike then land). Decision 3's numbers, learning and temperature are the mod's
knobs; the personalities trial sets `threatinc_councilPersonalities` to one `default` entry.

All cells council on, radar off, planner sizing on (round 12's defaults), 30 seeds; the base cell equals round 12's
"both" cell exactly. New game 104 months, ranked by mutual then humanScore (* outside the base cell's p10-p90):

| cell | threatKills (w / b) | humanKills | mutual | scores T / H | hives | bases | classes | plays: hammers / starves / sieges sailed |
|---|---|---|---|---|---|---|---|---|
| A + B3000 + C | 3.5* (1.9 / 1.5*) | 1.9* | 1.9* | 714 / 2* | 90.5* | 2* | both 83 | 225 / 14.5 / 73.5 (140 short of supplies) |
| relief pause off | 6.0 (2.1 / 3.9) | 1.0* | 1.0* | 873 / 8 | 133 | 8 | both 53, other 47 | 26.5 / 38.5 / 20 |
| B, capacity / 3000 | 6.3 (2.0 / 4.3) | 0.9* | 0.9* | 1070 / 11.5 | 138 | 11.5 | both 47, one-sided 37 | 43 / 49 / 21.5 |
| B, capacity / 6000 | 6.2 (2.0 / 4.2) | 0.6* | 0.6* | 908 / 12 | 132 | 12 | both 43, one-sided 30 | 31 / 33 / 16 |
| A | 5.2* (2.0 / 3.0*) | 0.4 | 0.4 | 1103 / 3.5* | 138 | 3.5* | both 13, one-sided 37 | 65 / 15.5 / 13.5 (81.5 short of supplies) |
| C | 7.7 (2.2 / 5.4) | 0.2 | 0.2 | 932 / 14.5 | 139 | 14.5 | one-sided 67 | 3 / 9 / 3 (2 turned to hammers) |
| consolidate on losses | 6.9 (2.1 / 5.0) | 0.2 | 0.2 | 1085 / 13 | 151 | 13 | one-sided 70 | 5 / 10 / 2.5 |
| personalities all Tri-Tachyon / all Hegemony | 7.9 / 7.4 | 0.2 / 0.2 | 0.2 / 0.2 | 1064 / 16, 1076 / 15 | 150 / 147 | 16 / 15 | one-sided 70 | 5 / 12 / 3, 5 / 10.5 / 2 |
| temperature 2 / 0.5 | 6.8 / 6.9 | 0.2 / 0 | 0.2 / 0 | 869 / 16.5, 1032 / 13.5 | 129 / 143 | 16.5 / 13.5 | one-sided 67 / 80 | 7 / 8.5 / 3, 4 / 9.5 / 2 |
| hammer share 0.8 / 0.4 | 6.9 / 6.9 | 0.1 / 0 | 0.1 / 0 | 1125 / 12, 1009 / 15 | 154 / 150 | 12 / 15 | one-sided 67 / 90 | 5 / 10 / 2 |
| base; muster floor 0.25 / 0.75; learning off | 7.1 (2.2 / 4.7) | 0 | 0 | 967 / 13 | 144 | 13 | one-sided 73, other 27 | 5 / 11 / 2.5 |

Mid-war 48 months: every cell's humanKills and mutual are 0 except relief pause off (0.3*, bases held 11.5 -> 8*). A
again burns the bases (held 11.5 -> 5*, 48.5 sieges short of supplies), A+B+C 2*; B3000 sails 3* sieges to the base's 0;
consolidate on losses puts the swarm in CONSOLIDATE 5.2 months of 48 (base 0; the start state's fronts on hives trigger it)
and halves its founding (88.5 -> 54*, hives at the end 145 -> 110*).

Reading. B is the one idea that raises the humans' killing without a cost: more plays at once, sieges sailed 2.5 -> 21.5,
bases held intact; its gain is capped by the relief pause (`council.heldPlayDays` 4324 -> 19138). The relief pause is the
main brake on everything - relief is owed about 70% of faction-days here (`council.reliefDays` 8713 of 12480), which round 7
found the simulator overstates, so relief-off's 1.0 is an upper bound and B's 0.9 is the honest figure. A fails for the
doc's own reason: a hammer per strategy without the means behind it - 81.5 of the sieges could not be supplied and the
bases that paid were lost (13 -> 3.5), which is why A+B+C's 1.9 costs the humans' score (2). C changes nothing: a
campaign reaches "starved" twice a run. Shares, the muster floor, learning, temperature and personalities move nothing
clear of seed noise - with planner sizing the hammer share only sizes the hunting force. Feints: only A/A+B+C run them;
they drew the swarm 0.5 of 2.5 and 2 of 10.5 times (mid 0 of 5.5), and the strike landed 0 times after a drawing feint, 2
after one that did not - the feint as built does not pull. Decision 5 as worded consolidates more mid-war, not less:
a single front on a hive is enough; the pressure path barely fires now (2.1 months of 104 in the base).
