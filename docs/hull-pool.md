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
standing upkeep paid X of Y supplies, Z FP starved`. Not the player's faction (its fleets keep the old
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
- The player's orders: `ThreatAidCapacity.capacityFP` = standing hulls, `ownFreeFP` = the
  faction's free pool (ships fly themselves to the base that stages them), staged task forces
  fold in as before. `aidBaseFP` and `aidRebuildDays` only apply with the pool off.
- Commit: `ThreatReturns.provision` commits every NPC fleet at its fleet points (the player's
  are committed by the order that built them). **Convoys draw no hulls** (`ThreatReturns.MEM_NO_HULLS`;
  vanilla's trade fleets are no patrols) and scouting parties and patrols launch only with the hulls free
  (`ThreatScouts.launch`) - my call after hw30, where supply fleets and scouts committed unchecked put
  Tri-Tachyon 2,300 FP out on a 200 FP pool and no warship could sail; the user to review. Release: `ThreatReturns.settle`, and the three
  disbands that never settled (home gone, timed out). Loss: `ThreatAidCapacity.poll` (a dead
  fleet is a debt at once, no clock) and `release` (the part that did not come back, by
  `ThreatReturns.health`).

## 4. What it shows

- Board FP column: a colony's standing hulls; the Total row the faction's free / standing,
  coloured by free (white enough for a guard, yellow a minimum, red less). Row tooltip: standing
  hulls with the patrol counts, fleet size and quality; the faction's pool; the yards' rebuild
  rate.
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
