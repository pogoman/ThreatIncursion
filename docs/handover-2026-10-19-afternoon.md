# Handover 2026-10-19 afternoon - council back, N1 / N1b, the leash loop, the marine step

START HERE next session. Everything below is in `facts.md` (the day's entries from "DECISION 2026-10-19 00:20"
on) and `game-runs-2.md` s54; this is the shape of it.

## 1. Where the build stands (jar 13:20, commit 382541ec, all local - nothing pushed)

- **Council back on** (`threatinc_warCouncil` true; off for one morning, hw213 scratched by the user at 09:30).
- **N1 / N1b** (09:42 / 10:41): `IncursionManager.siegeCanPay` reads the hull pool and nets the yard hold off
  supplies; `ThreatPlays.invade` goes on past a refused system. Refused plays 0 in all eight games since.
- **Allied convoys + hull lending** (08:40): every willing ally answers a stock shortage; a faction with its own
  noted hull shortfall lends none. Hull convoys still a quarter of all allied convoys (98-157 a game).
- **Leash loop fixed** (11:12, 911f71f0): a raider kept its garrison flag, was sent to rearm as a reinforcement
  AND seated home by the raider poll - 7,804 recalls a frame apart in hw217c. Strays 7-22 a game since.
- **Invade-pass summary line** (04656c80): `Council <f> invade pass: K clusters in reach (...), H of T hives open,
  started N, no paying base in M: {marines=.., cannot pay=.., ...}` - one a pass a faction.
