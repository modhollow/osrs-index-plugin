package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.DBTableID;
import org.junit.Test;

public class SlayerTaskLookupTest
{
	private static final int KRAKEN_TASK_ID = 53;
	private static final int KRAKEN_TASK_ROW = 1_053;
	private static final int ZULRAH_BOSS_ID = 18;
	private static final int ZULRAH_SUBLIST_ROW = 2_018;
	private static final int ZULRAH_TASK_ROW = 1_201;
	private static final int KRAKEN_COVE_AREA_ID = 27;
	private static final int KRAKEN_COVE_AREA_ROW = 3_027;

	/** DB rows and fields held in maps, keyed the way the client looks them up. Unknown keys answer empty. */
	private static final class FakeTables implements CharacterReader.DbTables
	{
		final Map<String, List<Integer>> rows = new HashMap<>();
		final Map<String, Object[]> fields = new HashMap<>();

		FakeTables row(int table, int column, Object value, Integer row)
		{
			rows.put(table + "/" + column + "/" + value, Collections.singletonList(row));
			return this;
		}

		FakeTables field(int row, int column, Object[] values)
		{
			fields.put(row + "/" + column, values);
			return this;
		}

		@Override
		public List<Integer> rowsByValue(int table, int column, int tupleIndex, Object value)
		{
			return rows.getOrDefault(table + "/" + column + "/" + value, Collections.emptyList());
		}

		@Override
		public Object[] field(int row, int column, int tupleIndex)
		{
			return fields.getOrDefault(row + "/" + column, new Object[0]);
		}
	}

	/** A boss task wired through the sublist; the test then breaks one link of the chain. */
	private static FakeTables bossTables(Object[] sublistTaskField)
	{
		return new FakeTables()
			.row(DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, ZULRAH_BOSS_ID,
				ZULRAH_SUBLIST_ROW)
			.field(ZULRAH_SUBLIST_ROW, DBTableID.SlayerTaskSublist.COL_TASK, sublistTaskField)
			.field(ZULRAH_TASK_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, new Object[]{"Zulrah"});
	}

	private static FakeTables regularTables(Object[] nameField)
	{
		return new FakeTables()
			.row(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, KRAKEN_TASK_ID, KRAKEN_TASK_ROW)
			.field(KRAKEN_TASK_ROW, DBTableID.SlayerTask.COL_NAME_UPPERCASE, nameField);
	}

	@Test
	public void regularTaskIsNamedFromTheSlayerTaskTable()
	{
		assertEquals("Cave krakens",
			CharacterReader.slayerTaskName(regularTables(new Object[]{"Cave krakens"}), KRAKEN_TASK_ID, 0));
	}

	@Test
	public void bossTaskIsNamedThroughTheSublist()
	{
		assertEquals("Zulrah", CharacterReader.slayerTaskName(bossTables(new Object[]{ZULRAH_TASK_ROW}),
			CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID));
	}

	@Test
	public void bossTaskWithAnEmptySublistFieldStaysUnnamed()
	{
		assertNull(CharacterReader.slayerTaskName(bossTables(new Object[0]),
			CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID));
		assertNull(CharacterReader.slayerTaskName(bossTables(null), CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID));
	}

	@Test
	public void bossTaskWithASublistFieldOfAnotherTypeStaysUnnamed()
	{
		assertNull(CharacterReader.slayerTaskName(bossTables(new Object[]{"1201"}),
			CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID));
		assertNull(CharacterReader.slayerTaskName(bossTables(new Object[]{null}),
			CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID));
	}

	@Test
	public void bossTaskWithAMissingRowStaysUnnamed()
	{
		// No sublist row for this boss.
		assertNull(CharacterReader.slayerTaskName(bossTables(new Object[]{ZULRAH_TASK_ROW}),
			CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID + 1));
		// The sublist points at a task row that has no name.
		assertNull(CharacterReader.slayerTaskName(bossTables(new Object[]{ZULRAH_TASK_ROW + 1}),
			CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID));
	}

	@Test
	public void regularTaskWithAMissingRowOrMistypedNameStaysUnnamed()
	{
		assertNull(CharacterReader.slayerTaskName(regularTables(new Object[]{"Cave krakens"}), KRAKEN_TASK_ID + 1, 0));
		assertNull(CharacterReader.slayerTaskName(regularTables(new Object[0]), KRAKEN_TASK_ID, 0));
		assertNull(CharacterReader.slayerTaskName(regularTables(new Object[]{42}), KRAKEN_TASK_ID, 0));
	}

	@Test
	public void aNullRowOrRowListCountsAsMissing()
	{
		FakeTables tables = new FakeTables();
		tables.rows.put(DBTableID.SlayerTask.ID + "/" + DBTableID.SlayerTask.COL_ID + "/" + KRAKEN_TASK_ID,
			new ArrayList<>(Arrays.asList((Integer) null)));
		assertNull(CharacterReader.slayerTaskName(tables, KRAKEN_TASK_ID, 0));
		tables.rows.put(DBTableID.SlayerTask.ID + "/" + DBTableID.SlayerTask.COL_ID + "/" + KRAKEN_TASK_ID, null);
		assertNull(CharacterReader.slayerTaskName(tables, KRAKEN_TASK_ID, 0));
	}

	@Test
	public void areaIsNamedOnlyWhenItsRowAndFieldAreSound()
	{
		FakeTables tables = new FakeTables()
			.row(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, KRAKEN_COVE_AREA_ID, KRAKEN_COVE_AREA_ROW)
			.field(KRAKEN_COVE_AREA_ROW, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, new Object[]{"Kraken Cove"});
		assertEquals("Kraken Cove", CharacterReader.slayerAreaName(tables, KRAKEN_COVE_AREA_ID));
		assertNull(CharacterReader.slayerAreaName(tables, 0));
		assertNull(CharacterReader.slayerAreaName(tables, KRAKEN_COVE_AREA_ID + 1));
		tables.field(KRAKEN_COVE_AREA_ROW, DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, new Object[]{7});
		assertNull(CharacterReader.slayerAreaName(tables, KRAKEN_COVE_AREA_ID));
	}

	@Test
	public void aLookupThatThrowsLeavesTheTaskUnnamedAndReportsTheFailure()
	{
		CharacterReader.DbTables broken = new CharacterReader.DbTables()
		{
			@Override
			public List<Integer> rowsByValue(int table, int column, int tupleIndex, Object value)
			{
				throw new IllegalStateException("table reshaped");
			}

			@Override
			public Object[] field(int row, int column, int tupleIndex)
			{
				throw new IllegalStateException("table reshaped");
			}
		};
		List<RuntimeException> failures = new ArrayList<>();
		assertNull(CharacterReader.guardSlayerLookup(
			() -> CharacterReader.slayerTaskName(broken, CharacterReader.BOSS_TASK_ID, ZULRAH_BOSS_ID), failures::add));
		assertNull(CharacterReader.guardSlayerLookup(
			() -> CharacterReader.slayerAreaName(broken, KRAKEN_COVE_AREA_ID), failures::add));
		assertEquals(2, failures.size());
		assertTrue(failures.get(0) instanceof IllegalStateException);
	}

	@Test
	public void aLookupThatSucceedsPassesItsValueThroughTheGuard()
	{
		List<RuntimeException> failures = new ArrayList<>();
		String name = "Cave krakens";
		assertSame(name, CharacterReader.guardSlayerLookup(() -> name, failures::add));
		assertNull(CharacterReader.guardSlayerLookup(() -> null, failures::add));
		assertTrue(failures.isEmpty());
	}
}
