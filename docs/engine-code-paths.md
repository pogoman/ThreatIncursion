# Engine code paths - the mod's plumbing

Mapped 2026-10-01 (source, plus `javap` on `starfarer.api.jar` and `json.jar`) while building the
fog of war. By symbol: line numbers drift. Read this before adding saved state, a clock, a knob,
a reader of the swarm or a scout order.

## 1. Saved state

- **Keys.** Sector keys are `public static final String KEY_* = "threatinc_<camelCase>"`. Core keys
  live in `ThreatIncData`; other classes own theirs (`ThreatSwarmScouts`, `ThreatPosture`,
  `ThreatStance`, `ThreatFactionStance`, `ThreatReach`, `ThreatWarState`, `ThreatScouts`,
  `ThreatFrontlines`). Entity, fleet and market memory keys are `"$threatinc_*"`. A new class owns
  its key.
- **Accessors.** `ThreatIncData.map(key)` / `list(key)`: package-private get-or-create in
  `getPersistentData()` (it replaces a value of the wrong type). Per-faction precedent:
  `ThreatFactionStance` reads `map(KEY_STATE).get(factionId)` as a `float[]`.
- **Value types.** (a) Primitive maps (`Map<String, float[]>`, `ThreatPosture`'s "state is
  primitive maps only") - XStream-proof. (b) Public-field records (`ThreatScouts.Lead`,
  `ThreatWarState.FactionWar`, `ThreatScoutRoute.Party`): saved by class and field name, never
  rename; a retired field needs `x.omitField(...)` in `ThreatIncModPlugin.configureXStream`. Never
  store fleets or entities in a record; store numbers and ids.
- **Migration.** Additive state needs none: an absent key makes `map()` create an empty map. If one
  is ever needed: `ThreatIncData.CURRENT_DATA_VERSION`, `ThreatColonyManager.migrateLegacyData`. One-shot
  backfill idiom: `ThreatWarState.backfill()` with a boolean key. Retire a key with
  `getPersistentData().remove(...)` in `ThreatIncModPlugin.onGameLoad`.
- **Lifecycle hooks.** A system leaving the war: `ThreatIncData.clearSystem(systemId)` (it also drops
  the system from `discoveredSystems()`). A new campaign: `ThreatColonyManager.resetIncursion()`
  (siblings `ThreatScouts.reset()`, `ThreatSwarmScouts.reset()`, `ThreatOmens.reset()`).
- **Statics.** `ThreatIncModPlugin.onGameLoad` clears every static that holds sector state
  (`ThreatPosture.forget`, `ThreatStance.forget`, `ThreatReach.forget`, `ThreatFactionStance.forget`,
  `ThreatFuel.forget`, `IncursionManager.forgetThinned`, `ThreatFrontlines.forgetCaches`,
  `ThreatReserves.forgetCaches`). A per-day cache keys on the sector too: `ThreatReach.suppliesPerFP`
  checks `perFPSector == Global.getSector() && perFPDay == day`, so a same-day reload cannot read
  the old timeline's cache.

## 2. Clocks

- `IncursionManager` is a transient script (`addTransientScript`): every field resets on load.
- **Per frame**, before the throttle: `advanceModIntel`; the leashes
  (`ThreatColonyManager.enforceGarrisonLeash`, `ThreatFleetOrders.enforceLeash`,
  `ThreatSwarmDefend.enforceLeash`); the atrocity snapshot. Nothing else belongs there.
- **The poll**: `IntervalUtil(0.4f, 0.6f)` days, about two per day; then `ThreatIncConfig.enabled()`
  and `ThreatIncData.isStarted()`. Order: `ThreatWarState.poll` -> `ThreatReserves.poll` ->
  `ThreatConvoys.poll` -> `ThreatRaiders.poll` -> `ThreatScouts.poll` -> `ThreatSwarmScouts.poll` ->
  `ThreatOmens.poll` -> `ThreatFleetOrders.poll` -> `ThreatSoftening.advanceHunts` -> the siege
  pass (if `siegePassPending`) -> `ThreatReturns.poll` -> `ThreatUpkeep.poll` ->
  `ThreatAidCapacity.poll` -> `ThreatOutposts.poll` -> `ThreatFrontlines.poll` -> `detectStrikes` ->
  `ThreatPosture.poll` -> `maintainGarrisons`. A daily reader of the swarm goes just before
  `ThreatFleetOrders.poll`: after the observers and scouts update, before every planner.
- **5 days**: `ThreatPosture.poll` gates itself on a transient `lastPoll` (`postureDays`) and runs
  on the first poll after every load.
