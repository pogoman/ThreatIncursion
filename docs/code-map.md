# Source map

One line per class in `src/threatinc/`, grouped by subsystem. Use it to find *which
file owns what* before you start; use an `Explore` subagent for the granular "where
exactly / what pattern / trace this logic" — that stays accurate against live code, this
map deliberately stays at the responsibility level so it doesn't go stale.

**Keep it current:** when you add a new class or change what an existing class is *for*,
update its one line here in the same change. Editing logic *inside* a class needs no
update. If the file list below ever drifts from `src/threatinc/*.java`, that's the signal
to reconcile it.

## Source map (`src/threatinc/`)

### Mod bootstrap & config
- `ThreatIncModPlugin.java` — mod entry point; registers the incursion manager and migrates old saves on load.
- `ThreatIncConfig.java` — central settings accessor, reading LunaLib live config or bundled JSON fallback.
- `LunaConfigBridge.java` — thin isolation wrapper around LunaLib's settings API, loaded only when LunaLib is present.
- `ThreatIncData.java` — persistent-data schema and accessors for all incursion state (stages, colonies, waves, garrisons, vitality).
- `ThreatDebugWar.java` — the Instant War debug switch: once per toggle-on, founds a connected network of mature hive systems (home chain plus footholds, a quota near the core), fills their garrisons and mobilises every faction, so a fresh save can test the war balance; also the Hive Floor Size lever (`pollFloor`), which grows every hive colony below a set size through the normal growth step, once per value.

### Incursion core (spawning, colony lifecycle, siege engine)
- `IncursionManager.java` — the incursion's master clock: seeds infestations, dispatches waves, launches strikes/purges, drives all mod intel.
- `ThreatColonyManager.java` — stateless colony logic: founding, growth/decline economy, garrison maintenance, siege/bombardment rules.
- `FabricationCore.java` — hive industry supplying heavy machinery from population; the colony's growth organ.
- `SwarmNexus.java` — hive's military industry; fabricates Defense Swarms/expeditions and anchors size-based ground defense.
- `ThreatGroundDefenses.java` — machine-hive ground defense industry running on machinery/metals instead of vanilla marine logistics.
- `HiveVitalityCondition.java` — colony-screen tooltip surfacing the real hive vitality/decline numbers behind growth.

