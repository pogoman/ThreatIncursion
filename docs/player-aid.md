# Player aid - autonomous factions, the player as a participant

Written 2026-09-05 after the user saw Sindria's Guard button greyed out at low standing
and set the direction straight. **Built 2026-09-05, every section, untested in-game.**
Section 7 records the decisions; section 8 what the build did and what to verify.

## 1. The principle

> At no point should I be controlling what a faction does, regardless of my standing.
> They should be autonomous. What excites me is redirecting my personal resources -
> credits, fleets, resupply, marines - to wherever they are needed, at the front or to a
> struggling NPC colony, at my discretion. Aid to another faction earns reputation with
> it. Factions request help through the mission board, and allied factions help each
> other, the amount set by their standing.

Three consequences for what exists today:

- **NPC factions lose their order buttons.** Guard / Stage / Siege / Intercept / Supply /
  Outpost / Recall on an NPC faction, the Rally button, and the
  `threatinc_orderMinRelation` gate all go. A faction's own governors issue those
  (`ThreatConvoys.planLogistics`, `IncursionManager.tryStrikes`, `ThreatCoalition.tick`
  already do the autonomous versions). The player's OWN faction keeps every button - it
  is theirs.
- **The faction view becomes a window, not a console.** It still shows an NPC faction's
  colonies, reserves, fleets in flight and what it is short of - that is the intelligence
  the player acts on - but the Actions column offers *aid*, paid by the player.
- **Standing is earned by helping, not a prerequisite for it.** Sindria at -20 standing is
  exactly the colony a player might choose to save.

## 2. Player aid - the mechanics

Every aid is a physical fleet the player pays for, that can be lost on the way
(`ThreatRaiders` hunt it like any convoy) and that lands the way a convoy or sortie lands
today. Nothing new is invented on arrival; the new part is who pays and who gets credit.

| Aid | What the player sends | Arrival | Existing hook |
| --- | --- | --- | --- |
| **Defend** | A guard fleet, `aidGuardDays` (90) at the target colony | Orbits aggressive, then sails home | `ThreatFleetOrders.dispatchGuard` with the player as payer |
| **Strike / intercept** | A task force to a hive system's jump-point or a siege flotilla with marines | As Intercept / Siege today | `dispatchIntercept`, `launchSiegeExpedition` |
| **Resupply** | Fuel, supplies, heavy armaments | Deposited in the colony's reserve AND applied as a trade modifier for `convoyLandingDays`, exactly as a convoy landing | `ThreatConvoys` landing code, `ThreatReserves.deposit` |
| **Reinforce** | Marines | As above; a struck colony's militia | same |

**Where the fleet comes from - decided 2026-09-05: the player's own colonies only.**
Every aid sent from the war board is launched from one of the player's colonies and is
built from what that colony can actually field and stock. No source colony in reach, no
aid from the board. Credits are still paid, but money never buys capability - a colony
that cannot field the freighters, or lacks the fuel, cannot send it however rich the
player is. The player's personal fleet is the other route (section 4): it hands goods
over at a colony in person and defends one by being there, its own holds and guns being
the gate.

**Which colony sends - decided 2026-09-05: no picker.** Among the player's colonies with
a military structure in reach of the target, the board takes the closest one that can
cover the whole need; if none can, the one that can send the most. The button tooltip
names it and the figure.

**Capability - what a source colony can send.** Three checks, all read from what the
game already computes for that colony, so the board can show the exact figures:

1. *Fleet capacity.* The colony's vanilla fleet-size stat (the "Fleet size 169%" line on
   the colony screen: colony size, doctrine, ship-hull shortage, stability -
   `Stats.COMBAT_FLEET_SIZE_MULT`, already wrapped by `ThreatColonyManager.fleetSizeMult`)
   times `aidBaseFP` (100) is the fleet points the colony can have at sea at once. Every
   aid fleet costs points: escort combat FP plus the hulls needed to carry the cargo
   (freighter / tanker / transport points at the convoy ratios of 60 cargo, 100 fuel and
   40 marines per point). Points come back when the fleet returns (`ThreatReturns`); a
   fleet that is lost frees them after `aidRebuildDays` (60). A colony without a military
   structure fields nothing, as for sorties today.
2. *Stock.* Cargo comes only from the colony's reserve (`ThreatReserves.available`, above
   the floor) - the banked surplus of what its industries produce. Marines are the militia
   baseline plus surplus. No purchase from the open market, nothing from colony storage.
3. *Reach.* `IncursionManager.expeditionRangeLY(market)` as every sortie does.

