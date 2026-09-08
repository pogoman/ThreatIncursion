# The Abyssal War - working notes for AI assistants

Start with `docs/README.md`. It indexes everything learned the hard way about
this mod's custom intel UI, the war board, and the automated in-game test loop.
Read the relevant doc before touching `ThreatWarBoard.java` or launching the game.

Quick facts:
- Build: `compile.ps1` (JDK on PATH) writes `jars/ThreatInc.jar`. The game locks the
  jar while running - close it (or kill `java.exe`) before building.
- Platform: Starsector 0.98a-RC8, vanilla-only, LunaLib optional. Sources in `src/threatinc`.
- Line endings: sources are CRLF. Patch scripts must normalise before matching.
- Two sessions have edited `ThreatWarBoard.java` concurrently before; re-read a file
  immediately before patching it.

## UI text and layout - less is more

The user's rule (2026-09-05): the board is read a hundred times, so it shows what is true
now and nothing about how the mechanism works - that goes in `docs/`.

- No intro paragraphs above tables. Totals go in a Total row at the foot; colour meanings
  go in a one-line key of single words in their colours. The reserve key is white excess,
  yellow deficit, red critical, grey empty - reuse it, do not invent a second palette.
- A tooltip is one line per fact. A button's tooltip says what pressing it does while
  enabled and why it cannot be pressed while disabled (`ThreatFactionView.disableWith`).
- Orders are buttons, floating on their row (user's decision 2026-09-06, after a one-evening
  try at row-menu dialogs). A row that needs more than an Actions column can hold - the
  player's ground fronts - takes two table rows: the entry, then an empty row its buttons
  float over (`ThreatWarBoard.addFrontButtons`).
  Header tooltips are one line and never explain colours or buttons.
- Confirmation prompts are the question and the numbers.
- Clicking a colony row opens vanilla's colony screen (`ThreatColonyScreenDialog`); vanilla's
  UI shows the rest. Faction names on labels are capitalised (`ThreatWarState.displayName`).
- When in doubt, cut. If a sentence explains a rule, delete it and check the rule is in a doc.
- **Option panels: shape first, state last.** In any dialog menu, do every
  `addOption`/`removeOption`/`clearOptions` first, then every `setEnabled`/`setTooltip`.
  `removeOption` rebuilds the panel and drops every `setEnabled`, `setTooltip`, `setShortcut`
  and `addOptionConfirmation` on the *other* options, a superclass's included - only a tooltip
  passed into `addOption` itself survives. A plain `addOption` after `setEnabled` is fine
  (vanilla and `groundOps` both do it). Confirmed by decompiling
  `com.fs.starfarer.ui.newui.OoOO`; the tell was that re-adding "Go back" had always needed
  its escape shortcut re-set. This is what made the hive military-options menu ignore its own
  gating for weeks (`docs/ground-war.md`, "removeOption drops the enabled state"). Plain
  fields like `temp.canRaid` survive it, so a menu can look half-fixed and send you hunting
  the detection code when the detection was never wrong.

## Context discipline

Keep the main context lean - this codebase is large and discovery fills the window fast.

- Delegate discovery and any simple, self-contained legwork to a subagent (`Explore`
  for read-only searches, `general-purpose` for multi-step lookups). The subagent reads
  the files in its own context and returns just the conclusion, so the main window only
  pays for the answer, not the file dumps. Reach for this whenever answering means
  sweeping several files, docs, or CSVs - e.g. "where is X wired up", "what pattern does
  Y follow", "which configs reference Z". This includes the discovery phase of complex
  work you will finish yourself - tracing a bug through the call chain, mapping a lifecycle,
  reading the game log is discovery, not building, so it goes to a subagent even when the
  design or fix stays here. Rule of thumb: if you are about to open more than two or three
  files you were not pointed at by name, stop and send an `Explore` subagent to do the
  sweep, then act on its conclusion in this session.
- What stays on Fable is the *judgement* - designing an intricate feature, deciding the
  fix, writing the code - not the reading that feeds it. Do NOT delegate that judgement to
  a bare subagent. The global `pin-subagent-model` hook pins any subagent a Fable session
  spawns without an explicit model to a cheaper tier (`general-purpose` -> opus, `Explore`
  -> sonnet), which is right for discovery, legwork and reviews but wrong for designing an
  intricate feature. Build complex features in this session, or - only when you want
  parallel workstreams - delegate with an explicit `model: "fable"` so the hook passes it
  through (watch for the hook's `PINNED` notice - if a build task lands on opus, you forgot
  the explicit model; re-issue it with `model:"fable"`). Cheap tiers are
  for finding, fetching and reviewing Fable's work, never for architecting it. A review by
  Fable afterwards is a complement, not a substitute: it catches bugs but won't redo a weak
  design a cheaper model committed to while building.
- "Points at directly" means a named file or an explicit line range - read those here. A
  feature or symbol named only by concept ("the recall path", "how sieges land") is a
  discovery target, not a pointer: fan it out to a subagent rather than opening files one
  by one in the main thread.
- The user prefers this default: delegate simple/broad stuff to subagents rather than
  loading it all into the main thread.
- `docs/code-map.md` lists what each class in `src/threatinc/` is for. Check it first to
  locate a feature or bug. When you add a new class - or change what an existing class is
  *for* - update its one line there in the same change; editing logic inside a class needs
  no update.
