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
largest swarm of any kind if there is none. A seeding swarm that digs in as a colony's
first garrison stays a host.

## To verify in-game

- starsector.log has no variant or role errors at load; `[ThreatInc] Composed ...` lines
  appear with debug logging on.
- Garrisons over one hive differ visibly; a strike's fleets are not all the same mix.
- The mod's `default_ship_roles.json` entries replace vanilla's weights rather than being
  ignored (if ignored, the new variants still spawn - vanilla's weights are just slightly
  higher than intended).
- The new variants fight sensibly under the AI (Type 202 and 304 carry Defabrication Swarm
  off the fabricator; Type 252 has no fragments at all).
