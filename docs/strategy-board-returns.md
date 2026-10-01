# Strategy layer - faction view, fleet orders, returns

Split out of `strategy-layer.md` on 2026-10-01. One-line answers: `facts.md`.

## The faction selector and faction view (ThreatFactionView)

A row of buttons under the totals strip: "The Threat" plus one per mobilised faction
(the row is absent until somebody mobilises). `ThreatIncursionIntel.selectedFactionId`
holds the choice; the hive view is unchanged. A faction view replaces the ledger and
cards with three stock tables, faction-coloured:

1. **Colonies - staging bases first**: name, size, military structure, ground defense,
   fleet points free / capacity, the four reserves, where its convoys go, Threat strikes
   in flight against it. The **FP** cell (`fleetCell`) reads **free / capacity**
   (`ThreatAidCapacity.figures`, rounded once so the tooltip's lines add up): what can
   sail from the colony now - its own capacity less what it has in task forces, plus the
   LIVE strength of the task forces on station over it - over its own capacity
   (`capacityFP`), coloured by the free figure by the reserve key: white while a full task
   force (`guardFleetFP` x `TASK_FORCE_HULL_MULT`) can sail from it, yellow while only a
   reduced one (`aidGuardMinFP`) can, red while none can, a grey dash off the ledger (no
   military structure, or not the player's colony). Its Total is free summed / capacity
   summed, coloured by the single richest colony: a task force sails from ONE colony
   (`ThreatAid.pickTaskForceSource` - the nearest that fields a full guard, else the one
   with the most free points if that makes `aidGuardMinFP`) and points pool across
   colonies only by staging guards (see "Staging fleets" below). Every task-force figure
   the board quotes - prompts, button tooltips, announcements - is in the same unit as
   the cell, whole-fleet points with support hulls (`ThreatAid.taskForcePoints`,
   `Quote.points`); the combat points (`taskForceFP`, `Quote.fp`) are the factory's
   input and never shown. A reserve cell's number
   carries the meaning and its colour repeats it (`stockCell`): a positive number is the
   stock banked - white **excess** (no shortage), yellow **deficit** (short, depot
   covering or about to, so the stock is being spent); a red negative is **critical**,
   the colony's uncovered shortfall in vanilla units (short, depot too low to issue);
   a grey dash is **empty** (nothing banked, nothing short). A **Total** row at the
   foot follows the same rule (`totalCell`): the faction's banked stock
   (`ThreatReserves.factionStock`), yellow while any colony is short, the summed
   uncovered shortfall as a red negative when nothing is banked anywhere. A one-line
   colour key under the table replaces the old intro paragraph. A shortfall is not a
   gate: fuel and supplies are drawn best-effort by sorties and expeditions (see
   **Draws** above), so a red fuel cell means vanilla's shortage penalties and no
   accrual at that colony, not grounded fleets. Row tooltip: per-commodity stock / monthly accrual /
   cap, and what an expedition from here draws. Click = vanilla's colony screen
   (`ThreatColonyScreenDialog`), as the hive cards' Colony button.
   Buttons: **Guard** (labelled **Fleet**) and **Stage** (labelled **Supplies**) - the
   user's labels, 2026-09-05 evening; the column is headed **Staging** and the old
   Staging column is headed **Convoys** (see Convoys above). A player task force sails
   with EVERYTHING its colony has free
   (`ThreatAid.taskForceFP`; a 100 FP fleet "will never have any capitals"), the ledger
   charging what vanilla's factory actually built (`ThreatFleetOrders.builtPoints` -
   whole-fleet points against whole-fleet points; the first version compared combat
   points with the whole fleet and so always charged the full ask);
   `guardFleetFP` is the NPC size and the bar for picking a source first. The Staging column is .12 of the width: two floating
   buttons need ~114 px, and at .08 the second one used to overlap the Strikes column.
   **Every order button is gated by its own order's rule** (2026-09-05): vanilla's confirm
   dialog cannot grey its Confirm, so a button is disabled - the reason as its tooltip -
   whenever the order would raise nothing: Guard / Intercept / Escort by
   `ThreatAid.quoteDefend` / `quoteStrike` (a source colony with at least `aidGuardMinFP`
   free), Stage by `ThreatConvoys.stageDonor` (a same-faction colony whose stock reaches it that can
   spare a worthwhile load), Supply / Pull out by `ThreatConvoys.supplyBlockReason` /
   `pullOutBlockReason` (a front, no run already bound there, the orbit clear or held, a
   base with something above its floor), Siege by `IncursionManager.siegeBlockReason` (the
   base can commit `expeditionMinMarinesFraction` of the landing's marines - the same gate
   `launchSiegeExpedition` applies). The confirm prompt quotes the same figures
   (`IncursionManager.siegeWants`, on the same `siegeSizes` the launch and the convoy
   planner's `ThreatConvoys.stagingTargets` use, so none can disagree).
2. **Fleets and orders**: task forces (`ThreatResponseIntel`), expeditions
   (`ThreatPurgeFGI`), convoys, and standing orders, each with task, status, ETA. Click =
   show the fleet or intel on the map. **A group is one row per fleet** once its fleets are
   real (2026-09-06, user: recall some, leave others): a task force's `livingFleets()`, an
   expedition's `getFleets()` while `isSpawnedFleets()` (before that the expedition is one
   row, "N fleets"); the row shows the fleet's name and the marines it carries (cargo
   expeditions) or its FP. Buttons: **Recall** (this fleet only - `ThreatPurgeFGI.recallFleet`
   / `ThreatResponseIntel.recallFleet`, the group's spawned-strength baseline drops with it
   so the rest is not judged beaten, the last fleet out ends the group; convoys, orders and
   outposts as before) and **Hunt** (Intercept until 2026-09-24; `BUTTON_DETACH`: `detach`
   the fleet from its group and `ThreatFleetOrders.adoptHunt` it - the same fleet, nothing
   built or charged, hunts the target hive's Defense Swarms until they are gone, it falls
   below `softenRetreatStrength` or `softenDays` run out (see "Hunting forces"), then goes
   home to the expedition's source base on the tracked leg; marines aboard stay aboard;
   the Confirm is the question and the days only). Recall keys:
   `purgefleet:i:fleetId` / `tffleet:i:fleetId`.
   Both are a clean cut (`ThreatPurgeFGI.detach` -> `cutLoose`, seen 2026-09-06 at Gamma
   Hero): the fleet loses vanilla's `WarfleetAssignmentAI` (a raid fleet's own
   objective-capturing, colony-raiding script - every detached fleet used to sit on the
   hive's comm relay instead of its jump-point), any military-response assignment, the
   raid's busy flag and the blinkers, and is flagged `FLEET_NO_MILITARY_RESPONSE` so no
   response script - the raid's own, or a system's fight for its objectives
   (`WarSimScript`) - may borrow it. Every task force the layer builds
   (`ThreatFleetOrders.buildTaskForce`, `IncursionManager` response fleets) and every
   tracked leg home (`ThreatReturns.sendHome`) carries that flag too; vanilla's own
   despawning return was immune, the tracked one was not. Vanilla's incremental spawn never
   stamps `KEY_SPAWN_FP` on a fleet, so `ThreatPurgeFGI.noteSpawnFP` does: without it
   `detach` took nothing off the group's baseline and detaching three fleets of four left
   the fourth judged beaten - the expedition aborted and sailed home, leaving the front to
   the scour clock.
   **Fleets on tracked legs home are rows too** ("Returning", `ThreatReturns.all`, 2026-09-06:
   an expedition's fleets vanished from the table the moment it stood down - the table skips
   ending groups - though they were still weeks out; likewise a fleet vanilla cut below
   `fleetAbortsMissionFPFraction` and sent home alone). No Recall; **Intercept** turns the
   fleet back to hold the door of the hive it is returning FROM (`Return.fromSystemId`,
   captured in `sendHome` from where the fleet stood as it turned home; `returnfleet:i:fleetId`).
   It is offered en route or on station, as long as that origin hive still holds a live colony
   (`returnOriginSystem`) - not only while the fleet still sits in a hive system, the old gate
   that vanished the button the moment the fleet reached hyperspace (user, 2026-09-06).
   **Escort takes the nearest fleet already out** (`ThreatFleetOrders.nearestReassignable`,
   `takeOver`, `adoptEscort`; user 2026-09-06: intercepts sit on one jump-point of two and do
   not guard the planet, and Escort was raising a new task force from Ice Wind Desert): a
   fleet on its way home, an intercept or escort elsewhere, a guard of another faction's
   colony, an expedition fleet in the open or a task force - in the world's system first
   (closest to it), then by hyperspace distance; staged guards over own colonies are not
   taken. The prompt names the fleet, its duty and its distance ("Detach X (besieging the
   Gamma Hero system, in the system) to clear the orbit of Y for 60 days?"). It goes home to
   the base it was already bound for. A task force is raised only when nothing is out.
