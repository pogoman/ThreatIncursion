# Strata are the Threat - design review and spec (2026-10-25, nothing built, still designing)

The user, 2026-10-25: "i would like the strata to be the threat. so when a colony absorbs a fleet
it is absorbed into and increase the strata. comparitively if a colony needs a space fleet it
generates one and decrements its own strata. so worlds can increase and decrease in size from
this. it would need to be staggered and use same animation like how troops for sieges are
deployed from fleets. so not instantaneous. Concept of disruption and heavy batteries and all
military builds can completely go. the fabrication core remains as the sole building that creates
strata from nothing. and the fabrication core needs metals and fuel."

**The model in one line.** The Threat is one substance, strata. Underground it is a world's size
and its ground defence; in orbit, briefly, it is a fleet doing something; on an enemy world it is
a landed army. The Fabrication Core turns metals and fuel into strata; nothing else creates any,
and nothing is lost to time. Strata move between the ground and a fleet a ship at a time, on the
campaign map, both ways. Everything that reduces the Threat is a thing that takes strata: a
battle, a bombardment, a landing, a shortage.

Builds on the decisions of 2026-10-24 and 2026-10-25 (`facts.md` 803-805): the strata are the
hive's one figure, the strata are its troops, and ships are the soldiers of a strike. The
"SHIPS ARE THE SOLDIERS" build (jar 00:13, then the overrun-sized jar 08:39; being read in hw274 /
hw275 by another session this morning, uncommitted in the tree as of this review) should be
committed before phase A below starts; this spec assumes it.

**Decided so far (the user, 2026-10-25 morning, rounds 1 and 2):**

- The size table DOUBLES from 400 (section 5).
- A bombarded hive fires nothing back; it buds a fleet to fight (section 6).
- Build order A -> B -> C -> D (section 10); nothing built while the design is open.
- No upkeep, no decay: the anti-snowball is the Core's size-scaled demand for metals and fuel
  under accessibility - met in full grows, one unit short holds, two short starves (section 3).
- **Fleets do not hang in orbit.** A world buds a fleet when it is attacked (or to strike, shift,
  seed or scout) and reabsorbs it after; a budded fleet has no upkeep. There are no standing
  garrisons, no patrol-table floor (section 2).
- The hive is off supplies entirely: metals and fuel into the Core are its only inputs.
- At the cap, fed in surplus, nothing happens: a capped world sits. The hive's whole mass is
  bounded by its worlds' caps; it grows only by seeding.
- Accessibility on a hive world is not distance to a producer: it is **aggregate swarm density,
  like gravity** - the densest place has the highest accessibility; mass shifted to the front
  makes the front rich and the back poor (section 3).

**Decided round 3 (the user, 2026-10-25 midday):**

- Density self-weight 0: a world's own strata do not count toward its own density; its system's
  siblings count in full. The user's premise ("in-system imports have no accessibility
  modifier") is not vanilla's rule but is what the density makes true for a system of two or
  more hive worlds - checked in section 3; the lone-world consequence is open question 1.
- An NPC siege is sized against the buds the world can make over the siege's planned stay, not
  against its whole strata (section 6).
- The flow is proportional: 2% of the world's strata a day, at least 400 (section 2).
- No producer floor on shifting: the density feedback is the only brake (section 7).

**Decided round 4 (the user, 2026-10-25 afternoon):**

- The density calculation stands as written (section 3, "the calculation, step by step"); the
  lone seed that holds at size 3 is one with no hive mass within ~60 ly, accepted ("Fine").
- **No Spaceport on hive worlds** ("it no longer does anything for accessibility") - checked
  against the jars, section 4.
- Still designing: sections 2-3, 6, 7 and 8-9 are walked through in rounds 5-7.

**Decided rounds 5-7 (the user, 2026-10-25 afternoon):** growth 200 x size a month; a structure
750 strata; a seed lands as 800; starving a size per 90 / (u - 1) days (floor 15, an input at
zero always starves); a blockade cuts imports entirely; saves migrate; bombard fuel 2 a
stratum; a shifting donor keeps 800; a strike takes all above what the posture keeps; reactive
only - a world buds when bombarded, never against a landing.

**Decided round 8 (the user, 2026-10-25 evening):** one Bombard, no tactical, nothing instant -
1 stratum per FP a day for 2 fuel, blocked while a Threat fleet stands; a blockade triggers buds
at once, a marine drop does not; the buds pool and spawn whole, never a drip; Threat strata on a
human world consume its local production into growth; a conquered world grows into the planet's
size.

**Decided round 9 (the user, 2026-10-25 evening):** the bombard numbers stand; a landing's
covering force holding the orbit is a blockade; a doomed world spawns its pool short; the
player's Hunt, Bombard and Siege become ONE order, Attack, with Fuel, Marines and Fleet ladders
sized to what is staged, never to the target; the Scout button goes - "the player is the scout".

**Decided round 10 (the user, 2026-10-25 evening) - THE DESIGN IS CLOSED:** the Attack ladders
Fleet one / half / all, Fuel passage / half / all above the floor, Marines none / half / all
clipped to crew space; the force holds the orbit, fights what buds, razes every day it can,
lands on arrival, home when both are spent; Support and Defend off the hive rows; "Closed:
start phase A".

Nothing open. ALL FIVE PHASES BUILT 2026-10-25, untested: A (jar 13:22), B (13:54), C (14:16), D (14:47), E (14:55) - section 10 has each phase's BUILT block, judgement calls and verify list.

## 1. Review - most of this already exists, hidden

The hive already keeps a per-colony pool of fleet points that fleets are drawn from and credited
back to: the fabrication bank (`hive-economy.md` "Fabrication bank"). The redesign is, at its
core, that bank made visible, given a size, and moved a ship at a time instead of as a number -
and the standing garrisons it kept in orbit put back into the ground until needed.

