# Testing handover - 2026-10-13: hive guns x3, engaged reinforcements leashed, saturation unrest by what it broke

For the session running general seed tests on another machine, to measure what the 2026-10-13 morning
build does to the NPC war. Read the repo's `CLAUDE.md` first, then `suppression-balance.md` (the
return-fire table under section 1 and "Hive return fire" under section 7) and `swarm-defence.md`
("Fight on arrival"). How to run the harness, the mods, the pristine save and the user's standing rules
are unchanged from `handover-2026-10-05-testing.md` sections 2-8; the log lines the provisions and orbit
builds added are in `handover-2026-10-11-provisions-testing.md` and `handover-2026-10-12-orbit-contest-testing.md`.
`git pull` brings the jar (`jars/ThreatInc.jar`, committed with this file, built from `ef91651` plus the
hive knobs below); do not rebuild unless the source changes. Settings: shipped defaults. The four new
keys (`threatinc_hiveBombardReturnFirePerGunDefence`, `threatinc_hiveBombardFPWorth`,
`threatinc_defendInboundRange`, and the earlier `threatinc_defenseDeficitWeight`) have LunaSettings rows
bar the last; LunaLib defaults a new key, no migration.

## 1. What is being tested (all untested in a batch)

All three came out of the user's own game this morning, besieging Delta Mengryla I with one fleet.

| Change | What it does | Where |
|---|---|---|
| **Hive guns fire at 0.0024, three times a colony's 0.0008; a hive commander's worth is 10, not 30.** | `ThreatGroundFronts.returnFireConstant(market)` / `bombardWorth(market)` pick the hive theatre's knobs (`hiveBombardReturnFirePerGunDefence`, `hiveBombardFPWorth`) in `returnFirePerDay`, `bombardPlan`, `razePlan`, `fabricateCost`, `orbitSpent` and `defendWorth`; colonies read the shared knobs as before. The user's call ("a size 8 hive over a 30 day siege with heavy batteries should annihilate multiple 1000fp of fleets"), taken from stop-aware tables rather than 0.008: at a size-8 Heavy Batteries + Command hive (D 12,480) a commander with marines still bombards from 6,000 FP up and loses 275 over 51 days (91 before); a short-handed 3,000 FP commander softens 70 days for 436 (141); a player fleet of 1,000 / 3,000 / 6,000 sitting 30 days loses 510 / 359 / 259, nothing dies. `facts.md` "DECISION 2026-10-13", `suppression-balance.md` "Hive return fire". **This is the one that moves the NPC war** - a knowing pre-0.8 exception. | `ThreatGroundFronts.returnFireConstant`, `bombardWorth` |
| **Engaged reinforcements are leashed.** | A reinforcement that fought on arrival (`engageOnArrival`: aggressive, blinders off) was dragged across the system by vanilla's pursuit AI - three swarms of 184 / 368 / 293 FP stood 13-15,000 units from Delta Mengryla I for weeks. `ThreatColonyManager.enforceReinforcementLeash` (per frame, beside the garrison leash) walks a reinforcement inside its target's system, beyond `ORBIT_HOLD_RANGE` (1,500) and out of a live battle, back blinkered, clearing the engaged marks so the poll re-engages it on return. Engagement on arrival is player-only, so off-screen this should fire rarely; where it does, it is a reinforcement with no live battle sitting outside 1,500 units of its target with an order other than GO_TO_LOCATION to it. | `ThreatColonyManager.enforceReinforcementLeash` |
| Far fleets no longer lock the hive menu (player-only). | `gateOnDefenders` counts a fleet bound for the world as inbound only within `defendInboundRange` (3,000 su). Nothing for the batch. | `ThreatincMarketCMD.gateOnDefenders` |
| **Saturation unrest scales with what it broke.** | `saturationSlice` raised unrest to a flat 10 (stability 0, defences x0.25 for the 30 months of decay) however little it wore; now 10 x (1 - fortification condition), the tactical rule. NPC razing of colonies and the swarm's saturation (`strikeSaturationEnabled` is off by default) run the same slice, so a razing that strips everything still reaches 10, and one that does not no longer pins the world at stability 0 from its first day. | `ThreatGroundFronts.saturationSlice` |

