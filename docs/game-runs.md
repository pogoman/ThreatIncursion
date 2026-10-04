# Game test runs

The record of game runs from 2026-10-04 afternoon on, when the user froze the simulator and made the game the
test (`facts.md` Decisions). Earlier runs are in `war-sim-real-runs.md` (hw4d-hw5d) and
`war-sim-real-runs-oct2.md`.

How a run is made: `%TEMP%\threatinc-tests\run-go.ps1 -Tag <tag> -Clone <ngN> -Days 3750` - a new game on the hw4
sector, fast-forwarded to war day 3750, about 40 minutes, the user's settings restored after
(`testing-harness.md`). Several at once: `tools/test-harness/fastforward/sbs.ps1 -Tags a,b,c -Days 3750`, the
batch in one run's time (`testing-harness.md`, "Side-by-side runs"). The log is `%TEMP%\threatinc-tests\ti-<tag>.txt`, the monthly dumps
`tools/warsim/validation/<tag>`. Digests, in `tools/test-harness/digest/`:

- `uat.pl <tag> [month]`: worlds lost, hives, months in each stance, where CONSOLIDATE was entered, Threat
  landings, human landings on hives and how many were overrun, doomed landings refused, hives eradicated,
  exceptions.
- `marines.pl <tags>`: the human marine reserve, Threat landings and overruns by two years.
- `mflow.pl <tags>`: marines drawn by sieges, landed, lost in counter-attacks, issued as shortage cover.
- `mdump.pl <tags>` (from `tools/warsim/validation`): marine income a month from the dumps.
- `prof2.pl <jstack dumps>`: where the game's main thread is (`facts.md`, "How fast does a long run go").
- `council.pl <tag>`: what each human council read (band, ratio) and chose, by faction-month.
- `funnel.pl <tags>`: the human offence from strategy to dead hive: plays started, postponements and their
  bound, sailings, call-offs, landings.
- `hammer.pl <tags>`: each invasion play (HAMMER) from start to end: muster, siege paid or not, landings.
- `supplies.pl <tags>`: where the human factions' supplies and fuel go, against their income.
- `swarm.pl <tags>`: the swarm's side: when it is found and extinct, write-offs, transfers, what met each siege
  on its first day, FP each side lost over hive worlds, its means against the sieges and escorts sent, seedings,
  strikes and their guards, sieges first seen by eyes or picket, strikes recalled and how long after launch,
  landings both ways.
- `hives.pl <tag> <day> [system]`: one monthly dump's hives: held, fleets, want, need, bank, organs.

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

## 4. What stops the swarm defending and attacking (hw6a-hw6c, read 2026-10-04 evening)

The user's call after section 3: "we need to remove artificial constraints on the swarm like we just did for
humans". Read from the hw6 logs (figures hw6b) and the code. Each link is a rule (removed, with a knob that puts
it back) or a shortage (left, named).

**Defence: 12.9k FP in the home system, and each world fought alone.**

1. **A depot's capacity counted as an attack.** `ThreatPosture.read` took what a base staging for a hive system
   could pay (`Place.stagedFP`) as pressure: Yma read 37,550 "staged", Wotan 15,040. Since section 3 a siege is
   sized to the swarm reported at its world (1-3k FP), so nobody sends that force. Rule. Removed:
   `postureStagedShare` 0, pressure is the forces seen (attacks, hostiles, losses, forward guards).
