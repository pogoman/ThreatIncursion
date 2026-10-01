# Strategy layer - code paths (siege pass, stance, orders, hunts, coalition)

How the NPC war code actually flows, mapped 2026-10-01 for the attack planner
(`attack-planner.md`). By symbol; `protected static` members are visible to every
`threatinc` class. Behaviour and its reasons live in the topic docs (`strategy-layer.md`
indexes them); this file is the call graph, so nobody has to re-trace it.

## Clocks

- `IncursionManager.advance` polls on an `IntervalUtil` of 0.4-0.6 d. The monthly `tick()` runs
  when `daysSinceTick >= tickDays` (30). Inside `tick()`, in order: `tryPurgeBombardments`,
  `ThreatSoftening.tick`, `ThreatConvoys.planLogistics`, `ThreatCoalition.tick`.
- The poll also runs `ThreatSoftening.advanceHunts()`, then the extra siege pass when
  `siegePassPending` (below), and `ThreatGroundFronts.poll` (which runs `tickSupport`).

## The siege pass - `IncursionManager.tryPurgeBombardments`

1. Returns if `!responsePurgeEnabled`; `ThreatFactionStance.refresh()`.
2. Loops `ThreatFactionStance.pressedFirst(easiestFirst(ThreatIncData.getAllLiveColonyMarkets()))`.
   `easiestFirst` sorts on {`siegeRaidStrNeeded([h])`, `siegeOrbitFaced([h])`}.
