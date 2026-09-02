# Knowledge base

Notes written while building The Threat War Effort intel screen (Sept 2026).
Everything here was verified in the running game unless marked otherwise.

| Doc | What it covers |
| --- | --- |
| [intel-ui-platform.md](intel-ui-platform.md) | What the vanilla intel large-description API can and cannot draw, and the traps that crash or silently break it. Read before any custom intel UI work. |
| [war-board.md](war-board.md) | How `ThreatWarBoard` is built: data model, priority score, hive supply model, ledger, cards, buttons, tooltips, and the design decisions behind them. |
| [hive-economy.md](hive-economy.md) | How vanilla's economy really behaves (availability is a broadcast, shipping capacity is `10 x accessibility + 5`) and what that means for the hive planner, fuel reach and the board's Supply column. Read before touching `planHiveEconomy` or anything that reasons about shortages. |
| [testing-harness.md](testing-harness.md) | Launching the game, reaching the board and screenshotting it automatically. Scripts live in `tools/test-harness/`. |

Design mockups (HTML artboards) that led to the current layout are in `design/war-effort/`;
`Round2.dc.html` is the layout the board implements, `Kit.dc.html` the widget rules.

None of `docs/`, `tools/` or `design/` ships in release archives (see `.gitattributes`).
