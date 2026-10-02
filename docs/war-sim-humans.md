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
| `HumanCouncil.pausable`, `siegeCapacity`, `plan`'s `limit` | `ThreatPlays.pausable`, `siegeCapacityFP`, `majorLimit` (bc623ed) | Round 14: relief owed holds only PREPARE, a MUSTER under `councilMusterFloor` x planned FP, BOMB/WATCH with no squadron out and no siege sailed; major plays = max(1, capacity / `councilMajorPlayFP`), capacity each distinct base of the picture's clusters against its nearest cluster. Joint plays are not mirrored. |
| `HumanPools.pay(.., what)`, `drain`, `daily` | the ledger (round 14) | Every pool payment is booked `spend.<what>.<commodity>` and `spendBy.<faction>.<what>.<commodity>` (siege, playSiege, saturation, hunt, huntForce, squadron, raid, scout, convoy, guardVoyage, guardUpkeep, baseUpkeep, link, fleetUpkeep); accrual is `income.<commodity>`. `HumanCouncil.strike` counts `strike.paid` / `strike.unpaid` and, for an unpaid first world, `strike.shortBy.<c>` against `strike.held.<c>`, `strike.needFP` against `strike.paysFP`. Switches off by default: `warsim_siegeFirstCall` (no hunts while a hammer stages), `warsim_guardUpkeepMult`, `warsim_coalitionPays` (a siege calls on partners' depots), `warsim_accrualMult`. |
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

## 9. Round 14: paying is the brake (2026-10-02)

The mod's new defaults mirrored (`pausable`, `siegeCapacity`, `councilMajorPlayFP` 3000). Base cell, council, radar off,
planner sizing, 30 seeds. New game 104 months: threatKills 6 (2.1 / 3.7), humanKills 1.0 [0.1 - 2.5], mutual 1.0, scores
805 / 12, hives 117 [56 - 180], bases 12, both sides 53%; hammers 52 a run, 27 reached STRIKE and paid a siege, 14.5
did not; 137 starve checks found no saturation the pools pay (48.5 sailed). Mid-war 48 months: humanKills 0, 3.5 paid,
1.5 unpaid. The seeds are bimodal (hives 56-180), so no trial's humanKills is clear of noise at 30 seeds.

**What the unpaid were short of** (per unpaid hammer, first world): the report-sized siege needs 1,790 FP, the base pays
the voyage of 256; short of supplies by 3,260 against 2,260 callable (10.5 of 14.5 short of supplies, 2.5 of fuel, 1 of
marines; fuel callable 24,500, marines 5,100). Mid-war: 6,200 FP needed, 1,100 paid, short 9,840 supplies against 13,200.
Supplies are the brake; fuel and marines are not.

**Where the supplies go** (new game, a year, income 183k): forward-base links 60k, guard upkeep 46k, fleet upkeep 44k,
base (colony size) upkeep 27k, guard voyages 8.6k, saturation 8.3k, hunts 7.9k + hunting forces 5.2k, squadrons 5.9k,
scouts 3.5k, play sieges 2.5k (1.4%). Per faction (play sieges / hunts / guard upkeep / fleet upkeep): hegemony
358 / 2,026 / 12,230 / 14,769, persean 714 / 1,673 / 12,115 / 10,621, tritachyon 337 / 827 / 5,860 / 5,195. Mid-war
(income 377k): links 128k, fleet upkeep 113k, guard upkeep 99k, play sieges 611. Fuel (income 379k): saturation 75k,
guard voyages 54k, hunts 32k, scouts 24k, squadrons 18k, sieges 6k. The forward-base line (links, guards, their
voyages) takes about two thirds of the supplies; sieges get one or two percent.

| cell (new game) | threatKills (w / b) | humanKills | mutual | T / H | hives | bases | hammers paid / unpaid | classes |
|---|---|---|---|---|---|---|---|---|
| base | 6 (2.1 / 3.7) | 1.0 | 1.0 | 805 / 12 | 117 | 12 | 27 / 14.5 | both 53 |
| a hunts held while a hammer stages | 6 (2.0 / 3.9) | 0.5 | 0.5 | 863 / 13.5 | 135 | 13.5 | 13 / 14 (89.5* hunts held) | both 30 |
| b guard upkeep x0.5 | 6.2 (2.0 / 4.2) | 0.8 | 0.8 | 768 / 17.5 | 115 | 17.5 | 19.5 / 14 | both 37 |
| c coalition pays | 5.7 (2.0 / 3.7) | 1.5 | 1.5 | 799 / 12 | 120 | 12 | 36.5 / 14.5 (73 saturations) | both 60 |
| d orbit margin 1.25 / 1.0 | 6.1 / 5 | 0.6 / 1.1 | 0.6 / 1.1 | 857 / 12, 738 / 10 | 123 / 107 | 12 / 10 | 17.5 / 14.5, 32.5 / 15.5 | both 37 / 53 |
| e accrual x1.5 | 5.7 (1.8 / 3.9) | 1.3 | 1.3 | 850 / 17 | 124 | 17 | 30.5 / 12 | both 60 |

Mid-war: humanKills 0 in every cell; b lifts bases held 11.5 -> 19, e 11.5 -> 33.5* and the swarm's bases destroyed 3.9 ->
8.3* (more to destroy), c sails 32.5* saturations (21.5). Reading: the pools are not short, the allocation is - the
ceiling test (e) buys forward bases, not sieges, and halving guard upkeep (b) does the same; the one lever that moves
sieges is who can be called on (c, 36.5 paid, 1.5 a year) because a siege's supplies are the one faction's callable
stock while the forward-base line spends first. Holding hunts for the siege (a) helps nothing: hunts take 4% of the
supplies. Lowering the orbit margin cuts the FP need but the supplies want scales with the fleet it still needs.

## 10. Round 15: allocation rules for the siege's supplies (2026-10-02)

Five rules the mod could hold, each a switch off by default, no fitted constants. All take `HumanPools.gives(.., what)`: a
non-siege payment of supplies is cut by `HumanPools.reserve(s, depot, what)`, the purpose being the round-14 ledger's name
(`forwardLine`: link, guardVoyage, guardUpkeep, baseUpkeep). `HumanBases.plan`, `cannotGuard` (`spareFor`) and the garrison
upkeep pass their purpose; `drain` (fleet upkeep) and every siege payment (`siege=true`) are untouched.

- (a) `warsim_siegeReserve`: a staging play's siege has first call. `HumanCouncil.reserveSiege` (daily, after `advance`)
  sizes the first live world of every hammer or feint in PREPARE or MUSTER as `strike` will (`HumanPlanner.size` on the
  faction's report), sums the supplies wanted to `Faction.siegeWant`, and spreads it over the faction's depots by their
  callable supplies (`World.siegeReserve`); the forward-base line spends only what is left. Counters `siegeReserveDays`,
  `siegeWant.paidDays` / `.unpaidDays` (faction-days a staging siege was / was not affordable).
- (b) `warsim_siegeSplit` (0.25, 0.5): a standing share of each depot's callable supplies kept from every non-siege purpose.
- (c) `warsim_maxLinks` (2, 4): `HumanBases.plan` founds no link while the faction holds that many forward bases (`linkCapped`).
- (d) `warsim_strategyReserve`: (b)'s share set by the council's strategy (`HumanCouncil.reserveShare`: Hold 0, Starve and
  Rollback 1/3, Decapitate 1/2; 0 under the planner).
- (e) `warsim_linkWaitsForSiege`: no new link while `Faction.siegeUnpaid` (any staging siege of (a)'s sizing unaffordable
  today; `linkHeldForSiege`).

Ranked by the humans' own score (facts.md "The data decides balance"): `humanScore` = bases held + killWeight x hives killed,
killWeight 1, subject to `threatScore` not under the base's p10 and the quiet and stalemate shares not above the base's;
`mutual`, `turnover`, `deadYears` and the classes are diagnostics. The base cell is round 14's exactly.

30 seeds, council on, radar off, planner sizing; new game 104 months (base `threatScore` p10 560) and mid-war 48 months
(p10 263). humanScore / threatScore, bases founded / held, hives killed, hammers paid / unpaid, mutual, classes
(both-sides / one-sided / other). * = clear of the base's seed noise (`Main.compare`).

| cell | new game | mid-war |
|---|---|---|
| base | 21 / 842, 52.5 / 12, 6, 27 / 14.5, 1.0, 53 / 20 / 27 | 11.5 / 307, 38.5 / 11.5, 0, 3.5 / 1.5, 0, 0 / 73 / 27 |
| a first call | 18 / 835, 53 / 12, 4, 18 / 14, 0.6, 43 / 27 / 30 | 12.5 / 313, 41.5 / 12, 0, 3.5 / 2.5, 0, 0 / 77 / 23 |
| b split 25% | 18 / 921, 49 / 12, 5, 16.5 / 9.5, 0.8, 37 / 27 / 37 | 11.5 / 301, 36.5 / 11.5, 0, 3 / 1, 0, 0 / 83 / 17 |
| b split 50% | 15.5 / 794, 43 / 9, 4, 20 / 12.5, 0.6, 37 / 20 / 43 | 10.5 / 296, 31.5 / 10.5, 0, 4 / 1, 0, 0 / 80 / 20 |
| c cap 2 | 10.5 / 807, 28* / 6*, 5.5, 20.5 / 13.5, 0.9, 50 / 17 / 33 | 7 / 300, 18* / 6.5, 0, 4 / 3, 0, 0 / 67 / 33 |
| c cap 4 | 15.5 / 815, 45 / 10.5, 5, 21 / 13.5, 0.8, 47 / 20 / 33 | 11 / 301, 30.5 / 10, 0, 4 / 2, 0, 0 / 70 / 30 |
| d by strategy | 17 / 775, 49.5 / 11, 5, 16 / 12.5, 0.8, 37 / 17 / 47 | 12.5 / 298, 36 / 12.5, 0, 3.5 / 2, 0, 0 / 80 / 20 |
| e link waits | 20.5 / 802, 52 / 11, 6.5, 25.5 / 14, 1.1, 53 / 20 / 27 | 14 / 299, 38 / 14, 0, 5 / 2, 0, 0 / 80 / 20 |

Reading. No cell raises the humans' score from a new game; every reserve (a, b, d) lowers paid hammers (27 -> 16-20) rather
than raising them, and the caps (c) only cost bases. The mechanism: a hammer needs a base of the faction in the cluster's
range (`startHammer`'s `baseOf`), and the forward bases are what put one there - so what starves the forward-base line starves
the hammers with it (`plays.HAMMER` 52 -> 32-42 under a, b, d). First call (a) had a reserve standing 2,749 faction-days but
the staging siege was already affordable on 2,003 of them: the unpaid hammers are unpaid at `strike`, not at staging, and
the stock they lack is not what links spent since. Only e holds the base's score (20.5 / 21 new game; 14 / 11.5 mid-war,
70% of seeds, not clear) because it holds few links back (20.5 a run). At 60 seeds e is noise too: new game 19 against 18
(56% of seeds), mid-war 13 against 12 (63%), paid hammers 21.5 against 25 and 3 against 3. Kill weight: at 0 the score is bases held, where
nothing beats the base from a new game and e leads mid-war; at 2 no cell's kills (4-6.5 against 6) change the order.
Bases held moved up only in e mid-war; down in b50 and both caps. Verdict: no change to the mod. Next hypotheses: a counter-
siege or relief sized to the enemy at the base (round 8's missing answer); the siege's own supplies want (trip upkeep in
`ReachRules.siegeSuppliesPerPoint`) against what the mod actually draws; and why `strike.unpaid` is flat at 14 under every
allocation - what those hammers' bases hold on the day, by `strike.shortBy`.

## 11. Round 16: the shortfall's anatomy, partial sailings, relief to the besieged (2026-10-02)

**Anatomy of an unpaid hammer** (new game, base cell, `strike.shortBy` / `strike.held` / `strike.needFP` / `strike.paysFP`
over 14.5 unpaid a run): the report-sized siege needs 1,790 FP (72 points); the pools provision 256 FP. Short of supplies
3,260 against 2,260 callable; fuel 356k callable against a shortfall only in 2.5 of 14.5; marines 1 of 14.5. The supplies
want per point (`HumanPlanner.size`, `ReachRules.siegeSuppliesPerPoint`) is the 30-a-point hull deposit plus the ships'
upkeep for the whole trip, 25 FP x 0.94 a month x `siegeTripDays` / 30 - about 77 a point at the trips these sieges make
(muster 15 days, passage out at 0.5 ly a day, the bombard plan's stay, passage home): the trip is two thirds of the want.
Only the deposit is drawn at the launch; the trip is billed as it goes (`HumanSide.upkeep`). The shortfall is deep, not
marginal: the pools provision 14% of the siege (256 of 1,790 FP), so a rule that sails what is payable above the 50% muster
floor (trial b below) fires on 0 hammers a run (p90 2) - the unpaid hammers are seven times short, which is why no
allocation of the same pools (round 15) moved `strike.unpaid` and why `strike.paid` tracks `plays.HAMMER` instead.

**The mod's want is the same kind, and harsher.** pd10a's gate lines (`IncursionManager.siegeCanPay`: "Expedition postponed at
Hanuman Forward Base against Enyen: 41159/9455 fuel, 8822/11845 supplies across 16 markets pay for 75 of the 101 points
(needs 101)"; 12,606 such lines, 423 draws, 65 trims) price about 117 supplies a point - `siegeStayDays` takes the slowest
world's bombard plan and any razing - and require every point (`npcSiegeFullStrength` true: `mustPay` = the orbit's points),
fuel four times over. The draw is the deposit alone ("840/840 supplies"). So the simulator's want is of the right make and
if anything kind; no fidelity fix is owed. The coordinator's trial (a), every base in range paying, is already how both work:
`HumanPools.donors(.., siege=true)` is the faction's every depot down to its floor, as the mod's `siegeDonors` ("across 16
markets"); `richestBase` picks only where the siege stages.

Switches, off by default:
- (b) `warsim_hammerSailsPartial` (`HumanCouncil.strike`): a hammer whose provisions pay at least `councilMusterFloor` of
  the report-sized orbit (`Option.paysFP`, the gate's payable) sails with the fleets they pay for (`HumanPlanner.size(..,
  playFP)`, grown to carry the landing) and judges on arrival (`HumanSiege.orbitDay`'s call-off); short of marines it waits.
  Counters `playSiegesPartial`, `.fpShare`.
- (c) `warsim_tripBilledOut` (`HumanPlanner.size`): the gate prices the voyage out and the stay; the way home is billed as
  it goes like every other fleet's upkeep. The draw is unchanged.
- (r) `warsim_reliefToBesiegers` (`HumanBases.besiegers`, `guardAgainst`, `garrison(.., wanted, relief)`): a Threat strike
  bearing on or besieging a forward base that the faction has a report of (arrived, or in flight from a hive system in
  `Faction.reports`) calls at once for a relief sized to its strength x `frontlineGarrisonMargin` over the base's own
  defence, from the pools, past the upkeep budget (`cannotGuard`) but not past the voyage's price. Round 8's missing
  answer. Trialled with bases falling at once (the default) and under `warsim_basesHold` (the 30-day station siege a
  relief can lift in `SwarmOps.stationSiegeDay`).

30 seeds, same settings and objective as round 15. humanScore / threatScore, bases founded / held / destroyed, hives killed,
hammers paid / unpaid, mutual, classes (both / one-sided / other); * clear. The rh pair's base is `warsim_basesHold=true`.

| cell | new game | mid-war |
|---|---|---|
| base | 21 / 842, 52.5 / 12 / 22.5, 6, 27 / 14.5, 1.0, 53 / 20 / 27 | 11.5 / 307, 38.5 / 11.5 / 15.5, 0, 3.5 / 1.5, 0, 0 / 73 / 27 |
| b partial sailing | 21 / 845, 53 / 12 / 23.5, 6.5, 27 / 14.5, 1.0, 53 / 20 / 27 (0 partial) | 12.5 / 307, 38.5 / 12 / 15, 0, 3 / 1, 0, 0 / 70 / 30 |
| c trip billed out | 21 / 873, 55.5 / 13 / 22.5, 6, 24.5 / 14, 1.0, 50 / 20 / 30 | 13.5 / 307, 38.5 / 13 / 15, 0, 3.5 / 1.5, 0, 0 / 73 / 27 |
| r relief | 21 / 831, 44.5 / 13 / 12.5*, 5, 16.5 / 11.5, 0.8, 43 / 23 / 33 | 10.5 / 303, 28 / 10 / 7.5*, 0, 3 / 2, 0, 0 / 77 / 23 |
| basesHold alone | 21 / 842, 55.5 / 14 / 19.5, 4, 15 / 15, 0.7, 47 / 23 / 30 | 14 / 301, 37 / 14 / 13.5, 0, 3 / 2, 0, 0 / 70 / 30 |
| rh relief + basesHold | 21 / 774, 39.5* / 14.5 / 7*, 5.5, 16 / 12.5, 1.0, 50 / 23 / 27 | 14 / 295, 31 / 14 / 4.5*, 0, 5 / 2.5, 0, 0 / 70 / 30 |

Reading. Partial sailing never fires (above) and billing the trip out moves nothing: the trip home is a tenth of the want.
Relief to the besieged does what it says - bases destroyed halve, clear in both starts and both base worlds (22.5 -> 12.5,
19.5 -> 7, 15.5 -> 7.5, 13.5 -> 4.5; station sieges lifted 1.5 -> 5.5) - yet the humans' score does not move (21 / 21, 50%
of seeds; mid-war 10.5 against 11.5): the relief's voyages and the garrisons it leaves behind (sized to the strike x margin,
past the upkeep budget) spend the same supplies the links and the hammers wanted - bases founded 52.5 -> 44.5, paid hammers
27 -> 16.5, garrisons recalled unpaid 25.5 -> 34 - so bases held ends 13 against 12 and the war slows (`deadYears` 0.6 ->
0.8, mid-war 0.7 -> 1.0). Kill weight: at 0 r leads on bases held by one (61% of seeds); at 2 the order is unchanged; the
Threat's score falls under r (its bases destroyed) but stays above the base's p10 at every weight. Verdict: no change. The
supplies are the one budget behind links, guards, relief and sieges, and every rule so far moves spending between them. Next:
a relief that goes home when the strike is gone (so it is not a garrison with upkeep); the siege's trip price against what
the fleets actually burn (`upkeepWanted` / `upkeepOwed` against the gate's `siegeSuppliesPerPoint`); and whether the mod's
sieges, priced at 117 a point, are postponed as often as the simulator's (12,606 postponements against 423 draws in pd10a
says yes - the real council's 0-2 kills a run may be this gate).

## 12. Round 17: the siege's price (2026-10-02)

A knob grid on the price a report-sized siege must find in the pools, since round 16 found the gap 7x (the pools provision
14% of the siege). The knobs are the mod's own where it has them, simulator switches where the mod's is a formula:

- Hull deposit a point: `threatinc_expeditionSuppliesPerPoint` 30 -> 15, 10 (the gate's and the draw's alike).
- Trip horizon the gate bills (the mod's `siegeStayDays` into `siegeTripDays`; here `HumanPlanner.size`'s `trip`):
  `warsim_tripMult` 0.5, `warsim_tripFixedDays` 30. The burn (`HumanSide.upkeep`) is untouched: the fleets still eat as they go.
- Upkeep a fleet point a month (`ReachRules.DEFAULT_SUPPLIES_PER_FP` 0.94, the mod's `ThreatReach.DEFAULT_FACTION_SUPPLIES_PER_FP`):
  `warsim_suppliesPerFPMult` 0.5 through `HumanPlanner.suppliesPerFP`, read by the gate (`size`), the burn (`upkeep`), the
  reach (`HumanPools.rangeLY`) and the stance's force (`HumanStance.forceFP`) alike.
- Orbit margin `threatinc_npcSiegeOrbitMargin` 1.5 -> 1.25, 1.0 (the call-off on arrival stays: `HumanSiege.orbitDay`).
- Full orbit not required: `threatinc_npcSiegeFullStrength=false`, mirrored in `HumanCouncil.strike` only - the hammer sails
  the fleets the pools provision when they reach `threatinc_expeditionMinProvisionsFraction` (0.5) of the points, trimmed
  (`playSiegesTrimmed`; the mod's `siegeCanPay` `mustPay` and "Expedition trimmed"). The planner's own path (`HumanPlanner.plan`)
  still waits for the whole want, so a planner run does not mirror this knob.
- Income ceiling: `warsim_accrualMult` 1.5, 2 (every commodity's accrual, not supplies alone).

**Fidelity on the way.** Postponements: the simulator's planner (council off, new game, 30 seeds x 104 months) refuses
`siegesPostponed` 13,189 [8,605-17,601] options and sails 361 [250-410] sieges; pd10a logged 12,606 "Expedition postponed"
lines and 423 draws (preemptive purges included). The counts match; the make does not - the simulator's refusals are 7,661
fuel against 3,857 supplies (`postponed.<c>`), where pd10a's lines hold fuel four times over and want supplies. The planner's
farther options pay passage fuel the council's near hammers do not; the council's hammers are supplies-short in both
(round 14), so the grid's reading stands.

30 seeds, council on, radar off, planner sizing, killWeight 1; humanScore / threatScore, bases founded / held / destroyed,
hives killed, hammers paid / unpaid, mutual, classes both / one-sided / other; * clear. Base: new game 21 / 842 (p10 560),
52.5 / 12 / 22.5, 6, 27 / 14.5, 1.0, 53 / 20 / 27; mid-war 11.5 / 307 (p10 263), 38.5 / 11.5 / 15.5, 0, 3.5 / 1.5, 0, 0 / 73 / 27.

| cell | new game | mid-war |
|---|---|---|
| deposit 15 | 20.5 / 855, 55.5 / 15 / 24.5, 6, 28.5 / 15.5, 0.9, 47 / 17 / 37 | 12.5 / 299, 43 / 12 / 20.5, 0, 2.5 / 2 |
| deposit 10 | 19 / 1001, 54.5 / 13 / 22.5, 6, 20 / 22, 0.9, 47 / 13 / 40 | 13 / 305, 44.5 / 13 / 19, 0, 2 / 3.5 |
| trip x0.5 | 19 / 871, 55 / 13 / 21.5, 4.5, 20.5 / 11, 0.7, 43 / 27 / 30 | 13.5 / 310, 40 / 13 / 16.5, 0, 4 / 1.5 |
| trip 30 days | 19 / 913, 54 / 12.5 / 23.5, 4.5, 24.5 / 11.5, 0.7, 47 / 20 / 33 | 13 / 307, 39.5 / 12.5 / 17.5, 0, 4 / 1.5 |
| upkeep x0.5 | 25.5 / 753, 56.5 / 14 / 24, 8, 31.5 / 12.5, 1.3, 60 / 17 / 23 | 14 / 322, 45.5 / 13.5 / 20, 0, 3 / 2 |
| orbit margin 1.25 | 16.5 / 891, 54.5 / 12 / 25.5, 3.5, 17.5 / 14.5, 0.6, 37 / 30 / 33 | 13 / 319, 41 / 12.5 / 17, 0, 3 / 3 |
| orbit margin 1.0 | 18.5 / 772, 48 / 10 / 18.5, 6.5, 32.5 / 15.5, 1.1, 53 / 27 / 20 | 12 / 308, 40 / 12 / 19, 0, 3 / 1.5 |
| full orbit off | 21 / 845, 53 / 12 / 23.5, 6.5, 27 / 14.5, 1.0, 53 / 20 / 27 (0 trimmed) | 12.5 / 307, 38.5 / 12 / 15, 0, 3 / 1 |
| income x1.5 (ceiling) | 28.5 / 886, 71.5 / 17 / 23.5, 8, 30.5 / 12, 1.3, 60 / 20 / 20 | 33.5* / 338, 76* / 33.5* / 33*, 0, 5 / 1.5 |
| income x2 (ceiling) | 27.5 / 680, 68 / 15 / 27.5, 14, 51 / 15.5, 2.4, 83 / 7 / 10 | 42.5* / 349, 91* / 42.5* / 36.5*, 1, 6.5 / 3 |

Reading the singles. Cutting what the gate asks without cutting what the fleets burn buys nothing: the deposit at 15 or 10
and the trip at half or 30 days leave the score at 19-20.5 against 21 (more hammers reach the strike, paid ones do not rise -
28.5, 20, 20.5, 24.5 against 27 - and at deposit 10 the unpaid rise to 22: cheaper sieges sail, starve on the way
(`HumanSide.upkeep` bills the trip as it goes) and the Threat's score climbs to 1,001). The orbit margin is worse at either
cut (16.5, 18.5). Full orbit off trims nothing (the pools provision 14%, the floor is 50%). The one price knob that moves the
score is the burn itself: upkeep x0.5, gate and burn alike, 25.5 against 21 from a new game (kills 8, paid hammers 31.5,
both-sides 60%) and 14 against 11.5 mid-war, the Threat's score 753 and 322 above p10 - not clear at 30 seeds. The income
ceiling says where the bill becomes payable: x1.5 gives 28.5 and 33.5* (mid-war bases held 11.5 -> 33.5*), x2 27.5 and 42.5*
with 14 kills and both-sides 83% from a new game - the humans' war is income-bound end to end, and the siege's supplies
bill (the burn) is what the income cannot meet.

Combinations on the one knob that moved, 30 seeds (same columns):

| cell | new game | mid-war |
|---|---|---|
| upkeep x0.5 + deposit 15 | 26.5 / 847, 59 / 16 / 24.5, 8, 32.5 / 18.5, 1.3, 67 / 10 / 23 | 17 / 318, 51.5 / 16.5 / 22.5, 0, 5.5 / 2 |
| upkeep x0.5 + full orbit off | 20 / 764, 55 / 12.5 / 24.5, 7.5, 29.5 / 12, 1.3, 63 / 13 / 23 (1 trimmed) | 14 / 326, 46.5 / 13.5 / 22, 0, 3.5 / 2 |
| upkeep x0.5 + deposit 15 + full orbit off | 24.5 / 796, 59.5 / 16 / 25, 7.5, 31.5 / 18, 1.2, 60 / 7 / 33 | 17 / 318, 51.5 / 16 / 22, 0, 5 / 2 |

Upkeep x0.5 at 60 seeds: new game 23 against 18 (bases held 13 against 10.5, kills 8 against 5), mid-war 16 against 12 (bases
held 15.5 against 11.5); the Threat's score 797 and 321, both above p10 (560, 272).

**What the mod can and cannot hold.** The upkeep rate is not a knob: `ThreatReach.suppliesPerFP(factionId)` measures the
faction's fleets out - vanilla maintenance a month over fleet points (`ThreatFrontlines.maintenancePerMonth`), once a day -
and 0.94 (`DEFAULT_FACTION_SUPPLIES_PER_FP`) is only the default before one sails. Upkeep x0.5 is therefore a 2x discount on
the factions' real maintenance, which "no cheating either side" rules out as a mod rule; the cell measures the gap, it is
not a candidate. The pools' accrual is a knob: `threatinc_reserveSurplusMult` (1.0; troops `reserveTroopSurplusMult` 0.5)
scales the surplus share each colony banks a month (`ThreatReserves.accrualPer30`), which `warsim_accrualMult` stands for
(it scales every commodity, troops included, so it overstates marines a little). So the income cells are buildable settings
changes, not only a ceiling.

Kill weight. At 0 (bases held alone) the order is the same: upkeep x0.5 13-15.5 against 10.5-11.5, income x1.5 17 and 33.5*,
the price cuts 12.5-15 against 12. At 2 the kills term widens upkeep x0.5's lead from a new game (kills 8 against 5) and
income x2's (14 against 6) and changes no order; mid-war no cell kills, so the weight is moot there. The Threat's score stays
above the base's p10 in every cell at every weight; income x1.5 raises it (886 / 338 against 842 / 307: more worlds and bases
to destroy), income x2 lowers it from a new game (680) through the 14 hives killed.

**At 60 seeds.** Income x1.5: mid-war 32.5 against 12 (97% of seeds, clear; bases held 32 against 11.5*, founded 81.5
against 39.5*), new game 27.5 against 18 (80% of seeds; the median clears the base's p90 27.1, bases held 17 against 10.5,
kills 8 against 5, paid hammers 29 against 25); the Threat's score 849 and 336 against 829 and 308, above p10 (560, 272);
classes new game both-sides 60% against 45%, mid-war one-sided 62% against 80% with no quiet seed, `deadYears` 0.6 / 0.5
against 0.6 / 0.7. Upkeep x0.5 + deposit 15: 23 against 18 (71%) and 17 against 12 (72%), consistent, not clear, and not a
rule the mod may hold. Upkeep x0.5 at 120 seeds from a new game: 22 against 18 (63%).

**Verdict: `threatinc_reserveSurplusMult` 1.0 -> 1.5** (the pools bank half again the surplus share a month; the gate and
the prices unchanged - the bill is real, the income was short of it). Clear mid-war, 80% of seeds from a new game, the
Threat's score up not down, the war livelier on both measures. Caveats: the simulator scales every commodity's accrual
including the militia's marines (`reserveTroopSurplusMult` is separate in the mod and was not raised); the colonies' base
accrual is `HumanFit.ACCRUAL_BY_SIZE` fitted from the dumps, so x1.5 maps to the knob only as far as that fit holds; and
facts.md "What is still free in the human war economy" lists the reserves' accrual as unpaid banking, so this widens a
free flow - still production-bound (`reserveBankFromProduction`), not conjured. Confirm with the 5-minute mid-war game check
(`strike.paid` should rise and bases held double) before the setting is kept.

## 13. Round 18: the knob is shared; re-tests on the humans-only base; the swarm's levers (2026-10-02)

**The built change is not round 17's cell.** `threatinc_reserveSurplusMult` is read by `ThreatReserves` (the factions' pools)
and by `ThreatFuel` and `ThreatColonyUpkeep` (the hives' banking of their production); in the simulator `SwarmKnobs.surplusMult`
scales `SwarmEconomy`'s income the same way, and the human pools did not read the knob at all until this round
(`HumanPools.daily` now scales the accrual fit by `reserveSurplusMult` / 1.0, marines by `reserveTroopSurplusMult` / 0.5).
Round 17's winning cell was `warsim_accrualMult` 1.5 - the humans alone. With settings.json at 1.5 for both sides (30 seeds):
new game hives 185 [138-230] against 117, threatScore 4,109 [2,887-4,947] against 842 (round 17's cell 886), humanScore 17.5
against 21 (cell 28.5), kills 2.5 against 6 (cell 8), paid hammers 11.5 against 27, both-sides 37%; mid-war hives 226 against
145, threatScore 867 against 307 (cell 338), humanScore 20 against 11.5 (cell 33.5), kills 0, one-sided 97%. The swarm's
production is the larger base, so a shared multiplier hands it the bigger gain and the humans end below their old score.
A humans-only lever needs its own knob in the mod (`ThreatReserves.accrualPer30`'s multiplier split from the hives'), or
`reserveSurplusMult` back at 1.0 until it has one. This round's grid therefore runs on the humans-only base
(`threatinc_reserveSurplusMult=1.0; warsim_accrualMult=1.5`), with the shared 1.5 as a cell against it.

The same day the hives' banking was split off as `threatinc_hiveSurplusMult` (1.0; `ThreatFuel`, `ThreatColonyUpkeep`;
`SwarmKnobs.surplusMult` reads it) and `reserveSurplusMult` 1.5 became the humans' alone (`ThreatReserves.surplusMult`). The
new defaults against this base on the same 30 seeds (`compare`, `-killWeight 1`, `-months 104` / `48`): new game humanScore
30 against 28.5, threatScore 864 against 886; mid-war 32.5 against 33.5, 336 against 338; every class share identical. The
one real difference is marines: `warsim_accrualMult` scaled all four commodities, the knob leaves marines at
`reserveTroopSurplusMult` as the mod does (and the simulator still puts armaments under `reserveSurplusMult`, where the mod
has them under the troop share) - within seed noise on both starts.

Cells, 30 seeds, both starts, every one on that base. Human side, ranked by humanScore: the shared 1.5 (both sides);
relief to the besiegers (`warsim_reliefToBesiegers`, round 16 r); links waiting on an unpaid siege (`warsim_linkWaitsForSiege`,
round 15 e); upkeep x0.5 (`warsim_suppliesPerFPMult`, a diagnostic - not a rule the mod may hold, round 17); the council's
major plays per FP (`threatinc_councilMajorPlayFP` 3000 -> 2000, 5000); the muster floor (`threatinc_councilMusterFloor` 0.5
-> 0.25, 0.75); the humans' accrual at 1.25 and 2.0 (`warsim_accrualMult`) to show where 1.5 sits. Swarm side, ranked by
threatScore with humanScore not under the base's p10 and no quieter war: the Seeding Swarm's price (`warsim_seedPriceMult`
0.75, 1.25; `SwarmKnobs.foundSupplies`), the EXPAND feed share (`threatinc_feedShareExpand` 0.5 -> 0.35, 0.7), the
CONSOLIDATE trigger share (`threatinc_stanceConsolidateShare` 0.5 -> 0.25, 0.75), the relief strike's weight
(`threatinc_strikeReinforceWeight` 4 -> 2, 8). Not knobs, so not cells: the Bastion's timing (`SwarmFit.BASTION_DAYS` 120 and
`BASTION_FP`, bought from idle fabrication in `SwarmEconomy.militaryTier`) and a strike reserve share (the simulator's
strikes are sized by `StrikeRules.sustainableFP` on the spare stock, no share knob).

**New game, 30 seeds** (humanScore, threatScore, bases founded/held/destroyed, hives killed, hammers paid/unpaid, mutual,
both-sides share; `*` clear). The humans-only base: 28.5 [17.8-50], 886 [563-2,320], 71.5/17/23.5, 8, 30.5/12, 1.3, 60%
(one-sided 20%, quiet 0%, other 20%) - round 17's cell reproduced (28.5, 886).

| cell | humanScore | threatScore | bases f/h/d | kills | paid/unpaid | mutual | both |
|---|---|---|---|---|---|---|---|
| shared 1.5 (both sides) | 17.5 | 4,109* | 84/13.5/51* | 2.5 | 11.5/8 | 0.4 | 37% |
| r relief to besiegers | 33 | 788 | 58.5/16/15* | 8.5 | 28.5/14 | 1.4 | 60% |
| e links wait on siege | 27.5 | 970 | 64/16.5/22.5 | 8 | 26/12 | 1.2 | 60% |
| upkeep x0.5 (diagnostic) | 28.5 | 767 | 70.5/13.5/29 | 12 | 48/10 | 2.0 | 77% |
| councilMajorPlayFP 2000 | 27 | 713 | 67/15.5/26 | 9.5 | 34/16 | 1.5 | 60% |
| councilMajorPlayFP 5000 | 30 | 773 | 64.5/19.5/25 | 9 | 27.5/10.5 | 1.5 | 57% |
| councilMusterFloor 0.25 | 27.5 | 886 | 69.5/16/23 | 8 | 30.5/12.5 | 1.3 | 60% |
| councilMusterFloor 0.75 | 28.5 | 881 | 71.5/17/23.5 | 8 | 30.5/12 | 1.3 | 60% |
| accrual 1.25 | 24.5 | 807 | 61/14/25 | 9 | 28.5/14 | 1.5 | 63% |
| accrual 2.0 | 27.5 | 680 | 68/15/27.5 | 14 | 51/15.5 | 2.4 | 83% |
| seedPriceMult 0.75 | 23 | 540 | 49.5/9/19.5 | 14 | 43/10.5 | 2.1 | 57% |
| seedPriceMult 1.25 | 25 | 588 | 61/12/24 | 14 | 41.5/16.5 | 2.2 | 87% |
| feedShareExpand 0.35 | 27.5 | 924 | 70/15.5/26.5 | 7 | 23.5/15 | 1.2 | 53% |
| feedShareExpand 0.7 | 26 | 755 | 68/15/23 | 11 | 33/16 | 1.7 | 67% |
| stanceConsolidateShare 0.25 | 20 | 648 | 52.5/10/24 | 12.5 | 44.5/16 | 2.2 | 87% |
| stanceConsolidateShare 0.75 | 32.5 | 1,587 | 74.5/21.5/25.5 | 5.5 | 24/17 | 1.0 | 50% |

The muster floor at 0.25 and 0.75 moves nothing (0.5's figures to the half-point): the floor is a share of the FP the play
sent (`Play.plannedFP`, as `ThreatPlays`), so a muster that lost nothing en route always clears it. Section 14 takes that up.
`threatinc_strikeReinforceWeight` 2 and 8 reproduce the base to the digit on both starts: `SwarmOps.strike` multiplies a
relief target's weight by it, and the pick never turns on that weight, so the knob is not testable here.

**Mid-war, 30 seeds.** Base 33.5 [14.8-49.1], 338 [294-373], 76/33.5/33, kills 0, 5/1.5, mutual 0, one-sided 73% (both 0%, quiet 0%) -
round 17's cell again (33.5, 338). Hammers hardly fire from month 114 (10 plays, 5 paid, 0 kills a run), so the start is
decided by links and guards, and the humans' accrual is the only lever that moves it: `warsim_accrualMult` 1.0 -> 11.5
(round 17), 1.25 -> 20.5, 1.5 -> 33.5, 2.0 -> 42.5 (new game 21, 24.5, 28.5, 27.5 - it saturates at 1.5 there).

| cell | humanScore | threatScore | bases f/h/d | paid/unpaid | note |
|---|---|---|---|---|---|
| shared 1.5 (both sides) | 20 | 867* | 104/20/74.5* | 1*/0.5 | one-sided 97% |
| r relief to besiegers | 33.5 | 327 | 68.5/33.5/14* | 5/2.5 | destroyed bases halved, score flat |
| e links wait on siege | 33 | 329 | 79.5/33/29.5 | 5.5/2 | |
| upkeep x0.5 (diagnostic) | 36.5 | 339 | 87/36/36.5 | 8/3 | |
| councilMajorPlayFP 2000 / 5000 | 34 / 31.5 | 325 / 345 | 80/33.5/28.5, 82.5/31/37 | 5/4, 4/1 | |
| councilMusterFloor 0.25 / 0.75 | 33.5 | 338 | base to the digit | | inert (section 14) |
| accrual 1.25 / 2.0 | 20.5 / 42.5 | 322 / 349 | 59.5/20/26, 91/42.5/36.5 | 3/1.5, 6.5/3 | |
| seedPriceMult 0.75 / 1.25 | 33 / 34 | 409* / 294 | 79/33/36, 77.5/33/30 | 3.5/2, 4/1.5 | cheaper seeds: new game 540, mid 409 - the signs disagree |
| feedShareExpand 0.35 / 0.7 | 33.5 / 33 | 338 / 365 | base, 81/32/34 | | 0.35 inert at mid-war |
| stanceConsolidateShare 0.25 / 0.75 | 33 / 33.5 | 333 / 338 | 76.5/32/31, base | | 0.75 inert at mid-war |

**60 seeds** (the cells nearest a win; new base 27.5 [16-40.5], 849 [546-2,264]; mid base 32.5 [14.9-49], 336 [293-372]):
`warsim_huntMinShare` 0.1 new 30 (65% of seeds), mid 31.5 - noise; `warsim_reliefToBesiegers` new 28.5, mid 33.5, bases
destroyed 25 -> 14* and 35 -> 14* - the only clear effect of the round, and the score does not follow it (fewer links sail,
round 16); `threatinc_stanceConsolidateShare` 0.75 new threatScore 1,382 [637-2,364] against 849 (76% of seeds), humanScore
32.5 above the base's p10, both-sides 53% against 65%, mid-war identical to the base - the one swarm candidate, not clear by
the rule (medians inside each other's bands); a 120-seed new-game rerun would settle it.

**Verdict: no change on either side.** The humans-only base holds round 17's figures on both starts; nothing clears it. The
swarm's levers at hand (seed price, feed share, consolidate trigger, relief weight) are inert or mixed at mid-war; the new
game shows one candidate (consolidate at 0.75) to rerun. Hypotheses for round 19: a humans-only income knob in the mod (the
precondition for any of this to be buildable); the CONSOLIDATE share at 0.75 at 120 seeds; a hunt-share want of the
hammer's own (section 14, so a muster floor can judge something); the relief rule paired with a cheaper link so the bases it
saves are not paid for in links unsailed.

## 14. Round 18 addendum: the muster floor judges nothing; what the hunts alone achieve (2026-10-02)

In the mod and the mirror alike, `plannedFP` is the FP `force` actually sent (`councilHammerShare` x the payable FP, if at
least 25 FP), so `councilMusterFloor x plannedFP` only fails a muster that lost ships en route: `hammer.underFloor` is 0 a
run [0-1] on both starts. The hunts then sail whether or not the siege is paid ("the hunts go alone"). Counters split a
hammer that reached STRIKE by whether its siege sailed (`HumanCouncil.end`, `Play.struck`/`sieged`): on the humans-only
base, new game, 9 hammers a run [4.9-19.6] go alone against 28 [6-70] with a siege; the hunts alone take 0 worlds in every
run and lose 1,142 FP of hunt fleets a run [321-3,036] (the sieged hammers take 1.5 [0-6.1] and lose 5,422 FP of hunts).
Mid-war: 1 alone a run [0-6.2], 0 taken, 56 FP lost; 4.5 sieged, 0 taken [0-1]. The hunts alone are a pure loss: roughly a
fifth of the hunt FP the hammers lose, no world taken.

Three switches, off by default, 30 seeds on the humans-only base (humanScore new / mid; base 28.5 / 33.5):

| switch | symbol | new | mid | what moved |
|---|---|---|---|---|
| (a) floor vs want | `warsim_musterFloorVsWant`, `HumanCouncil.floorBase` | 27 | 34 | fails 26 musters a run (of 57.5 hammers): paid sieges 30.5 -> 17.5, kills 8 -> 5, hunts alone 9 -> 2 |
| (b) no hunts alone | `warsim_noHuntsAlone` | 30 (44% of seeds) | 30 | 9.5 stand-downs a run, hunts alone 0; kills 8 -> 13 (65%), paid 30.5 -> 37, plays 59 -> 75.5, mutual 1.3 -> 2.0 |
| (c) hunt share >= 0.1 of the strongest world | `warsim_huntMinShare` | 33.5 (64%) | 30.5 | 2 plays a run fail at PREPARE; hunts alone 9 -> 7.5 |
| (c) hunt share >= 0.25 | `warsim_huntMinShare` | 32 (59%) | 32.5 | 5.5 fail a run; hunts alone 9 -> 7.5 |

(a) is wrong as a rule: the want `floorBase` compares against is the report-sized siege plus the hunt share, and the
muster holds only the hunts, so it fails the plays whose siege the pools would have paid. The floor would need a want for the
hunt share alone, which neither the mod nor the mirror defines. (b) does what it says - no hunt is lost alone - and the
plays recycle faster (a stood-down play ends instead of watching), which lifts paid sieges and kills, but humanScore does
not follow (median up, 44% of seeds). (c) at 0.1 is the best human cell of the round on the new game and is noise on the
mid-war start; the 60-seed rerun is in section 13's verdict.

## 15. Round 19: the consolidate share's shape; converting the hunts' FP into a paid siege (2026-10-02)

On the new defaults (`reserveSurplusMult` 1.5 humans, `hiveSurplusMult` 1.0), 60 seeds, new game 104 months, `-killWeight 1`
with the kill-weight-2 score printed alongside (`Main.score`: `humanScoreK2`, `threatScoreK2`, no second run). Mid-war is
not rerun for the consolidate share (0.75 reproduced the base to the digit there, section 13) nor for `huntMinShare` 0.1 and
`noHuntsAlone` (31.5 / 30 against 32.5 / 33.5 at 60 / 30 seeds, section 13-14).

New switch `warsim_hammerWaitsForSiege` N (`HumanCouncil.strike`, `Play.waitSince`, `WAIT_RETRY_DAYS` 10): a hammer whose
siege the pools cannot pay at STRIKE holds its mustered hunts at the base (`HumanOrder.sailDay` back to `HELD`; the
muster's upkeep runs on) and re-tries the siege every 10 days (`musterCheck` waits for `phaseDue`) until N days have passed,
then stands down (`hammer.waitExpired`). `hammer.waitPaid` counts the sieges a wait bought; `hammer.waitRetries` the
re-tries (each is also a `strike.unpaid`). Relief that goes home once its strike is gone (2c) was not built - time.

Every command, from the repository root with `tools/warsim/out` built (`tools/warsim/build.ps1`), is
`java -cp tools\warsim\out warsim.Main compare -a warsim_noop=0 -b <cell> -start tools\warsim\start\pd9a-newgame -seeds 60
-months 104 -killWeight 1` (mid-war: `pd9a-month114`, `-months 48`), `<cell>` one of
`threatinc_stanceConsolidateShare=0.65|0.75|0.85`, `warsim_noHuntsAlone=true`, `warsim_huntMinShare=0.1`,
`warsim_hammerWaitsForSiege=60|120`.

**Results** (new game, 60 seeds; base on the new defaults: humanScore 29 [18-44.3], K2 38.5; threatScore 844 [556-2,243],
K2 880; bases founded/held/destroyed 64/17/26, kills 10, hammers paid/unpaid 33/14, mutual 1.7, both-sides 63%):

| cell | humanScore (K2) | threatScore (K2) | seeds | bases f/h/d | kills | paid/unpaid | mutual | both | verdict |
|---|---|---|---|---|---|---|---|---|---|
| consolidateShare 0.65 | 31 (44) | 1,066 (1,108) | T 60% | 74/17.5/27.5 | 9.5 | 33/15 | 1.7 | 63% | not clear |
| consolidateShare 0.75 | 30 (39) | 1,520 (1,553) | T 76% | 75/21/27.5 | 6 | 21/16 | 1.0 | 52% | not clear: [657-2,402] inside [556-2,243] |
| consolidateShare 0.85 | 31 (40) | 1,176 (1,228) | T 63% | 75.5/21/27 | 7 | 24/15 | 1.2 | 55% | not clear; the peak is 0.75 |
| noHuntsAlone | 26.5 (35) | 810 (853) | H 39% | 62.5/16/25.5 | 8.5 | 32.5/16 | 1.5 | 63% | no - round 18's 30-seed gain was noise |
| huntMinShare 0.1 | 30 (38) | 755 (804) | H 57% | 68.5/17/25 | 8.5 | 33/12 | 1.5 | 63% | no - noise at 60 seeds, as on the old base |
| hammerWaitsForSiege 60 | 27 (39) | 782 (825) | H 43% | 67/14.5/25 | 9 | 32.5/38 | 1.5 | 63% | no: 5 sieges a run bought by the wait (`hammer.waitPaid`), 2 waits expire, 33 re-tries, and the muster's upkeep costs more bases than the sieges win |
| hammerWaitsForSiege 120 | 28 (40.5) | 753 (799) | H 46% | 66/14/25 | 11 | 36/52.5 | 1.7 | 60% | no: the same 5 sieges a run as 60 days, 46.5 re-tries, bases held 14 |

The consolidate share's shape peaks at 0.75 (0.5 -> 844, 0.65 -> 1,066, 0.75 -> 1,520, 0.85 -> 1,176) without a
quieter war (both-sides 63% -> 52% at 0.75, quiet 0% throughout) and with the humans above their p10 (30-31 against 18) -
the swarm's one lever of the round, and not clear by the rule at 60 seeds because the seed spread of threatScore (p10-p90
556-2,243) is three times the gain. Kill weight 2 ranks every cell the same as weight 1.

**Verdict: no change on either side.** The hunts-alone FP (round 18) cannot be turned into sieges by waiting: the
wait buys 5 sieges a run but the held muster's upkeep is paid from the same pools the links draw on, and bases held fall
17 -> 14.5. Standing the hunts down outright (noHuntsAlone) saves the FP and changes nothing the score sees.
