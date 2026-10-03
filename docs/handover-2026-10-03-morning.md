# Handover - 2026-10-03 morning (the overnight session)

Written by the overnight session of 2026-10-03 (00:00-08:00, the user asleep: "lots of iterations and tweaks and
tests overnight, and address any deviations from test harness with real test"). Local commits on `main`, nothing
pushed. Read section 1, then section 2's question.

## 1. Where it stands

- **Your three rules are in and working in the game** (hw4d, `war-sim-real-runs.md` 7): phase-3 mobilisation, unspawned
  strikes guard their landings, waiting strikes book fuel.
- **As built, the swarm wins the NPC war late, but not always by much.** hw4d lost 51 of 59 human worlds by month 125,
  hw4e 37 by month 126, hw4j 37 by month 133 (only 10 by month 120), hw4l 43 by month 132 (21 by month 120). The guards over the swarm's fronts break hulls
  into troops while their front cannot hold (`fabricateTroops`), and those fronts outlast the defenders' marines.
- **Ten game runs on one sector point at the Defend break-up, with wide noise** (section 3). The three Defend-off runs
  ended at 22, 33 (month 134) and 26 (month 127), the lowest of any setting. At month 126 three of the four as-built
  runs had lost more than any Defend-off run (52, 39, 40 against 21, 20, 26); the fourth, hw4j, stood level with them
  (21). No break-up at all is no safer (hw4f 26, hw4i 42). Every setting has a run that lost 15-27 worlds in its last
  7-14 months: the late game is dangerous whatever you pick.
- **Mod changes tonight:** cbc5b2e (a waiting strike books only its fuel shortfall; a guard's ledger share weighted by
  `estimateFP`), 0387fd8 (new LunaLib knob `threatinc_fabricateDefendEnabled`, default true: off, a Defend fleet holds
  orbit and bombards but never breaks hulls; a strike short of its beachhead still does).
- **The simulator** (section 4) now matches the game on as built and no break-up. On Defend off it overran the
  fronts three times as often; three missing game rules were found and built (4d68264). They fix the Defend-off fronts
  but make the as-built ones too safe, so they stay off. A fourth found at the end, the game's guards going home
  when relief wears them (dd6f8b2), swings the as-built fronts the other way; off too. Use the game runs for the
  decision.
- **Local commits only** (`git log origin/main..main`: two from yesterday evening, the rest tonight), nothing pushed. Your
  LunaLib settings are as you left them (break-up both on, council on); the game is closed.

## 2. Waiting on you: the Defend break-up

Human worlds lost to the swarm (of 59; fewer is better for the humans), game runs on the hw4 sector, council on:

| | as built | Defend break-up off (beachheads keep it) | no break-up at all | 1 troop a FP (hw4m, after the night) | 5 troops a FP (hw4n; stalled swarm) | 5 troops a FP, fuel fix (hw4p, afternoon) | relief holds and bombards (hw4q, evening) |
|---|---|---|---|---|---|---|---|
| runs | hw4d, hw4e, hw4j, hw4l | hw4g, hw4h, hw4k | hw4f, hw4i | hw4m | hw4n | hw4p | hw4q |
| worlds lost, month 108 | 25, 3, 5, 6 | 7, 3, 6 | 8, 15 | 9 | 3 | 8 | 4 |
| worlds lost, month 126 (log count) | 52, 39, 21, 40 | 21, 20, 26 | 21, 42 | 26 | 5 | 35 | 34 |
| worlds lost, end (month) | 51 (125), 37 (126), 37 (133), 43 (132) | 22 (134), 33 (134), 26 (127) | 26 (133), 42 (130) | 33 (132) | 5 (127) | 40 (133) | 39 (131) |
| defenders' marines, end | 9k, 14k, 35k, 7k | 71k, 61k, 39k | 63k, 35k | 34k | 118k | 19k | 24k |
| hives killed | 5, 3, 3, 1 | 3, 4, 4 | 9, 4 | 1 | 2 | 2 | 2 |

- **Keep it (as built).** In three of four runs the swarm overran the sector by month 126 of a player-less game, and
  the humans' score fell under the p10 guard you kept on 2026-10-02. In the fourth (hw4j) it held to month 120.
- **Defend break-up off** (`threatinc_fabricateDefendEnabled` false; a LunaLib switch today, so you can try it in a save).
  The smallest change, and the best end figures so far. It doesn't stop the swarm: hw4h lost 25 worlds in its last
  14 months, hw4k 15 in its last 7.
- **No break-up** (`threatinc_fabricateEnabled` false): no better than Defend off in the game (26 and 42), and a strike
  short of its beachhead holds back instead (137 episodes in hw4f, 100 in hw4i).

My recommendation: Defend break-up off by default. At month 126 its three runs stand at 20-26 worlds lost against
as built's 21-52 (median 21 against 39.5), it keeps the beachhead sizing, and it is a switch you can turn back on in
a save. The fourth as-built run (hw4l, 40 at month 126) went the way of the first two. With spreads this wide,
three or four runs a setting are a lean, not proof.

## 3. Runs

All on the hw4 sector (four home worlds, council on), a fresh clone each. No mod exception in any.

| | hw4d | hw4e | hw4f | hw4g | hw4h | hw4i | hw4j | hw4k | hw4l |
|---|---|---|---|---|---|---|---|---|---|
| jar | round 28 | + fuel fix | hw4e's | 0387fd8 | 0387fd8 | 0387fd8 | 0387fd8 | 0387fd8 | 0387fd8 |
| setting | as built | as built | no break-up | Defend off | Defend off | no break-up | as built | Defend off | as built |
| worlds lost, m108 / end | 25 / 51 | 3 / 37 | 8 / 26 | 7 / 22 | 3 / 33 | 15 / 42 | 5 / 37 | 6 / 26 | 6 / 43 |
| marines, m96 / end | 81k / 9k | 103k / 14k | 139k / 63k | 112k / 71k | 133k / 61k | 126k / 35k | 159k / 35k | 99k / 39k | 94k / 7k |
| hives end / killed | 243 / 5 | 224 / 3 | 164 / 9 | 167 / 3 | 305 / 4 | 264 / 4 | 268 / 3 | 239 / 4 | 272 / 1 |
| strikes / landings | 295 / 125 | 323 / 128 | 332 / 99 | 298 / 109 | 319 / 105 | 317 / 112 | 355 / 129 | 325 / 136 | 315 / 118 |
| hulls broken up | 4,153 FP | 4,996 FP | 0 | 1,443 FP | 1,955 FP | 0 | 6,624 FP | 1,633 FP | 4,111 FP |
| end month | 125 | 126 | 133 | 134 | 134 | 130 | 133 | 127 | 132 |

Details: `war-sim-real-runs.md` 7-15. hw4h is the surprise: the same setting as hw4g, a war that held longer,
then a late collapse with a swarm that never stopped growing (305 hives against hw4g's 167). hw4j is
the other: as built, 10 lost by month 120, then 27 more in 13 months.

## 4. The simulator tonight

The question was why the simulator lands on so many more colony strikes than the game (hw4g by month 134: 246
landings against 109, beachheads overrun 187 against 62). Found, in order (`war-sim-calibration-r29.md` "Fronts, the
gate and the runaway swarm"):

- **The strike gate grows only with guards in the system** (worlds with no guard keep their day-5 gate for 10 years).
  `warsim_gateSystemGuards` built, off: it moves nothing.
- **The simulator's beachheads die twice as fast** (median 83 days against 157-173). The game's colonies counter-attack
  on stability (first hit 54-60 days after a landing, then every 25-28; the simulator 23 and 18) and both sides gain
  veterancy. `warsim_counterAttackPace` 0.68 + `warsim_firstCounterAttackPace` 0.55, and `warsim_veterancy`, built.
  The pace is on by default (check 1,718 -> 1,735 inside, the medians' distance 1,111 -> 1,027). The game's log shows
  veterancy at work (a front's batterings shrink 100, 83, 69 ... 40 marines as its level climbs); with it on as well,
  the options table sits on the game for two of the three columns (worlds lost at month 130: as built 49.5 against
  51 and 37, no break-up 27 against 26; landings 108 against 125-128 and 104 against 99). Defend off stays out (40
  against 22 and 33, landings 1.8x). Veterancy stays off: once the brace fix below was in, it cost 108 cells inside (1,794 -> 1,686) for distances a little better.
- **The game sends relief to every invaded colony** (`planRelief`): 293-566 fleets a run, 126k-210k FP. The simulator
  sent relief to forward bases only. `warsim_reliefToInvaded` built (its volume matches: 215k FP in one seed),
  off: alone it is neutral (inside 1,670, distance 1,098); its two parts, the relief fighting the guard and councils
  owing relief only while short, are each worse.
- **The brace came a day late.** The simulator's fronts asked whether to break off an assault before the day's attrition,
  the game's after, so 24 of 138 fresh beachheads were overrun at exactly 2.03:1 (game: 1 of 85). Fixed behind
  `warsim_braceAfterAttrition`, on by default: the check rose 1,735 -> 1,794 inside, on every run.
- **Why hw4g's swarm stalled and hw4h's ran away** (same setting): the hive planner's invest rule builds a forge only
  while the supply stock pays a forge and a founding kit. hw4g's stock never got there, so forges stuck at 21-24 and
  size-4 hives (where strikes stage) in 10-11 systems against hw4h's 43. The simulator's staging systems run about
  1.3x hw4h's, and so do its strikes (404 against 298 on hw4g's settings): the rest of the Defend-off gap.
- **The late runaway swarm is inside the game's spread**: hw4h's month-134 swarm sits on the simulator's medians.
- **Where it ends up** (r42: the new defaults, the options table, 60 seeds to month 130; the game's in brackets). As
  built 53 worlds lost (51, 37, 37 at the runs' ends) and 123 landings (125, 128, 129); no break-up 25 (26, 42) and 118 (99, 112).
  Those two are close to the game's. Defend off: 39.5 (22, 33), 233 landings (109, 105), 175 overruns (42, 62).
- **Why Defend off stays out: its fronts die young.** Landings per strike match the game on the other two settings
  (0.36 against 0.42, 0.33 against 0.30). On Defend off it's 0.58 against 0.33: with no guard feeding them, the
  simulator's fronts are overrun sooner, so the next strike makes a new landing where the game's reinforces. The
  game's counter-attacks have one shape on every setting (later ratios p50 1.57, p90 2.02-2.03, 7-10% overrunning).
  The simulator's drifts without a feed (p90 3.65-4.74, about 40%). Three game rules it lacked, built off (4d68264):
  the garrison follows the structures' disruption clock rather than the front's holding flag (`warsim_wearClock`),
  reinforcements dilute a front's entrenchment and level (`warsim_reinforceDilutes`, `ThreatGroundFronts.resupply`),
  and veterancy. On seed 3 they bring Defend off to 121 fronts / 66 overrun / 23 worlds lost (game 105 / 42 / 19-21
  at month 126), with the game's ratio shape on all three settings. On all seven runs (30 seeds each) they give the best
  medians (distance 1,448 -> 1,394, war rows 240 -> 224) but 99 fewer cells inside, all lost on the as-built runs,
  where veterancy makes fed fronts too safe (month 120 overruns 23.5 against hw4d's 46). With relief to invaded
  colonies on as well: 2,432 inside against base's 2,493. All stay off.
- **What a fed front lacks: its guard goes home** (`war-sim-calibration-r29.md` "The guards go home"). In the game
  108-157 guards a run (of 204-222) stand down with their front still standing: relief fleets wear them in vanilla
  battles, on screen or off, and a guard below a third of its arrival FP goes home unless it has started breaking
  hulls. Most overrun fronts had lost their guard over a month before. The simulator's guards stood to the end.
  `warsim_guardStandDown` (dd6f8b2) builds the rule: with the front rules, as-built overruns come to 1.4x the game's
  (from half) and the war rows are the best yet (227), but 2,407-2,452 inside against 2,493. Off. A relief that takes
  an uncommitted guard home in one battle, as the game's does (`warsim_reliefTakesGuard`, fb784dd), is worse (2,390,
  overruns 1.7x). Every guard the simulator lets go costs it overruns the game does not have: the gap is in its
  fronts' own staying power, not the guards.

## 5. Still out

- The simulator on Defend off (above): per landing it can match the game, but only with rules that break as built,
  and its strikes still run about 1.5x the game's on that setting (366 against 239-251 by month 120).
- How hard the game's relief hits a guard: there, one relief task force (median 360-717 FP) takes a guard below a
  third in one battle, a median 23-35 days after it is placed. The simulator's `reliefFight` against that is the next
  step for the as-built fronts.
- Forward bases: the simulator founds 2-4 times as many links a month after month 84; the game's factions run out of a
  base that can pay (127 `cannot pay for a link` lines after month 84 in hw4e).
- STARVE: the game sails STARVE expeditions about twice as often after month 84.
- NPC sieges sailed: about twice the game's with any break-up off (Defend off 23.5 against 11, 11 and 21; none 37 against 17 and 8); as
  built 8 against 12-13. All stop by month 96-108 in both.
- The councils play twice as often in the simulator (RECON 332 against 184 by month 134 on hw4h): its RECON plays get a
  fresh picture in 11 days against 23, and its STARVE plays end in 56 days against 179.

## 6. Machine-local

`%TEMP%\threatinc-tests`: `run-go.ps1 -Tag -Clone [-Days] [-Knobs "k=v"]` (one new-game run with wrap-up and
restore), `chain-hw4h.ps1` (tonight's three replications), `chain-hw4k.ps1` / `chain-hw4l.ps1` (the third Defend-off run, the fourth as built), `fit7.ps1 -Tag -Extra` (a
check on all seven runs), `chk.ps1` (a check), `fit-q.ps1 -Tag -Extra` (checks on all
five runs with one extra setting), `sumfit.sh <tag>...` (a fit's inside counts and distances over the five runs), `sv.ps1` (one verbose simulator
run), `rows2.pl` / `flip.pl` / `mdist.pl` (rows, in-band flips, median distance), `ovr.pl` / `fstat.pl` (counter-attack
ratios, fronts by outcome; they read the simulator's verbose log too),
`guardend.pl` / `standdown.pl` / `guardlife.pl` (the game logs' guards: the last guard line before each overrun, stand-downs
with the front standing, guard lives and the relief sent over them),
`life2.pl` / `flife.pl` / `first.pl` / `pace.pl` / `gg.pl` / `ca.pl` (the game logs' strike outcomes, front lives,
counter-attacks, gate). Saves `...ng6`-`ng14` are clones (hw4d-hw4l); the pristine save is untouched.
