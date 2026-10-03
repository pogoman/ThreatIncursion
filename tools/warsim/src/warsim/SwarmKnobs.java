package warsim;

/** The swarm side's knobs, read once a run from data/config/settings.json (a missing key throws). */
final class SwarmKnobs {
	final float tickDays, seedToColonyDays, growthBaseDays, fpUnit, upkeepPerMonth, postureMargin, postureBand,
			postureDays, pressRatio, weakOdds, consolidateShare, dwellDays, secondaryShare, nanoChance, surplusMult,
			orbitMargin, breakOff, fuelPerPointLY, upkeepAt3, upkeepRatio, breakEven, starveDays, feedExpand,
			feedPress, feedConsolidate, radarLY, scoutFP, outpostSupplies, outpostFuel, hullShare,
			foundingFPPerStructure, frontlineStrikeWeight;
	final int spreadMinSize, strikeMinSize, maxSize, conquestSize;
	final boolean conquestConverts, scouting, fog, militaryTier, startAtGameStart, stanceEnabled;
	/** ThreatAlarm's grudge, the relief strike and the retaliation (IncursionManager.pickStrikeTarget, retaliate). */
	final float alarmPerStratum, alarmPerEradication, alarmDecayPer30, alarmTargetMult, reinforceWeight;
	final boolean alarm, reliefFirst, retaliation;

