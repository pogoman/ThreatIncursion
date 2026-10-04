# Ground war - fabrication, holding the orbit, relief, stations

Split out of `ground-war.md` on 2026-10-01. One-line answers: `facts.md`.

### Fabricating troops from the fleet (2026-09-08, built, untested)

**The swarm's alone (2026-09-28).** It had been built for every Defend fleet, player and NPC
navies included; the user never meant that - bioships go down as troops, a navy's hulls do
not. `defendFabricates` and `fabricateTroops` now refuse any faction but the Threat, and the
player's Defend tooltip no longer offers it. A navy's Defend fleet bombards while it pays and
otherwise holds the orbit; its troops come from the next expedition.

The user, after watching the Defend behaviour they liked run out of road: a covering
fleet bombards while its front cannot hold - but the bombardment stops mattering once the
defences are fully worn, and a losing front then just watches a full fleet sit in orbit doing
nothing. So **once there is nothing left to bombard, the fleet starts sending fragments of
itself down as ground troops instead.** Hulls broken up into soldiers.

The trigger is the exact complement of the one that was already there
(`ThreatGroundFronts.defendFabricates`):

| | `defendBombards` | `defendFabricates` |
| --- | --- | --- |
| Its own front on the ground | cannot hold | cannot hold |
| Orbit | still pays for this fleet | done for it (`orbitDoneFor`: spent, or no ordnance - 2026-09-28) |

Both are false while the front holds, so the two states tile the "front in trouble" case
between them and neither runs while the ground is safe. Four rules the user set, and where
each one lives:

1. **Only while the front cannot hold.** `defendFabricates` is re-evaluated every poll and
   `fabricateNeed` returns 0 the moment the front is over the line, so the fleet stops
   cutting itself up the same poll the ground is safe and goes back to ordinary Defend -
   which, with the defences fully worn, means holding the orbit and paying nothing.
2. **It never gives up.** A Defend order and a swarm station both stand down when the
   batteries grind them below `defendMinStrength` (0.33) of their arrival strength. A
   fabricating fleet is **exempt** (`defendCommitted`, checked in both
   `ThreatFleetOrders.poll` and `ThreatSwarmDefend.tick`): it can fabricate now, or it has
   fabricated before and the front it fed still stands. It leaves when the front does, won
   or lost - never because feeding the front cost it ships. `applyFleetLosses` still spares
   the flagship and the last hull, so the fleet thins toward a single ship rather than
   vanishing, and a fleet down to that keeps holding the orbit with nothing left to give.
3. **Proportional, not everything.** `fabricateNeed` is the gap to
   `holdRequirement x fabricateHoldMargin` (1.05 - a little daylight so the front does not
   sit on the boundary and oscillate), and that gap alone is what gets converted. A shallow
   deficit costs a shallow bite. The margin is measured against what the front will be
   worth *after* the drop, not before: the fragments land with their own armaments, so
   pricing in the dry penalty and then cancelling it by landing would over-feed the front by
   half its own weight.
4. **The price is the undisrupted batteries.** `fabricateCost` is the day's return fire
   (2026-09-28: `bombardReturnFirePerGunDefence x D x share`) read at **full**
   condition instead of the suppressed one, charged on top of the hulls that became troops
   and banked as ordinary battery damage. The fragments go down through defended air, so
   the guns get the shot they would have had on the first day of the siege even though they
   are ground down to nothing.

**Cost and commitment are deliberately two numbers, not one.** The obvious build - spend
the undisrupted-battery toll per day and land *that* as troops - is wrong in a way that only
shows up at the edges: a world with no batteries charges nothing, so the rate would be zero
and a front there would never be fed at all, which is the exact opposite of what a cheap
world should mean. The commitment is sized by the front's need; the price is sized by the
guns; a world with no guns is fed for free.

