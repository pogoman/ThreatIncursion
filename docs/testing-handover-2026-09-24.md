# Testing handover - review fixes of 2026-09-24

Written 2026-09-24 afternoon. First harness run the same evening: results at the foot
("Run 1"); checks 3, 5, 6 (convoy side) and 8 still open.

**State of the tree.** `main` at 8da7a1a plus an UNCOMMITTED working set (`git status`:
13 source files, the LunaLib CSV, five docs, this doc, and one new class,
`ThreatScoutRoute.java`). `jars/ThreatInc.jar` is rebuilt from it and compiles clean. The set fixes the 15 findings of the code review over today's seven
commits (014a58b..8da7a1a) plus two lower-confidence notes. What each fix is and why is in
the docs it touched (`strategy-layer.md` "ONE SIZING" and the full-strength paragraph,
`player-aid.md` bounty bullet, `design-theory.md` 8.6, `code-map.md`) and in the memory
note `review-fixes-2026-09-24`. This doc is only how to prove them.

## Before you start

- **Debug logging on**: `threatinc_debugLogging` (LunaLib "Debug & Testing", or
  `data/config/settings.json`). Every check below reads `[ThreatInc]` lines from
  `starsector-core/starsector.log`. The log is appended across launches: take the LAST
  matching line, or note the timestamp before the run.
- **Which save**: any save from today's jar. For check 1 you specifically want one that was
  LOADED at least once under a jar built from 801240d or later (the wrapper build) - that is
  the save whose creator list has already grown. Instant War on a fresh save
  (`testing-harness.md`, "Instant war") stands up everything checks 2-6 need.
- **Harness or hands**: `tools/test-harness/` (`testing-harness.md`) or Continue, E, Major
  events, The Abyssal War. NEVER save on a cloned save folder unless its `saveDirName` has
  been fixed (same doc, 2026-09-05 section).
- **Rebuilding**: only if you change code. Close the game first; it locks the jar.

## Checks, in order

Each check: what to do, the line or screen that proves it, what failure looks like.

### 1. Mission creators stop multiplying (`ThreatMissionFilter`)

Load the save; quit to the main menu; Continue again.

- First load of a save the wrapper build touched: exactly one line
  `Vanilla mission creators filtered: N kept, M duplicate(s) dropped`, M >= 0.
- Second load: NO such line. `install()` logs only when it changes the list, so silence is
  the pass. A save the wrapper never touched logs once (plain creators swapped) then never.
- Failure: the line on every load, or `kept` climbing between loads.

Then play a few days. `Vanilla mission from a hive world dropped: <intel class>` may
appear; the intel screen must never list a survey or analyze mission issued by a Threat
world, and survey/analyze missions from human worlds must still appear (the filter must not
eat them all).

Save portability: save once with the mod, open `campaign.xml` in the save folder and grep for
`ThreatMissionFilter`. Expect nothing; the creators serialise under vanilla's class names
(`AnalyzeEntityIntelCreator` etc.). A `threatinc.ThreatMissionFilter$AnalyzeEntity` in the
XML means the alias did not take.

### 2. NPC siege sizing and gates (`IncursionManager`)

Needs a mobilised NPC faction with a military base in reach of a KNOWN hive (its scouts
found it, or Instant War). Each incursion poll logs, per hive system the faction could
siege:

```
Siege sizing vs <system>: N fleets, ground str ~X against Y needed, ~Z FP against W FP of Defense Swarms
```

followed by one outcome:

- launched: campaign message "<Faction> has launched a siege expedition into the ..." and
  `<faction> purge expedition vs <system> (... difficulty D)`;
- outweighed: `Expedition postponed at <base>: Z FP against W FP of Defense Swarms over <system> (takes at most K)`
  and `Swarm bounty posted by <faction> on <system> (siege takes K FP)`;
- short of marines: `Expedition postponed at <base>: a of b marines available (need c)`;
- short of provisions: `Expedition postponed at <base>: f/F fuel, s/S supplies pay for P of the Q points (needs M)`.

What to prove:

