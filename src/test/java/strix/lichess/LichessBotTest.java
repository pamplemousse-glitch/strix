package strix.lichess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LichessBotTest {

    /** One bot per line, which is what /api/bot/online actually returns. */
    private static final String NDJSON = """
            {"id":"maia1","username":"maia1","title":"BOT","perfs":{"blitz":{"rating":1500}}}
            {"id":"leela","username":"LeelaQueenOdds","title":"BOT","perfs":{"blitz":{"rating":2400}}}
            {"id":"strix","username":"antoinepamplemousse","title":"BOT"}
            """;

    @Test @DisplayName("Reads one bot per line, not a JSON array")
    void parsesNdjson() {
        assertEquals(List.of("maia1", "LeelaQueenOdds"),
                LichessBot.parseBots(NDJSON, "antoinepamplemousse"));
    }

    @Test @DisplayName("Skips our own account")
    void skipsSelf() {
        // Lichess accepts a self-challenge. It then sits unanswered until it
        // expires, which burns a seek slot and looks like every bot declining.
        assertFalse(LichessBot.parseBots(NDJSON, "antoinepamplemousse")
                .contains("antoinepamplemousse"));
        assertTrue(LichessBot.parseBots(NDJSON, "someoneelse")
                .contains("antoinepamplemousse"));
    }

    @Test @DisplayName("Case-insensitive on our own name")
    void selfMatchIsCaseInsensitive() {
        // /api/account and /api/bot/online do not agree on casing.
        assertEquals(2, LichessBot.parseBots(NDJSON, "AntoinePamplemousse").size());
    }

    @Test @DisplayName("Blank lines are keepalives, not bots")
    void ignoresBlankLines() {
        String withKeepalives = "\n\n" + NDJSON + "\n   \n";
        assertEquals(2, LichessBot.parseBots(withKeepalives, "antoinepamplemousse").size());
    }

    @Test @DisplayName("A line with no username is skipped rather than challenged")
    void skipsMalformed() {
        // A null here would become POST /api/challenge/null, a 404 per pass that
        // looks exactly like every bot refusing.
        String malformed = "{\"id\":\"x\",\"title\":\"BOT\"}\n" + NDJSON;
        List<String> bots = LichessBot.parseBots(malformed, "antoinepamplemousse");
        assertEquals(2, bots.size());
        assertFalse(bots.contains(null));
    }

    @Test @DisplayName("Empty and null input give an empty list, not a crash")
    void handlesEmpty() {
        assertTrue(LichessBot.parseBots("", "me").isEmpty());
        assertTrue(LichessBot.parseBots(null, "me").isEmpty());
    }

    // ---- our own challenges coming back at us ----

    private static final String INBOUND = """
            {"id":"aB3dEfGh","challenger":{"id":"eubos","name":"Eubos"},\
            "destUser":{"id":"antoinepamplemousse"},"variant":{"key":"standard"}}""";

    private static final String OUTBOUND = """
            {"id":"zZ9yXwVu","challenger":{"id":"antoinepamplemousse","name":"antoinepamplemousse"},\
            "destUser":{"id":"eubos"},"variant":{"key":"standard"}}""";

    @Test @DisplayName("A challenge we sent is recognised as ours")
    void detectsOutbound() {
        // The event stream uses one type for both directions. Accepting our own
        // outgoing challenge is a wasted request and a log line claiming we
        // accepted something nobody offered.
        assertTrue(LichessBot.isOurs(OUTBOUND, "antoinepamplemousse"));
        assertFalse(LichessBot.isOurs(INBOUND, "antoinepamplemousse"));
    }

    @Test @DisplayName("Direction check is case-insensitive and null-safe")
    void outboundEdgeCases() {
        assertTrue(LichessBot.isOurs(OUTBOUND, "AntoinePamplemousse"));
        assertFalse(LichessBot.isOurs(null, "antoinepamplemousse"));
        assertFalse(LichessBot.isOurs(OUTBOUND, null));
        assertFalse(LichessBot.isOurs("{\"id\":\"x\"}", "antoinepamplemousse"));
    }

    @Test @DisplayName("Counts one ongoing game per gameId")
    void countsOngoingGames() {
        // The wedge this replaced: an in-memory counter that was never released
        // when a game stream failed to close. The bot then sat at capacity with
        // nothing running, no error, and a healthy process.
        assertEquals(0, LichessBot.countGames("{\"nowPlaying\":[]}"));
        assertEquals(1, LichessBot.countGames("{\"nowPlaying\":[{\"gameId\":\"abc\"}]}"));
        assertEquals(2, LichessBot.countGames(
                "{\"nowPlaying\":[{\"gameId\":\"abc\"},{\"gameId\":\"xyz\"}]}"));
        assertEquals(0, LichessBot.countGames(null));
        assertEquals(0, LichessBot.countGames(""));
    }

    @Test @DisplayName("Error bodies are summarised to one short line")
    void summarisesErrors() {
        assertEquals("", LichessBot.summarise(null));
        assertEquals("", LichessBot.summarise("   "));
        assertEquals("{\"error\":\"nope\"}", LichessBot.summarise("{\"error\":\"nope\"}\nsecond line"));
        assertTrue(LichessBot.summarise("x".repeat(500)).endsWith("..."));
        assertEquals(163, LichessBot.summarise("x".repeat(500)).length());
    }

    @Test @DisplayName("A null-message exception still names itself")
    void describesNamelessExceptions() {
        // Observed in the real log: "POST /api/challenge/x failed: null" and
        // "game lOh0MvZw stream: null". getMessage() is null for plenty of
        // IOExceptions, so the line carried no information at all.
        assertEquals("IOException", LichessBot.describe(new java.io.IOException()));
        assertEquals("IOException: boom", LichessBot.describe(new java.io.IOException("boom")));
        assertEquals("unknown", LichessBot.describe(null));
        assertTrue(LichessBot.describe(
                new java.io.IOException(new IllegalStateException("root cause")))
                .contains("root cause"));
    }
}