# The offline war simulator

SPEC 2026-10-02, approved the same day (section 10), build in progress. A standalone program that plays the
strategic war (swarm against the human factions) outside the game, in seconds, over many seeds,
so balance and strategy changes are tried offline and only the winners get an in-game run.

## 1. Why

- A 100-month in-game run takes about 33 minutes plus analysis; one night bought five runs
  (`war-council-runs.md`).
- One run per variant cannot separate a change from luck. Six runs of one sector have ranged
  from extinction to a 78-world snowball (`facts.md`, "Does loading an old save behave like a new
  game?"); the four council runs of 2026-10-02 ended at 135-201 hives.
- The logs cannot be replayed: any rule change parts the run within months (pd4a-pd8a match to
  month 36). They are used to **calibrate** rates and to **validate** the model (section 6).

It answers strategy and balance questions (council tempo, the Hive Mind, stance, what binds:
fuel or supplies). It does not find bugs in the mod's own code - the late hunt release and the
double-counted siege of 2026-10-02 needed real runs, and that kind still will.

## 2. Shape

- `tools/warsim/`, plain Java 17, no game jars on the classpath, built by `tools/warsim/build.ps1`.
- **Knobs** come from `data/config/settings.json` (the same `threatinc_*` keys and defaults);
  `-set key=value` overrides one for a run. A tweak that wins offline is the same edit in game.
- **Shared rules.** A new package `src/threatinc/rules/` holds pure functions (numbers in,
  numbers out, no `com.fs.starfarer` import). The mod's classes call them and the simulator
  compiles the same sources. This is what keeps the model from drifting. First lift (all already
  pure maths inside their classes):
  - council: `ThreatWarCouncil.scores`, the band, `draw`, `learn`, the focus weights;
  - plays: phase transitions and shares in `ThreatPlays` (share of means, muster floor, starve checks);
  - fights: `ThreatAbstractBattle` loss shares, the daily siege call-off, suppression, return
    fire, `beachheadNeeded`;
  - reach: passage fuel, `tripSupplies`, `razeArrivalDays`, `haulFuel`, `siegeCapacityFP`;
  - swarm: `ThreatStance` triggers, `ThreatPosture` pressure/need/want and modes,
    `ThreatColonyUpkeep` sustenance and growth, `ThreatFuel.foundingCost`, the claim cap.
  The planner's score and trust formulas follow when the planner is modelled.
- **Modelled, not shared** (too tied to live game objects): fleets, routes, the vanilla economy,
  intel gathering, the ground front. Section 4 says how each is abstracted.

## 3. State

- **Map:** systems with coordinates; distance in ly; travel at `EST_LY_PER_DAY` 0.5.
- **Human colony:** faction, size, defence strength, the four reserves (marines, armaments, fuel,
  supplies) and their accrual per 30 days, front state.
- **Human faction:** mobilised or not, war state (strikes suffered), council (picture, strategy,
  review date, learned weights, personality), plays in progress, or the planner's prongs.
- **Hive:** system, size and growth progress, structures (forge, fuel, Nexus, Fabrication Core,
  military tier), FP bank, garrison FP, disruption clocks, fortification condition.
- **Swarm:** fuel and supplies stocks, stance, posture per system (pressure, mode), the loss ledger.
- **Fleets:** parcels of FP with an owner, a kind (garrison transfer, strike, wave, siege, hunt,
  squadron, scout, convoy), origin, destination, arrival day, cargo.
- **Knowledge:** per faction and per hive system a report (FP seen, age, source); for the swarm,
  the same per human world (`ThreatIntel`, `ThreatSwarmIntel`).

## 4. What is modelled, and how

Clocks as in the mod: daily (accrual, upkeep, growth, garrisons, fights, squadrons), every 5 days
(posture, stance, fleet upkeep), every 7 (planner), every 30 (the swarm's tick: spread, strikes;
the council's assessment). One seeded random source; a run is reproducible from its seed.

| Piece | Model | Source of truth |
|---|---|---|
| Hive income | FP, fuel, supplies per day from size and structures | `fabricationRatePerDay`, `ThreatFuel.perMonth` |
| Hive growth, upkeep | sustenance `100 x 2.5^(size-3)`, fed share by stance | shared `ThreatColonyUpkeep` |
| Garrison | want from the size table and pressure; built from the bank; 0.04 FP upkeep | shared `ThreatPosture` |
| Reinforcement | whole swarms from donors, arriving after ly / 0.5 days | shared rules, parcel fleets |
| Spread | claim cap, founding cost, weighted pick of a target system | shared; pick weights fitted |
| Stance | EXPAND / PRESS / CONSOLIDATE with dwell and hysteresis | shared `ThreatStance` |
| Swarm strikes | monthly pass, weighted pick, fuel and supplies gates, orbit then landing | shared gates; outcome fitted |
| Human income | per-colony accrual as constants from the map dump, scaled by size on change | dump (`Reserve ledger`) |
| Mobilisation | on the first strike suffered | `ThreatWarState.recordStrike` |
| Council, plays | as built | shared |
| Planner | as built (interval 7 days, confidence 0.8) | shared score and trust |
| Sieges on hives | daily fight against the garrison in orbit, suppression, landing | shared `ThreatAbstractBattle` |
| Ground phase | strata taken at a rate from marines against defence | fitted, simplified |
| Fog | a report per target refreshed by scouts, radar range and arrivals; ages by half-life | simplified |
| Convoys, hauls | stock moves between a faction's colonies at a fuel cost, no interception | simplified |

Left out: the player, on-screen battles, ship composition, missions, omens, Nexerelin, the UI.
All fights are the off-screen kind (15 of 21 strikes resolved that way in h53d, with the player
standing still).

## 5. What the mod must log first

The logs lack two things (inventory of 2026-10-02; pd8a is 75,571 lines in ~100 families):

- **Game time.** Extract lines carry none; only council lines hold a date. Add one `Clock: day N
  (date)` line at each daily poll that logs anything, so every line after it is dated.
- **The map and a starting state.** No coordinates and no per-hive list. Add a debug dump
  (`threatinc_debugSimDump`), written at game start and at each monthly tick to
  `saves/common/threatinc_simdump_<day>.json`: systems and coordinates; each human colony's
  faction, size, defence, reserves and accrual; each hive's size, structures, bank and garrison;
  stocks, stance, mobilised factions. A dump is both a start state and a checkpoint to compare
  the model against.

One in-game run of the PoseidonDeimos new game with both on gives the map, the month-0 state and
a dated trajectory.

## 6. Calibration and validation

**Calibration** (fitted from the existing 113 MB of extracts, then from the dated run):

| Rate | Log lines |
|---|---|
| Hive income, upkeep, banks | `Census: threat`, `Posture sector`, `Upkeep month`, `Garrison fleet fabricated` |
| Founding cost and distance | `Founding bill at`, `Seeding Swarm from`, `Spread to`, `Colony founded` |
| Growth | `Colony grew to`, `Colony upkeep: bill` |
| Reinforcement volume and delay | `Reinforcement:`, `Reinforcement arrived at`, `Posture:` |
| Strike cadence, size, outcome | `Strike launched from`, `Strike ledger`, `Off-screen fight`, `Colony Lost` |
| Human pools | `Reserve ledger`, `Census: <faction>`, `Order draw at`, `Return settled at` |
| Siege fights | `siegeSlice`, `Daily siege of`, `Abstract siege of`, `Hunt battle near` |
| Ground phase | `Front deployed at`, `Front took stratum`, `Strike pass (landing)` |
| Knowledge | `Intel: <faction> sees`, `Swarm intel:` |

**Validation**, in the order the war unfolds. Each gate must pass before the next piece is trusted:

1. **Swarm alone, months 0-36.** Every pd run matches to month 36, before any human siege:
   hives, total size, fleet FP, bank, fuel and supplies against `Census: threat`.
2. **Planner war (pd7a).** Hives 16 / 36 / 30 and kills 3 / 12 / 27 at months 48 / 72 / 97;
   about 2 sieges a month; the swarm in CONSOLIDATE for about half the months.
3. **Council war (pd4a-pd8a).** 135-201 hives at month 97, 0-2 kills, 15-27 sieges sailed,
   Hold in about half the faction-months.

A pass is the in-game figure inside the simulator's seed spread, and the split between gates 2
and 3 reproduced. Gate 2 rests on a single in-game run; two more planner runs of the same game
would tell us the game's own spread (about 35 minutes each, unattended).

## 7. Scoring a run (the user's, 2026-10-02)

What marks a successful AI strategy, for in-game runs and the simulator alike:

- **Humans: forward bases set up and held.** At its simplest, the number held at the end of the
  run (`ThreatFrontlines.all()`; the census's `links`).
- **Threat: spread.** Colonies founded during the run plus size levels gained, weighted up the
  scale: reaching size s scores `w(s)`, so 7 to 8 counts more than 2 to 3. Counted as gains over
  the run, so a run from an established save scores too. Proposed `w(s) = s` (a founding scores
  its starting size), to be tuned.
- **Both: worlds destroyed.** Hives the humans eradicate; human colonies and forward bases the
  Threat takes or destroys. Without it both sides could score by sitting passive.

`score = spread + killWeight x destroyed`, per side, with the weights as run options. **Start
with `killWeight` 0** (spread alone) and watch what emerges, then raise it. The score is reported
per side beside the raw counts, never alone.

**Baseline (2026-10-02 runs).** A forward base is a frontline link (`ThreatFrontlines`; the census's
`links`, not its `bases`, which counts every colony with a depot). By this score the humans are at
nought with council or planner: every base founded is lost.

| Run | founded | lost to a Threat strike or battle | given up, no garrison | held at the end |
|---|---|---|---|---|
| pd4a | 9 | 5 | 4 | 0 |
| pd5a | 42 | 30 | 12 | 0 |
| pd6a | 20 | 13 | 7 | 0 |
| pd7a (planner) | 25 | 16 | 9 | 0 |
| pd8a | 50 | 34 | 13 | 3 |

The log lines the score reads, once dated by `Clock:`: `Colony founded`, `Colony grew to`,
`Colony eradicated`, `Threat ground victory at`, `Frontline: <faction> founded` and
`Frontline: <faction> dismantled (<why>)`.

## 7a. Using it

- `warsim run -seed 7 -months 100` writes a monthly CSV (hives, sizes, FP, stocks, stance, kills,
  worlds lost, sieges sailed and landed, plays by type and outcome, each faction's pools).
- `warsim batch -seeds 200 -set threatinc_councilHammerShare=0.8` prints the median and the
  spread per month.
- `warsim compare A.set B.set` runs both on the same seeds and reports which differences exceed
  the spread.
- First questions: council tempo options A-D (`war-council-runs.md` 5), hunt upkeep, the Starve
  circle, then the Hive Mind's decisions (`swarm-strategy.md` 4) as prototypes before they are
  built in the mod.

## 8. Build order

1. The clock line and the dump in the mod; one dated in-game run.
2. `threatinc.rules`: lift the pure functions, the mod calling them; a short in-game run to show
   no behaviour changed.
3. The simulator's core and the swarm alone; gate 1.
4. Human pools, mobilisation, sieges and the planner; gate 2.
5. Council and plays; gate 3.
6. Batch and compare; then the first questions.

## 9. Limits

- A model: it can agree with the logs for the wrong reason. The gates and the one confirming
  in-game run per adopted change are the guard.
- New mechanics must be written twice unless they are pure rules; keeping new strategy code in
  `threatinc.rules` is the habit that pays for this.
- The ground phase, fog and convoys are simplified. A question that turns on them (interception,
  what a scout saw) is answered in game.

## 10. Decided (the user, 2026-10-02)

1. **Shared rules package:** yes. The mod's council, stance, posture and battle maths move into
   `threatinc.rules` with no behaviour change.
2. **Two more planner runs** in game, to measure the game's own spread before gate 2: yes.
3. **Dump cadence:** monthly, behind the debug switch, off by default.
4. **Scoring:** section 7.

## 11. As built

**Step 1, the mod's side (2026-10-02).** `ThreatSimDump.poll` (called from `IncursionManager.advance`
before `ThreatFleetOrders.poll`) logs `Clock: day N war M (date)` once a game day and, with
`threatinc_debugSimDump` on, writes `threatinc_simmap.json` once a session and
`threatinc_simdump_d<day>.json` every 30 days to `saves/common` (the game adds `.data`). Game days
are negative (the clock starts before 1970), so the file names do not sort; `Main.dumpDay` orders
them. A new game's first dumps hold no hives: the swarm has not landed.

**The skeleton (`tools/warsim/src/warsim/`).** Build with `tools/warsim/build.ps1`, run with
`tools/warsim/warsim.ps1 run|batch|compare|check`.

- `State`: the war at one moment - systems, `Hive`s, `World`s (colonies and forward bases),
  `Faction`s, `Parcel`s (fleets in flight or on station), the `Swarm`'s stocks and stance - and the
  only place worlds are founded, grown or destroyed (`foundHive`, `growHive`, `killHive`,
  `foundForwardBase`, `loseWorld`), so the score is counted once. `count(name, n)` adds to a
  cumulative counter; every counter is a column of the monthly table.
- `Side`: `init`, `daily`, `arrive(parcel)`. `SwarmSide` runs first each day, then `HumanSide`,
  then arrivals. A side keeps its own 5, 7 and 30 day clocks.
- `Start`: reads the map and a dump into a fresh `State`. `Knobs`: `settings.json` by the mod's
  keys, `-set key=value` on top; a missing key throws.
- `Sim.run` gives a row a month (`Sim.row`: what stands, then the counters). `Main`: `run` (one
  seed, optional CSV), `batch` (median and p10-p90 over seeds), `compare` (two knob sets on the
  same seeds; "clear" when the medians sit outside each other's p10-p90 and three seeds in four
  agree), `check` (a real run's dumps beside the simulator started from the first of them).
- Ownership, so the two sides can be built apart: the swarm side owns `Hive`, `Swarm`,
  `SwarmSide` and `Swarm*.java`; the human side owns `World`, `Faction`, `Front`, `HumanSide` and
  `Human*.java`. Shared rules live in `src/threatinc/rules/` (`BattleRules` so far).
