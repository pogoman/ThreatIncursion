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

## 2f. hw65 - bases bound by hulls (a46ad657, jar 04:17), read at 05:10

Section 2e's shape 1, confirmed: bases founded 3 / 29 / 84 (hw64: 136 / 61 / 109), lost to strikes
3 / 18 / 40 (64 / 47 / 70), foundings held for hulls 84 / 150 / 167. The swarm: hive peaks 80 / 56
/ 49, eradicated 0 / 8 / 4, campaigns 56 / 35 / 35, no exceptions; a is won outright (nothing known
left to strike, the fund climbs to 322k with 630k supplies banked). Yet every faction still ends at
0 free hulls with yards at 0-700 FP/mo, so the held supplies and fuel bought nothing - and the log
shows where the hulls go: **relief fed in piecemeal.** `sendRelief` sails whatever the base can pay
each ground-front poll, 4-10 FP once the pool is at 0 free, at armies of 1-6k FP: 1,881 / 2,888 /
3,356 sends, 34k / 61k / 60k FP a game, 696 / 1,482 / 1,691 relief fleets destroyed (698 sends from
Ailmar to Eldfell alone over 670 days). hw65a's Hegemony went from 4,440 FP and 1,400 FP/mo of
yards to 0 and 0 in ~15 months while the 5,800 FP opening strike's front took Chicomoztoc's strata
over 220 days and 63 relief orders of its own were lost one by one. `game-runs-2.md` 43.

## 2g. hw66 - relief only if enough (edd067f4, jar 05:11), read at 05:52

**Confirmed, and the first contested sector of the night.** Relief sends 127 / 266 / 390 (hw65:
1,881-3,356), relief fleets destroyed 5 / 62 / 95 (696-1,691), held 496 / 702 / 600. The humans
keep a navy where it works: free hulls at the end 19.6k / 9 / 16.1k FP, yards 3,900 / 0 / 2,700
FP/mo (hw65: 0 and 0-700 everywhere). Hives eradicated 42 / 9 / 27 of peaks 35 / 67 / 40 - a's
swarm ends at 32k FP with 0 supplies, c's at 65k; b is hw65 again (every faction's hulls lost,
yards 0, 67 hives), the opening strike's luck. The next binder shows in a and c: forward bases
"cannot pay" 155 / 153 with hulls plentiful - the pools, not the hulls. `game-runs-2.md` 43.
**hw67 (same jar, read at 06:36) confirms it in all three:** relief fleets destroyed 60 / 53 / 26,
held 632 / 583 / 483; every faction keeps yards - free hulls 5.8k / 1.7k / 1.3k FP at the end,
yards 2,400 / 2,400 / 2,100 FP/mo. Hives eradicated 11 / 7 / 30 of peaks 37 / 58 / 38; the swarm
still holds a and b (97k / 185k FP of fleets) and is beaten down in c. Forward bases founded
81 / 112 / 102, lost 46 / 71 / 70, "cannot pay" 123 / 41 / 91. The night's last batch.

## 2g-design. What the relief gate is

