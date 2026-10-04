# Game test runs 1-3: the humans' side (hw5e-hw6c, 2026-10-04)

Split from `game-runs.md` on 2026-10-05, the sections keeping their numbers: the stance floor and the marine
headroom, what stopped the humans attacking, and the council's restraints removed. How a run is made and the
digest scripts are in `game-runs.md`, with sections 4 on (the swarm's side).

## 1. hw5e-hw5g (2026-10-04): the stance floor and the marine headroom

The test of `243b4a0` (`stanceConsolidateMinPressed` 2) and `dc7274a` (`siegeMarineHeadroom` 2,
`siegeNoDoomedLanding`). Shipped defaults throughout: wartime supplies share 1, `stanceConsolidateShare` 0.5,
5 troops a broken-up fleet point. Clones `...ng33` to `...ng35`. No exceptions in any run. Counts to month 120
unless named. Before: hw4s, hw4t, hw4y, the same settings without the two fixes.

| run | months CONSOLIDATE | hives | worlds lost m108 / m120 / m126 | Threat landings | of those overrun | human landings on hives | of those overrun | median marines landed | doomed landings refused | hives eradicated | human marines at the end |
|---|---|---|---|---|---|---|---|---|---|---|---|
| hw4s (before) | 73 | 14 | 1 / 1 / 1 | 12 | 14 | 11 | 6 | 574 | - | 5 | - |
| hw4t (before) | 57 | 65 | 1 / 1 / 1 | 29 | 22 | 15 | 7 | 325 | - | 7 | - |
| hw4y (before) | 40 | 101 | 2 / 2 / 2 | 20 | 23 | 15 | 6 | 486 | - | 8 | 226k |
| hw5e | 11 | 140 | 2 / 8 / 8 | 99 | 49 | 13 | 2 | 1,189 | 16 | 11 | 66k |
| hw5f | 0 | 154 | 2 / 4 / 6 | 75 | 45 | 8 | 2 | 1,056 | 3 | 6 | 107k |
| hw5g | 8 | 96 | 3 / 3 / 3 | 60 | 36 | 14 | 0 | 890 | 8 | 14 | 123k |

**The stance floor holds.** 0-11 months in CONSOLIDATE against 40-73. The swarm entered it eight times over the
three runs and was out within 1-6 months each time; no run held it on one pressed system. Entries below half
the systems pressed (hw5g: 2 of 11, 3 of 13, 7 of 25) are the other path, hives lost while attacked.

**The marine headroom and the refusal hold.** Human landings arrive at about twice the size and 4 of 35 were
overrun, against 19 of 41. 27 landings were refused as doomed. Hives eradicated 6-14 against 5-8: the fix stops
the waste; the humans land no more often than before (8-14 landings in ten years).

**The war is hotter, in all three runs.** 60-99 Threat landings against 12-29, and 3-8 worlds lost by month 120
against 1-2. The swarm is at 127-182 hives by month 126. This is the swarm no longer stalled, not a new fault:
the unstalled runs at `stanceConsolidateShare` 0.75 (hw5a, hw5b, hw5d) had 26-78 landings and 76-174 hives by
month 126, and lost 1-3 worlds. Under the old surplus rule (share 0) 15-37 worlds fell.

**The human marine reserve is lower, and the headroom is not why.** 66-123k at the end against 150-280k in
hw5a, hw5b, hw5d and hw4y. Sieges drew 16-25k marines over the run against 12-22k. What changed is the war
around them: counter-attacks cost 8-17k against 2-7k, and in hw5e and late hw5g fewer worlds and forward bases
were banking (+3.8k a month against +4.1-7.2k).

**Open, the user's call:** whether this level of Threat pressure is the war wanted. The humans' offence is the
short side: 8-14 landings on hives in ten years against 60-99 Threat landings.

## 2. What stops the humans attacking (hw5e-hw5g, read 2026-10-04)

The user's question: "what is stopping the humans from attacking more? The humans should play to win just like
the threat should. There should be no artificial constraints. If there is a shortage that should be identified
and questioned." Read from the three logs of section 1 and the code, to month 120. Nothing was changed.

**It is not a shortage.** Over the war the council factions earned 6.4-7.5M supplies and 11.9-12.8M fuel and
held a mean 12-17k marines each. The sieges that land troops, the only thing that kills a hive, drew 17-29k
supplies (0.2-0.4% of the income) and 61-119k fuel (under 1%), and 12-22 of them sailed in ten years.

| where the means went | hw5e | hw5f | hw5g |
|---|---|---|---|
| Supplies income / fuel income | 6.4M / 11.9M | 7.4M / 12.8M | 7.5M / 12.6M |
| Upkeep of fleets out (hunts, mostly) | 3.4M supplies | 2.7M | 3.1M |
| Forward base structures | 649k supplies | 814k | 737k |
| Shortage cover | 512k supplies, 1.6M fuel | 552k, 2.2M | 433k, 1.5M |
| Guard, support and raid orders | 508k supplies, 3.6M fuel | 442k, 2.7M | 504k, 3.1M |
| Bombing-only (saturation) expeditions | 67k supplies, 5.1M fuel (64 sailed) | 56k, 3.5M (44) | 62k, 3.7M (59) |
| Landing sieges | 22k supplies, 100k fuel (18 sailed) | 17k, 61k (12) | 29k, 119k (22) |
| Hunt orders stood down out of supplies | 416 | 301 | 413 |

(`supplies.pl`. Fuel drawn by an expedition called off comes home; 2.4-4.0M fuel was returned. The tally
accounts for about 70% of the supplies income; forward-base garrison upkeep is not in it.)

**The chain, each link with its count.** Rules first, in the order a faction meets them:

1. **The band.** `ThreatWarCouncil.assess`: a faction's weight is the sum of its colony sizes (allies at half),
   the swarm's is the size plus twice the military tier of every hive it knows of. Fleets, marines and stock are
   not in it. One faction against the whole known swarm reads outmatched in 57-84% of faction-months (median
   ratio 0.35-0.47). `CouncilRules.scores` then gives an outmatched faction Hold 2.5, Starve 2.5-3.25, Roll back
   0.3 (0.15 pressed), Decapitate 0: it draws an invasion strategy about 1 time in 20. Shares of faction-months:
   Hold 40-45%, Starve 42-56%, Roll back plus Decapitate 2-13%. hw5g, the least outmatched run, killed the most.
2. **Hold attacks nothing.** `ThreatPlays.plan` returns after recon. No play at all ran in 38-58% of
   faction-months with a hive known; an invasion play ran in 14-33%.
3. **Starve must bomb before it may invade.** Under Starve the invasion's weight is 0 until every Nexus of the
   system is down (`plan`: `nexusesDown(focus) ? 2f : 0f`). Bombing cannot kill a hive.
4. **The bombing expedition is all or nothing.** `ThreatPlays.saturate` sets aside the fuel to bomb every hive
   in the system to the commander's stop before it sails (`expeditionFuel` sums `razingFuel`): a median
   312-416k against a 24-29k passage and a faction stock of 215-269k. Postponed 422-888 times a run, "no
   saturation expedition the pools pay" 158-170 times. The 44-64 that sailed went alone, with no hunts beside
   them, and 29-37 were called off on arrival (`ThreatPurgeFGI.breaksOffAbstract`: the system's swarms at a
   median 1.5-1.9x its strength). Landing sieges, which sail with hunts, were called off 0-1 times a run.
5. **Squadrons need an unguarded Nexus.** `ThreatPlays.bombable`: "every Nexus is reported guarded and no
   orbit is ours" 50-79 times against 14-26 squadrons sent.
6. **The invasion pays its escort first and its siege last.** `ThreatPlays.toMuster` sends a hunting force of
   `councilHammerShare` 0.6 of what the pools pay: a median 5.3-7.5k FP at the muster against a median 344-444 FP
   reported over the target, 17-30x, and 5-7x the siege (775-950 FP). It is held at the muster for weeks on
   vanilla maintenance. The siege is launched after, from one base (`baseOf`), at full strength or not at all
   (`npcSiegeFullStrength`): 3-10 hammers a run struck with "no siege paid", every one short of supplies
   (6,098 of 7,189; 6,602 of 6,781) while the pool held 150-375k fuel.
7. **Other sieges lock the system.** 7-12 hammers a run ended "another siege has its worlds"
   (`ThreatPlays.liveTargets`, `bookedWorlds`): a bombing expedition books every hive of the system.
8. **One siege, one landing.** The first landing takes every marine aboard (`ThreatPurgeFGI.unloadForLanding`):
   12-22 landing sieges made 8-14 landings of about 1,000 marines; 73% of hammers landed nothing. 3-16
   landings were refused as doomed (`siegeNoDoomedLanding`), where the answer to hand was more marines.
9. **The ceiling.** One major play per `councilMajorPlayFP` 3,000 FP of siege capacity (`majorLimit`), a
   median 90-100 days each; a strategy is held `councilReviewDays` 90.

Of 29-54 hammers started a run, 12-22 struck with a siege and 8-14 landed.

**The one real shortage is supplies, and it is self-inflicted.** Stock is about a month of income (74-108k a
faction against 215-269k fuel) because fleet upkeep takes 37-54% of it, and that upkeep is the hunting forces:
301-416 hunt orders a run stood down unpaid. The hunts are sized to the purse, not the target (link 6). Fuel is
not short: the largest draw is the bombing expeditions that cannot kill. Marines are not short: 67-125k in
reserve at the end, 16-25k drawn by sieges in ten years.

**The swarm has none of these links.** A strike lands where the stance and the hive stock allow: no band, no
bombing first, no escort paid ahead of the landing, no break-off on arrival (`facts.md`, "Does the swarm raze
human colonies").

**Why each link is there** (the user's question, 2026-10-04: "why the strategy layer is preventing? Is there
some assumed tactical benefit?"). The council was built on 2026-10-01 to replace the attack planner's sieges
sized to reported FP and re-planned on news with committed, coordinated plays (`war-council.md`, "Why"). None
of that asks for restraint. The restraint is in the tables written to fill it in:

| link | the benefit assumed | what the runs say |
|---|---|---|
| 1 band | none: fleet FP was ruled out as a measure, so strength became colony sizes against hive sizes | one faction against the whole known swarm reads outmatched from about 16 hives on, whatever its fleets, marines and stock |
| 2 Hold, "no invasions" | an outmatched or pressed side that attacks loses its force and its worlds | never tested. Landing sieges, when they sail, are called off 0-1 times a run and 4 of 35 landings were overrun. Relief is separate and already goes first |
| 3 bomb before invading | a hive with its Nexuses down is cheaper to land on | never measured. Most bombing expeditions do not get to bomb (link 4), and the invasion waits on them |
| 4 bombing paid whole, sails alone | a campaign that cannot run dry half-way | 29-37 called off on arrival a run; fuel for 44-64 sailings a run, no hive killed by them |
| 5 squadrons need an unguarded Nexus | h53c: squadrons sent alone into 600-3,500 FP were all driven off | real, but the fix was a gate, not an escort |
| 6 escort of 60% of the means | the swarm reinforces 2-5x by arrival (h50a-h52a, a quarter of sieges landed), so mass wins the orbit | real: the orbit is won. But it is sized to the purse (17-30x the report), not the target, and paid before the siege |
| 7 system lock, 8 one landing | bookkeeping: one siege's bill and marines per world (2026-09-29) | no tactical claim |
| 9 one play per 3,000 FP | concentration: one committed play at a time ("commit, then check"), loosened to one per 3,000 FP on 2026-10-02 | the loosening was sized by the simulator's round 13, not a game run |

Against it: pd7a (2026-10-02, one run), the council off and the old planner sailing about 2 sieges a month,
landed 43 sieges and killed 27 hives by month 97 and held the swarm at 30 hives, against 1-2 hives killed and
155-201 hives in the council runs of that week. Its write-up said the planner's strength was tempo, many
systems pressed at once, and proposed "every strategy sieges: strategy picks where, not whether"
(`war-council-runs.md` 4, option A). That was dropped because it "burned the paying bases" in the simulator's
round 13. No game run has tried it.

**Decided 2026-10-04** ("Ok proceed with recommended"): links 1-3 and 5-9 are replaced by the four changes in
`war-council-invasions.md`; link 4 (relief first) stays, the user's rule. Runs: section 3.

## 3. hw6a-hw6c (2026-10-04 evening): the council's restraints removed

Build c3bc019 (`war-council-invasions.md`): the invasion line, sieges before a report-sized escort, saturation
of the worlds the pools pay, one siege a world, no play cap. Three new games side by side on shipped defaults
(`sbs.ps1 -Tags hw6a,hw6b,hw6c`), 123-142 days a minute each. Stopped at months 78-89, once every swarm was
extinct. No exception and no play or council error in any run.

**Result: the humans exterminate the swarm in 3 of 3 runs, 24-34 months after they find it.**

| | hw6a | hw6b | hw6c | hw5e-hw5g (to m120) |
| --- | --- | --- | --- | --- |
| First hive found / first siege sails | m42 / m42 | m45 / m45 | m42 / m42 | - |
| Swarm at its peak | 18 hives m46, 11.3k FP, 2.7k FP/mo | 17 hives m51, 16.3k FP, 3.5k FP/mo | 17 hives m51, 9.4k FP, 2.5k FP/mo | 96-154 hives at m120 |
| Swarm extinct | m76 | m70 | m66 | never |
| Landing sieges sailed | 50 (79k FP, 86k marines) | 49 (70k FP, 91k marines) | 49 (65k FP, 81k marines) | 12-22 |
| Escorts | 28 (59k FP) | 23 (92k FP) | 22 (97k FP) | 29-54 musters, 5.3-7.5k FP each |
| Escort against the report (median) | 1.6k FP against 235 | 2.1k against 518 | 1.1k against 258 | 17-30x |
| Human landings / overrun by the hive | 19 / 1 | 20 / 2 | 19 / 1 | 8-14 / 0-2 |
| Sieges called off | 6 | 0 | 0 | 0-1 (bombing 29-37) |
| Hives eradicated | 18 | 18 | 18 | 6-14 |
| Threat landings / overrun by the garrison | 13 / 12 | 17 / 11 | 10 / 7 | 60-99 |
| Human worlds lost | 0 | 2 | 2 | 3-8 |
| Fleet upkeep | 733k supplies (17% of income) | 761k (18%) | 657k (15%) | 2.7-3.4M (37-54%) |

**The four changes do what they were built to do.** The invasion line started 15-19 of the 23-27 hammers; 21-22
of them struck, 1-4 waited for stock; no hammer sailed hunts without a siege; a siege is a median 1.2k FP with
1.2-1.5k marines; the escort is 4.0-4.1x the report; 16-17 sieges a run were added to a strike already out. No
expedition was postponed for fuel or supplies (422-888 a run before). The factions held a mean 152-231k supplies
and 317-429k fuel each: nothing was short.

**What it exposes: the swarm at first contact is no match for humans that attack.** The restraints were what
kept it alive. From the log (hw6b):

1. **It starts the war itself, and badly.** The swarm's first landings come the month it is found (m45): 1,200
   troops each on Chicomoztoc (8 strata) and Coatl, then 58-660 troops on a dozen garrisoned worlds. 7-12 of its
   10-17 landings are overrun by the garrison.
2. **Its hives stand almost unguarded.** The swarm holds 9-16k FP, most of it in the home system (13k FP reported
   in Alpha Laphirial); an outlying hive has 25-121 FP over it (off-screen fights: 889 FP against 25, 435 against
   72, 1,147 against 121). Its posture wanted 2,157 FP at Wotan and held 86.
3. **A hive falls to a few hundred marines.** Hlidskjalf: 448 marines sail, 300 land in m49, the Fabrication
   Core is destroyed in m50. Landings of 300-2,300 troops took 18 hives a run; the hive overran 1-2.
4. **Being attacked stops it growing.** Its stance goes CONSOLIDATE at m48-49 (2-4 systems pressed), which stops
   founding; income falls from 2.5-3.5k FP a month to 200-400 by m55.

The humans sent 65-79k FP of sieges and 59-97k FP of escorts in two years against a swarm of 9-16k FP earning
2.5-3.5k FP a month.

**Open, the user's call:** what the swarm is at first contact. Options as put to the user 2026-10-04:
(a) leave it: an annihilation is a good run by the 2026-10-02 scoring, but it is the same run every time and over
before a player matters; (b) ask of the swarm what was asked of the humans - what stops it defending and
attacking well (points 1, 2 and 4 are its own rules) - and fix those; (c) make a hive cost more on the ground
(point 3); (d) put the human restraints back - ruled out by the user's rule. **Decided the same evening: (b)**,
the humans left alone for now (section 4).

**Harness.** Three games at once ran at 123-142 days a minute each (two: 210), so about the same days a minute in
total; the wrap-up (dumps copied, settings restored) works, and closing the games by hand ends a batch cleanly
("GAME GONE").

