# Handover - 2026-10-08 morning: the overnight iterations

The user's brief (2026-10-07 evening): "solve problems like we talked about with strategy, not just
twisting knobs back and forth. and obviously still bugfix etc". Every batch below ran three games at once
from the pre-war checkpoint `ck2` (war opens at day 2231), 60 minutes each, read with
`tools/test-harness/fastforward/swarm-digest.sh <tags>`. Records: `game-runs-2.md` 43, `facts.md`.

## 1. hw60 - exposure, focus, the front, the corridor (a6e1ad20)

The sector turned over: hives 61 / 102 / 99 at the end (hw59: 6 / 13 / 6). The cause is the pre-war
exposure of 0 - every garrison banked into foundings, 38-41 hives at the war's opening against ~20.
**That is the founding-pace lever and it is the user's call**; nothing was tuned. Humans lost 11 / 62 / 58
forward bases of 35 / 74 / 98 founded; their yards fell from 900 to 300 to 0 FP a month of hulls as the
swarm took the worlds that make them (`answers hulls ... to rebuild at 0/mo`).

What hw60 showed and what changed for hw61 (ca0aec6f) - premises, no knobs:

1. **Supplies bind the swarm's whole war.** hw60c made 197k a month, spent 207k (colony sustenance 87k,
   navy upkeep 8k, fleets away, builds, foundings), stock 0-7k, while 2.5M fuel (70-130 months of demand)
   and 294k FP sat idle; 176 seedings held, 20 of 29 campaign saving lines "not yet kept", 72 waiting-build
   turns, two forge conversions in 77 months. -> **The shortage's answer is paid first**
   (`ThreatFuel.reserved` / `free`): a forge the planner waits to build or convert to has its price
   reserved, everything else draws on the stock above it (the humans' yards-first rule for the hive), and
   `convertSurplus` converts as many fuel plants a turn as the surplus spares.
2. **Strikes sized double.** Met 0.41-0.61 of sized over 240 strikes with `responseRatio` floored at 1.
   -> it learns both ways; a prong is never under the defence seen on the day.
3. **Bug: a colony with reinforcements inbound was stripped to zero** (hw60b Chlorr, 430 FP in flight to
   its system; 24 of b's 41 falls followed a strip). `stagedSpares` tier 1 now measures the station alone.

## 2. hw61 - the four changes above (ca0aec6f), read at 00:40

Hives 87 / 89 / 80 of peaks 87 / 91 / 85; falls after a strip 9 / 0 / 2 of 55 / 17 / 51 (hw60b: 24 of
41) - the station-only room holds. The ratio learns (0.43-0.90x), strikes met 0.54-0.74 of sized. Forges
18 / 52 / 21 built, supplies still spent as made (b: 215k of 205k, 807 in stock). Three things fixed for
hw62 (8ad48286, bac74c67, 22e7185a):

1. **The reserve starved the navy**: b lost 275 swarms (28k FP) to unpaid upkeep because the navy's
   charge drew on the free stock. Commitments (navy upkeep, fleets away) pay from the whole stock now.
2. **The fund hoarded again** (219-293k FP, "nothing the fund pays" 36-55 times): the focus was held on
   fund and fuel alone while every prong at it was too big to feed. The focus now needs a prong the means
   pay with supplies included; the empty-campaign line names the best prong and what kept it out.
3. **No hull convoy ever sailed** (hw60 all, hw61a/b): the donor test asked for no unrebuilt losses at
   all. Free hulls are net of the debt; that alone is the test now.

## 2b. hw62 - the three fixes (d7dd9679), read at 01:55

(Result figures below, "hw62 read".) Mid-run (01:11-01:25) it already showed the two shapes built for
hw63:

1. **The fund hoards for a real reason now: the supplies spare is zero or negative by design.** The
   new empty-campaign line names it: "the best prong, Zeta Toyol I Forward Base (553 FP), is out on the
   supplies away: 78 for 56 FP over 57 days, -2282 kept (0 free, -1091/mo spare)". The fund climbed
   38k -> 141k FP in all three games and never fielded a prong of its own, while hw62a's hives fell
   76 -> 47 to Tri-Tachyon's fronts. Where the month goes (hw62a, 89k made): fleets away 22.5k, navy
   charge 22.9k, sustenance 31k, and the feed's spare keeps sustenance/0.7 - an **expansion tithe**
   of ~13k - plus 5k a seeding (110-173 a game). Spread eats every month's supplies; the war chest sits.
   -> **The chest** (`ThreatStance.chestFull`, my shape, to confirm): a strike fund holding a horizon's
   saving (`strikeFundPerMonth` x `offensiveHorizonMonths`) with a known target is full, until spent
   below half. Full, whatever the stance: the feed's spare is the production less what the colonies
   actually took (the tithe feeds the fleets), nothing is founded the supplies surplus does not pay,
   and a full chest is a reason to PRESS when not losing. Spread resumes when the chest is spent.
   The alternative shapes, not built: forage (prongs bring a taken world's stock home - a new mechanic,
   your call), a founding kit that carries a forge (your lever), a smaller sustenance share (a knob).
2. **Still no hull convoy** after the donor fix: the second gate was `pickAllyDonor`'s minimum load,
   read off the cargo hold as 30 units (3,000 FP) - hw62b's Tri-Tachyon had 25 units to spare and
   sat. Hulls sail by the unit now (a ship is a load), and `aidHulls` logs the gate each helper
   stopped at while nobody sends.

**hw62 read (01:52):** hives 48 / 66 / 75 of peaks 76 / 81 / 80 (b ran slow, to war day 3811);
eradicated 75 / 35 / 32, after a strip 6 / 3 / 4; navy losses to unpaid upkeep 0 / 0 / 0 (fix 1 holds);
hull convoys 1 / 1 / 2 (fix 3: the first since hw58a); the fund 172k / 168k / 230k FP at the end,
climbing every month; "nothing the means pay" 65 / 38 / 60. Human yards 0-1,300 FP/mo at the end.
`game-runs-2.md` 43.

## 2c. hw63 - the chest and hull units (54db5cdc, jar 01:52; running from 01:53)

What to look for in `swarm-digest.sh hw63a hw63b hw63c`: the `-- fund:` trajectory should turn down
after the first `chest: full`; `chest: full N, spent M` should alternate; seedings held should rise
while the chest is full and campaigns should launch on the fund; `hull convoys` above 1-2, and the
`no-hulls` line names the gate when none sail.

## 3. For the user

- Founding pace: pre-war banking under exposure 0 is what flipped the sector. If 38-41 hives at the
  opening is too many, the lever is the pre-war want (one swarm a colony), not the war.
- Where the swarm's supplies go (hw61b, last three months, ~210k a month spent of 205k made): the
  fleets away on strikes ~80k (34k FP launched a month, each away ~3 months), colony sustenance 54k,
  navy upkeep 25k, planner builds ~25k (19 in three months), seedings 17k (10 at 5k each), growth
  1-27k. The offensive runs at the supplies limit by design (navies bound by supplies); fuel sits at
  1.0-1.4M with 540k-1.4M banked. Nothing here is a bug; the levers are the forge/fuel mix a founding
  kit starts with and the sustenance share - the user's.
- Human yards at 0 FP/mo by the end of hw60b/c: the swarm razes the worlds that build hulls. No hull
  convoy sailed in any hw60 game (hw58a: 9) - a bug, fixed for hw62: `hullsSurplus` asked the donor for
  no unrebuilt losses at all, which no faction at war has (Tri-Tachyon held 3,400 free with 640 lost
  while the Independents waited at 0 free, 3,564 to rebuild). Free hulls are already net of the debt.

Standing rules are in `CLAUDE.md`; nothing was pushed.
