# Hive economy - reach, idle stock outlets, levers

Split out of `hive-economy.md` on 2026-10-01. One-line answers: `facts.md`.

### Reach is the bill (2026-09-30, user's call; `ThreatReach`)

The hive had a radius: 4 ly x min(fuel available, vitality x size), 16-24 ly for a strike world.
Once passage was paid from a banked stock the radius said nothing true - h36a banked 256k fuel and
spent 65% of its income - while it walled the hive in: 18 of its 35 strikes flew at 80%+ of their
reach, all 17 human core worlds ended out of reach, 76 of 113 strikeable markets lay beyond every
hive's, and one scout flew in 19 months. Vanilla's own numbers say fleet size does not change
range (a fleet's range is its tanks over its burn, and ten ships carry ten tanks; every Threat
hull carries 200 ly of fuel, the Fabricator 1,000, human warships 15-33): size changes the bill.
So the hive has no radius now. A fleet goes where its trip can be paid, and where to go is a choice.

- **The bill.** Passage out of the fuel stock (`ThreatFuel.passage`, 0.4 fuel a FP a light-year
  there and back), and the supplies the fleet burns while away - its hulls' vanilla supplies a
  month, 0.78 a FP for the swarm's hulls, measured off the garrisons each day
  (`ThreatReach.suppliesPerFP`) - over the days away at the board's 0.5 ly a day.
- **The gate.** The stock must pay the passage, and the fleet's supplies must fit in the spare
  (`ThreatColonyUpkeep.spareSupplies`): the production, less the fleets away, less the navy charge,
  less every colony's sustenance at its share (`sustainShare` 0.7, so an expansion tithe of
  1/0.7 x sustenance is kept for growth, builds and seedings) - or, the chest full
  (`ThreatStance.chestFull`, 2026-10-08: the strike fund holds a horizon's saving and could field
  the cheapest known prong the fuel in stock can sail, `ThreatOffensive.cheapestProngCost`, yet has not - a fund that fields
  nothing is poor, not full: the user's pick after hw70), less what the colonies actually took, the tithe feeding the fleets (hw62 banked 38k ->
  141k FP against a spare of zero). A trip never starves a colony; it can starve growth, since
  fleets away are paid first. Launches between feeds commit their share (`ThreatReach.commit`). Its whole bill
  counts, not the month's rate (2026-10-01, `canSustain(fp, days)`): the supplies a month over its
  days away must fit in the spare over those days plus the stock above one founding kit
  (`freeStock`) - on the rate alone ng1b's dying hive held a raider with 36k supplies banked and
  ng2a sat on 30-84k. A spare already negative (fleets out past the flow) drains the stock first.
  The colonies draw on the same stock: when a stock-paid trip's burn leaves the month's flow short
  of their sustenance, the feed tops it up from the stock above one founding kit
  (`ThreatColonyUpkeep.feed`), so such a trip never starves a colony either.
  Strikes, waves, scouts and raiders pass the gate; reinforcements pay passage only - a garrison
  moving house is not a trip away.
- **The choice.** Strikes weigh a world by what it is worth per day away: `strikeValue` over
  `strikeDays` = 2 x ly / 0.5 + the 10.5-day muster. The muster is as many swarms as the spare keeps
  away, the target any whose passage the stock pays. The stance's weak targets the same. A hive
  system faces the faction it would strike first (`facedFaction`).
- **Opening a war** (2026-10-01, `IncursionManager.warOpen`). A faction mobilises the day its first
  world is struck (`ThreatWarState.recordStrike`), so a strike at a faction not yet at war starts
  that war. Before phase 3 - a size-6+ forge world with a near-nominal hull economy and a known
  core world the stock fuels a swarm to - the swarm strikes only a faction at war with it already,
  one that has hurt it (a grudge, `ThreatAlarm`), or one that never mobilises (pirates, ownerless
  stations); the player's worlds likewise open with the player's own mobilisation or a grudge.
  The new game's first hive (ng1b) struck the Hegemony at month 26 with six worlds, the Hegemony
  mobilised at 29, and every faction it struck mobilised 2-3 months after: it was razed over the
  next eight years without taking a human world. The front (`facedFaction`), the stance's weak
  targets and a strike's sweep of its target's system read the same rule (only the primary target
  mobilises its faction, so a swept bystander would be struck without one).
