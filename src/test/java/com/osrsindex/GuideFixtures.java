package com.osrsindex;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The guide bodies the tests read, in src/test/resources: shaped like the site's guide routes (docs/guides.md),
 * with fields from a later site mixed in. The text is sample text, not a real guide's.
 */
final class GuideFixtures
{
	static final String GUIDE = "guide-fixture.json";
	static final String LISTING = "guides-fixture.json";

	private GuideFixtures()
	{
	}

	static String read(String name)
	{
		try (InputStream in = GuideFixtures.class.getResourceAsStream(name))
		{
			if (in == null)
			{
				throw new IllegalStateException("no fixture " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	static Guide guide()
	{
		return GuideParser.guide(read(GUIDE)).value;
	}

	static Guide.Listing listing()
	{
		return GuideParser.listing(read(LISTING)).value;
	}
}
