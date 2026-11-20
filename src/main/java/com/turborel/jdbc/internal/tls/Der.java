package com.turborel.jdbc.internal.tls;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The little of DER [X.690] the client reads and writes: a tag, its length, its body. It wraps a
 * PKCS #1 RSA key or a SEC 1 EC key as PKCS #8, which is what the JDK reads, and walks a
 * certificate to its signature algorithm for {@code tls-server-end-point}.
 */
public final class Der {
    /** SEQUENCE. */
    public static final int SEQUENCE = 0x30;

    /** OBJECT IDENTIFIER. */
    public static final int OID = 0x06;

    /** INTEGER. */
    private static final int INTEGER = 0x02;

    /** OCTET STRING. */
    private static final int OCTET_STRING = 0x04;

    /** NULL. */
    private static final int NULL = 0x05;

    /** A SEC 1 key's parameters, {@code [0]} explicit. */
    private static final int EC_PARAMETERS = 0xA0;

    /** The bit of a length's first byte that says more bytes give it. */
    private static final int LONG_FORM = 0x80;

    /** The low bits of a long form's first byte: how many bytes give the length. */
    private static final int LENGTH_BYTES = 0x7F;

    /** The most bytes a length is given in here: what an {@code int} holds. */
    private static final int MOST_LENGTH_BYTES = 3;

    /** The bits of a byte. */
    private static final int BYTE = 8;

    /** The bytes of a tag and a short length, before a body. */
    private static final int HEAD = 2;

    /** The low byte of an {@code int}. */
    private static final int LOW_BYTE = 0xFF;

    /** rsaEncryption, 1.2.840.113549.1.1.1 [RFC 8017 Appendix C]. */
    private static final byte[] RSA_ENCRYPTION = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 1, 1, 1};

    /** id-ecPublicKey, 1.2.840.10045.2.1 [RFC 5480 §2.1.1]. */
    private static final byte[] EC_PUBLIC_KEY = {0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 2, 1};

    private Der() {}

    /** A tag's body and what follows it, of the DER read. */
    public static final class Read {
        private final byte[] body;
        private final byte[] rest;

        private Read(byte[] body, byte[] rest) {
            this.body = body;
            this.rest = rest;
        }

        /**
         * The tag's body.
         *
         * @return a copy of it
         */
        public byte[] body() {
            return body.clone();
        }

        /**
         * What follows the tag.
         *
         * @return a copy of it
         */
        public byte[] rest() {
            return rest.clone();
        }
    }

    /**
     * The body of {@code tag} that {@code der} begins with, and what follows it.
     *
     * @param der the DER
     * @param tag the tag expected
     * @return the body and the rest; none if {@code der} does not begin with the tag, or is cut
     */
    public static @Nullable Read expect(byte[] der, int tag) {
        if (der.length < HEAD || Byte.toUnsignedInt(der[0]) != tag) {
            return null;
        }
        int first = Byte.toUnsignedInt(der[1]);
        int at = HEAD;
        long length = first;
        if ((first & LONG_FORM) != 0) {
            int count = first & LENGTH_BYTES;
            if (count == 0 || count > MOST_LENGTH_BYTES || der.length < at + count) {
                return null;
            }
            length = 0;
            for (int i = 0; i < count; i++) {
                length = (length << BYTE) | Byte.toUnsignedInt(der[at++]);
            }
        }
        if (length > der.length - at) {
            return null;
        }
        int end = at + (int) length;
        return new Read(Arrays.copyOfRange(der, at, end), Arrays.copyOfRange(der, end, der.length));
    }

    /**
     * A PKCS #1 RSA private key wrapped as PKCS #8 [RFC 5208 §5].
     *
     * @param pkcs1 the RSAPrivateKey's DER
     * @return the PrivateKeyInfo's DER
     */
    static byte[] rsaToPkcs8(byte[] pkcs1) {
        byte[] algorithm = tlv(SEQUENCE, cat(tlv(OID, RSA_ENCRYPTION), tlv(NULL, new byte[0])));
        return tlv(SEQUENCE, cat(tlv(INTEGER, new byte[] {0}), algorithm, tlv(OCTET_STRING, pkcs1)));
    }

    /**
     * A SEC 1 EC private key wrapped as PKCS #8 [RFC 5915 §3, RFC 5208 §5], of the named curve its
     * parameters give.
     *
     * @param sec1 the ECPrivateKey's DER
     * @return the PrivateKeyInfo's DER
     * @throws TurborelException a connection failure if it names no curve
     */
    static byte[] ecToPkcs8(byte[] sec1) throws TurborelException {
        Read key = expect(sec1, SEQUENCE);
        Read version = key == null ? null : expect(key.body(), INTEGER);
        Read secret = version == null ? null : expect(version.rest(), OCTET_STRING);
        Read parameters = secret == null ? null : expect(secret.rest(), EC_PARAMETERS);
        Read curve = parameters == null ? null : expect(parameters.body(), OID);
        if (curve == null) {
            throw Broken.connection("[CLIENT Connecting] an EC private key names no curve");
        }
        byte[] algorithm = tlv(SEQUENCE, cat(tlv(OID, EC_PUBLIC_KEY), tlv(OID, curve.body())));
        return tlv(SEQUENCE, cat(tlv(INTEGER, new byte[] {0}), algorithm, tlv(OCTET_STRING, sec1)));
    }

    /**
     * The DER of {@code tag} around {@code body}.
     *
     * @param tag the tag
     * @param body the body
     * @return the tag, the length, the body
     */
    static byte[] tlv(int tag, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (body.length < LONG_FORM) {
            out.write(body.length);
        } else {
            int count = 0;
            for (int n = body.length; n > 0; n >>>= BYTE) {
                count++;
            }
            out.write(LONG_FORM | count);
            for (int i = count - 1; i >= 0; i--) {
                out.write((body.length >>> (i * BYTE)) & LOW_BYTE);
            }
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
