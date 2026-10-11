# Testing handover - 2026-10-11: carried provisions, the player's navy upkeep

For the session testing the 2026-10-11 builds on another machine. Read the repo's `CLAUDE.md` first, then
`fleet-provisions.md` (the design and the hw10 days-out measurement). How to run the harness, the mods, the
pristine save and the user's standing rules are unchanged from `handover-2026-10-05-testing.md` sections 2-8;
only what is tested and what to look for is here. `git pull` brings the jar (`jars/ThreatInc.jar`, built
12:55, committed with this file); do not rebuild unless the source changes.

## 1. What is being tested (all untested in game)

Today's commits, oldest first. The top two are the big ones.

| Commit | Change | Where |
|---|---|---|
| `78fac9c0` | **Fleets carry their provisions, both sides.** Beside the hull deposit a fleet draws a HOLD at launch (its ships' maintenance for the voyage + `sortieStayDays` 90; a siege its planned stay), `ThreatUpkeep` burns it per pass, restocks from a base of its faction IN ITS SYSTEM when below the leg home (`fleetResupply`), else stands down "supplies low" / `shortOfSupplies`. A siege's hold rides the expedition, burned at `fightingFP`. The player's built navy pays standing upkeep. Fleets table: an open-ended order shows "N d supplies". | `fleet-provisions.md`, `ThreatUpkeep`, `ThreatReturns.MEM_HOLD`, `ThreatPurgeFGI.hold`, `ThreatHulls.maintain` |
| `3f7cd128` | Standing upkeep stays navy-wide: hulls at home eat from any colony's stock above its floor (the user: fine for a garrisoned navy). | `ThreatHulls.maintain` |
| `f950f2a8` | The player is outside the opening: player worlds are struck as soon as seen and payable (no 40k bar / 8k floor), the player's Mobilise and raids no longer open the core war. | `IncursionManager.warOpen`, `openingWaits` |
| `4a0d7255`, `24622d70`, `049a14c5` | Board text: the Fleet column, no "FP" anywhere player-facing, the pool is patrols plus built navy. | `ThreatFactionView` |

Settings: shipped defaults. New LunaLib rows `threatinc_sortieStayDays` (90) and `threatinc_fleetResupply`
(true); an older LunaLib store leaves them at their defaults (new keys). No knob is set for the batch.

## 2. What to look for

**A. The hold works (player-less batch, hw151a-c on the OceanPena base save as hw150 was).** Grep the run
logs (`saves\_sbs\<tag>\logs\starsector.log`, lines `[ThreatInc]`):

- `Order draw at <base>: F fuel, S supplies, hold H of W supplies (D days)` - every task force and hunt
  launch. H should equal W except at an empty depot; D about 90 plus the voyage.
- `Fleet upkeep: <faction> N fleets out, paid P of Q supplies from their holds, X owed, restocked R` -
  one line per faction per 5 days. "owed" should be rare and small; it was the norm before (hw10a: 38% of
  hunts ended "out of supplies").
- `Restocked at <base>: N supplies for <fleet / the siege of X>` - forward bases working. None at all means
  either no hunt ran low in a system with a link, or the lookup (`ThreatBases.inSystem`) misses links -
  check against `Forward base` lines in the same system.
- `Order stood down (supplies low, N aboard)` and `Siege Short of Supplies (<faction>, <system>)` replace
  most `out of supplies` endings. Count both against hw150's stand-down reasons.
- `Siege Out of Supplies` should now appear only for sieges from before the hold (none on a fresh save).

**B. The war still runs.** The hold is a NEW launch cost (sieges priced it before but never drew it;
hunts and task forces paid nothing up front). Compare with hw150 (`handover` evening 9c in `facts.md`):
sieges sailed and landed per month, hunting forces built, hives at month 126, worlds lost. If sieges
collapse, the suspect is `expeditionHold` (siege draw asks deposit + hold from the same pool the gate
priced) - the fix would be in `IncursionManager.expeditionWants`, not a smaller stay.

**C. The player's side (a real game, the user's or a short one on the StarLord saves).**

- Fleets table: a Guard/Support/Defend with no term shows "N d supplies" in the ETA column; timed orders
  still show "N d left".
- Launch a task force and read the log's `Order draw` line: the hold came out of the colony's resource
  stockpile (the colony screen's stockpile drops by deposit + hold).
- Let a guard sit over a hive for months with no base in that system: it stands down with the notice
  "Task Force Low on Supplies" (fleet name, task, supplies aboard, days) and sails home; the stockpile gets
  the hold's remainder x surviving strength back on arrival (`Return settled at`).
- A guard over the player's own colony restocks there each pass (log `Restocked at <colony>`), so it costs
  the stockpile its maintenance - that is intended.
- Hull port row tooltip: "Navy upkeep: N supplies a month for the M fleet points at home." Hulls: log
  `Hulls: <faction> ... standing upkeep paid X of Y supplies` includes the player; an unpaid month strikes
  hulls with the notice "Navy Unpaid" (red title).
- Old save in flight: fleets launched before the build keep the old home billing until they return (no
  `hold` on them); nothing should stand down just from loading.

**D. The opening (`f950f2a8`).** On a save where the player has colonies: a swarm strike against a player
world should come as soon as a hive has seen it and can pay, without the 40k bar; the player's Mobilise
alone must not open the core war (`openingWaits` log lines read NPC war and NPC grudge only).

## 3. Reporting

Per-checkpoint findings file as always (`test-runs-report-as-they-go`), every finding with a proposed fix.
Record what the batch measured in `game-runs.md` (next section number) and one-line answers in `facts.md`.
The user's rules that bite here: no range gates on player orders, no shortage cheats (a short depot is a
finding, not something to pad), no fluoro green, one line per fact in any tooltip.
