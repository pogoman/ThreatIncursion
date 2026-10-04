# Handover - 2026-10-04 morning (the overnight session)

Written by the overnight session of 2026-10-04 (01:15-09:00, the user asleep: "Yes I'm going to sleep 8 hours do as
much testing as you need"). Local commits on `main`, nothing pushed. Read section 1, then section 2.

## 1. Where it stands

- **Your decision is in: supplies bank like fuel** ("Yeah keep them in line then"). Built as
  `threatinc_reserveWartimeSuppliesShare` (LunaLib, 0-1, default 1 = what you approved; 0 = the old surplus rule): a
  war faction banks that share of its worlds' peacetime supplies demand on top of their surplus
  (`ThreatReserves.wartimeShare`). It touches the NPC factions' war reserves only; your colonies' reserve is vanilla's
  stockpile, as before.
- **It ends the famine at any share from 0.25.** Twelve game runs on the hw4 sector tonight (section 3). At share 0
  the humans starve by months 108-126 and 15-37 worlds fall. From 0.25 up they lose 1-5 worlds by months 128-144,
  relief keeps sailing and nothing runs dry. 0.1 is the edge: it held to month 126, then the famine came back.
- **But supplied, the swarm stalls.** Full depots read to it as sieges about to sail, so it sits in CONSOLIDATE (which
  founds nothing) for years. Five of the eight supplied runs on the default stance spent 36-72 of their first 120
  months there (all three at share 1, one of three at 0.25, the one at 0.1); the share-0 runs spent 14-16. hw4s held
  at 14 hives from year 4 to year 12, and hw5c (0.25) had 48 hives at month 120. That is the stalemate your guard on
  the NPC war rules out ("quiet/stalemate shares not rising").
- **The existing knob `stanceConsolidateShare` 0.75 shortens the stall but does not end it.** Three runs at share 1:
  2, 0 and 31 months consolidating, against 39-72 on the default stance.
- **Mod changes tonight:** 7fe2a9f (wartime supplies; the simulator can scale supplies income), 7669f45 (a monthly
  `Reserve budget:` line per faction in the debug ledger), 4eb2a8d (the on/off knob became the share). The rest are
  run dumps and docs. No default changed past what you approved.
- **Local commits only**, nothing pushed. The game is closed. Your LunaLib store has the share at 1.0 (the default)
  and `stanceConsolidateShare` at 0.5 (the default).

## 2. Waiting on you

### 2a. The share and the stance

| setting | runs | worlds lost by month 126 / at the end | Threat landings by month 120 | months in CONSOLIDATE by month 120 | hives at month 120 |
|---|---|---|---|---|---|
| share 0 (old rule) | hw4u, hw4r | 26 / 29, 15 / 37 | 101, 81 | 14, 16 | 147, 107 |
| share 0.1 | hw4x | 2 / 5 (famine back from month 126) | 40 | 36 | 119 |
| share 0.25 | hw4w, hw4z, hw5c | 1 / 1, 3 / 5, 3 / 3 | 47, 78, 17 | 27, 7, 53 | 143, 131, 48 |
| share 0.5 | hw4v | 2 / 3 | 40 | 24 | 101 |
| share 1 (approved) | hw4s, hw4t, hw4y | 1 / 1, 1 / 1, 2 / 2 | 12, 24, 20 | 72, 57, 39 | 14, 65, 101 |
| share 1, `stanceConsolidateShare` 0.75 | hw5a, hw5b, hw5d | 1 / 3, 3 / 6, 1 / 1 | 40, 55, 22 | 2, 0, 31 | 116, 167, 75 |

The share decides the famine; the stall comes from the stance. Any share from 0.25 ends the famine, but no share ends
the stall: it struck at 0.25 (hw5c) as at 1. `stanceConsolidateShare` 0.75 made it rarer and shorter. Even so, hw5d
still consolidated for 31 months, with 6 of its 7 systems pressed.

**My recommendation: keep share 1 as you approved it, and fix the stall at its root (2b, option 1).** The cause is
the swarm reading full depots as sieges about to sail. Raising `stanceConsolidateShare` to 0.75 is the stopgap you can
set today in LunaLib: tested in three runs, it helps but leaves one stall in three. Both are changes to the swarm's
AI, so they are your call. 0.25 with the stance at 0.75 is untested.

### 2b. Why the swarm stalls

`ThreatPosture` reads each human staging base's stock as the siege it could pay (`siegeCapacityFP`: the lesser of fuel
and supplies over their price a point, x 25 FP). Supplies used to bind that figure. With full depots it balloons, and
the hive systems the bases stage against turn THREATENED. `ThreatStance` consolidates when half its systems are
pressed (`stanceConsolidateShare` 0.5), and a consolidating swarm founds nothing. So it stays at 4-10 systems, where a
few pressed systems keep it at the threshold, and it oscillates there for years until an expand window takes it past
about 11 systems. It is not a misread: sieges have no fleet ceiling, so the stock does pay that siege. But the humans
rarely send it (one major play a faction at a time).

Options (none applied):
1. Discount a staging base's figure the longer it sits without sailing, so the swarm learns that a base that never
   sends is no threat. New code, behind a knob, default off until a game run says otherwise. My pick.
2. `stanceConsolidateShare` 0.75 (above): an existing knob, a partial fix.
3. Let a faction run a second major play when its means are far above the swarm it faces. That changes NPC offense,
   so it is your call.

### 2c. Supplied NPC humans barely lose

From 0.25 up, the NPC humans lose 1-6 of 59 worlds in 11 years, even when the swarm runs free (116 and 167 hives at
month 120 with the stance at 0.75). You said the Threat may win the NPC war. This is the other way round: the Threat lands 12-78 times by
month 120, against 81-123 on the old rule (hw4p, hw4q, hw4r, hw4u), and in your game you add your own weight to the
humans'. Is that the war you want, or should the swarm get something back? I have not touched it.

## 3. Runs

`war-sim-real-runs.md` 22 has the run-by-run table: share, worlds lost, supplies banked, hives, landings,
consolidation, relief and the check. It also has the mechanism: the `Reserve budget:` lines show why the step
between 44k and 60k a month is so sharp. The findings as they came are in
`%TEMP%\threatinc-tests\overnight-20261004.md`. Dumps for every run are in `tools/warsim/validation/hw4s` to `hw4z`
and `hw5a` to `hw5d`.

The simulator cannot judge the share. It banks by `HumanFit` (fitted to the surplus rule), and its swarm spends 4-8
months in CONSOLIDATE in every check, where the game spent 7-72 on the default stance. Bringing it level means banking
by the share rule, which needs each world's availability and peacetime demand in the dumps. That is the next
simulator step if you want it.

## 4. Machine-local

`%TEMP%\threatinc-tests` holds the run scripts:
- `run-go.ps1 -Tag -Clone [-Days] [-Knobs "k=v;k=v"]`: one new-game run with wrap-up and restore. It puts back only
  keys that existed before.
- `night.sh <tag>`: worlds lost, relief, upkeep, break-up, supplies income by year, stocks.
- `stance.sh <tag>`: the swarm's stance changes, with its pressed systems.
- `stocks.pl <tag>`.

Saves `...ng21` to `...ng32` are clones (hw4s-hw5d); the pristine save is untouched. Your LunaLib store still
holds the unused `threatinc_reserveWartimeSupplies` key from the first build; it is harmless.
