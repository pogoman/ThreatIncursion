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

## 2b. hw62 - the three fixes (running from 00:42; filled in below when read)

## 3. For the user

- Founding pace: pre-war banking under exposure 0 is what flipped the sector. If 38-41 hives at the
  opening is too many, the lever is the pre-war want (one swarm a colony), not the war.
- Colony sustenance takes 87k of the swarm's 197k supplies a month at 99 hives (`feedShareConsolidate`
  0.5) - the next shape if supplies still bind after hw61.
- Human yards at 0 FP/mo by the end of hw60b/c: the swarm razes the worlds that build hulls. No hull
  convoy sailed in any hw60 game (hw58a: 9) - a bug, fixed for hw62: `hullsSurplus` asked the donor for
  no unrebuilt losses at all, which no faction at war has (Tri-Tachyon held 3,400 free with 640 lost
  while the Independents waited at 0 free, 3,564 to rebuild). Free hulls are already net of the debt.

Standing rules are in `CLAUDE.md`; nothing was pushed.