2. **Systems were written off.** Triage zeroed the need of a system the hive "could not hold": 13-15 write-offs
   a run, every attacked system, the home system included (m45: "Wotan written off - pressure 2303 needs 1535,
   the hive could gather 916" with 12.9k FP held). What it could gather left out every colony's launch stock.
   A written-off system is not reinforced. Rule. Removed: `postureTriage` off.
3. **The launch stock was never the defence's.** A colony's base want is its reserve plus two swarms kept for
   the next wave or strike (the home system's base alone: 12,074 FP). A donor gave only above its want and only
   from a quiet system, so a colony short of its stock gave nothing. Rule. Removed: a colony the war asks
   nothing of gives down to its reserve for one under attack (`redistributeByPressure`, `postureNeedAtAttack`).
4. **The need was split by the size tables, the orbit is fought at the world.** Alpha Laphirial II met a siege
   of 1,925 FP with the 1,432 FP over it (1,432 -> 449 -> 121 in three days), Alpha Laphirial IV one of 1,968
   with 956, in a system holding 12.9k. Rule. Removed: the need stands at the worlds a force is over or an army
   is on (`ThreatPosture.overWorlds`, `siegesOver`), their siblings give down to their reserve, and a siege
   arriving makes the next pass due at once (`ThreatPosture.alarm`; it was up to 5 days).

**Growth: being attacked stopped it.**

5. **CONSOLIDATE stopped every new claim and every strike but a spoiling blow.** Entered m48-50 with 2-4 systems
   pressed; no founding after m46. Rule. Removed: `stanceConsolidateSpreadShare` 1, `stanceConsolidateStrikes`
   (any world by its weakness, a base staging against a hive weighing x10). Consolidating still feeds pressed
   systems.
6. **A pressed system's forges stayed home whatever they held.** `trySpread` and `pickForgeSource` skipped them.
   Rule, and a second gate on top of `launchSpareFP`, which already lets a colony launch only what it holds
   above the need. Removed: `posturePressedForgesHome` off.

**Offence: the strike went home and left its landing.**

7. **An unspawned strike left one fleet a landing.** The m42 strike on Aztlan, 4,219 FP in 13 fleets, landed
   1,200 troops each on Chicomoztoc and Coatl, left 291 and 325 FP over them and took the rest home (2,306 of
   3,573 FP re-banked). A relief of 714 FP took each orbit. A spawned strike ends with every fleet over a front
   (`joinDefend`); 7-12 of 10-17 landings a run were overrun. Rule (the off-screen path's). Removed: its share of
   the fleets stays over each landing, all it has left over the last (`ThreatStrikeFGI.guardShare`,
   `strikeGuardWhole`).

**Shortages and what is left alone.**

- Fleets away: 8-10k of hw6b's 20-26k FP from m41 to m53 were two strikes from the home system (5,017 FP at
  Arcadia from m38, 4,219 at Aztlan from m42), 130-150 days out. Not a rule: the swarm chose them before it was
  found. Nothing recalls a strike when home is attacked (new behaviour, not built).
- Income fell from 3,500 FP a month (m50) to 200 (m53) and 0 (m61) with 5.4-5.9k FP banked: income is 0 at a
  colony whose Core or Nexus is down and its bank cannot build (`fabricationRatePerDay`, `canRebuildGarrison`).
  A consequence of losing the orbit, not a rule.
- An off-screen fight costs each side half the other's points a day, at most three quarters of its own
  (`BattleRules`): a garrison outnumbered 1.5:1 or worse trades at two thirds. Feeding a fight is not a
  grinder, which is why the triage could go.
- Left alone: the strike gate (13-25 "passed over" a month: no strike on odds it loses), one strike a hive
  system a month, strike weight size squared over days (Chicomoztoc, 8 strata, as an opening target), half the
  growth share of supplies while consolidating (`feedShareConsolidate`, the user's round 20), phase gates.
- The user's call, not touched: what a hive costs on the ground (300-2,300 marines took one in a month).

**Built 2026-10-04, commit 68d019a. Game-tested the same evening in hw7a-hw7c (section 5)**: all seven work as
built, and the swarm is still exterminated. A knob per rule allows a run with one put back
(`-Knobs "hw7c:threatinc_strikeGuardWhole=false"`).

## 5. hw7a-hw7c (2026-10-04 evening): the swarm's restraints removed

Build 68d019a on shipped defaults, three new games side by side (`sbs.ps1 -Tags hw7a,hw7b,hw7c -Days 3750`), run
to day 3750 in 29 minutes of fast-forward (131 days a minute each). No exception and no play or council error.
Read with `swarm.pl` on both batches (its months run one lower than `uat.pl`'s in section 3).

**Result: the removals do what they were built to do, and the humans still exterminate the swarm in 3 of 3
runs.** hw7a lasted 15 months longer than any hw6 run; hw7b and hw7c went as hw6 did.

| | hw7a | hw7b | hw7c | hw6a-hw6c |
| --- | --- | --- | --- | --- |
| First hive found / swarm extinct | m41 / m90 | m38 / m67 | m39 / m65 | m41-44 / m65-75 |
| Swarm at first contact | 17.0k FP, 2.3k FP/mo | 10.2k, 1.5k | 14.5k, 1.5k | 15.0-23.9k, 2.4-3.3k |
| Systems written off | 0 | 0 | 0 | 13-15 |
| Transfers after contact | 228 (35.3k FP) | 125 (16.9k) | 167 (27.4k) | 76-129 (10.4-21.0k) |
| A siege's first day, siege against garrison (median) | 1.4k FP against 356 | 1.2k against 163 | 930 against 169 | 1.3-1.7k against 168-206 |
| Sieges met at parity or better | 1 of 54 | 0 of 36 | 1 of 29 | 0-1 of 32-36 |
| Lost over hive worlds, Threat / human | 56.3k / 39.4k FP | 27.7k / 19.7k | 18.5k / 13.5k | 18.7-27.0k / 12.9-19.0k |
| Swarms built after contact | 332 (68.0k FP) | 164 (27.1k) | 192 (31.9k) | 103-190 (21.5-37.8k) |
| Seedings after contact | 5 | 4 | 0 | 2 |
| Strike guard over a landing (median) | 2 fleets, 0.9k FP | 7 fleets, 2.0k | 3 fleets, 1.0k | 1 fleet, 0.3-0.9k |
| Threat landings / overrun by the garrison | 9 / 8 | 5 / 3 | 6 / 4 | 10-17 / 7-12 |
| Human worlds lost | 0 | 2 | 2 | 0-2 |
| Human sieges arrived | 74 (111k FP) | 55 (68k) | 40 (48k) | 44-48 (63-75k) |
| Escorts | 38 (122k FP) | 21 (101k) | 17 (72k) | 22-28 (60-97k) |
| Human landings / overrun by the hive / hives eradicated | 20 / 1 / 19 | 17 / 0 / 17 | 15 / 0 / 15 | 19-20 / 1-2 / 18 |

**The rules, one by one.** Nothing is written off (1, 2). An attacked world draws from its siblings in the pass
its siege arrives on: Beta Laphirial I (hw7a) took eight transfers from six colonies (3, 4). The swarm seeds
a little more after contact (5, 4, 0 against 2); it launched 5, 3 and 0 strikes while consolidating (hw6: 9, 2,
3), no visible change (5). A strike leaves 2-7 fleets a landing (7); 6 was not read separately. In hw7a the
swarm kept producing under attack: 74k FP of income after contact against hw6a's 36k, 332 swarms built against
190, 39k FP of besiegers killed against 19k.

**Why it still loses: what is left is its means and where it puts them** (hw7a unless said).

1. **It is outbuilt, 2.6-4.5 to 1.** After contact the humans brought 48-111k FP of sieges and 72-122k FP of
   escorts; the swarm had 10-17k FP and built 27-68k more. A shortage, not a rule.
2. **It trades at 0.7, every run.** Over hive worlds the swarm loses 1.4 FP for each human FP (1.37-1.43; hw6
   1.42-1.50). A side outnumbered at the world loses three quarters of its own a day and kills half its own
   (`BattleRules`), and every garrison is outnumbered at its own world. The home system held 6,920 FP on eight
   worlds the day the first of five sieges arrived (10,375 FP in six days): II met 2,238 FP with 961, V 725 with
   429, IV 725 with 424, III 3,425 with 550, I-A 4,635 with 1,784. No siege outweighed the system; each
   outweighed its world. A week in, five worlds held 0-35 FP and Alpha Laphirial VI, with nothing over it, held
   1,936 FP in 9 fleets (`hives.pl hw7a -642841`): a sibling gives down to its reserve (`minimumFP`: half its
   size table, doubled or tripled under a Bastion or Command), and it had sent five fleets that week, one to
   each of five worlds. Transfers arrive a fleet at a time (150-350 FP) and die at the same rate.
3. **The home system sent half its fleet away a week before the blow.** It held 12.9k FP at m43. The five sieges
   sailed from Cordiance Forward Base on Oct 7; on Dec 3 the system, QUIET with a need of 0, launched 6,938 FP
   in 20 fleets at Eochu Bres, 134 days away; the sieges arrived Dec 10 and were first seen Dec 9. The swarm
   sees a siege only where it has eyes that day and nothing in hyperspace: 55-64% of sieges a run were first
   seen the day they entered the hive system, the rest at dispatch from a base it had eyes on, and such a
   contact fades after 10 days of a median 30 in flight (`facts.md`, "Does either side see an attack coming").
   The humans detected all 26 strikes from a world or base, weeks out. Nothing recalls a strike. That strike landed 3,980 troops and held the orbit with all 20
   fleets; Tri-Tachyon's relief was 10,503 FP and the beachhead was overrun in m53.
4. **Once a landing is down the hive is lost.** Landings are a median 851-1,208 marines; the Fabrication Core
   falls a median 77-91 days later (quickest 30-40; hw6: 86-103, so section 3's "in a month" was its quickest
   case). 15-19 hives eradicated a run; the hive overran 0-1 landings and 0-6 sieges were called off in orbit.

**Open, the user's call** (nothing built but the picket and the recall under b):

- (a) Mass the defence, the one option that only uses what the swarm has: a system's swarms answer a siege as
  one force. Siblings give everything, reserve included; the force goes over one world at a time, the siege it
  outweighs first; a garrison that cannot hold falls back on it instead of dying where it stands. Where it
  outnumbers 1.5:1 the trade turns from 0.7 to 1.5.
- (b) Strikes and the blow: hold or recall a strike when sieges are in flight for its system. The swarm has to
  know they sailed (its scouts watching the staging base), or `postureStagedShare` above 0 as a crude stand-in.
  **The seeing half was built the same evening (the user: "Yeah give same picket"):** a human attack force in
  hyperspace within `swarmPicketLY` 4 of a live hive is seen (`ThreatSwarmIntel.sees(where, hyper)`,
  `threat-fog.md` 4 "The hive picket"), about 5 days before it enters the system. **The recall followed (the
  user: "should be able to deviate those that are convenient"):** a system attacked and short of its need calls
  in the strikes within `postureRecallLY` 10 still preparing or travelling out and nearer it than their target;
  their fleets join its garrisons (`ThreatPosture.recallStrikes`, `swarm-defence.md` "Strikes come
  home"). Both game-tested in hw8, section 6. `swarm.pl` prints sieges first seen by source and strikes recalled.
- (c) Its means: what a hive costs on the ground, what the forges make, a defender's edge in orbit. Balance.
- (d) Leave it: found at m38-44, the swarm is gone by m65-90.

## 6. hw8a-hw8c (2026-10-04 night): the hive picket and the strike recall

Build 5875df2 on shipped defaults, three new games side by side (`sbs.ps1 -Tags hw8a,hw8b,hw8c -Days 3750`), run
to day 3750 in 51 minutes of fast-forward (74 days a minute each: hw8a's swarm reached 154 hives and slowed the
batch). No exception and no play or council error. By year: `%TEMP%\threatinc-tests\hw8-phases.pl <tags>`.

**Result: both work as built, and the swarm is no longer exterminated on schedule.** In hw8a it runs away (154
hives, 245k FP of fleets at m122), in hw8b it holds four years and is then ground down (3 hives at m123), in
hw8c it dies as in hw7 (m89). It takes one human world in three runs.

| | hw8a | hw8b | hw8c | hw7a-hw7c |
| --- | --- | --- | --- | --- |
| First hive found / swarm extinct | m40 / never | m40 / never (3 hives left) | m40 / m89 | m38-41 / m65-90 |
| Hives, fleets at m60 / m84 / m120 | 27, 30k / 70, 58k / 150, 228k | 17, 23k / 27, 24k / 5, 1k | 9, 5k / 2, 0 / 0 | 3-10, 1-5k / 0-2 / 0 |
| Swarm at first contact | 15.6k FP, 2.3k FP/mo | 12.9k, 2.3k | 9.5k, 1.5k | 10.2-17.0k, 1.5-2.3k |
| Sieges first seen by the picket | 251 of 411 | 111 of 173 | 41 of 68 | no picket |
| Strikes launched after contact / recalled | 184 / 118 (130k FP) | 26 / 23 (28k) | 1 / 2 (0.8k) | 2-12 / no recall |
| Days from launch to recall (median) | 3 | 4 | 0 and 34 | |
| Transfers after contact | 7,571 (1,265k FP) | 1,493 (263k) | 150 (31k) | 125-228 (17-35k) |
| A siege's first day, siege against garrison (median) | 1.5k FP against 601 | 1.6k against 464 | 1.4k against 307 | 0.9-1.4k against 163-356 |
| Sieges met at parity or better | 19 of 272 | 7 of 122 | 1 of 45 | 0-1 of 29-54 |
| Sieges called off in orbit | 107 | 19 | 8 | 0-6 |
| Lost over hive worlds, Threat FP a human FP | 1.34 | 1.42 | 1.45 | 1.37-1.43 |
| Swarms built after contact | 6,013 (1,134k FP) | 1,531 (284k) | 193 (47k) | 164-332 (27-68k) |
| Seedings after contact | 190 | 33 | 1 | 0-5 |
| Human sieges arrived | 373 (471k FP) | 162 (243k) | 66 (96k) | 40-74 (48-111k) |
| Escorts | 207 (495k FP) | 77 (391k) | 18 (84k) | 17-38 (72-122k) |
| Human landings / overrun by the hive / hives eradicated | 30 / 8 / 22 | 44 / 5 / 38 | 17 / 3 / 14 | 15-20 / 0-1 / 15-19 |
| Threat landings / overrun or bombarded | 17 / 11 | 2 / 2 | 4 / 3 | 5-9 / 3-9 |
| Human worlds lost | 0 | 0 | 1 | 0-2 |

**The picket** first sees 60-64% of sieges a run, each about five days before it enters the hive system; the
rest are seen at a base or world the swarm has eyes on. Each first sighting runs the posture pass that day.

**The recall** fires on nearly every strike launched after contact, a median 3-4 days after its launch, while it
is still preparing at its colony (0.0 ly out) or a system over. Its fleets are built where the route stands and
arrive as reinforcements over 10-25 days (hw8a's first: 17 fleets from Alpha to Beta Laphirial, 3.2 ly, 14 down
by day 25). The strike ends as a withdrawal.

**What the recall turned out to be: the swarm massing its defence.** A strike musters every spare swarm of its
system (`launchPool`: each colony's swarms above its reserve and its own need, and since rule 4 of section 4 a
world with no force over it has no need). The next pass reads the system short and sends the whole strike to
the worlds shortest. hw8a, Dec c209: 2,808 FP in 7 fleets called into Alpha Laphirial, 6,174 FP short of 9,694;
twelve days on Alpha Laphirial III held 2,261 FP against an Independent siege of 550, which turned home. The
swarm trades no better (1.34-1.45 Threat FP a human FP, as hw7): it wins by turning sieges away before they
land. hw8a's humans landed 30 times in 365 expeditions and had 95 called off against a median 1.7 times their
FP; it kept its hives and their income (1,210k FP after contact against hw7a's 74k) and seeded 190 worlds.

Where no strike launches there is nothing to call home and a system is defended as in hw7. hw8c launched one
strike after contact (9.5k FP at contact, 46 of 48 months in CONSOLIDATE). hw8b held 17-29 hives to m84 on 21
recalls, then wanted two to three times what it held (m60: 22.9k FP held, 52.8k wanted), launched 6 strikes in
years 7-10, and lost 38 hives to 44 landings; its humans built escorts of a median 3.5k FP (hw8a: 1.2k of 11.2k
wanted, 14 not built at all).

**What it costs.**

1. Fuel. A recalled strike has paid its passage out and back at launch. hw8a spent what it made: 0.25-0.6M
   fuel a year in years 4-8, 0.96M in year 9 and 1.86M in year 10 as it built fuel makers (36k a month made at
   m60, 100k at m108, 256k at m122); its stock stood at 0-45k from year 7 and 194 launches were held for fuel
   after m96. When the stock is dry no strike launches, and the massing stops with it.
2. The offence. A system short of its need calls every strike in range home, so after contact few sail. hw8a at
   80-228k FP (m96 on) launched 100 strikes, 44 recalled; it landed 13 times, 10 were overrun or bombarded, and
   it took no world in ten years. Not diagnosed further.
3. A strike comes whole. hw8a sent 5,269 FP to Beta Laphirial for a gap of 1,447, hw8b 5,135 for 606; of the
   130k FP hw8a called home 36k was above the gap it was called for, of hw8b's 28k, 12k.

**Open, the user's call** ((a) built the same night on the user's word, "If you recommend that then do it and
run another test": `swarm-defence.md` "The defence is massed", tested in section 7; the rest not built):

- (a) Make the massing deliberate, recommended. The pass itself sends a system's spare swarms to its worlds that
  are short - option (a) of section 5 - and a launch musters only what the system holds above its need. No fuel
  is spent on strikes that never sail, the defence no longer waits on the monthly launch, and strikes sail again
  once home is covered. The launch half was drafted and taken back out
  (`%TEMP%\threatinc-tests\strike-hold.patch`): on its own it ends the massing and puts the swarm back to hw7.
- (b) Leave it as built.
- (c) Send only the fleets that cover the gap and let the rest of the strike fly on (fault 3).
- (d) The swarm's offence: 228k FP took no world. A diagnosis of its landings (240-1,480 troops, 352 days of
  relief bombardment logged after m96) is a separate read of these logs.