- **(a) One sizing.** A base that logs the outweighed line and has a running bounty on that
  system must also, within `softenIntervalDays` (30), log
  `Hunting force from <base> to <system>: ...` - or one of the honest reasons it cannot:
  `Hunting force stays at <base>: ...` (floor above `softenMaxFP`) or
  `Hunting force waits at <base>: pays for ...` (depot). Failure: the outweighed line every
  poll, no hunting line, no reason line. That was the lock-out.
- **(b) Provisions floor.** In the provisions line, `needs M` is at least the points of the
  fleets that reach the ground need and the orbit; when convoys refill the depot the siege
  sails, possibly after `Expedition trimmed at <base>: a -> b fleets for ...`, and b is never
  below those leading fleets.
- **(c) Safety net silent.** The line
  `... supplies pay for X of the Y ground strength the siege sails with` must NEVER appear.
  It holds by construction; if it shows, the floor maths is wrong - report both lines.
- **(d) Capped flotilla sails.** When `Siege sizing` shows `ground str ~X` below `Y needed`
  at N = `siegeMaxFleets` (10), the siege must still launch. Before the fix it logged
  `pays for X of the Y ground strength the target needs` every poll, forever.
- **(e) Heavy escort is system-wide** (optional). A system holding a defended hive above
  `purgePreemptMaxSize` shows one fleet more in `Siege sizing` than a system of preemptive
  targets only, whichever colony's cooldown fired.

### 3. A hunt outlives its colony (`ThreatSoftening`, `ThreatFleetOrders`)

Needs a hunting force (NPC, or the board's Hunt) over colony A in a system whose colony B
also fields swarms. When A's swarms are gone: `Hunting fleet moves on to B` (and, for the
player, "moves on to hunt the swarms over B"). When A is DESTROYED while B still has swarms,
the same line must appear - the failure is `Order stood down (the swarms are gone): ...`
with B's garrison still in orbit. Forcing A's death: let a siege win it, or edit a cloned
save. Board: after the move the fleet row reads "en route" until it reaches B, then
"hunting" (before the fix it read "hunting" the whole way).

### 4. One bounty per ship, once per fleet (`ThreatSwarmBountyIntel`)

Two running bounties (two outweighed bases, systems X and Y). Catch a Defense Swarm of X
inside Y (garrison swarms roam; `ThreatRaiders` sends them after convoys). Expect ONE
`Swarm bounty paid on <X's system> (your fleet): ...` per battle, never a second line with
the same `FP destroyed` for the same battle.

Re-hunt: detach a fleet to Hunt from the fleets table, recall it, detach it again, fight.
Expect one `Swarm bounty paid ... (<fleet name>)` per battle, not two identical lines.

### 5. Refund after a re-hunt (`ThreatReturns`)

A player task force back at visibly reduced strength, say half, is detached to Hunt and
later comes home. `Return settled at <home>: ... (strength ~50%)` - launch strength, not
100%. A Defend fleet still stands down at `defendMinStrength` as before.

### 6. Player convoys never sail over the ledger (`ThreatConvoys`)

A player colony with little free FP (colonies table, FP free/capacity). Stage or Supply a
load from it. Expect NO `Convoy fitted: +N hulls ...` line for a player convoy (NPC convoys
still log it); the convoy's FP on its fleets row is at most what the ledger charged; FP free
never goes negative. `Convoy dispatched: player ...` may carry less than the ask - what the
bought hulls hold - and the reserve is drawn only for what boarded. Failure: `Convoy fitted`
on a player convoy, or a convoy fleet above its ledger entry.

### 7. War footing tooltip (`WarFootingCondition`)

A War-footing colony with a local shortage: hover the commodity line. "Short N; cover due"
now prints the LOCAL deficit, the number the cover then lifts availability by. Cross-check
against the colony screen's demand. Same for the two "depot too low to issue" lines.

### 8. Staging-target memo (`ThreatConvoys.stagingTargets`)

On the board, note a player staging base's convoy target figures; press Stage or Siege
(both change its free FP). The re-rendered board, same paused frame, shows the new figures.
Planner behaviour unchanged: `Convoy dispatched` lines as before. The slow tick should be
no slower with many colonies; it should be faster.

### 9. Scouts still walk (`ThreatScoutRoute` refactor, behaviour unchanged)

