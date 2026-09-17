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

    static String string(String json, String key) {
        if (json == null) return null;
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int q = json.indexOf('"', colon);
        if (q < 0) return null;
        int end = q + 1;
        StringBuilder sb = new StringBuilder();
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"') break;
            sb.append(c);
            end++;
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
        while (p < json.length() && (Character.isDigit(json.charAt(p)) || json.charAt(p) == '-')) p++;
        try {
            return Long.parseLong(json.substring(start, p));
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
