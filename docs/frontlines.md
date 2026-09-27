# Frontlines: outpost chains and strike warning

BUILT 2026-09-26, compiled, **untested in-game**. Designed in a brainstorm the
same day; the user decided the shape and left the rest to the build
("take liberties, I will review"). The liberties are listed in section 6.

## 1. The idea

In peacetime, factions stay put. Once a faction is on war footing it pushes a
**chain of small real markets** toward every found hive its bases can't
reach. The front line on the map is where the chains end. Links grow when fed
and shrink when starved. The swarm goes for the link whose loss cuts the most
of the chain. A strike nobody has seen is hidden, so outposts are also the
early-warning pickets.

The convoys and supply lines are unchanged. They still move the war reserves
for siege and hunt staging. An outpost's own upkeep is vanilla's economy.

## 2. Research behind it

Other games (full survey in the 2026-09-26 session):

- **Supply is fun as a line you can cut, not as a ledger.** Players praise
  Company of Heroes' connected territory and HOI4's hubs when cutting them is
  the play. They hate HOI4's supply percentages that don't say why.
- **Sprawl is only bad when it has no purpose.** Stellaris 2.3 border gore and
  Distant Worlds' mining-station spam were criticised because the AI had no
  visible reason. A chain aimed at a hive has one.
- **Static AI and ping-pong AI both fail.** X4's sectors never change hands;
  Bannerlord's fiefs are retaken endlessly. Here links end when their purpose
  ends.
- **Micro that grows with success kills late games** (Terra Invicta, Aurora,
  CK3). NPC chains need no player clicks.
- **Range costs time, never permission** (GalCiv backlash). This was already a
  rule of this mod.

Vanilla numbers that do the work (verified in the API source and in
`starfarer_obf.jar`):

- **Accessibility falls with distance.** `core_base` is 0.5 − LY from the
  economy's centre of mass / 50. There is no spaceport-less shortcut: a market
  with no spaceport takes −100%.
- **Import capacity.** Shipping capacity is (access + 0.5 in-faction) / 0.1.
  What a market gets is local supply, or else the lesser of the best exporter's
  output and this capacity. Imports are non-rival: a new outpost never starves
  anyone else.
- **Stations have fixed demand.** Crew and supplies are 3 / 5 / 7 for
  orbital / battle / fortress at any size. A shortage lowers the station's
  combat readiness. So a star fortress deep in the frontier is only possible
  if something raises its access.
- **NPC markets never grow on their own.** `CoreImmigrationPluginImpl.increaseMarketSize`
  is static and works on any market. Max industries is 1 at sizes 1–3 and 2 at
  size 4. Military Base counts as an industry; Patrol HQ, stations, Waystation
  and Ground Defenses do not.
- **NPCs never trigger station upgrades themselves**, but `startUpgrading()`
  runs on any market: 120 days to a battlestation, 180 to a star fortress.

## 3. What is built

`ThreatFrontlines` (daily, on IncursionManager's poll) and `FrontlineCondition`.

**Founding** runs every `frontlinePlanDays` for each mobilised NPC faction under
`frontlineMaxPerFaction` (0 = no cap; pirates and the player never found links).
One link per pass:
- **Cost:** the outpost cost (`outpostSupplies`, `outpostFuel`) once, drawn
  from the war reserve of the faction's base nearest the site that holds it,
  at any range. No base can pay, no link. There is no upkeep: a link lives on
  vanilla imports like any market.
- **Target:** the nearest found live hive that none of the faction's bases or
  links is within `frontlineReachLY` of.
- **Anchor:** the faction's colony, or connected link, nearest that hive.
- **Site:** an uncolonised planet (`ThreatOutposts.eligible`) in a system
  within `frontlineLinkLY` of the anchor. It must get at least 1 LY closer to
  the hive, or lie within `frontlineReachLY` of it, and it takes the one that
  gets closest. The system must have no hive, no hostile market, no link of
  this faction, and must not be hidden or cut off from hyperspace.
- **Every pair is tried.** All (unreached hive, anchor) pairs are tried
  nearest first, up to 24. An anchor boxed in beside a hive doesn't stall the
  faction.
- **The market:** size 1 on a `makeshift_station` entity, vanilla's
  pirate-base entity. It is tagged `station` and `use_station_visual`, so the
  orbital station industry adopts it and gives it its look. It has
  Population, Spaceport, Waystation, the faction's orbital station line (high
  for Tri-Tachyon; low for Hegemony, the Luddic factions and pirates; mid
  otherwise), an open market and the faction's tariff. It has **no storage**,
  so dismantling takes nothing of the player's. It also gets a
  station-commander admin, `NO_DECIV_KEY`, and is an invalid mission target.
