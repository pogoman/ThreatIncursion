# The swarm's defence - restraints removed, strikes come home, the defence massed, the system as one

Split from `hive-garrison-and-upkeep.md` "Posture" on 2026-10-04: that section holds the pressure, want,
modes and transfers these build on. Test results: `game-runs.md` 4-7 (the cover rally: `game-runs-2.md` 9). The
hive picket that sees a siege coming is in `threat-fog.md` 4.

- **The swarm's restraints removed (2026-10-04, built 68d019a, tested hw7: `game-runs.md` 5; the user: "remove
  artificial constraints on the swarm like we just did for humans"; diagnosis `game-runs.md` 4).** Each
  rule keeps a knob that puts it back.
  - `postureStagedShare` 0 (was 1): a depot's capacity is no pressure. `poll` reads max(A, B x share).
  - `postureTriage` false: no system is written off; its need is never zeroed.
  - `postureNeedAtAttack` true. (a) `overWorlds`: once a force is over a world - hostile fleets within
    `ORBIT_HOLD_RANGE` (`ThreatGroundFronts.hostilePointsNear`) plus the unspawned sieges in their payload
    over it (`siegesOver`, `ThreatPurgeFGI.abstractNow`) - the system's need is split by those points;
    a world with an army on it and nothing over it weighs as their average; a force under a tenth of
    the pressure (`OVER_MIN_SHARE`, a scout) singles nothing out, and with no world singled out the
    tables split it as before. The orbit is contested at the world (`orbitHeld`), so the tables' split
    left the attacked world its own garrison. (b) `redistributeByPressure`: for a receiver under attack,
    a donor whose own need is 0 gives down to its reserve (`thinnableFP`), its launch stock included
    and whether or not it holds its own want - same system first. Before, only a colony of a system not
    pressed, and only one at its want. (c) `ThreatPosture.alarm` (from `ThreatPurgeFGI.takeDaily`): a
    siege arriving makes the next pass due at once.
  - `posturePressedForgesHome` false: a pressed system's forge may send a wave and counts toward a
    claim; `launchSpareFP` still limits it to what it holds above the need. The appetite's forge
    count takes every system's forges.
  - `stanceConsolidateSpreadShare` 1 (was 0) and `stanceConsolidateStrikes` true
    (`ThreatStance.expansionShare`, `strikeTargetMult`): consolidating, the hive still claims, and
    strikes any world by its weakness, a base staging against a hive or a forward base weighing
    x`TARGET_WEIGHT`. `StanceRules.expansionShare` (shared with the frozen simulator) is unchanged;
    the mod no longer asks it about CONSOLIDATE.
  - `strikeGuardWhole` true: `ground-war-orbit-control.md`, "The swarm guards its unspawned landings too".
- **Strikes come home (2026-10-04, the user: "should be able to deviate those that are convenient", not
  one "already mid siege or close to its target"; built, game-tested hw8: `game-runs.md` 6).** `poll` lists each
  system that is attacked (attacks, hostiles or losses) and short of its need by more than its cheapest
  swarm; `recallStrikes` calls in, shortest system first and nearest strike first, each strike within
  `postureRecallLY` (10, 0 = off; Luna "Strike Recall Range (LY)") that is `ThreatStrikeFGI.recallable`
  (pre-launch, PREPARE or TRAVEL; not in its payload, not with a guard left, not returning) and nearer
  the system than its first target, whichever hive launched it, until the gap closes. A strike comes
  whole: `recallTo` builds an unspawned one where its route stands (`spawnFleets`, the bank settling as
  at a spawn), detaches every fleet, heaviest first to the world still shortest
  (`ThreatColonyManager.sendToGarrison`: off the ledger, a reinforcement's blinders, seated by
  `checkReinforcementArrivals`), then aborts as a withdrawal with nothing to re-bank. A strike spends
  `prepDays` 7-14 at its colony, so one launched just before a siege is seen is usually still there.
  `ThreatSwarmIntel.note` calls `ThreatPosture.sighted` on a siege's first sighting: the pass runs that
  day. Log: `Posture: strike recalled to <system> - N FP in M fleet(s), ...`.
