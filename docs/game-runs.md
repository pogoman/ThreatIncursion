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
- `mass.pl <tags>`: the massing (`postureMass`): fleets massed by year, moves between systems, give-backs
  within 30 days, FP massed before and after each siege came down.
- `months.pl <tag> [from] [to]`: one run by month: the swarm's census (hives, hives found, fleets, income,
  fuel), FP each side lost over its worlds, sieges arrived / called off / beaten / landed, fleets rallied,
  strikes and recalls.
- `calloff.pl <tags>`: how the sieges over hive worlds ended and the FP each ending left, the fight days, and
  what a day of exchange on every call-off would have cost each side.
- `fall.pl <tags>`: how long a hive takes to fall: days a siege spent over its world by ending, and by hive
  size the days from the first siege fight to the landing and from the landing to the last stratum.
- `cover.pl <tags>`: the orbit cover an unspawned landing leaves over its army: how many the swarm outweighed
  and when, what was rallied or sent to the world meanwhile, how each landing ended.
- `batch.sh <month> <tags>`: all of the above for a batch in one go, for a findings file a checkpoint.

Running a batch on another machine (setup, the pristine save, the settings backup):
`handover-2026-10-05-testing.md`.

Sections 1-3 (hw5e-hw6c: the stance floor, what stopped the humans attacking, the council's restraints
removed) are in `game-runs-humans.md`, under their numbers.

## 4. What stops the swarm defending and attacking (hw6a-hw6c, read 2026-10-04 evening)

The user's call after `game-runs-humans.md` 3: "we need to remove artificial constraints on the swarm like we just did for
humans". Read from the hw6 logs (figures hw6b) and the code. Each link is a rule (removed, with a knob that puts
it back) or a shortage (left, named).

**Defence: 12.9k FP in the home system, and each world fought alone.**