- `ThreatSwarmDefend.java` — the swarm's Defend stations: a strike fleet that landed or reinforced a Threat front stays over the world (ORBIT_AGGRESSIVE, no term), bombarding only while the front cannot hold (`ThreatGroundFronts.defendBombards`) and breaking up its own hulls for troops once orbit has nothing left to suppress (`defendFabricates`), home to its staging colony when the front is gone or the batteries have ground it below `defendMinStrength` - which a fabricating fleet is exempt from; also `holdOrbit`, which keeps any indefinite orbit station in place after a battle.
- `ThreatFabricationVisual.java` — the campaign-map animation for a fleet breaking itself up for the ground: glow trails falling INTO the planet (vanilla's own `Misc.addHitGlow`, the particles its bombardment burst uses, driven inward instead of scattered across the disc), a floating label and a danger ping, for a player in the system. Deliberately not the bombardment burst - this state is the one where the fleet has stopped bombarding.
- `ThreatPlanetaryShield.java` — replaces vanilla's Planetary Shield plugin (industries.csv): drops the x3 ground-defence bonus (`threatinc_shieldDefenseBonus`, 0 by default, and applied in proportion to condition when set) and writes the colony-screen tooltip for what the shield actually does here. Aliased to vanilla's class name in the save (`ThreatIncModPlugin.configureXStream`) so it carries no state and the mod stays removable.
- `ThreatShield.java` — the planetary shield as a military structure: it wears on the theatre's own disruption clock like any fortification, and what its condition buys is cover - `throughput` is the fraction of a bombardment that still reaches everything else on the world, `soak`/`soakTo` the full-weight share the shield takes itself. Sits in front of Useful Planetary Shield without overriding it.
- `ThreatSiegeMalus.java` — hidden structure carrying a human colony's siege state: each disrupted defence structure's multiplier restored in proportion to its condition (floored while no district is held), the districts-lost share of the ground defence, and the stability / accessibility cost of districts held; installed whenever a defence structure is suppressed, front or no front.
- `ThreatGroundWarCondition.java` — colony-screen tooltip for a world being invaded: districts held and their cost, industries seized, defences suppressed, invader strength vs the garrison, next counter-attack, and - since the swarm came off armaments (2026-09-08) - what pressing is costing them, in place of the supply line they no longer have.
- `ThreatincMarketCMD.java` — overrides vanilla bombardment/military-menu rules: the player's tactical bombardment of any world is a siege slice (hive or colony, ships lost to the batteries), hive saturation and the ground-ops submenu for Threat colonies, and the hive defender rule for that menu (which swarm fleets count, what Engage targets, when raid/bombard close).
- `ThreatincAidCMD.java` — market dialog command: the station commander's hand-over of the player's cargo against an open request (rules.csv).
- `ThreatGroundFronts.java` — persistent ground-siege mechanic: landing, pushing strata, counter-attacks; the only way a colony dies, hive or human. Owns the landing doctrine both expeditions call (`readyToLand` / `landingBlocked` / `landOrReinforce`), the orbital duel every besieger runs (`siegeSlice` / `applyFleetLosses` / `abstractSiege`, and `tickSupport` for Support sorties), the fabrication of ground troops from a Defend fleet's own hulls once bombardment has nothing left to reach (`defendFabricates` / `fabricateNeed` / `fabricateCost` / `fabricateTroops`) and the `Theatre` (hive / colony) that answers everything depending on whose ground it is - including which fortifications orbit suppresses and on what clock.
- `ThreatPurgeFGI.java` — NPC/player siege expedition fleet-group: fights for the orbit first (no pass while Defense Swarms hold it, fleets at that world hunt them - `ThreatFleetOrders.siegeLeash` bounds the hunt and recalls strays), then the orbital duel (slices while the war-strata are above the floor, spending no pass), then the landing - or a raid where it cannot land. No slice where nothing can land; the landing fleet and every empty fleet in the system leave the group for an indefinite Defend over the front (`stayOnDefend` / `joinDefend`, `detach(fleet, false)`).
- `ThreatStrikeFGI.java` — Threat's own offensive fleet-group of swarm fleets, running the besiege-land-reinforce doctrine against inhabited worlds: fleets circling a colony suppress its fortifications a slice at a time while the batteries cost them ships, then land once the defences are at the orbital floor (saturation bombardment behind an off-by-default knob). The landing fleet detaches to a `ThreatSwarmDefend` station over the world; a fleet with nothing left to land joins one rather than slicing. Fleets spawn without `ALLOW_LONG_PURSUIT` and are held to the target by `ThreatFleetOrders.siegeLeash`.
- `ThreatOutposts.java` — standalone orbital stations built over uncolonised worlds (player: any; NPC: purged; free to the winner of a ground war) to block re-seeding by the swarm; each holds a stockpile and acts as a forward base and depot (convoys land, task forces hold its orbit); the player's keeps it in a storage-only market on the station (vanilla's abandoned-station recipe); inherited, stock and all, as the station industry of a colony its faction later founds there.
- `ThreatOutpostDialog.java` — the dialog when the player docks at their own outpost: open the storage (vanilla cargo screen), decommission, leave.
- `ThreatIncCampaignPlugin.java` — transient campaign plugin registered on load; picks `ThreatOutpostDialog` for the player's outpost stations.
- `ThreatincOutpostCMD.java` — planet dialog command: "Build an outpost" over any uncolonised world in reach of a player military colony (rules.csv).

