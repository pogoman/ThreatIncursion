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
		ogChain = Math.max(0, (int) k.f("warsim_ogChain", 0f));
	}

	/**
	 * warsim_ogChain (round 21): how many colonies the opening chain lands, in a home system with no planet to spare
	 * (ThreatColonyManager.canSupportFullChain asks three colonisable planets, and each landing's free build is Mining
	 * wherever there are deposits). 0 = the five-world chain of pd9a: forge, fuel plant, refining, two mines. Four
	 * land forge, refining and two mines; three or fewer a forge and mines - the refinery and the fuel plant then wait
	 * for a second industry slot at size 4 (SwarmEconomy.choose), as in tr1a, whose swarm made no fuel for 16 months.
	 */
	final int ogChain;

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
