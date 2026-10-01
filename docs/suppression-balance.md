# Suppressing a colony's defences - every path, side by side

Written 2026-09-28, the day the orbital floor (`fortificationOrbitFloor`, 0.5) was removed.
**"Bombardment and siege redesign v2" below is BUILT (2026-09-28, untested)** - its build
notes close that section. The paths and tables before it describe the code as it stood
between the floor's removal and v2, and are kept as the case that led to the redesign.
Figures are computed from the formulas, not measured in-game (a throwaway Python sim
produced the tables).

Assumptions for every table: stability 10, no deficits, hazard 100%, no skills, no ground
support, no planetary shield, LunaLib defaults. Colony theatre only (a hive runs the same duel
at rate 30 / wear 300 - before v2 at a quarter of the fuel - and has no razing bar, section 4).

## The defence figure

`D = base(size) x (0.25 + 0.075 x stability) x product(1 + bonus x condition) x station`

- base: size 3 = 50, size 4+ = (size - 3) x 100 (vanilla `PopulationAndInfrastructure`).
- bonus: Ground Defenses 1, Heavy Batteries 2 (replaces GD), Patrol HQ 0.1, Military Base 0.2,
  High Command 0.3. Stations x1.5 / x2 / x3, binary - a station must be beaten in battle before
  anything bombards or raids, and that disrupts it, so stations never enter the dial.
- condition = 1 - clock / 180 (`fortificationDisruptDays`), on a besieged colony
  (`ThreatSiegeMalus`). Worn out = every bonus gone = base x stability only.

| Colony | D intact | D worn out | Hold line (0.17 x D) |
| --- | --- | --- | --- |
| Size 3, no fortifications | 50 | 50 | 9 |
| Size 5, Ground Defenses | 400 | 200 | 68 |
| Size 6, Heavy Batteries | 900 | 300 | 153 |
| Size 7, HB + High Command | 1,560 | 400 | 265 |

## The paths

| Path | Who | Suppression | Cost |
| --- | --- | --- | --- |
| Orbital siege slice (hovering) | Threat strikes, NPC expeditions, player Support / Defend | 12 x F/(F+D) days per day, net (a hovering fleet makes up the 1 d/day run-down) | 0.02 x F x D x share/(F+D) FP per day; share = 1 - 1/battery mult |
| Player tactical bombardment | Player | 12 x F/(F+D) x 15 days, instant | return fire for 3 days; fuel = D (0.25 D on a hive); -1 stability |
| Fortification raid | Player only (off with Nexerelin) | tokens x 20 d (HIGH) x lootMult, added in full, no cap | vanilla losses x depth toll 1 + (n^1.5 - 1) x (1 - RE) x weight |
| Ground front | Anyone landed | HOLDING +2 d/day = net +1; GRINDING net 0 | marines, attrition 0.30/30 d (x2 Threat) |
| Saturation bombardment | Player (colony: vanilla) | 365 d on everything - strips all fortifications | fuel D, stability -10, size -1 |

## Time and price to wear the fortifications out

F = fleet points over the world, M = marines aboard.

### Size 5, Ground Defenses (D 400 -> 200)

| Force | Hover siege | Player tac bombs (chained) |
| --- | --- | --- |
| 100 FP | 72 d, 29 FP lost | 4 bombs, 1,204 fuel, 7 FP, stability -4 |
| 300 FP | 33 d, 29 FP lost | 3 bombs, 872 fuel, 9 FP, stability -3 |
| 1,000 FP | 21 d, 28 FP lost | 2 bombs, 638 fuel, 11 FP, stability -2 |

| Marines | One deep raid (all tokens on GD) | Shallow chain (1 token, 3 d apart) |
| --- | --- | --- |
| 500 | 6 tokens, 126 d, 400 lost | 10 raids / 27 d, 243 lost |
| 2,500 | 9 tokens, 189 d, 572 lost | 10 raids / 27 d, 315 lost |

### Size 6, Heavy Batteries (D 900 -> 300)

| Force | Hover siege | Player tac bombs (chained) |
| --- | --- | --- |
| 100 FP | 318 d, 90 FP lost (the fleet all but dies) | 7 bombs, 3,839 fuel, 17 FP, stability -7 |
| 300 FP | 54 d, 89 FP lost | 4 bombs, 2,353 fuel, 23 FP, stability -4 |
| 1,000 FP | 27 d, 88 FP lost | 2 bombs, 1,440 fuel, 29 FP, stability -2 |

| Marines | One deep raid (all tokens on HB) | Shallow chain |
| --- | --- | --- |
| 500 | 4 tokens, 84 d, 400 lost | 10 raids / 27 d, 562 lost |
| 1,000 | 5 tokens, 105 d, 800 lost | 10 raids / 27 d, 497 lost |
| 2,500 | 7 tokens, 147 d, 2,000 lost | 10 raids / 27 d, 578 lost |
| 5,000 | 8 tokens, 168 d, 2,066 lost | 10 raids / 27 d, 643 lost |

### Size 7, Heavy Batteries + High Command (D 1,560 -> 400)

| Force | Hover siege | Player tac bombs (chained) |
| --- | --- | --- |
| 100 FP | never (fleet dies first, 99 FP lost) | 9 bombs, 7,727 fuel, 24 FP, stability -9 |
| 300 FP | 84 d, 144 FP lost | 5 bombs, 4,732 fuel, 33 FP, stability -5 |
| 1,000 FP | 33 d, 143 FP lost | 3 bombs, 2,958 fuel, 44 FP, stability -3 |

