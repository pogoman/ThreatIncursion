# The war council in test runs

Split from `war-council.md` (at its 40 KB limit) on 2026-10-01. What long runs showed about the
human factions' council (`ThreatWarCouncil`, `ThreatPlays`), with the causes traced by symbol.

## 1. pd2a: a new game, 8.6 years, the swarm's fog on (2026-10-01)

The run: `save_PoseidonDeimos_8594169485512677365` (a new game made by the swarm-fog build), 15
chunks, about 103 months. The swarm went from 5 to 139 hives and took 16 human worlds; the humans
found 100 hives and destroyed 1 (Kanni, month 49). Earlier new-game runs on the attack planner
without a council killed many (ng4a: the swarm had 78 worlds at month 117 after losing 50).

Mobilised: Persean month 37, independents 39, Hegemony 60, Tri-Tachyon 61, Luddic Church 84,
Sindrian Diktat 86. Strategy months: HOLD 179 (70%), STARVE 64, ROLLBACK 13, DECAPITATE 0. Plays 80:
BOMBERS 55 (7 success, 48 failure), RECON 9, HAMMER 8 (4 / 3 / 1 neutral), STARVE 8 (2 / 4 / 2 open).
Only 7 hammer sieges sailed in 8.6 years.

**Why Hold won** (`ThreatWarCouncil.scores`). Hold was 0.5 + 2 if pressed + 2 if outmatched, and
pressed (`Picture.pressed`: strikes in 90 days or fronts) was true in 218 of 256 faction-months and
also halved Starve, Roll back and Decapitate. Typical month: Hold 3.6-6.3 against Starve 1.3-1.8.
That defeated the user's decision "Starve fits outmatched, about as high as Hold" (facts.md). Hold
runs only recon and bombers, never a hammer (`ThreatPlays.plan`: a hammer starts under Roll back,
Decapitate, or Starve once `nexusesDown(focus)`). Luddic and Sindrian held every month and ran 0
plays. Decapitate needs AHEAD and a core in reach; "ahead" (ratio >= 2 against the whole known
swarm) ended before the swarm had cores: independent 10.6 at month 39, 0.7 by 63, 0.12 by 103.
**Fixed 2026-10-01:** pressed and outmatched add their +2 to Hold once (`Math.max`), and an
outmatched Starve is not halved when pressed: outmatched and pressed now read Hold 2.5, Starve 2.5
(3.25 with a rich cluster in reach).

**Why bombers failed** (`ThreatPlays.opportunity`). Bombers of opportunity run daily under every
strategy but Decapitate, gated by the opportunity fuel share and a report under `FRESH_DAYS` 10
showing 0 FP over a Nexus world. All 55 started on "reported unguarded today", a one-day snapshot
that cannot see swarms in transit: 28 of 55 targets were colonies at most 30 days old, still being
garrisoned by posture (mean 283 FP sent there in the 30 days before the sortie, 323 between sortie
and arrival, 13 after). The swarm saw the squadron a median of about 9 days before arrival (42 by
eyes, 9 by radar) and sent more after the sighting in 19 of 51. 46 failures read "raid over: orbit
contested" (a 300 FP squadron, 359 at the world, is contested by about 180 FP); failures cost fuel,
not ships. The play's verdict was learned (`learn("BOMBERS:<class>")`) but `opportunity` never read
it, and nothing stopped a re-try: Beta Welo I drew 11 squadrons. **Fixed 2026-10-01:** candidates
weigh size x `learned(c, "BOMBERS:" + class)`, and a failed squadron's world is skipped by that
faction for `intelHalfLifeDays` (market memory `$threatinc_bombersRepulsed_<faction>`,
`ThreatPlays.REPULSED_FLAG`).

**Still open, for the user:**
- Starve is circular. Its focus is the strongest cluster (`focus`: weight + 6 production + 4 core);
  `bombable` needs a report of no swarm or an orbit already held; the saturation expedition that
  would hold the orbit is paid from the richest base's pools and was 450-2,500 FP against swarms of
  several thousand ("no saturation expedition the pools pay" 16 times, "no squadron" at the start of
  6 of 8 plays, 4 squadrons sailed in all). The checks read "Nexus down 0 world-days". The two
  successes (7,125 FP and 2,500 FP expeditions) fed the only kill.
- Hammer sieges were small beside their hunts: the hunts (2.1-6.2k FP) are paid first at muster
  and the siege takes 60% of what is left (inferred from the figures, the stock fence not traced).
  Later sieges were 700-1,150 FP against 3-14k swarms.
- The band compares one faction's colony sizes with every known hive's weight sector-wide
  (`Picture.swarmWeight`), so every faction is outmatched from about month 61. The user rejected
  changing the ratio (facts.md, "Starve fits outmatched"); the means (fuel, supplies, marines) are
  logged but never read by `scores`.
- "Term served" and "stood down: out of supplies" count as a bombing success even with 0 Nexus-days
  down.

## 2. pd4a: the same new game with both fixes (2026-10-02)

