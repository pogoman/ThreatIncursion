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
   a task force, hunt or raid; `siegeStayDays` for an NPC siege (what the gate already priced,
   `siegeSuppliesPerPoint`), and for the player's siege at least `siegeHoldDays` (120) at the
   target on top of the passage there and back (`IncursionManager.expeditionHold`; the user,
   2026-10-12: "a siege is a long affair, the ships that come with should be prepared for months
   of siege" - the bombardment stay of 10-120 days ran out under the ground campaign and the
   siege turned home; player only, NPC sieges restock at their forward bases, rule 3). The Siege
   button's quote and the launch draw are the same figure (`expeditionWants`), so the quote
   rises with the knob. The gate prices the hold per point, so a depot provisions what it can
   pay to keep out there. A player order sails fuelled and supplied or not at all since 2026-10-11
   evening (`ThreatFleetOrders.provisionsShort` gates the board's buttons, the quotes and
   `buildPlayerForce`; sieges and bombings through `siegeBlockFacts` / `bombardBlockFacts`), and its
   fleets take the NPC trim and hold below (rule 7) - until then it always sailed with what the
   stockpile had. The stay is `sortieHomeStayDays` (30) for a world in the base's own system, where
   the fleet restocks from the base when low, `sortieStayDays` (90) beyond (2026-10-11 evening: a
   Max guard of a neighbouring planet quoted 12,195 supplies, 90 days of upkeep). A task force on
   station sent on a sortie sails itself and brings its fuel, deposit and hold with it: it settles
   at the host as it is re-tasked, ahead of its own draw (`ThreatFleetOrders.retask`,
   `ThreatAidCapacity.foldedProvisions`, `ThreatReturns.carried`), so the sortie's draw - and the board's quote and gate - is the
   difference between what the order needs and what the folded forces carry (the user: "if i move
   it to another planet in the system i dont pay another 30 days ... if i send it out of system, i
   pay the difference of required vs what is currently carried"). A fleet never loaded fills to the stay its distance from home says (`ThreatReturns.holdFull`). (A returning fleet was put back on station as a guard and restocked there from 2026-09-06 to 2026-10-12, `restation`; a fleet home now dissolves into its colony's hulls, `hull-pool.md` 3.)
2. **Carry and burn.** `ThreatUpkeep` burns each fleet's own hold per pass (`$threatinc_hold`);
   nothing is drawn from home while it is away. A siege's hold stays on the expedition and burns at
   its fighting strength (`fightingFP`), spawned or not; its fleets are not billed twice. The hold
   is priced on the sizes (25 FP a point) and burned at the spawned fleets' real points, which
   vanilla builds from the faction's doctrine and hull list (NPC ledgers: 1.13-1.34x; the player's
   doctrine can pass 2x, at which the hold is gone on arrival - the user's siege of 2026-10-12
   "turned back due to 0 supplies"). An NPC siege settles the excess hulls against its reserve
   (`settleLedger`); the player's built fleets are pruned at spawn to the points paid for
   (`ThreatPurgeFGI.pruneBuiltToPaid`, staged task forces never cut), so hold, hull ledger and
   fleets agree. A fleet that leaves the expedition alive - to defend a landing
   (`stayOnDefend` -> `ThreatFleetOrders.adoptLandingDefend`), recalled or sent to intercept -
   takes its share of the hold with it, by fleet points (`ThreatPurgeFGI.detach`): out of the
   group it is billed on its own hold (`charge`), and a hive system has no base to restock from.
   Until 2026-10-12 the hold stayed on the group and the staged task forces that stayed over Delta
   Mengryla I - folded in with their holds emptied into the group and a "full" mark left on the
   fleet - stood down "0 supplies aboard, 0 days" (the user's "siege turned back due to 0
   supplies"). **Player only until after 0.8** (the user, 2026-10-12): an NPC fleet left over a
   landing has no hold mark, so `charge` bills it from its home depot every pass (the pre-hold
   fallback - a remote flow, the one kept) and it stays until the front resolves; hw159a-c had no
   defend-fleet stand-downs. With a hold share it would restock at a forward base in the system or
   sail home while the campaign runs, so NPC sieges keep the fallback until the user revisits it.
   Two more faults from the first logged player siege (2026-10-12, Delta Mengryla, 12:5x jar):
   the "send what you can" marines trim (`launchSiegeExpedition`) cut the sizes 11 -> 2 fleets
   for 660 marines while all nine staged task forces still sailed (`takeStaged` reads the staged
   list, not the sizes), so the hold was priced on 625 FP and burned at 2,863 - the trim is NPC
   only now, the player's siege sails as the Siege button quoted it; and a staged force taken into
   a siege kept the upkeep clock of its last pass on station, so its first pass after detaching
   billed the whole siege again (972 aboard -> 311, "supplies low") - `ThreatUpkeep.restartClock`
   on `detach`, every faction (a double charge, not a rule).
   Convoys
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
   (105 of 207 "supplies low" stand-downs had 0 aboard). A player task force is gated at the board
   instead (rule 1).
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

Knobs: `threatinc_sortieStayDays` 90, `threatinc_siegeHoldDays` 120 (player sieges), `threatinc_fleetResupply` true, `threatinc_stagedLaunch` true; `fleetUpkeep` off restores
the flat deposit with no hold. Sections to keep in step: `strategy-hunting-reach.md` "Not reserved"
(now carried), `hull-pool.md` standing upkeep, `strategy-board-returns.md` refunds.
