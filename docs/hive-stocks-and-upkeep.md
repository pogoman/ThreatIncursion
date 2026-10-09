# Hive stocks and upkeep - fuel, parity, structures, size upkeep

Split out of `hive-garrison-and-upkeep.md` on 2026-10-09 (43.5 KB); posture and stance stay there. One-line answers: `facts.md`.

### Fuel - the hive pays passage (2026-09-30, `ThreatFuel`)

Before this the swarm moved for free inside its fuel range while the factions paid for every
light-year from shipped stock. In the 75-month test (h12a) it shipped 1,015 swarms between systems,
grew to ~100k FP and massed 8-9k FP over any system a faction stocked a forward base against, while
no faction could fuel a hunt of that size. Now both sides pay the same rate.

- **Stock:** one for the whole hive (vanilla's broadcast availability already shares one fuel
  plant's output with every hive world). It fills by a faction reserve's rule
  (`accrualPer30` x `productionShare`): each world's fuel, `getSizeMult` of it x the fuel econ unit x
  `hiveSurplusMult` (1.0; the hive's own rate since 2026-10-02, split from `reserveSurplusMult`), a month, summed and held to the better of what the hive makes above its own
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
- **A wave that stalls (2026-10-06, hw34b):** a Seeding Swarm neither founded nor dead
  `seedingWaveStallDays` (180) plus 30 a light year after launch (`WAVE_LAUNCH_KEY`, `WAVE_LY_KEY` on
  the fleet) is re-steered once (`Wave re-steered`); stalled again, a bootstrap wave is moved into its
  system beside its planet (`Wave unstuck` - the Abyss's incursion, before anyone can see it) and a
  colony's wave is withdrawn and refunded (`Wave stalled`). A wave in a battle or held by an outpost
  does not count. hw34b: the bootstrap wave to Blue, the Ala system's volatiles world, sat in the
  system from war day 159 to 3579 with every hull; `tryBuildLink` builds no fuel plant without
  volatiles in the hive, so the hive had fuel 0 for ten years and nothing could sail - no seed, no
  scout, no strike - and the humans never learned it existed.

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
  unpaced, built 20 fuel plants in one tick and 25 forges in two months. Replaced 2026-10-01 by
  the trailing demand ("Idle stock" below): only unpaid colony sustenance still notes a shortage.
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
- **The navy above the patrols pays supplies (2026-10-07, the user; `payNavySupplies`).** A hive's
  garrison FP above its patrol figure (`ThreatPosture.minimumFP`) - its launch stock and spare, the
  hive's counterpart of a faction's built hulls - pays the swarm's maintenance per FP x
  `standingUpkeepMult` a month from the hive stock, for the days `paySupplies` charged; the unpaid is
  demand and owed (`KEY_NAVY_OWED`), and once the owed reaches the smallest swarm's month that swarm
  is lost, never below the patrols. Month line: `Upkeep month: … navy upkeep X of Y supplies, lost K
  swarm(s) …`. "Might as well charge them supplies if threat has surplus anyway" - the swarm sat on
  0.4-1.4M supplies on the first sector (hw40-41), so it binds there not at all.
- **The garrison pays no supplies (2026-10-06, the user, after hw36-37).** One evening charged it
  (`payGarrisonSupplies`: garrison FP x the swarm's maintenance per FP from the hive stock, the
  smallest swarm lost once its month was owed) and then gated its growth on the hive's supplies
  surplus (`suppliesKeepSwarm`). hw36a: grown to its want on forge FP, the garrison took every supply
  the hive made (76k/mo, 43 forges built to feed it), its strikes starved, no war in 3,750 days;
  hw37: both navies under the supplies line, the swarm spending all it made on a garrison it could
  not use, humans 3-0 (`game-runs-2.md` 36-37). Cut: the garrison is vanilla's patrols - the Nexus
  sets the patrol table a human Military Base sets, and a hive market's shortages cut its fleet size
  and quality as a human world's do - and vanilla keeps it. The mod's stocks pay the mod's actions:
  fleets away (above), structures, waves, strikes; the navy a faction's yards build beyond its table
  (`ThreatHulls.maintain`, `docs/hull-pool.md` 2); the hive's navy above its patrols the same (`payNavySupplies`, above). A held strike now books its supplies shortfall as
  demand (`ThreatFuel.held`), as its fuel always did - kept from that evening.


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
  econ unit x `hiveSurplusMult`. No Spaceport demand is taken off - it stands for trade traffic
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
     the humans - up to the largest stance share of the production (`sustainShare`, 0.7), never
     more. **Since 2026-10-08 (`sustenanceFirst`, on) the break-even is paid FIRST, before the fleets away
     and the navy at home, out of the whole stock (the planner's forge reserve included) and of the whole
     production, not what the fleets leave: `sustenanceDue` is what `ThreatColonyManager.paySupplies`,
     `payNavySupplies` and `ThreatReach.freeStock` may not draw. hw68a, with sustenance last: a campaign
     launched on a +6k margin prepaid from the 23k stock, the stock hit 0, sustenance was paid 33 of 22.5k,
     sizes 179 -> 93 and income 72k -> 25k a month in eight months while the fleets already out kept drawing
     first; the navy shrank (96k -> 45k FP) only after the forges were gone. The forge is the income, the
     navy the expense: a navy the supplies cannot keep loses swarms smallest-first (`payNavySupplies`),
     never forges. Growth still draws only the free stock, out of the production the fleets away leave.** The hive always keeps a tithe for forges, waves and fleets: h35a let sustenance take
     everything, and the base save's hive shrank until it did (26k of 29k a month) with nothing
     left to buy the forges that would have fed it again. The leeway is the half level below its
     size a colony's progress runs before the size goes (below), not the stock: a raided forge
     stops growth at once and costs sizes only if it stays down.
  2. Growth: the rest of each colony's upkeep, out of its stance's share (`feedShare`: expanding
     0.5, pressing 0.7, consolidating 0.5), to a colony only while that share still holds its next
     size's sustenance. Forges whose next size adds more output than sustenance first; then
     pressing feeds the front, consolidating the biggest worlds, expanding the smallest. A stance
     change never starves anyone - sustenance keeps its 0.9 - it only moves what growth gets.
  - A size-2 world grows into size 3, where upkeep starts, only as it is fed for it (priced at
    size 3's upkeep); a size-1 seed and a forge world below size 3 grow free. h35a let size 2
    grow free: 76 worlds grew into size 3 unpaid and starved back.
- **Blockades and ports.** What a world's own forge does not make it imports. A human blockade over
  it cuts that half or all (`ThreatBlockade.hiveCut`, docs/ground-war-defenders.md "Blockade") and a disrupted
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
  `feedShareConsolidate` (0.5 / 0.7 / 0.5; consolidating was 0.9 until the simulator's round 20,
  `war-sim-rounds.md` 16 - the largest of the three is `sustainShare`).

### The navy fits the spare (2026-10-08) - moved

The fit, the builder respecting it, the fit floor as the colony's want, the pressure pass under the fit and the rally's
enough rule: `swarm-navy-fit.md` (split out 2026-10-09 at 48 KB).
