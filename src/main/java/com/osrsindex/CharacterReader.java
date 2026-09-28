package com.osrsindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Reads each part of the character from the client, in the shape the contract names. Every method
 * runs on the client thread and returns null when the client does not hold that data yet, so a
 * half-loaded login never overwrites good data on the site.
 */
@Singleton
class CharacterReader
{
	static final int INVENTORY_SLOTS = 28;
	static final int MAX_BANK_ITEMS = 2_000;
	static final int MAX_ITEM_ID = 200_000;

	/** EquipmentInventorySlot values that hold an item; ARMS, HAIR and JAW do not. */
	private static final EquipmentInventorySlot[] EQUIPMENT_SLOTS = {
		EquipmentInventorySlot.HEAD,
		EquipmentInventorySlot.CAPE,
		EquipmentInventorySlot.AMULET,
		EquipmentInventorySlot.WEAPON,
		EquipmentInventorySlot.BODY,
		EquipmentInventorySlot.SHIELD,
		EquipmentInventorySlot.LEGS,
		EquipmentInventorySlot.GLOVES,
		EquipmentInventorySlot.BOOTS,
		EquipmentInventorySlot.RING,
		EquipmentInventorySlot.AMMO,
	};

	/** The IRONMAN varbit (1777), values 0 to 6 in this order. */
	static final String[] ACCOUNT_TYPES = {
		"normal",
		"ironman",
		"ultimate_ironman",
		"hardcore_ironman",
		"group_ironman",
		"hardcore_group_ironman",
		"unranked_group_ironman",
	};