- **The defence is massed (2026-10-04, the user: "If you recommend that then do it and run another test";
  built 2f0b270, game-tested hw9 and OFF since: the swarm was exterminated in 3 of 3, `game-runs.md` 7).**
  hw8 massed by a side door: a strike mustered its system's spare swarms and the next pass recalled it
  (`game-runs.md` 6). With `postureMass` on the pass does it itself, in this order at the end of
  `ThreatPosture.poll`:
  1. `massWithin`: each attacked system with a world short of its own need by more than a swarm sends its
     colonies' spare fleets there. Spare is the launch's measure (`ThreatColonyManager.spareFleets`): the
     largest fleets above the `garrisonReserve` count, within held - need where the colony has a need of
     its own, out of battle, and without the `regrowing` gate. Shortest world first; the lightest fleet
     that covers the gap, else the heaviest. The call then stands at the sum of what its worlds still
     lack, not the system's need - held: a reserve on a sibling world is not over the world attacked.
  2. `recallStrikes`, unchanged, for the calls still short by more than a swarm.
  3. `massFromNeighbours`: still short, the spare fleets of the hive systems within `postureRecallLY`
     that have no call of their own, nearest first, each paying its one-way passage (`sendReinforcement`).
  4. The launch hold (`strikeCapFP`, read by `IncursionManager.launchStrike`): no strike sails from a
     system whose call is still short, nor from one within `postureRecallLY` of such a system unless its
     target is nearer than that system; an attacked system launches only what it holds above its need.

  The massing does not read the transfer pass's anti-circuit rules (`recentlyReceived`, `recentlyMoved`,
  `canRebuildGarrison`) but marks what it moves (`noteTransfer`). The transfer pass
  (`redistributeByPressure`) could not mass: it reserves in fleet points (`thinnableFP`), so a Bastion
  world full of light fleets read as below its minimum while a launch, which reserves by fleet count,
  took most of them. Knob `postureMass` (false since hw9 = the hw8 build, launch then recall). As built it
  masses to a need 2.4-4.3 times the siege over the world, strips every system in range, leaves nothing to
  seed or strike, and still arrives after the siege; its mend is the next bullet. Logs:
  `Posture: <donor> massed N FP at <world> (G FP short of NEED)`, with `, X ly` before the bracket from
  a neighbour, and `Strike from <colony> held: <reason>`. Judgement calls, not the user's words:
  neighbours give, the reserve count stays home, the call is read per world, a recall stays whole.
