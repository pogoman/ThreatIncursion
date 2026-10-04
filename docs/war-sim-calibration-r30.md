# War simulator, round 30 (2026-10-04): the supplied stall, test-driven

The user's rule from this round: every fix is driven by the simulator, test-driven; a game run is acceptance only,
once the simulator's numbers are good (`facts.md` Decisions). The fault to reproduce is the stall of
`handover-2026-10-04-morning.md`: with supplies banked (`reserveWartimeSuppliesShare` 1), hw4s sat in CONSOLIDATE from
month 46 to month 122 at 13-16 hives, while the NPC humans lost 1 world and killed 5 hives.

Scripts and outputs are machine-local in `%TEMP%\threatinc-tests\sim20261004` (`council.pl`, `posture.pl`, the
`s*-*.txt` checks). The month-44 bench is a copy of `tools/warsim/validation/hw4s` without its first 44 dumps
(`check` starts from the earliest dump, so it starts from the game's own state at month 44). The dumps' day numbers
are negative: the earliest dump is the most negative.

## 1. What the game run shows (hw4s log, from month 44)

- **The handover's account was half right.** The 432 postponements are the STARVE play's saturation expeditions
  (319 against Alpha Laphirial, 110 against Epsilon), short of the whole raze set's fuel and of supplies for a stay of
  about 110 days a world (`IncursionManager.siegeStayDays`, `siegeSuppliesPerPoint`). Hammers are not held by fuel:
  18 of 23 struck. And "one major play a faction" is out of date (`ThreatPlays.majorLimit`, 2026-10-02).
- **The councils are mostly OUTMATCHED**, so they pick STARVE or HOLD (`council.pl`): Hegemony STARVE 44% / HOLD 40%,
  Persean 79% / 16%, Diktat 69% / 28%, the Church and the Path HOLD 66-76%, Tri-Tachyon HOLD 100% (nothing in reach).
  Of about 330 plays ended, 262 are recons, 127 of them "no fresh report by its day".
- **The swarm's THREATENED turns are 80% staged stock** (`posture.pl`), but from month 66 what holds it is a wound:
  Epsilon read BESIEGED for 65 months (m66-m131) with no Threat-side front there. `ThreatPosture.read` wounds a
  system on any disrupted Core, Nexus or Port (`ThreatColonyManager.anyOrganDisrupted`), and the STARVE saturations
  poured 7k-162k fuel a hive into Epsilon (207 `saturationSlice` lines) keeping its Ports and Nexuses down. A hive has
  no razing bar (`ThreatRazing.razes`), so saturation never kills it: the invasion that should follow lands about 330
  marines and is overrun (780 against 331 at Epsilon II, m75).
- **With two systems, one wounded system is half the hive.** From month 77 the swarm held 2 systems; `pressedShare`
  1/2 meets `stanceConsolidateShare` 0.5, CONSOLIDATE founds nothing, so it never grows out of it.

## 2. What the simulator lacked, and what was added (all switches off by default)

| switch | what it mirrors | effect |
|---|---|---|
| `warsim_saturationGameGate` | the game's saturation gate: flotilla sized on the reported orbit x margin or the raze set's fuel / `warsim_saturationFuelPerFP` (100, from hw4s's postponements), supplies for a stay of `warsim_saturationDaysPerWorld` (110) a world, the whole raze set's fuel; all paid or it waits | the home system becomes unaffordable, as in the game (6,250 FP, 640k fuel against 160-250k pooled); full-run hw4s saturations 16 against the game's 15 |
| `warsim_woundByOrgans` | `ThreatPosture.read`'s wound on a disrupted Nexus or Core (the simulator wounded on a front only) | little alone: the simulator's swarm is rarely that small |
| `threatinc_postureStagedHalfLifeDays`, `threatinc_postureStagedFloor` | candidate fix: a faction's staged stock against a system halves each half-life it sends nothing there (`SwarmPosture.stagedCredibility`); not in the mod | no effect on the month-44 bench (see 3) |
| `threatinc_stanceConsolidateMinPressed` | the fix, in the mod and the simulator (section 4): CONSOLIDATE on the share needs at least N systems pressed, or all of them (`StanceRules.pressedEnough`). Replaced a first try, `stanceMinSystems` (the share counted over at least N systems) | default 2 |
| `warsim_humansNoOffence` | test double: the humans bank, scout, found and guard bases and relieve, but start no play, siege or hunt | the swarm never consolidates from month 44, staged read whole or off |
| `warsim_humansStarveOnly` | test double: the councils starve (squadrons, saturations) but no hammer, invasion, feint or hunt sails (`HumanCouncil.starveOnly`) | too weak: from month 44 or 80 the swarm kills nothing back, leaves CONSOLIDATE in two years and runs away (100-170 hives) |
| `warsim_reconOneStop` (round 29) | the game's one-stop recon scout | month-44 bench hives killed 21.5 -> 18 (game 5); hammers 138 -> 143 (game 23): not where the gap is |
| counters `consolidate.byShare`, `.byLosses`, `.floorBlocked` | what holds CONSOLIDATE, an evaluation at a time (`SwarmPosture.stance`); `batch -show` | |
| `warsim_stagedMult` | diagnostic: the staged read scaled | 0 changes nothing on the month-44 bench |

Supplies: on the month-44 bench `warsim_suppliesAccrualMult` 1 matches the game's stocks (1.2M against 1.2M at month
36 of the bench); 2 doubles them within a year. Use 1 on a bench started from a share-1 run's dumps.

## 3. Where the simulator still parts from the game

- **From the month-44 state its humans kill four times as many hives** (21.5 against 5 by month 100, with the gate):
  its landings succeed (sieges landed 50+ against 11), its recons come back (14 empty against 127), and it sends five
  times the squadrons (188 against 36 by bench month 36). Its CONSOLIDATE (75 months against 89) is held by real
  attacks and hive losses (`hiveDelta < 0 && attacked > 0`), not by the staged read, which is why switching the staged
  read off or discounting it changes nothing there.
- **From the start its swarm outgrows the game's from month 36** (17 hives against 13; 7 systems against 5 at month 46),
  so it never gets stuck small, and the stall that needs a two-to-five-system swarm does not form (0-25 months in
  CONSOLIDATE against 89).
- Until one of these is closed, the simulator cannot show what a stall fix is worth in the game.
- **The over-attack is in the hammers, not the recons**: the councils' strategies match the game (STARVE, HOLD), and
  recons match in number (248 against 263), but the simulator opens 138 hammers to the game's 23 and 60 starves to
  23. In the game 174 recon plays sent no scout ("probing raid" only; `ThreatScouts.recon` returned null) and 104 of
  them ran out of time; the log does not say which of in-flight, no base, unreachable or unpaid refused them. Next
  fidelity step: log that reason in the mod (`ThreatPlays` recon start), then mirror it.

## 4. The fix: `stanceConsolidateMinPressed` (2026-10-04, user-approved form, default 2)

The game's own stall, from its stance lines: m46 EXPAND -> CONSOLIDATE at 3 of 5 pressed; from m77 the swarm held 2
systems and 14 hives, and sat at **1 of 2 pressed, no hives lost, for 45 months** (m77-m122), then flickered at
1/2 - 0/2 to m144. One pressed system of two is the state the floor releases.

Benches (30 seeds; council, saturation gate, organ wounds, one-stop recon on):

| bench | months in CONSOLIDATE, 0 -> 2 | hives at the end, 0 -> 2 | worlds lost | hives killed |
|---|---|---|---|---|
| month 80 (`hw4s-m80`: the game's two-system state; the simulator's kills 6-9.5 against the game's 5 - in range) | 51.5 -> 44.2 (game ~55 of 64) | 23.5 -> 34 (game 14) | 4 -> 4 | 9.5 -> 9 |
| month 44 (`hw4s-m44`) | 65.7 -> 59.3 (game 89, cumulative) | | | 18 -> 18 |
| full hw4s, hw5d (from the start) | identical | identical | | |
| full hw5c | identical but p10/p90 | | | |

- The floor blocks CONSOLIDATE 13 evaluations at the month-80 start, then never again (`consolidate.floorBlocked`):
  the released swarm grows to 3+ systems and the simulator's humans, over-aggressive (above), soon press a second
  one, and it is back in CONSOLIDATE at 2/3, 2/4, 4/7 (seed 3). The game's humans pressed one system for 65 months,
  so the simulator's gain is a lower bound for the game.
- Big swarms are untouched: with 8+ systems a share of 0.5 already means 4+ pressed.
- Starve-only humans (the floor's purest case) run away either way: the double is not the game.
- Built in the mod (`ThreatStance.evaluate` -> `StanceRules.pressedEnough`, `ThreatIncConfig.stanceConsolidateMinPressed`,
  LunaLib row; no migration, the row is new). Game UAT waits until the simulated war is clean (user, 2026-10-04:
  no UAT while the simulator still shows problems; section 6).

## 5. Round 31: the overrun invasions (2026-10-04)

**The game (hw4s).** A STARVE that holds a Nexus down ends "starved, invasion next" and opens a HAMMER to invade
(13 times). Every human landing on a hive came in under its first counter-attack:

| landing | siege days, FP | carried, landed | first counter | end |
|---|---|---|---|---|
| Epsilon II (Persean) | 87 d, 1150 -> 498 | 758, 329 | 780 | overrun |
| Alpha VI (Hegemony) | 55 d, 4300 -> 3090 | ~768, 552 | 600 | overrun |
| Beta II (Hegemony) | 59 d, 950 -> 708 | ~519, 387 | 520 | overrun |
| Epsilon C I (Persean) | 43 d, 3208 -> 2681 | ~686, 574 | 650 | battered, overrun later |
| Epsilon II (Independent) | 9 d, 2275 -> 2133 | ~882, 827 | 650 | held, overrun later at 1140 |

Two faults. The marines are cargo and die with the hulls a long siege loses (15-57% over 43-87 days), while the
launch sizes them at the bare beachhead line. And `ThreatGroundFronts.readyToLand` lets an NPC first landing go once
orbit is done without `beachheadSurvives`; 10 of 11 landings came that way ("landed (ready)").

**The simulator** kept marines whole in the orbital fight and under the guns: `HumanSiege.hullsLost`, now always on
(`warsim_marinesDieWithHulls`, default true). With it the month-44 bench's overruns go 4 -> 8 (game 6) and its
doomed landings show the game's pattern (560-650 marines against Alpha's 4,000-9,500 counter-attacks, "dry").

**The fix**, mod and simulator: `siegeMarineHeadroom` (the wanted marines x N, `IncursionManager.raidStrNeededAt`,
so the flotilla grows to carry them) and `siegeNoDoomedLanding` (`ThreatPurgeFGI.doCustomRaidAction`, `HumanSiege.land`:
short of the beachhead, no landing and no raid, the marines sail home). 30 seeds, marines die with hulls:

| bench, last checkpoint | as built | refusal only | headroom 1.5 only | 1.5 + refusal | **2 + refusal** | game |
|---|---|---|---|---|---|---|
| m80 hives killed | 9.5 | 7.5 | 11.5 | 10.5 | **17** | 5 |
| m80 overruns / landings | 4 / 17 | 0 / 9 | 4 / 21 | 0.5 / 14.5 | **1 / 27.5** | 6 / 11 |
| m80 hives at the end | 38.5 | 39.5 | 30.5 | 39 | **28.5** | 14 |
| m80 worlds lost | 5.5 | 5 | 6 | 5.5 | **4.5** | 1 |
| m44 hives killed | 16 | 9.5 | 16 | 16 | **21.5** | 5 |
| m44 overruns / landings | 8 / 52 | 0 / 16 | 5.5 / 45.5 | 1 / 30.5 | **1 / 40.5** | 6 / 11 |
| m44 months CONSOLIDATE | 67 | 96.5 | 66 | 65 | **61.5** | 89 |

Refusal alone makes the humans passive (they land a third as often); headroom turns the marines it saves into kills.
Built defaults: headroom 2, refusal on (LunaLib rows; new, no migration). The from-start hw4s check is unchanged by
it (3 landings either way) - see section 6.

## 6. The simulated war is not clean yet (what blocks UAT)

- **From the start the swarm runs away**: full hw4s check, as built or fixed, 381-388 hives and 58 worlds lost by
  month 144 against the game's 14 and 1; the humans land 3 sieges (game 11). Benches from the game's own state
  (month 44, 80) are near the game; the opening months are not. This is the next fault to reproduce and fix.
- **From the game's state the humans over-attack**: 138-143 hammers against 23 (section 3); kills 17-21 against 5.

## 7. Round 32 (2026-10-04): the share-1 war, from the game's state

Target: the simulated hw5b/hw4z (share 1) wars lost 19.5/13.5 worlds by month 120 against the game's 2/3, with
landings 183/120 against 55/78; hw4p (share 0) was in range. Checks run with the B set (section 2) on hw5b, hw4z
and hw4p, 30 seeds; scripts in `%TEMP%\threatinc-tests\sim20261004` (`chk3.sh`, `b3.sh`, `cmp.pl`, `sfight2.pl`,
`fronts.pl`, `spend.pl`, `sflow.pl`, `income.pl`).

**Built (simulator only, all fidelity to the mod):**
- Human accrual refit (`HumanFit.ACCRUAL_SHARE0/1`, `BASE_SHARE0/1`): ledger medians of the first 48 prints per
  world, interpolated by `reserveWartimeSuppliesShare`; the pd7a table ran about half the game's fuel. The
  mobilisation seed and `basis()` read the same rate (`HumanPools.refRate`). Income now matches: 2.53M supplies by
  m72 against hw4z's 2.49M.
- Relief and guards, now default on: relief to invaded colonies fights the Threat guard and takes its place
  (`warsim_reliefToInvaded`, `reliefFights`, `guardStandDown`, `reliefTakesGuard`), relief sails in whole fleets of
  `reliefFleetFP` x 1.195 (the game's ~358 FP), and a Threat guard lasts the game's life (`warsim_guardLife`:
  median 11-15 days, `SwarmFit.GUARD_LIFE_DECILES`).
- `strikeValue` counts a forward base's cut links (`warsim_strikeCutLinks`, `ThreatFrontlines.strikeWeight`).
- `warsim_frontFacedOnly` on: a base stands a garrison only where a hive would strike its faction first, as
  `ThreatFrontlines.strikeAt` does under billed reach. Off, the swarm saw 68% of bases guarded (median 1,607 units)
  against the game's 190-330 and aimed at colonies 2:1 where hw5b's aimed at bases 2:1.
- `SwarmFit.SUPPLIES_PER_FP_AWAY` 0.5 -> 0.73 (the game's Reach lines, 0.71-0.74 a run; knob
  `warsim_suppliesPerFPAway`).

| m120, real \| sim median | worlds lost | landings | hives | swarm supplies |
|---|---|---|---|---|
| hw5b before round 32 | 2 \| 19.5 | 55 \| 183 | 167 \| 138 | 168k \| 570k |
| hw5b now | 2 \| 11.5 | 55 \| 86.5 | 167 \| 110 | 168k \| 309k |
| hw4z before | 3 \| 13.5 | 78 \| 120 | 131 \| 99.5 | 24k \| 869k |
| hw4z now | 3 \| 10.5 | 78 \| 59 | 131 \| 82 | 24k \| 545k |
| hw4p before | 24 \| 20.5 | 120 \| 210 | 163 \| 151 | 6k \| 627k |
| hw4p now | 24 \| 13.5 | 120 \| 142 | 163 \| 142 | 6k \| 180k |

**Still out, by cause (next steps in this order):**
1. **Fronts on armed colonies finish too often.** hw4z's game fronts end overrun (73, median 200 days), ground down
   (10) or standing (20, most at stratum 0); its 5 losses were all no-reserve worlds. The simulator's front engine
   takes 6-9.5 reserve colonies a seed at share 1, often from one oversize landing (seed 1: 6,338 FP -> 4,986 troops
   on Athulf, beachhead 412, four districts in 72 days with no counter-attack). Game landings: median 650-745 troops,
   p90 1,660-2,140; the simulator's median 1,119, p90 3,670.
2. **Strikes grow too big late**: hw4z medians 2,300-2,500 FP in years 8-9 against the game's 600-1,000 at the same
   count a year. The swarm still piles supplies (`canSustain` passes everything); hives now run under the game's, so
   the fix is in the flows, not the burn: made 5.09M against 4.40M, sustenance 1.75M against 1.24M by m120, and the
   game's ~1.5M unaccounted (structures and the rest) against the simulator's 0.67M on structures.
3. **Arrival break-off**: the game's strikes meet a live defence (`liveTargetDefence`: every hostile fleet in the
   system plus the station) and 15-28% arrive outweighed; the simulator's arrival reads the start dump's gate plus
   guards and breaks off 1-8%.
4. **Human supplies late**: the game's fall to 136-285k by m120, the simulator's stay 0.9-1.0M. `ThreatUpkeep.charge`
   bills every fleet with a home (relief, convoys, scouts too) at vanilla maintenance: hw4z 3.34M against the
   simulator's 1.8M (orders only).
5. **Bases held 2-5x the game's** with faced-only on (hw4z 69 against 14): the spared garrison budget founds links.
6. The humans' 3.5x sieges (section 6) remain.


## 8. Round 33 (2026-10-04): the swarm's stall was the humans' early hammers, which were the scouts' oracle

**Diagnosis (hw4z seed 3, per-system posture lines, `warsim_logSeen`-style log added to `SwarmPosture`).** The
simulated swarm sat in CONSOLIDATE over months 43-90 (game: 52-64) and founded nothing, so it piled supplies. Its
THREATENED systems read *staged* pressure from human hammer musters: 34,775 FP mustering against Beta Laphirial on
day 1301. The councils hammered early because they knew the hives early. Two faults:

1. **Scouts were oracles.** A lead's scout sailed straight to the strike's true origin and filed every hive system
   within `scoutLeadRadiusLY` at once (five Laphirial systems on day 1199), and the routine sweep sailed only to
   systems with a hive. The game's parties (`ThreatScouts.launchSorties`) walk a route through the unknown,
   uninhabited planet systems within the radius, nearest first from home, stay `scoutStayDays` a stop, and end at
   the first live hive: hw4z found its hives one system at a time over days 1283-1528.
2. **Swarm costs.** `SwarmFit.builtFP` used the mod's fallback table, +40 FP a fabricator. The dumps' `wantFP`
   (`ThreatPosture.rowsFP` of the learned `swarmCostEstimate`) put a fabricator at about +555: size-5 hives want
   1,355 in the game against the simulator's 800, and held 2,020 FP against 910 at day 904.

**Built** (both on by default):
- `warsim_scoutRoutes` (`HumanIntel.sorties`, `planRoute`, `routeStop`; `Faction.leads`, `State.swept`): leads
  persist until their origin is found, a party per route until nothing is left to sweep, routine sweeps only for a
  faction with no lead; a found system is looked at again only by the council's RECON.
- `SwarmFit.FABRICATOR_FP` 555 (`warsim_fabricatorFP`), LOW 67, HIGH 345.

**Results** (30-seed checks, month 120, real | round 32 | round 33):

| run | worlds lost | landings | hives | hives killed | swarm supplies | sieges | months consolidating | human supplies |
|---|---|---|---|---|---|---|---|---|
| hw4z | 3 \| 10 \| 14 | 78 \| 65 \| 134 | 131 \| 95 \| 125 | 4 \| 19 \| 9 | 24k \| 545k \| 22k | 15 \| 52.5 \| 24.5 | 7 \| 33 \| 4.8 | 136k \| 1.0M \| 2.0M |
| hw5b | 2 \| 9 \| 12.5 | 55 \| 81.5 \| 130 | 167 \| 95 \| 126 | 2 \| 23 \| 10 | 168k \| 336k \| 15k | 15 \| 66.5 \| 31 | 0 \| 17 \| 2.4 | 285k \| 0.9M \| 1.8M |
| hw4p | 24 \| 11.5 \| 14 | 120 \| 95.5 \| 145 | 163 \| 121 \| 128 | 2 \| 12 \| 7.5 | 6k \| 247k \| 14k | 8 \| 38 \| 19 | 1 \| 22.5 \| 4 | 42k \| 0.3M \| 1.05M |

The swarm side now fits: supplies, stance, hives, strikes. The fabricator refit alone moved nothing outside the
seed noise; the routes did it. What is out is the human side: the humans sail half the sieges they did, their
supplies pile to 1-2M where the game's run down to 42-285k, and colonies fall (12.5-14 against 2-3 on the
supplied runs).

**Still out, next:**
1. The human supplies pile (section 7, cause 4: the game bills relief, convoys and scouts at vanilla maintenance).
2. Worlds lost on the supplied runs: colonies fall early (first loss day 882-1145 against the game's 1450-2320),
   and a hive inside a human system is found at once, so the hammers still start early on those seeds.
3. The first strikes: day 729-1094 with 389-2,962 FP against the game's 976-1190 with 3,000-6,000.
