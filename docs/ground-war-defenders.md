# Ground war - defenders, shield, blockade, a colony's fall

Split out of `ground-war.md` on 2026-10-01. One-line answers: `facts.md`.

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
  and x vanilla's input-deficit factor weighed by `defenseDeficitWeight` (0.5: a full deficit halves the bonus, a partial one takes its share; both theatres, `ThreatSiegeMalus.weighDeficit`), so a starving battery gives less either way but a blockade alone can never switch the guns off (2026-10-12).
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
shipped in mid-siege buys a garrison over weeks. The player's words for the two figures (the user,
2026-10-24): the armed marines are **deployed**, the rest are **reserves, arming**, and any the
base holds for the siege it stages are **staging for X** (`ThreatReserves.stagingMarines`); never
"stock". While the army stands the colony keeps the marines its ground victory wants
(`ThreatReserves.frontKeep` = `ThreatGroundFronts.deployWant`, the line moving its way at
`frontPushBackRate`, or holding where that is out of reach) and may stage or sortie the rest
("They can stage an expedition while under siege assuming they have enough marines to retake the
world"). The ramp runs on its own clock (`marineArmedAt`)
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
`counterAttackStrength` are separate questions on the `Theatre`. Marines enter the first
whole and the second at `marineCounterAttackMult` - **1.0 since 2026-10-17** (the user: "the
marines have to take districts back or bleed the enemy dry"; 0.25 from 2026-09-08 to then, holding
a sledgehammer stockpile back from overrunning every beachhead); the garrison proper enters both
whole. A hive still counter-attacks with everything, having no marines to hold back.

**3. The defenders bleed.** THE ATTRITION RULE (the user, 2026-10-24, both sides alike -
`ThreatGroundFronts.attritionPerDay`): armed marines / `attritionTroopsPer` (100) x (the army's force /
the colony's force) x 1 / the colony's cover x the veteran loss factor, a day (`worldLossPerDay`). It
replaced `defenderLossPer30Days` (0.20) of the engaged frontage scaled by a pressure capped at 1 - the
user asked why the cap, then set the rule: "attrition should be based purely on cover, pressure and
soldier experience", with the army-size term per 100 troops. Plus
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
whole garrison. The war board quotes both through `defenderLossPer30Days` (THE ATTRITION RULE since 2026-10-24,
when the engaged cap went) and `counterAttackDefenderLoss`, so its figures follow.

**3b. Troops to spare buy tempo.** The cadence used to read stability and nothing else:

    interval = frontCounterAttackDays (40) / (stability / 10),  then / 1.5 with a military command

(The stability divisor itself went on 2026-10-17 - the user: "i dont think stability should affect
ground battles at all" - so today it is 40 / 1.5 with a military command / the tempo below, and no
more than `frontOverrunMusterDays` (10) when the counter-attack would beat the army past the overrun
odds, `counterAttackOverwhelms`: a garrison that massively outnumbers a landing does not wait a month.
983 defenders over 143 troops had read 30 days at stability 5.)

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

Hives pace on how well they are fed (`ThreatGroundFronts.hiveCounterAttackPace`, 2026-09-30):
with size upkeep on, `min(1, fed share / break-even) x (size - strata held) / size`, so a starving
hive, or one whose strata are mostly taken, strikes back slower; with it off, on vitality
(`ThreatColonyManager.computeHealth`). A hive has no garrison to spare or withhold, so there is no
ratio for the term to read.

**4. Troops have quality.** See Veterancy below.

The consequence to keep in mind: the defence figure now **moves every tick**, where it used to be
a wall. `frontStateHysteresis` (0.1) exists because of that - a front sitting on a threshold would
otherwise cross it back and forth and announce every flip.

### Defenders burn armaments (2026-10-17, built, untested)

The user: "i dont like how marines defending human worlds dont consume armaments. this needs
changing". A colony's armed marines now fight on the same supply rule as a landed front.
Each front tick every armed marine - the whole garrison is called up and in the field, where the
bleed's `engaged` frontage only caps who can be killed - burns `dailyUpkeep(armed)` x days of heavy
armaments, x `frontPushUpkeepMult` (2) while they push as a landing pays (the user, Sindria
2026-10-24), out of the colony's stockpile (`ThreatGroundFronts.burnDefenderArms`,
`defenderDailyUpkeep`; a player colony's resource stockpile, an NPC's ledger). A colony with
none in stock has dry defenders: `defendersDry` multiplies `colonyMarineDefense` by
`frontDryEffectivenessMult` (0.5), so both the line it holds and its counter-attacks are
halved until armaments land. The garrison proper (vanilla's ground-defense stat) is not
touched: it is the structures, not the marines. Hives are never dry - the swarm fights on
bodies (`needsArms`). Knob `defenderArmsEnabled` (default on).

Who gives them: any colony in reach that can spare them (`ThreatConvoys.reliefSpare` / `spare`), and
since 2026-10-24 a colony under an army itself spares its heavy armaments above its own
defenders' want (`frontWorldSpare` over `defenderArmsWant`; the user, Sindria: Volturn, the
Diktat's one armaments maker, sat invaded on 1,800 while Sindria ran dry) - its marines, fuel
and supplies still stay home.

What asks for them: `defenderArmsNeed` = `frontResupplyDays` (60) of the burn the defenders
will have once `reliefNeed`'s marines stand with them, less the stock. NPC relief convoys
(`ThreatConvoys.planRelief`) carry it beside the marines, a load of `RELIEF_MIN_ARMS` (100)
or more sailing even with no marines to spare; the player's Reinforce order
(ground-war-fronts.md "Orders from the board") asks for the same figure. The front tooltip
shows `Sindria arms N d = stock / (armed marines x each per 30 days [x 2.0 attacking] = M/d)`
or `Sindria arms 0: strength x 0.5 dry`; the colony condition tooltip has the same line.

### The defenders choose too (2026-10-17, built, untested)

The user, after watching a 983-defender world sit on a 143-troop landing: "the player should have a
similar set of options as when they are attack. for example just because the enemy is pushing doesnt
mean you cant push right back, or switch to dig in yada yada. i think the same actions should be
available regardless of if you attack or defend. and if defends push they take strata/districts
back. or they can hold indefinitely and grind down the enemy with dig in (they should also get
entrenchment bonus but its lost if they lose a strata if that makes sense. basically we can make
defending dynamic and interesting with strategy as well".

A colony with an army on it has a stance of its own, carried on the front (`GroundFront.defence`,
null = no order given yet; it reads as push until the first tick picks one, below):

- **Push** - the garrison counter-attacks on the cadence (`Theatre.COLONY.counterAttackInterval`:
  40 days / 1.5 with a military command / the force-ratio tempo, floored at `frontOverrunMusterDays`
  10 when it would overrun; no stability term). A win retakes a district (`hiveCounterAttack`,
  `strataHeld--`) or batters a beachhead that holds none; every attempt costs
  `defenderCounterAttackLossFraction` of the engaged marines, win or lose. The defence figure has no
  cover: pushing spends it day by day, as the attacker's does (the user, 2026-10-24; before, going over the top was exposed outright).
- **Dig in** - the garrison holds fire: no counter-attack is mounted (`defendersHoldFire`, read by
  `hiveCounterAttack`, `shouldBrace` and every board readout), and it earns cover:
  `defenderEntrenchDays` accrues while dug in, and `COLONY.defenderStrength` is multiplied by
  `defenderCoverMult` = 1 + (`frontEntrenchDefenseBonus` 1.5 - 1) x min(1, days /
  `frontEntrenchDays` 30). That figure is what the attacker pushes against, so a dug-in world is
  slower and costlier to push (`pushDaysEstimate`, `pushLossFactor`), harder to hold
  (`holdRequirement`) and bleeds its defenders less (the pressure term). When the army takes a
  district the cover is wiped (the user's "lost if they lose a strata", 2026-10-17, a hard rule
  since 2026-10-24); switching to Push keeps the days but applies no cover until the garrison digs in again.
  Ordering Push from Dig in starts the muster clock afresh (`orderDefenderPush` sets
  `lastCounterAttack` to now): the first counter-attack comes a full interval later.

**Who orders.** The player's own colonies from the fronts table: the army's row carries both
**Push** and **Dig in**, the marines' current one greyed, beside **Reinforce**. Both are shown because
a lone toggle reading "Dig in" under a swarm landing already "dug in" looked like the wrong side's
(user, 2026-10-17 night) - which is also what led to the per-tab fronts table the next day, where the
Stance column is the tab's own side's
(`ThreatWarBoard.addFrontButtons`, `ThreatFactionView.BUTTON_DEFEND_PUSH` / `BUTTON_DEFEND_ENTRENCH`,
own colony only). NPC colonies run `tickDefenderStance` each tick: since 2026-10-24 (`doctrineDefendersPush`, the
user: marines handed to Sindria "to push back" sat dug in for a week) push while pushing moves the
line toward them past `frontGrindBand`, dig in for the cover while it does not - unless dug in the
line would move against them while pushing holds it (the mirror of the swarm's `doctrinePushes`).
The player's colonies take the same pick ONCE, on the first tick after the swarm lands while no
order has been given (`front.defence` null); after that only the player's buttons change it (the
user, 2026-10-27, Arkoman 183 marines against 1,252 troops "pushing" from the day of the landing:
an unset stance read as push and the player's worlds never ran the AI, so every player colony
opened over-extending against a beachhead it could not move; log "Marines of X push / dug in
until ordered"). A front already on a player world when a save loads gets the pick on its first tick.
**Entrenchment is never worn by the enemy** (the user, 2026-10-24, reversing a "push wears cover"
rule that lived for an hour): a side's entrenchment days run from -`frontEntrenchDays` to
+`frontEntrenchDays`. They grow while dug in whatever the enemy does, are spent while pushing, and
past zero the push is **over-extended** - spread thin to the same degree as digging in gives
cover: the side's whole figure x 1 / (1 + (`frontEntrenchDefenseBonus` - 1) x over-extension), so
after 30 days of pushing past zero it fights at 2/3 (`overExtension`, `overExtendMult`,
`defenderOverExtension`, `defenderOverExtendMult`; the swarm's through `entrenchMult`, the colony's
on `defenderStrength`, `counterAttackStrength` and the line's D). A district changing hands resets
BOTH sides to 0, dug in or over-extended (`takeStratum`, `loseStratum`). The kept fractions
(`frontEntrenchKeptFraction`, `defenderEntrenchKeptFraction`) and the reinforcement dilution are
gone. The board reads "x 0.60 over-extended (12 of 30 days)" and "(over-extended x0.80, 12 of 30
days)" in the push line. A long push therefore runs out of steam by itself: the doctrine sees the
rate fall into the band and digs in to recover, which is the rhythm the user asked for.
**Each side decides against the other's best reply** (the same afternoon; the log at Sindria showed
the marines and the swarm swapping stances every few seconds, each answering the other's last move:
marines dig in, the swarm pushes and gains, the marines push to hold, the swarm digs in since
pushing moves nothing, the marines dig in since the line stands...). `lineRate` at a pair of
stances is what each weighs; a rate inside `frontGrindBand` weighs as 0 (`standing`). The swarm takes the stance whose worst case over the
defenders' two replies is best for it, the defenders the one whose worst case over the swarm's two
is best for them, and a tie digs in (cover is free, a push bleeds). Against a fixed stance - a hive
world, the player's colony, a player landing - the rate is read at that stance. The settled
outcome at Sindria is marines pushing against a dug-in swarm with the line standing: dug in they
would lose a district every ~40 days, and the tooltip says so ("Pushing holds the line").
Before, the test was "would one counter-attack win" (`counterAttackStrength > defenseStrength`),
which never said yes against a dug-in swarm the need was sized to push. That is a behaviour change for NPC worlds: before, an outmatched colony counter-attacked
on the cadence anyway, fed the front experience and lost marines each time. Hives have no stance and
counter-attack with everything, as before.

**Readouts** (`ground-war-fronts.md` "Reading a front": each tab reads its fronts from its own side).
On the colony owner's tab the row's Force reads "garrison <- landing", Stance is the garrison's (push /
dug in), Losses and Arms are its own, Held the districts still its own; the Counter column reads "held"
while the garrison is dug in. The row tooltip, which keeps both sides, says "Marines dug in: defence xN
after D days, no counter-attacks; the cover is lost with a district" or "Marines push: counter-attacks
on the cadence, no cover", and the muster line names the overrun pace when it applies. The colony's
ground-war condition tooltip says the same in a line.

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
| `ThreatGroundFronts.saturationSlice` | the day's fuel reaches a colony's razing bar x `throughput` (`ThreatRazing`; a hive has no bar) |
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
  of marines from the colonies in reach that can spare them (`ThreatConvoys.planRelief`,
  ahead of every depot; the player's mobilised faction gets the convoy too). The besieged
  colony's own banked marines are **committed** (`ThreatReserves.committed`): no sortie or
  convoy may ship them away mid-siege.
- **The Defend contract.** A Defend mission on the colony fails only when the swarm
  actually puts troops on the ground (`landOrReinforce`) or a saturation pass hits; a
  soften pass, or a landing turned back from a held orbit, is the contract working.
- **The line** (2026-10-23, replacing the counter-attack cadence). The defenders push the
  line with `defenderPressure` - the garrison plus armed marines at `marineCounterAttackMult`
  (1.0), the force figure the board shows and nothing on top: the military command x1.5 went on
  2026-10-24 (the user, Sindria: "no invisible multipliers", and the High Command was disrupted
  anyway) - continuously, against the army's strength in its cover; the army pushes back
  with its own against the defence figure. Dug in ("The defenders choose too") they push
  nothing and hold from cover instead. A district comes back every `frontLineBaseDays` /
  |rate|; a beachhead holding none is overrun. `ground-war-fronts.md` "The line".
- **Batteries.** While the expedition's fleets circle the world its Ground Defenses and
  Heavy Batteries are killing ships (the duel above); a colony whose fortification is
  intact makes the siege long and expensive, and relief that arrives during it finds a
  weakened expedition.
- **Attrition.** A colony that simply survives past the invaders' 90 days of armaments
  has them dug in and waiting; hold the orbit against the next expedition and they wither,
  make their final push, and collapse.

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

**Unfought swarms lift nothing while you hold station** (2026-10-12, the user). While the
player's fleet is within `ORBIT_HOLD_RANGE` of the hive world (`ThreatBlockade.playerHoldsStation`)
the swarm's side of the test counts only swarms without the unfought mark
(`foughtPointsNear`; `ThreatColonyManager.UNFOUGHT_KEY`, set on a reinforcement that reaches the
orbit under hostiles - swarm-defence.md "Fight on arrival" - and cleared by its first battle,
`FoughtMark`). Off-screen orbits keep the plain weighing. Why: three reinforcements sailed
blinkered into the player's orbit and their weight alone turned the cut from half to nothing,
the batteries' machinery and metals back with it, before a shot was fired. The log line adds
`, K of it fought` when the weighed figure is less than the swarm's whole.

**Convoys wait off a blockade** (2026-10-17, the user: "if the colony is blockaded the
resupply convoys should not be able to enter and should circle and wait until giving up
after x period"). Every plain convoy - the player's Supplies and Reinforce, an NPC's relief
and staging traffic, an ally's aid - that is inside the system of a colony whose
`ThreatBlockade.penalty` reads above 0 (half or full, the Blockaded condition's own
weighing, live) holds a passive orbit at the system's jump-point
(`ThreatFleetOrders.interceptPoint`) instead of running in (`ThreatConvoys.holdsOff`, from
`poll`). It runs in the moment the blockade lifts (`ThreatReturns.onLeg` re-sets the leg),
and after `convoyBlockadeWaitDays` (30) still blockaded it turns for home with its cargo
(`returnHome`). Front runs keep their own door (`pollFrontRun`, Defense Swarms over a hive);
an outpost has no market to blockade. Log: `Convoy holding off X: blockaded by the Threat`,
`Convoy runs in to X: blockade lifted after N days`, `Convoy gave up at X`.

### When the colony falls

**THE OCCUPATION (the user, 2026-10-27, `docs/occupation.md`, built untested): with `occupationEnabled` (default) none of the below runs for the swarm.** `colonyGroundVictory` hands the front to `ThreatOccupation.occupy`: it stays on every district, the market keeps its faction, vanilla's deciv is held off, its claimed industries feed the swarm and a pool buds Infestation Swarms over the world; the owner reclaims it by pushing the front off. The conversion below is the knob-off path.

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