**The tail nobody hits until they do:** a front with plenty of soldiers that cannot hold
only because it ran dry computes no gap, yet is still losing. It is short of guns, not men.
`fabricateNeed` falls through to `frontMinMarines` for that case so a drop still happens and
its armaments arrive - without it, a dry front that was strong enough on paper would sit
there for ever while the fleet above it fabricated nothing. Drops carry
`fabricateArms`: enough to bring the *whole* front (the troops there plus the ones landing)
up to `fabricateSupplyDays` (30) of burn, topped up rather than issued flat, so a long siege
does not pile armaments up.

Hulls leave the fleet as whole ships, smallest first, the remainder banked in its own
account (`FABRICATE_BANK_KEY`, kept apart from `SIEGE_DAMAGE_KEY` so a hull the batteries had
all but paid for is never handed to the ground as troops). So fragments go down a ship at a
time, not as a daily trickle - which is what the animation keys off.

Both theatres, as everything in the duel is: `ThreatGroundFronts.tickSupport` for a faction's
or the player's Defend order, `ThreatSwarmDefend.tick` for the swarm's stations. Support does
**not** fabricate - it bombards whether or not the front could hold, and that stays its whole
difference from Defend.

**Seen from the map** (`ThreatFabricationVisual`, the bonus ask). The engine turns out to
have everything needed: `IncursionManager.advance` already runs every frame, and vanilla's
own bombardment burst is a public `EveryFrameScript` scattering `Misc.addHitGlow` particles.
The fabrication visual is the same particles driven the other way - trails starting at 2.4x
the planet's radius and falling **into** the surface over ~1.1s, one per fragment, with a
floating "Fleet fragments landing" label and a `Pings.DANGER` ping. Reusing the bombardment
burst would have been trivial and would have said the opposite of what is happening: this is
the state where the fleet has *stopped* bombarding. Drawn only for a player in the same
location, and every call guarded - a campaign visual is never worth an exception in the siege
tick. The board and the fleet's own assignment say it too: `Order.task` reads "breaking up
for the front on X".

Knobs: `fabricateEnabled` (true), `fabricateTroopsPerFP` (25), `fabricateHoldMargin` (1.05),
`fabricateSupplyDays` (30). Off, a Defend fleet with the defences fully worn simply holds the orbit as before.
`fabricateDefendEnabled` (true, 2026-10-03) turns off only the Defend fleet's break-up: a strike short of its beachhead
still breaks hulls (`ThreatStrikeFGI.beachheadLanding`), and the Defend fleet's stop reads the landing's worth
(`defendWorth` returns `swarmWorth`).

### The swarm does not fight on armaments (2026-09-08, built, untested)

The user, immediately after the fabrication build: *"given the Threat never resupply, can we
remove their need for armaments - and given they can make more soldiers, can we increase
their losses when pushing to compensate."* Both halves are one call, and the second is the
price of the first.

**Why the rule was wrong.** For a faction, armaments are the far end of a logistics chain -
convoys, front runs, a base with a stockpile - and running dry is that chain failing. That
is a real decision with a real counterplay. The swarm has no chain: `ThreatConvoys.planFrontRuns`
and `supplyFront` both resolve their target through `ThreatIncData.resolveColonyMarket`,
which only answers for **hive** markets, and a Threat front stands on a **human colony** - so
the lookup returns null and the front is skipped. Nothing in the game can resupply a Threat
front except another landing. Its armaments were therefore not a supply line at all; they
were a countdown wearing the costume of one, and every penalty hung off them was a timer the
player could not influence except by killing the relieving expedition.

**What changed.** `ThreatGroundFronts.needsArms(front)` is false for a Threat-owned front,
and `isDry(front)` - the single test every dry rule now goes through - is false with it. So a
swarm front burns nothing, never announces dry, and takes none of:

| Dry penalty | Knob | Now |
| --- | --- | --- |
| Half effectiveness | `frontDryEffectivenessMult` (0.5) | never applies |
| Tripled attrition | `frontUnsuppliedLossMult` (3.0) | never applies |
| Dig in and wait for the next expedition | - | never fires |
| The final push after 120 dry days | `frontDryFinalPushDays` | never fires |
| Collapse when dry and below grind strength | `frontGrindFraction` | never fires |