### Faction economy & war-effort reserves
- `ThreatWarState.java` — the master war-mode gate: tracks which factions are mobilised (NPCs after a Threat strike; the player only by their own Mobilise / Stand down buttons on the war board); excluded factions (`warExcludedFactions`, pirates by default) never mobilise.
- `ThreatReserves.java` — per-colony war reserve (marines/arms/fuel/supplies) that mobilised factions' operations draw from: a player colony's vanilla resource stockpile, an NPC colony's banked ledger; also `hasDepot`, the Waystation / Swarm Nexus gate a base needs; and since 2026-09-08 the garrison bookkeeping a siege spends - `armedMarines` (the call-up ramp over `marineArmingDays`), `marineXp`, and `spendDefendingMarines`.
- `ThreatMarineXP.java` — marine veterancy on vanilla's own shape: level = xp / headcount, ranks named by vanilla's `PersonnelRank` (Regular / Experienced / Veteran / Elite, for every pool including the swarm's), a hard fight teaches and a curbstomp teaches nothing, losses preserve the level and reinforcement dilutes it; holds the maths for a front's pool and a colony's, and bridges both to vanilla's `PlayerFleetPersonnelTracker` so a landing inherits the fleet's rank and writes back what it earned (`fleetLevel` / `fleetReturn` / `playerGroundSkillMult`). Hives have none of this - their strength is structural.
- `ThreatBases.java` — one handle over the two kinds of base a fleet ships from or home to: a colony (reserve with a floor) or an outpost (stockpile without one).
- `WarFootingDemand.java` — hidden industry declaring a mobilised colony's extra vanilla demand for war materiel.
- `WarFootingCondition.java` — colony-screen tooltip explaining the War Footing reserve and demand to the player.
- `ThreatConvoys.java` — physical logistics fleets shipping war materiel between a faction's colonies and its staging bases; also front runs, relief marines to an own world under Threat invasion, and outposts shipping a purged system's stock home.
- `ThreatReturns.java` — settles fleets sent home from any strategy-layer sortie, refunding cargo and drawn provisions.
- `ThreatFleetOrders.java` — Guard/Intercept/Support/Defend sorties: the player's own navy on order, an NPC navy guarding an ally, and the player's aid task forces (flagged, on the capacity ledger, earning standing on arrival); Support (Escort until 2026-09-06, persisted kind still "escort") clears a besieged world's orbit so front runs can land and suppresses its defences while on station; Defend (2026-09-07, the same code with the kind a parameter: `dispatchOrbit` / `adoptOrbit`) holds the orbit and bombards only while the faction's front cannot hold, and is what an expedition's landing fleet takes by default (`adoptLandingDefend`, no term). A player guard over an own colony is staging: it stays until recalled, its points are the host's, sorties fold it in (`fold`) and the same hulls go back on station when they are home (`restation`). Support takes over the nearest fleet already out (`nearestReassignable` / `takeOver` / `adoptSupport`). Also owns the SIEGE LEASH (2026-09-07, `siegeLeash` / `nearestWorld`), the one place that decides where an expedition fleet of either theatre may go: aggression only inside `siegeHuntRange` of a contested world, vanilla's `$doNotGetSidetracked` blinkers everywhere else, and a recall past `siegeLeashRange` or out of the system.
- `ThreatCoalition.java` — allied factions answering a siege call with intercepting task forces, and helping each other's colonies (guards, convoys) by standing; the player can be helped.
- `ThreatAidCapacity.java` — the capacity ledger: fleet points a player colony can have at sea (vanilla fleet-size stat x aidBaseFP), held by every fleet it launches until it is home or rebuilt; a colony's free points include the task forces staged there (guards over it), which a combat sortie folds in. Nothing sails over-extended.
- `ThreatAid.java` — player aid from the war board: source auto-pick, quotes and dispatch of Defend/Aid/Strike (paid by the source colony's reserve and capacity, no credits), standing on arrival, crediting deliveries to open requests.
- `ThreatAidRequests.java` — mobilised NPC colonies' needs (outmatched by a strike; exhausted or standing shortages) and the slow tick that posts requests for help.
- `ThreatRaiders.java` — hive garrison swarms detached to hunt enemy supply convoys (guerre de course).
- `ThreatAlarm.java` — escalation/grudge tracking that speeds hive fabrication and retargets strikes at aggressors.

### Ground war support / mission design
- `ThreatMissionIntel.java` — dynamic defense-board contracts scoring and offering strikes against specific hive infrastructure links.
- `ThreatAidMissionIntel.java` — a faction's request for help at a colony as a vanilla mission: Defend (a window; fails if a strike lands; needs a player asset present) or Deliver N of a commodity (running total from convoys and hand-overs).

### Custom intel UI / war board
- `ThreatWarBoard.java` — the custom-drawn "The Abyssal War" war board (large description of `ThreatIncursionIntel`): ledger (with the Activity crests), the selected system's ground-fronts table (also drawn by the faction view; row buttons Push / Dig in / Support / Pull out / Supply), colony cards. The mod's bespoke war-board UI — re-read before patching (two sessions have edited it concurrently).
- `ThreatFactionView.java` — the war board's per-faction drill-down view (colonies, reserves, fleets, order buttons) shown when a mobilised faction is selected.
- `ThreatIncursionIntel.java` — the permanent sector-wide intel entry hosting the war board and routing its button/click events.
- `ThreatColonyScreenDialog.java` — opens vanilla's own colony info screen for a hive world from the war board, no custom UI.

### Other intel screens
- `SeedingSwarmIntel.java` — transit tracker for an in-flight colonization wave, from launch to planetfall or destruction.
- `InfestedSystemIntel.java` — per-system infestation marker (hidden; a map anchor with a status panel and the debug purge button; the purge-commission UI was removed 2026-09-05).
- `ThreatResponseIntel.java` — tracks a real NPC task force dispatched to retaliate against the colony that struck it.
- `ThreatSiegeReportIntel.java` — after-action sitrep summarizing what a siege expedition did to each target colony.
- `ThreatBountyIntel.java` — retired legacy intel class kept only so pre-rework saves deserialize and self-retire.
