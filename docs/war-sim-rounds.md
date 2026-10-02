# War simulator: the rounds from 18 on

Split from `war-sim-humans.md` on 2026-10-02; the sections keep their numbers (1-7 are in `war-sim-humans.md`, 8-12 in
`war-sim-council-trials.md`). Both sides' trials from round 18 on, on the defaults of the day: `reserveSurplusMult` 1.5
for the humans, `hiveSurplusMult` 1.0 for the hives. How a cell is judged: `war-sim.md` 7 and facts.md Decisions.

## 13. Round 18: the knob is shared; re-tests on the humans-only base; the swarm's levers (2026-10-02)

**The built change is not round 17's cell.** `threatinc_reserveSurplusMult` is read by `ThreatReserves` (the factions' pools)
and by `ThreatFuel` and `ThreatColonyUpkeep` (the hives' banking of their production); in the simulator `SwarmKnobs.surplusMult`
scales `SwarmEconomy`'s income the same way, and the human pools did not read the knob at all until this round
(`HumanPools.daily` now scales the accrual fit by `reserveSurplusMult` / 1.0, marines by `reserveTroopSurplusMult` / 0.5).
Round 17's winning cell was `warsim_accrualMult` 1.5 - the humans alone. With settings.json at 1.5 for both sides (30 seeds):
new game hives 185 [138-230] against 117, threatScore 4,109 [2,887-4,947] against 842 (round 17's cell 886), humanScore 17.5
against 21 (cell 28.5), kills 2.5 against 6 (cell 8), paid hammers 11.5 against 27, both-sides 37%; mid-war hives 226 against
145, threatScore 867 against 307 (cell 338), humanScore 20 against 11.5 (cell 33.5), kills 0, one-sided 97%. The swarm's
production is the larger base, so a shared multiplier hands it the bigger gain and the humans end below their old score.
A humans-only lever needs its own knob in the mod (`ThreatReserves.accrualPer30`'s multiplier split from the hives'), or
`reserveSurplusMult` back at 1.0 until it has one. This round's grid therefore runs on the humans-only base
(`threatinc_reserveSurplusMult=1.0; warsim_accrualMult=1.5`), with the shared 1.5 as a cell against it.

The same day the hives' banking was split off as `threatinc_hiveSurplusMult` (1.0; `ThreatFuel`, `ThreatColonyUpkeep`;
`SwarmKnobs.surplusMult` reads it) and `reserveSurplusMult` 1.5 became the humans' alone (`ThreatReserves.surplusMult`). The
new defaults against this base on the same 30 seeds (`compare`, `-killWeight 1`, `-months 104` / `48`): new game humanScore
30 against 28.5, threatScore 864 against 886; mid-war 32.5 against 33.5, 336 against 338; every class share identical. The
one real difference is marines: `warsim_accrualMult` scaled all four commodities, the knob leaves marines at
`reserveTroopSurplusMult` as the mod does (and the simulator still puts armaments under `reserveSurplusMult`, where the mod
has them under the troop share) - within seed noise on both starts.

Cells, 30 seeds, both starts, every one on that base. Human side, ranked by humanScore: the shared 1.5 (both sides);
relief to the besiegers (`warsim_reliefToBesiegers`, round 16 r); links waiting on an unpaid siege (`warsim_linkWaitsForSiege`,
round 15 e); upkeep x0.5 (`warsim_suppliesPerFPMult`, a diagnostic - not a rule the mod may hold, round 17); the council's
major plays per FP (`threatinc_councilMajorPlayFP` 3000 -> 2000, 5000); the muster floor (`threatinc_councilMusterFloor` 0.5
-> 0.25, 0.75); the humans' accrual at 1.25 and 2.0 (`warsim_accrualMult`) to show where 1.5 sits. Swarm side, ranked by
threatScore with humanScore not under the base's p10 and no quieter war: the Seeding Swarm's price (`warsim_seedPriceMult`
0.75, 1.25; `SwarmKnobs.foundSupplies`), the EXPAND feed share (`threatinc_feedShareExpand` 0.5 -> 0.35, 0.7), the
CONSOLIDATE trigger share (`threatinc_stanceConsolidateShare` 0.5 -> 0.25, 0.75), the relief strike's weight
(`threatinc_strikeReinforceWeight` 4 -> 2, 8). Not knobs, so not cells: the Bastion's timing (`SwarmFit.BASTION_DAYS` 120 and
`BASTION_FP`, bought from idle fabrication in `SwarmEconomy.militaryTier`) and a strike reserve share (the simulator's
strikes are sized by `StrikeRules.sustainableFP` on the spare stock, no share knob).