	SwarmKnobs(Knobs k) {
		tickDays = k.f("threatinc_tickDays");
		seedToColonyDays = k.f("threatinc_seedToColonyDays");
		growthBaseDays = k.f("threatinc_colonyGrowthBaseDays");
		fpUnit = k.f("threatinc_fabFPPerShipUnit");
		upkeepPerMonth = k.f("threatinc_garrisonUpkeepPerMonth");
		postureMargin = Math.max(0f, k.f("threatinc_postureMargin"));
		postureBand = Math.max(0f, k.f("threatinc_postureBand"));
		postureDays = k.f("threatinc_postureDays");
		pressRatio = k.f("threatinc_stancePressRatio");
		weakOdds = k.f("threatinc_stanceWeakOdds");
		consolidateShare = k.f("threatinc_stanceConsolidateShare");
		dwellDays = k.f("threatinc_stanceDwellDays");
		secondaryShare = k.f("threatinc_stanceSecondaryShare");
		nanoChance = k.f("threatinc_forgeNanoforgeChance");
		// the hive's own rate since 2026-10-02 (round 18: the shared knob at 1.5 fed the swarm more than the humans)
		surplusMult = k.f("threatinc_hiveSurplusMult", 1f);
		float margin = k.f("threatinc_npcSiegeOrbitMargin");
		orbitMargin = margin > 0f ? margin : 1f;
		float ratio = k.f("threatinc_siegeBreakOffRatio");
		breakOff = ratio > 0f ? ratio : 1f;
		fuelPerPointLY = k.f("threatinc_expeditionFuelPerPointLY");
		upkeepAt3 = k.f("threatinc_sizeUpkeepAt3");
		upkeepRatio = k.f("threatinc_sizeUpkeepRatio");
		breakEven = threatinc.rules.HiveRules.breakEven(k.f("threatinc_upkeepBreakEven"));
		starveDays = Math.max(1f, k.f("threatinc_starveDaysPerSize"));
		feedExpand = k.f("threatinc_feedShareExpand");
		feedPress = k.f("threatinc_feedSharePress");
		feedConsolidate = k.f("threatinc_feedShareConsolidate");
		// the mod's swarmRadarRangeLY is gone (262ac76, no radar); the simulator's own switch restores the old sight for comparison
		radarLY = k.f("warsim_swarmRadarLY", 0f);
		scoutFP = k.f("threatinc_swarmScoutFleetPoints");
		outpostSupplies = k.f("threatinc_outpostSupplies");
		seedPriceMult = k.f("warsim_seedPriceMult", 1f);
		outpostFuel = k.f("threatinc_outpostFuel");
		hullShare = k.f("threatinc_returnHullShare");
		foundingFPPerStructure = k.f("threatinc_foundingFPPerStructure");
		frontlineStrikeWeight = k.f("threatinc_frontlineStrikeWeight");
		spreadMinSize = k.i("threatinc_spreadMinSize");
		strikeMinSize = k.i("threatinc_strikeMinSize");
		maxSize = k.i("threatinc_colonyMaxSize");
		conquestSize = k.i("threatinc_conquestHiveSize");
		conquestConverts = k.b("threatinc_conquestConverts", true);
		scouting = k.b("threatinc_swarmScouting", true);
		fog = k.b("threatinc_swarmFogOfWar", true);
		militaryTier = k.b("threatinc_hiveMilitaryTier", true);
		startAtGameStart = k.b("threatinc_startAtGameStart", true);
		stanceEnabled = k.b("threatinc_stanceEnabled", true);
		alarm = k.b("threatinc_alarmEnabled", true);
		alarmPerStratum = k.f("threatinc_alarmPerStratum");
		alarmPerEradication = k.f("threatinc_alarmPerEradication");
		alarmDecayPer30 = k.f("threatinc_alarmDecayPer30");
		alarmTargetMult = k.f("threatinc_alarmTargetMult");
		reinforceWeight = k.f("threatinc_strikeReinforceWeight");
		reliefFirst = k.b("threatinc_strikeReliefFirst", true);
		retaliation = k.b("threatinc_retaliationEnabled", true);
		basesHold = k.b("warsim_basesHold", false);
		coloniesFall = k.b("warsim_coloniesFall", false);
		consolidateExpansion = Math.max(0f, Math.min(1f, k.f("warsim_consolidateExpansionShare", 0f)));
		strikeSizedMargin = Math.max(0f, k.f("warsim_strikeSizedMargin", 0f));
		sustainShare = Math.max(0f, k.f("warsim_sustainShare", 0f));
		scoutsAnySize = k.b("warsim_scoutsAnySize", false);
		homeWorlds = Math.max(3, k.i("threatinc_homeWorlds"));
		homeMinesOnly = k.b("warsim_homeMinesOnly", false);
		wholeMuster = k.b("warsim_strikeWholeMuster", true);
		landingRace = k.b("warsim_landingRace", false);
		pathNeverMobilises = k.b("warsim_pathNeverMobilises", false);
		postureLoop = k.b("warsim_postureLoop", true);
		strikeDefends = k.b("warsim_strikeDefends", true);
		guardSwarmsPerFleet = k.f("warsim_guardSwarmsPerFleet", 1.42f);
		guardFeedsFront = k.b("warsim_guardFeedsFront", true);
		fabricate = k.b("threatinc_fabricateEnabled", true);
		fabricateTroopsPerFP = Math.max(0.01f, k.f("threatinc_fabricateTroopsPerFP", 10f));
		fabricateHoldMargin = Math.max(1f, k.f("threatinc_fabricateHoldMargin", 1.05f));
		guardFirstPackMult = k.f("warsim_guardFirstPackMult", 1f);
		strikeWaitBooksFuel = k.b("threatinc_strikeWaitBooksFuel", true);
		investFuelWhenTight = k.b("threatinc_investFuelWhenTight", true);
		investFuelMinPlants = k.i("threatinc_investFuelMinPlants");
		strikeWaitBooksWhole = k.b("warsim_strikeWaitBooksWhole", false);
		holdsBookMonthly = k.b("warsim_holdsBookMonthly", true);
	}

