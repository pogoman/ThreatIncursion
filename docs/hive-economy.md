# The hive economy is vanilla's - and vanilla's is not a flow

Facts about the Starsector economy the hive runs on, established Sept 2026 while asking
why the whole hive had one fuel plant and no fuel shortages. Verified against the API jar
(`javap` on `Misc.getShippingCapacity`, the industry classes, `Spaceport.apply`), vanilla
`settings.json`, and the in-game commodity tooltip, which states the delivered-quantity rule
in so many words: "Only the highest local source of supply is used".

## What is vanilla and what is ours

Everything that decides how much of a commodity a hive world has is vanilla, untouched:
industry supply and demand figures, mining deposits, the closed econ group (`setEconGroup`,
a vanilla feature), best-single-source availability, and shipping capacity from
accessibility. The mod never moves a unit of anything.

What the mod adds, all through vanilla's own surfaces:

| Ours | Mechanism | Why |
| --- | --- | --- |
| Fabrication Core supplies heavy machinery at colony size | a real industry declaring supply | the vanilla chain is machinery-negative in every configuration; population-as-machinery makes it acyclic so a growth gate can never deadlock |
| Swarm Nexus demands ships at size and machinery at size-2, idle at full garrison | a real industry declaring demand | the hive's fleets are its military consumption; vanilla's Military Base demand shape |
| Population demands suppressed | `demandReductionFromOther`, the admin-skill mechanism | machines eat no food, drugs, luxury goods |
| Core-distance accessibility penalty refunded | one flat accessibility modifier, `applyHiveAccessibility` | the hive never trades through the Core; the penalty models a supply line it lacks |
| Disrupted port capped at a few shipping units | one flat accessibility modifier, `applyPortDisruption` | vanilla keeps a disrupted port flagged as present, so it barely moves shipping; the hive's one route is its port (below) |

Everything else the mod does with the economy is a *reader*: vitality averages how well the
growth inputs (`CORE_INPUTS`) are fed; fuel reach is fuel available x `strikeLYPerFuel`;
hull availability scales the garrison through vanilla's own ship-deficit multiplier; strike
staging needs fuel and hulls available. And the planner (`planHiveEconomy`) only decides
which vanilla industry to build where. No refactor is needed to "get back to vanilla" - the
model never left it; what drifted was the prose around it, corrected Sept 2026.

## Availability is a broadcast, not a pool

For each commodity at each market, vanilla asks "what is the largest quantity I can reach?"
and takes the best of local production, the biggest in-faction exporter, and the biggest
global exporter (each limited by shipping capacity, below). That is `getAvailable()`.
**Demand elsewhere never subtracts from it.** One refinery producing 5 metals makes 5 metals
available to ten colonies demanding 4 each - all of them 100% fed - and to the fiftieth.

Consequences the mod builds on:

- A deficit only ever comes from **size mismatch**: a consumer bigger than the best source
  (Refining wants ore at size+2 against a mine at size plus deposit; a Nexus demands hulls at
  size against a forge supplying size-2), or a source starved of its own inputs
  (its output drops by its largest input deficit, and every importer sees the drop).
- A **second producer of equal size adds nothing** until the first is lost. Redundancy is
  insurance against the player, not extra supply. Only a *bigger* producer raises what any
  consumer can draw.
- `ThreatColonyManager.planHiveEconomy` therefore builds every chain link once, then spare
  copies spread across systems (`threatinc_chainRedundancy`, one per held system, every
  link at two before any at three), then a bigger copy wherever the hive's largest consumer
  of an output outgrows its largest producer. The old `refineries >= forges` ratio rested on
  the flow misconception. `maintainHiveEconomy` re-plans size-capped colonies with a free
  slot every tick, so an existing hive fills in its redundancy without waiting for growth
  steps that never come.
- Reach is `fuelRangeLY = strikeLYPerFuel x min(fuel available, expeditionFuelCapacity)`.
  Fuel available is the biggest fuel plant's output at every colony, so every developed
  system sees the same figure (25 ly = 5 units x 5); the capacity term is
  `threatinc_reachFuelCarry` (4) times the colony's fleet-size figure - vanilla's own
  `Stats.COMBAT_FLEET_SIZE_MULT` untouched for faction and player worlds (colony size 0.5 at
  size 3 to 1.75 at size 8, times doctrine, hull shortage, stability, alpha core, skills), and
  vitality x size / 4 for hive worlds - so a healthy size-4 hive world reaches 20 ly, a size-2
  foothold 10, a size-8 world the full fuel-bound 25, and a besieged world less as its vitality
  falls. Fuel rises only by growing the fuel world or feeding its inputs. Hive ports
  are Spaceports, never Megaports (retired Sept 2026, `ensureSpaceport` migrates old saves):
  a Spaceport wants fuel at size-2, exactly what a same-size plant makes, where a Megaport
  wanted it at size and put a permanent fuel shortage on every card in exchange for an
  accessibility bonus the hive could not use.