| Today (hidden) | Under this design (the strata) |
| --- | --- |
| `fabBank` per colony, a number in `ThreatIncData` | `ThreatStrata` per world - one figure, already there |
| Income: forge SHIPS output x `fabFPPerShipUnit` 100 FP a month, banked by the Nexus | The Core's output: metals + fuel met in full -> strata a month, nothing at the cap |
| `maintainColonyGarrisons` builds a swarm from the bank in one poll and it orbits for good | The world BUDS a fleet when attacked: ships grown out of the strata one at a time at the flow rate, animated; reabsorbed when the attack ends |
| `fitNavyToSpare` / `recycleForUpkeep` scrap a swarm into the bank at `hullShare` 0.8 | The world ABSORBS a fleet: ships broken up into strata one at a time (today's `breakUpForHive`), animated, lossless |
| `settleLedger` credits a fleet home when it fades out of the sector | A fleet home lands as strata over its world |
| `garrisonUpkeepPerMonth` 0.04 of a fleet's FP charged to the bank; the navy pays supplies | Nothing. A budded fleet has no upkeep; strata do not decay |
| Size upkeep in supplies; growth clock; starving thins strata | The Core's three states on vanilla's deficit: grow / hold / starve a size per `starveDaysPerSize` |
| `redistributeByPressure` ships garrison swarms between worlds | SHIFT: bud at the back, fly, absorb at the front - the hive's one strategic lever |
| Accessibility: Spaceport, size, the Core-distance penalty refunded | One figure, "Swarm density" |
| `foundingFPPerStructure`, `ThreatBuildCost` supplies, founding kit | A structure costs strata; a seed is the strata the wave carried |
| Ground defence = strata standing x Nexus / Bastion / batteries / shield x condition | Ground defence = strata standing. No multipliers, no condition |
| Return fire from guns by their condition | None. The hive buds a fleet against the bombers or loses strata |
| Bombardment wears structures (disruption days); saturation takes strata per 300 disruption-days | Bombardment takes strata directly, a day at a time, for fuel; nothing to disrupt |
| `breakUpForHive` and `ThreatStrikeFGI.embark` break ships into troops at 5 a FP | Unchanged: the same exchange, the same animation, now the general case |
| Humans read Defense Swarm FP over a hive (`ThreatIntel.worldFP`) | Humans read the size, which is the strata, which is what the world can bud (section 6) |

What does not exist yet: budding (the reverse of break-up, with ships added to a fleet over days),
the Core as a producer with inputs and three states, the density accessibility, strata as the
price of structures and foundings, the reactive defence and the human sizing against it, and
the removal of five industries, the disruption clocks and the guns from the hive theatre.

## 2. The model

- **One figure a world:** `ThreatStrata` (market memory, as built). Size = the strata by the
  doubling table (section 5). Ground defence = strata standing (less a stratum per one a front
  holds). Troops = strata. Nothing else on a hive world carries military weight.
- **Fleets are strata in space, briefly.** A ship costs its FP x `strataPerFP` (5, today's
  `fabricateTroopsPerFP` - the user's 2026-10-03 number, tested in hw4p) to bud and gives the
  same back when absorbed. One exchange rate both ways, lossless; the cost is the time it takes
  and the strata that die while the fleet is out. A fleet exists to do one thing and is
  reabsorbed when it is done:
  - **Defend** - budded when an enemy force is seen over or bound for the world (the posture's
    rally as built, `attackFP`), up to the posture's want, at the flow rate; reabsorbed when no
    enemy stands within the contest radius for `defendLingerDays` (10).
  - **Strike** - budded at the staging system's worlds for the offensive, sails, lands as an
    army or comes home and is absorbed.
  - **Shift** - budded at one world, flown to another, absorbed there: how the hive moves mass
    toward the front or back to the producers (section 7).
  - **Seed** - a Seeding Swarm carrying a size of strata; absorbed into the new world.
  - **Scout / patrol** - the eyes, as built (`ThreatSwarmScouts`, `ThreatSwarmPatrols`); the
    only fleets that stand about, and small.
  No garrison swarms, no patrol table, no `minimumFP` floor, no `wantFP` standing over a quiet
  world. A quiet hive world has nothing over it.
- **The flow is staggered.** A world moves at most its flow a day each way, one ship a tick (the
  mod's half-day poll), with the campaign-map animation: absorbing, today's falling glows
  (`ThreatFabricationVisual`); budding, the same particles rising out of the surface to the
  fleet, label "Swarm budding" / "Strata rising". Flow (DECIDED round 3): `strataFlowFractionPerDay`
  (0.02) of the world's strata a day, at least `strataFlowPerDay` (400). Size 6 and under flow
  400 a day (80 FP): a Defense Swarm of 250 FP (1,250 strata) takes about three days either way,
  a 1,000-FP strike twelve; a size-7 world flows 512 (102 FP), a size-8 1,024 (205 FP) and buds
  that strike in five days. A world buds or absorbs, never both at once; absorb first when
  both are wanted. Nothing stands over the world while it buds; the fleet spawns whole when the pool is full
  (Budding, below).
- **Budding - the pool (DECIDED round 8).** The user: "we should see an animation but the fleet
  shouldn't spawn until it's ready, as I don't want them to drip feed tiny fleets to get
  annihilated." At the trigger (section 6) the posture fixes the want and checks only-if-enough;
  then the strata move off the ground into the world's budding pool at the flow rate, a day at a
  time, with the rising animation and nothing in orbit; when the pool holds the want the fleet
  is built by `ThreatFleetComposer` as today (vanilla sizes it, the archetype re-spends the FP)
  and spawned WHOLE over the world, and fights. Strata in the pool are neither on the ground (a
  bombardment cannot raze them, a landing does not meet them) nor in orbit. If the attacker
  leaves before the spawn, the pool drains back into the ground at the flow rate, animation
  reversed. A want the
  system cannot fill is not budded at all (only-if-enough). A pool spawns short only when the
  world is doomed (DECIDED round 9, the user: "if it is short and the world will fall anyway it
  might as well spawn fleet with whatever it has left"): when the strata left on the ground,
  falling at yesterday's rate to bombs and the landing, run out before the pool fills (ground /
  loss a day < (want - pool) / flow), the pool spawns at once as it stands, pool / 5 FP. A
  strike, a shift or a seed buds the same way, its want the order's size.
- **Absorbing.** A fleet over its own hive world is absorbed when its job is done (above), or
  when the world is under a landing and the posture chooses the ground (today's `breakUpForHive`,
  unchanged). Nothing is absorbed past the cap: the ships stay as a Defend fleet until another
  world with room is shifted to.
- **No decay, no upkeep.** Nothing thins because time passes - the user, round 2: "once
  fabricated they have no upkeep". A world thins only when its Core's inputs fall short
  (section 3). Replaces `garrisonUpkeepPerMonth`, the navy's supplies, the holds and
  `sortieStayDays` for Threat fleets.
- **The cap is the cap.** A size-8 world fed in surplus grows nothing (the user, round 2:
  "Nothing"). `cap` as built: the top row's strata plus a size's worth. The hive's whole mass is
  at most its worlds' caps; more Threat means more worlds, and a seed is strata spent.
- **Fuel for passage stays** (`ThreatFuel`, the user's 2026-09-30 rule): a fleet across
  hyperspace costs fuel from the hive's stock. Same-system moves are free. The stock is what the
  plants make above the hive's own demand, and the Cores' fuel demand is part of that demand,
  so growth and strikes compete for fuel through vanilla's own numbers.
- **A landing is strata on an enemy world.** As built on 2026-10-25: the strike's ships break up
  into troops at 5 a FP (`embark`). A landed army is nobody's: it bleeds by the attrition rule
  and is fed only by what lands. A front that wins a hive world is the new hive's strata
  (`digInAtConquest`: the army on the ground IS the strata, the fleet above is absorbed after).
  A front that wins a human world seeds a hive with the troops that stand.
- **Founding.** A Seeding Swarm is strata in flight: the wave buds at the source at `seedStrata`
  (400) plus the 400-strata (80 FP) swarm that carries it - it lands as 800, size 2, today's
  seed (DECIDED round 5) - pays passage, and on landing the whole fleet is absorbed into the new
  world: its starting size is what landed. No founding kit, no structure
  bill, no endowment: the Core and the Spaceport come with the hive. A wave shot down is strata
  lost.
- **Structures.** A hive builds Mining, Refining and Fuel Production, nothing military and no
  Spaceport (DECIDED round 4; what the Spaceport gated is in section 4). A build costs
  `structureStrata` (750, DECIDED round 5; five FP's worth of 150 - today's
  `foundingFPPerStructure` at the exchange) from the world's own strata, taken at the flow rate.
  Build time is vanilla's.

## 3. The Fabrication Core and the density

The user (round 1): "what if we made the requirements fuel and metals for each fabrication core
or demands rather scale with colony size so a size 8 needs like import source of 8 of each or
more depending on balance etc. That way if they spread too far too fast accessibility distance
penalty would mean the hive can't grow past certain sizes as import penalty from poor
accessibility would be restrictive right? And strata decays when there are shortages and then
drops size no shortage so then stabilises. In fact in that case if needs met exactly it doesn't
grow it needs surplus to grow." (Round 2): "I don't want accessibility to be tied to distance to
specific industry producer. I just want it to be aggregate proximity/density. Should be dependent
on strata concentration kinda like gravity. There should be a pull almost to the location with
the highest concentration of strata which has highest accessibility. However for example the
threat could convert it's backline strata to ships and then reabsorb them at the world's by the
front. So then accessibility at the front is high and worlds at the back with low swarm density
accessibility is low. So now there's a pro and con. Front like is strong but perhaps all the
producers at the back have now low size and accessibility so stop outputting etc. So it's
basically super dynamic."

The Core is the sole producer. A real industry as now (vanilla surface, the colony screen),
hive-only, not disruptable, not raidable (`unraidable`, skipped by every bombardment path the mod
runs on a hive). There is no disruption on a hive world; what hurts the Core is what starves it.

**What vanilla gives us** (`hive-economy.md`, verified in-game or by javap):

- Availability is a broadcast: a world sees the largest single in-faction source of each
  commodity, not the sum; demand elsewhere never subtracts. Refining makes metals at ITS size;
  Fuel Production fuel at ITS size minus 2. The biggest refinery world sets how many metals any
  hive world can see.
- Each world's imports are capped by its shipping capacity: `10 x accessibility + 5` units in
  faction (15 at 100%, 5 at 0%, 0 at -50%). A demand of 8 units needs 30% accessibility, 6
  units 10%; 5 units arrive at 0%.
- `getAvailable()` can exceed demand (one refinery making 5 feeds a world demanding 4 and shows
  5), so the deficit is a readable thing per input.
- **The Core-distance penalty cannot be removed, only overridden** (the user's round-2
  question). javap on the API jar: `accessibilityDistFromCOM` is read nowhere in the API's
  classes - `PopulationAndInfrastructure` applies only "No spaceport" and "Colony size", the
  Spaceport its own bonus; the distance line is applied by the obfuscated core when it
  recomputes the economy, and the setting is global (a mod `settings.json` override would turn
  it off for every faction). So the hive's accessibility is taken over wholesale: one mod line,
  "Swarm density", computed against the stat's current value so the TOTAL is our figure
  (`applyPortDisruption` already lands a target this way), re-asserted every poll. Vanilla's
  lines still show in the breakdown; the total is the density. `applyHiveAccessibility`'s refund
  and `coreDistanceOffset` go.

**The design:**

- **Demand** = the world's size in vanilla units: METALS at size, FUEL at size minus 2 -
  vanilla's own balanced table, so a refinery and a plant of the world's size meet it in full.
  `coreInputPerSize` (1) scales both. "A size 8 needs an import source of 8" is vanilla's numbers
  exactly. Fleets are not in the demand (they are transient and have no upkeep).
- **Three states, read from vanilla's own figures each poll:** met in full (available >=
  demand for both inputs) - the Core GROWS the world at `coreStrataPerSizeMonth` (200, DECIDED
  round 5) x size a month, to the cap and no further; short by one unit in all - HOLD, nothing
  grows, nothing thins; short by two or more - STARVE, **scaled to the deficit** (the user,
  round 5: "can we scale it to the amount of deficit"; the shape DECIDED round 6): with
  u the units short summed over metals and fuel, the world thins a size's worth per
  `starveDaysPerSize` (90) / (u - 1) days, never faster than a size per `starveMinDays` (15) -
  2 short a size per 90 d, 3 short 45 d, 4 short 30 d, 7 or more short 15 d; continuous (strata
  a day = the size step over those days). A world receiving NOTHING of an input it demands
  starves whatever the count (else a size-1 hive under total blockade would hold at 1 for ever;
  the user's 2026-10-24 rule is that it dies). The size follows the strata, so a starving world
  drops a size, its demand drops a unit or two with it, and it stabilises at the size its
  imports feed - the user's "drops size, no shortage, stabilises". Worked: a size-6 importer
  (12,800; 6 metals + 4 fuel) held to 2 units of each is 6 short -> a size per 18 d -> size 5
  (4 short, 30 d) -> size 4 (2 short, 90 d) -> size 3 (1 short) HOLDS, 138 days in. Cut to
  nothing it is a size per 15, 15, 18, 30, 90, 90 days -> dead in ~260 d; a seed (size 2) cut
  to nothing dies in 180 d, as today. One line on the colony screen: "Core: metals 7 of 8,
  fuel 6 of 6 - holding" / "Core: metals 2 of 6, fuel 2 of 4 - starving, a size in 18 d".
- **Why "met in full" grows and not "one unit over":** declared strictly - grow only when
  available exceeds demand - a world could grow only while some hive refinery is a size bigger
  than it, and the biggest refinery world could never grow at all (its own refinery makes
  exactly its size). Every world would freeze at the size of the largest producer. Read as "met
  in full = grow, one short = hold", a self-sufficient world grows with its own producers and an
  importer grows up to the size of the biggest producer it can reach, then holds. The HOLD band
  is one unit so the user's "met exactly = no growth" is a state the player sees on big
  importers.
- **Accessibility is swarm density** (the user's gravity; self-weight 0 DECIDED round 3). For
  hive world i: `density_i = sum over OTHER hive worlds j of S_j x w(d_ij)`, with
  `w(d) = 1 / (1 + (d / densityLY)^2)` (`densityLY` 10: a sibling in the same system counts in
  full, a world 10 ly off half, 20 ly a fifth, 30 ly a tenth); the world's own strata count
  `densitySelfWeight` (0, kept as a knob). Accessibility = `densityAccessPerDoubling` (0.10) x
  log2(density_i / `densityRef` 6,400), clamped to [`densityAccessMin` -0.30, `densityAccessMax`
  +0.50]. Nothing else moves a hive world's accessibility - not the Spaceport, not the size, not
  the Core-distance. One line in the tooltip: "Swarm density: +23%".
  - THE CALCULATION, STEP BY STEP (the user asked to see it, round 4). (1) For every other hive
    world j, take its strata S_j and the distance d in light-years between the two systems (0
    inside one system). (2) Weight it: w = 1 / (1 + (d / 10)^2) - same system 1.00, 5 ly 0.80,
    10 ly 0.50, 15 ly 0.31, 20 ly 0.20, 30 ly 0.10, 50 ly 0.04. (3) density = the sum of
    S_j x w. (4) accessibility = 10% x log2(density / 6,400), clamped: 800 -> -30%, 1,600 ->
    -20%, 3,200 -> -10%, 6,400 -> 0%, 12,800 -> +10%, 25,600 -> +20%, 51,200 -> +30%,
    102,400 -> +40%, 204,800 and up -> +50%. (5) Vanilla turns it into shipping: units = 10 x
    accessibility + 5, a commodity: -30% -> 2, -20% -> 3, -10% -> 4, 0% -> 5, +10% -> 6, +20% ->
    7, +30% -> 8, +40% -> 9, +50% -> 10. (6) The Core at size N wants N metals and N-2 fuel from
    imports, less what the world's own industries make; both met at the current size = grow.
    A NEW SEED (800) IN AN EMPTY SYSTEM, by what is near it: 5 ly from a system holding four
    size-6 worlds (51,200): 51,200 x 0.80 = 40,960 -> +27% -> 7 units -> reaches 8. 10 ly from
    it: 25,600 -> +20% -> 7 -> 8. 20 ly: 10,240 -> +7% -> 5 -> 6. 30 ly: 5,120 -> -3% -> 4 -> 5.
    50 ly: 1,969 -> -17% -> 3 -> 4. No hive within ~60 ly: ~0 -> -30% -> 2 -> 3. 5 ly from the
    home cluster at cap (204,800): 163,840 -> +47% -> 9 -> 8. So a seed beside the densest system
    is as accessible as the mass itself; the "caps at 3" case is a seed with no hive mass within
    ~60 ly of it, and the moment a sibling lands in its system the sibling counts in full.
  - THE USER'S PREMISE, CHECKED ("in-system imports have no accessibility modifier right"): not
    in vanilla. Every in-faction import, from the next planet or across the sector, is capped by
    the IMPORTER's shipping (10 x accessibility + 5 units, a commodity); vanilla knows no
    "same system". What reaches a world free of shipping is its OWN industries' output (a
    no-spaceport colony still eats its own Farming - confirm in the phase-A run). So the density
    is what makes the premise true: siblings in the system count in full, a system of two or
    more hive worlds lifts its own accessibility as it grows, and a pair or a cluster bootstraps
    to the cap on its own plants (section 5). The one world the rule bites is a LONE importer in
    a new system far from the mass: density 0, -30%, 2 units, size 3 and holding until a sibling
    lands in the system or it builds its own Refining (then fuel-bound at 5 until its own Fuel
    Production). Open question 1 is whether that is wanted.
  - The pull the user described falls out, system by system: mass shifted into a front system
    raises every front world's density by its SIBLINGS' gain (never its own); the back system,
    thinned, loses shipping, its importers starve a size per 90 days, its producers make less
    (Refining's output is its size) and the front's imports fall a step later - the pro and the
    con, with a 90-day lag the player can watch. A lone front world gains no accessibility from
    mass shifted into it: the pull is toward the dense SYSTEM, which is the user's "location with
    the highest concentration".
- **Machinery:** the Core keeps supplying HEAVY_MACHINERY = size unconditionally (Mining and
  Refining demand it; the chain stays bootstrapped - a Core short of metals still runs the mines
  that make them).
- **Blockade:** `ThreatBlockade.syncHive` already docks a hive world's accessibility while human
  warships hold its orbit; it becomes the one exception to "nothing else moves it": while a
  blockade holds, the density line lands the accessibility at -50% - shipping 0, imports cut
  entirely (DECIDED round 6) - every imported input arrives at zero, which is a famine
  (above), and the world starves by vanilla's own deficit, no `importCut` of ours. A world with
  its own producers holds under blockade; it must be bombed or beaten.
- **Fuel for growth and fuel for strikes** share the plants: `ThreatFuel.perMonth` banks what
  the hive makes above its own demand, and the Cores' demand is now in that figure, so a hive
  of big worlds banks less passage fuel. No separate claim - judgement call, section 12.

## 4. What goes, what stays

**Goes (the Threat side only - human colonies keep vanilla's structures and the siege wear the
user set on 2026-10-24):**

- Industries: Swarm Nexus, Swarm Bastion, Swarm Command, Ground Defenses, Heavy Batteries
  (`industries.csv` rows, `SwarmNexus`, `SwarmBastion`, `ThreatGroundDefenses`). The Nexus's
  ground-defence top-up to the strata standing moves to the Core; its patrol table
  (`ThreatHulls.applyPatrols`) goes with the garrisons.
- Standing garrisons: `maintainColonyGarrisons`, `desiredGarrison`, `garrisonReserve`,
  `garrisonAvailableForLaunch`, `musterFrom` (a strike buds, it does not muster),
  `growGarrisonFleet` as a garrison builder (kept as the per-ship add), `redistributeGarrisons`
  / `redistributeByPressure` (SHIFT replaces them), `ThreatGarrisonMuster` (no garrison to
  muster; a Defend fleet fights as one fleet), `minimumFP`, the fit floor, `fabricateFor`,
  `sendReinforcement` (a shift), `checkReinforcementArrivals` (an absorb), feeding swarms.
- Heavy Industry / Orbital Works on hive worlds and the home relics (`maintainHomeRelics`, the
  Pristine Nanoforge), the forge FP income (`forgeOutput`, `nexusDraw`, `fabFPPerShipUnit`), the
  supplies they made, the hive's supplies stock and its surplus rule (decided: off supplies).
- The fabrication bank: `fabBank`, `fpBank`, `drawFP`, `chargeFP`, `creditFP`, `canAffordFP`,
  `hivePoolableFP`, `poolSystemBanks`, `accrueFabrication`, `swarmCostEstimate` / `fabCosts`,
  the ledger binding (`bindToLedger`, `settleLedger`, `creditHome`, `LedgerReturn`), the
  endowments (`endowSave`, `endowSeed`), stillborn strikes' forfeit (a strike budding when its
  world is taken loses what it budded - the same outcome, no ledger).
- Upkeep in every form on the swarm: `fitNavyToSpare`, `fitFloor`, `fitActive`,
  `recycleForUpkeep`, `payNavySupplies`, `garrisonUpkeepPerMonth`, `standingUpkeepMult`,
  `ThreatUpkeep`'s hold and `sortieStayDays` for Threat fleets. Human fleets keep their holds.
- Size upkeep in supplies on hive worlds (`ThreatColonyUpkeep.feed`, `sustenanceDue`,
  `feedShare` by stance, `sustainShare`, `importCut`), `ThreatFeeding`'s hive side, the hive
  cover (`ThreatHiveCover`), `foundingBudget`, `ThreatBuildCost` on hives. The forward bases'
  feeding convoys (the human side) stay.
- Accessibility plumbing: `applyHiveAccessibility`, `coreDistanceOffset`, `applyPortDisruption`
  (nothing disrupts a hive port now) - one "Swarm density" line replaces them.
- **The Spaceport on hive worlds** (the user, round 4: "it no longer does anything for
  accessibility, assuming that doesn't break any vanilla mechanics"). Checked in the API and
  core jars (2026-10-25): vanilla reads `hasSpaceport` in `PopulationAndInfrastructure` (the
  -100% "No spaceport" line - our total overrides it), in the trade, patrol, pilgrim, academy,
  merc and disposable fleet managers and a bar event (hive markets sit in their own econ group
  and on `getMarketsWithoutTradeFleetSpawn`, so none of those spawn at a hive), in
  `RepairAvailable` (the player's dock, never at a hostile hive), in `Waystation`,
  `TradeCenter` and `FreeMarket` (not built on hives), in `CoreLifecyclePluginImpl` beside a
  station-tag check, in hub-mission search filters, and in the core only in `Market` itself and
  a market-info UI class. The Spaceport's own demands (fuel at size-2, supplies, ships), its
  crew supply, growth bonus and slipstream detection are the player's concerns; the Core takes
  over the fuel-demand slot (size-2, the same table). Nexerelin reads it in `NexUtilsMarket`,
  `ColonyManager`, `RebellionIntel` and `Nex_TransferMarket` - checked with Nexerelin loaded in
  phase D. Goes: `addEssentialStructure(SPACEPORT)` in `foundColony`, `ensureSpaceport` and its
  sweep, `portOf`, `applyPortDisruption`, `HiveVitalityCondition`'s port read,
  `ThreatBuildCost`'s Spaceport line, the war board's port row for hives; saves drop the port on
  load (section 9). A hive's port is nothing; its accessibility is the density.
- Disruption on hives: `disruptedDefenseResilience`, `defenseWearDays` on a hive, the hive
  theatre's `keyStructures` and `fortifications` (empty lists), `frontWearRate` on a hive,
  `ThreatSiegeMalus` on a hive (never installed there), the shield on a hive.
- Guns: `hiveBombardReturnFirePerGunDefence`, `hiveReturnFireWorth`, `groundDefensesBonus`,
  `heavyBatteriesBonus`, `nexusDefenseBonus`, `defenseDeficitWeight` on a hive, bombardment
  unrest on a hive (`suppression-balance.md` principle 5 - no stability on a hive).
- Raids on hives: `ThreatFortificationRaids` (the Core raid), `coreRaidDepthLoss`,
  `fortificationRaidDanger`; the raid menu on a hive reads "nothing to raid".
- The Bastion / Command military tier everywhere it is read (`SwarmBastion.tier`, the council's
  Picture reads "tiers" of hives - it reads sizes instead).
- On the human side, what read standing swarms: swarm bounties posted against a hive's Defense
  Swarms (`ThreatSwarmBountyIntel`, the base-outweighed kind), `ThreatSoftening`'s hunts of
  Defense Swarms (there are none to hunt; a hunt becomes a raid-in-force that makes the world
  bud and kills the buds), `ThreatIntel.worldFP` as the orbit figure (section 6).

**Stays:**

- The fog of war both ways, reports, scouts, patrols, information by ship.
- The posture (`ThreatPosture`: pressure, rally, the system defending as one) as the decider of
  WHEN to bud and how much; the stance; the offensive and the staged strike (`ThreatOffensive`,
  `stagedPlan`, the fund) - every FP figure they hold becomes strata / 5; the fund is the strata
  the worlds in reach can spare above what they keep (section 7).
- Strikes, the daily siege, `embark`, fronts, strata held by a front, the attrition rule, the
  line, relief, break-up over an own hive, conquest.
- Saturation and the player's Bombard order, re-expressed without disruption (section 6).
- Blockade (`ThreatBlockade.syncHive` -> accessibility -> the Core's imports).
- Passage fuel, the fuel stock, fuel plants; `colonyMaxSize` 8 as the cap on strata.
- The human side whole: hull pool, depots, convoys, council, forward bases, marines, their
  structures and disruption.

## 5. The arithmetic (DECIDED: doubling from 400, nothing at the cap, no upkeep)

With one figure, how fast a world grows, how big a fleet a world can bud, and what a landing
on it costs are the same number read three ways. The size table sets all three.

Core income 200 strata a size a month (DECIDED round 5; today's FP income x 5); exchange 5 strata a FP; a
Defense Swarm 250 FP = 1,250 strata; a 1,000-FP strike = 5,000.

| Size | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Strata | 400 | 800 | 1,600 | 3,200 | 6,400 | 12,800 | 25,600 | 51,200 |
| As a fleet (FP) | 80 | 160 | 320 | 640 | 1,280 | 2,560 | 5,120 | 10,240 |
| Months to the next size, met in full | 2 | 2 | 2.7 | 4 | 6.4 | 10.7 | 18.3 | - |
| Core demand: metals / fuel (units) | 1 / 0 | 2 / 0 | 3 / 1 | 4 / 2 | 5 / 3 | 6 / 4 | 7 / 5 | 8 / 6 |
| Accessibility the demand needs | any | any | any | any | 0% | 10% | 20% | 30% |

Seed to cap ~46 months (today ~56). The seed stays 800, today's figure, and today's Min landing
of ~800 marines takes it; a fortress is 51k troops nobody lands on - it is bombed a stratum at a
time, starved by density or blockade, or its buds are beaten in orbit. The hive's whole mass is at
most 51,200 (+ a size's worth) a world: ~100 worlds at cap would be 5M strata = 1M FP of
potential fleet, against today's 12-48k FP of standing fleets - but nothing stands; what the
humans meet is what the posture buds, at the flow rate, and every strata budded and lost is gone
from a world that regrows at 200 x size a month. Today's hive-wide income is ~5k FP a month; the
new hive at ~100 worlds averaging size 5 makes ~100k strata = 20k FP a month, and spends it only
by budding - growth at the cap is nothing.

**Density, worked** (self-weight 0, `densityLY` 10, ref 6,400, 10% a doubling, clamp -30% /
+50%; units = 10 x accessibility + 5, a commodity; a size-N world imports N metals and N-2 fuel
less what its own industries make; a world grows when its demand at its CURRENT size is met):

| Case | Density (others' strata, weighted) | Access | Units | Reaches |
| --- | --- | --- | --- | --- |
| A lone importer in a new system, no hive within ~60 ly | 0 | -30% | 2 | size 3, holds |
| The same with its own Refining (metals free, fuel imported) | 0 | -30% | 2 | 5, holds; 8 once it has Fuel Production too |
| A pair in one system, both size N (sibling S_N) | 800 / 1,600 / 3,200 / 6,400 / 12,800 / 25,600 | -30 / -20 / -10 / 0 / +10 / +20% | 2 / 3 / 4 / 5 / 6 / 7 = N exactly | 8, met at every step |
| The home cluster, four in one system | 3 x S_N: 2,400 at size 2 | -14% | 3 | 8, a unit ahead at every step |
| A seed 10 ly from one fortress (51,200 x 0.5) | 25,600 | +20% | 7 | 8 |
| A seed 20 ly from one fortress (x 0.2) | 10,240 | +7% | 5 | 6 |
| A seed 30 ly from one fortress (x 0.1) | 5,120 | -3% | 4 | 5 |
| A seed 50 ly from one fortress (x 0.04) | 1,969 | -17% | 3 | 4 |
| A fortress whose neighbours are all shifted away | 0 | -30% | 2 | holds at 8 on its own Refining + Fuel Production; as an importer starves a size per 90 d to 3 |

So "spread too far too fast" is a seed 30 ly from the mass stopping at size 5 and a lone seed
in an empty system stopping at 3; "the back thins" is an importer fortress left alone starving
to 3 while a self-sufficient one holds. Rounding of the units (floor or nearest) is vanilla's
and is checked in phase A; the pair's exact fit at every step rides on it (`densityRef` 3,200
gives the pair a unit to spare and lifts every reach a size).

## 6. Fighting a hive

Every path takes strata; no path wears anything. A quiet hive has nothing over it.

- **Arrival - reactive only (DECIDED rounds 7-8).** The user: "it does nothing unless bombarded,
  i.e. if the fleet is simply dropping off marines and doesn't attack it shouldn't spawn fleets
  to attack, just focuses on ground battle"; then: "ah forgot about the blockade. yes then they
  just instantly react to a blockade." Two triggers, both at once: the first bomb, and a
  blockade - hostile warships holding station within `ORBIT_HOLD_RANGE` on the ground-front poll
  (`ThreatBlockade` as built, with no defenders left to weigh against: any hostile weight holding
  the orbit is a blockade, a landing's covering force included - DECIDED round 9). A fleet that lands marines and does not hold station
  triggers nothing: the ground war is the answer. On a trigger the world - and its system's
  siblings, the rally as built on these triggers alone - buds up to the want (the attacker's FP
  at the rally margin), only if the system's buddable strata can match it (today's
  only-if-enough rule), into the pool at the flow rate (section 2: 80 FP a day from a size-6
  world or smaller, 102 from a size 7, 205 from a fortress), and the fleet spawns whole when the
  pool is full - a 1,200-FP answer to a 1,000-FP bomber takes a fortress six days; a seed cannot
  make one and is razed. Short of enough, the world keeps the ground and the posture shifts
  mass toward it if it can. Every bud killed is strata the world has lost. The Defend fleet
  lingers `defendLingerDays` after the last bomb or the blockade's lifting and is reabsorbed.
- **How the humans size against it** (DECIDED round 3, revised round 8 for the pool). Today the
  orbit gate and the siege flotilla are sized to Defense Swarm FP seen x `npcSiegeOrbitMargin`;
  nothing stands now. The report (`ThreatIntel`) carries the size seen, which is the strata by
  the table, which is what the world and its system can bud. A force F that will hold the orbit
  - to bomb or to cover a landing - expects one Defend fleet of `min(system buddable strata / 5,
  F x rally margin)` FP, spawning `want x 5 / flow` days after it arrives (a fortress's 1,200-FP
  answer in six days, a lone size-5 world's 1,280 FP in sixteen), and sails when F x
  `npcSiegeOrbitMargin` beats it: against a small system it sails to beat the whole system;
  against a fortress system, whose answer mirrors its own strength at the rally margin, it
  sails only with the margin to beat its mirror - or it raids, in and out inside the spawn
  delay, razing F x days strata for 2 fuel each and gone before the pool fills. The council's
  Picture reads sizes already and is untouched. The player sizes by hand: the Attack order
  (section 8) quotes the system's answer and sails what its ladders say.
- **Bombardment (DECIDED round 8: one Bombard, no tactical, nothing instant).** The user:
  "forgot no instant bombing. maybe we just get rid of tac bombing, just have sat bomb scaled to
  size of the fleet is how much they can do a day. so 1000 FP fleet could sat bomb 1000 a day,
  meaning size 8 full colony takes 50 days. 300 FP fleet would take 150 days, but more
  importantly it would also require 100k fuel." A day of bombardment by F fleet points razes
  `bombardStrataPerFPDay` x F strata (1.0) for `bombardFuelPerStratum` (2) fuel each: 1,000 FP
  razes a fortress (51,200) in 51 days, 300 FP in 171, either for 102k fuel; a seed (800) falls
  to 200 FP in four days for 1,600; a 1,400-FP NPC flotilla erases a size-5 hive (6,400) in five
  days for 12.8k. One path for everyone - the player's Bombard order on the board, the dialog's
  bombardment for the player's own fleet (begun and ceased there, ticking each day the fleet
  holds the orbit; no instant bomb, no tactical), the NPC razing plan - all quoting "N strata a
  day for 2N fuel, the goal in D days"; the goal is a size down or nothing left (the Force
  ladder's Min / Max). It is BLOCKED while a hostile fleet stands over the world, as vanilla
  blocks it: the hive's spawned fleet must be beaten before the bombs resume, so a bombardment
  is bomb, fight the spawn, bomb again - the fuel aboard first, then the base's reserve, as
  built. **No return fire (decided round 1).** No unrest, no stability, no condition. **Are the
  numbers reasonable (the user's question)?** Yes, with three notes: (1) at 1 a FP-day a fortress's
  own flow (2% a day, 1,024) is of the order of a bomber its own size, so a fed fortress holds a
  ~1,000-FP bomber to a standstill through its spawns and loses to 3,000; (2) the fuel is
  today's top end (100k-1M for a big hive, now 102k flat) but the time is a tenth of today's -
  an NPC flotilla razes a small hive in a week for 3-13k fuel, so the council's "raze where
  cheaper than landing" will raze far more often than today, every razing a fleet battle
  against the spawn; `bombardStrataPerFPDay` is the knob if razing swamps landing; (3) the pool
  makes hit-and-run worth it: a bomber gone inside the spawn delay pays only fuel.
- **Landing.** Unchanged: the front fights the strata standing, holds strata, bleeds them for
  good by the attrition rule, wins when it holds all that is left. Sizing (`siegeTierMarines`,
  `raidStrNeededAt`, `nexusAnchoredDefense`) reads the strata standing with no multiplier and no
  "worn" figure. The marine headroom for the orbit reads the buds expected (above).
- **Blockade.** Cuts the world's imports entirely (DECIDED round 6): while hostile warships hold
  the orbit (`ThreatBlockade`, as built) the world's shipping is 0 - the density line lands its
  accessibility at -50% for the duration. Every input it imports arrives at zero, a famine
  (section 3): it starves at the deficit's pace and dies at the last stratum (`collapse`, as
  built) - a seed in 180 days, a size-6 importer in ~260. A world with its own Refining and
  Fuel Production holds under blockade and must be bombed or beaten.
- **Raids.** None on a hive (decided): the menu says "nothing to raid".
- **Dying.** As built: strata at 0 and the hive is gone, by a front's victory, by bombardment
  or by hunger.

## 7. The swarm's decisions on strata

Every place the swarm reads "FP it can spend" reads strata / 5 of what the worlds can spare;
every place it reads "FP it holds" reads the strata. The deciders stay; what they order changes
from "move / build a fleet" to "bud / shift / absorb".

- **Posture** (`ThreatPosture`): pressure as built; `wantFP` becomes what to bud when BOMBARDED or
  BLOCKADED (the attacker's FP x the rally margin, at most the system's buddable strata; nothing
  for a fleet that lands marines without holding the orbit - reactive only, section 6); nothing stands over a quiet world. The system defends
  as one: a bombarded or blockaded world's siblings bud and fly (today's rally), and reabsorb at home after.
- **Shift** - the hive's one lever (the user's "convert its backline strata to ships and reabsorb
  them at the worlds by the front"). The pressure pass that moved garrison swarms
  (`redistributeByPressure`) moves strata: a world under pressure or near the humans is a
  receiver, a quiet world a donor, the amount the receiver's want less what it has; the donor
  buds, the fleet flies, the receiver absorbs (to its cap). A donor keeps `shiftFloorStrata`
  (800, DECIDED round 7: a seed's worth - a world is never shifted below size 2 by choice;
  budding for its own defence may still spend it all). The stance leans it: PRESS shifts
  forward, CONSOLIDATE shifts back toward the densest system. No producer floor (DECIDED round
  3) - the density feedback is the brake: a back line shifted away starves its importers a size
  per 90 days, its producers shrink with their worlds, and the front's imports fall with them.
  With self-weight 0 a shift lifts the receiver's SIBLINGS' accessibility, not its own, so the
  pass shifts into systems, not worlds: the receiver is the front system's worlds in proportion
  to their room.
- **Offensive** (`ThreatOffensive`): the fund is the strata the worlds in reach of the staging
  system can spare - all above what the posture keeps, the rally want against forces seen
  nearby plus the 800 floor (DECIDED round 7: a quiet fortress can send 10,000 FP) - read as
  FP; a prong
  buds at the staging system's worlds in proportion to their spare and gathers at the staging
  world (same-system sailing is free); the preparation time is the budding time. `strikeCapFP`
  and the launch hold as built. A strike that comes home is absorbed; a strike that lands is
  strata on the enemy's ground.
- **Stance** (`ThreatStance`): feed shares go (nothing is fed by the stance); the stance still
  sets the strike stock, the spread's lean and the shift's direction.
- **Spread** (`trySpread`, `launchColonizationWave`): a wave is budded at `seedStrata` plus its
  carrier where a world has the spare and the stock the passage; lands as the seed's strata.
  The planner (`planHiveEconomy`) reads the density: a seed is worth landing where the density
  will feed it, and a producer is worth building where the density is - "build near the mass".
- **Scouts, patrols, raiders:** budded where sent from, absorbed where they end.
- **Break-up over an own hive** (`breakUpForHive`): is "absorb under fire" - the same code.
- **Hull pool** (`ThreatHulls`): a hive's standing hulls = its strata / 5; the humans' pool is
  untouched.

## 8. Player-facing (KISS)

- **Colony screen of a hive:** Population, Fabrication Core and the resource industries (no
  Spaceport, round 4). The Core's tooltip: "Strata: 1,150 (size 3 at 1,600). Growing 480 a month.
  Core: metals 3 of 3, fuel 1 of 1. Swarm density: +23%." One line per fact; "holding" or
  "starving, a size in N days" in place of the growth line when it is.
- **War board, hive rows:** the strata figure beside the size, the density, and while a flow
  runs "budding 1,250 (2 d)" / "absorbing 640 (1 d)" / "shifting to X". No Defense Swarm count -
  there are none standing; a Defend fleet budded shows in the fleets table like any fleet.
  Fronts table's Strata column as built.
- **One order on a hive row: Attack (DECIDED rounds 9-10).** The user: "get rid of player
  actions hunt bombard and siege and replace them with one action called attack. with min med
  and max for fuel and min med max for marines and min med max for Fleet size. they are sized
  against what is available at staging, not at what the target needs. no scout button either
  the player is the scout". Hunt, Bombard, Siege and Scout go; the outcome-sized tiers of
  2026-10-24 go with them. **Attack** sails one task force from the nearest own colony with
  hulls free (the auto-picked source as built, `pickTaskForceSource`) carrying what three
  ladders above the hives table say, each a share of THAT colony's stock and nothing of the
  target's:
  - **Fleet:** one fleet / half the hulls free / every hull free (the Amount ladder's shares as
    built, `forceFP`).
  - **Fuel:** the passage only / half the fuel above the colony's floor / all of it. Every 2 fuel
    above the passage is a stratum razed.
  - **Marines:** none / half the pool free / all of it (`attackMedShare` 0.5), clipped to the
    fleet's crew space and quoted so.
  Min is the least that still sails, so one order is a blockade, a bombardment, a landing or
  all three. At the target the force holds the orbit - a blockade, so the world buds (section
  6) - and fights what spawns. Each day it holds the orbit with no Threat fleet standing it
  razes 1 stratum per FP for 2 fuel, down to the passage home, and lands its marines on arrival
  under the same gate (the landing gate as built). It stays while it has fuel to bomb or marines
  on the ground, then sails home; a force with neither is a blockade and stays its supplies
  hold (`sortieStayDays`) or until Recalled. Nothing is refused for the target's sake: the
  button is off only when the colony cannot field one fleet or pay the passage (`disableWith`,
  the figures). Support and Defend leave the hive rows - an Attack at Fuel Min and Marines Min
  is the cover they gave - and stay on the colonies table for own and allied worlds, sized by
  the Amount ladder as built (DECIDED round 10). A hive row then holds Attack, Push / Dig in
  and Pull out on the front row, and Recall per fleet, as built.
- **Attack prompt:** one line per fact, figures as "N to <what it buys>" (the 2026-10-24
  pattern): "From Haven: 1,200 fleet points in 4 fleets" / "Fuel 9,600: 4 days of bombardment,
  4,800 strata - a size down from 6,400" / "Marines 2,400 against 6,400 strata on the ground" /
  "Beta Prisma can bud 1,280 fleet points against it in 16 days" / "Passage 1,100 fuel, 24
  days". The player sizes by hand; the board says what is true.
- **Scout:** no button - "the player is the scout". Your fleet entering a system finds its hives
  and a colony of yours beside one reveals it (`revealNeighbours`, as built); every faction's
  finds are shared as built; your colonies' own patrol sweeps stand as any faction's (assumed,
  section 11).
- **Notices:** as built ("Stratum Razed", "Stratum Bled", "Hive Starving", "Hive Razed", the
  layer notices). New: none - budding, shifting and absorbing are visible on the map and the
  board, not announced (the 2026-09-27 no-progress rule).
- **Campaign map:** the falling glows for absorbing (as built), the rising glows for budding.
  Drawn only for a player in the system.

## 9. Saves and migration

- DECIDED round 6: saves migrate. A hive market on load: remove the five military industries and the Spaceport or Megaport
  (`removeIndustry`); add the Core where missing. The bank's balance x 5 goes into the world's strata up to the cap. Standing
  garrison fleets become Defend fleets of their world and are absorbed at the flow rate, to the
  cap; what does not fit is shifted to worlds with room by the posture's pass, and what fits
  nowhere stands until spent. `fabCosts`, the ledger keys and the endowment flags are dropped.
  Fleets bound to a ledger become their home world's (absorbed when they land).
- Human colonies: untouched. Forward bases: untouched.
- Knobs removed from `settings.json` / `LunaSettings.csv` (Luna migration version bump, the
  user's 2026-10-24 pattern): `fabFPPerShipUnit`, `foundingFPPerStructure`,
  `garrisonUpkeepPerMonth`, `standingUpkeepMult` (swarm side), `nexusDefenseBonus`,
  `groundDefensesBonus`, `heavyBatteriesBonus`, `hiveBombardReturnFirePerGunDefence`,
  `hiveReturnFireWorth`, `strataSuppressDaysPerSize`, `coreRaidDepthLoss`, `navyFitsSpare`,
  `hiveBreakUpEnabled` (always on - it is absorbing), `fabricateDefendEnabled`,
  `fabricateHoldMargin`, `fabricateSupplyDays` (swarm side), `disruptedPortShipping`,
  `coreDistanceOffset`, `sortieStayDays` (swarm side), `sizeUpkeep*`, `feedShare*`,
  `feedConvoys` (hive side), `hiveSurplusMult`, `foundingBudget`, the garrison size table and
  `garrisonRespawn*` remnants; the player orders' `bombardTierMinDays`, `bombardTierMedDays`,
  `playerScoutHome`, `playerScoutCost` (Hunt, Bombard, Siege and Scout retired for Attack, section 8).
- Knobs added: `strataPerFP` (5, renamed from `fabricateTroopsPerFP`, one number for every
  exchange), `strataFlowPerDay` (400, the floor) and `strataFlowFractionPerDay` (0.02),
  `defendLingerDays` (10), `coreStrataPerSizeMonth` (200), `coreInputPerSize` (1),
  `starveMinDays` (15; `starveDaysPerSize` 90 stays), `densityLY`
  (10), `densityRef` (6,400), `densityAccessPerDoubling` (0.10), `densityAccessMin` / `Max`
  (-0.30 / 0.50), `densitySelfWeight` (0), `seedStrata` (400), `structureStrata` (750),
  `bombardStrataPerFPDay` (1.0), `bombardFuelPerStratum` (2), `shiftFloorStrata` (800),
  `strataSizeBase` (400) and
  `strataSizeRatio` (2), `attackMedShare` (0.5, the Med share of the staged fuel and marines). Every default a knob (the expose-balance-as-knobs rule).

## 10. Build plan - four jars, each playable and game-tested before the next (DECIDED, not started)

The design closed in round 10 (the user, 2026-10-25 evening: "Closed: start phase A", after
round 1's "don't start build yet still designing"); phase A started the same evening.

**A. The bank becomes the strata; the Core produces; the density.** `ThreatColonyManager` (the
bank's methods redirected to `ThreatStrata` at the exchange; `settleLedger` adds to strata),
`FabricationCore` (demand by size, the three states, the Nexus's top-up moved in), the density
accessibility in place of `applyHiveAccessibility`, the doubling table (`ThreatStrata.perSize`
-> `sizeAt` / `sizeOf`), the cap as a hard stop, `ThreatOffensive` / `ThreatPosture` reading
strata. Migration of the bank. Garrisons still stand in this phase.
**BUILT 2026-10-25, jar 13:22, untested.** Judgement calls: the cap is the top size's strata plus
the span below it (76,800 at 8) so a fortress at its cap has a buffer before it desizes; a world's
bank (`spareFP`, what it builds garrisons and foundings from) is its strata above the larger of its
size's own and `shiftFloorStrata`, while a strike or a funded shift draws from everything above
800 alone (`strikeSpareFP`) and takes a size with it ("Hive Thins"); the strike fund is that spare
hive-wide, less the posture's defence shortfall and the offensive's escrow (`addStrikeFund(-cost)`
escrows, `(+cost)` releases), paid in proportion to what each world gives and STAGED at the staging
colony as fleet points in flight (`KEY_STAGED_FP`) so a pooled bill never flickers that world's
size; `creditFP` absorbs into strata with spill to the nearest world with room; `strikeFundShare`
stays only as the fund-path switch and `strikeStagedOpenFP` (40k) now reads spare strata; the
retired banks and fund are moved into the strata once a save (`migrateBankToStrata`), and a figure
written under the linear table on first read (`ThreatStrata.of`); the Core holds while absent or
building and cannot be disrupted; no income or upkeep anywhere (`militaryIdle` is the price alone);
no Spaceport (`foundColony` adds none, `ensureSpaceport` retires one; `portArms` and
`ThreatColonyUpkeep.importCut` no longer need a port); the Nexus's top-up stays in `SwarmNexus`
until the Nexus goes (phase C); `ThreatColonyUpkeep` still runs its supplies ledger but drives no
growth (phase B retires it). To verify in game: a hive fed in full grows 200 x size a month
(Core tooltip "Growing N a month"); a far seed reads a low density, few shipping units and holds or
starves; a strike thins its givers ("Hive Thins") and the density around them falls; the board's
census reads strata and density; an old save logs the migration; no port on any hive. Proves: the hive still
grows, strikes and founds on one figure; a far seed stalls; a thinned back line starves; the
census reads strata and density.

**B. The hive comes off supplies.** Forge FP and supplies income off hive worlds, size upkeep,
the feed, feeding swarms, the hive cover, holds and `sortieStayDays` for Threat fleets, the
navy fit and the upkeep recycle, `ThreatBuildCost` -> `structureStrata`, founding -> `seedStrata`,
`foundingBudget` gone. `ThreatColonyUpkeep`, `ThreatFeeding`, `ThreatHiveCover`, `ThreatUpkeep`,
`ThreatReach` (the bill = passage fuel). Proves: a blockade starves a seed by the Core's imports;
a fortress with its own plants holds; nothing thins with time.
**BUILT 2026-10-25, jar 13:54, untested.** Judgement calls: `ThreatColonyUpkeep` stays as the FORWARD
BASES' size-upkeep table (`enabled()`), with `hives()` false and the hive's feed deleted; the supplies
stock reads 0 rather than being removed from the save; structures keep vanilla's build time and
cost `structureStrata` off the payer world above its floor (`structureFP`, `canAffordFP`), a build
the strata cannot pay waiting (`buildWaiting`); a seed is `seedStrata` charged at the source plus
the Seeding Swarm's hulls absorbed on landing (`settleFounding`: 80 FP of hulls on 400 = 800, size
2); a Seeding Swarm needs a standing Core (`seedReady`), no forge or retool; forges and Domain
items on older saves are stripped each tick (`retireForge`, `maintainHomeRelics`); the board's
Fed / Vitality column is the Core's state (`fedState` on `daysToLoseSize` / `daysToNextSize`,
`stateColor`); the Abyss bootstrap wave still lands as a garrison (phase C absorbs it);
`sortieStayDays` stays for the humans. To verify in game: a seed lands at size 2 (800 strata, the
"Founded" log line); an old save logs forges retired and items given up; the Core column reads
growing / holding / starving; no seeding or strike is ever held on supplies; a planner build logs
"N FP banked".

**C. Fleets bud, shift and are absorbed; the flow is staggered and seen.** The garrisons go:
budding a ship at a time (`growGarrisonFleet` per ship, the flow, the queue per world, absorb
before bud), Defend on attack and reabsorb after (`defendLingerDays`), SHIFT in place of the
pressure pass, the strike budded at the staging system, absorbing at the same rate
(`breakUpForHive` generalised), the rising animation in `ThreatFabricationVisual`, the board's
flow cell, a budded fleet fighting at what it has, budding against bombers; migration of standing
garrisons. Proves: a quiet hive has nothing over it; a siege races the buds; a strike's
preparation is visible; a fleet home lands over days; the back line thins toward the front under
PRESS.
**BUILT 2026-10-25, jar 14:16, untested.** `ThreatBudding` is the whole mechanism: a per-world order in
market memory (kind defend / shift, want in FP, the pool in FP), filled at the flow each poll
(`take`: staged points first, then strata to a stratum for the world's own defence and to
`shiftFloorStrata` for a shift), spawned whole (`ThreatFleetComposer.createSized` on the garrison
archetype, seated as the world's Defend fleet in `garrisonsFor`, a shift sent by `sendReinforcement`
and absorbed on arrival), drained back when the attacker leaves, spawned short when doomed (ground /
yesterday's enemy loss rate, kept by `ThreatStrata.add` -> `noteLoss`). The trigger is any hostile
weight within ORBIT_HOLD_RANGE (`hostilePointsNear`) with less standing against it: the poll calls
`ThreatPosture.rally`, whose gather now counts the system's buddable strata and whose tail buds the
rest (`defend`: the world first, quiet siblings as shifts to it). Absorbing: a fleet over its own
world with the orbit quiet `defendLingerDays` (a shift at once) loses hulls smallest-first at the
flow (`applyFleetLosses`) into the strata up to the cap. Strikes: the staged branch of
`launchStrike` holds until the bill is in the colony's staged points (`strikeStaged`), ordering
`orderStrike`; the poll bites the fund (`spendStrikeFund`) or the banks at the staging system's
flow; the plan's budget counts the staged points. The shift replaces the pressure pass: one order a
poll, an attacked world short of an answer first, else PRESS -> the hive system nearest a known human
world, CONSOLIDATE -> the system with the most strata, the world with the most room; donor the nearest
quiet world of another system above the floor. Judgement calls: the posture's standing figures
(`floorFP`, `minimumFP`, `baseCount`, `rowsFP`, `oneSwarmFP`, `baseReserve`) read 0 rather than being
deleted, so `wantFP` is the pressure's share and the line alone; `maintainColonyGarrisons` only
prunes; `redistributeByPressure` and `splitLaunchSwarm` deleted, `fillGarrisonNow` returns 0; scouts,
patrols and raiders still draw strata instantly (`chargeFP`), not by budding; a strike or Defend
fleet that comes home still settles into the strata at once on despawn (`settleLedger`), the
flow-rate absorb applies to fleets seated over a world; the Abyss bootstrap wave and every standing
garrison of an older save are absorbed by the linger rule (no separate migration); a held prong whose
launch now waits on budding is dropped by the offensive that day and re-priced next pass, the order
keeps budding (lapses after 60 d unasked); the Nexus still stands (phase D); `ThreatHulls` still
reads the Nexus's patrol table (phase D). Three phase-B leftovers fixed: `getPhase`, the posture's
forge count and `isStrikePlatform` gated on a forge no hive has - now `coreStands`. Board: a Flow
column ("budding 1,250 (2 d)" / "shifting 640 to X" / "absorbing 400 (1 d)" / "settling" / "strike
..."). To verify in game: a quiet hive has nothing over it after `defendLingerDays`; a bomber over a
hive sees rising glows and a whole Defend fleet after want x 5 / flow days; the pool drains when it
leaves; a strike's "buds at" line, then its launch days later; an old save's garrisons absorbed ("absorbs N FP of") and the sizes step up; a shift order under PRESS.

**D. The military layer goes; the humans size against buds.** The five industries removed and
migrated, the Core unraidable and undisruptable, the hive theatre with no structures
(`keyStructures`, `fortifications` empty; `frontWearRate` on hives off), bombardment on a hive
as a strata exchange (`siegeSlice` / `saturationSlice` / `razePass` / `bombardPlan` /
`ThreatincMarketCMD` / `ThreatPurgeFGI`'s plan on hives: strata a day for fuel, no return fire),
raids off hives, `SwarmBastion.tier` reads gone, the orbit gate and the siege sized against the
buds (`ThreatIntel`, `npcSiegeOrbitMargin`'s read, the marine headroom), swarm bounties and hunts
re-aimed, the readouts (vitality tooltip, board rows), the player's Hunt / Bombard / Siege / Scout buttons
retired for the one Attack order with its Fuel / Marines / Fleet ladders and its prompt (section
8), knobs retired. Proves:
Beta Prisma I under the player's bombardment loses strata a day at the quoted rate and buds or
dies; a landing fights the strata figure alone; nothing on a hive reads "disrupted"; NPC sieges
still sail; an Attack at Fleet Max / Fuel Med / Marines Min sails with the quoted fuel, razes at
the quoted rate and comes home when the fuel is at the passage.

**BUILT 2026-10-25, jar 14:47, untested.** The military layer: `ThreatColonyManager.retireMilitaryLayer`
strips the Nexus, both batteries (ours and vanilla's), the Bastion and Command off a hive world in
every essentials sweep; the classes stay for older saves (`SwarmNexus.apply` adds nothing,
`SwarmBastion.tier` 0, `ThreatGroundDefenses` bonus 0) and `hasOperationalNexus` reads
`coreStands`. The Core carries the defence: a flat `GROUND_DEFENSES_MOD` top-up to
`ThreatStrata.standing` (`FabricationCore.apply`), tagged `unraidable` in industries.csv, and the
hive's RAID option is off ("Nothing to raid: the hive is its strata"). The HIVE theatre is
structureless (`keyStructures` / `defenseStructures` / `fortifications` empty, `suppressDaysPerDay`
0, `hiveFortificationBonus` 0) and its return fire, drop loss and worth are 0. ONE exchange path:
`ThreatGroundFronts.hiveRaze(market, fp, days, fuel, razer)` razes `hiveRazePerDay(fp)` =
`bombardStrataPerFPDay` x fp a day for `fuelPerStratum()` each, through `ThreatStrata.add` (sizes
step down, destroyed at 0); `siegeSlice` (now with a razer id), `saturationSlice`, `bombardPlan`
(`hivePlan`), `bombardDay`, `abstractSiegeStep`, `supportSlice`, the purge's live slice and daily
siege all route through it, with market-aware `bombardFuelPerDay(market, fp)` /
`bombardDaysFor(market, fuel, fp)` overloads (`siegeOrdnance`, `ThreatFleetOrders`, `ThreatPlays`,
`ThreatAttackPlanner`, `tacticalFuel`). A purge dry of fuel with no marines over strata holds the
orbit as a blockade up to `siegeOrbitDays`. The player's dialog over a hive: `strataBombardMenu` /
`strataBombardPrompt` (strata, size, a day's strata for its fuel, the size it leaves; the fuel paid
is the prompt's price, no pollution). The humans size against buds: `ThreatIntel.picture` folds
`ThreatBudding.buddableFP` into each report (`budFP`, `budDays`; `nearFP` and each world's swarm at
least the bud), so the orbit gate, hunts, bounties and the board read the bud as the swarm.
`nexusAnchoredDefense` reads `ThreatStrata.standing`; `bombardGoalDays` 0. THE ATTACK ORDER:
`IncursionManager.Attack` / `attackPlan(base, faction, system, fleetTier, fuelTier, marineTier)` /
`attackBlockFacts` / `launchAttack` -> `launchSiegeExpedition(..., downGoal, fuelGiven)` in attack
mode (the ladders' fuel and marines exactly, an empty raze set so the marines land); the board's own
hive row has one Attack button (`BUTTON_SIEGE`), three ladders above the table
(`addAttackLadders`: Fleet = `siegeTier`, Fuel = `fuelTier`, Marines = `marineTier` on
`ThreatIncursionIntel`; `addTierSelector` takes an x offset), `addAttackPrompt` one line a fact
and an "Attack Sails" notice. Knobs: `bombardStrataPerFPDay` 1, `bombardFuelPerStratum` 2,
`attackMedShare` 0.5 added; nexusDefenseBonus, groundDefensesBonus, heavyBatteriesBonus,
hiveBombardReturnFirePerGunDefence, strataSuppressDaysPerSize, hiveMilitaryTier, nexusBombardable,
hiveBombardFPWorth, hiveDropLossPerGunDefence, hiveSiegeSuppressDaysPerDay, bombardTierMinDays,
bombardTierMedDays retired (Luna migration 21). Judgement calls: `retireMilitary` and
`conversionRank` stay (dead without Bastions); the allied Hunt (aid) and the fleet-row Hunt detach
stay; the Bombard / Hunt / Scout handlers stay as dead code in `executeOrder`; `groundDefenseMult`
kept though unread; Nexus contracts are never offered again (old ones read the Core); the
blockade's stay is `siegeOrbitDays`, not `sortieStayDays`; an NPC siege now prices its fuel as
`hivePlan` days x fp x 2 (1,400 FP against 6,400 strata is about 5 days, 12.8k fuel). Verify:
the proves above; the Attack prompt's "Fuel N: D days, S strata - a size down from X" against what
lands; a hive's tooltip shows no Nexus and no batteries after one sweep on an old save.

**E. Consuming a human world** (section 13). The infestation condition on a market under a
Threat front (every industry's output x (1 - the seized share)), the landed strata growing as a
hive of the size held (`coreStrataPerSizeMonth` x colony size x share a month, into the front's
strata), the conquered hive's ceiling at the market's size and its growth into it a size per
`starveDaysPerSize` with no Core demand until there; the fronts table's Strata column reads the
growth. Proves: a Threat front holding half a world grows and the world's output halves; a
world taken by 2,000 strata reads size 6 a year on; the humans' counter-push still wins when
sized to it.

**BUILT 2026-10-25, jar 14:55, untested.** The infestation is `ThreatGroundWarCondition.apply`: every
industry's supply x (1 - `ThreatGroundFronts.infestShare`), the share the swarm's front holds of
the world's size (vanilla's `ResourceDepositsCondition` pattern, so it survives the recompute);
its tooltip says "Threat infestation: every industry's output xN; the swarm grows S strata a day on
it". The growth is `infestGrowthPerDay` = `coreStrataPerSizeMonth` x size x share / 30 (time
scale applied), joined to the front's strata (`front.marines`) each tick by `feedOnWorld`, after the
attrition; the fronts table's Held cell reads "2/6 +40/d" and the row tooltip has the arithmetic.
A ground victory seeds the hive at the army's strata (`colonyGroundVictory` passes `front.marines` to
`convertConquered(planet, market, armyStrata)`: `ThreatStrata.add` "Landed as", never under the
founding size) and sets `CONSUME_TO_FLAG` to the world's size (clipped to `maxColonySize`); while
`consumingTo(market)` > 0 the Core demands nothing (`coreDemand(market, commodity)`, read by
`FabricationCore.apply` and `coreState`) and `coreState.consuming` grows `ThreatStrata.span(size)` per
`starveDaysPerSize` days, a size per clock, held as any growth is by a front or saturation; at the
size the flag is unset ("has consumed its world"). The vitality tooltip reads "Growth: N a month,
consuming the world to size S". Judgement calls: the growth is not capped by anything but the attrition
(a dug-in front is a clock, as decided); the seized industries stay pinned disrupted as well (both
cuts stand); the condition's `apply` relies on vanilla reapplying conditions after industries (as
`ResourceDepositsCondition` does) - if the colony screen shows no output cut after a district falls,
add `market.reapplyConditions()` to `ThreatGroundFronts.reapply`. Verify: the proves above; a Threat
front holding 2 of 6 districts reads "+40/d" and the world's industries show "Threat infestation x0.67";
a conquered size-6 world's hive logs "consumed from size N up to 6" and reads "consuming the world to
size 6" with no Core inputs on its tooltip.

Every phase: `compile.ps1`, a game run from the current save and a day-0 run through the
harness, the digest, facts and the topic docs updated the same session (`code-map.md` for the
classes deleted and the Core's new line). A, B and C each touch most of `ThreatColonyManager`
(7,700 lines) and `ThreatPosture`; D is mostly deletion plus the human sizing.

Docs to rewrite after D: `hive-economy.md` (the bank section becomes history),
`hive-stocks-and-upkeep.md` (supplies and feeding become history), `hive-garrison-and-upkeep.md`
and `swarm-navy-fit.md` (retired), `swarm-defence.md` (the garrison parts), `suppression-balance.md`
(the hive rows), `ground-war-sieges.md` and `ground-war-orbit-control.md` (the hive paths),
`strategy-reserves-sieges.md` (the orbit gate), `war-board.md`, `code-map.md`, `facts.md` lines
that answer with a Nexus, Bastion, battery, bank, garrison or feed.

## 11. Decided, and open (closed in round 10)

**Decided (rounds 1-3):** the doubling table; no return fire - the hive buds to fight; build
order A -> B -> C -> D, nothing started while the design is open; the Core's size-scaled demand
under accessibility replaces upkeep, grow / hold / starve; fleets bud when the world is attacked
and have no upkeep - no standing garrisons; the hive off supplies entirely; nothing at the cap;
accessibility is aggregate swarm density, like gravity, not distance to a producer; density
self-weight 0 (siblings in the system count in full, the world itself not at all); a siege is
sized against the buds over its planned stay; the flow is 2% a day with a 400 floor; no
producer floor on shifting.

**Decided round 4:** the density calculation as written; the lone seed with no hive within
~60 ly holds at 3 - accepted; no Spaceport on hive worlds.

**Decided round 5 (sections 2-3):** growth 200 x size a month; a structure costs 750 strata;
a seed lands as 800 (size 2); starving scales to the deficit (shape proposed in section 3,
confirmed in round 6).

**Decided round 6:** starving a size per 90 / (u - 1) days, floor 15, an input at zero always
starves; a blockade cuts imports entirely; saves migrate. The board's hive row is as section 8
has it (size with the strata, the density, the flow cell) - no question put.

**Decided round 7:** bombard fuel a constant 2 a stratum; a shifting donor keeps 800; a strike
may take all above what the posture keeps; reactive only - a world buds only when bombarded,
never against a landing.

**Decided round 8:** one Bombard at 1 stratum per FP a day for 2 fuel, nothing instant, no
tactical, blocked while a Threat fleet stands; a blockade triggers buds at once; the pool -
spawn whole, never short; Threat strata on a human world consume its local production into
growth; a conquered world grows into the planet's size.

**Decided round 9:** the bombard numbers stand; a landing's covering force holding the orbit is
a blockade; a doomed world spawns its pool short (the test in section 2 is the session's); the
player's Hunt, Bombard and Siege become one Attack with Fuel / Marines / Fleet ladders sized to
the staging colony's stock, never to the target; the Scout button goes.

**Decided round 10 - the design is closed (the user: "Closed: start phase A"):** the Attack
order's shape as section 8 has it - Fleet one / half / all, Fuel passage / half / all above the
floor, Marines none / half / all clipped to crew space; at the target hold the orbit, fight what
buds, raze every day the fuel pays while no Threat fleet stands, land on arrival, home when both
are spent; Support and Defend off the hive rows.

**Nothing open.** Phase A started 2026-10-25 evening with committing the "ships are the
soldiers" tree the other session left (section 10).

Assumed unless the user says otherwise: the exchange stays 5 strata a FP both ways
(2026-10-03, 2026-10-25); the human side untouched; a founding is one size of strata; structures
cost strata; the Bastion/Command tier and the council's tier read go; no new notices; the growth
rate 200 strata a size a month (a knob); scouts and patrols stand as today (the Scout button goes; your faction's own sweeps stay); a Defend fleet lingers
10 days after the last enemy leaves.

## 12. Judgement calls and risks

- **Metals and fuel by vanilla availability** (the Core's deficit) rather than a stock of our
  own: a metals stock would be a third ledger to keep, and the user's standing rule is to fix
  shortages with placement, not simulation. The fuel stock already exists for passage and banks
  what the plants make above the hive's own demand, which now includes the Cores'; a hive of
  big worlds banks less passage fuel, and a hive that builds no plants grows nothing past size 2
  (fuel demand size minus 2). If growth starves strikes in runs, the Core's share is a knob on
  `perMonth`.
- **The HOLD band is one unit** so that a self-sufficient world can grow (section 3). It means
  an importer one unit short holds for ever rather than starving - the user's "stabilises" -
  with the one exception that an input arriving at zero is a famine, not a shortfall, so a
  total blockade kills a seed as the user ruled on 2026-10-24.
- **Self-weight 0 makes the system the unit.** A world's accessibility is its neighbours'; a
  lone world has none and lives on its own plants. "Spread too far" bites the first seed in an
  empty system (size 3) and a fortress whose neighbours were shifted away (holds only if
  self-sufficient). The table in section 5 is the whole argument; all five constants are knobs.
- **Seeds are helpless in orbit.** A seed cannot match any real force (only-if-enough) and
  never buds against one; a 500-FP raid razes it in two days for 1,600 fuel - a thing nobody
  could stop today either. The hive's answer is mass near the front before the raid, which the
  fog makes a guess - the intended game - and the seed's system siblings, who bud for it when
  together they are enough.
- **The pool makes the first days of a fortress siege free:** six days of bombing (6,000 strata
  to a 1,000-FP bomber) before ~1,200 FP spawns at once, then the bomber fights or leaves. One
  that wins bombs on; one that loses is gone, and the fortress regrows 1,600 a month. The
  fortress's dilemma stands - bud and spend the ground, or keep the ground and be razed - and
  the posture's choice is the rally's as built (the cover over a landed army), extended to "a
  bombardment or a blockade is an army with no troops".
- **Shifting churn.** A pressure pass that shifts every poll will have fleets in flight both
  ways; shift on the posture's `postureDays` cadence only, and never from a world under attack.
- **Humans lose their targets.** Bounties and hunts against Defense Swarms have nothing to hunt;
  the hull pool's hive patrols read strata. Re-aim bounties at strikes and Defend fleets.
- **Sizes that swing.** Vanilla reads market size in many places (industry slots, the council's
  Picture, accessibility - now ours). The doubling table makes a swing a size at most for
  anything but a seed; `pinMaxSize` already handles the cap; a size down removes no industry in
  vanilla.
- **The exchange is lossless.** Bud and absorb at the same rate with no discount, so shifting
  costs only time, passage fuel and the risk in flight. A discount would make every shift a leak.
- **The conquest front becomes the strata.** A human world taken seeds a hive at the army's
  strength - a large landing founds a large hive at once. If it reads as too strong, `seedStrata`
  caps the founding and the rest stands as a Defend fleet.
- **Nexerelin:** the invasion menu on a hive reads vanilla's defence, which is the strata
  standing; no structure reads change. The Core unraidable is a vanilla tag Nexerelin honours.
- **Scope.** The largest change since the fronts: four phases, each a session or more, on the
  files that every war mechanic runs through. Nothing in it is a knob turn.

## 13. Consuming a human world (the user's brainstorm, round 7 - DECIDED round 8)

The user: "the threat doesn't bombard human worlds at all. it just dissimilates its forces into
ground units which form a new strata on the colony. while these strata exist they consume colony
imports etc. The threat ideally convert their whole fleet into strata at the target once they
have control of space (if no control they fight in space as usual). dissimilation isn't instant
like they still need time to convert the whole fleet. once the swarm have become strata on a
planet they still fight in the siege but i would like them to start consuming the planet
somehow, the end result becoming a threat colony of the same size as the planet. haven't thought
this all through yet just brainstorming. not quite sure if the strata should grow or actually
consume imports. perhaps just local resource production is consumed, not sure if that means
metals or hulls and other stuff as well. what do you think?"

**Most of it is built** (2026-10-25, SHIPS ARE THE SOLDIERS, `facts.md` 381 and 805-807): the
swarm does not bombard human worlds by default (`strikeSaturationEnabled` false); a strike
fights for the orbit and lands only with it held (the orbit gate); every troop is a hull broken
up at 5 a FP (`ThreatStrikeFGI.embark`, flagship and last hull spared), over days, with the
animation; the landed troops are strata on the ground (the fronts table's Strata column), sized
to overrun; a front that wins seeds a hive at the army's strength (`digInAtConquest`). New in
the brainstorm: (a) the landed strata CONSUME the colony while they fight; (b) the end state is a
Threat colony of the planet's size.

**My take:**

- **Consume local production, not imports.** Imports are the owning faction's shipping into the
  colony; the Threat has no use for them, and taking them reads to the player as a trade
  problem. What the planet MAKES is the planet: the landed strata eat the seized share of every
  industry's output (the districts as built - a front that holds districts holds that share),
  as one condition on the market, "Threat infestation: output -N%", N the seized share. Every
  commodity alike, hulls included: the Threat does not care what it eats, and it is one number.
- **And grow by it.** What is eaten becomes strata: the landed strata grow as a hive of the size
  they hold - `coreStrataPerSizeMonth` (200) x (colony size x seized share) a month, no Core
  needed, the planet is the Core. Holding half a size-6 world grows 600 a month. The colony loses
  the same share of its output and its stability with it (vanilla's shortages), and its
  garrison's armaments burn as built. A dug-in Threat front is then a clock, not a stalemate:
  the humans must push it off or lose the world to it - the user's "humans play to win" turned
  on the defender. The attrition rule as built still bleeds the strata; eating against bleeding
  is the fight.
- **The end state: the planet's size, grown into, not granted.** On victory the hive starts at
  the army's strata (as built) and keeps the market's size as the CEILING it grows into by
  consumption - a size per `starveDaysPerSize` (90) days, the starvation clock run backwards -
  with no Core demand until it reaches that size; then it is a hive like any other. A size-6
  colony taken by a 2,000-strata army is a hive of 2,000 that becomes 12,800 over a year,
  visibly. Granting the 12,800 at the victory would make every conquest an instant fortress -
  the snowball the user spent a month breaking.
- **Space control** as built: a contested orbit is fought in space; with it held the strike
  dissimilates, the whole fleet (flagship and last hull spared), over days.
- **Issues:** (1) the human siege balance moves - today a landed army only bleeds; one that
  grows must be countered, so `frontGrindBand`, the dig-in doctrine and the relief sizing all
  meet a front that gets stronger, and one game run will show whether eating or bleeding wins
  at size 5-7 worlds; (2) a condition that cuts output is read by vanilla's economy as a
  deficit everywhere that imports from the world - the intended ripple, and it reaches the
  player's own markets; (3) the seized share must be a real figure - the districts as built
  (held, seized) give it.

**DECIDED round 8:** consume local production into growth; the conquered world grows into the
planet's size. Built as phase E (section 10).
