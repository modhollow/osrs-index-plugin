package com.osrsindex;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Revokes this client's token on osrsindex.com when the player unlinks it. Forgetting the token in the
 * plugin's settings is not enough on its own: on the site the token would stay live, keep counting toward
 * the account's 5 linked clients, and keep working from any copy, such as an old RuneLite profile.
 *
 * <p>The request is one {@code DELETE} to {@link Endpoints#revokeToken()}, with the token as a bearer and no
 * body. It is sent once and never retried. The site answers 204 when it revoked the token, and 401 when it
 * no longer knows it, for example because it was already revoked on the account page. Both mean the token
 * is gone. Any other answer, such as a 429, a 503, or a 404 from a site without the route, leaves the token
 * possibly live, and so does no answer at all. Either way the player is told to revoke the client on the
 * account page.
 *
 * <p>The plugin forgets the token itself whatever the answer, so unlinking works offline. The site limits
 * this route to 5 requests a minute per address, in a bucket of its own. Each Unlink sends one request,
 * and the button is gone once the token is cleared, so that limit is never close.
 */
final class TokenRevoker
{
	/** What the site's answer means for the token. */
	enum Result
	{
		/** 204 or 401: the token no longer works on the site. */
		CONFIRMED,
		/** Any other status: the site did not say the token is gone. */
		NOT_CONFIRMED,
		/** No response: offline, timed out, or the site could not be reached. */
		NO_RESPONSE,
	}

	/** One {@code DELETE} with the token as a bearer, answered with its status, or -1 for no response. */
	interface Transport
	{
		void delete(String url, String token, IntConsumer status);
	}

	/** What chat adds whenever the site did not confirm the revoke. */
	static final String REVOKE_ON_THE_SITE = "Revoke this client on your osrsindex.com account page to be sure.";

	private final Transport transport;

	TokenRevoker(OkHttpClient http)
	{
		this(okHttp(http));
	}

	TokenRevoker(Transport transport)
	{
		this.transport = transport;
	}

	/** Ask the site to revoke {@code token}, once. {@code done} gets the result on whichever thread answered. */
	void revoke(String token, Consumer<Result> done)
	{
		transport.delete(Endpoints.revokeToken(), token, status -> done.accept(result(status)));
	}

	static Result result(int status)
	{
		if (status == 204 || status == 401)
		{
			return Result.CONFIRMED;
		}
		return status < 0 ? Result.NO_RESPONSE : Result.NOT_CONFIRMED;
	}

	/** The chat line that says whether the site confirmed the revoke. */
	static String chatLine(Result result)
	{
		switch (result)
		{
			case CONFIRMED:
				return "osrsindex.com confirmed that this client's link is revoked.";
			case NO_RESPONSE:
				return "osrsindex.com could not be reached to revoke this client's link. " + REVOKE_ON_THE_SITE;
			case NOT_CONFIRMED:
			default:
				return "osrsindex.com did not confirm that this client's link is revoked. " + REVOKE_ON_THE_SITE;
		}
	}

	/** The transport the plugin uses: the {@code DELETE} through {@code http}. Only the status is read. */
	static Transport okHttp(OkHttpClient http)
	{
		return (url, token, status) ->
		{
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.delete()
				.build();
			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					status.accept(-1);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					// Closing the response frees the connection; its body, if any, says nothing more.
					try (Response closing = response)
					{
						status.accept(closing.code());
					}
				}
			});
		};
	}
}