## Vanilla industry numbers (javap on the API jar, 0.98a-RC8)

| Industry | Demands | Supplies |
| --- | --- | --- |
| Mining | machinery size-3, drugs size | each deposit at size + modifier (sparse/trace -1, moderate/diffuse 0, abundant +1, rich/plentiful +2, ultrarich +3) |
| Refining | ore size+2, rare ore size, machinery size-2 | metals **size**, rare metals size-2 |
| Heavy Industry / Orbital Works | metals size, rare metals size-2 | ships, machinery, supplies, weapons size-2 |
| Fuel Production | volatiles size, machinery size-2 | fuel size-2 |
| Spaceport / Megaport | fuel, supplies, ships size-2 (Megaport: size) | crew |
| Swarm Nexus (mod) | ships size, machinery size-2 | - |
| Fabrication Core (mod) | - | machinery scaled with size |

So at equal sizes the growth chain balances (metals at size feeds a forge at size; a
moderate mine at size feeds rare ore/volatiles at size; ore at size+2 needs a rich deposit)
and so does fuel now that hive ports are Spaceports (fuel wanted at size-2, made at size-2).
Hulls stay short by design: the Nexus wants N at size N against N-2 from the best forge, and
that gap is what scales the garrison through the ship-deficit multiplier. Every vanilla
population good (food, domestic
goods, drugs, supplies, ...) is 100% short on every hive world - `RELEVANT` hides them on the
board and `CORE_INPUTS` keeps them out of vitality on purpose. The hive is Chicomoztoc with
the pantry hidden, not a colony without shortages.

## Home relics

Vanilla's chain only balances at the top with Domain items: a plain size-8 forge makes 6 hulls
against a Swarm Nexus wanting 8 (the Nexus demand shape is the mod's, kept on purpose - it is
what scales the garrison), Refining wants ore at size+2 which only a rich deposit or a Mantle
Bore covers, and so on. `ThreatColonyManager.maintainHomeRelics` (setting `threatinc_homeRelics`)
equips the first hive system's industries the way vanilla's big colonies are equipped, and
only where its own deposits leave a link short: a Pristine Nanoforge on the home system's
largest forge (+3 hulls, so 9 against the Nexus's 8 - a fed home forge fills every garrison in
the hive), Mantle Bore or Plasma Dynamo on a mine below a rich deposit, Catalytic Core on a
refinery short of ore, Synchrotron on a fuel plant short of volatiles. Every other forge in the
hive rolls once, by its market id, for a Corrupted Nanoforge (`threatinc_forgeNanoforgeChance`,
default 0.2). Each item is installed only where vanilla's own requirements
(`ItemEffectsRepo.ITEM_EFFECTS...getUnmetRequirements`) are met, so nothing sits inert. Because
availability is best-single-source, an item on the home system lifts every hive world that
draws on it - and the items are the loot for taking the home hive. Added Sept 2026 after the user saw "Swarms 3/3" on a size-8 world that vanilla's
arithmetic caps at 3 of 5; the card now reads "3/3 of 5" in red when the hull shortage is
what sets the cap.

## The monthly lag, and the flush

