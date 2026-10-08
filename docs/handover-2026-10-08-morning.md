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

**hw63 read (03:16)** - the chest does what it was built for, and the sector swings hard to the
swarm. Hives 88 / 64 / 44 of peaks 88 / 64 / 45 (hw62: 48 / 66 / 75); eradicated 0 / 4 / 8 (hw62:
75 / 35 / 32); campaigns 48 / 22 / 17 (hw62: 15 / 11 / 16); the fund cycles between the horizon
and half of it (chest full/spent 9/8, 4/4, 2/2) instead of banking 170-230k; "nothing the means
pay" 12 / 30 / 8 (hw62: 65 / 38 / 60); hull convoys 13 / 71 / 80 (hw62: 1 / 1 / 2). Costs of the
shape: seedings held 476 / 177 / 120 while the chest is full (b and c founded 64 / 45 hives against
hw62's 81 / 80), and c lost 450 FP of small swarms to unpaid upkeep with the stock at zero (hw61b:
28k). Humans: forward bases 49 / 111 / 145 founded, 45 / 126 / 132 lost; yards 0-500 FP/mo at the
end; 313-432 plays unpaid. a is won outright by war day ~4300: nothing known left to strike, 700k
supplies and 237k FP banked. `game-runs-2.md` 43. (The first hw63 launch was discarded: it loaded
while hw62's cleanup restored your settings and ran 918 days unlogged - `facts.md`, "Never launch a
batch while the last one is still finishing".)

## 2d. hw64 - the chest on any known world (e4ed546b, jar 03:17; running from 03:17)

hw63's chest still needed a weak target (`weakTargets`, which reads none while every hive system is
pressed at home); hw64 needs only a known world, so a hive pressed everywhere spends its fund on
the relief and near strikes. Otherwise hw63's jar: a confirmation batch of the same shape against
hw63's spread (a 88 / b 64 / c 44 - the variance is large).

**hw64 read (04:16):** confirms hw63. Hive peaks 74 / 91 / 40; eradicated 3 / 3 / 11; campaigns
23 / 49 / 23; navy losses 0; hull convoys 83 / 47 / 52; the fund cycles in a and b, and in c climbs
to 140k under a chest full once and never spent - there the supplies spare binds outright (made
99.7k, spent 98.5k a month), which is the design. Humans: bases founded 136 / 61 / 109, lost to
strikes 64 / 47 / 70; yards 0-1,700 FP/mo at the end. `game-runs-2.md` 43.

## 2f. hw65 - bases bound by hulls (a46ad657, jar 04:17; running from 04:17, ends ~05:20)

Section 2e's shape 1. Read it with `swarm-digest.sh hw65a hw65b hw65c`: the humans line should show
`founding held for hulls` above 0 and bases founded well below hw64's 61-136, with fewer lost to
strikes; what the held supplies and fuel then buy (yards at links, convoys) is the question for 2e's
shape 2. If the batch is still running when you read this, `sbs-status.txt` says `done` when it is.

## 2e. The humans' collapse (hw63 read, 03:20) - bases bound by hulls, built for hw65

With the chest the swarm wins all three hw63 games, and the humans' side shows one binder: hulls.
Every faction's plan at the end reads `hulls 0 free of 1,300-1,900, 1,000-1,800 to rebuild at
0-300/mo` beside fuel for 24-226 months and supplies that hold. Yet they founded 111 / 145 forward
bases (b / c) and lost 126 / 132, 70 of b's to Threat strikes of ~650 FP: a link's founding is
gated on fuel and supplies, a rear link needs no guard, and the front link's guard sails capped at
the free hulls - zero. Each base is a kit of 1,500 supplies and 800 fuel handed to the swarm's
"worlds destroyed" score. Two shapes, the first built (my call, to confirm):

1. **Bases bound by hulls** (`ThreatFrontlines.planFor`, hw65): a faction founds a link only while
   its free hulls cover the site's guard need - the mirror of "no swarm its supplies cannot keep".
   The surplus then waits for the yards (the "holds N supplies for heavyindustry" rule) instead of
   buying paper bases. Log: `Frontline: F founds no link at S - N FP of hulls free, its guard
   needs M`.
2. **Yards at home, not at the front** (unbuilt, your call): the humans' hull answer is a Heavy
   Industry at a size-3+ forward base (`buildStep`), never at a core world, so the yards the swarm
   razes in the core (0-500 FP/mo at every hw63 end) are never replaced. The shape would let the
   faction's planner build the shortage's answer at its largest core world with a free slot, and
   the council's HOLD focus guard a disrupted yard's orbit before any expedition.

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
