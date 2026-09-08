# Ground war - eradication by ground victory

Design agreed with the user (Sept 2026, two sessions). This doc records what is
implemented, the judgment calls made, and the strategy-layer backlog. Every rate is a
config knob (settings.json / LunaLib).

## The model

**The colony is the fortress; the Core is the objective.** A size-S hive is S strata
deep with the Fabrication Core at the center. There is NO decline timer any more -
the old health-below-threshold decline engine is removed. Starvation and bombardment
only ever WEAKEN a colony; **eradication is a ground victory**: take every stratum,
destroy the Core, colony dies (`ThreatGroundFronts.groundVictory` ->
`ThreatColonyManager.eradicate`). This holds for NPCs too - purge expeditions land
their own fronts (below) - and, since 2026-09-05, **for the swarm as well**: Threat
strikes land Threat-owned fronts on inhabited worlds and can only kill a colony by
taking its last stratum ("Threat ground assaults" below). The rule is symmetric.

## The front (ThreatGroundFronts)

Marines + heavy armaments landed on a hive world ("Ground operations" on the military
options menu; commits everything aboard). Ticks on the colony poll at flat rates:

- **Supply**: armaments burn per MARINE per 30 days (`frontArmamentsPerMarinePer30Days`,
  0.25 - x`frontPushUpkeepMult` while
  assaulting). The stockpile IS the countdown; a dry front fights at
  `frontDryEffectivenessMult` (0.5) effectiveness AND attrits at
  `frontUnsuppliedLossMult` (3x) - "not enough armaments" is exactly the
  reinforce-before-pushing moment.
- **Effectiveness** = troops x entrenchment x supply factor, vs the world's CURRENT worn
  defense figure (`MarketCMD.getDefenderStr`). **Entrenchment is the attacker's cover, and
  it is earned** (user, 2026-09-06): a landing fights at `frontLandingMult` (0.75) and digs
  in to `frontEntrenchMaxMult` (1.0) over `frontEntrenchDays` (30) of holding - the ramp is
  frozen while pushing - and taking a layer or being battered by a counter-attack keeps only
  `frontEntrenchKeptFraction` (0.5) of it. Pushing costs cover, holding earns it. The
  defenders have no entrenchment number: their ground-defense figure IS their entrenchment.
- **Suppression states**: HOLDING (>= `frontHoldFraction`, 0.17, of the defense figure -
  lowered from 0.25 when the 1.5x entrenchment bonus went, so the old landing sizes still
  hold) suppresses Core/Nexus/port/defense structures by feeding their disruption clocks
  (the existing wear mechanic); GRINDING (>= `frontGrindFraction`, 0.10) harasses just
  the defense structures at half rate; FOOTHOLD suppresses nothing.
- **What drives a disruption clock** (2026-09-06): orbit besieges, day by day, to the
  orbital floor and never past it - a Threat strike over a colony, a faction's or the
  player's siege expedition over a hive, a Support sortie over either, and the player's
  own tactical bombardment (one slice of `siegeBombardSliceDays`, 3) all run the same
  duel ("Sieges from orbit" below). Saturation on a hive still writes `hiveSatDisruptDays`
  (20) to everything. Marine raids add their days (vanilla). A HOLDING front adds
  `frontSuppressDaysPerDay` (2) per day against the clock's own run-down, so a net day per
  day, up to a cap of 1.2 x `defenseWearDays`. Orbit alone therefore holds the defenses at
  the floor and no deeper; the wear curve below it is the ground forces' work. Danger close
  (a tactical pass with your own front down) lands `hiveTacDisruptDays` (60) on the Core
  and port too.
- **The Core wears in proportion** (2026-09-05): its fabrication factor is `coreDownFactor`
  (1.0) x (1 - clock / `defenseWearDays`) - one pass leaves 80 percent, a front holding for
  150 days half, 300 days nothing - and the hive's health, growth and counter-attack
  interval follow it smoothly instead of quartering the moment the Core is touched. A
  missing or unbuilt Core fabricates nothing. Swarm fabrication stays a hard gate: no new
  Defense Swarms while the Core or the Nexus is disrupted at all.

## The stratum campaign

- **Push** (automatic when troops land at HOLDING strength - `autoPush`, knob
  `frontAutoPush` - or ordered by the player; requires HOLDING strength): takes
  `frontPushBaseDays` x (defense/strength ratio, clamped 0.5-3x) days, at
  `frontPushLossPer30Days` (0.30) attrition - pushing is how you win and how you
  bleed. A pushing front is exposed (no cover) and its entrenchment ramp is frozen.
- **Stratum taken**: strips one size-worth of the base defense (SwarmNexus) and one
  size-share of fabrication output (`computeFabricationMult`) - momentum is real,
  and each stratum weakens the colony. Growth halts entirely while a front is on
  the surface.
- **Checkpoint**: after each stratum the front CONSOLIDATES for
  `frontCheckpointDays` (10), announces a reinforcement request, and - told
  nothing - **pushes on by doctrine**. Orders at the checkpoint: push now,
  entrench and stand fast, or withdraw (docking gates enforce that pulling out
  requires the space over the planet).
- **Counter-attacks**: every `frontCounterAttackDays` (40) / colony health - so a
  starved hive attacks 4x more rarely, which is what economic strangulation buys
  now. Attack = current defense figure vs the front's strength x its cover when dug in
  (1 + (`frontEntrenchDefenseBonus` - 1) x how dug in it is, so up to 1.5). Losing costs
  `frontCounterAttackLossFraction` troops and a held layer, and spoils half the
  entrenchment either way; a beachhead beaten 2:1 with no layers is overrun.
- **The final stratum** destroys the Core: eradication, survivors evacuated
  (player fronts), announced.

## NPC fronts

Purge expeditions (`ThreatPurgeFGI`) sail only from a **mobilised** faction's base, drawing
that base's war reserve (2026-09-05: `IncursionManager.tryPurgeBombardments` skips factions
not in war mode; the old abstract purge from a faction never struck is gone, as is the
player's purge commission - the faction view's Siege button is the one way to raise one).
**They fight for the orbit first** (2026-09-05, `ThreatPurgeFGI.SiegeRaidAction`, knob
`siegeFightsForOrbit`): while Defense Swarms hold a target's orbit no siege pass is delivered
there, and the expedition's fleets **at that world** are made aggressive and un-blinkered so
they hunt the swarms down.

**The fight for an orbit ends at that orbit** (2026-09-07, `ThreatFleetOrders.siegeLeash`,
knobs `siegeHuntRange` 1500, `siegeLeashRange` 6000, both theatres). Vanilla is why a human
expedition stays on its target and it is one flag: `FGRaidAction` refreshes
`$doNotGetSidetracked` on every fleet every 0.4 days, and raid fleets never go looking for a
fight. This stripped that flag off **every** fleet in the system for as long as **any** target
was contested, and a Threat fleet is born `MAKE_AGGRESSIVE` with `ALLOW_LONG_PURSUIT`
(`DisposableThreatFleetManager`), so one defender that ran drew the whole expedition after it.
Once it left, nothing friendly was near the world, so "contested" read true forever and the
aggression renewed itself daily - a Thanatos strike spent three weeks at 0/3 passes chasing one
fleet across the sector, with the intel stuck on "suppressing the defences" the whole time.

