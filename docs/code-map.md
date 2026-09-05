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

### Incursion core (spawning, colony lifecycle, siege engine)
- `IncursionManager.java` — the incursion's master clock: seeds infestations, dispatches waves, launches strikes/purges, drives all mod intel.
- `ThreatColonyManager.java` — stateless colony logic: founding, growth/decline economy, garrison maintenance, siege/bombardment rules.
- `FabricationCore.java` — hive industry supplying heavy machinery from population; the colony's growth organ.
- `SwarmNexus.java` — hive's military industry; fabricates Defense Swarms/expeditions and anchors size-based ground defense.
- `ThreatGroundDefenses.java` — machine-hive ground defense industry running on machinery/metals instead of vanilla marine logistics.
- `HiveVitalityCondition.java` — colony-screen tooltip surfacing the real hive vitality/decline numbers behind growth.
- `ThreatincMarketCMD.java` — overrides vanilla bombardment/military-menu rules for Threat colonies, including the ground-ops submenu.
- `ThreatincAidCMD.java` — market dialog command: the station commander's hand-over of the player's cargo against an open request (rules.csv).
- `ThreatGroundFronts.java` — persistent ground-siege mechanic: landing, pushing strata, counter-attacks, the only way a hive dies.
- `ThreatPurgeFGI.java` — NPC/player siege expedition fleet-group implementing the tactical-bombard-then-raid-or-land doctrine.
- `ThreatStrikeFGI.java` — Threat's own offensive raid fleet-group that bombards inhabited worlds using swarm fleets.
- `ThreatOutposts.java` — standalone orbital stations built over uncolonised worlds (player: any; NPC: purged) to block re-seeding by the swarm; inherited as the station industry of a colony its faction later founds there.
- `ThreatincOutpostCMD.java` — planet dialog command: "Build an outpost" over any uncolonised world in reach of a player military colony (rules.csv).

### Faction economy & war-effort reserves
- `ThreatWarState.java` — the master war-mode gate: tracks which factions are mobilised (NPCs after a Threat strike; the player only by their own Mobilise / Stand down buttons on the war board).
- `ThreatReserves.java` — per-colony banked stockpile (marines/arms/fuel/supplies) that mobilised factions' operations draw from.
- `WarFootingDemand.java` — hidden industry declaring a mobilised colony's extra vanilla demand for war materiel.
- `WarFootingCondition.java` — colony-screen tooltip explaining the War Footing reserve and demand to the player.
- `ThreatConvoys.java` — physical logistics fleets shipping war materiel between a faction's colonies and its staging bases.
- `ThreatReturns.java` — settles fleets sent home from any strategy-layer sortie, refunding cargo and drawn provisions.
- `ThreatFleetOrders.java` — Guard/Intercept sorties: the player's own navy on order, an NPC navy guarding an ally, and the player's aid task forces (flagged, on the capacity ledger, earning standing on arrival).
- `ThreatCoalition.java` — allied factions answering a siege call with intercepting task forces, and helping each other's colonies (guards, convoys) by standing; the player can be helped.
- `ThreatAidCapacity.java` — the capacity ledger: fleet points a player colony can have at sea (vanilla fleet-size stat x aidBaseFP), held by every fleet it launches until it is home or rebuilt.
- `ThreatAid.java` — player aid from the war board: source auto-pick, quotes and dispatch of Defend/Aid/Strike (paid by the source colony's reserve and capacity, no credits), standing on arrival, crediting deliveries to open requests.
- `ThreatAidRequests.java` — mobilised NPC colonies' needs (outmatched by a strike; exhausted or standing shortages) and the slow tick that posts requests for help.
- `ThreatRaiders.java` — hive garrison swarms detached to hunt enemy supply convoys (guerre de course).
- `ThreatAlarm.java` — escalation/grudge tracking that speeds hive fabrication and retargets strikes at aggressors.

### Ground war support / mission design
- `ThreatMissionIntel.java` — dynamic defense-board contracts scoring and offering strikes against specific hive infrastructure links.
- `ThreatAidMissionIntel.java` — a faction's request for help at a colony as a vanilla mission: Defend (a window; fails if a strike lands; needs a player asset present) or Deliver N of a commodity (running total from convoys and hand-overs).

### Custom intel UI / war board
- `ThreatWarBoard.java` — the custom-drawn "Threat War Effort" war board (large description of `ThreatIncursionIntel`): ledger, colony cards, operations table. The mod's bespoke war-board UI — re-read before patching (two sessions have edited it concurrently).
- `ThreatFactionView.java` — the war board's per-faction drill-down view (colonies, reserves, fleets, order buttons) shown when a mobilised faction is selected.
- `ThreatIncursionIntel.java` — the permanent sector-wide intel entry hosting the war board and routing its button/click events.
- `ThreatColonyScreenDialog.java` — opens vanilla's own colony info screen for a hive world from the war board, no custom UI.

### Other intel screens
- `SeedingSwarmIntel.java` — transit tracker for an in-flight colonization wave, from launch to planetfall or destruction.
- `InfestedSystemIntel.java` — per-system infestation marker/detail entry (now hidden, superseded by the war board) plus the purge-commission UI.
- `ThreatResponseIntel.java` — tracks a real NPC task force dispatched to retaliate against the colony that struck it.
- `ThreatSiegeReportIntel.java` — after-action sitrep summarizing what a siege expedition did to each target colony.
- `ThreatBountyIntel.java` — retired legacy intel class kept only so pre-rework saves deserialize and self-retire.
