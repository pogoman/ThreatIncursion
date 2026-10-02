# War council - DESIGN (2026-10-01; decisions answered the same day, section 11; build in progress)

A strategic layer for the human factions: each one (and each coalition) holds a strategy for
months and runs multi-phase plays - a massed joint strike, a feint that draws the swarm while the
main force hits elsewhere, a bombing campaign that starves a system before an invasion - judged by
the damage they do. It replaces the attack planner's per-world allocation
(`attack-planner.md`), which reacts to every sighting and sizes each blow to the fleet points
it saw. Build: a later session (user, 2026-10-01: "write it up then will do in another session").
Decision: `facts.md` "Strategy, not FP matching"; memory `strategic-controller`. Sections 9 and 10
come from three code maps made the same day; section 11 holds the user's open decisions.

## Why

**What the user asked** (2026-10-01, after h52a): "There shouldn't be any sizing based on enemy
FP. There should be strategic plays. Either a massive joint strike invasion fleet or decoy attacks
and bombers/scouts etc. Then based on damage done maybe they invasion play or other coordinated
strategies. I want to stop this deterministic reactive behaviour." Relative strength should drive
it, read from "overall known worlds and scale of the spread, size of the known worlds", never fleet
FP. The same morning: ~300 FP bombers on systems left unguarded while a siege distracts the Threat.

**What the attack planner does instead:**
- It re-plans on news: in h50a, 95% of 1,466 plans were triggered by a sighting, a hunt or a
  resolved prong.
- It sizes every siege to the FP last reported over the world (x `npcSiegeOrbitMargin`).
- Its prongs are independent. Nothing sequences them, and nothing uses one blow to set up another.
- The same picture gives the same plan. A player who learns the rule can steer it, for example
  by keeping a garrison just over what a nearby base can pay for.

**What the runs showed** (h50a-h52a, ng7 clones, about 2 years each):
- Sieges sail on fresh reports (age 0 at almost every launch), but from bases 30-95 days away.
- On arrival, the swarm over a defended world (100+ FP reported) is often 2-5x the report.
- About a quarter of those sieges land, whether the margin is 1.0x or 1.5x. Worlds reported
  under 100 FP land about half the time.
- At call-off, the swarm stood at a median of about 1.4x the siege's FP in all three runs.
- The runs are noisy: h50a and h51a used the same sizing and landed 8 and 27.

A margin knob cannot fix a timing and coordination problem. That is the case for a layer that
plans campaigns.

## How other games do it

- **Layers.** Strategy AIs that feel smart split strategy, operations and tactics, and the top
  layer never weighs single fleets. Civilization V gives each AI a grand strategy (conquest,
  culture, ...), weighted by the leader's flavour plus randomness, and re-checks it only every
  so often, with hysteresis. Hearts of Iron IV keeps standing AI strategies and front plans.
- **Operations as the unit of planning.** Civ V's military AI runs named operations (city attack,
  naval invasion, sneak attack). Each picks a target, gathers its army at a muster point, moves
  together, then strikes.
- **Plans as recipes.** HTN planning (Killzone, Horizon) breaks a goal into alternative methods
  with preconditions. "Take that system" becomes a massed assault, or starve then invade, or
  feint then strike. The AI picks a method, not a fleet size.
- **Commitment and surprise.** RTS AIs pick an opening (rush, boom, turtle) by weighted chance and
  hold it until scouting shows it failing. Competition StarCraft bots choose strategies as a
  bandit problem, learning which ones work against this opponent.
- **Strength in aggregate.** Influence maps show who is strong where, by region.
- **The warning: Stellaris.** Its AI mostly compares fleet power and reacts, and players exploit
  exactly that.
- **In Starsector:** Nexerelin, the optional mod we stay compatible with, has a strategic AI that
  weighs concerns and answers them with actions on a slow cycle. Our `nexerelin.md` covers only
  compatibility.

## Principles

1. **Strategy picks the play, the play picks the forces.** Forces are shares of the faction's
   means. No layer above a fleet's own on-arrival judgement reads enemy fleet points.
2. **Commit, then check.** A play runs through its phases. News changes the picture, never the
   plan. Decisions are taken at phase checks and strategy reviews.
3. **Judge by damage done.** Phase checks count what the play achieved: swarm hulls sunk, days a
   Nexus or a forge was down, days an orbit was held, worlds landed on.
4. **Not deterministic.** Choices are weighted-random among the good options, with faction
   personality. Timings are jittered. Feints are deliberate.
5. **Aggregate strength, from what the faction knows.** Known worlds, their sizes and spread, the
   faction's own means and its coalition's. The fog of war stays: swarm FP comes only from
   reports, while planet facts (size, tier, industries) are live by design.
6. **Closed economy, no arbitrary ceilings.** Every fleet is paid from the pools, as now. A play's
   share of the means is a strategic allocation, not a cap.
7. **Fleets keep their tactical judgement.** A fleet that arrives hopelessly outmatched still
   turns home (`ThreatPurgeFGI.breaksOff`, `ThreatFleetOrders.raidOver`). That outcome is data for
   the play's check, never a reason to resize the next one. The gates that decide whether to go
   at all (a siege's orbit gate and fleet goal, a hunt's go-in check) are sizing, and a play's
   forces do not pass through them.

## 1. Layers

