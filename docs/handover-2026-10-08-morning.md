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

## 8. hw73 + hw74 - yards at home on both sectors, read at 15:00: the old sector holds, the new one still falls

Built as `ThreatFrontlines.homeYards` (f404c36a, knob `homeYards`, `hull-pool.md` 3): a faction
short of hulls builds a Heavy Industry at its largest core world with a free slot. hw73a (ck2):
hives 38 -> 50 -> 34, humans 50 -> 28 (hw71a: 10), yards 5-7k FP a month all war (hw71a: 0),
14 hives eradicated, Hegemony keeps all 10 worlds with 15.8k FP of hulls. hw74a / b (ck5): hives
91 / 84 (hw72: 174 / 130), humans 5 / 4 (hw72: 5 / 3) - 24 / 13 home yards built, output peaked
6.4k / 3.8k a month by m36 and died with the worlds; the opener's victim (Hegemony 9 -> 1) falls by
m48-m60 as before. The yards work where the core survives to use them; on the new sector the
swarm takes the yard worlds. Open for the user: whether a contested old sector is the shape wanted
(`humans-must-not-win-unattended`); the next single human change (coalition relief, or Starve sized
to the pools); the swarm's navy outgrowing its income on the old sector (109k FP on 78k a month,
stock 0 for the last year) and the 300k+ FP hoard on the new. `game-runs-2.md` 43.

## 9. hw75 + hw76 - coalition relief, read at 16:35: the old sector trends human, the new one still falls; the supplies breakdown

`ThreatCoalition.jointRelief` (79e34157; partners = every non-hostile war faction, 17e3d89b, after
a first run on the Favourable gate found none). hw75a (ck2): humans 50 -> 38 (hw73a 28), hives
52 -> 27 by m60 then 35, yards 8-11k a month, 103 joint reliefs sent; hw76a / b (ck5): hives 162 /
132, humans 3 / 6, 90 / 87 joint reliefs sent but 235 / 211 held - the coalition fields 0.26-0.29
of the army. The census now breaks the swarm's supplies by purpose and lists the fleets away by
kind (`game-runs-2.md` 43): on the old sector 69k a month = feed 24k, strikes 8-27k,
reinforcements in transit 16k (179 fleets shuffling between hives), home navy 13k, spare 1.5k,
145 seedings held. The user's rule (facts Decisions, 2026-10-08): the swarm keeps no navy it cannot
supply and use; the fund hoard is ignored for now. Next: the shape for that rule (section 10 when
built), and the old sector's human trend is the user's question in the other direction.

## 10. hw77 + hw78 - the navy fits the spare, read at 17:30: the swarm stronger on both sectors, the old one contested again

`ThreatColonyManager.fitNavyToSpare` + the transit gate (15f82c6d, knob `navyFitsSpare`). hw77a
(ck2): hives 38 -> 62 -> 55 (hw75a 35), humans 50 -> 36 (hw75a 38), 4,602 swarms recycled for 882k
FP while 2,161 were fabricated - the rule churns through the bank rather than shrinking the navy
for good - fleets 83.6k FP (hw73a 109k), seedings held 77 (145), sends held 0 at the end, fund
13.8k. hw78a / b (ck5): hives 191 / 177 (162 / 132), humans 9 / 4, recycled 3,927 / 2,984 swarms,
seedings held 462 / 327, fund 196k / 332k, spare 15-30k yet 50 sends a month held. Open for the
user: an old sector contested at 55 hives against 36 worlds (`humans-must-not-win-unattended`);
the ck5 puzzle of sends held on a positive spare (the 17-40k build spend is the suspect for
draining the stock first); the churn (a recycled swarm rebuilt the next poll). `game-runs-2.md` 43.

## 11. hw79 + hw80 - the builder respects the fit, read at 19:40: nexus churn gone, the old sector falls to the swarm, the pressure pass is the next loop

