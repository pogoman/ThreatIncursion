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