	/**
	 * Round 25: warsim_postureLoop (true) - the posture's transfers as the game runs them
	 * (SwarmPosture.redistributeLoop: every day, any deficit, one swarm at a time until nothing goes); false is the
	 * pass every postureDays that sends each receiver the donor's largest swarm.
	 * warsim_strikeDefends (true since 2026-10-02) - a strike's fleet stays over its landing until the front ends
	 * (ThreatSwarmDefend; an unspawned strike leaves its first fleet there too, ThreatStrikeFGI.guardUnspawned - the
	 * user: every faction guards its siege on screen or off); false sends it home after the landing pass, as every
	 * unspawned strike did before.
	 */
	final boolean postureLoop, strikeDefends;

	/**
	 * The guard a landing strike leaves is its first fleet (ThreatStrikeFGI.guardUnspawned): a strike of n swarms flies as
	 * round(n / warsim_guardSwarmsPerFleet) fleets, and the first, the largest, is warsim_guardFirstPackMult times the
	 * even share. warsim_guardSwarmsPerFleet 0 parks the whole strike.
	 */
	final float guardSwarmsPerFleet, guardFirstPackMult;

	/**
	 * warsim_guardFeedsFront (true) - a guarded landing on a colony at war fights the front engine (SwarmOps.threatFrontDay)
	 * and its guard breaks hulls into troops while the front cannot hold (SwarmOps.feed, ThreatGroundFronts.fabricateTroops:
	 * threatinc_fabricateTroopsPerFP troops a point, up to the hold line x threatinc_fabricateHoldMargin). Run hw4d's guards
	 * turned 4,153 FP into 41,718 troops and the fronts outlasted the defenders' marines; the overrun clock cannot. false: the
	 * clock for every landing, as before.
	 */
	final boolean guardFeedsFront, fabricate;
	/** threatinc_investFuelWhenTight: idle supplies build a fuel plant while fuel is tight (SwarmEconomy.choose). */
	final boolean investFuelWhenTight;
	/** threatinc_investFuelMinPlants: the fuel plants the hive needs before investFuelWhenTight applies. */
	final int investFuelMinPlants;
	final float fabricateTroopsPerFP, fabricateHoldMargin;

	/**
	 * Round 26: warsim_holdsBookMonthly (true) - a held send books its unpaid bill as demand on the stock once a
	 * SHORT_DAYS a source (SwarmEconomy.bookHold, the game's ThreatFuel.bookHold), and a held reinforcement books its
	 * passage, and a muster no world's passage is paid for books nothing (IncursionManager.pickStrikeTarget skips the
	 * world, no held() follows); false books every held poll of a strike or a Seeding Swarm, the waiting muster's
	 * passage among them, and no reinforcement.
	 */
	final boolean holdsBookMonthly;

	/**
	 * threatinc_strikeWaitBooksFuel (true, 2026-10-02): a muster every world it would strike of which waits on fuel books
	 * the cheapest passage as demand, once a SHORT_DAYS a source (IncursionManager.pickStrikeTarget, ThreatFuel.held).
	 */
	final boolean strikeWaitBooksFuel;
	/** warsim_strikeWaitBooksWhole (false): book the whole cheapest passage, as run hw4d's jar did; false books what the stock is short of it (ThreatFuel.heldShort). */
	final boolean strikeWaitBooksWhole;

	/**
	 * Round 24 (2026-10-02 night), three places the simulator's swarm was not the game's, each with its old behaviour
	 * behind a switch (docs/war-sim-calibration.md 3):
	 * warsim_strikeWholeMuster (true) - a strike is the whole muster the spare supplies keep away, weighed at the gate
	 * as that, and a world whose passage the fuel does not pay for that muster is no candidate
	 * (IncursionManager.pickStrikeTarget); false sends the largest part of the muster the fuel pays, gated as the whole.
	 * warsim_landingRace (false) - true decides a landing's fate before the struck faction has mobilised, so the first
	 * colony the swarm invades of every faction falls on the pirates' clock (the bug: SwarmOps.fronts ran a day early).
	 * warsim_pathNeverMobilises (false) - true takes the Path with the pirates as a faction that never mobilises
	 * (struck before phase 3, its worlds falling on the clock); the game excludes threatinc_warExcludedFactions alone.
	 */
	final boolean wholeMuster, landingRace, pathNeverMobilises;