`ThreatColonyManager.fitActive` / `fitFloor` (febabf9f, 606a1e31 - the first gate, on the fit
binding now, still churned a build and a recycle a poll; the floor now holds a month after the
last recycle). hw79a (ck2): hives 38 -> 92 (hw77a 55), humans 50 -> 23 (36); recycled 2,284
(4,602), nexus built 1,021 (5,727), same hive-month pairs 66 (4,019). The pressure pass took over:
4,330 swarms fabricated for receivers (2,161), 1,060 recycled at the same hive - with every colony
at the fit's floor every colony is short of the posture's want, so `redistributeByPressure` finds
no donor and fabricates. hw80a / b (ck5): hives 177 / 87, humans 4 / 4. Next: the pressure pass
wants the fit's floor for a receiver nobody attacks while the fit is active (hw81 / hw82); the
watcher's check pairs the pressure fabrication too. The user (18:30): fix the mechanism's
wrinkles, no new strategy layer. `game-runs-2.md` 43.

## 12. hw81 + hw82 - the fit's floor is the colony's want, read at 20:20: recycling down by two thirds, a third loop at the receiver

`ThreatPosture.wantFP` returns `ThreatColonyManager.fitFloor` for a colony nobody attacks while
the fit is active (f65b0bd2). hw81a (ck2): hives 38 -> 70 (hw79a 92), humans 50 -> 22 (23);
recycled 1,241 (2,284), nexus built 1,199 (1,021) - but the pressure pass fabricated 5,200 (4,330)
and 395 were recycled at the hive they were for: Gream, unattacked, had its want move with the
pressure, so swarms were fabricated elsewhere, sent, recycled on arrival, sent again. hw82a / b
(ck5): hives 185 / 180, humans 2 / 3, pairs 125 / 44. Fix (33ad93bc, jar 20:20, hw83 / hw84):
`redistributeByPressure` fabricates for a receiver only when its system is attacked, and the fit
skips a colony `ThreatPosture.recentlyReceived`. `game-runs-2.md` 43.

## 13. hw83 + hw84 - nothing fabricated for a quiet hive, read at 23:10: loop three closed; the old sector contested by sieges fed a swarm at a time; a vanilla fatal at the swarm's victory

33ad93bc. hw83a (ck2): hives 37 -> 58 -> 39 -> 53 (hw81a 70), humans 50 -> 25; recycled 897, pairs
252 (3 a month: the churn is done). The pressure pass fabricated 4,501 swarms / 470k FP for hives under
attack, mean 105 FP, and 40 hives were eradicated by human fronts with swarms arriving one at a time
to be hunted (Nomios: 4,450 FP siege, 800 standing, 91 swarms sent in three months, Epiphany's bank
1,668 -> 74). The ordinary transfers and the fabrication were outside the rally's enough rule. hw84b
(ck5): hives 167, humans 2, then a fatal from vanilla's `MiscAcademyFleetCreator` (no market left to
send from) - `ThreatAcademyFleetGuard` guards it (hw85 / hw86). hw84a never loaded (harness). Next:
the pass's enough gate (`ThreatPosture.forceOver` / `standsFor`, `pressureSpare`, `fabricableFor`) -
nothing to an attacked receiver unless what stands, what donors could spare and what banks could build
together outweigh the force (hw87 / hw88). The user (23:00): run until morning, fix buggy behaviour,
no new strategy layer, until the swarm wins both sectors consistently. `game-runs-2.md` 43.

## 14. hw85 + hw86 - the academy guard (hw83's build again), read at 00:05: old sector contested, new sector the swarm's, no fatal

f23c009d. hw85a (ck2): hives 38 -> 61 (hw83a 53), humans 50 -> 31 (25); pressure fabricated 5,441,
18 hives eradicated; the churn check's m45 flag (38 pairs) was fabricated-for swarms recycled once
the attack passed. hw86a / b (ck5): hives 133 / 156, humans 7 / 3, no exception at the end (the guard
holds, or the pick never came up null). The old sector reads 53-92 hives across four samples of the
same strategy: contested, not won. hw87 / hw88 (811737ff, jar 00:04) run the pass's enough gate; the
watcher's check is `check-enough.sh` (fabricated-for 250+ in three months, or fleets-away cost above
the supplies made). `game-runs-2.md` 43.

## 15. hw87 + hw88 - the pass under the enough rule, read at 00:50: the gate on the banks barely bit; regated on what the pass would send

