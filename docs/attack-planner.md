# Attack planner and fog of war - DESIGN (2026-10-01, decided, being built)

The user's request of 2026-10-01: NPC factions plan their war from what they know at the time,
hit in several places so that one blow is likely to land, and react when a fleet arrives
outmatched. No cheats: nobody reads the swarm's strength remotely, the player included, and
forward bases get radar. Section 9 records the user's answers (2026-10-01).

## Why: what h48a showed

The lt save at month 144, 2026-10-01. The numbers are in `docs/ground-war.md`, "Saturation
presses a hive".

- **Sieges are sized for the whole system, then padded.** The orbit gate wants 1.5x every garrison in
  the target system (`npcSiegeOrbitMargin`, `npcSiegeOrbitSystem`). The six core systems hold
  12-17k FP each, about 80% of the swarm, while their strongest world holds 1-2.5k. They need
  18-26k and are never attacked. All 7 launches hit 1-2-hive systems under ~4.3k.
- **No plan.** Each base picks monthly, easiest first, on its own: one siege, then nothing,
  then another. Nothing links two operations, inside a faction or across a coalition.
- **No fog.** Every NPC read of the swarm is exact, live and remote. `siegeOrbitFP` sums
  `getFleetPoints` over every garrison in a system on the day, wherever the reader is. The
  board shows the player the same for every discovered system. The only fog is whether a
  system has been found.
- **A siege learns only by failing.** It sees the truth on arrival, and its only option is to call off
  (`swarmsMet`, remembered 90 days). It never goes elsewhere.
- **Sized for a convergence that never comes.** `npcSiegeOrbitSystem` assumes that a system's
  garrisons converge on a besieged world. A world's reserve never leaves it: only FP above 1.25x
  a colony's want moves, and in a core the want is the reserve. Pelephanar's want is 11,032
  against a need of 200-466; Bastion and High Command multiply the reserve by 2 and 3. So a core
  world's own orbit holds 5-21% of its system, 0.9-2.5k. (ThreatPosture, ThreatColonyManager
  5307-5340.)
- **The openings nobody takes.** What does move travels as real fleets, nearest donor first. It
  lands 0-15 days after the 5-day pass that sends it, and some sends took 30+ days. A quiet system
  drains to its reserve for a system under attack, and a fresh seed holds one swarm of 63-67 FP.
  In one h48a pass, 21 transfers moved 9.4k FP and left the donors with 2-8 swarms each. No
  faction struck any of them.

## Principles

1. **Know only what you saw.** A faction plans from its reports: what its worlds, forward
   bases, fleets and scouts saw, and when. Nothing reads the swarm remotely and live.
2. **Old news is a guess.** A report loses trust with age; a month-old picture is a coin flip.
3. **Doubt buys more prongs, not bigger ones.** No padding. Each prong is sized to what the
   faction knows, and the plan sends enough prongs that one is likely to land.
4. **Eyes decide on arrival.** A prong that arrives outmatched does not fight a battle it
   cannot win. It diverts or goes home, and what it saw becomes a report.
5. **A campaign, not a pick.** A faction holds a standing plan and re-plans on news.
6. **Unchanged:** a closed economy with no caps, reach paid in fuel, disruption one day at a
   time.

## 1. Reports - what a faction knows

**A report** is one observer's picture of one Threat system, and it holds:
- the Threat FP over each world, counted within the 1,500-unit radius the contest rule uses;
- the Threat FP loose elsewhere in the system;
- the day it was seen, and how.

The observers are every mobilised faction and the player. Planet facts (size, structures,
defence) stay live, because they change slowly and mostly by the observer's own hand. The
swarms are what moves.

| Source | Refreshed | Precision |
| --- | --- | --- |
| **Eyes** - any fleet of the observer in the system (siege, raid, hunt, scout, convoy, patrol), an abstract expedition whose route is in the system, a world of the observer there | daily while present, and on arrival | exact |
| **Radar** - every forward base (frontline link) and military world | daily, every Threat system within `radarRangeLY` | two significant figures |
| **Scouts** - a recon sortie sent to a known system | on arrival | exact |
| **Allies** - decision 2 | when shared | as the ally saw it, with its date |

