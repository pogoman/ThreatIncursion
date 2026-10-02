package warsim;

/** What a human parcel is for: the human side's record on Parcel.order. */
final class HumanOrder {
	/** The base it sailed from and settles at. */
	World home;
	/** The hive a siege or a raid is for. */
	Hive target;
	/** A muster's day to sail, and what it sails as. */
	int sailDay;
	Parcel.Kind sailAs;
	/** The trust the planner launched it on (PlannerRules.trust). */
	float trust;
	boolean returning;
	/** Days in orbit, days fought, the day it arrived. */
	int orbitDays, fights, arrivedDay = -1;
	/** The hulls' supplies deposit, refunded by health (ThreatReturns.settle). */
	float deposit;
	/** Upkeep unpaid so far (ThreatUpkeep): a month of it owed and the force stands down. */
	float owed;
	/** For a relief force: the forward base it garrisons. */
	World guards;
	/** The war council's play that sent it (HumanCouncil.Play), or null. */
	Object play;
	/** A raid the swarms drove off (ThreatFleetOrders.endRaid: orbit contested, or called off). */
	boolean drivenOff;
	/** A raid's days over its world when not RAID_STAY_DAYS (a play's squadron stays its check). */
	int stayDays;
}
