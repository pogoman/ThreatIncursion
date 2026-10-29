# The war effort - needs, aid and comms (planned 2026-10-23, BUILT 2026-10-24)

**BUILT 2026-10-24, jar 00:10, untested in game** (`ThreatNeeds`, `ThreatComms`, `ThreatAidRequests`,
`ThreatincAidCMD`, `ThreatAid.quoteAid` / `dispatchAid` / `onDelivered`, `ThreatCoalition.allyAid`,
`ThreatFactionView` Needs column + Request button, `ThreatWarBoard` fronts Aid; the knobs of section 7).
Departures from the plan, each a judgement call: allies send no unasked goods to the player's colonies
(the player Requests; relief and guards are unchanged); a hold need whose Min is 0 (convoys at sea
cover it) quotes the first tier with a figure; the NAVY need is served by `ThreatCoalition.aidHulls` as
before; `{Battle}` in the navy comms reads "the front"; a DEPOT_MARINES table was added; met notices
are one a world a tick; the fronts table's Aid button sails at its own (supply) tier. The user, the same evening as [front-line-proposal.md](front-line-proposal.md):

> the mission system needs a rework. firstly they shouldn't pay out. the missions should change
> to outcome based: sindria is requesting x marines to push back the front, but you should be
> able to send / personally deliver more than that if you want. in a similar fashion they might
> need x hulls or y fuel for a siege expedition. make it purpose based and the reward is faction
> rep. the player can request aid for a colony and any faction with favourable or higher
> relations will send aid to the amount (min med max) requested, and whichever faction does
> this will decrement your rep accordingly. the aid request should be sized to the scenario:
> under siege, enough marines to hold (min), push back (med) or overrun (max); if space is
> controlled by the threat they cannot aid the front, only send ships. do away with the
> arbitrary mission system and expand the military aid system. rather than notification spam,
> a clearer picture on each faction's board of what they are short of at any given planet, and
> the notifications are war comms chatter - "sindria needs marines, we cannot hold!" - rather
> than clinical "sindria needs exactly x marines". plan it all out before building, including
> all the text.

Both plans build on each other: the line (the other doc) defines *hold / push back / overrun*
as numbers; this doc spends them.

## 1. One ledger of needs

Everything below reads one thing: **a colony's needs**, computed live, never posted, never
accepted, never expiring (`ThreatNeeds`, replacing `ThreatAidRequests` + `ThreatAidMissionIntel`).
A need is `(colony, purpose, good, min, med, max)`; the good is one of marines, heavy
armaments, fuel, supplies, ship hulls, or **fleets** (warships over the world, in FP). The
purpose names why, and is the word the board, the dock and the comms all use:

| Purpose | When | Good | Min / Med / Max |
| --- | --- | --- | --- |
| **hold the line** | Threat army on the world, the line moving against the defenders (losing / overrun) | marines | marines that bring the line's rate to 0 / to -0.25 (push back) / to -0.5 (overrun) - the line doc section 4 |
| **push back** | army on the world, the line grinding or stalled | marines | the same three figures; the colony is at or past Min, so Min reads "nothing" and the ask opens at Med |
| **arm the defenders** | army on the world, `defenderArmsNeed` > 0 | heavy armaments | 30 days of the defenders' burn / 60 / the stockpile's capacity |
| **lift the orbit** | the swarm holds the orbit over an invaded world, or a Threat strike in flight outweighs the defenders (`needsDefence`) | fleets | the Threat FP over the world x `reliefOrbitMargin` / x 1.25 / x 1.5, net of guards already bound there |
| **keep the depot** | peacetime gap the depot cannot cover (`shortageStanding`, as today) | fuel, supplies, armaments | the gap / x `convoyExtraLoadFactor` / fill to capacity |
| **rebuild the navy** | the faction is short of hulls with losses unrebuilt (`hullsShort`), at its hull port | ship hulls | the debt's units / the debt + 30 days of losses / the pool's cap |
| **provision the expedition** | a planned siege postponed for fuel or supplies (`expeditionMinProvisionsFraction`), at its staging base | fuel or supplies | the shortfall / x 1.25 / the voyage in full |

