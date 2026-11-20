package com.turborel.jdbc.internal.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Octets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** SCRAM-SHA-256's client, by RFC 7677's test vector and its failures ([PROTOCOL Mechanisms]). */
class ScramTest {
    // [RFC 7677 §3] The test vector: user "user", password "pencil".
    private static final String NONCE = "rOprNGfwEbeRWgbNEkqO";
    private static final String SERVER_FIRST =
            "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";
    private static final String CLIENT_FINAL =
            "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=";
    private static final String SERVER_FINAL = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=";

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertRefused(org.junit.jupiter.api.function.Executable call) {
        TurborelException e = assertThrows(TurborelException.class, call);
        assertEquals("28000", e.getSQLState(), e.getMessage());
        assertEquals(-919, e.sqlcode());
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] [RFC 7677 §3] the client's messages are the test vector's
     * byte for byte: client-first, client-final with its proof, and the server's signature checked.
     */
    @Test
    void theRfc7677TestVector() throws TurborelException {
        ScramClient client = new ScramClient("user", Binding.Flag.UNBOUND);
        assertArrayEquals(ascii("n,,n=user,r=" + NONCE), client.first(NONCE));
        ScramClient.ServerFirst first = client.read(ascii(SERVER_FIRST));
        assertEquals(4096, first.iterations());
        Derived keys = Derived.of("pencil", first.salt().toByteArray(), first.iterations());
        assertEquals(CLIENT_FINAL, new String(client.prove(first, keys), StandardCharsets.US_ASCII));
        assertFalse(client.verified());
        client.verify(ascii(SERVER_FINAL));
        assertTrue(client.verified());
        assertFalse(client.bound());
        assertEquals("Derived(..)", keys.toString());
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] a server's message out of turn, malformed, of a nonce not
     * extending the client's, of a salt not base 64, of iterations not a positive number, or not
     * UTF-8, is refused, credentials refused (SQLSTATE 28000).
     */
    @Test
    void aMalformedServerFirstIsRefused() {
        assertRefused(() -> new ScramClient("u", Binding.Flag.UNBOUND).read(ascii(SERVER_FIRST)));
        for (String bad : new String[] {
            "r=other,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096",
            "r=" + NONCE + ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096",
            "r=" + NONCE + "x,s=!!!,i=4096",
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=0",
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=many",
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1,x=extra",
            "r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==",
            "s=W22ZaJ0SNY7soEsUEjb6gQ==,r=" + NONCE + "x,i=1",
            "r=" + NONCE + "x y,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1",
            "r=,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1",
            "r=" + NONCE + "\u007f,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1",
            "r=X" + NONCE + ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1",
            "r,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1",
            "rr" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1",
        }) {
            ScramClient client = new ScramClient("u", Binding.Flag.UNBOUND);
            client.first(NONCE);
            assertRefused(() -> client.read(ascii(bad)));
        }
        ScramClient client = new ScramClient("u", Binding.Flag.UNBOUND);
        client.first(NONCE);
        assertRefused(() -> client.read(new byte[] {(byte) 0xFF}));
        ScramClient twice = new ScramClient("user", Binding.Flag.UNBOUND);
        twice.first(NONCE);
        ScramClient.ServerFirst first = assertDoesNotThrow(() -> twice.read(ascii(SERVER_FIRST)));
        assertDoesNotThrow(
                () -> twice.prove(first, Derived.of("pencil", first.salt().toByteArray(), first.iterations())));
        assertRefused(() -> twice.read(ascii(SERVER_FIRST)));
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] the server's final message is checked: out of turn, an error
     * of the server's, a signature not base 64 or not the one expected, each refused.
     */
    @Test
    void theServerFinalIsChecked() throws TurborelException {
        assertRefused(() -> new ScramClient("user", Binding.Flag.UNBOUND).verify(ascii(SERVER_FINAL)));
        for (String bad : new String[] {"e=invalid-proof", "v=!!!", "v=AAAA", "x=1"}) {
            ScramClient client = new ScramClient("user", Binding.Flag.UNBOUND);
            client.first(NONCE);
            ScramClient.ServerFirst first = client.read(ascii(SERVER_FIRST));
            client.prove(first, Derived.of("pencil", first.salt().toByteArray(), first.iterations()));
            assertRefused(() -> client.verify(ascii(bad)));
        }
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] a user's {@code =} and {@code ,} are written {@code =3D} and
     * {@code =2C} in client-first [RFC 5802 §5.1]; the GS2 header names the binding.
     */
    @Test
    void theUserAndTheBindingAreWritten() {
        assertArrayEquals(ascii("y,,n=a=3Db=2Cc,r=N"), new ScramClient("a=b,c", Binding.Flag.UNOFFERED).first("N"));
        Binding endPoint = new Binding.EndPoint(Octets.of(new byte[] {1}));
        assertArrayEquals(ascii("p=tls-server-end-point,,n=,r=N"), new ScramClient("", endPoint).first("N"));
        assertArrayEquals(
                new byte[] {'p', '=', 't', 'l', 's', '-', 'e', 'x', 'p', 'o', 'r', 't', 'e', 'r', ',', ',', 2},
                new Binding.Exporter(Octets.of(new byte[] {2})).input());
        assertTrue(endPoint.bound());
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] the mechanism binds by tls-exporter where the channel has it,
     * else by tls-server-end-point, else offers none; named scram-sha-256-plus when bound; and binds
     * nothing when told not to.
     */
    @Test
    void theMechanismBindsAsTheChannelAllows() {
        Scram scram = new Scram("pencil");
        assertEquals(Scram.NAME, scram.name());
        scram.bind(new TlsChannel(null, Octets.of(new byte[32])));
        assertEquals(Scram.NAME_PLUS, scram.name());
        assertTrue(new String(scram.initial().toByteArray(), StandardCharsets.US_ASCII)
                .startsWith("p=tls-exporter,,n=,r="));
        assertEquals(Scram.NAME, scram.name(), "a binding is the connection's alone");
        scram.bind(new TlsChannel(Octets.of(new byte[] {0x30, 0}), null));
        assertTrue(new String(scram.initial().toByteArray(), StandardCharsets.US_ASCII).startsWith("y,,"));
        Scram off = new Scram("pencil").channelBinding(ChannelBinding.DISABLE);
        off.bind(new TlsChannel(null, Octets.of(new byte[32])));
        assertEquals(Scram.NAME, off.name());
        assertTrue(scram.secret());
        assertFalse(scram.inTheClear().secret());
        assertEquals("Scram(..)", scram.toString());
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] the server's signature is checked before the last, empty
     * Answer, and Ready is refused unless the server proved it; binding required refuses an
     * unbound connection before the proof.
     */
    @Test
    void theMechanismRefusesAServerThatDidNotProveItself() throws TurborelException {
        Scram scram = new Scram("pencil");
        assertThrows(TurborelException.class, () -> scram.answer(Octets.of(ascii(SERVER_FIRST))));
        assertThrows(TurborelException.class, scram::ready);
        String first = new String(scram.initial().toByteArray(), StandardCharsets.US_ASCII);
        String nonce = first.substring(first.indexOf("r=") + 2);
        Octets serverFirst = Octets.of(ascii("r=" + nonce + "S,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"));
        assertTrue(
                new String(scram.answer(serverFirst).toByteArray(), StandardCharsets.US_ASCII).startsWith("c=biws,"));
        assertThrows(TurborelException.class, scram::ready);
        assertRefused(() ->
                scram.answer(Octets.of(ascii("v=" + Base64.getEncoder().encodeToString(new byte[32])))));
        Scram strict = new Scram("pencil").channelBinding(ChannelBinding.REQUIRE);
        strict.initial();
        assertRefused(() -> strict.answer(serverFirst));
    }