| Marines | One deep raid (HB only) | Shallow chain |
| --- | --- | --- |
| 500 | RE below 0.25 - cannot raid | - |
| 1,000 | 4 tokens, 84 d, 800 lost | 10 raids / 27 d, 933 lost |
| 5,000 | 8 tokens, 168 d, 4,000 lost | 10 raids / 27 d, 940 lost |

A ground front alone wears any of these in 180 days (net +1 d/day while HOLDING).

## Asymmetries

1. **The tactical bombardment is fifteen days of siege for three days of return fire.** Per
   suppression day it costs a fifth of the hulls a hovering fleet pays, and it lands at once.
   Nothing checks for a recent bomb before the next one (vanilla's 80% skip is gone with our
   slice), so bombs chain until fuel runs out. Spacing them a day apart changes nothing: the
   clock only loses a day between bombs. One bomb a day wears 15x faster than hovering with
   the same fleet. Fuel is the only real price, and each bomb's -1 stability also lowers D, which
   hovering never does. With the floor gone, 2-5 bombs now strip what used to stop at 50%.
   - Option A: return fire = suppression days (`tacBombardSuppressDays` = `siegeBombardSliceDays`
     on the attrition side). Re-run: size 6 HB, 300 FP -> 4 bombs, 2,408 fuel, 111 FP (hover: 54 d,
     89 FP). Speed is then paid in fuel plus slightly more hulls, because each bomb meets the
     batteries as they stood before it.
   - Option B: a separate return-fire knob, e.g. 8 d, for rough parity with hovering.
2. **Hovering costs about the same hulls whatever the fleet size** - roughly D x share / 600 per
   suppression day - so fleet size buys speed, not efficiency. A fleet under about D/9 FP dies
   before the job is done.
3. **Splitting a fleet still wears faster.** The odds are F/(F+D) per fleet, so two fleets of
   F beat one of 2F (at F = D: +50%). Hull cost per suppression day is unchanged. Option: sum
   every besieger's points over a world into one ratio.
4. ~~Each fleet made up its own run-down~~ - FIXED 2026-09-28 (`ThreatGroundFronts.madeUpDays`):
   N fleets over one world got N days back per day. The make-up is now once per world.
5. **Raids: the depth toll works, and the shallow chain is the exploit.** One token per raid, the
   rest in reserve, waiting 3 days for preparedness to fade: 10 raids in 27 days wear Heavy
   Batteries out for about a quarter of the marines one deep raid loses (2,500 marines: 578 vs
   2,000). Each raid costs -1 to -3 stability and draws a military response (not in the tables).
   - **Suspected bug:** `ThreatFortificationRaids.depthMult` reads its pressure from
     `MarketCMD.getRaidEffectiveness(market, player)`. That is the AI raid formula
     (0.25 x max personnel), not the player's raid effectiveness (marines + support), so the
     depth toll misreads the fight. Fix: compute pressure from the raid's own attacker strength.
     Not yet verified in-game.
6. **Only the player raids fortifications.** NPC and Threat raid AI cannot target them - vanilla
   tags them unraidable - so the fastest marine route is player-only.
7. **The shield taxes orbit only.** Raids and fronts ignore it; orbit pays about 1.9x the time and
   hulls until the shield is spent. With the floor gone, orbit can now spend it fully.
8. **Fronts are now the slowest wear.** Net +1 d/day means 180 days. Before, fronts were the only
   route past 50%; now they matter for districts and the hold test, not for the guns.
   Option: raise `frontSuppressDaysPerDay` if boots should still out-wear a hovering fleet.
9. **The Threat lands sooner than NPCs.** It lands once its troops reach 0.17 x D at 0.75
   entrenchment, with no beachhead test. NPC expeditions need about 0.83 x counter-attack
   strength, so they hover far longer. With the floor gone, a "wait until worn out"
   expedition hovers up to twice as long as before (180 d of clock, not 90), and lands smaller
   (`siegeRaidStrNeeded` now sizes against fully worn defences).
10. **Saturation bombardment is a full strip in every case now.** Before, a besieged colony held
    its fortifications at 50%. Vanilla's 365 days are past the 180-day wear, so it strips
    everything, at stability -10 and size -1.

## Bombardment and siege redesign v2 - FINAL SPEC (2026-09-28, BUILT the same day, untested)

This section is the complete, agreed design from the 2026-09-28 brainstorm. Every item below
is either the user's decision or a recommendation the user accepted ("do all
recommendations"). It was built the same day; "Built 2026-09-28" at the end of the section
records what the build decided where the spec left it open. v1 at the bottom is history only.

### State of the code at handover

- **Built, compiles, jar NOT rebuilt, untested, uncommitted:** the 50% orbital floor
  (`fortificationOrbitFloor`) is removed everywhere, and the per-world make-up fix
  (`ThreatGroundFronts.madeUpDays`, asymmetry 4 above). See the top of this doc and memory
  `orbital-floor-removed`. Rebuild with `compile.ps1` (game closed) and commit before starting.
- The same working tree holds other uncommitted work from earlier sessions (see `git status`);
  do not assume every modified file belongs to this design.

### Principles (the user's rule: every number grounded in real-world logic, no arbitrary caps)

1. **Orbit softens; boots finish.** Bombardment hits what it can see first; hardened positions
   survive and each pass finds fewer targets (Iwo Jima, the Somme). Returns diminish, with no
   floor and no cap; the commander stops when a day buys too little for its hulls and fuel.
