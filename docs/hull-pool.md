# The hull pool - one hull economy from vanilla's figures

Built 2026-10-06 on the user's decision (`facts.md` Decisions, the same day). One-line answers:
`facts.md`. Run records: `game-runs-2.md` 30.

## 1. Why

hw29a (`game-runs-2.md` 29): the swarm died FP-starved - `want 23k, held 10k, surplus 0` for
4.5 years - on 1.7 million fuel and 450,000 supplies, while the humans threw hundreds of
thousands of FP of hunts and landings at it. Three different hull rules, none vanilla's:

| side | hulls | voyage |
|---|---|---|
| swarm | forge output, 100 FP a ship unit a month, spent for good | fuel + supplies |
| NPC factions | none - supplies per FP, spawned from nothing (`ThreatSoftening.payableFP`) | fuel + supplies |
| player's faction | `aidBaseFP` x fleet size, regenerating 60 days after a loss | fuel + supplies |

The user: "if they do they bloody shouldnt, and there should be parity with the threat. FP
require heavy industry"; then "use all vanilla calculations for everything ... should apply to
player, threat and human factions ... factor in quality ... if theres a pristine nanoforge
somewhere that effects all spawned fleets".

## 2. The rule (`ThreatHulls`)

**Standing hulls of a market** = the patrols vanilla keeps over it:

- counts by weight from `PATROL_NUM_LIGHT/MEDIUM/HEAVY_MOD` - vanilla's `MilitaryBase.apply`
  sets them: Patrol HQ 2 light; Military Base by size (5: 2/2/1, 6: 3/2/1, 7: 3/3/2, 8: 3/4/3);
  High Command one more medium and heavy; an improved structure +1. The Swarm Nexus sets the
  same table for a hive at its tier (`ThreatHulls.patrolTable`: Nexus = Patrol HQ, Bastion =
  Military Base, Swarm Command = High Command);
- times the mean combat points of the weight (`MilitaryBase.getPatrolCombatFP`: 20 / 37.5 /
  62.5);
- through `Misc.getAdjustedStrength`: x (0.5 + quality) x fleet size % x (1 + (officer
  doctrine - 1) / 4). Quality is `Misc.getShipQuality`: the faction's best ships producer
  (pristine nanoforge +0.5, corrupted +0.2, Orbital Works +0.2, cross-faction import -0.25),
  stability, ship-quality doctrine. Fleet size % is the colony screen's figure (size 5 = 100%,
  8 = 175%, quantity doctrine up to 1.5x, stability, hull shortage);
- times `hullPoolMult` (1).

A market with no military structure keeps no hulls. Nothing is invented.

**The pool** of a faction is its markets' standing hulls summed (`standingFP(factionId)`;
forward bases are markets and count, player outposts are not). Every fleet the mod sends holds
its fleet points against it (`ThreatAidCapacity`'s ledger, now every faction's) until it is home
(`release`); what does not come home - a fleet destroyed, or the lost part of one that returns
- is a **debt** (`lose`). `freeFP` = standing - out - debt, never below 0.