| Layer | Clock | Decides | Built from |
| --- | --- | --- | --- |
| Council (strategic) | assessment monthly, review every ~90 days | the strategy, which plays run, each play's share of the means | new |
| Play (operational) | its own phase clock, checked daily | targets inside its goal, the roles, when a phase ends | new, sequencing existing moves |
| Move (tactical) | as now | how a fleet fights, bombs, lands, turns home | sieges, raids, hunts, scouts, convoys, relief |

One council per mobilised NPC faction. A coalition adds a joint council for joint plays
(section 7). The player never has a council: the player is the player.

## 2. Assessment (monthly, coarse)

**The known swarm,** from the faction's reports (`ThreatIntel`):
- the hive worlds it knows, with their sizes and tiers and the systems they lie in;
- clusters: a core is a system with several hives, the frontier is isolated hives;
- spread: how many systems, and how far apart;
- distance from the faction's own colonies, and which hives can reach them;
- trend: known hives and sizes against the last assessments;
- how stale the picture is, cluster by cluster.

**Its own means:**
- the provisions its pools can spend on offense (fuel, supplies, marines) and what they bank a
  month;
- forward bases and links;
- colonies at risk, and Threat strikes suffered in the last 90 days;
- what running plays have already committed.

**Allies:** coalition partners at war and their means, coarse.

All of it is readable today without cheating (section 10 lists the reads). Nothing in the mod
keeps a history, so the council saves its own monthly picture to read trends from.

**Output: a picture, not numbers to size with.** For each cluster: its scale (small, medium or
large, from world count, sizes and tiers), distance and threat to us. Overall: our means against
the known swarm's scale, in bands (outmatched, even, ahead) with a hysteresis band so the verdict
does not flicker. Whether reported swarm FP may feed the scale as a coarse band (summed over a
cluster, never per fleet) is decision 2.

## 3. Strategies (held for months)

| Strategy | Fits when | Plays it favours |
| --- | --- | --- |
| **Hold** | outmatched, or pressed (colonies struck, Threat fronts on our worlds) | relief first, recon, bombers on the hives that threaten us; no invasions |
| **Starve** | outmatched or even, with a cluster in reach (outmatched added 2026-10-01 after h53b) | bombing campaigns that keep its Nexuses and defences down (and its forges, where the pools pay), then invasions of starved worlds |
| **Roll back** | even or ahead against the frontier | recon, then invasions of isolated hives one at a time (feint and strike where a neighbour can reinforce) |
| **Decapitate** | ahead, usually with a coalition, against a core | hammer, prepared by recon and bombers |

**Choosing:** each strategy is scored from the picture x the faction's personality weight x its
learned weight (section 6), then picked at random in proportion to the scores, with a
temperature knob. It is held until the next review (`councilReviewDays`, ~90). A challenger must
beat the current strategy by a margin, so the faction does not flip-flop.

**Early review**, outside the clock: a colony lost, a play's decisive success or failure, or a
coalition forming or breaking.

## 4. Plays

Every play has:
- a goal and a target (a system, or a cluster);
- roles: main force, feint, bombers, scouts, escorts;
- a share of the means;
- phases, each with a check and an abort condition;
- an outcome record, for learning.

A play does not re-plan on news. Its daily check reads the picture the reports keep current.

### 4.1 Hammer: massed joint strike, then invasion

Target: one system, usually a core. Share: most of the faction's means (decision 3), plus
partners' shares in a joint hammer.
1. **Prepare** (weeks): stock the staging base with the play's provisions, gather marines, and
   send scouts to refresh the target. Stocking telegraphs: the swarm reads staged stock exactly and
   reinforces the target before anything sails (section 9). Where a base reaches both, it stages
   against another hive and turns at the last.
2. **Muster** (deadline ~30 days, jittered): each faction's force gathers at its own bearing off
   the target, as hunts muster today, and the council holds them until all are in. It goes when
   the muster reaches its share, or at the deadline with what it has. Below a floor
   (decision 3), it disbands and the provisions go back. Forces from far bases can instead sail
   on dates that make them arrive the same day, as the planner's prongs do.
3. **Strike:** the whole force moves in together. The hunt forces fight for the orbits, and the
   lead faction's siege expedition, carrying the marines and sized by its share, comes with them.
4. **Exploit**, judged by damage done. The siege bombards and lands once its orbit is its own (the
   daily siege already works this way). The play's check at D days: orbits held and the swarm seen
   thinning means stay and land; otherwise everything withdraws and the play records what it sank.

### 4.2 Feint and strike

Two targets: B, the main effort, and A, the feint. A is a small known hive world near B, ideally
B's nearest hive neighbour: a fresh seed, or a size 2-3 world without a Bastion. Planet facts give
that, not FP. Which pair is chosen, and how long the feint runs, are random within the strategy's
options.
1. **Feint:** a raid or hunt at A, the play's small share, in plain sight. Within a posture pass
   (5-5.6 days) the swarm sends A fleets from its nearest colonies. Each colony that receives
   cannot donate for 30 days, and each donor is below its want until it refills.
2. **Watch:** the feint's own eyes see the transfers arrive (its reports at A rise). The swarm
   coming is the feint's success, even when `raidOver` then sends the raid home because the
   orbit is contested.
