package com.turborel.jdbc.internal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.turborel.jdbc.internal.codec.Octets;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The channel binding tls-server-end-point, as Turborel's binding.rs tests it ([PROTOCOL Mechanisms]). */
class EndPointTest {
    private static final byte[] PKCS1 = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01};
    private static final byte[] ECDSA = {0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04};
    private static final byte[] HASHALGS = {0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02};
    private static final byte[] SHA1 = {0x2b, 0x0e, 0x03, 0x02, 0x1a};

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] arc(byte[] base, int... more) {
        byte[] out = Arrays.copyOf(base, base.length + more.length);
        for (int i = 0; i < more.length; i++) {
            out[base.length + i] = (byte) more[i];
        }
        return out;
    }

    private static byte[] tlv(int tag, byte[] body) {
        return cat(new byte[] {(byte) tag, (byte) body.length}, body);
    }

    private static byte[] algorithm(byte[] oid, byte[] parameters) {
        return tlv(0x30, cat(tlv(0x06, oid), parameters));
    }

    private static byte[] certificate(byte[] signature) {
        return tlv(0x30, cat(tlv(0x30, new byte[] {0x02, 1, 7}), signature, tlv(0x03, new byte[] {0})));
    }

    private static Octets hashed(String hash, byte[] der) throws NoSuchAlgorithmException {
        return Octets.of(MessageDigest.getInstance(hash).digest(der));
    }

    private static byte[] pss(byte @Nullable [] hash, byte @Nullable [] mask) {
        byte[] h = hash == null ? new byte[0] : tlv(0xa0, algorithm(hash, new byte[] {0x05, 0}));
        byte[] m =
                mask == null ? new byte[0] : tlv(0xa1, algorithm(arc(PKCS1, 8), algorithm(mask, new byte[] {0x05, 0})));
        return algorithm(arc(PKCS1, 10), tlv(0x30, cat(h, m)));
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] [RFC 5929 §4.1] a certificate signed with MD5 or SHA-1, by RSA
     * or ECDSA, is bound by SHA-256 of its whole DER; with SHA-256, SHA-384 or SHA-512, by that.
     */
    @Test
    void theSignatureHashChoosesTheBindingHash() throws NoSuchAlgorithmException {
        Object[][] cases = {
            {arc(PKCS1, 4), "SHA-256"}, {arc(PKCS1, 5), "SHA-256"}, {arc(ECDSA, 1), "SHA-256"},
            {arc(PKCS1, 11), "SHA-256"}, {arc(ECDSA, 3, 2), "SHA-256"}, {arc(PKCS1, 12), "SHA-384"},
            {arc(ECDSA, 3, 3), "SHA-384"}, {arc(PKCS1, 13), "SHA-512"}, {arc(ECDSA, 3, 4), "SHA-512"},
        };
        for (Object[] each : cases) {
            byte[] der = certificate(algorithm((byte[]) each[0], new byte[] {0x05, 0}));
            assertEquals(hashed((String) each[1], der), EndPoint.of(der));
        }
        byte[] ecdsa = certificate(algorithm(arc(ECDSA, 3, 3), new byte[0]));
        assertEquals(hashed("SHA-384", ecdsa), EndPoint.of(ecdsa), "parameters absent, as ECDSA's are");
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] [RFC 4055 §3.1] RSASSA-PSS is bound by its hash, SHA-256 for
     * SHA-1 or the defaults, when its MGF1 is of the same hash; of two hashes, or a mask not MGF1,
     * it has no binding.
     */
    @Test
    void rsassaPssIsBoundByItsOneHash() throws NoSuchAlgorithmException {
        byte[] same = certificate(pss(arc(HASHALGS, 2), arc(HASHALGS, 2)));
        assertEquals(hashed("SHA-384", same), EndPoint.of(same));
        byte[] defaults = certificate(pss(null, null));
        assertEquals(hashed("SHA-256", defaults), EndPoint.of(defaults));
        byte[] sha1 = certificate(pss(SHA1, null));
        assertEquals(hashed("SHA-256", sha1), EndPoint.of(sha1));
        assertNull(EndPoint.of(certificate(pss(arc(HASHALGS, 1), null))), "two hashes");
        assertNull(EndPoint.of(certificate(pss(arc(HASHALGS, 3), arc(HASHALGS, 1)))), "two hashes");
        byte[] otherMask = tlv(0xa1, algorithm(arc(PKCS1, 5), algorithm(arc(HASHALGS, 1), new byte[0])));
        byte[] masked = algorithm(
                arc(PKCS1, 10), tlv(0x30, cat(tlv(0xa0, algorithm(arc(HASHALGS, 1), new byte[0])), otherMask)));
        assertNull(EndPoint.of(certificate(masked)), "a mask not MGF1");
        assertNull(EndPoint.of(certificate(pss(arc(PKCS1, 8), arc(PKCS1, 8)))), "a hash not known");
        assertNull(EndPoint.of(certificate(algorithm(arc(PKCS1, 10), new byte[0]))), "no parameters");
        byte[] hashNotIdentified = algorithm(arc(PKCS1, 10), tlv(0x30, tlv(0xa0, tlv(0x04, new byte[] {1}))));
        assertNull(EndPoint.of(certificate(hashNotIdentified)), "a hash that is no AlgorithmIdentifier");
        byte[] maskNotIdentified = algorithm(arc(PKCS1, 10), tlv(0x30, tlv(0xa1, tlv(0x04, new byte[] {1}))));
        assertNull(EndPoint.of(certificate(maskNotIdentified)), "a mask that is no AlgorithmIdentifier");
        byte[] mgf1OfNothing = algorithm(arc(PKCS1, 10), tlv(0x30, tlv(0xa1, algorithm(arc(PKCS1, 8), new byte[0]))));
        assertNull(EndPoint.of(certificate(mgf1OfNothing)), "MGF1 of no hash");
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] [RFC 5929 §4.1] an algorithm of no hash, as Ed25519, or not
     * known, has no binding; nor has DER not of a certificate, cut anywhere, never an exception.
     */
    @Test
    void noBindingWithoutOneKnownHash() {
        assertNull(EndPoint.of(certificate(algorithm(new byte[] {0x2b, 0x65, 0x70}, new byte[0]))), "Ed25519");
        byte[] whole = certificate(algorithm(arc(ECDSA, 3, 2), new byte[0]));
        for (int cut = 0; cut < whole.length; cut++) {
            assertNull(EndPoint.of(Arrays.copyOf(whole, cut)), "cut at " + cut);
        }
        assertNull(EndPoint.of(certificate(tlv(0x30, new byte[] {0x05, 0}))), "no algorithm");
    }
}