2. **Bombardment takes time.** A bombardment is a day of sorties, never an instant. One per day,
   fleet-wide, for the player and the AI alike.
3. **Fuel is ordnance.** A fleet can only drop what it carries; tankers compete with warships
   and transports for the fleet's size. An AI expedition's provisions set how long it can bomb.
4. **Marines reach the guns only by landing.** No raiding party lands under intact batteries
   (Merville, Pointe du Hoc were parts of landings). The ground front is the marine route.
5. **Saturation razes a colony.** Only saturation can destroy a human colony from orbit, and it
   is priced on what it destroys. A hive it presses and never razes (2026-10-01, section 4).
6. **Return fire is set by the guns.** A battery fires at its own rate until silenced, so a
   bigger fleet that silences it sooner pays less (Lanchester, counter-battery).
7. **No decline.** A human colony dies only two ways: a siege won on the ground, or saturation
   to destruction; a hive only the first (2026-10-01). The user rejected a Core-driven decline again on 2026-09-28 (it was first removed
   2026-09-04, "no arbitrary timer").

### 1. Tactical bombardment (player) = the AI's hovering slice, one day at a time

- One bombardment = **one day of the siege slice** (`ThreatGroundFronts.siegeSlice`). The
  15-days-for-3 multiplier goes: retire `tacBombardSuppressDays` (15) and
  `siegeBombardSliceDays` (3).
- **Suppression per day = rate x F/(F+D) x condition**, rate 12 colony / 30 hive
  (`siegeSuppressDaysPerDay`, `hiveSiegeSuppressDaysPerDay`), F = fleet points, D = the defence
  figure now, condition = what still stands (`ThreatSiegeMalus.condition`). The x condition
  factor is new: condition decays exponentially, never quite to 0 from orbit.
- **Once a day, fleet-wide**, locked like vanilla's raid (`$raid_cooldown`, `raidCooldownDays`
  1): "Your forces will be able to organize another bombardment within a day or so." Tactical
  and saturation share the lock. The AI hovering slice already runs once a day; it must follow
  the same x condition rule.
- **Return fire = 0.008 x the defence the guns add, per day**, where "the guns add" =
  D x (1 - 1/guns multiplier) (`batteryShare`; order-independent, cannot double-count since
  Heavy Batteries replace Ground Defenses). No Ground Defenses / Heavy Batteries = no loss.
  Replaces `siegeBatteryAttritionPerDay`'s 0.02 x F x D x share/(F+D). Calibrated so a 600 FP
  fleet over size-6 Heavy Batteries loses what it does today.
- **Fuel = 0.04 per FP per bombardment day**, from the fleet's cargo (AI: its provisions). No
  fuel, no bombardment. Replaces vanilla's fuel = D for our slice.
- **Unrest raised to 10 x (1 - condition), never stacked** (replaces vanilla's -1 per
  bombardment, `bombardTacticalStability`, which stacked to -10 in ten days).
- **Show the ships.** The confirm prompt and the Support / Defend tooltips name the ships the
  day's return fire would take - smallest first, flagship spared, banked partial damage shown as
  the next ship lost - not a bare FP figure.

Computed, stability 10 at start (unrest lowers D as condition falls). Days, FP lost, fuel, and D
left, to condition 50% / 25%:

| Colony (D intact) | 300 FP | 600 FP | 1,000 FP |
| --- | --- | --- | --- |
| Size 5 GD (400) | 20 d, 20 FP, 240 f, D 185 / 35 d, 25 FP, 420 f, D 108 | 15 d, 15 FP, 360 f / 28 d, 19 FP, 672 f | 13 d, 13 FP, 520 f / 25 d, 17 FP, 1,000 f |
| Size 6 HB (900) | 31 d, 93 FP, 372 f, D 371 / 51 d, 112 FP, 612 f, D 192 | 21 d, 63 FP, 504 f / 36 d, 76 FP, 864 f | 17 d, 50 FP, 680 f / 30 d, 62 FP, 1,200 f |
| Size 8 HB (1,500) | 45 d, 226 FP, 540 f, D 616 / 71 d, 267 FP, 852 f, D 322 | 28 d, 139 FP, 672 f / 46 d, 167 FP, 1,104 f | 21 d, 104 FP, 840 f / 36 d, 127 FP, 1,440 f |

### 2. No raids on military structures

- Retire `ThreatFortificationRaids.FortificationRaid`: human Ground Defenses, Heavy Batteries,
  Patrol HQ, Military Base and High Command go back to vanilla's unraidable. Retire the knobs
  `fortificationRaidDanger`, `fortificationRaidDepthLoss`. The suspected `depthMult` bug
  (asymmetry 5) goes with it.
- Hives: tag the Swarm Nexus, Ground Defenses and Heavy Batteries and the planetary shield (the
  mod owns that row) unraidable. The **Fabrication Core raid stays** (`OrganRaid`,
  `coreRaidDepthWeight`) - it is not a defence.
- Vanilla item raids (e.g. a Pristine Nanoforge) are untouched: priced on D, so bombardment
  softening still helps a heist (see the Kazeron section).

### 3. Ground fronts wear the guns by advantage

Today a HOLDING front wears at a flat net +1 d/day (`frontSuppressDaysPerDay` 2, less repair)
whatever its size. New: **gross 12 x E/(E+D) days per day, less the defender's 1 d/day of
repair, NOT scaled by condition** (boots clear the hardened points orbit cannot). E = effective
troops. Break-even at E = 0.09 x D, about where GRINDING starts today.