811737ff. hw87a (ck2): hives 38 -> 76, humans 50 -> 19 - the best old-sector sample - but pressure
fabricated 4,489 (hw83a 4,501), 25 hives eradicated, 703 hunt battles: the gate summed every idle bank
in the sector as what could stand (60k), so it refused 49 times. hw88a / b (ck5): hives 150 / 168,
humans 7 / 6. hw89 / hw90 (jar 00:50): could = stands + min(accept, gather) - a receiver wanting 1,000
under a 4,450 FP siege is sent nothing. Watcher: `check-enough.sh`. `game-runs-2.md` 43.

## 16. hw89 + hw90 - the gate on what the pass would send, read at 01:35: the feed down a third; hunts meet one swarm at a time

a8677deb. hw89a (ck2): hives 38 -> 64 (trough 46 at m43), humans 50 -> 18; pressure fabricated 3,142
(hw87a 4,489), refused 117 with the figures, 25 eradicated, 786 hunt battles. Read from the hunt battles:
89% met a single swarm (171 FP vs 478, 839 FP of garrison standing by), 303 of 487 fought 600+ units
from the world - garrison swarms orbit 400-700 units out, up to 1,400 apart, outside the engine's
500-unit join range, and a chaser is caught alone coming back under the 700-unit leash. hw90a / b (ck5):
hives 162 / 184, humans 4 / 4. hw91 / hw92 (693f8025, jar 01:35): `ThreatGarrisonMuster` - a garrison
swarm's battle within 1,500 units of its world pulls the hive's other swarms on station and the
reinforcements bound for it in (BattleAPI.join); watcher `check-muster.sh` (single-swarm share above
60% flags). `swarm-defence.md` "The garrison fights as one", facts. `game-runs-2.md` 43.

## 17. hw91 + hw92 - the garrison fights as one, read at 02:25: hunts meet the garrison; the old sector trades worlds

