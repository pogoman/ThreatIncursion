# Information travels by ship (2026-10-05)

The user's rule: "information is power and should not be instant". Nothing about the enemy is known to a side
until a ship that saw it is in one of that side's systems, where the relay shares it with the whole side at once.
A faction still knows at once when one of its own worlds is attacked, and the player's finds are shared at once.
Built 2026-10-05 in three steps, each behind a knob; not yet run in a game.

## 1. What changed

| Was instant | Now | Knob (old behaviour) |
|---|---|---|
| A detected strike gave the struck factions a scouting lead to its origin | A strike only mobilises | `strikeLeads` true |
| A scouting party's find and its picture of the hive system | Carried: filed when the party is in a system of its faction or one not hostile to it | `carriedIntel` false |
| A Scouting Swarm's chart and places | Carried: filed when it is in a system with a live hive | `carriedIntel` false |
| A Scouting or Patrol Swarm in a system gave the hive eyes there | It sees, and must bring it home | `carriedIntel` false |
| Humans saw a strike in hyperspace within `strikeDetectLY` 4 of a military world | A patrol near it sees it and brings it home | `patrolsEnabled` false |
| The hive saw an attack force in hyperspace within `swarmPicketLY` 4 of a hive (the picket) | A Patrol Swarm near it sees it and brings it home | `patrolsEnabled` false |
| Humans swept within `scoutRangeLY` 10 of a military world every 60 days | Patrols, no radius | `patrolsEnabled` false |

Still instant, by the user's scope: a colony beside a hive and an army on it reveal it (`ThreatScouts.revealNeighbours`);
a world's own system sees what enters it; a strike is seen when its payload begins.
Not yet covered: a siege's or hunt's fleets in a hive system write their faction's report as they look
(`ThreatIntel`), and a strike's fleets in a human system give the hive eyes there (`ThreatSwarmIntel.eyesIn`).

## 2. Carried sightings

`ThreatScoutRoute.Party.carried` holds what a party saw; `carry` adds, `advance` files it (`report` -> `deliver`)
the first poll the fleet is in a `friendly` system, and a party that never gets there loses it ("lost, and N
sighting(s) with it"). `HomeMark` flags a fleet that despawned at its world, so `advance` tells home from lost.

- Humans (`ThreatScouts.ROUTE`): `Find` = the system and `ThreatIntel.look`'s picture, filed by `reveal` and
  `ThreatIntel.file` (dated the day it was seen); `StrikeSeen` = a strike's key, filed by
  `IncursionManager.strikeReported` (the strike is detected if it still flies unseen).
- Swarm scouts (`ThreatSwarmScouts.ROUTE`): `Chart` = the system, the day and `ThreatSwarmIntel.looked`'s places,
  filed into `known()` and `ThreatSwarmIntel.file`.
- Swarm patrols (`ThreatSwarmPatrols.ROUTE`): `Sighting` = a human force's contact, filed by
  `ThreatSwarmIntel.note` with source `PATROL`, dated the day it is brought home.

## 3. Patrols

Both sides, once at war. The budget is fixed FP; its split into patrols follows losses (`ThreatScoutRoute.level`,
`lost`, `calm`): a patrol's size is the base size doubled for every patrol lost and not yet forgotten
(`patrolCalmDays` 120 without a loss forgets one), so many small patrols become few heavy ones where they die.

- Humans: `ThreatScouts.launchPatrols`, per mobilised faction. Budget `patrolFPPerBase` 40 a military world, base
  size `scoutFleetPoints` 20. Each patrol takes the nearest `patrolStops` 6 systems not swept within
  `scoutMemoryDays` from its base (`planRoute`, no radius; a CONSOLIDATE faction within `scoutRangeLY`). Paid as
  any scouting party.
- Swarm: `ThreatSwarmPatrols.launchAll`, per hive system that can stage a swarm (`ThreatSwarmScouts.pickStaging`),
  from phase 2 with a faction mobilised. Budget `swarmPatrolFPPerSystem` 120, base size `swarmPatrolFP` 30. Route:
  the nearest `patrolStops` systems no hive and no human holds, not patrolled within `swarmPatrolMemoryDays` 45
  (3 stops while the system is attacked). Paid from the fabrication bank, fuel and supplies as a Scouting Swarm;
  aggressive, so the game's fleet AI has it hunt scouting parties.

## 4. A patrol meets a force

`ThreatScoutRoute.near`: the same system, or within `patrolSightLY` 1 in hyperspace. Called once a day for every
force the side's own eyes do not see: `ThreatSwarmIntel.sweepContacts` -> `ThreatSwarmPatrols.meet` (sieges,
hunts, sorties), `IncursionManager.detectStrikes` -> `ThreatScouts.sight` (strikes).

A real fleet is left to the game's AI; the patrol takes the sighting and turns for home. A force flying as a
route is settled by `ThreatScoutRoute.meetAbstract` on fleet points and burn:

| Patrol | Outcome |
|---|---|
| slower and no stronger | destroyed; nothing gets home |
| stronger and at least as fast | fights a day: each side loses `BattleRules.defenderLoss` (the force as route damage) |
| otherwise | one gets away: the patrol runs home with the sighting |

A route's burn is a knob by what it carries: `patrolBurnBombers` 6 (razing fuel aboard), `patrolBurnSiege` 7
(marines aboard), `patrolBurnRaid` 8, `patrolBurnStrike` 9. The patrol's is its fleet's own burn level.

## 5. To watch in the first runs

- Whether human patrols find the hives at all, and when (before: 0-5 months after the first strike).
- Whether Patrol Swarms catch scouting parties through the fleet AI off-screen.
- How many sieges the hive sees coming (the picket saw 60-64% about five days out).
- Patrol losses and the size each side's patrols settle at.

## 6. The log (debugLogging)

One line each, grep `ti-<tag>.txt`:

- `Patrol of F from X (N FP): [route]` / `Scouting party of F from X (lead|sweep): [route]` (ThreatScouts.launch); the swarm side logs its own launches in ThreatSwarmPatrols.launch.
- `<party> at <system> (stop i of n, day D out, N FP): no hostile fleet | K hostile fleet(s), F FP {faction=count}` on arrival at every stop (ThreatScoutRoute.logArrival).
- `<party> won|lost a battle in <system> [to <fleet>], now N FP of L` for every battle (ThreatScoutRoute.BattleMark, a fleet listener added on the first poll out).
- `<party> home|lost[, and N sighting(s) with it] (out D days, i of n stops, K fight(s), W won; sailed at L FP[, back with N])` (ThreatScoutRoute.advance).
- `Patrols passed: ...; after: swarm home|lost|flies, human home|lost|flies` - the closest a Patrol Swarm and a human party came (ThreatSwarmPatrols.logApproaches; fates from ThreatScoutRoute.FATES, not saved).
- `Patrol census (swarm|humans): N out, M returning, F FP; levels <key level, ...>; this month launched A, home B, lost C, sightings filed D` every 30 days (ThreatScoutRoute.census, from ThreatSwarmPatrols.census).

First seen in a run: none yet (built 2026-10-06).