	static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};

	/**
	 * Region key, then the completion varbit for easy, medium, hard and elite. Karamja's first three
	 * tiers predate the shared diary varbits and have their own.
	 */
	private static final Object[][] DIARIES = {
		{"ardougne", new int[]{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE}},
		{"desert", new int[]{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE}},
		{"falador", new int[]{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE}},
		{"fremennik", new int[]{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE}},
		{"kandarin", new int[]{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE}},
		{"karamja", new int[]{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE,
			VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE}},
		{"kourend", new int[]{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE}},
		{"lumbridge", new int[]{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE}},
		{"morytania", new int[]{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE}},
		{"varrock", new int[]{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE}},
		{"western", new int[]{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE}},
		{"wilderness", new int[]{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE}},
	};

	static final String[] CA_TIERS = {"easy", "medium", "hard", "elite", "master", "grandmaster"};

	private static final int[] CA_TIER_COUNTS = {
		VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER,
	};

	private final Client client;
	private final ItemManager itemManager;

	@Inject
	CharacterReader(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	@Nullable
	String characterName()
	{
		Player player = client.getLocalPlayer();
		String name = player == null ? null : player.getName();
		return name == null || name.isEmpty() ? null : name;
	}

	// --- containers (schema 1) ---------------------------------------------

	@Nullable
	JsonObject equipment()
	{
		ItemContainer container = client.getItemContainer(InventoryID.WORN);
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		JsonObject out = new JsonObject();
		for (EquipmentInventorySlot slot : EQUIPMENT_SLOTS)
		{
			int index = slot.getSlotIdx();
			out.add(slot.name().toLowerCase(Locale.ROOT), index < items.length ? item(items[index]) : JsonNull.INSTANCE);
		}
		return out;
	}

	@Nullable
	JsonArray inventory()
	{
		ItemContainer container = client.getItemContainer(InventoryID.INV);
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		JsonArray out = new JsonArray();
		for (int slot = 0; slot < INVENTORY_SLOTS; slot++)
		{
			out.add(slot < items.length ? item(items[slot]) : JsonNull.INSTANCE);
		}
		return out;
	}

	/** The bank is only readable while it is open, so the plugin calls this from the container event. */
	@Nullable
	JsonArray bank()
	{
		return items(InventoryID.BANK, MAX_BANK_ITEMS);
	}

	/**
	 * A container as a list of items, without empty slots, placeholders or bank fillers. Null when the
	 * client does not hold the container right now (a store that has not been opened this session).
	 */
	@Nullable
	JsonArray items(int containerId, int max)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return null;
		}
		JsonArray out = new JsonArray();
		for (Item raw : container.getItems())
		{
			if (out.size() >= max)
			{
				break;
			}
			if (raw == null || raw.getId() < 0 || raw.getQuantity() <= 0 || raw.getId() == ItemID.BANK_FILLER)
			{
				continue;
			}
			ItemComposition composition = itemManager.getItemComposition(raw.getId());
			if (composition.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			JsonElement parsed = item(raw);
			if (!parsed.isJsonNull())
			{
				out.add(parsed);
			}
		}
		return out;
	}

	/** An item in the contract's shape, or JSON null for an empty slot. Ids are canonical: unnoted, never a placeholder. */
	JsonElement item(@Nullable Item raw)
	{
		if (raw == null || raw.getId() < 0 || raw.getQuantity() <= 0)
		{
			return JsonNull.INSTANCE;
		}
		int id = itemManager.canonicalize(raw.getId());
		if (id < 0 || id > MAX_ITEM_ID)
		{
			return JsonNull.INSTANCE;
		}
		return itemJson(id, raw.getQuantity());
	}

	static JsonObject itemJson(int id, int quantity)
	{
		JsonObject out = new JsonObject();
		out.addProperty("id", id);
		out.addProperty("quantity", quantity);
		return out;
	}

	// --- sections (schema 2) -----------------------------------------------

	@Nullable
	JsonObject account()
	{
		Player player = client.getLocalPlayer();
		int type = client.getVarbitValue(VarbitID.IRONMAN);
		if (player == null || type < 0 || type >= ACCOUNT_TYPES.length)
		{
			return null;
		}
		int combat = player.getCombatLevel();
		if (combat < 3 || combat > 126)
		{
			return null;
		}
		JsonObject out = new JsonObject();
		out.addProperty("type", ACCOUNT_TYPES[type]);
		out.addProperty("combat_level", combat);
		return out;
	}

	@Nullable
	JsonObject skills()
	{
		JsonObject out = new JsonObject();
		long total = 0;
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			int xp = Math.max(0, Math.min(200_000_000, client.getSkillExperience(skill)));
			int level = Math.max(1, Math.min(99, client.getRealSkillLevel(skill)));
			total += xp;
			JsonObject value = new JsonObject();
			value.addProperty("level", level);
			value.addProperty("xp", xp);
			out.add(skill.name().toLowerCase(Locale.ROOT), value);
		}
		// Hitpoints alone starts at 1,154 xp, so a zero total means the stats have not arrived yet.
		return total == 0 ? null : out;
	}

	/** Runs one client script per quest, so call it at login and when quest points change, not every tick. */
	@Nullable
	JsonObject quests()
	{
		JsonObject states = new JsonObject();
		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			if (name == null || name.isEmpty() || name.length() > 100)
			{
				continue;
			}
			QuestState state = quest.getState(client);
			states.addProperty(name, state.name().toLowerCase(Locale.ROOT));
		}
		if (states.size() == 0)
		{
			return null;
		}
		JsonObject out = new JsonObject();
		out.addProperty("points", Math.max(0, Math.min(1_000, client.getVarpValue(VarPlayerID.QP))));
		out.add("states", states);
		return out;
	}

	JsonObject diaries()
	{
		JsonObject out = new JsonObject();
		for (Object[] region : DIARIES)
		{
			int[] varbits = (int[]) region[1];
			JsonObject tiers = new JsonObject();
			for (int i = 0; i < DIARY_TIERS.length; i++)
			{
				tiers.addProperty(DIARY_TIERS[i], client.getVarbitValue(varbits[i]) > 0);
			}
			out.add((String) region[0], tiers);
		}
		return out;
	}

	JsonObject combatAchievements()
	{
		JsonObject out = new JsonObject();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			out.addProperty(CA_TIERS[i], Math.max(0, Math.min(1_000, client.getVarbitValue(CA_TIER_COUNTS[i]))));
		}
		return out;
	}

	/** Obtained and total from the game's own counters, plus the items the plugin has seen on log pages. */
	@Nullable
	JsonObject collectionLog(CollectionLogTracker tracker)
	{
		int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		int obtained = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		if (total <= 0 || total > 10_000)
		{
			return null;
		}
		JsonObject out = new JsonObject();
		out.addProperty("obtained", Math.max(0, Math.min(total, obtained)));
		out.addProperty("total", total);
		if (tracker.size() > 0)
		{
			out.add("items", tracker.toJson());
		}
		return out;
	}

	/** The real world point, resolved out of an instance, or null when it is outside the contract's ranges. */
	@Nullable
	JsonObject location()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		WorldPoint point = client.isInInstancedRegion()
			? WorldPoint.fromLocalInstance(client, player.getLocalLocation())
			: player.getWorldLocation();
		if (point == null || point.getX() < 0 || point.getX() > 16_383 || point.getY() < 0 || point.getY() > 16_383
			|| point.getPlane() < 0 || point.getPlane() > 3)
		{
			return null;
		}
		JsonObject out = new JsonObject();
		out.addProperty("x", point.getX());
		out.addProperty("y", point.getY());
		out.addProperty("plane", point.getPlane());
		int world = client.getWorld();
		if (world >= 1 && world <= 65_535)
		{
			out.addProperty("world", world);
		}
		return out;
	}

	// --- sections (schema 3) -----------------------------------------------

	/** Every allowlisted var by its gameval name (GameVars). Zeros are sent too: "not done" is the point. */
	JsonObject vars()
	{
		JsonObject out = new JsonObject();
		for (int i = 0; i < GameVars.VARBIT_IDS.length; i++)
		{
			out.addProperty(GameVars.VARBIT_NAMES[i], client.getVarbitValue(GameVars.VARBIT_IDS[i]));
		}
		for (int i = 0; i < GameVars.VARP_IDS.length; i++)
		{
			out.addProperty(GameVars.VARP_NAMES[i], client.getVarpValue(GameVars.VARP_IDS[i]));
		}
		return out;
	}

	/** The occupied Grand Exchange slots. Null before the offers have loaded. */
	@Nullable
	JsonArray grandExchange()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return null;
		}
		JsonArray out = new JsonArray();
		for (int slot = 0; slot < offers.length && slot < 8; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == null || offer.getState() == GrandExchangeOfferState.EMPTY
				|| offer.getItemId() < 0 || offer.getItemId() > MAX_ITEM_ID)
			{
				continue;
			}
			JsonObject value = new JsonObject();
			value.addProperty("slot", slot);
			value.addProperty("item_id", offer.getItemId());
			value.addProperty("state", offer.getState().name().toLowerCase(Locale.ROOT));
			value.addProperty("price", Math.max(0, offer.getPrice()));
			value.addProperty("total", Math.max(0, offer.getTotalQuantity()));
			value.addProperty("sold", Math.max(0, offer.getQuantitySold()));
			value.addProperty("spent", Math.max(0, offer.getSpent()));
			out.add(value);
		}
		return out;
	}
}
