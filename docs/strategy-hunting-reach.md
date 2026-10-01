# Strategy layer - hunting forces, finding hives, reach and stance

Split out of `strategy-layer.md` on 2026-10-01. One-line answers: `facts.md`.

## Hunting forces (ThreatSoftening, 2026-09-24)

The user's call: human factions send their own softening fleets to hive systems with a
swarm bounty (docs/player-aid.md section 4); they cost resources but carry no siege, so
they can be stronger than a siege; a siegeable world in reach always comes first.

- Every slow tick, for each running `ThreatSwarmBountyIntel`, every mobilised NPC faction sends
  one hunting force from the first of its bases that can (2026-09-29: `ThreatSoftening.huntBases` -
  every base in fuel reach of the hive, not resting and with no siege of its own, ranked by the
  fleet points it and its donors can pay a force there for, `payableFP`, the nearest breaking a
  tie; coalition answers, `ThreatCoalition`, try the same list. The nearest base alone
  (`ThreatFleetOrders.pickBase`), whatever its stock and never passed over, launched no hunt at all
  in a test where the Threat grew from 36 hives to 66), unless:
  - the faction already hunts in that system;
  - the base sent one within `softenIntervalDays` (30);
  - the base has a hive of its own to siege (`IncursionManager.hasSiegeableHive`: a known
    hive it is `siegeBaseFor` whose swarms its fullest flotilla outweighs, off its siege
    cooldown, not already under this faction's siege, and whose landing the base can man,
    arm and provision - rc1 review). It banks for that siege instead;
  - a faction hostile to it has fleets under orders or a siege in the system
    (`ThreatSoftening.hostileAt`, rc1 review: all 9 badly-hurt stand-downs of Run 7 came
    after a fight with another faction's force). Coalition answers skip the same, and skip
    an enemy's call.
- Target: the world the siege's orbit gate reads - the strongest garrison among the worlds
  the siege is fighting (`ThreatSoftening.strongest`, 2026-09-26; see the Run 5 note under
  "NPC sieges"). A siege takes a subset of the system since 2026-09-27, so a coalition
  answer reads the caller's purge targets (`IncursionManager.siegeTargetsOf`) and a bounty
  hunt the poster's `siegeTargets` (the bounty's "Strongest swarms" line reads the same) - except
  (2026-09-29, `ThreatSoftening.gateWorlds`) that a bounty hunt targets the WHOLE system, strongest
  garrison first, whenever the system's swarms or `swarmsMet` drive the siege's orbit gate
  (`siegeOrbitWeighed` > `siegeOrbitFaced` of its targets; `gateWorlds` returns null = the whole
  system): a force that beat the targets' 75 FP and stood down clear left the gate reading 11k FP over
  the siblings. `stagingWants` is sized against the same worlds. Coalition answers to a live siege
  still cover that siege's own worlds (`siegeTargetsOf`); the force records them (`Force.targetIds`; null, and on older saves, is the whole
  system) and is sized to their swarms. The Church besieged Epsilon Qades I alone while
  Tri-Tachyon's answer went over I-B. A force that cannot be fielded or paid against it
  does not go for a weaker world instead.
