package com.togoptimizer;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/** Tracks visits by RuneScape profile, including when the player is away from Juna. */
final class WeeklyReminder
{
	private static final String GROUP = TogOptimizerConfig.GROUP;
	// These first two keys also hold visits saved by earlier versions of the optimizer.
	private static final String UNLOCK = "unlockAt";
	private static final String XP = "xpAtLastGame";
	private static final String QP = "qpNeededToReturn";
	private static final String CONFIRMED = "confirmedUnlock";
	private static final String NOTICE = "lastReadyNoticeDay";
	private static final String READY = "You can play Tears of Guthix now.";
	private static final String USAGE = "Use ::togstatus, ::togstatus sync DAYS XP (both remaining in your quest journal), or ::togstatus last YYYY-MM-DD (UTC).";

	private final Client client;
	private final ConfigManager config;
	private String profile;
	private Long unlock;
	private Long xpBaseline;
	private Integer qpTarget;
	private boolean confirmed;
	private Long lastNotice;
	private boolean questComplete;
	private int questCheckTick;
	private int sampleXpTick;
	private boolean readyChat;

	@Inject
	WeeklyReminder(Client client, ConfigManager config)
	{
		this.client = client;
		this.config = config;
		resetSession();
	}

	void resetSession()
	{
		profile = null;
		unlock = null;
		xpBaseline = null;
		qpTarget = null;
		confirmed = false;
		lastNotice = null;
		questComplete = false;
		questCheckTick = -1;
		sampleXpTick = -1;
		readyChat = false;
	}

