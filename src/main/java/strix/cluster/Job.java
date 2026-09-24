package strix.cluster;

/**
 * One unit of distributable work: a PAIR of games, the same opening played twice
 * with colours reversed.
 *
 * The pair is the unit rather than the game because the pentanomial model scores
 * a pair jointly, so half a pair is not an observation the statistical test can
 * consume. That constraint predates the cluster and is the reason a worker plays
 * two games before reporting anything.
 *
 * {@link #key()} is the identity the whole protocol turns on. It is derived from
 * the job's coordinates rather than assigned by the coordinator, which means two
 * workers handed the same job independently produce the same key, and the
 * coordinator can recognise the second result as a duplicate without any shared
 * state beyond the key itself.
 */
public record Job(int openingIndex, int pairIndex) {

    /**
     * The pair index alone, because that alone determines the work.
     *
     * It used to be {@code openingIndex + ":" + pairIndex}, and openingIndex is
     * {@code pairIndex % Openings.size()}. The book size was therefore baked
     * into the identity: add or remove one opening between a crash and a resume
     * and every key past the old size mapped to a different string. The log
     * would not match, so those pairs were replayed into the Sprt from the log
     * AND requeued under new keys, re-run, and recorded a second time. The same
     * games moved the LLR twice under two identities with nothing counting a
     * duplicate.
     *
     * Since Openings.lineFor takes the pair index, the opening index is now
     * purely informational and has no business in the identity.
     */
    public String key() {
        return String.valueOf(pairIndex);
    }

    /** Accepts the legacy {@code opening:pair} form so existing logs still resume. */
    public static Job fromKey(String key) {
        String k = key.trim();
        int colon = k.indexOf(':');
        int pair = Integer.parseInt(colon < 0 ? k : k.substring(colon + 1));
        return new Job(Math.floorMod(pair, strix.harness.Openings.size()), pair);
    }

    /** Legacy keys normalise to the new form, so a resumed log compares correctly. */
    public static String normaliseKey(String key) {
        return fromKey(key).key();
    }
}