693f8025. hw91a (ck2): hives 38 -> 66, humans 50 -> 21; hunt battles against a single swarm 27%
(hw89a 89%), 559 musters, hunts completed 155 / stood down 144 (257 / 67); but 39 hives eradicated
against 36 human colonies lost - a siege that outweighs a garrison takes the hive and the rally stays
home when the system cannot match it (the user's rule). hw92a / b (ck5): hives 175 / 115, humans 7 / 3.
hw93 / hw94: the same build, a second sample. Across the night's old-sector samples on one strategy
(hw83a-hw91a): hives 53 / 61 / 76 / 64 / 66, humans 25 / 31 / 19 / 18 / 21 - contested, the swarm ahead,
never decisive; the new sector is the swarm's every time (115-185 hives, 2-7 worlds). `game-runs-2.md` 43.

## 18. hw93 + hw94 - the muster build's second sample, read at 03:10: the old sector trades worlds again; the pass fabricates by the receiver's cheapest row

693f8025 again. hw93a (ck2): hives 38 -> 47, humans 50 -> 29, 33 eradicated / 29 colonies lost; supplies 0
in stock with 128k FP in the fund, away 49k of 80k made (151 reinforcements in flight, 20k FP, 15.5k a
month; 82 First Strike fleets on Defend over the swarm's sieges, 11k). Read from the flight: Culann,
wanting 4,582 FP under a human siege, was fabricated 57 swarms of 52-97 FP in one poll - `fabricateFor`
built the receiver's cheapest garrison row (a size-1 table's LOW swarm) one a dispatch; 3,498 such swarms
in the game, 1,665 under 100 FP. hw94a / b (ck5): hives 190 / 148, humans 0 / 4. hw95 / hw96: the same
build, a third sample (launched 03:09). Fix for hw97 / hw98: `ThreatColonyManager.rowFor` - the swarm
fabricated for a receiver is the dearest garrison row of any size's table that its deficit, the
fabricator's idle bank and the fuel pay for. `game-runs-2.md` 43.

## 19. hw95 + hw96 - the muster build's third sample, read at 04:00: the old sector slides (40 hives / 32 worlds)

693f8025 a third time. hw95a (ck2): hives 38 -> 40, humans 50 -> 32, 62 eradicated / 20 colonies lost;
pressure fabricated 4,350 (55% under 100 FP), 181 reinforcements in flight at the end (26k FP, 19.5k a
month, sends at a mean of 26 ly); Mairaath's front counter-attacked 55 times. hw96a / b (ck5): hives
143 / 116, humans 0 / 6. The old sector on one build: 66 / 47 / 40 hives against 21 / 29 / 32 worlds.
hw97 / hw98 (jar 03:55): `rowFor`, the swarm fabricated for a receiver sized to its deficit (watched by
`check-rowfor.sh`: share under 100 FP, reinforcements in flight). hw99 / hw100 next: `counterGap`, the
front fed to the counter-attack line (7fb70a50, `check-feed.sh`: braces and counter-attacks per swarm
front). `game-runs-2.md` 43.

## 20. hw97 + hw98 - the swarm sized to the deficit (rowFor), read at 04:50: fewer, bigger swarms; the old sector's weakest swarm result (one sample)

711cec96. hw97a (ck2): hives 38 -> 42, humans 50 -> 43 (hw95a 32), 41 eradicated / 9 colonies lost; fabricated
2,792 at a mean of 195 FP (hw95a 4,350 at 110), 111 in flight (181); but 9 ground victories against 20-39
in the earlier samples and the fund 25-39k through the run (the bigger rows spend the idle banks deeper:
544k FP built for receivers against 402k). hw98a / b (ck5): hives 145 / 168, humans 6 / 1. Not read as the
fix's doing on one sample (ck2 ranges 21-43 worlds on one build); hw99 / hw100 (jar 04:42) run `counterGap`
on top of it, and a sample with `counterGap` alone separates the two if hw99a is as weak. `game-runs-2.md` 43.

## 21. hw99 + hw100 - the front fed to the counter line (counterGap), read at 05:35: the old sector's best swarm result, the feed still idling on a holding front

7fb70a50. hw99a (ck2): hives 38 -> 80 (hw97a 42), humans 50 -> 29 (43), 23 eradicated / 26 colonies lost,
26 ground victories (9), 229 hull break-ups (68); the economy hot at the end (spare -20k, fuel spent 212k
of 92k made). hw100a / b (ck5): hives 167 / 216, humans 1 / 3, 54 / 55 ground victories. Read from
hw100b's Nortia (150 against 263, braced 41 times under an idle fleet): a front that holds the hold line
but not the counter line was neither bombarded for (`defendBombards` asked hold-or-overrun) nor fed while
orbit still paid - 4084b5d1 makes the guns go first; hw101 / hw102 (jar 05:27) run it. `game-runs-2.md` 43.

## 22. hw101 + hw102 - counterGap complete, read at 06:20: the old sector swings (71 hives at m40, 36 at the end), the new sector contested in one game

4084b5d1. hw101a (ck2): hives 38 -> 71 (m40) -> 36, humans 50 -> 22, 50 eradicated / 33 colonies lost;
the fall is human eradications with the swarm's fund at 189k FP and 139k supplies in stock at the end
(the rally refuses what cannot win: `no rally to Suddene, 1,218 FP could stand against 2,527`). hw102a / b
(ck5): hives 214 / 76, humans 3 / 22 - hw102b is the new sector's first contested game (106 First
Strike fleets on Defend, 22k FP, 17.4k supplies a month; counter-attacks battered 107). hw103 / hw104
(06:16): the same build, a second sample. Old sector across the night: 66 / 47 / 40 / 42 / 80 / 36 hives
against 21 / 29 / 32 / 43 / 29 / 22 worlds. `game-runs-2.md` 43.

## 23. hw103 + hw104 - second sample, read at 07:15: old sector 77 hives / 27 colonies; the reinforcement leak found

