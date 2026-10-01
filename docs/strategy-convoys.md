# Strategy layer - convoys, staging, logistics reach

Split out of `strategy-layer.md` on 2026-10-01. One-line answers: `facts.md`.

## Convoys (ThreatConvoys)

Persistent list `threatinc_convoys` of `Convoy { fleetId, factionId, fromMarketId,
toMarketId, marines, armaments, fuel, supplies, departedTimestamp }`; the cargo is ALSO
physically in `fleet.getCargo()` (marines as marines, the rest as commodities).

**Logistics AI** (slow tick, war-mode factions only): a STAGING BASE is the military
world a siege sails from - the faction's NEAREST base in expedition range of a live hive
(`ThreatConvoys.stagingHive`, the same pick as the Siege button's
`ThreatFleetOrders.pickBase`). A military world in range of hives that is never the
nearest base for any is a donor, not a staging base (changed 2026-09-05: before, every
military world in range counted, so one hive cluster made all five player colonies
"staging", all wanting 4,700 marines, none with spare, nothing concentrating). For each
staging base and commodity, target stock = what the Siege button's expedition would draw
(`IncursionManager.siegeWants`) x `stagingTargetMult` (1.5) - **only while the siege is fundable**
(2026-09-29, `ThreatConvoys.siegeStock`, `siegeFundable`: its fuel and supplies both fit within
`fundingInReach`, the stock across the `stockNetwork` - the markets whose stock reaches the base
directly or through relays - plus `stagingHorizonMonths` (6) of what each banks; marines and
armaments are not weighed, a hunt spends neither). When it is not, the target becomes the smallest
hunting force's fuel and supplies against the same hive (`ThreatSoftening.stagingWants`: the
`musterFloorFP` sortie's `sortieWants`, no marines or armaments), x the same multiplier; relays
follow either way (`ownWants`). The base then counts as staging for a hunt (`stagesForHunt`). Staged
for Pelephanar's siege, weighed on 14,288 FP of system swarms, Hegemony's staging bases and relays
banked 100-560k fuel against a faction total of 59.6k, none of it ever spendable, while the hunts
that would have thinned the system went unpaid. Once hunts thin it enough the siege fits and the
target returns to it - with hysteresis: a hunt-staged base returns to its siege only when the
siege's fuel and supplies fit within 0.8 x the funding in reach (`SIEGE_RETURN_SHARE`), a
siege-staged one drops to hunt staging above 1.0 x (a base that flipped four times in one run
turned its convoys around each time). The faction funds its sieges cheapest first (2026-09-30,
`ThreatConvoys.committedBefore`, called in `siegeStock`): a base's siege fits only in what its
network holds after the `siegeWants` of every cheaper staging base in the same `stockNetwork` whose own
verdict is a siege (cheaper = less fuel wanted; ties by market id; a player base is not counted). Before,
each base read the whole network's stock plus banking alone, so a dozen bases on one pool all staged for
sieges and none reached its target (40-month test: 1,148 postponements, median pool 23% of need).
`WANTS_MEMO` holds the day's `siegeWants` per base; `ALLOCATING` stops the walk re-entering a base
whose verdict is being read. The verdict persists in `threatinc_huntStagingVerdicts`. A
"Staging:" log line reports each verdict change (`logOnChange`), naming the
siege's wants and what is in reach. This applies to NPC bases; the player orders their own sieges. Cross-reference (2026-09-29): NPC
staging, and the sieges, hunts and Support / Defend orders it feeds, now raise the Threat's pressure on
the hive system (`ThreatPosture`: a staged base counts at 1.0 for its siege, 0.5 for a hunt; a live
siege or hunt counts in full), so the hive holds a bigger garrison there - docs/hive-garrison-and-upkeep.md
"Posture".
If the base is short by
at least `convoyMinLoadFraction` (0.5) of a load or of the target (whichever is smaller),
the same-faction colony whose stock reaches the base (`stockReachLY`; the player's at any range)
that holds the most above `donorKeepFraction` (0.5) of its own months cap - a staging
base counts too, above that plus its own staging target (2026-09-24) - ships a convoy
of everything it can spare that the base still wants, and every donor that can does the
same in parallel until the shortfall is covered (`planLogistics`; net of `inbound`, the
cargo already at sea to the base). 2026-09-29, no arbitrary caps: it was one convoy per base
at a time, each up to `convoyMarineCapacity` (2,000) marines / `convoyCargoCapacity` (6,000)
units, which made a staging target of tens of thousands a queue of round trips. Those two
are now **reference loads**: the unit "worth a sailing" is measured in
(`ThreatConvoys.minLoad`, `convoyMinLoadFraction` of a load or of what the donor could spare
when full - the old test against a hull load alone meant 1,000 marines or 3,000 units,
which no reserve ever reached, so nothing sailed), never a ceiling on a load;
`fitHulls` grows the fleet to carry the load, up to vanilla's `maxShipsInAIFleet` (`fleetShipLimit`,
an engine limit; see "Hulls fit the load"). Deposits are
not capped, so a staging base fills past its own cap. The base's short commodities are
tried shortest first until one has a donor, and that donor sends everything it can spare
that the base wants (2026-09-24: a base whose worst need nobody banked - Chicomoztoc's
fuel - used to get no convoy at all). **Stage** (the hand order) is the
override: `stageDonor` / `stageLoad` send whatever the best donor in reach can spare, no
minimum, preferring donors that are not staging bases; the confirm prompt names the donor.
The colony table's **Convoys** column reads **Staging base** (yellow) for a base, **to
<base> N ly** (white) for a donor, a grey dash for a colony with no staging base to feed
(its reserve stays home; an NPC donor looks only as far as its fuel pays - tooltip "No staging
base its fuel reaches" - a player donor anywhere) and **No Waystation** where nothing sails or lands;
the row tooltip names the hive a base stocks for and the four targets.

**NPC reach and front runs (2026-09-27, overnight after run 9).**
- **Convoy reach:** an NPC donor reached a staging base as far as its own fuel did
  (`IncursionManager.expeditionRangeLY`, at least `convoyRangeLY`), the range a base
  stages at, and `stagingBaseFor` used the same rule. In run 9 Hegemony's forward
  staging base Calu got 2 convoys in three years, while 65 others moved marines around its
  core worlds, and its sieges waited on marines with 15k banked. Since 2026-10-01 a donor
  reaches as far as its fuel pays the voyage ("Logistics reach" below).
- **Front-run source:** an NPC front run loads at whichever of the nearest base or the
  faction's markets in reach of the hive covers the most of its wants (`frontScore`).
  Links are excluded, as the base too (2026-09-27): a link in the hive's own system is
  always the nearest base, so a nearest link gives way to the faction's nearest colony
  base at any range (`ThreatConvoys.colonyBase`, `evacuate`'s fallback) for supply runs
  and pickups alike - a link's stock dies with its station, and run 16 lost a withdrawn
  front's survivors into one.
- **Orbit cover lets runs in:** a front run is let in by the front's own orbit cover
  (`ThreatGroundFronts.coverHolds`), both when it is planned (`canRunTo`) and at the door
  (`pollFrontRun`). In run 9 Loka's run waited at the door while 5,900 FP of cover held
  the orbit, and the dry front was overrun three days later.
- **Relays (2026-09-29, `ThreatConvoys.relayPlan` / `drawRelays` / `pickRelay`).** A market's stock
  reaches another only within `stockReachLY`, so a forward staging base drew on the few markets
  near it while the core's could not reach it at all, and its sieges sailed from the core at two
  to three times the passage (Chicomoztoc to Damar's Star, 31.9 ly, while Gamma Shero stood 11.4 ly
  out). A market whose in-reach donors cannot cover its staging shortfall names one **relay**: a
  depot whose stock reaches it, not under a ground front, reached itself by more of the faction's
  markets, the most of them new to the needy market (the nearest breaks a tie). The relay takes the
  unmet part as a target of its own (`relayTargets`), convoys stock it from its own donors like any
  staging base (`planLogistics`), and it ships onward what it holds for that market (`relayHold`
  feeds `sendable`). A relay short in turn names its own, so a chain climbs toward the core one
  real convoy hop at a time; every market is weighed once, fewest-reaching first, so no chain can
  loop. Relay stock sits inside the staging bank (`spare`): hunts and other staging bases leave
  it. Relays also serve guard bases: a base whose garrison voyage could not be paid carries a
  voyage want (docs/frontlines.md, `noteVoyageWant`) and the relay plan weighs its need and keep
  by `ownWants`, its siege stock raised to that want. Logged as "Relay:". The player's convoys reach
  any range and have no relays.
- **The staging target (2026-09-29).** `ThreatConvoys.stagingTargets` = `bankTargets` (the siege's
  stock plus any relay target) + the garrison voyage want (`withVoyage`: the voyage's fuel and
  supplies above the keep the voyage never draws). The staging bank
  (`ThreatReserves.stagingBank`) is `bankTargets` alone - it excludes the voyage want, so the
  stock convoys bring for a voyage stays spendable and the voyage can spend it, while hunts and donors
  still leave the siege's and the relay's.

A player base
stages for the nearest hive the PLAYER HAS FOUND (`ThreatIncData.discoveredSystems`), at
any range, as the Siege button has none for the player; until 2026-09-05 evening it took
the nearest hive in expedition range whether found or not, and the board (as "Stocks
for") named a system the player could not find on the map.

**Hulls fit the load** (2026-09-24, `ThreatConvoys.fitHulls`): after the fleet is built
(about a point of hull per 40 marines / 60 units), the faction's own personnel, freighter
and tanker hulls are added until the marines fit the berths, the goods the hold and the
fuel the tanks (each pass runs until the load fits or no hull of the role can be added;
2026-09-29: `MAX_FIT_HULLS`, 12 a pass, silently cut a big load). Before, vanilla's hull picks
(and, until 2026-09-29, an NPC navy's fleet-size multiplier, now off for every convoy) left convoys a few dozen free berths, and marines were loaded only
to that - 19 to 76 of a few hundred planned. Fuel is loaded against tank space, not the
hold. NPC convoys and outpost returns only: a player colony's convoy keeps the hulls its
free fleet points bought (`ThreatAidCapacity.fitLoad`) and loads what they carry, so it
never sails over the ledger - it is never grown or split, vanilla prunes it at its ship limit,
the load is clamped to what it carries and anything clamped stays at the donor.

**Split convoys (2026-09-29).** `fitHulls` grows a fleet only up to `maxShipsInAIFleet`
(`fleetShipLimit`); it used to grow one fleet to 50-100 ships. A load that needs more sails as
several convoys in parallel (`ThreatConvoys.dispatch`; the `nextHulls` search builds the fleet
for the largest share of the load that fits, to within `SPLIT_PRECISION` 1.25). Each fleet
pays its own escort - including a `convoyEscortFP` base per fleet - is paid for what was built
(`builtEscort`: vanilla pruning below `BUILT_SHORT` is not billed), and is tracked and settled on
its own. The total shipped is the load; only its division into fleets changes. The first fleet
carries the whole sailing in `Convoy.sailing`, and the planner's totals (relief and front-run
accounting) read it through `carried()`. `dispatch` pays the escort and draws what is loaded
(`sail`: "draw only what was loaded"), so a donor holding less than asked sails less, and an empty
fleet refunds its escort and fades. A fleet that comes up short ends the sailing; the rest waits
for the next pass. Logged as "Convoy split".

**Resolution** (fast poll): a convoy whose fleet is dead is lost with its cargo
(announced if the player knows the faction is at war); one that reaches its destination
deposits whatever is still aboard into the base reserve and turns for home
(`GO_TO_LOCATION_AND_DESPAWN`). One still afloat past `convoyTimeoutDays` (2026-09-29) is not
written off with its cargo: it turns for home and settles (`returnHome`), and
`ThreatReturns` despawns it only if it is not home within another `convoyTimeoutDays`. Fleets are flagged `$threatinc_convoy`; they are
ordinary faction fleets, so the Threat hunts them and the player can raid them.

**Escorts pay (2026-09-29, closed economy).** An NPC convoy's escort sails at the sortie's
voyage rate (`ThreatConvoys.escortRate`: `expeditionSuppliesPerPoint` per
`FP_PER_RESPONSE_DIFFICULTY` points, fuel for the distance), drawn from the donor's
spendable stock (an outpost's whole stockpile) beyond the cargo it ships, and shrinks to what
is paid (`paidEscort`), to none. Since 2026-10-01 the voyage's fuel is paid whatever the escort,
or nothing sails (below). The convoy
comes home on the tracked leg (`ThreatReturns.sendHome`), its hulls re-banked at what
survived. A convoy that never sailed refunds the escort in full (`refundEscort`). A player
convoy's escort is its capacity ledger's business. An NPC convoy whose donor is gone or has
changed hands settles at its faction's nearest base (`ThreatFleetOrders.pickBase`), else its
nearest colony (`fallbackHome`, `homeBase`); a front run's arrival no longer deposits leftovers
into a depot that changed hands. A convoy that times out turns home and settles too (see
"Resolution").

**Logistics reach (user's call 2026-10-01: "old fuel radius should be abolished. supply fleets
can go wherever if they have enough fuel.").**
- **No radius.** `ThreatConvoys.stockReachLY(donor)` = the donor's fuel above its floor
  (`ThreatReserves.available`) / `haulFuel(1)`: as far as its fuel pays a convoy's voyage.
  `haulFuel(ly)` = `baseEscort()` (`convoyEscortFP`, 30) x `escortRate(ly)[0]` = 30 / 25 x
  `expeditionFuelPerPointLY` (10) x ly = 12 fuel a light-year, so `convoyEscortFP` sets the reach
  and every haul below (at 0 both are unlimited and free). It is the donor rule of the
  staging planner (`stagingBaseFor`, `pickDonor`), relief (`planRelief`), Stage (`stageDonor`),
  ally aid (`pickAllyDonor`), relays and every pool (`IncursionManager.marketsReaching`). The
  player's markets reach any range. It was the larger of `convoyRangeLY` (15 ly) and the
  market's fuel radius (`IncursionManager.logisticsRangeLY`), which nothing paid for; both are
  gone, the setting `threatinc_convoyRangeLY` with them. `expeditionRangeLY` is a strike's
  reach, not a logistics radius, and is unchanged.
- **A convoy pays its voyage's fuel or stays home.** `buildHulls` builds nothing for an NPC
  sailing whose donor cannot pay the voyage (`paysVoyage`): `haulFuel(ly)`, the fuel the base
  escort's 30 FP burn, 12 a ly - whether staging, relief, relay, outpost return, front run or
  pickup. It is paid from the donor's stock above its floor (`voyageStock`:
  `ThreatReserves.available`, the measure its reach is read from; an outpost's whole
  stockpile), then from the fuel the convoy ships (drawn by `payEscort` / `payEscortPart`, taken
  off the load by `burn`); what it burns does not sail, so a donor shipping all the fuel it
  spares still sails. A refused sailing logs `Convoy held:` with the fuel above the floor
  against the bill, once a donor a month (`logHeld`).
- **The escort is what is paid, down to none.** Up to the base escort its supplies (36 for
  30 FP) come from the stock above the floor, then the supplies cargo; the value escort above it
  (`convoyEscortPerThousand`) from spendable stock beside the cargo, fuel and supplies
  (`paidEscort` / `payable`). A donor with no supplies to spare sends its convoy unescorted.
  First (h43a) the whole base escort, supplies too, was paid from spendable stock or nothing
  sailed: spendable stock is what a donor ships, so a convoy carrying no supplies found none,
  and NPC sailings fell from 82 to 17. Paid from the stock above the floor (h44a), every
  refusal was still a donor with thousands of fuel above its floor and 0 supplies - supplies
  bind the whole economy - so supplies size the escort, never the voyage.
- **Pools pay a haul.** Stock taken aboard elsewhere without a convoy - a siege's donors
  (`IncursionManager.donorAvailable`, `siegeDraw`), a hunt's (`ThreatSoftening.donorGives`,
  `drawDonors`, `ThreatPosture.siegeCapacityFP`), a link's upkeep, founding, builds and voyage
  (`ThreatFrontlines.gives` / `drawGiven`), a fleet's ordnance (`ThreatGroundFronts.payOrdnance`) -
  burns `ThreatConvoys.haulPerUnit(c, ly)` = `haulFuel(ly)` / the reference load
  (`capacityFor`: `convoyMarineCapacity` 2,000, `convoyCargoCapacity` 6,000): 0.006 fuel a marine
  a ly, 0.002 a unit of armaments, fuel or supplies - as if the stock sailed in reference loads,
  each paying its voyage. `netOfHaul`: fuel pays its own passage out of the load (have /
  (1 + rate)); anything else gives only as much as the donor's fuel above its floor pays for.
  `payHaul` burns the haul from that fuel before the draw. No haul within a system or from the
  player's markets (`haulRate` 0). Proportional, so a day's upkeep pays a day's share. The
  hunt and upkeep pools (`ThreatSoftening.donorGives`, `ThreatFrontlines.gives`) first read the
  haul against the donor's spendable fuel while drawing it from above the floor: a donor whose
  fuel sat in a staging bank gave no supplies at all, and fleets standing down out of supplies
  went from 17 to 47 in a test (h45a, 2026-10-01).
- **Relays stay**: they carry stock past a donor whose fuel cannot pay the distance.

## Built overnight 2026-09-04/05 (verified in-game on the clone save)

**Convoy planner v2** (`ThreatConvoys.planLogistics`): loads size to the shortfall (2026-09-29:
up to `convoyMarineCapacity` (2,000) / `convoyCargoCapacity` (6,000) then; they are reference
loads now, see "Logistics AI" above); escort =
`convoyEscortFP` + cargo value / 1,000 x `convoyEscortPerThousand`; EQUALISATION
(a donor sent at most half the gap between the stocks) was REMOVED 2026-09-05 - it
answered every colony being a staging base, and it made a base unable to ever hold more
than its donors; with one staging base per hive and a staging base donating only what it
holds above its own siege's needs (`ThreatConvoys.spare`, 2026-09-24 - never before), the
traffic in each commodity has one direction. Every base short of stock sails each tick, as
many convoys in parallel as it has donors for (2026-09-29; it was one at a time per base),
neediest first, FRONT RUNS FIRST (no per-tick cap since
2026-09-27: the cap of 2 held Hegemony's fronts back 12 times in run 14). After any fight `trimToHulls` drops cargo the
surviving ships cannot carry (Blackett's constant loss per attack).

**Raiders** (`ThreatRaiders`): when a convoy sails, hive colonies within
`raiderRangeLY` of the route midpoint with a Defense Swarm above their garrison
reserve roll `raiderChance`; the nearest success detaches its largest swarm from the
garrison list (so the leash ignores it), one after another until the pack's fleet points
reach the convoy's x `RAIDER_MARGIN` (1.5; 2026-09-29: it was one raider per convoy, one
swarm per hive), gives it INTERCEPT on the convoy for
`raiderDays`, then brings it home with the leash's own blinders recipe and rejoins
the garrison. The board shows "Interdiction vs X convoy" as an outbound op on the
hive's row and the convoy reads HUNTED.

**Front runs** (`ThreatConvoys.planFrontRuns`): a friendly front is a reserve
consumer. Wants = `frontResupplyDays` of armaments and marines back to the whole peak
(`landedStrength`) or what holds the front (`holdGap`), whichever is more
(`ThreatConvoys.frontWants`; 2026-09-29: `frontReinforceFraction`, 0.8, of peak is gone -
an army was never let back to the strength it landed at). Runs are sized to the wants,
several in parallel from the bases and forward outposts in reach (it was one run at a time,
each a hull load; the run's hulls grow to carry it, up to `maxShipsInAIFleet` - a supply load past
that sails as several runs in parallel by the same split rule as convoys ("Split convoys" above),
each paying its own escort; a pickup is one fleet grown to the limit, and what its berths cannot
hold rides home aboard it rather than being split off a front still fighting); the nearest base in reach sends a
run out of its reserve above the floor. The run sails to the hive system's
jump-point, waits while `orbitContested` (up to `frontRunWaitDays`, then home), runs
in, lands cargo via `resupply`, and goes home on the tracked return leg. An NPC
front that is dry AND below grind strength sets `withdrawRequested`; the next
planner pass sends an EVACUATION run that lifts the front off (`withdraw`) and
carries it home into the base reserve. Board: **Supply**, **Pull out** and **Escort**
on the faction view's hive rows (own front only) and on the war board's Fronts table
rows; the hive card carries no second copy.