3. **Hive systems in reach**: known infested systems within expedition reach of any of
   the faction's military worlds, with the nearest base and distance. Buttons:
   **Siege** (a full purge expedition from that base, drawing its reserve),
   **Intercept**.
   **Arrival** (`ThreatReturns.arrived`, 2026-09-06): home once within `ARRIVAL_RANGE` (350)
   past both hulls' radii, in the home's own location. Vanilla ends a GO_TO_LOCATION at the
   entity's edge and the fleet stops there, idle, while the colony orbits on - a detachment
   sat 46 days in Sun Wukong's system with an empty queue, the planet 7000 units away. So
   `ThreatReturns.onLeg` re-issues the leg whenever the fleet's current assignment is not it,
   for returns and convoys alike (`ThreatConvoys.poll`, front runs), and the row shows an ETA
   at `EST_LY_PER_DAY` plus a day in-system (`ThreatReturns.etaDays`).

Layout follows docs/intel-ui-platform.md: the selector's first button flows, the rest
sit `rightOfTop` of it and the flow height is put back to one row; every order button
is a floating `addGenericButton` anchored `belowRight` of its table panel and added
last. A button's tooltip is the one place its rule is written (`disableWith(main, button,
enabled, reason, help)`): one line saying what pressing it does while enabled, the reason
while disabled. Header tooltips are one line each and never explain colours or buttons.

## Fleet orders (ThreatFleetOrders)

Persistent list `threatinc_fleetOrders` of `Order { fleet, factionId, kind, baseMarketId,
targetId, targetName, issuedTimestamp, days }`. Every order is a real task force
(`guardFleetFP`, 100, for an NPC navy - since 2026-09-29 only its minimum: `ThreatFleetOrders.buildSortie`
sizes an NPC sortie to what it faces, in fleets of up to `softenFleetFP` folded into one up to
`softenMergeMaxShips`, stopping when the next fleet cannot be paid from the base's spendable stock;
the first must be paid in full or the sortie stays home, and it logs `sortie_unpaid`; the player's sail
with all the colony has free) built at the faction's nearest military world in reach and
provisioned from that base's reserve (fuel x distance, supplies), flagged
`$threatinc_ordered`, with vanilla assignments:

- **Guard**: `ORBIT_AGGRESSIVE` at the colony for `guardDays` (90), then home. Over one
  of the player's OWN colonies: `guardOwnDays` (0 = until recalled), and the task force is
  staged there - see "Staging fleets" below.
- **Hunt** (replaced Intercept 2026-09-24): `ORBIT_AGGRESSIVE` over the hive colony with
  the weakest standing garrison for `softenDays` (60), moving on as each garrison falls -
  see "Hunting forces". Intercept (the hive's jump-point, `interceptDays`, now removed) is
  no longer given; intercepts already out in a save run their term.
- **Support** (Escort until 2026-09-06): `ORBIT_AGGRESSIVE` over a besieged world this
  faction has a ground front on, for `supportDays` (60, key `threatinc_escortDays`) - it
  clears the orbit so front runs can land, and while on station its fleet points suppress
  the world's defences like any siege fleet's (`ThreatGroundFronts.tickSupport`,
  docs/ground-war-sieges.md "Sieges from orbit"). See "Support, and the refuse rule" below.
- **Defend** (2026-09-07): the same station for `defendDays` (60), but it bombards only
  while the faction's own front on the world cannot hold
  (`ThreatGroundFronts.defendBombards`) - Support that keeps its ships. An expedition's
  landing fleet takes an indefinite Defend by default (`adoptLandingDefend`,
  docs/ground-war-sieges.md "The landing fleet stays, on Defend").
- **Siege**: `IncursionManager.launchSiegeExpedition` from the nearest base, sized by
  `computeSiegeDifficulty`; postponed with a message when the base lacks marines.
- **Stage**: `ThreatConvoys.stageTo` - a convoy from the same-faction colony that can
  spare the most marines (or the most of anything) to the chosen colony, whether or not
  it is a staging base. What it carries (`stageLoad`, 2026-09-05 evening): the marines and
  heavy armaments the donor holds above its sortie floor
  (`ThreatReserves.available`) whatever the target already holds - the landing force is
  never "enough" (2026-09-29: no longer a hull load, every tier is capped only by what the
  donor holds above its floor and the run's hulls grow to carry it); fuel and supplies only up to what the target is short of its staging
  target (or its own cap when it is not a staging base). Before that, every commodity
  was capped at the target's staging target, so a base sitting at its target greyed the
  button with a reason that blamed the donors. Any distance (see "Ranges").
- **Recall**: task force `standDown`, expedition `abort`, convoy `returnHome` (cargo
  back to the donor), order fleet home.

Who may order: the player's own faction once mobilised, and nobody else's - NPC navies
are autonomous (docs/player-aid.md, 2026-09-05); their views offer the player's aid
instead. `ordersEnabled` turns the buttons off. Every player sortie is limited by the
sending colony's capacity ledger (`ThreatAidCapacity`).
All orders confirm first (`doesButtonHaveConfirmDialog`), with the cost and the base
named in the prompt.

## Returns - what comes back (ThreatReturns)

Recalled fleets, and fleets whose order ran out, come home on a tracked `GO_TO_LOCATION`
leg (persistent list `threatinc_fleetReturns`) instead of despawning. On arrival the
base gets back everything still physically aboard - marines, armaments, cargo - so
losses in transit are simply what did not return; plus `returnRefundMult` (0.5) of the
supplies drawn at launch, scaled by surviving strength (fleet points now over
fleet points at launch, remembered in the fleet's memory by `provision`). Fuel is settled
differently since 2026-09-29 (below); `returnRefundMult` now applies to supplies only. A fleet lost
on the way home returns nothing. Convoys recalled or whose destination fell carry their
cargo back the same way. A recalled (or never-landed) expedition refunds its undeployed
troops and armaments in full and its provisions at the refund rate when the intel ends
(`ThreatPurgeFGI.refundOnReturn`), scaled by route damage; a destroyed one refunds
nothing. Task forces that finish their attack naturally no longer despawn free (2026-09-29,
closed economy; they used to fall through to a queued despawning return, unsettled): the
response task force (`ThreatResponseIntel.advanceImpl`) polls `ThreatReturns.orderRanOut` - the
order queue is empty, or only the old despawning return is left, and the fleet is not already
on its tracked leg - and sends the fleet home (`sendFleetHome` -> `sendHome`) to settle like any
recall. `retarget` leaves fleets already on their tracked leg home alone.

**The hull share (2026-09-29, closed economy).** For an NPC fleet, `ThreatReturns.suppliesBack`
splits the supplies drawn at launch: `returnHullShare` (0.8) of it paid for the hulls and
comes back in full at the surviving strength (what comes home intact is not destroyed); only
the rest is the voyage, refunded at `returnRefundMult`. Losses are the real cost of a sortie.
The player's fleets keep the old rule for supplies, all of it at `returnRefundMult`.
**Fleet upkeep (2026-09-30, `ThreatUpkeep`, `fleetUpkeep` default on).** Every NPC fleet the layer
provisioned (`ThreatReturns.MEM_HOME`: hunts, sieges, sorties, convoys) burns its ships' vanilla
supplies per month (`ThreatFrontlines.maintenancePerMonth`, maintenance only - no repair or CR
recovery) for each day it is out, charged every 5 days from its base's stock, then the faction's
markets in reach (`payFromOthers`); what nobody can pay is owed on the fleet and asked again. A
forward base's garrison is left to its link's upkeep (`isGuard`). With it the whole launch draw
is the hulls' and comes back at the surviving strength (`suppliesBack`). The flat draw burned ~6
supplies per 25 FP a sortie whatever its time out, where vanilla ships burn ~1 per FP a month, and
the factions' supplies piled up (Persean 11k to 239k in 71 months). First test (17 months): the
Hegemony paid 58k and the Perseans 75k, all but ~150 of it; the Hegemony held 28k supplies at
month 15 against 50k without it. Vanilla's own patrols pay through their markets' demand, which
the reserves never bank; the player's and the Threat's fleets are not charged. A fleet that owes a
month of its upkeep goes home, once (`ThreatUpkeep.starve`, 2026-09-30): a hunt or sortie stands
down "out of supplies", a siege is called off (`ThreatPurgeFGI.outOfSupplies`, "Siege Out of
Supplies" notice); convoys and fleets already heading home run on. In h26a ~25% of upkeep went
unpaid while the fleets fought on; the first check sent 34-39 hunting fleets home in 15 months.
**Fuel is charged for the round trip (2026-09-29).** The passage is drawn at
`expeditionFuelPerPointLY` a point per light-year, about vanilla's burn there and back, in one pool.
`ThreatReturns.fuelBack` refunds only the return leg of the hulls lost from a fleet that comes home:
drawn x `RETURN_LEG_SHARE` (0.5) x (1 - health). The survivors burned their way home, so a fleet home
at full strength refunds nothing, and one destroyed outright refunds nothing (its fuel went down with
it); it was `returnRefundMult` x health, which sailed a whole flotilla home free. A fleet settled where
it stands, never flying home (`ThreatFleetOrders.fold`, the yards-built-short fold), gets the whole
return leg back (`fuelBack(..., flownHome=false)`). `ThreatPurgeFGI.refundOnReturn` works per fleet
through `KEY_SPAWN_FP` (fleet points at spawn against now); unburned ordnance and razing fuel are cargo
and come back in full with the fleets that return; the fuel pools that came home count by their
spawn share, so a fleet lost whole is not credited.
**NPC sieges settle on the real hulls (`ThreatPurgeFGI`, "the hull ledger", 2026-09-29).** The
launch draws supplies on an estimate (`expeditionSuppliesPerPoint` a point, a point =
`FP_PER_RESPONSE_DIFFICULTY` fleet points); vanilla builds each fleet from the faction's own sizes
with jitter. `spawnFleets` -> `settleLedger` compares the fleet points that really spawned with
what the expedition still held (route damage off): the shortfall is drawn from the base, then
the siege donors (`IncursionManager.siegeDraw`), and the excess is refunded. What still cannot be
paid for is pruned (`pruneOne`): warships first, one a pass, never a flagship or a civilian ship,
and a whole fleet only when no warship is left. After the settle the supplies ride each fleet as
a `ThreatReturns.provision` stamp (`suppliesDrawn` is zeroed), and each fleet refunds its own share
by the hull-share rule when it is home - not at group end (`refundOnReturn` no longer holds
them), so a fleet destroyed on the way home returns nothing. Fleets vanilla built but never
placed when the expedition ended first (`settleUnplaced`) re-bank in full with their landing.
Routes that never spawn, the player's expeditions and sieges from before the ledger
(`ledgerHome` null) stay on the estimate. Fuel and ordnance are still on the estimate.
Everything an NPC sends comes home on this leg now: convoy escorts (`ThreatConvoys.payEscort`
draws the voyage, `sendHome` returns the hulls), recalled garrisons over a link
(`ThreatFrontlines.recallGarrison`: the base re-banks what survived; a home that fell sends
them to the faction's nearest base) and scouting parties (`ScoutReturn`). A fleet built with
nothing drawn (`buildSortie`) does not sail: every NPC sortie is paid in full or stays home,
and an outpost that fails to be raised refunds its cost.

