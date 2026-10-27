# The grand fleet - NPC attack doctrine (BUILT 2026-10-26 evening; runs gf1-gf5 that night and the next morning; the 2026-10-27 07:37 jar holds every fix and run gf5 confirmed them - section 10; the held chain of section 10 is in the 08:58 jar, untested)

The user's choices (2026-10-26, the dialog): REPLACE the council's sieges; military score = the
board's Navy figure (hulls free + at sea, `ThreatGrandFleets.navy`); stay at a world until its
strata hit 0. Built as sections 1-3 and 5 below describe, in `ThreatGrandFleets` (new) and
`ThreatPurgeFGI` (grand-fleet legs, the hop). Untested: the hop (a new leg sailing from the
system just fought in, live fleets handed over) is the part to watch first.

The user, 2026-10-26 evening, after a run in which every NPC siege sailed with 0 marines
(`facts.md`, "Every NPC siege sails with 0 marines"):

> im sick of all the sizing failures i want to rethink attack strategies for humans, given any
> amount of marines and bombing can hurt a threat planet now why dont we try a simplified
> strategy. its gonna be too hard to predict what they need given the threat are so fluid with
> colony sizes and strata etc. what if each faction just saves up for a grand fleet and they have
> certain tiers given the size of their military score? so heg doesnt fly until it musters a
> certain size, but that size would be much bigger than luddic path for example. then that fleet
> flies around destroying the closest thing first until they run out of fuel or supplies and need
> to head home

## 1. What goes