- Size: the Defense Swarm FP over the siege's worlds (the whole system's for a force with
  no set) x `npcSiegeOrbitMargin` (1.5, the siege's own orbit margin, `ThreatSoftening.margin`),
  with no ceiling (2026-09-29: `softenMaxFP` 12,000 and
  `MAX_FLEETS` 30 left every hive over ~4k FP a world unhunted for a 3.7-year test; only what
  the bases can pay bounds it), and never below the target's garrison NOW x that margin
  (`musterFloorFP`, `garrisonNowFP`: what the colony owns - its garrison, raiders out and
  reinforcements inbound). 2026-09-30: no projection of regrowth - the old floor added the hive's
  bank and its net income over the passage and muster (~86 days at 35 ly), but the posture spends
  banks wherever the pressure is, and the projection asked 8-13k FP of hunts that waited on fuel for
  months while it grew (822 waits, 1 launch in a 33-month test). A garrison reinforced during the
  muster meets the go-in check (`advanceForce`), which sends the force against another hive
  (STRIKES ELSEWHERE, below) or stands it down. 2026-09-29: `softenMargin` (2.0), `softenHeadroom` (1.5) and the refill-to-nominal
  rule are removed - stacked, they asked 15,790 FP of a force against Alpha Mesh I's 3,158 - and the
  projection replaces the regrowth the old refill stood in for, which the bank may not pay for at
  all. Sized on the swarms projected, not those present: 15% of Run 7's forces met a garrison that had
  regrown during the muster and stood down outmatched (rc1 review). It waits if its bases cannot
  pay for it. Counted on the WARSHIPS BUILT
  (`combatFP`): each fleet is built at the points it is paid for (`ignoreMarketFleetSizeMult`,
  2026-09-29 - the planner used to ask for points / the market's `COMBAT_FLEET_SIZE_MULT`
  and a 1.5 multiplier sailed half again free) and the planner adds up what each fleet really
  came out at; what vanilla pruned is refunded (`refundShort`).
  If the yards built less than the floor, the fleets fold straight back into the depot
  (`ThreatFleetOrders.fold`, full refund).
- POOLED (`softenPool`, default on): after the nearest base, every other base of the
  faction that reaches the hive on its own `expeditionRangeLY`, or the primary base within
  `ThreatConvoys.stockReachLY` (as far as its fuel pays a convoy's voyage; until 2026-10-01
  max(`convoyRangeLY`, its `expeditionRangeLY`)), is not resting and has
  no siege of its own chips in, nearest the hive first, until the force reaches its size
  (2026-09-29, `contributors`: the hive's range alone kept every depot behind the primary base out
  of the hunt; a base in reach of the primary chips in, its fleets paying fuel for the whole way).
  The markets that field no fleets - not a base - but whose stock reaches the primary are the
  `huntDonors`, used when `softenPool` is on; forward bases (links) that field no fleets are donors
  too (2026-09-29). Each gives what a hunt may take
  (`ThreatReserves.spendable`, less `outpostKeep` for a forward base: `siegeOutpostKeepMonths` of
  its garrison's supply upkeep, the keep it holds against a sibling's siege) toward the primary's fleets (`payableFP` counts it,
  `payFromDonors` pays nearest first; since 2026-10-01 net of its haul to the primary, `donorGives`),
  and the "Hunting force waits" log line names them. What a
  donor gives rides the fleet (`MEM_FUEL` / `MEM_SUPPLIES`) and comes home to the base the fleet
  returns to, as if a convoy had carried the stock - it is not refunded to the donors. A siege's
  pooled provisions refund the same way ("refunds still land at the base"). Each base that sent a
  fleet rests `softenIntervalDays`.
- Cost: fuel (FP / 25 x LY x `expeditionFuelPerPointLY`) and supplies (FP / 25 x
  `expeditionSuppliesPerPoint`) drawn from the base's stock above the floor and the staging bank
  (`ThreatSoftening.donorSpendable`; donors the same), so a hunt never spends what convoys banked
  for the base's own siege. 2026-09-30: not `ThreatReserves.spendable` any more - its donor keep
  (half the months basis on top of the floor) is the convoys' reserve, and with it a hunt pooled
  from 20-24 bases paid for 500-1,100 FP against the 5,250 its target needed (400 waits, 3
  launches in 23 months); a hunt now draws to the floor as a sortie does (rc1 review: one hunt
  took Culann from 55,852 fuel to 3,765 and its siege postponed). **The exception
  (2026-09-29, `ThreatSoftening.huntSpendable`):** where the base's own siege of that system is
  blocked by the very swarm the hunt targets (`siegeWaitsOnHunt`: the system is the hive it
  stages for and `hasSiegeableHive` is false - its orbit gate is what the hunt thins), that
  siege's staging bank is spendable too and the base keeps only its floor;
  the siege cannot sail until the swarm is thinned, and the staging bank starved the one force
  that would thin it. A base staging for a hunt rather than its siege (`ThreatConvoys.stagesForHunt`,
  below) releases its bank to a hunt in ANY system, not only its own staging hive: it holds nothing
  for a siege that could sail. A siege's pooled marines
  and armaments come from the other bases the same way. Everything is a warship: no marines, no armaments, no landing.