- The market shares the economy, with no own econ group, so it imports. It is
  added without orbital junk or chatter, so a dismantled link leaves nothing
  behind.
- War footing's sync gives it War Footing like any colony. The Waystation
  makes it a depot, and a Patrol HQ then makes it a **base**: it can launch
  sieges, hunts and convoy staging under the rules that already exist.

**Growth and starvation.** A link grows one size every `frontlineGrowDays` of
running with no shortage of supplies, fuel, crew or food, up to
`frontlineMaxSize`. A shortage is demand over availability, as vanilla's
industries measure it. It is not the trade stockpile, so the player buying at
a link doesn't starve it. A shortage resets the count. `frontlineStarveDays` of
shortage shrinks it one size; at size 1 it is abandoned.

**The builder** runs one project at a time, with vanilla build times. Each step
waits until every commodity it demands can be had, judged by vanilla's own
figures (`canSupply`). A step that can't be supplied doesn't block the steps
after it.

| Size | Step | Demand checked |
| --- | --- | --- |
| 3 | Patrol HQ | supplies, fuel, ships s−1 |
| 3 | orbital → battlestation | crew 5, supplies 5 |
| 4 | Patrol HQ → Military Base | supplies, fuel, ships s+1 |
| 4 | battlestation → star fortress | crew 7, supplies 7 |

No Ground Defenses or Heavy Batteries (2026-09-26): nothing lands on a
station, and vanilla's pirate base has none. Links built before keep theirs.

A test gotcha: vanilla's `PopulationAndInfrastructure.isUpgrading()` is true
whenever the market is below its max size (the growth bar), so "a project is in
progress" skips Population.

**Forward relay.** A link within `frontlineLinkLY` of a colony, or of a link
that is itself connected, gets +`frontlineRelayAccess` accessibility. Vanilla
lists it in the accessibility tooltip as "Forward relay". When a middle link
falls, everything beyond it loses the bonus on the next day's update.

**End of a link:**
- **Abandoned:** after `frontlineAbandonDays` without purpose, the link is
  dismantled the way vanilla tears a market down: people and industries are
  removed (the station industry takes its fleet), then the market leaves the
  economy, chatter is cleared, and the entity fades. Purpose means the faction is at war, and either the hive
  the link was founded toward lives, or a found hive lies within
  `frontlineKeepLY` (the link is then adopted by that hive).
