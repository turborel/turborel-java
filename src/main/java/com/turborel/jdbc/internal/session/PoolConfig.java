package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.transport.Timeouts;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a pool keeps its connections ([CLIENT Pools]), as Turborel's {@code PoolConfig}.
 *
 * @param max the most connections made at once, handed out and idle
 * @param maxIdle the most kept idle
 * @param idleTimeout how long one may stay idle; none, as long as it lives
 * @param maxLifetime how long one lives from when it was made; none, without limit
 * @param maxWait how long {@link Pool#get} waits for one to come back, the reference's {@code wait}
 * @param check whether an idle connection is checked to answer before it is handed out
 * @param reset statements run, after the pool's own, as each connection comes back: what else a
 *     program's sessions change and the next must not see
 */
public record PoolConfig(
        int max,
        int maxIdle,
        @Nullable Duration idleTimeout,
        @Nullable Duration maxLifetime,
        Duration maxWait,
        boolean check,
        List<String> reset) {
    /** The connections a pool makes at most by default. */
    public static final int DEFAULT_MAX = 10;

    /** How long {@link Pool#get} waits by default for a connection to come back: as long as a step of connecting. */
    public static final Duration DEFAULT_WAIT = Timeouts.CONNECT_WITHIN;

    /** How long a connection may stay idle by default: ten minutes. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    /** How long a connection lives by default: an hour, so a pool moves to servers added and settings changed in time. */
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofHours(1);

    /**
     * A configuration of the parts given, the statements copied.
     *
     * @param max the most connections made at once
     * @param maxIdle the most kept idle
     * @param idleTimeout how long one may stay idle, or none
     * @param maxLifetime how long one lives, or none
     * @param maxWait how long a get waits
     * @param check whether an idle connection is checked
     * @param reset the program's statements of a reset
     */
    public PoolConfig {
        reset = List.copyOf(reset);
    }

    /**
     * The defaults: 10 connections, 10 idle, ten minutes idle, an hour's life, 30 seconds' wait,
     * checked, no statements of the program's.
     *
     * @return the defaults
     */
    public static PoolConfig defaults() {
        return new PoolConfig(
                DEFAULT_MAX, DEFAULT_MAX, DEFAULT_IDLE_TIMEOUT, DEFAULT_MAX_LIFETIME, DEFAULT_WAIT, true, List.of());
    }

    /**
     * These, of another most connections made.
     *
     * @param most the most
     * @return the configuration
     */
    public PoolConfig withMax(int most) {
        return new PoolConfig(most, maxIdle, idleTimeout, maxLifetime, maxWait, check, reset);
    }

    /**
     * These, of another most kept idle.
     *
     * @param most the most
     * @return the configuration
     */
    public PoolConfig withMaxIdle(int most) {
        return new PoolConfig(max, most, idleTimeout, maxLifetime, maxWait, check, reset);
    }

    /**
     * These, of another idle timeout.
     *
     * @param most how long one may stay idle, or none
     * @return the configuration
     */
    public PoolConfig withIdleTimeout(@Nullable Duration most) {
        return new PoolConfig(max, maxIdle, most, maxLifetime, maxWait, check, reset);
    }

    /**
     * These, of another lifetime.
     *
     * @param most how long one lives, or none
     * @return the configuration
     */
    public PoolConfig withMaxLifetime(@Nullable Duration most) {
        return new PoolConfig(max, maxIdle, idleTimeout, most, maxWait, check, reset);
    }

    /**
     * These, of another wait for a connection to come back.
     *
     * @param most how long a get waits
     * @return the configuration
     */
    public PoolConfig withMaxWait(Duration most) {
        return new PoolConfig(max, maxIdle, idleTimeout, maxLifetime, most, check, reset);
    }

    /**
     * These, checking an idle connection or not.
     *
     * @param checked whether an idle connection is checked
     * @return the configuration
     */
    public PoolConfig withCheck(boolean checked) {
        return new PoolConfig(max, maxIdle, idleTimeout, maxLifetime, maxWait, checked, reset);
    }

    /**
     * These, of other statements of the program's reset.
     *
     * @param statements the statements
     * @return the configuration
     */
    public PoolConfig withReset(List<String> statements) {
        return new PoolConfig(max, maxIdle, idleTimeout, maxLifetime, maxWait, check, statements);
    }
}
