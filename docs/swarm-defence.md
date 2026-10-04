# The swarm's defence - restraints removed, strikes come home, the defence massed (2026-10-04)

Split from `hive-garrison-and-upkeep.md` "Posture" on 2026-10-04: that section holds the pressure, want,
modes and transfers these build on. Test results: `game-runs.md` 4-7. The hive picket that sees a siege
coming is in `threat-fog.md` 4.

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
  built 2f0b270, untested).** hw8 massed by a side door: a strike mustered its system's spare swarms and
  the next pass recalled it (`game-runs.md` 6). The pass now does it itself, in this order at the end of
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
  took most of them. Knob `postureMass` (true; false = the hw8 build, launch then recall). Logs:
  `Posture: <donor> massed N FP at <world> (G FP short of NEED)`, with `, X ly` before the bracket from
  a neighbour, and `Strike from <colony> held: <reason>`. Judgement calls, not the user's words:
  neighbours give, the reserve count stays home, the call is read per world, a recall stays whole.
