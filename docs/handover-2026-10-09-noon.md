# Handover 2026-10-09 noon - start here

The next session's first job: **put the questions in section 1 to the user and wait for the answers**
before building anything. Then section 2 (what is running and how to read it), section 3 (state of the
build), section 4 (the frozen-swarm bug, what was tried), section 5 (where to find everything).

Standing rules are in `CLAUDE.md` and the memory index; the overnight record is
`handover-2026-10-08-morning.md` (sections 1-29, one per batch hw60-hw118) and `game-runs-2.md` 43.

## 1. Outstanding questions for the user (ask all of them)

**Balance - the old sector (ck2, Aphelion/Dysnomia).** The new sector (ck5) is the swarm's every time
(115-244 hives against 0-8 human worlds, twice to the last colony). The old sector on ONE build
(hw111a-hw117a, same rules, the carry stopgap in all four) ended 89 / 21, 49 / 33, 44 / 32 and 151 / 6
hives / colonies: the opening decides (which yard world the first strike takes, which hives the first
sieges find), not the rules. Of the eradicated hives that could be placed, most sat in systems with 2-4
sibling hives: a human siege brings 1.5x the whole system's garrisons and the rally rule (only if enough)
keeps every other system home. Three shapes were put to the user at 11:50, none built:

1. **Regional relief (recommended).** A besieged hive draws garrison swarms from hives within fuel
   reach, sized to the siege it can see, sent only if the pooled force beats it (the humans' coalition
   relief of hw75, mirrored; keeps "nothing sails that cannot win").
2. **Counter-stroke the siege's base.** When a siege outweighs the system, strike the forward base it
   sailed from instead of rallying (trades the hive for a base).
3. **Found in depth only.** New hives only within relief reach of existing ones (slows the spread).

Question: which, if any? Or accept the old sector as a coin-flip on the opening and stop sampling it?

**counterGap** (7fb70a50, hw99+): the swarm's holding front is fed to the counter-attack line. It bends
the user's 2026-09-08 rule "the front is never stripped". Keep?

**The carry stopgap**: a reinforcement swarm still overdue at its second read (180 days) is placed 1,500
units from its target (`ThreatColonyManager.overdue`, kick 2+). It works every time; the cause of the
freeze is not found (section 4). Keep for good, or keep only until a cause is found?

