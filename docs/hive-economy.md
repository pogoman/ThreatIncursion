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
growth inputs (`CORE_INPUTS`) are fed (the legacy growth path only, size upkeep off); the
factions' fuel reach is fuel available x `strikeLYPerFuel` (the hive's is its bill, `ThreatReach`);
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
  copies spread across systems (one per held system, `redundancyTarget`; bounded only by free
  industry slots, `Misc.getMaxIndustries` - 2026-09-29: the `threatinc_chainRedundancy` knob, 3,
  is gone, and with it "every link at two before any at three"), then a bigger copy wherever the hive's largest consumer
  of an output outgrows its largest producer. The old `refineries >= forges` ratio rested on
  the flow misconception. `maintainHiveEconomy` re-plans size-capped colonies with a free
  slot every tick, so an existing hive fills in its redundancy without waiting for growth
  steps that never come.
- The hive's reach is its bill (`billedReach`, 2026-09-30): no radius, see "Reach is the bill"
  below. The old rule - the factions' still, and the hive's with `billedReach` off - is
  `fuelRangeLY = strikeLYPerFuel x min(fuel available, expeditionFuelCapacity)`.
  Fuel available is the biggest fuel plant's output at every colony, so every developed
  system sees the same figure (20 ly = 5 units x 4); the capacity term is
  `threatinc_reachFuelCarry` (4) times the colony's fleet-size figure - vanilla's own
  `Stats.COMBAT_FLEET_SIZE_MULT` untouched for faction and player worlds (colony size 0.5 at
  size 3 to 1.75 at size 8, times doctrine, hull shortage, stability, alpha core, skills), and
  vitality x size / 4 for hive worlds - so a healthy size-4 hive world reaches 16 ly, a size-2
  foothold 8, a size-8 world the full fuel-bound 20, and a besieged world less as its vitality
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

## The hive does not build guns it cannot feed - the 0.6.0 freeze

Found 2026-09-09 in a 467-day save whose home hive was still five size-3 worlds, and
deterministic for every save started on 0.4.0 through 0.6.0: the board read `Vitality 50%`,
and 50% is not a coincidence.

The arithmetic. A colony has ONE industry slot until size 4 (vanilla's `maxIndustries`
table, the rule a player colony lives by), and the planner puts Mining in it. At size 3 it
added Ground Defenses - a structure, no slot - which demands machinery and metals at
`size - 2` = 1 each. `computeSupplyMult` averages `min(1, available/demand)` over every
growth input the colony demands: machinery 1.0 off the Fabrication Core, metals 0.0 because
no refinery existed and none could - the only slot was Mining's - and ore, volatiles, rare
ore not counted, having no consumer. Mean exactly **0.5**, `growthMultFor`'s stall value, a
hard zero. No growth, so never size 4, so never a second slot, so never a refinery, so never
metals. Re-planning cannot help: the planner has nowhere to build. The ordering and the
demand shape date from v0.2.0; v0.4.0's health-scaled growth made the stall permanent.