Now the leash decides, per fleet, per tick: inside `siegeHuntRange` of a **contested** world it
hunts - the same 1,500 units the contest itself is measured over, so a defender that breaks off
is one there is nothing left to fight for; anywhere else vanilla's blinkers go back on and
aggression comes off; beyond `siegeLeashRange` of its nearest target, or out of the system
where the raid's own `MilitaryResponseScript` cannot reach it, it drops what it is doing and is
sent back. `ALLOW_LONG_PURSUIT` is stripped at spawn *and* every tick, so a fleet already
chasing in an old save lets go. In-system, that flag hygiene is the whole fix: vanilla's
response scripts walk a fleet back to the target on their own once nothing overrides its
heading. **Who holds an orbit is a strength contest** (2026-09-07,
`ThreatGroundFronts.orbitHeld`, knob `orbitContestFraction` 0.5, both theatres): the armed
fleets hostile to the besieger within 1,500 units of the planet - a hive's Defense Swarms, a
colony's station and patrols - hold it only while their fleet points are at least that
fraction of the besieger's own warships there; with nothing friendly there, any defender holds
it. Before this a hive's orbit was contested while ANY garrison fleet was alive anywhere in
its list, and the hive refills that list every poll (local respawn, sibling redistribution),
so four Defend fleets of 850 points over Gamma Hero II sliced on the rare poll with no swarm
alive and looked as if they bombarded for nothing. `orbitContested(marketId)`, the plain
head-count, stays for the convoy refuse rule: an unarmed run is turned back by any swarm. Vanilla's raid action re-flags its fleets every tick not to get sidetracked,
which is why a single swarm ship used to stall a siege for its whole stay - the landing gate
refused every pass and each fell through to a commando raid at ~1,200 marines (pirates and
the player alike, Gamma Gibidigi, seen in the log). The stage's vanilla time limit still
bounds the hunt; an expedition that cannot clear the orbit withdraws having done nothing,
which is the honest outcome. Once the orbit is clear, and the war-strata are at the orbital
floor or the troops could hold as they are (`ThreatGroundFronts.readyToLand`, the strike's
gate), they land an NPC-owned front (troops = combined expedition ground strength,
armaments = `npcFrontSupplyDays` of that force's own burn) instead of their first commando raid.
The front runs a stance AI - push when strong enough and supplied, entrench
otherwise - through the same checkpoint pacing. When its supply runs dry it breaks off any
assault and digs in for its convoys (2026-09-06), calls for a pickup once it is also below
grind strength, and withers meanwhile; follow-up expeditions land fresh fronts. This is how factions erase hives without
the player. The player cannot direct or resupply an NPC front (dialog shows status
only), and cannot land where one already fights.

## Threat ground assaults - the rule is symmetric

Built 2026-09-05, untested in-game. **The swarm no longer erases worlds from orbit.**
Saturation bombardment is gone from Threat strikes (knob `strikeSaturationEnabled`,
default FALSE, restores the old behaviour verbatim). A strike now does to an inhabited
world exactly what a purge expedition does to a hive - and for the same reason: *only a
ground victory kills a colony, in either direction.*

### The doctrine: besiege, land, reinforce (reworked 2026-09-06, untested)

`launchStrike` leaves `raidParams.bombardment` null - that is what routes each pass into
`ThreatStrikeFGI` instead of vanilla's bombardment path - budgets `strikePassesPerColony`
(3) passes per world in the sweep, and gives every world in the sweep `siegeOrbitDays`
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
2. **Land.** The world is ready (`readyToLand`: every defence structure at the orbital
   floor, or the landing could hold as it is - `troops x frontLandingMult >=
   holdRequirement`) and nothing blocks the landing (`landingBlocked`: saturation fallout -
   `FALLOUT_FLAG` binds the swarm too - another army on the ground, or, with real fleets,
   the orbit held against it): the pass puts the world's share of the troop pool on the
   surface as a `Factions.THREAT` ground front.
3. **Reinforce.** A pass over the swarm's own front lands whatever of the world's share
   is still aboard - a top-up, never a schedule: **a Threat front's real reinforcement is
   the next expedition**. A dry front signals for it (`wantsExpedition`), and
   `IncursionManager.pickStrikeTarget` weighs that world `strikeReinforceWeight` (4x) as
   the next strike's target; the expedition that answers resupplies the front on its pass.
   There is no convoy layer behind the swarm.

A pass that lands nothing - the share spent, fallout on the ground - is still a pass
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
  sweep)`, and a world never receives more than its share across all its passes
  (`landedAt`). A small strike lands fewer worlds, not token forces.
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

### Sieges against human factions (built 2026-09-06, untested)

The human side used to lean on vanilla, and vanilla's shape is a binary switch under a
multiplicative stack: Chicomoztoc is 500 base x 3 (star fortress) x 2.5 (batteries) x 1.1;
kill the fortress and disrupt the batteries and the 3 and the 2.5 vanish in one step, for
365 days, from one free bombardment. The rework keeps the asymmetry - a hive is dug out,
a colony is besieged - on one skeleton, and splits what vanilla lumps together:

**Defenders = garrison x fortification.**

- **Garrison is people**: vanilla's base by size x stability, x the districts still held
  (`colonyGarrison` - vanilla's own figure asked with `forBombard = true`), plus the colony's
  **armed** stockpile marines times what their experience is worth. Orbit cannot touch either.

  The marine half deliberately does NOT go through vanilla's `getDefenderStr(market, false)`.
  That form counts marines in **both** the resource stockpile and personal **Storage**, flat and
  at 1:1, and the war has no business in personal Storage: the stockpile is the war chest the
  Waystation, staging and sieges all read; Storage is the player's own (user, 2026-09-08).
  Taking the marine term from `ThreatReserves` instead keeps three things true at once - Storage
  stays out of the war, the player and NPC paths are one arithmetic rather than two, and **every
  marine the figure counts is one the siege can actually kill**. Storage marines still defend
  against vanilla's own raids; that is vanilla's business, left alone.
- **Fortification is hardware**: each defence structure's vanilla multiplier (Ground
  Defenses x2, Heavy Batteries x3, Patrol HQ / Military Base / High Command x1.1 / 1.2 /
  1.3) scaled by its **condition** - 1 intact, falling in a straight line to 0 at
  `fortificationDisruptDays` (180) on its disruption clock - instead of vanilla's on/off,
  and x vanilla's own input-deficit factor, so a starving battery gives less either way.
  From orbit alone the condition never falls below `fortificationOrbitFloor` (0.5); once a
  front stands on the world the floor is 0 and a holding front wears the structures down
  as it always did. Boots finish what orbit started.

### Marines defend, and they die

Before 2026-09-08 a colony's defence was a **fixed wall**: it fell only when a structure was
suppressed or a district seized, never from the fighting itself. Marines parked in the stockpile
were a permanent, free, non-attritable multiplier on it, and they entered the colony's
counter-attack at full weight. The reported failure was exact: 5,000 marines dropped into a
besieged colony's stockpile turned a losing siege into an instant win, because the counter-attack
figure went from ~1,200 to ~6,200 in one tick and overran the beachhead outright. Four rules
answer it, and they only compose properly together.

**1. Marines are called up, not conjured.** Only `ThreatReserves.armedMarines` defends: a figure
that ramps toward the stockpile over `marineArmingDays` (20), clamped down the instant stock
leaves. A colony's standing marines are already armed - the ramp is seeded from the stock the
first time the colony is seen, so nobody is caught with their rifles in crates - but a regiment
shipped in mid-siege buys a garrison over weeks. The ramp runs on its own clock (`marineArmedAt`)
so the reserve poll and the front tick can both drive it without double-counting; the front tick
drives it too because a colony can be invaded by someone it is not formally at war with, and the
reserve poll only walks warring factions.

The seed is taken at **game load** (`ThreatReserves.seedMarineArming`, from
`ThreatIncModPlugin.onGameLoad`) and again before any deposit (`ensureMarineSeed`, called from
`deposit`), never lazily on first sight. Seeded lazily it captured whatever the stockpile held the
first time the colony happened to be walked - so on an upgraded save, marines delivered before that
first tick were grandfathered in as a standing garrison and skipped the ramp entirely. The tell was
a counter-attack cadence collapsing from 66 days to 3 the moment marines were added (user,
2026-09-08): the tempo term was reading the armed count correctly, and the armed count was wrong.
Note this cannot be repaired retroactively - once a delivery has been seeded in as a garrison there
is no record of what the colony held before it. Shipping the marines out and back in re-ramps them.

**2. Holding a line and going over the top are different jobs.** `defenderStrength` and
`counterAttackStrength` are now separate questions on the `Theatre`. Marines enter the first
whole and the second at `marineCounterAttackMult` (0.25); the garrison proper enters both whole.
A hive still counter-attacks with everything, having no marines to hold back.

**3. The defenders bleed.** `defenderLossPer30Days` (0.20) of the armed marines per 30 days,
scaled by the front's pressure (`effectiveStrength / holdRequirement`, capped at 1), plus
`defenderCounterAttackLossFraction` (0.15) every time the colony counter-attacks - **win or
lose**. Bouncing off a dug-in front used to be free; it is now the expensive case. The bleed is
deliberately *not* gated on the front's state: gating it on HOLDING or GRINDING would let a
colony switch the cost off by reinforcing past the threshold, which is precisely the move this is
meant to charge for. All of it is drawn through `ThreatReserves.spendDefendingMarines`, so the
armed count, the stockpile and the veterancy pool stay consistent.

**3b. Troops to spare buy tempo.** The cadence used to read stability and nothing else:

    interval = frontCounterAttackDays (40) / (stability / 10),  then / 1.5 with a military command

so a colony with 5,000 marines went over the top exactly as often as one with 50, and
reinforcing a besieged world bought strength but never initiative. `counterAttackTempo` now
divides that interval by the force ratio the counter-attack actually fights at -
`counterAttackStrength / effectiveStrength(front)` - clamped both ways by
`counterAttackRatioClamp` (3.0). A world that outmatches the beachhead threefold hits three
times as often; one being overrun three-to-one manages a third as many. Set the knob to 1 for
the old stability-only cadence.

It is deliberately the fighting ratio and not a raw headcount, so a colony that has spent every
marine still musters its garrison rather than falling silent. And it pairs with the cost above:
more tempo means more attempts, and every attempt spends marines, so a colony that hugely
outnumbers a front wins quickly **and pays for it**. That pairing is what stops the reinforce-
and-forget move - the rescue works, but it burns the regiment that made it work.

Hives keep pacing on vitality alone (`ThreatColonyManager.computeHealth`): a hive has no
garrison to spare or withhold, so there is no ratio for the term to read.

**4. Troops have quality.** See Veterancy below.

The consequence to keep in mind: the defence figure now **moves every tick**, where it used to be
a wall. `frontStateHysteresis` (0.1) exists because of that - a front sitting on a threshold would
otherwise cross it back and forth and announce every flip.

### Veterancy

On vanilla's own shape, in `ThreatMarineXP`. Vanilla keeps one XP pool for the player fleet's
marines (`PlayerFleetPersonnelTracker`): an absolute `xp` clamped to the headcount, so
`level = xp / num` in [0, 1], and the rank is only a **label on a ramp** - Regular below 0.25,
Experienced below 0.5, Veteran below 0.75, Elite above. The effects are continuous, not stepped.
There is no Green marine: `REGULAR` merely reuses the green *crew* icon.

The mod holds the same model for the two pools vanilla has no opinion about - a front's marines
(`GroundFront.xp`) and a colony's stockpiled marines (`ColonyReserve.marineXp`) - and carries
over three of vanilla's rules deliberately:

- **A hard fight teaches; a curbstomp teaches nothing.** `xpGain = (1 - effectiveness) x
  headcount x marineXpPerBattle`, so the side that was outmatched learns the most. This is
  vanilla's own raid formula, and it is the opposite of the intuitive win-to-level-up.
- **Losses preserve the level.** Casualties scale the pool down in proportion; survivors are not
  promoted for surviving. Earning XP is what promotion is for.
- **Reinforcement dilutes.** Nothing to implement - the headcount rises while the pool does not.
  This is what stops a mass of raw marines being worth its headcount.

Effects are `marineVeterancyEffectMax` (1.0, up to +100% strength) and
`marineVeterancyLossReduction` (0.5, down to half casualties) - vanilla's own figures.

**Two-way with the player fleet** (`marineFleetXpTransfer`, on). A landing inherits the fleet's
marine rank; a withdrawal or evacuation writes back what it earned, *after* the bodies are in the
cargo, because vanilla clamps the pool to the headcount and the order is load-bearing. Green
survivors coming home dilute a veteran fleet and veterans lift a green one - the same arithmetic
vanilla applies when the player recruits. The same switch wires up `Stats.PLANETARY_OPERATIONS_MOD`
- Planetary Operations and Tactical Drills - which the ground-front engine **did not read at all**
before this: a player with the skills fought exactly as well as one without. NPC and Threat
landings muster at `npcLandingVeterancy` (0.25) instead.

An NPC front evacuating to a base seasons that base's garrison. A player **outpost** has no
veterancy pool: outposts are not economy markets and are never a siege target, so there is nothing
for a rank to modify - marines garrisoning one keep their bodies but not their record.

**One vocabulary.** Every pool - a player front, an NPC front, a Threat front, a colony's
garrison - is named with vanilla's four: Regular, Experienced, Veteran, Elite.

A swarm-specific ladder (Fresh / Adapting / Adapted / Apex) was built on 2026-09-08 and
**reverted the same evening**: "fresh troops" reads as *rested*, not *inexperienced*, which is
the opposite of what the bottom rung means (user, on seeing it in the tooltip). One scale the
player already knows from their own cargo beats a second one that has to be learned - and the
maths was identical either way, so the only thing the second vocabulary bought was ambiguity.

**Hives, as DEFENDERS, have none of this.** Hive strength is structural - `hiveDefensePerSize` x
strata x structure condition - and it already erodes by losing strata, which is why rules 1-3 read
as parity rather than asymmetry: they bring human colonies up to the standard hives were already
held to. A hive garrison has no headcount for a level to divide by. A Threat front standing on a
human colony is a different thing entirely and does season - a landing that has survived two
months of counter-attacks is genuinely harder, which is the reason to hit one early rather than
let it mature.

`npcLandingVeterancy` is 0.15, not 0.25: vanilla's thresholds are UPPER bounds, so a landing set
to exactly 0.25 arrives Experienced/Adapting with no room to grow into the tier. 0.15 lands
inside Regular/Fresh.

### To verify - marines and veterancy (2026-09-08, built, untested)

1. Drop marines into a besieged colony's **resource stockpile**: the Defenders figure should NOT
   move that tick, and should climb over ~20 days as they are armed. The front row tooltip says
   how many are still being armed.
2. Drop marines into personal **Storage** instead: the Defenders figure should not move at all,
   ever. (Vanilla's own raid dialog will still count them - that is correct and left alone.)
3. The front row tooltip's counter-attack figure should be visibly lower than its Defenders figure
   on a colony holding marines, with the x0.25 line explaining why, and an 'it costs them N
   marines to mount' line beneath.
4. Watch a besieged colony's Marines cell fall over a siege, and check the faction view's Def cell
   agrees with the fronts table - they contradicted each other before (Def counted Storage,
   Marines did not).
5. Land marines from a fleet with a Veteran rank: the front's Troops line should read Veteran, not
   Regular. Withdraw them and confirm the fleet's own rank moves the right way.
6. Reinforce a long-dug-in front: both its rank and its entrenchment should visibly dilute.
7. Confirm an EXISTING save loads and colonies are not caught with 0 armed marines - the seeding
   path (`marineArmSeeded` false on old saves) must mean 'already armed', not 'none armed'.
8. Confirm the colony screen's ground-war tooltip explains the gap between vanilla's own Ground
   defenses readout (garrison only) and the siege's Defenders figure.
9. Every front's Troops line reads the vanilla four (Regular / Experienced / Veteran / Elite),
   the Threat's included - there is no second vocabulary.
10. Fronts already in a save read level 0 (the field is new), so they show a bare rank with no
    percentages until they have fought. That is correct, not a bug.
11. Reinforce a besieged colony and watch the front tooltip's 'Next counter-attack in N days'
    SHORTEN, with the Cadence line saying why. Before this it never moved.
12. Land a fleet of Veteran marines and confirm the front reads Veteran immediately - the level
    is now read before the cargo is emptied, which is the bug that made every landing green.

The carrier is **`ThreatSiegeMalus`** (`threatinc_siege_malus`) - a hidden, unbuildable,
undisruptable structure modelled on `WarFootingDemand` - installed on a colony the swarm
has **besieged** (`$threatinc_besieged`, written by every siege slice, expiring after
`fortificationDisruptDays`) while any defence structure is suppressed, or on any colony
with a front, and removed otherwise (`ThreatGroundFronts.syncSiegeState`, swept every poll
by `sweepSieges` so the condition tracks the clock). Raids and bombardments elsewhere in
the sector stay vanilla. It restores a disrupted structure's multiplier scaled by its
condition under its own key (vanilla stripped it whole - the colony's Defenses tooltip
reads "Heavy Batteries (suppressed, 60% effect)"), applies the district loss
`GROUND_DEFENSES_MOD modifyMult((size - held) / size)`, and - each district held - takes
`districtStabilityPenalty` (1) stability and `districtAccessPenalty` (0.1) accessibility.
The player's own tactical bombardment of a colony is a siege slice like any fleet's
("Sieges from orbit" below).

**The orbital siege is a duel** (`ThreatGroundFronts.siegeSlice`). Each day an unopposed
Threat fleet of F points (x `siegeFPWeight`, 1.0) sits over a colony of defence D
(`getDefenderStr(market, true)`, no cargo marines):

- its defence structures gain `siegeSuppressDaysPerDay` (6) x F / (F + D) disruption days,
  never past the floor (`floorDays` = `fortificationDisruptDays` x (1 - floor), 90); the
  moment the fleet leaves, recovery starts from there;
- the batteries answer: the fleet loses `siegeBatteryAttritionPerDay` (0.02) x F x the
  batteries' share of D (1 - 1 / their multiplier) / (F + D) points, taken off the live
  fleet smallest ship first (`applyFleetLosses`, the remainder banked in fleet memory, the
  last ship spared - the group's own abort rule takes a gutted expedition home).

So Chicomoztoc after its fortress dies (D ~1,375) takes a 300-point fleet about 170 days
and costs it most of its ships, a 1,500-point armada ~60 days and ~500 points; Nomios (D
~95) falls to the floor in a week for a handful of points. Every defence building has a
job: batteries hurt the fleet, Ground Defenses slow suppression, the fortress contests
the orbit, a military command speeds counter-attacks, reserve marines deepen the garrison.

**Districts** are the human word for the layers a front takes (strata stay hive-only;
`Theatre.layer`). Each district held, besides the garrison share and the stability and
accessibility above, **seizes** held / size of the colony's other disruptable industries
(`seizedIndustries`, by id so the same ones stay seized), pinned at `districtSeizeDays`
(30) of disruption while held and recovering that fast once the district is retaken. A
HOLDING front still feeds the clocks of every `TAG_TACTICAL_BOMBARDMENT` industry plus
the port as before; a GRINDING one harasses just `grounddefenses` / `heavybatteries` at
half rate.

| | Hive | Human colony |
| --- | --- | --- |
| Layers | strata, underground, one per size | districts, one per size |
| Losing a layer | fabrication and per-stratum defence fall | garrison share, stability, accessibility fall; the district's industries are seized |
| Orbital bombardment | suppresses the war-strata to the floor, the size-anchored strata untouched, weapon growths fire back | suppresses fortification to the floor, garrison untouched, batteries fire back |
| Counter-attacks | paced by hive health | paced by stability and military command; strength is the garrison |
| Victory | the Core dies: eradicated, free outpost | the last district falls: a hive is seeded on the spot |

### Sieges from orbit - one duel, both theatres (2026-09-06, untested)

The user's call the same evening: the orbital duel is the doctrine for everyone, not the
swarm's alone. `ThreatGroundFronts.siegeSlice` now asks the `Theatre` for what orbit
suppresses and what answers:

| | Hive (`Theatre.HIVE`) | Human colony (`Theatre.COLONY`) |
| --- | --- | --- |
| Fortifications | Ground Defenses / Heavy Batteries, Swarm Nexus | Ground Defenses, Heavy Batteries, Patrol HQ, Military Base, High Command (`ThreatSiegeMalus.FORTIFICATION_IDS`) |
| Clock (condition 1 -> 0) | `defenseWearDays` (300) | `fortificationDisruptDays` (180) |
| Condition carrier | the organs themselves (`ThreatColonyManager.disruptedDefenseResilience`, now a straight line with the orbital floor - the old `disruptedDefenseFraction` step is gone) | `ThreatSiegeMalus` |
| Floor from orbit | `fortificationOrbitFloor` (0.5) of the bonus, 0 once a front stands | the same |
| Suppression rate (net of the clock's run-down) | `hiveSiegeSuppressDaysPerDay` (30) x F / (F + D) | `siegeSuppressDaysPerDay` (6) x F / (F + D) |
| Batteries' share | the two defence structures' multipliers on the hive's machinery/metals deficit | the two batteries' multipliers on vanilla's deficit |
| Return fire | `siegeBatteryAttritionPerDay` (0.02) x F x share x D / (F + D), smallest ship first, flagship spared | the same |

A disruption clock runs down a day per day (vanilla's expiry), which the first build of the
duel missed: a live fleet's slice now makes up the days the clock lost since its last slice
and then adds the rate x F / (F + D), so the rate is net progress and a fleet that stays holds
the clock where it is; an instantaneous slice (the player's bombardment, the abstract siege)
makes nothing up. The hive rate is higher because a hive's figure is anchored to its size
and runs several times a colony's: a 600-point expedition takes a size-5 hive (~9,000) to
the floor in about 60 days, as 1,000 points take a 4,000 colony in about 75 - and both lose
most of their ships doing it. Who delivers slices:

- **A Threat strike** over a colony (`ThreatStrikeFGI.siegePass`, as before).
- **A faction's or the player's siege expedition** over a hive
  (`ThreatPurgeFGI.SiegeRaidAction.performRaid` -> `siegePass` / `abstractSiege`): the
  flat 60-day tactical pass is gone; a live fleet slices while the strata are above the floor
  and the troops could not hold, spending no pass, and lands once `readyToLand` says so. An
  unspawned expedition runs its whole siege abstractly first (`ABSTRACT_FP_PER_POINT` x
  its difficulty points, less route damage; the batteries' toll comes off its marines and
  armaments - the strike's off its troops aboard), and a pass over a world still above the
  floor returns before vanilla counts it (`waitsAboveFloor`; vanilla's `performRaid` counts
  the pass before it asks what to do with it, so the check cannot live in
  `doCustomRaidAction`). The expedition's payload stage is `siegeOrbitDays` (120) per world,
  as the strike's is.
- **A Support sortie** (Escort until today) on station over a hostile world whose orbit
  nothing holds against it: every poll, `tickSupport` delivers its live fleet points as a
  slice and the batteries answer. A front on the ground does not stop it - the floor still
  holds from orbit, but the sortie keeps the fortifications there while the front pushes.
  Over a contested orbit (a colony's station or patrols, a hive's Defense Swarms) it fights
  instead and its row reads "clearing the orbit of X" until it is clear. NPC factions fly
  Support too (`ThreatConvoys.supportFor`).
- **The player's tactical bombardment** (`ThreatincMarketCMD.bombardTactical` /
  `bombardConfirm`, hive or colony): the targets are the fortifications above the floor, the
  prompt quotes the days each gains and the fleet points the batteries take, vanilla's fuel
  (a hive's `hiveTacCostFraction` bill), reputation and unrest flow runs, and the 365 days it
  wrote are taken back for the slice. Ships lost are named in the result; the flagship is
  never one of them. Nothing to suppress: "X's defences are suppressed as far as orbit can
  push them." A world with no fortification at all (nothing the duel describes) keeps
  vanilla's bombardment; Lion's Guard HQ is not a fortification and is left alone.

The landing gate is one rule everywhere (`readyToLand`): the fortifications are at the floor
(`suppressedToFloor`, every fortification at `siegeFloorDays`) or the troops x
`frontLandingMult` could hold. The second branch went round a loop on 2026-09-06: the user
first asked why any commander would not soften the ground for the troops when orbit can, then
recalled that every day in orbit costs ships to the batteries - a force that can already
hold spends marines rather than hulls, and the Support button is there when that call is
wrong. The original rule stands. `needsSoftening` is the floor test's complement on both
theatres.

**How long a fleet stays, and what it does there** (the user's question, 2026-09-06 evening,
after a strike dropped its marines and left): an expedition's fleets deliver a slice on each
raid pass (vanilla's own cadence, about every 3 days per fleet) while the world is above the
floor and the troops could not hold - so a strike whose troops could hold on arrival lands at
once and never bombards, which is what "disrupted 1 day from marines" was: the front's own
suppression, no orbital siege, and the intended trade (see the landing gate above). The
passes are the landings: `strikePassesPerColony` (3) per
world, the first a landing, the rest reinforcements, and once they are spent or
`siegeOrbitDays` (120) run out vanilla moves the group on to the next world in the sweep.
Every slice plays vanilla's bombardment burst on the planet (`MarketCMD.addBombardVisual`,
once per whole day of suppression added per world, so a slice that adds a tenth of a day
is not a bombardment - only for a player in the system).

**The landing fleet stays, on Defend** (2026-09-07, the user, after watching an expedition
land at Gamma Hero II and then pay the batteries over Gamma Hero I with nothing left to
land): the fleet whose pass lands or reinforces a front leaves its expedition and holds the
orbit over that world with no term - a faction's as an indefinite **Defend** order
(`ThreatFleetOrders.adoptLandingDefend`, shown on the board with Recall), the swarm's on the
`ThreatSwarmDefend` list - and every other expedition fleet in the system with nothing left
to land goes with it (`ThreatPurgeFGI.stayOnDefend`; a fleet still carrying a landing sweeps
on). Defend is not Support: it fights whatever contests the orbit but bombards only while
the front it covers cannot hold **or would not survive the next counter-attack**
(`ThreatGroundFronts.defendBombards`: the faction's own front, and the fortifications not
yet at the floor), so a front that holds *and is safe* costs it nothing. It stands down when the front is gone (won or
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
while the orbit is clear it bombards the defences at about N disruption days per day to no
lower than the floor; the batteries answer at about M fleet points of ships lost per day,
smallest first - N and M from `siegeSliceEstimate` at the fleet's points against the
world's defence figure; and that it bombards whether or not the front could hold, where
Defend bombards only while it cannot and an expedition lands as soon as its troops can, to
spare its ships. The Defend button (`defendEffect`) says the same the other way round: it
does not bombard while the front holds, and only while it cannot does it bombard - and only
then do the batteries answer. The expedition's own status line
says which branch opened its landing gate (`ThreatGroundFronts.landingPhase`: "moving to
land - defences at the floor" or "moving to land - the troops can hold, sparing the ships").

### Fabricating troops from the fleet (2026-09-08, built, untested)

The user, after watching the Defend behaviour they liked run out of road: a covering
fleet bombards while its front cannot hold - but the bombardment stops mattering at the
orbital floor, and a losing front then just watches a full fleet sit in orbit doing
nothing. So **once there is nothing left to bombard, the fleet starts sending fragments of
itself down as ground troops instead.** Hulls broken up into soldiers.

The trigger is the exact complement of the one that was already there
(`ThreatGroundFronts.defendFabricates`):

| | `defendBombards` | `defendFabricates` |
| --- | --- | --- |
| Its own front on the ground | cannot hold | cannot hold |
| Fortifications | above the floor | at the floor (`suppressedToFloor`) |

Both are false while the front holds, so the two states tile the "front in trouble" case
between them and neither runs while the ground is safe. Four rules the user set, and where
each one lives:

1. **Only while the front cannot hold.** `defendFabricates` is re-evaluated every poll and
   `fabricateNeed` returns 0 the moment the front is over the line, so the fleet stops
   cutting itself up the same poll the ground is safe and goes back to ordinary Defend -
   which, at the floor, means holding the orbit and paying nothing.
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
4. **The price is the undisrupted batteries.** `fabricateCost` is `siegeSlice`'s own return-fire
   formula - `siegeBatteryAttritionPerDay x fp x share x D / (fp + D)` - read at **full**
   condition instead of the suppressed one, charged on top of the hulls that became troops
   and banked as ordinary battery damage. The fragments go down through defended air, so
   the guns get the shot they would have had on the first day of the siege even though they
   are ground to the floor.

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
`fabricateSupplyDays` (30). Off, a Defend fleet at the floor simply holds the orbit as before.

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

### The planetary shield (2026-09-07, built, untested)

The mod owns the `planetaryshield` industry outright (`ThreatPlanetaryShield`, swapped in
through `data/campaign/industries.csv`). Vanilla's shield multiplies the garrison by three and
does nothing about bombardment; that is backwards here on both counts, so the ground-defence
bonus is off (`threatinc_shieldDefenseBonus`, default 0) and cover is what the shield buys
instead. Set the knob to 2 for vanilla's figure and it is applied in proportion to the
shield's condition, never as vanilla's on/off switch - nothing in this mod gets a cliff.

`ThreatIncModPlugin.configureXStream` aliases the plugin to vanilla's class name, so a save
never mentions `threatinc.ThreatPlanetaryShield` and still loads with this mod removed; the
class therefore carries **no instance fields**, and `getDisruptedKey` is pinned to vanilla's
key so a shield's disruption clock survives the swap in both directions.

A planetary shield is a fortification that protects structures instead of the garrison. It
wears on the theatre's own clock - `fortificationDisruptDays` on a colony, `defenseWearDays`
on a hive - through the same `Theatre.condition` curve every battery uses, so its state is
read exactly as a suppressed gun's is. What its condition buys is **cover**:

    absorb     = shieldAbsorbMax x condition(shield)        // 0.75 x 1.0 intact
    throughput = 1 - absorb                                 // what still gets through

Every disruption a bombardment would write on anything else is multiplied by `throughput`
(`ThreatShield.throughput`). The shield itself has no cover and takes each pass at full
weight times `shieldSoakMult` (`soak` for the orbital slice, `soakTo` for a raise-to pass).
So a bombardment spends itself twice: an intact shield turns most of a strike aside, and
each pass buys less cover for the next. Grind it down and the world is bare.

The orbital floor applies to the shield like anything else, so `siegeFloorDays` caps what a
fleet in orbit can spend: at `fortificationOrbitFloor` 0.5 a besieged shield stops at 50%
condition and keeps absorbing 37.5%. Boots take it the rest of the way - `tickFront`'s
suppression grinds the shield at exactly the rate it grinds the guns (`suppressShield`, full
rate while HOLDING, `frontGrindSuppressMult` while GRINDING, capped at `wearDays x 1.2` like
any other structure), and `floorApplies` is false while a front stands, so the condition curve
has no floor and the cover goes to nothing. That is the whole counterplay: **a shielded world
cannot be broken from orbit alone** - land, or bring enough that partial suppression is enough.

The shield is deliberately NOT in `keyStructures` / `defenseStructures`; those lists also
answer "are this colony's defences held", which the shield does not speak to. It is suppressed
by its own call beside them.

**The ground-defence cliff, and why owning the plugin is the fix.** Vanilla's
`PlanetaryShield.apply()` multiplies the garrison by three (x1.5 more with an alpha core,
x1.25 improved) and drops *all* of it the instant the shield is disrupted, because
`BaseIndustry.isFunctional()` is false and `apply()` then calls `unapply()`. Nothing tripped
that before, since no bombardment could disrupt a shield. Left alone it inverts the whole
mechanic: one pass would cut a shielded world's defence by two thirds, so a shielded colony
would be *easier* to besiege than an unshielded one - cheaper fuel, a better ratio on every
later slice, and `holdRequirement` cut by the same two thirds. This was briefly patched from
`ThreatSiegeMalus`; owning the plugin removed the need, because `ThreatPlanetaryShield.apply`
never writes the cliff in the first place. Worth remembering as a general rule: **making
anything a new disruption target in this mod means checking what vanilla's plugin does when
`isFunctional()` goes false.**

Where it hooks - every write, no exceptions:

| Site | What changes |
| --- | --- |
| `ThreatGroundFronts.siegeSlice` | fortifications take `add x throughput`; the shield takes `add x shieldSoakMult`. `throughput` is read once, before the loop, so one slice sees one shield state |
| `ThreatGroundFronts.siegeSliceEstimate` | `[0]` is already cut by the shield, so every caller quoting suppression gets the true figure; `[2]` is what the pass puts on the shield |
| `ThreatincMarketCMD.applySaturationDisruption` | raise-to `dur x throughput`, the shield raised to the full `dur` |
| `ThreatincMarketCMD.applyDangerClose` | the deep organs are under the shield too |
| `ThreatincMarketCMD.bombardTactical` | the shield is a bombardment target in its own right while it is above the floor, and `siegeSliceApplies` now fires for a shielded world with no guns at all. It is printed on its own line, not under the shared "about N days each" headline, because it takes a different figure - clamped to the room left below the floor |
| `ThreatGroundFronts.tickFront` | `suppressShield` - boots grind it at the same rate as the guns, past the orbital floor |
| `ThreatPlanetaryShield.apply` | vanilla's x3 ground defence never written; the knob's value applied in proportion to condition when set |

Knobs: `threatinc_shieldAbsorbEnabled` (true), `threatinc_shieldAbsorbMax` (0.75),
`threatinc_shieldSoakMult` (1.0). With the mechanic off the shield is an ordinary structure
again - not a target, no cover, disrupted by saturation as vanilla does it.

**Useful Planetary Shield.** Nothing is overridden and nothing needs to be. UPS gates its own
mitigation on `prev.shieldFunctional` - the shield's state at its *previous* ~0.1-day poll -
so the first strike on an intact shield still gets UPS's binary absorb (halved disruption,
halved unrest, no pollution, no size loss, and a 60-day floor on the shield's own clock), and
that same strike is what starts the shield's clock. From then on UPS is inert and the
proportional cover above is the whole story, until the clock runs out and the shield comes
back up. The two layers stack in the right order without either knowing about the other, so
UPS's no-size-loss and no-deciv guarantees can never stall a ground take: by the time a front
lands, the siege has long since put the shield down.

### How the defender fights back

`getDefenderStr(market, forBombard = false)` for a non-hive target - vanilla's own "who is
actually defending the ground" figure, which counts a player colony's **stored marines** -
**plus**, on an NPC colony, `ThreatReserves.stock(marketId, MARINES) x reserveDefenseMult
(1.0)`. The mod spent a whole logistics layer shipping those marines here; they pick up
rifles. (A player colony's reserve IS its resource stockpile, which vanilla's figure has
already counted, so it is not added twice - fixed 2026-09-06.) Everything else the
defender has:

- **Orbit.** `orbitContestedFor(THREAT, market)` (the colony theatre's
  `orbitHeldAgainst`) blocks the landing outright while the colony's station fleet and
  the armed fleets hostile to the Threat within 1,500u of the planet - a Guard order, an
  escort, an ally's task force, or the player in person - are at least
  `orbitContestFraction` of the swarm's points there (any at all, with no swarm there).
  This is the cleanest counterplay: hold the orbit and nothing comes down. The gate is live-fleet only because
  vanilla's `FGRaidAction.autoresolve` has already weighed the expedition against the
  system's defenders plus station strength, skipped any world that outweighs it and
  disrupted the station of one that does not - the same rule, resolved by strength ratio
  - before delivering all the passes in one frame.
- **Relief.** A Threat front on an NPC faction's own world is answered on the slow tick:
  a Guard task force over it (`ThreatFleetOrders.planRelief`, one per world) and a convoy
  of marines from the colony in range that can spare the most (`ThreatConvoys.planRelief`,
  ahead of every depot; the player's mobilised faction gets the convoy too). The besieged
  colony's own banked marines are **committed** (`ThreatReserves.committed`): no sortie or
  convoy may ship them away mid-siege.
- **The Defend contract.** A Defend mission on the colony fails only when the swarm
  actually puts troops on the ground (`landOrReinforce`) or a saturation pass hits; a
  soften pass, or a landing turned back from a held orbit, is the contract working.
- **Counter-attacks.** Paced by `frontCounterAttackDays / max(0.25, stability / 10)`
  instead of hive vitality, divided again by `colonyCounterAttackMilitaryMult (1.5)` when
  the colony has a Patrol HQ, Military Base, High Command or Lion's Guard. Keep a colony
  stable and staffed and it counterstrikes often; let it riot and it will not.
- **Batteries.** While the expedition's fleets circle the world its Ground Defenses and
  Heavy Batteries are killing ships (the duel above); a colony whose fortification is
  intact makes the siege long and expensive, and relief that arrives during it finds a
  weakened expedition.
- **Attrition.** A colony that simply survives past the invaders' 90 days of armaments
  has them dug in and waiting; hold the orbit against the next expedition and they wither,
  make their final push, and collapse.

### The swarm holds the orbit

A swarm that has taken the space over its own besieged hive **bombards the enemy front**:
`tickSwarmBombard` kills `swarmFrontBombardPer30Days` (0.60) of it per 30 days, scaled by the
swarm's weight over the planet against what the front can put in the way
(`fp / (fp + defenseStrength)`) and by the front's own veterancy. It needs
`swarmOrbitMinFleetFP` (50) at the planet to count as holding the orbit at all - a bombardment
is a fleet operation, not a lone frigate - and an armed hostile fleet on station stops it
outright (`swarmOrbitContested`).

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

The player's own saturation bombardment is untouched: `ThreatincMarketCMD` still applies the
full disruption set and `setFallout`. Only the hive self-scour is gone.
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

### When the colony falls

`ThreatGroundFronts.colonyGroundVictory` (the colony theatre's `victory`): none of the
hive bookkeeping applies. No `eradicate` (that is hive teardown), no free outpost, no
`ThreatAlarm` (`ThreatAlarm.add` ignores the Threat outright, so the swarm's own strata
never feed its alarm), no `retaliate` (guarded so it can never fire for a Threat winner).
**The swarm converts what it conquers** (user, 2026-09-06, knob `conquestConverts`):
`ThreatColonyManager.convertConquered` runs vanilla's own teardown
(`DecivTracker.decivilize(market, false)`) and founds a hive of `conquestHiveSize` (2) on
the ruin at once, as a colonisation wave would (`foundColony`). Any Defend contract fails.
Announced: *"X has fallen to the Threat ground assault - the colony is lost."* then *"The
swarm has seeded a hive on the ruins of X."* With the knob off, or a world that cannot
carry a hive, the old path runs: the mod's own `$threatinc_killedBy` flag names the Threat
- which `IncursionManager.processPendingDecivChecks` reads beside vanilla's
RECENTLY_BOMBARDED flag - and vanilla `DecivTracker.decivilize(market, true)` runs, so the
deciv-to-hive conversion picks the world up later.

A story-critical world with `destroyStoryCritical` off is never targeted; if the knob is
turned off mid-siege the front holds one stratum short (`lastStratumProtected`), pushes
no further, and withers on its armaments.

### Player-facing readouts

`ThreatGroundWarCondition` (`threatinc_ground_war`), on the colony screen for as long as
the front stands: districts held of size and what they cost, what is seized, which
defences are suppressed and to what, invader strength vs what the colony fields and what
they need to hold, days to the next counter-attack, and the invaders' supply - or, dry,
whether they are waiting for an expedition (and the days to their final push) or making
it. The colony's own Defenses tooltip shows each suppressed structure's surviving
multiplier. `HiveVitalityCondition` still covers the hive side. Announcements
(`announceAlways`), one sentence each: the landing, each stratum taken, a wave
reinforcing, the front battered / overrun / withered, and the colony falling.

### Engine changes this needed

- `ThreatGroundFronts.resolveMarket(marketId)` - the front engine used to resolve through
  `ThreatIncData.resolveColonyMarket`, which returns null for anything not flying the
  Threat flag and would have deleted a Threat front on a human world on the next poll.
- Whose ground it is lives in one object: `ThreatGroundFronts.Theatre` (`HIVE` /
  `COLONY`) answers `defenderStrength`, `counterAttackInterval`, `keyStructures`,
  `defenseStructures`, `needsSoftening`, `orbitHeldAgainst`, `carriesSiegeState` and
  `victory`; the tick asks it instead of branching, and a third theatre is a third
  subclass. The static helpers (`defenderStrength(market)`, `orbitContestedFor`, the
  requirement helpers, the old `orbitContested(marketId)`) keep their signatures and
  delegate. The front's owner is the other axis and stays on the front (`ownerOf`, never
  null - `deploy` writes `Factions.PLAYER` for the player).
- The landing doctrine is the engine's, not the expeditions': `needsSoftening`,
  `landingBlocked` (fallout / another army / the orbit, one reason string for the
  expeditions and the board alike) and `landOrReinforce` (deploy or resupply, the
  announcement, the Defend-contract failure for a Threat landing) are called by both
  `ThreatPurgeFGI` and `ThreatStrikeFGI`.
- `upgradeInFlightStrikes` no longer clamps ground-doctrine strikes (they have no
  bombardment type, and their passes ARE the siege).

## Reading a front - the board (2026-09-05, untested in-game)

The war board's **Ground fronts** table (docs/war-board.md item 4) is the ground war's
console: the hive view shows the selected system's fronts, a faction view the fronts that
faction is party to (its own landings, and Threat landings on its worlds). Every figure comes
from the readout helpers at the foot of `ThreatGroundFronts` ("readouts for the board"),
which reuse the tick's arithmetic, so the table quotes what will happen:

| Column | Helper | What it is |
| --- | --- | --- |
| Attackers | `effectiveStrength`, `entrenchMult` | troops x entrenchment (0.75 on landing -> 1.0 over 30 days of holding) x 0.5 when dry |
| Defenders | `defenderStrength` | the world's current figure; holding needs 17% of it, grinding 10% |
| Defenses | `defensesTrend` / `defensesLabel` | **worn** (holding front, disruption clocks fed faster than they run), **held** (grinding front keeps the defense structures down), **recovering** (foothold keeps nothing down), **intact** |
| State, Held, Stance | the front's fields | holding / grinding / foothold; strata or districts held/size; push / regrouping / dug in |
| Attrition | `attritionPer30Days` | 8% of troops per 30 days holding, 30% pushing (x the Lanchester skew), x3 when dry |
| Attack | `daysToCounterAttack`, `counterAttackRepelled` | days to the next counter-attack; green if the front's `defenseStrength` (x its cover, up to 1.5, when not pushing) beats the world's current figure |
| Arms | `supplyDaysLeft` | days of heavy armaments at the current burn (x2 while pushing) |
| Falls | `pushDaysEstimate` | "~N d" until the last stratum or district falls while the front can push (what is left of the current layer plus whole ones after it) |

The row tooltip gives the arithmetic one line per fact, including which structures are
disrupted and for how many more days, the next counter-attack's figures and outcome
(`counterAttackLoss`, `counterAttackOverruns`: repelled / costs N troops and a layer /
overrun), a dry Threat front's wait for its expedition or its final push, the push in
progress or what one would cost (`pushCasualtyEstimate`), and what the front wants
brought (`ThreatConvoys.frontWants`). The word is *troops* everywhere a front is
described - the swarm has no marines; *marines* survives only on convoy rows, which carry
the commodity (user, 2026-09-06).

**Orders from the board** (the player's own fronts, while mobilised): **Push** / **Dig in**
(`ThreatFactionView.BUTTON_PUSH` / `BUTTON_ENTRENCH` -> `orderPush` / `orderEntrench`,
the same calls the planet dialog makes; gated by `pushBlockReason` - holding strength, as the
dialog - and `entrenchBlockReason`; either also answers a checkpoint), Escort, Pull out,
Supply (refused when the front wants nothing, `ThreatConvoys.supplyBlockReason` - unless
the **Supply run** ladder over the table is set to Max, which asks for a hull load whether
the front wants one or not; `ThreatConvoys.supplyAsk`, docs/player-aid.md "load ladders").

**What "dig in" and "push" mean.** Stance is the front's *order*, state is what its strength
*earns*. Dug in (`STANCE_ENTRENCH`): attrition at the holding rate, armaments at base burn,
entrenchment growing, defends counter-attacks at its cover (up to
x`frontEntrenchDefenseBonus`, 1.5, once dug in); takes no ground. Push (`STANCE_PUSH`):
progress toward the next layer at `frontPushBaseDays` x the defense/strength ratio
(clamped 0.5-3x) while HOLDING strength lasts (progress pauses below it), attrition at
the push rate, burn x2, entrenchment frozen, exposed to counter-attacks (no cover).
Regrouping (`STANCE_CONSOLIDATE`): the checkpoint after a layer, `frontCheckpointDays`;
told nothing it pushes on if it still holds, digs in otherwise. The player's front pushes the
moment its troops land if it can hold (`ThreatGroundFronts.autoPush`, from `deploy` and from
any `resupply` that brings troops; knob `threatinc_frontAutoPush`, default on - user,
2026-09-07); too weak to hold, it digs in and pushes when a later landing brings it to
holding strength. A Dig in order (board or dialog) holds until the next troops land; an
armaments-only drop never changes stance. NPC and Threat fronts run the stance AI: push
whenever supplied and holding, dig in otherwise - and a front that runs dry mid-assault
breaks it off and digs in (2026-09-06).

**Bracing, and who goes first** (`shouldBrace`, knob `frontBraceEnabled`; 2026-09-08). A front
holding NO ground that is caught assaulting is destroyed OUTRIGHT rather than pushed back - the
overrun branch - and assaulting costs it every point of cover, since `coverMult` is 1 while
pushing. So a front digs in when an assault would get it overrun, and resumes when entrenchment
or the odds make one survivable.

**The order of operations is the front's, not the fleet's** (user, 2026-09-08). The troops take
cover first; the guns speak only if cover was not enough on its own. `defendBombards` therefore
refuses outright while its own front is on `STANCE_PUSH`: a bombardment that close to troops in
the open is friendly fire, and a front still assaulting has decided it needs neither. The three
rules compose into a combined-arms loop with no special-casing: land -> the assault would be
overrun, so dig -> now dug in, the fleet may bombard -> the defence falls -> an assault survives
-> push, and the fleet stops.

**The test is "would an assault get me killed", not "is a blow due soon".** An earlier cut used
a proximity window (`frontBraceDays`, 10) and was wrong in shape: cover takes `frontEntrenchDays`
to dig, so a front 22 days from a counter-attack it cannot survive should already be digging, and
instead it pushed on exposed while its own fleet bombarded to cover the gap.

`overrunIfPushing` asks the question at the cover the front would have **while pushing**,
whatever its current stance. That is what makes it stable: asked at the front's live cover it
would flip the moment the front took any, flip back the moment it moved, and oscillate every
tick. Entrenchment only accrues while dug in and never decays, so this projection improves
monotonically and settles.

Three limits stand. It applies **only while the front holds nothing** - once it has ground, a
counter-attack takes a district back instead of annihilating it, a setback rather than a death.
It does **not** fire if the assault lands first (`pushDaysRemaining <= daysToCounterAttack`):
taking the layer strips the defenders of its share *and* puts ground under the front, so the
counter-attack that follows takes a layer back instead of overrunning a beachhead - racing to
finish beats taking cover whenever the layer can fall in time, and the tick order allows it since
the push resolves before `hiveCounterAttack`. And a `finalPush` never braces: it is spent either
way.

Two traps this walked into, both worth remembering. The NPC stance AI runs ~180 lines LATER in
the same `tickFront`, saw the entrench the brace had just ordered, and pushed the front straight
back out of cover - so the brace was dead for every NPC and Threat front until it learned
`shouldBrace` too. And `orderPush` and `orderEntrench` BOTH zero `pushProgress`, so a braced
front restarted its assault from nothing each cycle and, with a push longer than the cadence,
could never finish one at all; `bracedPushProgress` carries it over.

**What "reinforces" the defenders.** Nothing explicit. A world's ground figure is a stat
rebuilt every frame - a hive's from `hiveDefensePerSize` x the strata it still holds x the
Nexus bonus scaled by disruption; a colony's from vanilla's ground-defense stat plus its
banked reserve marines - so the defenders "reinforce" exactly as their disrupted structures
recover, and lose exactly what a holding front keeps suppressed and what each stratum strips.
The Defenses column is that trend. The one enemy *action* is the counter-attack (every
`frontCounterAttackDays` / vitality on a hive, / stability on a colony, faster with a military
command), which retakes a stratum or batters the beachhead. Defense Swarms in orbit never
fight on the ground; they contest the orbit, which is what blocks runs and landings. On a
human world the swarm besieges, real reinforcement does exist: relief guards and marine
convoys (`ThreatFleetOrders.planRelief`, `ThreatConvoys.planRelief`) add to the reserve
marines the figure counts.

## Vanilla-tool integration (unchanged from phase 1)

- **Tac bomb + own front = danger close**: costs the front
  `frontDangerCloseLossFraction` marines, in exchange the strike's disruption also
  lands on the Core and port. Player-owned fronts only. Warned before confirm.
- **Sat bomb**: destroys ANY front on the surface (warned), sets
  `$threatinc_fallout` for `falloutDays` (40 >= the 20-day blackout - sat bombing
  must forfeit ground tempo). Role: theater shaping, never eradication progress.
  The flag binds the swarm too: a Threat strike will not land into fallout either.

- **Military options menu - who the defenders are (2026-09-07, TESTED, works)**: for a
  Threat colony the defenders are (`ThreatincMarketCMD.threatDefenders`) the live swarm fleets
  in its system that are its garrison (`GARRISON_FLAG`), reinforcements bound for it
  (`REINFORCE_TARGET_KEY`), Defend Swarms stationed over it (`ThreatSwarmDefend`), plus any
  other swarm fleet within `threatinc_defendRadius` (1000 su) of the world. Not vanilla's scan,
  which reaches only `battleJoinRange` (500 su net of radii, straddled by the garrison orbit at
  400-700 su and left entirely by a swarm hunting the player), skips a fleet whose finished
  battle still hangs off it, and then reads the swarm's willingness and weight through its
  fleet AI - hence "Engage the defenders" enabled with nothing to engage (the 2026-09-06
  "otherFleet is null" crash) and raids/bombardment open under a full garrison or with the
  garrison a few hundred su out (2026-09-07, Alpha Novy Tayvay I). Rule (`gateOnDefenders`):
  a free defender in reach - Engage open, raid, bombardment and ground landing closed
  (`temp.canRaid`); free defenders but none in reach - all three closed and Engage closed
  ("still N units out"); every defender busy in a battle - raid open, bombardment closed
  (vanilla's distraction rule); none - vanilla's result stands, raid cooldown included.
  `getInteractionTargetForFIDPI` is overridden to the same list (nearest free fleet in reach,
  else nearest busy one), so vanilla's draw and its Engage click agree; `engage()` keeps its
  backstop. Vanilla's defender text is suppressed for hives and replaced by one line (fleets
  in orbit and their fleet points, or fleets inbound and the nearest distance). Every menu
  draw logs the fleets weighed (`Military options at <world>: near/inbound/busy [...]`).

- **`removeOption` drops the enabled state of the other options (2026-09-07)**: this is why
  the two rewrites above changed nothing in game, and why the sessions before them failed -
  the detection was being fixed over and over while the menu was discarding the verdict. The
  hive path gated first and then called `removeOption(GO_BACK)` + `addOption("Ground
  operations")` + `addOption("Go back")` to slot our option in above Go back; the
  `removeOption` dropped every disable already set, vanilla's own included. Hence Engage
  clickable over an empty orbit (vanilla disables it there itself, with "There are no
  defenders to engage") and raid and bombardment open under a full garrison. Meanwhile
  `temp.canRaid` is a plain field, not panel state, so it survived and Ground operations >
  Land ground forces greyed correctly - which made the landing look like the only option that
  bothered to check, and sent several sessions hunting the detection code.
  Confirmed against the decompiled panel (`com.fs.starfarer.ui.newui.OoOO` in
  `starfarer_obf.jar`), not inferred: `removeOption` rebuilds the whole panel, and
  `setEnabled`, `setTooltip`, `setShortcut` and `addOptionConfirmation` all attach to the
  *button widget* that the rebuild throws away. Only a tooltip passed into `addOption` itself
  survives. We had already been papering over this without knowing: the escape shortcut on "Go
  back" has to be re-set after the re-add for exactly this reason.
  Everything else in the block is innocent and provably so: a plain `addOption` after
  `setEnabled` appends a button and touches no existing one (vanilla's own `addOption("Go
  back")` in `MarketCMD.showDefenses` runs after it greys raid for the cooldown, and
  `groundOps` greys the landing and then adds its own "Go back" - both grey correctly). A
  human colony returns straight after `super.showDefenses` and touches nothing, which is why
  vanilla targets were never affected. Nothing runs after the rule either -
  `fireBest("DialogOptionSelected")` fires one rule and our row carries `score:1000`. And the
  log proved the gate itself ran and saw the defender (`near 1`) on the very draw whose
  bombardment then went through, so the verdict was reached and thrown away. Vanilla calls
  `removeOption` in exactly one place (the `RemoveOption` rule command), never inside a menu
  build - so nothing in the base game trips over it.
  Fix: settle the panel's shape first, write its state last. `gateOnDefenders` now runs after
  the structural block and sets Engage, raid and bombardment *every* time rather than only the
  ones it wants closed - after a rebuild there is nothing left to inherit.
  **Rule for this codebase: in any dialog menu, do all addOption/removeOption/clearOptions
  first, then all setEnabled/setTooltip.**

## What replaced "decline" in the UI

- War board cards: `Front 1,250 push 2/6` in place of Reach; forecast column shows
  `s6 -> core ~85 d` while a strong front fights, `s6 contested` otherwise; card
  border lights up when a front is on the ground. Vitality bar threshold now
  `ThreatColonyManager.CRITICAL_HEALTH` (0.35, presentational only).
- HiveVitalityCondition, InfestedSystemIntel, ThreatSiegeReportIntel,
  ThreatMissionIntel: decline meters/forecasts replaced with strata-taken figures.
- Purge follow-up "wounded" test: front present or organs disrupted.
- Legacy decline persistent-data maps are kept but unread (old saves load fine).

## Strategy-layer backlog (the "proper strategy game" - see docs/strategy-layer.md for what was built on 2026-09-04)

Agreed direction from the user, in rough build order:

1. **Troops ride fleets for real**: marines/armaments as actual cargo aboard NPC
   expedition fleets (vanilla `CargoAPI` exists on every fleet), with troop-carrier
   and cargo capacity mattering. A pulled-out front boards a circling fleet and can
   be redeployed elsewhere.
2. **Fleet orders**: a way to order individual friendly fleets - orbit superiority,
   jump-point interception (vs Mutual Defense relief), logistics runs to fronts,
   troop redeployment. Missions the player could fly personally, delegated.
3. **Faction reserves, fully transparent**: every deployment of marines/armaments/
   fuel comes from a visible per-faction reserve, generated by functioning colonies
   and industries. War board gains a reserves/logistics panel.
4. ~~Hive-side fronts on core worlds (Threat strikes reworked onto the same object),
   outposts on eradicated worlds (non-economy garrison entities, forward depots).~~
   BOTH DONE - see "Threat ground assaults" above and `ThreatOutposts`.

## Strategy-layer decisions (user, 2026-09-04, after the session above died)

- **Reserves are PER COLONY, with staging**: stocks live on the colonies that
  generate them and must physically ship to a staging point before they deploy.
  This pulls in the convoy layer from the start; that is accepted.
- **Everything fleet-related must be doable REMOTELY** - from the board, not by
  flying to a fleet and hailing it. The war board is the fleet-order home.
- **Faction selector on the board**: pick a faction from a list and the board
  shows THAT faction's systems and planets - state of its defenses, attacks
  against it, its fleets - so the player can send fleets to reinforce allies.
- **War mode gates all of it**: NPC factions behave exactly as vanilla until the
  Threat attacks them. Being struck by the Threat flips the faction into war
  mode; only then does the mod start tracking its reserves, defensive fleets,
  attack fleets and so on. Do not touch core-world fleets outside war mode.
  (Natural hook: the reactive-defense path in `IncursionManager` that already
  musters a faction task force when a Threat strike targets its colony.)

## Testing

`tools/test-harness` cycle + a cloned save with injected fronts (see
docs/testing-harness.md; persistent-data XML injection of
`threatinc.ThreatGroundFronts$GroundFront` works - element name uses `_-` for `$`).

### To verify - sieges against human factions (2026-09-06, built, untested)

1. A Threat strike reaching a colony no longer bombards: the log reads "Siege slice vs X"
   every few days per fleet, the colony's Defenses tooltip shows "Heavy Batteries
   (suppressed, N% effect)" climbing down to 50%, and the fleet loses ships to the batteries
   (log: "batteries cost N FP").
2. The landing comes once every defence structure carries 90 days (the floor) or the
   troops could hold - not before. The strike intel reads "suppressing the defences" then
   "moving to land - defences at the floor" or "moving to land - the troops can hold,
   sparing the ships"; the purge's reads "besieging from orbit" then the same.
3. A dry Threat front digs in (board: stance "dug in", Arms "dry", tooltip "Dug in for the
   next expedition - final push in N days"), the next strike goes to that world more often
   than not, and after 120 days with none it announces the final push.
4. Entrenchment: a fresh landing reads "x 0.75 entrenchment (0 of 30 days)", reaches 1.00
   only while holding, and halves when a layer is taken or a counter-attack lands.
5. Districts: each one held shows on the colony screen with stability -1 and accessibility
   -10% per district, "Seized: ..." naming the industries, and those industries disrupted
   at 30 days; retaking the district frees them within 30 days.
6. Victory: the last district falls and the world is a size-2 hive at once, with no deciv
   intel and no colonisation wave queued for it.
7. The fronts table reads Troops / Held / Falls, and no tooltip says "marines" of a front.
8. A player colony's stored marines are counted once in Defenders (compare the vanilla
   Defenses tooltip figure plus nothing).
9. Old saves: fronts load with daysAlive 0 (the tooltip falls back to the timestamp),
   dryTimestamp 0, finalPush false; a bombarded colony picks up the siege state on the
   first poll.

### To verify - the duel on both theatres, Support (2026-09-06 evening, built, untested)

10. A faction or player-commissioned siege expedition over a hive logs "Siege slice vs X"
    per fleet instead of "Siege pass (tactical)"; the hive's Ground Defenses tooltip reads
    "(suppressed, N% effect)" falling to 50% and no lower while no front stands; the
    expedition loses ships (log "batteries cost N FP"); its intel reads "besieging from
    orbit" then lands. The sitrep lists one "Orbital siege" line per world.
11. A hive struck by a marine raid or saturation pass keeps most of its defence bonus now
    (no half-effect step): 20 days on a 300-day clock is 93%.
12. The player's tactical bombardment of a hive and of a human colony shows the slice
    prompt (days per structure, fleet points lost, fuel), takes the smallest ship(s) and
    names them, never the flagship, and moves each structure's clock by about the quoted
    days rather than 60 / 365. A second bombardment at the floor is refused with the
    "as far as orbit can push them" line.
13. The button reads Support; its row label, the fleets table kind, the board Activity
    entry ("supporting the siege") and the Supply / Pull out refusal ("send Support
    first") all say Support. Saves from before the rename keep their Escort orders working
    under the new name.
14. A Support fleet on station over a besieged world with a clear orbit logs "Support over
    X: ... lost N FP" as the batteries bite and the world's fortifications fall to the
    floor; over a contested orbit it fights instead and suppresses nothing.
15. Balance to read off the log: what a default 400-point siege expedition does to a
    size-5 hive per slice, and whether its landing gate opens before `siegeOrbitDays`.
16. The planet shows vanilla's bombardment burst on every siege slice while the player is
    in-system (one burst per whole day of suppression added per world), from an expedition, a Support
    fleet or the player's own tactical bombardment. The Support button's tooltip and its
    confirm name the fleet that goes and quote the days per day it suppresses, the floor,
    and the fleet points per day the batteries take.

### To verify - the landing fleet defends, and the Defend order (2026-09-07, built, untested)

17. A faction expedition against a two-hive system (Gamma Hero) lands at the first world
    and the log then reads "Siege pass (landing) vs X" followed by "Order issued: <faction>
    defending the orbit of X" for the landing fleet and any other empty fleet in the
    system; the fleets table shows them as **Defend**, "-" for days left, with Recall. No
    "Siege slice vs <second world>" follows: instead "Siege of <second world>: nothing left
    to land, no siege", or nothing at all once every fleet has joined the Defend.
18. A Defend fleet over a front that holds logs no "Defend over X: ... lost N FP" and the
    hive's fortifications recover from the floor; the moment the front cannot hold (a
    counter-attack, armaments out) the slices and the bombardment burst start and the
    fleets-table task reads "covering the front on X", back to "defending the orbit of X"
    when it can hold again.
19. When the front wins (hive eradicated) or is destroyed, the log reads "Order stood down:
    ... defending the orbit of X" and the fleet flies home on the tracked leg; the
    expedition itself ended when its last fleet detached, with "Expedition return ..." and
    a fuel refund scaled by the surviving strength (not 0%).
20. The Defend button sits between Support and Pull out on the player's front rows; its
    tooltip and confirm name the fleet, then say it holds the orbit, does not bombard while
    the front holds, and only while it cannot bombards at about N days per day with the
    batteries answering at about M points per day. Pressing Support over a world with a
    Defend fleet takes that fleet (and the reverse): the two swap a fleet's doctrine.
21. The Threat's strike: after "Strike pass (landing)" the log reads "Swarm defend: <fleet>
    stays over X"; the swarm fleet sits over the colony and bombards only while its front
    cannot hold; the rest of the strike sweeps on; a swarm fleet with nothing left to land
    joins the station rather than slicing; the station stands down ("Swarm defend over X
    stands down") when the front is gone and the fleet flies back to its staging colony.
22. Reading the duel's size off the log (2026-09-07, after a Defend fleet looked as if it
    bombarded for nothing): every "Siege slice vs X" line now carries "+N d on the clock
    (C of F)", and each Support / Defend fleet logs once a day "<fleet> at P FP suppresses
    ~N d/day, batteries ~M FP/day; clock C of F d". The Support and Defend tooltips carry
    the same clock line. A 100-point task force against a hive whose defence figure is in
    the thousands adds about one day per day on a 300-day clock: that is the proportional
    rule at `siegeFPWeight` 1.0 and `hiveSiegeSuppressDaysPerDay` 30, not a lost slice.
    CORRECTION the same morning: the slices WERE lost - four fleets of 850 points should have
    added 10-30 days a day. The gate before the slice held the orbit contested while any
    Defense Swarm was alive in the garrison list, refilled every poll; now a strength contest
    (`orbitHeld`). Verify: with Defend fleets over a hive the daily log line reads
    "suppresses ~N d/day ... clock C of F" on most days and "fights for the orbit - H FP of
    defenders against F FP here" only while a real swarm force is at the planet; the clock
    climbs by the summed rate and reaches the floor (150 on a hive) in days, not months.
    SECOND CORRECTION: with the contest fixed, only one of four Defend fleets ever logged
    a slice - the other three failed the 1,500-unit distance check, chasing swarms round
    the system on MAKE_AGGRESSIVE. Support / Defend fleets and the swarm's stations are now
    leashed per frame exactly as garrison swarms are (`ThreatFleetOrders.enforceLeash` /
    `leash`: beyond the hold range and out of battle, blinkers on, GO_TO_LOCATION back, the
    orbit re-queued, blinkers off on arrival). Verify: every Defend fleet logs its own daily
    "suppresses" line; "strayed N units - recalled to the orbit" appears at most now and
    then; the clock climbs by the four fleets' summed rate (~11 d/day at 250 FP each).
    THIRD CORRECTION (the reload after the leash): three of the four Hero II fleets did
    slice, but on different days and almost never two on the same day, so the clock climbed
    at one fleet's rate; only 7 leash lines in 35 days, all at 1,500-1,502 units. The
    leash logs on the first frame out and then nothing while the fleet is in a battle or
    already on its way back, so a fleet chasing a swarm into a fight and crawling back for
    days was invisible. At Gamma Brador IV a Support fleet flying IN was recalled every
    poll, 8,665 units down to 1,846, by something re-tasking it each poll. Changes:
    - HOLD STATION: Support / Defend fleets and swarm stations keep the blinkers on for
      the whole order (`orbitFleetsHunt` false) - they fight what attacks them or meets
      them at the planet, and never pursue. Defense Swarms are aggressive, so the fight for
      the orbit still happens, at the planet.
    - Once a day every order fleet logs one line: at the planet, the slice line as before,
      or "holds the orbit - the front holds / the defences are at the floor"; away from it,
      "<fleet> at P FP is N units out (or: out of the system, in X), <assignment>, in a
      battle, blinkered/hunting". A leash line now says what the fleet was doing
      ("(was: ORBIT_AGGRESSIVE -> Gamma Brador IV '...')"), which names the other writer.
    Verify: four Defend fleets over one world give four lines a day, all "suppresses" once
    the front cannot hold, and the clock climbs at their sum until the floor (150 on a
    hive); no fleet is more than 1,500 units out for more than a day; if "strayed" repeats
    for one fleet, the "(was: ...)" text says who keeps re-tasking it. The floor is the
    end of what orbit can do: at 149-150 of 150 the clock cannot rise, and a front that
    cannot hold there needs troops, not ships (`fortificationOrbitFloor` sets the floor).

### Verified - military options on a hive (2026-09-07, tested in game, works)

The two that kept failing now pass: over a hive with **no** defenders "Engage the defenders"
greys ("There are no defenders to engage"), and over one with a garrison in orbit raid and
bombardment grey. If either ever goes clickable again, the panel is being rebuilt after the
state is written - check what touches `options` after `gateOnDefenders` in `showDefenses`.

Known cosmetic gap, left alone deliberately: `gateOnDefenders` writes the raid tooltip only
when its own defender count is non-zero, so if vanilla greys raid for a reason
`threatDefenders` does not count, the option greys with no explanation. Vanilla's raid-cooldown
greying has no tooltip either, so that case is already at parity. Vanilla's
`addOptionConfirmation` on Engage is also lost to the rebuild and never replayed; it is
unreachable on a hive, where the player is always hostile.

The rest of the list below was the pre-fix verification plan and still holds:

- Hive with a garrison in orbit: text says "The swarm holds the orbit: N fleets, X fleet
  points"; Engage enabled; Launch a raid, Consider an orbital bombardment and Ground
  operations > Land ground forces disabled with their tooltips.
- Engage: opens the fleet encounter against the nearest garrison, never the "no defenders in
  range" line while a swarm is visibly in orbit; after killing every swarm, Go back / re-enter
  shows raid and bombardment open and Engage disabled ("There are no defenders to engage").
- Garrison hunting you, still beyond 1000 su of the world when the menu opens: text says the
  defenders are inbound with the nearest distance; Engage, raid, bombardment all disabled;
  once it is in reach, re-enter - Engage open.
- Garrison fighting an NPC siege fleet at the hive: raid open, bombardment closed, Engage
  offers to join the battle.
- Check `starsector.log` for `Military options at <world>:` - it lists every swarm fleet the
  menu weighed with distance and state; a wrong menu is explained there.
- Human colony: untouched (vanilla text, vanilla gates) - the override is Threat-only.
- Knob: `threatinc_defendRadius` (LunaLib "Orbit Defence Radius"); at 300 a garrison parked
  600 su out no longer blocks a raid.

### To verify - troops push on landing (2026-09-07, built, untested)

22. Land at holding strength: the dialog reads "on the ground and moving on stratum 1", the
    log line ends ", pushing", the front row shows the push stance with a Dig in button (no
    Push button), and the board tooltip counts push progress from day one.
23. Land too weak to hold: "digging in - too few to hold, so they wait for more", log ", dug
    in"; a later troop drop that reaches holding strength adds "Reinforced to holding
    strength, the front moves on stratum N" and the stance flips to push.
24. Order Dig in, then send an armaments-only drop or convoy: the front stays dug in. Send
    troops: it pushes again. With `threatinc_frontAutoPush` off no landing changes stance.
25. NPC and Threat fronts behave as before (they already pushed on their first tick).

### To verify - the planetary shield (2026-09-07, built, untested)

- A colony with an intact Planetary Shield, tactically bombarded: the prompt lists the shield
  as a target with its own %, and says "The shield turns 75% of that away from everything
  under it; this pass puts N days on the shield itself." The days quoted for the other
  structures are a quarter of what the same fleet does to an unshielded world.
- Confirm it. The shield's disruption clock starts; the guns take the reduced figure. Check
  the log line: `siegeSlice ... shieldThrough=0.25`.
- Bombard again. `shieldThrough` has risen (the shield is worn, so more gets through) and the
  guns take more than last time. Repeat until `shieldThrough` reaches 1 - or 0.625 at the
  orbital floor with LunaLib's `fortificationOrbitFloor` 0.5, where it should stop.
- Land a front on that world: the floor goes, and further passes take the shield to 0
  condition and `shieldThrough` to 1.
- The colony screen's invasion tooltip reads "Planetary shield at N% effect: it absorbs M% of
  incoming disruption." after "Defences suppressed to: ...", and the shield appears in that
  suppressed list once it is disrupted.
- Support / Defend buttons over a shielded world show the shield line under the siege clock,
  and the disruption-per-day figure they quote is the reduced one.
- A Threat strike against a shielded player colony takes visibly longer to suppress it than
  against an unshielded one of the same defence figure.
- With UPS installed: the FIRST bombardment of an intact shield still shows UPS's "absorbed
  the brunt" message and its halving; the second and later ones do not (the shield is down).
- Set `threatinc_shieldAbsorbEnabled` false: the shield is not a bombardment target, quotes no
  absorption anywhere, and a saturation pass disrupts it like any other structure.
- A world whose ONLY defence is a shield (no Ground Defenses / Heavy Batteries / Patrol HQ /
  Military Base / High Command): the tactical option still offers a pass, targets the shield
  alone, and reports no return fire.
- **The x3 is gone.** On a colony with a Planetary Shield, the ground-defence breakdown on the
  colony screen names no Planetary Shield entry at all, and the figure does not change when the
  shield is disrupted or repaired. Set `threatinc_shieldDefenseBonus` to 2 and it reappears -
  and then *falls in a straight line* as the shield is bombarded, never dropping to nothing in
  one step.
- **The tooltip is ours.** Hovering the Planetary Shield on the colony screen describes
  absorption in proportion to condition and quotes the live condition / absorb figures - not
  the old flat-50% text, and no claim about size reduction or decivilization.
- **Save compatibility.** Save with a shielded colony, disable ThreatIncursion, load: the save
  must open with a plain vanilla shield rather than a `CannotResolveClassException`. Re-enable
  and load again: the shield is ours, and a shield that was disrupted still is (the disruption
  key is pinned to vanilla's).
- Land a front on a shielded world and hold it: the shield's condition keeps falling past the
  orbital floor (below 50% at LunaLib defaults) and reaches 0, at the same rate the guns fall.
  While GRINDING rather than HOLDING it should fall at `frontGrindSuppressMult` of that.
- Balance watch: at LunaLib defaults (`siegeSuppressDaysPerDay` 6, `fortificationOrbitFloor`
  0.5, `siegeOrbitDays` 120) a 600 FP expedition floors an unshielded size-5 colony's guns in
  ~115 days, inside its orbit budget, but needs ~218 days against a shielded one - so from
  orbit alone it will time out. It does not need the floor to land (suppression lowers
  `defenderStrength` continuously), but if the swarm never manages a landing on shielded
  worlds, `threatinc_shieldAbsorbMax` is the dial; 0.5 roughly halves the penalty.

### To verify - fabricating troops from the fleet (2026-09-08, built, untested)

The mechanic starts where bombardment stops, so the setup is a Defend fleet over a world
whose fortifications are already at the floor with a front that cannot hold.

1. **It engages at the right moment, and only then.** Bombard a world to the floor with a
   front on it that is short of `holdRequirement`. The fleet's log line should switch from
   the slice line to `... FP fabricates - the defences are at the floor and the front is N
   short of holding`, and the board row from "covering the front on X" to "breaking up for
   the front on X". Above the floor it must still bombard.
2. **It stops when the front holds.** Once the drops carry the front over the line the row
   should go back to "defending the orbit of X" and the fleet stop losing ships. Watch for
   oscillation across the boundary - that is what `fabricateHoldMargin` is for; if it
   chatters, raise it.
3. **It commits proportionally.** A front a few hundred marines short should cost a frigate
   or two, not a fleet. Check `broke up N FP of hulls into M troops` against the gap in the
   preceding line: `M` should be about the shortfall, not the fleet.
4. **It never goes home.** Let the batteries and the fabrication grind a Defend fleet under
   33% of its arrival strength while the front still stands. It must stay. Then win or lose
   the front and confirm it *does* leave.
5. **The last hull.** Run a small fleet down. It should thin to one ship, log `(nothing left
   to break up)`, and keep holding the orbit rather than despawning or throwing errors.
6. **The dry tail.** A front with plenty of marines but zero armaments cannot hold; confirm
   a drop still happens (`frontMinMarines` worth) and its armaments un-dry the front.
7. **The price.** On a world with heavy batteries, `lost N FP to the batteries doing it`
   should appear beside the drop and be larger than the suppressed-condition figure the
   slice line was quoting before the floor. On a world with no defence structures at all,
   fabrication should still run and cost nothing.
8. **Both theatres.** A faction/player Defend over a hive, and a swarm station over a human
   colony under invasion. The swarm's messages should read "The swarm over X is breaking up
   its own ships".
9. **The visual.** In the same system, fragments should fall inward to the planet with a
   ping and a floating label - and it must NOT look like the bombardment burst. Out of the
   system, nothing should be drawn and nothing should throw.
10. **Off.** `threatinc_fabricateEnabled` false: a Defend fleet at the floor idles as it did
    before, with the old "the defences are at the floor" reason, and the tooltip loses its
    fabrication lines.

### To verify - a station that comes back (2026-09-08, built, untested)

1. Let a Threat siege run long enough on a world with a star fortress that the station is
   destroyed and its industry goes into repairs, then let the repairs finish while the front
   is still on the ground.
2. The respawned station should be **engaged**, not orbited past. Log lines: "fights for the
   orbit" from `supportSlice`/`fabricateTroops`, and a swarm station whose guns go cold should
   read "fighting for the orbit" on the board rather than "the front holds".
3. It should fight it whether or not the swarm outweighs it - the case that started this is a
   Threat force big enough to *hold* the space against the fortress and ignore it anyway.
4. It must not chase: check the leash lines still recall strays, and that no fleet follows a
   runner out of the system on the back of the new aggression.
5. After the station dies, ground defence should drop by the fortress multiplier and the front
   should start making progress again; the fleet should go back to holding station (no more
   aggression grants in the log).
6. Watch a Defend fleet's strength - fighting a star fortress may grind it under
   `defendMinStrength` and send it home. That is the existing valve, but check it does not
   turn every long siege into a fleet that leaves.

### To verify - the swarm off armaments, and paying on the assault (2026-09-08, built, untested)

1. **They never run dry.** Let a Threat landing sit on a colony well past 90 days with no
   further wave. Its board Arms cell should read a grey `-` throughout, no "have exhausted
   their heavy armaments" message should ever fire, and the front must not dig in for an
   expedition, make a final push, or collapse.
2. **The old rule still works.** Set `threatinc_threatFrontNeedsArms` true and confirm the
   whole 2026-09-06 sequence returns: the dry message, x0.5 strength, x3 attrition, the
   120-day final push, the collapse.
3. **Assault losses doubled.** Compare a pushing Threat front's Losses figure against a
   pushing faction front of the same size on a comparable world - the swarm's should be
   about 2x. The row tooltip should name `x2.0 swarm assault`, and it must NOT appear while
   the front is dug in.
4. **The three figures agree.** The board Losses cell, the row tooltip and the push
   confirmation estimate must all quote the same number for a Threat front. This is the
   thing most likely to be wrong - `pushCasualtyEstimate` is a separate copy of the formula.
5. **Reinforcement priority survives.** A Threat front that cannot hold should still pull
   the next strike toward its world (`strikeReinforceWeight`, 4x). Check the log for the
   strike target pick while such a front stands.
6. **Fabrication does not ship them armaments.** A swarm Defend station feeding its own
   front should land troops with **0** armaments (`fabricateArms` returns 0), and the front
   should not gain a supply figure.
7. **The colony tooltip.** A world under Threat invasion should show "Invaders' losses: N
   troops per 30 days at their current stance" where it used to show heavy armaments, and no
   final-push line.
8. **Nothing else moved.** A player or NPC front on a hive must still burn armaments, still
   go dry, still show days of supply, and still request a pickup when dry and below grind
   strength.
