package com.turborel.jdbc.internal.auth;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.codec.Octets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * A client's exchange of SCRAM-SHA-256 [RFC 5802 §3, §6, §7; RFC 7677] ([PROTOCOL Mechanisms]): its
 * first message, its proof answering the server's first, bound to its channel, and the server's
 * signature checked, so the client knows the server knew the verifier; as Turborel's {@code
 * protocol/src/scram/client.rs}.
 *
 * <pre>
 * client-first:  GS2 header, n=user,r=client nonce
 * server-first:  r=client nonce + server nonce,s=salt in base 64,i=iterations
 * client-final:  c=base 64 of the GS2 header and the data bound,r=both nonces,p=ClientProof
 * server-final:  v=ServerSignature
 * </pre>
 */
final class ScramClient {
    /** The SQLSTATE of credentials refused. */
    static final String INVALID_AUTHORIZATION = "28000";

    /** The least printable character of a nonce, {@code !}. */
    private static final int PRINTABLE_FIRST = 0x21;

    /** The last printable character of a nonce, {@code ~}. */
    private static final int PRINTABLE_LAST = 0x7E;

    /** The attributes of server-first: its nonces, its salt and its iterations. */
    private static final int SERVER_FIRST_FIELDS = 3;

    /** The characters that begin an attribute: its name and {@code =}. */
    private static final int PREFIX = 2;

    /** A split's limit that keeps empty fields, so a trailing comma is seen. */
    private static final int KEEP_EMPTY = -1;

    /** How the server's final message begins if it refuses. */
    private static final String ERROR = "e=";

    private final String user;
    private final Binding binding;
    private @Nullable String bare;
    private @Nullable String nonce;
    private byte @Nullable [] expected;
    private boolean verified;

    /**
     * An exchange as {@code user}, of the binding given.
     *
     * @param user the user, empty where the protocol's Hello names it
     * @param binding its channel binding
     */
    ScramClient(String user, Binding binding) {
        this.user = user;
        this.binding = binding;
    }

    /**
     * A refusal of the exchange: credentials refused, SQLCODE -919, SQLSTATE 28000.
     *
     * @param why what was wrong
     * @return the failure
     */
    static TurborelException refused(String why) {
        return new TurborelException(Broken.SQLCODE, INVALID_AUTHORIZATION, "[PROTOCOL Mechanisms] SCRAM: " + why);
    }

    /**
     * client-first, of a nonce, which should be new: the exchange begun again.
     *
     * @param ofNonce the client's nonce
     * @return client-first
     */
    byte[] first(String ofNonce) {
        bare = "n=" + saslname(user) + ",r=" + ofNonce;
        nonce = ofNonce;
        expected = null;
        verified = false;
        return (binding.header() + bare).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * server-first, read and checked against this exchange.
     *
     * @param serverFirst the server's first message
     * @return its nonces, salt and iterations
     * @throws TurborelException SQLSTATE 28000 if it is out of turn, malformed, or its nonce does
     *     not extend the client's
     */
    ServerFirst read(byte[] serverFirst) throws TurborelException {
        if (nonce == null || expected != null) {
            throw refused("the server's first message came out of turn");
        }
        String message = text(serverFirst);
        String[] fields = message.split(",", KEEP_EMPTY);
        if (fields.length > SERVER_FIRST_FIELDS) {
            throw refused("the server's first message has more than it should");
        }
        int at = 0;
        String both = attribute(fields, at++, 'r');
        String salt = attribute(fields, at++, 's');
        String iterations = attribute(fields, at, 'i');
        if (!isNonce(both) || both.length() <= nonce.length() || !both.startsWith(nonce)) {
            throw refused("the server's nonce does not extend the client's");
        }
        return new ServerFirst(message, both, Octets.of(decode(salt, "the salt is not base 64")), positive(iterations));
    }

    /**
     * client-final, answering {@code first} by the keys its salt and iterations derive.
     *
     * @param first the server's first message, read
     * @param keys the keys derived
     * @return client-final
     */
    byte[] prove(ServerFirst first, Derived keys) {
        String withoutProof = "c=" + Base64.getEncoder().encodeToString(binding.input()) + ",r=" + first.both();
        String auth = bare + "," + first.message() + "," + withoutProof;
        byte[] client = keys.client().toByteArray();
        byte[] authBytes = auth.getBytes(StandardCharsets.UTF_8);
        byte[] proof = Crypto.xor(client, Crypto.hmac(Crypto.sha256(client), authBytes));
        expected = Crypto.hmac(keys.server().toByteArray(), authBytes);
        return (withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * server-final checked: the server's signature, proving it knew the user's verifier.
     *
     * @param serverFinal the server's final message
     * @throws TurborelException SQLSTATE 28000 if it is out of turn, malformed, an error of the
     *     server's, or not the signature expected
     */
    void verify(byte[] serverFinal) throws TurborelException {
        if (expected == null) {
            throw refused("the server's final message came out of turn");
        }
        String message = text(serverFinal);
        if (message.startsWith(ERROR)) {
            throw refused("the server refused: " + message.substring(PREFIX));
        }
        byte[] signature = decode(attribute(new String[] {message}, 0, 'v'), "the server's signature is not base 64");
        if (!MessageDigest.isEqual(signature, expected)) {
            throw refused("the server did not prove it knows the user");
        }
        verified = true;
    }

    /**
     * Whether the server proved it knew the user's verifier.
     *
     * @return whether verified
     */
    boolean verified() {
        return verified;
    }

    /**
     * Whether the exchange is bound to its channel.
     *
     * @return whether bound
     */
    boolean bound() {
        return binding.bound();
    }

    private static String attribute(String[] fields, int at, char name) throws TurborelException {
        if (at >= fields.length) {
            throw refused("the attribute " + name + " is missing");
        }
        String field = fields[at];
        if (field.length() < PREFIX || field.charAt(0) != name || field.charAt(1) != '=') {
            throw refused("the attribute " + name + " was expected, not " + field);
        }
        return field.substring(PREFIX);
    }

    private static boolean isNonce(String candidate) {
        return !candidate.isEmpty()
                && candidate.chars().allMatch(c -> c >= PRINTABLE_FIRST && c <= PRINTABLE_LAST && c != ',');
    }

    private static long positive(String iterations) throws TurborelException {
        try {
            long n = Integer.toUnsignedLong(Integer.parseUnsignedInt(iterations));
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException notANumber) {
            // Refused below, as any iterations not a positive number.
        }
        throw refused("the iterations are no positive number");
    }

    private static byte[] decode(String base64, String why) throws TurborelException {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            throw refused(why);
        }
    }

    private static String text(byte[] bytes) throws TurborelException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw refused("a message is not UTF-8");
        }
    }

    /** A user as a saslname: {@code =} and {@code ,} written {@code =3D} and {@code =2C} [RFC 5802 §5.1]. */
    private static String saslname(String name) {
        return name.replace("=", "=3D").replace(",", "=2C");
    }

    /**
     * The server's first message, read and checked.
     *
     * @param message its whole text
     * @param both both nonces
     * @param salt the salt
     * @param iterations the iterations
     */
    record ServerFirst(String message, String both, Octets salt, long iterations) {}
}
