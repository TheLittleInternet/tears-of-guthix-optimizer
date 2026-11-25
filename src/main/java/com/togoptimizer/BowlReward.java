package com.togoptimizer;

import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/** Samples the player's own bowl, independently of the optimizer's simulated games. */
final class BowlReward
{
	private static final String LAST_BOWL = "lastRewardTears";
	private final Client client;
	private final ConfigManager configManager;
	private final RoomBowl bowl = new RoomBowl();
	private int nextSkillCheck;
	@Nullable
	private RewardPreview skill;

	@Inject
	BowlReward(Client client, ConfigManager configManager)
	{
		this.client = client;
		this.configManager = configManager;
	}

	void reset()
	{
		bowl.reset();
		nextSkillCheck = 0;
		skill = null;
	}

	void sampleRoom()
	{
		bowl.sample(client.getVarbitValue(VarbitID.TOG_TEARS_COLLECTED), client.getTickCount(),
			client.getVarbitValue(VarbitID.TOG_COUNTDOWN) > 0);
	}

	void tearsChanged(int value)
	{
		bowl.changed(value, client.getTickCount());
	}

	void drank()
	{
		int count = bowl.finalCount(client.getVarbitValue(VarbitID.TOG_TEARS_COLLECTED), client.getTickCount());
		if (count >= 0)
		{
			configManager.setRSProfileConfiguration(TogOptimizerConfig.GROUP, LAST_BOWL, count);
		}
		else
		{
			configManager.unsetRSProfileConfiguration(TogOptimizerConfig.GROUP, LAST_BOWL);
		}
		reset();
	}

	@Nullable
	String estimate(boolean inRoom, @Nullable Integer requestedCount, boolean refresh)
	{
		Estimate result = projection(inRoom, requestedCount, refresh);
		return result == null ? null : result.line();
	}

	/** Read on the client thread. The returned estimate can also be used by Swing. */
	@Nullable
	Estimate projection(boolean inRoom, @Nullable Integer requestedCount, boolean refresh)
	{
		int tick = client.getTickCount();
		if (refresh || tick >= nextSkillCheck)
		{
			skill = RewardPreview.read(client);
			nextSkillCheck = tick + 10;
		}
		if (skill == null)
		{
			return null;
		}
		Integer current = requestedCount == null && inRoom ? client.getVarbitValue(VarbitID.TOG_TEARS_COLLECTED) : null;
		Integer previous = requestedCount == null && !inRoom
			? configManager.getRSProfileConfiguration(TogOptimizerConfig.GROUP, LAST_BOWL, Integer.class) : null;
		return estimateFor(skill, requestedCount, current, previous);
	}

	static Estimate estimateFor(RewardPreview skill, @Nullable Integer requested, @Nullable Integer current,
		@Nullable Integer previous)
	{
		if (requested != null)
		{
			return new Estimate(skill, Basis.CUSTOM, requested);
		}
		if (current != null)
		{
			return new Estimate(skill, Basis.CURRENT, current);
		}
		if (previous != null && previous >= 0)
		{
			return new Estimate(skill, Basis.LAST, previous);
		}
		return new Estimate(skill, Basis.RATE, null);
	}

	enum Basis
	{
		CURRENT("in your bowl"),
		LAST("to match last game"),
		CUSTOM("requested"),
		RATE("");

		private final String description;

		Basis(String description)
		{
			this.description = description;
		}
	}

	static final class Estimate
	{
		private final RewardPreview reward;
		final Skill skill;
		final String perTear;
		final Basis basis;
		@Nullable
		final Integer tears;
		@Nullable
		final Integer xp;

		private Estimate(RewardPreview reward, Basis basis, @Nullable Integer tears)
		{
			this.reward = reward;
			this.skill = reward.skill;
			this.perTear = reward.perTear();
			this.basis = basis;
			this.tears = tears;
			this.xp = tears == null ? null : reward.xpFor(tears);
		}

		Estimate forCount(int count)
		{
			return new Estimate(reward, Basis.CUSTOM, count);
		}

		String line()
		{
			if (tears == null)
			{
				return perTear + " " + skill.getName() + " XP/tear";
			}
			return String.format(Locale.ENGLISH, "~%,d %s XP (%,d %s %s)", xp,
				skill.getName(), tears, tears == 1 ? "tear" : "tears", basis.description);
		}
	}

	/** Keeps the final visible count until the local player's drinking animation. */
	static final class RoomBowl
	{
		private int tears = -1;
		private int roomTick = -1;

		void reset()
		{
			tears = -1;
			roomTick = -1;
		}

		void sample(int value, int tick, boolean timerRunning)
		{
			// Zero during play is a real empty bowl. Once time runs out, the game clears the counter.
			if (value > 0 || timerRunning)
			{
				tears = value;
				roomTick = tick;
			}
		}

		void changed(int value, int tick)
		{
			// The last increment or loss may be delivered while the player is leaving the room.
			if (value > 0 && sampledWithin(tick, 2))
			{
				tears = value;
			}
		}

		int finalCount(int live, int tick)
		{
			if (live > 0)
			{
				return live;
			}
			// A last observed 1 followed only by the end-of-game zero could also be a final green loss.
			// Leave the count unknown so next week's estimate falls back to XP per tear.
			return sampledWithin(tick, 15) && tears != 1 ? tears : -1;
		}

		private boolean sampledWithin(int tick, int limit)
		{
			return roomTick >= 0 && tick >= roomTick && tick - roomTick <= limit;
		}
	}
}
