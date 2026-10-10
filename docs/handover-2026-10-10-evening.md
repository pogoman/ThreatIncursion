# Handover 2026-10-10 evening (19:10) - the swarm on the OceanPena day-0 seed

START HERE. The earlier handover (`handover-2026-10-08-morning.md`, sections 38-45) holds the day's reads in
full; this file is the state at the session's end and what to do next. Everything below is committed and
pushed (last commit 3e930526 plus the facts line for ck1, see section 7).

## 0. Standing rule (20:00, in `CLAUDE.md` "Models"): Fable for all

The 19:15 rule (Opus main session, Fable subagents for strategy) is withdrawn: the Opus session misread
hw146's log on its first read ("the hold never fired") and only caught it when this session's read landed.
This repo runs on Fable, main session and all reasoning; lower tiers do legwork only, checked here.

## 1. Ask the user first

- hw146 (section 5) is the first run of the fixed losing hold from the new checkpoint. Read it; if a game
  lost, the user's loop (section 3) says the next trials start from THAT game's snapshot.
- The user's two unbuilt thoughts (17:50): sweep the opening bar (`threatinc_strikeStagedOpenFP` 40000 ->
  80000) as the next pure-knob trial; a swarm that spreads AWAY from humans and takes every empty system
  ("would become unstoppable ... not saying do it yet"). My read in section 6.
- D1 bends the user's 2026-10-07 "losing spends" rule (the fund horizon shortens and spoiling blows weigh
  x (1 + 9p) while losing). The user said "You can try it"; the rule's text in `strikes-staged.md` section 4
  now says so. Confirm they want the hold kept if hw146 wins.

## 2. The build (all pushed to origin/main)

| commit | what |
|---|---|
| d69b7f33 | opening bar `strikeStagedOpenFP` 40000; `defenceFirst` false; `consolidateLosing` 0 (code kept for both) |
| 1dc17b95 | `ThreatOffensive.pass` prices no prong before the bar; `IncursionManager.openingWaits` (attacked first opens now: bar holds only while nobody mobilised and `ThreatAlarm.alarm` is 0) |
| 082c13b2 | D1 first cut: `offensiveHoldLosing` 0.3 - the campaign priced a month's fund each pass (flawed, see hw144) |
| e276bee5 | D1 fixed: `payDefence(0f)` pays the pressed hives first, the campaign prices at most a month's fund of what is left |
| 3e930526 | `sbs.ps1 -Snapshots N`: every game's quicksave copied to `save_X<tag>d<war>`; recipe in `testing-harness.md` |

Knobs in `data/config/settings.json`: `threatinc_strikeStagedOpenFP` 40000, `threatinc_offensiveHoldLosing`
0.3, `threatinc_defenceFirst` false, `threatinc_consolidateLosing` 0.

## 3. The user's testing loop (decided today, recorded in `facts.md` Decisions and memory)

1. **Speed (18:55):** batches start from the seed's pre-war checkpoint and stop at war day 3,800 (both losing
   games on this seed read by then). `save_OceanPena_1024894203100914406ck1` = last save at war day 1,883, the
   war opened at 2,005; made 19:05 on the current build. Remake it (`sbs.ps1 -Tags ck -Checkpoint ck1 -Base
   save_OceanPena_1024894203100914406 -Days 4700 -Resolution 1920x1080`, ~11 minutes) after any PRE-WAR change
   (the opening bar and the pass gate were such changes; the hold is not).
2. **A failing run becomes the checkpoint (19:00):** "switch to specific checkpoint when we have a failing run
   so we can try different strategies until threat win. Then if they win we go back to random seed general
   testing." Batches run `-Snapshots 300`; a lost game's snapshot from before its turn (`game-runs-2.md`'s
   per-150-day table says when) is the next batch's `-Bases`.
3. **Commit and push before every launch** (16:00).

