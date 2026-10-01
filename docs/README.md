# Knowledge base

Notes written while building The Abyssal War intel screen (Sept 2026).
Everything here was verified in the running game unless marked otherwise.

| Doc | What it covers |
| --- | --- |
| [facts.md](facts.md) | Grep first. One-line standing answers ("why are there 20k FP fleets", "what gates a convoy") and the user's design decisions, each with the symbol and the doc section that holds the detail. |
| [code-map.md](code-map.md) | One line per class in `src/threatinc/`, grouped by subsystem. Start here to find which file owns a feature or bug before diving in. |
| [intel-ui-platform.md](intel-ui-platform.md) | What the vanilla intel large-description API can and cannot draw, and the traps that crash or silently break it. Read before any custom intel UI work. |
| [war-board.md](war-board.md) | How `ThreatWarBoard` is built: data model, priority score, hive supply model, ledger, cards, buttons, tooltips, and the design decisions behind them. |
| [hive-economy.md](hive-economy.md) | How vanilla's economy really behaves (availability is a broadcast, shipping capacity is `10 x accessibility + 5`) and what that means for the hive planner; then the Threat's closed economy: the FP bank, paid founding, garrison upkeep. Read before touching `planHiveEconomy` or anything that reasons about shortages. |
| [hive-garrison-and-upkeep.md](hive-garrison-and-upkeep.md) | Posture (the garrison the war calls for), the Threat's stance, the hive's fuel and supplies stocks, structures costing supplies, size upkeep and growth. |
| [hive-reach-and-stock.md](hive-reach-and-stock.md) | Reach is the bill, idle stock (retire, convert, the Bastion/Command military tier), the verified levers, what the board shows. |
| [testing-harness.md](testing-harness.md) | Launching the game, reaching the board and screenshotting it automatically. Scripts live in `tools/test-harness/`. |
| [ground-war.md](ground-war.md) | Index of the ground war, split 2026-10-01 into six files; its table maps each section to its file. |
| [ground-war-fronts.md](ground-war-fronts.md) | The front engine (states, strata, NPC fronts), the board's front readouts, vanilla-tool integration (the `removeOption` trap). |
| [ground-war-defenders.md](ground-war-defenders.md) | What a defended colony is made of: garrison, marines, veterancy, planetary shield, blockade, how it falls. |
| [ground-war-sieges.md](ground-war-sieges.md) | Threat assault doctrine, landing sizing, the per-day bombardment engine, saturation over a hive, off-screen fights. |
| [ground-war-orbit-control.md](ground-war-orbit-control.md) | Fabricating troops from hulls, the swarm off armaments, holding the orbit, relief, stations that come back. |
| [ground-war-verify.md](ground-war-verify.md) | In-game verify lists for the features before bombardment v2. |
| [ground-war-runs.md](ground-war-runs.md) | Retired mechanics, the strategy backlog, bombardment v2 checks and the overnight 2026-09-29 siege AI runs (siege sizing history). |
| [ground-war-code-paths.md](ground-war-code-paths.md) | How a siege runs once launched, mapped 2026-10-01: the expedition's route lifecycle, the one-frame off-screen resolve and its four `autoresolve` entries, `abstractSiege`, the watched per-fleet cycle, abstract strength and where losses land, `ThreatAbstractBattle.fought`, where a daily off-screen tick hooks in, traps. By symbol. |
| [economy-coherence.md](economy-coherence.md) | How the successful 4X games run economies (stockpile vs flow, physical logistics), what vanilla's economy API actually offers (trade mods, econ units, deficits), and the seven rules that make the war reserves one truth with the colony screen. Built and verified in-game 2026-09-05 (section 5). |
| [design-theory.md](design-theory.md) | Review of the direction against design and military literature (AI War, Old World, Stellaris crises, Lanchester, Clausewitz, Corbett, Blackett's convoy research, feedback loops): what we already do right, and the seven things to verify or decide before building more. Section 2a holds the two standing design rules (2026-09-29): no arbitrary caps, and a closed economy. |
| [player-aid.md](player-aid.md) | BUILT 2026-09-05, untested in-game: NPC factions are autonomous (their order buttons, the Rally button and the standing gate are gone); the player sends Defend / Aid / Strike fleets from their own colonies, gated by a capacity ledger (vanilla fleet-size stat) and reserve stock, paid in credits, earning standing on arrival; factions post requests for help as vanilla missions the player completes in person (station commander hand-over) or by colony fleet; allies aid each other by standing. Section 8 lists what the build did and what to verify. |
| [strategy-layer.md](strategy-layer.md) | Index of the human factions' strategy layer, split 2026-10-01 into five files; its table maps each section to its file. |
| [strategy-reserves-sieges.md](strategy-reserves-sieges.md) | Principles, war mode, per-colony reserves, and the whole NPC siege sizing and targeting block (in "Reserves"); the Luddic Path. |
| [strategy-convoys.md](strategy-convoys.md) | Convoys, staging stock, logistics reach (no radius) and hauls, relays, raiders. |
| [strategy-board-returns.md](strategy-board-returns.md) | The faction selector and view, fleet orders, what comes home and how it is settled. |
| [strategy-orbit-outposts.md](strategy-orbit-outposts.md) | Staging fleets and the FP gate; Support and the refuse rule, which also holds escalation, coalition, outposts and relief. |
| [strategy-hunting-reach.md](strategy-hunting-reach.md) | Hunting forces, finding hives (`sectorKnows`), the factions' billed reach and stance, test notes. |
| [strategy-code-paths.md](strategy-code-paths.md) | The call graph of the NPC war code, mapped 2026-10-01: the siege pass and its gates, targets, orbit sizing and the launch; the stance; orders, Support and its stand-down; hunts; the coalition call; what is saved. By symbol. |
| [engine-code-paths.md](engine-code-paths.md) | The mod's plumbing, mapped 2026-10-01: saved state and its lifecycle hooks, the clocks and where a daily reader goes, Threat systems and fleets, discovery, observers and the coalition, who watches (links, military worlds, player outposts), the orbit contest rule, vanilla `RouteManager`, human scouts, knobs and the LunaLib default migration. By symbol. |
| [nexerelin.md](nexerelin.md) | Compatibility with Nexerelin (optional, built and partly verified in-game 2026-09-23): who owns the military menu, how besieged colonies are kept out of Nexerelin invasions, and what Nexerelin still does unchecked. |
| [frontlines.md](frontlines.md) | BUILT 2026-09-26, untested in-game: mobilised NPC factions push chains of real-market outposts toward hives (grow from size 1, build only what vanilla imports can supply, forward relay cut when a middle link falls, relief forces); Threat strikes hidden until detected; the swarm targets critical links. Research, vanilla numbers, knobs, verify list, liberties taken. |
| [omens.md](omens.md) | BUILT 2026-09-26, untested in-game: foreboding messages before any hive is found - what triggers each kind, the clocks, the stop rule, knobs, verify list. |
| [fleet-archetypes.md](fleet-archetypes.md) | BUILT 2026-09-24, untested in-game: the 12 new Threat variants, the archetypes (Host, Vanguard, Battery, Tide, Hunter, Scout) and which fleet gets which, how composition keeps vanilla's strength, and how to add a variant or archetype. |
| [suppression-balance.md](suppression-balance.md) | Every way a colony's defences get worn down (hover siege, player tac bomb, fortification raids, fronts, saturation) with time and price tables at four defence levels, and the asymmetries between them. Computed, not measured (2026-09-28). **Holds the spec of the bombardment/siege redesign v2, BUILT 2026-09-28 (untested) - the build notes are at the end of its spec section.** |
| [attack-planner.md](attack-planner.md) | DESIGN 2026-10-01, decided (section 9), being built: fog of war as per-faction reports (eyes, forward-base radar, scouts) replacing every live remote read of the swarm, the player's board included; a per-faction planner that runs sieges, raids, recon and hunts per world, spread across targets until one is likely to land; judgement on arrival (raids divert, sieges go home); off-screen sieges a day at a time. Its "Why" holds the h48a facts on how the swarm really reinforces (reserves stay home, surplus moves). |
| [testing-handover-2026-09-24.md](testing-handover-2026-09-24.md) | UNTESTED, uncommitted: how to prove the 15 review fixes of 2026-09-24 in-game - ten checks in priority order, the exact log lines each one hinges on, load risks, and what to do when it passes. |

Gotchas that cost a test run:

- **`CampaignClock.getElapsedDaysSince(0L)` returns `Float.MAX_VALUE`,** because vanilla reads a
  timestamp of 0 as "never". For absolute days use `getElapsedDaysSince(1L)` (`ThreatPosture.today()`).
  Stamping with `since(0L)` made every stamp the same day, so nothing decayed and a system's first
  pressure stood for a year (2026-09-29, ti8c).

Design mockups (HTML artboards) that led to the current layout are in `design/war-effort/`;
`Round2.dc.html` is the layout the board implements, `Kit.dc.html` the widget rules.

None of `docs/`, `tools/` or `design/` ships in release archives (see `.gitattributes`).
