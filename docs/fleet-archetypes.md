# Threat fleet archetypes

BUILT 2026-09-24, untested in-game. Vanilla's Threat has 6 hulls and 9 variants, and every
fleet the mod spawned was vanilla's overseer-led mix, so every swarm looked the same.

## The pieces

- `data/variants/threat/` - 12 new variants (Type 102-104, 202-203, 251-252, 303-304,
  351-352, 451). Threat weapons only; any fragment weapon comes with `fragment_swarm`;
  `fragment_coordinator` / `secondary_fabricator` only on the hulls vanilla puts them on
  (standoff, hive, fabricator). Every Threat hull has 1000 OP, so OP is never the limit.
- `data/world/factions/default_ship_roles.json` - merged over vanilla's. Adds the new
  variants to the roles and re-weights vanilla's so each hull keeps its vanilla share of a
  role. This also puts the new variants in vanilla's own abyss fleets.
- `data/config/threatinc_fleets.json` - the archetypes and which jobs use them.
- `ThreatFleetComposer` - the code; knob `threatinc_fleetArchetypes` (off = vanilla's mix).

## How a fleet is composed

1. Vanilla's `DisposableThreatFleetManager.createThreatFleet` builds the fleet as before
   (same tier table, same fabricators, same flags).
2. An archetype is picked from the job's pool (`jobs` in the json).
3. Every hull the archetype's `mix` names is stripped and its FP added to a budget; the
   budget is spent again, picking a hull with weight `mix / hullFP` (so `mix` reads as a
   share of FP) while at least half its FP is left. Fabricators and hulls the mix does not
   name are never touched.
4. Within a hull, variants are equally likely except the archetype's `favour` list, which
   gets `favourWeight` (4).

Strength stays vanilla's to within half a ship (simulated: 46 / 134 / 347 / 458 FP at
LOW / MEDIUM / HIGH / MAXIMUM, every archetype within 3 FP). Ship count does not: the
frigate-heavy Hunter pack is ~34 ships at HIGH where vanilla is ~26.

The variant list per hull is read from the Threat's ship roles at first use, so a new
variant only needs its `.variant` file and a line in `default_ship_roles.json`.