The batch command now:
```
powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-harness/fastforward/sbs.ps1 -Tags hw147a,hw147b,hw147c -Bases "hw147a=save_OceanPena_1024894203100914406ck1;hw147b=...;hw147c=..." -Days 1917 -Snapshots 300 -Resolution 1920x1080
```
(`-Days` = days run from the loaded save: 3800 - 1883.) From a snapshot `save_OceanPena_...hw146bd2700` use
`-Days 1100`. Run it as a background Bash task; status in `%LOCALAPPDATA%\Temp\threatinc-tests\sbs-status.txt`
(`done` at the end), logs `ti-<tag>.txt` beside it. Watcher:
`bash tools/test-harness/reads/watch.sh hw147 60 "hoard,nowar,overdue,feed,churn,enough,muster,rowfor,broke,lopsided,starved,collapse,bleed,ref" "$PWD/tools/test-harness/reads/check-hold.sh"`
(exits 1 on a signal; restart with the signal in the ignore list if it is a reading, not a fault). Read a
batch with `sh tools/test-harness/batch-read.sh hw147`.

## 4. What today's reads say (full tables in handover sections 43-45, `game-runs-2.md` hw142-hw145)

- **The opening bar works.** hw143 (bar 40k): 0 / 34 / 36 hives; opened at war day 1,947-2,036 with 18-23
  hives and a 32-38k navy (was 1,306-1,673 with 7-14k); nobody found a hive before any opening. hw142 on the
  same seed with defence first on was 0 / 0 / 0.
- **hw144** (D1 first cut): **81 / 9 / 68 hives**, humans 12 / 28 / 33 colonies - a the biggest swarm of any
  run. Every faction found hives in all three games, so finders no longer separate games; nor does siege
  volume (b lost under 498k FP of sieges, c held under 525k).
- **The decider:** the garrison at each hive against the strongest attacker's median siege (the rally gate
  refuses help that cannot win at x1.25). hw144: a 2.7k FP a hive, c 1.2-2.7k, b 0.8k against Hegemony's
  1,200 - b fell 45 -> 9. b's siege fights d3,300-3,750 ran 1:1 (336 fights, 66k Threat FP lost, 62k human)
  on a 10k FP/mo forge income, and HALF the fights found no Threat fleet over the world.
