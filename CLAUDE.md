# Threat Incursion - working notes for AI assistants

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
  Header tooltips are one line and never explain colours or buttons.
- Confirmation prompts are the question and the numbers.
- Clicking a colony row opens vanilla's colony screen (`ThreatColonyScreenDialog`); vanilla's
  UI shows the rest. Faction names on labels are capitalised (`ThreatWarState.displayName`).
- When in doubt, cut. If a sentence explains a rule, delete it and check the rule is in a doc.

## Context discipline

Keep the main context lean - this codebase is large and discovery fills the window fast.

- Delegate discovery and any simple, self-contained legwork to a subagent (`Explore`
  for read-only searches, `general-purpose` for multi-step lookups). The subagent reads
  the files in its own context and returns just the conclusion, so the main window only
  pays for the answer, not the file dumps. Reach for this whenever answering means
  sweeping several files, docs, or CSVs - e.g. "where is X wired up", "what pattern does
  Y follow", "which configs reference Z".
- Read specific files/line ranges the user points at directly; only fan out to a subagent
  when the target is unknown or spread across many files.
- The user prefers this default: delegate simple/broad stuff to subagents rather than
  loading it all into the main thread.
- `docs/code-map.md` lists what each class in `src/threatinc/` is for. Check it first to
  locate a feature or bug. When you add a new class - or change what an existing class is
  *for* - update its one line there in the same change; editing logic inside a class needs
  no update.