**Rebuilding and building** (`rebuild`, daily): a faction's shipyards - Heavy Industry and
Orbital Works' SHIPS supply (`shipUnits`, read as the hive's `forgeOutput`) x `fabFPPerShipUnit`
(100) a month - pay its losses down first, and while the faction is at war what is left adds to
its **built** hulls (`built`, `KEY_BUILT`): standing over vanilla's patrols, lost like any other, no
cap (the user, 2026-10-06: "its a wartime economy why would they stop producing"; until then yard
output beyond the losses did nothing, and a navy never grew - hw32 humans 3.4k FP/mo of yards against
the hive's 45k, 7.7k hulls against 378k, `game-runs-2.md` 32). The player's yards build the same once
they mobilise. No shipyard, no replacement and no growth. The hive keeps no such ledger: its garrisons
are rebuilt by its forge bank as before, and only its **want** changes (`ThreatPosture.minimumFP` = the hive's standing hulls, at least
one swarm; the launch stock on top as before).

**Standing upkeep of the built navy** (`maintain`, daily; the user, 2026-10-06 - after hw33-35 went to
whichever side's hulls compounded first, and after hw36-37 showed that charging vanilla's patrol figure
double-counts what vanilla does: "every world both sides has innate patrols based on vanilla systems and
custom mod actions draw on the tangible resources"). Vanilla's patrols - the standing figure, the
hive's garrison - are vanilla's to keep: the Military Base demands supplies, fuel and ships, a
shortage cuts fleet size and quality, and the figure reads both. Only the navy the yards built beyond
the table is charged: `builtAtHome` = min(`built`, `freeFP`) (the fleets out hold vanilla's patrols
first and pay as fleets, `ThreatUpkeep`) x `standingRate` = `ThreatReach.suppliesPerFP(faction)` (its
fleets out measured, 0.94 by default) x `standingUpkeepMult` (1) a month. Each market pays its standing
share from its reserve above its floor and holds (`ThreatReserves.drawAbove`), then any market of the
faction above its floor. A hull-month nobody paid is a built hull lost (`starve`: unpaid / rate FP, never
a debt), and the unpaid is booked as demand for the faction's planner. The month line reads `Hulls: …
standing upkeep paid X of Y supplies, Z FP starved`. Since 2026-10-11 the player's built navy pays too, with a "Navy Unpaid" notice (`docs/fleet-provisions.md`); before that: not the player's faction (its fleets kept the old
rule too).

**Supplies-bound growth** (the user, 2026-10-06): the yards bank no built hull the faction's supplies
surplus cannot keep - `rebuild` adds at most `suppliesKeepFP` = (what the reserves bank a month,
`ThreatFactionStock.perMonth`, less their trailing demand) / `standingRate`; losses still rebuild
first, and the rest of the output is idle (`Hulls: X yards idle: N FP/mo its supplies cannot keep`).
hw36 charged vanilla's figure and the hive's garrison too and hw37 gated the garrison's growth: the swarm
spent every supply it made holding a garrison it could not use and the humans won 3-0 (`game-runs-2.md`
36-37); both were cut the same evening.

## 3. Where it binds

- **Yards at home (the user, 2026-10-08, trialled from hw73):** a faction short of hulls
  (`ThreatFactionStock.shortest` = hulls) builds a Heavy Industry at its largest core world with a
  free slot and no yard (`ThreatFrontlines.homeYards`, daily from `poll`), not only at a size-3+
  forward base (`buildStep`). Same rules as a link's answer: inputs importable, paid from the
  world's reserves and what reaches it or held for, one answer a faction a month, never over a
  running project. Why: the core yards the swarm razed or took were never replaced - yards at
  0-500 FP a month at every hw63-hw72 end, hulls unrebuilt in the thousands - and hw70a, the one
  game the humans won, was the one whose yards kept running. Knob `homeYards`.
- NPC hunts and plays: `ThreatSoftening.payableFP` capped at the faction's free hulls, the sum
  over bases (`huntForce`'s `builds`, `playPayableFP`) capped once - the bases share one pool;
  the build loop takes each fleet built off its budget.
- NPC sieges: `IncursionManager.launchSiegeExpedition`'s `payable` points capped at free hulls
  / `FP_PER_RESPONSE_DIFFICULTY`. The hulls are held when the fleets spawn (`ThreatReturns.
  provision`), not at dispatch: two sieges dispatched the same day can both read the pool free.
- NPC sorties and guards: `ThreatFleetOrders.payableFP`, `ThreatFrontlines.payableFP`.
- The player's orders: `ThreatAidCapacity.capacityFP` = the colony's SHARE of the faction's
  hulls (`ThreatHulls.shareFP`: its patrols, plus the built navy at the hull port, plus hulls that came home to it - below), `ownFreeFP`
  = that share less what sailed from it (never above the faction's free), and a sortie from a
  colony sails its own free plus the task forces staged over it (`freeFP`), which fold in as
  before. From 2026-10-06 to 2026-10-11 `ownFreeFP` was the whole faction's free pool at every
  colony ("ships fly themselves to the base that stages them"), the builder's call; the user
  (2026-10-11, a Hunt quoting the whole navy from Haven): "so whats the point of staging bases
  again given it just pools from all bases? ... all this was working in older versions" - so
  hulls reach another colony only by a Fleet order to it (`ThreatAid.pickTaskForceSource`
  takes the nearest OTHER colony first for a guard over an own colony; the colony's own hulls
  stage over itself only when none other can). NPC navies still draw on their faction's pool
  (`ThreatHulls.cap`). `aidBaseFP` and `aidRebuildDays` only apply with the pool off.
  A player force built from staged guards (`ThreatFleetOrders.buildPlayerForce`) sends the
  guards themselves, one a fleet (`ThreatFleetOrders.retask`, 2026-10-11 19:40): the colony's own
  free hulls build fleets first; past them each task force on station is re-tasked in place - its
  guard ends, what it carries settles at the base, own hulls top it up when it is short of the
  ask, it is provisioned for the trip and sails on the order. Its ledger entries stay charged to
  their senders until it is home, when the hulls are the base's (below; until 2026-10-12 it went
  back on station at the base as a guard, `restation`).
  Until then the fold expired each guard and the yards built its points again (the user: the
  fleets "disappear then respawn coming out of the planet"), and a fleet could fold two guards
  (a Med hunt of 1,451 built five fleets and folded nine). A Siege or Bombard takes
  them the same way (`ThreatPurgeFGI.takeStaged`: the guards wait in orbit, alive, and are handed over
  at the spawn; off the map only with an expedition the player cannot see), sized at the Force tier (`IncursionManager.playerExpeditionSizes`: the need, half
  the free points, all of them).
- Commit: `ThreatReturns.provision` commits every NPC fleet at its fleet points (the player's
  are committed by the order that built them). **Convoys draw no hulls** (`ThreatReturns.MEM_NO_HULLS`;
  vanilla's trade fleets are no patrols) and scouting parties and patrols launch only with the hulls free
  (`ThreatScouts.launch`) - my call after hw30, where supply fleets and scouts committed unchecked put
  Tri-Tachyon 2,300 FP out on a 200 FP pool and no warship could sail; the user to review. Release: `ThreatReturns.settle`, and the three
  disbands that never settled (home gone, timed out). Loss: `ThreatAidCapacity.poll` (a dead
  fleet is a debt at once, no clock) and `release` (the part that did not come back, by
  `ThreatReturns.health`).
- A fleet home is home - hulls belong where they land, and nothing is re-stationed on return
  (the user, 2026-10-12, "Dissolve into Haven's Hulls", after a fleet returned to Haven read 219
  at Haven and 93 at Arkoman twice over). Until then (2026-09-06 to 2026-10-12) a fleet carrying
  ships staged at the host was KEPT on station there as a guard when it came home (`restation`,
  two overloads, now deleted), every sender's charge intact and Recall taking it to the sender
  charged most - so the hulls never reached the column and a release refunded each sender. Now:
  - a player fleet home at a player colony moves every other colony's charge to it
    (`ThreatHulls.moveHome`, persistent `threatinc_hullMoved`, added to `shareFP`): the home's
    share rises by what came back, the sender's falls, the faction's standing is unchanged;
  - **the player's Hulls cell has no faction-wide cap** (12:39 jar, after the first logged recall:
    the 302 came back to Haven, 0 -> 218, and Arkoman rose 9 -> 93 only because the faction's
    free total of 9 had been capping every cell - Arkoman's own 93 was there all along). A player
    colony's cell is its share less its own out (`ownFreeFP`, `isPlayerOwned` branch); its losses
    come off its share (`ThreatHulls.lostAt`, from `ThreatAidCapacity.lostPart`) and the yards'
    rebuilds and allies' hull landings put them back at the hull port (`rebuiltAt`, from `rebuild`
    and `receive`), so the cells still sum to the faction's free. NPC colonies keep the cap (no
    NPC balance change before 0.8). A colony over-committed from before the own-share rule (Haven:
    605 out of 521) clips at 0 until its fleets are home. `rehome` had returned on `o.aid` - the
    board's Guard IS an aid order - so the 09:38 jar's version never ran; 12:33 jar fixed it.
    `ThreatAidCapacity.logHulls` logs every colony's cell and its parts before a recall and after
    a settle - read starsector.log before theorising;
    called from `ThreatAidCapacity.release` (tracked returns) and from the expedition-over path of
    `ThreatAidCapacity.poll` (home = the entry's host, else the expedition's `sourceBase`);
    `ThreatPurgeFGI.releaseStagedLive` (an expedition that never sailed) dissolves its forces into
    the base the same way;
  - a guard of the player's on station over a player colony is charged to that colony from
    arrival (`ThreatFleetOrders.rehome`: entries re-pointed, Recall takes it home there), run every
    poll so a guard staged before the rule counts too;
  - player colonies only; NPC fleets come home to their sender. A share pushed below 0 (the sender
    later losing patrols, or the hull port moving) reads 0; `ownFreeFP`'s cap at the faction's
    free keeps the rows from paying out hulls that do not exist. Staging is only ever an explicit
    Fleet order to a colony.

## 4. What it shows

- Board **Hulls** column (the user, 2026-10-11 17:10: "rename to Hulls. and no x / y. the column
  is just Hulls available. if all have been spent then just dashes"; "Fleet" and "FP" before, and
  free / standing for a quarter of an hour; 18:05: "they are there so why arent hulls consumed it
  should say zero"): the colony's own hulls not yet sent out (`ThreatAidCapacity.ownFreeFP`:
  its own share less what sailed from it), a grey dash with none left; a task force on station
  over it is hulls already out, listed in the fleets table, and still sails with the colony's
  Hunt, Siege or Fleet orders (`freeFP` = own plus staged is what those quote); the Total row
  the rows summed - 0 with the fleet out, on station or not. Cells are
  coloured by free (white enough for a guard, yellow a minimum, red less). Row tooltip: one "Hulls" line
  beside the resource lines - standing fleet points and what the yards add a month
  (`ThreatFactionView.hullLine`); the pool / on-station / free block that sat below it was
  cut 2026-10-11 (its figures disagreed with the cell; the user: "yellow garbage").
- Fleet / Hunt / Defend buttons and their prompts (2026-10-11): "a task force of about 302 of the
  2,365 fleet points free from Haven" - one order is ONE fleet, and vanilla builds at most
  `maxShipsInAIFleet` ships of it, so the ask (`ThreatAid.taskForceFP`) stops at the per-fleet cap
  `ThreatSoftening.fleetCapFor` (learned from a pruned build, player and NPC alike; probed by a dry
  build, `ThreatFleetOrders.dryTaskForce`, before the first) and the quote (`taskForcePoints`) at
  `builtCapPoints`, the whole-fleet points that build sailed with. Each press stages another fleet;
  the free figure is the source colony's own (or, for a sortie, own plus staged).
- Log: `Hulls: <faction> hulls S FP (B built): O out, L lost, F free; yards P FP/mo` monthly with the sim
  dump; `Hulls: <faction> lost N FP with <fleet>; D FP to rebuild at P FP/mo` per loss.
- Dump: per faction `hulls`, `hullsBuilt`, `hullsOut`, `hullsLost`, `hullsFree`, `hullYards`.

## 5. The scale (estimates before the first run)

Chicomoztoc (8, High Command, pristine, Hegemony officer doctrine 5) ~2,500 FP; Eventide ~1,300;
Jangala ~700; Hegemony ~6,000 standing against the 5-10k a siege it sent before. Luddic Church
~900. A size-7 hive with a Bastion ~1,470 (want 1,356 before), size 6 ~800 (1,956), size 1-3
~200; hw29a's 24-hive swarm ~14,000 against a want of 23,000. Hegemony's one real shipyard
rebuilds ~600 FP a month, the hive's 13 forges 4,500. The humans shrink to about a quarter, the
swarm to about 0.6x; Heavy Industry is the war's engine on both sides. Knobs: `hullPool`,
`hullPoolMult`, `fabFPPerShipUnit` - never free hulls.

## 6. Yards first (2026-10-19)

The user, 2026-10-19 00:20, after the hw209/hw210 read (every postponed human siege hull-bound - `launchSiegeExpedition` pays the fewest points of fuel, supplies and free hulls at 25 FP a point - while the factions banked 200-250k fuel and 60-70k supplies; Heavy Industry built only as a shortage's answer, 10-18 producers a game against 80-120 military builds): "heavy industry should be the primary objective let's make the wartime economy focus 100% on hull production first where possible". Knob `threatinc_yardsFirst`.

- **Build order** (`ThreatCoreBuild.yardStep`, before `answerShortage` and `militaryStep`, every daily pass - not one a month): a Heavy Industry at the largest core colony without one (an Orbital Works counts) that has a free industry slot and can import metals and transplutonics (`canSupplyProducer`); else a Heavy Industry standing idle is upgraded to Orbital Works. Paid by `payBuild` (the colony's depot above its floor, then the faction's markets in reach) or its price held at the colony (`ThreatFactionStock.hold`). A yard started or held is the faction's whole pass: nothing else is built while one waits.
- **Spending order** (`ThreatFactionStock.yardWant`: the held yard prices summed): a founding kit's payer must cover it on top of the kit (`ThreatFrontlines.canFund`; `payer` logs "founds nothing while its yards wait" once), and a siege's pooled supplies are read net of it (`launchSiegeExpedition`). Defence - garrison voyages, relief, hull upkeep - is untouched: the colonies that hold the yards are hull production too.
- **Where possible** means a free slot and importable inputs; nothing is demolished for a yard and no colony grows for one.
- **Read it by**: `Core build: X (f) builds heavyindustry (yards first) - <describe>`, `... upgrades heavyindustry to orbitalworks (yards first)`, `Core build: X holds N supplies for a heavyindustry|orbitalworks (f yards first)`, `Frontline: f founds nothing while its yards wait (N supplies held for them)`; the monthly `Hulls: F hulls N FP ... yards N FP/mo` line is the outcome.
- First trial hw211 (set B) / hw212 (set A) against hw209 / hw210.
