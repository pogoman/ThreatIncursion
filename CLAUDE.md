# The Abyssal War - working notes for AI assistants

**Look it up before you research it.** For any "why / how / where / what does X do"
question, grep `docs/facts.md` first: one-line standing answers and the user's design
decisions, each pointing at a symbol and a doc section. Then `docs/code-map.md`, then the
doc section it names, then the source. In session 18c7e7b4 (2026-10-01), 20-40 minute
research agents re-derived answers that were already in the docs, and the user had to repeat
decisions three times. `docs/README.md` indexes the topic docs.

**Finding code is a grep, not an agent** (user's rule 2026-10-02). `docs/symbols.md` is generated
at every build (`tools/gen-symbols.pl`): one line per method of `src/threatinc`, as
`Class.method(params) :line - what it does`, 3,500 of them. To find where something is decided,
Grep it for a word of the behaviour ("landing", "bounty", "upkeep") or the symbol, then Read that
method at its line (`offset` / `limit`, 60-150 lines) in the main thread. Never read
`symbols.md` whole (400 KB) and never send an agent to "map" or "find" code: an agent starts
with nothing and spends 30-60 minutes re-reading 6,000-line classes. A method with no javadoc
shows without a description - when you work out what one does, add its one-sentence javadoc so
the next grep finds it.

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
- Locating code is never delegated (grep `docs/symbols.md`, above). Delegate only bulk reading
  that would flood the window - a run's log, a set of dumps, screenshots - and self-contained
  legwork, to a subagent (`Explore` for read-only sweeps, `general-purpose` for multi-step
  lookups); it returns just the conclusion, so the main window pays for the answer, not the
  dumps. Tracing a call chain is done here: grep the index for each symbol and read the methods
  at their lines - a chain of five methods is five short reads, not an agent.
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
- **Models (the user's standing rule, 2026-10-10 20:00, replacing the 19:15 one).** This repo runs on
  **Fable, main session and all reasoning**. An Opus main session was tried for one evening (19:15-20:00)
  and misread a batch log on its first read ("the hold never fired" - it grepped the first cut's log
  text) and built the wrong deduction on it; the user: "we stick with fable for all, i dont have time to
  waste with opus fucking up". Never delegate a diagnosis, a strategic choice or a build to a lower
  tier; never recommend switching the main session down. Simple legwork only - discovery, bulk log
  reads, screenshots, reviews - goes to lower tiers (`Explore` -> sonnet, lookups -> sonnet/haiku), and
  its findings are checked here before they are recorded. The global `pin-subagent-model` hook
  downgrades subagents spawned without a model - always pass `model` explicitly.
- A feature named only by concept ("the recall path", "how sieges land") is a grep of
  `docs/symbols.md` and `docs/facts.md`, then a read of the methods found - not an agent.
- Never read a class of thousands of lines whole; read the methods the index names.
- Screenshots go to subagents. Every image read stays in context until compaction, so
  in-game test screenshots are taken and read by a subagent that returns what it saw
  (text, figures, anything wrong) - never Read a screenshot in the main thread unless
  the user asks to see it there. The project `.claude/settings.json` also compacts at
  350K (`CLAUDE_CODE_AUTO_COMPACT_WINDOW`) rather than near 1M.
- `docs/code-map.md` lists what each class in `src/threatinc/` is for. Check it first to
  locate a feature or bug. When you add a new class - or change what an existing class is
  *for* - update its one line there in the same change; editing logic inside a class needs
  no update.
