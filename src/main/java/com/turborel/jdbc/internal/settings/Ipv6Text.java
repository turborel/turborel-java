package com.turborel.jdbc.internal.settings;

/**
 * An IPv6 address in its shortest text, as RFC 5952 §4 lays it and Rust writes it: each group in
 * lower-case hexadecimal without leading zeros, the longest run of two zero groups or more, the
 * first of the longest, written {@code ::}; an IPv4-mapped address as {@code ::ffff:} and its
 * dotted IPv4 address (RFC 5952 §5).
 */
final class Ipv6Text {
    /** The groups of an IPv6 address. */
    private static final int GROUPS = 8;

    /** The octets of a group. */
    private static final int GROUP_OCTETS = 2;

    /** Where no run of zero groups is to be written {@code ::}. */
    private static final int NONE = -1;

    /** The group of an IPv4-mapped address before its IPv4 address. */
    private static final int MAPPED = 0xFFFF;

    /** The bits of an octet, and the mask of one. */
    private static final int OCTET = 8;

    private static final int MASK = 0xFF;

    /** The group before an IPv4-mapped address's IPv4 address. */
    private static final int MAPPED_AT = 5;

    private Ipv6Text() {}

    /**
     * The text of the address of 16 octets {@code octets}.
     *
     * @param octets the address
     * @return its text
     */
    static String of(byte[] octets) {
        int[] groups = new int[GROUPS];
        for (int i = 0; i < GROUPS; i++) {
            groups[i] = ((octets[GROUP_OCTETS * i] & MASK) << OCTET) | (octets[GROUP_OCTETS * i + 1] & MASK);
        }
        if (mapped(groups)) {
            int high = groups[MAPPED_AT + 1];
            int low = groups[GROUPS - 1];
            return "::ffff:" + (high >> OCTET) + "." + (high & MASK) + "." + (low >> OCTET) + "." + (low & MASK);
        }
        int bestAt = NONE;
        int bestLength = 1;
        for (int i = 0; i < GROUPS; ) {
            int j = i;
            while (j < GROUPS && groups[j] == 0) {
                j++;
            }
            if (j - i > bestLength) {
                bestAt = i;
                bestLength = j - i;
            }
            i = j == i ? i + 1 : j;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < GROUPS; i++) {
            if (i == bestAt) {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (text.length() > 0 && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }

    private static boolean mapped(int[] groups) {
        for (int i = 0; i < MAPPED_AT; i++) {
            if (groups[i] != 0) {
                return false;
            }
        }
        return groups[MAPPED_AT] == MAPPED;
    }
}
