package com.turborel.jdbc.internal.auth;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SCRAM-SHA-256's cryptography, the JDK's own ([PROTOCOL Mechanisms] [RFC 5802 §2.2]): HMAC-SHA-256,
 * SHA-256, PBKDF2-HMAC-SHA-256 of one block, which is Hi, and nonces of the system's randomness.
 */
final class Crypto {
    /** The bytes of a key, a proof or a signature: SHA-256's. */
    static final int KEY_BYTES = 32;

    /** The random bytes of a nonce: 144 bits, in 24 characters of base 64. */
    static final int NONCE_BYTES = 18;

    /** HMAC-SHA-256, as the JDK names it. */
    private static final String HMAC = "HmacSHA256";

    /** SHA-256, as the JDK names it. */
    private static final String SHA256 = "SHA-256";

    /**
     * The key HMAC takes of an empty password: HMAC pads a key with zeros to its block, so a key of
     * one zero byte is the empty key, which the JDK's key spec refuses.
     */
    private static final byte[] EMPTY_KEY = {0};

    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    /**
     * HMAC-SHA-256 of {@code data} under {@code key} [RFC 2104 §2].
     *
     * @param key the key
     * @param data the data
     * @return the tag, 32 bytes
     */
    static byte[] hmac(byte[] key, byte[] data) {
        return mac(key).doFinal(data);
    }

    /**
     * SHA-256 of {@code data}.
     *
     * @param data the data
     * @return the digest, 32 bytes
     */
    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance(SHA256).digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /**
     * SaltedPassword: Hi, PBKDF2-HMAC-SHA-256 of one block [RFC 5802 §2.2], the slow step.
     *
     * @param password the password's UTF-8
     * @param salt the salt
     * @param iterations the iterations, at least 1, a {@code u32}
     * @return SaltedPassword, 32 bytes
     */
    static byte[] salted(byte[] password, byte[] salt, long iterations) {
        Mac mac = mac(password);
        mac.update(salt);
        byte[] u = mac.doFinal(ByteBuffer.allocate(Integer.BYTES).putInt(1).array());
        byte[] result = u.clone();
        for (long i = 1; i < iterations; i++) {
            u = mac.doFinal(u);
            for (int b = 0; b < result.length; b++) {
                result[b] ^= u[b];
            }
        }
        return result;
    }

    /**
     * The bytes of two keys, each XORed with the other's.
     *
     * @param a one
     * @param b the other, as long
     * @return a XOR b
     */
    static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /**
     * A new nonce of the system's randomness, printable and without a comma.
     *
     * @return the nonce, base 64
     */
    static String nonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Mac mac(byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key.length == 0 ? EMPTY_KEY : key, HMAC));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every JDK has HMAC-SHA-256", e);
        }
    }
}