- Fleets: split into fleets of at most `softenFleetFP` (1500), each a `KIND_HUNT` order
  (`ThreatFleetOrders.dispatchHunt`, "Hunt" on the board) carrying the force's id
  (`Order.forceId`, a `ThreatSoftening.Force` in persistent data).
- MUSTER (2026-09-24 review): the fleets fly blinkered to their faction's muster point,
  3,000 units off the hive system's hyperspace anchor on a bearing of its own (`musterPoint`;
  one shared point had hostile factions' forces fighting each other there). A base inside the
  system waits at home. They hold there. The force goes in when
  every fleet is in, or `softenMusterDays` (15) after the first arrived. It goes home if
  none arrived within `softenDays`. It goes in only if the fleets present beat the weakest
  garrison by the margin. If they do not but the whole force would, it waits up to
  `softenMusterStragglerMult` (3) muster spells for the stragglers. Where it musters is
  recorded on the force (`musterInSystem`/`musterEntityId`, or `musterX/Y`), so a
  contributor from another system counts at an in-system muster. A force that stands down without going in gets its
  fuel and supplies back in full on the spot (`standDownAll`, 2026-09-25); one that fought
  takes the return rule's refund. The 60-day term starts when it goes in. Before this each fleet
  flew alone at its own burn and met the whole garrison one by one: in the 21-month run 69
  hunting fleets broke off badly hurt and 3 cleared a system.
- FLEET SIZE (`softenFleetFP` 1500): vanilla prunes an AI fleet to `maxShipsInAIFleet` (30)
  at spawn, and a navy without big hulls loses the points. In the test Nortia's
  Independents built 304 FP of a 1,683 ask. Most navies top out at 250-500 FP per fleet.
  A fleet built below 80% of its ask refunds the provisions for the missing points, and
  the faction asks at most 1.1x what it built from then on (`FLEET_CAP`, cleared on load).
  It is learned only from a real prune (the fleet at the ship cap). There is no fleet-count
  cap since 2026-09-29: the force takes as many fleets of that size as its bases pay for (the
  old `MAX_FLEETS` 30 was what made forces come up short and scrap every month, rc1 review).
