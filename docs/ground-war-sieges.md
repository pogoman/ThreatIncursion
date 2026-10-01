# Ground war - Threat assaults, bombardment, saturation, off-screen fights

Split out of `ground-war.md` on 2026-10-01. One-line answers: `facts.md`.

## Threat ground assaults - the rule is symmetric

Built 2026-09-05, untested in-game. **The swarm no longer erases worlds from orbit.**
Saturation bombardment is gone from Threat strikes (knob `strikeSaturationEnabled`,
default FALSE; on, since 2026-09-28 the swarm saturates by the razing bar like anyone). A strike now does to an inhabited
world exactly what a purge expedition does to a hive - and for the same reason: *only a
ground victory kills a colony, in either direction.*

### The doctrine: besiege, land, reinforce (reworked 2026-09-06, untested)

`launchStrike` leaves `raidParams.bombardment` null - that is what routes each pass into
`ThreatStrikeFGI` instead of vanilla's bombardment path - budgets `expeditionPasses` (fleets +
2) passes per world in the sweep (2026-09-29: `strikePassesPerColony`, 3, and the purge's
`siegePassesPerColony`, 4, are gone - they left the cargo of every fleet past the third or
fourth aboard; vanilla's autoresolve loops `raidsPerColony`, so the count is finite but
scales with the fleets), and gives every world in the sweep `siegeOrbitDays`
(120) of siege (vanilla re-times the payload stage per world once the fleets spawn, so
the knob is written to `maxDurationIfSpawnedFleets*` too). The landing questions are the
engine's, shared with the purge
(`ThreatGroundFronts.landingBlocked`, `landOrReinforce`), so the two doctrines cannot
drift apart.