- **The hold** stopped b's strikes (0-4 per 200 days by the end) but the first cut priced "a month's fund"
  every month - the fund's whole income - so 24.7k FP reached the hives in 1,300 days. Fixed (e276bee5).
  The fund is ~3k/mo against a 33k garrison shortfall (b's posture at d3,500: held 39k, want 73k): the
  fund cannot fill the line; the forges' bank building (`maintainGarrisons`) and WHERE the pressure pass
  puts the swarms when sieges land are the next reads.
- Rejected today: encirclement of the core ("would spread them thin", 16:50); consolidation (A, inert);
  defence first everywhere (C1, 3/3 lost).

## 5. Running at handover: hw146

Launched 19:06 from ck1: `-Tags hw146a,hw146b,hw146c -Bases ck1 x3 -Days 1917 -Snapshots 300`, ends ~19:30;
the fixed hold's first run. **If this session's background tasks died with it, check the status file: a
`done` line with `REACHED` per game means it finished; otherwise relaunch the same command as hw146.**
Read: ends (`batch-read.sh hw146`), per game the hold passes / FP paid / strikes after the first hold / navy
per hive (`check-hold.sh`'s line in the watcher output), the losing peak, and `SNAPSHOT` lines in the status
file (the resumable saves). Record in `game-runs-2.md` (hw146 line) and a handover section; commit; push.

Then, by the loop: a lost game -> next batch from its snapshot before the turn, with the strategy to test;
all won -> the opening-bar sweep (80k) from ck1, then back to day-0 random seeds.

**Read at 19:45: hw146 = 53 / 45 / 66 hives at war day 3,800** (113k / 47k / 97k FP, humans 24 / 31 / 20
colonies) - no game lost by 3,800, the first such batch on this seed. The hold fired 9 / 11 / 0 times and paid
6k / 64k / 0 FP to the hives (the fix works: hw144b's cut paid 25k in 43 passes). **b is sliding:** navy per
hive 2.1k at d3,116 -> 1.07k at d3,730 under 386 sieges, losing 0.32-0.37; at the last hold pass the fund held
45k with the hives reading "0 FP short" while the campaign priced 3k - the hold pays `ThreatPosture.needFP`
(the pressure's need), and the fights are lost at garrisons the need calls sufficient. By the user's loop b is
not "lost" (held at 3,800), but a run of b to 4,700 from its d3,741 snapshot, or the next strategy trial from
`save_OceanPena_1024894203100914406ck1hw146bd3122` (before the slide), would settle it. **This is the first
question for a Fable subagent (section 0): why does b's navy fall with 45k idle in the fund, and what should
the hold pay - need, or the fight line (the strongest attacker's median siege x the margin, per hive)?**
By the loop, all three held -> back to random-seed general testing (a new day-0 seed, `testing-harness.md`
"Fast-forward runs and a new game") - the user's call, since the session is being switched to Opus.

## 6. Open strategy questions (the user decides; strategy before knobs)

- **Where the navy is when sieges land.** Half of hw144b's fights had no defender fleet. Read
  `ThreatPosture.poll` / `redistributeByPressure` sends against the siege landings in b's log (d3,300+):
  are the swarms in transit, at the wrong hives, or away on strikes? This is the first read of the next
  session if hw146 loses a game.
- **The opening bar as the strategy knob** (the user: "they've got all the time in the world unless the
  player spots them"): sweep 40k vs 80k from ck1 (80k is reached ~day 2,600 at 2.5k/mo).
- **Spread away from humans** (the user's "final strategy" thought): dominant in the ML sense, one sign
  flip in `SpreadRules.billedWeight` (the pull toward unwarred worlds) plus a turn-inward phase; my
  concerns given 17:55: it removes the player's game unless they get a thread to find the hives, the fringe
  is thinner in deposits and farther for strikes, and the data may not need it. Not built, not decided.
- Unlanded strikes: 13 of hw143a's 15 late strikes "ended unspawned" (drawn garrison spare that never
  landed, half re-banked). Worth a read of the strike ledger - a separate defect candidate.

## 7. Gotchas for the next session

- Build out of process (`powershell -NoProfile -ExecutionPolicy Bypass -File compile.ps1`); the game locks
  the jar while a batch runs - check with a temp-dir javac (the PowerShell one-liner in the chat: compile
  `src` to `%TEMP%\ti-check-classes` with compile.ps1's classpath) and build after the batch.
- `python` is a stub; patch with Perl scripts written by the Write tool (a long Bash heredoc with nested
  quotes failed to parse once today). Files are mixed LF/CRLF; the patch scripts normalise.
- The watcher exits on any signal (`ref`, `holdfall`, `starved`...); most are readings, not faults.
- The facts line for ck1's war day (1,883) and this file are the only uncommitted edits at 19:10 - commit
  them first (`git add docs && git commit && git push`).
- Memory files updated today: open-later-fixes-off, no-encirclement, losing-holds-navy,
  failing-run-is-the-checkpoint, checkpoint-before-war, consolidate-on-keystones, decided-by-day-2000,
  defence-first-c1; `handover-2026-10-10.md` now points here.

## 8. Opus session, 19:50

The hw146 read is the paragraph at the top of section 5 (the Fable session's, 19:45); b's navy per hive halved
(2.1k -> 1.07k) while the hold paid `needFP` - the Fable question stands. Added: batches from ck1 take ~32
minutes, not 18 (the war runs 68 days a minute; corrected in `facts.md` and `testing-harness.md`); check-hold.sh
greped the first cut's log text and showed 0 hold passes in the watcher (fixed). hw147 launched 19:44 by the
section-5 loop (all held -> opening-bar sweep): ck1 with `threatinc_strikeStagedOpenFP` 80000 (the fund holds
37.6k at ck1, so ck1 stays valid for either bar).

## 9. hw148 - the third seed, 20:53

hw147 (Opus's 80k sweep) died unread; the bar is back at 40000 (a68a032f). The user, 20:08: "random seed test yep
proceed" - a new day-0 seed `save_ZhiFarah_3202965389391319559` (recipe and click positions in `testing-harness.md`,
"Third seed"), batch hw148 from day 0 to 3,800 with snapshots. **41 / 42 / 43 hives, humans 20 / 25 / 29, no game
lost** - but b and c are falling at the end (55 -> 42, 54 -> 43) with 100k navies and the fund idle at 66-96k FP
while every hold pass reads "0 FP short": the same shape as hw146b, now on a second seed. The next read by the
loop: run b on from `save_ZhiFarah_3202965389391319559hw148bd3563` (`-Bases`, `-Days 1200`, ~15 min) to see
whether it falls by 4,700, and in that log where the swarms are when the sieges land (section 6, first bullet).
If it falls, the strategy to test from that snapshot is what the hold pays: the fight line (the strongest
attacker's median siege x the rally margin per pressed hive) instead of `needFP`. a is the healthy shape to
compare against (navy per hive rising to 2.6k, 37-42 hives steady).