The humans' mirror of your rally rule for the swarm (2026-10-05, "if the fleets that can be
gathered aren't strong enough to defend then they shouldn't bother"), under the same
`systemDefenceOnlyIfEnough` switch - my shape, to confirm: `ThreatFleetOrders.reliefEnough` sails a
relief only when what the base can field (its stock above the floor, capped at the faction's free
hulls) plus the guards already bound there outweighs the army over the world; held, it logs once
(`Relief held: F can field N FP from B against M over W (G bound) - not enough, holds`) and keeps
its hulls and provisions, and `reliefOwed` counts only a relief that would sail, so a siege does not
wait on hulls the faction will not send. What sails still holds the orbit as before. Read a batch
with `swarm-digest.sh hw67a hw67b hw67c` and
`grep -c 'Order lost (fleet destroyed): [a-z_]* relieving' ti-hw67a.txt` (hw65: 696-1,691, hw66:
5-95) against `grep -c 'Relief held' ti-hw67a.txt`. Not built, for you: a coalition relief of a
besieged capital (no ally relieved Chicomoztoc; the council's joint play is offence only), and
2e's shape 2 (yards at home).

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

**The night in one line:** five premise changes, each confirmed by its batch and the next - the
chest (hw63/64: the swarm fields its fund), hulls by the unit (convoys sail), bases bound by hulls
(hw65: no paper bases), relief only if enough (hw66/67: the humans keep a navy and yards) - took the
sector from a swarm walkover (hw60-65: 0-11 hives eradicated a game, every human yard at 0) to a
contested war (hw66/67: 7-42 eradicated, yards 2,100-3,900 FP/mo at the end, the swarm still
holding two games in three). Nothing was tuned; no exception in any batch since hw63. Last commit
34ee8e1f + hw67's record; nothing pushed. Where it binds now, and the levers (yours):

- **The pools bind the humans' founding** (hw66/67: "cannot pay" 41-155 links a game with hulls
  free): a faction with a navy and yards cannot pay a kit's 1,500 supplies and 800 fuel. Shapes:
  the surplus faction's convoy to the short one already exists (`humans-trade-through-war`); a
  faction could found from a stocked ally's base; or founding could wait on the pool as it now
  waits on hulls (built: the gate is `payer`, `canPayVoyage`).
- **Coalition relief of a besieged capital** (unbuilt): no ally relieved Chicomoztoc in hw65a while
  a 5,800 FP front sat on it 220 days; the council's joint play is offence only. A joint relief
  play sized by the swarm's points over the world, from every willing ally's base, is the shape.
