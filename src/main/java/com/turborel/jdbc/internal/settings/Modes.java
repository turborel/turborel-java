package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.tls.Pem;
import com.turborel.jdbc.internal.tls.Roots;
import com.turborel.jdbc.internal.tls.TlsConfig;
import java.nio.file.Path;
import java.security.KeyStore;
import org.jspecify.annotations.Nullable;

/**
 * TLS by mode ([CLIENT TLS by mode]): the client's TLS for each {@link TlsMode}. {@code prefer}
 * and {@code require} encrypt and check that the server holds the key of the certificate it
 * presents, but not who signed it, so they keep out an eavesdropper and not a server in the
 * middle; {@code verify-ca} checks the certificate against the authorities trusted, not its name;
 * {@code verify-full} checks both. Each presents the client's certificate if one is given. As
 * Turborel's {@code client/src/tls/modes.rs}.
 */
final class Modes {
    private Modes() {}

    /**
     * The client's TLS of {@code mode}, trusting and presenting what {@code files} name: none of
     * {@link TlsMode#DISABLE}.
     *
     * @param mode the mode
     * @param files the files
     * @return the TLS, or none
     * @throws TurborelException a connection failure if a file cannot be read, {@code verify-ca} or
     *     {@code verify-full} is asked for with no authority trusted, or the JDK cannot make it
     */
    static @Nullable TlsConfig config(TlsMode mode, TlsFiles files) throws TurborelException {
        return switch (mode) {
            case DISABLE -> null;
            case PREFER, REQUIRE -> TlsConfig.unchecked(identity(files));
            case VERIFY_CA -> TlsConfig.of(trusted(files), identity(files), false);
            case VERIFY_FULL -> TlsConfig.of(trusted(files), identity(files), true);
        };
    }

    /** The authorities {@code files} trust. */
    private static KeyStore trusted(TlsFiles files) throws TurborelException {
        return Roots.of(files.roots(), files.systemRoots(), System::getenv);
    }

    /** The identity {@code files} name: none if they name no certificate. */
    private static TlsConfig.@Nullable Identity identity(TlsFiles files) throws TurborelException {
        Path chain = files.certificate();
        if (chain == null) {
            return null;
        }
        Path key = files.key();
        if (key == null) {
            throw Broken.connection(
                    "[CLIENT TLS by mode] a client certificate is presented with its key, and none is named");
        }
        return new TlsConfig.Identity(Pem.certificates(chain), Pem.privateKey(key));
    }
}
