package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class CharacterReaderTest
{
	/** Where the fake player stands as the client reports it, which inside an instance is the instance's copy. */
	private static final WorldPoint INSTANCE_COPY = new WorldPoint(9000, 9000, 0);

	/** Scene tile (50, 50): chunk (6, 6), tile (2, 2) inside it. */
	private static final LocalPoint SCENE_TILE = new LocalPoint(50 << 7, 50 << 7, -1);

	private final Map<String, Function<Object[], Object>> client = new HashMap<>();
	private final Map<String, Function<Object[], Object>> view = new HashMap<>();

	private final CharacterReader reader = new CharacterReader(fake(Client.class, client), null);

	/**
	 * A fake of a RuneLite interface: each named method gives its answer, and every other method
	 * returns null, false or 0.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
		{
			Function<Object[], Object> answer = answers.get(method.getName());
			if (answer != null)
			{
				return answer.apply(args);
			}
			Class<?> returns = method.getReturnType();
			if (returns == boolean.class)
			{
				return false;
			}
			if (returns == int.class)
			{
				return 0;
			}
			if (returns == long.class)
			{
				return 0L;
			}
			if (returns == String.class)
			{
				return type.getSimpleName();
			}
			return null;
		});
	}

	private void standOnSceneTile(boolean instance)
	{
		Map<String, Function<Object[], Object>> player = new HashMap<>();
		player.put("getWorldLocation", args -> INSTANCE_COPY);
		player.put("getLocalLocation", args -> SCENE_TILE);
		client.put("getLocalPlayer", args -> fake(Player.class, player));
		client.put("getWorld", args -> 302);

		// Template chunk (400, 400), plane 0, no rotation: the real tile is (3202, 3202).
		int[][][] chunks = new int[4][13][13];
		chunks[0][6][6] = 400 << 14 | 400 << 3;
		view.put("isInstance", args -> instance);
		view.put("getInstanceTemplateChunks", args -> chunks);
		WorldView worldView = fake(WorldView.class, view);
		client.put("getTopLevelWorldView", args -> worldView);
		client.put("getWorldView", args -> worldView);
	}

	@Test
	public void skillsSendsEveryRealSkillAndNoOverall()
	{
		client.put("getSkillExperience", args -> 1_000 + ((Skill) args[0]).ordinal());
		client.put("getRealSkillLevel", args -> 10);

		JsonObject skills = reader.skills();

		assertNotNull(skills);
		assertEquals(Skill.values().length, skills.size());
		for (Skill skill : Skill.values())
		{
			JsonObject value = skills.getAsJsonObject(skill.name().toLowerCase(Locale.ROOT));
			assertEquals(1_000 + skill.ordinal(), value.get("xp").getAsInt());
			assertEquals(10, value.get("level").getAsInt());
		}
		assertTrue(skills.has("sailing"));
		assertFalse(skills.has("overall"));
	}

	@Test
	public void skillsAreNullUntilTheStatsArrive()
	{
		assertNull(reader.skills());
	}

	@Test
	public void locationOutsideAnInstanceIsWhereTheClientSaysThePlayerIs()
	{
		standOnSceneTile(false);

		JsonObject location = reader.location();

		assertNotNull(location);
		assertEquals(9000, location.get("x").getAsInt());
		assertEquals(9000, location.get("y").getAsInt());
		assertEquals(0, location.get("plane").getAsInt());
		assertEquals(302, location.get("world").getAsInt());
	}

	@Test
	public void locationInsideAnInstanceIsTheRealTileTheInstanceCopies()
	{
		standOnSceneTile(true);

		JsonObject location = reader.location();

		assertNotNull(location);
		assertEquals(3202, location.get("x").getAsInt());
		assertEquals(3202, location.get("y").getAsInt());
		assertEquals(0, location.get("plane").getAsInt());
	}

	@Test
	public void locationWithNoWorldViewYetIsWhereTheClientSaysThePlayerIs()
	{
		standOnSceneTile(true);
		client.put("getTopLevelWorldView", args -> null);

		JsonObject location = reader.location();

		assertNotNull(location);
		assertEquals(9000, location.get("x").getAsInt());
	}

	@Test
	public void locationIsNullWithoutAPlayer()
	{
		assertNull(reader.location());
	}

	@Test
	public void varsReadTheBuiltInListUnlessTheSiteChoseOthers()
	{
		client.put("getVarbitValue", args -> (Integer) args[0] + 1);
		client.put("getVarpValue", args -> (Integer) args[0] + 2);

		JsonObject builtIn = reader.vars(3);
		assertEquals(GameVars.VARBIT_IDS.length + GameVars.VARP_IDS.length, builtIn.size());
		assertEquals(GameVars.VARBIT_IDS[0] + 1, builtIn.get(GameVars.VARBIT_NAMES[0]).getAsInt());

		SiteConfig.Vars site = SiteConfig.parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"NEW_QUEST\":20512},"
			+ "\"varps\":{\"NEW_MINIGAME_POINTS\":6100}}}").vars;
		JsonObject chosen = reader.vars(3, site);
		assertEquals(2, chosen.size());
		assertEquals(20_513, chosen.get("NEW_QUEST").getAsInt());
		assertEquals(6_102, chosen.get("NEW_MINIGAME_POINTS").getAsInt());
	}

	@Test
	public void aVarTheClientCannotReadIsLeftOutFromThenOn()
	{
		int[] reads = {0};
		client.put("getVarbitValue", args ->
		{
			reads[0]++;
			if ((Integer) args[0] == 60_000)
			{
				throw new IndexOutOfBoundsException("no such varbit");
			}
			return 1;
		});
		client.put("getVarpValue", args ->
		{
			throw new ArrayIndexOutOfBoundsException("no such varp");
		});
		SiteConfig.Vars site = SiteConfig.parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"BAD_QUEST\":60000,"
			+ "\"GOOD_QUEST\":30001},\"varps\":{\"ALSO_BAD_QUEST\":65000}}}").vars;

		JsonObject first = reader.vars(3, site);
		assertEquals(1, first.size());
		assertEquals(1, first.get("GOOD_QUEST").getAsInt());
		assertEquals(2, reads[0]);

		JsonObject second = reader.vars(3, site);
		assertEquals(1, second.size());
		assertEquals("the unreadable varbit is not tried again", 3, reads[0]);

		reader.forgetUnreadableVars();
		reader.vars(3, site);
		assertEquals("a new session tries it again", 5, reads[0]);
	}
}
