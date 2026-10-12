# Testing handover - 2026-10-12: starved guns weighed, the swarm's orbit contested by weight

For the session running general seed tests on another machine, to measure what the 2026-10-12 afternoon
build does to the NPC war. Read the repo's `CLAUDE.md` first, then `ground-war-defenders.md` ("Fortification
is hardware") and `ground-war-orbit-control.md` ("The swarm holds the orbit"). How to run the harness, the
mods, the pristine save and the user's standing rules are unchanged from `handover-2026-10-05-testing.md`
sections 2-8; what the 2026-10-11 provisions build added to the logs is in
`handover-2026-10-11-provisions-testing.md`. `git pull` brings the jar (`jars/ThreatInc.jar`, built **18:21**,
committed with this file - the 15:49 jar plus the evening's proportional bombardment below); do not rebuild
unless the source changes.

## 1. What is being tested (all untested in a batch)

Found by the user in their own game this afternoon, besieging Delta Mengryla I with one fleet.

| Change | What it does | Where |
|---|---|---|
| **Starved guns are weighed, not silenced.** | A defence structure's multiplier is 1 + bonus x input deficit x condition. Vanilla's deficit factor is 0 when none of an input reaches the world, and a full human blockade (`ThreatBlockade.hiveCut`, hostiles at 1.25x the swarm's orbit points) sets a hive's shipping to 0 units. Any hive importing metals or machinery lost its guns the day a siege outweighed its orbit, before a shot: `batteryShare` read 0, so the return fire and vanilla's bombard fuel bill went with them, and the 300-day wear clock never mattered. Now the deficit is weighed by the new knob `threatinc_defenseDeficitWeight` (0.5): a full deficit halves the bonus, a partial one takes its share. Both theatres. | `ThreatSiegeMalus.weighDeficit`, `ThreatGroundDefenses.apply`; `facts.md` "Why does a hive's Ground Defenses line vanish" |
| **One fleet no longer stops the swarm bombarding an enemy front on its hive.** | `swarmOrbitContested` read any armed hostile fleet within 1,500 units, at any weight, so the user's lone fleet dancing inside the radius against a thousand points of swarm kept `tickSwarmBombard` off while the hive's last stratum fell. Now the hostiles within range must weigh `orbitContestFraction` (0.5, the existing knob) of `swarmOrbitStrength` - the colony theatre's own rule (`orbitHeldAgainst`) mirrored. With no swarm there any armed fleet still contests. The unspawned flotilla's `coverFP` keeps its old test. | `ThreatGroundFronts.swarmOrbitContested`; `facts.md` "One fleet inside the radius" |
| Tooltip wording | `Ground defenses (shortage, suppressed, 98% effect)` - one bracket, no commodity named. | `ThreatGroundDefenses.apply` |
| **The swarm's bombardment is proportional to its weight, and pays fuel (evening, jar 18:21).** | The grind was `swarmFrontBombardPer30Days` (0.6) of the front per 30 days x `fp / (fp + defenseStrength)`: capped at 2% a day however many swarms stood overhead, free, with no visual and no log line - 1,015 FP over Delta Mengryla I read 1% a day against a 1,378-marine push and the hive fell ("1% for how much fuel? ... Shouldnt be proportional to the FP overhead?"). Now marines a day = `threatinc_swarmBombardMarinesPerFPDay` (0.05) x FP over the orbit / the front's cover (`coverMult`, dug in up to 1.5x; pushing exposed) x veterancy (1,015 FP: 51 a day), and the swarm pays `threatinc_swarmBombardFuelPerFPDay` (2.86, the human saturation rate) x FP a day from its fuel stock (`ThreatFuel`, census purpose "bombard"; 1,015 FP: ~2,900 a day); a day the stock cannot pay is skipped. 0 on the first knob falls back to the old rule. Vanilla's burst on the planet once a day. **Expect this to shift the war:** a human landing under a swarm that holds the orbit by weight now dies in weeks, and a long swarm siege of its own skies drains the fuel stock. | `ThreatGroundFronts.swarmBombardPer30Days`, `tickSwarmBombard`; `facts.md` "The swarm bombards in proportion to its weight"; `ground-war-orbit-control.md` "The swarm holds the orbit" |
| The blockade holds against unfought swarms (player-only). | While the player's fleet is within hold range of a hive, swarms that arrived under it and have not fought weigh nothing in `ThreatBlockade.hiveCut`. Off-screen orbits weigh as before - nothing for the batch. | `ThreatBlockade.foughtPointsNear` |

The same jar carries the other 2026-10-12 session work, committed together (player-facing unless noted):
reinforcements fight for the orbit on arrival and lift no blockade until they have fought
(`ThreatColonyManager.engageOnArrival`, `facts.md` "Swarm reinforcements fight for the orbit on arrival" -
this one runs for NPC sieges too, so watch it), a returning player fleet dissolves into the colony it lands at
("A fleet home is home"), a landing's defending fleets take their share of the expedition's hold
(player-only), the player's siege hold sized by `threatinc_siegeHoldDays` (player-only), and a colony sails
its own hulls plus what is staged over it (2026-10-11 evening). Settings: shipped defaults. The one new key,
`threatinc_defenseDeficitWeight`, has no LunaLib row.

## 2. What to look for

Player-less batch, three games on the usual bases (hw156's: OceanPena, ZhiFarah, AphelionDysnomia), the
`[ThreatInc]` lines of `saves\_sbs\<tag>\logs\starsector.log`.

**A. The guns stay in the fight.**

- `siegeSlice <world> [hive] fp=F defence=D rate=R days=N condition=C loss=L` - every bombardment day. On a
  hive under a full blockade `defence` should no longer collapse to the bare size anchor (400 x strata left)
  the moment the siege arrives; with condition near 1 it should read the anchor x 1.5 or more (Ground
  Defenses at half bonus) and then fall with `condition` over the wear days. Before this build the drop was
  immediate and `loss` (the return fire) 0 from day one.
- `Blockade of <hive>: imports cut 100% (human H FP over the swarm's S)` lines mark when the full cut
  starts; pair them with the next `siegeSlice` of the same world.
- Sieges should now cost ships: the fuel and FP `loss` on slices against blockaded hives, zero before.

**B. The swarm's orbit bombardment runs.**

- `Front at <hive> (<faction>) destroyed by swarm orbital bombardment` - count per game. With the old rule
  any human fleet in the radius stopped it; now a human landing under a swarm that outweighs its cover
  2:1 should be ground down. A jump in this count is the change working; zero in all three games means the
  gate still never opens (check `Orbit cover over <hive> lost` and the Defend lines `Defend over <hive>:
  <fleet> at N FP holds the orbit - the front holds`, which are the human side holding it).
- `Swarm bombards the <owner> front at <hive>: N FP over the orbit, M marines a day, front K marines, held D d,
  fuel F a day` - once per whole day of swarm bombardment (new, jar 18:21). The rate check: M should be
  0.05 x N on a pushing front, 0.05 x N / 1.5 on one fully dug in. Sum F over a game against the swarm's
  fuel census (`ThreatFuel` month summary, purpose "bombard") - if the stock runs dry the line turns into
  `Swarm bombardment over <hive> held: no fuel for N FP (F a day)` and the front is not bombarded that day;
  count those too. A swarm that spends its whole fuel stock bombarding one front is a finding (the fuel
  knob is the lever, 2.86 is the human rate copied, not measured).
- `Reinforcement engages at <hive>: N FP against H hostile FP over the world - fighting for the orbit, not
  seated` - the arrival change. Arrivals should fight and be seated later (`Reinforcement arrived at`), not
  pile up unseated for months; if a hive's garrison never grows while a siege sits over it, that is the
  arrival change starving the garrison, not this one.

**C. The war.** Baseline hw159 (jar 2026-10-11 15:20, the same three bases; `d66929b`): hives at month 126
35 / 43 / 99, OceanPena contested, Aphelion the swarm's. hw156 before the staged launches: 49 / 41 / 137.
Expected direction: sieges against hives cost more and human landings under a swarm that holds the orbit by
weight are ground down in weeks (51 marines a day per 1,000 FP), so hives at m126 should be the same or
higher and human worlds lost the same or higher; if the swarm's fuel stock collapses instead, the bombard
fuel is the suspect. The user's rules: the Threat
ahead in a player-less run is fine (`threat-may-win-npc-war`); the humans winning an unattended run is the
mod not working (`facts.md` "The humans must not win an unattended run"); no tuning toward parity. If
sieges stop landing altogether, the suspect is the weighed deficit making the gate's defence figure read
higher than the planner expects (`IncursionManager.strikeOutweighed` reads fleets, not ground defence, so
look at the siege gate's own lines first), not the fraction.

**D. The user's own game.** The user is in a real game (hold over Delta Mengryla I, stratum 3 of 3 under
push). Nothing to do from the batch machine; the user reports what the player side shows.

## 3. Reporting

Per-checkpoint findings file as always (`test-runs-report-as-they-go`), every finding with a proposed fix
(`issues-come-with-solutions`). Record what the batch measured in `game-runs-2.md` (next section number,
44) and one-line answers in `facts.md`. Ask the user before any in-game run beyond the batch they asked for
(`no-unprompted-game-launch`). Local commits, no push unless the user says so.