Ships are built by `FleetFactoryV3` with the source colony as `source` and
`ignoreMarketFleetSizeMult` set, so the FP shown on the board is what sails, and quality
comes from the colony (nanoforge, orbital works, stability - `Misc.getShipQuality`).
Cargo is loaded clamped to real hold space and only what was loaded is drawn, exactly as
`ThreatConvoys.dispatch` does. A colony's offer is `min(need, spare stock, hull space the
free points buy)` and the board shows all three so the player can see which one binds.

**Paying - revised 2026-09-05: no credits.** The first build charged the wallet per point
and per light-year (`aidCreditsPerFP` / `aidCreditsPerLY`) on top of the reserve draw and
the capacity hold. The user struck that: an aid fleet already pays three real prices - the
source colony's stock, its fleet capacity for the whole trip, and the risk of losing the
hulls - and the player's own-faction orders pay exactly those three and nothing else, so a
fourth price for the same fleet with a different destination was a double charge that fed
nothing downstream (standing is computed from the goods' value at the receiving market, not
from what was paid). The two knobs are gone, as is the wallet check on Defend / Aid /
Strike. The purge commission on the infested-system intel and the board's Purge button,
which shared the launch path and so also drew marines and held points, lost its fee the
same day (`commissionCostPerPoint` / `commissionCostPerLY` removed; `commissionEnabled`
stays). Credits now buy only what no reserve can: outposts (`outpostCredits`) and new
colonies. Goods are the colony's own surplus. Nothing is refunded on loss; a recalled or
returning fleet puts undelivered cargo back in the source reserve in full
(`returnRefundMult` applies to provisions only).

**Today's gating, for the record.** The purge expedition gates on real marine stock in
the source reserve (postponed and refunded when short, shrunk to what the marines can
staff), but its fleet sizes come from the target's difficulty, and vanilla's mission fleet
creator ignores the colony's fleet-size stat. Guard / Intercept use a flat `guardFleetFP`
(100) which vanilla silently multiplies by the base's fleet-size stat; convoys cap cargo
with flat constants and their holds are scaled the same way, so a weak colony already
ships less than planned. None of it reads the colony's capability on purpose - the gate
above is new, and should apply to the player's own-faction orders too.

## 3. Reputation

On arrival at an NPC colony, one `CustomRepImpact` with the receiving faction:

- Resupply / reinforce: `deliveredValueCredits / aidRepPerCredits` (default 1 point of
  relationship per 10,000 credits of goods at the receiving market's price), capped at
  `aidRepMaxPerDelivery` (+10). Value at the *receiving* market's price rewards taking
  fuel where it is short.
- Defend: `aidRepGuardArrived` (+3) on station, plus `aidRepGuardPerFightWon` (+2) per
  strike fleet it defeats there, plus `aidRepGuardCompleted` (+2) if it serves the full
  term. A guard that shows up and sees no action still counts for something.
- Strike / intercept at the front: reputation with every faction that has a colony in
  the hive's strike reach (`IncursionManager` already knows the reach map), split
  `aidRepFrontTotal` (+5) between them.
- Delivered to the player's own colony: no rep, obviously.

Vanilla's own `RepLevel` thresholds do the rest - a player who saves Sindria twice will
be Favourable and can dock there.

## 4. Requests for help - the mission board

`ThreatMissionIntel` today scores hive infrastructure links and offers strikes. Two new
objective kinds join it, scored on the same board and capped by the same
`maxPostedMissions`. They are vanilla missions in every way (decided 2026-09-05):
posted in the asking faction's name, accepted from the intel screen, expired unaccepted
on `missionPostingDays` with no penalty, and once accepted **failed** if not completed
within their term or if the target colony is destroyed - `RepActions.MISSION_FAILURE`
with the asking faction, as `ThreatMissionIntel` already does for an abandoned strike.

The player completes either kind in person or by sending a colony fleet from the board;
both count toward the same contract, and sending board aid to a colony with an open
request accepts it.

- **Defend X for `missionDefendDays` (60).** Posted when a Threat strike is staged or in
  flight against a colony and the faction is outmatched: the strike's strength estimate
  exceeds the defenders' (`WarSimScript.getFactionStrength` at the system plus the
  station) times `defendRequestRatio` (1.0). The contract is a window: it completes at
  the end of the term if no Threat action landed on the colony (no raid, bombardment or
  ground assault - the strike was stopped in space) and a player asset was in the system
  when each strike arrived: the player's fleet, or a guard sent from a player colony. It
  fails the moment an action lands, or the colony is destroyed. If no strike came at all
  in the term, or the faction beat it with no player asset present, the contract ends
  unpaid with no penalty - nothing was owed. A guard sent for a contract serves the
  contract's term. Reward: credits `missionDefendCredits` scaled by colony size, plus
  the reputation in section 3 doubled (`missionRepMult` 2.0) - asked-for help is worth
  more.
- **Aid X with N of C.** Posted when a colony's depot is exhausted for C (the
  `CommodityStatus.exhausted` flag from `ThreatReserves.status`) or its vanilla deficit
  has stood for `missionAidShortageDays` (60). N is the deficit in items. Delivered by
  any mix of colony resupply landings and personal hand-overs; the contract keeps a
  running total and completes when it reaches N within the term. Reward: the goods'
  value at the receiving market's price times `missionAidPayMult` (1.5) - the faction
  pays a premium for delivery - plus section 3's reputation doubled.

**Handing over in person.** At the receiving colony the station commander offers
"Deliver aid" (a rules.csv option on the market dialog, vanilla-only), listing what the
contract still needs and what the player's fleet holds. What is handed over leaves the
player's cargo, lands exactly as a convoy does (reserve deposit and trade modifier), and
earns section 3's reputation. The player's own holds are the gate - buying 20,000 fuel
and hauling it is legitimate; the tankers are the cost.

**Defending in person.** The player's fleet in the target system counts as the asset; no
docking needed. Board-sent guards count the same way.

## 5. Allies helping each other

`ThreatCoalition` already answers a siege call with intercepts. It grows into the
general case, driven by relationships between factions (`FactionAPI.getRelationship`):

- On the slow tick, every mobilised faction looks at every OTHER mobilised faction's
  colonies with a request-worthy need (the same triggers as section 4). For each, the
  would-be helper's willingness is its standing with the needy faction mapped through
  `allyAidByRel` (a curve: Hostile 0, Neutral 0, Favourable 0.25, Welcoming 0.5,
  Friendly 0.75, Cooperative 1.0), and its means are what `ThreatConvoys.pickDonor` says
  it can spare above its own keep fraction.
- Aid is a real convoy or guard, paid from the helper's reserves, crossing hyperspace
  where raiders can catch it. Willingness x means x `allyAidChance` (0.5 per 30 days per
  need) decides whether one sails; the amount is the need, capped by means. A convoy
  landing at an ally's colony deposits in the *receiving* faction's reserve and the
  landing trade modifier applies there.
- Factions below Favourable never help each other; the player is never a helper here (the
  player helps by choice, section 2) but IS a possible recipient: an ally will send a
  convoy to a struck player colony, which is the first time the sector helps the player
  back.
- The war board logs each one ("Hegemony sends 1,500 fuel to Sindria - Cooperative") so
  the player can see the sector's alliances at work.

## 6. What goes, what stays

| Today | After |
| --- | --- |
| Guard/Stage/Siege/Intercept/Supply/Outpost/Recall on NPC factions | Removed; replaced by Defend / Resupply / Reinforce / Strike aid buttons paid by the player |
| Same buttons on the player's faction | Stay |
| Rally (batched allied orders) | Removed; section 5 does it autonomously |
| `threatinc_orderMinRelation` | Removed; `aidMinRelation` only refuses aid to factions at war with the player |
| Coalition intercepts on siege calls | Stay, folded into section 5's willingness curve |
| Convoys between a faction's own colonies | Stay, unchanged |

## 7. Decided and still open

Decided 2026-09-05: own colonies only for board aid, auto-picked source (closest that
covers the need), nothing from the personal fleet via the board; the personal fleet
completes missions in person; missions are vanilla missions (accept, expire, fail on
term or colony loss); defence contracts are windows with an outmatched trigger.

Still needs a yes:

1. Picking the amount for board aid. The intel UI has no numeric input. Recommend: the
   button sends `min(need, spare, hull space)`, need being the request's N or the
   colony's deficit from `CommodityStatus`; the tooltip shows the figure and which limit
   binds.
2. `aidBaseFP` (100 FP at "100% fleet size") and `aidRebuildDays` (60) as defaults.
3. Whether the vanilla stat is the whole gate (recommended - the ship-hull shortage and
   stability lines already cover disrupted heavy industry) or heavy industry / a patrol
   HQ is a hard requirement on top.
4. Apply the same capacity ledger to the player's existing own-faction orders (Guard,
   Intercept, Supply, purge expedition) so they are not a way around it. For a purge
   whose target needs more than the colony can field: send what you can (the marine rule
   today) or refuse. Recommend send-what-you-can with the board saying so.
5. Recipient standing: aid to a faction at war with the player (`aidMinRelation`,
   default the hostile threshold) is refused - their patrols would shoot the convoy.
   Inhospitable Sindria at -20 still qualifies.
6. Defence contract: the "player asset present when the strike arrives" rule, and
   ending unpaid (no penalty) when no strike came or the faction managed alone.
   Alternative: pay half for a term served with no action.
7. Defence trigger `defendRequestRatio` (1.0), and whether a colony already under a
   contract can post again when a second strike stages (recommend no, one at a time).
8. Failing costs standing (vanilla `MISSION_FAILURE`). Does losing the colony, which
   may not be the player's fault, carry the same penalty? Recommend yes, keep it simple.
9. Guard size: `min(guardFleetFP, free points)`, at least `aidGuardMinFP` (50), else the
   button is disabled with the reason.
10. Reputation numbers in section 3 as defaults (all knobs).
11. The ally curve in section 5 and that the player can be a recipient.
12. Unmobilised factions: recommend mobilised only for ally aid.

Build order once approved: the capability ledger and auto-pick, then Defend and Resupply
from the player's colonies (that is Sindria), the mission kinds with in-person hand-over,
section 3, then 5, then the removals in 6 last so nothing is lost before its replacement
works.

## 8. Built - what, where, and what to verify

Built 2026-09-05 in one pass, compiled clean, not yet run. Classes (see
`docs/code-map.md`): `ThreatAidCapacity` (ledger), `ThreatAid` (board aid),
`ThreatAidRequests` (needs and posting), `ThreatAidMissionIntel` (the two request
kinds), `ThreatincAidCMD` + `rules.csv` (hand-over). Changed: `ThreatFleetOrders`
(player-only orders, aid flag, arrival hooks, ledger), `ThreatConvoys` (recipient
faction, aid flag, ledger fit, tracked return for player convoys), `ThreatReturns`
(ledger release), `IncursionManager` (poll/tick wiring, purge fitted and committed),
`ThreatStrikeFGI` (strike-landed hook), `ThreatCoalition` (ally aid; Rally gone),
`ThreatFactionView` (NPC views show Defend / Aid / Strike and the player's aid rows;
own view gated by the ledger), `ThreatWarBoard` (Rally button gone), config and Luna
settings (`threatinc_aid*`, `threatinc_missionDefend*`, `threatinc_defendRequestRatio`,
`threatinc_missionAid*`, `threatinc_missionRepMult`, `threatinc_allyAid*`;
`threatinc_orderMinRelation` removed).

Where the build departs from the text above, on purpose:

- Requests have their own cap, `aidRequestMaxPosted` (3), rather than sharing
  `maxPostedMissions` with the strike contracts and their two-tier logic.
- `aidRepGuardPerFightWon` was not built - there is no clean hook for "this guard won
  that fight". A guard earns on arrival and on serving its term.
- The station commander's hand-over is offered only while a request for goods stands
  at the colony (the market memory flag `$threatinc_aidRequest`), not for unsolicited
  gifts. Board aid to a colony with nothing short is refused too.
- NPC fleets keep vanilla's fleet-size scaling (their navy is their colony); only
  player-launched fleets are built at exactly the ledger's points.
- A player purge expedition that still exceeds the free points after trimming to two
  fleets of minimum difficulty sails anyway, and the colony is over-extended until it
  returns (section 7, item 4).
- The Strike aid button lives in each NPC faction's view, on its hives-in-reach rows;
  standing goes to every faction with a colony in the hive's strike reach.
- `aidBaseFP` is 100 as approved. Vanilla's own patrols at a Military Base add up to
  roughly two to three times that, so the knob may want raising once it is felt.

To verify in-game, in this order:

1. Own faction view: Guard / Stage buttons and their reasons; the colony tooltip's
   "Fleet capacity" line; a Guard commits points and they return when it is home.
2. Sindria (or any mobilised NPC faction) view: no order buttons; Defend and Aid on
   colony rows with prices in the tooltip; Strike on hive rows; the aid fleet appears
   under "Fleets in flight, and your aid to them" and can be recalled.
3. A resupply lands: reserve deposit, trade modifier on the colony screen, the standing
   message, the request (if open) accepted and credited.
4. A request appears on the intel screen for a struck NPC colony; accept; fail it by
   letting the strike land, or complete it by parking the player fleet there.
5. Dock at a colony with an open delivery request: "Deliver aid for the war effort" on
   the main menu; hand over; the contract progresses.
6. Over a few ticks with two mobilised NPC factions at Favourable or better: an ally's
   guard or convoy appears in the log ("... sends ... for ...").
