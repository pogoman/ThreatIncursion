# Knowledge base

Notes written while building The Threat War Effort intel screen (Sept 2026).
Everything here was verified in the running game unless marked otherwise.

| Doc | What it covers |
| --- | --- |
| [intel-ui-platform.md](intel-ui-platform.md) | What the vanilla intel large-description API can and cannot draw, and the traps that crash or silently break it. Read before any custom intel UI work. |
| [war-board.md](war-board.md) | How `ThreatWarBoard` is built: data model, priority score, hive supply model, ledger, cards, buttons, tooltips, and the design decisions behind them. |
| [hive-economy.md](hive-economy.md) | How vanilla's economy really behaves (availability is a broadcast, shipping capacity is `10 x accessibility + 5`) and what that means for the hive planner, fuel reach and the board's Supply column. Read before touching `planHiveEconomy` or anything that reasons about shortages. |
| [testing-harness.md](testing-harness.md) | Launching the game, reaching the board and screenshotting it automatically. Scripts live in `tools/test-harness/`. |
| [ground-war.md](ground-war.md) | The ground-front siege rework: design, phase-1 mechanics (fronts, danger-close, fallout), judgment calls awaiting review, and the phase-2 backlog (fleet tasking, hive-side fronts, outposts). |
| [economy-coherence.md](economy-coherence.md) | How the successful 4X games run economies (stockpile vs flow, physical logistics), what vanilla's economy API actually offers (trade mods, econ units, deficits), and the seven rules that make the war reserves one truth with the colony screen. Built and verified in-game 2026-09-05 (section 5). |
| [design-theory.md](design-theory.md) | Review of the direction against design and military literature (AI War, Old World, Stellaris crises, Lanchester, Clausewitz, Corbett, Blackett's convoy research, feedback loops): what we already do right, and the seven things to verify or decide before building more. |
| [strategy-layer.md](strategy-layer.md) | Phase 2, built untested (Sept 2026): per-faction war mode, per-colony reserves from vanilla production, troops as real expedition cargo, staging convoys, the board's faction selector and view, and remote fleet orders (guard, stage, intercept, siege, recall). |

Design mockups (HTML artboards) that led to the current layout are in `design/war-effort/`;
`Round2.dc.html` is the layout the board implements, `Kit.dc.html` the widget rules.

None of `docs/`, `tools/` or `design/` ships in release archives (see `.gitattributes`).