The rule now (the user's, 2026-09-09): **the hive arms once it can pay.** `defensesAffordable`
gates Ground Defenses and the Heavy Batteries upgrade on the machinery and metals they would
demand being at least half-met (`STALL_MET_FRACTION`, the same bar a growth input must
clear), so building them can never be what stalls the world. A bare mining world grows to 4,
builds its refinery on that growth step, metals appear, and the batteries follow on the next
planner call. Cutting the hive's metals later still stalls every world whose batteries or
forge want them - that lever is untouched.

Saves already frozen are healed on load (`healStalledBootstrap`, from the manager's first
poll). The fingerprint - a hive with no refinery anywhere, and size-3+ worlds carrying
batteries that stored a health on the stall floor with their only slot full - cannot arise
under the new rule. Each such world is grown the one size the freeze cost it, then the chain
is stood up to a fixed point the way the instant war does, so the hive is fed the moment the
save opens. The tick sweep also re-plans stalled colonies with a free slot now, not only
capped ones. The debug lever Hive Floor Size (`ThreatDebugWar.pollFloor`) grows every hive
colony to a set size through the same growth step, for catching a save up further.

Why testing missed it: the harness saves come up through Instant War, which founds the home
chain at size 6 and plans it to completion before the first tick. The real seeding path
founds at size 1 and had never been walked to size 4 under the health-gated growth.

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

## Fabrication bank - the Threat's closed economy (2026-09-29, built, untested)

Vanilla's hull availability is a rate the whole econ group reads at once and nothing consumes
(above: "Availability is a broadcast"). So the swarm's fleets used to come free, one per
`garrisonRespawnDays` at every colony. Now every Threat fleet is paid for out of a bank of fleet
points, and the bank is filled by what the hive's forges really make.

- **Bank per colony** (`ThreatColonyManager`: `fabBank`, `bankedFP`, `fpBank`, `drawFP`,
  `chargeFP`, `creditFP`, `canAffordFP`; saved under `threatinc_fabBank`). It may dip below 0:
  a fleet built on the last of it.
- **Income.** The hive's forges' SHIPS output (`forgeOutput`: vanilla's `getMaxSupply` of ships,
  capped by what the hive structurally has of it; 0 with the forge down) x `fabFPPerShipUnit`
  (100) fleet points per 30 days, split between the nexuses by the hulls each draws
  (`nexusDraw`, `fabricationRatePerDay`; never more than a nexus draws). More nexuses split the
  same hulls; only more forges raise the total. Nothing is banked while the Fabrication Core or
  the Swarm Nexus is down, or no forge reaches the colony. The alarm no longer speeds it.
  Sized so a mid-war hive (a forge per held system) earns about what the old timer gave: ~160 FP
  a month for a size-4 world, 240 at 6, 320 at 8.
- **What is charged.** Every fleet the bank fabricates, at its actual FP: each garrison swarm
  (`maintainGarrisons`: the bank must hold `swarmCostEstimate`, a running mean of what that
  tier/fabricator spec came out at, else 46 / 134 / 347 / 458 FP by tier plus 40 a fabricator),
  a Scouting Swarm (`ThreatSwarmScouts.launch`), and a colonization wave's upsize over the swarm
  it is made of, plus the structures it founds with ("Paid founding" below). A recycled weak swarm gives its hulls back. No bank, no build.
- **No pace timer.** `garrisonRespawnDays` is gone: the bank is the bound, at one swarm per
  colony per poll (about half a day), so a large bank comes out over days, never as a frame of
  fleets. The size table (`desiredGarrison`) is a floor the nexus rebuilds first, not a
  ceiling: past it the nexus keeps building for as long as production pays, taking the
  table's rows in turn (counted in swarms). Reinforcement (`redistributeGarrisons`) runs every
  pairing each poll until the garrisons balance; a swarm arriving at a full table is seated,
  never despawned (`absorbSurplus` is deleted).
- **Grown garrison fleets.** Once every row of the size table is held (`fit >= table.length`), a
  new swarm's hulls join a standing garrison fleet of its spec with room for them, up to
  `maxShipsInAIFleet` (`growGarrisonFleet`, host from `garrisonHostFor`; the count rides the
  fleet in `SWARM_COUNT_KEY`, `swarmCount`). Only a swarm no fleet has room for takes orbit as a
  fleet of its own. The same hulls at the same price - fewer, fuller fleets (it was a fleet a
  swarm: dozens to hundreds over a rich hive). Garrison counts on the board are fleets. Musters
  count fleets (`musterFrom` takes the largest fleets first) and yield one expedition size per
  swarm a fleet embodies, so a grown fleet re-embodies as every swarm in it
  (`ThreatFrontlines.strikeOf` weighs it as its strength x `swarmCount`).
- **Strikes fly packed.** A strike's swarms (one expedition size each) are packed into as few
  fleets as `maxShipsInAIFleet` allows (`ThreatStrikeFGI.pack`: biggest first, each into the
  first fleet with room by `shipsEstimate` - 7 / 11 / 27 / 26 ships at LOW / MEDIUM / HIGH /
  MAXIMUM plus its fabricators, 0 at NONE, which is the first `FabricatorEscortStrength` ordinal;
  an earlier build indexed the table without NONE and mis-sized every tier, fixed 2026-09-29, so a
  LOW swarm packs about four to a fleet and a MEDIUM two; the pack list is kept in `packs`). `params.fleetSizes` entries are
  fleet totals (`packSize`), so strength is unchanged while the count - `expeditionPasses`, the
  board's `preparingStrikeFleetCount` - is the real fleets'. A swarm a fleet has no room for at
  spawn goes to `overflow` and is placed as an extra fleet. `estimateFP` is worked swarm by swarm
  (a packed entry is no swarm's size). Older strikes are a fleet a swarm (`packs` null).
- **Learned costs are keyed by job.** `fabCosts` is `"tier:fabricators"` for a garrison swarm - the
  key every existing save carries - and `"job/tier:fabricators"` for other fleets
  (`costKey`, `ThreatFleetComposer.JOB_*`); a job with no history falls back to the garrison's.
- **Strikes are booked on the bank.** A strike's swarms are re-embodied at expedition size,
  which can weigh more than the swarms that left. `launchStrike` charges the staging colony's
  bank that excess and musters fewer swarms if it cannot pay (`Strike from ... held: the bank
  ... cannot re-embody even one swarm`), never a strike the bank cannot pay. `ThreatStrikeFGI`
  carries the ledger fields (`ledgerHome`, `ledgerPaid`, `ledgerBuilt`, `ledgerSpawned`,
  `ledgerClosed`) and settles the spawn against what the fleets really weigh. A relief strike
  goes to its front alone.
- **Homecoming.** A fleet the bank paid for is bound to it (`bindToLedger`, memory
  `$threatinc_ledgerHome`; a `LedgerReturn` listener). When it leaves the sector by any road but
  battle, its fleet points go back to the paying colony's bank (`settleLedger`, once per
  fleet); the colony gone, to the nearest live colony (`creditHome`). A swarm that joined a
  garrison is the garrison's and is not credited. Strike fleets, withdrawn waves and Scouting
  Swarms (`ThreatSwarmScouts.launch` charges the bank and binds the scout, so what survives
  re-banks when it fades out at home) come home this way. A dead colony's garrison (it withdraws)
  and the stray raiders of a dead colony are bound to the nearest live colony's bank
  (`bindStrayToNearest`, which drops the garrison flag) and credited when they leave the sector,
  not up front - a swarm shot down on its way out is lost. Only a reinforcement disbanded because
  its colony is gone is credited on the spot (`creditHome(null, ...)`, then despawned). A swarm
  Defend fleet whose front is gone is bound from spawn and re-banks on despawn by any road. Losses
  in battle are the real cost.
- **Stillborn strikes.** A strike broken in preparation - `IncursionManager.abortStrikesFrom`: a
  raid on the forge or the Swarm Nexus, a bombardment, the colony's destruction - is stillborn
  (`ThreatStrikeFGI.markStillborn`): what it mustered and paid is forfeit, its fleets are unbound
  before the abort sends them off, and `notifyEnding` credits nothing ("Strike ledger: stillborn").
  Before, it was re-banked in full, so breaking the forge only delayed the strike. A strike in
  flight is autonomous and unaffected.
- **Endowment.** A save from before the ledger is endowed once with `FAB_ENDOWMENT_DAYS` (180) of
  each colony's income (`endowSave`, "Fabrication ledger opened"); a seed from the Abyss lands
  with 180 days of a full nexus's income at its size (`endowSeed`). A new colony's clock starts at
  first sight and does not back-pay. The save endowment is deferred per colony
  (`threatinc_fabEndowPending`): each colony waits for the first poll at which its fabrication
  rate is above 0, so a colony whose economy has not yet recomputed is not endowed nothing.
- **What else is unbounded now (2026-09-29, no arbitrary caps; forges, swarms and fuel are
  the bounds).** Colonies grew to 10 (`HIVE_MAX_SIZE`, vanilla's `population_10`) until
  2026-09-30, when `colonyMaxSize` (8) came back (user's call): at month 62 of h26a, 27 of ~46
  hives were size 10 and razing one cost ~1M fuel. `pinMaxSize` holds the cap, and a hive over it
  loses a size a poll (`ThreatFrontlines.shrink`). A colonization wave per unclaimed planet
  (`tryExpandInSystem`), and a pending claim per free forge (`IncursionManager`); waves fly in
  parallel, each paid for by a mustered swarm. `pickChainPlanets` is no longer cut at five.
  One spare production link per held system. No scout limits. Raiders detach swarms until the
  pack outweighs the convoy by 1.5x. `maxInfestedSystems` is gone. Strikes pool the whole
  system's colonies for the muster (`garrisonAvailableForLaunch`). Siege sizing follows the same
  rule for the NPC side: `sectorPayableFP` (the resource bound `razeFleetPoints` searches up to)
  treats the player's part as 0 when the capacity ledger is off, instead of going unbounded and
  dropping the NPC supplies bound; and `SIEGE_FLEETS_SANITY` (100,000 fleets) means "cannot be
  done" - a goal past it is not grown toward (logged, "cannot be done"), not clamped into a
  100,000-fleet list. See docs/strategy-layer.md "No cap on the flotilla".

### Paid founding (2026-09-29, built, untested)

A Seeding Swarm used to dig in as its new colony's first garrison, so a founding moved fleet
points from one bank's garrison to another's and the colony itself came free: hives went 36 to 90.
Now founding is paid, not transferred.

- **The wave.** A seeding wave still consumes one mustered Defense Swarm, and the source's bank pays
  the difference to the wave's size (`launchColonizationWave`).
- **The structures.** Charged at launch, so an unaffordable founding never sails:
  `threatinc_foundingFPPerStructure` (150) each - 4 core structures (Population, Spaceport,
  Fabrication Core, Swarm Nexus, `FOUNDING_CORE_STRUCTURES`), +1 where the planet has mining
  deposits (`foundingStructuresEstimate`). The bill and the wave's source ride the fleet
  (`FOUNDING_FP_KEY`, `FOUNDING_SOURCE_KEY`); the key's presence marks a wave launched under these
  rules.
- **Pooled bill.** The bill is paid from the source's bank topped up from the system's other
  colonies (`poolSystemBanks`; `launchColonizationWave` returns false, nothing sails, if the pool
  cannot pay). A sibling gives only what it has banked, and only while it is not regrowing
  (`ThreatPosture.regrowing`). Logged `Founding bill at X: N FP, M pooled from A n, B n`.
  `poolableFP(source)` is the same sum unspent: the source's bank plus the giving siblings'.
  Before this one colony's bank paid alone and a founding waited with tens of thousands of FP
  banked in the other colonies of its system (2026-09-29, ti-h8d/ti-h8e).
- **Landing.** The wave is consumed into the colony: `settleFounding` reconciles the real structure
  count against what was booked (the difference drawn from the source, or the new colony's own bank
  if the source is gone, or credited back), the fleet is unbound from the ledger and despawned. It
  does NOT become the garrison. The new hive starts with an empty bank and no garrison and builds its
  floor from its own income. A paid wave is not consumed out from under a battle.
- **Failure.** A wave that cannot found, withdraws, or is dropped refunds the structures
  (`refundFounding`, to the source or the nearest live colony; `abandonWave`, which
  `ThreatIncData.clearSystem` now calls for every wave it drops, where an untracked wave would have
  orbited its target for good). The hulls follow the ledger: re-banked if it withdraws, lost if shot
  down.
- **Old rules kept.** A wave launched before this (in flight in an older save) and an Abyss
  bootstrap wave (source null, endowed by `endowSeed`) still dig in as the first garrison.
- **Conquest garrison.** A strike's landing fleet over a world it took does dig in as the new hive's
  garrison (`digInAtConquest`; docs/ground-war.md "Conquest pays"), unlike a paid founding wave.
- **Conquest** is paid the same way: docs/ground-war.md "Conquest pays".

**Forge retooling.** A launch disrupts the source's forge for 1 day per `RETOOL_FP_PER_DAY` (10)
FP of wave (`retoolForge`, vanilla's industry disruption, never shortening a longer one) - about 50
days for a MAXIMUM wave. `forgeOutput` is 0 while it is disrupted, so hive-wide income dips with it.
`hasReadyForge` is the cooldown (no launch timer anywhere) and now really gates `pickForgeSource`,
`trySpread` and `launchColonizationWave` itself.

**Structures are bought.** Every planner build, and every upgrade, costs `foundingFPPerStructure`
(150) from the colony's own bank (`buyStructure`). An unpaid build waits: the planner spends its turn
on it (`KEY_BUILD_WAITING`) and `buyWaitingStructures` retries each poll. A waiting build pauses
garrison growth past the floor. A missing organ (Fabrication Core, Swarm Nexus) is added as a debt,
charged whatever the bank holds (`addEssentialStructure`), its production paying it off before
anything else is built. Migration swaps (`ensureSpaceport`), the debug war and the one-time save
heal (`planHiveEconomyFree`) stay free.

### Upkeep - a standing fleet costs its bank (2026-09-29, built, untested)

Paying for a fleet once let a rich hive's garrisons pile up forever, while the human side's forward
guards pay supplies to stand.

- **Rate.** `threatinc_garrisonUpkeepPerMonth` (0.04) of a fleet's FP per 30 days
  (`upkeepPerDay`), charged in `accrueFabrication`. 0 disables. The bank may go below 0 on it.
- **What a colony pays for.** Its garrison list at real FP (a grown fleet at its whole FP), its
  raiders, reinforcements inbound to it, and every live fleet in space bound to its ledger -
  strikes, waves, scouts, strays withdrawing (`ownedFleetFP`, `ledgerFleetFP`).
- **Recycling.** When the bank is below 0 and its income is below its upkeep, `recycleForUpkeep`
  scraps the weakest swarms first (a weak holder, else the smallest on station; never one in
  battle; at most one pass over the garrison), each crediting `hullShare` (0.8) of its FP, until the
  bank is back at 0 or the income covers the upkeep. This can take a colony below its floor: a
  floor the bank cannot carry is not one. Logged monthly ("Upkeep month:").
- **Equilibrium.** A garrison settles where its income meets its upkeep: income / rate. A size-6
  hive at about 240 FP/month settles near 6,000 FP.
- **Census.** The census line ends with `hiveLedgerSummary`: total fleet FP, income/month,
  upkeep/month, banked.

**Settings.** `threatinc_foundingFPPerStructure` (150 FP a structure) and
`threatinc_garrisonUpkeepPerMonth` (0.04) are in `settings.json` and LunaLib, beside
`fabFPPerShipUnit` (100) and the endowment.

### Posture - the garrison the war calls for (2026-09-29, built, untested)

Until now every nexus built past its size table whenever its bank paid, and only upkeep stopped it:
90-100k FP of mostly idle standing fleets. `ThreatPosture` weighs the war around each hive system and
holds the garrison that war calls for, no more. `postureEnabled` false gives the old behaviour.

- **Pressure** (per hive system, fleet points, read every `postureDays`): P = max(A, B) + C + D + F.
  It rises at once and decays over 30 days (`DECAY_DAYS`).
  - **A - attacks:** the warship points of every live NPC siege booked on the system's worlds, plus
    hunts aimed at it and Support / Defend orders over its worlds (each fleet once).
  - **B - staged capacity:** what the bases staging against the system could pay a force there
    (`ThreatSoftening.payableFP`, with hunt donors - capacity, not a siege's sizing, which reads the
    garrison and would chase its own tail). Weight 1.0 for a base staging for its siege, 0.5 for one
    staging for a hunt (`stagesForHunt`). The most any one base of a faction could, then summed across
    factions, each x (1 + grudge x `alarmTargetMult`) (`ThreatAlarm.targetMult`). `swarmsMet` is never
    used.
  - **C - losses:** Threat ships lost in the system lately, decaying (`noteBattle`, fed by
    `ThreatSwarmBountyIntel`'s battle listener; each ship once).
  - **D - hostiles:** hostile fleets present in the system that A did not already count.
  - **F - forward guards:** frontline garrison fleets near the system (within `frontlineKeepLY`) but
    outside it.
  - **E - wounds** are not pressure: a ground front on a world, a disrupted organ or a recently thinned
    system (`recentlyThinned`) force BESIEGED.
- **Want** (per colony) = max(base, its share of need). Need = P / `npcSiegeOrbitMargin` x
  `postureMargin`, split across the system's colonies by their size tables' cost (`floorFP`).
  Base (`baseFP`) = the colony's `garrisonReserve` swarm count costed at the table's cheapest rows
  (`minimumFP`, always at least one swarm) plus, at a forge colony of at least
  min(`spreadMinSize`, `strikeMinSize`), `LAUNCH_STOCK` (2) more rows (`stockFP`) - the substance of
  its next wave or strike. There is no "floor" of the whole size table any more (2026-09-29 test: the
  full table cost more than upkeep let the hive hold, so the want never left a surplus).
  While the sector stance is PRESS, a staging colony's want also gains the strike stock its target
  needs (`ThreatStance.extraWantFP`, added in `wantFP`; see "Stance").
- **Triage** (write-off). A system whose P / `npcSiegeOrbitMargin` exceeds what the hive could
  gather there - its held + its bank + the sector's held above its bases elsewhere - is written off:
  its need is zeroed, so its want falls back to its base and transfers stop feeding it (a 37k want
  against a 40k-FP hive would have stripped the quiet hives into it, ti-h8d). Read every pass, so it
  is lifted when the pressure or the hive's strength changes. Logs `Posture: X written off - pressure N
  needs N FP, the hive could gather N` / `... defensible again - ...`.
- **The size table** only decides the shape and tiers of what is built, not how many. Under posture
  `maintainGarrisons` builds only while held + inbound < want (`wantsGrowth`; held counts the garrison,
  raiders out and reinforcements inbound), or while the colony has no fleet at all. Nothing above the
  want is built; the bank keeps the rest for waves, strikes and foundings.
- **"Full strength" for launches** means held >= want (`ownAvailableForLaunch`), not the size table -
  with slack: a colony counts as regrowing (and launches nothing) only when it is short of want by
  more than its cheapest swarm (`ThreatPosture.regrowing`); held against the want itself, a colony a
  few FP short - a learned swarm cost crept up, a scratch, a bank a swarm short - sent nothing at all.
  `garrisonTargetCount` is the swarm count a colony fills toward - the table's
  (`desiredGarrisonCount`) with posture off, `ThreatPosture.baseCount` (reserve + launch stock) with
  it on. The war board's garrison "x/y" and fabrication trend read it, so a quiet colony shows full
  at its lean target (docs/war-board.md).
- **Modes** from ratio = (P / `npcSiegeOrbitMargin`) / held, with hysteresis (one threshold each flipped
  a system every few days as transfers moved its held FP across it). WATCHFUL is entered at 0.25 and left
  below 0.15; THREATENED is entered at 1.0 (the hive outmatched at the siege's own margin) and left
  below 0.8 (1 / `postureMargin`, the hive holding its want) - at 0.75 a system that had built to its
  want read 0.8 and stayed THREATENED, forges home, for as long as anything stood staged against it.
  THREATENED also when nothing is held and P > 0. Wounds give BESIEGED; a healed BESIEGED system
  leaves through the THREATENED band (it stays THREATENED until the ratio falls below 0.8). THREATENED
  and BESIEGED are "pressed". A change is logged.
- **Under attack** (`underAttack`) is narrower than pressed: BESIEGED, or attack fleets or hostiles
  present, or Threat ships lost there in the last 30 days (state field `S_ATTACKED`). A THREATENED
  system only faces a threat.
- **Launch spare** is held - need (`launchSpareFP`): what a launch may take from a colony. In a quiet
  system (need 0) that is everything above `garrisonReserve` - a quiet hive thins to its reserve to
  spread (its lean base is that reserve plus the launch stock).
  Under pressure only fleets that fit inside what is held above the need go, largest first.
- **Surplus** (held above want x (1 + `postureBand`) and one swarm, `releasableFP`) is released in this
  order:
  1. The pressure pass in `redistributeGarrisons` (`redistributeByPressure`). Under posture only this
     pass runs; the nominal pass toward the size tables (`redistributeNominal`) runs only with posture
     off. A colony holding less than its want (inbound counted), the neediest first, draws whole
     fleets from siblings: a donor gives what it holds above its own want x (1 + band) and one swarm,
     and must hold at least two fleets. Only a receiver in a system UNDER ATTACK may also draw the
     colonies of quiet systems down to their minimum (`thinnableFP`). Same system first, then the most
     to spare, then the nearest; the fleet must be covered whole by the donor's spare and leave the
     receiver no surplus to send back (the largest within the deficit, else the smallest). No
     circuits (a test sent 89 fleets in a month round a ring of three systems and back):
     - a donor gives only from fleets on station (held less what is inbound), and never while below its
       own want;
     - a colony that received a transfer rests 30 days (`DECAY_DAYS`, `threatinc_postureReceived`)
       before it can donate;
     - a fleet that was moved rests 30 days (`$threatinc_postureMoved`) before it is sent again;
     - each transfer counts as inbound to the receiver while it travels.
     - **Fabrication** (`fabricatorFor`, `fabricateFor`; 2026-09-29, ti-h8f - 60-80k FP lay banked at colonies at
       their want while pressed systems held 5-10k short): when no garrison in reach can spare a fleet for a
       receiver short of its want, a colony that holds its own want, can fabricate and reach the receiver, and
       whose bank holds its own want plus the swarm's cost builds the receiver's cheapest garrison row
       (`cheapestRow`) and sends it as a normal reinforcement (same rests and inbound). Richest idle bank
       (bank - want - cost) first, same system first. The cost is paid from that bank (`chargeFP`).
  2. Waves and strikes, through launch availability.
  3. Recycling (`recycleSurplus`), only after the break-even: (1 - `hullShare`) / upkeep x 30 days -
     150 days at the defaults - one fleet per colony per pass, weakest first, out of battle; or at
     once, as much as it takes, for a waiting bill (`buildWaiting`, or the founding of a pending claim
     the colony would source).
- **Appetite and claims.** Appetite = what the quiet systems hold above their want (as a share of want;
  fleets above want and banks above want both count) plus the share of forges whose system pays the next
  founding (`poolableFP` >= 5 x `foundingFPPerStructure`, `forgesCovered`; a waiting bill reads it too). Forges in pressed systems are not free
  (`trySpread`, `pickForgeSource` skip them), and pending claims are at most
  `claimCap` = ceil(free forges x appetite x `ThreatStance.expansionShare`), appetite capped at 1, at
  least 1 claim while that product is above 0. The share is 1 expanding, `stanceSecondaryShare` pressing,
  0 consolidating (no claims).
  `pickForgeSource` prefers a forge whose system can pay the founding (`poolableFP` >= 5 x
  `foundingFPPerStructure`) over one that cannot, then ranks by launch-available swarms, then distance
  (before this the swarm-richest forge was picked, failed its bill and the claim waited months, ti-h8e).
- **Settings:** `postureEnabled` (true), `postureMargin` (1.25), `postureBand` (0.25), `postureDays` (5).
  State is primitive maps (`threatinc_posture`, `threatinc_postureLoss`, `threatinc_postureReceived`);
  the per-session wants are forgotten on load. A state of the older six-field layout reads as unread,
  so the first pass after loading an old save starts its pressure afresh, once (the 7-field layout
  adds `S_ATTACKED`). Day stamps are days since timestamp 1 (`today()`) - see the gotcha in
  docs/README.md.
- **Logs:** `Posture: <system> QUIET->THREATENED pressure N (staged ..., attacks ..., losses30d ...,
  hostiles ..., forward ...) want N (base N, need N) held ... (+N inbound) bank ...` on a mode change; `Posture: <donor> sent N FP to ...`; `Posture: X fabricated a N FP swarm for Y (B FP banked)`;
  `Posture: <colony> recycled a N FP surplus fleet`; `Posture: <system> written off` / `defensible again`;
  and a monthly `Posture sector: ...` line, ending `; forges paying n/N; banks <top 4 colony banks>`.
  NPC staging raises Threat pressure (docs/strategy-layer.md "Staging").

Design rule and reasoning: docs/design-theory.md "Two design rules".

### Stance - what the surplus is for (2026-09-29, built, untested)

Posture decides what each system holds; `ThreatStance` decides what the rest is for. One stance for
the whole sector, evaluated at the end of every posture pass (`ThreatPosture.poll` hands it the pass).
`stanceEnabled` false, or posture off, reads as EXPAND everywhere (posture alone).

- **PRESS:** the hive outweighs a rival in reach and knows a weak world of theirs. **EXPAND:** the
  default. **CONSOLIDATE:** pressed at home, or losing hives under attack.
- **Breathing room:** no system under attack and sector pressure no higher than at the last pass (true
  on the first pass). It blocks CONSOLIDATE.
- **Enter and hold:**
  - CONSOLIDATE: no breathing room and (pressed share >= `stanceConsolidateShare`, or hive count down
    over the window while a system is under attack). Held until the pressed share falls below 0.8 x
    `stanceConsolidateShare`. Checked first.
  - PRESS: not losing, pressed share < half of `stanceConsolidateShare`, and a weak target of a rival
    outweighed >= `stancePressRatio` exists. Held while that ratio stays >= 0.8 x `stancePressRatio`.
  - Else EXPAND.
  - A stance holds `stanceDwellDays` before it changes; entering CONSOLIDATE never waits.
- **Strength per rival:** the hive's held FP in the hive systems facing that rival (systems it stages
  against, attacks or guards forward bases facing, plus systems within fuel reach of a known world of
  theirs - billed reach, the systems that would strike that rival first, `ThreatReach.facedFaction`)
  over the rival's FP in reach (staged capacity, attacks running, forward guards, as posture read
  them). The rival with most FP in reach is the strongest.
- **Weak known targets:** each hive system not pressed at home, from its strike staging colony
  (`pickStrikeTarget`'s source), over known strikeable worlds (`swarmKnows`, `strikeAllowed`) within the
  staging colony's `fuelRangeLY` - billed reach, any whose passage the stock pays, the muster capped
  at what the spare supplies keep away and the score taken per day away; needs phase 2. Muster = its
  colonies' garrisons above minimum + banks.
  Odds = defence / (muster's strength x `siegeBreakOffRatio`), the strike gate's own figure. Weak when
  odds <= `stanceWeakOdds`. Ranked by `IncursionManager.strikeValue` x (1 - odds). Need = the fewest of
  the staging colony's heaviest size-table rows that bring the odds to weak.
- **Trend:** the sector attrition ledger (Threat FP lost, enemy FP sunk, `ThreatPosture.noteBattle`),
  decaying over 60 days. Losing = lost > 5% of held FP and killed < lost. Hive count change over the last
  90 days (live colonies).
- **Home pressure:** pressed share = THREATENED or BESIEGED systems over hive systems; breathing room
  as above.
- **Wiring:**
  - PRESS: each staging colony's want gains max(0, need - its stock) (`extraWantFP`); `claimCap` x
    `stanceSecondaryShare`; `pickStrikeTarget` weights worlds by `strikeTargetMult` (docs/strategy-layer.md
    "Strike target weight").
  - EXPAND: `trySpread`'s candidate weight x `spreadMult` = sqrt(1 + LY to the nearest known world of the
    strongest rival), so seeding leans away from it.
  - CONSOLIDATE: `claimCap` 0; `redistributeByPressure` treats a THREATENED receiver as under attack
    (`feedsPressed`), so quiet colonies may feed it.
- **Settings:** `stanceEnabled` (true), `stancePressRatio` (1.5), `stanceWeakOdds` (0.5),
  `stanceConsolidateShare` (0.5), `stanceDwellDays` (30), `stanceSecondaryShare` (0.5), in `settings.json`
  and LunaLib. Persistent state, primitives: `threatinc_stance` {stance, day entered, sector pressure},
  `threatinc_stanceTrend` {lost, killed, day}, `threatinc_stanceHives` {day, live colonies, ...}. The
  targets and extra wants are rebuilt each pass and forgotten on load (`ThreatStance.forget`).
- **Logs:** `Stance: A->B - pressed n/n, attacked n, pressure ..., exchange ..., hives ..., rivals ...,
  best weak target ...` on a change and on the first pass after load; the monthly `Posture sector:`
  line ends `; stance X`.

### Fuel - the hive pays passage (2026-09-30, `ThreatFuel`)

Before this the swarm moved for free inside its fuel range while the factions paid for every
light-year from shipped stock. In the 75-month test (h12a) it shipped 1,015 swarms between systems,
grew to ~100k FP and massed 8-9k FP over any system a faction stocked a forward base against, while
no faction could fuel a hunt of that size. Now both sides pay the same rate.

- **Stock:** one for the whole hive (vanilla's broadcast availability already shares one fuel
  plant's output with every hive world). It fills by a faction reserve's rule
  (`accrualPer30` x `productionShare`): each world's fuel, `getSizeMult` of it x the fuel econ unit x
  `reserveSurplusMult`, a month, summed and held to the better of what the hive makes above its own
  demand and the sector's best exporter x `reserveBankImportsMult` (`ThreatFuel.perMonth`, accrued
  each `maintainGarrisons` poll). A faction world banks only what is left over its peacetime demand,
  its trade and civilian traffic; a hive world runs no trade fleets, so all its fuel is its fleets'.
  Read as surplus, the imports that meet a hive Megaport's demand banked 0 and grounded every fleet
  (first test). The cap made it ~9,000 a month on the test save, about one faction's banking. A save
  from before it starts with `FAB_ENDOWMENT_DAYS` of banking. Cutting the hive's fuel (plants or
  imports) now grounds it by stock as well as by range.
- **Passage:** fleet points / 25 x light-years x `expeditionFuelPerPointLY` - the factions' rate,
  which is their round trip. Strikes (`launchStrike`, the muster trimmed to what the stock fuels) and
  raiders (`ThreatRaiders.detach`, to the convoy) pay both ways; Seeding Swarms
  (`launchColonizationWave`) and reinforcements (`sendReinforcement`; the pressure pass's donor and
  fabricator picks skip what the stock cannot fuel) stay where they are sent and pay the way out
  only (`RETURN_LEG_SHARE`). Scouts pay one way along their whole route and home. Same-system moves
  are free. Nothing is refunded.
- **Founding (2026-09-30):** a Seeding Swarm carries what a faction's forward base costs
  (`ThreatOutposts.npcCost`: `outpostSupplies` 1,500, `outpostFuel` 800) on top of its hulls,
  structures and passage - a single swarm founded a hive for its structures' fleet points alone. The
  hive keeps a supplies stock beside its fuel, filled by the same rule (`ThreatFuel.perMonth(id)`;
  a save from before it starts with `FAB_ENDOWMENT_DAYS` of banking). `launchColonizationWave` waits
  while the stocks cannot pay (`canFound`), `loadFounding` draws them onto the fleet's memory, and
  they go into the colony when it is founded (`settleFounding`), back into the stocks with a wave that
  withdraws, and down with a wave shot down (`refundFounding`, `unloadFounding`). First test (18
  months): supplies bank 4.5-7.5k a month and pile up (126k) with nothing else to spend them on;
  fuel, spent on passage as fast as it banks, is what holds waves (5 in 18 months).
- **Setting:** `threatPaysPassage` (true), in `settings.json` and LunaLib; it gates the founding
  cost too.
- **Logs:** `Hive stock: <what> held, N fuel and M supplies in stock` (once per kind), and the monthly
  census line ends `; fuel N (+M/mo, spent S); supplies N (+M/mo, spent S), sends held H`.
  `Hive stock sources: <c> makers, output, available, own demand, made` (units) follows each census.

### Parity - wartime fuel, plants for shortages, supplies upkeep (2026-09-30)

- **Fuel at the war rate (A, replaced by structure costs below).** A hive plant's whole output
  counted, as a faction's does under `reserveWartimeFuel`. h32a (60 months from the base save): the
  hive went from 3 plants to 17 by month ~25, banked 108-186k fuel a month (4.8M stock) and took the
  sector - 91 hives, 142k FP, the Hegemony down to 6 colonies, the independents gone. Back on the
  surplus rule (h33a) a hive Spaceport's fuel demand (size-2) eats a same-size plant's output: 21
  plants banked 0, and the hive's 9k a month was the sector's best exporter x
  `reserveBankImportsMult`, whatever it built. Supplies the same: 4 forges made 24 units against 199
  wanted by Spaceports.
- **Plants for shortages (B).** A send held for fuel (`held`, not a Seeding Swarm) or founding short
  (`canFound`), or supplies upkeep unpaid, notes the stock short for `SHORT_DAYS` (30;
  `noteShort`/`shortOf`). `planHiveEconomy` answers after the bootstrap with a fuel plant or a forge,
  one at a time: each gets `SHORT_DAYS` to show before the next (`mayAnswer`/`answered`). h29a,
  unpaced, built 20 fuel plants in one tick and 25 forges in two months.
- **Supplies upkeep (C, `threatSuppliesUpkeep`, true).** Threat fleets away from home - raiders,
  reinforcements in transit, and everything on the ledger (strikes, waves, scouts) - burn their
  hulls' vanilla supplies a month (`ThreatFrontlines.maintenancePerMonth`) from the hive stock
  (`paySupplies`), as the factions' fleets on the war's orders do (`ThreatUpkeep`). A garrison at
  home is the hive's patrol: vanilla feeds patrols from the market's own supplies demand, which the
  hive's structures already take, so it keeps the FP upkeep (`upkeepPerDay` on `garrisonFP`).
  What goes unpaid notes supplies short and is owed; a month's worth owed turns the colony's
  raiders home and aborts its strikes (`starveAway`, "Swarm Out of Supplies"; logged `Upkeep: X owes
  N supplies for its fleets away`). A month paid in full clears the debt.
- **First try, garrisons paying too (h29a from h26 month 50, 5 months; h30a 3 more).** The 50k FP swarm burned 29-39k
  supplies a month against 4.5-6.75k banked; the 310k stock ran out in 11 months and the recycling
  took the swarm to 8-17k FP, with 70-137k FP banked and unspendable. Forges do not help:
  37 forges make 178 units of supplies, the hive's own structures (spaceports, stations, defences)
  demand 208, so 6 units a month reach the stock. Fuel went the other way: 27 plants, 138 units,
  ~200k a month banked, 4.9M in stock. (The 208 is Spaceport demand, size-2 a hive - not the
  stations' or defences', which demand none.)

### Structures cost supplies (2026-09-30, user's call; `ThreatBuildCost`)

The Threat conjured industries: a structure cost one founding's FP and stood at once, and the stocks
filled from the sector's best exporter whatever the hive built - so cutting a fuel plant or a forge
changed nothing it could afford, and the planner copied every industry into every system. The two
sides are not the same war (the Threat has years to spread before the factions mobilise), so this
is asymmetric by design where it has to be, and the one rule where it can be.

- **Cost.** A structure costs its vanilla build cost in credits at the supplies base price (100),
  times `structureSuppliesMult` (1.0): Heavy Industry 5,000, Fuel Production 4,500, Refining 2,250,
  Mining 1,000, Orbital Works 3,000 (an upgrade), Patrol HQ 3,000, Military Base 4,500, a station
  2,500-10,000. The hive's ground defences and heavy batteries are priced as vanilla's (1,500 /
  3,000). Logged once each: `Build cost: <id> N supplies (spec cost C, D days)`. Credits were ruled
  out - nothing the player can hit. Metals were ruled out too: humans would have to stockpile them,
  and cutting metals already bites through Heavy Industry.
- **Time.** Vanilla build time (`startBuilding`; Orbital Works by `startUpgrading`, the forge
  running on meanwhile). A structure under construction supplies nothing.
- **The hive** (`buyStructure`, `affordStructure`) pays from its supplies stock; a build it cannot
  pay waits (`buildWaiting`, the cost in `KEY_BUILD_WAITING_SUPPLIES`), notes supplies short, and
  `buyWaitingStructures` retries once the stock holds it. Garrisons no longer wait behind a build:
  fleet points are for hulls. Founding structures cost no FP (`foundingFP` is 0): a Seeding Swarm's
  `npcCost` supplies and fuel pay for them, as a forward base's founding does. Free builds (save
  heals, the debug war) stand at once.
- **The hive earns what it makes** (`ThreatFuel.perMonth`): every plant's and forge's whole output x
  econ unit x `reserveSurplusMult`. No Spaceport demand is taken off - it stands for trade traffic
  and a hive runs none - and no import fallback, as a hive does not trade. At the base save that is
  4 forges (24 units, ~18k supplies a month) and 8 fuel plants (48 units, ~72k fuel).
- **Forward bases** (`ThreatFrontlines.startNew`, `upgrade`, `payBuild`) pay from the link's supplies
  above its floor and staging bank, then the faction's other markets in reach (what a hunt may
  take, `payFromOthers`). One project at a time, as before: an unaffordable one waits
  (`Frontline: X waits on N supplies for <id>`). The swap of a fuel plant for a Heavy Industry
  checks the price before it tears the plant down. Founding stays `npcCost`.
- **Setting:** `structuresCostSupplies` (true). Off restores the FP costs, instant hive builds, free
  link builds and the surplus-and-imports stock rule.

### Size upkeep - growth is paid for (2026-09-30, user's call; `ThreatColonyUpkeep`)

Growth was the last free thing: a hive grew a size every 60 days x size at full vitality whatever
it cost, and the base save's hive stood on 33 size-8 worlds, 30 of them without a forge. Now a
colony pays for its size and grows on what it is paid - the same rule for a hive world and a
forward base, commodity-bound the way vanilla's economy is.

- **Upkeep.** Size 3 and up costs `sizeUpkeepAt3` x `sizeUpkeepRatio`^(size-3) supplies a month:
  100, 250, 625, 1,563, 3,906, 9,766 for sizes 3-8. A ratio of 2.5 is population^0.4 - vanilla's
  population is x10 a size, which nothing could pay. Sizes 1 and 2 are free: a seed costs nothing
  until it is a world, and a new chain has no income to pay with.
- **Growth is the share paid** (`growthRate`). At `upkeepBreakEven` (0.5) of its upkeep a colony
  holds its size; paid in full it grows at the old pace (60 days x size a size); below break-even
  it starves through a level every `starveDaysPerSize` (90) paid nothing. Progress is continuous
  across a size lost: a short siege costs progress, a long one sizes, and what is lost regrows at
  the growth pace. The size goes half a level below it (`SHRINK_MARGIN`), so a colony just grown
  does not lose it to the first lean week and one that loses it lands halfway back; a world at
  its cap banks a full level while fed. A fed size-8 fortress paid nothing holds 135 days, then
  loses a size every 90; regrowing 7 to 8 takes 420. A ground front or saturation holds growth,
  never hunger. h35a, before the margin: the base save's 23 capped size-8 worlds, progress 0, all
  lost a size in the first week. The Fabrication Core no longer gates growth. Vitality
  (fabrication x supply) is retired with it: reach is the bill (below) and a colony's
  counter-attack pace is the supplies it is paid.
- **Feeding order** (`feed`, each poll, out of the production of the days fed after the fleets
  away are paid):
  1. Sustenance: the break-even share of every colony, forge worlds first, then the worlds nearest
     the humans - up to the largest stance share of the production (`sustainShare`, 0.9), never
     more. The hive always keeps a tithe for forges, waves and fleets: h35a let sustenance take
     everything, and the base save's hive shrank until it did (26k of 29k a month) with nothing
     left to buy the forges that would have fed it again. The leeway is the half level below its
     size a colony's progress runs before the size goes (below), not the stock: a raided forge
     stops growth at once and costs sizes only if it stays down.
  2. Growth: the rest of each colony's upkeep, out of its stance's share (`feedShare`: expanding
     0.5, pressing 0.7, consolidating 0.9), to a colony only while that share still holds its next
     size's sustenance. Forges whose next size adds more output than sustenance first; then
     pressing feeds the front, consolidating the biggest worlds, expanding the smallest. A stance
     change never starves anyone - sustenance keeps its 0.9 - it only moves what growth gets.
  - A size-2 world grows into size 3, where upkeep starts, only as it is fed for it (priced at
    size 3's upkeep); a size-1 seed and a forge world below size 3 grow free. h35a let size 2
    grow free: 76 worlds grew into size 3 unpaid and starved back.
- **Blockades and ports.** What a world's own forge does not make it imports. A human blockade over
  it cuts that half or all (`ThreatBlockade.hiveCut`, docs/ground-war.md "Blockade") and a disrupted
  port halves it (`importCut`). A blockaded forge world keeps what its own upkeep takes and the rest
  of its output cannot leave (`reachesStock`, in `ThreatFuel.perMonth`). So a blockade starves the
  worlds that import; a forge world declines once its forge is raided too.
- **Founding.** The forge no longer retools after a launch (`retoolForge` is skipped): a wave's
  price is its cargo and its swarm. The cargo carries the colony's four structures too
  (`ThreatBuildCost.foundingKit`: the Spaceport 500 and the Swarm Nexus priced as the Patrol HQ it
  stands in for, 3,000; Population and the Core cost nothing in vanilla) - 5,000 supplies with
  `npcCost`'s 1,500 - and the colony's first build is bought from the stock like any other.
- **The opening chain's first forge** (`SEED_FORGE_KEY`). Each landing's one free build is Mining
  wherever there are deposits, and a chain may be deposit worlds alone: with structures paid in
  supplies and supplies made only by forges, such a hive could never buy its first forge (the
  stock starts at 0). `launchOGChain` marks the leanest planet that is not the chain's best ore,
  rare ore or volatiles world (else the leanest), and it lands with Heavy Industry, free. The
  Pristine Nanoforge follows onto it (`maintainHomeRelics`).
- **Sieges** (`ThreatPurgeFGI.raidValue`): a working forge is the top prize (100); the Core drops to
  the Nexus's 60 - it still halts the swarms, not the growth.
- **Readouts.** Hive Vitality's tooltip: the share of the month's upkeep paid, any import cut, and
  the growth pace or the days to the next size lost. The board's size line counts down
  (`s5 -> s4 ~40 d`). Census: `Colony upkeep: bill B (N/mo), paid S sustenance + G growth, short X;
  stance S share F; growing a, holding b, shrinking c, free d; blockaded e, ports down f`. A size lost
  logs `Starved a level of X: size a -> b`; a blockade's change `Blockade of X: imports cut N%`.
- **Why these numbers** (h31a-h34a logs and saves, vanilla's source). A forge makes 750 supplies
  and 100 FP a month per unit, size-2 of them (+1 Corrupted Nanoforge, +3 Pristine); Orbital Works
  makes the same; nothing else makes supplies and a colony counts one forge. Against that straight
  line an exponential curve gives every forge world a best size and a size where it stops paying
  for itself. At these settings a forge's next size pays for its sustenance up to size 6; a plain
  size-8 forge cannot hold itself (4,500 made, 4,883 sustenance), only the Pristine one can; a new
  chain of five with one forge reaches size 5 with a surplus to buy its second. The base save's 33
  size-8 worlds ask ~161k a month of sustenance against ~16k made, so it sheds toward forges at 6
  and the rest at 4-5. A faction banks ~7k supplies a month; a size-4 link's sustenance is 125, a
  size-6 link's 781. The one-off growth fee first proposed would have cost h34a 2.6k a month, 13%
  of its output - too little to change a decision.
- **Tests** (5 minutes each from the base save, ~19 months).
  - h35a (sustenance drawn from the stock, no margin, size 2 growing free): 23 capped size-8
    worlds lost a size in the first week; the hive shrank until sustenance took 26k of its 29k
    a month and bought nothing more; 76 size-2 worlds grew into size 3 unpaid and starved back.
  - h36a (the tithe, the margin, paid growth into size 3): size 269 -> 192 over ~13 months, then
    held - sustenance ~31.5k of 35-39k a month, nothing short. The tithe bought forges: supplies
    income 15.75k -> 39k a month, FP income 2.1k -> 5.2k. Fleets 101k -> 44k FP as the garrisons'
    sizes fell (83k FP banked). No world bounced between sizes; links grew without flapping;
    humans blockaded Ohai (224 FP over the swarm's 21) and Vassago (1,447 over 773). No colony
    grew: an oversized hive holds at sustenance's 0.9 edge until its forges lift production past
    its stance's share. No exceptions.
- **Settings:** `sizeUpkeep` (true; off restores vitality growth, retooling, the free founding
  structures and links that grow on shortage-free days), `sizeUpkeepAt3` (100), `sizeUpkeepRatio`
  (2.5), `upkeepBreakEven` (0.5), `starveDaysPerSize` (90), `feedShareExpand` / `feedSharePress` /
  `feedShareConsolidate` (0.5 / 0.7 / 0.9).

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
- **The gate.** The stock must pay the passage, and the fleet's supplies a month must fit in the
  spare (`ThreatColonyUpkeep.spareSupplies`): the production, less the fleets away, less every
  colony's sustenance at its 0.9 cap. A trip never starves a colony; it can starve growth, since
  fleets away are paid first. Launches between feeds commit their share (`ThreatReach.commit`).
  Strikes, waves, scouts and raiders pass the gate; reinforcements pay passage only - a garrison
  moving house is not a trip away.
- **The choice.** Strikes weigh a world by what it is worth per day away: `strikeValue` over
  `strikeDays` = 2 x ly / 0.5 + the 10.5-day muster. The muster is as many swarms as the spare keeps
  away, the target any whose passage the stock pays. The stance's weak targets the same. A hive
  system faces the faction it would strike first (`facedFaction`).
- **Founding** may claim anywhere a forge can send a wave. A claim weighs its deposits' need and the
  stance's lean as before, over the days a swarm needs from the network to get there times the days
  a strike staged there would be away at the nearest faction world (the old weight's 1 + ly and
  1 + ly² - the squared pull toward inhabited space leaned on the radius to keep the jump short),
  times the share of it the hive could hold (`holdShare`): the days the nearest faction military
  world that reaches the system needs to put a siege there (`razeArrivalDays`) over the days the
  nearest hive world's swarms need to get there, 1 when the hive gets there first or no base
  reaches it. The nearest forge sends the wave.
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