- **The system defends as one (2026-10-05, the user: "I'm assuming they defend the system just don't know
  the exact world until the attack arrives ... Why don't they just defend the jump point then"; of the two
  shapes offered, "simplicity and the middle version"; built `eb7a1f1`, game-tested hw10a-hw10c).** The fight stays at the world -
  no battle at the jump point - but the world's siege meets the system's force:
  1. `ThreatPosture.rally(world, attackFP)`: a force is over a hive world, and the system's other worlds
     send it their spare swarms - every fleet above each one's `garrisonReserve` count
     (`ThreatColonyManager.spareFleets(c, false)`: the colony's own need does not hold them), out of battle;
     a world with a hostile fleet near it, an unspawned siege over it or an army on it sends none. The
     lightest fleet that covers the gap, else the heaviest, until the Threat points at the world plus those
     already bound for it reach the force x `systemDefenceMargin` (1.25; 0 = every spare swarm). The
     unspawned sieges at the world that day weigh together (`siegesAt`). Sent by `sendReinforcement`, so
     seated on arrival and marked as a transfer.
  2. Called by an unspawned siege each day it fights (`ThreatPurgeFGI.dailyDay`, with its own points plus
     its play's hunts in the system) and by the posture pass for every colony with hostile fleets near it
     (`poll`: hunts, a spawned siege, the player's fleet).
  3. `ThreatPosture.defenders`: a siege the swarm had in sight when it came down (`seenComing`, read in
     `takeDaily` from `ThreatSwarmIntel.inSight`; always, with the swarm's fog off) fights the fleets at the
     world and the swarms bound for it anywhere in its system (`boundFor`: `REINFORCE_TARGET_KEY`), from
     its first day - weighed for the call-off, struck in the exchange, counted for the contested orbit.
     One that came unseen fights them as they arrive, as before.

  Against hw9's three faults: it sends what the force over the world calls for, not the system's whole
  pressure; only its own system gives; and the swarms count the day the siege comes down, not 2-4 days
  later. A human system already defends this way off-screen (vanilla's autoresolve weighs every defender
  in the system). The humans learn it as they learn any swarm met: a called-off siege books what it
  faced (`noteSwarmsMet`), and the next is sized to that. Knobs `systemDefence` (true),
  `systemDefenceMargin` (1.25); `postureMass` stays off. Logs: `Posture: <donor> rallied N FP to <world>
  (H FP stood against A)`, and `Daily siege takes over ... , seen coming|unseen`. My calls, not the user's
  words: sized to the force (so the rest can answer a second siege), each world's reserve count stays
  home, real forces (hunts, the player) draw the rally too, an unseen siege gets no first-day force.

  In the game (hw10a-hw10c, `game-runs.md` 8): it works as built - every siege seen coming, 164-2,902
  fleets rallied a run, sieges called off 20-56% (hw8 10-26%), the trade 1.25-1.35 Threat FP a human FP
  (1.34-1.45) - and the outcome is hw8's, one runaway and two exterminated. A siege that meets the massed
  defence turns home the same day with 87-98% of its FP (`callOff`: outweighed, it pays nothing), where
  one that fights and is beaten leaves 72-74% behind: the rally saves the world and spares the fleet.
  Proposed, the user's call: a called-off siege pays a day of the exchange as it turns away.

  The cover over a landed army draws the rally too (2026-10-05, built after hw10, game-tested hw11a-hw11c). A hive takes
  1-5 months to fall once an army is down (`game-runs.md` 8, `fall.pl`), and an unspawned landing leaves
  its flotilla over the army as an abstract figure (`GroundFront.coverFP`, median 0.9-1.5k FP) that
  `hostilePointsNear` does not see: hw10's rally stopped the day the army landed, and the swarm outweighed
  the cover in 7 of 16 / 3 of 31 / 0 of 21 landings. `poll` now adds `ThreatGroundFronts.coverOver(c)` to
  the force it rallies against; once the swarms over the world outweigh the cover it is lost
  (`swarmOrbitContested`) and the swarm bombards the army (`tickSwarmBombard`).
  hw11 (`game-runs-2.md` 9): the rally reached 3 of 19 / 11 of 45 / 0 of 21 covered landings (11 / 32 / 0
  fleets, against 1 / 1 / 0), the covers fell no more often (1 / 8 / 3) and 9 of the 12 hives whose cover
  was lost were eradicated anyway. Read from `rally`: the donors it excludes (an army on them, a siege over
  them, a hostile fleet near) are the system's other worlds when the humans land on them together.

  Only when it is enough (2026-10-05, the user, after hw11; built, untested): `rally` sends nothing unless
  the swarms at the world, those bound for it and the pool together outweigh the force
  (`systemDefenceOnlyIfEnough`, on) - the swarm's mirror of a siege's call-off. Log: `Posture: no rally to
  <world> (N FP could stand against M)`.

  Regional relief (2026-10-09, the user, after the old sector swung 44-151 hives on one build; built for
  hw121): when the system's own pool cannot outweigh the force, `rally` adds the swarms already bound for
  the world from other systems (`boundFromAfar`) and the spare of the hives in other systems
  (`regionalPool`): nearest system first, each passage paid from the fuel stock (`ThreatFuel.passage`,
  as `sendReinforcement` charges it), each colony within its own need (`spareFleets(c, true)`), a front
  colony never below its want (`frontline`, `wantFP`), a system with a force over, a siege at or an
  army on any of its worlds giving none. The pool stops at the system's want (attack x
  `systemDefenceMargin`). The only-if-enough rule reads the whole of it: nothing sails unless the
  world's own system and the region together outweigh the force. Sent after the system's own swarms,
  by `pickFor`. Knob `regionalRelief` (true). Log: `Posture: <donor> rallied N FP to <world> from L ly
  (regional relief; ...)`, and `no rally` lines end `, K regional` when a regional pool was counted.
  The humans' coalition relief mirrored (`ThreatCoalition.jointRelief`).

  The pressure pass is under the same rule (2026-10-08, hw83a): `ThreatColonyManager.redistributeByPressure`
  sends a receiver under attack (`ThreatPosture.underAttack`) nothing - no transfer, no swarm fabricated
  for it - unless what stands for it (`ThreatPosture.standsFor`: the swarms at the world and those bound
  for it in its system) plus what the pass would send it this poll - its accept (deficit, band and a swarm),
  or less if every donor's spare (`pressureSpare`) and every idle bank (`fabricableFor`) hold less - would
  outweigh the force over it (`ThreatPosture.forceOver`: the heavier of the hostile fleets near it and the
  unspawned sieges fighting it). Read once a pass per receiver. Log: `Posture: no transfer to <world> - N
  FP over it, M FP could stand (S standing, A FP more wanted, G FP to gather)`. hw87a, gated on the sector's
  banks alone (what "could" be gathered, 60k FP), refused 49 times and fabricated 4,489 swarms for attacked
  hives, hw83a's 4,501: a want of 1,000 under a 4,450 FP siege is fed 400 FP a poll, so what the pass
  would send is what counts (hw89 / hw90). hw83a: the ordinary transfers and
  the fabrication had sat outside the rule, so Nomios, under a 4,450 FP siege with 800 FP standing, was
  sent 91 swarms of 40-90 FP in three months - Epiphany's bank fabricating one a dispatch, 1,668 FP down
  to 74 - each hunted as it arrived; 4,501 swarms / 470k FP fabricated for attacked hives in a game, 40
  hives eradicated with swarms still arriving.

  The swarm fabricated for a receiver is sized to its deficit (2026-10-09, hw93a): `rowFor` takes the
  dearest garrison row of any size's table (`desiredGarrison`, up to {2, HIGH} / {1, MAXIMUM}) whose
  estimated cost (`swarmCostEstimate`) the receiver's deficit, the fabricator's bank past its own want
  and the fuel to the receiver pay for, and the receiver's cheapest row only when nothing more fits.
  hw93a had built the cheapest row - a captured world's size-1 table, a LOW swarm of 52-97 FP - one a
  dispatch: Culann, wanting 4,582 FP under a human siege, was fabricated 57 of them in one poll, 151
  reinforcements were in flight at a time (20k FP, 15.5k supplies a month) and the old sector's swarm
  ran its stock to 0 with 128k FP in the fund.

  The garrison fights as one (2026-10-09, hw89a; `ThreatGarrisonMuster`, per frame after the leash): a
  garrison swarm in a battle within 1,500 units of its world pulls the hive's other swarms on station,
  and the reinforcements bound for the world inside its system, into the battle (`BattleAPI.join`), once
  per battle; a battle the player is in is left to the engine's join range. Why: swarms orbit 400-700
  units out on an aggressive orbit, up to 1,400 apart - outside the engine's 500-unit join range - and
  one that chases a hunter is caught alone coming back under the 700-unit leash. hw89a's 487 hunt
  battles met a single swarm in 89% of them (171 FP against 478 on average, 839 FP of garrison reported
  standing by), 303 of them 600+ units from the world; 191 hunts completed, 36 stood down hurt. The
  human hunt is sized against the whole garrison it reads (`ThreatSoftening`); now it meets it. Log:
  `Garrison musters at <world>: N swarm(s), M FP join the fight D units out`.

  Overdue reinforcements are ordered on (2026-10-09, hw101a; `ThreatColonyManager.overdue`, from the
  arrivals poll): a reinforcement out longer than `reinforcementOverdueDays` (90) is logged where it
  stands - its location, its distance from the target or the nearest jump point, its current order,
  whether it is in a battle, has an AI, its burn - and given its travel order afresh (blinders on, the
  365-day GO_TO_LOCATION and the orbit fallback), once a span; one in a battle is left to finish it.
  Why: in every run read, 92-177 reinforcement swarms (18-34k FP) sat 120+ days inside one system -
  most in the big fabricators' systems (Thule, Beta Cormoran; the new sector's Alpha Laphirial) - and
  the hives they were sent to fell meanwhile: hw101a's Zipacna was sent a 724 FP swarm from Beta
  Cormoran I on war day 2690, which stood in Beta Cormoran until Zipacna fell on day 3418 to a 520-625
  FP siege the rally could answer with 196 FP (its system read 3,065 FP held, +2,730 inbound). The
  dump's REINFORCEMENT records carry `days` in flight and `battle`. hw105a (the first read, 07:25):
  a fresh order does not move them - 48 swarms were ordered on three times and more; they move at
  burn 9 (`getCurrBurnLevel` is the real speed) with the order active and no battle, yet never close
  on the target: three swarms for Blue stood inside Blue's own system for 990 days, their distance to
  Blue a clean 540-day sinusoid between 3k and 22k units - the planet's orbit seen from a fleet that
  circles one spot. Most do arrive (hw105a 1,916 of 2,231 sends; hw106a 978 of 1,011).
  The remedy ladder (095cce7a, hw107/hw108; `overdue`, `whereabouts`): the read names everything -
  position from the centre, order, heading (the move destination), speed and burn, whether the AI
  is fleeing and what it targets, hyperspace transition, terrain, the nearest hostile - and
  escalates once a span: the first span the travel order afresh, the second a fresh fleet AI
  (`FactoryAPI.createFleetAI`), the third and after the swarm carried to 1,500 units from its target
  (`LocationAPI.removeEntity`/`addEntity`, `setLocation`), a stopgap flagged to the user until the
  cause is read. Log: `Reinforcement overdue: N FP -> <world>, D d out, <whereabouts>, battle b,
  ships n - ordered on again (kick 1) | fresh AI, ordered on again (kick 2) | carried to 1,500 units
  from <world> (kick N) | left to its battle`.
  The cause (hw107/hw108, 08:40): vanilla's asteroid belts. Of 766 consecutive reads of one swarm, 214
  found it at the same unit of position 90 days apart at full speed, and 1,240 of 1,497 full-speed reads
  stood in an asteroid belt or ring - the source hive's own, mostly. `AsteroidBeltTerrainPlugin.applyEffect`
  knocks any fleet that is not slow-moving off course (`AsteroidImpact`: a reversed velocity for 0.2 s)
  each time its impact timeout runs out, heavier for a big fleet (`Misc.getFleetRadiusTerrainEffectMult`);
  a 16-25 ship swarm at burn 9 never gets out. The plugin skips a fleet whose memory holds
  `$asteroidImpactTimeout`, so `ThreatFleetComposer.beltSafe` sets it for a million days on every fleet the
  mod builds (`ThreatFleetComposer.create` / `createScouts`, the human Task Forces, convoys, front and
  scouting fleets) and every swarm it orders out (`sendReinforcement`, `sendToGarrison`, `overdue`, the
  leash return) - both sides, so neither gains; vanilla's own patrols are vanilla's. First run hw111/hw112.
  The 333 crawling reads (speed 44, burn 2.2 - 223 in hyperspace with human fleets 1-20k units off, 85
  inside the target's system in no terrain) are the next read: the movement fields (listed, orbit,
  travel speed with the fleet's burn and every fleetwide max-burn modifier, active abilities, go-slow,
  the impact keys) are in the same read since 7a4764ab.
  REFUTED (hw111/hw112, 09:30): with the key set on every swarm (`impact true`), 137 frozen pairs still
  stood at the same unit of position a span apart, all at full speed in a belt or ring, heading unchanged -
  neither the fleet nor its AI is being advanced; a fresh AI does not help, the carry does. The belt is where
  the swarm was built and never left, not what holds it; `AsteroidImpact` is not the cause (beltSafe was removed on
  2026-10-09 afternoon by the user's word; it ran hw111-hw120). The crawlers are `slow true`: vanilla's sneak burn (`Misc.getGoSlowBurnLevel`, min burn x the
  sneak multiplier, 9 -> 2) with the travel speed intact; the mod never calls `goSlowOneFrame`, and 69 of
  261 had no hostile in range. hw113/hw114 run the frame pulse (`ThreatColonyManager.FramePulse`, attached
  at the send and at every overdue read): the read then says `pulse N frames, last D d ago at x/y`, with
  `alive`, `current` (the player's location), `station`, `aimode` - a stopped pulse is a fleet the engine no
  longer advances.
  The pulse told nothing (hw113/hw114, 10:15) - CORRECTED 2026-10-09: the read tested `p.day < 0` and
  `ThreatPosture.today()` is negative on the sector clock, so every read said `pulse never ran`; an entity's
  scripts run in any location (`BaseCampaignEntity.advance` ends with `runScripts`). The 10:15 reading
  ("scripts, and `AsteroidImpact`, run only in the player's location") was wrong. Fixed for hw122: "never" is
  `frames == 0`. The crawlers arrive (39 of 39 read twice had moved 10,000+ units).
  hw115/hw116 run the carry split: kick 1 `setVelocity(0, 0)` in place, kick 2 `setLocation` one unit over,
  kick 3 the carry; the read adds `moved N units since the send` (`REINFORCE_AT_KEY`, stored at the send).
  Read (hw115/hw116, 11:00): 68 of 774 overdue swarms had `moved 0 units since the send` - they never left
  the spot they were sent from (all in a belt or ring, the hive's). A span after `velocity zeroed`: 11 free,
  6 creeping (under 2,000 units a span), 4 still; after the one-unit nudge: 2 free, 14 creeping; the carry
  always frees. hw117/hw118: kick 1 clears the nav module's avoid list (`ModularFleetAIAPI.getNavModule()
  .clearAvoidList()` - a fleet steering round something it will not pass shows a heading and a velocity and
  no displacement), kick 2 hops 500 units along the heading (the spot or the fleet?), kick 3 carries; the
  overdue and arrival lines carry `id <fleet id>` and the days out, so a swarm's reads and its arrival link.
  Read (hw117/hw118, 11:50): 46 of 650 overdue swarms never moved from their send spot; a cleared avoid
  list left 12 still and 19 creeping (3 free), the 500-unit hop 7 creeping (4 free); the carry frees all and
  carried swarms arrive within days. So nothing done to the fleet in place frees it; only placing it near
  its target does. hw119/hw120: the carry at the SECOND read (kick 2; the swarm loses 180 days, not 270),
  and the read adds `velocity x/y`, `facing` and `from <hive> N units off (radius R)` - the source hive's
  planet, to tell whether a frozen swarm sits on its hive.
  Read (hw119/hw120, 12:50): every frozen pair (52 / 34 / 20) holds its velocity vector to the unit - full
  burn, aimed at the target - and its facing in 101 of 106: a dead stop, not an oscillation; nothing
  integrates the fleet's position. Neither a reversed facing (31 / 13 / 8 of the frozen, 8 / 14 / 6 of the
  free) nor a small source body (radius under 60 behind 27 / 13 / 6, 150+ behind 15 / 6 / 4) marks them.
  The carry stays (the user, 2026-10-09); the next step is the decompile of `CampaignFleet.advance`.

  **The engine, decompiled (2026-10-09, CFR 0.152 over `starfarer_obf.jar`; sources in that session's
  scratchpad `dec\`).** What moves a campaign fleet, by symbol:
  - `CampaignEngine.advance`: the current location every iteration; every other star system and hyperspace
    once in 60 iterations with 60 x the frame time (in fast-advance every location every iteration) - a
    per-location throttle that never reaches zero. `CampaignState` runs the engine round(speed-up) times a frame.
  - `BaseLocation.advance`: over a copy of `objects.getList(CampaignEntity.class)` - expired entities removed,
    then `e.advance(f)`, the orbit advanced, and `location += velocity x f` for EVERY entity, unconditionally.
    `getFleets()` is the same `ObjectRepository` (each object filed under all its classes and interfaces);
    `isAlive()` is membership of the repository's HashSet.
  - `CampaignFleet.advance`: the AI only if `ai != null`, not fading, not `isDoNotAdvanceAI()`; movement only
    with a `moveDestination` (never cleared once set): `SmoothMovementModule.advance`, then location and
    velocity copied from the module - the only place facing changes. `ModularFleetAI.advance` returns early
    only in a battle. `CampaignFleet.setVelocity` writes the module's vector, not `getVelocity()`'s, so on a
    fleet not advanced `setVelocity(0, 0)` changes nothing a read sees.
  - Nothing there stops a listed, unexpired fleet at full burn: no sleep flag, no per-fleet throttle, no
    spatial grid, no separate advance list, no try/catch swallowing an exception in the three loops.
  What the logs then say (hw115-hw120): position, velocity, facing and the move destination stopped
  together (headings read 10,000 less 6 s of travel - the nav module's click point, one tick past), so no
  AI tick since: `CampaignFleet.advance` is not being called. After every send, kick or nudge a frozen
  fleet moves whole ticks (359-360, 719-720, 1,079-1,080 units at speed 180) and freezes again; the carry
  frees because 2-3 ticks cover the 800 units from its drop point to `GARRISON_LEASH_RADIUS` (700), not
  because it repairs anything. The only core state that fits a frozen listed fleet is one missing from the
  CampaignEntity list while still in the CampaignFleetAPI list - but an order or `setLocation` does not touch
  list membership, so "a few ticks then frozen" stays unexplained. hw122's read adds the check
  (`ThreatColonyManager.engineLists`): `engine list xN` (the fleet in the list the engine iterates, by the
  interface's name), `fleets xN`, `location held` (a star system or hyperspace the sector holds) and
  `moveDest` (the expiry of `$ai_moveDest`, renewed every AI tick and counted down only while the fleet is
  advanced - the same figure at two reads = not advanced between them), with the pulse fixed.