The same jar carries the 07:35 fuel-or-ships rule (hw172 / hw173, `facts.md` "Guns while the fuel is
good") and the break-up notice; nothing else changed since hw173.

## 2. What to look for

Player-less batch, random seeds from day 0, three games a sector on the usual bases (OceanPena,
ZhiFarah, AphelionDysnomia - hw161's), the `[ThreatInc]` lines of `saves\_sbs\<tag>\logs\starsector.log`.

**A. Sieges of hives cost three times the ships and land the same.**

- `siegeSlice <world> [hive] fp=F defence=D rate=R days=N condition=C loss=L` - `loss` on hive slices
  should read three times hw173's for the same `defence` and `condition` (0.0024 x D x the guns' share);
  colony slices unchanged. Sum `loss` over hive slices per game against hw171-hw173's.
- The commander's stop: hive sieges that bombarded before should still bombard. The worth test is
  `taken < fire x worth` with worth 10 on a hive; the first day of bombardment is gated on it, so a
  siege that lands with no `siegeSlice` at all against a hive it bombarded on hw173's jar is the stop
  biting harder than the tables say (it should not: at D 12,480 a 6,000 FP fleet gets 14 defence per FP).
  Landings at full defence need 10,000+ marines on a size-8 hive - watch `marine postponements` and
  launches against hw173 (`Siege of <hive> postponed: marines`, `launch` lines). A collapse in launches with
  the same worlds is the stop; a rise in postponements with the same launches is landings sized bigger.
- Group aborts: an expedition cut below 0.33 of what it set out with turns home (vanilla's
  `groupAbortsMissionFPFraction`). `Siege Called Off` notices and `turns home` lines per game against hw173.
  Three times the fire should abort some sieges that sat long under Heavy Batteries hives; that is the
  rule working, unless it is most of them.
- The swarm's side is untouched: `defendWorth` for THREAT still reads `swarmWorth` (a hull's worth in
  troops), and only the constant moved. A `Swarm defend` or `supportSlice` line over a Threat-held hive
  showing a fleet-point `loss` three times hw173's for the same figure would mean the swarm is paying
  its own batteries' fire - report it with the line.

**B. The reinforcement leash.**

- `Reinforcement strayed from <world>: <fleet> (N FP) D units out (was: <order>) - recalled` - the new
  line. Off-screen it should be rare (engagement on arrival is player-only). Count per game. A fleet
  recalled every 30 days (the GO_TO_LOCATION expires, the ORBIT_AGGRESSIVE fallback trips the leash again
  because the fleet never got inside 1,500) is a frozen or crawling reinforcement, the hw105-hw120 case,
  and the overdue poll's lines (`Reinforcement overdue: ...`) say which; not this change's fault but worth
  listing with the fleet id.
- A fleet recalled while a reinforcement in transit from another system is simply crossing this one
  with GO_TO_LOCATION already set would not log (the assignment check skips it). If a reinforcement
  bound for a world in the same system is repeatedly `recalled` with `(was: GO_TO_LOCATION -> <planet>)`,
  the de-dupe is broken - report it.

**C. Saturation unrest.**

- `saturationSlice <world> fp=F days=N poured=P through=T razed=R` lines are the razings; the unrest they
  raise is in `raiseUnrest`'s effect, visible as the world's stability in the next `Blockade` / `Siege`
  reads or in the dump's market records. An NPC razing that strips everything in a day still reaches 10;
  a partial first day no longer does. Nothing to count unless a razing's world sits at stability 0
  after a day that wore it under 50% - then the rule did not take.

**D. The war.** Baselines on this jar's predecessor: hw171 (random seeds from day 0, 2026-10-12/13) and
hw173 (the fallen games' snapshots, 2026-10-13 08:03-08:28: a 108 / 80 hives vs hw171a 69, b 82 / 72 vs
hw171b 59, c 87 / 87 vs hw167b 75 - all the swarm's). Expected direction from the guns alone: hive sieges
cost more ships and abort more, so hives at m126 the same or higher and human worlds lost the same or
higher; humans still land the big ones. The user's rules: the Threat ahead in a player-less run is fine
(`threat-may-win-npc-war`); the humans winning an unattended run is the mod not working (`facts.md`
"The humans must not win an unattended run"); no tuning toward parity; humans play to win - if sieges
stop landing, say which stop (worth, abort line, gain < 1 d/day) ended them, from the `bombardPlan` lines
(`Siege of <hive>: bombard N d ...`) and the slices, before proposing a number.

**E. The user's own game.** Delta Mengryla I: a size-3 hive, Ground Defenses only, under the user's
blockade; the three reinforcements come back on load and re-engage within 1,500 units. The user reports
the player side; nothing to do from the batch machine.

## 3. Reporting

Per-checkpoint findings file as always (`test-runs-report-as-they-go`), every finding with a proposed fix
(`issues-come-with-solutions`). Record what the batch measured in `game-runs-2.md` (next section number,
51) and one-line answers in `facts.md`. Ask the user before any in-game run beyond the batch they asked
for (`no-unprompted-game-launch`). Local commits, no push unless the user says so.