| Troops (effective) vs defence | Size 6 HB (900): today | new | after 75% bombardment: today | new |
| --- | --- | --- | --- | --- |
| 0.17 x D (153) | 180 d | 135 d | 45 d | 19 d |
| 0.3 x D (270) | 180 d | 68 d | 45 d | 12 d |
| 0.5 x D (450) | 180 d | 45 d | 45 d | 9 d |
| 1 x D (900) | 180 d | 30 d | 45 d | 7 d |
| 2 x D (1,800) | 180 d | 23 d | 45 d | 6 d |

Same multiples, same days on any colony. Marine attrition unchanged.

### 4. Saturation bombardment - a campaign to destruction

Replaces vanilla's instant saturation on human colonies (today `bombardSaturation` passes
straight to super) and the mod's hive saturation (`threatSatConfirm`, 20 days on everything,
`hiveSatDisruptDays`, never reduces size).

**A hive has no bar** (2026-10-01, user's call; docs/ground-war.md "Saturation presses a hive"):
over a hive saturation is the lock, the structures, the growth pause and the unrest below, at
2.86 fuel per FP a day, flown to the commander's stop - each structure's clock closing on the
wear cap a day at a time, never a size off. The bar, its tables and combined arms are a human
colony's.

- **Once a day**, the shared bombardment lock. At least one day per bombardment.
- **On the structures**: the tactical slice on every building (not just military), with the
  same diminishing returns, return fire and x condition.
- **On the people**: a razing bar per size level, our own counter (see engine note). Each level
  lost is a size off (as vanilla's `reduceMarketSize`); the last level ends the colony (vanilla
  `DecivTracker.decivilize`). No size threshold: vanilla's `bombardSaturationDestroySize` 4 does
  not apply.
- **Fuel per level at size s = 69 x 10^(s/2)** - x3.2 per size, the built-up area, not the
  headcount (x10). Anchored on the user's figure: **destroying a size 4 costs 10,000 fuel and
  takes 7 days for a 500 FP fleet.**
- **Delivery = 2.86 fuel per FP per day** (10,000 / (7 x 500)), poured into the top enemy-held
  level; overflow carries into the next. Fuel spent = what is delivered, from the fleet's cargo
  (AI: provisions).
- **The dead stay dead**: no repair on the bar. The colony's own growth continues between
  visits, but **growth pauses while it is being saturated** (as it already does under a ground
  front, `updateColonyVitality`).
- **Unrest raised to 10**, never stacked (vanilla `bombardSaturationStability` 10 stacked).
  Stability does not change the fuel (it is priced on people); it lowers D, so after the first
  day the guns cost about a quarter of the ships.
- Reputation: vanilla's saturation impact and atrocity handling stay (`bombardNoAtrocity`,
  `waiveAtrocity` on Threat colonies as today).
- **Fallout landing block removed** (`falloutDays` 40, `ThreatGroundFronts.setFallout`), and
  saturation no longer destroys a front on the world (`ThreatGroundFronts.destroy` in
  `threatSatConfirm`).

| Size | Fuel for the top level | Fuel to destroy | 200 FP | 500 FP | 1,000 FP | 2,000 FP |
| --- | --- | --- | --- | --- | --- | --- |
| 2 | 691 | 909 | 2 d | 1 d | 1 d | 1 d |
| 3 | 2,184 | 3,094 | 6 d | 3 d | 2 d | 1 d |
| 4 | 6,907 | 10,000 | 18 d | 7 d | 4 d | 2 d |
| 5 | 21,840 | 31,840 | 56 d | 23 d | 12 d | 6 d |
| 6 | 69,070 | 100,910 | 177 d | 71 d | 36 d | 18 d |
| 7 (Kazeron) | 218,400 | 319,320 | 1.5 yrs | 224 d | 112 d | 56 d |
| 8 | 690,700 | 1,010,000 | 5 yrs | 2 yrs | 354 d | 177 d |

(Days to destroy.) A Prometheus holds 2,500 fuel: a size 4 is four tanker loads, a size 6 forty,
a size 8 four hundred. Fleets over one world add their FP; fuel, not time, is the brake.

**Combined arms: layers held count as size lost.** A human colony is one district per size, and
held layers already come off the defence figure. Saturation is priced on
**size - layers held**: marines take the expensive top layers, bombers finish the rest - or the
marines keep pushing if no fuel is coming. Bombs fall on the enemy's layers only. If the front
is thrown back, the price rises with it. A razed level is a size off, so it also lowers the base
defence the front's hold test reads - bombing and landing on one world reinforce each other.

| Size 6, layers held | Priced as | Fuel | Days at 500 FP |
| --- | --- | --- | --- |
| 0 | size 6 | 100,910 | 71 |
| 1 | size 5 | 31,840 | 23 |
| 2 | size 4 | 10,000 | 7 |
| 3 | size 3 | 3,094 | 3 |
| 4 | size 2 | 909 | 1 |

**Engine note:** vanilla tags Population & Infrastructure `unraidable, no_saturation_bombardment`
and it carries the colony's base defence, stability and demand; disrupting the real industry may
have side effects. Keep the razing bar as our own per-market counter (market memory or
`ThreatIncData`), shown on the Population & Infrastructure row.

### 5. Hives take bombardment unrest

