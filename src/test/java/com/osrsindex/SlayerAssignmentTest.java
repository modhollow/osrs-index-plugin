package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
	public void schemaFiveStillCarriesTheNamedTask()
	{
		JsonObject vars = new JsonObject();
		vars.addProperty("SLAYER_COUNT", 72);
		CharacterReader.addSlayerAssignment(vars, Part.LATEST_SCHEMA, 72, "Cave krakens", "Kraken Cove");
		assertEquals(5, Part.LATEST_SCHEMA);
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

	@Test
	public void hardSpaceAtEitherEndIsStrippedAndTheTaskKept()
	{
		assertKept("\u00A0Cave krakens", "Kraken Cove\u00A0");
		assertKept("Cave krakens\u00A0", "\u00A0Kraken Cove");
		assertKept("\u00A0\u00A0Cave krakens\u00A0", "\u00A0Kraken Cove\u00A0\u00A0");
	}

	@Test
	public void byteOrderMarkAtEitherEndIsStrippedAndTheTaskKept()
	{
		assertKept("\uFEFFCave krakens", "Kraken Cove\uFEFF");
		assertKept("Cave krakens\uFEFF", "\uFEFFKraken Cove");
		assertKept("\uFEFF\u00A0Cave krakens \uFEFF", "\u3000Kraken Cove\u2028");
	}

	@Test
	public void hardSpaceInsideANameBecomesAPlainSpace()
	{
		assertKept("Cave\u00A0krakens", "Kraken\u00A0Cove");
	}

	@Test
	public void eightyCharactersAreCountedAfterTheEndsAreStripped()
	{
		String eighty = "k".repeat(80);
		JsonObject vars = new JsonObject();
		CharacterReader.addSlayerAssignment(vars, 4, 20, eighty + "\u00A0", null);
		assertEquals(eighty, vars.get("SLAYER_TASK_NAME").getAsString());

		JsonObject tooLong = new JsonObject();
		CharacterReader.addSlayerAssignment(tooLong, 4, 20, "\u00A0" + eighty + "k", null);
		assertFalse(tooLong.has("SLAYER_TASK_NAME"));
	}

	@Test
	public void nameOfOnlyTrimmedCharactersIsDropped()
	{
		JsonObject vars = new JsonObject();
		CharacterReader.addSlayerAssignment(vars, 4, 20, "\u00A0\uFEFF ", "Kraken Cove");
		assertFalse(vars.has("SLAYER_TASK_NAME"));
		assertFalse(vars.has("SLAYER_TASK_AREA"));
	}

	@Test
	public void validSlayerTextRefusesHardSpaceAndByteOrderMarkAtEitherEnd()
	{
		assertTrue(CharacterReader.validSlayerText("Cave krakens"));
		assertFalse(CharacterReader.validSlayerText("\u00A0Cave krakens"));
		assertFalse(CharacterReader.validSlayerText("Cave krakens\u00A0"));
		assertFalse(CharacterReader.validSlayerText("\uFEFFCave krakens"));
		assertFalse(CharacterReader.validSlayerText("Cave krakens\uFEFF"));
		// Inside a name neither is trimmed, and neither is a control character, so the site takes it as it is.
		assertTrue(CharacterReader.validSlayerText("Cave\u00A0krakens"));
		assertTrue(CharacterReader.validSlayerText("Cave\uFEFFkrakens"));
	}

	/**
	 * Every UTF-16 code unit against ECMAScript's own definition, written independently of the plugin's list:
	 * WhiteSpace is tab, vertical tab, form feed, U+FEFF and any space separator (Zs), and LineTerminator is line
	 * feed, carriage return, U+2028 and U+2029. Characters outside the set, such as U+200B and U+180E, stay valid
	 * at the ends because the site's trim() keeps them too.
	 */
	@Test
	public void trimmedSetIsExactlyJavaScriptTrim()
	{
		for (int code = 0; code <= 0xffff; code++)
		{
			char c = (char) code;
			boolean script = code == 0x09 || code == 0x0b || code == 0x0c || code == 0xfeff
				|| Character.getType(c) == Character.SPACE_SEPARATOR
				|| code == 0x0a || code == 0x0d || code == 0x2028 || code == 0x2029;
			assertEquals(String.format("U+%04X", code), script, CharacterReader.isScriptTrimmed(c));
			if (script)
			{
				assertFalse(String.format("U+%04X first", code), CharacterReader.validSlayerText(c + "Cave krakens"));
				assertFalse(String.format("U+%04X last", code), CharacterReader.validSlayerText("Cave krakens" + c));
			}
		}
		assertTrue(CharacterReader.validSlayerText("\u200BCave krakens\u200B"));
		assertTrue(CharacterReader.validSlayerText("\u180ECave krakens\u180E"));
	}

	@Test
	public void scriptTrimStripsBothEndsOnly()
	{
		assertEquals("Cave krakens", CharacterReader.scriptTrim("\t\u2003Cave krakens\r\n\u205F"));
		assertEquals("a\u3000b", CharacterReader.scriptTrim("\u3000a\u3000b\u3000"));
		assertEquals("", CharacterReader.scriptTrim("\u1680\u202F\u2029"));
		assertEquals("", CharacterReader.scriptTrim(""));
		assertNull(CharacterReader.normaliseSlayerText(null));
	}

	private static void assertKept(String name, String area)
	{
		JsonObject vars = new JsonObject();
		CharacterReader.addSlayerAssignment(vars, 4, 20, name, area);
		assertEquals("Cave krakens", vars.get("SLAYER_TASK_NAME").getAsString());
		assertEquals("Kraken Cove", vars.get("SLAYER_TASK_AREA").getAsString());
	}
}