	private boolean loadProfile()
	{
		String current = config.getRSProfileKey();
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null || current == null)
		{
			return false;
		}
		if (!current.equals(profile))
		{
			// A login message can arrive before the first game tick with a profile.
			boolean loginMessage = profile == null && readyChat;
			resetSession();
			readyChat = loginMessage;
			profile = current;
			unlock = config.getRSProfileConfiguration(GROUP, UNLOCK, Long.class);
			xpBaseline = config.getRSProfileConfiguration(GROUP, XP, Long.class);
			qpTarget = config.getRSProfileConfiguration(GROUP, QP, Integer.class);
			confirmed = unlock != null && unlock.equals(config.getRSProfileConfiguration(GROUP, CONFIRMED, Long.class));
			lastNotice = config.getRSProfileConfiguration(GROUP, NOTICE, Long.class);
		}
		return true;
	}

	private boolean normalMembersWorld()
	{
		return client.getWorldType().contains(WorldType.MEMBERS) && !RewardPreview.specialWorld(client);
	}

	void onChat(ChatMessage event)
	{
		if ((event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
			&& Text.removeTags(event.getMessage()).replace('\u00a0', ' ').trim()
				.startsWith("You are eligible to drink from the Tears of Guthix") && normalMembersWorld())
		{
			// If a new character is already identifiable, drop the previous character's transient state first.
			loadProfile();
			readyChat = true;
		}
	}

	/** Called on every game tick, including when notifications are disabled. */
	@Nullable
	String check(boolean notifyEnabled)
	{
		if (!loadProfile() || (!readyChat && sampleXpTick < 0 && (!notifyEnabled || unlock == null)))
		{
			return null;
		}
		if (!normalMembersWorld() || client.getOverallExperience() <= 0)
		{
			return null;
		}
		long now = System.currentTimeMillis();
		long today = now - Math.floorMod(now, 86_400_000L);
		if (readyChat)
		{
			if (!confirmed || unlock == null || unlock > now)
			{
				saveVisit(unlock == null || unlock > now ? today : unlock, null, null, true);
			}
			readyChat = false;
			questComplete = true;
		}
		int tick = client.getTickCount();
		if (sampleXpTick >= 0 && tick >= sampleXpTick)
		{
			xpBaseline = client.getOverallExperience();
			config.setRSProfileConfiguration(GROUP, XP, xpBaseline);
			sampleXpTick = -1;
		}
		if (!notifyEnabled || unlock == null || unlock > now || (lastNotice != null && lastNotice == today))
		{
			return null;
		}
		if ((!confirmed && !Eligibility.progressMet(requiredQuestPoints(), questPoints(), client.getOverallExperience(), xpBaseline))
			|| !questFinished())
		{
			return null;
		}
		lastNotice = today;
		config.setRSProfileConfiguration(GROUP, NOTICE, today);
		return READY;
	}

	private boolean questFinished()
	{
		int tick = client.getTickCount();
		if (!questComplete && tick >= questCheckTick)
		{
			questComplete = Quest.TEARS_OF_GUTHIX.getState(client) == QuestState.FINISHED;
			questCheckTick = tick + 10;
		}
		return questComplete;
	}

	void gameEnded()
	{
		if (loadProfile() && normalMembersWorld())
		{
			saveVisit(Eligibility.unlockAfterGame(System.currentTimeMillis()), null, questPoints() + 1, false);
			// The reward XP is applied after the drinking animation starts.
			sampleXpTick = client.getTickCount() + 3;
		}
	}

	void waitDays(int days)
	{
		if (days >= 0 && days <= Eligibility.DAYS && loadProfile() && normalMembersWorld())
		{
			saveDate(Eligibility.unlockInDays(days, System.currentTimeMillis()));
		}
	}

	private void saveDate(long date)
	{
		if (unlock == null || unlock != date)
		{
			// A different return date can reveal a visit made on another client.
			saveVisit(date, null, null, false);
		}
	}

	private void saveVisit(long date, @Nullable Long xp, @Nullable Integer qp, boolean progressConfirmed)
	{
		unlock = date;
		xpBaseline = xp;
		qpTarget = qp;
		confirmed = progressConfirmed;
		sampleXpTick = -1;
		readyChat = false;
		config.setRSProfileConfiguration(GROUP, UNLOCK, date);
		writeOptional(XP, xp);
		writeOptional(QP, qp);
		writeOptional(CONFIRMED, progressConfirmed ? date : null);
	}

	private void writeOptional(String key, @Nullable Number value)
	{
		if (value == null)
		{
			config.unsetRSProfileConfiguration(GROUP, key);
		}
		else
		{
			config.setRSProfileConfiguration(GROUP, key, value);
		}
	}

	private int questPoints()
	{
		return client.getVarpValue(VarPlayerID.QP);
	}

	private int requiredQuestPoints()
	{
		return qpTarget == null ? client.getVarbitValue(VarbitID.TOG_QP_BEFORE_RETURN) : qpTarget;
	}

	@Nullable
	Long nextUnlock()
	{
		return unlock != null && unlock > System.currentTimeMillis() ? unlock : null;
	}

	boolean requirementCertain()
	{
		return xpBaseline != null && client.getOverallExperience() >= xpBaseline && requiredQuestPoints() > 0;
	}

	@Nullable
	String progressRequirement()
	{
		if (confirmed)
		{
			return null;
		}
		return Eligibility.trackedRequirement(unlock != null, requiredQuestPoints(), questPoints(), client.getOverallExperience(), xpBaseline);
	}

	@Nullable
	String status()
	{
		if (!loadProfile())
		{
			return null;
		}
		if (!normalMembersWorld())
		{
			return "Weekly reminders are available on regular members' worlds.";
		}
		if (client.getOverallExperience() <= 0)
		{
			return "Your character is loading. Try again in a moment.";
		}
		if (!questFinished())
		{
			return "Complete the Tears of Guthix quest to play.";
		}
		if (unlock == null)
		{
			return "Your last visit is unknown. " + USAGE;
		}
		String needed = progressRequirement();
		long now = System.currentTimeMillis();
		if (unlock > now)
		{
			LocalDate date = Instant.ofEpochMilli(unlock).atZone(ZoneOffset.UTC).toLocalDate();
			return "Next game on " + date + " at 00:00 UTC (in " + Eligibility.formatRemaining(unlock - now) + ")."
				+ (needed == null ? "" : " " + needed + ".");
		}
		return needed == null ? READY : "The weekly wait is over. " + needed + ".";
	}

	@Nullable
	String command(String[] args)
	{
		String current = status();
		if (current == null || args.length == 0 || !normalMembersWorld() || !questComplete || client.getOverallExperience() <= 0)
		{
			return current;
		}
		long now = System.currentTimeMillis();
		try
		{
			if (args.length == 3 && args[0].equalsIgnoreCase("sync"))
			{
				int days = Integer.parseInt(args[1]);
				int xp = Eligibility.journalXp(args[2]);
				Long baseline = Eligibility.journalBaseline(days, xp, client.getOverallExperience());
				saveVisit(Eligibility.unlockInDays(days, now), baseline, xp == 0 ? null : questPoints() + 1, xp == 0);
			}
			else if (args.length == 2 && args[0].equalsIgnoreCase("last"))
			{
				LocalDate utcDate = LocalDate.parse(args[1]);
				if (utcDate.getYear() < 2005 || utcDate.isAfter(LocalDate.now(ZoneOffset.UTC)))
				{
					throw new IllegalArgumentException();
				}
				saveDate(Eligibility.unlockAfterGame(utcDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()));
			}
			else
			{
				return USAGE;
			}
			return "Saved. " + status();
		}
		catch (DateTimeException | IllegalArgumentException ex)
		{
			return "Check the journal values. Days must be 0-7, remaining XP must be 0-100,000, and the visit date must be today or earlier (UTC).";
		}
	}
}