    /**
     * Verifies: [CLIENT The SCRAM key cache] keys a password derived are kept, a later login of the
     * same salt and iterations skipping the derivation; a login that fails drops its entry; the
     * cache holds at most 64, the oldest dropped; it never shows what it holds.
     */
    @Test
    void theKeyCacheKeepsAndDrops() {
        KeyCache cache = new KeyCache();
        Octets salt = Octets.of(new byte[] {1, 2, 3});
        Derived first = cache.derived("pencil", salt, 100);
        assertEquals(first, cache.derived("pencil", salt, 100));
        assertArrayEquals(new long[] {1, 1}, cache.lookups());
        cache.derived("other", salt, 100);
        assertArrayEquals(new long[] {1, 2}, cache.lookups(), "a wrong password finds nothing");
        cache.forget("pencil", salt, 100);
        assertEquals(1, cache.size());
        for (int i = 0; i < KeyCache.ENTRIES + 5; i++) {
            cache.derived("p", Octets.of(new byte[] {(byte) i}), 1);
        }
        assertEquals(KeyCache.ENTRIES, cache.size());
        long[] before = cache.lookups();
        cache.derived("p", Octets.of(new byte[] {(byte) (KeyCache.ENTRIES + 4)}), 1);
        assertEquals(before[0] + 1, cache.lookups()[0], "the newest kept");
        cache.derived("p", Octets.of(new byte[] {0}), 1);
        assertEquals(before[1] + 1, cache.lookups()[1], "the oldest dropped first");
        assertEquals("KeyCache(64 entries)", cache.toString());
        cache.clear();
        assertEquals(0, cache.size());
    }