- **30 days**: `daysSinceTick` starts at 999, so `tick()` runs on the first poll after every load
  (`tickDays`; `debugFastClock` divides by 10). `tick()` runs advanceStages, trySpread, tryStrikes,
  tryPurgeBombardments, `ThreatSoftening.tick`, `ThreatCoalition.tick`.
- **Once a day**, two idioms: a persisted `KEY_LAST_UPDATE` stamp (`ThreatFrontlines.poll`; fires
  every 1.0-1.8 d because polls are 0.4-0.6 d apart; the first call only stamps), or a calendar-day
  stamp `ThreatReach.today()` (`getTimestamp() / DAY_MS`, exactly once per calendar day). A
  transient gate re-runs after every load.
- **"Today"** has three conventions: `ThreatPosture.today()` (`getElapsedDaysSince(1L)`, float),
  `ThreatReach.today()` (long day number), `ThreatFactionStance.today()` (timestamp / 86400000f).
  Never `getElapsedDaysSince(0L)` (`Float.MAX_VALUE`). Clamp ages >= 0 (`ThreatPosture.within`) for
  a save loaded over a later timeline.

## 3. Threat systems and fleets

- Faction ids: `Factions.THREAT` ("threat"), `Factions.PLAYER`, `Factions.NEUTRAL`.
- Systems: `ThreatIncData.stages()` (can hold systems with no live colony), `colonyMarkets()`
  (systemId -> market ids), `colonyIdsIn` (never adds a key), `getLiveColonyMarkets(systemId)`
  (read-only), `getPrimaryColonyMarket`, `getAllLiveColonyMarkets`, `resolveColonyMarket` (exists,
  Threat-owned, not `ThreatMapFog.conditionOnly`). "Systems with a live hive": `colonyMarkets()`
  keys filtered by a non-empty `getLiveColonyMarkets` (as `ThreatPosture.poll` does);
  `ThreatScouts.hasLiveHive(systemId)`.
- A Threat market: `Factions.THREAT.equals(market.getFactionId())` (`ThreatSoftening.isHive`,
  `ThreatGroundFronts.isHiveTarget`); a mod-founded one also has `ThreatColonyManager.COLONY_FLAG`.
  Iterate `Misc.getMarketsInLocation(system)` and test `ThreatMapFog.hidden` / `conditionOnly`,
  never `market.isHidden()` (the veil hides Threat markets while a core tab is open).
- `ThreatIncData.garrisonsFor(marketId)` MUTATES (puts an empty list when absent); a pure read is
  `garrisons().get(id)`. It holds only Defense Swarms on station: raiders out, strike musters and
  inbound reinforcements are elsewhere (`ThreatColonyManager.ownedFleetFP` adds them back).
- No single "Threat combat fleet" predicate. Discriminators: `ThreatColonyManager.WAVE_FLAG` (a
  seeding swarm), `ThreatSwarmScouts.SCOUT_FLAG` (a swarm scout; human scouts carry
  `ThreatScouts.SCOUT_FLAG`), `ThreatRaiders.isRaider`, `ThreatColonyManager.LEDGER_HOME_KEY` /
  `ledgerBound`, `ThreatSwarmDefend.all()`, `fleet.isStationMode()`. The Threat has no convoys:
  `ThreatConvoys.isConvoy` is the human factions' (trade-flagged, so `Misc.isTrader` skips it).
- FP measures differ: `getFleetPoints()` (all hulls: `swarmOrbitStrength`, `pointsNear`,
  `siegeOrbitFP`, the contest rule) vs `ThreatSoftening.combatFP` (non-civilian: `ThreatPosture`)
  vs vanilla WarSim's `getEffectiveStrength()`.
