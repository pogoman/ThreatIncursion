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

Untested in-game as of writing: the load (45 skins, 107 variants), the swarm overlay and THREAT
engines on a converted hull, and the fleet mix. `tools/converted-hulls/preview.png` is the
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

Untested in-game beyond a clean load.
