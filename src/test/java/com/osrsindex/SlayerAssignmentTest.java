package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import org.junit.Test;

public class SlayerAssignmentTest
{
	@Test
	public void schemaFourCarriesNamedCurrentTaskAndOptionalArea()
	{
		JsonObject vars = new JsonObject();
		vars.addProperty("SLAYER_COUNT", 72);
		CharacterReader.addSlayerAssignment(vars, 4, 72, "Cave krakens", "Kraken Cove");
		assertEquals("Cave krakens", vars.get("SLAYER_TASK_NAME").getAsString());
		assertEquals("Kraken Cove", vars.get("SLAYER_TASK_AREA").getAsString());
	}

	@Test
	public void schemaThreeDowngradeKeepsOnlyNumericGamevals()
	{
		JsonObject vars = new JsonObject();
		vars.addProperty("SLAYER_COUNT", 72);
		CharacterReader.addSlayerAssignment(vars, 3, 72, "Cave krakens", "Kraken Cove");
		assertEquals(1, vars.size());
		assertFalse(vars.has("SLAYER_TASK_NAME"));
	}

	@Test
	public void zeroOrUnresolvedTaskNeverCreatesStaleAssignment()
	{
		JsonObject vars = new JsonObject();
		CharacterReader.addSlayerAssignment(vars, 4, 0, "Cave krakens", "Kraken Cove");
		CharacterReader.addSlayerAssignment(vars, 4, 20, null, "Kraken Cove");
		CharacterReader.addSlayerAssignment(vars, 4, 20, "Cave\nkrakens", "Kraken Cove");
		assertFalse(vars.has("SLAYER_TASK_NAME"));
		CharacterReader.addSlayerAssignment(vars, 4, 20, "Cave krakens", "Area\nunknown");
		assertTrue(vars.has("SLAYER_TASK_NAME"));
		assertFalse(vars.has("SLAYER_TASK_AREA"));
	}
}