- **Radar range is 10 ly,** the same as `frontlineReachLY`. A chain stops growing once a link is
  within 10 ly of its hive, so the last link of every chain watches the hive it was built to
  reach. Links stand 12 ly apart (`frontlineLinkLY`), so radar covers the frontier and never the
  interior.
- **Trust** = 0.5 ^ (age / `intelHalfLifeDays` 30). A watched system is always fresh. A system
  never seen is Unknown.
- **Discovery is unchanged.** Finding a hive is still sector-wide news (`discoveredSystems`,
  `ThreatScouts.sectorKnows`).

**Every remote read goes.** These read the observer's report instead of live fleets:
- In `IncursionManager`: `siegeOrbitFP`, `siegeOrbitWeighed`, `easiestFirst`,
  `anyTargetGarrisoned` and the launch gate (postpone and bounty). `swarmsMet` folds into
  reports, and `siegeMetMemoryDays` retires.
- `ThreatFactionStance.weakestTarget`, `ThreatFleetOrders.dispatchOrbit`, `ThreatSoftening.send`,
  the hunting muster's go / no-go, and `ThreatSoftening.divert`'s pick.
- `ThreatCoalition`, `ThreatAid` and the stance's "theirs" also gain the `sectorKnows` gate they
  lack today.
- Player-facing:
  - the board's system rows and colony cards;
  - `InfestedSystemIntel`'s garrison lines (and `IncursionManager` 5292-5320, which feeds them);
  - `ThreatSwarmBountyIntel`'s FP lines;
  - contract text;
  - the "Siege Called Off" notice.

**What stays live is a fleet's own eyes.** `breaksOff`, `breaksOffAbstract`, the contest rule
(`ThreatGroundFronts.orbitContestedFor`) and the siege leash all read the system the fleet is in.

## 2. The planner

`ThreatAttackPlanner` runs one plan per mobilised faction: the operations it has out (each
with its target, prongs and fallback) and the day it last planned.

**When it plans:** every 7 days (the stance clock) and on news. News is:
- a report that moves a target's swarms by half or more, either way;
- a prong's arrival verdict;
- an operation ending.

**What it can run:**

| Operation | What it is | Built on |
| --- | --- | --- |
| Siege | troops and a flotilla to take or raze worlds | today's expedition (`ThreatPurgeFGI`) |
| Raid | a task force that holds one hive's orbit and bombs it, with no troops and no front | new: a Support order flagged as a raid (section 4) |
| Recon | a scout sent to a known system to refresh the picture | `ThreatScouts`, given a target |
| Hunt | a force that thins a system's swarms | today's `ThreatSoftening`, reading reports |

**Targets are worlds, not systems.** A core system's worlds are separate targets because their
garrisons are leashed to them. Prongs at three worlds of one core are three prongs.

**Sizing: the 1.5x goes.** A siege's orbit need becomes the reported swarms over the strongest
world it takes, at 1.0x:
- `npcSiegeOrbitMargin` 1.5 -> 1.0;
- `npcSiegeOrbitSystem` true -> false.