- **Founding** may claim anywhere a forge can send a wave. A claim weighs its deposits' need and the
  stance's lean as before, over the days a swarm needs from the network to get there times the days
  a strike staged there would be away at the nearest world of a faction not yet at war with the hive
  (the old weight's 1 + ly and 1 + ly² - the squared pull toward inhabited space leaned on the radius
  to keep the jump short; a faction at war pulls nothing since 2026-10-01, ng3a's war claims having
  gone a median 2 ly from a human world to be razed within a year),
  times the share of it the hive could hold (`holdShare`): the days the nearest faction military
  world that reaches the system needs to put a siege there (`razeArrivalDays`) over the days the
  nearest hive world's swarms need to get there, 1 when the hive gets there first or no base
  reaches it. The nearest forge sends the wave. The best claim is taken, not drawn: with no radius
  every system is a candidate and the far ones' small weights summed to a lottery ticket (h37a sent
  2 of 17 claims 27-28 ly out on it). A claim whose founding and way out the fuel stock cannot pay
  now is not taken (`ThreatColonyManager.foundingFuel`): it would hold its forge's claim for months.
- **Reinforcements** come from the nearest donor with FP to spare, then the nearest idle bank.
- **Scouts** chart every uncharted system holding a strikeable world, nearest first, each route as
  long as the scout's own tanks carry it there and home (200 ly for the swarm's hulls) and the stock
  pays; the stops past that wait for the next scout.
- **Raiders** hunt a convoy whose route's middle they reach before it does: half the route, both at
  the board's one speed (`raiderRangeLY` with `billedReach` off).
- **Phase 3** needs a known core world the stock can fuel one swarm to and back.
- **Abstract strikes pay.** A strike far from the player flies as a route with no fleets; its swarms
  burned nothing (h36a: no strike ever owed). They burn the hive's rate a FP now, from the muster on
  (`ledgerFleetSupplies`, `ThreatStrikeFGI.abstractFP`).
- **The factions read the front off it.** A link is at the front where a hive world that would strike
  the faction first has it as the faction's nearest market; its guard is sized to those worlds'
  strikes (`ThreatFrontlines.strikeAt`). Aid credit goes to the factions no farther from the hive
  system than its first target; a mission's urgency is the days a strike from the nearest hive world
  is away over the days one from this world is.
- **Readouts.** Census: `Reach: spare S supplies/mo (fleets away F/mo, x a FP); strikes n (mean a,
  max b ly); waves ...; sends ...; raids ...; scouts ...; hive spans X ly over N systems, facing
  {faction=systems}`. Strike launches log their ly and days away, claims their distance from the hive
  and the nearest faction world, waves and scouts their ly, the stance's best weak target its ly.
- **Settings:** `billedReach` (true; off, the radius above and `raiderRangeLY`).