Replace the flat +10 "Machine hive-order" stability (`ThreatColonyManager` about line 152) with
stability held at 10 minus bombardment unrest, so no other vanilla stability source reaches a
hive. Drop `SwarmNexus`'s cancel of the stability defence multiplier ("Machine hive-order (unrest
has no effect)"). Keep the population-demand suppression (`demandReductionFromOther`): that, not
the stability pin, is what stopped the old food-shortage penalties.

### 6. Planetary shield

The shield keeps soaking bombardment (`ThreatShield.soak`, `shieldAbsorbMax`, `shieldSoakMult`)
for tactical and saturation alike, and saturation wears it like any structure, under the same
diminishing returns. While it stands, the day's saturation delivery into a colony's razing bar is
cut by the share it absorbs. Raids and fronts ignore it, as today.

### 7. Diplomacy - a ground front is an act of war

- Finding: vanilla raids and bombardments of a non-hostile colony make its faction hostile (at
  best HOSTILE, -0.01 x size); a raid with the transponder off skips the hit; saturation sets the
  target to VENGEFUL and turns atrocity-minded factions in the system hostile. The mod keeps all
  of this (`bombardConfirm` calls super).
- Gap: landing a ground front (`ThreatincMarketCMD.groundDeploy` -> `ThreatGroundFronts.deploy`)
  needs no hostility and costs no reputation, so the player can invade a colony it is at peace
  with for free. Support and Defend fleets need hostility to bite; the front does not.
- **Fix: landing a front applies vanilla's bombardment impact** (`CustomRepImpact`, delta -0.01 x
  size, `ensureAtBest` HOSTILE), never covert. The covert heist stays the vanilla raid with the
  transponder off.

### 8. AI on the same rules

- NPC and Threat hovering fleets bombard by rules 1 and 4 exactly: once a day, the x condition
  slice, guns-set return fire, fuel from provisions (no fuel, no bombardment).
- **NPC raze task**: a bombing fleet with the fuel aboard, strong enough for the Defense Swarms,
  instead of a siege (`ThreatPurgeFGI`; `strikeSaturationEnabled` for the swarm's side, default
  false today). A colony is razed where that is cheaper than a siege; a hive, since 2026-10-01,
  is only saturated to the commander's stop, where its landing is beyond the marines held and it
  still produces - by a bombing squadron while the flotilla holds the orbit (docs/ground-war.md
  "Saturation presses a hive").
- `IncursionManager.siegeRaidStrNeeded` sizes landings against defences worn by the new rules.

### 9. The player's Bombard order (added 2026-09-28, the user's decision)

The player gets the AI's raze expedition as a war-board order, on the same numbers. A
**Bombard** button on a found hive's row sends a fleet sized for the orbit, with no marines and
the saturation's fuel from the player's stockpile. It fights the Defense Swarms for the orbit,
then saturates each hive a day at a time to the commander's stop (a hive has no bar since
2026-10-01: pressed, never razed), or until the fuel runs out and it comes home. The order only
saturates. Tactical bombardment stays inside Siege (it bombards before it lands) and Support /
Defend (over a front the player holds); the user chose raze-only over a tactical mode.

