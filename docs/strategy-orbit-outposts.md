# Strategy layer - staging fleets, Support and the refuse rule, escalation, coalition, outposts, relief

Split out of `strategy-layer.md` on 2026-10-01. One-line answers: `facts.md`.

## Staging fleets, and the fleet-point gate (2026-09-05, untested)

Seen in-game the same day: five Siege orders sailed 1,025 FP from a colony with 239 FP of
capacity (each sailed as the "minimum viable expedition, over-extended"), and five Guards
sent to that colony added nothing to its FP, so there was no way to bring the other
colonies' points to the staging base. Two rules replace that:

**No order over-extends a colony.** `IncursionManager.siegeBlockReason` and
`launchSiegeExpedition` refuse a player siege whose flotilla, after
`ThreatAidCapacity.fitExpedition` trims it (fleets dropped to two, then shrunk to
`responseMinDifficulty`), still holds more points than the base has free - the button is
greyed with "X has N FP free; the smallest expedition needs M." (The purge commission on
the infested-system intel and the board's Purge button, which did not apply this gate to
their button, were removed 2026-09-05 evening - one way to raise a siege, one gate.) `siegeSizes` is the one place the flotilla is
computed; the Siege prompt quotes fleets, points held, points free, marines wanted and
marines the base can commit. Guard / Intercept / Escort already refused below
`aidGuardMinFP`.

**A guard over your own colony is staging** (`ThreatFleetOrders.stagedAt`,
`ThreatAidCapacity.stagedFP` / `freeFP` / `commitSortie` / `foldStaged`,
`ThreatFleetOrders.fold` / `restation`):

- The task force stays `guardOwnDays` (default 0 = until recalled; the fleets table shows
  "on station" and no term). Its points stay charged to the colony that sent it. From the
  moment it is on station (`Order.arrived`, within `ORBIT_HOLD_RANGE`) they count in the
  host's pool at the fleet's LIVE strength (`ThreatAidCapacity.liveFP`, vanilla's fleet
  points of the ships it has - the fleets table's Strength; a fleet that lost ships on the
  way counts what it has, not what its sender was charged): `freeFP(host)` = own free
  points + staged live points, and that is the FP cell and what sorties from the host are
  sized by. `describe` (the row tooltip) is one line per fact with the sum written out:
  capacity, in task forces, at home; on station here, per fleet with its sender; free =
  at home + on station. A colony guarding itself shows its own fleet on both lines (in
  task forces AND on station here): the ships are real and in orbit, so they can fold
  into the next sortie like anyone's.
- A combat sortie from the host - Siege, Intercept, Escort, a Guard elsewhere, aid
  Defend / Strike - uses the host's own free points first, then folds staged task forces
  in until it is covered: the smallest one that covers what is left, else the largest,
  whole fleets (the last may overshoot). A folded fleet's order ends, what it carries and
  drew is settled at the host (`ThreatReturns.settle`), it fades out, and its ledger entry
  rides the sortie (`Commitment.fleet` / `group` re-pointed, `hostMarketId` set,
  `foldedFP` = its live points at that moment; the sender's charge `fp` stays what sailed
  from it).