3. **Strike** inside the 30-day window. The main force strikes B from a base close to B that has
   not staged against B. Bombers take down B's Nexus as the main force arrives, so B cannot
   rebuild garrisons. They go then, not before the feint has drawn: a wounded system gives
   nothing to A.
4. **Exploit:** as the hammer's.

Before the Threat's own fog (decision 8), the main force is seen the day it sails, so it must
strike from close. With the Threat fogged, the feint deceives at any range.

### 4.3 Starve then invade: a bombing campaign

Target: a system's ability to rebuild and to produce. The war is won by cutting production
(facts, Decisions).
1. **Bomb:** squadrons of a doctrine size (~300 FP, the user's figure) keep the target's Nexus and
   defences down in rotation (`dispatchRaid` and its `raidOver` rules), with scouts watching. A
   colony with its Nexus down rebuilds no garrison and donates nothing. Forges, ports and fuel
   plants fall only to saturation: a siege expedition sent to bomb and not land, like the player's
   Bombard. The play adds one when its pools can pay; in h48a the NPC path never could.
2. **Check, every 30 days:** days of Nexus down achieved, swarms seen at the target (falling, or
   regrowing from transfers), and raids driven off. Driven off N times in a row means abort,
   recorded as a failure.
3. **Invade** when the damage done says so: the Nexus down for K weeks and the garrison seen not
   regrowing. That hands over to a hammer on the starved system.

### 4.4 Recon in force

Runs when the picture of a cluster is older than the half-life, or the cluster is unknown.
Scouts (`ThreatScouts.recon`), plus one probing raid. Short and cheap; its result feeds the next
assessment. Every strategy runs it before committing a big play to a stale picture.

### 4.5 Relief comes first (exists)

`ThreatFleetOrders.planRelief` stays as it is: a colony under a Threat front gets relief before
any play. Today `reliefOwed` blocks only new work and never pauses anything already running, so
the council checks `reliefOwed(faction)` itself. While relief is owed, it holds each play's next
phase. The play is paused, not cancelled.

### 4.6 Bombers of opportunity (bounded)

Inside Starve and Roll back, a small standing share lets bombers hit a hive the faction sees
unguarded. It is the one reactive element, and it is bounded by its share, not by a count.

## 5. Sizing: shares of the means

**Superseded 2026-10-02 (the user):** a play's siege is sized as the planner sizes one, from the
faction's report (`IncursionManager.siegeSizesFor`; facts.md "Where is a siege sized?"). Sizing from
a report is planning; the hack is reading the live garrison. Hunts, decoys and squadrons keep
their shares. The rest of this section is the 2026-10-01 design as first built.

**Overtaken for sieges on 2026-10-02 (the user's decision):** a play's siege - the hammer's, the
feint's strike, the starve's saturation expedition and its invasion - is sized exactly as the attack
planner sizes one (`IncursionManager.siegeSizesFor`: the strongest world in the faction's report x
`npcSiegeOrbitMargin`, the landing, the guns). Share-of-means sizing stays for the hunts, decoys
and squadrons only. Section 16, "Sized by the planner".

- **Means** are what the faction's pools can pay for the play's whole horizon: passage fuel,
  supplies and marines, at the prices sieges and sorties already pay (section 10). A force the
  depot cannot pay whole stays home, as now.
- **Shares** by play (doctrine, data-driven): hammer most, feint about a tenth, bombers in
  squadrons of the doctrine size, scouts at 20 FP. Personality and strategy shift them.
- **A siege's quality is already means-based** (`siegeDifficulty`, from the faction's own strength
  at the base). Only the fleet count reads the enemy, so a play passes its own fleet sizes.
  Marines stay sized to the world's ground defence: a planet fact, not swarm FP.
- **Gates that read enemy FP do not apply to a play's forces.** This means the orbit gate,
  `npcSiegeOrbitMargin`, the fleet goal and a hunt's go-in check. One override waives them for
  plays (section 10). The economic gates stay.
- **What stays FP-based** is only a fleet's own judgement on arrival. Relief keeps its sizing too:
  it is defence, done at once.

## 6. Not reactive, not predictable

- **Clocks, not news.** The council assesses monthly and reviews about quarterly. Plays move on
  their phase clocks. A report, a hunt or a resolved prong updates the picture and nothing else.
  The interrupts are a colony struck or lost (relief), a play's abort condition, and a coalition
  forming or breaking.
- **Weighted chance.** Strategies, plays and targets are drawn in proportion to their scores
  (temperature knob `councilTemperature`). Phase timings are jittered by about +-25%. The feint
  is deliberate deception.
- **Learning.** Each faction keeps a weight per play type and target class (core, frontier,
  production). A success multiplies it by 1.25, a failure by 0.8, within [0.25, 4]. It is saved,
  so a faction learns which plays work against this swarm and this player, as competition bots do.

## 7. Personalities and coalitions

**Personalities** are data (settings.json), with a default for factions the table does not name
(modded ones included). A first cut, for the user to mark up:

| Faction | Leans to | Temper |
| --- | --- | --- |
| Hegemony | Decapitate, hammer | patient musters, big shares |
| Tri-Tachyon | Starve, feints and bombers | precise, low risk |
| Persean League | Roll back, joint plays | coalition-minded |
| Sindrian Diktat | Starve | holds its fuel close |
| Luddic Church | Hold, roll back | stubborn, slow to switch |
| Independents and default | Hold, bombers of opportunity | cautious |

**Coalitions.** When allied factions are at war together (`ThreatCoalition`), a joint council
proposes joint plays, typically a hammer led by the partner with the most means. Today a
coalition Call is answered by a hunt sized from enemy FP on its own clock, with no marines and
nothing synchronised (section 10). A joint play needs its own path: the lead asks each partner for
a share, each partner musters its own force, and the lead releases them together. Each partner
keeps its own strategy for the rest of its means. Inviting the player to join a hammer (a muster
point and a date, standing for joining) is v2.

## 8. What stays and what goes

**Stays:**
- `ThreatIntel`: reports and the fog of war;
- raid orders, as bombers and feints;
- recon, as scouts;
- the daily off-screen siege;
- relief;
- hunts and swarm bounties: they stay bounty-driven in v1, and a play may also send a hunt as a
  move.

**Goes, or is replaced:**
- the attack planner's per-world allocation, its trust-and-chance prong arithmetic, and planning
  on news;
- FP-based sizing for a play's forces [reinstated 2026-10-02 from reports, section 5];
- `ThreatFactionStance.evaluate`'s FP ratio (decision 9).

**Kept off:** the monthly siege pass, as under the planner.

**Knob:** `warCouncil`. Off falls back to the attack planner as built, so runs can compare.

## 9. Does the swarm take the bait? (feint feasibility)

**2026-10-02:** radar is gone on both sides (facts.md "How does a faction see a hive system?"), so
staged stock and a sailing force are seen only by Threat ships present; a feint deceives at any
range and staging no longer telegraphs. The analysis below predates that.

Mapped 2026-10-01. The mechanism, by symbol, is in `hive-garrison-and-upkeep.md`, "How fast it
answers". **Yes, narrowly. A small feint does nothing.**

- **It saw everything at once** (until the swarm's fog, 2026-10-01, `threat-fog.md`). An attack
  counted at its target from dispatch, at any distance, and the posture pass that reads it runs
  every 5-5.6 days. A force staging against a system raised that system's pressure with its stock
  before anything sailed. Since decision 10 a booked off-screen siege counts too. With the fog on,
  each of these counts only once the swarm's eyes or scouts have seen it (radar too, until 2026-10-02).
- **What a feint pulls.** The feinted system's want is about 0.83x its pressure, and the deficit
  (want less what it holds) is what moves. So a feint pulls only when it outweighs the system's
  garrison by about 1.2x, or when it sinks swarms. A thinly held world makes the feint cheap: a
  fresh seed holds one swarm of 63-67 FP.
- **Where it pulls from.** The system's own colonies first, then the nearest colonies with a spare
  fleet, one whole fleet per donor per loop. They are real fleets and land in 0-15 days. A donor
  must be quiet (below THREATENED) and hold its own want. For a system under attack, it may go one
  fleet below its want, never below its reserve.
- **How long the opening lasts.** A donor refills one swarm per tick (about half a day) while its
  bank pays and its Core and Nexus stand, so a banked donor is thin for only 1-5 days. What lasts
  is the colonies that received transfers: they cannot donate for 30 days.
- **No other answer.** There are no reply swarms. Retaliation comes only after a hive is
  eradicated. Raids and strata add grudge, which reweights the Threat's strike targets. Under many
  attacks, its stance turns CONSOLIDATE: forges stay home and strikes become spoiling blows.

**What that means for plays, before the Threat's fog:**
- A feint cannot hide the main blow. The main force is seen the day it sails, and its target is
  reinforced within about 5-20 days (one posture pass, then the transfers' travel). So the main force strikes from close, never stages against its real
  target, and sails inside the window. A base stages against one hive (`ThreatConvoys.stagingHive`),
  so staging against the decoy is a lever the council can own.
- The feint works on the network, not on B's own garrison. A's colonies that receive are locked
  as donors for 30 days, and the donors that sent are below their want. If A is B's nearest
  neighbour, B's help must then come from farther away.
- Bombers on B's Nexus stop B rebuilding and stop it donating. They go in after the feint has
  drawn, with the strike: a wounded system is BESIEGED and gives nothing.
- The hammer and starve-then-invade need none of this. Feint and strike gets much stronger once
  the Threat is fogged.

## 10. Where it lands in the code

Mapped 2026-10-01 by symbol. Where a doc disagreed with the source, the docs were corrected the same
day.

**Reads for the assessment** (all fog-safe):

| Picture | Read |
| --- | --- |
| known systems and hive worlds | `ThreatAttackPlanner.knownSystems()` filtered by `ThreatIntel.known(observer, id)`; the keys of `ThreatIntel.report(observer, system).worlds`; pooled with partners (`sharersOf`, `partnersOf`). Only the player and mobilised factions hold reports (`observers()`, `observerOf`). |
| sizes, tiers, industries | live planet facts: `MarketAPI.getSize()`, `SwarmBastion.tier` (1 Bastion, 2 Command), industries; `ThreatColonyManager.nominalGarrison` prices a world no report covers |
| staleness | `ThreatIntel.age`, `trust` (half-life `intelHalfLifeDays` 30) |
| our means | `ThreatReserves.factionStock`, `spendable`, `available`, `accrualPer30` (banked surplus a month, not gross output), `armedMarines`; `IncursionManager.siegeBasesFor`, `siegePooled`, `siegeDonors`, `expeditionRangeLY`; `ThreatConvoys.fundingInReach` (stock plus 6 months' accrual) |
| links and forward bases | `ThreatFrontlines.all()` (the saved links). Not `isFront` or `frontOf`: they read live hives, `threatStrength` and `facedFaction` |
| pressure on us | `ThreatWarState.get(fid)`: `lastStruckTimestamp` (the mobilisation time until a first strike), `strikesSuffered` (lifetime: the council diffs its monthly snapshots); the stance's pressed test and `trend` (FP lost and sunk, 60-day decay: damage done, usable) |
| reach | `ThreatWarState.hiveInReach(faction)` (one boolean); per colony, max(`expeditionRangeLY`, `ThreatColonyManager.fuelRangeLY`) against known hives |
| allies | `ThreatCoalition` partners, `ThreatWarState.warFactionIds()` |
| template | `ThreatFrontlines.census()` already prints colonies, sizes, links, bases, reserves and stance per faction every 30 days (debug log) |

**Never read** (live Threat state; reading it is cheating):
- `ThreatReach.facedFaction` / `facedLY`;
- `getAllLiveColonyMarkets`, `totalColonySize`, `countColonies` (they count unfound hives);
- `ThreatFuel.*`, `ThreatColonyUpkeep.*`, posture wants, `computeHealth`, `threatStrength`,
  `siegeOrbitWeighed`;
- `ThreatFactionStance`'s ours/theirs ratio: it compares fleet FP.

Note that "found" is sector-wide, not per faction (`attack-planner.md` section 11).

**Moves to reuse as they are:**
- **Raids:** `ThreatFleetOrders.dispatchRaid(faction, hive, base, need, days, fallbackId)` takes an
  explicit FP and days, paid whole through `buildSortie` or not at all.
  - Lifecycle: `raidOver`, `divertRaid`, `endRaid`; `tickSupport` -> `supportSlice` ->
    `siegeSlice`; `ThreatReturns.sendHome`.
  - `sailPending`'s pre-checks (`sortieReachFP`, no raid there, not booked) are the template for a
    play's sailing check.
  - A raid wears the war-strata only: defence structures, the Nexus, and the Bastion or Command.
- **Sieges:** `IncursionManager.launchSiegeExpedition(base, faction, system, targets, fleetSizes,
  playerCommissioned, random[, marineGoal[, razeGiven]])` takes the play's fleet sizes.
  - The player's Siege and Bombard buttons are the precedent; `launchPlanned` takes no size.
  - `razeGiven` with no landing is a bombing expedition, the only way to saturate production.
  - `expeditionWants` prices exactly what a launch will draw.
  - On success, the launch posts a coalition Call.
- **Pricing:** `ThreatFleetOrders.sortieReachFP(base, destHyper)`; `ThreatSoftening.payableFP(base,
  system, donors)`; `IncursionManager.expeditionFuel`, `siegeSuppliesPerPoint`. Fleets fold into one
  up to `softenMergeMaxShips`.
- **Staging downstream:** `ThreatConvoys.planLogistics` fills a base to `stagingTargets`, and
  `ThreatReserves.stagingBank` fences that stock from hunts and donors. `ThreatConvoys.dispatch`
  hand-pushes a load; it is unfenced unless the target is raised.
- **Arriving together:** the planner's `Prong` `departDay` / `arriveDay`, `sailPending`,
  `siegeArrival`. Only the kinds SIEGE and RAID exist.
- **Also as they are:** relief (`reliefOwed`, `planRelief`), `ThreatCoalition.post`,
  `ThreatScouts.recon`, and the hunt machinery: `Force` (saved), `atMuster`, `sendIn`,
  `mergeInto`, `standDownAll`, and `dispatchHunt`, which takes any FP and muster.

**Moves that need a change:**
- **One per-play override of the enemy-FP gates.**
  - Readers: `siegeOrbitNeeded`, `siegeFleetGoal`, the `mustPay` / `strGoal` re-checks, and the
    hunt go-in (`ThreatSoftening.advanceForce`).
  - `siegeOrbitNeeded`'s readers must agree: the launch, `siegeFleetGoal`, `siegeAffordable`,
    `orbitBounty`, `ThreatFleetOrders.dispatchOrbit`, `ThreatConvoys.supportFor`.
  - Today only global switches exist (`npcSiegeOrbitGate`, `npcSiegeFullStrength`,
    `siegeBreakOffRatio` 0), and they would change the planner and hunts too.
- **`breaksOff` counts the play's own forces.** It stays as the judgement on arrival, but should
  count the play's other forces in the system, as `raidOver` now counts the raid's own fleets.
  Today it ignores a hunt beside the siege.
- **A hunt sized by the play.** `ThreatSoftening.send` sizes from enemy FP (max(`musterFloorFP`,
  `siegeOrbitFP` x margin)).
  - Add an entry that takes the play's FP, bases and muster point (`Force` already stores
    `musterX/Y` and the entity).
  - `contributors()` pools only bases that are not resting and have no launchable siege, so a play
    needs its own list.
  - A `Force` is one faction, with its own bearing (`musterPoint`), so a joint hammer is one force
    per partner, held at the go-in until all are in.
  - Hunts fight swarms only; the marines go in the siege.
- **Staging chosen by the play.** `ThreatConvoys.stagingHive(base)` is the nearest known hive the
  base is `pickBase`'s nearest for. `siegeStock` sizes the stock as a full `siegeWants` flotilla x
  `stagingTargetMult` 1.5, which is enemy-sized. Neither has a setter, and both are memoised for a
  day (`forgetStagingTargets`). A play needs an override in `siegeStock` (base -> its system and
  sizes).
- **Coalition answers.** `answerCalls` (monthly, `coalitionSupportChance` 0.5 per partner) sends
  an enemy-sized hunt on its own clock. A joint play adds its own path; Calls stay for everything
  else.
- **Relief does not pause running work.** The council calls `reliefOwed` before each phase.

**Clock and state:**
- **Hook:** `IncursionManager.advance`, just before `ThreatAttackPlanner.poll`, over
  `ThreatWarState.warFactionIds()`.
- **Day stamps:** the manager is transient, and its monthly `tick()` fires on the first poll after
  every load, so the council keeps saved day stamps (as `ThreatFrontlines.poll`'s `KEY_LAST_*` and
  `Plan.lastPlanned`). "Never" is -Float.MAX_VALUE: the calendar's days are negative.
- **State:** a saved map per faction (strategy, since, learned weights, plays with phase and
  stamps) in primitives, as `ThreatAttackPlanner.Plan`. Statics are cleared in
  `ThreatIncModPlugin.onGameLoad`.
- **The stance:** `ThreatFactionStance` keeps its readers (`siegeAllowed`, `target`, `foundsLinks`,
  `pressedFirst`, `pressingFirst`); where its value comes from is decision 9.

## 11. Decisions (answered 2026-10-01)

Answers 1, 6, 9 and 10 were the user's choices; the rest were taken as proposed.

1. Plays in v1: all four (hammer, feint and strike, starve then invade, recon in force), plus
   bombers of opportunity.
2. Scale of the swarm: worlds, sizes, tiers and spread only. Never summed swarm fleet points.
   [2026-10-02: for the assessment. A play's siege is sized from the report's fleet points, never live ones.]
3. Shares: a hammer gets 60% of the means. Its muster disbands below half its share.
4. Personalities: the table in section 7.
5. Randomness: temperature 1, with learning on.
6. Coalition hammer: in this build, built last.
7. The Threat: its AI stays as is.
8. Feints: the close-range form of section 9 now. Feints at any range wait for the Threat's fog.
9. The stance: the council sets it. Hold is CONSOLIDATE; Starve, Roll back and Decapitate are
   PRESS; EXPAND when no play runs. `evaluate`'s ratio no longer applies to governed factions.
10. Off-screen sieges: count booked sieges, spawned or not, as the swarm's attack fleet points.

## 12. Player's view (less is more; built as `ThreatWarCouncil.strategyLine` and the play rows of `ThreatFactionView.fleetRows`)

- The faction view gets one line, e.g. "Strategy: Starve (Gamma Sonora), since m14".
- Each running play is an operations row, e.g. "Hammer on Gamma Sonora: mustering at Mazalot,
  12 d".
- Tooltips are one line per fact. No new notifications in v1 beyond what the moves already send.

## 13. Logging and tests

**Log lines**, for the digest:
- monthly: `Council <f>: picture <known hives, systems, cores, frontier, stale>, means <...>, <band>; strategy <S> since <date>`;
- on a change: `Council <f>: strategy <S> -> <T> (<scores>)`;
- per play: `Play <id> <type> <f> at <target>: <phase> -> <phase> (<why>)`, and at the end
  `Play <id>: <outcome>` with the damage done.

**Tests:**
- ng7 clones, 3 x 110 s, as h50a-h52a.
- The digest counts plays by type, the phase each reached, and outcomes; strategies held and
  changed; landings, hives eradicated and provisions spent per landing, against h51a/h52a.
- Two runs of the same clone should choose different plays.

## 14. Build order (built 2026-10-01, session fbb85ee0)

Built in this order: the per-play gate and staging overrides; the council skeleton with its
logging; recon in force; starve then invade; hammer; feint and strike in the close-range form;
personalities, chance and learning; the coalition hammer; the UI lines.

## 15. Build map (2026-10-01, the build session)

Five maps made at the start of the build, by symbol. They correct section 10 where it differs.

**Siege launch.** `IncursionManager.launchSiegeExpedition` (7, 8 and 9 arguments) uses a caller's
`fleetSizes` as given: never grown, only trimmed (never below two fleets) when marines or
provisions fall short. Its enemy-FP reads, all inside `isAtWar`, are the orbit gate (`fieldable <
siegeOrbitNeeded`: postpone and post the bounty) and `siegeFleetGoal` feeding `mustPay`. `strGoal`,
`siegeWearFP` and raze fleet points read the world's defence and structures: planet facts. With
`razeGiven` null, `razeWorlds` sizes a hypothetical flotilla from swarm FP to choose raze or land,
so a play passes its own raze set (empty: land everywhere). Prep is 7-14 days (`prepDays`) before
travel. `siegeOrbitNeeded` sees only (faction, targets), so the play waiver is an argument of the
launch, not a branch in `siegeOrbitNeeded`. Its other readers serve front support, never a siege or
hunt (`ThreatConvoys.supportFor` -> `ThreatFleetOrders.dispatchOrbit`).

**Siege outcome.** The launch returns the `ThreatPurgeFGI` (null on any refusal). The expedition
has no owner field and no end reason: `callOff`, `outOfSupplies` and `abortPurgesAgainst` all
`setFailedButNotDefeated(true); abort()`. Every end path runs `notifyEnding()` once. Landings are
records in `siegeActions` ("Ground landing", success). A world taken is not an expedition event: it
is `ThreatGroundFronts.hiveGroundVictory` (eradication). The purge list (`getPurgeList`) is never
pruned, and the war board addresses it by index. An attached play keeps its expedition through
`playId` (section 16).

**Hunts.** `ThreatSoftening.Force` is saved (`forces()`; public fields, so new ones load as
defaults). It has no hold flag: a force goes in when all its fleets are at the muster or
`softenMusterDays` after the first arrival. `send` sizes from reports (`musterFloorFP`,
`siegeOrbitFP` x `margin`) and pools `contributors()`. `ThreatFleetOrders.dispatchHunt(faction,
base, hiveWorld, fp, forceId, muster)` builds one fleet with no payment gate: `send`'s
`payableFP` budget is the gate. In `advanceForce` the go-in, the move-on and `divert` read reports x
margin, and `divert` can move a force to another system. A hunt records no damage: only `baseFP`
and its live `presentFP`. A force that is gone from `forces()` is over.

**Raids.** `ThreatFleetOrders.dispatchRaid` pays whole from one base, with no donors, and returns
the lead Order (`raidId` shared by its fleets). `raidOver` ends it for one of these: term served,
world lost to the swarm, orbit contested, out of ordnance, a day buys less than a day down, or a
third of the arrival FP lost. `endRaid` keeps no reason. Damage lives only in the structures' live
clocks (`ThreatColonyManager.hasOperationalNexus`, `ThreatGroundFronts.siegeDisruptDays`), so a
play samples them daily.

**Scouts.** `ThreatScouts.recon(fid, system)` sends one 20 FP party, paid whole, or returns null.
On arrival it writes an exact SCOUT report (`ThreatIntel.see`).

**Reports.** `ThreatIntel.report` already pools partners' reports (newest wins), and keeps
`float[]{FP, fleets}` per world, with no history. A play keeps its own baseline.
`ThreatCoalition.partners(fid)` is recomputed on each call and never saved.

**Staging.** `ThreatConvoys.stagingHive` is not memoised and has eight readers, the swarm's
pressure read among them. `siegeStock` is memoised per base for the day (`targetsMemo`) and feeds
fill, fence, donor keep and relays. A play's staging goes in at `siegeStock`'s cache miss (section 16).

**The swarm's read of attacks.** `ThreatPosture.attacksBySystem` counts a siege's spawned fleets,
and since decision 10 a booked siege's abstract flotilla too (`countsAbstract`).

**The stance.** `ThreatFactionStance.refresh` has three callers (the planner's poll,
`ThreatFrontlines`, the legacy pass). `TARGET` is not saved, so after a load the council sets it
again. Under CONSOLIDATE, `siegeAllowed` still reads the live `facedFaction`.

## 16. As built (2026-10-01, session fbb85ee0)

What long runs showed, and the fixes since: `war-council-runs.md` (pd2a: Hold won 70% of months,
bombers learned nothing; both fixed 2026-10-01).

`ThreatWarCouncil` is the strategic layer and `ThreatPlays` the operational one. Both are saved in
`ThreatIncData` (`KEY`, `ThreatPlays.KEY`), both only run while `warCouncil` is on, and
`ThreatWarCouncil.reset` clears them along with the play staging.

**Daily order.** `IncursionManager.advance` runs `ThreatIntel.poll`, then `ThreatWarCouncil.poll`,
then `ThreatAttackPlanner.poll`, which is a no-op while the council is on (`ThreatAttackPlanner.active`).
`poll` steps each governed faction (`governs`: an NPC at war) in this order:
1. `assess`, monthly or on the first day after a load, builds the `Picture` (transient, in `PICTURES`).
2. The early-review triggers fire on a colony lost or the coalition changed.
3. `record` keeps 24 monthly rows.
4. `review` runs when due and draws the strategy.
5. `ThreatPlays.plan` starts plays.
6. `setStance` sets the stance.
7. `logPicture` logs the picture.

Then `ThreatPlays.advance` steps every play. The monthly siege pass (`tryPurgeBombardments`) and
the stance's own evaluation (`ThreatFactionStance.refresh` skips governed factions) stay off. The
council writes the stance through `ThreatFactionStance.set`.

**Sized by the planner (2026-10-02, the user's decision; simulator round 11 showed share-sized
plays killing 0-2 hives a run).** `ThreatPlays.strike`, `saturate` and `stage` call
`IncursionManager.siegeSizesFor` - the planner's own sizing (`launchPlanned` calls the same): the
swarms the faction's report shows over the strongest world the siege takes x `npcSiegeOrbitMargin`
(`siegeFleetGoal` -> `siegeOrbitNeeded` -> `siegeOrbitFaced`), the landing the ground defence
needs, the guns of the worlds it razes, fitted to the base (`fitExpedition`). `saturate` passes its
raze set (every target) through the new `siegeSizesFor(base, faction, targets, marineGoal,
razeGiven)` overload; `playSiegeSizes` and `fundingFP` are gone, and so is every
`councilHammerShare` / `councilStarveShare` x `siegeCapacityFP` siege figure. Nothing reads a hive's
live garrison: the report alone. The hunts (`toMuster`, `inviteJoint`, a feint's), the feint's
squadron and the bombing budgets keep their shares. With no report of the target (`strike`), the
play sends `ThreatScouts.recon`, moves to `muster` and waits until `Play.sizeBy` (twice the
passage + 15 d) for one; it fails on that day without one. `plan` starts a RECON play instead of a
big one on a focus with no report (clusters are built from known systems, so this is a guard). The
strike's log line names the reported figure: `siege of N FP sails ... against M FP reported over
the strongest world`.

**The gate override is the `playId`.** These read it:
- `launchSiegeExpedition`'s 10-argument overload skips `siegeOrbitNeeded` (no postponement or
  bounty: the council decided when, and its hunts go in beside the siege) and the Coalition Call.
  Since 2026-10-02 `siegeFleetGoal` weighs the orbit for a play's siege too, so the provisions trim
  never cuts the orbit's fleets; the pool pays them or the launch postpones.
- `ThreatPurgeFGI.playId`: its break-off reads count the play's hunts in the system as its own
  (`friendsNear` -> `ThreatSoftening.playFP`).
- `ThreatSoftening.sendPlay` builds a `Force` with `playId` and `hold`. It shares `send`'s helpers
  (`newForce`, `build`, `foldAll`) and pays from `playPayableFP` with no report sizing.

In `advanceForce` a play's force:
- stays put while `hold` is set;
- once let go, goes in at `playTarget` with whatever has mustered (all its fleets count, so
  "badly hurt" is measured from the whole);
- moves on through the play's worlds with no need test or `divert`;
- stands down when its play is gone or the council is off.

**Staging override.** `ThreatConvoys.stageForPlay(base, playId, system, wants)` makes
`stagingHive` name the play's system and `siegeStock` stock to the play's wants. A feint stages
against the decoy A. `clearPlayStaging` ends it when the siege sails or the play ends.

**Raids report back.** `ThreatFleetOrders.endRaid`, `standDown` and the destroyed path call
`ThreatPlays.raidEnded`, which books each `raidId` once. "Driven off" covers an orbit contested, a
third lost, and destroyed. A BOMBERS play ends with its raid.

**Phases.** `Play.phase`, with the deadline in `phaseDue`:
- HAMMER: `prepare` (stage, a scout if the report is stale) -> `muster` (`toMuster`: held forces,
  `inviteJoint`) -> `strike` (`strike`: the siege sized by `siegeSizesFor` (planner sizing, 2026-10-02),
  target sets from all worlds down to one; hunts let go at estimated arrival - `RELEASE_LEAD_DAYS`, or sooner on the siege's live
  ETA, `siegeNear`) ->
  `exploit` (up to `MAX_EXTENSIONS` x `councilExploitDays`; a feint's bombers may sail here too) ->
  `withdraw` (judged when the siege ends, or a check later).
- FEINT: `watch`. The baseline is A's report on the day the squadron is over it (`feintArrived`),
  and only a later report at `DREW` x that counts. Then `strike` from a base within
  `councilStrikeMaxDays`, with bombers on B's Nexus.
- STARVE: `bomb` checks every `councilStarveCheckDays`. It aborts on `councilStarveAbortRaids`
  driven off in a row, hands over to a hammer (`fromId`: the starve's own siege is not in its way)
  once a Nexus streak reaches `councilInvadeNexusDays`, and ends after `STARVE_MAX_CHECKS`. Its fuel
  budget is `councilStarveShare` of the means each check; squadrons go only where `bombable`, and the
  saturation siege sails from `richestBase`, sized by `siegeSizesFor` with its raze set (2026-10-02). It does not start if it can pay neither a
  squadron nor a saturation siege. Under Starve, `plan` also weighs a hammer (x2) while the focus's
  Nexuses are down.

A muster below `councilMusterFloor` of its share disbands as a failure. Relief owed adds a day to
`phaseDue` in prepare, muster, bomb and watch. `liveTargets(..., own(pl))` skips worlds booked by other
sieges, but not the play's own. A world under another siege ends a play as neutral; a world no longer
a hive calls `finish`. Probing raids share the monthly opportunity fuel (`oppLeft`). A recon on a system
waits an intel half-life (`Council.reconDay`), after which the big play goes on the picture it has.

**Outcomes.** `finish` and `end` decide the outcome:
- success: a world taken, a landing, or `nexusDownDays` >= `councilInvadeNexusDays`;
- failure: a disbanded muster, a starve aborted, a siege refused;
- neutral otherwise, and every RECON.

A decisive end calls `learn` on `TYPE:targetClass` and `strategy:S` (x1.25 or x0.8, clamped to
0.25-4) and asks for an early review. A JOINT play learns nothing. A play that throws is ended
(`advanceOne`), and so is one left without a phase.

**Log lines** to grep:
- `Council f: strategy A -> B (...)`;
- `Council f: picture ...` and `Council f: S focus ...`;
- `Play id TYPE f at target: a -> b (why)`;
- `Play id: outcome (why; damage, N d)`;
- `Play id force ...` (ThreatSoftening);
- `Faction stance: f A->B - war council: ...`.

Knobs are `threatinc_council*` in settings.json. Personalities live in `threatinc_councilPersonalities`.