**New game, 30 seeds** (humanScore, threatScore, bases founded/held/destroyed, hives killed, hammers paid/unpaid, mutual,
both-sides share; `*` clear). The humans-only base: 28.5 [17.8-50], 886 [563-2,320], 71.5/17/23.5, 8, 30.5/12, 1.3, 60%
(one-sided 20%, quiet 0%, other 20%) - round 17's cell reproduced (28.5, 886).

| cell | humanScore | threatScore | bases f/h/d | kills | paid/unpaid | mutual | both |
|---|---|---|---|---|---|---|---|
| shared 1.5 (both sides) | 17.5 | 4,109* | 84/13.5/51* | 2.5 | 11.5/8 | 0.4 | 37% |
| r relief to besiegers | 33 | 788 | 58.5/16/15* | 8.5 | 28.5/14 | 1.4 | 60% |
| e links wait on siege | 27.5 | 970 | 64/16.5/22.5 | 8 | 26/12 | 1.2 | 60% |
| upkeep x0.5 (diagnostic) | 28.5 | 767 | 70.5/13.5/29 | 12 | 48/10 | 2.0 | 77% |
| councilMajorPlayFP 2000 | 27 | 713 | 67/15.5/26 | 9.5 | 34/16 | 1.5 | 60% |
| councilMajorPlayFP 5000 | 30 | 773 | 64.5/19.5/25 | 9 | 27.5/10.5 | 1.5 | 57% |
| councilMusterFloor 0.25 | 27.5 | 886 | 69.5/16/23 | 8 | 30.5/12.5 | 1.3 | 60% |
| councilMusterFloor 0.75 | 28.5 | 881 | 71.5/17/23.5 | 8 | 30.5/12 | 1.3 | 60% |
| accrual 1.25 | 24.5 | 807 | 61/14/25 | 9 | 28.5/14 | 1.5 | 63% |
| accrual 2.0 | 27.5 | 680 | 68/15/27.5 | 14 | 51/15.5 | 2.4 | 83% |
| seedPriceMult 0.75 | 23 | 540 | 49.5/9/19.5 | 14 | 43/10.5 | 2.1 | 57% |
| seedPriceMult 1.25 | 25 | 588 | 61/12/24 | 14 | 41.5/16.5 | 2.2 | 87% |
| feedShareExpand 0.35 | 27.5 | 924 | 70/15.5/26.5 | 7 | 23.5/15 | 1.2 | 53% |
| feedShareExpand 0.7 | 26 | 755 | 68/15/23 | 11 | 33/16 | 1.7 | 67% |
| stanceConsolidateShare 0.25 | 20 | 648 | 52.5/10/24 | 12.5 | 44.5/16 | 2.2 | 87% |
| stanceConsolidateShare 0.75 | 32.5 | 1,587 | 74.5/21.5/25.5 | 5.5 | 24/17 | 1.0 | 50% |

