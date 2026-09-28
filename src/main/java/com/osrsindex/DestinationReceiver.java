package com.osrsindex;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the plugin does with each destination the socket delivers: route it with {@link RouteDispatcher}, tell the player in
 * chat, then {@code ack} it. Run it on the client thread, where the dispatcher posts its
 * {@code PluginMessage}.
 */
final class DestinationReceiver
{
	private final RouteDispatcher dispatcher;
	private final Supplier<RouteWith> routeWith;
	private final Consumer<String> chat;
	private final Predicate<String> ack;

	DestinationReceiver(RouteDispatcher dispatcher, Supplier<RouteWith> routeWith, Consumer<String> chat,
		Predicate<String> ack)
	{
		this.dispatcher = dispatcher;
		this.routeWith = routeWith;
		this.chat = chat;
		this.ack = ack;
	}

	RouteDispatcher.Result accept(Destination destination)
	{
		RouteDispatcher.Result result = dispatcher.dispatch(destination, routeWith.get());
		if (result.chatMessage != null)
		{
			chat.accept(result.chatMessage);
		}
		if (result.ackId != null)
		{
			ack.test(result.ackId);
		}
		return result;
	}
}