4084b5d1 again. hw103a (ck2): hives 38 -> 86 (m60) -> 77, humans 50 -> 27 (+24 forward bases), 53
eradicated / 22 conquests, fund 281k, 281 reinforcements (80k FP) in flight at the end. hw104a / b
(ck5): hives 169 / 154, humans 3 / 5. Found while it ran (hw101a's dumps): 92-177 reinforcement swarms
(18-34k FP) sit 120+ days inside one system every run - Thule, Beta Cormoran - while their targets fall;
Zipacna's 724 FP swarm stood in Beta Cormoran 728 days. e66c0a5a (jar 07:11, hw105 / hw106): a
reinforcement out 90+ days is logged where it stands (location, distance, order, battle, AI, burn) and
ordered on again; the dump carries days in flight; `check-overdue.sh`. The cause is read from hw105's
first `Reinforcement overdue` lines. `swarm-defence.md` "Overdue reinforcements are ordered on", facts.

## 24. hw105 + hw106 - overdue reinforcements ordered on, read at 07:58: old sector 92 hives / 15 colonies; a fresh order does not move a stuck swarm

e66c0a5a (jar 07:11). hw105a (ck2): hives 38 -> 92, humans 50 -> 15 (+6 forward bases), 25 eradicated /
34 conquests, 137 reinforcements (21.6k FP) in flight at the end (hw103a 281 / 80k), 82 of them 90+
days out; 104 swarms were ordered on three times and more without arriving. They move at burn 9 (real
speed) with the order active and no battle: three swarms for Blue stood 990 days inside Blue's own
system, their distance to Blue a 540-day sinusoid between 3k and 22k units - the planet's orbit seen
from a fleet circling one spot. 88% of sends arrive. hw106a / b (ck5): hives 244 / 192, humans 5 / 8.
hw107 / hw108 run 095cce7a (jar 07:55): the full whereabouts read (heading, speed, fleeing, tactical
target, terrain, nearest hostile) and the remedy ladder (order afresh, fresh fleet AI, carried to 1,500
units - the carry is a stopgap for the user to keep or drop); `check-whereabouts.sh`. The cause is
read from hw107's first lines. `swarm-defence.md` "Overdue reinforcements", facts.

## 25. hw107 + hw108 - the cause read at 08:40: asteroid belts pin a big swarm at full burn; old sector 65 hives / 36 colonies

095cce7a (jar 07:55). hw107a (ck2): hives 38 -> 65, humans 50 -> 36 (+8 forward bases), 44 eradicated / 13
conquests - the swing against hw105a's 92 / 15 on the same rules. hw108a / b (ck5): hives 170 / 162,
humans 1 / 4. The read: of 766 consecutive reads of one swarm, 214 at the same unit of position 90 days
apart at full speed; 1,240 of 1,497 full-speed reads inside an asteroid belt or ring. Vanilla's
`AsteroidBeltTerrainPlugin` knocks a fleet that is not slow-moving off course every time its impact
timeout runs out (`AsteroidImpact`), heavier for a big fleet - a 16-25 ship swarm at burn 9 never gets
out of its own hive's belt. It skips a fleet whose memory holds `$asteroidImpactTimeout`: every fleet
the mod builds or orders, both sides, now carries it for good (`ThreatFleetComposer.beltSafe`, at the
creation sites, sendReinforcement / sendToGarrison / overdue, the leash return). hw109 / hw110 (the
movement read alone) were aborted at month 1 for hw111 / hw112 with the fix (jar 08:43). Still to read:
333 crawling swarms at burn 2 (speed 44), most in hyperspace with human fleets 1-20k units off - the
movement fields (burn modifiers, active abilities, go-slow, orbit, listed) are in that jar.
`swarm-defence.md` "Overdue reinforcements", facts.

## 26. hw111 + hw112 - the belt diagnosis refuted at 09:30; new sector humans wiped out twice; old sector 89 hives / 21 colonies

8f6d8988 (jar 08:43, beltSafe + the movement read). hw111a (ck2): hives 38 -> 89, humans 50 -> 21 (+9
forward bases), 35 eradicated / 27 conquests - the night's second-best old-sector end state (hw105a 92 /
15). hw112a / b (ck5): hives 154 / 203, humans 0 / 0 - no colony and no forward base left. The
reinforcement gap 3.8% (hw107a 3.9%): the belt key changed nothing. The read: 137 frozen pairs, all at
full speed with the belt key set, in a belt or ring, heading unchanged across 90 days - neither the fleet
nor its AI is being advanced; a fresh AI does not help, the carry does. So `AsteroidImpact` is NOT the
cause (the belt is where the swarm was built and never left). The crawlers (261 reads, speed 44) are
`slow true`: vanilla's sneak burn (min burn 9 x the sneak multiplier = 2), travel speed intact, 192 near
a human fleet in hyperspace, 69 with none in range; the mod never calls `goSlowOneFrame`. hw113 / hw114
(jar 09:28) run the frame pulse (`ThreatColonyManager.FramePulse`): a script on each reinforcement swarm
recording the last frame the engine advanced it, read back with `alive` / `current` / `station` / `aimode`.
`game-runs-2.md` 43, `swarm-defence.md` "Overdue reinforcements".