- **Station destroyed (2026-09-26, as a vanilla pirate base):** a link is a
  station, so nothing lands on it (`ThreatGroundFronts.landingBlocked`), and
  when its station falls the base goes with it (dismantled, "Forward Base
  Destroyed" notice). Three ways:
  - a battle destroys the station fleet: `StationListener` on it, vanilla's
    `PirateBaseIntel` signal (`DESTROYED_BY_BATTLE`), flags the market and the
    next daily update ends the base - player battles included;
  - a strike resolved far from the player (`ThreatStrikeFGI.stationAssault`):
    vanilla has already fought it - `FGRaidAction.autoresolve` weighs the
    strike against every hostile fleet in the system (garrison and relief
    included) plus the station, skips the target if the defenders are as
    strong, else disrupts the station and calls `performRaid`. Reaching
    `stationAssault` is the station beaten. (An earlier version re-weighed it
    in other units and read the disruption vanilla had just written as "station
    down"; runs 3 and 4 logged every assault that way);
  - a live strike pass finds the station already down (not flying).
  The 2026-09-26 long test lost 9 links to Threat ground assaults before this.
- **Captured by someone else** (Nexerelin): the record is dropped and the
  condition removed; the market is theirs.

**The Threat breaks the chain.** In `pickStrikeTarget` a link's weight is
max(size, 3)² × `frontlineStrikeWeight` × (1 + links its loss would cut), in
place of size². `isStrikeableWorld` admits links below size 3. The swarm still
needs to have scouted the system (`ThreatSwarmScouts.swarmKnows`).

**Strike warning.** `ThreatStrikeFGI` is hidden until detected: no intel
entry, no updates, no board row, no help requests, no faction-view count. Each
poll, `IncursionManager.detectStrikes` spots a strike when any live fleet of
it is:
- visible to the player's fleet;
- in a system with a non-Threat market (pirates excluded unless the market is
  the player's);
- in hyperspace within `strikeDetectLY` of a military world or frontline
  outpost.

Far from the player, vanilla doesn't spawn the fleets at all and the group
flies as an abstract route. In that case the route's current location and
interpolated hyperspace position are tested against the same pickets. Its
payload starting also counts as detection. A retaliation strike is announced
to the sector, so it is seen at launch. The warning is the sector's: the
Threat is everyone's enemy. When a strike is detected:
- vanilla's "new intel" message appears;
- the struck faction mobilises (`recordStrike`);
- its scouts get a lead, and its task force sails against the hive if the
  hive is found;
- links among the targets call their guard.

A strike can now be seen weeks after launch. So its "New" tag counts from
detection, the task force only sails if the hive still lives, and targets that
have left the economy are skipped. All of those used to happen at launch.
Strikes in flight in an older save have no hidden flag and stay visible.

**Front and rear (2026-09-27, the user's call).** Only the front stands guard.
For every found hive world that stages strikes, the faction's market nearest it is
its front toward that hive (within 0.5 LY, so a whole system counts), if the hive's
fuel reaches it. A link in a system with any hive world is at the front too (run
10's unguarded ground-victory bases died to theirs). A link at the front keeps a
standing garrison, as below. Every other link is the rear: it has no standing garrison and is never given up for
lacking one. Run 14 had every faction's upkeep budget full of garrisons over rear
links, so no ground victory could raise a forward base and the swarm re-seeded
the freed worlds.

**A seen strike calls the guard** (it replaced the 60-day Relief Force). When a
strike on a link is detected, the link's guard is brought up to the seen strikes
bound for it × `frontlineGarrisonMargin`, less its station (at the front, at least
its standing need). The faction's nearest base sends the difference if the navy
can spare all of it and the faction can pay the voyage; the upkeep budget does not
hold it back. Otherwise the link fights with what it has: no piecemeal feeding.
While the strike comes, the daily step retries weekly. Once no seen strike is bound
for a rear link, its guard goes home. A link that falls behind the front (a new link
founded beyond it) sends its standing garrison home the same way after 30 days
behind it, since a hive's fuel range drifts. A strike contests the whole system and
vanilla's autoresolve weighs every fleet in it, so the guards of the faction's other
links in the system count toward a call. A front link that falls sends the link
behind it a garrison at once; the new front's founding does not count the guards
it sends home against the navy's spare strength or the budget. Strikes are
hidden until detected (below), so the guard races the strike from its detection.

**Garrisons: no paper bases (2026-09-26, user's rule).** The third long test
founded 155 links and lost 111 to strikes: a 100-FP orbital station cannot hold
against 300-675 FP strikes, and every founding's 1,500 supplies and 800 fuel went
with it. A faction now founds a link only if one of its bases can spare a
garrison big enough to hold it, and pay its voyage and upkeep.

**Sized to the strikes, kept for life (2026-09-26, after run 5).** Run 5 guarded
links until their station was a star fortress with a flat 400 FP. The strikes that
reached a link weighed ~1,350 in vanilla's raid strength against ~670 for the
garrison, and won 52% of their fights. All seven finished star fortresses fell to
a single strike the month their garrison went home. So now:
- **Need** = the strongest strike in reach × `frontlineGarrisonMargin` (1.25),
  less the link's station, at least `frontlineGarrisonFP` (200, now a minimum).
  A strike is a hive world's own Defense Swarms re-embodied, so each found hive
  world that stages strikes (`strikeMinSize`) and whose fuel range reaches the
  site is weighed by its largest live swarms above its home reserve (its live
  count, if reinforcement filled it past its own). Each swarm counts at its
  route strength (`FleetGroupIntel`, 50 per size point), which is what vanilla's
  `FGRaidAction.autoresolve` weighs a strike by far from the player, where the
  fights happen. Run 6 weighed the swarms' own fleet strength instead. That is
  about 4x the route figure (a 1,350 strike read about 5,500), so every one of
  196 foundings was refused on upkeep.
- **Sent** as fleets sized at 1.4 strength per FP (run 6 measured 1.2-1.6), then weighed for real and
  topped up once if short. The log line gives the strength sent, needed and the
  strike in reach.
- **Kept for as long as the link stands.** Each month, after the upkeep is paid,
  a garrison under 80% of its need (the hives grew) is reinforced from its home
  base.
- **Paid for:** a faction founds, re-sends or reinforces a front garrison only
  while its front garrisons' upkeep stays within `frontlineUpkeepShare` (0.5) of its monthly
  supply banking (`ThreatReserves.accrualPer30`), leaving the rest for sieges.
  The garrisons a new front link puts behind the front are not counted, since
  they go home. A guard called by a strike is not held against the budget.
  Upkeep is drawn from the link, then the garrison's home base (while the
  faction still holds it), then any of the faction's other markets nearest
  first, except other links. Sieges don't pool from links either: a link's
  stock pays its own garrison. Every monthly payment is logged with who paid it.

The rules from before:
- **Spare strength** is the navy's: vanilla's strength of the faction summed
  over its bases' systems (`WarSimScript`, each system once), in the relief
  force's fleet points, less every garrison it has out (`navySpareFP`). So a
  faction holds as many links as its navy can guard - no count cap. Run 8
  weighed only the sending base's own system, and Hegemony, the biggest navy,
  "could not spare" 1,200 FP most of the run. The garrison sails from the
  faction's nearest base. Its voyage is paid from that base, then the faction's
  other markets except links.
- **The garrison** is real task forces on DEFEND_LOCATION over the link, built
  at the size asked for (`ignoreMarketFleetSizeMult` - run 4's 400-point
  garrisons sailed at 756 on average with the base's fleet-size multiplier).
  Its upkeep is its ships' vanilla supplies per month, maintenance only (run 4
  billed `getTotalSuppliesPerDay`, which adds repair and CR recovery: up to
  12,901 a month, 55 garrisons recalled unpaid). Drawn monthly from the link's
  reserve, then its base's, then the faction's other bases nearest first.
  Paid under half, it goes home.
- **A link given up** (starved, no garrison, no hive in reach) sends its reserve
  to the faction's nearest market that is not a link (`carryStockHome`). A
  destroyed one loses it. Run 12 lost 2,299 marines that a front had evacuated
  into a link that starved 15 days later.
- **Recalled** when the link is dismantled or changes hands, or garrisons are
  switched off. (Until run 5 it also went home at a star fortress, and a
  fortress under construction paused the unguarded clock; both are gone.)
- **Lost** (beaten in battle, recalled unpaid, or a link raised without one - a
  converted outpost, a ground-victory prize): a new one is sent when a base can
  spare it, at most every 30 days. A link unguarded for `frontlineAbandonDays`
  is given up ("no garrison to hold it").
- Purged-world forward bases (`ThreatOutposts.planNPC`) need a garrison too.
- The census line reports guarded links, their garrison FP, and star fortresses.

**Held worlds (2026-09-26).** NPC factions no longer build the old
`ThreatOutposts` stations; only the player does. Where an NPC used to get one,
it founds a forward base instead (`ThreatOutposts.raiseForwardBase`):
- **Ground victory:** free, and the front's survivors bank into the new base's
  reserve. Since 2026-09-27 (after run 10) it is raised only if the winner can
  garrison it, and the garrison is sent at once. Without one, no base is raised
  and the survivors go home. Run 10's four unguarded victory bases died within
  2-51 days to the system's other hive worlds, taking the banked survivors with
  them.
- **Purged worlds** (`planNPC`, `outpostChance` per slow tick): paid at the
  outpost cost from a base in reach, and only where a found live hive lies
  within `frontlineKeepLY`, or the base would stand idle and be abandoned.
- **Old NPC outposts in a save** convert where they stand on the next poll,
  stock carried into the new market's reserve.

A forward base blocks the swarm re-seeding its world, as an outpost did
(`ThreatOutposts.holds`).

**Census.** With debug logging on, each mobilised faction logs a monthly
`Census:` line (colonies and links with total sizes, bases, faction reserve of
marines, arms, fuel and supplies), and the swarm one with its live and found
hives. A long war test reads these for runaway growth.

## 4. Knobs

All are in `settings.json` and LunaLib, under Frontline Outposts:

- `frontlinesEnabled`
- `frontlinePlanDays` (15)
- `frontlineMaxPerFaction` (0 = no cap)
- `frontlineLinkLY` (12)
- `frontlineReachLY` (10)
- `frontlineKeepLY` (36)
- `frontlineMaxSize` (4)
- `frontlineGrowDays` (60)
- `frontlineStarveDays` (90)
- `frontlineAbandonDays` (60)
- `frontlineRelayAccess` (0.2)
- `frontlineStrikeWeight` (3)
- `frontlineReliefEnabled` (a seen strike calls the guard)
- `frontlineGarrisonEnabled`
- `frontlineGarrisonFP` (200, the minimum garrison)
- `frontlineGarrisonMargin` (1.25)
- `frontlineUpkeepShare` (0.5)
- `strikeDetection`
- `strikeDetectLY` (4)

## 5. Verify in-game, in this order

Look for the "Frontline:" and "Strike ... detected by" log lines.

1. **Load an existing save.** No crash. Strikes already in flight still show.
2. **A mobilised faction with a found hive out of reach founds a link** within
   15 days: a `<planet> Forward Base` station, drawn as the faction's orbital
   station and not as an invisible dot, with a market that opens. Check the colony screen: size 1, the Frontline
   Outpost condition, and "Forward relay" in the accessibility tooltip.
3. **After an economy tick, the market imports.** Its food and supplies aren't
   all short. If they are, the faction lacks exporters or its access is too low.
4. **Growth.** Advance 60 days with no shortage and it grows to size 2; at 3
   it builds a Patrol HQ if it can supply one.
5. **The next link** is founded from the first toward the hive, and so on.
6. **A new strike does not appear in intel at launch.** It appears when its
   fleets reach a system with a colony, or come within 4 LY of a military
   world or link, or when you see them. Test with the player far away too
   (the route path), and check it shows as "New".
7. **The struck NPC faction's response task force** now sails at detection,
   not at launch.
8. **The swarm strikes a link.** The link's intel appears and its guard is
   called ("rear; strike on its way") if the navy can spare it. No landing is ever logged against a
   link ("a station, not a world"); `Station assault on` lines weigh the
   strike, and a lost station logs `dismantled ... (station destroyed by ...)`.
9. **Kill the target hive.** The links dismantle after 60 days unless another
   found hive is within 36 LY.

Load risks to watch:
- `createMarket` on a custom entity: this is the pirate-base recipe, without
  hidden, and in the shared economy.
- `startBuilding` and `startUpgrading` on NPC markets: they should complete by
  vanilla's own advance.
- Nexerelin may upsize NPC markets, including links, past `frontlineMaxSize`.
  Nothing stops that yet.

## 6. Liberties taken (the user was away, review these)

- **Player outposts stay storage-only.** As real markets they would count
  toward the colony limit (−2 stability per colony over it) and trigger
  colony crises.
- **A fallen link is removed.** The swarm does not seed a hive on it, because
  `convertConquered` needs a planet.
- **Warnings are shared with the whole sector,** player included.
- **Relief only for links, not for colonies.** Relieving colonies too would
  shift the balance of every strike, and humans already win early.
- **Recording a strike (mobilisation) moved to detection,** with the response.

## 7. Not built

- **No war board rows for links.** They show as vanilla markets only. A
  "Frontline" table in the faction view, or chain lines on a board sector map
  (`createSectorMap` + `MapParams.markers`/arrows), are the natural next steps.
- **No in-system fleet reveal near outposts.** It is doable with
  `Stats.DETECTED_BY_PLAYER_RANGE_MULT` and `setExtendedDetectedAtRange`.
  Revealing fleets in other systems is an engine limit.
- **Links are never resettled into colonies** after the war (the Nexerelin
  idea).
- **The Threat has no reaction to a strike being detected,** such as rerouting.
