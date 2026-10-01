# Strategy layer - war mode, reserves, convoys, fleet orders

Phase 2 of the ground-war rework (docs/ground-war.md): the "proper strategy game".
Design agreed with the user on 2026-09-04; built WITHOUT in-game testing so far (the
remote session could not render the game - see docs/testing-harness.md). Every rate is
a config knob (settings.json / LunaLib), master switch `threatinc_strategyEnabled`.

Split by topic on 2026-10-01; the sections are unchanged and keep their headings.

## Where each section lives

| Section | File |
| --- | --- |
| Principles (the user's calls) | [strategy-reserves-sieges.md](strategy-reserves-sieges.md) |
| War mode (ThreatWarState) | [strategy-reserves-sieges.md](strategy-reserves-sieges.md) |
| Reserves (ThreatReserves) | [strategy-reserves-sieges.md](strategy-reserves-sieges.md) |
| The Luddic Path: tithes and zealots (2026-09-27, user's call, untested) | [strategy-reserves-sieges.md](strategy-reserves-sieges.md) |
| Convoys (ThreatConvoys) | [strategy-convoys.md](strategy-convoys.md) |
| Built overnight 2026-09-04/05 (verified in-game on the clone save) | [strategy-convoys.md](strategy-convoys.md) |
| The faction selector and faction view (ThreatFactionView) | [strategy-board-returns.md](strategy-board-returns.md) |
| Fleet orders (ThreatFleetOrders) | [strategy-board-returns.md](strategy-board-returns.md) |
| Returns - what comes back (ThreatReturns) | [strategy-board-returns.md](strategy-board-returns.md) |
| Staging fleets, and the fleet-point gate (2026-09-05, untested) | [strategy-orbit-outposts.md](strategy-orbit-outposts.md) |
| Support, and the refuse rule (2026-09-05, renamed from Escort 2026-09-06, untested) | [strategy-orbit-outposts.md](strategy-orbit-outposts.md) |
| Hunting forces (ThreatSoftening, 2026-09-24) | [strategy-hunting-reach.md](strategy-hunting-reach.md) |
| Finding the hive (ThreatScouts, 2026-09-24, untested) | [strategy-hunting-reach.md](strategy-hunting-reach.md) |
| The factions' reach and stance (2026-10-01, user's call) | [strategy-hunting-reach.md](strategy-hunting-reach.md) |
| Not built yet | [strategy-hunting-reach.md](strategy-hunting-reach.md) |
| Testing notes | [strategy-hunting-reach.md](strategy-hunting-reach.md) |
