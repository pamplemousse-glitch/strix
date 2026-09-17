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

    public LichessBot(String token) {
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        Path tokenFile = Path.of(args.length > 0 ? args[0] : ".lichess-token");
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        new LichessBot(token).run();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .header("Authorization", "Bearer " + token);
    }

    private void post(String path) {
        try {
            http.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("POST " + path + " failed: " + e.getMessage());
        }
    }

    public void run() throws Exception {
        var acct = http.send(request("/api/account").build(), HttpResponse.BodyHandlers.ofString());
        username = Json.string(acct.body(), "username");
        System.out.println("playing as " + username + "  (https://lichess.org/@/" + username + ")");
        rejoinOngoing();
        System.out.println("waiting for challenges...");

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
                String variant = Json.nested(challenge, "variant", "key");
                boolean standard = variant == null || variant.equals("standard");
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

    private void playGame(String gameId) {
        Search search = new Search(new Psqt());
        search.tt = new TranspositionTable(64);
        search.ordering = new Ordering();
        final boolean[] weAreWhite = new boolean[1];
        final boolean[] known = new boolean[1];

        try {
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
                    if (m == Move.NONE) return;
                    board.make(m);
                }

                SearchLimits limits = new SearchLimits();
                limits.wtime = wtime; limits.btime = btime;
                limits.winc = winc;   limits.binc = binc;

                search.think(board, limits);
                if (search.bestMove == Move.NONE) return;
                post("/api/bot/game/" + gameId + "/move/" + Move.toUci(search.bestMove));
            });
        } catch (Exception e) {
            System.err.println("game " + gameId + " stream ended: " + e.getMessage());
        }
    }

    private static int resolve(Board b, String uci) {
        int[] m = new int[MoveGen.MAX_MOVES];
        int n = MoveGen.generateLegal(b, m, new int[MoveGen.MAX_MOVES]);
        for (int i = 0; i < n; i++) if (Move.toUci(m[i]).equals(uci)) return m[i];
        return Move.NONE;
    }
}
