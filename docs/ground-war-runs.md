# Ground war - retired mechanics, backlog, bombardment v2 checks, overnight siege AI runs

Split out of `ground-war.md` on 2026-10-01. One-line answers: `facts.md`.

### Raiding the fortifications (2026-09-25) - RETIRED 2026-09-28

**Retired by "Bombardment v2".** Marines reach the guns only by landing: every human
fortification is vanilla's unraidable again, the hive's Swarm Nexus, Ground Defenses and
Heavy Batteries and the planetary shield are tagged unraidable, and the knobs
`fortificationRaidDanger` / `fortificationRaidDepthLoss` are gone. The Fabrication Core raid
stays (it is not a defence), its depth toll on `coreRaidDepthLoss` and read from the
player's own raid strength (the suspected `depthMult` bug, fixed). What follows is history.

Vanilla tags a colony's Ground Defenses, Heavy Batteries, Patrol HQ, Military Base and
High Command `unraidable` with no disrupt danger: its tactical bombardment knocks them out
for 365 days, so raiding them was never needed. Ours is a siege slice that wears them down
over the full clock at vanilla's fuel bill; raiding trades marines for speed.
`ThreatFortificationRaids` (a
`GroundRaidObjectivesListener`, priority 1, after vanilla's list) puts every human colony's
fortifications on the disrupt-raid picker at the danger in `threatinc_fortificationRaidDanger`
(default HIGH, the hive batteries' own in industries.csv: 20 days per marine token).

What the defence decides is vanilla's: raid strength / (raid strength + defender strength)
sets how many marine tokens you have and trims losses as it rises. The danger label is
fixed per structure and only sets the base casualty rate and days per token. Wearing the
guns down iteratively beats knocking them out in one go, by design (user, 2026-09-25):

- Each raid's days add in full. Vanilla shrinks a raid on a clock already running
  (dur x dur / (dur + already)); the fortification's condition is linear in its clock, so
  that only punished coming back.
- Marines lost on a fortification objective grow by (tokens ^ `threatinc_fortificationRaidDepthLoss`
  - 1) x the defence's share of the fight, defender / (raid + defender) - the same odds
  vanilla sets the tokens by (`modifyMarineLossesStatPreRaid`, shown in the losses breakdown
  as "Deep raid on one structure"). Vanilla averages danger per token, so five tokens
  on one objective cost what one does. First cut (same day) had no defence term: 8 tokens
  at 1.5 was a flat x22.6, and a raid of 800 marines on a 333-defence world lost 387 where
  vanilla would have lost ~17. Now that raid (~0.2 share) is ~x5; against a garrison
  holding 0.7 of the fight, a 3-token raid is ~x4 against 3 shallow raids' ~x2.7.
- The same term is weighted by what the structure adds to the defence now - its bonus x
  condition x input deficit, against Ground Defenses' intact x2 (user, 2026-09-25: a
  Military Base's x1.2 showed the same x2.7 at full commit as Ground Defenses' x2). Heavy
  Batteries weigh 2, Ground Defenses 1, High Command 0.3, Military Base 0.2, Patrol HQ
  0.1, and a structure already worn down weighs less - so each shallow raid makes the
  next one cheaper.
- Vanilla's own terms still apply on top: raid strength is 0.25 x the fleet's personnel
  capacity + ground support, times marine XP and skills - not the marine count - and the
  loss is a fraction of every marine aboard, so a bigger stock loses more for the same raid.
  "Increased defender preparedness" adds 50% per recent raid, decaying, so shallow raids
  back to back get dearer.
- A raid marks the world besieged (`BESIEGED_FLAG`, wearDays) and syncs the siege state, so
  the structure loses its bonus in proportion to its clock. Before this a raid on a world
  nobody had bombarded fell back to vanilla's on/off: 20 days stripped Ground Defenses' x2.
- Every clock read is `siegeDisruptDays`: the bombard's revert leaves a ghost expire on a
  structure it set back to 0, and vanilla's raw read would stack the raid on top of it.
- Off with Nexerelin: its bombardment runs human colonies' menus and still writes the year.
- Hives: their defences were raidable already (HIGH, industries.csv). The Swarm Nexus and
  Fabrication Core take the same depth toll (2026-09-27, built, untested): vanilla lists them,
  so `modifyRaidObjectives` swaps each for an `OrganRaid` that quotes the toll. Their days add
  in full as well (2026-09-28): vanilla's shrink left a second raid on an organ with a long
  clock a few percent of its days. Weight: the Nexus by its defence bonus as worn (`nexusDefenseBonus` x
  resilience, 0.5 intact); the Core adds no defence, so `threatinc_coreRaidDepthWeight`
  (default 1, Ground Defenses intact) x its fabrication wear. Before this five tokens on the
  Nexus bought 100 days at a one-token casualty rate. On with Nexerelin too.

To verify: Disrupt lists the colony's fortifications at Heavy danger; one token on Ground
Defenses at an unbombarded colony leaves it at ~89% effect (not 0); the losses breakdown
grows with tokens on one fortification, and the objective's hover tooltip quotes its multiplier (x at the next token counts with one token, the current figure and a shallow-raids hint with more); a second raid adds its full 20 days; with
Nexerelin loaded the list is vanilla's.
## Strategy-layer backlog (the "proper strategy game" - see docs/strategy-layer.md for what was built on 2026-09-04)

Agreed direction from the user, in rough build order:

1. **Troops ride fleets for real**: marines/armaments as actual cargo aboard NPC
   expedition fleets (vanilla `CargoAPI` exists on every fleet), with troop-carrier
   and cargo capacity mattering. A pulled-out front boards a circling fleet and can
   be redeployed elsewhere.
2. **Fleet orders**: a way to order individual friendly fleets - orbit superiority,
   jump-point interception (vs Mutual Defense relief), logistics runs to fronts,
   troop redeployment. Missions the player could fly personally, delegated.
3. **Faction reserves, fully transparent**: every deployment of marines/armaments/
   fuel comes from a visible per-faction reserve, generated by functioning colonies
   and industries. War board gains a reserves/logistics panel.
4. ~~Hive-side fronts on core worlds (Threat strikes reworked onto the same object),
   outposts on eradicated worlds (non-economy garrison entities, forward depots).~~
   BOTH DONE - see "Threat ground assaults" above and `ThreatOutposts`.

## Strategy-layer decisions (user, 2026-09-04, after the session above died)

- **Reserves are PER COLONY, with staging**: stocks live on the colonies that
  generate them and must physically ship to a staging point before they deploy.
  This pulls in the convoy layer from the start; that is accepted.
- **Everything fleet-related must be doable REMOTELY** - from the board, not by
  flying to a fleet and hailing it. The war board is the fleet-order home.
- **Faction selector on the board**: pick a faction from a list and the board
  shows THAT faction's systems and planets - state of its defenses, attacks
  against it, its fleets - so the player can send fleets to reinforce allies.
- **War mode gates all of it**: NPC factions behave exactly as vanilla until the
  Threat attacks them. Being struck by the Threat flips the faction into war
  mode; only then does the mod start tracking its reserves, defensive fleets,
  attack fleets and so on. Do not touch core-world fleets outside war mode.
  (Natural hook: the reactive-defense path in `IncursionManager` that already
  musters a faction task force when a Threat strike targets its colony.)

### To verify - bombardment v2 (2026-09-28, built, untested)

Spec and build notes: docs/suppression-balance.md, "Bombardment and siege redesign v2".

1. **The menu.** Over a hive, Bombard reads "Tactical bombardment: N fuel a day." and
   "Saturation bombardment: up to N fuel a day."; after either, both grey out with the lock
   line until the next day.
2. **The tactical prompt** lists each fortification "X% to Y%", the shield when one stands,
   the ships the day's return fire takes by name ("Lost to return fire: ...", "Next to go:
   ..."), and the unrest the day leaves. Three days in a row take less off each day.
3. **Hive unrest.** After a tactical day a hive's stability reads 10 minus the unrest, and its
   Defenses tooltip falls with it; the Swarm Nexus no longer cancels the stability multiplier.
4. **Saturating a hive** (2026-10-01). A day takes no size off and puts no "Razed" on the
   colony screen: every structure gains disruption days, fewer each day as it wears, unrest
   goes to 10, and the hive's tooltip reads "Growth: halted under saturation" until a day or two
   after the last. A hive carrying an older save's "Razed" loses it on the next day.
5. **Razing a colony.** Saturation on a human colony (no Nexerelin) puts "Razed" on its colony
   screen ("Level N: X of Y fuel", "Razed with N more fuel"), takes a size off per level paid,
   and the last level ends it.
6. **Combined arms.** With a front holding layers, the saturation prompt names them, and over a
   colony prices size less layers held; the front survives the saturation, and it can land at
   once afterwards (no fallout).
7. **The shield** cuts the day's delivery while it stands: into a colony's bar (prompt and bar
   agree), or a hive's wear.
8. **Fronts wear by advantage.** A front with troops about equal to the defence takes the
   guns down far faster than one at a tenth of it; a grinding front wears them too.
9. **An act of war.** Landing a front on a non-hostile colony costs -0.01 x size reputation
   and leaves the faction hostile.
10. **Raids.** Over a hive the raid list has no Ground Defenses, Heavy Batteries, Swarm Nexus
    or shield; the Fabrication Core is still there with its depth line. Over a human colony,
    vanilla's list (no military structures).
11. **Support and Defend** tooltips name the ships the day's return fire takes and the fuel;
    with no fuel aboard and none at home the fleet holds the orbit, "out of fuel to bombard
    with".
12. **Besieged colony at 0 stability.** No decivilization warning while the siege lasts.
13. **The AI.** The log shows each siege slice with its fuel, landings on "bombardment has done
    what it can" or "the troops can hold", and per hive a "Saturate or siege" line with the
    stay's fuel, days and points and the marines held; a saturating expedition reads "saturating
    from orbit", stops at "a day adds less than a day anywhere", and the hive stands, as in 4.
14. **The commander's stop.** An NPC siege of a Heavy-Battery hive bombards a few weeks at most,
    keeps most of its fleet, and lands before its fleets drop to a third of what sailed - no
    siege aborts under the guns. Over a colony it bombards as the spec's tables say.
15. **One day over one world.** With three fleets of one expedition over a world, the log's
    "batteries cost" figures of a day add up to one day's return fire, not three.
16. **Bombard.** Each hive row shows Siege, Bombard and Hunt without overlapping (the Actions
    column is wider). The prompt lists the fleets, "Carries X of the Y fuel the saturation
    takes.", the fuel drawn, and per hive "Name: N days in orbit, S FP bombing, about F FP lost, down D days.";
    the expedition reads "bombarding the ..." on the fleets table, saturates its hives in turn to
    the commander's stop, and Recall brings the unburned fuel home. With no fuel past the
    passage the button is greyed and says so.
17. **Siege prompt** quotes "Carries N of the M fuel its bombardment burns."
**Test run 1 (2026-09-28, clone save_IWBomb1, 700 d, log only).** No exceptions; slices diminish;
colony return fire 3-5 FP/day as the spec says; no decivilization. Fixed after it, untested:
off-screen sieges and razings recompute the defence every step (it stayed at the intact figure);
an unspawned strike lands once its off-screen siege has run (Eventide and Mazalot lost a third of
the strike and never landed; the purge's gate the same); a live station holds a colony's orbit
whatever it weighs, so the first day no longer meets its x3; a suppressed fortification keeps its
stability bonus x condition (the first touch cost ~25% of the garrison); a razing is priced for
the size the hive will have on arrival (ThreatRazing.fuelToDestroyThrough(market, days)); the
raze-or-siege line logs on a changed verdict only. Still open: hive return fire at 0.008 - every
hive bombarded in the run had no guns.
**Test run 2 (2026-09-28, clone save_IWBomb2, 394 d, all off-screen).** Fixes 1, 3, 4 and the log
line held; 3 hives killed by NPC fronts, 0 colonies lost. The station gate (fix 2) is untested -
it only reads live fleets. Razing still razed nothing; fixed after it (jar 19:07, untested): an
unspawned expedition acts only when its payload window ends, and a raze-only expedition's window
was siegeOrbitDays (~120 d) - it is now the days its razing takes (razeRun); a razing short of the
top level pours nothing and takes its fuel home (ThreatRazing.shortOfALevel); ThreatRazing.pour
takes a level 0.5 fuel short (an exact pour left the last level standing). Open: hives never
answered - every hive bombarded had no Ground Defenses or Heavy Batteries.
**Test run 3 (2026-09-28, clone save_IWBomb3, 439 d, all off-screen, jar 19:14).** No exceptions.
Hives arm: 34/34 size >= 3 armed by day 35 (was 6). Razing razes: five hives razed from orbit,
Qaras 3 -> 2 -> 1 -> gone in 2 d; dead worlds the swarm refounds are razed again. Hive return fire
at 0.008: Gamma Golgotha II-L5 (defence 10,952) cost Hegemony ~58 FP/d at condition 0.72, 45 d,
5950 -> 4790 FP, then landed; a colony (Tartessus, 1,200) costs ~6 FP/d. "0 d" Threat landings
are the commander's trade (troops already hold, `readyToLand`), not skipped bombardment. Open:
Hegemony's 2,700 FP against Beta Vigri II (size 8, defence 14,400) - siege sizing weighs only
the Defense Swarm faced (385 FP), and the stop trades 1 FP for 1 defence point (siegeFPWeight
1.0), so it bombarded 27 d to 1,031 FP, landed at condition ~0.7, then lost orbit cover to 2,651
FP of regrouped swarms and stalled at the door.

**Test run 4 (2026-09-28, clone save_IWBomb4, 502 d, jar 20:06).** User's call after run 3: a fleet
point is worth far more than a marine, and return fire was overtuned - sizing left alone.
`bombardFPWorth` 30 (a day must take 30 defence off per fleet point lost: ~6,000 cr a point
armed and crewed, a marine 200) and `bombardReturnFirePerGunDefence` 0.008 -> 0.0008 (LunaLib
migration 5). No exceptions. Hive sieges bled a tenth as much and stopped sooner: Persean
League on Gamma Golgotha II-L5, 5,950 FP, 18 d, lost 85 FP (run 3: 45 d, 1,160 FP), landed
5,998; on Gamma Golgotha I (D 14,400), 7,200 FP, 49 d, D -> 3,906, lost 147 FP; Epsilon Qades
I-C 93 d, 54 FP, Fabrication Core destroyed. Colonies cost ~0.5 FP/d and the stop ends them in
9-24 d (were 39-45). Razing still razes. Because the day's worth (defence taken / FP lost) is
k x F/(F+D) / rate, independent of condition, worth x rate sets a line: a fleet under ~1/3 of a
hive's defence (F/(F+D) < 0.24; 0.36 on a colony) does not bombard at all and plans to land at
full defence - 10,000-11,450 marines on a size 7-8 hive. Marine postponements 327 (run 3: 120
in 439 d), launches 19 (23).

**Test run 5 (2026-09-28, clone save_IWBomb5, 506 d, jar 20:42).** Run 4 plus short of troops,
soften first. No exceptions. Beta Vigri II, run 3's failure: Persean sailed with 5,569 of
10,019 marines, 5,950 FP bombarded 21 d (D 12,600 -> 7,026, lost 97 FP), landed 5,479 and
eradicated the hive. Hegemony likewise took Gamma Golgotha I (5,937 of 10,217 marines, 24 d, 123
FP). Launches 25 (run 4: 19), marine postponements 65 (327), fuel postponements 287 (30) - the
longer bombardment's ordnance (~35,000 fuel for a size-8 hive) is now what a base waits for.
Hives eradicated 5 (4, 3). Two short landings never landed: Defense Swarms that reinforced after
the launch ground them below the abort line with the ordnance unburned (the orbit gate's
problem, not the guns'). Threat beachheads overrun by colony garrisons: 29 (runs 3-4: 29, 30).
The "lands X of Y" line is logQuiet since (21:09 jar, untested).

### Overnight 2026-09-29 - siege AI fixes (built, tested in runs N1-N3)

Runs on clones of save_IWLong19 (day 0 = 0215-05-15, no player, all sieges off-screen), findings
per checkpoint in the session scratch folder. Run 6 / N1 baseline at ~540 d: 22-28 launches, 5 hives
eradicated, 0 colonies lost, Threat hives 39 -> 38 - a stalemate.

- **Off-screen break-off** (`ThreatPurgeFGI.breaksOffAbstract`, called from
  `SiegeRaidAction.autoresolve`). Vanilla's `FGRaidAction.autoresolve` weighs the expedition's
  strength in the system against every hostile fleet there plus the station; where the defence is
  as strong it charges up to 75% damage and skips the raid. `breaksOff` only reads live fleets, so
  an unspawned siege or razing met by converging Defense Swarms came home at 25% with its ordnance
  unburned (7 of 15 razings in run 6, "strength 25%" x10). The same test now runs first, in
  vanilla's units: outweighed by `siegeBreakOffRatio` over a hive still to take, it turns home
  intact ("Abstract break-off at X", "Siege called off"). No break-off while the faction has a
  front on ANY of the expedition's worlds (`holdsAFront`). N2: 14 called off, 0 razings worn.
- **Sibling forward bases pool** (`siegeDonors`, `donorAvailable`). Forward bases were excluded as
  donors; run 6's Hegemony held 19-53k fuel at Alpha Spair I and Calu while Temblor postponed 73
  times for fuel. Now every market in reach gives above its floor; a forward base keeps back
  `siegeOutpostKeepMonths` (3) of its garrison's supply upkeep (`ThreatFrontlines.garrisonUpkeepAt`).
  N2: launches 26 at 270 d (N1: 14).
- **A called-off siege remembers** (`IncursionManager.swarmsMet` / `noteSwarmsMet`). The launch
  gate weighs one world's Defense Swarms (`npcSiegeOrbitPerWorld`), but garrisons converge and
  vanilla's fight weighs the system: N2's sieges sailed into 4-7x their weight, Goodfellow twice.
  `callOff` records the swarms met on the system per faction for `siegeMetMemoryDays` (90);
  `siegeOrbitNeeded` weighs the larger of the two. The bounty the call-off posts sends hunters in.
- **Threat beachheads sized to survive** (`ThreatStrikeFGI.beachheadLanding`,
  `ThreatGroundFronts.beachheadTroops`). Strikes never asked `beachheadSurvives`: abstract ones
  landed after "0 d" of bombardment (the world's even share, 200-300 troops) against counter-attacks
  of 440-680, and 37 of 42 beachheads were overrun in N1 with no colony taken. Now the swarm's
  siege (live and abstract) uses the faction form of `readyToLand` like a purge, and a first
  landing short of the line lands more of what the strike carries, then breaks hulls up at
  `fabricateTroopsPerFP` for the rest (abstract strikes charge `fabricatedFP` off their strength);
  a strike that cannot reach it holds back ("Strike landing at X held back").
- LunaLib migration 6: `frontDangerCloseLossFraction` 0.05 -> 0.005 (the v2 default never reached
  a stored settings file).
- **The orbit gate weighs the whole system** (`npcSiegeOrbitSystem`, `siegeOrbitWeighed`,
  `systemSwarms`). With the break-off in place, N3's first sieges into heavy systems still sailed
  blind (Gamma Spair I-B: 10,656 FP of swarms met by 1,450). The gate now weighs the larger of the
  strongest target world's garrisons, every Defense Swarm in the system, and the swarms last met
  there. A heavy system stays postponed (bounty posted, hunters thin it) and the easiest-first walk
  moves the base on. N4 vs N3 at 540 d: 17 launches vs 37 for 11 vs 13 hives killed, fuel waits
  182 vs 399, hunt battles 41 vs 13, Threat hives 33 vs 34.
- **The swarm relieves its fronts** (`ThreatGroundFronts.losingGround`, `strikeReliefFirst`).
  `wantsExpedition` read only `frontCanHold` (the fortification line, 0.17 x the defence), which
  stayed true while garrisons won every counter-attack. It now also asks whether the world's
  counter-attack by `siegeBeachheadMargin` beats the front; such a front in reach takes the next
  strike before any new target.
- **Off-screen expeditions resolve on arrival** (`ThreatPurgeFGI.resolveOnArrival`,
  `abstractResolveOnArrival`). Vanilla autoresolves an unspawned raid when its payload segment
  ENDS, and that segment is `siegeOrbitDays` (~120 d): strikes whose siege took 0-51 d landed
  150-180 d after launch, and relief reached fronts months after they fell. The same resolution
  now runs a day after arrival and the segment ends. N5 vs N4: relief passes 19 vs 5, razings 10
  vs 6, strikes 139 vs 82 (the staging hive frees sooner).
- **The strike gate** (`IncursionManager.strikeOutweighed`, `strikeDefenceGate`). Nothing weighed
  a target's defence before a strike: 17 of 24 measured strikes in N4 landed nothing, 12 of 13
  against forward bases - vanilla's off-screen fight skips a raid the defenders hold as strongly
  and charges up to 75%. The swarm now passes over a world whose system defence (vanilla's
  `WarSimScript` enemy + station strength) outweighs the strike its staging hive can muster
  (`ThreatColonyManager.peekGarrison`) by `siegeBreakOffRatio`.

- **Relief strikes go to their front alone** (`launchStrike`): a strike whose target
  `wantsExpedition` no longer sweeps the rest of the system, so the whole troop pool reinforces
  the front it was sent for (swept, it split into even shares and the front got 300).
- Review fixes (run N8): `ThreatPurgeFGI.finish` keeps an abort that vanilla's expired-route
  finish would clear; `resolveOnArrival` leaves raze-only expeditions to their razing window
  (`razesAll`); `beachheadLanding`'s fabricated troops join the pool and a live fleet that comes
  up short keeps them aboard rather than land under the line; `swarmsMet` lives in sector
  memory (a distant system's own memory may not tick its expiry).
- **Measured, off-screen**: human sieges do not bombard themselves down - 14 large abstract
  sieges in N5 lost 0-2.2% of their fleet to the guns. Live Defend fleets are untested (every
  NPC siege in the harness resolves off-screen).
- **900-day run (N7)**: Threat hives 38 -> 27 by day ~680, then back to 34 (total size 271 ->
  223) as the swarm refounds razed worlds; 1 Threat ground victory.
**Relief focus result (N9 540 d, N10 900 d, dba556f):** the swarm takes colonies - 3 by day 540 in both runs, 4 by day 900 (Asharu, Jangala, Nachiketa, Yama; Hegemony 12 -> 9) - while the humans eradicate 7 and raze 20, hives 39 -> 30 (size 273 -> 226). No snowball.

Open (design, for the user): before the relief focus the Threat took almost no colony. A ~300-marine beachhead
pushes at x2 casualties (`threatPushLossMult`), wears to half in ~2 months and is overrun while
human relief convoys bring 200-1,800 marines; strike relief comes in 300-troop shares.