`threatFrontNeedsArms` true restores every row of that table unchanged.

**What replaces the pressure.** `pushLossMult` - a Threat front takes
`threatPushLossMult` (2.0) times the casualties **while it is pushing**. That is the whole
compensation, and it is deliberately on the assault and not on holding: the swarm can now
break up its own hulls for replacements (`fabricateEnabled`) and can no longer be starved, so
what it should pay for is *ground*, in bodies. A dug-in Threat front bleeds at the ordinary
rate. The counterplay moves from "outlast them" to "make them come to you" - hold the
districts, let them assault, and every push costs them double.

The multiplier is applied in three places that must agree or the board lies: the tick that
actually kills marines (`tickFront`), the per-30-day readout (`attritionPer30Days`), and the
forward-looking tooltip estimate (`pushCasualtyEstimate` - which already omitted
`frontUnsuppliedLossMult` and would have under-quoted every swarm row).

**The signal that had to be re-keyed.** `wantsExpedition` - how a landed front tells
`IncursionManager.pickStrikeTarget` to weight this world up by `strikeReinforceWeight` (4x)
for the next wave - used to mean "dry". With dryness gone it would have returned false for
ever, and a stalled swarm landing would have quietly lost its reinforcement priority: a
behaviour change nobody asked for, hidden inside one that was. It is re-keyed to the thing
dryness stood for - **a front that cannot hold is the one that needs the next wave**
(`!frontCanHold`). Under `threatFrontNeedsArms` the old test is used unchanged.

**What the player sees.** A swarm row's Arms cell reads a grey `-` rather than a day count or
`dry`, its row tooltip says "Heavy armaments: not used - it carries no supply line and needs
none", and its Losses line names the `x2.0 swarm assault` factor while it is pushing. The
colony-screen tooltip (`ThreatGroundWarCondition`, which exists only for a Threat front)
loses its armaments line - that line was carrying the player's read on how the siege ends -
and carries their casualty rate instead, which is the new answer to the same question.

Knobs: `threatFrontNeedsArms` (false), `threatPushLossMult` (2.0).

### The swarm holds the orbit

A swarm that has taken the space over its own besieged hive **bombards the enemy front**:
`tickSwarmBombard` kills `swarmFrontBombardPer30Days` (0.60) of it per 30 days, scaled by the
swarm's weight over the planet against what the front can put in the way
(`fp / (fp + defenseStrength)`) and by the front's own veterancy. It needs
`swarmOrbitMinFleetFP` (50) at the planet to count as holding the orbit at all - a bombardment
is a fleet operation, not a lone frigate - and an armed hostile fleet on station stops it
outright (`swarmOrbitContested`).

**Orbit cover for autoresolved landings (2026-09-26, overnight after run 7).** Far from the
player, vanilla resolves an NPC siege without spawning any fleet, so after an abstract
landing no flotilla existed to put on DEFEND. The swarm then held the orbit unopposed and
bombarded the front until it ran dry and was overrun: runs 6 and 7 lost both Gamma
Golgotha II landings this way (2,917 and 3,333 marines). Now `ThreatPurgeFGI.stayOnDefend`,
with no live fleet, leaves what the world's abstract siege left (its allotment less route
damage and that world's batteries, in abstract FP) on the front as `GroundFront.coverFP` -
out of one flotilla (`abstractFP`), as the live fleets stay where they landed: the next
world the sweep lands on or reinforces gets only what earlier landings did not keep
(2026-09-27: each world's pass read the whole flotilla afresh, and one expedition
reinforcing two fronts covered both with all of it). Each world's siege itself still
starts from the whole allotment.
The cover contests the orbit while it is at least the
swarm's FP there. Once the swarm outweighs it, the cover is lost for good and logged
("Orbit cover over ... lost").