- IN AS ONE (the same night's test): sent in on separate headings, the fleets strung out
  and fought the garrisons they passed alone. The slowest MUSTERED fleet leads with the hunt
  order, and the rest FOLLOW it blinkered (`sendIn`). At go-in every mustered fleet folds
  into the lead at once (rc1 review: merged only on its heels, the lead met the garrison
  first, and all 214 hunt battles of Run 7 had one hunter fleet); stragglers fold in
  (`softenMerge`, `mergeInto`): ships, provisions and launch strength summed, home to the
  lead's base. Vanilla's AI never keeps separate fleets together in a fight: before this,
  every battle of a 16-fleet Hegemony force was one ~400 FP fleet against an 876-1,037 FP
  swarm. Merged, the same force (449 ships, 5,895 FP) cleared Alpha Novy Tayvay I. Merging
  stops at `softenMergeMaxShips` (90; one merged fleet reached 900 ships), and fleets
  beyond it follow the lead. The lead drops its blinkers within 2,500 units of the target,
  with every follower within 2,000 of it; a follower near the lead in a battle drops them too.
- In: all fleets `ORBIT_AGGRESSIVE` over the target garrison (the strongest of the siege's
  worlds; it goes in only if the fleets present beat it by the margin). When it is gone the
  force moves on to what the gate reads now - the next strongest of them - only if its
  warships still beat that garrison by the margin; otherwise it strikes elsewhere (below) or
  goes home ("outmatched by"). The whole force goes home (tracked leg, refund on arrival) when the siege's worlds
  are clear ("the siege's worlds are clear"; "the swarms are gone" for a force with no set), it falls below
  `softenRetreatStrength` (0.4) of its strength when it went in or last moved on, or
  `softenDays` (60) run out. Strength is what is IN the fight: the fleets that went in
  (`Force.inForce`) wherever they are, plus a straggler once it reaches the lead
  (`presentFP`), not stragglers that never came in (rc1 review: a force
  ground down to 41 FP at Rhesh still read 53%). A single hunt rebaselines when it moves on.
- STRIKES ELSEWHERE (2026-09-30, `ThreatSoftening.divert`): a force outmatched at its muster,
  or by the next garrison when it moves on, turns on another hive instead of going home. The
  Threat's posture pours swarms into a hive a force gathers against (Epsilon Shero I: 621 FP
  when the force was sized, 5,602 when it mustered, 10,921 against the next force), so the
  swarms it drew left other hives thinner. Candidates are every live hive whose swarms,
  standing and inbound (`garrisonNowFP`), the force's present warships beat by the margin, with
  a standing garrison to kill, in no system a hostile faction works (`hostileAt`) or another of
  its own forces hunts. The detour - the route's extra light-years by the new hive and home,
  over going home from where it is, at the one-way rate (`detourFuel`) - is drawn from the
  force's bases (`huntSpendable`) and then the first base's donors, and rides the fleets'
  `MEM_FUEL`; a hive whose detour they cannot pay is out. Of the rest it takes the most standing
  swarms per fuel the whole sortie burns (what it drew plus the detour). The force goes in at
  once (no second muster) and its orders move to the new system (`Order.systemId`, targets
  cleared). A "Hunting Force Turns" notice goes out when it changes system. None payable and
  beatable: it stands down as before, and the log names why. First test (18 months): 5 forces
  struck elsewhere, none stood down outmatched (30 fleet stand-downs before), 16 launches, 15
  went in, 120 hunt battles against 45; the Threat ended at 45k FP of fleets against 63k.
- A THINNED SYSTEM IS SIEGED NOW (the same night): when a hunt clears a colony's swarms,
  a force that fought goes home, or (rc1 review) any battle sinks Threat ships of a
  bountied system (`ThreatSwarmBountyIntel.thinned`), `IncursionManager.huntThinned` marks
  the system. The
  next poll runs the siege pass (`tryPurgeBombardments`) instead of waiting for the
  monthly tick, and the system's colonies count as wounded (the `purgeFollowUpDays`
  cooldown) for that long. In Run 4 a hunt opened Alpha Novy Tayvay's gate (355 FP) and
  the swarms were back at 3,233 FP before the next tick. `siegeMaxFleets` went from 10
  to 25 the same night: big hive worlds hold 3.7k-7.5k FP of swarms each, and 10 fleets
  (~2.4k FP) never could reach the orbit margin. (2026-09-29: the knob is gone - no fleet cap.)
- Upkeep runs on the half-day order poll (`ThreatSoftening.advanceHunts` from
  `IncursionManager.advance`). It used to ride the 30-day strategy tick, so a hunt could
  fight on for a month before its retreat rule was read.
- The PLAYER'S HUNT (same day, the user's call: it replaces Intercept, "quite a lot of
  crossover and this action feels more useful"). The faction view's Intercept buttons are
  Hunt now: on an own hive row (`BUTTON_HUNT`, a task force with everything the nearest
  colony has free), on a group fleet's row (detach -> `adoptHunt`), and the aid button on
  another faction's hive rows (`ThreatAid.dispatchStrike`, an aid-flagged hunt earning the
  front standing once on arrival). Coalition answers hunt too. Greyed out when the system
  has no Defense Swarms (`ThreatAid.quoteStrike`). The player's hunting fleets keep the
  single-fleet rules (weakest garrison first - `huntTarget` - and home below
  `softenRetreatStrength`; the NPC forces aim at the strongest since 2026-09-26), with a
  message when one moves on, finishes or breaks off.
