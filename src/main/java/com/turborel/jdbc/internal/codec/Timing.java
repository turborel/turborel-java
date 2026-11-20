package com.turborel.jdbc.internal.codec;

/**
 * The server's time for a statement, tag 18 of a Complete, in microseconds ([PROTOCOL Complete
 * 'K']), each a {@code u64}'s bits.
 *
 * @param total from when a worker began the request until the completion was ready
 * @param queued the time the request waited for a worker before that, not part of total
 * @param locks the part of total spent waiting for locks
 */
public record Timing(long total, long queued, long locks) {}