1. **A depot's capacity counted as an attack.** `ThreatPosture.read` took what a base staging for a hive system
   could pay (`Place.stagedFP`) as pressure: Yma read 37,550 "staged", Wotan 15,040. Since `game-runs-humans.md` 3 a siege is
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
Read with `swarm.pl` on both batches (its months run one lower than `uat.pl`'s in `game-runs-humans.md` 3).

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
   falls a median 77-91 days later (quickest 30-40; hw6: 86-103, so `game-runs-humans.md` 3's "in a month" was its quickest
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

## 7. hw9a-hw9c (2026-10-04 night): the defence massed

Build `0dfa536`, `postureMass` on (`massWithin`, the recall, `massFromNeighbours`, the launch hold:
`swarm-defence.md`). 37 minutes, no exception, no council error. **The swarm is exterminated in 3 of 3. The
knob is off again (`postureMass` false = the hw8 build).**

| | hw9a | hw9b | hw9c | hw8a / b / c |
|---|---|---|---|---|
| extinct | m124 | m91 | m105 | never / 3 hives at m123 / m89 |
| hives m60, m84, m108 | 16, 16, 27 | 12, 8, 0 | 14, 9, 0 | 27, 70, 107 / 17, 27, 8 / 9, 2, 0 |
| months in CONSOLIDATE since found | 68 of 80 | 47 of 51 | 48 of 64 | 7 of 82 / 66 of 83 / 46 of 48 |
| seeding swarms since found | 27 | 8 | 10 | 190 / 33 / 1 |
| strikes since found (recalled) | 14 (10) | 2 (3) | 7 (6) | 184 (118) / 26 (23) / 1 (2) |
| fleets massed in a system, from neighbours | 1,009, 805 | 379, 97 | 611, 294 | - |
| FP massed | 421k | 117k | 168k | recalled 130k / 28k / 0.8k |
| sieges down, called off | 222, 29 | 110, 18 | 95, 13 | 373, 113 / 162, 19 / 66, 8 |
| first day, siege against swarms over the world | 1.4k v 385 | 1.4k v 367 | 1.3k v 313 | 1.5k v 601 / 1.6k v 464 / 1.4k v 307 |
| sieges met at parity or better | 9 of 158 | 2 of 68 | 4 of 73 | 19 of 272 / 7 of 122 / 1 of 45 |
| Threat FP lost a human FP | 1.31 | 1.35 | 1.39 | 1.34 / 1.42 / 1.45 |
| human landings, hives eradicated | 44, 40 | 25, 20 | 25, 22 | 30, 22 / 44, 38 / 17, 14 |
| human worlds lost | 2 | 1 | 1 | 0 / 0 / 1 |
| fuel in stock at the end | 1,656k | 584k | 601k | 10k / 536k / 612k |

**What works.** The pass moves the fleets (`Posture: X massed N FP at Y`), nothing is spent on strikes that
never sail (hw9a 77-151k fuel a year after contact, hw8a 0.25-1.86M), and the launch hold was reached 6 / 1 / 2
times only: the massing leaves nothing to launch.

**Why it loses.**

1. It calls for far more than the fight. The need the pass puts on an attacked world is its system's whole
   pressure - every force seen bound for the system in 10 days, plus 30 days of losses - laid on the worlds a
   force is over (`overWorlds`): a median 3.2 / 2.4 / 4.3 times the siege that came down. The calls do not close,
   so every hive system within 10 ly is stripped to its reserve count (805 of hw9a's 1,814 moves crossed systems).
2. Nothing is left to grow or strike. A stripped colony is below its want (`regrowing`) and musters no wave or
   strike: CONSOLIDATE 47-68 months, 8-27 seeding swarms since contact, 0.6-1.7M fuel unspent. hw8's side door
   took only what a strike had mustered and left the rest to seed (hw8a 190).
3. It arrives after the siege. Until a siege comes down the need is split by the size tables, so the median
   massed toward a system in the 10 days before one landed is 0 FP in all three (a mean 0.7-1.1k in the 10 days
   after), and those fleets arrive one at a time into a garrison already outnumbered. First-day defence and
   parity are no better than hw8; sieges called off 13-16% (hw8a 30%, hw8b and hw8c 12%).

The mass also chases the attack: a world gave within 30 days of receiving in 53-59% of moves.

**What the user chose (2026-10-05).** Not a sighting that carries the target world (my first proposal,
rejected: a sighting tells the system, for both sides) but the system defending as one: the fight stays at
the world, the system's spare swarms are sent there sized to the force over it, and a siege seen coming
fights them from its first day. Built the same night with the humans' system-only reading of a strike:
`swarm-defence.md` "The system defends as one", `frontlines.md` "A seen strike tells its system"; game-tested
in section 8. The massing stays off; a neighbour giving only above its own want, the partial recall and the
offence diagnosis of section 6 are still open.

## 8. hw10a-hw10c (2026-10-05): the system defends as one

Build `eb7a1f1`: `systemDefence` on (margin 1.25), `strikeSeenBySystem` on, `postureMass` off. 53 minutes, no
exception, no council error. **The defence works as built and the outcome is hw8's: one runaway, two
exterminated.** Three runs a side cannot tell that from the spread between runs.

| | hw10a | hw10b | hw10c | hw8a / b / c |
|---|---|---|---|---|
| extinct | never (175 hives at m123) | m93 | m74 | never (150) / 3 hives at m123 / m89 |
| hives m60, m84, m108 | 25, 64, 124 | 25, 3, 0 | 7, 0, 0 | 27, 70, 107 / 17, 27, 8 / 9, 2, 0 |
| hives the humans had found, m48 and m60 | 9 of 14, 9 of 25 | 15 of 17, 21 of 25 | 13 of 15, 7 of 7 | 10 of 16, 15 of 27 / 7 of 17, 5 of 17 / 10 of 13, 9 of 9 |
| months in CONSOLIDATE since found | 4 of 97 | 39 of 51 | 34 of 38 | 7 of 82 / 66 of 83 / 46 of 48 |
| seeding swarms since found | 221 | 26 | 1 | 190 / 33 / 1 |
| strikes since found (recalled) | 173 (104) | 20 (17) | 2 (2) | 184 (118) / 26 (23) / 1 (2) |
| fleets rallied to a world attacked (FP) | 2,902 (501k) | 603 (126k) | 164 (31k) | - |
| sieges down, of them seen coming | 432, 432 | 146, 146 | 60, 60 | 373 / 162 / 66, not read |
| sieges ended: called off, beaten, landed or on a front | 244, 155, 27 | 34, 37, 71 | 12, 18, 30 | 98, 215, 52 / 16, 71, 72 / 8, 17, 35 |
| FP a called-off siege took home | 94% | 98% | 87% | 79% / 87% / 68% |
| FP the sieges lost over the worlds, of what came down | 34% | 25% | 25% | 53% / 43% / 32% |
| first day, siege against swarms over the world | 1.4k v 999 | 1.3k v 337 | 1.2k v 167 | 1.5k v 601 / 1.6k v 464 / 1.4k v 307 |
| sieges met at parity or better | 37 of 174 | 8 of 84 | 2 of 41 | 19 of 272 / 7 of 122 / 1 of 45 |
| Threat FP lost a human FP | 1.25 | 1.35 | 1.31 | 1.34 / 1.42 / 1.45 |
| human FP sent (sieges + escorts) v swarm FP built, since found | 1,166k v 1,114k | 351k v 121k | 158k v 27k | 966k v 1,134k / 633k v 284k / 180k v 46k |
| human landings, hives eradicated | 16, 14 | 31, 30 | 21, 16 | 30, 22 / 44, 38 / 17, 14 |
| human worlds lost | 2 | 0 | 1 | 0 / 0 / 1 |

**What works.** Every siege was seen coming (638 of 638), so the unseen branch never ran. The rally fires
(`Posture: X rallied N FP to Y`) and the swarms bound for the world count the day the siege comes down: sieges
called off 56% / 23% / 20% (hw8 26% / 10% / 12%), 85% of them before a single fight day, and the trade is
better in all three. Reinforcements sent against arrived are as in hw8 (92% / 93% / 82% against 96% / 95% /
91%): the rallied fleets are not dying on the way. The humans' system-only reading is neutral: every faction
has mobilised at phase 3 before a strike is seen (struck-at mobilisations 1 / 0 / 0), strikes detected 79 / 10 /
1 (hw8 88 / 8 / 3), guard orders 470 / 103 / 41 (479 / 90 / 10), "faces the strike short" 20 / 0 / 0 (28 / 0 / 0).

**Why two still die.**

1. A massed defence saves the world and kills nothing. A siege fights only while it outweighs the swarms over
   the world (`BattleRules.callsOff`, `siegeBreakOffRatio` 1); outweighed, it turns home the same day and pays
   nothing. So the better the swarm masses, the cheaper it is to attack: a siege that fights and is beaten
   leaves 72-74% of its FP behind, one that is called off 2-13%, and the rally turns the first kind into the
   second (hw10a 155 beaten and 244 called off, hw8a 215 and 98). The sieges lost 25-34% of the FP they
   brought down against 32-53% in hw8, and the fleets spared come back. Every fight the swarm does get is one
   it entered outweighed (the defence was the heavier side on 51 of 844 / 11 of 446 / 4 of 216 fight days).
2. The war follows how many sieges come, and that follows how much of the swarm the humans have found. At
   m60 hw10a and hw10b both hold 25 hives. In hw10a 9 are found: over m53-m70 1.7 sieges a month (2.3k FP)
   come down against an income of 4.1-7.2k FP a month, and the swarm grows in the dark. In hw10b 21 are found:
   5.2 sieges a month (7.1k FP) against 4.8-5.8k (less after m65), and it is gone 33 months later. The same split holds over
   the six runs: found 41-64% of the hives at m48, alive at m123; 77-88%, extinct by m74-m93. No shape of
   defence closes 3 to 1.
3. Pressed, it neither seeds nor strikes. hw10b: CONSOLIDATE 39 of 51 months, 26 seeding swarms and 20
   strikes since found, fuel 26k at m54 and 418k at m70. Not a rule (CONSOLIDATE has not stopped spreading
   since `68d019a`): its fleets are in the defence and its colonies under their want, as in hw8b and hw8c.

**Proposed, the user's call (nothing built).**

- A called-off siege pays one day of the exchange as it turns away - the pursuit a fight at the jump point
  would have given. At the weights on the call-off lines (swarms 2.2 / 1.4 / 2.1 to 1) that day costs the
  humans 201k / 35k / 5k FP and the swarm 143k / 27k / 4k, and the trade in the siege fights goes from 1.22 /
  1.33 / 1.32 to 0.90 / 1.06 / 1.15 Threat FP a human FP (`calloff.pl`). It is a rule on the humans' sieges, and
  alone it would not have saved hw10b or hw10c (35k of 351k FP sent, 5k of 158k).
- Keep `systemDefence` and `strikeSeenBySystem` on. The first did what was asked and improved the trade;
  without the pursuit it also spares the humans' fleets, so the two belong together.
- What closes 3 to 1 is the swarm's means (the ground cost of a hive, forge output, a defender's edge in
  orbit) or its staying unfound - the open list of section 6 and `swarm-strategy.md` 3.

**How long a world takes to fall (the user's question the same morning; `fall.pl`).** Months. A siege fleet's
stay is short (called off day 1; beaten in a median 5-21 days; one that lands has been over the world a median
2-29 days, up to 116), and the orbit is decided in its first days. The ground is not: from the landing to the
last stratum a median 10 days at size 1, 42 at size 2, 62 at 3, 83 at 4, 87 at 5, 121 at 6, 139 at 7, and from
the first siege fight over a world to its fall a median 165-545 days a run. No hive above size 1 fell in under
22 days. Through those months the landing's flotilla holds the orbit as an abstract cover (`coverFP`, median
0.9-1.5k FP) and the rally did not see it: the swarm outweighed the cover in 7 of 16 / 3 of 31 / 0 of 21
landings, and overran 2 of 16 / 1 of 31 / 5 of 21 armies. Gamma Laphirial II (hw10b, size 3): 411 marines
landed under 400 FP of cover on day 1832 and took the hive 72 days later; the swarm, with 16-21k FP of fleets,
sent it one fleet of 146 FP. Fixed after the batch (the rally reads the cover, `swarm-defence.md`), untested.

## 9. hw11a-hw11c (2026-10-05, on a second machine): the rally reads the cover over a landed army

Build `e5ce9b9` on shipped defaults (`systemDefence` on, margin 1.25, `strikeSeenBySystem` on, `postureMass`
off). 33 minutes of fast-forward, no exception, no council error. **The rally now reaches a covered world, but
to one landing in five and with too little: the covers fall no more often than in hw10 and the hives die as
before.** Two runs extinct, one at 3 hives and falling; all three had 78-93% of their hives found at m48, the
band that died in hw8 and hw10.

| | hw11a | hw11b | hw11c | hw10a / b / c |
|---|---|---|---|---|
| extinct | m81 | never (3 hives at m123) | m78 | never (175) / m93 / m74 |
| hives m60, m84, m108 | 11, 0, 0 | 14, 25, 18 | 6, 0, 0 | 25, 64, 124 / 25, 3, 0 / 7, 0, 0 |
| hives the humans had found at m48 | 14 of 16 | 14 of 18 | 13 of 14 | 9 of 14 / 15 of 17 / 13 of 15 |
| landings left under cover (median cover) | 19 (1,362 FP) | 45 (1,137 FP) | 21 (688 FP) | 16 (924) / 31 (1,493) / 21 (1,312) |
| landings a rally reached while the cover stood | 3 | 11 | 0 | - |
| rallied to a world while its cover stood | 11 fleets (3.2k FP) | 32 (4.7k FP) | 0 | 1 (115 FP) / 1 (100 FP) / 0 |
| other transfers to it meanwhile | 19 (2.9k FP) | 256 (27.6k FP) | 8 (1.0k FP) | 139 (16.4k) / 36 (5.1k) / 15 (1.7k) |
| covers the swarm outweighed (median days after the landing) | 1 (57) | 8 (28) | 3 (25) | 7 (27) / 3 (38) / 0 |
| landings ended: hive eradicated, army overrun | 18, 1 | 42, 2 | 17, 4 | 14, 2 / 30, 1 / 16, 5 |
| of the covers lost: hive eradicated anyway, army overrun | 0, 1 | 8, 0 | 1, 2 | 6, 1 / 3, 0 / - |
| sieges down, called off | 75, 7 | 263, 61 | 55, 4 | 432, 244 / 146, 34 / 60, 12 |
| sieges ended `front` (FP down) | 22 (43.1k) | 31 (45.7k) | 7 (9.5k) | not read |
| fleets rallied in all (FP) | 153 (33.8k) | 1,333 (253.6k) | 76 (14.6k) | 2,902 (501k) / 603 (126k) / 164 (31k) |
| Threat FP lost a human FP | 1.15 | 1.31 | 1.40 | 1.25 / 1.35 / 1.31 |
| seeding swarms, strikes, since found | 3, 4 | 41, 27 | 0, 1 | 221, 173 / 26, 20 / 1, 2 |
| human worlds lost | 1 | 0 | 1 | 2 / 0 / 1 |

Against the handover's six questions (`handover-2026-10-05-testing.md` 6):

1. **The rally reaches a covered world, rarely.** 11 / 32 / 0 fleets against 1 / 1 / 0, and the `Posture: X
   rallied` lines name worlds with an army on them - but 3 of 19 / 11 of 45 / 0 of 21 landings drew any, and
   the ordinary transfers still carry more (hw11b 27.6k FP against 4.7k). Read from `rally`, not yet measured
   in a log: a donor with a siege over it, an army on it or a hostile fleet near it gives nothing, and the
   humans land on a system's hives together (hw11a, Alpha Laphirial: nine landings between day 1578 and day
   2158, each standing 81-750 days), so the worlds that could give are the ones excluded. hw11c had 734 FP of
   fleets left at m60 and nothing to send.
2. **The swarm does not take the orbit back more often.** 1 of 19 / 8 of 45 / 3 of 21 covers outweighed, a
   median 57 / 28 / 25 days after the landing (hw10: 7 of 16 / 3 of 31 / 0 of 21 at 27-38 days).
3. **It does not save the hive.** Of the 12 covers lost, 9 hives were eradicated anyway and 3 armies overrun;
   hw11b lost 8 covers and all 8 hives. Armies overrun 1 / 2 / 4 of the landings (hw10 2 / 1 / 5). So both
   links are short: the rally rarely arrives, and a lost cover rarely turns the ground (`tickSwarmBombard`,
   `swarmFrontBombardPer30Days` 0.60).
4. **The war** follows the found share, as in hw8 and hw10: 88% / 78% / 93% found at m48, extinct m81 / 3
   hives at m123 / extinct m78. No run fell in the 41-64% band that survived before, so the batch says nothing
   about a runaway. hw11b recovered to 38 hives at m89 with 18-23 of 25-31 found, then fell.
5. **No side effect seen.** Sieges called off 9% / 23% / 7% (hw10 56% / 23% / 20%): the cover rally did not
   starve the defence against the next siege - it sent 3.2k and 4.7k FP in whole runs.
6. **No exception, no council error** (exceptions file 270 bytes, vanilla's noise, on this machine too).

**Proposed, the user's call (nothing built).**

- Let a world with an army on it or a siege over it still give its spare to a neighbour whose cover it can
  outweigh, or count the system's covers together and mass on the lightest first. As built the rule that
  protects a donor empties the pool exactly when the system is being taken world by world.
- What a regained orbit is worth: 9 of 12 hives fell after the cover was lost. The bombardment rate and the
  hive's counter-attack are the figures to look at before more fleets are sent to win an orbit that does not
  save the world.
- A log line when `rally` finds the world short and the pool empty (which donors were excluded and why), so
  the first proposal is measured rather than read from the code. This one is instrumentation only.

**The harness on a second machine.** The first launch failed at the launcher (`Error loading settings`):
`run-settings.ps1` wrote the prefs value `gameplay/Settings` empty on a machine that had never had one. Fixed:
it is edited only where it exists, and `restore-settings.ps1` removes it when the backup has none. On such a
machine vanilla autosave is left as it is; a clone is deleted at the end either way. Three games ran in 6.4 GB
of free memory at 113 days a minute each.

## 10. hw12a-hw12c (2026-10-05): rally only if enough; troops a broken-up FP 10; forge output doubled

Build `72724b4` (`systemDefenceOnlyIfEnough` on) in all three; hw12b `fabricateTroopsPerFP` 10 (set 10.01:
a store at version 11 migrates an exact 10 back to 5), hw12c `fabFPPerShipUnit` 200. No exception, no council
error. **None of the three saves the swarm.**

| | hw12a (rule) | hw12b (+ troops 10) | hw12c (+ forges x2) | hw11a / b / c |
|---|---|---|---|---|
| extinct | m95 | never (2 hives at m120) | never (5 unfound at m120) | m81 / never (3) / m78 |
| hives m60, m84, m108 | 16, 6, 0 | 14, 17, 12 | 23, 22, 5 | 11, 0, 0 / 14, 25, 18 / 6, 0, 0 |
| found at m48 | 14 of 18 | 15 of 17 | 11 of 18 | 14 of 16 / 14 of 18 / 13 of 14 |
| swarm income since found | 95k FP | 282k | 369k | 47k / 318k / 27k |
| sieges arrived (FP), escorts (FP) | 114 (170k), 53 (152k) | 263 (425k), 96 (229k) | 254 (553k), 107 (284k) | 75 (106k), 36 (167k) / 263 (355k), 109 (509k) / 55 (56k), 28 (62k) |
| sieges called off | 17 | 110 | 135 | 7 / 61 / 4 |
| Threat FP lost a human FP | 1.37 | 1.36 | 1.29 | 1.15 / 1.31 / 1.40 |
| rallies sent, declined (`no rally`) | 222, 526 | 589, 1,245 | 743, 398 | 153 / 1,333 / 76, - |
| covers lost, of landings; those hives eradicated anyway | 5 of 32; 1 | 4 of 45; 3 | 11 of 37; 9 | 1 of 19; 0 / 8 of 45; 8 / 3 of 21; 1 |
| seeding swarms, strikes since found (recalled, all) | 12, 7 (9) | 42, 39 (35) | 43, 51 (48) | 3, 4 (2) / 41, 27 (25) / 0, 1 (1) |
| Threat landings, human worlds lost | 6, 0 | 5, 1 | 7, 0 | 6, 1 / 4, 0 / 6, 1 |

- The rule declines 398-1,245 rallies a run and changes no outcome that three runs can show.
- Troops at 10 has nothing to act on: the swarm lands 5-7 times a run, and 35 of 40 strikes are called home.
- Doubled forges gave 70k FP of fleets at the peak and four times hw12a's income; the humans sent 553k FP of
  sieges, 135 of 254 turned away with 97% of their FP (section 8, "a massed defence saves the world and kills
  nothing"), the trade stayed 1.29, and 48 of 54 strikes were recalled (108k of 127k FP). It fell from 22
  hives at m84 to 5 at m108.
- Common to all nine runs of hw10-hw12: the swarm loses 1.15-1.40 FP a human FP over its own worlds, an
  outweighed siege leaves free, and the strikes turn home within a median 2-5 days.