Same lines as before: `Scouting party of <faction> from <home> (lead|sweep): [...]`,
`Scout of <faction> home|lost`, `Scouting Swarm from <colony>: [...]`,
`Scouting Swarm charted <system>`, `Scouting Swarm home|lost`. A save with scouts already
out must load and those scouts must keep moving (their fields moved to a superclass; a
scout stuck at its first stop after load, or a load error, is the failure). Hive reveals
still land ("... scouts have found ...").

### 10. Text

Detach confirm: "Detach <fleet> to hunt the Defense Swarms in the <system> for N days?"
and nothing else. LunaLib: "NPC Sieges at Full Strength" and "Expedition Provisions Floor"
descriptions read as intended.

## Load risks to watch on the first Continue

- New persisted fields `Order.systemId` and `Order.credited` default to null/false; scout
  records gained a superclass. A load failure shows as a vanilla error dialog during
  "Loading stage"; `starsector.log` will name a `ConversionException` with a `threatinc`
  class. Report the class and field.
- Leftover `ThreatMissionFilter` wrappers in an old save are unwrapped by `install()`; if the
  first load throws inside `GenericMissionManager`, that is the place.

## Isolating a failure

Knobs that switch a fix's mechanism off without touching the others: `npcSiegeFullStrength`,
`npcSiegeOrbitGate`, `softenEnabled`, `expeditionMinProvisionsFraction` (0 = no floor). Report
the `[ThreatInc]` lines around the event with the in-game date.

## When it passes

Commit the working set as one "Review fixes" commit, then update the memory note
`review-fixes-2026-09-24` (drop "NOT committed, untested") and this doc's first line.

## Run 1 (2026-09-24 evening, harness, laptop panel)

Clone `save_StarLord_1371204341708796700rf` of the `tt` clone (saveDirName fixed, pretest
copy beside it), debug logging on for the run, about nine campaign months over three launches.
The original save and the launcher prefs were never touched; both restored afterwards.

- **1 PASS.** First load: `2 kept, 0 duplicate(s) dropped` (the save predates the wrapper, so
  the plain swap). After F5 and a relaunch: no line. The saved XML has no
  `ThreatMissionFilter`; the creators are under vanilla's names. Hive-world survey/analyze
  missions are dropped (log), and a Hegemony "Analyze Derelict Ship" still lists.
- **2 PASS (a-d; e not looked at).** Outweighed bases post bounties and log
  `Hunting force waits at <base>: pays for N FP` on every poll (a); Chicomoztoc later sent a
  551 FP hunt. Nachiketa logged `pays for 14 of the 48 points (needs 48)`, then convoys
  refilled it and it sailed at 900/900 marines, 5428/5428 fuel, 4800/4800 supplies, untrimmed (b).
  The safety-net line never appeared (c). Its 5 fleets are the minimum: 4 give 960 FP
  against a 963 FP orbit gate. (d) did not come up: no capped flotilla was short of ground strength.
- **3 NOT PROVEN.** The one NPC hunt (over Surgat) stood down "badly hurt" before its target
  died. The player's 212 FP hunt on Delta Mengryla (2230 FP of swarms) was destroyed.
- **4 PARTIAL.** A battle paid exactly once: `Swarm bounty paid on Delta Mengryla system
  (Hunting Force): 3000 for 25 FP destroyed`. The cross-system and detach/recall/re-detach
  paths were not staged.
- **5 NOT RUN.** Never got a surviving reduced player force. NPC `Return settled at
  Chicomoztoc ... (strength 1%)` reads the launch strength.
