# War simulator calibration, round 29

Split out of `war-sim-calibration.md` (its section 12) on 2026-10-03 when that doc passed 40 KB. The scripts it
names are listed in `war-sim-calibration.md` 10.

## hw4d, the guards' troops, and the colonies' real garrison (2026-10-03 night)

Run hw4d on the round-28 jar (`war-sim-real-runs.md` 7) lost 51 human worlds by month 125, 38 of them after day
3,000; `check` with the round-28 simulator put it at 9 [6-10]. Three faults, all in the simulator, and one
overshoot in the mod:

- **No hull break-up.** A guard that bombarded what it can breaks its hulls into troops while its front cannot hold
  (`ThreatGroundFronts.fabricateTroops`, 10 a point, up to the hold line x 1.05). hw4d: 4,153 FP became 41,718
  troops. Now `SwarmOps.feed`, and a guarded landing on a colony at war fights the front engine
  (`threatFrontDay`) rather than the overrun clock: `warsim_guardFeedsFront` true.
- **A guessed garrison.** The engine's colony was 15 a size plus marines; the game's is vanilla's ground defence
  (`colonyGarrison`), Sindria 3,120 against 105. Now the dump's `defence` less its armed marines (`World.garrison`,
  `Start`), `warsim_colonyGarrison` true. This is why `warsim_coloniesFall` over-predicted losses in round 26.
- **Every strike landed.** The game sizes a first landing to outlast the first counter-attack
  (`ThreatStrikeFGI.beachheadLanding`: `beachheadTroops`, the shortfall broken out of the hulls, held back if
  they cannot make it). Now in `SwarmOps.land`, `warsim_beachheadRule` true.
- **A holding front's garrison stood whole.** A Threat front that holds suppresses the world's key structures, and
  vanilla's ground defence falls with them: under a holding front a colony kept 0.26 of its pre-war garrison in the
  hw4d dumps (0.40 in hw4c; a grinding front about all of it). Now `SwarmOps.suppressed`, `warsim_frontHoldSuppress`
  0.65 off the garrison while `Swarm.Landing.holding`.
- **The guard's size** is its first pack: `warsim_guardSwarmsPerFleet` 1.42 (hw4d: 5.6 fleets a strike); about 0.20
  of a strike's FP stays (game 0.17-0.22).
- **The mod: fuel booked whole.** The waiting muster booked its whole passage each `SHORT_DAYS`; hw4d's plants
  made 63k a month against 31k spent at month 60. Now the shortfall (`ThreatFuel.heldShort`). In the simulator
  the two bookings do not differ (60 seeds, 130 months: worlds lost 42.5 against 44): its fuel piles up only after
  a collapse, with nothing left to strike.

`check`, 30 seeds (hw4d with `warsim_strikeWaitBooksWhole=true`, its jar's booking; hw4c with the round-27 rules):

| | hw4d inside | hw4d worlds lost m84 / m108 / m125 | hw4c inside | hw4c landings m108 |
|---|---|---|---|---|
| game | | 7 / 25 / 51 | | 56 |
| round 28 | 327 of 422 | 4 / 7 / 9 | | |
| + break-up and engine | 311 | 16 / 39.5 / 54 | | |
| + garrison | 304 | 9 / 20 / 40 | 278 of 378 | 143 |
| + beachhead | 340 | 9 / 22 / 41.5 | 288 | 149 |
| + holding suppresses the garrison | 341 | 13.5 / 35.5 / 53 | 288 | 152 |

With the suppression hw4d's landings and overruns fit (month 125: landings 125 | 150 [127-161], was 215; overruns
46 | 90.5, was 160; to month 96 overruns 40 | 45.5). Worlds lost now run ahead of the game from month 84 (the game
lost none from day 1,890 to 3,000, then 38 in 700 days; the simulator loses them steadily; only month 96 falls
outside the band, 7 | 22 [10.8-27.1]). Not the marines: `check` now compares the defenders' reserve (`humanMarines`,
the stockpiled marines of every world that keeps one; 351 of 433 inside), and the simulator tracks it through the
collapse - month 84 85.9k | 92.2k, 108 33.7k | 26.4k, 125 9.3k | 3.6k.

Still out: the strikes and landings of a run without fuel booking (hw4c month 108: strikes 127 | 241, landings
56 | 149). hw4d's strikes match (252 | 269): the booking raised the game's strikes to the simulator's, so the gap
is the old rule's fuel, not the landing pass. The game's `waits: bombardment still has work to do` (118 in hw4c,
50 in hw4d) are not lost landings: they are the sweep's other worlds after the first took every troop aboard
(`Strike pass (landing) vs Athulf: 240 troops; 0 still aboard`, then Fikenhild and Suddene wait, no abstract siege
of theirs resolved). The gap is what the strikes aim at (section 8: the game's go to forward bases a third of the
time).

Options for the collapse, 60 seeds from the new game to month 130, the simulator with all of the above (`r34`,
`r32b`):

| month 130 | defaults | no break-up (`threatinc_fabricateEnabled=false`) | beachhead break-up only, guards do not feed | rules before round 28 |
|---|---|---|---|---|
| worlds left / lost | 3.5 / 55.5 | 36.5 / 22.5 (clear) | 21.5 / 37.5 | 49 / 10 |
| hives / hives killed | 276 / 8.5 | 179 / 20.5 (clear) | 234 / 15 | 201 / 2 |
| threatScore | 4,076 | 2,798 | 3,360 | 4,195 |
| humanScore | 9 [3-26.6] | 30.5 [17.9-60.6] (clear) | 19 [6.9-60.1] | 22.5 [11-46.5] |
| landings held back (no hulls for the beachhead) | 1 | 198 | 1 | 2 |
| outcomes | swarm wins 12%, both sides 52% | both sides 98% | both sides 78% | both sides 22%, one-sided 47% |

