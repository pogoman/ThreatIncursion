# War simulator, round 30 (2026-10-04): the supplied stall, test-driven

The user's rule from this round: every fix is driven by the simulator, test-driven; a game run is acceptance only,
once the simulator's numbers are good (`facts.md` Decisions). The fault to reproduce is the stall of
`handover-2026-10-04-morning.md`: with supplies banked (`reserveWartimeSuppliesShare` 1), hw4s sat in CONSOLIDATE from
month 46 to month 122 at 13-16 hives, while the NPC humans lost 1 world and killed 5 hives.

Scripts and outputs are machine-local in `%TEMP%\threatinc-tests\sim20261004` (`council.pl`, `posture.pl`, the
`s*-*.txt` checks). The month-44 bench is a copy of `tools/warsim/validation/hw4s` without its first 44 dumps
(`check` starts from the earliest dump, so it starts from the game's own state at month 44). The dumps' day numbers
are negative: the earliest dump is the most negative.

## 1. What the game run shows (hw4s log, from month 44)

- **The handover's account was half right.** The 432 postponements are the STARVE play's saturation expeditions
  (319 against Alpha Laphirial, 110 against Epsilon), short of the whole raze set's fuel and of supplies for a stay of
  about 110 days a world (`IncursionManager.siegeStayDays`, `siegeSuppliesPerPoint`). Hammers are not held by fuel:
  18 of 23 struck. And "one major play a faction" is out of date (`ThreatPlays.majorLimit`, 2026-10-02).
- **The councils are mostly OUTMATCHED**, so they pick STARVE or HOLD (`council.pl`): Hegemony STARVE 44% / HOLD 40%,
  Persean 79% / 16%, Diktat 69% / 28%, the Church and the Path HOLD 66-76%, Tri-Tachyon HOLD 100% (nothing in reach).
  Of about 330 plays ended, 262 are recons, 127 of them "no fresh report by its day".
- **The swarm's THREATENED turns are 80% staged stock** (`posture.pl`), but from month 66 what holds it is a wound:
  Epsilon read BESIEGED for 65 months (m66-m131) with no Threat-side front there. `ThreatPosture.read` wounds a
  system on any disrupted Core, Nexus or Port (`ThreatColonyManager.anyOrganDisrupted`), and the STARVE saturations
  poured 7k-162k fuel a hive into Epsilon (207 `saturationSlice` lines) keeping its Ports and Nexuses down. A hive has
  no razing bar (`ThreatRazing.razes`), so saturation never kills it: the invasion that should follow lands about 330
  marines and is overrun (780 against 331 at Epsilon II, m75).
- **With two systems, one wounded system is half the hive.** From month 77 the swarm held 2 systems; `pressedShare`
  1/2 meets `stanceConsolidateShare` 0.5, CONSOLIDATE founds nothing, so it never grows out of it.

## 2. What the simulator lacked, and what was added (all switches off by default)

| switch | what it mirrors | effect |
|---|---|---|
| `warsim_saturationGameGate` | the game's saturation gate: flotilla sized on the reported orbit x margin or the raze set's fuel / `warsim_saturationFuelPerFP` (100, from hw4s's postponements), supplies for a stay of `warsim_saturationDaysPerWorld` (110) a world, the whole raze set's fuel; all paid or it waits | the home system becomes unaffordable, as in the game (6,250 FP, 640k fuel against 160-250k pooled); full-run hw4s saturations 16 against the game's 15 |
| `warsim_woundByOrgans` | `ThreatPosture.read`'s wound on a disrupted Nexus or Core (the simulator wounded on a front only) | little alone: the simulator's swarm is rarely that small |
| `threatinc_postureStagedHalfLifeDays`, `threatinc_postureStagedFloor` | candidate fix: a faction's staged stock against a system halves each half-life it sends nothing there (`SwarmPosture.stagedCredibility`); not in the mod | no effect on the month-44 bench (see 3) |
| `threatinc_stanceMinSystems` | candidate fix: `StanceRules.pressedShare` counts over at least N systems; the simulator calls it, the mod does not yet | month-44 bench: 75.6 -> 61.5 months in CONSOLIDATE at 3 or 4 |
| `warsim_humansNoOffence` | test double: the humans bank, scout, found and guard bases and relieve, but start no play, siege or hunt | the swarm never consolidates from month 44, staged read whole or off |
| `warsim_stagedMult` | diagnostic: the staged read scaled | 0 changes nothing on the month-44 bench |

Supplies: on the month-44 bench `warsim_suppliesAccrualMult` 1 matches the game's stocks (1.2M against 1.2M at month
36 of the bench); 2 doubles them within a year. Use 1 on a bench started from a share-1 run's dumps.

## 3. Where the simulator still parts from the game

- **From the month-44 state its humans kill four times as many hives** (21.5 against 5 by month 100, with the gate):
  its landings succeed (sieges landed 50+ against 11), its recons come back (14 empty against 127), and it sends five
  times the squadrons (188 against 36 by bench month 36). Its CONSOLIDATE (75 months against 89) is held by real
  attacks and hive losses (`hiveDelta < 0 && attacked > 0`), not by the staged read, which is why switching the staged
  read off or discounting it changes nothing there.
- **From the start its swarm outgrows the game's from month 36** (17 hives against 13; 7 systems against 5 at month 46),
  so it never gets stuck small, and the stall that needs a two-to-five-system swarm does not form (0-25 months in
  CONSOLIDATE against 89).
- Until one of these is closed, the simulator cannot show what a stall fix is worth in the game.
