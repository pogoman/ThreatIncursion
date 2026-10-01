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

Hives pace on how well they are fed (`ThreatGroundFronts.hiveCounterAttackPace`, 2026-09-30):
with size upkeep on, `min(1, fed share / break-even) x (size - strata held) / size`, so a starving
hive, or one whose strata are mostly taken, strikes back slower; with it off, on vitality
(`ThreatColonyManager.computeHealth`). A hive has no garrison to spare or withhold, so there is no
ratio for the term to read.

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

