# Nexerelin compatibility

Built 2026-09-23; checks 1 and 3 below **verified in-game** the same day on a clone of a
live save with Nexerelin added mid-game (2, 4 and 5 not run).

Nexerelin is optional: nothing links against `ExerelinCore.jar`, the build
does not need it, and every call into it is reflection behind `isModEnabled("nexerelin")`.
A Nexerelin API change can only switch the in-flight part off (logged), never break loading.

Nexerelin's jar ships its own `.java` sources - extract it to read them.

## 1. Who owns the military menu

Nexerelin replaces vanilla's whole raid / bombard / invade flow with `Nex_MarketCMD`, on
vanilla's option ids at `score:2`. Our `ThreatincMarketCMD` overrides eight of the same ids
at `score:1000` (`marketConsiderHostile`, `mktEngage`, `mktBombard*`, `mktRaidGoBack`,
`mktBombardGoBack`). Unconditioned, ours won on every market and the player lost
Nexerelin's Invade option everywhere.

Each of those rules now carries `!ThreatincNexCMD yields`:

- **Hive (Threat) market:** ours, as before. Nexerelin's rules still run the options we do
  not override (raid menu, bombard result, never-mind) - the same mix as before this change.
- **Any other market, Nexerelin loaded:** Nexerelin's menu, Invade included. The player's
  tactical bombardment of a human colony is then Nexerelin's (vanilla-shaped), not our
  siege slice.
- **Nexerelin not loaded:** unchanged.

## 2. Besieged colonies are not invaded

"Besieged" (`ThreatNexCompat.besieged`): a non-Threat market with a ground front on it, or
carrying `$threatinc_besieged` (orbit contested within `wearDays`).

Nexerelin's AI never targets a hive: `NexUtilsMarket.shouldTargetForInvasions` rejects any
market whose faction is not `playableFaction`, and Nexerelin's `threat.json` says false.
Threat is also never an invader (`processInvasionPoints` skips non-playable factions). So only
human colonies need covering.

| What | How | Where |
| --- | --- | --- |
| New NPC invasions, raids, sat-bomb strikes, rebellions | `$nex_npc_no_invade` on the market while besieged; cleared when the siege ends. A flag someone else set is left alone (`$threatinc_nexNoInvade` marks ours). | `ThreatNexCompat.poll`, from the colony poll |
| Invasion fleets already en route | Nexerelin checks the flag only at target pick. `InvasionIntel.terminateEvent(OTHER)` on any targeting a besieged market. | same |
| AI ground battles already on the surface | `GroundBattleIntel.endBattle(CANCELLED)` - Nexerelin's own no-transfer ending. A battle the player started or joined is left to run. | same |
| Player's Invade option | Greyed from `NexPostShowDefenses` (the last step of Nexerelin's menu build, so state after shape) with the tooltip "The swarm is besieging X." | rules.csv `threatinc_nexBlockInvade` |

**Not `$nex_uninvadable`.** It would also block the player's Invade, but
`SectorManager.recheckLiveFactions` skips flagged markets - a faction with every world
besieged would count as eliminated.

## 3. What Nexerelin still does unchecked

- Peaceful handovers ignore both flags: `TransferMarketAction` (strategic AI),
  `RequestMarketIntel`, `Nex_TransferMarket`, `ConquestMissionIntel`. A besieged colony
  changing owner this way keeps its front - the front's owner is the swarm and the theatre
  is re-read from the market, which stays COLONY. Only a flip to or from Threat would
  corrupt a front, and Nexerelin has no path that does that.
- A ground battle the player is in keeps running on a besieged world.
- `GroundBattleIntel.endBattle` calls `setDisrupted(0)` on battle industries that are not
  truly disrupted - a ghost expire; our siege reads are gated on `isDisrupted` already.
- Nexerelin replaces the fleet encounter dialog for all fleets (`MOD_GENERAL`); ours picks
  only our outpost stations. Not expected to clash, not checked.

## 3b. Diplomacy: the swarm stays outside it

Nexerelin's diplomacy, alliances, strategic AI, agents, victory score, respawn and the faction
directory all iterate `SectorManager.liveFactionIds`. Threat never enters it, however many hives it
owns: the only writers (`reinitLiveFactions`, `factionRespawned`) return for a faction whose
config has `playableFaction` false, which Nexerelin's `threat.json` sets. So there are no peace
treaties or ceasefires with it, no alliances, no agent actions, no directory entry or commission.
It doesn't count toward victory, and there's no "eliminated" message when the last hive dies
(`recheckLiveFactions` only eliminates factions that were live).
`hostileToAll: 2` sets -0.6 through `setRelationshipAtBest`, which only lowers - our -1 pin
(`enforceThreatHostility`) is below it, so the two never fight. Nothing to build.

Between the other factions Nexerelin's diplomacy does move relations (wars, peace, alliances),
and our ally aid (by standing) reads those relations as they stand - not checked in game.

## 4. Verify in-game

1. **Verified.** Nexerelin on, hostile-options menu at a human colony (Chicomoztoc): Nexerelin's
   menu, "Invade Chicomoztoc" present.
2. Not run (no hive in reach of the test fleet): at a hive, our menu with Ground operations, no Invade.
3. **Verified.** Chicomoztoc given `$threatinc_besieged` for 2 days in the clone: Invade greyed
   with "The swarm is besieging Chicomoztoc."; once the flag ran out, Invade showed Nexerelin's
   own reason instead (star fortress). Log on the first poll: "Chicomoztoc is besieged - closed
   to invasion", "Nachiketa is besieged - closed to invasion" (a real swarm front), then
   "Chicomoztoc is no longer besieged - open to invasion". No exceptions.
4. Not run (needs an invasion in flight): launch a Nexerelin invasion at a colony, then start a
   swarm siege there - the invasion intel ends, the log shows "calling off an invasion".
5. Not run on this build: Nexerelin off, the mod loads and every menu is as before (every
   Nexerelin path is behind `isModEnabled`, and `!ThreatincNexCMD yields` is true without it).

The compat log lines (`[ThreatInc] Nexerelin compat:`) are always on, unlike the rest of the
mod's logging, so a Nexerelin player's `starsector.log` shows them without Verbose Logging.
Nexerelin added to an existing save opens a one-page options dialog first ("4" = Done).
