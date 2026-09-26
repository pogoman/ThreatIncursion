# Omens - the swarm before anyone finds it

BUILT 2026-09-26, untested in-game. Class: `ThreatOmens`.

Between the start message ("anomalous fabrication signatures...") and the first hive
anyone finds, the war used to be silent. Omens fill that gap with rumour-shaped
messages that say something is out there and roughly where, never what or exactly
where.

## 1. What plays

| Kind | Trigger | Names | Clock |
| --- | --- | --- | --- |
| static (far) | player fleet within `omenStaticLY` of a live, unfound hive colony | compass bearing to it | static |
| static (near) | same, within half that range | compass bearing to it | static |
| spread | a system newly seeded (any path that writes `ThreatIncData.stages`) | nearest inhabited system | sector |
| scout | a Scouting Swarm charts an inhabited system (`ThreatSwarmScouts` onEnter) | that system | sector |
| strike | a strike launches undetected (`IncursionManager.launchStrike`) | its target system | sector |
| reach | once, the first poll at phase 2 (the swarm can strike) | nothing | none |

- The static clock is `omenStaticDays`, the sector clock `omenSectorDays`. An omen
  whose clock is not ready is dropped, not queued.
- Each kind plays its lines in order the first time (they escalate), then repeats
  at random from the latter half of its pool.
- The seeds present when omens first run (the war's opening seeds, or everything in
  an older save) are the start message's and are never heralded.
- The bearing is from the fleet's hyperspace position, 8-point compass, north up.

## 2. When it stops

For good, once any infested system is in `ThreatIncData.discoveredSystems()` - the
player flew in, or any faction's scouts found it (`KEY_DONE` latches). Also off with
`omensEnabled` false, or `hiveFogOfWar` false (every hive is known from the start).
RESET War clears the omen state with the rest of the war.

## 3. Knobs

| Key | Default | |
| --- | --- | --- |
| `threatinc_omensEnabled` | true | |
| `threatinc_omenStaticLY` | 6 | 0 turns static off |
| `threatinc_omenStaticDays` | 20 | |
| `threatinc_omenSectorDays` | 25 | |

## 4. Verify in-game

1. New game, war started: no omen in the first poll (opening seeds are silent).
2. Fly within 6 LY of the OG hive without entering: a "far" static line with a
   bearing that points at it; closer than 3 LY a "near" line. None within 20 days.
3. Log `Omen (spread)` when the first spread lands; the named system is the
   inhabited one nearest the new seed.
4. Phase 2: the one-off "ships have started going missing" line.
5. Enter a hive system (or let a scout find one): no omen afterwards, ever.