The break-up rate does not matter (`r31c`, before the suppression: 5 or 2.5 troops a point both lose 39 worlds,
the guards just spend more hulls). The third column is `threatinc_fabricateDefendEnabled=false` since 2026-10-03 (the mod knob, default true;
the simulator reads it in `SwarmOps.feed`, as it did `warsim_guardFabricates`).

**hw4e** (`war-sim-real-runs.md` 8, the fuel fix): 344 of 433 inside. The simulator loses worlds a year early on it
(month 108: 32.5 against 3) because the game's swarm consolidated 13 months by month 72 (simulator 4.6) and struck
forward bases; across hw4c, hw4d and hw4e the game's months in CONSOLIDATE (0, 5, 15) straddle the simulator's
medians (1.7, 9.6, 7.8), so that is the run's draw, not a bias.

**Forward bases, late.** On all three runs the simulator founds 2-4 times as many links a month after month 84
(month 108: game 43-54, simulator 61-103) and abandons more (hw4e: 7 against 21.5, every one "no garrison to hold
it"; single seeds 31-58). The price matches (5,500 supplies, 800 fuel), the cadence too (`frontlinePlanDays`), and
the refusals for want of stock (game 127 after month 84 in hw4e, simulator `linkCannotPay` 122 to month 108). One
difference found: the game's front is only the links a hive would strike first (`ThreatFrontlines.frontOf`,
`ThreatReach.facedFaction`), the simulator's every faction's nearest. `warsim_frontFacedOnly` (`HumanBases.strikeAt`)
adds the rule; it fits worse on all three runs (hw4c 293 -> 262 of 388, hw4d 351 -> 341, hw4e 344 -> 342) because
the garrisons it spares become more links (founded 129 at hw4e month 108), so it is off. The gap is what the
factions' pools hold late, which no `check` row reads yet.

**STARVE, late.** The game starts 1.5-2 times the simulator's STARVE plays after month 84 (`plays.STARVE` month
108: 28-35 against 15.5-19) while spending less time in the strategy: new `check` rows `strategy.HOLD`,
`strategy.STARVE`, `strategy.ROLLBACK` (factions holding each, from the dumps' `factions[].strategy` and
`Faction.strategy`) put hw4d's councils in STARVE 0 at months 84-96 against the simulator's 4, all seven in HOLD at
96 against 2.5. A game council in STARVE runs several plays at once (Tri-Tachyon #2, #4, #6, #8 sailed within 80
days of day 1,724), each failing on "no squadron (every Nexus is reported guarded)", and a failed one reviews the
strategy at once (STARVE -> ROLLBACK -> STARVE in 50 days). Both sides cap plays by `majorLimit`; not traced
further. With the three rows hw4d is 374 of 466 inside.

**Beachheads and landing troops** (hw4f, the deviation that started it: with break-up off the simulator held back
about twice the game's landings). Three findings from the game's logs against its dumps (`gl3.pl`):
- *The need reads a suppressed garrison.* The game lands at least `beachheadTroops`, which it sizes after the
  bombardment (`readyToLand` waits for `orbitSpent`). On worlds with a pre-war garrison over 1,000 the landed troops
  put the garrison left at no more than 0.25-0.40 of it (median, p25 0.20). `SwarmOps.beachheadTroops` read the
  pre-war figure; `warsim_landingSuppress` 0.65 now cuts it. The check is a tie: totals 374+370+302 = 1,046 at 0,
  364+362+320 = 1,046 at 0.65, 1,033 at 0.8. At 0 hw4f's landings fit (110 against 99, held back 138 a run against
  the game's 137 episodes); at 0.65 the beachheads' hull spending fits (sweep from hw4e: 3,191 FP at month 108,
  1,751 at 0.8, 7,462 at 0, game 1,091). It stays at 0.65, the measured mechanism.
- *A strike sweeps the system* (`IncursionManager`, SEQUENTIAL raid of every eligible world; `worldShare` =
  max(`strikeFrontMinTroops`, pool / worlds), `availableLanding` = min(aboard, max(share, need))). The simulator
  landed the whole strike on its target. `SwarmOps.sweep` adds it behind `warsim_strikeSweep`, off: in the game the
  first world takes the whole pool (median 0 aboard after a landing, hw4e and hw4f), and with the sweep on the
  simulator lands 1.5-1.7x the game's landings on all three runs (hw4d 345, hw4e 323, hw4f 324 inside).
- *The simulator's strikes are bigger.* Landed troops: game median 720 (hw4f), simulator 1,800-2,200. Swarms a
  strike, years 7-10: game 3-8, simulator 6-14, FP a swarm the same (hw4e year 8: 4 swarms and 878 FP against 9
  and 2,035). The muster rules match (`garrisonAvailableForLaunch` pools the system as `SwarmOps.spares` does;
  regrowing and pressed systems send nothing), so the gap is in the state: more spare swarms a system. Not found.
  It also explains why the 0 setting fits hw4f's landings: an inflated need offsets an inflated pool.