- Threat FP over one hive: `ThreatGroundFronts.swarmOrbitStrength(market)` - every alive Threat
  fleet within 1500 su, no flag filter (scouts, strikes, raiders, seeding swarms and stations
  count). Summing per world double-counts a fleet within 1500 su of two worlds: dedupe with a set
  (`ThreatPosture`'s `counted`).

## 4. Discovery

- `ThreatScouts.sectorKnows(systemId | hive)`: true when `hiveFogOfWar` is off, else
  `ThreatIncData.discoveredSystems()` holds it. One sector-wide list, not per faction.
- `ThreatScouts.reveal(systemId, finder)`: no-op if known; else marks, sends "Hive Found", logs.
  Other writers: the player entering the system (the poll), `ThreatColonyManager`,
  `IncursionManager`, `ThreatMissionIntel`, `ThreatDebugWar`, `ThreatScouts.revealNeighbours`.
- `ThreatSwarmScouts.known()` is the swarm's own charts, separate.
- `sectorKnows` does not gate `ThreatSoftening`, `ThreatCoalition`, `ThreatAid`, `ThreatPosture`
  or the stance's "theirs". A reader that observes the swarm must check it, or it discovers hives.

## 5. Observers and the coalition

- `ThreatWarState`: `wars()` (`FactionWar` records), `enabled()`, `isAtWar(id | faction)`,
  `get(id)`, `warFactionIds()` (mobilisation order; empty when disabled), `excluded(id)` (pirates by
  default), `mobilise`, `playerMayMobilise`, `mobilisePlayer`, `standDownPlayer`; `poll` can stand a
  faction down after `warModeStandDownDays`. The player has a `FactionWar` only while it chose to
  mobilise (a strike on its world does not), so a reader that means "every faction and the player"
  adds `Factions.PLAYER` itself. Loop guard idiom: `PLAYER || THREAT || excluded(fid)`;
  `ThreatScouts.mayScout` adds `isNeutralFaction()`.
- `ThreatCoalition` has no coalition id and no "partners of X". Calls: `answerCalls` (any
  mobilised non-player faction not hostile to the caller and not hostile in the system,
  `ThreatSoftening.hostileAt`), `all()`, `callFor(systemId)`, `post(faction, system)`. Aid:
  `willingness(helper, needyId)` from the helper's relationship to the needy - Cooperative 1.0,
  Friendly .75, Welcoming .5, Favourable .25, else 0; `allyAid` never makes the player a helper.
- A relationship test: `faction.getRelationshipLevel(other).isAtWorst(RepLevel.COOPERATIVE)`.

## 6. Who watches: forward bases, military worlds, player outposts

- `ThreatFrontlines.Outpost` (NPC forward links; the planner skips the player), `all()`,
  `isOutpost(market)` (`OUTPOST_FLAG`), `find`, `marketOf(o)` (null if not in the economy),
  `hiveNear(token)`. A link is a real market (id prefix `threatinc_fl_`).
- `IncursionManager.hasMilitary(market)` (Patrol HQ, Military Base or High Command), `isBase`
  (military or outpost, plus depot). `ThreatReserves.marketsOf(factionId)` excludes hidden markets.
- `ThreatFrontlines.detectedAt(location, hyperLoc)`: inside a system any `watches(m)` market there;
  in hyperspace a `watches` market that is an outpost or military within `strikeDetectLY` (4 ly).
  `watches(m)`: not hidden, has an entity, in the economy, not Threat, not `COLONY_FLAG`, not an
  excluded NPC (the player allowed). It is faction-agnostic; a per-faction reader must filter
  `m.getFactionId()` itself. Used by `IncursionManager.detectStrikes` -> `onStrikeDetected`.
- The player's `ThreatOutposts.Outpost` (`outpostsOf(factionId)`, `outpostIn`, `holds`;
  `ThreatBases.of(o)` -> `starSystem()`, `hyperLoc()`) is market-less and not in the economy:
  `watches`, `marketsOf` and `detectedAt` never see it.
- `frontlineReachLY` (10) is how far a link reaches; `frontlineLinkLY` (12) its spacing.

## 7. The orbit contest rule (`ThreatGroundFronts`)

- `orbitContestedFor(front | ownerId, market)` -> `Theatre.of(market).orbitHeldAgainst`. The hive
  theatre is `orbitHeld(owner, market, hostilePointsNear(owner, market))`; the colony theatre adds
  the colony's station (a flying, non-disrupted hostile station holds the orbit outright).
- `orbitHeld`: false when hostile FP <= 0; else hostile >= friendly x `orbitContestFraction` (0.5).
  With nothing friendly there, any defender holds.