3. Per hive it skips: `!ThreatScouts.sectorKnows(colony)` (the pass's only fog gate, hive-level),
   `onPurgeCooldown(colony)`, and a system already weighed this pass (one weigh per system).
4. Bases: `siegeBasesFor(system)` -> `findSiegeBases` (non-player, non-Threat, at war, `isBase`,
   within `expeditionRangeLY`; nearest first; memoised per clock instant). Then
   `pressingFirst(system, cheapestFirst(system, bases))`: affordable first, by the credit cost of
   `expeditionWants` at base price, then nearest.
5. Per base until one launches: `reliefOwed(faction)` skips; `!siegeAllowed(faction, system)`
   skips; `targets = siegeTargets(base, faction, system)`; empty or `!anySiegeReady` skips;
   `siegeDifficulty(...)` (responseMinDifficulty + strength / responseStrengthDivisor, max 10);
   `siegeSizesFor(base, faction, targets, 0f)` -> `siegeWants` -> `siegeSizes` ->
   `siegeFleetSizes`; then `launchSiegeExpedition(...)`.
6. After a launch: the classification (defended, heavy assault, preemptive, razed), the
   ThreatNotice ("Bombing Expedition" / "Preemptive Purge" / "Full Siege" / "Siege Expedition")
   and the launch log are written HERE, in the pass, not in the launch.

At most one siege per system per pass: the system is weighed once and the base loop stops at the
first launch.

### Cooldown - `onPurgeCooldown`

Per world (colony id in `ThreatIncData.lastPurgeTimes()`), `purgeCooldownDays` x time scale,
x `purgeDefendedCooldownMult` for a defended heavy assault, cut to `purgeFollowUpDays` when the
world is wounded (`hasFront`, an organ disrupted, or `recentlyThinned(system)`). Also read by
`anySiegeReady` and `siegeTargets`' ready filter. The launch stamps every target.

### Targets - `siegeTargets(base, faction, system)`

- Player or null base: the whole system. Memoised per base:system per clock instant
  (`TARGETS_MEMO`), cleared after every launch.
- `free` = `easiestFirst(all)` minus `bookedWorlds()` (every live purge's `allowedTargets`,
  anyone's), `heldByOtherArmy`, `frontFinishesFirst`. `ready` = free and off cooldown, else free.
- Not at war: ready. At war: the longest prefix (n >= 2) `siegeAffordable` accepts; else the
  first affordable world alone; else the easiest alone (a staging fallback the launch re-gates).
- ONE sizing: every one of these reads `siegeTargets` and/or `siegeSizesFor`, so they agree:
  `hasSiegeableHive`, `cheapestFirst`, `ThreatFactionStance.weakestTarget`,
  `ThreatSwarmBountyIntel.siegeTargets`, `ThreatConvoys.siegeStock` / `stagingTargets`,
  `ThreatSoftening.stagingWants`, `ThreatFactionView`.

### Affordability

`siegeCanPay` is reached only through `siegeAffordable(...)`, whose callers are
`hasSiegeableHive`, `cheapestFirst`, `siegeTargets` and `ThreatFactionStance.weakestTarget`. The
pass itself never calls it; the money gates are inside the launch.

### Orbit sizing (all live reads today)

`siegeOrbitFP(targets)` sums `getFleetPoints` over `ThreatIncData.garrisonsFor` ->
`siegeOrbitFaced` (strongest world with `npcSiegeOrbitPerWorld`, else the sum) ->
`siegeOrbitWeighed(faction, targets)` = max(faced, `swarmsMet`, `systemSwarms` while
`npcSiegeOrbitSystem`) -> `siegeOrbitNeeded(faction, targets)` = weighed x `npcSiegeOrbitMargin`
(0 for the player or with `npcSiegeOrbitGate` off) -> `siegeFleetGoal(faction, targets, raze)` =
max(orbit need, `siegeWearFP(landTargets)`, raze fleet points).

`siegeOrbitNeeded` is recomputed in five places that must agree: the launch's orbit gate,
`siegeFleetGoal`, `siegeAffordable`, `ThreatFleetOrders.dispatchOrbit` and
`ThreatConvoys.supportFor`.

`swarmsMet` is sector memory `$threatinc_swarmsMet_<faction>_<system>` for `siegeMetMemoryDays`
(90), written only by `ThreatPurgeFGI.callOff` through `noteSwarmsMet`.

### The launch - `launchSiegeExpedition`

Three overloads; the full body takes (base, faction, system, targets, fleetSizes,
playerCommissioned, random, marineGoal, razeGiven). In order:
1. `GenericRaidParams`: source = base, prepDays 7-14, payloadDays ~ siegeOrbitDays,
   `raidParams.where = system`, SEQUENTIAL, `allowedTargets.addAll(targets)`,
   `allowNonHostileTargets`.
2. `raze = razeGiven != null ? razeGiven : razeWorlds(...)`.
3. At war: the ORBIT GATE. `orbitNeed = siegeOrbitNeeded`, `fieldable =
   ThreatAidCapacity.expeditionPoints(fleetSizes)`; if fieldable < need it logs
   `postpone:<base>:<system>`, posts `ThreatSwarmBountyIntel.post(base, system, fieldable /
   margin)` and returns null.
4. The marines gate (`wants[0] x minMarinesFraction`, with trim), the PROVISIONS gate (pooled fuel
   and supplies through `siegeDonors`; short postpones, and posts the bounty when the payable FP
   is under the orbit need), the armaments gate.
5. The draws (zealots through `ThreatCoalition.report`), `raidsPerColony = expeditionPasses`.
6. `new ThreatPurgeFGI(params, playerCommissioned)`, then `setCargoAllotment`, `setProvisions`,
   `setOrdnance`, `setLedger` (NPC), `setRazeWorlds`; `addIntel`; `getPurgeList().add`.
7. Clears `TARGETS_MEMO` and `RAZE_MEMO`, `ThreatCoalition.post(faction, system)`, stamps
   `lastPurgeTimes` for every target.

Every postponement returns null WITHOUT stamping a cooldown.

### Injecting a planned siege

- Calling `launchSiegeExpedition` directly keeps every gate inside it (orbit, marines,
  provisions, armaments, their bounties), the draws, the intel, the coalition call and the
  cooldown stamps. It skips the pass's pre-gates (sectorKnows, `reliefOwed`, `siegeAllowed`,
  `anySiegeReady`, the `siegeTargets` filters), the sizing (`siegeDifficulty`, `siegeSizesFor`)
  and the notice and log. Extracting pass steps 5-6 into one helper serves both.
- `ThreatSwarmBountyIntel.post(base, system, siegeFP)` is one per system and re-derives its
  targets live through `siegeTargets`; `ThreatSoftening.tick` hunts those (`gateWorlds`). A
  planner that launches other worlds must keep `siegeTargets` and the bounty in step.

### The extra pass after a hunt - `huntThinned`

`huntThinned(systemId)` puts the system in `THINNED` and sets `siegePassPending`; the poll then
reruns the whole pass ("Siege pass after a hunt thinned ..."). Only the thinned system's
cooldown is cut (`recentlyThinned`, within `purgeFollowUpDays`). Callers: a hunting force moving
on, `standDownAll` when engaged, `advanceSingle`, and `ThreatSwarmBountyIntel` (any battle that
sinks Threat ships in a bountied system). `ThreatPosture` also reads `recentlyThinned`.

## Stance - `ThreatFactionStance`

- EXPAND 0, PRESS 1, CONSOLIDATE 2; `EVAL_DAYS` 7. Saved in ThreatIncData maps
  `threatinc_factionStance` ({stance, dayEntered}) and `threatinc_factionTrend`; `TARGET`
  (faction -> hive system) and `READ_AT` are not saved (`forget()` on load).
- Queries: `stance(factionId)` (EXPAND when off, unread or the player), `stanceName`,
  `target(factionId)` (only while PRESS).
- `siegeAllowed(faction, system)`: true unless CONSOLIDATE; then only the system whose
  `ThreatReach.facedFaction` is this faction. The pass's only hard stance gate.
- `pressedFirst` / `pressingFirst` only reorder. `foundsLinks` governs forward-base founding.
- `evaluate(faction, day)` from `refresh()` every 7 days per war faction: pressed (struck within
  60 d or a Threat front on its world), losing (decayed trend), ours = `forceFP`, theirs =
  `ThreatColonyManager.ownedFleetFP` over every hive system whose `facedFaction` is this faction,
  best = `weakestTarget`. CONSOLIDATE = pressed and (losing or ratio < 1); PRESS = not pressed,
  not losing, a target, ratio >= `stancePressRatio`. Dwell `stanceDwellDays` except into
  CONSOLIDATE.
- `weakestTarget(faction)` returns {systemId, name, score} over known hive systems (sectorKnows)
  and the faction's bases in `cheapestFirst` order: the first base with non-empty, ready,
  affordable `siegeTargets` wins; score = sum of target sizes x (1 - min(1, weighed / fp)) /
  trip days. System-level.

## Orders - `ThreatFleetOrders`

- `Order` fields: fleet, factionId, kind, baseMarketId, targetId (reserve key, market id or
  system id), targetName, issuedTimestamp, days, recipientFactionId, aid, arrived, credited,
  systemId and forceId (hunts). `indefinite()` is days <= 0; `task()` writes the board text.
  Kinds: guard, intercept, "escort" (= `KIND_SUPPORT`, the persisted string), defend, hunt.
  Persisted in `threatinc_fleetOrders` (`all()`); a new boolean field reads false on old saves.
- `record(fleet, faction, kind, base, targetId, targetName, days)` builds and logs an order.
- `dispatchOrbit(faction, hive, base, kind)`: null unless `orbitEnabled(kind)`; the NPC branch
  sizes `need = siegeOrbitNeeded(faction, [hive])`, `buildSortie(base, faction, need, hyperLoc,
  true, what)`, then `orbitOrder` per fleet (ORBIT_AGGRESSIVE for days or NO_TERM_DAYS, then home).
  No capacity ledger and no notice for NPCs. The 3-argument form picks `pickBase`.
- `buildSortie(base, faction, need, dest, whole, what)`: fleets of at least `guardFleetFP`, at
  most min(`softenFleetFP`, fleetCap), until combat FP reaches need. The first fleet must be
  payable in full (else `sortie_unpaid`); later ones only from the base's spendable stock; whole
  and short refunds and despawns (`sortie_short`). Passage fuel and supplies are drawn at build;
  bombardment ordnance is paid daily.
- `hasSupport` / `hasDefend` / `hasOrder(kind, faction, targetId)` key on (kind, faction,
  target) with a live fleet. `friendlyOrbit` counts Support, Guard and Defend fleets near the
  planet as cover (the convoy door). `enforceLeash` / `leash` hold Support and Defend on station.
- THE NPC SUPPORT STAND-DOWN is in `ThreatFleetOrders.poll`: `supportLost` = an NPC, non-aid
  Support whose `ThreatGroundFronts.navyHoldsOver` is false. `navyHoldsOver` is false whenever the
  faction owns no front on that world, so an NPC Support over a hive without its own front goes
  home at the next poll (half a day). Then `ThreatReturns.sendHome` and "Order stood down".

## Support on station - `ThreatGroundFronts`

- `tickSupport` (from `ThreatGroundFronts.poll`, about every half day), per Support or Defend
  order with a live fleet: indefinite orders `ThreatSwarmDefend.holdOrbit`; stamps `fightOrbit`;
  away from the planet it reports and waits; Defend-only branches; then `supportSlice`. No front
  is required.
- `supportSlice(fleet, market, factionId, days, label)`: nothing unless the fleet is alive and
  near a hostile market of another faction; nothing while `orbitContestedFor` (it fights for the
  orbit). Otherwise pays `bombardFuelPerDay(fp) x days` through `payOrdnance` (fleet fuel, then
  `ordnanceSources`: the supply line's spendable reserve, haul-adjusted), scales the days by any
  shortfall (`idleReport` "out of fuel to bombard with"), then `siegeSlice(...)` and
  `applyFleetLosses`. Works as-is over a hive with no front.
- The contest: `orbitContestedFor(owner, market)` -> `Theatre.orbitHeldAgainst`; for a hive
  `orbitHeld(owner, market, hostilePointsNear(...))` = hostile FP > 0 and >= friendly points near
  x `orbitContestFraction` (0.5). Both sums use `ORBIT_HOLD_RANGE` (1500 su) and skip traders,
  smugglers and scavengers (`pointsNear`). `orbitContested(marketId)` is only a live-garrison
  head-count, for the convoy door.
- The per-day primitives: `returnFirePerDay`, `bombardFuelPerDay`, `bombardDaysFor`,
  `suppressionRate`. The commander's stop is the planning model `bombardPlan` (a day's gain
  under 1, defence taken under fire x worth, or the fleet under the abort floor); `orbitDoneFor`
  and `orbitSpent` are its live counterparts.

### When an NPC Support sails today - `ThreatConvoys.supportFor`

Only from `planFrontRuns` on the monthly `planLogistics`: for each front the faction owns with no
pickup bound, if `canRunTo` refuses ("Orbit contested - send Support or Defend first"),
`supportFor` checks no Support or Defend already there, `need = siegeOrbitNeeded(faction,
[hive])`, `pickBase`, need within `sortieReachFP` (else "Support for X waits"), then
`dispatchSupport`. So NPC Support exists only to open the door for the faction's own ground
front; it lasts `supportDays` (60).

### What a raid order needs (the planner's)

A `raid` flag on `Order` set by a raid dispatch with an explicit need; exempt raids from
`supportLost`; a raid stand-down on the contest (`orbitContestedFor`, only once arrived, since
friendly FP is 0 before arrival) with some grace, because the hive refills its garrison list from
siblings every poll; an indefinite term (then nothing queues the return, so the poll must send
it home); a key apart from `hasSupport` so a front's Support and a raid do not block each other.
Side effects already present: `ThreatPosture.attacksBySystem` counts Support and Defend FP as an
attack on the system, `friendlyOrbit` counts the raid as cover, and the board and faction view
show `KIND_SUPPORT` rows. Ordnance comes daily from the supply line, not from the raid's hold.

## Hunts - `ThreatSoftening`

- `tick(random)` (monthly): per running bounty, per war faction not the player, not already
  hunting and not `hostileAt`: `worlds = gateWorlds(...)` (null = the whole system when
  `siegeOrbitWeighed > siegeOrbitFaced`, else the siege's targets), then `send` from
  `huntBases` (excludes resting, out-of-reach, unpayable bases and any base with
  `hasSiegeableHive`; best paid first, then nearest) until one sails.
- `send(faction, base, system, targets)`: `first = strongest(system, among)` (null: no hunt);
  `floor = musterFloorFP(first)` = margin x `garrisonNowFP(first)`; `want` = max(floor,
  `siegeOrbitFP(targets or collectSiegeTargets)` x margin); `contributors` and `huntDonors` pay
  (short of the floor logs `huntwait:` and stops); fleets from `dispatchHunt` of at most
  `softenFleetFP` until want; a `Force` (id faction:system:timestamp, `targetIds`, muster in the
  system or at `musterPoint` 3000 su out); notice "Hunting Force Musters".
- `advanceForce` (every poll, from `advanceHunts`): waits for all fleets or `softenMusterDays`
  after the first arrival; `target = strongest(...)` (null: stand down); `need =
  garrisonFP(target) x margin`, read live at the muster; waits for stragglers up to
  `softenMusterStragglerMult` x muster days; still short -> `divert`, else `standDownAll`
  ("outmatched at the muster"). Engaged: merges, retreats under `softenRetreatStrength` 0.4,
  moves on to the next strongest world (`huntThinned`), diverting again when outmatched.
- `divert(force, orders, fp, failed)`: every live hive in the sector (no `sectorKnows` gate),
  needing a standing garrison and `garrisonNowFP x margin <= fp`, not hunted by the same faction
  nor `hostileAt`, detour fuel payable from the purse; score = standing / (sunk fuel + detour).
  Retargets the force and posts "Hunting Force Turns".
- Swarm reads: `garrisonFP` -> `IncursionManager.siegeOrbitFP`; `garrisonNowFP` adds
  `ThreatColonyManager.ownedFleetFP`; `gateWorlds` reads faced and weighed. All live and remote.

## Coalition - `ThreatCoalition`

- `Call`: systemId, callerFactionId, issuedTimestamp, answered; `daysLeft()` from
  `coalitionCallDays` (60). Saved in `threatinc_coalitionCalls`; `callFor(systemId)`.
- `post(faction, system)`: one Call per system (a repeat renews it and resets `answered`). Its
  only caller is `launchSiegeExpedition`, so every launch posts or renews one.
- `tick(random)` (monthly, after the pass and the hunts): `answerCalls` drops expired calls or
  dead systems; for each war faction not the player and not answered, not hostile to the caller,
  not `hostileAt`, with a `pickBase`: already hunting there counts as answered; otherwise a
  `coalitionSupportChance` (0.5) roll, then `ThreatSoftening.send(faction, base, system,
  IncursionManager.siegeTargetsOf(caller, system))` from `huntBases`. The answer is always a
  pooled hunt against the caller's siege targets (the whole system once that siege has ended),
  never a siege, raid or Support. Then `allyAid`.

## What lives how long

- Not saved, cleared on load: `THINNED`, `siegePassPending`, the memos (targets, raze, bases,
  donors, need), `SIEGE_STRENGTH`, the stance's `TARGET` and `READ_AT`.
- Saved: orders, calls, stances and trend, `swarmsMet` sector memory, `lastPurgeTimes`,
  `getPurgeList()` and its intel.
