# Handover - 2026-10-11 14:10: batch gs1 (random seeds from day 0, laptop)

For the session on the other machine. Review the logs; nothing here is built into the jar yet.

## The batch

- Build: jar 13:21 (1a5599a2, founding convoys + carried provisions), shipped defaults. This laptop's LunaLib
  store had `threatinc_reserveSurplusMult` 1.5 (stale); set to the shipped 1.0 for the run with `-Knobs`.
- `sbs.ps1 -Tags gs1a,gs1b,gs1c -Base "gs1a=save_AmaruDugas_...;gs1b=save_AphelionDysnomia_...;gs1c=save_AmaruDugas_..." -Days 3800 -Snapshots 300`,
  13:22-14:07. Tags `gs1` so they do not collide with the hw numbering.
- c reached war day 3,813, b 3,809; a was stopped at ~w3,500 by the user's "end it". No real exceptions.
- Logs: `tools/warsim/validation/gs1{a,b,c}/ti-gs1*.txt` (full `[ThreatInc]` log), `exc-gs1*.txt`, monthly dumps
  beside them; the mid-run digest `gs1a/gs1-findings-mid.txt`. Digest: `bash tools/test-harness/reads/read-hw156.sh gs1`
  after copying the `ti-` files to `%TEMP%\threatinc-tests` (or point `T` at the folder).
- Snapshots stay on the laptop only (`saves\save_<seed>gs1<g>d<war>`).

## What it showed

- End (last census): a 97 hives / b 159 / c 141, swarm found 87 / 133 / 120 - the swarm's in all three.
  Mid-run (w~2,500): 65 / 99 / 87 hives. gs1b (AphelionDysnomia) against hw154c's 100 / 20 on the 10:02 jar.
- **Bug, founding convoys: 15 of 28 arrived "short" at mid-run, every one with 5,500 / 5,500 supplies and
  672-745 of 800 fuel.** Vanilla's engine burns the convoy's cargo fuel as it sails (3-9 ly), after
  `dispatchFounding` already paid the passage from the stock, and the whole-kit test sent it home. A fight takes
  supplies before fuel (`trimToHulls`), so fuel alone short is that burn. **Fix in source, NOT BUILT, NOT TESTED:**
  `ThreatConvoys.pollFounding` calls a convoy short only on supplies and founds on the fuel aboard. Build and
  rerun hw156's check (`check-founding.sh`): "arrived short" should fall to fights only. The same double burn
  silently trims every supply convoy's fuel delivery (not fixed, not measured).
- Digest bug fixed: `read-hw156.sh` counted every `Fleet upkeep` line as owing (the line has no "owed" when 0).
  Real owed counts were not read yet.
- Provisions at mid-run: order draws 213 / 225 / 366, restocked 115 / 102 / 102, stood down supplies low
  50 / 33 / 19, sieges short 0, out of supplies 0.
