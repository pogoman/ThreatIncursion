# War council: invasions without restraints (built 2026-10-04)

The user's rule (2026-10-04): the humans play to win; a rule that stops a faction with the means
from attacking is a fault to remove. `game-runs.md` 2 lists the nine links that held the council's
factions back and what each assumed. The user approved four changes the same day ("Ok proceed with
recommended"). This is what was built. It supersedes `war-council.md` sections 3 (Hold: "no
invasions"), 4.1 (the muster ahead of the strike), 4.3 (the saturation expedition) and 16 ("Major
plays scale with means"). Game runs: `game-runs.md` 3.

## 1. The four changes

| Change | Where | Before |
| --- | --- | --- |
| An invasion starts on any hive the pools pay a landing on, under every strategy, Hold included, easiest first | `ThreatPlays.invade`, `payingBase` | Hold never invaded; Starve invaded only a starved focus; one focus cluster a strategy |
| The sieges are paid first; the escort is sized to the swarm reported in the system | `ThreatPlays.strike`, `besiege`, `escort` | `toMuster` sent 60% of the means as hunts (median 5.3-7.5k FP against 344-444 reported), then the siege was paid from what was left: 28-47 of 48-54 hammers sailed none |
| A bombing campaign's saturation expedition takes the worlds its pools pay the razing fuel of, sails with an escort, and books those worlds alone | `ThreatPlays.saturate` | Set aside for every hive of the system (median 312-416k fuel), postponed 422-888 times a run; sailed alone, 29-37 a run called off on arrival; booked the whole system |
| One siege a world; no cap on plays | `ThreatPlays.besiege`; `majorLimit`, `siegeCapacityFP`, `councilMajorPlayFP` removed | One siege a play carried the strongest world's landing, put every marine down on the first world and booked them all; one major play per 3,000 FP of siege capacity |

Kept: relief of the faction's own worlds comes first (`ThreatFleetOrders.reliefOwed`, the user's
rule 2026-09-27) - `plan` returns before the invasion line while it is owed. `CouncilRules.scores`
and the strategy review are untouched: the strategy still picks where its own plays go, never
whether the faction attacks.

## 2. The invasion line (`ThreatPlays.invade`)

Called from `plan` each day, it runs every `councilInvadeDays` (7) on the faction's own day
(`ThreatReach.today()` + a hash of the faction id).

1. **Strikes already out.** For each of the faction's hammers and feints that has sailed a siege
   and is not withdrawing (`striking`), `besiege` sails sieges for the play's other worlds the
   pools now pay.
2. **Candidates.** Every hive of an in-reach cluster with a report (`ThreatIntel.report`) and no
   strike of the faction's own there (`strikingAt`), not booked by any siege and not under another
   faction's front (`liveTargets`).
3. **Order.** Worlds whose last siege is not recent first (`IncursionManager.onPurgeCooldown`),
   each group easiest first (`IncursionManager.easiestFirst`: the landing, then the swarm reported
   over the world). The cooldown is an order, never a stop, as in the planner's `siegeTargets`.
4. **Pay.** `payingBase`: the cluster's nearest base whose marines cover the landing
   (`IncursionManager.siegeMarinesPooled` against `siegeRaidStrNeeded` x `minMarinesFraction`) and
   whose pools pay the siege (`IncursionManager.siegeCanPay`), skipping a base its own front beats
   to the world (`frontFinishesFirst`).
5. **Start.** One hammer a system a pass, from that base, struck the same day (`startHammer` with
   a base: no staging, no decoy, no prepare). If the launch then refuses, the play ends NEUTRAL
   and the pass stops.

What stops an invasion now: no report of the system, marines, fuel, supplies, armaments, or relief
owed.

## 3. The strike (`ThreatPlays.strike`)

- A strategy's hammer stages for `councilPrepareDays` as before, but the strike is tried every day
  of the prepare (`early`): the day its stock pays one siege, it sails.
- `besiege`: one landing siege per world in `easiestFirst` order, each sized by
  `IncursionManager.siegeSizesFor` on that world alone and launched with the play's id; worlds a
  siege already takes are passed over, and worlds the faction's own front finishes first.
- No siege paid on its day: the play waits in MUSTER, tried daily, up to `councilStrikeWaitDays`
  (30), then ends FAILURE. No hunting force sails without a siege.
- `escort`: `councilEscortMargin` (4) x the fleet points reported in the system, at least the
  sieges' own FP, at most `councilHammerShare` (0.6) x `ThreatSoftening.playPayableFP`. Held at its
  bearing, released `RELEASE_LEAD_DAYS` before the sieges arrive (`siegeNear`). Partners make up a
  shortfall of 25 FP or more (`inviteJoint`).
- A lost base: the play moves to the faction's next base in reach (`baseFor`).
- `exploitCheck` sails more sieges as the pools come to pay them, and the escort stays while a
  siege of the play is still on its way (`siegeInbound`).
- Stance: PRESS whenever a major play runs, Hold included; Hold with none is CONSOLIDATE
  (`ThreatWarCouncil.setStance`).

## 4. Bombing (`ThreatPlays.saturate`)

Targets are the play's bombardable worlds no other siege has booked, operational-Nexus worlds
first, then smallest. It tries the first n for n = all down to 1 and sails the largest set whose
razing fuel, supplies and arms the base's pools pay (`siegeCanPay` with the raze set). Its escort
is `escort`, released as the expedition nears and stood down when it is over (`advanceStarve`).
Still once a campaign. The worlds it does not take stay open to the invasion line.

## 5. Knobs

| Knob | Default | Luna |
| --- | --- | --- |
| `threatinc_councilInvadeDays` | 7 | Invasion Pass (Days) |
| `threatinc_councilEscortMargin` | 4 | Escort Margin |
| `threatinc_councilHammerShare` | 0.6 | Escort Ceiling (was Hammer Share) |
| `threatinc_councilStrikeWaitDays` | 30 | - |

Removed: `threatinc_councilMajorPlayFP`. Legacy only (an older save's muster with forces out):
`councilMusterDays`, `councilMusterFloor`.

## 6. Judgement calls for the user to check

- The escort margin of 4 x the reported swarm. FP-sized blows were ruled out on 2026-10-01; sieges
  have been report-sized since 2026-10-02, and this recommendation was approved 2026-10-04.
- "Bombing pays as it goes" is built as "takes the worlds it can pay", not resupply in flight.
- A strategy hammer that finds no siege to pay in `councilStrikeWaitDays` past its day is a FAILURE
  for learning.
- The play cap went outright, with its knob.
- One play staging a base (`ThreatConvoys.stageForPlay`): a later play's staging replaces an
  earlier one's.

## 7. Log lines (for the digests)

- `Play X HAMMER: siege of F FP sails from BASE for WORLD, M marines aboard`
- `Play X TYPE: escort of B FP built of W wanted (S FP reported in SYS, its sieges F FP; M FP its share pays)`
- `... PREPARE -> STRIKE (...; N siege(s), F FP, sail from BASE against R FP reported over the strongest world, arrive in D d; escort E FP)`
- `... -> MUSTER (...; no siege the pools pay yet (R FP reported over the strongest world), waits up to W d for stock)`
- `Play X TYPE: N more siege(s) sail` (the weekly pass), `stays, N more siege(s) sail (...)` (the exploit check)
- `Play X STARVE: saturation expedition of F FP sails from BASE to raze N of M worlds`
- `Play X STARVE: no saturation expedition the pools pay, even of one world (F FP needed)`
- `invades WORLD, the easiest hive its pools pay a landing on; from BASE` (a line hammer's start)
