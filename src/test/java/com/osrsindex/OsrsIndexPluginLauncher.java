package com.osrsindex;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** `gradlew run`: RuneLite in developer mode with this plugin loaded as if it were built in. */
public class OsrsIndexPluginLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsIndexPlugin.class);
		RuneLite.main(args);
	}
}
