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

    public String key() {
        return openingIndex + ":" + pairIndex;
    }

    public static Job fromKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("bad job key: " + key);
        return new Job(Integer.parseInt(key.substring(0, colon)),
                       Integer.parseInt(key.substring(colon + 1)));
    }
}
