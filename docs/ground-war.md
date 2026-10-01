# Ground war - eradication by ground victory

Design agreed with the user (Sept 2026, two sessions). This doc records what is
implemented, the judgment calls made, and the strategy-layer backlog. Every rate is a
config knob (settings.json / LunaLib).

Split by topic on 2026-10-01; the sections are unchanged and keep their headings.

## Where each section lives

| Section | File |
| --- | --- |
| The model | [ground-war-fronts.md](ground-war-fronts.md) |
| The front (ThreatGroundFronts) | [ground-war-fronts.md](ground-war-fronts.md) |
| The stratum campaign | [ground-war-fronts.md](ground-war-fronts.md) |
| NPC fronts | [ground-war-fronts.md](ground-war-fronts.md) |
| Player-facing readouts | [ground-war-fronts.md](ground-war-fronts.md) |
| Engine changes this needed | [ground-war-fronts.md](ground-war-fronts.md) |
| Reading a front - the board (2026-09-05, untested in-game) | [ground-war-fronts.md](ground-war-fronts.md) |
| Vanilla-tool integration (unchanged from phase 1) | [ground-war-fronts.md](ground-war-fronts.md) |
| What replaced "decline" in the UI | [ground-war-fronts.md](ground-war-fronts.md) |
| Sieges against human factions (built 2026-09-06, untested) | [ground-war-defenders.md](ground-war-defenders.md) |
| Marines defend, and they die | [ground-war-defenders.md](ground-war-defenders.md) |
| Veterancy | [ground-war-defenders.md](ground-war-defenders.md) |
| The planetary shield (2026-09-07, built, untested) | [ground-war-defenders.md](ground-war-defenders.md) |
| How the defender fights back | [ground-war-defenders.md](ground-war-defenders.md) |
| Blockade (2026-09-27, built, untested) | [ground-war-defenders.md](ground-war-defenders.md) |
| When the colony falls | [ground-war-defenders.md](ground-war-defenders.md) |
| Threat ground assaults - the rule is symmetric | [ground-war-sieges.md](ground-war-sieges.md) |
| The doctrine: besiege, land, reinforce (reworked 2026-09-06, untested) | [ground-war-sieges.md](ground-war-sieges.md) |
| Sizing the landing - the troop pool | [ground-war-sieges.md](ground-war-sieges.md) |
| Bombardment v2 - the day of sorties (2026-09-28, built, untested) | [ground-war-sieges.md](ground-war-sieges.md) |
| Sieges from orbit - one duel, both theatres (2026-09-06, untested) | [ground-war-sieges.md](ground-war-sieges.md) |
| Saturation presses a hive (2026-10-01, user's call) | [ground-war-sieges.md](ground-war-sieges.md) |
| Off-screen fights cost both sides (2026-10-01, user's call) | [ground-war-sieges.md](ground-war-sieges.md) |
| How a siege runs, watched and off-screen (code paths) | [ground-war-code-paths.md](ground-war-code-paths.md) |
| Fabricating troops from the fleet (2026-09-08, built, untested) | [ground-war-orbit-control.md](ground-war-orbit-control.md) |
| The swarm does not fight on armaments (2026-09-08, built, untested) | [ground-war-orbit-control.md](ground-war-orbit-control.md) |
| The swarm holds the orbit | [ground-war-orbit-control.md](ground-war-orbit-control.md) |
| Relief (2026-09-27, built, untested) | [ground-war-orbit-control.md](ground-war-orbit-control.md) |
| A station that comes back (2026-09-08, built, untested) | [ground-war-orbit-control.md](ground-war-orbit-control.md) |
| To verify - marines and veterancy (2026-09-08, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| Testing | [ground-war-verify.md](ground-war-verify.md) |
| To verify - sieges against human factions (2026-09-06, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - the duel on both theatres, Support (2026-09-06 evening, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - the landing fleet defends, and the Defend order (2026-09-07, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| Verified - military options on a hive (2026-09-07, tested in game, works) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - troops push on landing (2026-09-07, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - the planetary shield (2026-09-07, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - fabricating troops from the fleet (2026-09-08, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - a station that comes back (2026-09-08, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| To verify - the swarm off armaments, and paying on the assault (2026-09-08, built, untested) | [ground-war-verify.md](ground-war-verify.md) |
| Raiding the fortifications (2026-09-25) - RETIRED 2026-09-28 | [ground-war-runs.md](ground-war-runs.md) |
| Strategy-layer backlog (the "proper strategy game" - see docs/strategy-layer.md for what was built on 2026-09-04) | [ground-war-runs.md](ground-war-runs.md) |
| Strategy-layer decisions (user, 2026-09-04, after the session above died) | [ground-war-runs.md](ground-war-runs.md) |
| To verify - bombardment v2 (2026-09-28, built, untested) | [ground-war-runs.md](ground-war-runs.md) |
| Overnight 2026-09-29 - siege AI fixes (built, tested in runs N1-N3) | [ground-war-runs.md](ground-war-runs.md) |
