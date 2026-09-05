# Economy coherence - how 4X games run economies, and how the war reserves must fit vanilla

Written 2026-09-05 at the user's request; all seven rules approved by the user the same day
and BUILT the same day - section 5 records what each rule became and what the clone-save
test showed. The problem statement: a colony can show a fuel
shortage on vanilla's colony screen while the war board shows it holding thousands of
fuel in reserve. Every system must interact; the player must be able to think "I can
help Kazeron by selling it fuel" and be right.

## 1. How the successful 4X economies work

| Game | Model | Stockpile? | Logistics? | What we can borrow |
| --- | --- | --- | --- | --- |
| Civilization (V/VI) | Yields per turn (flow); gold, faith, and in VI strategic resources accumulate to a cap; units cost upkeep, shortage cuts combat strength [CIV] | Yes, capped | No | A stock fed by a visible per-turn flow, spent by upkeep, with a soft penalty rather than a hard stop |
| Stellaris | One empire stockpile per resource, monthly net income; deficits give empire-wide penalties [4X-ECON] | Yes, empire-wide | None (the "spreadsheet" criticism) | What NOT to do: a pooled number with no place, so nothing can be intercepted or helped locally |
| Endless Space 2 | FIDSI per system (flow); dust and strategics stockpiled | Mixed | No | Per-system production, empire-wide spend |
| Victoria 3 | No stockpiles at all: goods produced are sold instantly into a market whose price is supply vs demand; shortages cut building throughput; trade routes move goods between markets [VIC3-DD][VIC3-DEEP] | No | Abstract (market access) | Shortage as a throughput malus, not a wall; the market as the single truth both civilians and the state read |
| Hearts of Iron IV | National stockpiles of equipment and resources; supply flows physically through hubs, rail and trucks; trade by convoy [HOI4] | Yes, national | Yes, physical | Stock that must be moved, and can be sunk on the way |
| Aurora 4X | Physical minerals and fuel on each body, refined and shipped by real freighters; logistics chains are the whole game [AURORA] | Yes, per body | Yes, physical | Per-location stock and physical transfer |
| Distant Worlds 2 | Private freighters move resources on their own; shortages bottleneck shipbuilding regardless of money; the freighter AI is the weak point [DW2] | Per location | Yes, automated | Automated movement with a veto - and the warning that dumb freighter AI ruins it |
| Starsector vanilla | Availability per market in log-scale "units", supply vs demand broadcast through accessibility; no stockpiles; shortages are deficits that raise prices and cut industry output; the player's sales become temporary trade modifiers on availability | No (submarket cargo only) | Abstract (accessibility) | The truth we must not contradict |

The pattern across the good ones: **one source of truth for a commodity per place**, a
visible flow into it, a visible drain out of it, and shortages that degrade rather than
forbid. Our reserves broke the first rule - a second fuel number per colony that vanilla
cannot see.

## 2. What vanilla already gives us (verified in the API source)

- `CommodityOnMarketAPI.getAvailable()` / `getMaxDemand()` / `getMaxSupply()` are econ
  units (a log-like tier: a size-6 world "makes 6 units"). `getDeficitQuantity()` and
  `getExcessQuantity()` are physical item counts of the shortfall / surplus.
- `getModValueForQuantity(items)` and `getQuantityForModValue(units)` convert between
  physical quantities and econ units; `CommoditySpecAPI.getEconUnit()` is the item count
  behind one unit; `BaseIndustry.getSizeMult(units)` is vanilla's own tier-to-multiplier
  curve (the local-resources submarket stockpiles `getSizeMult(extra) x econUnit` per
  month of excess).