- The player's hunting fleets COLLECT SWARM BOUNTIES on their own
  (`ThreatSwarmBountyIntel.HunterPay`, a FleetEventListener added at dispatch and adopt,
  saved with the fleet): a battle one fights pays the bounty for the Threat ships lost,
  times that fleet's share of its side's starting FP, only while it flies a Hunt order. A
  battle the player is in pays through `Kills` alone: vanilla's player-involvement share
  already counts the player's fleets (rc1 review: a hunter wiped out in the fight had been
  paid on top). Standing and the message come once per battle (`reportBattleFinished`),
  not once per autoresolve round.
- Test, 2026-09-24, on the clone save with provisions free (Chicomoztoc could pay for 0 FP
  at the real rates):
  - Chicomoztoc sent 8 fleets (2,883 FP) to Alpha Mesh. Its swarms fell 2,883 -> ~300 FP,
    under the 1,633 its siege can take, so that siege now waits only on marines.
  - Nachiketa's force against Delta Mengryla was beaten back; the swarms there grew to
    ~3,450 on reinforcements, so it stays home (floor above the cap).
  - Every hunting fleet came home "badly hurt".

## Finding the hive (ThreatScouts, 2026-09-24, untested)

User's call: a strike should not reveal where it came from; NPC factions find hives with
scouting parties, and a hive any faction finds is known to all, player included.

- One shared list, `ThreatIncData.discoveredSystems()`. It now gates the NPC war too:
  `ThreatScouts.sectorKnows` (not debug-bypassed) filters purge sieges
  (`tryPurgeBombardments`), the struck faction's task force (`dispatchFactionResponse`)
  and its retarget (`ThreatResponseIntel.retarget`), NPC convoy staging
  (`ThreatConvoys.stagingHive`), the stand-down test (`ThreatWarState.hiveInReach`) and
  the defense-board contracts (`ThreatMissionIntel.bestObjective`).
- What reveals a hive: the player entering its system (silent, as before); a scout
  entering it; any non-Threat colony in the same system (`revealNeighbours`). A scout's
  or neighbour's find is announced. Strikes and retaliation strikes no longer reveal.
- A strike on an NPC colony gives its faction a LEAD on the origin. Sorties sweep
  uninhabited systems with a planet within scoutLeadRadiusLY of the origin, nearest-first
  from the faction's nearest military world, as many parties as the route needs (2026-09-29:
  `scoutStops`, 4 per sortie, is gone; a route joins a stop only while it lies nearer the last
  stop than the party's start, `ThreatScoutRoute.nearestFirst`), until the origin is
  entered. A lead ignores sweeps older than itself. No task force sails until the origin
  is known; after that the normal purge tick takes it up.
- A mobilised faction with no lead sweeps within scoutRangeLY of a random military world
  every scoutIntervalDays. A system swept clear is skipped for scoutMemoryDays.
- No limit on sorties out at once (2026-09-29: `scoutMaxPerFaction`, 2, and one launch per poll
  are gone; a faction sends a party per route until nothing is left to sweep around each of its
  military worlds); a scouting party is a PATROL_SMALL of
  scoutFleetPoints, non-aggressive, stays scoutStayDays per empty system, reports home.
  **Scouts pay (2026-09-29, closed economy):** a party pays what any NPC sortie pays from its
  home's spendable reserve - supplies at the voyage rate per point, fuel per point per light-year
  of the whole route - in full or it does not sail (the route waits on the depot), and what
  survives is settled home on return (`ScoutReturn`, `ThreatReturns.settle`).
- Sweeps skip abyssal, hidden-theme and cut-off systems (`ThreatScouts.unreachable`): a
  fleet's GO_TO into vanilla's "Unknown Location" pockets never arrives - one Hegemony scout
  sat 132 days on one (test run 2026-09-24). Backstop for any stop: a party that has not
  reached it in scoutLegMaxDays (60) logs "gave up on" and moves to the next.
