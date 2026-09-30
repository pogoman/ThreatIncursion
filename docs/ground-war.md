# Ground war - eradication by ground victory

Design agreed with the user (Sept 2026, two sessions). This doc records what is
implemented, the judgment calls made, and the strategy-layer backlog. Every rate is a
config knob (settings.json / LunaLib).

## The model

**The colony is the fortress; the Core is the objective.** A size-S hive is S strata
deep with the Fabrication Core at the center. There is NO decline timer any more -
the old health-below-threshold decline engine is removed (rejected again 2026-09-28).
**A colony dies two ways**: a ground victory - take every stratum, destroy the Core
(`ThreatGroundFronts.groundVictory` -> `ThreatColonyManager.eradicate`) - or saturation
razed down to its last level (2026-09-28, `ThreatRazing`, "Bombardment v2" below).
Starvation and tactical bombardment only ever WEAKEN it. This holds for NPCs too - purge expeditions land
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
  (the existing wear mechanic); GRINDING (>= `frontGrindFraction`, 0.10) wears just
  the defense structures; FOOTHOLD suppresses nothing. Either wears by its advantage,
  `frontWearRate` (12) x troops / (troops + defence) days a day (2026-09-28).
- **What drives a disruption clock** (2026-09-28, "Bombardment v2" below): orbit, a day
  of bombardment at a time - a Threat strike over a colony, a faction's or the player's
  siege expedition over a hive, a Support or Defend sortie over either, and the player's
  own bombardment all fly the same day - adding the theatre's rate x F / (F + D) x what
  still stands of each fortification, so it softens with diminishing returns and never
  wears anything out. A front adds `frontWearRate` (12) x E / (E + D) a day, not cut by
  condition - boots finish what orbit cannot - against the clock's own run-down of a day a
  day, up to a cap of 1.2 x the wear days. No raid reaches a fortification any more;
  on a hive only the Fabrication Core is raidable. Danger close (a tactical day with your own front down) lands the day on the
  Core and port too.