Rules that hold the ledger together:

- **A world the swarm holds the orbit over has one need: fleets.** Goods cannot land (the
  blockade door holds every convoy), so the marines / armaments needs are *suspended*, shown
  greyed with "blockaded" on the board, and nobody is asked for them. The exception is the
  player's own fleet, which fights its way in and hands over at the dock: the dock keeps
  listing the suspended needs for the player in person.
- Needs net out what will land (`ThreatConvoys.inbound(id, landing)`, today's rule) and what
  is bound (guards for fleets).
- Over-delivery is always allowed: Min / Med / Max size the *ask*; a hand-over or a convoy may
  carry more, and all of it lands and counts (the surplus at the depot rate, section 4).
- Nothing is accepted, nothing fails, nothing expires, nothing pays credits. The vanilla
  mission intel (`ThreatAidMissionIntel`, kinds DEFEND and AID, `aidRequestMaxPosted`,
  `missionPostingDays`, `missionDefendDays`, `missionDefendCredits`, `missionAidShortageDays`,
  `raiseNeed`, the "Accepted" tab entries) goes. Knobs retired with it: `missionRepMult` (folded
  into the purpose weights below), `defendRequestRatio` (kept, it defines `needsDefence`).
- The **swarm bounty** stays as it is: it is vanilla's system bounty on Threat ships, not a
  mission, and it is the one place credits still flow - per kill, as the sector does for pirates.
  (Decision 1 below asks whether to keep it.)

## 2. The board - what each faction is short of, where

The user: "a clearer picture on each faction's board of what they are short of at any given
planet. i guess we kind of get that already." The colony reserves table already colours a
deficit; what it lacks is the *purpose* and the *size*. Changes, all within the no-prose rule:

- **Needs column** on the colony reserves table (every faction tab, the player's own included):
  the purpose word(s) for the row, coloured by urgency - `hold the line` red, `lift the orbit`
  red, `push back` and `arm the defenders` yellow, the rest white; "blockaded" grey after a
  suspended one. Tooltip: one line per need, `purpose - good - min / med / max`, and for a
  fleets need the Threat FP over the world and the guards bound.
- **Aid button** on an NPC colony row (today's Resupply / Reinforce fold into it; the Reinforce
  button goes, as the user guessed): it serves the row's most urgent need at the Amount tier
  (since 2026-10-28 the colonies table's levers: each good's share of the best donor's spare,
  `ThreatAid.quoteAid`; the need names the purpose only - see player-aid.md "The load ladders"),
  from the player's nearest colony that can cover it (today's `ThreatAid.quoteResupply` /
  `quoteReinforce` picking rules), and its tooltip quotes `purpose: N good from Colony`. A
  fleets need makes the button Defend (today's `quoteDefend`), with the FP it sends.
- **Request aid** button on the player's own colony rows, section 3. Greyed with the reason
  when nothing is short, when no faction is Favourable, or within the cooldown.
- The fronts table is unchanged apart from the line doc's columns; its row tooltip gains the
  marines need in one line (`hold the line: 320 / 410 / 560 marines`).

## 3. Requesting aid for the player's colonies

**Since 2026-10-29 (the user: "it really shouldnt be guaranteed and instant, as other factions
might need all their resources / be busy with their own defences ... needs to depend on factions
current level of besetness by the threat cross referenced with your overall standing"):** the
Request sits on the player's sieged world's row of the fronts table (beside Reinforce), not the
colony rows, and is a general call at the fronts' Marines, Arms and Fleet levers
(`ThreatAidRequests.request(market, frontLevers)`). It stands `aidRequestAnswerDays` (7) and the
slow tick then answers it (`answer` -> `answerNow`); Cancel withdraws it and lifts the cooldown.
Each candidate sends the levers' share of its spare, and the Fleet lever's share of its free
hulls as a guard (`ThreatFleetOrders.dispatchGuardPoints`), times `willingness` = 1 - besetness x
`aidRequestBesetWeight`, where besetness is the share of its own worlds under a Threat army,
blockaded or with strikes bound (`besetCount`); and nothing at all while that beset share
outweighs the player's standing with it, read from Favourable's floor (0) to Cooperative's (1)
(`standing`). The tooltip and prompt carry a line a faction (`standLine`). The paragraphs below
are the 2026-10-24 and 2026-10-28 designs the rest still follows (candidates, budgets, prices).

The mirror of giving. The player presses Request aid on an own colony; the Amount tier picks
Min / Med / Max of the row's most urgent need (the tooltip names it; the confirmation is the
question and the numbers). (Since 2026-10-28 the colonies table's levers instead, answered AT
ONCE: of each good at a lever, each candidate sends the lever's share - half or all - of what
its colonies in reach spare of it in all, counting only colonies sparing `aidRequestMinSpare`
(100; a unit for hulls) or more, filled from the colony sparing the most, and a guard when a
fleets need stands - `ThreatAidRequests.request` / `answerNow` / `donors`. The answers are one
intel entry, `ThreatAidAnswersIntel`, opened from its notification: a section a faction with
what sailed from where and the standing it cost, or why nothing did ("no colony in reach with
100 or more to spare", "would take you below Favourable", "no base in reach"), and the standing
spent in all. No notice a sender any more. The rules below on candidates, budgets and prices
stand; "on the next slow tick" is now "at once".) Then, on the next slow tick:

- Every faction at **Favourable or better** with the player, not hostile to the player's
  faction, is a candidate. Candidates are ordered by standing (Cooperative first), then by
  distance of their nearest colony with spare. Each sends what it can spare of the good
  (`ThreatConvoys.pickAllyDonor`'s rules: above its own keep, in fuel reach, a load worth
  sailing) until the ask is covered net of what is at sea. For a fleets need each sends a
  guard task force from its nearest base with free hulls (`ThreatCoalition.jointRelief`'s
  sender rules), the player's colony its target, until the FP is covered.
- **Each sender's price is rep**, debited the day its convoy or fleet sails:
  `value at the player's market / aidRepPerCredits` for goods (the same scale the player earns
  at, so giving and asking balance over a war), the guard's FP x `aidRepGuardArrived` / 100 for
  fleets; both times the tier (Min 1.0, Med 1.25, Max 1.5 - asking for the decisive amount costs
  more than asking to hold). A sender never takes the player **below Favourable**: it sends
  up to the rep budget that keeps it there, so an ally is never spent into neutrality by aid
  and the player can always see the floor coming on the Request tooltip ("Hegemony: up to 1,200
  marines before Favourable").
- One request per colony per `aidRequestCooldownDays` (30); a request stands until covered or
  the need is gone; the player may cancel it (the button toggles to Cancel request).
- A convoy or guard that is lost at sea was still paid for: the rep stays spent (the ally bore
  the loss). Nothing is refunded.
- The NPC factions use the **same path among themselves**: `ThreatCoalition.allyAid` stops
  reading `shortageStanding` / `requestItems` and answers the ledger's needs by willingness, as
  it does today for stock plans and hulls. No standing changes between NPC factions (vanilla
  does not model it usefully).

## 4. Reputation for giving

As today's section 3 of player-aid.md, with the mission doubling replaced by a purpose weight:

| Purpose | Weight on `value / aidRepPerCredits` |
| --- | --- |
| hold the line, lift the orbit | 2.0 |
| push back, arm the defenders, provision the expedition | 1.5 |
| keep the depot, rebuild the navy | 1.0 |
| surplus beyond Max, or a good nobody needs | 0.5 |

Capped at `aidRepMaxPerDelivery` (+10) per landing as now. Defend (a guard on station) keeps
its arrival / fight / term points. Nothing pays credits. The notice on landing stays a fact
notice ("Aid Landed": the cargo, the colony, the faction), one per landing.

## 5. The dock - handing over in person

"Deliver aid for the war effort" at any war faction's colony (station commander over the comm
directory too) lists **every need the colony has**, suspended ones included, one line each:

```
Sindria holds the line against 212 Threat troops. Marines: 320 to hold, 410 to push back, 560 to overrun. You carry 480.
Sindria's defenders burn 2 heavy armaments a day. Armaments: 60 / 120 / 437. You carry 0.
```

Options: `Hand over 320 marines (hold the line)`, `Hand over 410 marines (push back)`, `Hand
over 480 marines (all you carry)` - one option per tier the hold can cover, plus "all you
carry" when that is more than Max. Ship hulls are never in a hold (today's rule); the port
says they sail from the hull port by convoy. With no need: "The station commander has nothing
to ask of you." After a hand-over the text panel gives the thanks line of section 6 and the
rep change.

## 6. War comms - the text

> The live text is `ThreatComms`; the tables below are the plan. Revised 2026-10-24 13:50 on the user's review: one met table per good/purpose, no "Need met", no medieval voice (`docs/facts.md`, that day's decision).

The user: "war flavour messages rather than clinical sterile text". Rules, so they stay inside
the notice discipline (CLAUDE.md "Notifications go through ThreatNotice"):

- A comms notice is a **title**, then **one voiced line in quotes** from the colony or the
  faction's command, in the faction's colour, then **one fact line** naming the purpose and
  the good without the number ("Needs: marines, hold the line - the board has the figure").
  The exact figures live on the board and at the dock, where they are read a hundred times;
  the notice is read once.
- One notice when a need **appears**, one when it **escalates** (push back -> hold the line ->
  overrun; keep the depot -> exhausted), one when it is **met** or the colony is lost. Never
  on a figure changing. A faction with three needs at three worlds sends three notices, not
  one a day: `aidCommsMinDays` (15) between notices for the same world and purpose.
- Three variants per scenario, picked by a seeded roll on the world and the day so a reload
  reads the same. Faction names and world names are substituted; nothing else varies.
- Title colours as the rule: the Threat's colour for what the swarm does, red for our side's
  losses, light blue otherwise. A comms title is light blue with the faction's crest.

### 6.1 Needs appearing

| Scenario | Title | Voiced line (three variants) | Fact line |
| --- | --- | --- | --- |
| hold the line (losing) | `{World} Calls For Marines` | "{World} to all stations: the line is breaking. Marines on the ground or the city falls." / "We are losing districts by the day. Send marines - anything that can hold a rifle." / "The garrison is bleeding out on {World}. Marines, now, or write us off." | Needs: marines, hold the line |
| overrun (the line collapsing) | `{World} Is Falling` | "This is {World}. We cannot hold. If anyone is coming, come now." / "Last district. {Faction} command, we are being overrun." / "{World} is going dark. Whoever hears this: it took them {days} days." | Needs: marines, hold the line - {n} days at this pace |
| push back (grinding / stalled) | `{World} Holds` | "{World} holds, barely. Give us marines and we take the districts back." / "The line is steady. More marines and we go over the top." / "We have them stopped. We need the numbers to finish it." | Needs: marines, push back |
| arm the defenders | `{World} Short Of Armaments` | "Our marines are fighting with hunting rifles. Heavy armaments to {World}, anyone who can." / "{World} quartermaster: we are down to sidearms. Armaments, by any hull." / "The depot is bare. Heavy armaments or the line goes quiet." | Needs: heavy armaments, arm the defenders |
| lift the orbit (swarm over an invaded world) | `{World} Blockaded` | "The swarm sits on {World}'s orbit. Nothing lands until it is driven off - warships, not freighters." / "{Faction} command: {World} is cut off from above. We need hulls in that sky." / "Every convoy we send waits at the jump point. Clear the orbit and the marines are an hour away." | Needs: fleets, lift the orbit |
| lift the orbit (strike inbound) | `Threat Inbound On {World}` | "Threat fleets inbound on {World}. Our patrols will not hold them. Any fleet in reach." / "{World} tower to all captains: they are coming, and we are outweighed." / "{Faction} command: {World} will be struck within days. We ask for ships." | Needs: fleets, lift the orbit |
| keep the depot (fuel) | `{World} Depot Dry` | "{World}'s tanks are dry: our convoys are grounded." / "No fuel at {World}. The patrols fly on fumes." / "{World} asks for fuel. The war does not wait for the tankers." | Needs: fuel, keep the depot |
| keep the depot (supplies) | `{World} Short Of Supplies` | "{World} cannot keep its ships flying. Supplies, if you have them." / "The yards at {World} are idle for want of supplies." / "{World} to the sector: our stores are out. Supplies, any quantity." | Needs: supplies, keep the depot |
| keep the depot (armaments, no army) | `{World} Short Of Armaments` | "{World}'s garrison drills with empty racks. Heavy armaments wanted." / "The militia at {World} has nothing to shoot with." / "{Faction} quartermaster: {World} needs armaments before the swarm finds it." | Needs: heavy armaments, keep the depot |
| rebuild the navy | `{Faction} Short Of Hulls` | "The yards at {World} cannot replace the losses at {Battle}. The navy sails thin." / "{Faction} admiralty: we have crews and no hulls. Ships, by convoy, to {World}." / "Every slip at {World} is full and it is not enough. We ask for hulls." | Needs: ship hulls, rebuild the navy |
| provision the expedition | `{Faction} Expedition Waits` | "The {Target} expedition is fuelled for half the voyage. Fuel to {World} and it sails." / "{Faction} command: the fleet is ready and the stores are not. Supplies to {World}." / "We have the ships and the marines. We lack the fuel to get them there." | Needs: fuel (or supplies), provision the expedition |

### 6.2 Needs met, escalating, ending

| Scenario | Title | Voiced line (three variants) | Fact line |
| --- | --- | --- | --- |
| marines landed, the line holds | `{World} Holds The Line` | "{World} to all stations: the marines landed. The line holds." / "They are in the trenches. We are not going anywhere." / "Reinforcements on the ground. {World} fights on." | Marines landed; the line holds |
| the line turns (gaining) | `{World} Pushes Back` | "{World}: we are taking the districts back." / "The swarm is giving ground on {World}." / "{Faction} command: {World} is on the offensive." | The defenders gain ground |
| army destroyed | `{World} Is Clear` | "{World} is clear. The swarm is dead on the ground." / "No Threat left on {World}. Thank you - all of you." / "{World} to the sector: it is over here." | The Threat army is gone |
| orbit lifted | `{World}'s Sky Is Clear` | "The orbit is ours. Convoys, {World} is open." / "{World} tower: the swarm is gone from above. Land everything." / "Sky clear over {World}. Now the ground." | The swarm no longer holds the orbit |
| depot need met | `{World} Resupplied` | "{World} depot: tanks full. Convoys roll." / "Supplies landed at {World}. The patrols fly." / "{World} is stocked. Our thanks." | Need met |
| hulls / expedition met | `{Faction} Sails` | "The hulls are in. The {Target} expedition sails." / "{Faction} admiralty: the fleet is whole again." / "Fuelled and stored. {Target} will hear from us." | Need met |
| colony lost | `{World} Lost` (red, the faction's crest) | "{World} has fallen." / "We have lost {World}. {Faction} remembers who came." / "{World} is gone. The swarm holds it." | one fact line as today's Colony Lost |

### 6.3 The player's requests answered

| Scenario | Title | Lines |
| --- | --- | --- |
| a faction answers | `{Faction} Answers {World}` (crest: the sender) | "{Faction} command: {n} {good} sail from {Colony} for {World}. We will not forget the asking." / "{Colony} to {World}: help is coming." / "{Faction} to the {PlayerFaction}: our convoy sails. Consider the favour noted." - then the fact: `{n} {good} from {Colony}; standing with {Faction} -{r}` |
| a faction sends fleets | `{Faction} Sails For {World}` | "{Faction} admiralty: {n} FP under way to {World}." / "Ships are coming, {World}. Hold the sky until they arrive." / "{Faction} to {World}: we are {days} days out." - fact: `{n} FP from {Base}; standing with {Faction} -{r}` |
| nobody answers | `No Answer For {World}` | one fact line per candidate's reason, today's `ThreatNotice.Reason` style: `{Faction}: not Favourable` / `{Faction}: nothing to spare` / `{Faction}: no colony in reach` / `{Faction}: would take you below Favourable` |
| request covered | `{World}'s Call Answered` | "{World}: the last convoy is in. Our thanks to every flag that answered." - fact: `{total} {good} landed from {k} factions` |
| the player's hand-over at the dock (text panel, not a notice) | - | "{World} takes the marines with thanks. The {Faction} will remember this." / "The quartermaster signs for {n} {good}. 'You may have just saved this city.'" / "'That is more than we asked. It will all be used.'" (when over Max) - then the rep line |

## 7. What goes, what stays

| Today | After |
| --- | --- |
| `ThreatAidMissionIntel` (Defend X, Aid X with N of C, hull contracts), accept / expire / fail, credits | Gone. `ThreatNeeds` ledger, Needs column, dock lists needs |
| `ThreatAidRequests.tick` posting, `aidRequestMaxPosted`, `battleItems`, `raiseNeed` | `ThreatNeeds.of(market)` computed live; `battleItems` becomes the hold / push back / overrun triple |
| "Help request posted: Deliver 507 marines to Volturn" | Comms notice 6.1 |
| Reinforce button | Folded into Aid (the need's purpose chooses) |
| Resupply / Defend buttons on NPC rows | Aid / Defend, serving the row's most urgent need |
| `ThreatCoalition.allyAid` on `shortageStanding` | On the ledger, same path as the player's requests |
| Swarm bounty | Stays (Decision 1) |
| `missionRepMult` 2.0 | Purpose weights (section 4) |
| Player cannot ask for aid | Request aid button, rep-priced (section 3) |

## 8. Build order (after the line) - steps 1-3 BUILT 2026-10-24, step 4 owed

1. `ThreatNeeds` ledger + Needs column + Aid button reading it (one evening). The old intel
   keeps running one build longer so saves with open contracts end them cleanly ("withdrawn").
2. Dock and comm-directory hand-over on the ledger; 6.1 / 6.2 comms with the variant tables
   in one `ThreatComms` class (one evening).
3. Request aid + rep pricing + 6.3; allies on the ledger; retire the mission intel and its
   knobs (one evening).
4. Test on the Sindria save: Sindria and Volturn both show hold the line, the dock offers the
   three tiers, one request from Yuendumu is answered by the Hegemony at a visible rep cost.

## 9. Decisions - taken 2026-10-23 22:40

1. **Keep the swarm bounty.** Keep the swarm bounty (credits per Threat ship killed) alongside the rep-only needs, or
   retire it too so nothing in the war pays credits?
2. **Never below Favourable.** The rep floor: a sender never takes the player below Favourable (proposed), or no floor
   (the player can spend an alliance down to Neutral)?
3. **Min 1.0 / Med 1.25 / Max 1.5.** Tier prices for asking: Min 1.0 / Med 1.25 / Max 1.5 of the giving rate (proposed), or a
   flat 1.0?
4. **No figure.** Comms shape: title + one voiced line + one fact line without the figure (proposed), or the
   figure on the fact line too?