**The swarm guards its unspawned landings too (2026-10-02, the user: "every faction should guard
their siege on screen or off").** Before, only a spawned strike - one near the player - left its landing
fleet on `ThreatSwarmDefend`; an unspawned one went home after the pass (`Strike ledger: ended unspawned`,
117-174 a run in hw4). Now `ThreatStrikeFGI.stayOnDefend` with no fleet calls `guardUnspawned`: the strike's
first `params.fleetSizes` entry - the pack `spawnFleets` would build - is built over the world and put on
Defend, one guard a world a strike. Since 2026-10-04 (`strikeGuardWhole`, built 68d019a, not game-tested) it
leaves its share of the fleets, not one: `guardShare` = what it has left over the worlds it may still land on,
rounded up, and all of them once fewer than `frontMinMarines` troops are aboard; `guardOne` builds each. In
hw6b a strike of 4,219 FP left 291 FP over Chicomoztoc and took the rest home (`game-runs.md` 4). The entry leaves the strike (`fabricatedFP` scaled so `ledgerShare` holds);
its share of what the strike held is weighted by `estimateFP` of its pack against all packs (by size points
until 2026-10-03, which built guards at 1.35x their share in hw4d against 0.96x for a spawn); the bank pays the
guard beyond that share or takes back what it fell short, as at a
spawn, and the guard re-banks on despawn (ledger-bound). A strike whose every entry stayed passes no more
(`abstractSpent`, checked in `AnnihilationAction.performRaid`). Unlike the humans' `coverFP`, the swarm's
guard is a real fleet, so every reader of a live Defend station applies. Log: `Strike guard over X`.

**This replaced the scour** (removed 2026-09-08, user). The hive used to saturation-bomb its
OWN surface once the swarm had held the orbit unopposed for `threatScourDays`, annihilating
the front to the last marine and closing the ground with fallout for `falloutDays`. That rule
predated the siege system and cut across all of it: orbital superiority skipped the ground war
rather than feeding it, and no amount of entrenchment, veterancy, reinforcement or bracing
mattered against a guillotine on a timer. A swarm that owns the orbit now does what every
other besieger does - it grinds - and its counter-attacks (paced by `counterAttackTempo`,
which since the same day answers being outnumbered on a hive as well as a colony) do the rest.

The counterplay keeps its shape - get a fleet over the planet - but becomes a question of
degree rather than a deadline: contesting the orbit stops the bleeding, and arriving late now
costs troops instead of everything.

The player's saturation bombardment became a campaign to destruction on 2026-09-28
("Bombardment v2"); fallout went with it.
### Relief (2026-09-27, built, untested)

A Threat army on an NPC faction's own world is answered by `ThreatFleetOrders.planRelief`,
on the ground-front poll (it was monthly):

- **Sized to break the hold.** The relief is the Threat's points over the world
  (`pointsNear`, `ORBIT_HOLD_RANGE`) times `npcSiegeOrbitMargin` (1.5), less the guards
  already bound there, built at the nearest base whose depot can provision one
  (`pickReliefBase`) from fleets of `reliefFleetFP` (300
  combat points) and merged into one "Relief Force" up to `softenMergeMaxShips`. There is no
  fleet cap (`sendRelief`, 2026-09-29: 50 fleets of `reliefFleetFP` held a relief to ~15,000 FP):
  it builds until it reaches what is owed or the depot cannot provision another. Each fleet
  is what the depot pays for in full (2026-09-29: a depot at `expeditionMinProvisionsFraction`
  of one fleet's provisions used to send the whole fleet half paid), from the stock above
  its floor, so a depot runs out rather than conjuring fleets. The nearest base alone was
  often the invaded world itself, drained dry, and relief never sailed while Chicomoztoc
  sat 3 ly away stocked (Nachiketa, 2026-09-29, verified on a clone of the user's save:
  3,245 FP sailed on load, took the orbit, the garrison overran the beachhead). A colony
  under a ground front donates nothing to logistics convoys (`ThreatConvoys.spare`), as it
  already gave no siege. With no swarm overhead,
  one guard of `guardFleetFP` still goes, as before. The old flat 100-point guard
  (258 FP built) sat 2,600 units off Coatl against 2,812 FP for 76 days.
- **Any distance (2026-10-03, the user: "Why wouldn't a far away base be allowed to send relief?").** `pickReliefBase`
  takes the nearest base whose depot can provision one relief fleet at its distance; `canProvisionRelief` prices the
  voyage by the light-year, and the time is the voyage's. It used to skip any base beyond its expedition range
  (`expeditionRangeLY`, `ThreatReach.baseRangeLY`), which is how far a whole siege could sail on the depot, so a base
  that could pay for relief but not a siege counted as out of reach: 11 of the 30 worlds taken in hw4q (14 of 32 in
  hw4p) got none. An ally's relief (`ThreatCoalition.allyAid`) picks its base the same way; an ally's guard against
  a strike still uses `pickBase`. Marine convoys already had no radius (`ThreatConvoys.stockReachLY`, 2026-10-01).
- **The siege hull ledger (2026-09-29, closed economy).** The purge's counterpart to the
  strike's bank ledger: `ThreatPurgeFGI.setLedger` books an NPC siege's fleet points at launch and
  `settleLedger` settles them against the fleets that really spawn (shortfall from the base then
  the donors, excess refunded, unpaid warships pruned, supplies stamped on each fleet and
  refunded per fleet on arrival home). Details: docs/strategy-board-returns.md "Returns".
- **Marines by convoy: sized to the need (2026-09-29).** `ThreatConvoys.planRelief` sends
  what the counter-attack needs to beat the army by `siegeBeachheadMargin`
  (`ThreatGroundFronts.reliefNeed`: 0 with no Threat front on the world or once the garrison
  is enough; banked marines not yet armed count), less what is already at sea to the world
  (`inbound`). Every colony in reach that can spare marines sends what it can of that, the
  richest first, in parallel - it was one hull load (`convoyMarineCapacity`) from one donor
  at a time per invaded world. An NPC donor reaches as far as its fuel pays a convoy's base
  escort (`ThreatConvoys.stockReachLY`, 2026-10-01; it was the larger of the flat `convoyRangeLY`
  and its fuel radius), and the convoy sails only if that escort is paid - docs/strategy-convoys.md
  "Logistics reach".
- **Relief before offensives.** While a faction owes relief it could send
  (`reliefOwed`), no base of it starts a new siege. Running sieges keep their fleets.
- **Help after the landing.** While the swarm holds the orbit over the army, the owner
  posts a swarm bounty on the system (`ThreatSwarmBountyIntel.postRelief`, ends when the
  army is gone), and allies send relief for what the owner could not
  (`ThreatCoalition.allyAid`, by standing and `allyAidChance`; 2026-09-29: every willing helper
  ships what it can spare until the shortage is covered, net of what is at sea - it was one
  convoy per helped colony, one helper per need, clipped to a hull load). Before this, allied
  guards and Defend contracts stopped once the strike landed. The Defend contract itself
  still covers strikes in flight only.
- **Holds until the army is gone (2026-10-03, the user: "relief should stay until invaders defeated").** With
  `threatinc_reliefStays` on, relief has no term: `sendRelief` queues the voyage and an orbit with no end, the
  order is leashed like a Defend (`enforceLeash`, "Relief"), and `ThreatGroundFronts.tickRelief` keeps it on its
  orbit and fighting while any Threat fleet is over the world. It goes home (`ThreatFleetOrders.poll`,
  `ThreatGroundFronts.reliefDone`) when the Threat army is gone, beaten or the world fallen, or when it is worn
  below `defendMinStrength` AND the swarm there outweighs it by `siegeBreakOffRatio`, a navy's Defend's rule;
  `planRelief` then sends what the swarm there asks. While it holds the orbit nothing comes down: the swarm's
  landings, its guard's bombardment (`supportSlice`) and its break-up (`fabricateTroops`) all refuse a contested
  orbit, as they always did, and the marine convoys get through. Before, relief left after `guardDays` (90).
- **Bombards the army while the defenders are losing (2026-10-03, the user).** Breaking hulls up for troops gives
  up the orbit, and the side that takes it now uses it. `tickReliefBombard`, on the front's tick beside
  `tickSwarmBombard`, uses the swarm's formula: `reliefBombardPer30Days` (0.60) of the army per 30 days, times the
  relief's points over the army's defence strength, times its veterancy loss multiplier. It fires only with no Threat
  fleet over the world and while `defendersLosing`: the army is pushing, or it holds and the next counter-attack
  would not overrun it, which is what keeps a Defend fleet's guns cold, seen from the other side. Each fleet pays
  `bombardFuelPerDay` from what it carries above its passage home (`reliefFuelHome`) and then its supply line
  (`payOrdnance` with `keep`). A fleet that cannot pay a day does not fire, so it never burns the fuel it needs to
  stay or go home. An army bombarded below `frontMinMarines` is destroyed ("Threat Landing Destroyed"). The board's
  Losses figure includes it (`attritionPer30Days`). The simulator has no relief bombardment; its relief rules are off.

To verify: Coatl-like siege gets a Relief Force at about 1.5x the Threat FP within a
day of loading; it engages rather than keeping its distance; a depot too dry to
provision one leaves the owner short, and the bounty and allied relief follow; no new
siege launches from that faction meanwhile.

### A station that comes back (2026-09-08, built, untested)

*The user: the Threat held the space over a core world it was sieging, "the siege went so long
my destroyed/disrupted star fortress came back online and multiplied my ground defence - the
threat ignored it".*

Everything that **reads** the orbit was already live and already right. `supportSlice` fires
nothing and `fabricateTroops` sends nothing down while `orbitContestedFor` is true, and both
log "fights for the orbit": orbit before ground, the same order `siegePass` keeps before a
landing. Nothing was latched and nothing was cached.

What was missing was the other half - something that turns that refusal into an **attack**.
Hold station (`ThreatFleetOrders.leash`, `orbitFleetsHunt` false by default) sets
`FLEET_IGNORES_OTHER_FLEETS` and strips `MAKE_AGGRESSIVE` from every Defend or Support fleet
and every swarm station, every frame, and once the fleet is at the planet it never takes them
off again. That rule was written against defenders that **run** - four Defend fleets over Gamma
Hero II were being drawn off one at a time. A station cannot run. So the blanket blinder blinded
the swarm to the one hostile guaranteed to still be sitting there, and a siege that outlasted
its target's repairs orbited beside a live star fortress for the rest of its life.

Two things now take the blinders off, both stamped on the fleet each poll by
`ThreatGroundFronts.fightsForOrbit` via `ThreatFleetOrders.fightOrbit`
(`$threatinc_orbitFight`, one day, so it lapses on its own if the poll stops):

- **The orbit is held against the besieger** (`orbitContestedFor`) - a relief force, an ally's
  task force, the player in person, or a station heavy enough to win the contest.
- **A station flies there at all** (`stationFlies`), whatever it weighs. Losing the contest is
  not the test, and this is the case the user hit: a besieger that outweighs a star fortress
  still *holds* the space by `orbitHeld` and would leave it alone forever. A station is the one
  fortification orbit cannot grind down - `ThreatSiegeMalus.FORTIFICATION_IDS` is ground works
  only (ground defences, heavy batteries, patrol HQ, military base, high command) - so while it
  is undisrupted it goes on multiplying the world's ground defence and nothing else in the siege
  can touch it. Vanilla despawns the station fleet while the industry is disrupted and respawns
  it when the disruption ends, so "it still flies" *is* "it is no longer under repair".

The anti-chase rule is kept whole. Aggression is granted at the planet only, `ALLOW_LONG_PURSUIT`
and `$doNotGetSidetracked` are stripped with it, and the leash still walks the fleet back from
anything it follows out. When the station dies the flag stops being stamped, hold station
re-blinkers the fleet on the next frame, and the siege goes back to grinding - now against a
ground defence without the multiplier. `idleReason` says "fighting for the orbit" so the board
shows why the guns are cold.

Knob: the existing `siegeFightsForOrbit` (default on) - a doctrine that never fights for an
orbit still does not.