Vanilla recomputes what each market can draw from the others on its own monthly economy
step; an industry's local supply updates the moment it is reapplied. So right after the tick
installs a relic or builds a plant, the producing world reads the new figure and every
importer still reads the old one for up to a month - seen Sept 2026 as Gamma Sar III at 24 ly
(6 fuel, its own newly fed plant) beside siblings at 20 ly (5, the old import), and the home
forge at 9 hulls with its siblings' garrisons still capped by 6. `ThreatColonyManager.
markEconomyDirty` is set by every structural change the tick makes (industry built, port
swapped, relic installed) and `flushEconomy` at the end of the tick runs vanilla's
`EconomyAPI.tripleStep` - the full recompute sector generation uses - once, so the board and
the planner see one consistent economy. Disruption-driven changes (a port cut, a relic's host
disrupted) still ride vanilla's own cadence.

## Shipping capacity - what accessibility actually does

`Misc.getShippingCapacity(market, inFaction)`:

    capacity (units) = max(0, (accessibility [+ 0.5 if same faction]) / 0.1)

i.e. in-faction shipping is **`10 x accessibility + 5` units**, no market-size term. It caps
what a market can import (and export) of each commodity; it is a ceiling, not a multiplier.
At 100% accessibility that is 15 units, at 0% still 5; it reaches zero at -50%. Confirmed
in-game on Jannow: 63% accessibility, tooltip "Same-faction imports and exports limited to
11 units", "Other imports limited to 6". Since no hive producer makes more than 6 units
(size-2 at size 8), nothing about the hive's shipping bites until accessibility is well
below zero.

Why a Core colony feels accessibility and the hive does not: a fresh player colony imports
everything through the *global* path, whose cap has no +0.5 term (30% accessibility = 3
units of everything, shortages across the board). The hive is a closed econ group and only
ever uses the same-faction column.

Vanilla's distance penalty is 1.0 per 50 ly from the sector economy's centre of mass
(`accessibilityDistFromCOM`) - distance from the Core, not from the hive - and
`applyHiveAccessibility` refunds it. The same-faction proximity term is only ever a bonus.
So a hive colony's accessibility never falls with its distance from the rest of the hive,
and reach cannot decay with distance. Prose that said an isolated seed "starves" or "is
grounded" described an effect the model never produced; corrected Sept 2026.

## The disrupted-port rule

Tested in-game (Jannow, Spaceport disrupted 21 days): the accessibility breakdown showed no
-100% "No spaceport" line, only the port's own bonus gone. `javap` on `Spaceport.apply`
explains it: when the port is non-functional vanilla clears its supply and calls `unapply`,
then **re-asserts `setHasSpaceport(true)`** - a disrupted port deliberately still counts as a
port. Right for a Core world with other ports and the open market; for the hive, whose one
route for everything is the port, it meant "disrupt the Megaport" was a fiction the mod's
own text promised.

`ThreatColonyManager.applyPortDisruption` (setting `threatinc_disruptedPortShipping`, default
3, 0 = nothing docks, -1 = vanilla): while a hive world's Megaport or Spaceport is disrupted, a
flat modifier holds its accessibility where `Misc.getShippingCapacity` gives that many
same-faction units (3 units = -15%, computed from vanilla's `SAME_FACTION_BONUS` and
`PER_UNIT_SHIPPING`). Computed against the stat's current value, not a fixed -1, because the
Core-distance refund plus proximity can keep a well placed colony above 0% even after -100%.
Effects, all through vanilla: the world's imports drop to the trickle (a size-8 forge fed 3
of 8 metals, growth stalled, supply factor around 0.7 with machinery still local), a fuel
importer reads 15 ly, and its exports reach the hive only as the same trickle (the hive falls
back to its next-best source - the point of redundancy). Lifted at the next poll after the
port recovers.

The first version zeroed shipping (-50%). Rejected in play: the siege raids the Nexus, then
the Core, then the port, and with the Core down a colony imports its machinery, so zero
shipping on top took supply to 0 and the decline rate to its 3x cap - a port cut deadlier than
a Core cut. Vitality is fabrication x supply; the Core is the kill, the port slows.

## Levers, verified

| Lever | Works on the hive? | Why |
| --- | --- | --- |
| Kill or disrupt a producer | yes | everyone falls back to the next-best source, or none; with redundancy that is N targets, and `ThreatMissionIntel.networkImpact` scales its lever by `1 + 2/providers` |
| Starve a producer's inputs (bomb the volatiles mine) | yes | its output drops and every importer sees it |
| Disrupt the Fabrication Core or Nexus | yes | machinery to 0 locally; hulls demand unmet; seen in vanilla's own tooltip as "0x" machinery |
| Disrupt the port | yes, by the mod's rule above: a trickle, not a cut | vanilla alone: no, cap stays at 5+ units |
| Piracy, hostility, other accessibility maluses | no | same 5-unit floor; cosmetic for the hive |

## What the war board shows because of this

The Supply column is icons only: what each system makes. Shares of a hive total and "largest
producer" framing were removed - a share of a total says nothing about who feeds whom when
one producer feeds everyone. `HiveSupply` keeps the demand totals only, to dim an output no
hive world wants.