Built the same day (untested): the targets are the system's hives with a structure for
saturation to fall on and no front on them (combined arms stays with the menu, which pays danger
close); several hives are saturated in turn by one flotilla, sized to outlast every world's guns
(`IncursionManager.razeRun`); the fleets fit the base's free points like a Siege and, like a
Siege, are not sized against the Defense Swarms; the fuel - what each stay to the commander's
stop burns (`razingFuel`) - comes from the base alone (the player's sieges never pool), passage
first, and a reserve short of the whole sends what it has.

### The Kazeron heist, computed (the case that started this)

Kazeron: size 7, Heavy Batteries, Military Base, Star Fortress (x3 until beaten), stability 10
assumed. The Pristine Nanoforge raid is EXTREME (7 tokens), marines = about 2.33 x D; the guns
count through D (3,360 marines with them, 1,120 without) and do not also raise the item's danger
tier. After the station is beaten, D = 1,440:

| Route | Time | Fuel | Hulls | Marines |
| --- | --- | --- | --- | --- |
| Raid, no bombardment | 1 day | - | - | 3,360 |
| Vanilla tac bomb, then raid (old, for comparison) | 1 day | 1,440 | - | 863 |
| New tactical bombardment to 1,500 marines, 600 FP | 23 days | 552 | 115 FP | 1,454 |
| New tactical bombardment to 1,000 marines, 600 FP | 32 days | 768 | 134 FP | 998 |
| New tactical bombardment to 1,000 marines, 1,000 FP | 25 days | 1,000 | 102 FP | 977 |

Every bombardment day is an act of war and a month over the League's capital with its patrols;
the raid alone keeps the transponder-off option.

### Knobs (LunaLib + settings.json, per the expose-balance-as-knobs rule)

| Knob | Default | Replaces / note |
| --- | --- | --- |
| `bombardCooldownDays` | 1 | new; shared tactical/saturation lock |
| `bombardFuelPerFPDay` | 0.04 | new; tactical fuel |
| `bombardReturnFirePerGunDefence` | 0.0008 | replaces `siegeBatteryAttritionPerDay`; was 0.008 until run 4 |
| `bombardFPWorth` | 30 | new (run 4); defence a day must take off per fleet point lost (NPCs; the swarm uses `fabricateTroopsPerFP x mult / frontHoldFraction`) |
| `bombardUnrestMax` | 10 | new; tactical unrest = this x (1 - condition) |
| `satFuelPerFPDay` | 2.86 | new; saturation delivery - a colony's bar, a hive's day of wear |
| `satFuelSize4` | 10,000 | new; anchor for fuel per level (x3.2 per size); human colonies only |
| `frontWearRate` | 12 | replaces `frontSuppressDaysPerDay`'s flat rate |
| `npcRazeEnabled` | true | new (build); NPC raze task on/off (a hive saturated, never razed) |
| retire | - | `tacBombardSuppressDays`, `siegeBombardSliceDays`, `hiveSatDisruptDays`, `hiveTacDisruptDays` (check use), `falloutDays`, `fortificationRaidDanger`, `fortificationRaidDepthLoss` |

Knob names are proposals. `hiveBombardCostMult` goes too (fuel no longer derives from D).

### Where it lands in the code (verify before editing)

- `ThreatincMarketCMD`: `bombardTactical`, `bombardConfirm`, `bombardSaturation`,
  `threatSatConfirm`, `applySaturationDisruption`, `satCost`, `groundDeploy`; the ship-loss
  confirm text.
- `ThreatGroundFronts`: `siegeSlice`, `supportSlice`, `deploy`, `setFallout`, `destroy`,
  `madeUpDays`, the front's wear per day.
- `ThreatSiegeMalus.condition`, `ThreatShield.soak`, `ThreatFortificationRaids`,
  `ThreatColonyManager` (hive-order stability, `updateColonyVitality`), `SwarmNexus`,
  `ThreatPurgeFGI` / `ThreatStrikeFGI`, `IncursionManager.siegeRaidStrNeeded`,
  `ThreatFleetOrders` (Support / Defend tooltips).
- Board and dialog text follows CLAUDE.md: current values, one fact per line, mechanism here.
- Update `docs/ground-war.md`, `docs/war-board.md`, `docs/code-map.md` in the same change.

### Rejected along the way (do not rebuild)

- The 50% orbital floor (removed); any per-bombardment cap.
- v1: instant bombs, no daily limit, fuel as the only gate.
- Raids on military structures, and v1's raids-in-the-bombardment's-shape.
- Vanilla's instant saturation with destruction at size 4 or less.
- Saturation priced on the defence figure (1.5 x D).
- Fuel per person killed (x10 per size, 1 per 1,000 people): made small worlds free and big ones
  impossible; superseded by the size-4 anchor at x3.2.
- A Core-driven hive decline (dead-stays-dead campaign instead; see principle 7).
- A repair rate on the razing bar (a minimum fleet): the dead do not come back.

### Still to decide in the build

- The rates carrying the tables (rate 12/30, 0.008, 0.04, 2.86, x3.2) are first cuts - tune after
  a test run, not before.
- How the NPC decides raze vs siege (a cost comparison of fuel against marines and days).

### Built 2026-09-28 - what the build decided

- **"Orbit has done what it can"** (`ThreatGroundFronts.orbitSpent`): a day's bombardment now
  gains less than one day of the defenders' repair (1 d/day). It replaces the "fully worn" gate
  (`suppressedFully`), which diminishing returns never reach; no new knob. `orbitDone` also
  counts the fuel: less than half a day's ordnance aboard is done too. `bombardPlan` runs the
  days forward (shield and unrest included, capped at `siegeOrbitDays`) so a landing is sized on
  what orbit will leave.
- **Fuel.** The player's tactical day costs 0.04 x FP less vanilla's fleet bombardment
  capability (`FLEET_BOMBARD_COST_REDUCTION`). Saturation pours the least of 2.86 x FP, the fuel
  aboard and what the colony still needs, and never costs less than a tactical day (the buildings
  are bombed too). The player may pour every ton aboard.
- **Support and Defend** pay their ordnance from the fuel they carry (`ThreatReturns.MEM_FUEL`),
  then from their supply line's spendable reserve (`payOrdnance`; 2026-09-29, it was the home base
  at any range - fuel that never sailed). The supply line is `ThreatGroundFronts.ordnanceSources`:
  the home base when the fleet is in its home system; otherwise only markets whose stock reaches
  where the fleet stands (`IncursionManager.marketsReaching`, `ThreatConvoys.stockReachLY`), the
  home base first if it does, then the faction's others nearest first. Since 2026-10-01 an NPC
  market's fuel from another system pays its own passage to the fleet out of the load, 0.002 fuel
  a unit a light-year (`ThreatConvoys.haulRate` / `netOfHaul`; docs/strategy-layer.md "Logistics
  reach"). Player fleets use their home base alone (its reach is any range). Out of both, they hold the orbit
  without bombarding ("out of fuel to bombard with"). A Defend fleet with no fuel over a front
  that cannot hold fabricates troops, since an empty tank counts as orbit done (`orbitDoneFor`).
  The swarm pays from the hive's fuel stock at the same rates since 2026-10-01
  (`threatPaysOrdnance`, docs/hive-economy.md "Idle stock" part 4); before that it paid nothing.
- **One atrocity per saturation campaign** (`$threatinc_satAtrocity`, 30 days), not one a day: a
  70-day campaign counts once, as vanilla's one-shot did.
- **Story-critical worlds** stop at size 3 and are never destroyed, as vanilla's saturation
  spares them. Below size 3 the size comes off by vanilla's steps in `ThreatRazing.reduceSize`,
  since vanilla's `reduceMarketSize` refuses to go under 3.
- **Besieged human colonies** hold vanilla's `NO_DECIV_KEY` (expiring, with our own
  `$threatinc_decivHeld` flag so another mod's hold is never lifted): bombardment unrest can take
  stability to 0 without vanilla decivilizing the colony (principle 7).
- **Hive stability** is pinned at 10 minus recent unrest (`ThreatColonyManager.applyHiveOrder`),
  re-applied whenever bombardment raises the unrest.
- **The razing bar** is a colony condition (`threatinc_razed`, `ThreatRazedCondition`), not a
  line on the Population & Infrastructure row: that row's tooltip is vanilla's plugin. A hive has
  none since 2026-10-01; an older save's is cleared on the next pour (`ThreatRazing.clearBar`).
- **Danger close** (a front's own marines under the bombs) is per day now: 0.005 of the marines a
  day (was 0.05 a bombardment).
- **Fabrication price**: 0.008 x days x D x the intact batteries' share - what the return fire
  would have cost over the days of bombardment the fabrication replaces.
- **Core raid**: `fortificationRaidDepthLoss` is renamed `coreRaidDepthLoss` (the Core is the one
  raid left), and its pressure reads the player's raid strength (marines plus ground support,
  through planetary operations) instead of `MarketCMD.getRaidStr`, the AI's figure (asymmetry 5).
- **Retired knobs**: `tacBombardSuppressDays`, `siegeBombardSliceDays`, `hiveSatDisruptDays`,
  `hiveTacDisruptDays`, `hiveBombardCostMult`, `hiveTacCostFraction`, `falloutDays`,
  `fortificationRaidDanger`, `siegeBatteryAttritionPerDay`, `frontSuppressDaysPerDay` (now
  `frontWearRate`), `frontGrindSuppressMult` (a grinding front wears by its advantage too).
- **Nexerelin** runs human colonies' military menus, so with it loaded the player's bombardment
  days apply to hives only.

The AI half, and what reviewing it changed (same day):

- **The commander's stop.** `orbitSpent` first stopped only when a day gained less than a day
  of repair. Over a hive with Heavy Batteries that kept fleets bombarding while the guns sank
  them: a size-4 hive (D 7,200) fires 38 FP a day, so a 450 FP flotilla planned for 120 days
  lasted 6, landed a quarter of the marines it needed and was overrun. Now a day is flown only
  while it takes at least `bombardFPWorth` (30) defence off the world per fleet point the guns
  take (a fleet point of hull is ~6,000 credits armed and crewed, a marine 200; a defence point
  is a marine the landing no longer needs) and leaves the fleet above vanilla's abort line
  (0.33 of what it set out with). `bombardPlan` counts the fleet's losses day by day, and a
  live expedition lands rather than let the guns take it under the line (`gunsWouldBreak`).
  Human colonies are unchanged - the day's trade stays 4 to 7 in the fleet's favour there, and
  the tables above hold. Heavy-Battery hives: a siege bombards about 18 days to 47% and lands
  about 1,400 (size 2) to 4,200 (size 6) marines from 960-2,900 FP flotillas.
- **One day over one world.** An expedition's fleets over a world sliced separately, each taking
  the guns' whole day: N fleets paid N times, while splitting a fleet wore faster (asymmetry 3).
  Now they bombard as one - the structures wear at the rate their combined points earn
  (`orbitPoints`: the faction's armed fleets over the world) and the guns answer once, each
  fleet taking its share by points. NPC expeditions, Support, Defend and the swarm's strikes all
  do this; the player's own fleet in the menu takes the whole day.
- **The raze task.** An NPC razes a colony when razing's fuel (at 25) costs less than the
  landing's marines (200) and armaments (500), the pooled reserve holds the fuel over the
  passage, and a flotilla grown until it outlasts the guns (`razePlan`) finishes within
  `siegeOrbitDays`. Never a world another faction's front stands on, a story-critical one, or a
  razing that would leave the rest of a mixed siege short of fuel. A hive is not priced this way
  since 2026-10-01: it is saturated to the commander's stop only where its landing is beyond the
  marines held and it still produces (docs/ground-war.md "Saturation presses a hive").
  `npcRazeEnabled` switches the task off.
- **Ordnance.** A siege expedition draws its passage, its ordnance (ONE world since 2026-09-29 -
  the dearest of the non-front targets, not each world it lands on: the first landing unloads
  every marine and the marine need is sized to the strongest target, so later worlds are never
  bombarded; `bombardPlan` days x 0.04 x the flotilla's FP) and its razing fuel (still summed
  over the razed worlds) through the provisions
  gate; the passage is paid first, then the razing, and the rest is ordnance. Unburned fuel comes
  home with the refund. No ordnance left: no slice, and the landing gate opens.
- **Landing sizes.** A landing is sized on the wear of the flotilla that carries it, solved
  together with the landing - the least any siege of those worlds sails with - so the launch,
  the sizing, the convoys and the board read one figure. (2026-09-29: the need is read on the
  LANDING side of the bisection - `IncursionManager.needAndWear` returns {need at hi, hi}. No day
  is flown until it takes `bombardFPWorth` of defence off per fleet point the guns take, so the
  need is a step in fleet points: unworn below the first day's line, about a third of that above
  it. The low side sat just under the step and asked the unworn landing, 3x (12,882 against
  4,294 for a size-9 hive). `siegeWearFP` - the fleet points that wear the worlds to that need
  and carry it - is a minimum fleet goal in `siegeFleetSizes` and `siegeFleetGoal`, and the
  launch has a new postpone reason for it: "the bombardment needs" N FP, beside "orbit" and
  "razing".)
- **The swarm's saturation doctrine** (`strikeSaturationEnabled`, off) razes human colonies by the
  bar through `saturationSlice`; a pass over a world not yet razed does not use up vanilla's pass
  count.
- **Hive return fire - open.** The 0.008 was calibrated on a size-6 colony. A hive's figure is
  4-8 times a colony's, so its guns fire 19-58 FP a day (size 2-6 with Heavy Batteries) - about
  three times what the old attrition took from a 1,000 FP fleet, six times from a 500 FP one.
  With the commander's stop the AI copes (it lands big, saturating only beyond its marines), but
  large Heavy-Battery hives may be beyond most NPC sieges. A separate hive rate is the knob to add
  if a test run agrees.
- **Old saves.** Expeditions already out drew no ordnance: they bombard unpaid and raze nothing.

## Proposed model v1 - SUPERSEDED by the final spec above (brainstorm 2026-09-28, history only)

The user's direction: no arbitrary levers or caps - every limit is a cost the attacker chooses
to pay (fuel, hulls, marines, stability). The AI plays by exactly the player's rules.

### Decided

- **Orbital floor removed** (built, see top).
- **No per-day bombardment limit.** Fuel is the gate: a fleet may drop its whole load as soon
  as it likes. This makes dedicated bombing fleets that soften a world ahead of the siege force
  a real choice.
- **AI hovering = the player's bombardment.** A besieging AI fleet bombs by the same rules,
  paying the same toll and the same fuel from its own provisions (no fuel, no bomb). The
  continuous siege slice goes.
- **Tactical bombardment**, on military structures (GD, HB, Patrol HQ, Military Base, High
  Command):
  - days = 180 x fleet FP / D, i.e. a fleet whose FP equals the defence figure wears everything
    out in one bomb (K = 180). D is the bombard-facing figure, read before the bomb lands.
  - ships lost per bomb = 0.1 x the defence the guns add now, where "add" = D x (1 - 1/guns'
    multiplier) - the drop D would take if the guns vanished. This is independent of the order
    of multipliers and can't double-count (HB replaces GD). No GD/HB, no loss. Size-8 HB at
    stability 10 = 100 FP. Fleet size does not change it, so a big fleet finishing in one bomb
    pays one toll.
  - fuel = D per bomb (vanilla).
- **Saturation bombardment:**
  - days on EVERY building = 0.9 x fleet FP / colony size (200 FP per colony size = full
    effect), capped at 180. Not tied to defences. On heavy-battery worlds this matches a tac
    bomb day for day on the military structures.
  - stability penalty = 10 x days / 180, **raised to, not added** (a second sat bomb does not
    make -20).
  - fuel = 1.5 x D; the guns' toll as for a tac bomb.
- **Hive unrest (recommendation accepted):** replace the flat +10 "Machine hive-order"
  stability (`ThreatColonyManager` ~line 152) with stability held at exactly 10 minus
  bombardment unrest, so no other vanilla stability source reaches a hive. Drop
  `SwarmNexus`'s cancelling of the stability defence multiplier, so unrest cuts hive defence
  as it does a human colony's. Keep the population-demand suppression (`demandReductionFromOther`):
  that, not the stability pin, is what stopped the old food-shortage penalties. The "cheaper
  each pass" spiral the pin was stopping is now paid for in hulls by the guns' toll.

### Raids - direction, parameters open

Problem: without a depth toll a big raid puts 100+ days on the guns at once and sieges become
trivial; with the n^1.5 toll, a small force raiding one token at a time over a week beats a big
force, which makes no sense. Proposal: give raids the bombardment's shape, paid in marines.

- days on the raided fortification = 180 x marines / (R x D), capped at 180
- marines lost per raid = T x the defence the guns add now - one toll per raid, whatever its size
- replaces vanilla's token days and the depth toll on fortifications; vanilla's
  preparedness (+defence for 3 days after each raid) stays and also favours one big raid.

At R = 2, T = 0.5 (marines = 2 x D wears a structure out in one raid):

| Colony (D) | 500 marines | 1,000 | 2,500 | 5,000 |
| --- | --- | --- | --- | --- |
| Size 5 GD (400) | 2 raids, 138 lost | 1 raid, 100 | 1 raid, 100 | 1 raid, 100 |
| Size 6 HB (900) | spent at 41% worn | 2 raids, 433 | 1 raid, 300 | 1 raid, 300 |
| Size 8 HB (1,500) | spent at 17% | spent at 64% | 2 raids, 583 | 1 raid, 500 |
| Size 8 HB+HC (1,950) | spent at 13% | spent at 36% | 2 raids, 883 | 1 raid, 650 |

A big force is always better; instant 180 days is possible but paid in full. R and T to pick.

### Open

- Tac bomb stability: vanilla -1 per bomb, or proportional to days like saturation?
- Saturation: keep vanilla's -1 size and destruction at size 4 or below?
- Planetary shield: does it still turn aside part of each bomb (and each raid)?
- Which "can't siege" penalty to remove - the saturation fallout landing block (40 d) is the
  assumed one.
- Raid R and T.
- Ground fronts' wear rate (net +1 d/day) in the new world.
- Calculators used: `%TEMP%\p\proposal3.py` (tac), `sat.py`, `raid.py` - throwaway.
