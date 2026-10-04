# The swarm's fog of war - DESIGN and BUILD (2026-10-01)

The human side has had fog since 2026-10-01 (`ThreatIntel`, `attack-planner.md`). The swarm did
not: it read every human attack from dispatch, every staging base's depot, forward guards, convoys
and a strike target's defence exactly, live and from any distance. That is why the war council's
Starve, feints and bombers could not work (h53c-d, `facts.md`): the swarm massed over each target
before anything arrived. The user's rule (2026-10-01): no cheats, fog for everyone; knowledge comes
from eyes and battles (radar too until 2026-10-02, when the user removed it from both sides: section
4, "No radar"; since 2026-10-04 the hives keep a picket for attack forces in flight: section 4, "The
hive picket"). The user asked for this change on 2026-10-01, after the
council was committed (4a5ead4), as "the Threat's fog", deferred by attack-planner decision 1 and
war-council decision 8.

## 1. Where the swarm reads humans (code map, 2026-10-01)

Mapped by a five-reader sweep plus a critic (session fbb85ee0). By symbol. "Remote" means no
Threat asset is near.

**Posture pressure** (`ThreatPosture.poll` every `postureDays` 5, inside the 0.4-0.6 day manager
poll; P = max(A, B) + C + D + F, held with a 30-day decay: pressure = max(raw, prev x exp(-dt/30))).
- A, attacks (`attacksBySystem`), remote. Siege loop: every live `GenericRaidFGI` in
  `IncursionManager.getPurgeList` whose first `allowedTargets` market is in a hive system; spawned
  fleets by `ThreatSoftening.combatFP`, an unspawned one by `ThreatPurgeFGI.abstractNow` when
  `countsAbstract` (knob `threatSeesBookedSieges`). Order loop: every `ThreatFleetOrders` order
  (HUNT by `huntSystemId`, SUPPORT/DEFEND/raids by target world's system), each fleet once (the
  `counted` set, which also keeps them out of D), from the order's creation, mustering included.
  Feeds want, transfers, mode, `S_ATTACKED` and the stance's `Pass.addForce` per faction.
- B, staged capacity (`read`), remote. Bases from `IncursionManager.siegeBasesFor` /
  `findSiegeBases` (mobilised non-player factions, `isBase`, within `expeditionRangeLY`, itself a
  stock read via `ThreatReach.baseRangeLY`); mapped to one hive system by
  `ThreatConvoys.stagingHive` (the human's own pick, a play's decoy included, `sectorKnows`-gated);
  valued by `siegeCapacityFP` (exact fuel and supplies of the base and its donors) at weight 1, or
  0.5 x `ThreatSoftening.payableFP` for a base `ThreatConvoys.stagesForHunt` judges a hunt base.
  Per faction the largest base, summed across factions. `siegeCapacityFP` is also called by
  `ThreatPlays` (humans sizing their own plays): never fog it in place.
- C, losses (`noteBattle`, `ThreatAbstractBattle`): witnessed. Stays.
- D, hostiles (`read` over `system.getFleets()`): in the hive system. Eyes. Stays.
- F, forward guards (`read` via `ThreatFrontlines.all` / `hiveNear` / `liveGuards`), remote:
  NPC forward bases' guards outside the system, each base toward its nearest found hive within
  `frontlineKeepLY`.
- Wounds (`recentlyThinned`, fronts, disrupted organs): felt. Stay.
- `ThreatStance.evaluate` adds no human read of its own beyond the strike side below: "theirs" per
  faction is A + B + F of the pass, so fogging those fogs the stance.

**Strike side** (`IncursionManager.tick` every `tickDays` 30: `tryStrikes`, `trySpread`).
- `swarmKnows` (`ThreatSwarmScouts.known()`: systemId -> first-charted time, permanent, nothing
  recorded; also true wherever a hive or a Threat front shares the system) gates WHERE: strike
  candidates, `ThreatStance` known worlds, `ThreatReach.faced`, `coreWorldInReach`, `retaliate`.
  World facts inside a charted system (owner, size) are live, as planet facts are for humans.
- Target defence, remote and exact: `IncursionManager.targetDefence` (WarSimScript
  `getEnemyStrength(threat, system, true)` + `getStationStrength`), read by the launch gate
  (`strikeOutweighed` and the `strikeDefenceGate` check in `pickStrikeTarget`), by
  `ThreatStance.weakTargets` (the PRESS target and `extraWantFP`) and through the odds by
  `ThreatStance.strikeTargetMult`.
- `strikeTargetMult` in CONSOLIDATE also asks `ThreatConvoys.stagingHive(market)`: is this base
  staging against us (the human's pick).
- Spread: `pickSpreadTarget` -> `siegeBases` / `holdShare` read every military base's reach from
  its stocks (`baseRangeLY`, `fuelRangeLY`); `isValidSpreadCandidate`,
  `distanceToNearestInhabited`, `unwarredLocations` read where humans live, uncharted too.
- `warOpen` reads `ThreatWarState.isAtWar`, which the player's Mobilise button sets at once.
- No Threat-side arrival break-off: `ThreatStrikeFGI` has only the launch gate; on arrival vanilla's
  autoresolve skips the raid against an equal defence and charges up to 75%.

**Fleets.**
- `ThreatRaiders.consider`, remote: called at convoy dispatch (`ThreatConvoys.sail`,
  `sailFrontRun`), reads the convoy's exact FP and route; every hive within half the route's length
  of its midpoint rolls; `detach` gives the raiders vanilla `INTERCEPT` on the live convoy fleet.
  `poll` ends a hunt the moment the convoy's ledger entry ends anywhere.
- On-site, left live (a fleet's own judgement, the `ThreatIntel` precedent): the orbit contest
  (`ThreatGroundFronts.orbitContestedFor`, `countsNear`, `swarmOrbitContested`), strike passes,
  `ThreatSwarmDefend`, Threat fronts' defender figures, `ThreatBlockade`, the leashes (vanilla AI on
  the fleet's own sensors). `AnnihilationAction.directFleets` reads every target's contest each frame
  but acts only on a fleet within 1500 su of that world.

**Exempt** (not a reaction to humans): the war's trigger and the first seeds, accessibility's
centre of mass, omens, Nexerelin's invasion guard, the player's own raid dialog.

## 2. Design

The swarm keeps its own reports, a mirror of `ThreatIntel`, in a new class `ThreatSwarmIntel`.

**Senses** (one daily sweep, `poll`, in the manager poll block after `ThreatSwarmScouts.poll`):
- **Eyes**: everything human in a system where the swarm has a live hive colony, any live Threat
  fleet (garrisons, Scouting Swarms, raiders, Defend stations, spawned strike fleets), an unspawned
  Threat strike route currently in it, or a Threat-owned ground front. Exact figures.
- **No radar** (user, 2026-10-02; Bastion radar was built 2026-10-01 and removed with the humans'):
  no place is seen from hyperspace. A place or contact is real time while a Threat ship, hive or front
  is in its system; from the day the last leaves it stands and ages.
- **Picket** (user, 2026-10-04): a human attack force in hyperspace within `swarmPicketLY` (4) of a
  live hive is seen, exact - the humans' strike picket mirrored. Forces in flight only (section 4,
  "The hive picket").
- **Scouts**: a Scouting Swarm entering a system (`ThreatSwarmScouts.ROUTE.onEnter`) looks at every
  human place there (source SCOUT, exact).
- Battles need nothing new: a Threat fleet in a fight is in the system, so eyes.

**What it records** (saved under `threatinc_swarmIntel`):
- A **Contact** per human attack force seen: key (`siege:` + the FGI's identity, `order:` + the
  order's fleet id), faction, the hive system it is bound for, FP as A counts it today, first and
  last day seen, source. A force's position: a spawned fleet's location (system: eyes test;
  hyperspace: the picket); an unspawned siege's route position
  (interpolated hyperspace location, or its current system), only while `threatSeesBookedSieges`.
  An order still mustering at a base is seen only if that base is.
- A **Place** per human base or world seen: market, system, faction, day, source, and what the
  swarm's readers need from it: `stagedFP` and `stagesFor` (B's per-base figure and target, on
  the base's own stock only - see section 4), `guardsFP` (a forward base's live guards), `reachLY`
  (`siegeBases`' range, own stock only) and `defenceFP` (`targetDefence`). Figures never decay; trust does:
  `trust = 0.5 ^ (age / intelHalfLifeDays)`, the humans' formula and knob.

**Readers, fog on** (`threatinc_swarmFogOfWar`, default true; off = today's live reads):
- A = the contacts bound for the system last seen within `swarmContactDays` (10), at their
  last-seen FP. A contact is dropped when its force ends. The `counted` set (fleets kept out of D)
  is still built from the live forces' fleets in the system, which is on-site.
- B = per faction the largest `stagedFP x trust` of its places staging for the system, summed.
- F = each sighted forward base's `guardsFP x trust` toward its `hiveNear` system.
- C, D, wounds: unchanged.
- `targetDefence(market)`: the place's `defenceFP` (a market never seen has no defence figure and is
  not a strike candidate until scouted or seen). The launch gate and `weakTargets` read it.
- `strikeTargetMult`'s "staging against us" = the place's `stagesFor` is set.
- `siegeBases` / `holdShare`: only bases with a place, at its `reachLY`.
- `ThreatRaiders`: a convoy is not considered at dispatch. The sweep checks live convoys; the
  first time one is seen (eyes) `consider` runs, with the FP as seen.
- **Re-scouting**: `ThreatSwarmScouts.planRoute` also routes to charted systems holding a
  strikeable world whose newest place is older than `intelHalfLifeDays`, after the uncharted ones.
- **Old saves**: the first sweep seeds a place for every human market in every charted system
  from a live read, dated the day it was charted (so it is already old and the scouts go back).
  Once per save (a saved flag), and once more each time the fog is switched back on.

**Left alone, on purpose**: on-site judgement (section 1, "Fleets"); world facts in charted
systems; spread's "is this system inhabited" filters (planet facts); `warOpen` (mobilisation is
flipped by a detected strike for NPCs; the player's button is the one leak, noted);
`ThreatColonyUpkeep.frontLY` (planet positions).

**Decisions taken by default** (the user asked for the build without a question round; each is the
mirror of the human side or the smaller change):
1. Radar on Bastion and Command worlds only, at the humans' range (10 ly). Every hive seeing 10 ly
   would see nearly every siege at dispatch again. [Overtaken 2026-10-02: no radar at all.]
2. A sighted attack counts at its real target (the swarm reads intent once it sees a fleet).
   Attributing it to the nearest hive instead would make feints land on the wrong system.
3. No arrival break-off for blind strikes: vanilla's autoresolve already skips a raid into an equal
   defence and charges it; a break-off is a separate change if strikes are ground up.
4. A contact ends when its force ends (the swarm sees it leave), not after it fades from sight.

## 3. API (`ThreatSwarmIntel`, LF)

```java
public static final String EYES = "eyes", PICKET = "picket", SCOUT = "scout";   // RADAR went 2026-10-02
public static class Contact { public String key, factionId, systemId, source;
                              public float fp, firstDay, day; }
public static class Place   { public String marketId, systemId, factionId, source, stagesFor;
                              public float day, stagedFP, guardsFP, reachLY, defenceFP; }
static boolean enabled();                        // ThreatIncConfig.swarmFogOfWar()
static void poll();                              // once per day (own sweptDay latch)
static void scouted(StarSystemAPI system);       // Scouting Swarm arrival
static String sees(LocationAPI where);           // places, convoys: EYES / null
static String sees(LocationAPI where, Vector2f hyper);  // attack forces: EYES / PICKET / null
static List<Contact> contactsOn(String hiveSystemId);  // seen within swarmContactDays
static Place place(String marketId);             // null if never seen
static List<Place> places();                     // every place (B, F, siegeBases)
static float trust(Place p);                     // 0.5^(age/intelHalfLifeDays), 0 if null
static boolean stale(String systemId);           // newest place in the system older than half-life
static void reset(); static void drop(String systemId); static void forget();
```
`ThreatPosture` exposes the per-base figure it already computes as
`static float stagedBy(MarketAPI base, String hiveSystemId)` (0 if the base does not stage for that
system) and its target as `ThreatConvoys.stagingHive`. Under the fog it counts the base's own
stock only (section 4), so it is no longer exactly what the fog-off B reads.

Knobs: `threatinc_swarmFogOfWar` (true; Luna "Fog of War for the Swarm"),
`threatinc_swarmContactDays` (10), `threatinc_swarmPicketLY` (4; Luna "Hive Picket Range (LY)";
0 = off). `threatinc_swarmRadarRangeLY` went on 2026-10-02 (LunaLib migration 10 drops a stored
value).

Logs: `Swarm intel: sees <faction> <kind> of N FP bound for <system> by <source>` on a contact's
first sighting; `Swarm intel: <source> on <market> (<faction>): staged N for <system>, guards N,
defence N` on a place's first sighting or a 50% change; a monthly `Swarm intel census:` line
(contacts, places, oldest, stale systems).

## 4. As built

Built 2026-10-01 (session fbb85ee0), reviewed the same night (three lenses, then verify, then fix),
untested in-game until the long new-game run. All files LF.

**Departures from section 2:**
- Re-scouting is a second pass in `ThreatSwarmScouts.launchAll` (`planRoute(colony, range,
  rescout=true)`) after the uncharted pass, not one merged route. `knownStop` treats a stale
  system as not known, so a route may stop there.
- `IncursionManager.strikeSeen(market)` is the fog's filter on strike candidates: a world needs a
  place. `pickStrikeTarget`, `strikeAllowed`, `coreWorldInReach` and `ThreatReach.faced` all use it
  (always true with the fog off). `targetDefence` returns `Float.MAX_VALUE` when there is no
  place; `ThreatStance.strikeTargetMult` weighs that 0 and `weakTargets` skips it.
- Every place records `defenceFP` (not only strikeable ones), and `stagesFor` is set for any seen
  base whose `stagingHive` is non-null.
- A siege's contact key is `siege:<faction>:<route seed>`; an order's is `orderKey(fleet)`.
- Raiders: `ThreatConvoys.sail` / `sailFrontRun` skip `consider` under the fog;
  `ThreatRaiders.sweep` (called by the daily poll) rolls each convoy once on first sight
  (`CONSIDERED_FLAG "$threatinc_raidConsidered"`), with the hives in the convoy's current system
  qualifying first (`consider(convoy, random, fp, seenIn)`), since a convoy first seen at its
  destination is half its route from the midpoint.

**Review fixes (all built, jar 22:45:49):**
- Donor leak: `record` once stored `stagedFP` and `reachLY` pooled from every donor depot in the
  sector (`huntDonors`, `siegeDonors`), which the swarm cannot see. Now `ThreatPosture.stagedBy`
  uses `stagedCapacity(base, system, null)` (own stock) and `reachLY` comes from
  `IncursionManager.seenSiegeBaseReachLY` (`ThreatReach.ownRangeLY`, or the fuel radius). The
  fog-off reads are unchanged (`ThreatReach.rangeOn` is shared). **Still open:** whether a base
  counts as staging at all is `siegeBasesFor(staging).contains(m)`, which uses the donor-pooled
  range, so a yes/no still leaks; left because changing it changes which bases count.
- Scouting off with the fog on left nothing to strike (no scout, so no place outside eyes).
  `seedUnscouted` now, while `ThreatSwarmScouts.enabled()` is false, seeds each system
  holding a strikeable human world once per save (saved `UNSCOUTED` map), from a live read dated
  that day; systems already placed keep what was seen. Default taken: once per system, so a world
  founded later in a seeded system waits for eyes.
- The seed latch was static and reset on every load; it is the saved `SEEDED` flag now.
  `IncursionManager` calls `ThreatSwarmIntel.poll()` always; with the fog off it only runs
  `fogOff()`, which clears `SEEDED` and `UNSCOUTED` so re-enabling the fog seeds again.
- Posture's `counted` (fleets kept out of D) took every live force while A took only contacts
  already swept, so a force arriving between the sweep and the posture pass was in neither. Under
  the fog a force is in `counted` only while `inSight(key)` (contact with FP > 0 seen within
  `swarmContactDays`).
- Convoys at sea in an old save were rolled at dispatch but carry no flag. `forget()` (on load)
  runs `migrateConvoys()`: no `threatinc_swarmIntel` store means a pre-fog save, a new game or a
  fog-off game, so every convoy is flagged as considered.

**No radar (2026-10-02, the user's decision, both sides).** `ThreatSwarmIntel.RADAR`, `RADAR_SITES`,
`inRadar`, `routeHyper` and the Bastion loop in `senses` are gone; `sees(LocationAPI)` answers EYES
for a system the swarm has eyes in (`eyesIn`: a live hive, a Threat front, an unspawned strike
route, or any live Threat fleet there today) and null anywhere else, hyperspace included. `note`
and `record` no longer round. A save's "radar" contacts and places keep the string and age like
any other. The census line reads `(eyes N, scout N)`. Knob `swarmRadarRangeLY` removed; LunaLib
migration 10 (`LunaConfigBridge.drop`) deletes it from a store.

**The hive picket (2026-10-04, the user's decision: "Yeah give same picket don't worry about
changing in flight behaviour otherwise").** hw7a-hw7c showed the swarm first saw 55-64% of sieges
the day they entered the hive system, while the humans saw every strike weeks out from a forward
base (`game-runs.md` 5). The hives now keep the humans' strike picket
(`ThreatFrontlines.detectedAt`): `senses` lists one hyperspace site per system with a live hive
(`PICKET_SITES`; every hive, not Bastion worlds only - in hw7a at month 43 one hive of the home
system's eight was a Bastion), and `sees(where, hyper)` answers EYES in a system with eyes, else
PICKET when `where` is hyperspace and `hyper` is within `swarmPicketLY` of a site (`inPicket`).
`sweepContacts` asks it for an unspawned siege (`routeLocation`, `routeHyper`), a spawned siege
fleet and an order's fleet; figures are exact, as the humans' are. Places and convoys still go
through the one-argument `sees`: no base, world or convoy is seen from hyperspace. A route flies
1,500 units a day (vanilla `RouteLocationCalculator.getTravelDays`), 0.75 ly, so 4 ly is about 5
days of warning before a siege enters the system; a force inside the picket is re-seen daily, so
its contact does not fade. Nothing in flight changes: routes do not meet, blinkered fleets stay
blinkered. The log's first sighting reads `... by picket`. Built, jar 19:18, not game-tested.
A siege's first sighting, by any sense, makes the posture pass due that day (`ThreatPosture.sighted`,
while `postureRecallLY` is above 0), so a strike it calls home turns at once
(`hive-garrison-and-upkeep.md`, "Strikes come home").

**Knobs and logs** as section 3, plus `Swarm intel: seeded N place(s) in <system>` (old-save
seeding) and `Swarm intel: scouting off - seeded N place(s) in M system(s)`. The fast-forward
`reach-digest.ps1` has six "Swarm" rows (sightings, places, census).

**Run pd2a** (2026-10-01, a new game generated by this build, `save_PoseidonDeimos_8594169485512677365`,
15 chunks, about 103 months; fog-off twin pd3a). No exceptions; NaN, Infinity and 3.4E38 never
appear.
- Contacts first sighted 1,229: eyes 1,042, radar 187, scouts none (scouts make places). Of the
  705 attack contacts (hunts, raids, sieges), 71 (10%) were first seen by radar, the rest only on
  arrival in a hive system, 13 of the 15 sieges included. Radar sightings start at month 48, once
  Bastions stand.
- Places: 82 distinct worlds (scout 76, radar 15, eyes 1 on first sight). All 165 strike targets
  had a place before launch (scout 160, radar 5). The census peaked at 64 places, 55 in 30
  systems at the end; stale systems ran 8-16 until radar places arrived at month 91.
- Posture: 31 of 40 moves to THREATENED had seen staging, 13 had a seen attack; every
  transition line ends ", as seen". The swarm never went CONSOLIDATE (EXPAND 72 months, PRESS 31).
- The strike gate passed over 637 times on 50 targets at a median defence 1.62x the strike (h53d:
  104 at 2.4x).
- Found by the run: a place's figures were re-logged about once a day (3,024 lines, the defence
  swinging as fleets came and went); since then a place re-logs at most every `RELOG_DAYS` 30 unless
  its staging target changes (`Place.loggedDay`). The census's "scout" count is always 0, because a
  Scouting Swarm in a system makes later sightings there eyes, which overwrite `Place.source`. The 13
  "charted unseen" systems hold no scout-worthy human world.

**Fog off, pd3a** (the same new game and build, `threatinc_swarmFogOfWar` false; compared at equal
months, since pd2a ran slower per real second). The runs match to about month 36. At month 103,
fog on / off: hives 139 / 205 (size 649 / 873), strikes 157 / 206, landings 65 / 103, human worlds
taken 15 / 32, hives killed by humans 1 / 1. Per hive the strike rate is the same (3.8 per 100
hive-months); most of the gap is a spread stall in pd2a (no outward claim from month 37 to 49), with
equal growth after. One pair cannot separate that from run-to-run noise (about 1.3 sd).
- Where the fog shows: with it off, posture garrisons wherever human stock stands. Mean staged FP
  read was 2.2x, threatened systems 5.3 against 2.2 and want 115k against 61k (months 61-84), and the
  swarm spends its whole income (supplies averaged 54k against 238k). Before a bombing sortie the
  fog-off swarm had already sent 554 FP to the world against 288; the fog-on swarm reacts in transit
  (24 of 52 plays) or not at all.
- The strike gate's ratio is the same (median 1.62 / 1.63) but it passes over more often per strike
  launched with the fog on (4.0 / 2.3 at month 103): a place's `defenceFP` stays as last seen,
  so a world seen while defended stays "defended" until re-seen. That is the design (figures do not
  decay, trust does), noted in case strikes look timid.
- Raiders detach at the same rate per convoy (28% / 26%). Scouting Swarms: 478 launches / 17.
- The humans did no better against the fogged swarm (bombers driven off 48 of 55 / 37 of 51), so
  the council, not the swarm's knowledge, is why the war goes one way (`war-council-runs.md`).
- **The spread stall** (pd2a months 37-49, traced): two gates in turn. First `ThreatPosture.claimCap`:
  the stance went PRESS (the cap halves) with 6-7 claims pending whose waves were held for stock
  (run divergence: pd3a hit it once). Then fuel: `pickSpreadTarget` returned null at
  `ThreatFuel.canPay(FUEL, foundingFuel(...))` for 7 months with 125-1,220 fuel in stock against
  3.7-5.0k for the 23-32 ly candidates left. The fog's part: the re-scout pass sent 478 Scouting
  Swarms (17 with the fog off), 7-9 out at 50-65 ly, about 0.8-3.8k fuel a month, taken on the fast
  poll before the monthly founding could lump. **Fixed 2026-10-02:** the re-scout pass in
  `ThreatSwarmScouts.launchAll` runs only while the stock covers the dearest pending claim's
  founding from that colony (`claimReserve`, `pendingClaims`); first-time scouting is unchanged.
  **pd5a (with the reserve) corrected this:** re-scouts in months 37-48 fell from 80 to 26 and the
  fog was not starved (0 of 232 strikes without a place, places younger), but the claims still
  stalled 11 months (months 39-50). The scouts took under 0.7k fuel a month there; the stock went
  on sector reinforcements (22-27 swarms a month) while blockades cut the fuel made. The stall is a
  generic fuel crunch at that stage (the fog-off pd3a hit the same gate for 6 months), not a fog
  effect; the reserve stays as a margin.