- **The marine step** (13:20, the user's version): `ThreatPlays.payingBase` notes the marines a landing lacked;
  `ThreatCoreBuild.marineStep` raises Patrol HQ -> Military Base -> High Command before another yard while the
  note stands. Knob `threatinc_marineStep`. UNTESTED: hw221 (B) / hw222 (A) running from 13:22.
- **Harness**: `sbs.ps1` relaunches a tag whose launch failed once after the others (`Requeue`) - the second
  slot lost its window in hw217-hw220, a game a round. First live for hw221.
- **ALLIED STAGING A + CONVOYS ONLY** (13:55, the user: "yeah A is fine ... no teleporting please"; built 14:05,
  jar 13:42, `patch-allied-staging.pl`): the siege pools are OFF (`siegePoolMarines` / `siegePoolProvisions`
  false) - a siege draws its base's stock alone, brought by convoys; zealots sail as marine convoys; the invade
  pass stages one hammer a faction where nothing pays today (`threatinc_invadeStaging`). An ally's base at
  Welcoming+ (`ThreatCoalition.stagingHost`) is a staging base: in the clusters, every base pick, `planFor`'s
  "reached"; its stock the depot's, the guest's markets convoy to it (`ThreatConvoys.guestDonors`), a base stages
  several plays. hw221 scratched at d84 (the marine step fired 33 times); trial hw223 (B) / hw224 (A) from 13:43.
  Still pooled instantly (open for the user): link upkeep, founding kits, ordnance, hive-world feeding.
- **S1 - STAGING IS THE BASE'S COMMITMENT** (15:30, the user: "s1"; built 15:37, `patch-s1.pl`): hw225 / hw227 showed
  convoys only killed the human sieges (0 of 424 staged hammers filled; marines shipped base to base after each
  failure). A hammer's staging outlives the play (`PlayStaging.committed`, cleared by a siege sailing or 180 d), the
  wait window renews while the refused stock rises (`ThreatPlays.stockRising`), two staging hammers a faction, one a
  base (`invadeStagingMax`). Trial hw229 (B) / hw230 (A) from 15:37 DIED with the Claude session at 15:55 (hw229a w2,688, c w2,811); partial read in game-runs-2 s54 (churn halved, hammers still never fill - refused for marines); relaunched as hw231 (B) / hw232 (A) from 16:14, same jar. hw231 READ 16:50: hives 110 / 116 / 146, colonies 36 / 38 / 20 - the swarm's 3 of 3 with the sieges alive (filled 20 / 18 / 13, commitments met 37 / 20 / 38) - S1 CONFIRMED; hw232 (A) 17:20: hives 117 / 68 / 81, colonies 28 / 29 / 33 - the swarm's 3 of 3, filled 18 / 12 / 15. Six of six. Then hw233 (B) / hw234 (A) day-0 full wars from 17:20 (run-seeds-6.ps1, scheduled tasks), read with `check-hw231.sh hw233` + `colonies.sh`. hw233 READ 18:00: a 84 hives, b / c NEVER LEFT THE SEEDS (4 hives, 0 founded, 0 feeding sends - the day-0 regression, facts); hw234 the same 1 of 3; fix = feeding fabricates its carrier from the bank (ThreatFeeding.pickHiveFabricator / fabricateCarrier, patched 18:40, javac clean), build after hw234, then hw235 (B) / hw236 (A) day-0 on the new jar - BUILT 18:37 together with MARINES FOLLOW THE WAR FOOTING (the user 18:50: options 1 + 3; ThreatCoreBuild.marineLevy, the yard pause, reserveTroopSurplusMult 1.0 in all three stores), launched 18:37 as ThreatIncSeeds7 / WatchS7; read with `check-hw231.sh hw235`, `colonies.sh`, grep `Marine levy` / `yards paused` / `fabricated a`. The swarm took both hw227 games (88 / 176 hives, **hw235 (B, day 0, 19:24): hives 42 / 97 / 124, colonies 40 / 41 / 23 - the feeding carrier CONFIRMED (b / c founded 101 / 127 vs 0), the LEVY OVERSHOOTS (161 levies a game, factions end with 20-107k reserve marines vs 3-12k on hw233a; the want was the base's shortfall, the stockpile made every core world a fortress: a 15 landings held back, 171 strikes in orbit at 42k supplies/mo, spare -18k, 42 hives vs hw233a 84). FIX patched 19:45 (`patch-levy-net.pl`: want = landing need - the faction's reserve, coverage likewise), build after hw236 (A, ~20:15), then hw237 / hw238 snapshots (run-siege-30, tasks ThreatIncSiege30 / Watch30, disabled: enable, run, disable) and a day-0 B round vs hw235.** **hw236 (A, 20:08): hives 115 / 106 / 82, colonies 33 / 30 / 34 - the swarm's 3 of 3, carrier confirmed on A (founded 134 / 114 / 87), levy overshot (18-102k reserve marines) but the spare stayed positive. JAR 20:09:43 = the levy-net fix; hw237 (B) / hw238 (A) snapshots then hw239 (day-0 B) launched 20:09:54 as ONE chained task ThreatIncChain30 / WatchChain30 (launch-chain-30-8.cmd; ends ~22:00); read with check-hw231.sh hwNNN + colonies.sh, the levy signals: reserve marines near the want (3-12k), `holds X marines against its landing's want` quiet lines, held-back landings down, a's spare positive.**
  colonies 22 / 13) by the dead siege machine; expect OceanPena back toward hw219's contested 95 / 52.

## 2. The day's reads (hives / human colonies; b games lost their window in hw217-hw220)

| round | build | OceanPena | ZhiFarah | Aphelion | PoseidonDeimos | AresDaniels | AmaruDugas |
|---|---|---|---|---|---|---|---|
| hw211 / hw212 | yards first | 93 / 35 | 67 / 43 | 136 / 20 | 137 / 24 | 79 / 33 | 83 / 32 |
| hw215 | council + N1 | 70 / 33 | 91 / 38 | 189 / 12 | - | - | - |
| hw217 / hw218 | N1b | 86 / 46 | - | 131 / 33 | 112 / 37 | - | 83 / 40 |
| hw219 / hw220 | leash fix | 95 / 52 | - | 195 / 11 | 113 / 51 | - | **41 / 70** |

- The swarm takes Aphelion (131-195) and PoseidonDeimos (112-137) every time; OceanPena leans human and the
  humans grow there each round (33 -> 46 -> 52 colonies); **AmaruDugas went human in hw220c (41 hives, 70
  colonies) on a strategy-neutral build** after 83 / 83 / 91 - the opening's swing, and a human win unattended.
- hw215's Hegemony-core collapses (12 -> 5 / 12 -> 1) were the opening too: hw217 held 11 / 9 under the same
  93 strikes.

## 3. The idle navy = marines (12:30)

The Hegemony sits on 15-39k FP free with 13-44 council sieges a game because `payingBase` wants the whole
landing's marines pooled (1,100-2,700) and its markets raise 40-77 a month each (`ThreatReserves.accrualPer30`:
vanilla surplus x `reserveTroopSurplusMult` 0.5 + militia). 594 of 683 refused systems in hw219a's pass lines
were marines. The marine step (s1) is the answer the user chose; `reserveTroopSurplusMult` 1.0 is the knob
behind it if the buildings do not close the gap. Caveat the user has: the humans already lean in half the games.

## 4. Open for the user

1. **Allied staging + convoys only (built 14:05, s1): read hw223 / hw224** for `hosts ... (allied staging)`,
   `sails X -> Y (<host>'s base)`, `staged N` on the pass lines, 0 `pooled for` lines, the base count (the point:
   fewer chains), and whether staged hammers reach STRIKE or fail at 60 days waiting for a convoy fill
   (`councilPrepareDays` 30 + `councilStrikeWaitDays` 30). Then the remaining instant pools (link upkeep,
   founding, ordnance, hive feeding) are the user's call.
2. **Humans must not win unattended:** OceanPena x3 lean human, AmaruDugas won once - before the marine step
   lands. The swarm's answer is the next strategic question once hw221 / hw222 are read.
3. Carried over from the 18th: O1 bombard budget, guard need growing in transit, hive drop constant 0.9, set B
   round of the defenders build.

## 5. How to resume

- `scratchpad/seeds-hw174/`: `run-siege-N.ps1` (copy the last, change `$n`, `siegeNN.done`, the Log line),
  `check-hwNNN.sh` (sed the tag), `watch-siege.sh` (`for n in`, `siegeNN.done`, `IGN`), `read-hw208.sh hwNNN`,
  `tools/test-harness/reads/effects-ledger.sh hwNNN`. One `sbs.ps1` at a time; a round is ~32 min; the early
  check at +22 min reads the pass lines and `marine step` lines.
- Pull before testing: `git fetch origin main` was clean at 13:17 (ahead 69, behind 0).
- **Launch batches detached (2026-10-19 16:14).** A driver started from the session's shell (Bash `run_in_background`) dies with the session - hw229 did. Register the driver and watcher as one-shot scheduled tasks and run them: `schtasks /create /tn ThreatIncSiegeNN /tr '"<path>aunch-siege-NN.cmd"' /sc once /st 23:58 /f` then `schtasks /run /tn ThreatIncSiegeNN` (the .cmd redirects the driver's output to `run-siege-NN.out`; `launch-watch-NN.cmd` runs the watcher through Git's bash.exe). The task runs on the interactive desktop, so the window automation works. Poll `watch-siege-NN.out` / `sbs-status.txt`; nothing wakes the session.
