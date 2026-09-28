package com.osrsindex;

/**
 * One container or section of the sync body. The declaration order is the packing priority: when a body would exceed the size
 * budget, the later parts wait for the next sync.
 */
enum Part
{
	LOCATION("location", 2),
	ACCOUNT("account", 2),
	EQUIPMENT("equipment", 1),
	INVENTORY("inventory", 1),
	SKILLS("skills", 2),
	DIARIES("diaries", 2),
	COMBAT_ACHIEVEMENTS("combat_achievements", 2),
	QUESTS("quests", 2),
	GRAND_EXCHANGE("grand_exchange", 3),
	KILL_COUNTS("kill_counts", 3),
	STORAGE("storage", 3),
	BANK("bank", 1),
	COLLECTION_LOG("collection_log", 2),
	VARS("vars", 3);

	/** The newest schema this plugin speaks. */
	static final int LATEST_SCHEMA = 3;

	/** The body key. */
	final String key;

	/** The lowest schema number that carries this part. */
	final int minSchema;

	Part(String key, int minSchema)
	{
		this.key = key;
		this.minSchema = minSchema;
	}
}