**Fabricators in budded fleets (2026-11-08).** A fabricator_unit is 600 FP (a hive 20, an overseer
25, a skirmish unit 7), and from budding (2026-10-25) to this date nothing bought one: `createSized`
started from vanilla's empty params (0 fabricators) and the mixes name none, and a strike re-embodies
the garrison swarms it musters, so strikes had none either (the user: "even near size 6 worlds
fabricators don't spawn ... in v9 i don't think i've seen a single fabricator in strike fleets").
Now `createSized` adds `fabricatorsFor(fp)` fabricators before the mix - one per
`threatinc_fabricatorPerFP` (1,200; 0 = never) of the budget, at most `threatinc_fabricatorsMax` (3) -
pays them out of the budget, and tags the fleet `SWARM_TIER_KEY` HIGH / `SWARM_FABS_KEY` n, so
`ThreatColonyManager.expeditionSizeFor` reads it as expedition size 9 and a strike re-embodies it with
a fabricator (`specFor`). A 1,200-2,399 FP Defense or Shifting Swarm carries one (the line was 1,500 for an hour; the user: "lower the line to 1200FP"); under 1,200, none -
a swarm, under the swarm doctrine in battle. The fabricator is set as the fleet's
flagship (`setFlagship`): every troop break-up (`ThreatStrikeFGI.embark`, `ThreatGroundFronts.applyFleetLosses`,
smallest first, the flagship spared) leaves it whole and never counts its 600 FP as troops; a strike
down to its fabricator holds its landing back ("nothing left to break up but the flagship").

The rule only shaped new buds, and a mid-war save's fleets kept their hulls (the user, the same
evening, a 100-ship swarm and size-6/7 garrisons: "not a single fabricator in sight"; the log since
launch: nothing budded above 1,344 FP). So `ThreatFleetComposer.anchorFleets` runs once on load
(`ThreatIncModPlugin`) and daily (`IncursionManager`, before `ThreatFleetOrders.poll`): every Threat
fleet not in battle whose points want more fabricators than it has trades its smallest plain Threat
hulls, 600 FP a fabricator, for them (`anchorFleet`) - FP-neutral, so the strata it embodies are
unchanged; converted hulls (their shapes are a world's ledger), hives and overseers are never traded,
and a fleet short of tradeable hulls waits. This also reaches fleets grown or packed from smaller
swarms. Log: "Fabricators: Defense Swarm (2,310 FP) trades 86 hulls, 602 FP, for 1 fabricator(s)".

## Archetypes and jobs

| Archetype | Mix (share of FP) | Favours |
| --- | --- | --- |
| host | overseer-led, frigate screen (about vanilla) | - |
| vanguard | assault 45, overseer 30 | 202, 200, 102, 251 |
| battery | standoff 50 | 300, 303, 304, 104, 252 |
| tide | hive 50 | 351, 352, 103, 251 |
| hunter | assault 50, skirmish 35, overseer 15 | 101, 102, 201 |
| scout | skirmish only | 104, 103 |

| Job | Where | Pool |
| --- | --- | --- |
| strike | `ThreatStrikeFGI.createFleet` | vanguard 3, battery 3, tide 2, host 2 |
| garrison | `ThreatColonyManager.fabricateGarrisonSwarm` | host 3, battery 3, tide 2, hunter 2 |
| seeding | `ThreatColonyManager.launchColonizationWave` | host |
| scout | `ThreatFleetComposer.createScouts`, called by `ThreatSwarmScouts.launch` | scout |

Raiders (`ThreatRaiders.detach`) take the largest hunter-pack swarm in the garrison, the
largest swarm of any kind if there is none. A seeding swarm is a host. It no longer digs in as
the new colony's first garrison (2026-09-29: founding is paid): it is consumed into the colony
when it founds it, and the hive builds its garrison from its own bank. Only a wave already in
flight in an older save, or out of the Abyss, still digs in as one.

## To verify in-game

- starsector.log has no variant or role errors at load; `[ThreatInc] Composed ...` lines
  appear with debug logging on.
- Garrisons over one hive differ visibly; a strike's fleets are not all the same mix.
- The mod's `default_ship_roles.json` entries replace vanilla's weights rather than being
  ignored (if ignored, the new variants still spawn - vanilla's weights are just slightly
  higher than intended).
- The new variants fight sensibly under the AI (Type 202 and 304 carry Defabrication Swarm
  off the fabricator; Type 252 has no fragments at all).

## Converted hulls (2026-11-07)

The Threat in the sector sails reclaimed vanilla warships beside its own units - "converted
ships of the fleets it destroyed" (the user's ask, 2026-11-07). 45 vanilla combat hulls (every
frigate to capital that is not phase, civilian-grade, a module ship, a station or unique; carriers
included) each get a Threat skin. Nothing is hand-made: `tools/converted-hulls/gen.py` (Python 3,
Pillow, numpy) regenerates it all from vanilla's files, so change the script, never the output.

- **Sprite** `graphics/threatinc/ships/<hull>_conv.png`: the vanilla sprite's luminance histogram
  matched to vanilla's own Threat sprites (`threat_reference`), coloured from the Threat's
  luminance-to-colour ramp sampled from them, faction paint turned amber, a low-frequency mottle,
  and a few mirrored amber glow nodes for the hive's hatches. Only brightness is remapped, so panel
  lines and shading survive. Built-in decorative weapons (Onslaught TPCs, Legion covers) keep their
  vanilla sprites.
