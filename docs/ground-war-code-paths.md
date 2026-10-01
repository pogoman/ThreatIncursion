# Ground war code paths - how a siege runs, watched and off-screen

Mapped 2026-10-01 from source and vanilla `starfarer.api.zip`, by symbol (line numbers drift).
Companion to [strategy-code-paths.md](strategy-code-paths.md), which maps how a siege is chosen
and launched; this file starts where the expedition leaves its base. The rules themselves are in
[ground-war-sieges.md](ground-war-sieges.md).

Classes: `ThreatPurgeFGI` (the NPC siege expedition and its `SiegeRaidAction`), `ThreatStrikeFGI`
(the swarm's strike, `AnnihilationAction` mirrors the siege action), `ThreatGroundFronts` (slices,
landing, fronts), `ThreatAbstractBattle` (the defenders' half of an off-screen fight).

## 1. Lifecycle of an expedition

**Launch.** `IncursionManager.launchSiegeExpedition`: prep 7-14 days; the payload segment lasts
`siegeOrbitDays` x 0.9-1.1 for the whole expedition, not per world; `raidsPerColony` = fleets + 2
(`expeditionPasses`); then `new ThreatPurgeFGI`, `setLedger`, `addIntel`, `getPurgeList().add`.

**The route.** Vanilla `GenericRaidFGI.initActions` / `FleetGroupIntel.createRoute` make ONE
`RouteData` with `OptionalFleetData{strength, damage}` and segments wait -> travel -> payload
(`SiegeRaidAction`, at the system centre) -> return. While abstract there are no fleets and no
`directFleets`: `RouteManager.advanceRoutes` only adds `elapsed` and moves on at `daysMax`.
"Arrives" means the payload segment becomes current.

**Per frame.** `FleetGroupIntel`'s constructor adds itself as a sector script
(`IncursionManager.advanceModIntel` does not list FGIs). `BaseIntelPlugin.advance` calls the mod's
`ThreatPurgeFGI.advanceImpl` first, then `FleetGroupIntel.advance`'s abstract branch, which:
aborts when 1 - damage < `groupAbortsMissionFPFraction` (vanilla 0.33); on a segment change
calls the previous action's `notifySegmentFinished` (for `FGRaidAction`: `autoresolve()`); when
the route expires calls `finish(false)` (the mod's `finish` override keeps an earlier abort).

**Arrival - the one-frame resolve.** `advanceImpl` calls the static `resolveOnArrival` every frame.
It fires when the current segment is the `FGRaidAction` and `seg.elapsed >= 1`, skips
`razesAll()` expeditions, calls `action.autoresolve()`, then cuts `seg.daysMax` to elapsed + 0.1.

**Four entries into `autoresolve`** (guard every one against double charging): `resolveOnArrival`;
`notifySegmentFinished` at the payload's end; vanilla `FGRaidAction.directFleets` when not in spawn
range and elapsed > `originalDuration` (the live path once the player leaves); and vanilla's own
damage loop inside `autoresolve`.

**`SiegeRaidAction.autoresolve`** (in `ThreatPurgeFGI.java`): `breaksOffAbstract` first and return
on a call-off; else read str / def / defenders / FP before, run `super.autoresolve()`, then
`ThreatAbstractBattle.fought(..., before - fightingFP(), "siege", defenders)`.
`ThreatStrikeFGI.AnnihilationAction.autoresolve` mirrors it.

**Vanilla `FGRaidAction.autoresolve`.** Per allowed hostile market: damage = min(0.75, 0.5 x def /
str), `strMult *= 1 - damage`; skip the market when def >= str; else disrupt the station, call
`performRaid(null, target)` x `raidsPerColony`, then str = origStr x strMult. After the loop it
writes `extra.damage` and `setActionFinished(true)`.

**`SiegeRaidAction.performRaid(null, W)`.** If `razes(W)`: `razePass` -> `abstractRaze` ->
`razeAbstract`, a one-shot saturation. Otherwise, when there is no cargo or `abstractTroops` >=
`frontMinMarines`: `purge.abstractSiege(W)`, then `waitsInOrbit`, then `super.performRaid` ->
`doCustomRaidAction`: the landing gate (`landingBlocked(..., liveFleets=false)` - no orbit test
off-screen), `reinforce` or `landOrReinforce` (deploys a persistent `GroundFront`), then
`stayOnDefend(null, W)`, whose abstract branch sets `front.coverFP` from the `abstractFP` pool.

**Spawn.** `RouteManager.shouldSpawn` fires when the route's interpolated position is within
`SPAWN_DIST_LY` = 1.6 ly of the player. `FleetGroupIntel.spawnFleet` removes the route FIRST, drops
earlier actions, then calls `spawnFleets()`; the mod's `spawnFleets` runs `settleLedger`, reloads
cargo, returns surplus to the base and sets `everSpawned`. Then `setSpawnedFleets(true)` (one-way)
and `FGDurationAction.notifyFleetsSpawnedMidSegment` (durDays x= 1 - progress). Vanilla
`GenericRaidFGI.spawnFleets` skips total x damage x 0.5 points of fleets (probability = damage) and
sets `fleetDamageTaken`: only route damage reaches the spawn. On the first live `directFleets`,
`computeSubstages` resets durDays = stages x `siegeOrbitDays` + 10 and each world's stage clock.

**End.** The payload is cut short, the route runs its return segment and expires, `finish(false)`,
then `notifyEnding`: `postSiegeReport`, `settleUnplaced`, `refundOnReturn` (keep = 1 - route
damage). Aborts: `callOff`; `outOfSupplies` (from `ThreatUpkeep.chargeAbstract` and `starve`);
`IncursionManager.abortPurgesAgainst` plus `sweepOrphanedExpeditions` (poll and
`ThreatColonyManager.pollColonies`); vanilla's damage abort.

## 2. `ThreatGroundFronts.abstractSiege(market, start, troops, abortFraction, factionId, fuel)`

Inputs from the purge: start = `abstractAllotment()`, fuel = `ordnanceLeft()`, troops =
`abstractTroops`. Guarded once per world by `siegeResolved`; skipped when a front stands there.

The loop runs while fp > 0, elapsed < `siegeOrbitDays` and fp > start x abortFraction:
1. break if `readyToLand(W, troops, factionId, orbitDone(...))` (beachhead for an NPC);
2. days = min(3, `bombardDaysFor(fuel, fp)`); dry when 0;
3. break (land on what is left) if `returnFirePerDay` x days would take fp under the abort line;
4. fp -= `siegeSlice(fp, W, days, elapsed=false, reapply=false)` - the slice writes real
   disruption days, shield soak, unrest and `BESIEGED_FLAG`; then pay fuel, `syncSiegeState`,
   `market.reapplyIndustries()`.

Returns {fp, fuel, days, dry}. The landing is not inside it: it follows in the same `performRaid`
frame, and the front then ticks on `ThreatGroundFronts.poll`. All 120 days pass in one frame, so
no sibling swarm can reach the besieged world ([attack-planner.md](attack-planner.md) section 5).

## 3. A watched (spawned) siege, per cycle

- **Per fleet pass**: `SiegeRaidAction.performRaid(fleet, W)` -> `razePass` for razing worlds, else
  `siegePass`: joins Defend when nothing is left to land; logs and does nothing while
  `orbitContestedFor`; returns false when `readyToLand` (the counted pass goes to
  `doCustomRaidAction`); else slices `days = burnOrdnance(fp, siegeSliceDays(fleet))`,
  `siegeSlice(fp, orbitPoints(...), W, days, elapsed=true, reapply=true)`,
  `applyFleetLosses(fleet, loss)` (smallest hull first, banked remainder, spares flagship and last
  ship).
- **Not daily.** Vanilla `BaseAssignmentAI.checkRaid`: a 0.3-0.7 d tracker, a 0.5 d HOLD, the raid,
  then `RECENTLY_PERFORMED_RAID` for 3 d - about 3.3-4.5 d between passes per fleet.
  `siegeSliceDays` integrates the days since that fleet's last slice, clamped to [0.5, 10], first
  slice 3.
- **Every frame**: `SiegeRaidAction.directFleets` runs `breaksOff(live)` (the worst live target's
  `hostilePointsNear` against `liveFP()`, never while `holdsAFront()`) and the leash
  (`ThreatFleetOrders.siegeLeash`, `anchorWorld`).
- **Every poll** (0.4-0.6 d): `ThreatGroundFronts.poll` -> `tickFront`, `tickSupport`, `planRelief`.
- **Garrisons** are leashed within 700 su (`ThreatColonyManager.enforceGarrisonLeash`, per frame);
  reinforcements are real GO_TO_LOCATION fleets that join the roster on arrival.
- **Orbit contest** over a hive: `Theatre.HIVE.orbitHeldAgainst` = `orbitHeld(owner, W,
  hostilePointsNear)`. The contest rule itself is in [engine-code-paths.md](engine-code-paths.md)
  section 7.

## 4. Abstract strength and where losses land

- FP = sum(`params.fleetSizes`) x 25 x (1 - `route.extra.damage`) = `abstractAllotment()`
  (`ThreatGroundFronts.ABSTRACT_FP_PER_POINT` = 25). Vanilla's `extra.strength` is separate (about
  50 per size point); `extra.fp` is null; WarSim sees only strength x (1 - damage). The logged
  "Siege ledger: spawned X FP against Y held" ran 0.76-1.28x (12 samples, median about 1.0), so
  25 per point is a fair mapping.
- FGI state: `marinesAllotted`, `armamentsAllotted`, `ordnance`, `razeFuel`, `fuelDrawn`,
  `suppliesDrawn`, the ledger fields (`ledgerHome`, `ledgerPaid`, `ledgerBuilt`, `ledgerSpawned`),
  `abstractLeft`, `abstractFP` / `abstractStarted` (the orbit-cover pool), `upkeepAt` /
  `upkeepOwed`, `siegeResolved`, `siegeAnnounced`, `squadrons`. New fields must be null-safe in
  `readResolve`.
- **The battery toll never reaches route damage.** It lives in `abstractLeft` and in
  `marinesAllotted` / `armamentsAllotted` x left/start. The spawn count, `refundOnReturn`,
  `settleLedger` (held = suppliesDrawn x (1 - routeDamage)), upkeep (`fightingFP`) and the next
  world's start (`abstractAllotment()` again) all ignore it. Route damage comes only from vanilla's
  half, written after all the `performRaid` calls.

## 5. `ThreatAbstractBattle.fought`

share = min(0.75, 0.5 x attacker / defender) in vanilla strength units. `strike` hits every fleet
in the location whose faction is among the defending factions (any faction with a hostile market
in the SYSTEM - no distance test): `removeShare` takes weighted random members (civilians x 0.25,
partial-hull chance, empties destroyed, `forceSync`); station-mode, player, trade and smuggler
fleets are skipped; unspawned routes in the location get `extra.damage` compounded. Books
`ThreatPosture.addLoss`, `ThreatStance.noteTrend`, `ThreatFactionStance.noteTrend`; logs
"Off-screen fight in S (siege)". It charges the attacker nothing (that is vanilla's half). It runs
once per `autoresolve`, while vanilla's attacker hit compounds per world.

## 6. Where a daily off-screen tick hooks in

`ThreatPurgeFGI.advanceImpl` runs every frame, abstract or not, and already hosts
`resolveOnArrival`. Gate on route time: due = floor(`seg.elapsed`) - 1 - `abstractDays`, at most
about 3 catch-up ticks; no timestamps, nothing to iterate. New null-safe fields: `abstractDays`,
`abstractWorld`, `abstractWorldDays`. Add W to `siegeResolved` only when its siege ENDS
(`waitsInOrbit` and `orbitDoneHere` read it as "the siege already ran"). On first sight of an
in-flight expedition start counting from floor(`seg.elapsed`); never catch up legacy days.
The alternative (the 0.4-0.6 d poll, the `ThreatUpkeep.chargeAbstract` pattern) adds jitter, a
list walk and persisted timestamps.

`siegeSlice` must run with `elapsed=true` in a daily tick: with `elapsed=false` the disruption
clocks run down a day between slices and a rate below 1 d/day never accumulates; `madeUpDays`
restores the run-down on each slice, so the net matches the watched path.

## 7. Traps for any off-screen change

1. `orbitHeld` reads the besieger's friendly points from REAL fleets only (`friendlyPointsNear`).
   An abstract besieger has friendly = 0, so any hostile FP holds the orbit: supply its abstract FP.
2. Spawning mid-siege becomes the common case once a siege lasts more than a frame. `spawnFleet`
   removes the route first, so every day's state must already be in route damage plus FGI
   fields; vanilla skips only total x damage x 0.5 points of fleets, so spawned FP exceeds the
   abstract FP unless pruned (`pruneOne`, as `settleLedger` does for unpaid hulls). The live stage
   clock restarts at `siegeOrbitDays` and live `siegePass` starts with a 3-day slice.
3. The payload segment is about 120 d for ALL worlds; when it ends, vanilla's segment-end
   `autoresolve` re-resolves unfinished worlds the old way. Extend `seg.daysMax` or
   `setActionFinished`.
4. Vanilla's abort at 1 - damage < 0.33 can pre-empt the "land on what the orbit left" rule:
   `gunsWouldBreak` lookahead is mandatory.
5. `ThreatStrikeFGI` shares `abstractSiege`, `ThreatAbstractBattle` and `resolveOnArrival`; a
   purge-only change makes them diverge.
6. `siegeSlice` logs every call: a daily driver must use `logQuiet`.

**Unverified (2026-10-01):** off-screen fleets advance at a lower frame rate (vanilla's
`LocationAPI` docs say so); garrison positions off-screen were not measured.

## 8. The daily off-screen siege

Built 2026-10-01 with the attack planner (decision 4 in [attack-planner.md](attack-planner.md)
section 9). Build notes are in that doc's build section.
