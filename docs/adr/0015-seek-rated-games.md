# ADR 0015: The bot challenges, it does not wait

**Date:** 2026-09-24
**Status:** accepted

## The problem

Every strength number in this repo is self-play. The README says so, and says why
that inflates: two builds of the same engine share every blind spot, so roughly
60% of a self-play Elo gap typically survives contact with a different opponent.

The fix was supposed to be the Lichess bot, which shipped on 2026-09-16 and has
run as a launchd agent ever since. On 2026-09-24 it had played **zero rated
games** in eight days of uptime.

The cause is one line. `onEvent` handled `challenge` by accepting it, and nothing
anywhere issued one. Nobody challenges an unrated bot they have never heard of,
so the process sat on a healthy event stream, reconnecting politely, forever.

That is the worst shape a bug can take: everything running, nothing failing, no
error to find, and the one claim that does not depend on trusting the author never
gets made.

## What I chose

A daemon thread that lists online bots and challenges them to **rated** 3+2 games,
one at a time.

Rated is the whole point. An unrated game leaves no evidence.

## The four outcomes, which had to be separated

The first version treated every non-429 response the same and slept 25 seconds
after each. The first live run showed why that is wrong:

```
challenged maia1 -> 400
```

400 here is not a malformed request. The body says so:

```json
{"error":"maia1 played 100 games against other bots today, please wait until ...",
 "ratelimit":{"key":"bot.vsBot.day","seconds":38101}}
```

Lichess caps bot-versus-bot at 100 games per bot per day, and the popular bots hit
that cap first. Waiting 25 seconds on each capped opponent turns one pass of a
50-bot list into twenty minutes of sleeping.

So the loop reads the body, not just the code:

| Response | Meaning | Action |
|---|---|---|
| 200/201 | challenge is live | wait ~25s, it expires on its own |
| 400 + `bot.vsBot.day` | opponent capped today | next name, immediately |
| 429 | we are rate-limited | back off 60s |
| anything else | unknown | log the body, short pause |

The list is also shuffled, because the endpoint returns the same popular bots in
the same order and those are exactly the ones already capped.

## The second bug the first run exposed

The log filled with `accepting challenge <id>` for challenges nobody had sent.

`/api/stream/event` emits `challenge` for challenges you **send** as well as ones
you receive, with no separate type. The bot was accepting its own outgoing
challenges. Harmless, but one wasted request per seek and a log that claims
something false. Fixed by comparing `challenge.challenger.id` to our own username.

Both bugs are the same shape as the SPRT one in ADR 0010: **a confident-looking
log line covering for something that is not happening.**

## Three more bugs, all found by running it rather than reading it

Within two hours of going live:

**1. The wedge.** Capacity was tracked by an in-memory counter, incremented on
`gameStart` and released when the game stream closed. A game ended at 16:06 and
the seek loop was still idle at 16:29. The stream had not closed, so the counter
never dropped, so the bot believed it was permanently at capacity. Healthy
process, clean log, no error, no games.

Fixed by deleting the counter. `/api/account/playing` is the authority on how many
games we are in; a local mirror of remote state drifts, and this one drifts toward
"busy forever", silently. Lichess knows, so ask it.

**2. The dropped move, which was costing games.** Of the first six rated games,
four were won by mate and **both losses were on time.** The engine was not being
outplayed, it was failing to move. In the log:

```
POST /api/bot/game/uYdGydZd/move/d6b4 failed: HTTP/1.1 header parser received no bytes
```

`uYdGydZd` is one of the two time losses. Moves went through the same fire-and-
forget `post` used for accept and decline, which logs a failure and returns. For a
move that is fatal in the quietest way available: the move never lands, the
position never changes, so no new `gameState` arrives, so nothing recomputes. The
bot sits on a healthy stream until it flags.

Now retried three times with backoff. Resending is safe, because a move that
already landed is rejected as out of turn, and losing it is the expensive
direction.

**3. The startup crash.** The account fetch was a bare `send()` outside any retry,
so an `UnresolvedAddressException` (DNS not up yet, ordinary for a launchd agent)
threw out of `main` and killed the process. KeepAlive restarted it, so nothing was
lost, but the event stream already retries forever and there was no reason startup
should be the fragile step.

All three share the shape this project keeps running into: **nothing throws, the
logs look fine, and the thing simply is not happening.** Same as the SPRT billion
LLR in ADR 0010 and the 704 silent sessions.

They also all needed production to find. The unit tests pass on every one of these
code paths, because none of them were logic errors.

## One game at a time, and this is a strength decision

`maxGames` defaults to 1.

The harness plays at fixed nodes, where two games sharing a core produce identical
results to one game with the core to itself (ADR 0011). Here the clock is real, so
a second game on the same core halves the nodes this one searches, and the engine
plays worse. A rating collected under contention is a rating for a slower engine
than the one the README describes.

## What it costs

A public, permanent number. If the engine is weak, that is now on the internet
under a real account, and it cannot be quietly withdrawn.

That is the correct trade. The whole project is built on the claim that measured
beats asserted, and declining to measure the one thing that would settle it
because the answer might be unflattering would make every other number in this
repo worth less.

## What is still not claimed

A rating is not a comparison to Stockfish, Calvin or Serendipity. It is a number
against whichever bots accepted, on a 3+2 clock, on this hardware. It replaces
"not measured" with "measured, here is the link", and nothing more.
