# OSRS Index

> [!WARNING]
> **Work in progress: not officially live.** Neither this plugin nor [osrsindex.com](https://osrsindex.com) has
> launched yet.
> - Accounts on the site are invite-only.
> - Features, and what the plugin sends, may change without notice.
> - The plugin is not on the RuneLite Plugin Hub.

A RuneLite plugin that syncs your character to your own [osrsindex.com](https://osrsindex.com) account, and brings
the site into the game. Only you can see what it sends, on the site's home page and your character page.

- **One-click linking:** no token to paste and no code to type.
- **Character sync:** gear, bank and item stores, skills, quests, diaries, combat achievements, collection log, slayer,
  kill counts, Grand Exchange offers and location.
- **Walk here from the site:** pick a place on osrsindex.com's map, and this client routes you there with Shortest
  Path.
- **A panel** with your last sync and buttons to your character page, the map at your location and your account.
- **Right-click lookups:** "OSRS Index" beside Examine on items and NPCs opens the site's search.

## Linking

1. Log in to the game, open the **OSRS Index** sidebar panel (the orange roundel), and click **Link with
   osrsindex.com**.
2. Your browser opens at osrsindex.com with this client's code already filled in. Sign in if you are not already.
   Check that the page names your character and shows the same code as the panel, then click **Approve**.
3. Switch back to RuneLite. **The first link takes up to about 15 seconds after you approve.** Chat then says
   "Linked to your osrsindex.com account.", and syncing starts.

The browser never opens by itself. A code works for 10 minutes, and **Get a new code** in the panel makes a new one.
You can also type the code at osrsindex.com/link, or paste a token from your account page into **Account token** in
the plugin's settings.

**Unlink this client** in the panel stops all sending. To remove what was sent, use **Delete tracker data** on your
osrsindex.com account page.

## What it sends

| What | When |
|---|---|
| Worn items and inventory | when they change |
| Bank | when it changes while open (the client can read it only then) |
| Account type and combat level | at login, and when they change |
| Skills (level and xp) | at login, and on xp changes |
| Quests (every quest's state, quest points) | at login, and when quest points change |
| Achievement diaries | at login, and when one completes |
| Combat achievements | at login, and when one completes |
| Collection log (obtained/total, plus the items on each log page you open) | at login, and as you browse the log |
| Location (x, y, plane, world) | when you move |
| Progress values: combat achievement tasks, diary tasks, slayer, quest steps, music, clues, minigames, pets, fairy rings, prayers | when any changes |
| Item stores: looting bag, seed vault, GIM group storage, quiver, POH costume room, forestry kit, huntsman's kit | when one is opened or checked |
| Kill counts, from "Your X kill count is: N." chat lines | as they appear |
| Grand Exchange offers | when an offer changes |

Leagues, POH and interface settings are not sent. The plugin sends at most one request every 30 seconds, and only
what changed. Nothing is lost if a sync fails: a change stays queued until the site confirms it was stored. Each
group can be turned off in the plugin's settings.

**The collection log fills in as you browse.** RuneLite can read a collection log item only while its page is on
screen. The obtained/total count is always right, because it comes from the game's own counter. The item list grows
as you open pages, and it is kept in your RuneLite profile. To fill it at once, open the log and click through each
tab.

## The panel

Once linked, the **OSRS Index** panel shows:
- the character it syncs;
- the last sync, for example "Last sync at 14:02: location, skills and bank.";
- whether it is listening for places sent from the site.

| Button | Opens |
|---|---|
| **My character** | your character page, with everything the plugin has sent |
| **Map at my location** | the site's map at your current tile |
| **Account and linked clients** | your account page: each linked client with Revoke, and Delete tracker data |

## Walk here from the site

Pick a place on osrsindex.com's map and click **Walk here in RuneLite**. This client hands it to the
[Shortest Path](https://runelite.net/plugin-hub/show/shortest-path) plugin, which draws the route. Install and turn
on Shortest Path from the Plugin Hub.

- **On by default.** A connection to osrsindex.com stays open while this client is linked and you are logged in.
  Turn off **Receive destinations** under *Walk here from the site* in the settings to close it.
- It receives only the places you send, and nothing else.
- If another RuneLite client with the same link connects, that client takes over.
- The last line of the panel says whether it is listening.

## Right-click lookups

**OSRS Index** appears beside **Examine** on items (inventory, worn equipment, the bank, other interfaces and the
ground) and on NPCs. It opens osrsindex.com's search for that name in your browser. Nothing is sent from the client.
Turn it off with **Right-click lookups** under *Look up on osrsindex.com* in the settings.

## Chat messages

| Message | Meaning |
|---|---|
| Link this client to your osrsindex.com account… | A link code is ready. Click **Link with osrsindex.com** in the panel, then Approve. |
| Approve this client in your browser… | You clicked Link. Approve on the page that opened; linking takes up to about 15 seconds after that. |
| Linked to your osrsindex.com account. | The link was approved, and syncing starts. |
| Synced to your osrsindex.com account. | The first sync of the session was stored. |
| osrsindex.com did not accept this client's link… | The link was revoked. Link again from the panel. |
| The site refused part of a sync (code). | The site could not store part of a sync. It is sent again when that data changes. |
| osrsindex.com: routing to PLACE (x, y, plane) with Shortest Path. | A place you sent from the site's map is being routed. |
| osrsindex.com sent PLACE (x, y, plane), but Shortest Path is not on. | Install or turn on Shortest Path, then send the place again. |

## Building from source

You need JDK 17. The plugin compiles to Java 11 bytecode, as the Plugin Hub requires.

```
./gradlew build
```

```
./gradlew run
```

`gradlew run` opens RuneLite in developer mode with the plugin loaded, in its own `osrs-index-dev` profile, so your
normal settings are untouched.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
