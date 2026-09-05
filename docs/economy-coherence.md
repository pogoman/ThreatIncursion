# Economy coherence - how 4X games run economies, and how the war reserves must fit vanilla

Written 2026-09-05 at the user's request. The problem statement: a colony can show a fuel
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
   counts, but the colony tooltip shows them beside vanilla's tiers ("fuel: 3 units
   surplus, banking 1,200 a month, 4,800 in reserve, 60 days of expedition fuel").

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

## 5. Build order (not started)

1. `ThreatReserves.accrualPer30` -> surplus formula via `getSizeMult` and `getEconUnit`;
   retire the four per-unit knobs for one `reserveSurplusMult` (1.0).
2. `War footing` condition (`data/campaign/market_conditions.csv` + plugin): demand while
   mobilised, tooltip with reserve, bank rate and the "sell here" hint.
3. Shortage countering on the fast poll (rule 3) with the cover-fraction knob and a board
   status.
4. Convoy arrival applies `addTradeModPlus`.
5. Faction view tooltip units (rule 7).

## Sources

- [4X-ECON] Strategic resource types and stockpile vs flow: https://www.gamedev.net/forums/topic/386970-4x-strategic-resources/ and https://www.gamedev.net/forums/topic/687197-realistic-economy-in-4x/
- [CIV] Civilization resource design discussion: https://medium.com/@kallist/game-economy-design-of-premium-games-through-the-example-of-a-4x-strategy-on-pc-db60594d171b
- [VIC3-DD] Victoria 3 Dev Diary 9, National Markets: https://forum.paradoxplaza.com/forum/threads/victoria-3-dev-diary-9-national-markets.1484917/
- [VIC3-DEEP] Modeling the global economy in Victoria 3: https://www.gamedeveloper.com/design/deep-dive-modeling-the-global-economy-in-victoria-3
- [HOI4] HoI IV logistics overhaul: https://eip.gg/hoi4/news/hearts-of-iron-iv-patch-1-11-logistics-overhaul-no-step-back-dlc/
- [AURORA] Aurora 4X mineral logistics: https://aurora2.pentarch.org/index.php?topic=11528.15
- [DW2] Distant Worlds 2 economy guide and shortage threads: https://game.lb-product.com/en/games/distant-worlds-2/guides/distant-worlds-2_economy-guide and https://steamcommunity.com/app/1531540/discussions/0/3369278831715796670/
