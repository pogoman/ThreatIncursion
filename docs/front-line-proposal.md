# The line - a continuous ground front (proposal, 2026-10-23)

Design only; nothing built. The user, from the Sindria save (both sides pushing, Sindria
still falls): "i would potentially do away with counter attack which is a bit random and not
reflecting real time status push and pull. ideally state column with values gaining losing and
grinding. grinding being both sides pushing but forces relatively equal, and losing and gaining
fairly evident. could have further substages like overrun or blitzing etc." and "no idea why
sindria asked for so few marines. you would think they would ask for enough to push back the
threat troops."

## 1. What the engine does today, and why Sindria falls with more marines than the swarm

Two different arithmetics decide a front (`ThreatGroundFronts.tickFront`):

- **The swarm's push is continuous and nearly unconditional.** A Threat front is *holding* at
  `frontHoldFraction` 0.17 of the colony's `defenderStrength` and pushes while holding. Its
  progress on the next district runs at `frontPushBaseDays` 20 x `paceRatio` (defence over
  strength, clamped 0.5-3). So a front at a fifth of the defence still takes a district every
  60 days at most; at Sindria, 212 against 253, one every ~24 days - seven in 283. The marines'
  numbers slow it a little and never stop it.
- **The defence answers only on the cadence.** `hiveCounterAttack` fires every
  `frontCounterAttackDays` 40 / tempo / 1.5 with a command (~20-23 days at Sindria), compares
  `counterAttackStrength` with `defenseStrength(front)` (cover-adjusted) once, and retakes one
  district if it wins, overruns at 2:1 odds. Between blows the marines only bleed
  (`bleedDefenders`). The tooltip's last line is the whole problem in one sentence: "Pressing
  on: the district falls before the counter-attack."

The marines ask (`reliefNeed`, which `battleItems` and the Reinforce button quote) is sized to
the **blow**, not the **line**: marines until `counterAttackStrength >= defenseStrength(front) x
siegeBeachheadMargin 1.25`. Sindria: 212 x 1.25 - 255 = 10, less the 56 still arming = 0. By the
engine's own rule Sindria had enough marines to win its next counter-attack; it just never got to
mount it. That is why the ask is small, and why the user is right that it should be "enough to push
back the threat troops" - a figure the cadence model cannot express.

## 2. The proposal: one line, two pressures

Replace the state machine (holding / grinding / foothold + the counter-attack cadence + brace)
with a single quantity per front, **the line**: progress on the current district, 0..1, moved
every tick by the net of the two sides' pressure.

```
pressureA = effectiveStrength(front)      x stanceA   (push 1.0, dig in 0: defends only)
pressureD = defenderStrength(market)      x stanceD   (push 1.0, dig in 0)
holdA     = effectiveStrength(front)      x coverMult(front)    (what A defends with)
holdD     = defenderStrength(market)      x defenderCover       (what D defends with)

netA = pressureA - holdD      (the swarm's gain, > 0 when it outweighs the marines' cover)
netD = pressureD - holdA      (the marines' gain)
rate = (netA - netD) / (strengthA + strengthD)      in -1..1
line += rate x elapsedDays / frontPushBaseDays
```

