# Fleet provisions - carried, burned, restocked (2026-10-11)

The user, after the Waystation question: "a navy needs to take lump sum supplies before heading
out. or are you saying they teleport supplies from base to the navy while its 10LY away" - the
remote billing (`ThreatUpkeep` drawing the fleet's home depot every pass, then the markets reaching
it) was exactly that. Decision: **both sides carry their provisions, with resupply from forward
bases when running low**, and the player's built navy pays standing upkeep like every NPC navy.

## What was measured first (hw10a-c logs, 2026-10-11)

Days away, by the 5-day upkeep passes between a fleet's launch line and its end line:

| Kind | Median | p25 | p75 | p90 |
|---|---|---|---|---|
| Hunts (n=2,096 paired, 3 runs) | 65-90 | 30-70 | 85-165 | 96-435 |
| Task forces (guard / relief, n=97) | 85-115 | 50-115 | 120-235 | 175-235 |
| Sieges, draw to return (n=685) | 45-80 | 35-65 | 65-94 | 85-118 |

hw10a: 38% of ended hunts ended "out of supplies", 43% "complete", 11% "badly hurt". Pairing is
lossy (ends under-logged; FIFO and LIFO bracket the truth), so the median is the figure to trust.
Hence `threatinc_sortieStayDays` 90: a task force or hunt sails with 90 days of its ships'
maintenance, which covers the median trip with the voyage on top.

## The rule

1. **Provision at launch.** Beside the hull deposit (`expeditionSuppliesPerPoint`, back by hull
   share) a fleet draws a HOLD: its ships' vanilla maintenance (`ThreatFrontlines.maintenancePerMonth`)
   for the voyage there and back (`ThreatReach.strikeDays(ly)`) plus the stay - `sortieStayDays` for
   a task force, hunt or raid; `siegeStayDays` for a siege (what the gate already priced,
   `siegeSuppliesPerPoint`). The gate prices the hold per point, so a depot provisions what it can
   pay to keep out there. A player task force always sails, with what the stockpile had.
2. **Carry and burn.** `ThreatUpkeep` burns each fleet's own hold per pass (`$threatinc_hold`);
   nothing is drawn from home while it is away. A siege's hold stays on the expedition and burns at
   its fighting strength (`fightingFP`), spawned or not; its fleets are not billed twice. Convoys
   paid their trip at launch (`escortRate`) and burn nothing more; a forward base's garrison pays
   through its link as before.
3. **Resupply from forward bases.** A fleet whose hold is below its reserve for the leg home tops
   up to its launch hold from a base of its faction IN ITS SYSTEM: a colony that is a base
   (`IncursionManager.isBase`: military or link, with a depot), or an outpost. Colonies give above
   their floor, outposts their whole stockpile. A siege restocks at its target system. Knob
   `threatinc_fleetResupply`. Allied bases do not restock (not yet).
4. **Home when low.** Below the leg-home reserve with nothing to restock from, a task force stands
   down ("supplies low") and a siege turns home ("Siege Short of Supplies"); it sails on what is
   left. A fleet that still runs dry owes its upkeep and a month owed starves it as before.
5. **Back with the hulls.** The hold is spread over the ships: what is left comes home times
   surviving strength (`ThreatReturns.settle`, `refundOnReturn`). The hull deposit comes back by the
   NPC rule for the player too (hull share in full) - the player's fleets no longer "keep the old
   rule", they pay the voyage like everyone.
6. **The player's navy pays standing upkeep.** `ThreatHulls.maintain` no longer skips the player:
   built hulls at home cost their maintenance a month from the colony stockpiles; a hull-month unpaid
   is a built hull lost, with a notice ("Navy Unpaid").

7. **Staged launches (the user, 2026-10-11, after hw156 / gs1: "they should never run out unless
   unforeseen circumstances ... raids should never launch with zero supplies for FB, FB needs to be
   supplied/staged with supplies and fuel first").** An NPC task force, hunt or raid sails only with
   its FULL provisions - the voyage's fuel and deposit and the whole hold - drawn from the base it
   launches at; short of any, `ThreatFleetOrders.buildTaskForce` puts the draw back, expires the
   hulls and logs `Order held at <base>: f of F fuel, s of S supplies, hold h of H - stays home`
   (once per base). No other market pays toward it remotely: `ThreatSoftening.huntDonors` is empty,
   so `payableFP` sizes the force on the base's own stock and `payFromDonors` has nothing to top.
   Convoys stage the base first (`ThreatConvoys.planLogistics` -> `stagingTargets`: siege stock, or a
   hunting force's when `stagesForHunt`). The failure this answers: gs1a drew 105 of 838 orders with
   `hold 0 of N` from forward bases whose stock was empty - the force was sized on the faction's
   pooled reach (`payableFP` + `huntDonors`), drawn from the base alone, and stood down at once
   (105 of 207 "supplies low" stand-downs had 0 aboard). A player task force still always sails.
   Sieges keep their pooled remote payment (`IncursionManager.siegeDonors` / `siegePooled`) - open
   question for the user. Knob `threatinc_stagedLaunch` (true).
   - **Trim to the paid hold (hw157 first read, 2026-10-11 14:40).** The gate and the planner price the
     hold per point at the faction's measured supplies per fleet point (`ThreatReach.sortieHold`); the
     launch wants the built hulls' own maintenance. hw158's draw log showed why they differ: vanilla's
     fleet factory builds 109-114% of the fleet points asked (the rate itself matched, 1.12 built vs 1.10
     measured), so the hold is priced for the points asked and wanted for the points built. With fuel
     and deposit covered,
     `buildTaskForce` leaves the smallest warships home until the hold the base paid feeds the rest, down
     to `TRIM_FLOOR` (75% of the built points; below it the order is held), refunds their deposit share
     and the hold the rest do not need, shrinks the fuel and deposit want with the hulls (hw158: without
     that every trimmed fleet was held against the untrimmed want), and logs `Order trimmed at <base>:
     B -> A FP for a hold of H`.
     The measured rate is now the faction's task forces' (fleets carrying a hold) when any are out, not
     every homed fleet's (convoys, garrisons). The draw log carries both figures (`N FP of M asked,
     x supplies/FP/mo vs y measured`) to tell overshoot from rate error.
   - **A merged fleet keeps every hold.** `ThreatFleetOrders.absorb` merged fuel, supplies and the FP
     marks but not `MEM_HOLD` / `MEM_HOLD_FULL`: a relief force of several fleets lost the absorbed
     fleets' holds with them and stood down "supplies low, 0 aboard" over the world it relieved (hw157c,
     ten times). Fixed the same hour.

Knobs: `threatinc_sortieStayDays` 90, `threatinc_fleetResupply` true, `threatinc_stagedLaunch` true; `fleetUpkeep` off restores
the flat deposit with no hold. Sections to keep in step: `strategy-hunting-reach.md` "Not reserved"
(now carried), `hull-pool.md` standing upkeep, `strategy-board-returns.md` refunds.
