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

**Founding** runs every `frontlinePlanDays` for each mobilised NPC faction (pirates and the
player never found links; 2026-09-29: `frontlineMaxPerFaction` is gone - what a faction can
found and garrison is bounded by its depots), while `frontlinesEnabled` is on. The knob gates founding only: links already
standing are kept up, paid, guarded and pruned with it off. One link per pass:
- **Cost:** the outpost cost (`outpostSupplies`, `outpostFuel`) plus, since
  2026-10-01, the structures it stands up with at their vanilla build cost
  (`ThreatOutposts.linkCost`, `ThreatBuildCost.linkKit`: Population, Spaceport,
  Waystation and the orbital station, about 4,000 supplies - as the hive's
  Seeding Swarm pays its kit; they still stand at once, as the hive's do). Drawn
  once by the faction's base nearest the site that can fund it, at any range:
  its war reserve above the floor, then what the markets reaching it can spare
  (`ThreatFrontlines.canFund`/`drawFounding`, `payFromOthers`) - read from one
  base alone, 29 tries in h40a found no payer while the Persean League held ~22k
  supplies across its markets. No base can fund it, no link. In a new game (ng7a) the
  Hegemony, just mobilised and three 1,000-supply Waystations bought, held 64.5k supplies
  across 12 colonies and still could not fund a 5,500-supply link. Not measured: likely the
  floors (a quarter of six months' banking) and donor keep (half of it) holding most of a young
  depot. It is drawn before the garrison
  is weighed, so the voyage check reads what the founding leaves, and refunded
  if no garrison can be had. Upkeep: size upkeep (`ThreatColonyUpkeep`, the
  hive's table, from size 3) and its garrison's supplies (`payUpkeep`); the
  structures themselves cost nothing to keep (user, 2026-10-01: not now).
- **Stance:** a consolidating faction founds no link, a pressing one on
  `stanceSecondaryShare` of its passes (`ThreatFactionStance.foundsLinks`).
- **Target:** the nearest found live hive that none of the faction's bases or
  links is within `frontlineReachLY` of.
- **Anchor:** the faction's colony, or connected link, nearest that hive.
- **Site:** an uncolonised planet (`ThreatOutposts.eligible`) in a system
  within `frontlineLinkLY` of the anchor. It must get at least 1 LY closer to
  the hive, or lie within `frontlineReachLY` of it, and it takes the one that
  gets closest. The system must have no hive, no hostile market, no link of
  this faction, and must not be hidden or cut off from hyperspace.
- **One faction's links per system** (2026-09-27): a system with any faction's
  link takes no other. Run 15's Alpha Vigri held four; when vanilla made three
  of them hostile, their garrisons fought and four stations fell with no Threat
  there. Purged worlds and converted old outposts follow the same rule.
- **Every pair is tried.** All (unreached hive, anchor) pairs are tried
  nearest first, until one yields a site (2026-09-29: the first 24 only; a faction
  whose one open way lay further down the list never built toward it). An anchor
  boxed in beside a hive doesn't stall the faction.
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
running with no shortage of supplies, fuel, crew or food, up to vanilla's
max market size for it (`Misc.getMaxMarketSize`; 2026-09-29: the `frontlineMaxSize`
knob, 4, is gone and held every fed link there - supply is the gate). A shortage is demand over availability, as vanilla's
industries measure it - the peacetime demand (`WarFootingDemand.peacetimeDemand`):
the War Footing on a link declares one unit over the other industries', and
vanilla's max demand read every fully supplied link short. It is not the trade
stockpile, so the player buying at a link doesn't starve it. A shortage resets
the count. `frontlineStarveDays` of shortage shrinks it one size; at size 1 it
is abandoned.

**Under size upkeep** (2026-09-30, `sizeUpkeep`, the default; `feedSize`; docs/hive-garrison-and-upkeep.md
"Size upkeep") a link grows and starves on supplies instead. From size 3 it costs the hive's
curve - 100 a month at size 3, 250, 625, 1,563 at size 6 - paid daily: its own stock above its
floor and staging bank pays all it can, and its home base and the faction's markets in reach
(`payFromOthers`) top it up to the break-even half, what a hunt may take of each. A link grows on
what it holds; its faction sends what keeps it standing. What they send arrives cut by the
Threat's blockade over it (`ThreatBlockade.cutOf`: half or all), and they pay only for what
arrives. The share paid moves it as a hive's moves: a size per `frontlineGrowDays` paid in full,
held at half, a size lost per `starveDaysPerSize` (90) paid nothing, progress carried across. Sizes 1
and 2 cost nothing, so no link starves away; commodity shortages no longer shrink it
(`frontlineStarveDays` is the old rule's). Size changes log `Frontline: X grew to size N (paid P%
of its upkeep)` / `starved down to size N`.

**The builder** runs one project at a time, with vanilla build times. Each step
waits until every commodity it demands can be had, judged by vanilla's own
figures (`canSupply`). A step that can't be supplied doesn't block the steps
after it.

**Each step is paid in supplies** (2026-09-30, `structuresCostSupplies`; `payBuild`,
`ThreatBuildCost`): its vanilla build cost at the supplies base price - Patrol HQ 3,000, Heavy
Industry 5,000, Fuel Production 4,500, Military Base 4,500, station upgrades 5,000 / 10,000. The
link's own supplies above its floor and staging bank go first, then what a hunt may take of the
faction's other markets in reach (`payFromOthers`). A step it cannot pay for waits, and the link
builds nothing else meanwhile (`Frontline: X waits on N supplies for <id>`). The Threat's planner
pays the same prices from the hive's stock (docs/hive-economy.md). **The shortage's answer first** (user, 2026-10-06):
when the producer of what the faction is shortest of is wanted here and cannot be paid, the
market holds the price still wanted above its floor until the next pass
(`ThreatFactionStock.hold`, read by `ThreatReserves.floor`), so fleet upkeep, hunts and other
markets' builds no longer draw what the yard is saving for (`Frontline: X holds N supplies for
heavyindustry (hulls short)`); an ally's convoy lands where it is held (docs/player-aid.md 5).
hw33b: four Hegemony bases read "5,000 wanted, 1,008 to hand" for 1,200 days while the
navy's upkeep took the rest.

| Size | Step | Demand checked |
| --- | --- | --- |
| 3 | Patrol HQ | supplies, fuel, ships s−1 |
| 3 | orbital → battlestation | crew 5, supplies 5 |
| 3 | the producer of what the faction is shortest of (`ThreatFactionStock.shortest`): Heavy Industry for hulls or supplies, Fuel Production for fuel - in a free slot, else by conversion (`convertFor`) | as below |
| 3 | Heavy Industry, in a free industry slot | metals s, rare metals s−2 |
| 3 | Fuel Production, in a free industry slot | volatiles s, heavy machinery s−2 |
| 4 | Patrol HQ → Military Base | supplies, fuel, ships s+1 |
| 4 | battlestation → star fortress | crew 7, supplies 7 |

**Heavy Industry** (2026-09-27, user's call; knob `frontlineHeavyIndustry`): run
17's links took in 242k supplies by convoy and sent 8.5k back, and their garrisons'
upkeep stalled Hegemony's sieges for 16 months. Heavy Industry makes supplies, heavy
armaments and ships (vanilla: s−2 of each), all of which the war banks. The ships
also serve the Military Base. It takes the one industry slot at size 3; the
Military Base takes the second at size 4. No Mining: a station's market does not
hold its planet's deposits, and ore is nothing a link or the war needs.

**The faction's stock plan** (2026-10-06, user's decision: "human and threat build logic should be
identical ... build to bridge the shortage, and ... decommission industry or structure in favour of
new structure to address shortage, but this shouldnt be instant"; `ThreatFactionStock`): the hive's
`ThreatFuel` rules read per faction over its war reserves. Every reserve draw is demand on the
faction's trailing window (`noteDemand`, e-folded over a producer's 120-day build time); a stock
**runs dry** when stock < (demand − banking − producers building) × build months, and is in
**surplus** when banking covers the demand and the stock covers it over a build time. Hulls are the
third stock, read off the hull pool: **short** with nothing free to send or a debt the yards cannot
rebuild within a build time, in **surplus** with no debt and hulls free. `shortest` is hulls first
(they bound every expedition in hw31), then the drier of fuel and supplies. A link builds its
producer in a free slot, one answer a faction a month (`mayAnswer`); with no slot free it tears
down a producer of a stock in surplus whose loss still leaves the banking over the demand
(`surplusProducer`) - a fuel plant for a Heavy Industry, a Heavy Industry for a fuel plant - or,
with hulls to spare, its Patrol HQ (the hive's `retireMilitary`); one conversion a faction a
month, the new structure paid before anything comes down and built over its vanilla build time.
Log: `Frontline: X answers hulls - hegemony: fuel ... (covers N months, surplus); ... hulls F free
of S, D to rebuild at P/mo (short)` and `Frontline: X turns its fuelprod into a heavyindustry
(hulls short) - ...`. Before this, `fuelShort` (fuel against supplies, each over its staging
banks) picked fuel first and `swapFuelForHeavyIndustry` ran one way: hw31's links built fuel
first 8 / 9 / 16 times to Heavy Industry first 2 / 4 / 0 on 350k-1.1M fuel while every expedition
waited on hulls (`game-runs-2.md` 31).
**Fuel Production** (2026-09-30, user's call; knob `frontlineFuelProduction`): no link
made fuel, so a faction's fuel banking was capped by the sector's best single exporter
(`ThreatReserves.productionShare`, ~16-18k a month) while its sieges wanted 40-115k each
and were postponed 4,211 times in 71 months. Vanilla's Fuel Production makes s−2 fuel
from volatiles s and heavy machinery s−2 and needs no resource condition. A link builds
it when fuel is what its faction runs dry of (the stock plan above); each takes a slot, so a
small link holds one of the two. First test (21 months,
from the lt save): 25 links built it; fuel banking rose from 16.7k to 61.8k a month
(Hegemony) and 19.9k to 36.8k (Persean), the Hegemony's stock from 29k to 109k, and
16 sieges drew against 8 without it. A fuel link is now worth striking.

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
  economy, chatter is cleared, and the entity fades. The market stays on the
  fading entity, as it does on vanilla's pirate base. Vanilla mercs pick links as
  destinations and read `getMarket().getName()` when their fleet spawns, so
  nulling it crashed the game (2026-09-27; `repairMercRoutes` fixes older saves on load). Purpose means the faction is at war, and either the hive
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

**The Threat breaks the chain.** In `pickStrikeTarget` (`strikeValue`, then the stance multiplier,
docs/strategy-orbit-outposts.md "Strike target weight") a link's weight is
max(size, 3)² × `frontlineStrikeWeight` × (1 + links its loss would cut), in
place of size². `isStrikeableWorld` admits links below size 3. The swarm still
needs to have scouted the system (`ThreatSwarmScouts.swarmKnows`).

**Strike warning.** `ThreatStrikeFGI` is hidden until detected: no intel
entry, no updates, no board row, no help requests, no faction-view count, and its
staging world reads as quiet on the board and mission board (`isActiveStrikeSource`,
`preparingStrikeFleetCount` and `hasPreparingStrikeFrom` skip it). Each
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

**A seen strike tells its system, not its worlds (2026-10-05, the user:
"humans shouldn't know the exact world just the system"; built `eb7a1f1`,
game-tested hw10a-hw10c: no measurable change, `game-runs.md` 8).**
`ThreatFrontlines.boundFor(strike, market)` is true for every market of the
strike's system (`raidParams.where`), not only those on its list, and every
reader goes through it: `strikesOn` (the guard's weight, ETA, rear grace and
upkeep, `ThreatFleetOrders.guardNeed`), `sendRelief` (every link of the system
calls; `neighbourGuards` still nets one guard against the others'),
`ThreatAidRequests.strikesAgainst` (help requests, the allies' guard) and the
faction view's count. `IncursionManager.struckWorlds` gives `onStrikeDetected`
every human-held world of the system, the largest of a faction that fights
first: each faction there mobilises and gets the lead, and the first one's
task force sails. So a faction the hive left off its list (`warOpen`, the size
floor, the player's grace) now answers too. The strike's own intel still names
the worlds once it is over them. Knob `strikeSeenBySystem` (true; false = the
worlds on the list).

A strike can now be seen weeks after launch. So its "New" tag counts from
detection, the task force only sails if the hive still lives, and targets that
have left the economy are skipped. All of those used to happen at launch.
Strikes in flight in an older save have no hidden flag and stay visible.

**Front and rear (2026-09-27, the user's call).** Only the front stands guard.
For every found hive world that stages strikes, the faction's market nearest it is
its front toward that hive (within 0.5 LY, so a whole system counts), if the hive's
fuel reaches it - with the hive's reach its bill (2026-09-30, docs/hive-reach-and-stock.md
"Reach is the bill"), if the hive world would strike this faction first
(`ThreatReach.facedFaction`). A link in a system with any hive world or any Threat fleet but a
Scouting Swarm is at the front too (run 10's unguarded ground-victory bases died to
theirs; run 15's Vlaan-Tone base died under a dead hive's 732 FP of leftover Defense
Swarms; a scout visits every uncharted system with a strikeable world, and would flip
a rear link to front for the day). A link at the front keeps a
standing garrison, as below. Every other link is the rear: it has no standing garrison and is never given up for
lacking one. Run 14 had every faction's upkeep budget full of garrisons over rear
links, so no ground victory could raise a forward base and the swarm re-seeded
the freed worlds.

**A seen strike calls the guard** (it replaced the 60-day Relief Force). When a
strike on a link is detected, the link's guard is brought up to the seen strikes
bound for it × `frontlineGarrisonMargin`, less its station (at the front, at least
its standing need). The faction's nearest base sends the difference if the faction
can pay the voyage; the upkeep budget does not hold it back. Nothing else gates it
(2026-09-29, no arbitrary caps): the navy's spare strength (`navySpareFP`, vanilla's
faction strength / `responseStrengthDivisor`) is gone, and with it the partial guard
of what the navy could spare and its 150 FP floor. A garrison is spawned fresh and
takes no navy ships; the last test's Persean calls all read "can spare 0 of 878 FP"
and 22 links fell undefended. A voyage the depots cannot pay leaves the link to fight
with what it has, and logs why (`fl_nocall_`, `Frontline: ... no guard called`).
The guard sails only once the first strike is due within its voyage (1.5 days
per LY from the nearest colony base) plus 20 days: run 16's strikes took 159-203
days from launch to target, and guards called at detection sat on station for
months. A strike is due on arrival (`strikeEta`): spawned fleets fight there, and
a route strike resolves a day into its payload (`abstractResolveOnArrival`,
`ThreatPurgeFGI.resolveOnArrival`). Only with that knob off is a route due at the
end of its payload stage, `siegeOrbitDays` (120) after it arrives. Timed on the
payload's end with the knob on, no guard sailed in the 3.7-year test of
2026-09-29: links fell 42-69 days after launch while the ETA read 100+, and the
silent exits hid it (they log now, `fl_guard_`). Until then the daily step asks
again; a refusal waits a week. A guard on station is reinforced only once it
falls under 80% of what it must weigh (run 17: Akron took 9 top-ups of 37-524 FP
in 80 days), and then sent until it holds. It calls at the front too, on top of a standing garrison the strikes
outweigh (run 16: two strikes, 2,700, met Yami's 1,496 FP garrison with nothing
called) and after an unpaid recall (run 15, Eps Golgotha I). A strike stops counting as bound for the link once it has struck: run 15's
called guards stayed a median 155 days, the strike's whole return leg. Once no
seen strike is bound for a rear link, its guard goes home. Guards sail from a
colony, never from a link: in run 15, 15 of 19 called guards spawned at their own
link, which had become a base. A link that falls behind the front (a new link
founded beyond it) sends its standing garrison home the same way after
`frontlineRearGraceDays` (60) behind it, since a hive's fuel range drifts. Run 17
used 30 days, and 4 of 7 guards went home with a strike launched at them but
not yet seen (strikes are seen a median 16 days after launch, up to 59). A strike
due while that guard still sails home turns it back to the link rather than a new
one sailing. Next, before the navy is asked, the guards the faction has behind
the front with no strike on their own link (on station through the rear grace, or
sailing home from it) are sent, nearest first, if they make it before the strike
(`borrowRear`, 2026-09-27, untested: run 18's six calls all found the navy with 0 FP
to spare). A strike contests the whole system, and vanilla's autoresolve
(`FGRaidAction`) weighs `WarSimScript.getEnemyStrength` of the raider plus the
target's station. That is the fleets and routes of every faction that holds a market
in the system and is hostile to the Threat. So the call counts the guards of the
faction's other links in the system, and the link's patrols and any such third
party's fleets (`otherDefenders`). An ally's task force in a system where it holds no
market is not counted, because vanilla does not count it (run 18's Persean task force
over Pontus). A front link that falls sends the link
behind it a garrison at once; the new front's founding does not count the guards
it sends home against the budget. Strikes are
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
  site - billed reach, that has the site at its front by the rule above - is
  weighed by its largest live swarms above its home reserve (its live
  count, if reinforcement filled it past its own). Each swarm counts at its
  route strength (`FleetGroupIntel`, 50 per size point), which is what vanilla's
  `FGRaidAction.autoresolve` weighs a strike by far from the player, where the
  fights happen. Run 6 weighed the swarms' own fleet strength instead. That is
  about 4x the route figure (a 1,350 strike read about 5,500), so every one of
  196 foundings was refused on upkeep.
- **Sent** as fleets sized at 1.4 strength per FP (run 6 measured 1.2-1.6), then weighed for real and
  topped up until it holds (2026-09-29: it was once; two passes left a garrison short
  whatever the depots could pay), within `payableFP` - the points whose voyage the base and the
  markets reaching it can pay (pooled stock / `voyageCost`). `spawnForce` builds nothing past
  it, so a garrison is never spawned and then despawned for want of pay (2026-09-29: the whole
  force was spawned, weighed against the depots and despawned if they fell short, and
  `callGuard` asked again every week). A garrison may therefore sail below its need when the
  depots cannot pay it all, and the callers top it up later (the monthly reinforcement, the
  daily call). The log line gives the strength sent, needed and the strike in reach; a base that
  can pay under 30 FP logs `fl_unpaid_`.
- **Kept for as long as the link stands.** Each month, after the upkeep is paid,
  a front garrison under 80% of its standing need (the hives grew) is reinforced
  from its home base. A rear guard answers the seen strikes only, through the
  daily call with its netting, timing and throttle: a monthly top-up sized on
  the strikes' gross figure undid the call's netting.
- **Paid for:** a faction founds, re-sends or reinforces a front garrison only
  while its front garrisons' upkeep stays within its budget (`upkeepBudget`): the whole
  of its monthly supply banking (`ThreatReserves.accrualPer30`; 2026-09-29: it was
  `frontlineUpkeepShare`, 0.5, of it - an arbitrary half, now gone), plus its supplies
  above the floors spread over `frontlineUpkeepStockMonths` (12; 0 = banking only). The
  staging banks are what keep the sieges fed. Run 18's Hegemony was refused at 4,439 a month on a 3,375 budget while
  it held 37,000 supplies, and six links were lost that way. A stock drawn down shrinks
  the budget back to the banking. Sieges come first: only the stock beyond what the
  faction's staging bases and relays are banking (`ThreatConvoys.bankTargets`: the siege bank and
  any relay's, not a garrison voyage's want, which is this budget's) counts. Run 19 counted all of it, and its sieges' fuel-and-supply postponements rose
  from 36 to 141. The surplus is counted market by market (2026-09-29): netted
  faction-wide, one staging base's unmet target zeroed every other depot's surplus and
  pinned Hegemony at ~3,750 a month for a 3.7-year test.
  The garrisons a new front link puts behind the front are not counted, since
  they go home. A guard called by a strike is never refused for the budget, but
  its upkeep counts in it (2026-09-27, user's call): while it is out the faction
  founds and stands fewer front garrisons. Run 17's Hegemony paid ~5,065 a month
  on a 3,750 budget, and its sieges starved.
  Upkeep is drawn from the link, then the garrison's home base (while the
  faction still holds it, and only if its stock reaches the link - 2026-09-29), then the
  faction's other markets whose stock reaches the link, nearest first, except other links
  (`payFromOthers`). The link's own stock pays down to its floor plus
  its staging bank (2026-09-29: down to the floor alone, staging links fed their
  garrisons out of the siege's savings); the home base and the others give only
  what a hunt may take (`ThreatReserves.spendable`: above the floor, the donor
  keep and the staging bank), so a garrison never spends what convoys banked for
  a siege - its home is often the hive's staging base. Since 2026-10-01 a market's
  stock reaches as far as its fuel pays, and each pays out of its fuel the haul to
  the market it pays at (`gives` / `drawGiven`, for upkeep, size upkeep, foundings,
  builds and voyages alike; docs/strategy-convoys.md "Logistics reach"). A voyage is checked and
  paid from the same stock, in full or the fleets stand down. Sieges pool from
  links like any other base (`IncursionManager.siegeDonors`). Every monthly
  payment is logged with who paid it.

The rules from before:
- **No navy share (2026-09-29).** There used to be a "spare strength" rule: the
  faction's vanilla strength over its bases' systems, less every garrison out
  (`navySpareFP`). It is deleted. The garrison sails from the faction's nearest
  base, and the depots paying its voyage are the gate. Its voyage is paid from
  that base, then the faction's other markets except links - only those whose stock REACHES the
  base (2026-09-29, `othersPay` for the check, `payFromOthers` for the draw, both through
  `IncursionManager.marketsReaching` / `ThreatConvoys.stockReachLY`, the rule of a siege's and a
  hunt's donors; every market of the faction paid at any range before - stock that never sailed).
  When a voyage cannot be paid, the base notes what it lacks past what the markets reaching it give
  (`noteVoyageWant`; base memory, 30 days, `VOYAGE_WANT_DAYS`, NPC bases only), so convoys stock
  it like a staging base and the relays carry it on past its donors' reach
  (`ThreatConvoys.stagingTargets` = `bankTargets` + the want). A garrison that sails clears it
  (`clearVoyageWant`).
- **The garrison** is real task forces on DEFEND_LOCATION over the link, built
  at the size asked for (`ignoreMarketFleetSizeMult` - run 4's 400-point
  garrisons sailed at 756 on average with the base's fleet-size multiplier; since 2026-09-29
  every NPC fleet the layer builds is built this way - hunting forces, sorties, task forces,
  scouts, convoy escorts - at the points it is paid for, and whatever vanilla prunes is
  refunded, `refundShort`).
  `spawnForce` has no fleet-count cap and loops until the strength is met, each
  fleet up to `softenFleetFP` (2026-09-29: 16 fleets of 250 FP held a garrison
  to 4,000 FP whatever the depots could pay).
  Its upkeep is its ships' vanilla supplies per month, maintenance only (run 4
  billed `getTotalSuppliesPerDay`, which adds repair and CR recovery: up to
  12,901 a month, 55 garrisons recalled unpaid). Drawn monthly from the link's
  reserve, then its base's, then the faction's other bases nearest first, each
  fleet billed from the day it joined the guard (a guard called on payday is not
  billed the month before it). Paid under half, it goes home, and a strike seen
  while it sails turns it back, as a rear guard is.
- **A link given up** (starved, no garrison, no hive in reach) sends its reserve
  to the faction's nearest market that is not a link (`carryStockHome`). A
  destroyed one loses it. Run 12 lost 2,299 marines that a front had evacuated
  into a link that starved 15 days later.
- **Recalled** when the link is dismantled or changes hands, or garrisons are
  switched off. It sails home on the tracked leg (2026-09-29, closed economy: it despawned on
  arrival with nothing back) and the base re-banks its hulls at what survived
  (`recallGarrison`, `ThreatReturns.settle`); a home that fell sends it to the faction's nearest base.
  A guard that arrived home is the base's again, not one to turn back (`sailingHome`).
  Recall goes through `sendGuardHome` (its base, else the faction's nearest; despawned only with
  no base left). Guards no longer have a despawning return queued behind their order: when one
  runs out (its station gone, or the term run) the daily garrison step (`sendHomeRanOut`,
  `ThreatReturns.orderRanOut`) takes it off the guard and sends it home to settle. (Until run 5 it also went home at a star fortress, and a
  fortress under construction paused the unguarded clock; both are gone.)
- **Lost** (beaten in battle, recalled unpaid, or a link raised without one - a
  converted outpost, a ground-victory prize): a new one is sent when a base can
  pay for it (2026-09-29: no navy share), at most every 30 days. A link unguarded for `frontlineAbandonDays`
  is given up ("no garrison to hold it").
- Purged-world forward bases (`ThreatOutposts.planNPC`) need a garrison too.
- The census line reports guarded links, their garrison FP, and star fortresses.

**Held worlds (2026-09-26).** NPC factions no longer build the old
`ThreatOutposts` stations; only the player does. Where an NPC used to get one,
it founds a forward base instead (`ThreatOutposts.raiseForwardBase`):
- **Ground victory: nothing (2026-09-27, user's call, player too).** A base
  used to be raised free on the freed world. Runs 10-15's died within days to
  the system's other hive worlds or its leftover swarms (4 of 4 in run 10;
  Vlaan-Tone in run 15, 2 days, with 2,611 marines banked in it). The front's
  survivors bank into the nearest colony base's reserve, veterancy kept - never
  a link, which takes its stock with it when it falls (run 16 banked 3 of 4
  victories' survivors into links).
- **Purged worlds** (`planNPC`, `outpostChance` per slow tick): paid at the
  outpost cost from a base in reach, and only where a found live hive lies
  within `frontlineKeepLY`, or the base would stand idle and be abandoned. The
  site rules are a link's (`siteSystemOk`: no hive or hostile market in the
  system, one faction's links per system), and
  both Outposts Enabled and Frontline Outposts Enabled gate it.
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

- `frontlinesEnabled` (founding only; standing links are kept up with it off)
- `frontlinePlanDays` (15)
- `frontlineLinkLY` (12)
- `frontlineReachLY` (10)
- `frontlineKeepLY` (36)
- `frontlineGrowDays` (60)
- `frontlineStarveDays` (90)
- `frontlineAbandonDays` (60)
- `frontlineRelayAccess` (0.2)
- `frontlineStrikeWeight` (3)
- `frontlineReliefEnabled` (a seen strike calls the guard; off, none is called)
- `frontlineGarrisonEnabled`
- `frontlineGarrisonFP` (200, the minimum garrison)
- `frontlineGarrisonMargin` (1.25)
- `frontlineUpkeepStockMonths` (12)
- `frontlineHeavyIndustry` (true)
- `frontlineFuelProduction` (true)
- `frontlineRearGraceDays` (60)
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
   called ("rear; strike on its way") if the depots can pay its voyage. No landing is ever logged against a
   link ("a station, not a world"); `Station assault on` lines weigh the
   strike, and a lost station logs `dismantled ... (station destroyed by ...)`.
9. **Kill the target hive.** The links dismantle after 60 days unless another
   found hive is within 36 LY.

Load risks to watch:
- `createMarket` on a custom entity: this is the pirate-base recipe, without
  hidden, and in the shared economy.
- `startBuilding` and `startUpgrading` on NPC markets: they should complete by
  vanilla's own advance.
- Nexerelin may upsize NPC markets, including links, beyond what supply grows them
  to. Nothing stops that; links already grow to vanilla's max market size
  (`frontlineMaxSize` is gone, 2026-09-29).

## 6. Liberties taken (the user was away, review these)

- **Player outposts stay storage-only.** As real markets they would count
  toward the colony limit (−2 stability per colony over it) and trigger
  colony crises.
- **A fallen link is removed.** The swarm does not seed a hive on it, because
  `convertConquered` needs a planet.
- **Warnings are shared with the whole sector,** player included.
- **Relief only for links, not for colonies.** Relieving colonies too would
  shift the balance of every strike, and humans already win early.
  (Still true of strikes. A Threat army that has LANDED on a colony is relieved since
  2026-09-27, sized to the swarm over it: docs/ground-war-orbit-control.md "Relief".)
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
