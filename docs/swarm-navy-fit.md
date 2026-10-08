# The swarm's navy fits the spare

Split from `hive-garrison-and-upkeep.md` (2026-10-09). The swarm keeps no navy it cannot supply and use (the user,
2026-10-08): the sections below are the fit (`ThreatColonyManager.fitNavyToSpare`), the builder and the pressure pass
under it, and what the overnight batches hw77-hw86 read. The posture, stance, fuel, parity, structures and size
upkeep stay in `hive-garrison-and-upkeep.md`; the rally's enough rule, which the pass is under since hw87, in
`swarm-defence.md` "Only when it is enough".

## The navy fits the spare (2026-10-08, the user's rule; `ThreatColonyManager.fitNavyToSpare`)

The user, after hw73a (fleets 109k FP on 78k supplies a month, the stock at 0 for a year, 29-31
sends held): "if it cant strike cause it cant afford to cause its maintaining too many fleets then
yeah it shouldnt maintain more than it can supply and use". The census's breakdown (hw75a, the
same build with the purposes tallied) read the 69k a month as feed 24k, campaigns in the field
8-27k, reinforcements in transit 16k (179 fleets, 25k FP, forever shuffling between hives under
posture), the home navy above the patrols 13k, spare 1.5k, 145 seedings held on an empty stock.

- **The rule.** Each poll, after the feed, the spare a month (`ThreatReach.spare`: production less
  the colonies' upkeep, the fleets away and the navy at home) must cover what a send held this
  month needs: one founding's supplies (`ThreatFuel.foundingCost`) while any send was held
  (`ThreatFuel.heldThisMonth`), else 0. Short of it, standing swarms are recycled into the bank at
  `ThreatReturns.hullShare`, the smallest on station first, from the colony with the most garrison
  above its floor - the patrols (`ThreatPosture.minimumFP`) or the pressure's need (`needFP`),
  whichever is more - never a colony under attack nor one the offensive's held prongs will muster on
  (`ThreatOffensive.earmarked`), until the saved charge fills the gap. Banked FP pays no upkeep and
  the nexus rebuilds from it when the flow grows, so the hulls are kept as FP, not fed as fleets.
- **Transit.** A transfer in transit pays the away rate (`awayFleetSupplies`): `redistributeByPressure`
  sends none the flow cannot carry (spare below 0) to a receiver nobody attacks. A receiver under
  attack is fed whatever the flow.
- Not a cap: the navy grows again as the flow does (the hw36 growth gate was already the flow's).
  Knob `navyFitsSpare`. First run hw77 (ck2) + hw78 (ck5). Log lines `Navy: <hive> recycled ...`
  and `Posture: no transfer to ...`.
- **The builder respects the fit** (2026-10-08, after hw77a; the user: "the two wrinkles need to be
  addressed, not another strategy layer"). While the fit binds (`fitBinding`: the spare short of
  `fitWant`) the nexus builds a colony only up to `fitFloor` (the same floor the fit recycles to),
  never to the posture's want. hw77a's nexus had refabricated what the fit recycled, month for
  month (149 recycled / 156 built, 158 / 136; 4,602 recycled and 7,888 built a game, hw75a built
  3,328), so the navy was churned through the bank rather than kept at the floor. Gated on the fit
  binding now it still churned (hw79a, stopped at 20 minutes: a build and a recycle a poll at the
  same hive, 255 / 260 in a month) because the feed records the spare before the fit recycles and
  the next poll read it above the want; so the builder's gate is `fitActive`: a send held this
  month, or the fit recycled within `FIT_HOLD_DAYS` (30; the timestamp `KEY_FIT_TIMESTAMP`). First
  full run hw79 (ck2) + hw80 (ck5): the nexus churn gone (same hive-month built-then-recycled 66,
  hw77a 4,019), the old sector 92 hives against 23 human worlds.
- **The fit's floor is the colony's want** (2026-10-08, after hw79a). With every colony held at the
  floor, every colony was short of the posture's want, so the pressure pass found no donor and had
  a fabricator build the receiver a swarm from its bank, which the fit recycled (4,330 fabricated,
  hw77a 2,161; 1,060 recycled at the same hive). So while the fit is active `ThreatPosture.wantFP`
  returns `fitFloor` for a colony nobody attacks - the nexus, the pressure pass (receivers, donors,
  `releasableFP`, `fabricatorFor`) and the launch gates all read one want - and `fitFloor` keeps
  the strike a pressing stance stages there (`extraWantFP`: a strike staged is the navy used). A
  colony under attack keeps the posture's want. First run hw81 (ck2) + hw82 (ck5): recycling a
  third of hw79a's, the nexus at the ordinary rate, but the pressure pass still fabricated for a
  receiver nobody attacks whose want moved with the pressure (Gream: nine swarms fabricated and
  sent a month, recycled the poll they arrived, sent again).
- **Nothing fabricated for a quiet hive while the fit is active; the fit never cuts a hive just
  reinforced** (2026-10-08, after hw81a). `redistributeByPressure` fabricates for a receiver only
  when its system is attacked (donations of existing surplus still flow), log `Posture: nothing
  fabricated for ...`; `fitNavyToSpare` skips a colony `ThreatPosture.recentlyReceived` (the
  donors' own DECAY_DAYS rule). First run hw83 (ck2) + hw84 (ck5).
- **What the sends held on ck5 were** (hw78, read 2026-10-08): not the fit's. The new sector's
  swarm spends its whole income each month - at the end 130k a month: feed 50k, away 35k, navy
  13k, structures 3-20k, foundings 10-30k (5-6 hives a month) - and the stock sits at 5-12k; the
  50 sends held a month are the queue beyond that income, and the fit idled from m36 (0 recycles)
  because the spare (production less feed, away and navy, which the census's `spare` is - it
  excludes structures and foundings) stayed above one founding. One order question is the user's:
  structures are paid from the raw stock (`affordStructure`) before a founding, which needs the
  stock above the planner's reserve (`ThreatFuel.free`).
