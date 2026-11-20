package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.internal.codec.Octets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * The SCRAM key cache ([CLIENT The SCRAM key cache]): after an exchange the server proved, the keys
 * a password derived under the server's salt and iterations are kept in the process's memory, so a
 * login meeting the same salt and iterations skips PBKDF2, the slow step; as Turborel's {@code
 * client/src/scram/cache.rs}.
 *
 * <p>An entry is found by the salt, the iterations and a tag of the password, an HMAC of it under a
 * random secret of the cache's own, so a wrong password finds nothing and the cache holds neither
 * the password nor anything it could be guessed from offline. It holds {@link #ENTRIES} at most,
 * the oldest dropped first, and drops an entry when a login that used it fails. It is safe to share
 * across threads.
 */
public final class KeyCache {
    /** The most entries a cache holds: a process's servers and users, few. */
    public static final int ENTRIES = 64;

    private final byte[] secret = Crypto.nonce().getBytes(StandardCharsets.US_ASCII);
    private final Deque<Entry> held = new ArrayDeque<>();
    private long hits;
    private long misses;

    /** An empty cache, its secret new. */
    public KeyCache() {
        // The secret is made above, of the system's randomness.
    }

    /**
     * The keys a password derives under a salt and iterations: the cache's, or derived, outside
     * the lock, and kept.
     *
     * @param password the password
     * @param salt the salt
     * @param iterations the iterations
     * @return the keys
     */
    Derived derived(String password, Octets salt, long iterations) {
        Index index = index(password, salt, iterations);
        synchronized (this) {
            for (Entry entry : held) {
                if (entry.index.equals(index)) {
                    hits++;
                    return entry.keys;
                }
            }
            misses++;
        }
        Derived keys = Derived.of(password, salt.toByteArray(), iterations);
        synchronized (this) {
            if (held.stream().noneMatch(e -> e.index.equals(index))) {
                if (held.size() >= ENTRIES) {
                    held.removeFirst();
                }
                held.addLast(new Entry(index, keys));
            }
        }
        return keys;
    }

    /**
     * Drops the keys of a password under a salt and iterations, as an exchange by them failed.
     *
     * @param password the password
     * @param salt the salt
     * @param iterations the iterations
     */
    synchronized void forget(String password, Octets salt, long iterations) {
        Index index = index(password, salt, iterations);
        for (Iterator<Entry> it = held.iterator(); it.hasNext(); ) {
            if (it.next().index.equals(index)) {
                it.remove();
            }
        }
    }

    /**
     * How many entries it holds.
     *
     * @return the count
     */
    public synchronized int size() {
        return held.size();
    }

    /**
     * How many lookups found their keys, and how many derived them.
     *
     * @return hits, then misses
     */
    public synchronized long[] lookups() {
        return new long[] {hits, misses};
    }

    /** Drops every entry. */
    public synchronized void clear() {
        held.clear();
    }

    /** Never the secret, nor the keys. */
    @Override
    public synchronized String toString() {
        return "KeyCache(" + held.size() + " entries)";
    }

    private Index index(String password, Octets salt, long iterations) {
        Octets tag = Octets.of(Crypto.hmac(secret, password.getBytes(StandardCharsets.UTF_8)));
        return new Index(tag, salt, iterations);
    }

    /**
     * What an entry is found by.
     *
     * @param tag the password's tag under the cache's secret
     * @param salt the salt
     * @param iterations the iterations
     */
    private record Index(Octets tag, Octets salt, long iterations) {}

    /**
     * An entry: what it is found by, and its keys.
     *
     * @param index its index
     * @param keys its keys
     */
    private record Entry(Index index, Derived keys) {}
}
