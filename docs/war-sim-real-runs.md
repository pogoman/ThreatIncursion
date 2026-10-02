# Real runs against the simulator

What the long in-game runs showed that the offline simulator (`war-sim.md`) is checked against, run by run. The
dumps are in `tools/warsim/validation/<run>`; the logs are machine-local. Start here before asking "does the game do
what the simulator says" - then `war-sim-swarm.md` 11 (pd9a, pd10a) and `war-council.md` 16 (pd4a-pd8a).

## 1. tr1 (2026-10-02, the docked laptop): 113 months from a three-planet home, council on

A new game on the round-20 build (shipped defaults, LunaLib marker 12), run in two legs: `tr1a` to month 50 and `tr1b`
from its quicksave to month 113 (war day 3,436). Dumps `tools/warsim/validation/tr1a` (116 monthly dumps and the map); logs
`%TEMP%\threatinc-tests\ti-tr1a.txt`, `ti-tr1b.txt`, joined as `ti-tr1.txt`. No mod exception in either leg.
`check -dumps tools\warsim\validation\tr1a -seeds 30 -log <ti-tr1.txt> -set threatinc_warCouncil=true`: 134 of 218
figures inside the simulator's p10-p90.

**The built feed shares (round 20's owed game check, done).** `Colony upkeep: ... stance <name> share <f>` reads 0.5 in
EXPAND (75 months), 0.7 in PRESS (35) and 0.5 in CONSOLIDATE (2): `feedShareConsolidate` 0.5 is what the game runs.

**The swarm.** From three worlds (section 17 of `war-sim-rounds.md`) to 23 hives at month 60, 48 at month 84, 131 at
month 108 and 168 (size 665, 204k FP of fleets) at month 113 - the upper half of the simulator's band (68.5 [8-137] at
month 108). It struck 176 times by month 108 (simulator 109 [45-251]) and spent 2 months in CONSOLIDATE where the
simulator's swarm spends 32 [11-57]: nothing the humans did made it draw in.

**The humans do far less than the simulator's.** First mobilisation month 47. Cumulative at months 60 / 84 / 108, real
against the simulator's median [p10-p90]:

| | real | simulator |
|---|---|---|
| sieges sailed | 8 / 19 / 28 | 14 / 37 / 49.5 [16-82] |
| sieges landed | 1 / 4 / 4 | 7.5 / 20 / 24 [8-50] |
| hunts sailed | 0 / 7 / 18 | 29 / 71.5 / 111 [65-161] |
| hives killed | 1 / 2 / 3 | 4 / 12 / 14 [4-26] |
| forward bases founded | 8 / 32 / 50 | 10 / 23 / 35.5 [26-57] |
| forward bases destroyed | 4 / 18 / 35 | 3 / 8 / 16 [8-24] |
| forward bases held | 4 / 10 / 6 | 5 / 7 / 11.5 [5-22] |

A year, since the first mobilisation: the Threat destroys 8.2 (7.2 of them forward bases) against the simulator's 4.2
[2.5-6.5]; the humans 0.5 against 2.3 [0.6-4.2]. One siege in seven lands (the simulator: one in two); of the
expeditions that ended, 17 were aborted, 8 destroyed, 5 completed, and 11 sieges were called off over the
orbit. The simulator's humans are too effective by a factor of about five in hives killed, and its links survive twice
as well. That gap, not a knob, is the next thing to work on (section 2).

**Guards called against a seen strike** (`guard-digest.ps1 -Tag tr1b`, months 50-113; the question of
`war-sim-rounds.md` 16a): 73 calls to a front link (`strike in reach`), 19 to a rear link (`strike on its way`), 6
guards sent behind the front, 11 guards sailing home turned back; 21 links faced a strike with no guard called, all
"cannot pay the voyage" (600-4,800 FP), and 20 more could not be guarded on later polls. About 1.4 calls a war-month;
the simulator's `warsim_reliefToBesiegers` asks 209 a run, about 2.5 a war-month, and its base has no call at all.
With the call in the game, 35 of 50 links still fell.

**What the sieges wait for** (the open thread of round 20, closed). 587 postponements in leg b, all for provisions.
577 carry a razing reserve - `Expedition postponed ... 0/22374 fuel past 240322 for razing`: they are the STARVE play's
saturation expedition (`ThreatPlays.saturate`), which razes every hive of the play in the system and so has the fuel
of each one's whole saturation set aside (`IncursionManager.expeditionFuel`, the sum of `razingFuel` over the raze set):
a median 240k fuel (34k-745k), ten times its passage and ordnance, for a flotilla of a median 3,336 FP (232-11,367).
24 saturation expeditions sailed all the same - a late pool does hold it (the League: 291k fuel at month 113) - and 123
times the play logged "no saturation expedition the pools pay". The other 10 postponements were short of supplies.
So the game's sieges are short of fuel as the simulator's are; the supplies-short postponements of pd10a were a planner
run's. The simulator prices a saturation at one world's stay (`HumanCouncil.saturate`), the game at every world of the
play.

## 2. What to do about the gap (proposals, not built)

1. **Find where the simulator's humans get their kills.** Its sieges land one time in two, the game's one in seven.
   Compare, in the simulator and in `ti-tr1.txt`, what a siege meets on arrival (the `Siege called off over <world>: N
   FP of swarms against N` lines and `Abstract break-off` against the simulator's break-off counters) before touching
   any knob: a simulator that lands too easily ranks every human knob wrongly.
2. **Price the saturation as it is flown** - one world at a time, each paid when the last is done - in the game, or
   price every world in the simulator; then let the simulator say which the humans' score prefers.
3. **The hunts.** 18 in nine years against 111: read the hunt gate (`ThreatSoftening`) against the simulator's
   `huntsSailed` rule the same way.