	/**
	 * threatinc_homeWorlds (ThreatIncConfig.homeWorlds, round 22): the worlds the opening chain lands on, in a home
	 * system with at least that many planets (SwarmOps.pickOG, homeChain). Five land pd9a's chain: forge, fuel plant,
	 * refining, two mines. Four land forge, refining and two mines; three a forge and mines - the refinery and the fuel
	 * plant then wait for a second industry slot at size 4 (SwarmEconomy.choose), as in tr1a, whose swarm made no fuel
	 * for 16 months.
	 */
	final int homeWorlds;
	/** warsim_homeMinesOnly: every opening world but the forge lands a mine (a home whose best deposits sit on separate planets). */
	final boolean homeMinesOnly;

	/** warsim_scoutsAnySize: scouts sail from a hive of any size with a nexus, as the simulator had it before round 20 (the mod asks strikeMinSize). */
	final boolean scoutsAnySize;

	/**
	 * warsim_sustainShare (round 20): the share of the supplies' net that sustenance may take whatever the stance
	 * (ThreatColonyUpkeep.sustainShare), set apart from the stance shares it is the largest of in the mod; 0 = as the mod.
	 * Found because threatinc_feedShareConsolidate 0.9 -> 0.7 moved the mid-war start, where the swarm spends no month
	 * in CONSOLIDATE: the knob is also the sustain cap.
	 */
	final float sustainShare;

	/**
	 * Round-20 rule trials for the swarm (2026-10-02), off at 0, no mod symbol behind either:
	 * warsim_consolidateExpansionShare - a consolidating swarm keeps claiming systems at this share of its claim cap
	 * (StanceRules.expansionShare gives CONSOLIDATE 0), leaning away from the strongest rival as EXPAND does
	 * (SwarmOps.trySpread); warsim_strikeSizedMargin - a strike takes only the swarms the defence last seen calls for
	 * x this margin, at least two, and the rest stay for the next strike or a Seeding Swarm (SwarmOps.strikeFrom
	 * sends every spare swarm).
	 */
	final float consolidateExpansion, strikeSizedMargin;

	/**
	 * Round-8 hypotheses (2026-10-02), off by default, no mod symbol behind either: warsim_basesHold - a forward
	 * base's station is not destroyed by the strike that sinks its guard, it falls to a station siege
	 * (SwarmOps.stationSiegeDay); warsim_coloniesFall - a Threat landing on a colony at war fights the front engine
	 * (SwarmOps.threatFrontDay) instead of the overrun clock.
	 */
	final boolean basesHold, coloniesFall;

	/** Supplies a founding takes from the stock (ThreatFuel.foundingCost()[0]). */
	/** warsim_seedPriceMult: an experiment's multiplier on the Seeding Swarm's price (1 = the mod's 5,000 supplies). */
	float seedPriceMult = 1f;

	float foundSupplies() { return (outpostSupplies + SwarmFit.FOUNDING_KIT) * seedPriceMult; }

	float sizeUpkeep(int size) { return threatinc.rules.HiveRules.sizeUpkeepPerMonth(size, upkeepAt3, upkeepRatio); }

	float passage(float fp, float ly, boolean roundTrip) {
		return threatinc.rules.SpreadRules.passageFuel(fp, ly, roundTrip, SwarmFit.FP_PER_POINT, fuelPerPointLY,
				SwarmFit.RETURN_LEG_SHARE);
	}

	float days(float ly) { return threatinc.rules.SpreadRules.days(ly, State.LY_PER_DAY); }

	float strikeDays(float ly) {
		return threatinc.rules.SpreadRules.daysAway(ly, true, SwarmFit.STRIKE_PREP_DAYS, State.LY_PER_DAY);
	}
}
