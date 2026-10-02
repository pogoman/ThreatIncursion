# War simulator: the council trials (rounds 13-17)

Split from `war-sim-humans.md` on 2026-10-02; the sections keep their numbers (1-7 are there: what the human side
models and how it was fitted; 13 onward are in `war-sim-rounds.md`). Every round here ran before `reserveSurplusMult`
went to 1.5, so its base is the old one (new game humanScore 21, mid-war 11.5).

## 8. Round 13: the council docs' open ideas, trialled (2026-10-02)

Switches, all off by default, each mirrored from the doc's own words: `warsim_councilAlwaysSiege` (war-council-runs.md 5 A:
the strategy picks where, not whether - Hold hammers the cluster that threatens it, Starve runs one campaign beside the
hammer slot; `HumanCouncil.plan`), `warsim_councilMajorPerFP` (option B: the major-play limit is the faction's siege
capacity over this many FP, `siegeCapacity` = each base's `capacityFP` against its nearest cluster, counted in
`council.majorLimitDays`), `warsim_councilStarveToHammer` (option C: a starved campaign turns to the hammer in the same
play, straight to the muster; `advanceStarve`, counter `starveToHammer`), `warsim_councilReliefPause=false` (4.5: plays
are not held while relief is owed; `reliefPause`), `warsim_swarmConsolidateOnLosses` (swarm-strategy.md 4 decision 5, the
doc's words: CONSOLIDATE when the hive count falls or a front stands on a hive, never on pressure; `SwarmPosture.stance`).
The feint's own success test is counted at a feint play's end (`feint.drew`, `feint.notDrew`, `.landed`: did A's
reports rise within the watch, did the strike then land). Decision 3's numbers, learning and temperature are the mod's
knobs; the personalities trial sets `threatinc_councilPersonalities` to one `default` entry.

All cells council on, radar off, planner sizing on (round 12's defaults), 30 seeds; the base cell equals round 12's
"both" cell exactly. New game 104 months, ranked by mutual then humanScore (* outside the base cell's p10-p90):

| cell | threatKills (w / b) | humanKills | mutual | scores T / H | hives | bases | classes | plays: hammers / starves / sieges sailed |
|---|---|---|---|---|---|---|---|---|
| A + B3000 + C | 3.5* (1.9 / 1.5*) | 1.9* | 1.9* | 714 / 2* | 90.5* | 2* | both 83 | 225 / 14.5 / 73.5 (140 short of supplies) |
| relief pause off | 6.0 (2.1 / 3.9) | 1.0* | 1.0* | 873 / 8 | 133 | 8 | both 53, other 47 | 26.5 / 38.5 / 20 |
| B, capacity / 3000 | 6.3 (2.0 / 4.3) | 0.9* | 0.9* | 1070 / 11.5 | 138 | 11.5 | both 47, one-sided 37 | 43 / 49 / 21.5 |
| B, capacity / 6000 | 6.2 (2.0 / 4.2) | 0.6* | 0.6* | 908 / 12 | 132 | 12 | both 43, one-sided 30 | 31 / 33 / 16 |
| A | 5.2* (2.0 / 3.0*) | 0.4 | 0.4 | 1103 / 3.5* | 138 | 3.5* | both 13, one-sided 37 | 65 / 15.5 / 13.5 (81.5 short of supplies) |
| C | 7.7 (2.2 / 5.4) | 0.2 | 0.2 | 932 / 14.5 | 139 | 14.5 | one-sided 67 | 3 / 9 / 3 (2 turned to hammers) |
| consolidate on losses | 6.9 (2.1 / 5.0) | 0.2 | 0.2 | 1085 / 13 | 151 | 13 | one-sided 70 | 5 / 10 / 2.5 |
| personalities all Tri-Tachyon / all Hegemony | 7.9 / 7.4 | 0.2 / 0.2 | 0.2 / 0.2 | 1064 / 16, 1076 / 15 | 150 / 147 | 16 / 15 | one-sided 70 | 5 / 12 / 3, 5 / 10.5 / 2 |
| temperature 2 / 0.5 | 6.8 / 6.9 | 0.2 / 0 | 0.2 / 0 | 869 / 16.5, 1032 / 13.5 | 129 / 143 | 16.5 / 13.5 | one-sided 67 / 80 | 7 / 8.5 / 3, 4 / 9.5 / 2 |
| hammer share 0.8 / 0.4 | 6.9 / 6.9 | 0.1 / 0 | 0.1 / 0 | 1125 / 12, 1009 / 15 | 154 / 150 | 12 / 15 | one-sided 67 / 90 | 5 / 10 / 2 |
| base; muster floor 0.25 / 0.75; learning off | 7.1 (2.2 / 4.7) | 0 | 0 | 967 / 13 | 144 | 13 | one-sided 73, other 27 | 5 / 11 / 2.5 |

Mid-war 48 months: every cell's humanKills and mutual are 0 except relief pause off (0.3*, bases held 11.5 -> 8*). A
again burns the bases (held 11.5 -> 5*, 48.5 sieges short of supplies), A+B+C 2*; B3000 sails 3* sieges to the base's 0;
consolidate on losses puts the swarm in CONSOLIDATE 5.2 months of 48 (base 0; the start state's fronts on hives trigger it)
and halves its founding (88.5 -> 54*, hives at the end 145 -> 110*).

Reading. B is the one idea that raises the humans' killing without a cost: more plays at once, sieges sailed 2.5 -> 21.5,
bases held intact; its gain is capped by the relief pause (`council.heldPlayDays` 4324 -> 19138). The relief pause is the
main brake on everything - relief is owed about 70% of faction-days here (`council.reliefDays` 8713 of 12480), which round 7
found the simulator overstates, so relief-off's 1.0 is an upper bound and B's 0.9 is the honest figure. A fails for the
doc's own reason: a hammer per strategy without the means behind it - 81.5 of the sieges could not be supplied and the
bases that paid were lost (13 -> 3.5), which is why A+B+C's 1.9 costs the humans' score (2). C changes nothing: a
campaign reaches "starved" twice a run. Shares, the muster floor, learning, temperature and personalities move nothing
clear of seed noise - with planner sizing the hammer share only sizes the hunting force. Feints: only A/A+B+C run them;
they drew the swarm 0.5 of 2.5 and 2 of 10.5 times (mid 0 of 5.5), and the strike landed 0 times after a drawing feint, 2
after one that did not - the feint as built does not pull. Decision 5 as worded consolidates more mid-war, not less:
a single front on a hive is enough; the pressure path barely fires now (2.1 months of 104 in the base).

## 9. Round 14: paying is the brake (2026-10-02)

The mod's new defaults mirrored (`pausable`, `siegeCapacity`, `councilMajorPlayFP` 3000). Base cell, council, radar off,
planner sizing, 30 seeds. New game 104 months: threatKills 6 (2.1 / 3.7), humanKills 1.0 [0.1 - 2.5], mutual 1.0, scores
805 / 12, hives 117 [56 - 180], bases 12, both sides 53%; hammers 52 a run, 27 reached STRIKE and paid a siege, 14.5
did not; 137 starve checks found no saturation the pools pay (48.5 sailed). Mid-war 48 months: humanKills 0, 3.5 paid,
1.5 unpaid. The seeds are bimodal (hives 56-180), so no trial's humanKills is clear of noise at 30 seeds.

**What the unpaid were short of** (per unpaid hammer, first world): the report-sized siege needs 1,790 FP, the base pays
the voyage of 256; short of supplies by 3,260 against 2,260 callable (10.5 of 14.5 short of supplies, 2.5 of fuel, 1 of
marines; fuel callable 24,500, marines 5,100). Mid-war: 6,200 FP needed, 1,100 paid, short 9,840 supplies against 13,200.
Supplies are the brake; fuel and marines are not.

**Where the supplies go** (new game, a year, income 183k): forward-base links 60k, guard upkeep 46k, fleet upkeep 44k,
base (colony size) upkeep 27k, guard voyages 8.6k, saturation 8.3k, hunts 7.9k + hunting forces 5.2k, squadrons 5.9k,
scouts 3.5k, play sieges 2.5k (1.4%). Per faction (play sieges / hunts / guard upkeep / fleet upkeep): hegemony
358 / 2,026 / 12,230 / 14,769, persean 714 / 1,673 / 12,115 / 10,621, tritachyon 337 / 827 / 5,860 / 5,195. Mid-war
(income 377k): links 128k, fleet upkeep 113k, guard upkeep 99k, play sieges 611. Fuel (income 379k): saturation 75k,
guard voyages 54k, hunts 32k, scouts 24k, squadrons 18k, sieges 6k. The forward-base line (links, guards, their
voyages) takes about two thirds of the supplies; sieges get one or two percent.

| cell (new game) | threatKills (w / b) | humanKills | mutual | T / H | hives | bases | hammers paid / unpaid | classes |
|---|---|---|---|---|---|---|---|---|
| base | 6 (2.1 / 3.7) | 1.0 | 1.0 | 805 / 12 | 117 | 12 | 27 / 14.5 | both 53 |
| a hunts held while a hammer stages | 6 (2.0 / 3.9) | 0.5 | 0.5 | 863 / 13.5 | 135 | 13.5 | 13 / 14 (89.5* hunts held) | both 30 |
| b guard upkeep x0.5 | 6.2 (2.0 / 4.2) | 0.8 | 0.8 | 768 / 17.5 | 115 | 17.5 | 19.5 / 14 | both 37 |
| c coalition pays | 5.7 (2.0 / 3.7) | 1.5 | 1.5 | 799 / 12 | 120 | 12 | 36.5 / 14.5 (73 saturations) | both 60 |
| d orbit margin 1.25 / 1.0 | 6.1 / 5 | 0.6 / 1.1 | 0.6 / 1.1 | 857 / 12, 738 / 10 | 123 / 107 | 12 / 10 | 17.5 / 14.5, 32.5 / 15.5 | both 37 / 53 |
| e accrual x1.5 | 5.7 (1.8 / 3.9) | 1.3 | 1.3 | 850 / 17 | 124 | 17 | 30.5 / 12 | both 60 |

Mid-war: humanKills 0 in every cell; b lifts bases held 11.5 -> 19, e 11.5 -> 33.5* and the swarm's bases destroyed 3.9 ->
8.3* (more to destroy), c sails 32.5* saturations (21.5). Reading: the pools are not short, the allocation is - the
ceiling test (e) buys forward bases, not sieges, and halving guard upkeep (b) does the same; the one lever that moves
sieges is who can be called on (c, 36.5 paid, 1.5 a year) because a siege's supplies are the one faction's callable
stock while the forward-base line spends first. Holding hunts for the siege (a) helps nothing: hunts take 4% of the
supplies. Lowering the orbit margin cuts the FP need but the supplies want scales with the fleet it still needs.

## 10. Round 15: allocation rules for the siege's supplies (2026-10-02)

Five rules the mod could hold, each a switch off by default, no fitted constants. All take `HumanPools.gives(.., what)`: a
non-siege payment of supplies is cut by `HumanPools.reserve(s, depot, what)`, the purpose being the round-14 ledger's name
(`forwardLine`: link, guardVoyage, guardUpkeep, baseUpkeep). `HumanBases.plan`, `cannotGuard` (`spareFor`) and the garrison
upkeep pass their purpose; `drain` (fleet upkeep) and every siege payment (`siege=true`) are untouched.

- (a) `warsim_siegeReserve`: a staging play's siege has first call. `HumanCouncil.reserveSiege` (daily, after `advance`)
  sizes the first live world of every hammer or feint in PREPARE or MUSTER as `strike` will (`HumanPlanner.size` on the
  faction's report), sums the supplies wanted to `Faction.siegeWant`, and spreads it over the faction's depots by their
  callable supplies (`World.siegeReserve`); the forward-base line spends only what is left. Counters `siegeReserveDays`,
  `siegeWant.paidDays` / `.unpaidDays` (faction-days a staging siege was / was not affordable).
- (b) `warsim_siegeSplit` (0.25, 0.5): a standing share of each depot's callable supplies kept from every non-siege purpose.
- (c) `warsim_maxLinks` (2, 4): `HumanBases.plan` founds no link while the faction holds that many forward bases (`linkCapped`).
- (d) `warsim_strategyReserve`: (b)'s share set by the council's strategy (`HumanCouncil.reserveShare`: Hold 0, Starve and
  Rollback 1/3, Decapitate 1/2; 0 under the planner).
- (e) `warsim_linkWaitsForSiege`: no new link while `Faction.siegeUnpaid` (any staging siege of (a)'s sizing unaffordable
  today; `linkHeldForSiege`).

Ranked by the humans' own score (facts.md "The data decides balance"): `humanScore` = bases held + killWeight x hives killed,
killWeight 1, subject to `threatScore` not under the base's p10 and the quiet and stalemate shares not above the base's;
`mutual`, `turnover`, `deadYears` and the classes are diagnostics. The base cell is round 14's exactly.

30 seeds, council on, radar off, planner sizing; new game 104 months (base `threatScore` p10 560) and mid-war 48 months
(p10 263). humanScore / threatScore, bases founded / held, hives killed, hammers paid / unpaid, mutual, classes
(both-sides / one-sided / other). * = clear of the base's seed noise (`Main.compare`).

| cell | new game | mid-war |
|---|---|---|
| base | 21 / 842, 52.5 / 12, 6, 27 / 14.5, 1.0, 53 / 20 / 27 | 11.5 / 307, 38.5 / 11.5, 0, 3.5 / 1.5, 0, 0 / 73 / 27 |
| a first call | 18 / 835, 53 / 12, 4, 18 / 14, 0.6, 43 / 27 / 30 | 12.5 / 313, 41.5 / 12, 0, 3.5 / 2.5, 0, 0 / 77 / 23 |
| b split 25% | 18 / 921, 49 / 12, 5, 16.5 / 9.5, 0.8, 37 / 27 / 37 | 11.5 / 301, 36.5 / 11.5, 0, 3 / 1, 0, 0 / 83 / 17 |
| b split 50% | 15.5 / 794, 43 / 9, 4, 20 / 12.5, 0.6, 37 / 20 / 43 | 10.5 / 296, 31.5 / 10.5, 0, 4 / 1, 0, 0 / 80 / 20 |
| c cap 2 | 10.5 / 807, 28* / 6*, 5.5, 20.5 / 13.5, 0.9, 50 / 17 / 33 | 7 / 300, 18* / 6.5, 0, 4 / 3, 0, 0 / 67 / 33 |
| c cap 4 | 15.5 / 815, 45 / 10.5, 5, 21 / 13.5, 0.8, 47 / 20 / 33 | 11 / 301, 30.5 / 10, 0, 4 / 2, 0, 0 / 70 / 30 |
| d by strategy | 17 / 775, 49.5 / 11, 5, 16 / 12.5, 0.8, 37 / 17 / 47 | 12.5 / 298, 36 / 12.5, 0, 3.5 / 2, 0, 0 / 80 / 20 |
| e link waits | 20.5 / 802, 52 / 11, 6.5, 25.5 / 14, 1.1, 53 / 20 / 27 | 14 / 299, 38 / 14, 0, 5 / 2, 0, 0 / 80 / 20 |

Reading. No cell raises the humans' score from a new game; every reserve (a, b, d) lowers paid hammers (27 -> 16-20) rather
than raising them, and the caps (c) only cost bases. The mechanism: a hammer needs a base of the faction in the cluster's
range (`startHammer`'s `baseOf`), and the forward bases are what put one there - so what starves the forward-base line starves
the hammers with it (`plays.HAMMER` 52 -> 32-42 under a, b, d). First call (a) had a reserve standing 2,749 faction-days but
the staging siege was already affordable on 2,003 of them: the unpaid hammers are unpaid at `strike`, not at staging, and
the stock they lack is not what links spent since. Only e holds the base's score (20.5 / 21 new game; 14 / 11.5 mid-war,
70% of seeds, not clear) because it holds few links back (20.5 a run). At 60 seeds e is noise too: new game 19 against 18
(56% of seeds), mid-war 13 against 12 (63%), paid hammers 21.5 against 25 and 3 against 3. Kill weight: at 0 the score is bases held, where
nothing beats the base from a new game and e leads mid-war; at 2 no cell's kills (4-6.5 against 6) change the order.
Bases held moved up only in e mid-war; down in b50 and both caps. Verdict: no change to the mod. Next hypotheses: a counter-
siege or relief sized to the enemy at the base (round 8's missing answer); the siege's own supplies want (trip upkeep in
`ReachRules.siegeSuppliesPerPoint`) against what the mod actually draws; and why `strike.unpaid` is flat at 14 under every
allocation - what those hammers' bases hold on the day, by `strike.shortBy`.

## 11. Round 16: the shortfall's anatomy, partial sailings, relief to the besieged (2026-10-02)

**Anatomy of an unpaid hammer** (new game, base cell, `strike.shortBy` / `strike.held` / `strike.needFP` / `strike.paysFP`
over 14.5 unpaid a run): the report-sized siege needs 1,790 FP (72 points); the pools provision 256 FP. Short of supplies
3,260 against 2,260 callable; fuel 356k callable against a shortfall only in 2.5 of 14.5; marines 1 of 14.5. The supplies
want per point (`HumanPlanner.size`, `ReachRules.siegeSuppliesPerPoint`) is the 30-a-point hull deposit plus the ships'
upkeep for the whole trip, 25 FP x 0.94 a month x `siegeTripDays` / 30 - about 77 a point at the trips these sieges make
(muster 15 days, passage out at 0.5 ly a day, the bombard plan's stay, passage home): the trip is two thirds of the want.
Only the deposit is drawn at the launch; the trip is billed as it goes (`HumanSide.upkeep`). The shortfall is deep, not
marginal: the pools provision 14% of the siege (256 of 1,790 FP), so a rule that sails what is payable above the 50% muster
floor (trial b below) fires on 0 hammers a run (p90 2) - the unpaid hammers are seven times short, which is why no
allocation of the same pools (round 15) moved `strike.unpaid` and why `strike.paid` tracks `plays.HAMMER` instead.

**The mod's want is the same kind, and harsher.** pd10a's gate lines (`IncursionManager.siegeCanPay`: "Expedition postponed at
Hanuman Forward Base against Enyen: 41159/9455 fuel, 8822/11845 supplies across 16 markets pay for 75 of the 101 points
(needs 101)"; 12,606 such lines, 423 draws, 65 trims) price about 117 supplies a point - `siegeStayDays` takes the slowest
world's bombard plan and any razing - and require every point (`npcSiegeFullStrength` true: `mustPay` = the orbit's points),
fuel four times over. The draw is the deposit alone ("840/840 supplies"). So the simulator's want is of the right make and
if anything kind; no fidelity fix is owed. The coordinator's trial (a), every base in range paying, is already how both work:
`HumanPools.donors(.., siege=true)` is the faction's every depot down to its floor, as the mod's `siegeDonors` ("across 16
markets"); `richestBase` picks only where the siege stages.

Switches, off by default:
- (b) `warsim_hammerSailsPartial` (`HumanCouncil.strike`): a hammer whose provisions pay at least `councilMusterFloor` of
  the report-sized orbit (`Option.paysFP`, the gate's payable) sails with the fleets they pay for (`HumanPlanner.size(..,
  playFP)`, grown to carry the landing) and judges on arrival (`HumanSiege.orbitDay`'s call-off); short of marines it waits.
  Counters `playSiegesPartial`, `.fpShare`.
- (c) `warsim_tripBilledOut` (`HumanPlanner.size`): the gate prices the voyage out and the stay; the way home is billed as
  it goes like every other fleet's upkeep. The draw is unchanged.
- (r) `warsim_reliefToBesiegers` (`HumanBases.besiegers`, `guardAgainst`, `garrison(.., wanted, relief)`): a Threat strike
  bearing on or besieging a forward base that the faction has a report of (arrived, or in flight from a hive system in
  `Faction.reports`) calls at once for a relief sized to its strength x `frontlineGarrisonMargin` over the base's own
  defence, from the pools, past the upkeep budget (`cannotGuard`) but not past the voyage's price. Round 8's missing
  answer. Trialled with bases falling at once (the default) and under `warsim_basesHold` (the 30-day station siege a
  relief can lift in `SwarmOps.stationSiegeDay`).

30 seeds, same settings and objective as round 15. humanScore / threatScore, bases founded / held / destroyed, hives killed,
hammers paid / unpaid, mutual, classes (both / one-sided / other); * clear. The rh pair's base is `warsim_basesHold=true`.

| cell | new game | mid-war |
|---|---|---|
| base | 21 / 842, 52.5 / 12 / 22.5, 6, 27 / 14.5, 1.0, 53 / 20 / 27 | 11.5 / 307, 38.5 / 11.5 / 15.5, 0, 3.5 / 1.5, 0, 0 / 73 / 27 |
| b partial sailing | 21 / 845, 53 / 12 / 23.5, 6.5, 27 / 14.5, 1.0, 53 / 20 / 27 (0 partial) | 12.5 / 307, 38.5 / 12 / 15, 0, 3 / 1, 0, 0 / 70 / 30 |
| c trip billed out | 21 / 873, 55.5 / 13 / 22.5, 6, 24.5 / 14, 1.0, 50 / 20 / 30 | 13.5 / 307, 38.5 / 13 / 15, 0, 3.5 / 1.5, 0, 0 / 73 / 27 |
| r relief | 21 / 831, 44.5 / 13 / 12.5*, 5, 16.5 / 11.5, 0.8, 43 / 23 / 33 | 10.5 / 303, 28 / 10 / 7.5*, 0, 3 / 2, 0, 0 / 77 / 23 |
| basesHold alone | 21 / 842, 55.5 / 14 / 19.5, 4, 15 / 15, 0.7, 47 / 23 / 30 | 14 / 301, 37 / 14 / 13.5, 0, 3 / 2, 0, 0 / 70 / 30 |
| rh relief + basesHold | 21 / 774, 39.5* / 14.5 / 7*, 5.5, 16 / 12.5, 1.0, 50 / 23 / 27 | 14 / 295, 31 / 14 / 4.5*, 0, 5 / 2.5, 0, 0 / 70 / 30 |

Reading. Partial sailing never fires (above) and billing the trip out moves nothing: the trip home is a tenth of the want.
Relief to the besieged does what it says - bases destroyed halve, clear in both starts and both base worlds (22.5 -> 12.5,
19.5 -> 7, 15.5 -> 7.5, 13.5 -> 4.5; station sieges lifted 1.5 -> 5.5) - yet the humans' score does not move (21 / 21, 50%
of seeds; mid-war 10.5 against 11.5): the relief's voyages and the garrisons it leaves behind (sized to the strike x margin,
past the upkeep budget) spend the same supplies the links and the hammers wanted - bases founded 52.5 -> 44.5, paid hammers
27 -> 16.5, garrisons recalled unpaid 25.5 -> 34 - so bases held ends 13 against 12 and the war slows (`deadYears` 0.6 ->
0.8, mid-war 0.7 -> 1.0). Kill weight: at 0 r leads on bases held by one (61% of seeds); at 2 the order is unchanged; the
Threat's score falls under r (its bases destroyed) but stays above the base's p10 at every weight. Verdict: no change. The
supplies are the one budget behind links, guards, relief and sieges, and every rule so far moves spending between them. Next:
a relief that goes home when the strike is gone (so it is not a garrison with upkeep); the siege's trip price against what
the fleets actually burn (`upkeepWanted` / `upkeepOwed` against the gate's `siegeSuppliesPerPoint`); and whether the mod's
sieges, priced at 117 a point, are postponed as often as the simulator's (12,606 postponements against 423 draws in pd10a
says yes - the real council's 0-2 kills a run may be this gate).

## 12. Round 17: the siege's price (2026-10-02)

A knob grid on the price a report-sized siege must find in the pools, since round 16 found the gap 7x (the pools provision
14% of the siege). The knobs are the mod's own where it has them, simulator switches where the mod's is a formula:

- Hull deposit a point: `threatinc_expeditionSuppliesPerPoint` 30 -> 15, 10 (the gate's and the draw's alike).
- Trip horizon the gate bills (the mod's `siegeStayDays` into `siegeTripDays`; here `HumanPlanner.size`'s `trip`):
  `warsim_tripMult` 0.5, `warsim_tripFixedDays` 30. The burn (`HumanSide.upkeep`) is untouched: the fleets still eat as they go.
- Upkeep a fleet point a month (`ReachRules.DEFAULT_SUPPLIES_PER_FP` 0.94, the mod's `ThreatReach.DEFAULT_FACTION_SUPPLIES_PER_FP`):
  `warsim_suppliesPerFPMult` 0.5 through `HumanPlanner.suppliesPerFP`, read by the gate (`size`), the burn (`upkeep`), the
  reach (`HumanPools.rangeLY`) and the stance's force (`HumanStance.forceFP`) alike.
- Orbit margin `threatinc_npcSiegeOrbitMargin` 1.5 -> 1.25, 1.0 (the call-off on arrival stays: `HumanSiege.orbitDay`).
- Full orbit not required: `threatinc_npcSiegeFullStrength=false`, mirrored in `HumanCouncil.strike` only - the hammer sails
  the fleets the pools provision when they reach `threatinc_expeditionMinProvisionsFraction` (0.5) of the points, trimmed
  (`playSiegesTrimmed`; the mod's `siegeCanPay` `mustPay` and "Expedition trimmed"). The planner's own path (`HumanPlanner.plan`)
  still waits for the whole want, so a planner run does not mirror this knob.
- Income ceiling: `warsim_accrualMult` 1.5, 2 (every commodity's accrual, not supplies alone).

**Fidelity on the way.** Postponements: the simulator's planner (council off, new game, 30 seeds x 104 months) refuses
`siegesPostponed` 13,189 [8,605-17,601] options and sails 361 [250-410] sieges; pd10a logged 12,606 "Expedition postponed"
lines and 423 draws (preemptive purges included). The counts match; the make does not - the simulator's refusals are 7,661
fuel against 3,857 supplies (`postponed.<c>`), where pd10a's lines hold fuel four times over and want supplies. The planner's
farther options pay passage fuel the council's near hammers do not; the council's hammers are supplies-short in both
(round 14), so the grid's reading stands.

30 seeds, council on, radar off, planner sizing, killWeight 1; humanScore / threatScore, bases founded / held / destroyed,
hives killed, hammers paid / unpaid, mutual, classes both / one-sided / other; * clear. Base: new game 21 / 842 (p10 560),
52.5 / 12 / 22.5, 6, 27 / 14.5, 1.0, 53 / 20 / 27; mid-war 11.5 / 307 (p10 263), 38.5 / 11.5 / 15.5, 0, 3.5 / 1.5, 0, 0 / 73 / 27.

| cell | new game | mid-war |
|---|---|---|
| deposit 15 | 20.5 / 855, 55.5 / 15 / 24.5, 6, 28.5 / 15.5, 0.9, 47 / 17 / 37 | 12.5 / 299, 43 / 12 / 20.5, 0, 2.5 / 2 |
| deposit 10 | 19 / 1001, 54.5 / 13 / 22.5, 6, 20 / 22, 0.9, 47 / 13 / 40 | 13 / 305, 44.5 / 13 / 19, 0, 2 / 3.5 |
| trip x0.5 | 19 / 871, 55 / 13 / 21.5, 4.5, 20.5 / 11, 0.7, 43 / 27 / 30 | 13.5 / 310, 40 / 13 / 16.5, 0, 4 / 1.5 |
| trip 30 days | 19 / 913, 54 / 12.5 / 23.5, 4.5, 24.5 / 11.5, 0.7, 47 / 20 / 33 | 13 / 307, 39.5 / 12.5 / 17.5, 0, 4 / 1.5 |
| upkeep x0.5 | 25.5 / 753, 56.5 / 14 / 24, 8, 31.5 / 12.5, 1.3, 60 / 17 / 23 | 14 / 322, 45.5 / 13.5 / 20, 0, 3 / 2 |
| orbit margin 1.25 | 16.5 / 891, 54.5 / 12 / 25.5, 3.5, 17.5 / 14.5, 0.6, 37 / 30 / 33 | 13 / 319, 41 / 12.5 / 17, 0, 3 / 3 |
| orbit margin 1.0 | 18.5 / 772, 48 / 10 / 18.5, 6.5, 32.5 / 15.5, 1.1, 53 / 27 / 20 | 12 / 308, 40 / 12 / 19, 0, 3 / 1.5 |
| full orbit off | 21 / 845, 53 / 12 / 23.5, 6.5, 27 / 14.5, 1.0, 53 / 20 / 27 (0 trimmed) | 12.5 / 307, 38.5 / 12 / 15, 0, 3 / 1 |
| income x1.5 (ceiling) | 28.5 / 886, 71.5 / 17 / 23.5, 8, 30.5 / 12, 1.3, 60 / 20 / 20 | 33.5* / 338, 76* / 33.5* / 33*, 0, 5 / 1.5 |
| income x2 (ceiling) | 27.5 / 680, 68 / 15 / 27.5, 14, 51 / 15.5, 2.4, 83 / 7 / 10 | 42.5* / 349, 91* / 42.5* / 36.5*, 1, 6.5 / 3 |

Reading the singles. Cutting what the gate asks without cutting what the fleets burn buys nothing: the deposit at 15 or 10
and the trip at half or 30 days leave the score at 19-20.5 against 21 (more hammers reach the strike, paid ones do not rise -
28.5, 20, 20.5, 24.5 against 27 - and at deposit 10 the unpaid rise to 22: cheaper sieges sail, starve on the way
(`HumanSide.upkeep` bills the trip as it goes) and the Threat's score climbs to 1,001). The orbit margin is worse at either
cut (16.5, 18.5). Full orbit off trims nothing (the pools provision 14%, the floor is 50%). The one price knob that moves the
score is the burn itself: upkeep x0.5, gate and burn alike, 25.5 against 21 from a new game (kills 8, paid hammers 31.5,
both-sides 60%) and 14 against 11.5 mid-war, the Threat's score 753 and 322 above p10 - not clear at 30 seeds. The income
ceiling says where the bill becomes payable: x1.5 gives 28.5 and 33.5* (mid-war bases held 11.5 -> 33.5*), x2 27.5 and 42.5*
with 14 kills and both-sides 83% from a new game - the humans' war is income-bound end to end, and the siege's supplies
bill (the burn) is what the income cannot meet.

Combinations on the one knob that moved, 30 seeds (same columns):

| cell | new game | mid-war |
|---|---|---|
| upkeep x0.5 + deposit 15 | 26.5 / 847, 59 / 16 / 24.5, 8, 32.5 / 18.5, 1.3, 67 / 10 / 23 | 17 / 318, 51.5 / 16.5 / 22.5, 0, 5.5 / 2 |
| upkeep x0.5 + full orbit off | 20 / 764, 55 / 12.5 / 24.5, 7.5, 29.5 / 12, 1.3, 63 / 13 / 23 (1 trimmed) | 14 / 326, 46.5 / 13.5 / 22, 0, 3.5 / 2 |
| upkeep x0.5 + deposit 15 + full orbit off | 24.5 / 796, 59.5 / 16 / 25, 7.5, 31.5 / 18, 1.2, 60 / 7 / 33 | 17 / 318, 51.5 / 16 / 22, 0, 5 / 2 |

Upkeep x0.5 at 60 seeds: new game 23 against 18 (bases held 13 against 10.5, kills 8 against 5), mid-war 16 against 12 (bases
held 15.5 against 11.5); the Threat's score 797 and 321, both above p10 (560, 272).

**What the mod can and cannot hold.** The upkeep rate is not a knob: `ThreatReach.suppliesPerFP(factionId)` measures the
faction's fleets out - vanilla maintenance a month over fleet points (`ThreatFrontlines.maintenancePerMonth`), once a day -
and 0.94 (`DEFAULT_FACTION_SUPPLIES_PER_FP`) is only the default before one sails. Upkeep x0.5 is therefore a 2x discount on
the factions' real maintenance, which "no cheating either side" rules out as a mod rule; the cell measures the gap, it is
not a candidate. The pools' accrual is a knob: `threatinc_reserveSurplusMult` (1.0; troops `reserveTroopSurplusMult` 0.5)
scales the surplus share each colony banks a month (`ThreatReserves.accrualPer30`), which `warsim_accrualMult` stands for
(it scales every commodity, troops included, so it overstates marines a little). So the income cells are buildable settings
changes, not only a ceiling.

Kill weight. At 0 (bases held alone) the order is the same: upkeep x0.5 13-15.5 against 10.5-11.5, income x1.5 17 and 33.5*,
the price cuts 12.5-15 against 12. At 2 the kills term widens upkeep x0.5's lead from a new game (kills 8 against 5) and
income x2's (14 against 6) and changes no order; mid-war no cell kills, so the weight is moot there. The Threat's score stays
above the base's p10 in every cell at every weight; income x1.5 raises it (886 / 338 against 842 / 307: more worlds and bases
to destroy), income x2 lowers it from a new game (680) through the 14 hives killed.

**At 60 seeds.** Income x1.5: mid-war 32.5 against 12 (97% of seeds, clear; bases held 32 against 11.5*, founded 81.5
against 39.5*), new game 27.5 against 18 (80% of seeds; the median clears the base's p90 27.1, bases held 17 against 10.5,
kills 8 against 5, paid hammers 29 against 25); the Threat's score 849 and 336 against 829 and 308, above p10 (560, 272);
classes new game both-sides 60% against 45%, mid-war one-sided 62% against 80% with no quiet seed, `deadYears` 0.6 / 0.5
against 0.6 / 0.7. Upkeep x0.5 + deposit 15: 23 against 18 (71%) and 17 against 12 (72%), consistent, not clear, and not a
rule the mod may hold. Upkeep x0.5 at 120 seeds from a new game: 22 against 18 (63%).

**Verdict: `threatinc_reserveSurplusMult` 1.0 -> 1.5** (the pools bank half again the surplus share a month; the gate and
the prices unchanged - the bill is real, the income was short of it). Clear mid-war, 80% of seeds from a new game, the
Threat's score up not down, the war livelier on both measures. Caveats: the simulator scales every commodity's accrual
including the militia's marines (`reserveTroopSurplusMult` is separate in the mod and was not raised); the colonies' base
accrual is `HumanFit.ACCRUAL_BY_SIZE` fitted from the dumps, so x1.5 maps to the knob only as far as that fit holds; and
facts.md "What is still free in the human war economy" lists the reserves' accrual as unpaid banking, so this widens a
free flow - still production-bound (`reserveBankFromProduction`), not conjured. Confirm with the 5-minute mid-war game check
(`strike.paid` should rise and bases held double) before the setting is kept.