- **6 PARTIAL.** With Haven at 4/216 FP free, every Supplies/Fleet button is disabled ("No
  colony of yours can spare a load..."), so nothing can sail over the ledger. No player
  convoy sailed, so the fitted-hull side was not seen.
- **7 PASS, plus two fixes.** Coatl: `Fuel: 0 banked. Short 1 unit` matches its colony
  screen (1 red unit). FIXED: that line also said `2,250 kept for the garrison` with 0
  banked; the garrison branch now needs stock and prints what it actually keeps. FIXED: a
  player colony read `Short 0 units; the stockpile is covering`, because the deficit is taken
  after vanilla's cover lifts availability. It now prints the units the cover lifts
  (`localDeficit`, verified: "Short 2 units"). `deficit` is unchanged, so allies still
  aid only uncovered shortages.
- **8 NOT RUN** (needs free staging FP).
- **9 PASS, plus one fix.** Scouts saved mid-route resumed after load: the swarm scout went
  from leg 2 to 4 and home; Hegemony scouts reported home. No load errors. FIXED: a Hegemony
  sweep had vanilla's abyssal "Unknown Location" (DEEP_SPACE) as a stop, and its GO_TO had
  run 132 days without arriving. Sweeps now skip abyssal, hidden-theme and cut-off systems,
  and any leg not reached in the new `scoutLegMaxDays` (60, LunaLib "Scout Leg Limit")
  is skipped. New persisted field `Party.legSince`; 0 on old saves means the limit starts
  at the first poll. Verified: `Scout of hegemony gave up on Unknown Location` about
  60 days after load.
- **10 PARTIAL.** Detach prompt checked in source only (`ThreatFactionView` 1619: the
  question alone). Both LunaLib descriptions match what the run showed.
- **Load risks:** none. Three loads, no `ConversionException`.

Observed, not investigated: the player's credits fell from 833k to 48k over the nine
months, with Rockslime at -11,686 a month while Blockaded (-60% access). Pelephanar logs
its identical sizing and postpone pair six times per poll (once per colony).
## Run 2 (2026-09-24 evening, Saturn Hadean "staging" save)

Clone `save_SaturnHadean_8807243588242812142hr` (game date 225, mobilised, five High
Command colonies, about 1,100 FP of player task forces). The save needs **Officer Extension**
(`officerextension.campaign.ModifiedPromoteOfficerIntel` otherwise fails to resolve on load).
It was enabled for the run and `enabled_mods.json` restored afterwards. On the current jar
the save migrated cleanly: planetary shields, marine arming, mission filter, no errors.

- **10 PASS in-game.** Hunt on a commissioned Detachment's row asks exactly "Detach Detachment
  to hunt the Defense Swarms in the Gamma Hero system for 60 days?".
- **4 re-hunt: not reachable as written.** A recalled hunter's "Returning" row only offers
  Hunt when `fromSystemId` is a live hive system, i.e. when it was recalled INSIDE the hive
  system. Recalled en route, the row has no Hunt. Gamma Hero had no bounty, so nothing paid.
- **5 indicative.** Staged task forces folded into a hunt settled at `strength 81%` and
  `78%`, and the Detachment detached then recalled settled at `71%`: all against launch
  strength, none reset to 100%. The clean "detached at ~50%, hunts, comes home" case was not
  staged.
- **3 still not proven.** In this late save (phase 3, fabrication x1.8) a 352 FP hunt, all
  four staged task forces folded in, was destroyed at Gamma Hero II (size 4, 5 swarms,
  reinforced from Corwin and Beta Vuzgrimeti within days). The player's 900-marine Gamma Hero
  siege aborted with 0 ground actions; the Threat destroyed the outpost and founded Gamma
  Hero I. Proving 3 needs a save edit: strip one colony's Defense Swarms under a live hunt.
- **8 not run.** Sun Wukong has no siege target after the abort, so its row has no
  staging-target line, and board-row tooltips did not appear under the hover helper.
- Not a bug: Sun Wukong read `398/261` FP. Staged task forces from Earth and Ice Wind Desert
  guarding it count at the host (fleet-staging design), and the hunt folded them in.
## Run 3 (2026-09-24 night): long run and old-save load

**Long run PASS.** On the Saturn "staging" clone (`...hr`), about 21 in-game months: no mod
exceptions, the save steady at 13.4-14.2 MB (no growth), and a steady ~0.6 days per real
second under Shift. The player fleet was parked in deep hyperspace and given supplies in the
clone's XML, because accidents and encounters kept pausing the clock. `starsector.log` rolls
over at 50 MB, so a harness counting lines from an offset must detect the rollover.

**Old save PASS.** The data-version-3 save "postbomball" (`...5585686433998629287ov`, 30/08)
loaded under the current jar and migrated: shields, 20 retired Fragment Fabricators stripped,
marine arming, mission filter. It ran ~130 days and saved twice with no errors.

**What the war did in those 21 months** (subagent read of the [ThreatInc] lines):
- The war is one-sided. The Threat launched 59 strikes, founded 9 colonies, won 5 ground wars
  (4 seeded as hives) and never lost a world. Swarm FP over hive systems kept climbing, e.g.
  Isirah 2.6k -> 19k.
- One NPC siege sailed in 21 months, against 679 postponements (652 of them "outweighed"). A
  base's ceiling is 10 fleets, ~2,400 FP, and the orbit gate faces 10k-29k FP of swarms, so
  it can never open. The one that sailed caught Galatia's orbit momentarily empty, then died
  when the swarms came back.
- Hunts: 70 launched; 59 of 62 stand-downs were "badly hurt". Gilead logged "pays for 0 FP"
  on all 23 polls (its depot never refills). All 97 bounties paid 0 (the player was idle).
- Garrisons overshoot their cap. `Reinforcement arrived at Norvia (5/3)`: arrivals
  (`ThreatColonyManager` ~3027) and returning raiders join without a cap check. That inflates
  the orbit FP the siege gate reads.
- A strike ping-pongs between its sweep order and the siege leash: 9 recalls in 2.6 s
  (`ThreatFleetOrders` ~1490).
- Log noise: `Siege sizing` + `Expedition postponed` repeat once per colony per poll with
  no cooldown (~14% of the log), and the daily "braces for a counter-attack" countdown.
- Cosmetic: raw market ids in "Threat front dispersed at raesvelg" and "Convoy recalled".
- FIXED: "Recycled weak Defense Swarm" always logged 0 FP (it read the fleet after
  `despawn`).
### Fixed after Run 3 (jar rebuilt, untested in game)
- Garrison overshoot: a reinforcement or raider coming home to a full garrison (live >=
  table head-count) is absorbed. Its hulls join the standing swarms with room, weakest first,
  capped by vanilla's `maxShipsInAIFleet`. What fits nowhere is recycled.
  `ThreatColonyManager.absorbSurplus`, called from `checkReinforcementArrivals` and
  `ThreatRaiders`. Log: `Surplus swarm absorbed at X: n ships (a of b FP) into k swarms`.
- Strike/siege leash ping-pong: the leash now anchors on the world the raid's sweep has the
  fleet heading for (`ThreatFleetOrders.anchorWorld`), and leaves a fleet alone while any
  assignment targets that world.
- Log noise: `Siege sizing`, `Expedition postponed` and `Hunting force waits/stays` go
  through `ThreatIncConfig.logQuiet`. They log when the wording changes (numbers aside) or
  once per 30 days.
- Names: fronts (`dispersed`, `stranded`, `evacuated`) and `Convoy recalled` fall back to
  the planet's name when the market has left the economy.
- A dead order fleet now logs `Order lost (fleet destroyed)`.
- Verify: grep `Surplus swarm absorbed` and check no `(n/m)` with n > m; grep `recalled to the
  siege` rate; grep `Order lost`.

### Hunt and siege-gate rework (built after Run 3, jar rebuilt, untested in game)
The user's call (2026-09-24): build all of it, then test. The detail is in
docs/strategy-layer.md ("Hunting forces", and the orbit gate paragraph).
- Hunting forces pool the faction's bases, count the warships actually built, gather at
  the hive system's hyperspace anchor, go in together, re-check the odds before moving on,
  and retreat as one force. Upkeep moved from the 30-day tick to the half-day poll.
- Knobs: `softenMargin` 1.0 -> 2.0, `softenMaxFP` 4000 -> 12000, new `softenPool` (on)
  and `softenMusterDays` (15).
- NPC siege gate: weighs the strongest single world's swarms (`siegeOrbitFaced`,
  `npcSiegeOrbitPerWorld` on). The swarm bounty's readout uses the same figure.
