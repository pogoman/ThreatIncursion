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
