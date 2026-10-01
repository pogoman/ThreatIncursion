# The Abyssal War - working notes for AI assistants

**Look it up before you research it.** For any "why / how / where / what does X do"
question, grep `docs/facts.md` first: one-line standing answers and the user's design
decisions, each pointing at a symbol and a doc section. Then `docs/code-map.md`, then the
doc section it names, then the source. In session 18c7e7b4 (2026-10-01), 20-40 minute
research agents re-derived answers that were already in the docs, and the user had to repeat
decisions three times. `docs/README.md` indexes the topic docs.

Quick facts:
- Build: `compile.ps1` (JDK on PATH) writes `jars/ThreatInc.jar`. The game locks the
  jar while running - close it (or kill `java.exe`) before building. Run it out of process
  (`powershell -NoProfile -ExecutionPolicy Bypass -File compile.ps1`) and check the jar's
  timestamp: in-process, javac's "unchecked" note stops the script before `jar` and the old jar stays.
- Platform: Starsector 0.98a-RC8, vanilla-only, LunaLib optional. Sources in `src/threatinc`.
- Line endings are mixed: on 2026-10-01, 74 of `src/threatinc/*.java` were LF and 8 CRLF (git `autocrlf`
  true). Check a file with a raw Perl read (Git Bash grep misreports carriage returns; `sed -i` strips them)
  and normalise in patch scripts before matching.
- Two sessions have edited `ThreatWarBoard.java` concurrently before; re-read a file
  immediately before patching it.
- `python` is a Windows Store stub here and fails. Script with Perl, PowerShell or the Edit tool.

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
- Notifications go through `ThreatNotice` (user's rule 2026-09-26), never `MessageIntel` or one
  flat-coloured sentence: a 1-4 word Title Case title, then one fact per bullet with factions in
  their colour (`ThreatNotice.faction`), markets in their owner's (`market`), figures highlighted.
  Title colours: the Threat's faction colour for anything the swarm does (Threat crest), red only
  for our side's losses (a faction crest + `bad()`), the default light blue for good or neutral.
  The word "Threat" in a line is coloured automatically.
- **Never fluoro green** (`Misc.getPositiveHighlightColor`), anywhere - user's rule 2026-09-26.
  A good state or gain uses `ThreatNotice.goodColor()` (the player's UI blue).
- **Option panels: shape first, state last.** In any dialog menu, do every
  `addOption`/`removeOption`/`clearOptions` first, then every `setEnabled`/`setTooltip`.
  `removeOption` rebuilds the panel and drops every `setEnabled`, `setTooltip`, `setShortcut`
  and `addOptionConfirmation` on the *other* options, a superclass's included - only a tooltip
  passed into `addOption` itself survives. A plain `addOption` after `setEnabled` is fine
  (vanilla and `groundOps` both do it). Confirmed by decompiling
  `com.fs.starfarer.ui.newui.OoOO`; the tell was that re-adding "Go back" had always needed
  its escape shortcut re-set. This is what made the hive military-options menu ignore its own
  gating for weeks (`docs/ground-war-fronts.md`, "removeOption drops the enabled state"). Plain
  fields like `temp.canRaid` survive it, so a menu can look half-fixed and send you hunting
  the detection code when the detection was never wrong.

## Context discipline

Keep the main context lean - this codebase is large and discovery fills the window fast.

- Look up first (top of this file). A question `facts.md` or a doc section answers needs no
  agent; at most a grep to confirm the symbol still says so.
- Delegate the rest of discovery and any simple, self-contained legwork to a subagent
  (`Explore` for read-only searches, `general-purpose` for multi-step lookups). The subagent
  reads the files in its own context and returns just the conclusion, so the main window only
  pays for the answer, not the file dumps. Reach for this whenever answering means
  sweeping several files, docs, or CSVs - e.g. "where is X wired up", "what pattern does
  Y follow", "which configs reference Z". This includes the discovery phase of complex
  work you will finish yourself - tracing a bug through the call chain, mapping a lifecycle,
  reading the game log is discovery, not building, so it goes to a subagent even when the
  design or fix stays here. Rule of thumb: if you are about to open more than two or three
  files you were not pointed at by name, stop and send an `Explore` subagent to do the
  sweep, then act on its conclusion in this session.
- **Brief research agents narrowly.** Subagents remember nothing either, so hand each one
  question, the `facts.md` lines and doc sections you already have, and the files to check.
  Ask for medium breadth, aim for about 10 minutes, and say "confirm or correct this" rather
  than "find out how X works". Never "very thorough" sweeps for a design question.
- **Do not idle on an agent.** While it runs, keep working: read the named files you will
  edit, draft the spec, and answer what you already can.
- **Write findings down before compaction eats them.** When an agent or a test teaches you
  something `facts.md` lacks, add the one-line answer there in the same turn; a decision the
  user makes goes in its Decisions list (and memory) the same turn. A code map an agent
  returns (a call chain, hook points, a lifecycle) goes into the topic doc that owns that
  subsystem, by symbol rather than line number, and is committed - the user's rule 2026-10-01:
  no session remaps what an earlier one mapped. Only a run's raw output stays in a scratch
  file. After a compaction, re-read those instead of re-spawning the research.
- Keep topic docs under about 40 KB so an agent reads the doc rather than the source. Split a
  doc by topic when it grows past that, and update `README.md` and `facts.md` pointers.
- Complex work - designing an intricate feature, deciding the fix, writing the code - runs
  on the **current session's model**: build it in this session, or, for parallel
  workstreams, delegate with an explicit `model` equal to this session's own model. Never
  pick a model above the session's. Simple work - discovery, legwork, reviews - goes to
  lower tiers (`Explore` -> sonnet, lookups -> sonnet/haiku). The global
  `pin-subagent-model` hook downgrades subagents spawned without a model, so an unspecified
  build agent may land on a cheaper tier - pass the model explicitly for build work.
- "Points at directly" means a named file or an explicit line range - read those here. A
  feature or symbol named only by concept ("the recall path", "how sieges land") is a
  discovery target, not a pointer: fan it out to a subagent rather than opening files one
  by one in the main thread.
- The user prefers this default: delegate simple/broad stuff to subagents rather than
  loading it all into the main thread.
- Screenshots go to subagents. Every image read stays in context until compaction, so
  in-game test screenshots are taken and read by a subagent that returns what it saw
  (text, figures, anything wrong) - never Read a screenshot in the main thread unless
  the user asks to see it there. The project `.claude/settings.json` also compacts at
  350K (`CLAUDE_CODE_AUTO_COMPACT_WINDOW`) rather than near 1M.
- `docs/code-map.md` lists what each class in `src/threatinc/` is for. Check it first to
  locate a feature or bug. When you add a new class - or change what an existing class is
  *for* - update its one line there in the same change; editing logic inside a class needs
  no update.