- Verify on the hr clone (backup `campaign.xml.pre-hunt`), grep the log for:
  - `Hunting force from A and B ... FP built ... mustering`, then `goes in over X: n/n
    fleets mustered`;
  - `moves on to` or `outmatched by`, and `badly hurt, a of b FP`: one stand-down per
    fleet of the force, all in the same poll;
  - fewer `Expedition postponed ... Defense Swarms`, and NPC sieges launching;
  - no `Exception`.

## Run 4 (2026-09-24 night): hunt rework, iterated in game
Clone `...8807243588242812142hr` (backup `campaign.xml.pre-hunt`). Five builds, about 240
days in total, 0 exceptions. Logs are in %TEMP%\sslogs-hunt1..6. A new `Hunt battle near`
diagnostic lists every fleet on the other side of a hunt's battle.
- Build 1 (gather + pool + odds): forces pooled (Hegemony: 7 bases, 18 fleets, 6,444 FP)
  and gathered, and pulled out when the target outgrew them. But the battle log showed
  every fight was ONE ~400 FP hunting fleet against a single 750-1,000 FP swarm, often a
  neighbour colony's met on the way in. Also found: hostile factions' forces brawled at a
  shared gathering point.
- Build 2 (lead + FOLLOW, per-faction gathering points): the force arrived whole, but
  fights still split into single fleets once near the target.
