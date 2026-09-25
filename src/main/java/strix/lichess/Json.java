package strix.lichess;

/**
 * Just enough JSON reading for the Lichess bot streams, so the project keeps
 * zero runtime dependencies.
 *
 * This is deliberately not a JSON parser. It finds a key and reads the value
 * after it, which is sufficient for the flat, known-shape messages Lichess sends
 * and would be wrong for anything else.
 */
final class Json {

    private Json() {}

    /**
     * The value of a STRING field, or null if the field is absent or holds
     * something else.
     *
     * The "or holds something else" is load-bearing. An earlier version skipped
     * ahead to the next quote after the colon, so on {@code "id":20,"name":"started"}
     * it sailed past the number and returned "started". That turned a game id into
     * the literal string "name" and produced a 404 on the game stream.
     */
    static String string(String json, String key) {
        if (json == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;

        int q = colon + 1;
        while (q < json.length() && Character.isWhitespace(json.charAt(q))) q++;
        if (q >= json.length() || json.charAt(q) != '"') return null;   // not a string

        int end = q + 1;
        StringBuilder sb = new StringBuilder();
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"') break;
            if (c != '\\') { sb.append(c); end++; continue; }

            // An escape used to be skipped with `end += 2` and nothing appended,
            // so the escaped character was DELETED: {"text":"say \"hi\""} came
            // back as `say hi`, and a unicode escape appended its four hex
            // digits as literals. Harmless for the ASCII ids the bot reads,
            // wrong for anything else, and wrong silently.
            // (Written without the backslash-u spelling on purpose: javac
            // expands unicode escapes inside comments too, and it does not
            // compile.)
            if (end + 1 >= json.length()) break;
            char esc = json.charAt(end + 1);
            switch (esc) {
                case 'n' -> { sb.append('\n'); end += 2; }
                case 't' -> { sb.append('\t'); end += 2; }
                case 'r' -> { sb.append('\r'); end += 2; }
                case 'b' -> { sb.append('\b'); end += 2; }
                case 'f' -> { sb.append('\f'); end += 2; }
                case 'u' -> {
                    if (end + 5 < json.length()) {
                        try {
                            sb.append((char) Integer.parseInt(json.substring(end + 2, end + 6), 16));
                            end += 6;
                        } catch (NumberFormatException e) { sb.append(esc); end += 2; }
                    } else { sb.append(esc); end += 2; }
                }
                default -> { sb.append(esc); end += 2; }   // \" \\ \/ and anything else
            }
        }
        return sb.toString();
    }

    static long number(String json, String key, long fallback) {
        if (json == null) return fallback;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return fallback;
        int colon = json.indexOf(':', i);
        if (colon < 0) return fallback;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        int start = p;
        if (p < json.length() && (json.charAt(p) == '-' || json.charAt(p) == '+')) p++;
        while (p < json.length() && Character.isDigit(json.charAt(p))) p++;
        int intEnd = p;
        // Accept a fractional part rather than stopping at the dot and leaving
        // the caller with a truncated integer. Lichess sends increments as
        // whole numbers today, but "1.75" silently became 1.
        boolean fractional = p < json.length() && json.charAt(p) == '.';
        if (fractional) {
            p++;
            while (p < json.length() && Character.isDigit(json.charAt(p))) p++;
        }
        try {
            if (fractional) return (long) Double.parseDouble(json.substring(start, p));
            return Long.parseLong(json.substring(start, intEnd));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The raw text of a nested object, braces included. */
    static String object(String json, String key) {
        if (json == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int brace = json.indexOf('{', i);
        if (brace < 0) return null;

        // The brace has to be THIS key's value. Searching forward for the next
        // one meant that on a non-object value the search sailed into a later
        // key's object: nested(challenge, "variant", "key") returned a field
        // from "challenger" when "variant" happened to be a bare string.
        int colon = json.indexOf(':', i);
        if (colon < 0 || colon > brace) return null;
        for (int p = colon + 1; p < brace; p++) {
            if (!Character.isWhitespace(json.charAt(p))) return null;
        }
        int depth = 0;
        for (int p = brace; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(brace, p + 1);
        }
        return null;
    }

    static String nested(String json, String objectKey, String key) {
        return string(object(json, objectKey), key);
    }
}