- **Skin** `data/hulls/skins/threatinc_<hull>_conv.skin`: hull id `threatinc_<hull>_conv`, name
  "<Hull> (Converted)", tech Threat, tags `threat`, `threat_combat` (vanilla's
  `ThreatCombatStrategyAI.isCombatUnit`, so the Threat's battle coordination counts them),
  `threatinc_converted`; built-ins `automated`, `threat_hullmod`, `fragment_swarm` (vanilla's
  `FragmentSwarmHullmod` draws its swarm on any non-Threat-tagged hull - the branch exists for the
  player's own ships); every engine slot restyled `THREAT` (`engineSlotChanges`); hint UNBOARDABLE.
  Fleet points, OP, slots and the base description are the vanilla hull's.
- **Variants** `data/variants/threatinc_converted/`: vanilla's goal variants of each hull (not the
  Starting/Tutorial ones) cloned onto the skin - 107 of them, vanilla loadouts, the reclaimed
  armament. The Threat faction only "knows" Threat weapons, so they are never autofitted: the
  composer adds variants by id.
- **Roles** `threatincConvertedFrigate/Destroyer/Cruiser/Capital` in `default_ship_roles.json`
  (between the generator's marker comments): roles of their own, so vanilla's abyss fleets and
  `DisposableThreatFleetManager.addShips` never draw them.
- **Composer**: `ThreatFleetComposer.mixKey` keys a converted hull by its class group
  (`converted_frigate` ... `converted_capital`) instead of its base hull; a mix's group share buys
  any hull of the class, priced at the variant's own FP (`fill` now charges the variant picked, and
  a group's price for the pick weight is its mean FP). `threatVariants` reads the converted roles
  only while `threatinc_convertedHulls` is on (settings.json, LunaLib "Converted Hulls"; read once
  a launch with the archetypes).
- **Mixes** (`threatinc_fleets.json`): host 15 of 100 converted, vanguard 10, battery 10 (cruisers
  and capitals), hunter 10 frigates, tide and scout none, and a new **reclaimed** archetype (overseers
  herding converted hulls of every class, 65 of 100) at weight 2 in the strike and garrison pools.

Seen in-game 2026-11-08 (the user, StarLord save, shipped as 0.9.1): the load (45 skins, 107
variants), the swarm overlay and THREAT engines on a converted hull, and the fleet mix
(`forum/converted-1..4.png`). `tools/converted-hulls/preview.png` is the
recolour beside vanilla's Lasher, Hammerhead, Eagle, Onslaught and two Threat units.

### The reclaimed-hull ledger (2026-11-07, the user's decisions the same evening)

The converted share is no longer cosmetic: a world buds converted hulls only of the shapes it has
reclaimed. `ThreatReclaimed`:

- **The ledger** is a count per vanilla base hull in each hive world's market memory
  (`$threatinc_reclaimed`). It is shape, never mass.
- **Claims, both channels** (the user: off-screen fights carry most of the human losses - 82-452 a
  run in hw200a-c / hw202b-c, 8-24k human FP a run against 150-230 logged real scout/patrol battles):
  - a battle the Threat wins (`ThreatReclaimed.Listener`, transient): every hull vanilla's snapshot
    says the losers lost (`Misc.getSnapshotMembersLost`), the player's included, named, booked to the
    winning swarm's own world (`GARRISON_FLAG`) or the nearest live hive;
  - the mod's off-screen fights (`ThreatAbstractBattle`): the ships `removeShare` strikes from real
    fleets are named claims (a strike's defenders, a hive's besieger's hunts); a route not yet spawned
    and a human expedition's own autoresolve damage are fleet points only, claimed by class
    (`claimFP`: shapes drawn from the losing faction's known hulls with converted skins, the cheaper
    the likelier, until the points are spent). A swarm attacking books at its nearest hive; a hive
    besieged books at itself.
- **Mass**: every claim adds strata at the world, `reclaimStrataShare` (0.5, the user: a wreck is half
  a ship) of the hull's FP through `ThreatColonyManager.absorb`, so a capped world spills to the
  nearest. The swarm's own dead (Threat and converted hulls) are never reclaimed again.
- **Spending**: `ThreatFleetComposer.fill` takes a ledger world (`create`/`createSized`/`recompose`
  overloads; `ThreatBudding.spawn`, garrisons, seeding from a source, occupation buds and strikes
  from their staging `getParams().source` pass theirs). A converted group is priced at the ledger's
  mean shape of the class and each pick is `ThreatReclaimed.draw`; with no ledger or no shape of the
  class the group is empty and its share goes to Threat hulls - so the first swarms are all Threat
  and converted hulls appear as the war's losses mount. `createScouts` and fleets built with no
  world never carry them.
- **Return**: `ThreatBudding.absorbHulls` and `ThreatOccupation.absorb` refund the converted hulls
  they absorb (`applyFleetLosses`' lost list; the whole fleet when it is absorbed whole). A converted
  hull destroyed, or broken into troops at a landing, is spent. A shape is in one place: a ledger,
  or a fleet.
- **Seen**: the debug board's hive section lists "Reclaimed hulls" (total shapes, FP, each world's
  summary). Nothing on the war board.

- **The Threat refit (2026-11-08, the user: "they are all loaded with generic vanilla weapon loadouts")**:
  `gen.py refit_threat` rewrites each cloned variant slot by slot. A mount takes the Threat weapon of the
  vanilla weapon's size and a type the mount accepts (the vanilla weapon's own type first): mass drivers
  for ballistic small/medium, the Neoferric Quadcoil for large ballistic, Voltaic Cannon / Voidblaster
  for small/medium energy, Neutron Torpedo (Kinetic Fragments for a kinetic original) and the Swarm
  Launcher for small/medium missiles. A point-defence weapon is replaced only by the Voltaic Discharge
  (small energy); ballistic PD, large energy and large missile mounts keep their vanilla weapon. Each
  swap is kept while the loadout's sustained weapon flux stays within the greater of the hull's
  dissipation and 1.15x the vanilla loadout's. Ordnance points are NOT a limit (the user, 2026-11-08
  evening: "remove the op limits for the threat ... I would rather them having lore appropriate
  weaponry"): Threat guns cost more OP than vanilla's, the variant may run over the hull's budget, and
  vents and capacitors stay as vanilla fitted them - the engine enforces OP only in the refit screen, an
  NPC variant over budget fights as written. The first cut (same day, morning) refused a swap the OP
  did not cover and let vents/caps give up half: 559 swaps, 195 refused for OP. Without the limit: 747
  swaps over 107 variants, 17 refused for flux, 100 variants over budget (at most 116 OP over), 6
  variants untouched.
- **Mid-war saves (2026-11-08, the user: "just want midwar saves to get straight into it")**:
  `ThreatReclaimed.backfillOnLoad`, from `ThreatIncModPlugin.onGameLoad`, runs once per save
  (persistent `threatinc_reclaimedBackfilled`): every live hive world of `reclaimBackfillMinSize` (5) or
  more gets `reclaimBackfillShapes` (6) shapes drawn from every converted hull, the cheaper the likelier,
  no strata. A save whose hives are all smaller gets nothing and never runs it again. A "Reclaimed
  Hulls" notice reports the worlds and shapes.
- **Testing it (debug, 2026-11-08)**: the LunaLib switch "Reclaimed Swarm" (`threatinc_debugReclaimedSwarm`,
  fires once per toggle-on, `ThreatDebugWar.pollReclaimed`) seeds every live hive world's ledger with
  `threatinc_debugReclaimedSeedFP` (3000) of shapes, one of every converted hull in turn, then buds a
  "Reclaimed Swarm" of the `reclaimed` archetype at `threatinc_debugReclaimedSwarmFP` (400) from the
  hive nearest the player (`ThreatFleetComposer.createArchetype`), tagged as that hive's garrison,
  places it 1,500 units from the player's fleet in their location and orders it to intercept them for
  60 days. A notice reports the ship count, converted count and FP. Turn the switch off afterward or
  it fires again on the next save loaded with it on. The seeded shapes stay on the ledgers, so the
  worlds' later buds carry converted hulls too.

Tested in-game 2026-11-08 04:36 (run rl1: Instant War from the Aphelion Dysnomia seed save, 307 war days, debug logging on): the ledger fills by both channels and is drawn down when a world buds. Real battles: "Epsilon Far Salish I claims 33 hulls (314 FP, 19 shapes) from Shifting Swarm's battle" (named hulls, `ThreatReclaimed.Listener`); off-screen fights: "Cilicia books 4 shapes by class from tritachyon's 44 FP lost", "Beta Kalico IV books 24 shapes by class from hegemony's 178 FP lost" (`claimFP`); draws: "First Strike buds 6 converted of 11 hulls off Epsilon Far Salish I's ledger; 45 shapes left" (`fill`), the ledger falling by one a draw (53 -> 37 over eleven strikes) and rising with each claim. 89 `Reclaimed:` lines in 307 war days, no mod exception. Those three log lines (`ThreatIncConfig.log`, debug logging only) were added for the test and stay.

## Battle doctrine by composition (2026-11-08)

Vanilla's `ThreatCombatStrategyAI` (added to every battle by `ThreatHullmod`) mutes the admiral and anchors
the fleet on its fabricators: fabricators within 1-2k of a point a third of the map back on its own edge,
hives just ahead of it, two DEFEND waypoints 2,000 short of the enemy's centre of mass, the all-in flags
(DO_NOT_BACK_OFF / DO_NOT_VENT) only when enemies close within ~2,000 of the anchor, and a one-minute
search-and-destroy for half the skirmish hulls every 60-90 s. Retreat is off (`ThreatFIDConfig`:
`aiRetreatAllowed = false`, `fightToTheLast`). The ships carry no officers, so they fly on the faction
doctrine's aggression 2, cautious.

A fleet with NO fabricator or hive deployed - every small swarm - falls into the branch that drops the
waypoints and every capture and never reaches the all-in flags: no orders at all, each ship on its own
cautious AI, unable to retreat, pinned on its own edge by the end (the user, 2026-11-08: "they seem to be
very cautious ... would expect machines to be a bit more swarmy/aggressive"; "fabricators aren't in the
smaller fleets, so the doctrine doesn't really make sense. really the doctrine should be chosen based on
the fleet composition").

`ThreatSwarmDoctrine` (an `EveryFrameCombatPlugin`, registered in settings.json `plugins`, one instance a
battle) fills that branch. Once a second on the enemy side of a battle against the Threat (the campaign
context's other fleet is the Threat faction; the simulator and missions are judged by `threat_hullmod`
alone):

- a fabricator or hive deployed: nothing - vanilla's defence stands; any ship the plugin touched
  earlier (a fabricator arriving as a reinforcement) has its default `ShipAIConfig` restored.
- none: the swarm doctrine. Every Threat combat unit (`isCombatUnit`, not an overseer, not under
  construction, not a fighter) gets a NEW default ship AI (`SettingsAPI.createDefaultShipAI` on a
  `ShipAIConfig` with `personalityOverride` = `threatinc_swarmDoctrinePersonality` - aggressive; reckless
  accepted, anything else falls back - `alwaysStrafeOffensively` on, `backingOffWhileNotVentingAllowed`
  and `turnToFaceWithUndamagedArmor` off, then `ship.setShipAI`), once, its old AI kept for restoring,
  and is ordered to search and destroy unless it already is. Setting those fields on the LIVE AI's
  config - the block Alex left commented in `ThreatCombatStrategyAI` - does nothing: the ship AI reads
  its personality when it is built. First test 2026-11-08 20:17 (jar 20:05): the orders took, the swarm
  encircled the player's fleet, but stood off at range as cautious ships do. Second test (jar 20:23, the
  AI rebuilt aggressive): still the standoff ring at 2-3k - the ship AI backing off against a heavier
  enemy, which no personality overrides. So each tick every swarm ship also carries the AI flags
  vanilla's own aggro mode sets when enemies close on a fabricator - the one recipe in its Threat
  script that makes these ships attack: `DO_NOT_BACK_OFF`, `DO_NOT_VENT` (off with
  `threatinc_swarmDoctrineNoVent` false), 2 s each, refreshed every ~1 s; and `MANEUVER_RANGE_FROM_TARGET`
  when `threatinc_swarmDoctrineRange` is above 0 (default 0: the AI's own weapon range). Third build
  (jar 20:52), to retest; if the ring persists, try Range 400 in LunaLib (point blank) before another build. Vanilla's skirmish SND still cancels and
  IGNORES_ORDERS half the skirmish hulls for a minute at a time; re-ordering them is harmless.

Overseers keep vanilla's escort logic. Retreat stays off: a swarm presses and dies rather than runs.
The player's side is never touched. Off with `threatinc_swarmDoctrine` (LunaLib, The Hive). Logs
`Swarm doctrine on/off` once a flip.

To verify in-game: a First/Second Strike without a fabricator closes and keeps pressing instead of
holding its edge; a Third Strike or hive garrison with fabricators still holds its line as before;
`starsector.log` shows one `Swarm doctrine on:` line a battle; the player's own converted hulls fight on
their officers' personalities.