The muster floor at 0.25 and 0.75 moves nothing (0.5's figures to the half-point): the floor is a share of the FP the play
sent (`Play.plannedFP`, as `ThreatPlays`), so a muster that lost nothing en route always clears it. Section 14 takes that up.
`threatinc_strikeReinforceWeight` 2 and 8 reproduce the base to the digit on both starts: `SwarmOps.strike` multiplies a
relief target's weight by it, and the pick never turns on that weight, so the knob is not testable here.

**Mid-war, 30 seeds.** Base 33.5 [14.8-49.1], 338 [294-373], 76/33.5/33, kills 0, 5/1.5, mutual 0, one-sided 73% (both 0%, quiet 0%) -
round 17's cell again (33.5, 338). Hammers hardly fire from month 114 (10 plays, 5 paid, 0 kills a run), so the start is
decided by links and guards, and the humans' accrual is the only lever that moves it: `warsim_accrualMult` 1.0 -> 11.5
(round 17), 1.25 -> 20.5, 1.5 -> 33.5, 2.0 -> 42.5 (new game 21, 24.5, 28.5, 27.5 - it saturates at 1.5 there).

| cell | humanScore | threatScore | bases f/h/d | paid/unpaid | note |
|---|---|---|---|---|---|
| shared 1.5 (both sides) | 20 | 867* | 104/20/74.5* | 1*/0.5 | one-sided 97% |
| r relief to besiegers | 33.5 | 327 | 68.5/33.5/14* | 5/2.5 | destroyed bases halved, score flat |
| e links wait on siege | 33 | 329 | 79.5/33/29.5 | 5.5/2 | |
| upkeep x0.5 (diagnostic) | 36.5 | 339 | 87/36/36.5 | 8/3 | |
| councilMajorPlayFP 2000 / 5000 | 34 / 31.5 | 325 / 345 | 80/33.5/28.5, 82.5/31/37 | 5/4, 4/1 | |
| councilMusterFloor 0.25 / 0.75 | 33.5 | 338 | base to the digit | | inert (section 14) |
| accrual 1.25 / 2.0 | 20.5 / 42.5 | 322 / 349 | 59.5/20/26, 91/42.5/36.5 | 3/1.5, 6.5/3 | |
| seedPriceMult 0.75 / 1.25 | 33 / 34 | 409* / 294 | 79/33/36, 77.5/33/30 | 3.5/2, 4/1.5 | cheaper seeds: new game 540, mid 409 - the signs disagree |
| feedShareExpand 0.35 / 0.7 | 33.5 / 33 | 338 / 365 | base, 81/32/34 | | 0.35 inert at mid-war |
| stanceConsolidateShare 0.25 / 0.75 | 33 / 33.5 | 333 / 338 | 76.5/32/31, base | | 0.75 inert at mid-war |

**60 seeds** (the cells nearest a win; new base 27.5 [16-40.5], 849 [546-2,264]; mid base 32.5 [14.9-49], 336 [293-372]):
`warsim_huntMinShare` 0.1 new 30 (65% of seeds), mid 31.5 - noise; `warsim_reliefToBesiegers` new 28.5, mid 33.5, bases
destroyed 25 -> 14* and 35 -> 14* - the only clear effect of the round, and the score does not follow it (fewer links sail,
round 16); `threatinc_stanceConsolidateShare` 0.75 new threatScore 1,382 [637-2,364] against 849 (76% of seeds), humanScore
32.5 above the base's p10, both-sides 53% against 65%, mid-war identical to the base - the one swarm candidate, not clear by
the rule (medians inside each other's bands); a 120-seed new-game rerun would settle it.

**Verdict: no change on either side.** The humans-only base holds round 17's figures on both starts; nothing clears it. The
swarm's levers at hand (seed price, feed share, consolidate trigger, relief weight) are inert or mixed at mid-war; the new
game shows one candidate (consolidate at 0.75) to rerun. Hypotheses for round 19: a humans-only income knob in the mod (the
precondition for any of this to be buildable); the CONSOLIDATE share at 0.75 at 120 seeds; a hunt-share want of the
hammer's own (section 14, so a muster floor can judge something); the relief rule paired with a cheaper link so the bases it
saves are not paid for in links unsailed.

## 14. Round 18 addendum: the muster floor judges nothing; what the hunts alone achieve (2026-10-02)

In the mod and the mirror alike, `plannedFP` is the FP `force` actually sent (`councilHammerShare` x the payable FP, if at
least 25 FP), so `councilMusterFloor x plannedFP` only fails a muster that lost ships en route: `hammer.underFloor` is 0 a
run [0-1] on both starts. The hunts then sail whether or not the siege is paid ("the hunts go alone"). Counters split a
hammer that reached STRIKE by whether its siege sailed (`HumanCouncil.end`, `Play.struck`/`sieged`): on the humans-only
base, new game, 9 hammers a run [4.9-19.6] go alone against 28 [6-70] with a siege; the hunts alone take 0 worlds in every
run and lose 1,142 FP of hunt fleets a run [321-3,036] (the sieged hammers take 1.5 [0-6.1] and lose 5,422 FP of hunts).
Mid-war: 1 alone a run [0-6.2], 0 taken, 56 FP lost; 4.5 sieged, 0 taken [0-1]. The hunts alone are a pure loss: roughly a
fifth of the hunt FP the hammers lose, no world taken.

Three switches, off by default, 30 seeds on the humans-only base (humanScore new / mid; base 28.5 / 33.5):

| switch | symbol | new | mid | what moved |
|---|---|---|---|---|
| (a) floor vs want | `warsim_musterFloorVsWant`, `HumanCouncil.floorBase` | 27 | 34 | fails 26 musters a run (of 57.5 hammers): paid sieges 30.5 -> 17.5, kills 8 -> 5, hunts alone 9 -> 2 |
| (b) no hunts alone | `warsim_noHuntsAlone` | 30 (44% of seeds) | 30 | 9.5 stand-downs a run, hunts alone 0; kills 8 -> 13 (65%), paid 30.5 -> 37, plays 59 -> 75.5, mutual 1.3 -> 2.0 |
| (c) hunt share >= 0.1 of the strongest world | `warsim_huntMinShare` | 33.5 (64%) | 30.5 | 2 plays a run fail at PREPARE; hunts alone 9 -> 7.5 |
| (c) hunt share >= 0.25 | `warsim_huntMinShare` | 32 (59%) | 32.5 | 5.5 fail a run; hunts alone 9 -> 7.5 |

(a) is wrong as a rule: the want `floorBase` compares against is the report-sized siege plus the hunt share, and the
muster holds only the hunts, so it fails the plays whose siege the pools would have paid. The floor would need a want for the
hunt share alone, which neither the mod nor the mirror defines. (b) does what it says - no hunt is lost alone - and the
plays recycle faster (a stood-down play ends instead of watching), which lifts paid sieges and kills, but humanScore does
not follow (median up, 44% of seeds). (c) at 0.1 is the best human cell of the round on the new game and is noise on the
mid-war start; the 60-seed rerun is in section 13's verdict.

## 15. Round 19: the consolidate share's shape; converting the hunts' FP into a paid siege (2026-10-02)

On the new defaults (`reserveSurplusMult` 1.5 humans, `hiveSurplusMult` 1.0), 60 seeds, new game 104 months, `-killWeight 1`
with the kill-weight-2 score printed alongside (`Main.score`: `humanScoreK2`, `threatScoreK2`, no second run). Mid-war is
not rerun for the consolidate share (0.75 reproduced the base to the digit there, section 13) nor for `huntMinShare` 0.1 and
`noHuntsAlone` (31.5 / 30 against 32.5 / 33.5 at 60 / 30 seeds, section 13-14).

New switch `warsim_hammerWaitsForSiege` N (`HumanCouncil.strike`, `Play.waitSince`, `WAIT_RETRY_DAYS` 10): a hammer whose
siege the pools cannot pay at STRIKE holds its mustered hunts at the base (`HumanOrder.sailDay` back to `HELD`; the
muster's upkeep runs on) and re-tries the siege every 10 days (`musterCheck` waits for `phaseDue`) until N days have passed,
then stands down (`hammer.waitExpired`). `hammer.waitPaid` counts the sieges a wait bought; `hammer.waitRetries` the
re-tries (each is also a `strike.unpaid`). Relief that goes home once its strike is gone (2c) was not built - time.

Every command, from the repository root with `tools/warsim/out` built (`tools/warsim/build.ps1`), is
`java -cp tools\warsim\out warsim.Main compare -a warsim_noop=0 -b <cell> -start tools\warsim\start\pd9a-newgame -seeds 60
-months 104 -killWeight 1` (mid-war: `pd9a-month114`, `-months 48`), `<cell>` one of
`threatinc_stanceConsolidateShare=0.65|0.75|0.85`, `warsim_noHuntsAlone=true`, `warsim_huntMinShare=0.1`,
`warsim_hammerWaitsForSiege=60|120`.

**Results** (new game, 60 seeds; base on the new defaults: humanScore 29 [18-44.3], K2 38.5; threatScore 844 [556-2,243],
K2 880; bases founded/held/destroyed 64/17/26, kills 10, hammers paid/unpaid 33/14, mutual 1.7, both-sides 63%):

| cell | humanScore (K2) | threatScore (K2) | seeds | bases f/h/d | kills | paid/unpaid | mutual | both | verdict |
|---|---|---|---|---|---|---|---|---|---|
| consolidateShare 0.65 | 31 (44) | 1,066 (1,108) | T 60% | 74/17.5/27.5 | 9.5 | 33/15 | 1.7 | 63% | not clear |
| consolidateShare 0.75 | 30 (39) | 1,520 (1,553) | T 76% | 75/21/27.5 | 6 | 21/16 | 1.0 | 52% | not clear: [657-2,402] inside [556-2,243] |
| consolidateShare 0.85 | 31 (40) | 1,176 (1,228) | T 63% | 75.5/21/27 | 7 | 24/15 | 1.2 | 55% | not clear; the peak is 0.75 |
| noHuntsAlone | 26.5 (35) | 810 (853) | H 39% | 62.5/16/25.5 | 8.5 | 32.5/16 | 1.5 | 63% | no - round 18's 30-seed gain was noise |
| huntMinShare 0.1 | 30 (38) | 755 (804) | H 57% | 68.5/17/25 | 8.5 | 33/12 | 1.5 | 63% | no - noise at 60 seeds, as on the old base |
| hammerWaitsForSiege 60 | 27 (39) | 782 (825) | H 43% | 67/14.5/25 | 9 | 32.5/38 | 1.5 | 63% | no: 5 sieges a run bought by the wait (`hammer.waitPaid`), 2 waits expire, 33 re-tries, and the muster's upkeep costs more bases than the sieges win |
| hammerWaitsForSiege 120 | 28 (40.5) | 753 (799) | H 46% | 66/14/25 | 11 | 36/52.5 | 1.7 | 60% | no: the same 5 sieges a run as 60 days, 46.5 re-tries, bases held 14 |

The consolidate share's shape peaks at 0.75 (0.5 -> 844, 0.65 -> 1,066, 0.75 -> 1,520, 0.85 -> 1,176) without a
quieter war (both-sides 63% -> 52% at 0.75, quiet 0% throughout) and with the humans above their p10 (30-31 against 18) -
the swarm's one lever of the round, and not clear by the rule at 60 seeds because the seed spread of threatScore (p10-p90
556-2,243) is three times the gain. Kill weight 2 ranks every cell the same as weight 1.

**Verdict: no change on either side.** The hunts-alone FP (round 18) cannot be turned into sieges by waiting: the
wait buys 5 sieges a run but the held muster's upkeep is paid from the same pools the links draw on, and bases held fall
17 -> 14.5. Standing the hunts down outright (noHuntsAlone) saves the FP and changes nothing the score sees.

## 16. Round 20: relief that goes home; the swarm's rules and knobs (2026-10-02, on the second machine)

Run after the move to another computer (`handover-2026-10-02.md`); the base reproduced there (30 seeds: new game humanScore
29.5, threatScore 821, kills 8.5; mid-war 32.5 / 336). Every compare is `java -cp tools\warsim\out warsim.Main compare -a
<A> -b <B> -start tools\warsim\start\pd9a-newgame -seeds 60 -months 104 -killWeight 1` (mid-war `pd9a-month114`,
`-months 48`); `<A>` is `warsim_noop=0` (the base) unless a row says otherwise.

**Fidelity fix first.** `HumanPools.daily` now banks heavy armaments at the troop rate with marines
(`threatinc_reserveTroopSurplusMult` / 0.5), as `ThreatReserves.surplusMult` does; it had them under `reserveSurplusMult`, 1.5x
too fast since round 17's build. The base moves inside seed noise (30 seeds, new game humanScore 29.5 -> 26, threatScore
821 -> 778, kills 8.5 -> 10; mid-war unchanged to the digit): armaments do not bind. The 60-seed base of this round: new
game 29 [16.9-42] / 782 [566-2,243], bases founded/held/destroyed 65/15/25.5, kills 10, both-sides 65%; mid-war 31.5
[14.9-49] / 336 [293-372], 78.5/31.5/35, kills 0, one-sided 62%.

### 16a. Relief that goes home (round 16's idea, cell 2c)

The mod already answers a seen strike: `ThreatFrontlines.callGuard` brings a link's guard up to the seen strikes bound for it
x `frontlineGarrisonMargin`, outside the upkeep budget, if the depots pay the voyage; a rear link's called guard goes home
once no seen strike is bound for it (`ThreatFrontlines.garrison`), a front link's stays and is billed with the garrison.
The simulator's base has none of it. Switches, all off by default:

- `warsim_reliefToBesiegers` (round 16, `HumanBases.besiegers` / `garrison(.., relief)`): the call.
- `warsim_reliefRearGoesHome` (new): the mod's rule - a rear link's relief (`World.reliefFP`, kept apart from the standing
  guard; worn with it in `SwarmOps.strike` / `stationSiegeDay`) sails home when no reported strike bears on the base
  (`HumanBases.reliefHome`: the way home paid in fuel like the way out, the deposit refunded by health at
  `HumanSide.settle`). r + this is the mod-faithful cell, `m`.
- `warsim_reliefGoesHome` (new, the rule on trial): the front's relief goes home too. r + this is `rg`. A relief whose base
  fell before it arrived turns for home (`reliefToBesiegers.tooLate`). Counters `reliefToBesiegers.wentHome`, `.wentHomeFP`,
  `.homeUnpaid`.

humanScore / threatScore, bases founded / held / destroyed, share of seeds the cell's humanScore leads in; `*` clear.

| cell (A -> B) | new game | mid-war |
|---|---|---|
| base -> r (relief stays) | 30.5 / 792, 56.5 / 18.5 / 13*, 51% | 34.5 / 324, 65.5 / 34.5 / 14.5*, 54% |
| base -> m (the mod's rule) | 32 / 800, 62 / 22 / 14.5*, 64% | 36.5 / 323, 63.5 / 36 / 13*, 62% |
| base -> rg (all relief goes home) | 31.5 / 772, 62 / 21 / 15.5*, 64% | 41 / 320, 70 / 41 / 14.5*, 70% |
| r -> rg | 31.5 against 30.5, 62% | 41 against 34.5, 73% |
| m -> rg (the new rule alone) | 31.5 against 32, 54% | 41 against 36.5, 75%, inside the band [24.9-47.1] |

Reading. The relief halves the bases destroyed in every cell (clear), as in round 16. Sending it home is what turns that
into bases held: a relief that stays is a garrison past the upkeep budget, and its upkeep blocks the next link (founded 65
-> 56.5 under r, 62-70 once it goes home). Most of that gain is the rear's relief going home, which the mod does already;
the front's going home too adds nothing from a new game (54% of seeds) and 4.5 bases mid-war (75% of seeds, the median
inside the base's band) - not clear. **Verdict: no mod change.**

**Is the simulator's base wrong to lack the call?** Not on the evidence at hand, so the switches stay off. Against the two
real planner runs (`check -dumps tools\warsim\validation\pd9a` / `pd10a`, 30 seeds, 90 figures each without the logs): base
68 + 54 inside, r 67 + 59, m 63 + 54, rg 66 + 53 - no setting fits better. And the real council runs lost about two thirds
of their links to strikes with `callGuard` in the build (facts.md "How many forward bases do the factions hold"), where the
simulator's relief saves half: it knows a strike from the day it leaves a reported system and sails at once, while the mod
sees one a median 16 days after launch (`IncursionManager.detectStrikes`) and sends the guard only when the strike is due
within the voyage plus 20 days. To settle it, count `Frontline: ... reinforces|garrisons ... (rear; strike on its way` and
`faces the strike ... short` lines in `ti-pd9a.txt` / `ti-pd10a.txt` (on the first machine) against
`reliefToBesiegers.asked` 209 a run and `guardsSailed` 128.

### 16b. The swarm: two rules and the knobs round 18 left

Two rule switches, off at 0, no mod symbol behind either (`SwarmKnobs`):

- `warsim_consolidateExpansionShare`: a consolidating swarm keeps claiming systems at this share of its claim cap
  (`StanceRules.expansionShare` gives CONSOLIDATE 0), leaning away from the strongest rival as EXPAND does
  (`SwarmOps.trySpread`).
- `warsim_strikeSizedMargin`: a strike takes only the swarms the defence last seen calls for x this margin, at least two
  (`SwarmOps.strikeFrom`; the mod's `IncursionManager.launchStrike` sends every sendable swarm).

Screening, 30 seeds, new game, threatScore against the base's 814 (share of seeds ahead where it was read):

| cell | threatScore | note |
|---|---|---|
| `warsim_consolidateExpansionShare` 0.25 / 0.5 / 1.0 | 856 / 786 / 764 | noise |
| `warsim_strikeSizedMargin` 1.5 / 2 / 3 | 785 / 822 / 853 | bases destroyed 24 -> 36 / 29 / 32, humanScore 22 / 25.5 / 26; not the Threat's score |
| `threatinc_frontlineStrikeWeight` 1 / 6 (3) | 815 / 935 | noise at 60 seeds |
| `threatinc_stanceSecondaryShare` 1.0 | 881 (30%) | noise |
| `threatinc_stancePressRatio` 1.0 / 2.5 | identical to the base | inert |
| `threatinc_stanceDwellDays` 90 | 720 | worse |
| `threatinc_stanceWeakOdds` 0.25 / 0.75 | 728 / 739 | worse |
| `threatinc_strikeMinSize` 3 / 5 (4) | 723 / 1,407 (67%) | see 16d |
| `threatinc_feedSharePress` 0.5 (0.7) | 935 (60%) | 826 at 60 seeds: noise |
| `threatinc_feedShareConsolidate` 0.7 (0.9) | 1,067 (79%) | see 16c |

Neither rule moves the Threat's score: nothing to build from them.

### 16c. The consolidate feed share is also the sustenance cap

`threatinc_feedShareConsolidate` 0.9 -> 0.7 at 60 seeds: new game threatScore 782 -> 951 (78% of seeds), mid-war 336 -> 481
[433-520] against [293-372], every seed, clear - on a start where the swarm spends no month in CONSOLIDATE. The reason is
`ThreatColonyUpkeep.sustainShare`: the largest of the three stance shares is the share of the supplies' net sustenance may
take in any stance, and it prices `spareSupplies` (what a new trip may burn). At 0.7 the hive keeps 30% clear of sustenance,
launches fewer strikes (mid-war 249 -> 194, clear) and founds more (hives founded 93 -> 139, clear). 0.5 gives the same
mid-war figures to the digit (the cap is then `feedSharePress`, 0.7).

`warsim_sustainShare` (`SwarmKnobs.sustainShare`, `SwarmEconomy.feed`; 0 = as the mod) sets the cap apart from the stance
shares. 60 seeds, before 16d's fix (base 29 / 782 new game, 31.5 [14.9-49] / 336 mid-war), threatScore and humanScore:

| sustain share | new game | mid-war |
|---|---|---|
| 0.8 | 811 (64%) / 27 | 392* / 30.5 |
| 0.7 | 818 (84%) / 27.5 | 481* / 22.5 |
| 0.6 | 798 (63%) / 29.5 | 657* / 14, under the floor |
| 0.5 | 830 (71%) / 30.5 | 734* / 12 |
| 0.4 | 1,052 (78%) / 28 | 1,106* / 9 |

### 16d. The scouts' size gate, then the grid

**Fidelity fix.** `ThreatSwarmScouts.pickStaging` sends a Scouting Swarm only from a hive of `strikeMinSize` with its nexus
up; `SwarmOps.scouts` asked for the nexus alone. Fixed (`warsim_scoutsAnySize=true` restores the old simulator). 60 seeds,
old -> new base: new game humanScore 29 -> 28 [14.9-44], threatScore 782 -> 889 [569-2,506] (53% of seeds), scouts sent
239 -> 139 (clear); mid-war 31.5 -> 37 [19.9-51.2], 336 -> 324 [286-370], bases destroyed 35 -> 24.5, scouts 123 -> 88
(clear). Every cell below is against this base.

threatScore (share of seeds ahead) / humanScore; `*` clear. `fc` is `threatinc_feedShareConsolidate`.

| cell | new game | mid-war | dead years mid-war (0.5) |
|---|---|---|---|
| sustain 0.75 | 889 (80%) / 29 | 413* / 33 | 0.5 |
| sustain 0.7 | 885 (91%) / 28.5 | 462* / 31 | 0.6 |
| sustain 0.65 | 887 (75%) / 28 | 558* / 27.5 | 0.7 |
| sustain 0.7, fc 0.7 | 1,045 (71%) / 30 | as sustain 0.7 | |
| sustain 0.7, fc 0.5 (= fc 0.5 alone) | 1,569 (79%) / 28.5 | 462* / 31 | 0.6 |
| sustain 0.7, fc 0.3 | 1,343 (56%) / 31.5 | as sustain 0.7 | |
| sustain 0.65, fc 0.6 / 0.5 / 0.4 | 1,200 (72%) / 1,569 (78%) / 1,763 (75%) | 558* / 27.5 | 0.7 |
| sustain 0.6, fc 0.5 | 1,569 (78%) / 28 | 716* / 18, under the floor (19.9) | 0.8 (77% of seeds) |
| `strikeMinSize` 5 | 1,963 (78%) / 26 | 363 (80%) / 34 | |
| `strikeMinSize` 6 | 2,514* (85%) / 21.5 | 354 (73%) / 33 | |
| fc 0.5, `strikeMinSize` 5 | 2,042 (82%) / 27.5 | 482* / 28.5 | |

Reading.

- The new game's threatScore is two-humped (a contained swarm near 600-900, a runaway one near 2,500), so its median jumps
  where the share of seeds moves a little and the bands never part; read the share of seeds.
- **The consolidate feed share** peaks at 0.4-0.5 (79% of seeds at 0.5). A consolidating hive that puts 0.9 of its net into
  growing its colonies has nothing banked when it leaves the stance; at 0.5 its hive levels gained go 234 -> 394. The opening
  is untouched: to month 48 every figure is the base's (`batch`, 30 seeds).
- **The sustenance cap** is clear mid-war at every value and costs the humans bases held as it falls (36.5 -> 33 / 30 / 27 /
  18); 0.6 is under the humans' floor and lengthens the longest dead stretch. From a new game it does not bind.
- **`strikeMinSize`** 6 is clear from a new game, and not built: its gain is the war starting a year later (first strikes
  at month 37-48 against 25-36, `batch` checkpoints: strikes by month 36 9 / 3 / 0 at 4 / 5 / 6, worlds lost 3 / 1 / 0),
  the swarm consolidating 0 months against 11 because nobody attacks it, hives killed 6 -> 3, "both sides" 53% -> 30%. The
  stalemate clock starts at the first mobilisation, so the rule's quiet test cannot see a longer quiet opening; when the
  war starts is the user's (it is also the Phase 2 gate, `IncursionManager.getPhase`, and what `ThreatMissionIntel` calls a
  strike platform).

**Built: `threatinc_feedShareConsolidate` 0.9 -> 0.5** (settings.json, LunaSettings.csv, `LunaConfigBridge` marker 12). No
new knob: with the press share at 0.7 the cap falls to 0.7 by the mod's own rule. 0.65 scores more mid-war (558) on thinner
margins (humans 27.5, dead stretch 0.7 y) one step from the value that fails; a separate cap knob was not worth it. 120
seeds, the old defaults against the built ones (`compare -a "threatinc_feedShareConsolidate=0.9" -b "warsim_noop=0"
-seeds 120`):

| | new game | mid-war |
|---|---|---|
| threatScore | 889 [546-2,518] -> 1,636 [707-2,603], 76% of seeds | 326 [284-375] -> 464 [438-504], every seed, clear |
| humanScore | 29 [18-42.1] -> 29 [18.9-48.1], 61% | 37 [21.8-53] -> 31 [18.9-46], 36% |
| hives founded / levels gained | 126 / 233 -> 141 / 411 | 93.5 / 95.5 -> 140 / 141, clear |
| strikes launched | 210 -> 255 | 245 -> 188, clear |
| bases held / destroyed | 19 / 25 -> 21 / 26 | 36 / 26 -> 31 / 28.5 |
| dead years, quiet share | 0.6 -> 0.6, 0% | 0.5 -> 0.6, 0% |

The gain is a larger swarm, not levels lost and regained: at the end of the mid-war run it holds 195 hives against 148 and
502 size levels against 411 (clear), paid the same sustenance (2,310k against 2,277k), and broke off half as many strikes
(33 against 60).

Clear mid-war and ahead in 76% of new-game seeds, the humans above their floor (18 / 21.8) on both starts - the same
standing as round 17's winner. **The base from here** (120 seeds): new game humanScore 29, threatScore 1,636, hives killed
6.5; mid-war 31 / 464.

### 16e. The older verdicts on the built base (60 seeds)

- `threatinc_stanceConsolidateShare` (round 19's one lever) is now noise from a new game: 0.35 / 0.65 / 0.75 give 1,622 /
  1,668 / 1,939 against 1,569, ahead in 47% / 39% / 42% of seeds. What 0.75 bought before was fewer months in a stance that
  overfed growth; the feed share took that at the source. The knob stays 0.5.
- Relief: m (the mod's rule) humanScore 28.5 -> 30 (58% of seeds) new game, 31 -> 32.5 (60%) mid-war; rg (all relief goes
  home) 33 (57%) and 36 (62%); bases destroyed halve in all four (clear) and the longest dead stretch grows mid-war (0.6 ->
  0.8 y, 89% of seeds). 16a's verdict stands.

### 16f. The siege gate's trip price against the burn (round 16's open question)

Counters `siegeTrip.sieges`, `.priced` (the gate's supplies want less the deposit, at `HumanPlanner.launch`), `.burned` and
`.owed` (`HumanSide.upkeep`, the siege's parcels from muster to home). New game, 30 seeds, built base
(`compare -a "warsim_noop=0" -b "threatinc_warCouncil=false" -show siegeTrip.sieges,siegeTrip.priced,siegeTrip.burned,siegeTrip.owed`):

| | sieges | trip priced | burned | owed |
|---|---|---|---|---|
| council | 20 | 36.3k | 23.9k (66%) | 1.2k |
| planner | 304 | 699k | 336k (48%) | 16.4k |

The gate asks 1.5x (council) to 2.1x (planner) the supplies a siege goes on to eat: a siege is home, or smaller, well
inside the horizon it was priced for (which of the two was not split out). Round 17 already cut the price (`warsim_tripMult` 0.5,
`warsim_tripFixedDays` 30) and the humans' score did not rise, so the margin is not what holds the sieges back. Closed.