- Build 3 (followers keep blinders): no change. Vanilla's AI never fights separate fleets
  together.
- Build 4 (`softenFleetFP` 1500): the yards cap at 30 ships. Most navies build 250-500 FP
  per fleet; Nortia built 304 of a 1,683 ask and lost the rest. So: per-faction learned
  fleet cap, and a refund for pruned points.
- Build 5 (merge into the lead once gathered, `softenMerge`): works. Diktat fought Alpha
  Grimsharos II's whole garrison as one 1,313 FP fleet. A 449-ship, 5,895 FP Hegemony
  fleet cleared Alpha Novy Tayvay I. Salus was cleared by three forces. Over ~240 days:
  16 forces, 8 went in, 4 cleared their system, 4 broke off badly hurt, the rest pulled
  out before going in (outmatched). Absorption: 43, no garrison over cap.
- Sieges: 2 NPC sieges sailed (weak targets). The big hive worlds hold 3.7k-7.5k FP of
  swarms EACH, against a siege ceiling of ~2,400 FP (`siegeMaxFleets` 10 x ~245). A hunt
  thinned Alpha Novy Tayvay to 355 FP and the gate read open (1,950 vs 355), but no siege
  sailed before the run ended. Swarms regrow fast between hunts (e.g. 367 -> 3,233).

### After Run 4 (user: "Yes do it"), built, jar rebuilt, UNTESTED (PC locked)
- `siegeMaxFleets` 10 -> 25: in settings.json, the LunaSettings.csv default, and the user's
  stored `saves/common/LunaSettings/threatinc.json.data`, which otherwise shadows the new
  default.
- A hunt that thins a system triggers the siege pass on the next poll
  (`IncursionManager.huntThinned`, `recentlyThinned` counts as wounded).
- Verify: `Siege pass after a hunt thinned [...]`, then `Expedition draw at` for that
  system; `Siege sizing` lines reaching up to 25 fleets (~6k FP); no `Exception`.
  Clone backup before this run: `campaign.xml.pre-siege`.

## Run 5 (2026-09-24 night): siegeMaxFleets 25 + siege pass after hunts
Clone hr (backup `campaign.xml.pre-siege`; the player fleet had run dry, so the clone was
given 40,000 supplies and CR 0.7 to stop accident pop-ups stalling the run). About 257
days, 0 exceptions.
- `Siege pass after a hunt thinned [...]` fired 4 times (it now logs system names, not ids).
- NPC sieges: 4 launched (Chicomoztoc x2, Sindria at ~2,950 FP, Jangala), plus a landing
  at Salus. Run 4 had 2 in ~240 days, Run 3 had 1 in 21 months. Sizing now reaches 25
  fleets (~6,150 FP).
- The big hive worlds still hold 4.7k-7.5k FP of swarms each. At the 1.5x margin a siege
  needs 7k-11k FP, so most wait for hunts to thin them first, which is the design.
  Example: Alpha Novy Tayvay was sized at 4,700 FP against 3,002 faced after hunts.
- Hunts: 10 raised, 6 went in, 1 cleared its system, 6 broke off badly hurt. Hegemony
  went in over Surtr with 19 fleets merged, 8,188 FP against 2,479. Absorbed 38; no
  garrison over its cap.
