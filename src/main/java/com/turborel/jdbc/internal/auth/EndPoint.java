package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.internal.codec.Octets;
import com.turborel.jdbc.internal.tls.Der;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The channel binding {@code tls-server-end-point} [RFC 5929 §4] ([PROTOCOL Mechanisms]): the hash
 * of the server's certificate, by a hash its signature algorithm chooses, as Turborel's {@code
 * protocol/src/scram/binding.rs}: of MD5 or SHA-1, SHA-256; of SHA-256, SHA-384 or SHA-512, that;
 * of no hash, as Ed25519's, or of two, as RSASSA-PSS of a mask hash not its message hash, or of an
 * algorithm not known, none [RFC 5929 §4.1].
 */
final class EndPoint {
    // The bodies of the object identifiers, without tag and length [X.690 §8.19].
    private static final byte[] MD5_RSA = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x04);
    private static final byte[] SHA1_RSA = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x05);
    private static final byte[] MGF1 = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x08);
    private static final byte[] RSA_PSS = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0a);
    private static final byte[] SHA256_RSA = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b);
    private static final byte[] SHA384_RSA = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0c);
    private static final byte[] SHA512_RSA = oid(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0d);
    private static final byte[] SHA1_ECDSA = oid(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x01);
    private static final byte[] SHA256_ECDSA = oid(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02);
    private static final byte[] SHA384_ECDSA = oid(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x03);
    private static final byte[] SHA512_ECDSA = oid(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x04);
    private static final byte[] SHA1 = oid(0x2b, 0x0e, 0x03, 0x02, 0x1a);
    private static final byte[] SHA256 = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01);
    private static final byte[] SHA384 = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x02);
    private static final byte[] SHA512 = oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x03);

    /** The tag of RSASSA-PSS-params' hashAlgorithm, {@code [0]} explicit [RFC 4055 §3.1]. */
    private static final int PSS_HASH = 0xa0;

    /** The tag of RSASSA-PSS-params' maskGenAlgorithm, {@code [1]} explicit. */
    private static final int PSS_MASK = 0xa1;

    /** The JDK's names of the hashes that bind. */
    private static final String SHA_256 = "SHA-256";

    private static final String SHA_384 = "SHA-384";
    private static final String SHA_512 = "SHA-512";

    /** The signature algorithms of one hash, and the hash that binds a certificate they sign. */
    private static final Map<Octets, String> SIGNATURES = Map.of(
            Octets.of(MD5_RSA), SHA_256,
            Octets.of(SHA1_RSA), SHA_256,
            Octets.of(SHA256_RSA), SHA_256,
            Octets.of(SHA384_RSA), SHA_384,
            Octets.of(SHA512_RSA), SHA_512,
            Octets.of(SHA1_ECDSA), SHA_256,
            Octets.of(SHA256_ECDSA), SHA_256,
            Octets.of(SHA384_ECDSA), SHA_384,
            Octets.of(SHA512_ECDSA), SHA_512);

    /** The hashes RSASSA-PSS names, and the hash that binds a certificate it signs with one. */
    private static final Map<Octets, String> HASHES = Map.of(
            Octets.of(SHA1),
            SHA_256,
            Octets.of(SHA256),
            SHA_256,
            Octets.of(SHA384),
            SHA_384,
            Octets.of(SHA512),
            SHA_512);

    private EndPoint() {}

    private static byte[] oid(int... body) {
        byte[] bytes = new byte[body.length];
        for (int i = 0; i < body.length; i++) {
            bytes[i] = (byte) body[i];
        }
        return bytes;
    }

    /**
     * The binding of a server's certificate: its hash; none if its signature algorithm defines
     * none, or it is not a certificate.
     *
     * @param certificate the certificate's DER
     * @return the hash, or none
     */
    static @Nullable Octets of(byte[] certificate) {
        Der.Read whole = Der.expect(certificate, Der.SEQUENCE);
        Der.Read tbs = whole == null ? null : Der.expect(whole.body(), Der.SEQUENCE);
        Der.Read algorithm = tbs == null ? null : identifier(tbs.rest());
        if (algorithm == null) {
            return null;
        }
        String hash = Arrays.equals(algorithm.body(), RSA_PSS)
                ? pss(algorithm.rest())
                : SIGNATURES.get(Octets.of(algorithm.body()));
        return hash == null ? null : Octets.of(digest(hash, certificate));
    }

    /** The algorithm of the AlgorithmIdentifier {@code der} begins with, its parameters after. */
    private static Der.@Nullable Read identifier(byte[] der) {
        Der.Read sequence = Der.expect(der, Der.SEQUENCE);
        return sequence == null ? null : Der.expect(sequence.body(), Der.OID);
    }

    /** The hash binding a certificate signed by RSASSA-PSS of these parameters, if it uses one hash. */
    private static @Nullable String pss(byte[] parameters) {
        Der.Read sequence = Der.expect(parameters, Der.SEQUENCE);
        if (sequence == null) {
            return null;
        }
        byte[] fields = sequence.body();
        byte[] hash = SHA1;
        Der.Read explicit = Der.expect(fields, PSS_HASH);
        if (explicit != null) {
            Der.Read named = identifier(explicit.body());
            if (named == null) {
                return null;
            }
            hash = named.body();
            fields = explicit.rest();
        }
        Octets mask = mask(fields);
        return mask != null && mask.equals(Octets.of(hash)) ? HASHES.get(mask) : null;
    }

    /** The hash of the MGF1 of maskGenAlgorithm, SHA-1 by default; none of another mask. */
    private static @Nullable Octets mask(byte[] fields) {
        Der.Read explicit = Der.expect(fields, PSS_MASK);
        if (explicit == null) {
            return Octets.of(SHA1);
        }
        Der.Read function = identifier(explicit.body());
        if (function == null || !Arrays.equals(function.body(), MGF1)) {
            return null;
        }
        Der.Read of = identifier(function.rest());
        return of == null ? null : Octets.of(of.body());
    }

    private static byte[] digest(String hash, byte[] data) {
        try {
            return MessageDigest.getInstance(hash).digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every JDK has " + hash, e);
        }
    }
}
