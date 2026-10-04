# Game test runs

The record of game runs from 2026-10-04 afternoon on, when the user froze the simulator and made the game the
test (`facts.md` Decisions). Earlier runs are in `war-sim-real-runs.md` (hw4d-hw5d) and
`war-sim-real-runs-oct2.md`.

How a run is made: `%TEMP%\threatinc-tests\run-go.ps1 -Tag <tag> -Clone <ngN> -Days 3750` - a new game on the hw4
sector, fast-forwarded to war day 3750, about 40 minutes, the user's settings restored after
(`testing-harness.md`). The log is `%TEMP%\threatinc-tests\ti-<tag>.txt`, the monthly dumps
`tools/warsim/validation/<tag>`. Digests, in `tools/test-harness/digest/`:

- `uat.pl <tag> [month]`: worlds lost, hives, months in each stance, where CONSOLIDATE was entered, Threat
  landings, human landings on hives and how many were overrun, doomed landings refused, hives eradicated,
  exceptions.
- `marines.pl <tags>`: the human marine reserve, Threat landings and overruns by two years.
- `mflow.pl <tags>`: marines drawn by sieges, landed, lost in counter-attacks, issued as shortage cover.
- `mdump.pl <tags>` (from `tools/warsim/validation`): marine income a month from the dumps.
- `prof2.pl <jstack dumps>`: where the game's main thread is (`facts.md`, "How fast does a long run go").

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