- **The Core wears in proportion** (2026-09-05): its fabrication factor is `coreDownFactor`
  (0.8 since 2026-09-28; at 1.0 a Core under ~45 days still grew at full pace) x
  (1 - clock / `defenseWearDays`) - one pass leaves 64 percent, 150 days 40, 300 days
  nothing - and the hive's health, growth and counter-attack interval follow it smoothly
  instead of quartering the moment the Core is touched. A
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
which is the honest outcome. Once the orbit is clear, and the war-strata are
fully worn or the troops could hold as they are (`ThreatGroundFronts.readyToLand`, the strike's
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
  From orbit alone the condition falls all the way to 0 like everything else; a holding
  front on the world just wears it down faster (2026-09-28: the orbital floor is gone).

**Conquest pays (2026-09-29, closed economy).** When the Threat takes a human colony and seeds a
hive on the ruins, the hive is paid for: `convertConquered` -> `foundColony(planet, size, payerId)`
with the payer from `conquestPayer` - the nearest live colony whose bank can pay the four core
structures and a first build (5 x `foundingFPPerStructure`, 750 FP), else the nearest live colony,
else the new hive itself. The core four (Population, Spaceport, Fabrication Core, Swarm Nexus) are
charged whatever the bank holds - a debt its production pays off - and the rest wait on the
hive's own bank (docs/hive-economy.md "Paid founding"). The conquered hive starts with an empty
bank and no garrison.

**Conquest garrison (2026-09-29).** When a Threat landing fleet's front ends because the world was
taken (ThreatSwarmDefend.tick: the old market gone or Threat-owned), the fleet does not fly home: it
digs in as the new hive's garrison (ThreatColonyManager.digInAtConquest). Its ledger is unbound (its
hulls leave the source's bank; upkeep charges them where they stand), it takes GARRISON_FLAG and joins
garrisonsFor the nearest live hive within twice the leash, orbiting aggressive. No hive under it, or the
fleet in a battle, and it goes home as before. Before this the conquerors flew away and the new hive
stood empty (Asharu, Corvus, razed by 275 FP days later, ti-h8d). Logged Conquest garrison: ....

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

**3. The defenders bleed.** `defenderLossPer30Days` (0.20) of the engaged armed marines per 30 days,
scaled by the front's pressure (`effectiveStrength / holdRequirement`, capped at 1), plus
`defenderCounterAttackLossFraction` (0.15) of them every time the colony counter-attacks - **win or
lose**. Bouncing off a dug-in front used to be free; it is now the expensive case. The bleed is
deliberately *not* gated on the front's state: gating it on HOLDING or GRINDING would let a
colony switch the cost off by reinforcing past the threshold, which is precisely the move this is
meant to charge for. All of it is drawn through `ThreatReserves.spendDefendingMarines`, so the
armed count, the stockpile and the veterancy pool stay consistent.

Both scale with the *engaged* defenders, `ThreatGroundFronts.engaged(armed, front)` = min(armed marines,
`front.marines`): the frontage is the smaller force. Scaled on the whole garrison, a 1,000-marine
beachhead bled a 5,000-marine world 1,000 a month and 15% of 5,000 per counter-attack; a 20-month test
lost the Hegemony ~39k marines to fronts that lost ~2k, and five worlds fell once relief convoys had fed
the grinder dry (2026-09-29, ti-h8h). A front with no marines counted (`front.marines` 0) engages the
whole garrison. The war board quotes both through `defenderLossPer30Days` and `counterAttackDefenderLoss`,
so its figures follow.

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
  up to fully worn at `fortificationDisruptDays` (180); the
  moment the fleet leaves, recovery starts from there;
- the batteries answer: the fleet loses `siegeBatteryAttritionPerDay` (0.02) x F x the
  batteries' share of D (1 - 1 / their multiplier) / (F + D) points, taken off the live
  fleet smallest ship first (`applyFleetLosses`, the remainder banked in fleet memory, the
  last ship spared - the group's own abort rule takes a gutted expedition home).

So Chicomoztoc after its fortress dies (D ~1,375) costs a 300-point fleet most of its
ships to wear down, a 1,500-point armada far less; Nomios (D ~95) wears out fast for a
handful of points. Every defence building has a
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
| Orbital bombardment | suppresses the war-strata to nothing, the size-anchored strata untouched, weapon growths fire back | suppresses fortification to nothing, garrison untouched, batteries fire back |
| Counter-attacks | paced by hive health | paced by stability and military command; strength is the garrison |
| Victory | the Core dies: eradicated, the survivors come home | the last district falls: a hive is seeded on the spot |

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
  of fuel a day poured into the razing bar through the shield. Fuel per level at size s =
  `satFuelSize4` x 10^(s/2) / 144.8; a level paid is a size off (below 3 too); the last ends
  the colony (`hiveRazed` / `colonyRazed`). Layers a front holds count as size lost. The dead
  stay dead. A story-critical world stops at size 3. One atrocity per campaign (a month
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
  flotilla and fuel allow, whatever the landing would cost. `hiveRazed` evacuates the front.
  Only when it is faster: a front that takes the last stratum before the razing would land
  (`daysToLastStratum` against `razeArrivalDays` plus the razing's days) is left to finish, and a
  world whose front finishes before a siege could arrive is not targeted at all
  (`frontFinishesFirst`; run 6 sent a razing to a hive its front took a day later).
  Off with `npcRazeEnabled`; never the swarm or the player.
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
  razes a hive from orbit instead of landing where the razing fuel costs less than the landing's
  marines and armaments at vanilla base prices, its reserve holds the fuel, and a flotilla big
  enough to outlast the guns (`ThreatGroundFronts.razePlan`) finishes within `siegeOrbitDays`.
  The razing fleets carry the fuel (`razeFuel`), pour it a day at a time (`razePass`), and the
  board reads "razing from orbit". `npcRazeEnabled` turns it off. The swarm razes by the same
  bar when `strikeSaturationEnabled` is on.
- **The player's Bombard order** (war board, docs/war-board.md): the same razing expedition,
  player-commissioned, against a system's hives without a front; fleets sized to outlast the
  guns, fuel from the base's reserve.
- **Ordnance**: Support and Defend fleets pay the day's fuel from their provisions, then their
  home base's spendable reserve (`payOrdnance`); with none they stand idle ("out of fuel to
  bombard with"). The swarm has no fuel economy and bombards free.
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
| Fortifications | Ground Defenses / Heavy Batteries, Swarm Nexus | Ground Defenses, Heavy Batteries, Patrol HQ, Military Base, High Command (`ThreatSiegeMalus.FORTIFICATION_IDS`) |
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

### Raiding the fortifications (2026-09-25) - RETIRED 2026-09-28

**Retired by "Bombardment v2".** Marines reach the guns only by landing: every human
fortification is vanilla's unraidable again, the hive's Swarm Nexus, Ground Defenses and
Heavy Batteries and the planetary shield are tagged unraidable, and the knobs
`fortificationRaidDanger` / `fortificationRaidDepthLoss` are gone. The Fabrication Core raid
stays (it is not a defence), its depth toll on `coreRaidDepthLoss` and read from the
player's own raid strength (the suspected `depthMult` bug, fixed). What follows is history.

Vanilla tags a colony's Ground Defenses, Heavy Batteries, Patrol HQ, Military Base and
High Command `unraidable` with no disrupt danger: its tactical bombardment knocks them out
for 365 days, so raiding them was never needed. Ours is a siege slice that wears them down
over the full clock at vanilla's fuel bill; raiding trades marines for speed.
`ThreatFortificationRaids` (a
`GroundRaidObjectivesListener`, priority 1, after vanilla's list) puts every human colony's
fortifications on the disrupt-raid picker at the danger in `threatinc_fortificationRaidDanger`
(default HIGH, the hive batteries' own in industries.csv: 20 days per marine token).

What the defence decides is vanilla's: raid strength / (raid strength + defender strength)
sets how many marine tokens you have and trims losses as it rises. The danger label is
fixed per structure and only sets the base casualty rate and days per token. Wearing the
guns down iteratively beats knocking them out in one go, by design (user, 2026-09-25):

- Each raid's days add in full. Vanilla shrinks a raid on a clock already running
  (dur x dur / (dur + already)); the fortification's condition is linear in its clock, so
  that only punished coming back.
- Marines lost on a fortification objective grow by (tokens ^ `threatinc_fortificationRaidDepthLoss`
  - 1) x the defence's share of the fight, defender / (raid + defender) - the same odds
  vanilla sets the tokens by (`modifyMarineLossesStatPreRaid`, shown in the losses breakdown
  as "Deep raid on one structure"). Vanilla averages danger per token, so five tokens
  on one objective cost what one does. First cut (same day) had no defence term: 8 tokens
  at 1.5 was a flat x22.6, and a raid of 800 marines on a 333-defence world lost 387 where
  vanilla would have lost ~17. Now that raid (~0.2 share) is ~x5; against a garrison
  holding 0.7 of the fight, a 3-token raid is ~x4 against 3 shallow raids' ~x2.7.
- The same term is weighted by what the structure adds to the defence now - its bonus x
  condition x input deficit, against Ground Defenses' intact x2 (user, 2026-09-25: a
  Military Base's x1.2 showed the same x2.7 at full commit as Ground Defenses' x2). Heavy
  Batteries weigh 2, Ground Defenses 1, High Command 0.3, Military Base 0.2, Patrol HQ
  0.1, and a structure already worn down weighs less - so each shallow raid makes the
  next one cheaper.
- Vanilla's own terms still apply on top: raid strength is 0.25 x the fleet's personnel
  capacity + ground support, times marine XP and skills - not the marine count - and the
  loss is a fraction of every marine aboard, so a bigger stock loses more for the same raid.
  "Increased defender preparedness" adds 50% per recent raid, decaying, so shallow raids
  back to back get dearer.
- A raid marks the world besieged (`BESIEGED_FLAG`, wearDays) and syncs the siege state, so
  the structure loses its bonus in proportion to its clock. Before this a raid on a world
  nobody had bombarded fell back to vanilla's on/off: 20 days stripped Ground Defenses' x2.
- Every clock read is `siegeDisruptDays`: the bombard's revert leaves a ghost expire on a
  structure it set back to 0, and vanilla's raw read would stack the raid on top of it.
- Off with Nexerelin: its bombardment runs human colonies' menus and still writes the year.
- Hives: their defences were raidable already (HIGH, industries.csv). The Swarm Nexus and
  Fabrication Core take the same depth toll (2026-09-27, built, untested): vanilla lists them,
  so `modifyRaidObjectives` swaps each for an `OrganRaid` that quotes the toll. Their days add
  in full as well (2026-09-28): vanilla's shrink left a second raid on an organ with a long
  clock a few percent of its days. Weight: the Nexus by its defence bonus as worn (`nexusDefenseBonus` x
  resilience, 0.5 intact); the Core adds no defence, so `threatinc_coreRaidDepthWeight`
  (default 1, Ground Defenses intact) x its fabrication wear. Before this five tokens on the
  Nexus bought 100 days at a one-token casualty rate. On with Nexerelin too.

To verify: Disrupt lists the colony's fortifications at Heavy danger; one token on Ground
Defenses at an unbombarded colony leaves it at ~89% effect (not 0); the losses breakdown
grows with tokens on one fortification, and the objective's hover tooltip quotes its multiplier (x at the next token counts with one token, the current figure and a shallow-raids hint with more); a second raid adds its full 20 days; with
Nexerelin loaded the list is vanilla's.
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

The shield wears from orbit like any other fortification, all the way to 0 by the theatre's
wear days (2026-09-28: the orbital floor that used to stop it at 50% condition is gone) -
`siegeSlice` spends it the same way it spends the guns. `tickFront`'s
suppression grinds the shield at exactly the rate it grinds the guns too (`suppressShield`, full
the front's advantage-set rate while HOLDING or GRINDING, capped at `wearDays x 1.2` like
any other structure), so a ground front finishes what orbit alone only approaches. From orbit
the shield wears under the same diminishing returns as the guns (x its own condition).

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
| `ThreatGroundFronts.bombardStructures` (every slice, tactical and saturation, 2026-09-28) | each day the structures take `rate x condition x throughput`, `throughput` re-read each day; the shield takes `rate x shieldSoakMult x its integrity` |
| `ThreatGroundFronts.saturationSlice` | the day's fuel reaches the razing bar x `throughput` (`ThreatRazing`) |
| `ThreatGroundFronts.bombardDay` | the prompt and tooltip figures, already cut by the shield |
| `ThreatincMarketCMD.applyDangerClose` | the deep organs are under the shield too |
| `ThreatincMarketCMD.bombardTactical` | the shield is a bombardment target in its own right (`bombardable` fires for a shielded world with no guns at all), printed on its own line |
| `ThreatGroundFronts.tickFront` | `suppressShield` - boots grind it at the same rate as the guns |
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
- **The siege hull ledger (2026-09-29, closed economy).** The purge's counterpart to the
  strike's bank ledger: `ThreatPurgeFGI.setLedger` books an NPC siege's fleet points at launch and
  `settleLedger` settles them against the fleets that really spawn (shortfall from the base then
  the donors, excess refunded, unpaid warships pruned, supplies stamped on each fleet and
  refunded per fleet on arrival home). Details: docs/strategy-layer.md "Returns".
- **Marines by convoy: sized to the need (2026-09-29).** `ThreatConvoys.planRelief` sends
  what the counter-attack needs to beat the army by `siegeBeachheadMargin`
  (`ThreatGroundFronts.reliefNeed`: 0 with no Threat front on the world or once the garrison
  is enough; banked marines not yet armed count), less what is already at sea to the world
  (`inbound`). Every colony in reach that can spare marines sends what it can of that, the
  richest first, in parallel - it was one hull load (`convoyMarineCapacity`) from one donor
  at a time per invaded world. An NPC donor reaches as far as its fuel
  (`expeditionRangeLY`), not the flat `convoyRangeLY`.
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

To verify: Coatl-like siege gets a Relief Force at about 1.5x the Threat FP within a
day of loading; it engages rather than keeping its distance; a depot too dry to
provision one leaves the owner short, and the bounty and allied relief follow; no new
siege launches from that faction meanwhile.

### Blockade (2026-09-27, built, untested)

Threat warships over a human colony blockade it the way vanilla's Persean League
blockade does, per colony rather than per system (`ThreatBlockade`, condition
`threatinc_blockaded`, vanilla's blockade icon). The effect is accessibility only;
vanilla turns that into lost import capacity (`accessibilityPerUnitShipping` 0.1 per
unit, in-faction imports +0.5) and export income by itself. Strength follows vanilla's
`BlockadeFGI.getAccessibilityPenalty`: Threat points within `ORBIT_HOLD_RANGE` against
every non-hostile fleet there (station, patrols, a Guard, the player). Below 0.75x
nothing, below 1.25x half, else `blockadeAccessPenalty` (0.6, vanilla's figure).
Refreshed on the ground-front poll.

To verify: a colony whose Space column reads Threat shows Blockaded with -60%
accessibility; the penalty halves when defenders come close and lifts when they win
or the swarm leaves; the colony's import shortages follow.

**The other way round** (2026-09-30, under size upkeep, docs/hive-economy.md "Size
upkeep"). Warships hostile to the swarm over a hive world blockade it by the same
test (`ThreatBlockade.hiveCut`: their points within `ORBIT_HOLD_RANGE` against the
swarm's there - below 0.75x nothing, below 1.25x half, else all; the player's fleet
counts). Condition `threatinc_hive_blockaded` (`HiveBlockadeCondition`, display only).
The cut comes off what the world imports of its size upkeep - what its own forge does
not make - and off what it exports to the hive's stock beyond its own upkeep; vanilla's
shipping is held at the disrupted port's trickle (half) or nothing (all) by
`applyPortDisruption`, since vanilla's accessibility barely moves same-faction shipping.
A world paid under half its upkeep starves a size every 90 days at nothing: blockade
the worlds that import, raid the forge of the ones that don't. A forward base
blockaded by the Threat is fed the same way (`ThreatFrontlines.feedSize`, the Threat
blockade's share cut off what its faction sends).

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
the ruin at once, as a colonisation wave would (`foundColony`; paid from a colony's bank since
2026-09-29, see "Conquest pays" above). Any Defend contract fails.
`foundColony` only builds the market: `registerConquest` books it (stage, `colonyMarkets`,
growth/garrison clocks) and marks the system found, since the siege was public. Before
2026-09-27 this step was missing - conquered hives (Qaras, Yma) ran off the registry,
off the board and never besieged; `adoptUnbookedConquests` books those on load.
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
  `landingBlocked` (another army / the orbit, one reason string for the
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
the **Supply run** ladder over the table is set to Max, which asks for everything the base can
spare above its floor (a hull load until 2026-09-29) whether the front wants it or not; `ThreatConvoys.supplyAsk`, docs/player-aid.md "load ladders").

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
  `frontDangerCloseLossFraction` (0.005 a day since 2026-09-28) of its marines, in
  exchange the day also lands on the Core and port. Player-owned fronts only. Warned
  before confirm.
- **Sat bomb** (2026-09-28): a day of razing, the bombs on the owner's layers only; a
  front on the surface survives it and its layers count as already lost. No fallout.

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
2. The landing comes once every defence structure is fully worn out or the
   troops could hold - not before. The strike intel reads "suppressing the defences" then
   "moving to land - defences worn out" or "moving to land - the troops can hold,
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
    "(suppressed, N% effect)" falling to 0% eventually; the
    expedition loses ships (log "batteries cost N FP"); its intel reads "besieging from
    orbit" then lands. The sitrep lists one "Orbital siege" line per world.
11. A hive struck by a marine raid or saturation pass keeps most of its defence bonus now
    (no half-effect step): 20 days on a 300-day clock is 93%.
12. The player's tactical bombardment of a hive and of a human colony shows the slice
    prompt (days per structure, fleet points lost, fuel), takes the smallest ship(s) and
    names them, never the flagship, and moves each structure's clock by about the quoted
    days rather than 60 / 365. A second bombardment once fully worn is refused with the
    "as far as orbit can push them" line.
13. The button reads Support; its row label, the fleets table kind, the board Activity
    entry ("supporting the siege") and the Supply / Pull out refusal ("send Support
    first") all say Support. Saves from before the rename keep their Escort orders working
    under the new name.
14. A Support fleet on station over a besieged world with a clear orbit logs "Support over
    X: ... lost N FP" as the batteries bite and the world's fortifications fall to
    nothing; over a contested orbit it fights instead and suppresses nothing.
15. Balance to read off the log: what a default 400-point siege expedition does to a
    size-5 hive per slice, and whether its landing gate opens before `siegeOrbitDays`.
16. The planet shows vanilla's bombardment burst on every siege slice while the player is
    in-system (one burst per whole day of suppression added per world), from an expedition, a Support
    fleet or the player's own tactical bombardment. The Support button's tooltip and its
    confirm name the fleet that goes and quote the days per day it suppresses and the
    fleet points per day the batteries take.

### To verify - the landing fleet defends, and the Defend order (2026-09-07, built, untested)

17. A faction expedition against a two-hive system (Gamma Hero) lands at the first world
    and the log then reads "Siege pass (landing) vs X" followed by "Order issued: <faction>
    defending the orbit of X" for the landing fleet and any other empty fleet in the
    system; the fleets table shows them as **Defend**, "-" for days left, with Recall. No
    "Siege slice vs <second world>" follows: instead "Siege of <second world>: nothing left
    to land, no siege", or nothing at all once every fleet has joined the Defend.
18. A Defend fleet over a front that holds logs no "Defend over X: ... lost N FP" and the
    hive's fortifications recover; the moment the front cannot hold (a
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
    climbs by the summed rate and reaches full wear (300 on a hive) in days, not months.
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
      or "holds the orbit - the front holds / the defences are worn out"; away from it,
      "<fleet> at P FP is N units out (or: out of the system, in X), <assignment>, in a
      battle, blinkered/hunting". A leash line now says what the fleet was doing
      ("(was: ORBIT_AGGRESSIVE -> Gamma Brador IV '...')"), which names the other writer.
    Verify: four Defend fleets over one world give four lines a day, all "suppresses" once
    the front cannot hold, and the clock climbs at their sum toward the full clock (300 on a
    hive); no fleet is more than 1,500 units out for more than a day; if "strayed" repeats
    for one fleet, the "(was: ...)" text says who keeps re-tasking it.
    2026-09-28: orbit alone can now wear the defences all the way to nothing by itself - a
    front on the ground just gets there faster (`fortificationOrbitFloor` is gone).

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
  guns take more than last time. Repeat until `shieldThrough` reaches 1 (2026-09-28: the
  orbital floor is gone, so orbit alone gets there eventually).
- Land a front on that world: further passes take the shield to 0
  condition and `shieldThrough` to 1 faster than orbit alone.
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
- Land a front on a shielded world and hold it: the shield's condition keeps falling,
  reaching 0 at the same rate the guns fall.
  While GRINDING rather than HOLDING it should fall at `frontGrindSuppressMult` of that.
- Balance watch: at LunaLib defaults (`siegeSuppressDaysPerDay` 6, `siegeOrbitDays` 120) a
  600 FP expedition wears an unshielded size-5 colony's guns down within its orbit budget,
  but a shielded one takes longer - so from orbit alone it may time out. Landing does not
  need the guns already worn down (suppression lowers `defenderStrength` continuously), but
  if the swarm never manages a landing on shielded
  worlds, `threatinc_shieldAbsorbMax` is the dial; 0.5 roughly halves the penalty.

### To verify - fabricating troops from the fleet (2026-09-08, built, untested)

The mechanic starts where bombardment stops, so the setup is a Defend fleet over a world
whose fortifications are already fully worn with a front that cannot hold.

1. **It engages at the right moment, and only then.** Bombard a world to fully worn with a
   front on it that is short of `holdRequirement`. The fleet's log line should switch from
   the slice line to `... FP fabricates - the defences are worn out and the front is N
   short of holding`, and the board row from "covering the front on X" to "breaking up for
   the front on X". While not yet fully worn it must still bombard.
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
   slice line was quoting before the guns were fully worn. On a world with no defence
   structures at all, fabrication should still run and cost nothing.
8. **Both theatres.** A faction/player Defend over a hive, and a swarm station over a human
   colony under invasion. The swarm's messages should read "The swarm over X is breaking up
   its own ships".
9. **The visual.** In the same system, fragments should fall inward to the planet with a
   ping and a floating label - and it must NOT look like the bombardment burst. Out of the
   system, nothing should be drawn and nothing should throw.
10. **Off.** `threatinc_fabricateEnabled` false: a Defend fleet with the defences fully worn
    idles as it did before, with the old "the defences are worn out" reason, and the
    tooltip loses its fabrication lines.

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

### To verify - bombardment v2 (2026-09-28, built, untested)

Spec and build notes: docs/suppression-balance.md, "Bombardment and siege redesign v2".

1. **The menu.** Over a hive, Bombard reads "Tactical bombardment: N fuel a day." and
   "Saturation bombardment: up to N fuel a day."; after either, both grey out with the lock
   line until the next day.
2. **The tactical prompt** lists each fortification "X% to Y%", the shield when one stands,
   the ships the day's return fire takes by name ("Lost to return fire: ...", "Next to go:
   ..."), and the unrest the day leaves. Three days in a row take less off each day.
3. **Hive unrest.** After a tactical day a hive's stability reads 10 minus the unrest, and its
   Defenses tooltip falls with it; the Swarm Nexus no longer cancels the stability multiplier.
4. **Razing a seed.** A size-2 hive under a ~500 FP fleet with 1,000 fuel is razed in a day or
   two: a "Hive Razed" notice, the hive gone from the board, its system clear.
5. **Razing a big hive.** Saturation on a size 4+ hive puts "Razed" on the colony screen
   ("Level N: X of Y fuel", "Razed with N more fuel"), takes a size off per level paid, and the
   hive's tooltip reads "Growth: halted under saturation" until a day or two after the last.
6. **Combined arms.** With a front holding layers, the saturation prompt prices size less
   layers held; the front survives the saturation, and it can land at once afterwards (no
   fallout).
7. **The shield** cuts the day's delivery into the bar while it stands (prompt and bar agree).
8. **Fronts wear by advantage.** A front with troops about equal to the defence takes the
   guns down far faster than one at a tenth of it; a grinding front wears them too.
9. **An act of war.** Landing a front on a non-hostile colony costs -0.01 x size reputation
   and leaves the faction hostile.
10. **Raids.** Over a hive the raid list has no Ground Defenses, Heavy Batteries, Swarm Nexus
    or shield; the Fabrication Core is still there with its depth line. Over a human colony,
    vanilla's list (no military structures).
11. **Support and Defend** tooltips name the ships the day's return fire takes and the fuel;
    with no fuel aboard and none at home the fleet holds the orbit, "out of fuel to bombard
    with".
12. **Besieged colony at 0 stability.** No decivilization warning while the siege lasts.
13. **The AI.** The log shows each siege slice with its fuel, landings on "bombardment has done
    what it can" or "the troops can hold", and a raze-or-siege line with both costs and the
    razing fleet's points per target; a raze expedition reads "razing from orbit" and the hive
    goes as in 4-5.
14. **The commander's stop.** An NPC siege of a Heavy-Battery hive bombards a few weeks at most,
    keeps most of its fleet, and lands before its fleets drop to a third of what sailed - no
    siege aborts under the guns. Over a colony it bombards as the spec's tables say.
15. **One day over one world.** With three fleets of one expedition over a world, the log's
    "batteries cost" figures of a day add up to one day's return fire, not three.
16. **Bombard.** Each hive row shows Siege, Bombard and Hunt without overlapping (the Actions
    column is wider). The prompt lists the fleets, the fuel carried and drawn, and one line per
    hive; the expedition reads "razing the ..." on the fleets table, razes its hives in turn,
    and Recall brings the unburned fuel home. With no fuel past the passage the button is
    greyed and says so.
17. **Siege prompt** quotes "Carries N of the M fuel its bombardment burns."
**Test run 1 (2026-09-28, clone save_IWBomb1, 700 d, log only).** No exceptions; slices diminish;
colony return fire 3-5 FP/day as the spec says; no decivilization. Fixed after it, untested:
off-screen sieges and razings recompute the defence every step (it stayed at the intact figure);
an unspawned strike lands once its off-screen siege has run (Eventide and Mazalot lost a third of
the strike and never landed; the purge's gate the same); a live station holds a colony's orbit
whatever it weighs, so the first day no longer meets its x3; a suppressed fortification keeps its
stability bonus x condition (the first touch cost ~25% of the garrison); a razing is priced for
the size the hive will have on arrival (ThreatRazing.fuelToDestroyThrough(market, days)); the
raze-or-siege line logs on a changed verdict only. Still open: hive return fire at 0.008 - every
hive bombarded in the run had no guns.
**Test run 2 (2026-09-28, clone save_IWBomb2, 394 d, all off-screen).** Fixes 1, 3, 4 and the log
line held; 3 hives killed by NPC fronts, 0 colonies lost. The station gate (fix 2) is untested -
it only reads live fleets. Razing still razed nothing; fixed after it (jar 19:07, untested): an
unspawned expedition acts only when its payload window ends, and a raze-only expedition's window
was siegeOrbitDays (~120 d) - it is now the days its razing takes (razeRun); a razing short of the
top level pours nothing and takes its fuel home (ThreatRazing.shortOfALevel); ThreatRazing.pour
takes a level 0.5 fuel short (an exact pour left the last level standing). Open: hives never
answered - every hive bombarded had no Ground Defenses or Heavy Batteries.
**Test run 3 (2026-09-28, clone save_IWBomb3, 439 d, all off-screen, jar 19:14).** No exceptions.
Hives arm: 34/34 size >= 3 armed by day 35 (was 6). Razing razes: five hives razed from orbit,
Qaras 3 -> 2 -> 1 -> gone in 2 d; dead worlds the swarm refounds are razed again. Hive return fire
at 0.008: Gamma Golgotha II-L5 (defence 10,952) cost Hegemony ~58 FP/d at condition 0.72, 45 d,
5950 -> 4790 FP, then landed; a colony (Tartessus, 1,200) costs ~6 FP/d. "0 d" Threat landings
are the commander's trade (troops already hold, `readyToLand`), not skipped bombardment. Open:
Hegemony's 2,700 FP against Beta Vigri II (size 8, defence 14,400) - siege sizing weighs only
the Defense Swarm faced (385 FP), and the stop trades 1 FP for 1 defence point (siegeFPWeight
1.0), so it bombarded 27 d to 1,031 FP, landed at condition ~0.7, then lost orbit cover to 2,651
FP of regrouped swarms and stalled at the door.

**Test run 4 (2026-09-28, clone save_IWBomb4, 502 d, jar 20:06).** User's call after run 3: a fleet
point is worth far more than a marine, and return fire was overtuned - sizing left alone.
`bombardFPWorth` 30 (a day must take 30 defence off per fleet point lost: ~6,000 cr a point
armed and crewed, a marine 200) and `bombardReturnFirePerGunDefence` 0.008 -> 0.0008 (LunaLib
migration 5). No exceptions. Hive sieges bled a tenth as much and stopped sooner: Persean
League on Gamma Golgotha II-L5, 5,950 FP, 18 d, lost 85 FP (run 3: 45 d, 1,160 FP), landed
5,998; on Gamma Golgotha I (D 14,400), 7,200 FP, 49 d, D -> 3,906, lost 147 FP; Epsilon Qades
I-C 93 d, 54 FP, Fabrication Core destroyed. Colonies cost ~0.5 FP/d and the stop ends them in
9-24 d (were 39-45). Razing still razes. Because the day's worth (defence taken / FP lost) is
k x F/(F+D) / rate, independent of condition, worth x rate sets a line: a fleet under ~1/3 of a
hive's defence (F/(F+D) < 0.24; 0.36 on a colony) does not bombard at all and plans to land at
full defence - 10,000-11,450 marines on a size 7-8 hive. Marine postponements 327 (run 3: 120
in 439 d), launches 19 (23).

**Test run 5 (2026-09-28, clone save_IWBomb5, 506 d, jar 20:42).** Run 4 plus short of troops,
soften first. No exceptions. Beta Vigri II, run 3's failure: Persean sailed with 5,569 of
10,019 marines, 5,950 FP bombarded 21 d (D 12,600 -> 7,026, lost 97 FP), landed 5,479 and
eradicated the hive. Hegemony likewise took Gamma Golgotha I (5,937 of 10,217 marines, 24 d, 123
FP). Launches 25 (run 4: 19), marine postponements 65 (327), fuel postponements 287 (30) - the
longer bombardment's ordnance (~35,000 fuel for a size-8 hive) is now what a base waits for.
Hives eradicated 5 (4, 3). Two short landings never landed: Defense Swarms that reinforced after
the launch ground them below the abort line with the ordnance unburned (the orbit gate's
problem, not the guns'). Threat beachheads overrun by colony garrisons: 29 (runs 3-4: 29, 30).
The "lands X of Y" line is logQuiet since (21:09 jar, untested).

### Overnight 2026-09-29 - siege AI fixes (built, tested in runs N1-N3)

Runs on clones of save_IWLong19 (day 0 = 0215-05-15, no player, all sieges off-screen), findings
per checkpoint in the session scratch folder. Run 6 / N1 baseline at ~540 d: 22-28 launches, 5 hives
eradicated, 0 colonies lost, Threat hives 39 -> 38 - a stalemate.

- **Off-screen break-off** (`ThreatPurgeFGI.breaksOffAbstract`, called from
  `SiegeRaidAction.autoresolve`). Vanilla's `FGRaidAction.autoresolve` weighs the expedition's
  strength in the system against every hostile fleet there plus the station; where the defence is
  as strong it charges up to 75% damage and skips the raid. `breaksOff` only reads live fleets, so
  an unspawned siege or razing met by converging Defense Swarms came home at 25% with its ordnance
  unburned (7 of 15 razings in run 6, "strength 25%" x10). The same test now runs first, in
  vanilla's units: outweighed by `siegeBreakOffRatio` over a hive still to take, it turns home
  intact ("Abstract break-off at X", "Siege called off"). No break-off while the faction has a
  front on ANY of the expedition's worlds (`holdsAFront`). N2: 14 called off, 0 razings worn.
- **Sibling forward bases pool** (`siegeDonors`, `donorAvailable`). Forward bases were excluded as
  donors; run 6's Hegemony held 19-53k fuel at Alpha Spair I and Calu while Temblor postponed 73
  times for fuel. Now every market in reach gives above its floor; a forward base keeps back
  `siegeOutpostKeepMonths` (3) of its garrison's supply upkeep (`ThreatFrontlines.garrisonUpkeepAt`).
  N2: launches 26 at 270 d (N1: 14).
- **A called-off siege remembers** (`IncursionManager.swarmsMet` / `noteSwarmsMet`). The launch
  gate weighs one world's Defense Swarms (`npcSiegeOrbitPerWorld`), but garrisons converge and
  vanilla's fight weighs the system: N2's sieges sailed into 4-7x their weight, Goodfellow twice.
  `callOff` records the swarms met on the system per faction for `siegeMetMemoryDays` (90);
  `siegeOrbitNeeded` weighs the larger of the two. The bounty the call-off posts sends hunters in.
- **Threat beachheads sized to survive** (`ThreatStrikeFGI.beachheadLanding`,
  `ThreatGroundFronts.beachheadTroops`). Strikes never asked `beachheadSurvives`: abstract ones
  landed after "0 d" of bombardment (the world's even share, 200-300 troops) against counter-attacks
  of 440-680, and 37 of 42 beachheads were overrun in N1 with no colony taken. Now the swarm's
  siege (live and abstract) uses the faction form of `readyToLand` like a purge, and a first
  landing short of the line lands more of what the strike carries, then breaks hulls up at
  `fabricateTroopsPerFP` for the rest (abstract strikes charge `fabricatedFP` off their strength);
  a strike that cannot reach it holds back ("Strike landing at X held back").
- LunaLib migration 6: `frontDangerCloseLossFraction` 0.05 -> 0.005 (the v2 default never reached
  a stored settings file).
- **The orbit gate weighs the whole system** (`npcSiegeOrbitSystem`, `siegeOrbitWeighed`,
  `systemSwarms`). With the break-off in place, N3's first sieges into heavy systems still sailed
  blind (Gamma Spair I-B: 10,656 FP of swarms met by 1,450). The gate now weighs the larger of the
  strongest target world's garrisons, every Defense Swarm in the system, and the swarms last met
  there. A heavy system stays postponed (bounty posted, hunters thin it) and the easiest-first walk
  moves the base on. N4 vs N3 at 540 d: 17 launches vs 37 for 11 vs 13 hives killed, fuel waits
  182 vs 399, hunt battles 41 vs 13, Threat hives 33 vs 34.
- **The swarm relieves its fronts** (`ThreatGroundFronts.losingGround`, `strikeReliefFirst`).
  `wantsExpedition` read only `frontCanHold` (the fortification line, 0.17 x the defence), which
  stayed true while garrisons won every counter-attack. It now also asks whether the world's
  counter-attack by `siegeBeachheadMargin` beats the front; such a front in reach takes the next
  strike before any new target.
- **Off-screen expeditions resolve on arrival** (`ThreatPurgeFGI.resolveOnArrival`,
  `abstractResolveOnArrival`). Vanilla autoresolves an unspawned raid when its payload segment
  ENDS, and that segment is `siegeOrbitDays` (~120 d): strikes whose siege took 0-51 d landed
  150-180 d after launch, and relief reached fronts months after they fell. The same resolution
  now runs a day after arrival and the segment ends. N5 vs N4: relief passes 19 vs 5, razings 10
  vs 6, strikes 139 vs 82 (the staging hive frees sooner).
- **The strike gate** (`IncursionManager.strikeOutweighed`, `strikeDefenceGate`). Nothing weighed
  a target's defence before a strike: 17 of 24 measured strikes in N4 landed nothing, 12 of 13
  against forward bases - vanilla's off-screen fight skips a raid the defenders hold as strongly
  and charges up to 75%. The swarm now passes over a world whose system defence (vanilla's
  `WarSimScript` enemy + station strength) outweighs the strike its staging hive can muster
  (`ThreatColonyManager.peekGarrison`) by `siegeBreakOffRatio`.

- **Relief strikes go to their front alone** (`launchStrike`): a strike whose target
  `wantsExpedition` no longer sweeps the rest of the system, so the whole troop pool reinforces
  the front it was sent for (swept, it split into even shares and the front got 300).
- Review fixes (run N8): `ThreatPurgeFGI.finish` keeps an abort that vanilla's expired-route
  finish would clear; `resolveOnArrival` leaves raze-only expeditions to their razing window
  (`razesAll`); `beachheadLanding`'s fabricated troops join the pool and a live fleet that comes
  up short keeps them aboard rather than land under the line; `swarmsMet` lives in sector
  memory (a distant system's own memory may not tick its expiry).
- **Measured, off-screen**: human sieges do not bombard themselves down - 14 large abstract
  sieges in N5 lost 0-2.2% of their fleet to the guns. Live Defend fleets are untested (every
  NPC siege in the harness resolves off-screen).
- **900-day run (N7)**: Threat hives 38 -> 27 by day ~680, then back to 34 (total size 271 ->
  223) as the swarm refounds razed worlds; 1 Threat ground victory.
**Relief focus result (N9 540 d, N10 900 d, dba556f):** the swarm takes colonies - 3 by day 540 in both runs, 4 by day 900 (Asharu, Jangala, Nachiketa, Yama; Hegemony 12 -> 9) - while the humans eradicate 7 and raze 20, hives 39 -> 30 (size 273 -> 226). No snowball.

Open (design, for the user): before the relief focus the Threat took almost no colony. A ~300-marine beachhead
pushes at x2 casualties (`threatPushLossMult`), wears to half in ~2 months and is overrun while
human relief convoys bring 200-1,800 marines; strike relief comes in 300-troop shares.
