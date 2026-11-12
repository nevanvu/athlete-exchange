package com.athletex.events;

import java.security.SecureRandom;
import java.util.Random;

/**
 * ULID: 48-bit millisecond timestamp + 80 bits of randomness, Crockford base32, 26 chars,
 * lexicographically sortable by time. Accepts an explicit clock and random source so the
 * simulator can mint reproducible ids.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String generate() {
        return generate(System.currentTimeMillis(), SECURE_RANDOM);
    }

    public static String generate(long timeMillis, Random random) {
        char[] out = new char[26];
        long time = timeMillis & 0xFFFFFFFFFFFFL;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        long high = random.nextLong() & 0xFFFFFFFFFFL; // 40 bits
        long low = random.nextLong() & 0xFFFFFFFFFFL;  // 40 bits
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (high & 0x1F)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (low & 0x1F)];
            low >>>= 5;
        }
        return new String(out);
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() != 26) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (new String(ALPHABET).indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }
}
