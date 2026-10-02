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
		surplusMult = k.f("threatinc_reserveSurplusMult");
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
		radarLY = k.f("threatinc_swarmRadarRangeLY");
		scoutFP = k.f("threatinc_swarmScoutFleetPoints");
		outpostSupplies = k.f("threatinc_outpostSupplies");
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
	}

	/** Supplies a founding takes from the stock (ThreatFuel.foundingCost()[0]). */
	float foundSupplies() { return outpostSupplies + SwarmFit.FOUNDING_KIT; }

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