- An expedition's ledger entries - the base's own points and every folded task force - are
  split among its fleets by spawned strength the moment they are all real
  (`ThreatAidCapacity.splitGroup`, from `ThreatPurgeFGI.noteSpawnFP`); from then on each
  fleet carries its own share, so a fleet recalled, detached to Intercept or lost settles
  alone (a loss starts that share's rebuild clock). Only an expedition that ends before it
  ever became real settles as a group (`poll`, at the route's damage, folded ships as a
  fresh guard over the host).
- When a fleet is home (`ThreatReturns.settle` -> `ThreatAidCapacity.release`) and any
  entry it holds was folded in at that colony, the fleet STAYS: the same hulls go on
  station as a guard of the host (`ThreatFleetOrders.restation`), every entry it holds
  re-labelled to that guard and still charged to its sender, Recall taking it home to the
  sender charged most; the row tooltip lists every sender. Otherwise its entries are
  released and the fleet dissolves. Below `aidGuardMinFP` nothing is kept. (Until
  2026-09-06 a fresh task force was built at the host the moment the sortie *ended* - the
  whole group's folded entries at once - while the real ships were still sailing home, and
  those dissolved on arrival with nothing to hand back: one recalled fleet produced three
  "back on station over Sun Wukong" messages and left the intercept fleets' ships unbanked.)
- A colony guarding itself is its own ships made real: `ThreatAid.pickTaskForceSource(loc,
  self)` sizes the colony by its OWN free points (so a colony with points free guards
  itself before a neighbour is asked), and a self-guard commits without folding. Net zero
  on its pool; it is how "guard myself with my FP" works, and it is absorbed by the next
  sortie like any staged guard.
- Convoys never fold warships in: `quoteResupply`, `stageLoad` and `dispatch` size by
  `ownFreeFP`. NPC guards, relief guards and allied guards are unchanged (not on the
  ledger, `guardDays`).

**Ranges (the user's call, 2026-09-05 evening).** "Too many variables to model - a fuel
world with enough tankers can cross the sector; distance should cost time, not
permission." So no range gate on the player's hand orders: `ThreatAid.sources` (the
source of every Guard / Intercept / Escort / Defend / Aid / Strike), `ThreatConvoys.
stageDonor` (Stage), `ThreatFleetOrders.pickBase` for the player (Supply / Pull out
runs, purged worlds for Outpost) and the faction view's `nearestBase` (the Siege base,
and which hives are listed - the player's table is headed "Known hive systems" and
lists every known one). What distance costs: fuel drawn at launch by points x
light-years (`expeditionFuelPerPointLY`, best-effort), the real transit each way, and
the capacity held for all of it. NPC navies and NPC factions' automatic traffic (the
convoy planner's donor reach in `pickDonor` / `stagingBaseFor` / `planRelief` /
`pickAllyDonor` - `convoyRangeLY` then, since 2026-10-01 as far as the donor's fuel pays,
"Logistics reach" - and `stagingHive`) keep their ranges: autonomy needs bounds, and a base
is still "the nearest base" to its hive. The player's faction has no range anywhere,
hand order or planner (2026-09-05 evening: Diggers, a fuel world 20 ly out, showed a dash
in Convoys while Supplies could sail from it): its planner's donors feed the staging base
from any distance, and its staging pick (`stagingHive` for a player base) follows the
Siege button, the nearest KNOWN hive at any range. The fleets table's Fleet column names
the colony a task force sailed from after the fleet's name: "Task Force (Earth)".

