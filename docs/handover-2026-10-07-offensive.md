# Handover - 2026-10-07: the offensive, continued on another computer

For the session picking this up on a machine without this one's memory. Read `CLAUDE.md` first, then
`strikes-staged.md` (the offensive, 2 and 3), `game-runs-2.md` 42 (what six batches showed), `facts.md`
Decisions of 2026-10-06/07. The user's standing rules are in section 6 - they do not travel otherwise.

## 1. Where it stands

Built today, all committed, pushed with this file:

- **`ThreatOffensive`** - the swarm's grand strategy, replacing the one-strike-a-month pass (`swarmOffensive`
  on). Monthly: one prong a known target system, priced by `stagedPlan` for the answer it expects; the campaign
  is the most valuable set the strike fund + fuel + supplies pay (fund and fuel by the deadline, supplies as
  `canSustain` allows); it saves until paid, then launches every prong in one pass, the farthest first and
  the rest held to arrive together (`poll`, daily). Losing: targets within `offensiveNearLY` 10 only, on a
  3-month horizon, falling back to all known when nothing is near. Relief strikes at once; the 8,000 FP
  opening floor per strike (the user's rule) and `strikeMinSize` 4 (kept at the user's word) unchanged.
- **Sized for the answer** (the user: "the threat wouldn't know what that is unless it knew all worlds"):
  `expected` = (defence seen + the faction's navy seen elsewhere, `ThreatSwarmIntel.knownNavyFP`, shared among
  the campaign's prongs at that faction) x `responseRatio` learned from what earlier strikes met
  (`ThreatStrikeFGI.noteMet` / `reportMet` -> `ThreatSwarmIntel.noteMet`). **The learning never reported in
  hw47** (23 strikes ended, no line); the report now works from what was captured at launch
  (`setExpected(fp, target)`) and logs `Swarm intel: a strike sized for N ended with no read of what it met`
  when it skips. Unverified - the first thing to read in the next batch's log is whether
  `Swarm intel: a strike at <faction> sized for X met Y; its answer is now Rx` appears.
- hw47 (daa6bdd7, second sector): **humans 3-0** by eradication. The mechanism worked; the economy bound it:
  the hives banked 0 supplies the whole run, so after the 8-10k opening (lost each time at Chicomoztoc) every
  campaign was 1-3 prongs of 1-7k. The swarm paid the same supplies to keep a 20-30k FP navy at home.

The jar in `jars/ThreatInc.jar` (built 11:30) is the current source; `git pull` is enough. Rebuild only if the
source changes (`compile.ps1` out of process, game closed, check the jar's timestamp).

## 2. The user's instructions for the next step (2026-10-07, this handover)

**(1) Spend it - yes.** The swarm's navy above its patrols is to be spent on the offensive rather than sit at
home paying supplies. Build it as: the offensive's prongs take the hive's spare Defense Swarms first and the
fund builds only what is missing. The knob for this exists and is off: `strikeStagedGarrisons`
(`IncursionManager.stagedPlan`, `stagedSpares`: every hive system's spare above `ThreatPosture` reserve,
nearest the staging system first, within `strikeStagedGatherLY` 0 = all). What to check before turning it on
for the offensive:

- `stagedPlan` with spares takes **every** spare fleet the fuel, supplies and bank pay ("the mass, not a match
  of the defence") and only then builds to the margin. For a priced prong (bankLimit `Float.MAX_VALUE`) that
  would make every prong the whole hive's spare. Change it so a prong takes spares only up to its `need`
  (the expected answer x margin), in the order `stagedSpares` gives, then builds the rest - and so a campaign
  of several prongs does not count the same spare fleet twice (`ThreatOffensive.price` must pass each prong
  the spares the earlier prongs left; `StagedPlan.from/counts` says what each took).
- A held prong (`schedule()`) must reserve its spare fleets too, or by its day they may have been spent; the
  simplest honest rule is to take the spares only for prongs that sail today and let held prongs build from the
  fund, or to re-plan a held prong on its day (it already re-runs `launchStrike`, which re-plans).
- The spare is what `ThreatColonyManager.garrisonAvailableForLaunch` leaves above the reserve - the patrol
  table (`ThreatPosture.minimumFP`) stays home; that is the parity rule (`hive-garrison-and-upkeep.md`).
- Expect the navy charge's bill (`payNavySupplies`) to fall as the spare leaves, which is the point.

**(2) "Losing" must be nuanced - not one lost opening, not hives falling as a one-off.** The user's words: "it
should be more nuanced. like how would you evaluate if the swarm is actually losing? likely more a series of
worlds falling not just a one off etc". Today `ThreatStance.losing()` reads the stored stance pass: consolidating,
or the exchange ledger lost (`SIGNIFICANT_LOSS` 5% of held FP, more lost than sunk, decayed over 60 days), or
fewer hives than 90 days ago - and hw47 flipped to losing on its first opening (8k lost, 0 killed), so the swarm
turned cautious after its first blow. A design to propose and build, the user to confirm the shape:

- A **trend over a longer window**, not a snapshot: hives lost per year against hives founded per year
  (`ThreatStance.hiveTrend` keeps 90 days; keep 365), and the exchange ledger over the same year.
- **A series**: losing means hives have fallen in two or more of the last N windows (say three 90-day
  windows), or the net hive count is down over the year - never a single loss. One eradicated hive, or one
  strike lost, is the cost of war.
- **Weighed against the swarm's own means**: losing when the losses outrun what the forges replace (the
  exchange lost over the year > a share of the fund's income over the year) rather than any fixed FP.
- **Degrees, not a flag**: `losing()` could return a 0-1 pressure (share of hives lost, exchange ratio) that
  the offensive uses to shrink its radius and horizon gradually (`offensiveNearLY` x (1 - pressure), horizon
  between 12 and 3 months) instead of a cliff at 10 ly / 3 months.
- Keep "losing is a war's verdict" (no war, not losing - built today) and the fallback to all known when
  nothing is near.

Record whatever shape is chosen in `facts.md` Decisions the same turn, and as one line in `ThreatStance.losing`'s
javadoc.

## 3. Then run

Both changes in one batch, three games, on the second sector, where the result is readable (hw47's 3-0 is the
baseline). The command, from the repo root, game closed (`sbs.ps1` kills any that is open):

```
powershell -NoProfile -ExecutionPolicy Bypass -File tools\test-harness\fastforward\sbs.ps1 -Tags hw48a,hw48b,hw48c -Days 3750 -MaxMinutes 240 -Base save_AphelionDysnomia_4103534775338436064
```

The pristine save is now in the repo: `tools\test-harness\saves\save_AphelionDysnomia_4103534775338436064`
(copy it into `Starsector\saves\` once; never load and save it by hand). The first sector's save
(`save_AmaruDugas_2921423183749615243`) is beside it. Machine setup (mods, LunaLib store, foreground rules) is in
`handover-2026-10-05-testing.md` 2 and `testing-harness.md`. **Ask the user before launching** - the leave to
relaunch today was for the offensive's test and ended with hw47.

Reading a batch: `sh tools/test-harness/batch-read.sh hw48` (Git Bash) gives one screen per game; the offensive's
lines are `Offensive: saving for ...`, `Offensive launched: ...`, `Offensive: the prong at X sails on its day`,
and the learning's `Swarm intel: a strike at <faction> sized for X met Y`. Read at m55-60 and report; the full
m125 for the end state. Logs are `%LOCALAPPDATA%\Temp\threatinc-tests\ti-<tag>.txt`; copy them to `-keep` before
the next batch. Record the batch as `game-runs-2.md` 43 (the shape of 42) and update the fact "What paces the
swarm's strikes" in `facts.md`.

What to judge it by: the swarm's own score (foundings, size-ups, worlds destroyed - `run-scoring` in memory,
never a mutual score); whether the fund's spending matches its income; whether campaigns are multi-prong once the
spare garrison is in them; whether the answer ratio moves after the first campaign; and whether "losing" now
holds through one lost opening.

## 4. Open after that (raise, do not decide)

- Yard-count parity: a forge on every hive against 1-2 Heavy Industry per faction (hw41 on the first sector was
  swarm 6-0 by spread on that).
- The 70% of fabrication that becomes garrisons (`hiveSurplusMult`, the fund share 0.3): with (1) built the
  garrison is spent; whether the share should rise is the user's.
- A scout's defence read of a core world is its patrols on the day (74-290 for Gilead/Yama); the sizing now
  adds the faction's seen navy, but the read itself is still the day's.
- Release re-tag of v0.8.0, forum changelog, zip - only on the user's say.

## 5. Commits today, newest first (all on `main`)

`4430374c` hw42-47 recorded, met-report fix; `daa6bdd7` supplies as canSustain allows; `445bb134` supplies
budget, losing needs a war; `9a64880a` sized for the expected answer; `f25156ae` pricing past the live gates;
`13ed59c8` fuel summed; `8f5a2f01`/`03baaaf3` arriving together; `7792a9c8` the offensive; `f1125311` the hive's
navy above its patrols pays supplies; `386db992`...`5eeb5ff2` the cut-back and hw38-41.

## 6. The user's standing rules (memory does not travel)

- Never launch the game or the harness without asking. Never save on a cloned save. Never delete
  `Starsector\screenshots`. Push only when the user says.
- Both sides play to win; no cheating either side; the swarm is not omniscient - it knows what it has seen,
  information travels by ship. No arbitrary caps; a closed economy - every fleet paid from production. Parity
  of rules between sides (innate patrols free both sides; only built navies and mod actions draw on stocks).
- Balance values and the swarm's strategy are the user's call: build what is asked with defaults as knobs, rank
  trials by each side's own score, verify in the game, record. Decisions go in `facts.md` Decisions the same
  turn. A bug is fixed with the mechanic it hinges on; no unrelated rebalancing. Issues come with solutions.
- Find code by grepping `docs/facts.md` then `docs/symbols.md`; never a code-mapping agent. Build out of
  process (`powershell -NoProfile -ExecutionPolicy Bypass -File compile.ps1`) with the game closed and check
  the jar's timestamp. `python` is a Store stub: Perl, PowerShell or the Edit tool. Files are mixed LF/CRLF
  (`ThreatStrikeFGI.java`, `ThreatFuel.java`, the docs' `facts.md`, `code-map.md`, `game-runs-2.md`,
  `LunaSettings.csv` are CRLF) - a Perl `s///` that silently fails is the tell; use the Edit tool.
- Player-facing text: current values, one line a fact, never fluoro green; notifications through
  `ThreatNotice`. Screenshots are read by subagents, never in the main thread.
- Commit messages end with `Co-Authored-By: Claude <noreply@anthropic.com>` and name no model.
- Unattended: never `AskUserQuestion` or `Workflow`; take defaults, report as you go.