- **Yards at home** (2e's shape 2, unbuilt): the humans' hull answer is only a Heavy Industry at a
  size-3+ link, never a core world.
- **Where the swarm still wins** (hw67a/b, hw66b): the opening strike's target - a yard world
  taken early starves that faction for the war. Not a bug; the founding-pace lever below.
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

## 4. hw68 - the counter-stroke (eb1dae56), read at 09:50, against the user's criterion

The user (09:xx): "Remember the player isnt involved in these runs. if the humans win the mod isnt
working correctly." By that rule hw66a, hw67c and now hw68a / hw68c are failures. The counter-stroke
(`strikes-staged.md` 6) was my pick to answer them; hw68 measured it: a trade (15 forward bases, 7
core-world landings) that saved no hive (26 of 30 died) and was refused on supplies 20 of 50 times.
Hives eradicated 26 / 3 / 23 against hw67's 11 / 7 / 30 - within the variance, no better.

**Where the losing games are lost** (hw68a in detail, `game-runs-2.md` 43): the swarm's supplies run
out - 42 hives spending 88-102k a month against 65k made, 46k of it on fleets away - the navy dies of
unpaid upkeep (95k FP of fleets at m35, 44k a year later, 580 upkeep-loss lines), and the undefended
hives are eradicated one by one. In the walkover games (hw66b, hw67b, hw68b) the opening strikes took
yard worlds early and the humans' navy never recovered. The variance is the opening's luck; the
mechanism in the losing games is supplies, as every batch since hw60 has said.

## 5. hw69 - sustenance first (64afc3a9), read at 10:35: the swarm wins all three

The user chose "feed the forges first" (`facts` Decisions, `hive-garrison-and-upkeep.md` "Feeding
order"), reading it as an investment - a smaller navy until the new forges produce - and asked for
the counter-stroke to go (knob off, code kept through this batch). hw69: hives 67 / 50 / 58,
eradicated 1 / 9 / 11, worlds taken 41 / 26 / 32, supplies income 102k / 110k / 92k a month; the
humans down to 7 / 23 / 22 colonies with the yards at 0 / 300 / 1,600. hw69b is the proof: its stock
sat at 197-854 for five months at the point hw68a collapsed, sustenance stayed paid, 14 swarms
were lost to unpaid upkeep, no size was starved, and the hive grew through it (`game-runs-2.md` 43).

**Next is the user's:** the pendulum has swung. If three swarm wins in six years with the humans
cut to a quarter of their worlds is more than the war the player is meant to turn, the human-side
shapes from hw66 are unbuilt (coalition relief, yards at home) - never a swarm knob, and never
undoing sustenance first. The counter-stroke code (`IncursionManager.counterStrike`, ~100 lines
behind `counterStrikeEnabled`) can be deleted now that a solvent swarm needs no case for it.

## 6. hw70 - the new sector (ck3, AmaruDugas), read at 11:50: the humans win one, hold two

The user asked for a run "with a new seed so they come from different part of map" before the
human-strategy review. ck3 (the hw4 sector, war opens 2133, opener Jangala): hives 3 / 20 / 16 at
the end from 13-14 at the opening, eradicated 30 / 20 / 20, humans 34 / 28 / ~30 of 44-46 colonies
kept, a human win in hw70a (`game-runs-2.md` 43 hw70). The swarm opened at a third of ck2's size
(13 hives, size 61, 30k supplies a month against 37, 134, 42k) and its navy outran its income all
war (170-502 swarms shed to unpaid upkeep, seedings held 76-98).

**The cause is the chest rule in the pre-war** (`facts.md` "Why did the ck3 swarm open the war at
13 hives"): ck3's pre-war ran under it, ck2's checkpoint predates it. At 4 hives the fund's
12-month horizon is 1,800 FP, so the chest read full on day 707 with the cheapest prong at 8,244 FP
and nothing fieldable; the fleets were fed before expansion until day 2131, their upkeep put the
supplies in deficit, and `canFound` held every Seeding Swarm from day 1856 with 188k-402k fuel
in stock. ck2 reached 29 hives by war 2085; ck3 12.

**Next is the user's:** the shape of "full". (a) full = fieldable - the fund could pay the cheapest
known prong and still holds a horizon's saving; a fund that can field nothing is poor, and the
expansion that grows its income goes on (recommended; the same lock would bite a losing swarm
mid-war). (b) No chest before the first war. (c) Delete the chest rule (hw62's hoarding may
return). After the pick: build, remake ck3 (a pre-war change), run hw71 from it. The human-side
points of the hw69 review hold on the new sector (the record lists them); only the blind time is
geography.

The user picked (a) at 12:35 ("do recc fix for swarm first, i want a test on the new seed and the
previous old one to go simultaneously"): built as `ThreatOffensive.cheapestProngCost` read in
`ThreatStance.evaluate` (a6818b7b, jar 12:42), ck3 remade as ck4 on it, and the test is hw71 (two games
from ck2) + hw72 (two from ck4) in one batch. Four games at once did not fit: 70 MB free, the clocks at 50 war days in 7 minutes;
hw71b was killed at 13:27 and the batch ran as three (hw71a on ck2, hw72a/b on ck5). Three games is the
ceiling on this machine.

## 7. hw71 + hw72 - the chest fix on both sectors at once, read at 14:05: the swarm wins all three, the humans nearly gone

hw71a (ck2): hives 39 -> 83, humans 50 -> 10 colonies, every yard 0. hw72a / b (ck5, the new
sector remade on the fix, opening at 32 hives): hives 174 / 130, humans 5 / 3, eradicated 0 / 2,
fund banked 346k / 348k FP (`game-runs-2.md` 43). The fix removed hw70's human win; the swarm now
overruns both sectors in six years. **The pendulum is the user's call** - the player is meant to
be needed, not to inherit a dead sector. Also open: the fund hoards 300k+ FP while the chest
cycles full / spent (the hw62 shape at scale - the campaigns spend slower than the fund grows).
Harness: three games at once is the ceiling (four left 70 MB free); never `tail -f`
`sbs-status.txt` (it locks the file and the status lines are lost).