1. **Besiege.** A live fleet that reaches a world not yet ready to be landed on delivers a
   *slice* of the orbital siege instead of a pass (`AnnihilationAction.performRaid` ->
   `siegePass`), spending none: the days since its last slice (a first one counts as 3,
   vanilla's own gap between a fleet's raids) of `ThreatGroundFronts.siegeSlice` - the
   defence structures suppressed a little, the batteries answering in ships ("Sieges against
   human factions" below). Vanilla's assignment AI brings the fleet back every ~3 days, so
   the siege is the expedition's fleets circling the world, each pass a slice, for as long
   as `siegeOrbitDays` or the group's abort fraction (a third of its points) lasts. While a
   station or armed hostile fleets hold the orbit (at least `orbitContestFraction` of the
   expedition's points at the planet), no slice is delivered and the
   expedition's fleets in the system are made aggressive to hunt it (`directFleets`, the
   purge's `siegeFightsForOrbit` rule). An expedition that never spawned (vanilla
   autoresolve, the player far away) runs its whole siege in one go first
   (`abstractSiege`: the same slices against the same batteries, its abstract strength
   standing in for the fleets, the disruption it writes real).
2. **Land.** The world is ready (`readyToLand`: bombardment has done what it can -
   `orbitDone`, 2026-09-28 - or the landing could hold as it is - `troops x
   frontLandingMult >= holdRequirement`) and nothing blocks the landing
   (`landingBlocked`: another army on the ground, or, with real fleets, the orbit held
   against it): the pass puts the world's share of the troop pool on the surface as a
   `Factions.THREAT` ground front.
3. **Reinforce.** A pass over the swarm's own front lands whatever of the world's share
   is still aboard - a top-up, never a schedule: **a Threat front's real reinforcement is
   the next expedition**. A dry front signals for it (`wantsExpedition`), and
   `IncursionManager.pickStrikeTarget` weighs that world `strikeReinforceWeight` (4x) as
   the next strike's target; the expedition that answers resupplies the front on its pass.
   There is no convoy layer behind the swarm.

A pass that lands nothing - the share spent - is still a pass
spent, and does not count toward the strike's success
(`AnnihilationAction.getSuccessFraction`); a slice of the siege spends nothing.

### Sizing the landing - the troop pool

Strike fleets are swarm ships with no marine cargo, so the force is abstract - but it is
fixed at **launch** from what the expedition is, the mirror of the purge's
`marinesAllotted`, never from the defender:

- `troopsAllotted = strikeTroopsPerPoint (20) x` the difficulty points of the swarms
  mustered (3-9 each; `launchStrike` puts them in `fleetSizes`). Shown on the strike's
  intel as "Aboard: N troops", with each world's phase and passes spent.
- A world's **share** is `max(strikeFrontMinTroops (300), troopsAllotted / worlds in the
  sweep)`, and a world receives no more than its share across all its passes
  (`landedAt`) unless it needs more: a front takes what it needs from the pool still aboard
  beyond its even share (2026-09-29; the even split capped it, and a front short of holding
  watched the troops that would save it sail on to the next world). A small strike lands
  fewer worlds, not token forces.
- Before every pass the pool is cut to `troopsAllotted x` the strike's **surviving
  strength** - live fleet points over `totalFPSpawned`, or `1 - routeDamage` while the
  fleets are abstract - so losses on the way are troops that never land. Below
  `frontMinMarines` a fresh landing is called off.
- `armaments = ThreatGroundFronts.landingSupply(troops, strikeFrontSupplyDays (90))` -
  0.75 per landed trooper at the default burn. **SUPERSEDED 2026-09-08** - the swarm no
  longer fights on armaments at all; see "The swarm does not fight on armaments" below for
  what replaced the dry rules (half strength, tripled attrition, the final push, the
  collapse, and "hold the orbit and it withers"). The paragraph that stood here described
  the 2026-09-06 build and is kept in that section for the `threatFrontNeedsArms` case,
  which restores it exactly. **The swarm still does not extract**: it has nowhere to
  withdraw to and no reserve to bank into, and `evacuate` deposits nothing.

### Bombardment v2 - the day of sorties (2026-09-28, built, untested)

The spec, its principles and its tables are `docs/suppression-balance.md` ("Bombardment and
siege redesign v2"); this is where it landed in the code. Sections below that predate it are
kept as history where they say otherwise.

- **One day, one rule.** `ThreatGroundFronts.siegeSlice` is a day (or an AI fleet's few
  days, integrated a day at a time) of bombardment: each fortification's clock gains the
  theatre's rate x F / (F + D) x its condition, the shield soaks its own share on the same
  rule, and the world's unrest is raised to `bombardUnrestMax` x (1 - condition), never
  stacked. The guns answer with `bombardReturnFirePerGunDefence` (0.0008) x the defence they
  add, a day - whatever the fleet's size. Fuel is `bombardFuelPerFPDay` (0.04) per fleet
  point a day. The 15-days-for-3 player bomb is gone.
- **The player** (`ThreatincMarketCMD`): the menu quotes each kind's fuel a day, a shared
  `bombardCooldownDays` lock (sector memory `$threatinc_bombardLock`) allows one bombardment a
  day, and the prompt names the ships the day's return fire would take and the next in line
  with the damage banked toward it (`lossLines`). Vanilla's reputation, hostility timeouts,
  military response, pollution and listeners run every day.
- **Saturation** (`saturationSlice`, `ThreatRazing`): the tactical day on every building,
  unrest raised to 10, growth paused (`$threatinc_saturated`), and `satFuelPerFPDay` (2.86) x F
  of fuel a day. Over a human colony the fuel is poured into the razing bar through the shield:
  fuel per level at size s = `satFuelSize4` x 10^(s/2) / 144.8; a level paid is a size off
  (below 3 too); the last ends the colony by vanilla's teardown, a front on it with it
  (`colonyRazed`; the swarm's razing counts as its kill). Layers a front holds count as size
  lost. The dead stay dead. A story-critical world stops at size 3. A hive has no bar: the fuel
  buys the day's wear alone ("Saturation presses a hive"). One atrocity per campaign (a month
  without saturation ends it), not per day.
- **When orbit has done what it can** (`orbitSpent`): the commander would not fly another day -
  its gain on the least-worn fortification or the shield is below the day of repair the
  defenders make, or the day would take less than `bombardFPWorth` (30) defence off the world for each
  fleet point the guns take - a hull's price in marines, or the day would take the fleet
  under vanilla's abort line (0.33). It replaces "fully worn" everywhere the AI decided on it:
  the landing gate (`readyToLand(..., orbitDone)`), Defend's bombard/fabricate split
  (`orbitDoneFor`, which also counts a fleet out of ordnance as done). `bombardPlan` runs the
  same day forward, the fleet's losses included, for sizing landings; an expedition lands
  rather than let the guns take it under its abort line (`gunsWouldBreak`).
- **Short of troops, soften first** (run 4 -> 5). While the troops aboard could not hold
  (`troopsToLand`: the hold, and an NPC's first-landing beachhead, with the garrison read at the
  day's defence), the ships' worth is waived: without the troops, softening is the only way in.
  The gain and the abort line still stop it. Every landing gate passes its troops
  (`orbitSpent/orbitDone(..., troops, beachhead)`; the purge's `orbitDoneHere(market, troops)`,
  the strike's calls, `abstractSiege`). A base that cannot raise the planned landing sails with
  all the marines it has when `landsAfterSoftening` says the longer bombardment lets them land,
  drawing that bombardment's ordnance (`expeditionWants/expeditionFuel(..., shortLanding)`);
  otherwise it waits as before, and a flotilla trimmed for provisions below that wear waits too.
- **The swarm's stop is what a hull becomes** (after run 5, untested). The Threat buys nothing
  with credits, so it never uses `bombardFPWorth`: a hull kept is `fabricateTroopsPerFP` troops,
  and a defence point bombarded off is worth `frontHoldFraction / mult` troops to the hold, so a
  day must take `fabricateTroopsPerFP x mult / frontHoldFraction` defence off per FP lost
  (`hullWorth`; 10 x 0.75 / 0.17 = 44 at the landing's footing, up to 59 dug in). Strikes use it
  at `frontLandingMult` (`swarmWorth`) and drop the short-of-troops waiver - a short landing is
  made up by fabrication. A Defend fleet over its own front that cannot hold uses it at the
  front's `entrenchMult` (`defendWorth`): bombard while a day beats sending the hulls down,
  then fabricate. Navies keep `bombardFPWorth`: they cannot fabricate.
- **NPC sieges break off when outweighed** (after run 5, untested). An expedition that arrives
  with no front down and finds Defense Swarms of at least `siegeBreakOffRatio` (1.0) x its live
  FP over any target turns home intact (`ThreatPurgeFGI.breaksOff`), with a "Siege Called Off"
  notice and a swarm bounty posted. 0 turns it off.
- **Finish by saturation** (after run 5, untested). An NPC Defend fleet over its own front razes
  the levels the enemy still holds when its ordnance covers the whole pour and its faction's
  ships outlast the guns (`defendRazes`, `defendRazeSlice`; `razePlan`) - faster than the push.
  `razeWorlds` also weighs a world the faction's own front stands on, razing it whenever
  flotilla and fuel allow, whatever the landing would cost; `colonyRazed` ends the front with
  the colony. Only when it is faster: a front that takes the last stratum before the razing would
  land (`daysToLastStratum` against `razeArrivalDays` plus the razing's days) is left to finish,
  and a world whose front finishes before a siege could arrive is not targeted at all
  (`frontFinishesFirst`; run 6 sent a razing to a hive its front took a day later). Never over a
  hive since 2026-10-01: saturation takes no size off one, so it cannot finish a front. Off with
  `npcRazeEnabled`; never the swarm or the player.
- **A navy holds over its own troops** (2026-09-28, untested). Holding orbit costs a Defend
  fleet nothing, stops the swarm bombarding the front (`tickSwarmBombard`) and keeps the door
  open for front runs, so a navy's Defend fleet (NPC or player) over its standing front no longer
  goes home at `defendMinStrength`: only when worn below it AND outweighed - Defense Swarms of at
  least `siegeBreakOffRatio` x its faction's points there (`navyHoldsOver`, via
  `defendCommitted`). The front runs' contested-door check then sends a Support sortie - only if
  a task force can clear the orbit it faces (`siegeOrbitNeeded`; run 6 sent 111
  FP against 4,800), and an NPC Support sortie outweighed over its own front goes home
  (`navyHoldsOver`, in `ThreatFleetOrders.poll`). (2026-09-29: the sortie is sized to the orbit
  by `ThreatFleetOrders.buildSortie` - `guardFleetFP` is only its minimum - and is refused only
  while the base's stock cannot provision it, `sortieReachFP`; before, one `guardFleetFP` force
  was the most it could send, and a front under a strong swarm never got its door opened.)
  A front that cannot hold asks its runs for the troops that hold it (`holdGap`, with
  `fabricateHoldMargin`) or back to the whole strength it landed at (`landedStrength`), whichever
  is more (2026-09-29: it was `frontReinforceFraction`, 0.8, of what it landed), and a run sails
  for a small shortfall while the front cannot hold.
- **One day over one world.** Fleets of one faction over a world bombard as one: the
  structures wear at the rate their combined points earn (`orbitPoints`) and the guns answer
  once, each fleet taking its share by points (the slices' `orbitFP` overloads).
- **The raze task** (`IncursionManager.razeWorlds`, `ThreatPurgeFGI` raze mode): an NPC siege
  works a world from orbit instead of landing on it. A colony is razed where the razing fuel
  costs less than the landing's marines and armaments at vanilla base prices; a hive is
  saturated, never razed, only where its landing is beyond the marines held ("Saturation presses
  a hive"). Either way the reserve holds the fuel and a flotilla big enough to outlast the guns
  (`ThreatGroundFronts.razePlan`) finishes within `siegeOrbitDays`. The fleets carry the fuel
  (`razeFuel`), pour it a day at a time (`razePass`), and the board reads "razing from orbit"
  over a colony, "saturating from orbit" over a hive. `npcRazeEnabled` turns it off. The swarm
  razes human colonies by the same bar when `strikeSaturationEnabled` is on.
- **The player's Bombard order** (war board, docs/war-board.md): the same expedition,
  player-commissioned, against a system's hives without a front, each saturated in turn to the
  commander's stop; fleets sized to outlast the guns, fuel from the base's reserve.
- **Ordnance**: Support and Defend fleets pay the day's fuel from their provisions, then their
  home base's spendable reserve (`payOrdnance`); with none they stand idle ("out of fuel to
  bombard with"). The swarm pays from the hive's fuel stock at the same rates (2026-10-01,
  `threatPaysOrdnance`; docs/hive-reach-and-stock.md "Idle stock" part 4) - it bombarded free before.
- **Fronts** wear by their advantage (`frontWearRate` above); no fallout; saturation no longer
  kills a front. A landing is an act of war: vanilla's bombardment reputation hit, never covert.
- **Hives take unrest**: stability is pinned at 10 less `RecentUnrest` every poll
  (`ThreatColonyManager.applyHiveOrder`), and the Swarm Nexus no longer cancels the stability
  defence multiplier. A besieged human colony is held off vanilla's zero-stability
  decivilisation while the siege lasts (`$threatinc_decivHeld`).

### Sieges from orbit - one duel, both theatres (2026-09-06, untested)

The user's call the same evening: the orbital duel is the doctrine for everyone, not the
swarm's alone. `ThreatGroundFronts.siegeSlice` now asks the `Theatre` for what orbit
suppresses and what answers:

2026-09-28: the orbital floor was removed - orbit wears fortifications and shields to 0.

| | Hive (`Theatre.HIVE`) | Human colony (`Theatre.COLONY`) |
| --- | --- | --- |
| Fortifications | Ground Defenses / Heavy Batteries, Swarm Nexus, Swarm Bastion / Swarm Command (2026-10-01, no battery) | Ground Defenses, Heavy Batteries, Patrol HQ, Military Base, High Command (`ThreatSiegeMalus.FORTIFICATION_IDS`) |
| Clock (condition 1 -> 0) | `defenseWearDays` (300) | `fortificationDisruptDays` (180) |
| Condition carrier | the organs themselves (`ThreatColonyManager.disruptedDefenseResilience`, a straight line to 0 - the old `disruptedDefenseFraction` step is gone) | `ThreatSiegeMalus` |
| Suppression a day (the run-down made up) | `hiveSiegeSuppressDaysPerDay` (30) x F / (F + D) x condition | `siegeSuppressDaysPerDay` (12) x F / (F + D) x condition |
| Batteries' share | the two defence structures' multipliers on the hive's machinery/metals deficit | the two batteries' multipliers on vanilla's deficit |
| Return fire | `bombardReturnFirePerGunDefence` (0.0008) x D x share a day, smallest ship first, flagship spared | the same |

A disruption clock runs down a day per day (vanilla's expiry), which the first build of the
duel missed: a live fleet's slice now makes up the days the clock lost since its last slice
and then adds the rate x F / (F + D), so the rate is net progress and a fleet that stays holds
the clock where it is; an instantaneous slice (the player's bombardment, the abstract siege)
makes nothing up. The hive rate is higher because a hive's figure is anchored to its size
and runs several times a colony's: a 600-point expedition wears a size-5 hive (~9,000)
down far more slowly than 1,000 points wear a 4,000 colony - and both lose
most of their ships doing it. Who delivers slices:

- **A Threat strike** over a colony (`ThreatStrikeFGI.siegePass`, as before).
- **A faction's or the player's siege expedition** over a hive
  (`ThreatPurgeFGI.SiegeRaidAction.performRaid` -> `siegePass` / `abstractSiege`): the
  flat 60-day tactical pass is gone; a live fleet slices while the strata aren't fully worn
  and the troops could not hold, spending no pass, and lands once `readyToLand` says so. An
  unspawned expedition runs its whole siege abstractly first (`ABSTRACT_FP_PER_POINT` x
  its difficulty points, less route damage; the batteries' toll comes off its marines and
  armaments - the strike's off its troops aboard), and a pass over a world not yet fully
  worn returns before vanilla counts it (`waitsInOrbit`; vanilla's `performRaid` counts
  the pass before it asks what to do with it, so the check cannot live in
  `doCustomRaidAction`). The expedition's payload stage is `siegeOrbitDays` (120) per world,
  as the strike's is.
- **A Support sortie** (Escort until today) on station over a hostile world whose orbit
  nothing holds against it: every poll, `tickSupport` delivers its live fleet points as a
  slice and the batteries answer. A front on the ground does not stop it - Support's slices
  stack with anything a ground front adds.
  Over a contested orbit (a colony's station or patrols, a hive's Defense Swarms) it fights
  instead and its row reads "clearing the orbit of X" until it is clear. NPC factions fly
  Support too (`ThreatConvoys.supportFor`).
- **The player's tactical bombardment** (`ThreatincMarketCMD.bombardTactical` /
  `bombardConfirm`, hive or colony): the targets are the fortifications not yet fully worn, the
  prompt quotes the days each gains and the fleet points the batteries take, vanilla's fuel
  (a hive's `hiveTacCostFraction` bill), reputation and unrest flow runs, and the 365 days it
  wrote are taken back for the slice. Ships lost are named in the result; the flagship is
  never one of them. Nothing to suppress: "X's defences are suppressed as far as orbit can
  push them." A world with no fortification at all (nothing the duel describes) keeps
  vanilla's bombardment; Lion's Guard HQ is not a fortification and is left alone.

The landing gate is one rule everywhere (`readyToLand`): the fortifications are fully worn
(`suppressedFully`, every fortification at `siegeWornDays`) or the troops x
`frontLandingMult` could hold. The second branch went round a loop on 2026-09-06: the user
first asked why any commander would not soften the ground for the troops when orbit can, then
recalled that every day in orbit costs ships to the batteries - a force that can already
hold spends marines rather than hulls, and the Support button is there when that call is
wrong. The original rule stands. `needsSoftening` is `suppressedFully`'s complement on both
theatres.

**How long a fleet stays, and what it does there** (the user's question, 2026-09-06 evening,
after a strike dropped its marines and left): an expedition's fleets deliver a slice on each
raid pass (vanilla's own cadence, about every 3 days per fleet) while the world isn't fully
worn and the troops could not hold - so a strike whose troops could hold on arrival lands at
once and never bombards, which is what "disrupted 1 day from marines" was: the front's own
suppression, no orbital siege, and the intended trade (see the landing gate above). The
passes are the landings: `expeditionPasses` (fleets + 2; 2026-09-29, it was
`strikePassesPerColony`, 3) per world, the first a landing, the rest reinforcements, and once they are spent or
`siegeOrbitDays` (120) run out vanilla moves the group on to the next world in the sweep.
Every slice plays vanilla's bombardment burst on the planet (`MarketCMD.addBombardVisual`,
once per whole day of suppression added per world, so a slice that adds a tenth of a day
is not a bombardment - only for a player in the system).

**The landing fleet stays, on Defend** (2026-09-07, the user, after watching an expedition
land at Gamma Hero II and then pay the batteries over Gamma Hero I with nothing left to
land): the fleet whose pass lands or reinforces a front leaves its expedition and holds the
orbit over that world with no term - a faction's as an indefinite **Defend** order
(`ThreatFleetOrders.adoptLandingDefend`, shown on the board with Recall), the swarm's on the
`ThreatSwarmDefend` list (2026-09-29, closed economy: when the front is gone a faction's Defend
fleet goes home on the tracked leg - `ThreatReturns.homeOf` where the entry names no base - and
settles; a swarm fleet is bound to its colony's FP bank from spawn, so it re-banks on despawn by
any road, at the nearest live colony if its own is gone) - and every other expedition fleet in the system with nothing left
to land goes with it (`ThreatPurgeFGI.stayOnDefend`; a fleet still carrying a landing sweeps
on). Defend is not Support: it fights whatever contests the orbit but bombards only while
the front it covers cannot hold **or would not survive the next counter-attack**
(`ThreatGroundFronts.defendBombards`: the faction's own front, and the fortifications not
yet fully worn), so a front that holds *and is safe* costs it nothing. It stands down when the front is gone (won or
lost) or the batteries have ground it below `defendMinStrength` (0.33) of its arrival
strength, and goes home on the tracked leg. Knob `landingDefendEnabled`; off, the
expedition sweeps on as before.

**Why the gate is not just "cannot hold"** (2026-09-08, found in play). HOLDING is the
*suppression* threshold - `effectiveStrength >= defenders x frontHoldFraction` (0.17) - and
says nothing about the counter-attack, which is resolved at the defence's full weight against
`defenseStrength(front)`. The two questions are unrelated, and a front can sit in holding at
four-to-one down. Gating on holding alone meant a swarm of ~1,000 FP stationed over a world
went cold the moment its front reached holding, then watched that front be overrun outright
by the colony's next counter-attack - the fleet had the exact lever needed (a slice suppresses
the fortifications, the siege malus cuts the ground-defence stat, and the counter-attack's own
strength falls with it) and no reason to pull it. It now also bombards while
`counterAttackOverruns`, so it stands by only when the front both holds and is not about to
die. Keeping the ships is still the point of Defend over Support - this is a narrower trigger
than "cannot repel", which almost every front would meet.

**No siege where nothing can land** (the actual bug): `siegePass` on both expeditions now
asks first whether the pass could ever land - the troops aboard the fleets here (purge) or
the world's unspent share (strike) at least `frontMinMarines` - and with nothing, delivers
no slice. The fleet joins the Defend over the expedition's own front instead
(`joinDefend`, this world's or the nearest of the sweep's); with no such front the pass goes
ahead and spends itself, so the expedition ends and goes home. An abstract expedition
(never spawned) skips the abstract siege on the same test. Before this a purge - whose
troop pool is sized for ONE landing, the hardest world in the system
(`IncursionManager.siegeRaidStrNeeded` takes the max, not the sum) - landed everything at
the first world and then ran the orbital duel over the second for the whole orbit budget.

Two things the detach path needs, either way: `FleetGroupIntel` judges a group beaten by
live points against `totalFPSpawned`, so a fleet pulled out takes its spawn points off that
baseline (`ThreatPurgeFGI.detach(fleet, false)` moves them to `detachedBaselineFP` so the
refund and surviving-fraction sums still count it; an emptied group finishes as vanilla's
does, not as a rout), and vanilla's raid AI must be cut loose (`cutLoose`) or it re-tasks
the fleet within days. An indefinite order has no return leg queued behind its orbit, so
`tickSupport` re-issues the orbit whenever a battle emptied the queue
(`ThreatSwarmDefend.holdOrbit`).

**The Support button says what it does** (the user, after the round trip above): its
tooltip and confirm name the fleet that goes, then one line per fact
(`ThreatFleetOrders.supportEffect`): it holds the orbit and fights whatever contests it;
while the orbit is clear it bombards the defences at about N disruption days per day;
the batteries answer at about M fleet points of ships lost per day,
smallest first - N and M from `siegeSliceEstimate` at the fleet's points against the
world's defence figure; and that it bombards whether or not the front could hold, where
Defend bombards only while it cannot and an expedition lands as soon as its troops can, to
spare its ships. The Defend button (`defendEffect`) says the same the other way round: it
does not bombard while the front holds, and only while it cannot does it bombard - and only
then do the batteries answer. The expedition's own status line
says which branch opened its landing gate (`ThreatGroundFronts.landingPhase`: "moving to
land - defences worn out" or "moving to land - the troops can hold, sparing the ships").

### Saturation presses a hive (2026-10-01, user's call)

Razing existed for size-8 hive systems no landing could take; `colonyMaxSize` and size upkeep
answer those now, so a hive has no razing bar (`ThreatRazing.razes` is false on the `HIVE`
theatre; a bar an older save left is cleared on the next pour, `clearBar`). Saturation over a
hive is the per-day engine alone (`saturationSlice`): each saturation target gains rate x
condition x shield throughput days of disruption a day - rate `hiveSiegeSuppressDaysPerDay`
(30) x F/(F+D), condition 1 - clock / 300 wear days (`disruptedDefenseResilience`) - so its
clock closes on the wear cap and never jumps to it. Unrest goes to 10, growth pauses
(`markSaturated`), and the fleet pays `satFuelPerFPDay` (2.86) fuel a FP a day. No size comes
off: a hive shrinks only as its upkeep starves it (`ThreatColonyUpkeep`) and dies only to troops.

The commander stops when a day adds less than a day anywhere - on every target and the shield
(`ThreatGroundFronts.saturationSpent`) - or at the abort line (`GROUP_ABORT_FRACTION`, 0.33),
the end of `siegeOrbitDays` (120) or the end of its fuel. `razePlan` flies it forward: {days,
fuel spent, fleet points left, finished, days the least-worn target is then down}. Its fuel
spent with no limit aboard is the stay's price (`IncursionManager.razingFuel`).

**The flotilla holds the orbit; a squadron bombs** (2026-10-01, user's call;
`ThreatGroundFronts.squadronPlan`). The defence a squadron meets falls as the fortifications it
wears stop answering, so a small one wears a hive nearly as deep as the whole flotilla for a
fraction of the fuel, only slower. h47a, size-7 hives at D 12,600, no shield:

| Bombing | Days | Fuel | Days down |
|---|---|---|---|
| 434 FP (the least) | 111 | 119,000 | 264 |
| 1,400 FP | 59 | 231,000 | 284 |
| 4,200 FP | 42 | 502,000 | 288 |
| 12,600 FP | 36 | 1,296,000 | 289 |
| the whole 12-20k FP flotilla | 35 | 1.25-2.04M | 290 |

The commander sends the squadron that buys a day down cheapest: its fuel plus the whole
flotilla's supplies for the stay (`ThreatReach.tripSupplies`), at base prices, over the days
the hive is then down. The candidates climb from the least squadron that reaches the stop on its
own (bisected) by `SQUADRON_STEP` (1.5) up to the flotilla, memoised per market per clock
instant; each must reach the stop above its own abort line on the fuel aboard and leave the
flotilla above its line. Short of the fuel for any, the least pours what there is; a flotilla
lighter than the least flies it whole. The squadron takes the guns' answer and turns back at
0.33 of itself. Every planner reads it - the verdict, `razeRun` (so `razeFleetPoints`,
`siegeStayDays`, the launch's payload days), `razingFuel`, the Bombard prompt - and the
expedition flies it: `ThreatPurgeFGI.squadron` picks it when the hive's saturation begins, from
`liveFP` and the razing fuel left, and keeps it with its losses; each fleet over the hive pours
fp x squadron / orbit (`saturationSlice(share, squadron, ...)`), and the stop is read at the
squadron's FP. An abstract razing (`razeAbstract`) does the same with its allotment. A colony
with a bar is razed by the whole flotilla: the bar fixes the fuel, and more FP pour it sooner.

**NPCs saturate what they cannot land on** (`IncursionManager.razeWorlds`): per hive of a siege
the expedition lands wherever the marines its reserve still holds cover the landing
(`minMarinesFraction`); short of that it saturates a world that still produces (`producing`: a
forge, a fuel plant or its Fabrication Core working) if its flotilla outlasts the guns
(`razeFleetPoints`) and the reserve holds the stay's fuel. Never a hive a front stands on.
Logged "Saturate or siege of X (size N) from B: saturating F fuel for D d, S of P FP bombing,
down N d (...), landing M marines of H held - verdict", the verdict "saturates - the landing is beyond its marines" or
"sieges - nothing standing worth saturating" / "- the guns would break a saturating flotilla" /
"- short of fuel to saturate it". h48a, with the squadron: the planned fuel fell 4-13x (median
9x) to 50k-278k and the stay grew (median 36 -> 81 days), squadrons 3-14% of the flotilla - and
still not one saturated: on all 368 verdicts the pool fell short of the flotilla's own passage
before any bomb (median pool 28k; the largest faction reserve 92k). The orbit's flotilla (5-26k
FP against the Defense Swarms) is what NPCs cannot pay to send, not the bombs.
The player saturates by the Bombard order (docs/war-board.md) or in person.

**The wreck, rejected the same day** (cd56a30; user's call 2026-10-01): a hive's bar was one
price, its defender strength in fuel, and paying it put every structure down for the full 300
wear days at once. That made the slow mechanisms - sieges, tactical bombing, saturation by the
day - pointless beside one pour, where a balanced per-day disruption engine already stood.

### Off-screen fights cost both sides (2026-10-01, user's call)

Why razes were free (ng3a: 29 razes cost the attackers 34 of 72,700 FP): off-screen no
battle is simulated. The garrison only sized the flotilla (1.5x, about 1.8x after rounding
to whole fleets) and set the break-off. Once committed, vanilla's
`FGRaidAction.autoresolve` charges the ATTACKER min(0.75, 0.5 x defence / strength) per
world raided - a refund haircut on human hulls (median expedition home at 83%) that the
"34 FP" figure never counted, since that was only the battery toll (zero under size 3) -
and never touches the defending fleets. The swarms of a razed world even rebound to the
next colony.

Now `ThreatAbstractBattle.fought` runs after vanilla's half of every off-screen fight -
`ThreatPurgeFGI.SiegeRaidAction.autoresolve` and `ThreatStrikeFGI.AnnihilationAction.autoresolve`,
both strengths read before vanilla's fight: every fleet vanilla counted in the defence
(`WarSimScript.getEnemyStrength`: each faction with a market there hostile to the
attacker; no stations, traders or smugglers) loses min(0.75, 0.5 x attacker / defence) of
its fleet points as ships struck (civilian hulls a quarter as likely, the last ship by
chance so small fleets lose their share on average; an emptied fleet is destroyed), and
unspawned routes take it as route damage. A 1.5x siege kills 75% of the garrison and
loses a third of itself per world raided. The exchange goes into the hive's loss ledger
(`ThreatPosture.addLoss`) and the stance's attrition trend (`ThreatStance.noteTrend`).
The break-off before the fight stays free. Knob `abstractDefendersFight` (on). Logged
"Off-screen fight in S (siege|strike): ...". The hive refills what it lost from its FP
banks - their first real sink.

Since 2026-10-01 an NPC siege off-screen fights this a day at a time over each world, striking the fleets it
weighed there, and goes home outweighed (`abstractSiegeDaily`): [ground-war-code-paths.md](ground-war-code-paths.md) section 8.
