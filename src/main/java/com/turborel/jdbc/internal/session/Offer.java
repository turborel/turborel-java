package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.codec.Attribute;
import com.turborel.jdbc.internal.codec.Compression;
import java.util.ArrayList;
import java.util.List;

/**
 * What a connection asks for as it opens ([CLIENT What a connection asks for]): the capabilities,
 * the connection's attributes, and the shortest request sent compressed once compression is
 * granted.
 *
 * @param capabilities the capabilities asked for, by name
 * @param attributes the connection's attributes, which the server shows in
 *     INFORMATION_SCHEMA.CONNECTION_ATTRIBUTES
 * @param compressFrom the shortest request sent compressed, once compression is granted
 */
public record Offer(List<String> capabilities, List<Attribute> attributes, int compressFrom) {
    /** The connection attribute that names the program. */
    public static final String APPLICATION = "application";

    /**
     * An offer of the parts given.
     *
     * @param capabilities the capabilities, copied
     * @param attributes the attributes, copied
     * @param compressFrom the compression threshold
     */
    public Offer {
        capabilities = List.copyOf(capabilities);
        attributes = List.copyOf(attributes);
    }

    /**
     * Every capability but compression, which costs time where the network is fast; no
     * attributes.
     *
     * @return the default offer
     */
    public static Offer standard() {
        List<String> all = new ArrayList<>(Capability.ALL);
        all.remove(Capability.COMPRESSION);
        return new Offer(all, List.of(), Compression.DEFAULT_THRESHOLD);
    }

    /**
     * No capability: the protocol's first form, byte for byte.
     *
     * @return an offer of none
     */
    public static Offer none() {
        return new Offer(List.of(), List.of(), Compression.DEFAULT_THRESHOLD);
    }

    /**
     * As it is, asking for compression too.
     *
     * @return the offer with compression
     */
    public Offer compressed() {
        if (capabilities.contains(Capability.COMPRESSION)) {
            return this;
        }
        List<String> more = new ArrayList<>(capabilities);
        more.add(Capability.COMPRESSION);
        return new Offer(more, attributes, compressFrom);
    }

    /**
     * As it is, with a connection attribute.
     *
     * @param name its name
     * @param value its value
     * @return the offer with it
     */
    public Offer attribute(String name, String value) {
        List<Attribute> more = new ArrayList<>(attributes);
        more.add(new Attribute(name, value));
        return new Offer(capabilities, more, compressFrom);
    }
}