- **The player's sales are already an economic act**: `BaseSubmarketPlugin
  .reportPlayerMarketTransaction` turns every stack sold into `addTradeMod(qty, days)`,
  which raises that market's availability for `TRADE_IMPACT_DAYS`. Selling fuel at a
  fuel-short Kazeron genuinely ends its shortage for a while. Buying does the reverse.
- `addTradeModPlus/Minus` let a script do the same thing a sale does - raise or lower a
  market's availability of a commodity by a physical quantity for a duration.
- Market conditions can add demand and carry a tooltip on the colony screen.

## 3. The coherence rules

Keep the physical reserve (convoys, fronts and raiders need a thing to carry), but make
it the colony's OWN surplus in vanilla's terms, visible on vanilla's screen, and spent
on vanilla's shortages before anything else.

1. **Accrue only from surplus.** Per 30 days a mobilised colony banks
   `getSizeMult(max(0, available - maxDemand)) x econUnit x reserveSurplusMult` of each
   reserve commodity - exactly the stock vanilla would let a local-resources submarket
   pile up from the same excess - plus the militia trickle for marines. A colony in
   deficit banks nothing. Replaces the "50 per unit of max supply" knobs.
2. **Mobilisation is vanilla demand.** A `War footing` market condition on every colony
   of a mobilised faction adds demand for marines, hand weapons, fuel and supplies
   (`warFootingDemandUnits`, 1-2 units, scaled by colony size). A colony that was only
   just balanced now shows a shortage on the colony screen - the war is eating its
   margin - and the board's staging column says the same thing.
3. **The reserve counters the colony's shortage first.** While a mobilised colony has a
   vanilla deficit in a reserve commodity, the reserve is spent on it: each poll,
   `addTradeModPlus(quantity covering the deficit, 30 days)` and the same quantity leaves
   the reserve. A colony cannot be short on the colony screen while the war board shows
   it sitting on that commodity - the governor issues the depot. If the reserve runs out,
   the shortage stands, and the board shows "reserve exhausted covering local shortage".
4. **Selling to a colony helps twice, honestly.** Vanilla's trade modifier ends the
   shortage (rule 3 stops draining the reserve) and lifts availability above demand, so
   rule 1 starts banking again for the modifier's duration. No separate "donate to the
   war effort" path is needed - the vanilla act IS the help. The `War footing` tooltip
   states the reserve, the monthly bank rate, and "selling fuel here raises it".
5. **Draws and deliveries stay physical.** Expeditions, sorties, outposts and convoys
   draw from and deposit into the reserve as now; a convoy delivery to a base also
   applies `addTradeModPlus` for what it landed, so the colony screen shows the
   shipment arriving the way a player sale would. Expedition draws apply nothing extra:
   the stock was surplus when banked.
6. **Reach stays vanilla fuel.** Expedition and convoy range keep reading vanilla fuel
   availability. "Short of fuel on the colony screen" therefore also means "cannot
   project", which is the same story told twice, not two stories.
7. **Units on the board are vanilla's.** The faction view's reserve columns keep item
   counts, but the colony tooltip and the War footing condition state each commodity in
   vanilla's units, one line each ("Fuel: 4,800 banked. Surplus 3 units; +1,200 a month,
   cap 7,200." / "Fuel: 0 banked. Short 2 units; depot too low to issue."). The line says
   what is true now; how the depot works is in the docs, not the tooltip.

## 4. Consequences worth knowing before building

- Reserves will be SMALLER and more uneven than today: only surplus banks. A faction
  with no fuel surplus anywhere has no war fuel, which is correct and which convoys and
  the player's trading now fix. `reserveSurplusMult` and `reserveInitialMonths` stay as
  the tuning levers.
- Rule 2 will create shortages in the vanilla economy on mobilisation, with vanilla's
  price and trade-fleet responses. That is the point, and it also makes the hive's
  raiders bite the vanilla economy, not just the board.
- Rule 3 means a besieged faction's depots drain into civilian shortages before they
  fund expeditions. Legible ("the fleet went without because the cities were starving")
  and a real strategic choice: a `reserveShortageCoverFraction` knob caps how much of the
  reserve the governor will spend that way (default 0.5).
- Hive colonies are untouched: the swarm keeps its own broadcast economy
  ([[vanilla-economy-alignment]]).

## 5. Built 2026-09-05 - what each rule became, and what the test showed

Built in one session and verified on the clone save (`...182493833221313174zz`, harness at
1920x1080 on the external monitor): load with the war-mode backfill, a month of Shift
fast-forward, a quicksave, and a reload on the rebuilt jar. No errors in the log.

1. **Accrual** - `ThreatReserves.accrualPer30`: `getSizeMult(max(0, available -
   maxDemand)) x econUnit x reserveSurplusMult` (1.0), militia trickle kept, the four
   per-unit knobs retired. `available` excludes the mod's own trade modifiers (covers and
   convoy landings, all sources prefixed `threatinc_`) - without that a convoy landing
   would bank itself for 120 days and shipments would breed stock. Player sales still
   count, as rule 4 wants.
2. **War footing** - market condition `threatinc_war_footing` (`WarFootingCondition`,
   `data/campaign/market_conditions.csv`) plus a hidden structure
   `threatinc_war_footing_demand` (`WarFootingDemand`, `data/campaign/industries.csv`).
   The structure exists because vanilla's market demand is the MAX over industries
   (`CommodityOnMarket.updateMaxSupplyAndDemand`), not a sum, and a condition has no
   demand of its own: the structure declares "the colony's highest existing demand + N",
   N = `warFootingDemandUnits` (1.0) x size / 5 rounded up. `ThreatReserves.syncWarFooting`
   adds and removes both on the fast poll and on mobilisation, with one `tripleStep`
   recompute when anything changed; both persist in the save.
3. **Shortage cover** - `ThreatReserves.coverShortage`: `addTradeModPlus` (source
   `threatinc_cover`) for `getQuantityForModValue(deficit units)`, paid from the reserve,
   lasting `reserveShortageCoverDays` (30) and re-issued when it lapses; whole units only,
   at most `reserveShortageCoverFraction` (0.5) of the stock per issue; issue timestamps
   persist in `ColonyReserve.coverIssued`. Board: the stock cell reads bright while
   covering and red when the stock is too low to issue a unit; the row tooltip says why.
4. **Sales** - nothing to build: a sale's trade modifier raises `available`, which ends
   the deficit (no cover is bought) and banks as surplus. The condition tooltip states the
   reserve, the bank rate, the cover and "selling any of these here raises its
   availability for 120 days".
5. **Convoys** - `ThreatConvoys.arrived` applies `addTradeModPlus` per landed quantity for
   vanilla's `TRADE_IMPACT_DAYS` (120), sources `threatinc_convoy_<uid>`.
6. **Reach** - unchanged, reads vanilla fuel.
7. **Units** - `ThreatReserves.status` feeds one shared line per commodity
   (`WarFootingCondition.addCommodityLine`) to both the condition tooltip and the faction
   view's row tooltip: "Fuel: 2,500 in reserve. Short 3 units (7 available, 10 demanded):
   depot too low to issue - a unit is 1,500 and the depot spends at most 50% of its stock
   per issue, so the shortage stands."

What the clone save showed (Hegemony, Persean League, the player and the backfilled
factions, days 2110-2144 of the incursion):

- **Vanilla imports only up to demand.** Every import-fed colony read available = demand
  for all four commodities before and after the War footing landed (Tigra City marines
  1/1 with its +1 unit of demand; Chicomoztoc fuel 7/10 only because no source could
  cover it). So "surplus" is in practice local overproduction: Chicomoztoc's Heavy
  Industry banks 400 armaments a month (10 available, 8 demanded), the player's
  nanoforged Ice Wind Desert 800; nobody banks fuel or supplies they import. The worry
  that every importer would bank a broadcast surplus does not arise; reserves are small
  and uneven as section 4 predicted, and convoys plus the player's sales are how fuel
  reaches a faction without a fuel surplus.
- **Marine demand exists in vanilla.** Ground Defenses / Heavy Batteries demand marines
  at size, supplies at size and heavy armaments at size-2 (Lion's Guard HQ armaments at
  size), so a military world's War footing marine demand is size + N, and a colony whose
  marines come from a distant High Command is short of them the moment it mobilises:
  Chicomoztoc (10 demanded, 7 reachable) spends 300 marines a month covering three
  units, Eventide 200, Kazeron 100 marines and 750 supplies.
- **Vanilla nets trade modifiers.** The depot's 3,000-fuel issue at Chicomoztoc moved
  availability by one unit, not two, because the player had been buying fuel there.
  `ownModUnits` credits the difference with and without the depot's quantity.
- **The cover fraction is a floor the player will see.** Chicomoztoc holding 2,500 fuel
  reads "depot too low to issue" (a unit is 1,500, half the stock is 1,250) with the
  shortage standing and the board cell red. That is the rule as approved; a fraction of
  1.0 means "spend it all".
- **The garrison floor is the other stop (fixed 2026-09-05).** The cover used to ignore
  `reserveFloorFraction` entirely, and the floor itself was a fraction of the LIVE cap,
  which is zero in deficit - so a struck colony's covers drained the depot to nothing
  (Sindria: 0 fuel, 0 supplies, 0 arms after repeated strikes). Now the floor stands
  on the largest cap the depot ever banked towards, and a cover spends at most the
  cover fraction of the stock and never below the floor; when the floor is what stops
  the issue the tooltip says "the depot keeps N for the garrison" instead of quoting
  the fraction. Old saves have no recorded cap until the next poll banks one.
- Covers lapse and re-issue on schedule (the day-2110 issues were re-bought after the
  30-day hold), survive a save and reload with their timestamps (26 days left after four
  days), and the condition and structure reload without re-mobilising anything.
- Seen in passing, not from this build: the war-mode backfill mobilised "neutral" (an
  in-flight strike at the purged world Skathi), which now has a selector button.

## Sources

- [4X-ECON] Strategic resource types and stockpile vs flow: https://www.gamedev.net/forums/topic/386970-4x-strategic-resources/ and https://www.gamedev.net/forums/topic/687197-realistic-economy-in-4x/
- [CIV] Civilization resource design discussion: https://medium.com/@kallist/game-economy-design-of-premium-games-through-the-example-of-a-4x-strategy-on-pc-db60594d171b
- [VIC3-DD] Victoria 3 Dev Diary 9, National Markets: https://forum.paradoxplaza.com/forum/threads/victoria-3-dev-diary-9-national-markets.1484917/
- [VIC3-DEEP] Modeling the global economy in Victoria 3: https://www.gamedeveloper.com/design/deep-dive-modeling-the-global-economy-in-victoria-3
- [HOI4] HoI IV logistics overhaul: https://eip.gg/hoi4/news/hearts-of-iron-iv-patch-1-11-logistics-overhaul-no-step-back-dlc/
- [AURORA] Aurora 4X mineral logistics: https://aurora2.pentarch.org/index.php?topic=11528.15
- [DW2] Distant Worlds 2 economy guide and shortage threads: https://game.lb-product.com/en/games/distant-worlds-2/guides/distant-worlds-2_economy-guide and https://steamcommunity.com/app/1531540/discussions/0/3369278831715796670/
