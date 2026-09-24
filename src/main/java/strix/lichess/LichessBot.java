package strix.lichess;

import strix.core.*;
import strix.eval.Psqt;
import strix.search.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Plays on Lichess as a labelled BOT account.
 *
 * Two streams, both newline-delimited JSON held open indefinitely:
 * the account event stream (challenges arriving, games starting) and, per game,
 * a game state stream (the move list and both clocks after every move).
 *
 * Lichess sends a blank line every few seconds as a keepalive on both, so an
 * empty line is normal and must not be treated as a disconnect.
 *
 * Unlike the test harness, this uses a real clock rather than fixed nodes. Fixed
 * nodes exists to make measurement reproducible; here the opponent is a human on
 * a real clock and losing on time is losing. See ADR 0011.
 *
 * <h2>Why it challenges rather than waits</h2>
 * An earlier version only accepted incoming challenges. Nothing challenges an
 * unrated bot nobody has heard of, so it sat on the event stream for eight days
 * and played zero games. A rating is the one claim about this engine that does
 * not rely on trusting the author, and it cannot be earned passively.
 *
 * So a background loop lists the bots currently online and challenges them. See
 * ADR 0015.
 */
public final class LichessBot {

    private static final String API = "https://lichess.org";

    private final String token;
    /**
     * HTTP/1.1 is forced deliberately. Java's HttpClient negotiates HTTP/2 by
     * default, and a long-lived streaming response over a pooled HTTP/2
     * connection can leave a later send() hanging indefinitely after the first
     * connection dies. Observed directly: the event stream dropped once with
     * "closed" and the reconnect never surfaced again, while the identical
     * request over curl worked every time.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService games = Executors.newCachedThreadPool();
    private volatile String username = "?";

    /**
     * Game ids we already have a thread for.
     *
     * Needed because a restart announces the same game twice: {@link #rejoinOngoing}
     * finds it via /api/account/playing, and the event stream then replays its
     * gameStart. Two threads on one game means two clients posting moves for the
     * same position.
     */
    private final java.util.Set<String> playing = ConcurrentHashMap.newKeySet();

    /**
     * Games Lichess says we are in, which is not the same as games we think we
     * are in.
     *
     * An in-memory counter was tried first and wedged the bot within the hour. A
     * game ended at 16:06 and the seek loop was still idle at 16:29, because the
     * counter was incremented on gameStart and released only when the game stream
     * closed, and that stream did not close. The bot sat at full capacity with
     * nothing running, no error, and a healthy process.
     *
     * Lichess knows the answer, so ask it rather than tracking a shadow copy.
     * Local state that mirrors remote state drifts, and this one drifts toward
     * "permanently busy", which is silent.
     */
    private int ongoingGames() {
        try {
            var resp = http.send(request("/api/account/playing").build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return 0;
            return countGames(resp.body());
        } catch (Exception e) {
            System.err.println("could not count games in progress: " + e.getMessage());
            // Zero, not "assume busy". Being wrong here costs a declined
            // challenge; being wrong the other way costs every game from now on.
            return 0;
        }
    }

    /** Occurrences of a gameId field, which is one per ongoing game. */
    static int countGames(String json) {
        if (json == null) return 0;
        int n = 0, i = 0;
        while ((i = json.indexOf("\"gameId\"", i)) >= 0) { n++; i += 8; }
        return n;
    }

    /**
     * One game at a time by default, and this is a strength decision rather than
     * a politeness one.
     *
     * The harness plays at fixed nodes, where two games sharing a core produce
     * identical results to one game with the core to itself (ADR 0011). Here the
     * clock is real, so a second game on the same core halves the nodes this one
     * searches and the engine plays measurably worse. A rating collected under
     * contention is a rating for a slower engine.
     */
    private final int maxGames;
    private final int clockLimit;
    private final int clockIncrement;

    public LichessBot(String token) {
        this(token, 1, 180, 2);
    }

    public LichessBot(String token, int maxGames, int clockLimit, int clockIncrement) {
        this.token = token;
        this.maxGames = maxGames;
        this.clockLimit = clockLimit;
        this.clockIncrement = clockIncrement;
    }

    public static void main(String[] args) throws Exception {
        Path tokenFile = Path.of(args.length > 0 ? args[0] : ".lichess-token");
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        int maxGames = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int limit    = args.length > 2 ? Integer.parseInt(args[2]) : 180;
        int inc      = args.length > 3 ? Integer.parseInt(args[3]) : 2;
        new LichessBot(token, maxGames, limit, inc).run();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .header("Authorization", "Bearer " + token);
    }

    /**
     * Sends a move, retrying, because dropping one costs the game.
     *
     * {@link #post} logs a failure and returns. For accept and decline that is
     * fine. For a move it is fatal in the quietest possible way: the move never
     * reaches Lichess, so the position never changes, so no new gameState
     * arrives, so nothing ever recomputes. The bot sits on a healthy stream with
     * a full event loop until it flags.
     *
     * Observed directly. Of the first six rated games, four were won by mate and
     * BOTH losses were on time, one of them immediately after:
     *
     * <pre>POST /api/bot/game/uYdGydZd/move/d6b4 failed:
     *     HTTP/1.1 header parser received no bytes</pre>
     *
     * That is a stale pooled connection, the same class of problem the HTTP/1.1
     * pin above addresses, and the only fix that helps is trying again.
     *
     * Resending a move is safe. If the first attempt actually landed, the retry
     * arrives when it is no longer our turn and Lichess rejects it, which is a
     * no-op. Losing the move is the expensive direction, not repeating it.
     */
    private boolean postMove(String gameId, String uci) {
        String path = "/api/bot/game/" + gameId + "/move/" + uci;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                var resp = http.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 200) return true;

                // 429 and 5xx are the server saying "not now", and the first
                // version returned on them. That is the same wedge as a dropped
                // connection: the move never lands, the position never changes,
                // no gameState arrives, and the game thread waits out the clock.
                if (code == 429 || code >= 500) {
                    System.err.println("move " + uci + " -> " + code + ", retrying");
                    if (attempt < 3) {
                        Thread.sleep(code == 429 ? 2_000L : 500L * attempt);
                        continue;
                    }
                    break;
                }

                // A 400 usually means the move already landed and it is no longer
                // our turn, which is success as far as the game goes. Retrying
                // would not help either way.
                System.out.println("move " + uci + " -> " + code + " " + summarise(resp.body()));
                return false;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                System.err.println("move " + uci + " attempt " + attempt + " failed: " + e.getMessage());
                if (attempt == 3) break;
                try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        System.err.println("GAVE UP sending " + uci + " in " + gameId + ", this game will flag");
        return false;
    }