Fog on, `scores` and `opportunity` fixed, 103 months. The swarm diverged from month 36 and ended
1.7x bigger (236 hives against 139), so the human losses (39 worlds against 15) are confounded.
- **The fixes did what they meant.** HOLD fell from 70% to 58% of faction-months and STARVE rose
  from 25% to 38%; where a cluster is in reach, HOLD 41% and STARVE 54% (HOLD stays about 96% with
  none in reach, where Starve scores 0). Late scores read Hold 2.0-3.5 against Starve 1.9-4.1.
  Bombers: 34 plays (55), success 26% (13%); no world drew more than 2 (Beta Welo I had 11); no
  faction retried a world that drove it off. The successes are nominal ("term served", "out of
  supplies"); Nexus-down days from bombers are 0 in both runs.
- **The humans were no more effective.** One hive killed (Garnir, month 46), none after; hammer
  sieges 5, none after month 66; STARVE plays 3 of 13 succeeded, 135 Nexus-down world-days all before
  month 76. Starve stays circular (saturation expeditions 450-950 FP against 2-5k at the focus;
  "no squadron" 15 times; "held in bomb (relief owed)" 18).
- **A siege fights alone.** Hegemony #15's 2,650 FP siege lost to 3,805 in one day with 3,213 FP of
  its own hunts in the system. Siege-to-hunt FP averaged 0.83. The design (`war-council.md` 4.1:
  "the hunt forces fight for the orbits, and the lead faction's siege expedition comes with them")
  was half built: the hunts counted in the daily siege's call-off (`friendsNear`), never in its fight.
  **Fixed 2026-10-02:** the play's hunts at the world (`ThreatSoftening.playFleetsNear`) fight in
  `ThreatPurgeFGI.dailyDay` and pay the same share (`ThreatAbstractBattle.foughtDay` with `friends`);
  outweighed without hunts still on their way in, the siege waits up to 3 days for them. Risks:
  the hunts also fight real battles with the same garrison, and their losses can stand a force down.
- **Supplies run out.** Summed human supply income fell from 17.3k a month (month 72) to 1.5k
  (96) and 0 (103), against a steady 25k in pd2a, while 400-585k fuel sat idle; Hegemony's upkeep
  was paid short on 39% of its lines. The last hammers failed at prepare ("no hunting force paid").
- **Odd:** Persean (weight 5, nothing in reach, 1.1k supplies) ran 42 RECON plays in months
  101-104, each "Hold: it threatens us; scout sent", ending neutral within days. Independents,
  the Luddic Church and the Luddic Path ran no plays at all.

## 3. pd6a: hunts beside the daily siege (2026-10-02)

The same new game with the siege fix, fog and council on, 97 months (the log ended there, no
crash). The fix went **unexercised**: no daily fight had a hunting fleet beside it.

- **The hunts were let go too late.** Release was the strike's estimate (`razeArrivalDays`, 14 d
  of muster plus the passage) less 3 d. At Qaras (Persean, month 60) the siege arrived early,
  landed, took the hive (the run's one kill) and was done before the play released its 17 hunts
  (6,699 FP); they closed on an empty orbit days later. **Fixed 2026-10-02:** the hunts go when the
  siege's live ETA (`getETAUntil(PAYLOAD_ACTION)`) is within 3 d plus their passage from the
  hyperspace muster (`ThreatPlays.siegeNear`, `ThreatSoftening.passageDays`).
- **Supplies stood the rest down.** At Huascar (independents, month 64) the hunts reached the world
  first, but 77 muster and 20 hunting fleets stood down out of supplies: independent#3 had built
  27,772 FP in 100 fleets on 47k supplies owed. A 79 FP fleet was all that fought. The siege ground
  the garrison to 12-26 FP over 10 fight days (Nexus down 41 d), then 10 reinforcements arrived
  and it was called off at 773 FP against 742.
- **The log hid it.** One fight line a month (`logQuiet`) hid days 2-10. Since 2026-10-02 every
  fight day is logged, with the hunting fleets beside the siege and their loss, and a call-off names
  the hunts in the system.
- Against pd4a and pd5a: sieges sailed 2 (5, 8), landed 1 (1, 2), hives killed 1 (1, 2), human
  worlds lost 8 (33, 16; run divergence: 4% of strikes took a world against 15% and 9%). Starve
  stayed circular (23 sailed, median ~550 FP, 15 turned home on Defense Swarms 2.3x their size);
  no hammer started under Hold (0 of 8; Hold 158 of 308 faction-months).
- **Read wrong, mostly:** Persean's 7,450 FP saturation "fought as 30,507" at Yma: the fight line is in
  vanilla units (2x FP), and its expired route, still in Yma (base Salamanca), was counted twice
  (fixed 2026-10-02, `ThreatAbstractBattle.attackerStrength`). pd5a's 30,950 FP siege beside a 6,641 FP
  hunt was its real, paid share. Factions build fleets their supplies cannot feed (the independents
  above): the force is paid its voyage, never the upkeep it runs up; 309 `out of supplies` stand-downs.

**pd8a (2026-10-02), with the live-ETA release and per-day fight lines:** hunts fought beside the
siege on 27 of 42 fight days (pd6a 0 of 2), closed on the world before the takeover in all four
sieges, and no siege had to wait for them. Outcomes did not change: all four sieges were beaten,
none landed (pd6a landed one), 0 hives killed. Two came close and were worn down by garrison
trickle: Delta Eye I (Persean, 43 d, hive condition 0.10) and Dazbog (independents, 26 d, 0.17).
Threat FP killed in daily fights rose from 951 to 13,392, hammer Nexus-down days from 104 to 189.
Hives at month 97: 135 (pd6a 201); kills 0, so the gap is slower founding late (2.6 against 5.0 per
100 hive-months in months 85-96). Only hunts within orbit range fight (1 of 11 at Qaras). Two of
four sieges' hunts stood down "badly hurt" soon after; too few to call. The fix works mechanically;
the council's problem is how few sieges it sails (section 4).

## 4. pd7a: the council off, the attack planner back (2026-10-02)

The same new game, fog on, `warCouncil` off (so `ThreatAttackPlanner` plans, section 10 of
`war-council.md`), 119 months. **The planner held the swarm to a fifth of its size.** The runs
match to month 36 and part at month 39, when the first planner siege sails.

| at month 48 / 72 / 97 | pd4a | pd5a | pd6a | pd7a (planner) |
|---|---|---|---|---|
| hives | 21 / 66 / 190 | 20 / 66 / 160 | 22 / 65 / 201 | 16 / 36 / 30 |
| hives killed | 1 / 1 / 1 | 0 / 2 / 2 | 0 / 1 / 1 | 3 / 12 / 27 |
| expeditions landed | 1 / 1 / 1 | 0 / 2 / 2 | 0 / 1 / 1 | 6 / 21 / 43 |
| sieges sailed | 4 / 11 / 15 | 3 / 17 / 27 | 1 / 13 / 25 | 23 / 115 / 202 |
| human worlds lost | 4 / 9 / 33 | 7 / 7 / 16 | 3 / 3 / 8 | 6 / 6 / 9 |

(Council sieges are HAMMER sieges plus STARVE saturations; the planner's are its prongs.)

- **How:** about 2 planner sieges a month from month 39, 47% of them preemptive, kill 27 hives by
  month 97 and press 7-12 of the swarm's 11-16 hive systems at once. That holds `ThreatStance` in
  CONSOLIDATE for 63 of 119 months (months 65-101 unbroken; 0 months in the council runs), and
  CONSOLIDATE zeroes `expansionShare`: the swarm founds 1.0-1.3 hives per 100 hive-months there
  and loses 1.5-2.3. Kills are about half the growth gap, the stopped founding the other half.
- **Not supplies:** human supplies were alike in all four runs; the council runs paid more fleet
  upkeep (0.77-1.06M by month 97 against 0.67M) and sat on 0.5-1.2M idle fuel (pd7a 216k).
- **Why the council sieges so little:** only a HAMMER play can land, a faction runs one major
  play at a time (`ThreatPlays.major`), and Hold (about half the faction-months) starts none; the monthly siege pass and the
  planner are off while the council is on (`ThreatAttackPlanner.active`).
- **Odd in pd7a:** 6,928 `Hunting force waits` lines (pd6a 225) and 11,298 `Expedition postponed`
  (10,527 pool shortfalls). 0 exceptions. The swarm recovered 30 -> 42 hives in months 97-119.

This is the user's to decide (`facts.md`, "Why is the swarm 5x smaller with the council off?").

## 5. Options: a council that sieges as often as the planner (for the user, 2026-10-02)

The planner's strength was throughput: many prongs at once (7-12 hive systems pressed), not
better sizing. The council's coordination can stay; what it lacks is tempo. Proposed at the play
level, none sizing on enemy FP:

- **A. Every strategy sieges.** Strategy picks *where*, not *whether*: Hold sieges the hive nearest
  its worlds, Rollback the frontier, Decapitate the core, Starve its starved system. Each faction
  always has a hammer running when its means pay one (a share of means, as now).
- **B. Plays scale with means.** Replace the one-major-play rule (`ThreatPlays.major`) with one
  major play per share of the faction's siege capacity, so a big navy presses several systems.
- **C. Invade on damage within Starve.** A starve play whose system is down (Nexus out, garrison
  thinned) turns to a hammer in the same play, instead of ending and waiting for a fresh start.
- **D. The planner under the council.** Planner prongs as the line, plays on top with their targets
  reserved. Fastest to prove (pd7a), but it brings back sizing on reported FP and re-planning on
  every sighting, which the user rejected on 2026-10-01.

*Recommended:* A + B, then C; a long new-game run against pd7a's 30 hives at month 97. On the
swarm's side, Hive Mind decision 5 (`swarm-strategy.md` 4: CONSOLIDATE only on real losses)
decides whether pressure alone still stops its founding as it did in pd7a.

Scripts and the full tallies: the session scratchpad's `pd2a-council.md` (not kept).