Everything that sizes an attack against a hive before it sails: `needAndWear` /
`raidStrNeededAt` for NPC landings, `razeWorlds`' saturate-or-siege verdict, `siegeCanPay`'s
marine, arms and ordnance reads, `landsAfterSoftening`, the short-landing pricing, and the
council's HAMMER / STARVE / JOINT plays that start sieges (`ThreatPlays.invade`, `besiege`,
`strike`). They stay in the code behind `npcGrandFleet` (false restores today's council) but
the NPC war runs on the doctrine below. RECON plays stay: a faction still has to find hives.
Relief of an own world under a Threat army (`ThreatFleetOrders.relief`) stays. The player's
own orders are untouched.

## 2. The doctrine

- **One grand fleet a faction** (`grandFleetsPerFaction`, 1). It musters at the faction's base
  nearest the nearest known Threat world (an ally's staging host counts, as today).
- **Muster size is the faction's military score.** Score = the board's Navy figure, hulls free
  plus hulls at sea (`ThreatGrandFleets.navy`). Grand fleet = `grandFleetShare` (0.5) of it,
  floored at `grandFleetMinFP` (300) and capped at `grandFleetMaxFP` (3,000). The floor is a
  floor: a navy below it waits for its yards (run gf1: the Diktat sailed its whole 243-FP navy,
  then 75 FP). The Hegemony's score is several times the Path's, so its muster is several
  times larger and takes longer. Nothing sails until `ThreatHulls.freeFP` covers the whole size.
- **It picks a world it outweighs.** The nearest known Threat world whose system's seen swarms
  (`IncursionManager.systemSwarms`, the faction's last report) x `grandFleetOutweigh` (1.0) are
  within the muster size, and that no leg was called off over within `grandFleetRetryDays`
  (60). None: it keeps mustering and the board says what outweighs it.
- **It sails on hulls, the passage and the cruise's supplies - the razing fuel is taken as found**
  (the user, 2026-10-27 12:40, option 1 on hw288c: "every attack goes with whatever it has, they can
  still land marines and defend the orbit" - section 12). The base banks the passage to that world
  (`IncursionManager.expeditionPassage`) and `grandFleetCruiseDays` (120) of the fleet's supplies,
  and takes **every** marine, armament and fuel unit the base and its donors hold above their
  reserve (the passage first) - no landing arithmetic, no kill bank. Short of hulls, passage or
  supplies it waits and the staging planner fills the base (the convoys stage the target's razing
  fuel, `razeFuel`, as a want, but it never holds the sail). SUPERSEDED (2026-10-26 to 27): the
  bank of the target's razing x `grandFleetOrdnanceMargin` for `grandFleetKills` worlds and the
  passages between (`heldChain`, `chainFrom` - kept in the source, unused).
- **It flies to the nearest Threat world and destroys it.** Nearest by passage from where
  the fleet is, not from home. At the world: its ships fight whatever holds the orbit (a
  fleeing fleet holds nothing - built 2026-10-26); its fuel razes strata every day
  (`hiveRaze`); its marines land the day the beachhead holds (`readyToLand`, unchanged) and
  are reinforced from the holds; it stays until the world is gone (strata 0) or it is beaten
  below vanilla's abort line. AS BUILT (run gf2, `ThreatPurgeFGI.dailyDay`'s `stays`): the
  leg's daily siege keeps a hive as its world after the landing - the landing is
  `landStaying` (vanilla's passes, the world kept), the days after bombard while the ordnance
  lasts, and when orbit can do no more it holds the orbit over its front; the ordnance running
  dry ends the stay, as does a hive it can neither bomb nor land on with no front of its own
  there. `siegeOrbitDays` and "nothing to land" do not apply to it; its route segment is kept
  30 days ahead (`stayWindow`). The hive gone, `dailyWorld` passes it over ("gone" / "no
  longer a hive") and `legDone` counts it destroyed. LIVE (the player in range, run gf3): the
  same stay in `siegePass` - with its front down or nothing aboard to land, every pass is a
  slice of bombardment (`liveSlice`), unspent, while the ordnance lasts and the hive stands;
  its fleets never leave for Defend (`stayOnDefend` returns for a grand fleet), so they sail on
  with the next leg. `IncursionManager.abortPurgesAgainst` (the purge stand-down when every
  target is dead) skips grand fleets: their worlds' destruction is the leg done, not an abort.
- **Then the banked chain's next world, else the next nearest** it outweighs (the user,
  2026-10-27: the chain first - `GrandFleet.chainIds`, dropped as its worlds die or fall out of
  reach, waited for while unaffordable), from where it stands, while the fuel aboard pays the
  passage there, that world's razing and the passage home, and the supplies the days of it plus
  `grandFleetReserveDays` (20) (`ThreatGrandFleets.hopNeed`); else home, where it dissolves into the base (hulls, marines
  and armaments return as today's returning fleets do). A leg beaten on arrival (vanilla's
  call-off, `ThreatPurgeFGI.callOff`) turns home with "Grand Fleet Turns Home" and its system
  waits `grandFleetRetryDays`.
- **Home under attack**: nearest-first already sends it to the hive in its own system; an own
  world under a Threat army is the relief mechanic's, as today.

## 3. Knobs (defaults)

| knob | default | what |
|---|---|---|
| `npcGrandFleet` | true | the doctrine on; off: today's council sieges |
| `grandFleetsPerFaction` | 1 | grand fleets mustering or out at once |
| `grandFleetShare` | 0.5 | share of the navy's standing FP that musters |
| `grandFleetMinFP` / `grandFleetMaxFP` | 300 / 3000 | the muster's floor and cap |
| `grandFleetKills` | 2 | UNUSED since 2026-10-27 (no kill bank, section 12) |
| `grandFleetCruiseDays` | 120 | days of supplies banked before it sails |
| `grandFleetReserveDays` | 20 | days of supplies kept beyond the passages when it hops |
| `grandFleetOrdnanceMargin` | 2 | UNUSED since 2026-10-27 (no kill bank, section 12) |
| `grandFleetOutweigh` | 1.0 | a world is picked only where the fleet's FP cover this x the swarms last seen there |
| `grandFleetRetryDays` | 60 | a system a leg was called off over is not picked again for this long |
| `grandFleetPointsPerFleet` | 10 | difficulty points a fleet is built of (25 FP a point) |

## 4. Open choices (the user's) - DECIDED 2026-10-26: 1 replace, 2 navy FP, 3 strata 0

1. Replace the council's sieges (section 1) or run the grand fleet alongside them?
2. Military score = the navy's standing FP (proposed), or the council's weight (the sum of
   colony sizes, `Picture.ourWeight`), or a fixed tier table by faction?
3. "Destroy the closest thing": stay at a world until its strata are gone, or move on once the
   marines are landed and the front holds (the front finishes it)?

## 5. Build shape (for the session that builds it)

`ThreatGrandFleets` (new, EveryFrameScript, persisted per faction: muster base, size, state)
musters and launches one `ThreatPurgeFGI` per leg with every hive in the destination system
as its targets and the whole pool aboard; when a leg's raid stage ends with provisions above
the home line, the FGI gets travel + raid stages appended for the next nearest known hive
system instead of its return stage. Reuse: `ThreatPurgeFGI` (fleets, holds, daily siege,
landing, return), `ThreatIntel.known` (targets), `ThreatHulls` (score, free FP),
`ThreatConvoys.planLogistics` (filling the muster base), `ThreatNotice` (Grand Fleet Musters /
Sails / Returns). Board: one row a faction on the faction view, "Grand fleet: mustering 640 of
1,200 FP at Chicomoztoc" / "at Thrial, 2 worlds left". `code-map.md` row for the new class.

## 6. Run gf1 (2026-10-26 night, the 21:18 jar, the user's StarLord save cloned, 364 days)

The first test, from the user's current save (Sindria a size-1 hive in Askonia with a 249-FP
swarm over it, the Diktat's Volturn and Cruor beside it). What happened:

- **Only the Diktat sailed.** Hegemony, League and Independents mustered for Askonia from day
  one and never left: the fuel bank was `grandFleetCruiseDays` of razing at the fleet's full
  rate - 3,000 FP x 1 stratum/FP/day x 2 fuel x 120 days = 723,000 fuel (League 724,000,
  Independents 390,000) against 250,000-280,000 banked. A day-count bank prices a 3,000-FP
  fleet as if it razed 75 hive sizes. FIXED: the bank is the kill (`razeFuel`, section 2).
- **The Diktat sailed four times with everything it had and was called off every time.** Its
  navy was 243 FP, below the 300 floor, so it sailed the lot (250 FP, 1 fleet, 193 marines,
  60,000 fuel of which it burnt none), reached Askonia 19 days later and met 1,214 FP of swarms
  (the swarm had shifted Zeta Ranau's defence to Sindria meanwhile) - vanilla's call-off, no
  bombardment, no landing, back to Kazeron; then again from Cruor (250 FP vs 3,476), then
  75 FP (vs 1,973 at Salus, 39% came back), then 68 FP (vs 4,382). Nothing in the doctrine
  weighed the known swarm or kept the floor. FIXED: the floor is a floor; the target is the
  nearest world the fleet outweighs by the faction's report; a called-off system waits
  `grandFleetRetryDays`; the notice reads "Grand Fleet Turns Home".
- The hop never ran (no leg finished its system), so it is still untested. No exceptions in
  364 days; recon under the grand fleet sent scouts as meant ("grand fleet: the picture of X is
  stale"); the Tri-Tachyon and Path logged "no known Threat world in reach of a base".
- Harness notes: `runto.ps1` yields the machine while the user is at it, so the run went at
  normal speed (364 days in about 45 minutes). The clone `...sd1hw275d3725gf1` is left in
  `saves\` (the guard refuses deletes under Program Files).

## 7. Run gf2 (2026-10-26 night, the 22:03 jar, the user's day-4036 StarLord save cloned, ~320 days)

The second test, from the save before the user's latest (`...sd1hw275d4036`, cloned `...gf2`),
war days 4,039-4,361 at normal speed (the user was at the machine; they quit the game out of
supplies). 0 exceptions, no "Grand fleets: error". What happened:

- **Every faction musters and sails now**: the Path on day 1 (1,575 FP, 2,049 marines), the
  Hegemony 3,000 FP in 12 fleets with 13,731 marines, the League and Independents at 2,400-3,000;
  the Diktat waits on hulls ("hulls 0 of 1,025"). The outweigh rule works ("turns home - 3,504 FP
  of swarms seen at Epsilon Shero against 975"). 29 "Grand Fleet Sails" notices in 320 days, all
  NPC: the player faction is skipped (`ThreatGrandFleets.daily`, `isPlayerFaction`) and never
  sailed.
- **A leg landed everything and left two days later, credited 0.** 13,731 marines on Labraxas,
  13,134 on Huascar; the daily siege then ended the world ("landed (ready)" / "front") and the leg
  turned home with its razing fuel unburnt (8,251 / 9,244). The fronts left behind did eradicate
  the hives (12 "Hive Eradicated" notices) but the fleet's "0 worlds destroyed" and its re-sail
  every few days were the noise the user saw. FIXED (section 2, "AS BUILT"): the leg stays.
- **The hop fired four times and every hopped leg aborted silently.** Independents and League to
  Yma (9 ly, 0 marines, 31,508 / 29,893 fuel), League and Hegemony to Duzahk (4 ly, the Hegemony's
  with 1,190 marines): "Siege expedition aborted (0 ground actions)", everything refunded at
  strength 100%, 16-20 days after sailing; the Hegemony's got "Daily siege takes over Duzahk" and
  aborted the next day. CAUSE: `ThreatPurgeFGI.initActions` swapped the travel action and dropped
  the wait AFTER `GenericRaidFGI.initActions` had built the route from the stock actions, so the
  route's segments pointed at a wait at the source and a travel from it - actions the leg no longer
  had. Vanilla's `FleetGroupIntel.spawnFleet` (the player in range of the route) drops every action
  up to the current segment's and aborts when none is left. FIXED: the hop's route is rebuilt from
  its own actions (`removeRoute` + `createRoute`), the old leg's route is removed on the hop, and
  every abort now logs "Expedition abort state: ..." (segment, actions left, live/abstract, route
  damage, daily siege day).
- **The Path's first leg was mauled in transit** by a 4,642-FP strike at its own base ("Off-screen
  fight in Al Gebbar (strike): threat 4642 vs 2210 defending ... 1657 other FP (3 routes)") and
  vanilla's route-damage abort sent it home at 25% with nothing said. FIXED: the end notice and log
  carry the strength left ("%s of its strength left"), and a fleet under vanilla's abort line
  (`getGroupAbortsMissionFPFraction`) reads "Grand Fleet Lost".
- **The bank was one kill.** A hop happens only when the fuel aboard pays the next kill plus
  both passages; big systems never qualify (Mel Texat 134,167 fuel, Kepheus 64,120, Morgoth
  101,596-130,400 against 8,000-50,000 aboard), so every leg went home after one world. The user
  (2026-10-27): bank two kills - `grandFleetKills` (2), the muster prices the target, then the
  next nearest it outweighs from there, the passages between and the one home (`chainFrom`).
- The council's plays loaded with the save finished their sieges (independent#54 HAMMER 5,475 FP,
  Hegemony daily sieges at Damar's Star and Hadreel); no new HAMMER/STARVE/JOINT play started.
- Harness: run at normal speed with the user at the machine; the clone `...sd1hw275d4036gf2` is
  left in `saves\`.

## 8. Run gf3 (2026-10-27 morning, the 06:21 jar, the day-4036 save cloned, 244 days)

The 22:52 jar's fixes plus the two-kill bank (`grandFleetKills`), from the same save (clone
`...gf3`), war days 4,039-4,283; the game was closed at 06:37. 0 exceptions. What happened:

- **The stay works, abstract.** "landed (ready), stays" and "holds the orbit over its front" lines
  (7), and bombardment razes strata to nothing: `hiveRaze Labraxas fp=3000 days=1.00 strata=-2275
  fuel=4550 left=0 DESTROYED` - a 3,000-FP fleet razes 3,000 strata a day (`bombardStrataPerFPDay`
  1.0), so a size-5 hive dies in a day of bombardment for 4,550 fuel. 13 "Hive Razed" and 10 "Hive
  Eradicated" notices in 244 days against gf2's 0 and 12.
- **Every kill ended the leg as an abort, credited 0.** `IncursionManager.abortPurgesAgainst`
  (the purge stand-down: every target dead) aborted the leg the moment its last world died -
  "Expedition abort state: SiegeRaidAction, 2 actions left, abstract, route damage 0%, daily
  siege on, day 1" - so `legDone` never ran: 13 of 14 ends read "0 worlds destroyed" and no hop
  was tried. FIXED: the stand-down skips grand fleets.
- **Live legs (the player in Askonia) landed everything and sailed home empty.** The League's
  2,900 FP landed 5,949 marines on Volturn, `stayOnDefend` handed all 12 fleets to Defend orders,
  and `legDone` found 0 FP to send on (no log line). FIXED: the live stay in `siegePass`
  (`liveSlice` a pass, unspent), `stayOnDefend` returns for a grand fleet, `legDone` logs when it
  has nothing to send on.
- **The strength line works**: the Path's first leg, mauled at its own base, reads "Grand fleet
  luddic_path lost after 1 legs, 0 worlds destroyed, strength 25%" with the abort state (route
  damage 75%) above it.
- **The two-kill bank**: musters now read "for Duzahk (3000 FP, 1 ly, then Qades) - fuel 48,888
  of 158,053". Near pairs sail (the Hegemony to Duzahk with 50,678 razing fuel); far second kills
  put the bank at 150,000-515,000 fuel (League for Hadreel then Damar's Star 242,017; Hegemony
  for Epsilon Shero then Hadreel 514,909) and those factions muster for months. The Hegemony's
  Duzahk leg spent 4,550 of its 50,678 on the one hive left standing there and still could not
  pay the nearest next (Mel Texat, 128,930): the bank is sized to the chain at muster, the hop to
  whatever is nearest when the leg is done. DECIDED (the user, 2026-10-27): the banked chain
  first (`GrandFleet.chainIds`), the nearest affordable after it - built in the 06:55 jar.
- The player faction never sailed (0 lines). No hop ran (every leg ended in the stand-down
  abort first).
- Harness: the Monitor's `tail -F` on `run-gf3.txt` locked the file against `runto.ps1`'s
  `Add-Content` (Windows), so the driver lost its round log; tail the ThreatInc log only.

## 9. Run gf4 (2026-10-27 morning, the 06:55 jar, the day-4036 save cloned, 239 days)

The 06:45 jar's fixes plus the banked-chain-first hop (the user: "Banked chain first"), clone
`...gf4`, war days 4,039-4,278, 0 exceptions; the game crawled from day 4,262 (3-6 days an 8-minute
round against 223 in the first; the harness's screenshots came back white, cause unknown) and the
run was stopped at 07:33. What happened:

- **The first hops ever ran, and kills are credited.** "Grand fleet independent leg 1 done at Duzahk
  Star System: the banked Penelope's Star Star System next" -> "leg 2: 3000 FP sails on from Duzahk
  to Penelope's Star (4 ly ...); 1 worlds destroyed so far"; the Hegemony the same from Samarra to
  Yma. The stand-down no longer takes a leg (no "Purge Stands Down" against a grand fleet; the one
  Ismara stand-down was a council purge with "daily siege off").
- **Both hops sailed with 0 fuel (passage 2,147,483,647).** `legDone` priced the hop's passage with
  `best`, which only the nearest-loop sets; a banked-chain pick left it at Float.MAX. FIXED: the
  passage is `points x ly(here, next) x rate`. Both hopped legs turned home at once from the new
  system ("after 2 legs, 1 worlds destroyed").
- **Two full-strength legs came home credited nothing, no hop tried.** The Path (1,450 FP, 34,039
  fuel aboard) arrived at Penelope's Star to a hive already gone ("market_penelope6: 1 d, 0 fight
  days, gone"); the Hegemony (3,000 FP, 46,279 fuel) to Duzahk a day after the Independents razed its
  one hive ("takes over Duzahk: 0 world(s)"). `notifyActionFinished` ran `legDone` only when
  `!isFailed()`, and vanilla's `isFailed` is "the raid action finished with success 0" - true for a
  leg that razed nothing. FIXED: a grand fleet's leg sails on whenever its action finishes unaborted
  (`grandFleet || !isFailed()`), and the sitrep's outcome is never "destroyed" for one.
- **"Grand Fleet Lost ... 100% of its strength left"** (five notices): `ThreatGrandFleets.ended` read
  `lost` from that same `isFailed`. FIXED: lost is the strength left below vanilla's abort fraction
  (the Path's real loss at 25% still reads lost).
- **Credit went to whoever was last.** `legDone` counted every dead world in the leg's target list,
  so the Hegemony's empty Duzahk leg would have been credited the Independents' kill. FIXED: a leg is
  credited only the worlds it razed (`ThreatPurgeFGI.razed`: the daily siege's "gone" after at
  least a day over it, the live slice that empties a hive).
- **The live stay works.** The League's 2,925 FP over Volturn (the player in Askonia): `hiveRaze
  Volturn fp=281 days=3.00 strata=-843 ... left=615`, landed 2,310, then `fp=204 ... left=0
  DESTROYED`; "leg 1 done at Askonia: turns home - fuel 39378 of 47534 for Yma" (the next was
  unaffordable, the leg came home "1 worlds destroyed, 99%"). The "Siege slice vs" line quoted
  colony figures (0.0 d/day, defences 100%) for a hive - now "razing strata".
- **The two-kill bank in the field**: the Hegemony sailed from Raesvelg for Yma with 180,045 fuel
  (passage 2,418, razing 178,065 for 4 worlds) and was turned home over Huascar by 4,020 FP of
  swarms against its 3,000; it then mustered 237,791 fuel for Qades (9 worlds) and sailed. Musters
  at the end: League 62,777 of 358,752 for Yma then Qades; Path 82,001 of 165,664 for Accreter-Hkere
  then Kepheus.
- 1 "Hive Razed", 4 "Hive Eradicated" in 239 days (gf3: 13 and 10 in 244 - gf4's legs lost their
  fuel to the passage bug and their hops to the failed gate).
- Harness: `runto.ps1` reports "QUICKSAVE NOT WRITTEN" every round on gf3 and gf4 (the run goes
  on); the Monitor's `tail -F | grep | awk` needs `fflush()` or it delivers nothing.

## 10. Run gf5 (2026-10-27 morning, the 07:37 jar, the day-4036 save cloned, 352 days) - the doctrine works end to end

Clone `...gf5`, war days 4,039-4,391, 0 exceptions, 11 sails, 5 hops, 8 cruises ended (7 "Grand
Fleet Returns", 1 "Lost" - the Path's real 25% mauling at its own base), 13 "Hive Razed" + 9 "Hive
Eradicated". Every section-9 fix confirmed:

- **Hops carry their fuel**: "leg 2: 3000 FP sails on from Askonia to Duzahk (2 ly, passage 2423
  fuel); 474 marines, 37035 fuel"; the Independents' second cruise hopped Penelope's Star -> Yma (9 ly,
  passage 11,381) with 124,890 fuel aboard, the Hegemony Yma -> Dantalion (7 ly, 8,998) with 85,966.
- **A leg that finds nothing sails on**: the Independents reached Askonia after the League had razed
  Volturn, were credited 0 ("0 worlds destroyed so far") and hopped to the banked Duzahk at once.
- **Ends read right**: "home after 2 legs, 2 worlds destroyed, strength 74%", "home after 1 legs, 3
  worlds destroyed, strength 100%"; the one "lost" is the Path at 25%.
- **Credit is the razer's**: the League's live leg over Volturn ("Siege slice vs Volturn: 238 FP for 3.0
  d razing strata - destroyed") came home "1 worlds destroyed, 99%"; the Independents arriving after it, 0.
- **The stay, abstract**: 5 "landed (ready), stays" (Walha, Tar, Ning, Huascar, Ismara); the Hegemony's
  Yma leg razed all 7 worlds in the system before hopping.
- **Tally by faction at the stop** (cruises ended): Hegemony 6 worlds in 3 cruises at 100% each, Path
  3, League 1, Independents 0; three more legs were out (Hegemony at Dantalion after 7 at Yma, the
  Independents bound for Yma after 2 at Penelope's Star, the League for Yma).
- **The swarm outgrows it**: hives 47 (size 226) -> 116 (size 503) over the 352 days, strata 832k ->
  1.34M, with ~20 worlds destroyed by grand fleets. Not a fault (the user, 2026-10-02: the Threat ahead
  in a player-less run is fine) - the figure to watch.
- **The bank sailed short when the chain blinked - FIXED (`heldChain`, 08:58 jar, untested).** The
  user: "why would it drop out due to a sighting anyway?" - `chainFrom` applied the targeting rule
  (swarms seen above the fleet's size exclude a system) afresh every day; now a world already in the
  chain stays until it is gone or called off over, and the sighting is weighed at the hop. The
  Hegemony mustered "for Duzahk, then Yma -
  fuel 46,515 of 66,322" and sailed the next day with 35,423 (razing 33,584: Duzahk alone), so at Duzahk
  it was 4,137 fuel short of Yma ("turns home - fuel 15638 of 19775 for Yma") and did the same three
  cruises running (Duzahk re-seeded each time; Yma's cost grew 19,775 -> 67,848) before Yma itself
  became the nearest and fell to a 139,383-fuel muster. `chainFrom` is recomputed daily from the swarms
  seen, so a day's sighting above the fleet's size dropped the second kill from the bank and the fleet
  sailed on one.
- Harness: the run crawled after its first round again (333 days, then 3-6 a round), and so did gf3 and
  gf4. Not the mod: the game's CPU sat at a fifth of a core, the clock moved only in bursts after the
  driver's key presses, `hold.ps1`'s Shift did nothing even after a real click on the title bar, the
  window captures came back white (gf2's were fine at 22:36 the night before), no save was ever written
  by the round-end F5, and the game process has no dialog window. The crawl starts at the first round's
  F5 in all three runs. The user looked: the window was black/white, frozen. Working theory: the F5
  quicksave of the 20 MB StarLord save never completes and leaves the game not rendering. `runto.ps1
  -NoSave` (run.ps1 `-SaveEvery 0`) now skips the round-end quicksave; the next run on this save
  uses it.

## 11. The user's manual test (2026-10-27 09:10): fleets idle over a dead Sindria - FIXED (09:32 jar)

The Independents' grand fleet (1,500 FP, 6 fleets) sailed for Askonia with the player there, landed
1,741 marines on Sindria (a hive in this save), razed it ("Hive Eradicated", "System Cleansed:
Askonia") and then sat over the dead world: "loads of fleets over Sindria none attacking". Live,
vanilla's stage ends when every target's passes are spent or its duration (the whole cruise) runs
out, and `SiegeRaidAction.performRaid` never counts a pass against a world no longer in the economy -
so the dead world held the stage, `legDone` never ran and the fleets neither hopped nor went home
(gf5's League leg at Askonia lingered the same way before its late hop). FIXED:
`SiegeRaidAction.passDeadWorlds` (from `directFleets`) spends the passes of any target that is gone
or no longer a hive, logging "Siege of X: gone, passed over - the stage moves on"; the stage ends,
`notifyActionFinished` runs `legDone`, the leg hops or turns home. Every live expedition gets it,
not only grand fleets; the daily siege already passes dead worlds itself.

## 12. Round 58 hw288 (2026-10-27, the first day-0 games): the fuel bank never fills - DECISION, fuel as found

hw288 (set B from day 0, the 11:34 jar) sailed grand fleets on two seeds (a: the Independents' 3,000 FP
cruise destroyed 8 worlds in 3 legs and came home at 99%; b: the Hegemony 4 worlds, the Church 3) and
none on the third: every mustering base on hw288c read "fuel 0 of 180,000-355,000" for 1,300 days -
the Independents' Nortia, the Hegemony's Citadel Arcadia, Tri-Tachyon's Skathi and the League's Yesod
all with a fuel income of 0 a month ("short 6u DEPOT TOO LOW", the few thousand held inside the
reserve floor), while the nearest hive sat in their own system and its razing bill grew with it. The
user: "i thought the point was every attack goes with whatever it has, they can still land marines no
and defend the orbit? why is fuel stopping them?" - and chose option 1 over shipping fuel to the
bases. BUILT (12:50, untested):

- `ThreatGrandFleets.muster` holds on hulls, the passage out and the cruise's supplies only; the
  target's razing fuel is staged for by the convoys but never gates the sail; no chain is banked.
- `launch` draws every pooled fuel unit above the reserve (the passage first); the log reads "fuel
  (passage P, N to raze with; the targets take R)".
- `hopNeed` wants the passage there only; the razing at the next world is whatever is left.
- `ThreatPurgeFGI` (the stay): dry no longer ends it - with marines aboard it lands them, with a front
  below it holds the orbit; only nothing to land and no front moves the leg on.

What to see in round 59: grand fleets sailing on every seed once the war opens (the hw288c bases had
hulls and marines); cruises ending "home after N legs" with worlds destroyed by fronts rather than
fuel; the swarm's count against hw288's 272 / 280 / 350 at w2,400-2,700.
