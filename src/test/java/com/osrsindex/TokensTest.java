package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The link token's form: nothing goes into a request header that OkHttp would refuse, and log, there. */
public class TokensTest
{
	private static final String TOKEN = "osrsidx_" + "Ab9-_".repeat(8) + "xyz";

	@Test
	public void theSitesFormIsWellFormed()
	{
		assertTrue(Tokens.wellFormed(TOKEN));
	}

	@Test
	public void aPasteKeepsNoSpaceOrInvisibleCharacter()
	{
		assertEquals(TOKEN, Tokens.normalize(" \t" + TOKEN + " ​﻿\n"));
		assertTrue(Tokens.wellFormed(Tokens.normalize(" " + TOKEN + " ")));
		assertEquals("", Tokens.normalize(null));
	}

	@Test
	public void anythingElseIsNeverSent()
	{
		assertFalse(Tokens.wellFormed(null));
		assertFalse(Tokens.wellFormed(""));
		assertFalse(Tokens.wellFormed("osrsidx_"));
		assertFalse(Tokens.wellFormed("osrsidx_short"));
		assertFalse(Tokens.wellFormed("Bearer " + TOKEN));
		assertFalse(Tokens.wellFormed(TOKEN + "é"));
		assertFalse(Tokens.wellFormed(TOKEN.replace('A', 'Ä')));
		assertFalse(Tokens.wellFormed("osrsidx_" + "a".repeat(257)));
	}
}