    /**
     * Verifies: [CLIENT The SCRAM key cache] a mechanism given a cache takes its keys from it, and
     * forgets them when the server refuses the login.
     */
    @Test
    void aMechanismUsesAndForgetsItsCache() throws TurborelException {
        KeyCache cache = new KeyCache();
        Scram scram = new Scram("pencil").withCache(cache);
        String first = new String(scram.initial().toByteArray(), StandardCharsets.US_ASCII);
        String nonce = first.substring(first.indexOf("r=") + 2);
        scram.answer(Octets.of(ascii("r=" + nonce + "S,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096")));
        assertEquals(1, cache.size());
        scram.refused();
        assertEquals(0, cache.size());
        Scram unproved = new Scram("pencil").withCache(cache);
        unproved.initial();
        unproved.refused();
        Scram erred = new Scram("pencil").withCache(cache);
        String again = new String(erred.initial().toByteArray(), StandardCharsets.US_ASCII);
        erred.answer(Octets.of(
                ascii("r=" + again.substring(again.indexOf("r=") + 2) + "S,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096")));
        assertEquals(1, cache.size());
        assertRefused(() -> erred.answer(Octets.of(ascii("e=invalid-proof"))));
        assertEquals(0, cache.size(), "the server's error forgets the keys");
    }

    /**
     * Verifies: [PROTOCOL Mechanisms] trust, password and certificate send their first bytes and
     * have no answer to a challenge; a password is secret, sent as its UTF-8, and never shown.
     */
    @Test
    void theSimpleMechanisms() throws TurborelException {
        assertEquals("trust", new Trust().name());
        assertEquals(Octets.EMPTY, new Trust().initial());
        assertEquals("certificate", new Certificate().name());
        assertEquals(Octets.EMPTY, new Certificate().initial());
        Password password = new Password("é");
        assertEquals(Octets.of(new byte[] {(byte) 0xC3, (byte) 0xA9}), password.initial());
        assertTrue(password.secret());
        assertFalse(new Trust().secret());
        assertEquals("Password(..)", password.toString());
        for (Mechanism m : new Mechanism[] {new Trust(), password, new Certificate()}) {
            assertThrows(TurborelException.class, () -> m.answer(Octets.EMPTY));
            m.ready();
            m.bind(new TlsChannel(null, null));
            m.refused();
        }
    }
}