- `pointsNear(market, factionId, friendly)` sums `getFleetPoints()` of every fleet in the world's
  location: alive, not expired, FP > 0, has a faction, not trader / smuggler / scavenger, within
  `ThreatFleetOrders.ORBIT_HOLD_RANGE` (1500 su, `nearWorld`). Friendly = same faction (the
  player's fleet counts for the player); hostile = other faction and `isHostileTo(owner)`.
  Station-mode fleets are not excluded. Only real fleets count - an abstract besieger has none.
- The swarm's mirror: `swarmOrbitStrength` (Threat only, hard-coded 1500, no trader filter),
  `swarmPresent`, `swarmOrbitContested` (any armed fleet hostile to the Threat within 1500 su, plus an
  unspawned flotilla's `front.coverFP`), `spaceHolder`.

## 8. Vanilla `RouteManager`

`javap -cp "<starsector-core>\starfarer.api.jar" -public com.fs.starfarer.api.impl.campaign.fleets.RouteManager`
(also `'...RouteManager$RouteData'`, `$RouteSegment`, `$OptionalFleetData`; `-p -c` for bytecode;
JDK 17 is at `C:\Users\zuzam\jdk\jdk-17.0.20.1+1\bin`).

- `RouteManager.getInstance()`, `getRoutesInLocation(location)`, `getRoutesForSource(source)`,
  `getRoute(source, fleet)`, `addRoute(...)`, `removeRoute(route)`.
- `RouteData`: `getFactionId()`, `getMarket()`, `getExtra()`, `getCurrent()`, `getSegments()`,
  `getActiveFleet()` (null while abstract), `getInterpolatedHyperLocation()`, `isExpired()`,
  `getSource()`, `getCustom()`. `RouteSegment`: public `from`, `to`, `elapsed`, `daysMax`, `custom`.
  `OptionalFleetData`: public `Float strength, quality, fp, damage`; `getStrengthModifiedByDamage()`.
- `getRoutesInLocation` puts each route in exactly ONE location from its current segment: the
  from / to entity's location, or hyperspace while in transit - a route mid-jump is in no system.
  The map is transient, rebuilt lazily once per frame (cheap for many systems). It returns the live
  list: copy before mutating.
- `extra.fp` is usually null; routes carry strength in vanilla units (about 50 per size point), not
  FP. Vanilla `WarSimScript.getFactionStrength(faction, system)` already sums a faction's fleets
  (no stations, traders, smugglers or player) and its unspawned routes there.

## 9. Human scouts (`ThreatScouts`, `ThreatScoutRoute`)

- `launch(factionId, home, route, leadSystemId)`: a real `PATROL_SMALL` of `scoutFleetPoints` (20),
  non-aggressive, no military response, paid in full from the home's spendable fuel and supplies
  (`voyageCost`) or null; refunded on return (`ScoutReturn`). Records a `Scout` (extends
  `ThreatScoutRoute.Party`). Walked by `GO_TO_LOCATION` + `PATROL_SYSTEM`, not by `RouteManager`.
  `launch` itself never checks `discoveredSystems()`.
- `nearestBase(factionId, location)` (military first), `mayScout(factionId)` (not player, Threat,
  excluded or neutral), `planRoute` (skips discovered and taken systems).
- `ROUTE.onEnter(scout, system, now)`: called once, on the first poll inside the target system;
  today `hasLiveHive` -> `reveal`; true sends the scout home. `onStay` marks the system swept
  after `scoutStayDays` (2). `knownStop` is consulted only for legs after the current one, so a
  single-stop route to a known system needs no gate change.
- A scout can die before its first poll inside; `ThreatScoutRoute.advance` drops it as lost.
- `ThreatScouts.poll` recalls every scout when `hiveFogOfWar` is off.

## 10. Knobs and the LunaLib migration

- `ThreatIncConfig.f()` / `i()` throw on a missing key; `b(key, def)` / `s(key, def)` take a default.
  Each tries `LunaConfigBridge` first, then `Global.getSettings()` - `settings.json` is the
  fallback even with Luna on, since Luna returns null for a key it has not stored.
- `settings.json`: flat `"threatinc_*"` keys, tabs, `#` comment lines allowed. `LunaSettings.csv`
  columns: `fieldID,fieldName,fieldType,defaultValue,fieldDescription,minValue,maxValue`; types
  Boolean / Double / Int / Header; a Boolean row has two empty trailing fields.
- **A changed default never reaches an existing LunaLib store** (`saves\common\LunaSettings\
  threatinc.json.data`). `LunaConfigBridge.migrateStoredDefaults()` (from
  `ThreatIncModPlugin.onApplicationLoad`, only when Luna is loaded) runs once per
  `MIGRATION_VERSION` (marker file `threatinc_lunaSettingsVersion`): per-version blocks
  `if (from < N) changed |= bump(json, key, oldDefault, newDefault, asInt)`, which moves a stored
  value only if it still equals the old default. Then it writes the file,
  `LunaSettings.SettingsCreator.refresh(MOD_ID)` and the marker.
- **`bump` throws on a boolean**: `json.getDouble` on a JSON boolean raises a `JSONException` that
  aborts the whole migration - nothing written, the marker not written, and it fails again every
  launch. A Boolean knob needs a boolean twin of `bump` (`json.getBoolean`, `put(key, boolean)`).