To verify: Fleet on Sun Wukong from Earth; the prompt's figure is Earth's FP cell (not
five-sixths of it); when the fleets table says "on station", Sun Wukong's FP cell reads
(its own at home + the fleet's Strength) / its capacity and its tooltip's last line adds
up to the cell, Earth's stays down by what the factory built; Siege from Sun Wukong now
sails (the Earth guard fades out, "folds into" in the log with live and charged points);
when the expedition ends, "back on station over Sun Wukong" and the guard row is back
with Earth as its Recall home. The Convoys column never names a hive; the row tooltip
names one you have found, or none.

## Support, and the refuse rule (2026-09-05, renamed from Escort 2026-09-06, untested)

A contested orbit is a closed door, not a queue. **Support** (`ThreatFleetOrders.
dispatchSupport`, `KIND_SUPPORT` - whose persisted value stays `"escort"` so old saves keep
their orders) is a combat sortie modelled on Guard: a task force built at the sender's best
base, `ORBIT_AGGRESSIVE` over the world's own planet for `supportDays` (60), then home on
the tracked return leg like every other sortie. On station over a hostile world whose orbit
nothing holds against it, it besieges: each poll `ThreatGroundFronts.tickSupport` delivers
its live fleet points as a siege slice and the batteries answer (docs/ground-war.md "Sieges
from orbit"). NPC size is what `buildSortie` sizes it to (`guardFleetFP` is its minimum, 2026-09-29); a player sortie is sized by
`ThreatAid.taskForceFP(base)` and held on the capacity ledger, exactly as Guard is. Recall
is the ordinary order Recall. Knobs `threatinc_escortEnabled` / `threatinc_escortDays` (the
keys keep the old name).

**Defend** (2026-09-07, `KIND_DEFEND`, `dispatchDefend` / `adoptDefend`, knobs
`threatinc_defendEnabled` / `threatinc_defendDays`) is Support's sibling through the same
code (`dispatchOrbit` / `adoptOrbit`, the kind a parameter): the same task force, the same
orbit, the same Recall, but `tickSupport` slices for it only while
`ThreatGroundFronts.defendBombards` - the faction's own front on the world cannot hold
(`effectiveStrength < holdRequirement`) and the fortifications haven't worn out. With
no front, or one that holds, it just holds the orbit and pays the batteries nothing. It
counts as a friendly orbit for the refuse rule. `nearestReassignable(faction, hive, kind)`
skips a fleet already on that kind over that world, so Support over a world with a Defend
fleet takes it and vice versa - the two buttons swap a fleet's doctrine; a landing's
indefinite Defend is never the pick for some OTHER world (its marines would be left bare). An
expedition's landing fleet takes an indefinite Defend (`adoptLandingDefend`; `poll` ends it
when the front is gone or `ThreatReturns.health` falls below `defendMinStrength`).

**Refused, not queued** (`ThreatConvoys.canRunTo(faction, hive)`, returning the reason
or null): while `ThreatGroundFronts.orbitContested(hiveId)` and no friendly combat
fleet holds the orbit, `supplyFront` and `pullOutFront` do not dispatch at all, and
the buttons are disabled with **"Orbit contested - send Support or Defend first."** The
existing in-flight jump-point wait (`frontRunWaitDays`) stays as the fallback for runs
already at sea when the orbit closes behind them.
`ThreatFleetOrders.friendlyOrbit(factionId, hiveMarketId)` is what "holds the orbit"
means: a live Support or Guard order fleet of that faction within `ORBIT_HOLD_RANGE`
(1,500 units) of the planet, in its system - or, for the player's faction, the player's
own fleet sitting there. `hasSupport(factionId, hiveMarketId)` is the one-at-a-time gate.

**NPC parity**: `planFrontRuns` checks `canRunTo` before anything else. Refused, the
faction dispatches Support to that world instead (one per world at a time,
provisioned from the base reserve like any sortie), and next tick's run goes in. A front with `withdrawRequested` on a contested
orbit gets the same Support before its pickup.

**Purge landings** (`ThreatPurgeFGI.doCustomRaidAction`) obey the same ground truth:
no landing while `falloutDaysLeft(market) > 0`, and - only for live fleets, since
autoresolve has no orbit to contest - none while `orbitContested`. A blocked pass
falls through to a commando raid (the tactical branch above it has already taken the
pass if softening still helps) and logs why. And if a front of the SAME faction
already stands on the world, the expedition **reinforces** it - `ThreatGroundFronts.
resupply` with what `unloadForLanding` gives - instead of raiding, recorded in the
sitrep as "Reinforced ground front".

**Escalation** (`ThreatAlarm`, docs/design-theory.md 8.1): grudge per faction
(+`alarmPerStratum`, +`alarmPerEradication`, +`alarmPerRaid` for raids and tactical
passes, NPC and player alike), alarm = the sum, decaying `alarmDecayPer30`. Grudge
multiplies a faction's worlds' strike weight by
`1 + grudge x alarmTargetMult`; a ground victory calls `IncursionManager.retaliate`,
which launches a normal strike (same phase, cap, garrison and reach rules) from the
nearest hive that can reach a world of the winner. Header shows "Alarm N".
(2026-09-29, closed economy: the alarm no longer speeds fabrication. It used to divide every
Swarm Nexus respawn interval by `1 + alarm x alarmTempoMult`, capped at `alarmTempoMax`; the
respawn interval (`garrisonRespawnDays`) is gone with the FP bank - see "Fabrication bank" in
docs/hive-economy.md - and both alarm knobs and `ThreatAlarm.tempoMult` are deleted from code and
config. The header read "Alarm N - fabrication xM"; its tooltip listed the formula, now each
faction's grudge and strike-weight multiplier.)

**Strike target weight** (`pickStrikeTarget`, 2026-09-29): a world's weight = `IncursionManager.strikeValue`
(size squared, or the outpost link weight, x the grudge multiplier; factored out of the loop, behaviour
unchanged) x `strikeReinforceWeight` for a dry front of the swarm's own x
`ThreatStance.strikeTargetMult(market, source, odds)`, with odds = defence / (strike x
`siegeBreakOffRatio`). The stance multiplier: EXPAND 1. PRESS max(0.05, 1 - odds), x10 for the world the
stance picked for that source system. CONSOLIDATE 0, except a world at odds <= `stanceWeakOdds` that is
an outpost or a base staging against a hive: max(0.05, 1 - odds). The relief pick (a dry front of its own)
is filled before the stance multiplier and not affected by it. docs/hive-garrison-and-upkeep.md "Stance".

**Also**: reserve floor (`reserveFloorFraction` for NPC colonies, `playerReserveFloorFraction`
for the player's, default 0) and militia trickle (`reserveBaselinePerSize`); "send what you can" trims a short expedition's flotilla
instead of postponing; `groundStrengthExponent` (1.0) on every ground ratio.

**Coalition** (`ThreatCoalition`, 8.7): `launchSiegeExpedition` by a mobilised faction
posts a call for `coalitionCallDays` (60); on the slow tick every other mobilised NPC
faction with a base in reach rolls `coalitionSupportChance` (0.5) and answers once with
a pooled hunting force (`ThreatSoftening.send`, 2026-09-25: sized, pooled and mustered
like any hunting force, bounty or not). A faction already hunting there has answered; one
whose base is resting, has a siege of its own, or cannot pay yet tries again while the
call stands. Until 2026-09-25 the answer was one `guardFleetFP` (100 FP) Hunt
(`ThreatFleetOrders.dispatchHunt`, still the path with `softenEnabled` off) - lone fleets
that met 171 FP swarms at 137 FP; an odds gate on it cut answers from 53 to 3 and was
reverted. Intercept at the jump-point until 2026-09-24; no answer when the system has no
Defense Swarms left. Rally (the player batching
allied orders) was removed 2026-09-05 with the rest of the player's authority over NPC
navies; allies now help each other's colonies on their own, by standing
(`ThreatCoalition.allyAid`, docs/player-aid.md section 5). Hunt orders show as inbound
"X hunt" ops on the ledger row.

**Outposts** (`ThreatOutposts`): `ThreatColonyManager.eradicate` records the planet in
`threatinc_purgedWorlds`. An outpost is vanilla's Orbital Station recipe without the
market - a hidden station-mode fleet holding the tier's variant (`outpostTier`, spec
`orbitalstation`/`battlestation`/`starfortress` + the faction's style suffix, read from
its own colonies' station line, else Hegemony/Luddics low-tech, Tri-Tachyon high-tech,
others midline) tied to a `station_built_from_industry` entity orbiting the planet. While
its fleet lives, `checkWaveArrivals` refuses to found a colony there (the wave stays and
fights). Player pays `outpostCredits` (150,000); an NPC base pays `outpostSupplies` /
`outpostFuel` above its floor, and mobilised NPC factions fortify one open purged world in
reach per tick with `outpostChance` (0.3). Faction view: a "Purged worlds in reach" table
with an **Outpost** button. A dead station is a lost outpost (fast poll). Until 2026-09-06
outposts also sat in the fleets table with a Recall that scuttled them - see "Storage and
orders" below for what replaced that.

**Free on a ground victory - 2026-09-05, REMOVED 2026-09-27 (user's call: survivors come
home; nothing is raised on the freed world, player or NPC).** `ThreatGroundFronts.groundVictory`
now raises the outpost itself, for the winning faction (the player's own faction for a
player front), at no cost - `ThreatOutposts.buildFree`, which skips the credit/reserve draw
and the paying-base check entirely: the fleet that won the siege is already in orbit, so
holding what it took costs nothing more. It runs AFTER `eradicate` (the world has to be
market-less before `eligible` passes) and is skipped when an outpost already stands there.
`buildFree` and the knob `threatinc_outpostOnVictory` are gone.

**Stockpile - 2026-09-05, untested.** An outpost is a BASE. Its stock is an ordinary
`ThreatReserves` entry keyed by the station ENTITY id instead of a market id, so every
existing `deposit(id, ...)` / `draw(id, ...)` works on it unchanged. What differs is that
it has no floor, no accrual and no War footing: it banks nothing on its own, so everything
in it was carried or won there and all of it is available. `ThreatBases` is the handle -
`Base` resolves a reserve key to a colony or an outpost and offers `id/name/factionId/
entity/hyperLoc/starSystem/sourceMarket/isOutpost`, plus `available` (floor for a colony,
zero for an outpost), `draw` and `deposit`. The stock dies with the station
(`ThreatOutposts.remove` clears it) and comes ashore on `carryOver` - it is moved into the
new colony's reserve before the outpost record goes.

**Survivors garrison it** (only an outpost already standing there, since 2026-09-27).
`groundVictory` passes the outpost to `evacuate`: with an
outpost over the dead world, BOTH player and NPC survivors (marines and armaments) are
deposited into its stockpile instead of being lifted off - one message for the player's own
front. With no outpost the old behaviour stands (player fleet cargo / nearest base reserve),
and the `poll()` path where the market simply vanished still uses it. The market's entity
and hyperspace position are captured before `eradicate` runs, since `decivilize` is what
takes them away.

**Front runs out of an outpost.** `ThreatConvoys.pickFrontBase` prefers an outpost of the
faction in the HIVE'S OWN SYSTEM over `ThreatFleetOrders.pickBase`: for a resupply when it
can cover a worthwhile load (>= 50 marines or >= 20 armaments of what is wanted), for a
pickup always - the front lands in the station next door rather than shipping home.
`dispatchFrontRun` takes a `ThreatBases.Base`, spawns the fleet at the station entity, and
builds it with a null `FleetParamsV3` source market (an outpost has no economy, so no
quality or fleet-size scaling). `ThreatReturns.sendHome/poll/settle` resolve the home
through `ThreatBases` too, so the run comes back to the station and unloads into its
stockpile. An outpost that cannot cover the run falls through to the nearest colony,
unchanged. Once the outpost's system holds no hive its stock ships home:
`planOutpostReturns` (in `planLogistics`, after front runs and relief) sends the whole stock at once in as many fleets as it needs (2026-09-29; it was one hull load per convoy, one convoy at a time; the fleets grow to `maxShipsInAIFleet`, then split as above), from the station to the faction's nearest base
(`dispatch` takes a `ThreatBases.Base` donor), so a ground victory's survivors return to
the war instead of sitting in a station nothing can draw from. The faction view's reserves
table lists each outpost's stock as a row of its own (no floor, no accrual, grey where
empty), so the Total row sums exactly what is shown; since 2026-09-06 that row carries the
Supplies and Fleet buttons too ("Storage and orders" above).

**Relief (2026-09-05, untested).** A Threat front on a faction's own world is answered on
the slow tick: `ThreatFleetOrders.planRelief` puts a Guard task force over it (NPC navies;
the player orders Guard by hand), one per world, sized to the swarm over it with no fleet
cap (`sendRelief`), and `ThreatConvoys.planRelief` sends the marines the counter-attack needs
(`ThreatGroundFronts.reliefNeed`, net of what is at sea) from every colony in reach that can
spare them, richest first, in parallel (2026-09-29; it was one convoy from one donor), ahead of
every depot (the player's mobilised faction too). The besieged colony's own banked marines are
committed while the front stands (`ThreatReserves.committed`, honoured by `available` and
`ThreatConvoys.spare`): they defend, they do not ship.

**Any uncolonised world - 2026-09-05.** The player can raise an outpost over ANY planet
that holds no live market (`ThreatOutposts.eligible`: not a star, in a star system, no
colony, no standing outpost), from the planet's own dialog: "Build an outpost" appears
between the survey options while one of the player's military colonies is within
expedition reach (`ThreatincOutpostCMD`, rules.csv `threatincOutpost*`), with a brief and
a Confirm disabled when the credits are short. The board's table stays the purged-world
shortlist, and NPC factions still fortify purged worlds only. The dialog does not require
the player's faction to be mobilised (the board's button does, like every board button):
credits, not the reserve, pay for it.

What an outpost is NOT, for the record (decided 2026-09-05): it has no colony and no
economy - it produces nothing. It is a makeshift structure: it cannot be upgraded (the tier
is fixed at build by `outpostTier`; a real upgrade would need a colony's yards) and no
industry, Waystation included, can be built on it. Making outposts real size-1 markets was
considered and rejected - a market would let the player build Patrol HQ, Heavy Industry and
the rest at what is supposed to be a station in orbit. The storage-only market of
2026-09-06 (below) is not that: size 0, neutral, never in the economy, one Storage
submarket and nothing to build.

**Storage and orders - 2026-09-06, untested.** The user's outpost (won by ground victory,
2,353 marines aboard) showed on the board as a row in the fleets table with a Recall that
scuttled it, and no way to supply it, send a fleet to it, or see what it held. Decided:

- **The station is its own depot.** `ThreatReserves.hasDepot(ThreatBases.Base)` is true
  for a living outpost with no Waystation asked - a station in orbit is the logistics
  structure. So the board's **Supplies** and **Fleet** buttons sit on the player's outpost
  rows exactly as on colony rows: `ThreatConvoys.stageTo/stageDonor/stageLoad` and
  `ThreatFleetOrders.dispatchGuard` take a `ThreatBases.Base` target (the `MarketAPI`
  overloads delegate), the button payload is the reserve key (`ThreatBases.of(id)` resolves
  either kind), `Convoy.toMarketId` and `Order.targetId` may now be a station entity id, and
  `ThreatConvoys.poll/arrived/boundFor`, `ThreatFleetOrders.atStation` and
  `ThreatRaiders.consider` resolve them through `ThreatBases`. A hand-ordered convoy to an
  outpost carries, by the load ladder (`stageLoad`; 2026-09-29, it was a hull load of everything
  the donor can spare), Min = a reference load (`convoyMarineCapacity` / `convoyCargoCapacity`),
  Med = that x `convoyExtraLoadFactor`, Max = everything available above the donor's floor,
  fuel and supplies included (no staging target - nothing there is "short"); a player donor's
  is fitted to its free FP. A guard over an own outpost is an
  own guard: `guardOwnDays` (0 = until recalled), points on the source's ledger as a sortie;
  it does not fold into any host (an outpost has no capacity ledger) and it is excluded from
  the reassignable list like a staged guard. The row's Convoys cell says "Forward base"
  while the outpost's system holds a hive, else "to <home> N ly" - where
  `planOutpostReturns` ships its stock (`ThreatConvoys.outpostHome`).
- **The player's stockpile is a real cargo.** `ThreatOutposts.ensureStorage` gives a
  player outpost vanilla's abandoned-station recipe on the station entity: a neutral size-0
  market, never added to the economy, with one Storage submarket already paid for. On
  `raise` for a player outpost, and on the fast poll for outposts from older saves.
  `ThreatReserves.backing(id)` returns that storage cargo for an outpost key, so `stock/
  deposit/draw` on the outpost ARE the storage (the same rule that makes a player colony's
  reserve its resource stockpile); ledger stock is moved into the cargo the first time it is
  asked for. NPC outposts stay on the ledger. `remove` detaches the market before deleting
  the entity.
- **Docking at the station** opens `ThreatOutpostDialog` (picked by
  `ThreatIncCampaignPlugin`, registered transient on load): the station's fleet panel, one
  line of what the storage holds, **Open the storage** (vanilla's cargo screen in OPEN
  trade mode on the station - take the marines, leave the fuel), **Decommission** with a
  prompt that says what is lost, and Leave. The planet's reminder line points at it.
- **Gone:** the outpost row in the fleets table and its Recall; `recall("outpost:i")`.

**Carry-over on colonisation (built 2026-09-05, untested).** When the world under an
outpost becomes a live colony of the outpost's own faction, the fast poll
(`ThreatOutposts.carryOver`) strikes the makeshift station and adds the same station line
(`o.specId`, e.g. `orbitalstation_mid`) to the new market as a built industry - the tier-1
outpost inherited into the colony's Orbital Station slot, from where vanilla upgrades it
to Battlestation and Star Fortress. A colony that somehow already has a station keeps it
and the outpost is simply struck. A colony of another faction leaves the station standing
in orbit unchanged.