In h48a's cores that is about 1-2.5k instead of 18-26k. A raid needs twice the reported swarms
over its world (the contest rule's 0.5) and enough hulls to outlast the guns (section 4).

**Choosing:**
- **Sieges**, if the stance allows: the weakest worlds the faction can take and pay for, as
  `ThreatFactionStance.weakestTarget` picks today, but from reports and per world. More than one
  can run when the pools pay for them.
- **Raids** are ranked by cost per expected day down: fuel and supplies at base prices, divided
  by the days down the raid buys times its trust at arrival. That is the yardstick that already
  picks the bombing squadron. They are funded best first from whatever the sieges leave in the
  pools. The pools are the only limit.
- **Trust is taken at arrival:** 0.5 ^ ((age + travel days) / 30). A fresh picture of a hive 50
  days away is a month-old picture by the time anyone gets there. A forward base 10 ly out
  arrives on a picture it can trust.

**Spread before size.** The planner adds prongs at other worlds until the chance that at least
one lands reaches `planConfidence` 0.8. That chance is 1 - the product of each prong's
(1 - trust at arrival). Only then does it enlarge a prong. A prong from a forward base comes close
to 0.8 on its own; the farther the base, the more prongs it takes.

**Scouts go where the picture is the weak part.** A candidate whose report is older than 30 days,
with no prong bound for it, gets a scout. A scout is a 20 FP fleet.

**Every prong carries a fallback:** the next-best target its fuel reaches from its first one.

**Coordination:**
- Raids planned together with a siege sail so they arrive no earlier than the siege does.
- The swarm answers a siege from the nearest systems first, and quiet ones drain to their reserve.
  The siege's own eyes see the swarm gather; radar sees a watched donor thin. Either is news, and
  the re-plan makes the thinned world a raid target at once.
- A faction's siege becomes a coalition Call (`ThreatCoalition`). A partner that answers adds its
  own raids or hunts against the systems the Call names.

**Stance:** PRESS runs everything, EXPAND runs raids and scouts, and CONSOLIDATE runs only scouts.

## 3. On arrival

Every prong writes a report when it arrives, then judges:

- **Siege:** as today, it commits unless the swarms over a world it is taking reach its own FP
  (`siegeBreakOffRatio` 1.0); otherwise it calls off and sails home. The call-off now writes a
  report, so the next plan knows. Sieges do not divert in v1 (decision 3).
- **Raid:** it bombs while its world's orbit is uncontested (hostile FP under half its own).
  Otherwise it diverts to its fallback if its fuel pays the detour, as `ThreatSoftening.divert`
  pays a hunt's; failing that it goes home. On station, it leaves at the first poll that finds
  its orbit contested.
- **Hunt:** as today (`divert` when outmatched at the muster or moving on), but picking from reports.

## 4. Raids

**A raid holds one hive's orbit and bombs it, with no troops and no front.** It is the NPC Support
order (`tickSupport` -> `supportSlice`, the tactical slice) without the stand-down that sends an
NPC Support home when its faction holds no front there.

**Its size is set by the target, never by a constant:**
- **The guns.** A day is worth flying only if it buys at least a day down:
  30 x F / (F + D) >= 1, so F >= D / 29. That gives 42 FP over a size-2 hive, 125 over size 3, 374
  over size 6, 435 over size 7 and 498 over size 8. These match the "outlasts the guns" figures in
  h48a's 368 verdicts.
- **The orbit.** A raid bombs only while uncontested, so it needs more than twice the reported
  swarms over its world:
  - a fresh seed (one swarm of 63-67 FP) needs about 130;
  - one HIGH swarm (about 475) needs about 950;
  - a core world (0.9-2.5k) needs 2-5k.
- **A raid is the larger of the two.** So 300 FP reaches fresh seeds, stripped worlds and soft
  hives (D up to about 8,700), and never a core world's orbit.

**What a day on station does** (the existing `supportSlice`, on the per-day primitives `returnFirePerDay`, `bombardFuelPerDay` and `suppressionRate`):
- **Days down bought:** 30 x F / (F + D) x condition. Condition is what is left of the guns, and
  it falls as they are worn.
- **FP lost to the guns:** 0.0008 x D x gun share, whatever the raid's size. The gun share is 0.5
  for Ground Defenses, 0.67 for Heavy Batteries and 0.83 for both. A bigger raid loses no more
  and buys more.
- **Fuel:** 0.04 x F (tactical). Saturation's 2.86 x F stays with a siege's squadron.
- **Example:** in h48a, 400 FP over a size-2 hive with no guns bought 7.5 days down a day at no
  loss, and wore the condition from 1 to 0.16 in 63 days.

**When it leaves:**
- at the commander's stop: a day buys less than a day down, or a third of the raid is gone
  (the stop in `ThreatGroundFronts.bombardPlan`);
- at the first poll that finds its orbit contested;
- when the supplies for its stay run out.

It then goes home and refunds what it did not use.

**What it costs.** Passage is 10 fuel per 25 FP per ly, and supplies are 30 per 25 FP. A 300 FP
raid 12 ly out and back costs about 2,900 fuel and 360 supplies, plus 12 fuel a day while
bombing. For comparison, a 20k flotilla's one-way passage over the same 12 ly is 96,000 fuel.

**Withdrawal has to beat the 0.5-day poll.** Swarms sent in reply land 0-15 days after the 5-day
pass that sends them, and a raid sees them enter its system. If tests show raids being caught,
the leave test widens to swarms bound for its world.

## 5. Off-screen fights per world

**The live game fights per world:**
- Garrisons are seated within 700 su of their world and leashed there.
- The contest rule counts the swarms within 1,500 su.
- Reserves stay home, and only surplus moves (see "Why").

**The off-screen siege does not:**
- `breaksOffAbstract` weighs vanilla's WarSim strength of the whole system.
- `ThreatAbstractBattle` makes every defending fleet in the system lose.
- It runs all its slices in one frame a day after arrival (`ThreatGroundFronts` 3574-3628), so
  swarms sent in reply never reach it.

**The change:** an off-screen siege runs a day at a time, like a watched one.
- Each day it weighs the swarms within 1,500 su of the world it is taking. The swarms are real
  fleets, so their positions exist off-screen too.
- It calls off on the same `siegeBreakOffRatio`.
- Losses fall on those fleets only.

Sieges are now sized per world. If off-screen sieges kept the one-frame shortcut, they would take
core worlds that a watched siege loses.

## 6. The player's view

- **The board's swarm figures are the player's reports:** the figure, then its age in days
  (`3,400 · 41 d`), and `Unknown` when never seen. Radar figures are rounded. No new colours.
- **The player's sources:**
  - their own fleets in the system, including the Defend / Aid / Strike task forces;
  - their colonies there;
  - their outposts (`ThreatOutposts`), which get the same radar as a forward base;
  - allies, if decision 2 says so.
- **Text the player reads from someone else** - a bounty, a contract, a "Siege Called Off"
  notice - carries the poster's report and its date.
- **Fronts the player holds stay live,** because the player's fleets are there.

## 7. Knobs

| Knob | Default | Note |
| --- | --- | --- |
| `intelHalfLifeDays` | 30 | a report's trust halves every this many days |
| `radarRangeLY` | 10 | = `frontlineReachLY`; forward bases, military worlds, the player's outposts |
| `planConfidence` | 0.8 | the chance that at least one prong lands, reached before any prong grows |
| `npcSiegeOrbitMargin` | 1.5 -> 1.0 | the padding goes |
| `npcSiegeOrbitSystem` | true -> false | a siege is sized on its strongest world, not the system's sum |
| `siegeMetMemoryDays` | retire | reports age instead |

## 8. Where it lands in the code

Line numbers are from 2026-10-01; verify them before editing.

- **New `ThreatIntel`:** the reports, per observer per system, persisted with the war data.
  - `see(observer, system, source)` writes a report from the live system. It is called only where
    the observer has eyes or radar.
  - `orbitFP` / `looseFP` / `age` / `trust` read a report. Readers never touch live garrisons.
  - Precedent: `ThreatSwarmScouts` keeps the swarm's own charted-systems map (55-58).
- **New `ThreatAttackPlanner`:**
  - the plan per faction (its operations, prongs and fallbacks);
  - the 7-day clock and the news triggers;
  - the ranking, the spread rule and the coordination.
  - Launches go through the existing paths, so provisioning, refunds and the closed economy are
    untouched:
    - a siege goes through `IncursionManager`'s launch for a chosen base and targets;
    - a raid goes through `ThreatFleetOrders.dispatchOrbit` with the raid flag and its own need;
    - a scout goes through `ThreatScouts`;
    - a hunt goes through `ThreatSoftening`.
- **`IncursionManager`:**
  - The monthly siege pass stops picking targets itself; the planner picks. The provisioning,
    postponement and swarm bounty stay.
  - `siegeOrbitFP` (2429), `siegeOrbitWeighed` (2486), `easiestFirst` (2217),
    `anyTargetGarrisoned` (2234) and the launch gate (2790-2814) read reports.
- **`ThreatPurgeFGI`:**
  - `resolveOnArrival` (614), `breaksOff` (647) and `breaksOffAbstract` (694) write a report.
  - `callOff` (728) stops writing `swarmsMet`.
  - An off-screen siege runs a day at a time against the world it is taking (section 5). This
    replaces the one-frame run (GF 3574-3628) and the system-wide weighing in
    `SiegeRaidAction.autoresolve` (1291-1296) and `ThreatAbstractBattle`.
- **`ThreatFleetOrders`:** a raid flag on `Order` (143-214) and a need overload on
  `dispatchOrbit` (1309).
  - `supportLost` in `ThreatFleetOrders.poll` (false `navyHoldsOver`, which is false wherever the
    faction owns no front) skips raids; a raid stands down on `orbitContestedFor` instead, once
    arrived (friendly FP is 0 before arrival, so the bare test reads contested).
  - A raid needs its own key apart from `hasSupport`, so a front's Support and a raid do not block
    each other. Code paths: `strategy-code-paths.md` "What a raid order needs".
  - `tickSupport` (GF 4223) bombs unchanged.
- **`ThreatSoftening`:** `send` (662), the muster (950) and `divert` (1223) read reports.
- **`ThreatScouts`:**
  - a recon sortie to a named, already-found system, reusing `ThreatScoutRoute`;
  - the three gates that skip found systems (380, 228-231, 116) let recon through;
  - on arrival it calls `see`.
- **`ThreatFrontlines`:** the radar sweep, once a day, over links and military worlds (the same
  watchers `detectedAt` uses for strikes, 2352-2377).
- **`ThreatOutposts`:** the player's radar.
- **Player-facing readers switch to the player's reports:**
  - `ThreatWarBoard` (system rows 303-317 and 2028, colony cards 2517-2541);
  - `InfestedSystemIntel` (156-251);
  - `ThreatSwarmBountyIntel` (508-512);
  - `ThreatMissionIntel` (1172).
- **Docs:** `code-map.md` lines for the two new classes. `ground-war.md` and `frontlines.md` point
  here.

## 9. Decisions for the user

Answered 2026-10-01: "1 yes 2 yes 3 no 4 whatever recommended 5 yes", and 1 confirmed as "separate
change". So: the Threat keeps its remote reads for now (fogging it is its own later change); allies
pool reports as made and the player gets the reports of factions at Cooperative; sieges do not
divert in v1; off-screen sieges run a day at a time, built with the planner; tests run on ng7a clones.

1. **Fog the Threat too, now or later?** The swarm reads several things exactly and remotely:
   - each staging base's fuel and supplies, as siege FP against the hive it stages for, so the
     swarm's pressure rises before anything sails;
   - the FP of every NPC attack and order;
   - forward guards;
   - convoy FP and routes (`ThreatRaiders`);
   - a target's defenders and station.

   *Recommended:* later, as its own change, so each test reads one change. Until then the swarm
   still reinforces a staged target before the siege sails.
2. **Do allies share reports?** The options are (a) coalition partners pool reports as they are
   made, (b) they share only within a Call, or (c) they never share. A second question: does the
   player get the reports of factions at Cooperative?
   *Recommended:* (a), and yes for the player.
3. **Do sieges divert?** *Recommended:* not in v1; an outmatched siege goes home.
   - Its marines are sized for one world.
   - Diverting an off-screen siege means rebuilding vanilla's raid route in flight, which has no
     precedent and is risky.
   - Raids and hunts divert; that pattern is already built.
   - A siege the player is watching could split its fleets into orders that divert, which is
     moderate work. Watched and off-screen sieges would then behave differently.
4. **Off-screen sieges a day at a time (section 5).** Per-world sizing is only honest with it, and
   it is the biggest single item in the build.
   *Recommended:* build it together with the planner.
5. **Test base:** a clone of ng7a (month 38, current rules) instead of the lt save.
   *Recommended:* yes.

The rates (half-life 30 days, confidence 0.8, radar 10 ly) are first cuts. Tune them after a
test, not before.

## 10. Tests

- **Base save:** a new game under current rules, not the lt save. The lt save is month 144, and
  its swarm grew under the old hive sizes (7.5 against 3.6-5.0 today). The newest current-build
  game is ng7a (month 38).
- **Runs:** first a baseline of the current build on a clone of that save, then the planner
  build on another clone. Both are 3 chunks of 110 s (`short-test-runs`), and the free tags start
  at h49.
- **What the log must show:**
  1. Reports: who saw which system, by which source, with the figure and the age.
  2. Plans: each faction's operations, every prong's trust, and the plan's chance. Raids should sail
     beside sieges, and on news.
  3. Arrivals: commit, divert or home, with what was reported against what was seen.
  4. Raids: days bombed, days down bought, fuel spent, and why each one left (contested, fuel,
     stop).
  5. Sieges launched against core systems, which get none today.
  6. No exceptions.
- **The fog check:** a search of the sources finds `getFleetPoints` over garrisons only in
  `ThreatIntel.see` and in the on-site readers listed in section 1.
- **The board** (a subagent reads the screenshots): ages and `Unknown` rows, with no exact
  figure for an unwatched system.