- `line` reaching 1 takes the district (`takeStratum`, unchanged); reaching 0 with a district
  held gives it back (`strataHeld--`, as the counter-attack's retake does today). Both sides'
  cover is spoilt on a district change, as now (`frontEntrenchKeptFraction`).
- Both stances survive with their current meaning: **push** is pressure and exposure (the
  pushing side's attrition rate, `frontPushLossPer30Days` 0.30), **dig in** is cover and no
  forward pressure (`frontMarineLossPer30Days` 0.08, entrenchment accrues). A side that digs in
  can only slow the line, never move it back. Two sides pushing at equal strength: rate ~0,
  *grinding*, both bleeding at the push rate - exactly the user's definition.
- Casualties per day stay the existing rates times the existing pressure factors
  (`pushLossFactor`, `pushLossMult`, veterancy); the one-off counter-attack casualty
  (`counterAttackDefenderLoss`, `counterAttackLoss`) goes, since the blow goes.
- Suppression (`frontWearRate`, key structures while holding) keys off the same rate: the swarm
  wears structures while `rate > 0`, defence structures only while `rate` is within the grind
  band, nothing while losing ground. The hysteresis band is no longer needed: the rate is
  continuous.
- Brace, `bracedFromPush`, `shouldBrace`, `daysToCounterAttack`, `counterAttackTempo`,
  `counterAttackInterval`, `hiveCounterAttackPace` all go. The hive theatre's pacing on supplies
  becomes a pressure multiplier instead: a hive's landed troops push at `fedShare / breakEven`
  (0..1) of their strength, so a starved hive still cannot counter-push, as today.
- The player's Push / Dig in orders and the NPC stance AI (`tickDefenderStance`) keep their
  shape: NPC defenders push while `netD > 0` and dig in otherwise; the swarm's front pushes while
  `netA > 0` (which replaces `holdRequirement`), consolidates and final-pushes as now.

## 3. The State column - from the side reading it

The fronts table is read from a tab's side (2026-10-17), so the state is the reader's:

| State | Rule (rate from the reader's side, r = -rate for the defenders' tab) | Colour |
| --- | --- | --- |
| overrunning | r > +0.5 (3:1 and better) | good blue |
| gaining | +0.1 < r <= +0.5 | good blue |
| grinding | -0.1 <= r <= +0.1 and both sides pushing | white |
| stalled | -0.1 <= r <= +0.1, one or both dug in (nothing moves) | grey |
| losing | -0.5 <= r < -0.1 | yellow |
| overrun | r < -0.5 | red |

Substages the user floated: *blitzing* = gaining with the district falling inside 10 days
(rate x 20 > 2); *overrun* above doubles as the destroyed-front case when the losing side's
strength falls under `frontMinMarines`. "Falls" (days to the next district at the current rate)
and "Held" stay as they are; the "Counter" column (days to the next blow) goes.

## 4. The marines ask - enough to turn the line

`reliefNeed` becomes the marines that make `rate` at least `-margin` from the swarm's side, i.e.
the defenders gain ground at a chosen pace with their current stance:

```
want = armed marines such that pressureD - holdA >= margin x (strengthA + strengthD)
```

with `siegeBeachheadMargin` reused as the margin (1.25 today reads as "hold with a quarter to
spare"; a plannable number like 0.25 of the combined strength is the honest knob). Sindria would
ask for the marines that out-push 212 troops at cover 0.85, not the 10 that win one blow. The
same figure feeds `battleItems`, `reinforceWants` and the Reinforce prompt, so the board, the
help request and the convoy all quote one number.

## 5. What it costs

- Saves: `GroundFront.pushProgress` already holds the line; `lastCounterAttack`,
  `bracedPushProgress`, `bracedFromPush` become dead fields (harmless under XStream).
- Tooltips: the front tooltip loses its five counter-attack lines ("Next marine counter-attack
  in 20 days ...", "Musters every 23 days", "Cadence x1.20", "Each costs the marines 32",
  "Pressing on") for two: the rate as a sentence ("Marines gaining 0.03 districts a day; next
  district back in 12 d") and each side's pressure and cover.
- Knobs retired: `frontCounterAttackDays`, `colonyCounterAttackMilitaryMult`,
  `counterAttackRatioClamp`, `frontStateHysteresis`, `frontHoldFraction`,
  `frontGrindFraction` (the last two become the grind band, one knob). New: `frontGrindBand`
  (0.1), `frontOverrunRate` (0.5).
- Military command (`hasMilitaryCommand`) stops pacing the cadence; it should keep a role, the
  obvious one a pressure bonus (x1.5 on `pressureD`), else a Patrol HQ means nothing to the ground
  war.
- The hive theatre and the player's own fronts (landing on hives) run on the same line, so the
  swarm's and the hive's counter-attacks ("Beachhead Overrun" notices) are replaced by the same
  rate - a hive's troops out-pushing a player landing overrun it the same way.
- Estimate: one evening for the engine and readouts, a second for the hive theatre and the
  docs; test on the Sindria save first (the user's: both worlds should now ask for marines and
  the state should read losing at Sindria, grinding at Volturn).

## 6. Open decisions for the user

1. Adopt the line (section 2) in place of the counter-attack cadence, both theatres?
2. State words from the reader's side as in section 3 (overrunning / gaining / grinding /
   stalled / losing / overrun), with "blitzing" as a substage or dropped?
3. The marines ask as "enough to gain ground at the margin" (section 4), the margin as a share of
   the combined strength (default 0.25)?
4. Military command as a pressure bonus (x1.5) or no role?