    /**
     * Fire-and-report, for accept and decline.
     *
     * The body used to be discarded entirely, so a 429 on an accept printed
     * "accepting challenge X" and then nothing happened at all, with no way to
     * tell that from a challenge the opponent withdrew. The seek loop already
     * learned this lesson; this path had not.
     */
    private void post(String path) {
        try {
            var resp = http.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("POST " + path + " -> " + resp.statusCode()
                        + " " + summarise(resp.body()));
            }
        } catch (Exception e) {
            System.err.println("POST " + path + " failed: " + e.getMessage());
        }
    }

    /** A status code with the body that explains it. */
    record Resp(int status, String body) {}

    /**
     * Form POST that surfaces the status code AND the body, because the seek loop
     * has to tell four outcomes apart: the challenge was created, this opponent
     * cannot play us today, we are rate-limited, or something else broke.
     * {@link #post} discards all four, which is fine for accept/decline and
     * useless here.
     *
     * The body matters. Lichess returns 400 for "this bot has already played 100
     * games against other bots today", which is routine and means move on, and
     * also 400 for a genuinely malformed request, which means stop and fix it.
     * Only the body distinguishes them.
     */
    private Resp postForm(String path, String body) {
        try {
            var resp = http.send(request(path)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Resp(resp.statusCode(), resp.body());
        } catch (Exception e) {
            System.err.println("POST " + path + " failed: " + e.getMessage());
            return new Resp(-1, e.getMessage());
        }
    }

    /**
     * Usernames of the bots currently online, ourselves excluded.
     *
     * The endpoint returns newline-delimited JSON, one bot per line, not a JSON
     * array. Parsing it line by line is what lets {@link Json} stay a key-finder
     * rather than a real parser.
     */
    private List<String> onlineBots() {
        try {
            var resp = http.send(request("/api/bot/online?nb=50").build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.out.println("bot list returned " + resp.statusCode());
                return List.of();
            }
            return parseBots(resp.body(), username);
        } catch (Exception e) {
            System.err.println("could not list online bots: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Pulled out of the HTTP call so it can be tested without a network. Skips
     * blank lines, lines with no username, and our own account: Lichess accepts a
     * self-challenge and it would sit unanswered until it expired.
     */
    static List<String> parseBots(String ndjson, String self) {
        List<String> out = new ArrayList<>();
        if (ndjson == null) return out;
        for (String line : ndjson.split("\n")) {
            if (line.isBlank()) continue;
            String name = Json.string(line, "username");
            if (name == null || name.isBlank()) continue;
            if (name.equalsIgnoreCase(self)) continue;
            out.add(name);
        }
        return out;
    }

    /**
     * Challenges online bots to rated games whenever there is capacity.
     *
     * Rated is the whole point. An unrated game is a game that leaves no evidence.
     *
     * Bots decline constantly: wrong time control, already busy, an allow-list we
     * are not on. So a decline is not an error and is not retried. The loop walks
     * the list, and a fresh list every pass means a bot that has gone offline
     * drops out on its own.
     *
     * Backs off hard on 429. Lichess rate-limits challenge creation, and the
     * documented remedy is to wait a minute rather than to retry faster.
     */
    /** First line of an error body, trimmed, so one bad response cannot flood the log. */
    static String summarise(String body) {
        if (body == null || body.isBlank()) return "";
        String first = body.split("\n", 2)[0].trim();
        return first.length() > 160 ? first.substring(0, 160) + "..." : first;
    }

    private void seekLoop() {
        int cursor = 0;
        List<String> bots = List.of();
        while (true) {
            try {
                if (ongoingGames() >= maxGames) {
                    Thread.sleep(10_000);
                    continue;
                }
                if (cursor >= bots.size()) {
                    bots = new ArrayList<>(onlineBots());
                    // Shuffled because the endpoint returns the same popular bots
                    // in the same order, and those are the ones that hit their
                    // daily cap first. Walking it unshuffled means every pass
                    // starts with the names least able to play us.
                    java.util.Collections.shuffle(bots);
                    cursor = 0;
                    if (bots.isEmpty()) {
                        System.out.println("no bots online, waiting");
                        Thread.sleep(60_000);
                        continue;
                    }
                }
                String target = bots.get(cursor++);
                String body = "rated=true"
                        + "&clock.limit=" + clockLimit
                        + "&clock.increment=" + clockIncrement
                        + "&color=random"
                        + "&variant=standard";
                Resp r = postForm("/api/challenge/" + target, body);

                if (r.status() == 429) {
                    System.out.println("rate limited, backing off 60s");
                    Thread.sleep(60_000);
                } else if (r.status() == 400 && r.body() != null
                        && r.body().contains("bot.vsBot.day")) {
                    // Lichess caps bot-versus-bot at 100 games per bot per day.
                    // A capped opponent is not an error and not our problem: the
                    // only correct response is the next name on the list.
                    //
                    // Worth separating, because waiting on this the way we wait
                    // on a live challenge costs 25 seconds per capped bot, and
                    // the popular bots are capped first. That turns a full pass
                    // of the list from seconds into twenty minutes.
                    System.out.println(target + " is at its daily bot limit, skipping");
                } else if (r.status() == 200 || r.status() == 201) {
                    System.out.println("challenged " + target);
                    // A live challenge still has to be answered, and Lichess
                    // expires it on its own after about 20 seconds. Waiting a
                    // little longer keeps at most one outstanding at a time.
                    Thread.sleep(25_000);
                } else {
                    System.out.println("challenge to " + target + " -> " + r.status()
                            + " " + summarise(r.body()));
                    Thread.sleep(2_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("seek loop: " + e.getMessage());
                try { Thread.sleep(30_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Fetches our own username, retrying until the network cooperates.
     *
     * This used to be a bare send(), so any blip at startup threw out of main and
     * killed the process. Observed: UnresolvedAddressException, i.e. DNS was not
     * ready yet, which is ordinary for a launchd agent starting at login or
     * waking from sleep.
     *
     * launchd's KeepAlive did restart it, so nothing was permanently lost, but
     * relying on that is wrong. The event stream below already retries forever;
     * there is no reason startup should be the one fragile step, and a crash loop
     * against a throttle interval is a worse way to wait than simply waiting.
     */
    private void identify() throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                var acct = http.send(request("/api/account").build(),
                        HttpResponse.BodyHandlers.ofString());
                String name = Json.string(acct.body(), "username");
                if (name != null && !name.isBlank()) {
                    username = name;
                    return;
                }
                System.out.println("/api/account returned " + acct.statusCode()
                        + " with no username, retrying");
            } catch (Exception e) {
                System.out.println("startup: " + e.getMessage() + ", retrying (attempt " + attempt + ")");
            }
            // Backs off to a minute and stays there. A token that is actually
            // invalid will never succeed, and hammering it helps nobody.
            Thread.sleep(Math.min(60_000L, 2_000L * attempt));
        }
    }

    public void run() throws Exception {
        identify();
        System.out.println("playing as " + username + "  (https://lichess.org/@/" + username + ")");
        rejoinOngoing();

        Thread seeker = new Thread(this::seekLoop, "seek");
        seeker.setDaemon(true);
        seeker.start();
        System.out.printf("seeking rated %d+%d games, %d at a time%n",
                clockLimit / 60, clockIncrement, maxGames);

        // Lichess replays pending challenges to a freshly connected stream, so a
        // reconnect never loses one. It also closes the stream routinely, which is
        // why a clean end is logged the same as an error and simply reconnected.
        int attempt = 0;
        while (true) {
            try {
                attempt++;
                stream("/api/stream/event", this::onEvent);
                System.out.println("event stream ended cleanly, reconnecting (attempt " + attempt + ")");
            } catch (Exception e) {
                System.out.println("event stream error: " + e.getMessage()
                        + ", reconnecting (attempt " + attempt + ")");
            }
            Thread.sleep(2_000);
        }
    }

    /**
     * Rejoin anything already in progress.
     *
     * The event stream only announces games as they START, so a restart would
     * otherwise abandon a live game on the clock. Losing on time because the
     * process bounced is not a chess result.
     */
    private void rejoinOngoing() {
        try {
            var resp = http.send(request("/api/account/playing").build(),
                    HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            int i = 0;
            while ((i = body.indexOf("\"gameId\"", i)) >= 0) {
                String id = Json.string(body.substring(i), "gameId");
                i += 8;
                if (id == null) continue;
                System.out.println("rejoining game in progress: https://lichess.org/" + id);
                final String gid = id;
                games.submit(() -> playGame(gid));
            }
        } catch (Exception e) {
            System.err.println("could not check for games in progress: " + e.getMessage());
        }
    }

    /** Reads a newline-delimited JSON stream until it closes. Blank lines are keepalives. */
    private void stream(String path, java.util.function.Consumer<String> handler) throws Exception {
        HttpResponse<InputStream> resp = http.send(request(path).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException(path + " returned " + resp.statusCode());
        try (var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) handler.accept(line);
            }
        }
    }

    private void onEvent(String json) {
        String type = Json.string(json, "type");
        switch (type) {
            case "challenge" -> {
                // The id must come from inside the challenge object. Searching the
                // whole message would also match the challenger's id.
                String id = Json.nested(json, "challenge", "id");
                String challenge = Json.object(json, "challenge");

                // The event stream carries challenges we SENT as well as ones we
                // received, and they are the same event type. Without this the bot
                // tries to accept its own outgoing challenge, which is one wasted
                // request per seek and a line in the log claiming it accepted
                // something nobody offered.
                if (isOurs(challenge, username)) return;

                String variant = Json.nested(challenge, "variant", "key");
                boolean standard = variant == null || variant.equals("standard");

                // Accepting ignored maxGames entirely, so being challenged during
                // a game gave two games sharing one core. That is exactly the
                // contention the maxGames doc says invalidates a rating.
                if (standard && ongoingGames() >= maxGames) {
                    System.out.println("declining " + id + ": already at capacity");
                    post("/api/challenge/" + id + "/decline");
                    return;
                }

                // A correspondence or unlimited challenge has no clock, and the
                // 60-second fallback in playGame would then invent one and hand
                // the search a budget that has nothing to do with the real game.
                String speed = Json.string(challenge, "speed");
                if (standard && speed != null
                        && (speed.equals("correspondence") || speed.equals("unlimited"))) {
                    System.out.println("declining " + id + ": " + speed + ", no real clock");
                    post("/api/challenge/" + id + "/decline");
                    return;
                }

                if (standard) {
                    System.out.println("accepting challenge " + id);
                    post("/api/challenge/" + id + "/accept");
                } else {
                    System.out.println("declining " + id + ": variant " + variant);
                    post("/api/challenge/" + id + "/decline");
                }
            }
            case "gameStart" -> {
                // gameId, not id: the game object also carries fullId, a nested
                // status.id and a nested opponent.id.
                String id = Json.nested(json, "game", "gameId");
                System.out.println("game started: https://lichess.org/" + id);
                games.submit(() -> playGame(id));
            }
            case "gameFinish" -> System.out.println("game finished: " + Json.nested(json, "game", "gameId"));
            default -> { }
        }
    }

    /**
     * True when we are the challenger, so this is our own outgoing challenge
     * coming back to us on the event stream rather than an offer to accept.
     */
    static boolean isOurs(String challenge, String self) {
        if (challenge == null || self == null) return false;
        String challenger = Json.nested(challenge, "challenger", "id");
        if (challenger == null) challenger = Json.nested(challenge, "challenger", "name");
        return challenger != null && challenger.equalsIgnoreCase(self);
    }

    private void playGame(String gameId) {
        if (!playing.add(gameId)) {
            // Already have a thread on this one. See the field comment: a restart
            // announces a live game through both rejoinOngoing and gameStart.
            return;
        }
        Search search = new Search(new Psqt());
        search.tt = new TranspositionTable(64);
        search.ordering = new Ordering();
        final boolean[] weAreWhite = new boolean[1];
        final boolean[] known = new boolean[1];

        // Reconnects for as long as Lichess still says the game is live.
        //
        // This used to call stream() once. Any drop on the game stream (a TCP
        // reset, a server rotation, the same class of failure the HTTP/1.1 pin
        // and the postMove retry both exist for) ran the finally, forgot the
        // game, and left it to flag. Nothing re-announced it: rejoinOngoing runs
        // only at startup and the event stream announces a game once, at
        // gameStart. Worse, ongoingGames() still counted it, so the seek loop
        // stayed parked at capacity. No new games, no error, healthy process.
        try {
            int failures = 0;
            while (true) {
                try {
                    streamGame(gameId, search, weAreWhite, known);
                    failures = 0;
                } catch (Exception e) {
                    System.err.println("game " + gameId + " stream: " + e.getMessage());
                    failures++;
                }
                if (!stillPlaying(gameId)) break;
                if (failures > 20) {
                    System.err.println("GIVING UP on " + gameId + " after " + failures + " stream failures");
                    break;
                }
                Thread.sleep(Math.min(5_000L, 500L * Math.max(1, failures)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            playing.remove(gameId);
        }
    }

    /** True while Lichess still lists this game as ours and in progress. */
    private boolean stillPlaying(String gameId) {
        try {
            var resp = http.send(request("/api/account/playing").build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body() != null && resp.body().contains(gameId);
        } catch (Exception e) {
            // Assume it is still live: reconnecting to a finished game is a
            // wasted request, dropping a live one loses it on the clock.
            return true;
        }
    }

    private void streamGame(String gameId, Search search, boolean[] weAreWhite, boolean[] known)
            throws Exception {
        {
            stream("/api/bot/game/stream/" + gameId, json -> {
                String type = Json.string(json, "type");
                String moves, state = json;
                if (type.equals("gameFull")) {
                    String white = Json.nested(json, "white", "id");
                    weAreWhite[0] = username.equalsIgnoreCase(white);
                    known[0] = true;
                    state = Json.object(json, "state");
                    if (state == null) return;
                } else if (!type.equals("gameState")) {
                    return;   // chatLine, opponentGone
                }
                if (!known[0]) return;

                String status = Json.string(state, "status");
                if (status != null && !status.equals("started")) return;

                moves = Json.string(state, "moves");
                if (moves == null) moves = "";

                int plies = moves.isBlank() ? 0 : moves.trim().split("\\s+").length;
                boolean ourTurn = (plies % 2 == 0) == weAreWhite[0];
                if (!ourTurn) return;

                long wtime = Json.number(state, "wtime", 60_000);
                long btime = Json.number(state, "btime", 60_000);
                long winc = Json.number(state, "winc", 0);
                long binc = Json.number(state, "binc", 0);

                Board board = Fen.parse(Fen.START);
                for (String u : moves.trim().split("\\s+")) {
                    if (u.isEmpty()) continue;
                    int m = resolve(board, u);
                    if (m == Move.NONE) {
                        // Every gameState replays the whole move list, so one
                        // unresolvable move silences this game permanently. It
                        // used to do that without printing anything.
                        System.err.println("game " + gameId + ": cannot resolve move '" + u + "'");
                        return;
                    }
                    board.make(m);
                }

                SearchLimits limits = new SearchLimits();
                limits.wtime = wtime; limits.btime = btime;
                limits.winc = winc;   limits.binc = binc;

                search.think(board, limits);
                if (search.bestMove == Move.NONE) return;
                postMove(gameId, Move.toUci(search.bestMove));
            });
        }
    }

    private static int resolve(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        return Move.NONE;
    }
}