**beltSafe** (`ThreatFleetComposer.beltSafe`, 8f6d8988): every fleet the mod builds or orders, both
sides, holds `$asteroidImpactTimeout` for good. It turned out moot (vanilla's belt impacts run only in
the player's location, and they were not the cause). Remove, or leave (harmless)?

**Older open decisions** (from `handover-2026-10-08-morning.md` 3 and the memory): the StarLord save
heal; the structures-before-foundings order; `hive-garrison-and-upkeep.md` at 43.5 KB (split?);
hw99a's hot economy; the four unbuilt human-side shapes of hw60 (defence-in-depth memory).

**Session hygiene:** the night's record is complete through hw118 (a1ef95c4). The user said at 10:00
"you've been going for 8 hours" - keep the batches to the question asked and report in tables.

## 2. What is running now and how to read it

- **hw119a (ck2) + hw120a / b (ck5)** launched 11:46 from jar 11:19 (= commit a1ef95c4's src): the
  carry at the SECOND overdue read, and the overdue read adds `velocity x/y`, `facing`, and
  `from <hive> N units off (radius R)` (the source hive's planet). Batch ends about 12:35.
  Logs `/tmp/threatinc-tests/ti-hw119a.txt` etc. (Git Bash `/tmp`); status
  `/tmp/threatinc-tests/sbs-status.txt`; done marker `sbs-go.done`; dumps under
  `tools/warsim/validation/<tag>/`.
- **Read it:** `perl tools/test-harness/reads/endread.pl hw119a` (end figures per run); the frozen-swarm
  tallies are the perl one-liners in the session's scratchpad `watch-hw119.txt` / the monitor commands
  (group overdue lines by `id <fleet id>`, a pair at the same unit of position a span apart = frozen).
- **Record it:** copy `tools/test-harness/reads/record-template.pl` (it writes the `game-runs-2.md` 43
  bullet after the previous batch's, the handover section, the `swarm-defence.md` paragraph, two
  `facts.md` lines and the memory), run with `<mod root> <memory dir>`, then commit the docs, the three
  dump folders and the jar, and push. Every batch so far is committed this way.
- **Next batch:** build with `powershell -NoProfile -ExecutionPolicy Bypass -File
  tools/test-harness/reads/compile-check.ps1` (writes `C:\Users\zuzam\AppData\Local\Temp\claude\chk.jar`,
  never the live jar - the game locks it); `tools/test-harness/reads/launch-template.sh` swaps chk.jar in
  (refuses while java.exe runs) and launches three games from the checkpoints (`-Bases` names them:
  `save_AphelionDysnomia_4103534775338436064ck2`, `save_AmaruDugas_2921423183749615243ck5`). Watch with
  `watch.sh <hwNNN> 70 <ignore list> check-whereabouts.sh` as a background task, one per sector.
- **The page:** https://claude.ai/artifact/2foTMjHYyEDfpcJuCCiDgr (version 16, hw60-hw114):
  `runs.pl <any dump> <tags...> > all.json`, `data.pl < all.json > data.js`, splice at `/*DATA*/` in
  `template.html` (edit its title / lede / `BATCH` map), publish to the same URL.

## 3. State of the build (jar 11:19 = a1ef95c4 src, all pushed)

Since the morning summary (`handover-2026-10-08-morning.md` 3): the four recycling loops closed, the
garrison fights as one (muster), swarms sized to the deficit (rowFor), the front fed to the counter line
(counterGap), overdue reinforcements re-ordered then carried, the whereabouts read, beltSafe, the frame
pulse (`ThreatColonyManager.FramePulse`, a test instrument - entity scripts run only in the player's
location, so it reads nothing; remove when done), `REINFORCE_AT_KEY` / `REINFORCE_FROM_KEY` (the send
position and source hive), fleet ids and days out on the overdue and arrival log lines. No exception in
any batch since hw85. Results per batch: section 1's table and `game-runs-2.md` 43.

## 4. The frozen-swarm bug - what is known, what was tried (hw101-hw118)

Fact: in every run, 6-18% of reinforcement sends (7-9% of overdue reads) are swarms that **never move a
unit from the spot they were sent from** (`moved 0 units since the send`), always inside an asteroid belt
or ring, velocity at full burn, a heading set, no battle, no burn modifier, no ability, orbit null, listed
in their location, `transition false`, `alive true`, `station false`, `aimode true`, listeners 0. Some
creep (under 2,000 units a span). The source hive's planet is often 20,000+ units away, so the swarm was
already standing there when picked from the garrison list. Frozen swarms sit mostly 3,000+ units from any
jump point, and a third freeze inside the target's own system - not a jump-arrival artefact.
hw119 first reads (12:04): the source hives of frozen swarms read as tiny bodies - `from Alpha Laphirial VI
23879 units off (radius 49)`, `Beta Cormoran I ... (radius 49)`, `Knossos ... (radius 45)` - worth a check whether
every hive planet is that small (a station or asteroid-sized body) or only the ones whose swarms freeze.

Tried, in place, each a batch, none frees more than half (by fleet id a span later): a fresh travel order
(hw105), a fresh fleet AI (hw107), beltSafe (hw111), a zeroed velocity (hw115: 11 free / 6 creeping /
4 still of 21), a one-unit nudge (hw115: 2 / 14 / 0), a cleared nav avoid list (hw117: 3 / 19 / 12), a
500-unit hop along the heading (hw117: 4 / 7 / 0). **The carry** (remove from its location, add to the
target's, `setLocation` 1,500 units from the target, velocity zero) frees every one; carried swarms
arrive within days. The crawlers at burn 2 are a separate, benign population (vanilla sneak burn near
human fleets; 39 of 39 read twice had moved 10,000+ units). Vanilla sources read: the belt and ring
terrain plugins (nothing but the keyed-off `AsteroidImpact`), `Misc.isSlowMoving` / `getGoSlowBurnLevel`,
`LocationAPI.addScript` ("locations that are not current may run at a lower number of frames per
second"), the fleet AI interfaces (`NavigationModulePlugin` exposes avoid / destination only).
Not read: the obfuscated `CampaignFleet.advance` and `ModularFleetAI` (a decompile would settle whether
a non-current location integrates position from velocity at all for a fleet in some state).
hw119's velocity-vector read (section 2) tells a dead stop (vector held) from an oscillation (vector
flips). Docs: `swarm-defence.md` "Overdue reinforcements", `facts.md` "Why do hives fall with the fund
full".

## 5. Where everything is

- Records: `game-runs-2.md` 43 (one bullet per batch), `handover-2026-10-08-morning.md` 1-29,
  `facts.md` (the standing one-liners), memory `navy-fits-supply-and-use.md` (the night's thread).
- Scripts: `tools/test-harness/reads/` (copied from the session scratchpad at noon: `watch.sh`, the
  `check-*.sh` chain, `endread.pl`, `runs.pl` + `data.pl` + `template.html` for the page,
  `launch-template.sh`, `record-template.pl`, `compile-check.ps1`). The session scratchpad
  (`C:\Users\zuzam\AppData\Local\Temp\claude\C--Program-Files--x86--Fractal-Softworks-Starsector-mods-ThreatIncursion\0a3019c9-412b-59c0-902a-676c31dc3566\scratchpad`)
  holds the per-batch launch and record scripts and the watch logs; the vanilla API sources are unzipped
  under its `api-src/` (from `starsector-core/starfarer.api.zip`).
- Logs kept: `/tmp/threatinc-tests/ti-hw1NNx-keep.txt` for hw109-hw118 (the launch script copies the
  previous batch's logs before a new batch overwrites them).