- The strike intel hides its origin until found: no source arrow, map point on the
  target, status and return ETA without the staging world, no recall hint while preparing.
- The player gets no leads: they scout in person, or wait for an NPC's find.
- Knob hiveFogOfWar false = the old rule (every strike reveals its origin, NPCs know all).

The swarm scouts too (`ThreatSwarmScouts`, user's call the same day): a strike does not go
out the moment a world is in reach.

- `pickStrikeTarget` (strikes and retaliation) takes only worlds the swarm knows
  (`swarmKnows`): its system charted by a Scouting Swarm, shared with a hive colony, or
  a Threat ground front on the world. Charted systems stay charted.
- From phase 2, a colony that passes the strike economy gates - strikeMinSize, hulls
  delivered, fuel, working Swarm Nexus, but NOT the garrison - sends a Scouting Swarm
  (`ThreatFleetComposer.createScouts`, the scout archetype, swarmScoutFleetPoints)
  through unknown systems with a strikeable world within its `fuelRangeLY`,
  nearest-first, a sortie per route, scoutStayDays each. The Defense Swarms stay home.
  Each Scouting Swarm is paid from the colony's fabrication bank (`canAffordFP`, then
  `chargeFP` at the fleet's real points; docs/hive-economy.md "Fabrication bank"): no bank, no
  scout. The scout is bound to the bank (`bindToLedger`), so what survives is re-banked when it
  fades out at home.
- No limit on scouts out at once (2026-09-29: `swarmScoutMax`, 2, hive-wide, and one sortie
  a poll are gone; `launchAll` sends a route per hive system that can pay until nothing unknown
  is left in reach). Scouts keep the swarm's stealth, pick no fights,
  and fade out at home. Charting a system is announced in debug mode only.
- Knob swarmScouting false = the swarm knows every world, as before. An existing save
  pauses its strikes until the first scouts have charted something.

Verify: a strike on an NPC colony logs "Scout lead"; a "Scouting party" fleet leaves the
faction's military world; entering the origin announces the find and a board row names
it; no "dispatched a task force" line before that; the strike intel names no origin.
Swarm side: "Scouting Swarm from X" in the log once phase 2 is reached, "Scouting Swarm
charted Y" on arrival, and no "Strike launched" at a system before it is charted.

## The factions' reach and stance (2026-10-01, user's call)

"Humans should have the same reach and stance systems" as the hive (docs/hive-reach-and-stock.md
"Reach is the bill", `ThreatStance`).

**Reach is the bill** (`humanBilledReach`, on). `IncursionManager.expeditionRangeLY` - every
"is this base in reach" test the factions run (sieges, hunts, relief, staging, the hive's
own read of who can siege its claims) - is `ThreatReach.baseRangeLY` for an NPC base: as far
as its war reserve and the donors pooling into it (`siegeDonors`) pay the smallest siege
flotilla's trip - two fleets of difficulty 5, 250 FP: its passage fuel (points x ly x
`expeditionFuelPerPointLY`) and, above the hulls' deposit, its ships' supplies for a strike's
days there and back (`ThreatReach.tripSupplies`: FP x the faction's measured supplies a FP a
month, 0.94 before it has fleets out). No radius and no military structure needed: a forward
base reaches from the day it holds stock (the "zero-reach link" bug is gone with the
radius); an empty depot reaches nothing; a faction never mobilised has no reserve and
reaches nothing. Memoised a day per base. The player's worlds keep the fuel radius.
- **Logistics have no radius** (user's call 2026-10-01; "Logistics reach" under Convoys). Stock
  pooling between a faction's own markets (`ThreatConvoys.stockReachLY`, the donor walks) reaches
  as far as the donor's fuel above its floor pays a convoy's voyage, and what it pools pays
  its haul. It reads the donor's own stock, never the reach: the bill reads that pool (net of the
  haul), so it must not read the reach back. This section's first draft kept the old fuel radius
  here (`IncursionManager.logisticsRangeLY`), which nothing paid for.