**h37a** (lt save, 3 x 110 s at 64x, ~20 months, 0 exceptions). The lt hive was grown under the old
rules: its colonies' sustenance ran ~200k supplies a month against 37k made, so the spare opened at
-176k and every strike waited (104 "Strikes from X wait" lines) for the ~15 months the colonies took
to starve down to what the forges feed (bill 400k -> 65k a month, 12 -> 11 systems). Then ~2 strikes a
month, 12 in all, 5-32 ly (mean 20): forward bases, and in phase 3 Yama, Chicomoztoc and Coatl, where
the swarm landed fronts. A scout flew 121 ly through 17 systems; raiders caught two convoys. Hegemony
razed Epsilon Shero and eradicated Alpha Mesh I; Persean fronts sat on two hive worlds. Supplies bind
(stock 400-5,000, every Seeding Swarm held for its 5,000-supply kit); fuel does not (500-640k, the
old rules' bank). A forge's net output peaks at size 6 (4 units x 750 against 781 sustenance) and is
negative at 8 (4,500 against 4,883), which is why size upkeep starves the old save's size-8 worlds.

**h38a** (h37a's end, 8 x 110 s, ~34 months, 0 exceptions). Past the starvation the spare opened
(2-28k a month) and the war came alive: 58 strikes at 3-42 ly on four factions and the player's Haven,
24 convoy raids, 13 scouts (21-79 ly routes), the hive from 11 to 22 systems (spanning 34 -> 69 ly),
supplies made 37k -> 62k a month, the stance cycling EXPAND / CONSOLIDATE / PRESS on pressure. The
legacy fuel bank ran dry (640k -> 0 in ~15 months), mostly on Posture sends into contested systems
(100-230 a month, 11-30 ly; one claim beside the Hegemony, Ib Nwork in Onora, wanted 30k FP); the
planner answered the noted fuel shortage with plants (fuel made 34k -> 66k a month). That is the
spread-thin bill the design asked for: a claim near the humans is cheap to take and dear to hold.
But the want was the grudge's, not the war's: ~x5.5 the staged capacity (see Posture, B), and a
third of the sends fed six frontier worlds that all fell within five months, 22% of them
disbanded in flight when their colony died.

**h39a** (the lt save again, 3 x 110 s, ~22 months, 0 exceptions, on the night's last build: the
planner's invest and first-link rules, forges fed first only while they make more than they eat,
posture without the grudge, the stock-aware gate, `warOpen`): the same oversized hive recovered
far faster - supplies made 16k -> 79k a month by month 5 (forges invested on worlds that had none,
size-8 forges let shrink), the spare +11-19k a month from month 13, 50 strikes in 22 months against
h37a's 12, 39 worlds and 62k FP at the end against 37 and 46k. FP banks to 162k: supplies bind the
trips, and FP has no other outlet.

**New game** (a mercenary start, Mar 206, pirates and Pathers set neutral to the player so no
encounter stops the clock; 14 x 110 s covers ~11 years, the early sector being light). **ng1b**
(the build above): the hive seeded Gamma Sonora at month 5 with six worlds and flew nothing for
16 months - Mining took five of six single slots, the fuel plant waited for size 3 - while 28k
supplies sat idle; it struck the Hegemony at month 26, the Hegemony mobilised at 29, and every
faction it struck mobilised 2-3 months after. It peaked at 13 worlds (size 45, ~8k FP, 4.5-6.7k
supplies a month) at months 37-40 and was razed down to nothing by month 132 - 9 of 13 losses
raids from orbit (hegemony 9, persean 2, independent 2), never taking a human world; 50k supplies
sat unspent at its death. **ng2a** (the planner's first links at founding, planning every month,
investing idle supplies, posture without the grudge): the chain stood at founding and fuel flowed
at month 13, 10 worlds by month 22, 15k supplies and 2.2k FP a month by month 55 - and the
Hegemony, struck earlier, mobilised at month 20 and began razing at 30: 4-7 worlds from month 64,
30-84k supplies and 15-26k FP idle. A faster hive only started its losing war sooner - hence
`warOpen` and the stock-aware gate above.

With `warOpen` the war became the hive's to start, and the same sector played out two ways.
**ng3a**: peace through phase 2 (12 worlds, size 47 by month 33), phase 3 at month 34, then the
Hegemony (37), Persean (39) and Luddic Church (44) struck and mobilised; the hive grew to 41 worlds
(size 149, 45-60k FP, ~50k supplies a month) by month 61 and was pushed back to 27 by month 84 (the
run stopped there on an encounter dialog): 35 of its worlds razed, 85 of its 104 strikes spent on
forward bases the humans rebuilt, 17 of 20 landings on core worlds beaten, no core world taken.
Its claims in the war went a median 2 ly from a human world and lived ~11 months; under
CONSOLIDATE three size-7 forges grew to 8 and the bill went 35k -> 53k. **ng4a** (same build, the
same sector, Fleets Ignore You on): the Hegemony mobilised at month 37, the Persean at 62, Luddic
Path 64, Luddic Church 77; the hive lost 50 worlds and razed Nachiketa (42) and Sphinx (105), and
out-expanded its losses to 78 worlds (size 317) by month 117 - ~150k FP of fleets, 124k supplies
and 16.5k FP a month, 297k FP banked unspent (supplies bind its trips; FP has no other outlet). So
the outcome is the war's, not the rules': a hive that loses its early fights is ground down, one
that wins them snowballs. Then: claims feel no pull toward a faction at war (only `holdShare`'s
risk), and consolidating grows the smallest worlds - a razing pours ~1k fuel into a size-2 world
and 39k into a size 5 - not the biggest. **ng5a** (that build): phase 3 at month 37, the Luddic
Church, Persean and Hegemony mobilised within six months (Tri-Tachyon at 108); the hive peaked at
33 worlds at month 89 and held 22-28 to month 123 (60-84k FP), losing 71 young worlds to orbital
razes and refounding as fast; no human world fell. Its supplies stock rose to 163k before trips
drew it down; fuel ran to 538k (plants built for old shortages make 82k a month against ~30k
spent) and FP to 217k banked. **ng6a** (the night's last build, with the 2026-10-01 review's fixes:
the sweep reads `warOpen`, sustenance tops up from the stock): phase 3 at month 36, and the wars came
one at a time - Hegemony 40, independents 56, Tri-Tachyon 65, Luddic Church 99; the hive grew to
35 worlds by month 81 and held 32-38 to month 124 (50-80k FP, ~52k supplies a month), 179 strikes,
Nachiketa razed at 45, 60 young worlds lost and refounded; 165k FP banked.

What the new-game runs show, as of 2026-10-01: the war's outcome is decided by the asymmetry
between the two doctrines more than by economy. Humans raze hive worlds from orbit - cheap, sized
1.5x the garrison they weigh, the garrison never trading in an abstract razing (29 razes in ng3a
cost the attackers 34 of 72,700 FP) - while the swarm only ever lands fronts (payload authority:
it besieges, it does not annihilate), and 17 of ng3a's 20 landings on core worlds were beaten.
Open for the user: whether abstract razes and sieges should fight the defending fleets (both
ways), and whether the swarm should raze too.

### Idle stock: retire, convert, garrison (2026-10-01, user's call; built, untested)

The overnight tests ended with the hive holding 160-300k FP in its colony banks and up to 538k fuel
(ng5a) while supplies bound every trip. Every hive stock was additive and uncapped, and the
planner only ever added producers: any held send, any unaffordable build (Orbital Works and Heavy
Batteries included) and any unpaid upkeep noted a 30-day shortage that the planner answered with
a plant (the logs count 59 fuel plants built "for fuel short" and 26 as spares, none ever retired).
The spare step added copies until every hive system had one, whatever the stock. FP buys only
fleets, and garrisons stop at the posture want, so the banks idled. Four rules now.

**1. The planner reads a trailing demand, not a held trip** (`ThreatFuel`, `noteDemand`,
`demandPerMonth`). Per stock (fuel, supplies) it keeps two decayed sums in persistent data: the
demand A and the days observed B, both decaying by e^(-days / T). B grows by the days elapsed
(`ageDemand`, each poll from `accrue`), so A / B x 30 is the mean monthly flow weighted toward the
last T days. The window opens full: at the first poll B is T and A is T of the production then
(nothing in a new game, before the hive lands), so demand reads as production until spending says
otherwise and no producer is built or retired on no evidence. Opened empty (the first draft), the
first days read as a month many times over: the lt save's 160k of founding fuel in its first month
read 717k a month and "runs dry" for three months while the stock rose. Opened at zero, a loaded
save read every plant as surplus. T is the producer's vanilla
build time (`buildDays`: Fuel Production and Heavy Industry, 120 days each, floored at 30).
Demand is everything `pay` draws (passage, founding cargo, structures, fleets' supplies, colony
upkeep), less refunds (`deposit`, as much of the bill as has not decayed since it was paid: a
wave's cargo back weeks later took unrelated demand with it). It also counts what was asked for
and not paid:
- a held send's bill: the last `canPay`/`pay` that failed. It is booked once a month per send
  (`held`, `bookHold`), as the trip it would have flown that month; a Seeding Swarm books both
  stocks, once a month per source forge, which sails one wave at a time.
- the price of a first chain link or a shortage's answer the stock cannot pay (`heldBuild`, once a
  month per industry - keyed by world, 15 forge-less colonies each booked the same forge). An
  optional build that waits books nothing and notes nothing.
- fleets' supplies left unpaid (`paySupplies`) and colony sustenance left unpaid
  (`ThreatColonyUpkeep.feed`).
- the swarm's bombardment unpaid (rule 4).

With S the stock, P the production a month (`perMonth`), C the output of producers under
construction (size - 2 units each, `comingPerMonth`), D the trailing demand a month and T in
months (4):
- **Runs dry** (`runsDry`): S < (D - P - C) x T. The stock and production over a build time cannot
  cover the demand over it. It is read after 30 days of watching. It is the only shortage the
  planner answers (`mayAnswer`: a plant or a forge, one a stock a month, unchanged), except unpaid
  colony sustenance, which still notes supplies short for 30 days (`noteShort`). A send held for a
  bill that next month's production pays is no shortage. Counting C stops a second plant going up
  on a shortage the first one, still building, will answer.
- **Surplus** (`surplus`): P >= D and S >= D x T, read after T of watching. The **bigger** step adds no
  producer of a stock in surplus.
- **Spare** (`wantsSpare`): S + (P + C - p_max) x T < D x T, i.e. losing the hive's biggest producer
  would run it dry. The redundancy step adds a fuel plant or a forge only then (refineries keep the
  old rule). Never before T of watching; always with structures free or passage off (the old rule).
- **Surplus producer** (`surplusProducer`): the stock is in surplus and P - p_i >= D, its own output
  p_i (`outputOf`) taken off. Retiring it never opens a gap: the stock alone covers T of demand,
  the time a replacement takes.
- **No oscillation.** After a retirement S >= D0 x T and P' >= D0. Runs dry then needs
  D - P' > S / T >= D0, i.e. a demand more than doubled at once. A smaller rise has to drain the
  stock down to (D - P') x T first, and that takes at least a build time. Spare is never wanted while
  the stock is in surplus: S >= D x T contradicts the spare rule whenever P' >= p_max.

**2. Surplus fuel plants are converted** (`ThreatColonyManager.convertSurplus`, `hiveConvertSurplus`,
on). A SHORT_DAYS apart hive-wide (`mayConvert`, the answer's pace), before the planner's sweep, every
fuel plant that is a surplus producer is torn down and its slot built into what the hive lacks
(`conversionFor`) - as many in the turn as the surplus spares, each while the production left still
covers the trailing demand (2026-10-07, after hw60: one a month gave five conversions in 77 months with
2.5M fuel banked at 70-130 months of demand while supplies bound every seeding and campaign). A forge
conversion the stock cannot pay reserves its price (`ThreatFuel.reserve`, the `RESERVE_CONVERSION` key)
so the next turn pays it - see "The shortage's answer is paid first" below. In order:
- a forge, while supplies are not in surplus and the world (size 3+) has none;
- else a missing chain link: Mining on deposits no hive world digs, or a first refinery, or a
  refinery bigger than the hive's largest once that no longer covers the largest metals consumer;
- else a Swarm Bastion (rule 3).

**The shortage's answer is paid first** (`ThreatFuel.reserved` / `free` / `reserve`, 2026-10-07 after
hw60): a forge the planner waits to build (`affordStructure`, while supplies are not in surplus) or to
convert a plant into has its price reserved, and the colonies' sustenance and growth
(`ThreatColonyUpkeep`), a founding (`canFound`) and new trips (`ThreatReach.freeStock`) draw only on the
stock above it. Commitments already made - the standing navy's upkeep (`payNavySupplies`) and the fleets
away (`paySupplies`) - pay from the whole stock: drawn on the free stock in hw61b, 30 swarms (2,672 FP)
were lost to unpaid upkeep for a forge's 5k, which is the one loss the reserve must never cause (hw60:
none). Capped at a month's production; released when
the build is bought, the world lost or the wait planned away (`buyWaitingStructures`). hw60c made 197k
supplies a month and spent 207k with the stock at 0-7k: 72 "waiting build" turns, 176 seedings held on
supplies and 20 of 29 campaign saving lines "not yet kept", while 2.5M fuel and 294k FP sat idle - a
forge's 5k was never in the stock because everything else drew first. The humans' yards-first rule
(`ThreatFactionStock`), the same for the hive.

Candidates:
- Only slot-full worlds: a world with a free slot builds there without tearing anything down.
- Never the last plant or the hive's biggest. The biggest stays so the bigger step has nothing to
  rebuild.
- Never a plant carrying a relic or an AI core: vanilla would drop it.
- Never a world under a front or saturation.
- Ranked forge, link, Bastion; then the bigger world (a forge there makes more, a Bastion keeps
  more); then the plant that makes least.

The new structure is paid before anything comes down: its supplies at its vanilla price and build
time, or the Bastion's fleet points. One that cannot be paid tears nothing down. The vanilla
one-copy rule is checked (no second forge, refinery or mine). Logged `Converted Fuel Production on
X to Y: fuel S covers M months of demand (D/mo trailing, P/mo made, p/mo from this plant); price`.

Forges are never retired. They make the fabrication bank's hulls as well as supplies, and the
invest rule builds them from idle supplies on purpose (ng1b), so a forge retired would only be
rebuilt. A size-8 plant makes 6 units x 1,500 = 9,000 fuel a month. ng5a's 82k a month against ~30k
spent is about five plants' surplus, one converted a month while the stock covers four months of
demand.

**Fuel tight, supplies idle: a fuel plant before the forge** (2026-10-03, runs hw4n and hw4o;
`threatinc_investFuelWhenTight` on, `threatinc_investFuelMinPlants` 2). Because forges never retire, the invest rule
could lock the hive out of fuel. In hw4n, fuel sat at `holds, wants a spare` for months: tight (`ThreatFuel.wantsSpare`,
losing the biggest plant would run it dry) but not running dry, so no shortage answer came. The spare step comes after
the invest step and stops at the redundancy target (4-6 plants against 6 hive systems), so while supplies piled up the
invest step put a forge in every free slot. hw4n stalled at month 60-72: 18 -> 21 hives (other runs 32-39), forges
stuck at 21, 64k -> 209k supplies, and no landing for two years. Now the planner builds a fuel plant (`fuel tight`)
ahead of the forge, on a world that lacks one, while fuel is tight and supplies pay for the plant and a founding kit.
It builds one a month, paced with the shortage answers (`ThreatFuel.mayInvest`), and only once the hive has two plants.
With one plant, losing the biggest always runs fuel dry: in hw4o the first version put a fuel plant on a home world at
month 24, where hw4n built a forge (`war-sim-real-runs.md` 18). The simulator (30 seeds, 7 runs, at 5 troops a FP)
never stalls like hw4n, so it cannot judge the case the rule targets. The one-plant version cut the median forges at
month 36 from 4.5 to 4 and raised the 10th-percentile hive count at month 120 from 116-146 to 131-176. The two-plant
rule is as without the step (inside 2,477 against 2,471, worlds lost unchanged). Neither touches hw4g's stall, where
supplies never reached a forge's price.

**3. The hive's military tier** (`SwarmBastion`, `hiveMilitaryTier`, on). These are two slot-using
industries.csv rows on one class: the Swarm Bastion (Military Base analogue, needs the Swarm Nexus,
`industry, unraidable, tactical_bombardment`, vanilla's military base icon) and its upgrade,
Swarm Command (High Command analogue, grown in place by `startUpgrading`). The player can never build
either, as with the other hive rows. Vanilla's Military Base and High Command do nothing for a hive:
patrols it does not run, commodity demand it cannot meet.
- **Garrison floor.** `garrisonReserve` = the base reserve (half the hull-scaled size table, at least
  one) x (1 + tier): doubled under a Bastion, tripled under Swarm Command. The posture minimum and
  base, the launch reserve, raiders and the board's target all read it, so the swarms it adds are
  fabricated from the bank, stay home whatever the posture wants, and then pay the 4%/month FP
  upkeep: the ongoing sink. Vanilla's own multiples are steeper (a Patrol HQ's 2 patrols against a
  Military Base's 6 and a High Command's 8 at size 6); the hive's reserve doubles and triples.
  At size 6-8 that is 2 more of the table's rows a step, about 950 FP of HIGH swarms at the
  default costs, so roughly 38 FP a month of upkeep a step. The floor counts while the structure
  stands: built, or a Bastion upgrading. Disruption wears its defence bonus, not its floor.
- **Defence.** Vanilla's own figures (`MilitaryBase.DEFENSE_BONUS_MILITARY` 0.2, `_COMMAND` 0.3)
  multiply the ground defence, worn by disruption like the Nexus's (`disruptedDefenseResilience`).
  It is in `Theatre.HIVE`'s key structures (a holding front suppresses it) and fortifications
  (orbit wears it; it is no battery and does not fire), and in `hiveFortificationBonus`.
- **Price.** The vanilla structure's build cost in supplies, converted at what a fleet point costs
  in supplies (`ThreatBuildCost.fleetPoints`: `expeditionSuppliesPerPoint` for
  `FP_PER_RESPONSE_DIFFICULTY` points, 30 for 25, 1.2 a point - what a faction's expedition draws to
  field one). Military Base 4,500 supplies = 3,750 FP; High Command 1,500 = 1,250 FP. It is paid
  from the colony's bank, its system's topping it up (`poolSystemBanks`, logged as `Swarm Bastion bill
  at ...`), over vanilla's build time of 120 days each.
- **When** (`militaryIdle`). The colony's garrison holds its want; its income carries the upkeep of
  that garrison plus the swarms the tier adds; and its pooled banks hold the price, those swarms
  (`militaryExtraFP`) and that upkeep over the build time. No number of its own. At size 8, holding
  2,000 FP, a Bastion needs about 3,750 + 956 + 118 x 4 = ~5,200 FP banked.
- **Where.** In a slot freed by a conversion (rule 2), or in a free slot when the colony's planner
  turn built and wants nothing, unless a stock is wanted (`ThreatFuel.wanted`) whose producer the
  world lacks: no forge while supplies are wanted, no fuel plant while fuel is. The first draft held
  the slot while either stock was wanted anywhere; under size upkeep supplies are short nearly
  always, and h40a banked 98k FP with no Bastion built. Swarm Command needs no slot. `maintainMilitaryTier` runs from the monthly sweep, after the planner; one structure a
  colony a tick still holds.
- **Retirement** (`retireMilitary`, user's call 2026-10-01). The slot goes back to production
  when the hive needs a producer and no world has a free slot to build it in. A need is a chain
  link the hive has none of, or a stock's answer (`mayAnswer`: it runs dry). Once a month, after
  the conversion, a standing Bastion or Command is torn down for it: a Bastion before a Command,
  then the bigger world. The producer is bought first; a refusal tears nothing down and books its
  price as demand (`heldBuild`). It takes its vanilla build time, as the Bastion took its own, so
  neither swap is instant, and the stock counts it as coming (`comingPerMonth`) while it builds.
  A structure still growing or upgrading stays, as does one under a front or saturation. Nothing
  of its price comes back; the swarms it kept home stay, free to launch. Logged `Hive planner:
  Swarm Bastion at X retired for <industry> (<reason>)`.
- **Readouts.** The colony screen shows the industries.csv description, "Defense Swarms kept at
  home: N" and vanilla's ground-defence line. The planner logs `Hive planner: Swarm Bastion at X for
  N FP, R swarms home once it stands (F FP more)`.

**4. The swarm pays its bombardment fuel** (`threatPaysOrdnance`, on, with `threatPaysPassage`).
`ThreatGroundFronts.payOrdnance` and `ordnanceAvailable` for the Threat side read and draw the hive's
fuel stock. That is the tactical day at `bombardFuelPerFPDay` (0.04 a FP: a 1,000 FP Defend fleet
burns 1,200 a month), paid by the Defend and Support slices. `ThreatStrikeFGI.saturationPass` pays
the saturation pour at `satFuelPerFPDay` (2.86 a FP a day, up to what the human colony's razing
needs) - the rates humans pay.
- Short of a tactical day, a live slice bombards the share it can pay.
- With nothing in stock the swarm does not bombard. A Defend fleet reads its orbit as done
  (`orbitDoneFor`): it fabricates troops if its front cannot hold, else it idles. A strike's
  saturation pass holds, and an unspawned strike keeps its one go.
- What went unpaid is unmet demand (`ThreatFuel.unmet`, `groundedOrdnance`: one day a fleet a day).
  A razing an empty stock holds is booked as a held send.
- Human-side ordnance is unchanged.
- The strike's own tactical slices pay too (`ThreatStrikeFGI.siegePass`, `harassPass`, through
  `payOrdnance`), and so does its abstract siege (`abstractSiege`, the stock as its fuel budget).
  A siege whose stock will not buy half a day counts orbit as done, as an abstract siege and every
  human besieger do: the troops land on what orbit left if they can hold, else the fleet stands
  over the world. A harassment with an empty stock spends its visit on nothing. An abstract siege
  that ran dry books the days it had left at its surviving strength as unmet demand.

**Readouts.** After each census a `Hive stock plan:` line for each stock: stock, trailing demand,
production (+ building), the months the stock covers, and the verdict (runs dry / surplus / holds,
wants a spare, still watching).

**New game, ng7a (2026-10-01, 3 chunks).** The hive landed on 6 worlds and held 11 at the end.
- About a year in, Gamma Sonora I raised a Swarm Bastion (3,749 FP, 2,873 of it pooled from four
  sister banks), then a Swarm Command three months later (1,250 FP). Early on no stock reads
  wanted, because demand is still being watched. Fleets still grew from 4.9k to 13.7k FP.
- Supplies ran at the margin throughout: 62 in stock at the end, 29k made and 31k spent a month.
- Fuel banked to 129k with one plant. No conversion, since that plant was the last; no second
  plant, since the stock was in surplus. Then one strike paid ~55k for its passage: 4,041 FP at
  Yama, 34 ly, five months of output. The plan turned from surplus to runs dry, wants a spare.

**Loaded save, h42a (2026-10-01, the lt save, 18 months, after the review fixes).**
- The window now opens at production. The first fuel reading was 64k a month (h40a: 717k),
  easing to 24k by month 5.
- The two fuel plants for a shortage came in months 7 and 9, after the stock fell from 98k to
  16k. Two forges for supplies came while the stock sat near zero against ~90k a month of demand.
- 16 Swarm Bastions and 14 Commands went up from month 6, about 78k FP: the idle FP's outlet.
  Fleets still grew from 58.5k to 75.7k FP, with 32.8k banked at the end.

**Settings:** `hiveConvertSurplus`, `hiveMilitaryTier`, `threatPaysOrdnance` (all true). Every other
number is derived (build times, vanilla prices, the expedition supplies rate, the garrison's own
rows and upkeep, the 30-day planner pace that was already there). Persistent state is primitives in
`threatinc_hiveFuel` (`demand_`, `demandDays_`, `demandAt_`, `demandSince_` per stock, `convertedAt`)
and `threatinc_hiveHeld` (send -> when booked, pruned monthly).

## Levers, verified

| Lever | Works on the hive? | Why |
| --- | --- | --- |
| Kill or disrupt a producer | yes | everyone falls back to the next-best source, or none; with redundancy that is N targets, and `ThreatMissionIntel.networkImpact` scales its lever by `1 + 2/providers` |
| Starve a producer's inputs (bomb the volatiles mine) | yes | its output drops and every importer sees it |
| Disrupt the Fabrication Core or Nexus | yes | machinery to 0 locally; hulls demand unmet; seen in vanilla's own tooltip as "0x" machinery |
| Disrupt the port | yes, by the mod's rule above: a trickle, not a cut | vanilla alone: no, cap stays at 5+ units |
| Piracy, hostility, other accessibility maluses | no | same 5-unit floor; cosmetic for the hive |
| Raid a forge (size upkeep) | yes | its output leaves the stock: growth stops hive-wide at once, worlds starve once the stock is spent |
| Blockade a hive world (size upkeep) | yes | its imports cut half or all and its shipping held at the port trickle or nothing; a forge world keeps its own output |
| Cut its fuel (billed reach) | slowly | the fleets fly on the banked stock, wherever it was made; the hive is grounded only once the stock is spent |
| Cut its supplies (billed reach) | yes | the spare goes first: with nothing spare after sustenance, no strike, wave, scout or raid launches |

## What the war board shows because of this

The Supply column is icons only: what each system makes. Shares of a hive total and "largest
producer" framing were removed - a share of a total says nothing about who feeds whom when
one producer feeds everyone. `HiveSupply` keeps the demand totals only, to dim an output no
hive world wants.
**Arming reads the hive, not the world (2026-09-28).** The 0.6.1 gate (defensesAffordable) read metals
on the world's own market, but vanilla imports only up to demand: a world with no batteries demands
no metals and shows none, however much the hive's refineries make. In the IWBomb2 save 35 of 41
hives never armed and only the 6 that make metals themselves had Heavy Batteries (0 Ground Defenses),
so hive return fire was always 0. The gate now also counts the hive's largest producer
(hiveMaxSupply), the source the world would draw from. With no refinery anywhere that is still 0, so
the size-3 stall stays fixed; once one exists every world of size 3+ arms on the next tick.
