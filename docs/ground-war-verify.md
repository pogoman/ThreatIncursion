# Ground war - in-game verify lists

Split out of `ground-war.md` on 2026-10-01. One-line answers: `facts.md`.

### To verify - marines and veterancy (2026-09-08, built, untested)

1. Drop marines into a besieged colony's **resource stockpile**: the Defenders figure should NOT
   move that tick, and should climb over ~20 days as they are armed. The front row tooltip says
   how many are still being armed.
2. Drop marines into personal **Storage** instead: the Defenders figure should not move at all,
   ever. (Vanilla's own raid dialog will still count them - that is correct and left alone.)
3. The front row tooltip's counter-attack figure should be visibly lower than its Defenders figure
   on a colony holding marines, with the x0.25 line explaining why, and an 'it costs them N
   marines to mount' line beneath.
4. Watch a besieged colony's Marines cell fall over a siege, and check the faction view's Def cell
   agrees with the fronts table - they contradicted each other before (Def counted Storage,
   Marines did not).
5. Land marines from a fleet with a Veteran rank: the front's Troops line should read Veteran, not
   Regular. Withdraw them and confirm the fleet's own rank moves the right way.
6. Reinforce a long-dug-in front: both its rank and its entrenchment should visibly dilute.
7. Confirm an EXISTING save loads and colonies are not caught with 0 armed marines - the seeding
   path (`marineArmSeeded` false on old saves) must mean 'already armed', not 'none armed'.
8. Confirm the colony screen's ground-war tooltip explains the gap between vanilla's own Ground
   defenses readout (garrison only) and the siege's Defenders figure.
9. Every front's Troops line reads the vanilla four (Regular / Experienced / Veteran / Elite),
   the Threat's included - there is no second vocabulary.
10. Fronts already in a save read level 0 (the field is new), so they show a bare rank with no
    percentages until they have fought. That is correct, not a bug.
11. Reinforce a besieged colony and watch the front tooltip's 'Next counter-attack in N days'
    SHORTEN, with the Cadence line saying why. Before this it never moved.
12. Land a fleet of Veteran marines and confirm the front reads Veteran immediately - the level
    is now read before the cargo is emptied, which is the bug that made every landing green.

The carrier is **`ThreatSiegeMalus`** (`threatinc_siege_malus`) - a hidden, unbuildable,
undisruptable structure modelled on `WarFootingDemand` - installed on a colony the swarm
has **besieged** (`$threatinc_besieged`, written by every siege slice, expiring after
`fortificationDisruptDays`) while any defence structure is suppressed, or on any colony
with a front, and removed otherwise (`ThreatGroundFronts.syncSiegeState`, swept every poll
by `sweepSieges` so the condition tracks the clock). Raids and bombardments elsewhere in
the sector stay vanilla. It restores a disrupted structure's multiplier scaled by its
condition under its own key (vanilla stripped it whole - the colony's Defenses tooltip
reads "Heavy Batteries (suppressed, 60% effect)"), applies the district loss
`GROUND_DEFENSES_MOD modifyMult((size - held) / size)`, and - each district held - takes
`districtStabilityPenalty` (1) stability and `districtAccessPenalty` (0.1) accessibility.
The player's own tactical bombardment of a colony is a siege slice like any fleet's
("Sieges from orbit" below).

**The orbital siege is a duel** (`ThreatGroundFronts.siegeSlice`). Each day an unopposed
Threat fleet of F points (x `siegeFPWeight`, 1.0) sits over a colony of defence D
(`getDefenderStr(market, true)`, no cargo marines):

- its defence structures gain `siegeSuppressDaysPerDay` (6) x F / (F + D) disruption days,
  up to fully worn at `fortificationDisruptDays` (180); the
  moment the fleet leaves, recovery starts from there;
- the batteries answer: the fleet loses `siegeBatteryAttritionPerDay` (0.02) x F x the
  batteries' share of D (1 - 1 / their multiplier) / (F + D) points, taken off the live
  fleet smallest ship first (`applyFleetLosses`, the remainder banked in fleet memory, the
  last ship spared - the group's own abort rule takes a gutted expedition home).

So Chicomoztoc after its fortress dies (D ~1,375) costs a 300-point fleet most of its
ships to wear down, a 1,500-point armada far less; Nomios (D ~95) wears out fast for a
handful of points. Every defence building has a
job: batteries hurt the fleet, Ground Defenses slow suppression, the fortress contests
the orbit, a military command speeds counter-attacks, reserve marines deepen the garrison.

**Districts** are the human word for the layers a front takes (strata stay hive-only;
`Theatre.layer`). Each district held, besides the garrison share and the stability and
accessibility above, **seizes** held / size of the colony's other disruptable industries
(`seizedIndustries`, by id so the same ones stay seized), pinned at `districtSeizeDays`
(30) of disruption while held and recovering that fast once the district is retaken. A
HOLDING front still feeds the clocks of every `TAG_TACTICAL_BOMBARDMENT` industry plus
the port as before; a GRINDING one harasses just `grounddefenses` / `heavybatteries` at
half rate.

| | Hive | Human colony |
| --- | --- | --- |
| Layers | strata, underground, one per size | districts, one per size |
| Losing a layer | fabrication and per-stratum defence fall | garrison share, stability, accessibility fall; the district's industries are seized |
| Orbital bombardment | suppresses the war-strata to nothing, the size-anchored strata untouched, weapon growths fire back | suppresses fortification to nothing, garrison untouched, batteries fire back |
| Counter-attacks | paced by the upkeep share fed and the strata still held | paced by stability and military command; strength is the garrison |
| Victory | the Core dies: eradicated, the survivors come home | the last district falls: a hive is seeded on the spot |

## Testing

`tools/test-harness` cycle + a cloned save with injected fronts (see
docs/testing-harness.md; persistent-data XML injection of
`threatinc.ThreatGroundFronts$GroundFront` works - element name uses `_-` for `$`).

### To verify - sieges against human factions (2026-09-06, built, untested)

1. A Threat strike reaching a colony no longer bombards: the log reads "Siege slice vs X"
   every few days per fleet, the colony's Defenses tooltip shows "Heavy Batteries
   (suppressed, N% effect)" climbing down to 50%, and the fleet loses ships to the batteries
   (log: "batteries cost N FP").
2. The landing comes once every defence structure is fully worn out or the
   troops could hold - not before. The strike intel reads "suppressing the defences" then
   "moving to land - defences worn out" or "moving to land - the troops can hold,
   sparing the ships"; the purge's reads "besieging from orbit" then the same.
3. A dry Threat front digs in (board: stance "dug in", Arms "dry", tooltip "Dug in for the
   next expedition - final push in N days"), the next strike goes to that world more often
   than not, and after 120 days with none it announces the final push.
4. Entrenchment: a fresh landing reads "x 0.75 entrenchment (0 of 30 days)", reaches 1.00
   only while holding, and halves when a layer is taken or a counter-attack lands.
5. Districts: each one held shows on the colony screen with stability -1 and accessibility
   -10% per district, "Seized: ..." naming the industries, and those industries disrupted
   at 30 days; retaking the district frees them within 30 days.
6. Victory: the last district falls and the world is a size-2 hive at once, with no deciv
   intel and no colonisation wave queued for it.
7. The fronts table reads Troops / Held / Falls, and no tooltip says "marines" of a front.
8. A player colony's stored marines are counted once in Defenders (compare the vanilla
   Defenses tooltip figure plus nothing).
9. Old saves: fronts load with daysAlive 0 (the tooltip falls back to the timestamp),
   dryTimestamp 0, finalPush false; a bombarded colony picks up the siege state on the
   first poll.

### To verify - the duel on both theatres, Support (2026-09-06 evening, built, untested)

10. A faction or player-commissioned siege expedition over a hive logs "Siege slice vs X"
    per fleet instead of "Siege pass (tactical)"; the hive's Ground Defenses tooltip reads
    "(suppressed, N% effect)" falling to 0% eventually; the
    expedition loses ships (log "batteries cost N FP"); its intel reads "besieging from
    orbit" then lands. The sitrep lists one "Orbital siege" line per world.
11. A hive struck by a marine raid or saturation pass keeps most of its defence bonus now
    (no half-effect step): 20 days on a 300-day clock is 93%.
12. The player's tactical bombardment of a hive and of a human colony shows the slice
    prompt (days per structure, fleet points lost, fuel), takes the smallest ship(s) and
    names them, never the flagship, and moves each structure's clock by about the quoted
    days rather than 60 / 365. A second bombardment once fully worn is refused with the
    "as far as orbit can push them" line.
13. The button reads Support; its row label, the fleets table kind, the board Activity
    entry ("supporting the siege") and the Supply / Pull out refusal ("send Support
    first") all say Support. Saves from before the rename keep their Escort orders working
    under the new name.
14. A Support fleet on station over a besieged world with a clear orbit logs "Support over
    X: ... lost N FP" as the batteries bite and the world's fortifications fall to
    nothing; over a contested orbit it fights instead and suppresses nothing.
15. Balance to read off the log: what a default 400-point siege expedition does to a
    size-5 hive per slice, and whether its landing gate opens before `siegeOrbitDays`.
16. The planet shows vanilla's bombardment burst on every siege slice while the player is
    in-system (one burst per whole day of suppression added per world), from an expedition, a Support
    fleet or the player's own tactical bombardment. The Support button's tooltip and its
    confirm name the fleet that goes and quote the days per day it suppresses and the
    fleet points per day the batteries take.

### To verify - the landing fleet defends, and the Defend order (2026-09-07, built, untested)

17. A faction expedition against a two-hive system (Gamma Hero) lands at the first world
    and the log then reads "Siege pass (landing) vs X" followed by "Order issued: <faction>
    defending the orbit of X" for the landing fleet and any other empty fleet in the
    system; the fleets table shows them as **Defend**, "-" for days left, with Recall. No
    "Siege slice vs <second world>" follows: instead "Siege of <second world>: nothing left
    to land, no siege", or nothing at all once every fleet has joined the Defend.
18. A Defend fleet over a front that holds logs no "Defend over X: ... lost N FP" and the
    hive's fortifications recover; the moment the front cannot hold (a
    counter-attack, armaments out) the slices and the bombardment burst start and the
    fleets-table task reads "covering the front on X", back to "defending the orbit of X"
    when it can hold again.
19. When the front wins (hive eradicated) or is destroyed, the log reads "Order stood down:
    ... defending the orbit of X" and the fleet flies home on the tracked leg; the
    expedition itself ended when its last fleet detached, with "Expedition return ..." and
    a fuel refund scaled by the surviving strength (not 0%).
20. The Defend button sits between Support and Pull out on the player's front rows; its
    tooltip and confirm name the fleet, then say it holds the orbit, does not bombard while
    the front holds, and only while it cannot bombards at about N days per day with the
    batteries answering at about M points per day. Pressing Support over a world with a
    Defend fleet takes that fleet (and the reverse): the two swap a fleet's doctrine.
21. The Threat's strike: after "Strike pass (landing)" the log reads "Swarm defend: <fleet>
    stays over X"; the swarm fleet sits over the colony and bombards only while its front
    cannot hold; the rest of the strike sweeps on; a swarm fleet with nothing left to land
    joins the station rather than slicing; the station stands down ("Swarm defend over X
    stands down") when the front is gone and the fleet flies back to its staging colony.
22. Reading the duel's size off the log (2026-09-07, after a Defend fleet looked as if it
    bombarded for nothing): every "Siege slice vs X" line now carries "+N d on the clock
    (C of F)", and each Support / Defend fleet logs once a day "<fleet> at P FP suppresses
    ~N d/day, batteries ~M FP/day; clock C of F d". The Support and Defend tooltips carry
    the same clock line. A 100-point task force against a hive whose defence figure is in
    the thousands adds about one day per day on a 300-day clock: that is the proportional
    rule at `siegeFPWeight` 1.0 and `hiveSiegeSuppressDaysPerDay` 30, not a lost slice.
    CORRECTION the same morning: the slices WERE lost - four fleets of 850 points should have
    added 10-30 days a day. The gate before the slice held the orbit contested while any
    Defense Swarm was alive in the garrison list, refilled every poll; now a strength contest
    (`orbitHeld`). Verify: with Defend fleets over a hive the daily log line reads
    "suppresses ~N d/day ... clock C of F" on most days and "fights for the orbit - H FP of
    defenders against F FP here" only while a real swarm force is at the planet; the clock
    climbs by the summed rate and reaches full wear (300 on a hive) in days, not months.
    SECOND CORRECTION: with the contest fixed, only one of four Defend fleets ever logged
    a slice - the other three failed the 1,500-unit distance check, chasing swarms round
    the system on MAKE_AGGRESSIVE. Support / Defend fleets and the swarm's stations are now
    leashed per frame exactly as garrison swarms are (`ThreatFleetOrders.enforceLeash` /
    `leash`: beyond the hold range and out of battle, blinkers on, GO_TO_LOCATION back, the
    orbit re-queued, blinkers off on arrival). Verify: every Defend fleet logs its own daily
    "suppresses" line; "strayed N units - recalled to the orbit" appears at most now and
    then; the clock climbs by the four fleets' summed rate (~11 d/day at 250 FP each).
    THIRD CORRECTION (the reload after the leash): three of the four Hero II fleets did
    slice, but on different days and almost never two on the same day, so the clock climbed
    at one fleet's rate; only 7 leash lines in 35 days, all at 1,500-1,502 units. The
    leash logs on the first frame out and then nothing while the fleet is in a battle or
    already on its way back, so a fleet chasing a swarm into a fight and crawling back for
    days was invisible. At Gamma Brador IV a Support fleet flying IN was recalled every
    poll, 8,665 units down to 1,846, by something re-tasking it each poll. Changes:
    - HOLD STATION: Support / Defend fleets and swarm stations keep the blinkers on for
      the whole order (`orbitFleetsHunt` false) - they fight what attacks them or meets
      them at the planet, and never pursue. Defense Swarms are aggressive, so the fight for
      the orbit still happens, at the planet.
    - Once a day every order fleet logs one line: at the planet, the slice line as before,
      or "holds the orbit - the front holds / the defences are worn out"; away from it,
      "<fleet> at P FP is N units out (or: out of the system, in X), <assignment>, in a
      battle, blinkered/hunting". A leash line now says what the fleet was doing
      ("(was: ORBIT_AGGRESSIVE -> Gamma Brador IV '...')"), which names the other writer.
    Verify: four Defend fleets over one world give four lines a day, all "suppresses" once
    the front cannot hold, and the clock climbs at their sum toward the full clock (300 on a
    hive); no fleet is more than 1,500 units out for more than a day; if "strayed" repeats
    for one fleet, the "(was: ...)" text says who keeps re-tasking it.
    2026-09-28: orbit alone can now wear the defences all the way to nothing by itself - a
    front on the ground just gets there faster (`fortificationOrbitFloor` is gone).

### Verified - military options on a hive (2026-09-07, tested in game, works)

The two that kept failing now pass: over a hive with **no** defenders "Engage the defenders"
greys ("There are no defenders to engage"), and over one with a garrison in orbit raid and
bombardment grey. If either ever goes clickable again, the panel is being rebuilt after the
state is written - check what touches `options` after `gateOnDefenders` in `showDefenses`.

Known cosmetic gap, left alone deliberately: `gateOnDefenders` writes the raid tooltip only
when its own defender count is non-zero, so if vanilla greys raid for a reason
`threatDefenders` does not count, the option greys with no explanation. Vanilla's raid-cooldown
greying has no tooltip either, so that case is already at parity. Vanilla's
`addOptionConfirmation` on Engage is also lost to the rebuild and never replayed; it is
unreachable on a hive, where the player is always hostile.

The rest of the list below was the pre-fix verification plan and still holds:

- Hive with a garrison in orbit: text says "The swarm holds the orbit: N fleets, X fleet
  points"; Engage enabled; Launch a raid, Consider an orbital bombardment and Ground
  operations > Land ground forces disabled with their tooltips.
- Engage: opens the fleet encounter against the nearest garrison, never the "no defenders in
  range" line while a swarm is visibly in orbit; after killing every swarm, Go back / re-enter
  shows raid and bombardment open and Engage disabled ("There are no defenders to engage").
- Garrison hunting you, still beyond 1000 su of the world when the menu opens: text says the
  defenders are inbound with the nearest distance; Engage, raid, bombardment all disabled;
  once it is in reach, re-enter - Engage open.
- Garrison fighting an NPC siege fleet at the hive: raid open, bombardment closed, Engage
  offers to join the battle.
- Check `starsector.log` for `Military options at <world>:` - it lists every swarm fleet the
  menu weighed with distance and state; a wrong menu is explained there.
- Human colony: untouched (vanilla text, vanilla gates) - the override is Threat-only.
- Knob: `threatinc_defendRadius` (LunaLib "Orbit Defence Radius"); at 300 a garrison parked
  600 su out no longer blocks a raid.

### To verify - troops push on landing (2026-09-07, built, untested)

22. Land at holding strength: the dialog reads "on the ground and moving on stratum 1", the
    log line ends ", pushing", the front row shows the push stance with a Dig in button (no
    Push button), and the board tooltip counts push progress from day one.
23. Land too weak to hold: "digging in - too few to hold, so they wait for more", log ", dug
    in"; a later troop drop that reaches holding strength adds "Reinforced to holding
    strength, the front moves on stratum N" and the stance flips to push.
24. Order Dig in, then send an armaments-only drop or convoy: the front stays dug in. Send
    troops: it pushes again. With `threatinc_frontAutoPush` off no landing changes stance.
25. NPC and Threat fronts behave as before (they already pushed on their first tick).

### To verify - the planetary shield (2026-09-07, built, untested)

- A colony with an intact Planetary Shield, tactically bombarded: the prompt lists the shield
  as a target with its own %, and says "The shield turns 75% of that away from everything
  under it; this pass puts N days on the shield itself." The days quoted for the other
  structures are a quarter of what the same fleet does to an unshielded world.
- Confirm it. The shield's disruption clock starts; the guns take the reduced figure. Check
  the log line: `siegeSlice ... shieldThrough=0.25`.
- Bombard again. `shieldThrough` has risen (the shield is worn, so more gets through) and the
  guns take more than last time. Repeat until `shieldThrough` reaches 1 (2026-09-28: the
  orbital floor is gone, so orbit alone gets there eventually).
- Land a front on that world: further passes take the shield to 0
  condition and `shieldThrough` to 1 faster than orbit alone.
- The colony screen's invasion tooltip reads "Planetary shield at N% effect: it absorbs M% of
  incoming disruption." after "Defences suppressed to: ...", and the shield appears in that
  suppressed list once it is disrupted.
- Support / Defend buttons over a shielded world show the shield line under the siege clock,
  and the disruption-per-day figure they quote is the reduced one.
- A Threat strike against a shielded player colony takes visibly longer to suppress it than
  against an unshielded one of the same defence figure.
- With UPS installed: the FIRST bombardment of an intact shield still shows UPS's "absorbed
  the brunt" message and its halving; the second and later ones do not (the shield is down).
- Set `threatinc_shieldAbsorbEnabled` false: the shield is not a bombardment target, quotes no
  absorption anywhere, and a saturation pass disrupts it like any other structure.
- A world whose ONLY defence is a shield (no Ground Defenses / Heavy Batteries / Patrol HQ /
  Military Base / High Command): the tactical option still offers a pass, targets the shield
  alone, and reports no return fire.
- **The x3 is gone.** On a colony with a Planetary Shield, the ground-defence breakdown on the
  colony screen names no Planetary Shield entry at all, and the figure does not change when the
  shield is disrupted or repaired. Set `threatinc_shieldDefenseBonus` to 2 and it reappears -
  and then *falls in a straight line* as the shield is bombarded, never dropping to nothing in
  one step.
- **The tooltip is ours.** Hovering the Planetary Shield on the colony screen describes
  absorption in proportion to condition and quotes the live condition / absorb figures - not
  the old flat-50% text, and no claim about size reduction or decivilization.
- **Save compatibility.** Save with a shielded colony, disable ThreatIncursion, load: the save
  must open with a plain vanilla shield rather than a `CannotResolveClassException`. Re-enable
  and load again: the shield is ours, and a shield that was disrupted still is (the disruption
  key is pinned to vanilla's).
- Land a front on a shielded world and hold it: the shield's condition keeps falling,
  reaching 0 at the same rate the guns fall.
  While GRINDING rather than HOLDING it should fall at `frontGrindSuppressMult` of that.
- Balance watch: at LunaLib defaults (`siegeSuppressDaysPerDay` 6, `siegeOrbitDays` 120) a
  600 FP expedition wears an unshielded size-5 colony's guns down within its orbit budget,
  but a shielded one takes longer - so from orbit alone it may time out. Landing does not
  need the guns already worn down (suppression lowers `defenderStrength` continuously), but
  if the swarm never manages a landing on shielded
  worlds, `threatinc_shieldAbsorbMax` is the dial; 0.5 roughly halves the penalty.

### To verify - fabricating troops from the fleet (2026-09-08, built, untested)

The mechanic starts where bombardment stops, so the setup is a Defend fleet over a world
whose fortifications are already fully worn with a front that cannot hold.

1. **It engages at the right moment, and only then.** Bombard a world to fully worn with a
   front on it that is short of `holdRequirement`. The fleet's log line should switch from
   the slice line to `... FP fabricates - the defences are worn out and the front is N
   short of holding`, and the board row from "covering the front on X" to "breaking up for
   the front on X". While not yet fully worn it must still bombard.
2. **It stops when the front holds.** Once the drops carry the front over the line the row
   should go back to "defending the orbit of X" and the fleet stop losing ships. Watch for
   oscillation across the boundary - that is what `fabricateHoldMargin` is for; if it
   chatters, raise it.
3. **It commits proportionally.** A front a few hundred marines short should cost a frigate
   or two, not a fleet. Check `broke up N FP of hulls into M troops` against the gap in the
   preceding line: `M` should be about the shortfall, not the fleet.
4. **It never goes home.** Let the batteries and the fabrication grind a Defend fleet under
   33% of its arrival strength while the front still stands. It must stay. Then win or lose
   the front and confirm it *does* leave.
5. **The last hull.** Run a small fleet down. It should thin to one ship, log `(nothing left
   to break up)`, and keep holding the orbit rather than despawning or throwing errors.
6. **The dry tail.** A front with plenty of marines but zero armaments cannot hold; confirm
   a drop still happens (`frontMinMarines` worth) and its armaments un-dry the front.
7. **The price.** On a world with heavy batteries, `lost N FP to the batteries doing it`
   should appear beside the drop and be larger than the suppressed-condition figure the
   slice line was quoting before the guns were fully worn. On a world with no defence
   structures at all, fabrication should still run and cost nothing.
8. **Both theatres.** A faction/player Defend over a hive, and a swarm station over a human
   colony under invasion. The swarm's messages should read "The swarm over X is breaking up
   its own ships".
9. **The visual.** In the same system, fragments should fall inward to the planet with a
   ping and a floating label - and it must NOT look like the bombardment burst. Out of the
   system, nothing should be drawn and nothing should throw.
10. **Off.** `threatinc_fabricateEnabled` false: a Defend fleet with the defences fully worn
    idles as it did before, with the old "the defences are worn out" reason, and the
    tooltip loses its fabrication lines.

### To verify - a station that comes back (2026-09-08, built, untested)

1. Let a Threat siege run long enough on a world with a star fortress that the station is
   destroyed and its industry goes into repairs, then let the repairs finish while the front
   is still on the ground.
2. The respawned station should be **engaged**, not orbited past. Log lines: "fights for the
   orbit" from `supportSlice`/`fabricateTroops`, and a swarm station whose guns go cold should
   read "fighting for the orbit" on the board rather than "the front holds".
3. It should fight it whether or not the swarm outweighs it - the case that started this is a
   Threat force big enough to *hold* the space against the fortress and ignore it anyway.
4. It must not chase: check the leash lines still recall strays, and that no fleet follows a
   runner out of the system on the back of the new aggression.
5. After the station dies, ground defence should drop by the fortress multiplier and the front
   should start making progress again; the fleet should go back to holding station (no more
   aggression grants in the log).
6. Watch a Defend fleet's strength - fighting a star fortress may grind it under
   `defendMinStrength` and send it home. That is the existing valve, but check it does not
   turn every long siege into a fleet that leaves.

### To verify - the swarm off armaments, and paying on the assault (2026-09-08, built, untested)

1. **They never run dry.** Let a Threat landing sit on a colony well past 90 days with no
   further wave. Its board Arms cell should read a grey `-` throughout, no "have exhausted
   their heavy armaments" message should ever fire, and the front must not dig in for an
   expedition, make a final push, or collapse.
2. **The old rule still works.** Set `threatinc_threatFrontNeedsArms` true and confirm the
   whole 2026-09-06 sequence returns: the dry message, x0.5 strength, x3 attrition, the
   120-day final push, the collapse.
3. **Assault losses doubled.** Compare a pushing Threat front's Losses figure against a
   pushing faction front of the same size on a comparable world - the swarm's should be
   about 2x. The row tooltip should name `x2.0 swarm assault`, and it must NOT appear while
   the front is dug in.
4. **The three figures agree.** The board Losses cell, the row tooltip and the push
   confirmation estimate must all quote the same number for a Threat front. This is the
   thing most likely to be wrong - `pushCasualtyEstimate` is a separate copy of the formula.
5. **Reinforcement priority survives.** A Threat front that cannot hold should still pull
   the next strike toward its world (`strikeReinforceWeight`, 4x). Check the log for the
   strike target pick while such a front stands.
6. **Fabrication does not ship them armaments.** A swarm Defend station feeding its own
   front should land troops with **0** armaments (`fabricateArms` returns 0), and the front
   should not gain a supply figure.
7. **The colony tooltip.** A world under Threat invasion should show "Invaders' losses: N
   troops per 30 days at their current stance" where it used to show heavy armaments, and no
   final-push line.
8. **Nothing else moved.** A player or NPC front on a hive must still burn armaments, still
   go dry, still show days of supply, and still request a pickup when dry and below grind
   strength.

