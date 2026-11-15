package com.mekaoptimization;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Human-like timing. Delays are bell-shaped inside the given range (most values near
 * the middle, rare values near the edges) and the same value is never used twice in a row.
 */
final class Humanizer {
    private static int last = -1;

    private Humanizer() {
    }

    /** Random delay in ms, min..max inclusive. */
    static synchronized long delay(int minMs, int maxMs) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int value;
        int guard = 0;
        do {
            // average of two uniform samples = triangular (bell-like) distribution
            double a = (r.nextDouble() + r.nextDouble()) / 2.0;
            value = minMs + (int) Math.round(a * (maxMs - minMs));
        } while (value == last && ++guard < 6);
        last = value;
        return value;
    }
}