- **A siege pays its whole trip.** The launch gate and `siegeCanPay` price a difficulty point
  at its deposit plus its supplies for `siegeTripDays` (muster and passage, the stay, passage
  home), so a far siege costs more than a near one and cheapestFirst weighs the distance. The
  stay is `siegeStayDays`: the days its commander would bombard the slowest world it lands on
  (`bombardPlan`) and a day to land, or the days it saturates or razes the rest in turn
  (`razeRun`: a hive's saturation runs to the commander's stop, weeks), whichever is longer, and
  at least a slice's days. The first
  draft billed `siegeOrbitDays` (120), the most it may stay - ~299 supplies a point in h40a,
  where the hive bills its strikes no stay at all (review).
- **Not reserved.** The trip is paid as it goes (`ThreatUpkeep`), not drawn at launch, so two
  sieges admitted in one pass can count the same stock, and upkeep draws only what others
  can spare (`payFromOthers`) where the gate counts the donors' stock above their floors. An
  over-committed siege runs short and turns home (`outOfSupplies`). Open.
- **An expedition that never spawns pays too.** `ThreatUpkeep.chargeAbstract`: an
  off-screen siege has no fleet for the upkeep pass to bill, so its sieges sailed for free;
  now its allotment's supplies a month come from its base (then the markets reaching it),
  and a month owed turns it home (`outOfSupplies`), as a spawned fleet's does.

**Stance** (`ThreatFactionStance`, `factionStanceEnabled`, on). Each mobilised NPC faction
keeps the hive's three stances, read every 7 days, held `stanceDwellDays` except into
CONSOLIDATE, logged "Faction stance: f A->B - reasons" and shown on the monthly census:
- **CONSOLIDATE** - pressed (struck within 60 days, or a Threat front on one of its worlds)
  and losing its exchange (`noteTrend`, fed by real battles via `ThreatPosture.noteBattle`
  and off-screen fights via `ThreatAbstractBattle`) or outweighed by the swarm facing it
  (hive systems whose `ThreatReach.facedFaction` is it): no new links, and sieges only at
  hive systems facing it. A siege it may not sail does not hold its base back from hunting
  (`hasSiegeableHive` skips it).
- **PRESS** - not pressed, not losing, its force (fleets out plus what its depots' supplies
  pay at a siege's price a FP) outweighs the swarm facing it by `stancePressRatio`, and a
  base can take and pay for a siege now - any of its bases in reach, cheapest first, as
  `tryPurgeBombardments` tries them (read from the nearest alone, PRESS fired once in h40a):
  the system with the most target size for the least
  orbit odds per day away is weighed first in `tryPurgeBombardments` and the pressing
  faction's bases sail first there; links on `stanceSecondaryShare` of its passes.
- **EXPAND** - otherwise: links toward the hive, sieges as they come.
- **Open:** CONSOLIDATE holds while any Threat front stands on the faction's worlds and the
  ratio is under 1, and the ratio's denominator moves with `facedFaction` (hegemony's "FP
  facing it" read 88,088, 2,629 and 66,966 within h40a), so stances can swing.

## Not built yet

- Redeploying a withdrawn front elsewhere by order (it comes home into the reserve
  today; a Siege from that base lands it again).
- The ending (8.5) - deferred to a live session.
- Outposts as bases for anything but front runs and the ship-home convoy - orders,
  expeditions and staging still start at colonies.
- The player's own verb to land a counter-force on their invaded colony (Guard and Aid
  convoys exist; a landing from the fleet does not).

## Testing notes

Clone save + XML injection as in docs/testing-harness.md. Cheapest proof lines (debug
logging on): `War mode:`, `War footing:`, `Reserve seed:`, `Reserve cover:`, `Reserve
ledger:` (one line per mobilised colony on the first poll after a load, then monthly:
stock, available/demand, surplus, bank rate, cover state), `Expedition draw`, `Convoy
dispatched`, `Convoy arrived`, `Convoy lost`, `Landing from cargo`.
