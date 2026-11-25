package com.togoptimizer;

import java.util.EnumSet;
import net.runelite.api.Skill;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BowlRewardTest
{
	private final BowlReward.RoomBowl bowl = new BowlReward.RoomBowl();
	private final RewardPreview fishing = RewardPreview.select(
		skill -> skill == Skill.FISHING ? 0 : 1, EnumSet.noneOf(Skill.class), false);

	@Test
	public void keepsTheFinalCountIncludingGreenLossesAndALateChange()
	{
		bowl.sample(100, 40, true);
		bowl.sample(95, 41, true);
		bowl.changed(91, 42);
		bowl.changed(0, 43);
		bowl.sample(0, 43, false);
		assertEquals(91, bowl.finalCount(0, 45));
		assertEquals(89, bowl.finalCount(89, 45));
	}

	@Test
	public void lossOfTheLastTearDuringPlayIsRecordedAsAnEmptyBowl()
	{
		bowl.sample(1, 50, true);
		bowl.changed(0, 51);
		bowl.sample(0, 51, true);
		bowl.sample(0, 52, false);
		assertEquals(0, bowl.finalCount(0, 54));
	}

	@Test
	public void anAmbiguousLastTearFallsBackToTheRate()
	{
		bowl.sample(1, 50, true);
		bowl.sample(0, 51, false);
		assertEquals(-1, bowl.finalCount(0, 52));
		assertEquals(1, bowl.finalCount(1, 52));
	}

	@Test
	public void rejectsExpiredSamplesAndUpdatesOutsideTheExitWindow()
	{
		bowl.sample(50, 100, true);
		bowl.changed(51, 102);
		bowl.changed(999, 103);
		assertEquals(51, bowl.finalCount(0, 115));
		assertEquals(-1, bowl.finalCount(0, 116));
		assertEquals(-1, bowl.finalCount(0, 99));
	}

	@Test
	public void logoutClearsTheSampleEvenWhenTickNumbersOverlap()
	{
		bowl.sample(175, 23, true);
		bowl.reset();
		bowl.changed(176, 24);
		bowl.sample(0, 25, false);
		assertEquals(-1, bowl.finalCount(0, 26));
		bowl.sample(0, 27, true);
		assertEquals(0, bowl.finalCount(0, 28));
	}

	@Test
	public void aRequestedOrCurrentCountTakesPrecedenceOverLastWeeksBowl()
	{
		assertEquals("~1,200 Fishing XP (120 tears requested)", BowlReward.estimateFor(fishing, 120, 1, 99));
		assertEquals("~0 Fishing XP (0 tears requested)", BowlReward.estimateFor(fishing, 0, 1, 99));
		assertEquals("~10 Fishing XP (1 tear in your bowl)", BowlReward.estimateFor(fishing, null, 1, 99));
		assertEquals("~0 Fishing XP (0 tears in your bowl)", BowlReward.estimateFor(fishing, null, 0, 99));
	}

	@Test
	public void beforeEnteringShowsLastBowlOrTheRateIfTheBowlIsUnknown()
	{
		assertEquals("~990 Fishing XP (99 tears to match last game)", BowlReward.estimateFor(fishing, null, null, 99));
		assertEquals("~0 Fishing XP (0 tears to match last game)", BowlReward.estimateFor(fishing, null, null, 0));
		assertEquals("10 Fishing XP/tear", BowlReward.estimateFor(fishing, null, null, null));
		assertEquals("10 Fishing XP/tear", BowlReward.estimateFor(fishing, null, null, -1));
	}
}
