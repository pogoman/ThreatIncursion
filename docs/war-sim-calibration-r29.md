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

## Fronts, the gate and the runaway swarm (2026-10-03, 03:20-04:10)

**The strike gate grows with the guards, not with time** (`gg.pl`). In the hw4d/f/g dumps a world with no human guard
(GUARD, HUNT or MUSTER, arrived) in its system kept its day-5 gate to day 3,600 (median ratio 0.9-1.1); one with a
guard read 1.1-1.5 units per guard FP above it. The game's gate counts every hostile fleet in the system; the
simulator counted the world's own guard only, at the Threat strike's 2.1 units a FP. `warsim_gateSystemGuards` (the
system's guards, worn together) and `warsim_guardUnitsPerFP` (1.3) are built, off: landings and worlds lost move under
3% (inside: hw4d 376 -> 369, hw4f 320 -> 291, hw4g 323 -> 328).

**How the game's colony strikes end** (`life2.pl`; hw4g, hw4f in brackets): of 224 (272), a new front 81 (78), an
existing front reinforced 74 (64), fought and no landing 19 (56), no off-screen fight logged 39 (62), outweighed at
arrival 11 (12); median FP lost in a won fight 0.29 (0.25). The simulator on hw4g's settings (seed 3): 243 new fronts,
34 reinforcements.

**The simulator's beachheads die twice as fast** (`flife.pl`, `first.pl`, `pace.pl`). Overrun fronts lived a median
157-173 days in the game (p25 76-107), 83 in the simulator (p25 26); open at the end 45-59 against 12;
`beachheadsOverrun` at hw4g's month 120 156 against 53. Each overrun frees the world for a new front where the game's
next strike reinforces. Two things in the game's code the simulator lacked:
- *Cadence.* A colony counter-attacks every 40 / (stability / 10) / 1.5 with a military command / tempo days
  (`Theatre.COLONY.counterAttackInterval`); the simulator holds no stability. The game's first counter-attack came
  54-60 days after the landing (pace backed out at the tempo 0.39-0.52), later ones every 25-28 (0.9-1.05); the
  simulator's 23 and 18. `warsim_counterAttackPace` 0.68 and `warsim_firstCounterAttackPace` 0.55 fit the intervals.
  Inside: hw4g 323 -> 346, hw4f 320 -> 336, hw4d 376 -> 341, hw4e 362 -> 349, hw4h 337 -> 363 (1,718 -> 1,735);
  the medians' summed distance (`mdist.pl`, |ln(sim / real)| over every cell) 1,111 -> 1,027, the war rows' 192 ->
  177. On by default since 2026-10-03 04:20. On hw4d the human councils then start half the game's offensive plays
  (BOMBERS 14 against 29) - the rows it loses.
- *Veterancy* (`ThreatMarineXP`). A swarm front lands at 0.15 (`npcLandingVeterancy`) and every counter-attack
  raises both sides: strength x (1 + level), losses x (1 - 0.5 level). The game's batterings cost a front 2-4% of
  its landing, the simulator's a flat 10%. `warsim_veterancy` (fronts and colony marines, diluted by raw marines) is
  built, off: with the pace it moves the medians closer (distance 1,014, war rows 168) but narrows the bands
  (inside 1,677). The game log shows it at work (`caidx.pl`): Salamanca's front in hw4g lost 100, 83, 69, 57, 47, 43
  and 40 marines to seven batterings in a row (marines x 0.10 x `lossMult`, the level up about 0.12 a counter-attack)
  while the colony's counter-attack rose 4-5% a time (3,227 -> 4,142). By counter-attack index the game's guard
  grows (406, 504, 556 ... 1,121 from the ninth) at a ratio of 1.5-1.7; the simulator's without veterancy falls (585,
  535, 442, 383) at 1.5-2.2 and its fronts are overrun after 3-4. With it (hw4g's settings, seed 3) the guard holds
  670-810 at 1.5-1.9 and overrun fronts live 164 days against the game's 168. Off by default all the same: it
  removes the slow-swarm seeds (hives at month 132 [255-340] against [142-330] without it), so hw4g's plateau (162)
  falls outside.
- `warsim_invadedGateShare` replaces the constant 0.1 (fitted on pd9a, before guards entered the gate). At 0.5 one
  seed splits 127 new fronts / 157 reinforcements (game 114 / 86) and loses more worlds. Not checked across runs.

**Relief to invaded colonies** (`ThreatFleetOrders.planRelief`): the game sends a task force to every invaded colony,
sized to the swarm's guard over it x `npcSiegeOrbitMargin` (or `guardFleetFP`), from the nearest base - 293-566
fleets a run, 126k-210k FP (hw4d, hw4h, hw4g). The simulator sent relief to forward bases only.
`warsim_reliefToInvaded` (`HumanBases.reliefInvaded`) builds it, off; its volume matches (one hw4g seed: 797 fleets,
215k FP). Alone it is neutral (inside 1,670, distance 1,098). Two parts are separate knobs, both off and both worse:
`warsim_reliefFights` (the relief fights the swarm's guard on arrival; the game logs no such fight off screen, and it
stopped hw4d's collapse: 39.5 worlds lost against 51) and `warsim_reliefOwedShort` (councils owe relief only while
the guards are short; they sailed 70 sieges on hw4d against the game's 13).

**The runaway swarm - inside the game's spread.** With break-up off hw4g's swarm stopped growing from month 96
(garrison 41-59k FP, hive size 328-450, supplies in stock 4-7k on 70k a month) while the simulator's kept growing
(month 134: a 350k garrison, size 1,401, 307 hives against 167, 374k supplies). hw4h, the same setting on a fresh
clone (`war-sim-real-runs.md` 11), ran away as the simulator does: month 134 hives 305, size 1,319, garrison 366k,
360k supplies, all on the simulator's medians. So the late swarm is not a simulator fault the two runs can show; the
landings (246 against 105-109) and overruns (187 against 42-62) are, and they come from the fronts above.

**Why hw4g's swarm stalled and hw4h's did not.** Both on one setting and one jar. hw4g's supply stock sat at 3-15k
from month 48 to the end; hw4h's climbed from month 96 (21k, 55k, 97k, 256k). The hive planner's invest rule builds a
forge on a size-3+ world only while the stock pays a forge and a founding kit besides (`hive-economy.md` "Except the
banked outputs"): in months 84-120 hw4h's planner logged 88 `heavyindustry (invest)` and 49 `ORBITALWORKS`, hw4g's
none. Forges stayed at 21-24 in hw4g (hw4h 42 -> 185), so the supplies never came. Hives go on founding (hive
systems: hw4g 72 at month 132, hw4h 67), but few grow: systems with a size-4 hive, the ones a strike can stage from,
stayed at 10-11 in hw4g against 43 in hw4h. The check prints both as rows that are not counted
(`-Dwarsim.cols=hiveSystems,stagingSystems`): the simulator's hive systems sit on both runs (70.5 at month 132), its
staging systems at 20 / 28.5 / 42 / 56.5 by months 96-132 [p10 5-8], about 1.3x hw4h's.

## After the pace (2026-10-03, 04:30-05:00)

**The options table with the pace and veterancy on** (r41, 30 seeds, month 130; the game's in brackets): as built 49.5
worlds lost (51, 37), 108 landings (125, 128), 48.5 overruns (46); no break-up 27 (26), 104 (99), 54.5 (37); Defend
break-up off 40 (22, 33), 192 (109, 105), 133 (62, 42). With the pace alone (r40) as built lands 144 and Defend off
247. So the first two columns now sit on the game; only Defend off stays out, at about 1.8x the landings.

**Where the Defend-off landings come from.** By how each front ended, the simulator's fronts now behave as the game's:
overrun fronts took a median 5 counter-attacks in both, 20-24% of them reinforced on the way (`fstat.pl`). The
simulator has more of them: on hw4g's settings (seed 3, veterancy on) 178 fronts against 114, 121 overrun against
62, 41 won against 18, 16 open at the end against 27. That is 404 strikes against 298 (the staging systems above) and
0.45 new fronts a strike against 0.38.

**The brace came a day late.** The game's front asks whether to break off its assault after the day's attrition and
the defenders' bleed (`ThreatGroundFronts` tick: `frontLose`, `bleedDefenders`, then `shouldBrace`); the simulator
asked before, so a front at 1.99:1 pushed on, bled past 2 and the day's counter-attack overran it. Every first
counter-attack that overran in the simulator did so at 2.03-2.04, on a front still pushing: 24 of 138 (seed 3), where
the game's first counter-attacks overran 1 of 85 (hw4g) and 1 of 74 (hw4h) (`ovr.pl`). `warsim_braceAfterAttrition`
asks in the game's order: 4 of 139. Across hw4d-h the check rose 1,735 -> 1,794 inside (every run gained), the war rows' distance fell 177 -> 171, all rows' 1,026 -> 1,036; on since 05:00. Total overruns hardly move (seed 3: 121): the fronts die later instead (overrun at a median 192 days against 164).
