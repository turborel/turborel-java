package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.ChannelBinding;
import java.util.List;
import java.util.Map;

/**
 * The values of connection settings that are one of a few words ([CLIENT Where settings come
 * from]): how TLS is used, whether SCRAM binds its channel, what a server must be to be taken,
 * whether servers are tried in order, and a yes or no. Each is written in lower case, as a setting,
 * the environment and a URI give it. As Turborel's {@code client/src/settings/values.rs}.
 */
final class Words {
    /** The words of {@link TlsMode}, in its order. */
    static final List<Map.Entry<String, TlsMode>> TLS_MODES = List.of(
            Map.entry("disable", TlsMode.DISABLE),
            Map.entry("prefer", TlsMode.PREFER),
            Map.entry("require", TlsMode.REQUIRE),
            Map.entry("verify-ca", TlsMode.VERIFY_CA),
            Map.entry("verify-full", TlsMode.VERIFY_FULL));

    /** The words of {@link ChannelBinding}. */
    static final List<Map.Entry<String, ChannelBinding>> CHANNEL_BINDINGS = List.of(
            Map.entry("disable", ChannelBinding.DISABLE),
            Map.entry("prefer", ChannelBinding.PREFER),
            Map.entry("require", ChannelBinding.REQUIRE));

    /** The words of {@link TargetRole}. */
    static final List<Map.Entry<String, TargetRole>> TARGET_ROLES = List.of(
            Map.entry("any", TargetRole.ANY),
            Map.entry("read-write", TargetRole.READ_WRITE),
            Map.entry("read-only", TargetRole.READ_ONLY));

    /** The words of {@link LoadBalance}. */
    static final List<Map.Entry<String, LoadBalance>> LOAD_BALANCES =
            List.of(Map.entry("disable", LoadBalance.DISABLE), Map.entry("random", LoadBalance.RANDOM));

    /** The words of a yes or no. */
    static final List<Map.Entry<String, Boolean>> BOOLEANS =
            List.of(Map.entry("true", Boolean.TRUE), Map.entry("false", Boolean.FALSE));

    private Words() {}

    /**
     * The value of {@code key} the word {@code text} is, among {@code words}.
     *
     * @param <T> the kind of value
     * @param key the setting's name
     * @param text its value, as written
     * @param words the words it may be
     * @return the value
     * @throws TurborelException SQLSTATE 08001 if it is none of them
     */
    static <T> T word(String key, String text, List<Map.Entry<String, T>> words) throws TurborelException {
        for (Map.Entry<String, T> each : words) {
            if (each.getKey().equals(text)) {
                return each.getValue();
            }
        }
        List<String> known = words.stream().map(Map.Entry::getKey).toList();
        throw Invalid.setting("[CLIENT Where settings come from] " + key + " is " + text + ", not one of "
                + String.join(", ", known));
    }

    /**
     * The channel binding {@code text} names, the value of {@code key}.
     *
     * @param key the setting's name
     * @param text its value
     * @return the binding
     * @throws TurborelException SQLSTATE 08001 if it names none
     */
    static ChannelBinding channelBinding(String key, String text) throws TurborelException {
        return word(key, text, CHANNEL_BINDINGS);
    }

    /**
     * The yes or no {@code text} is, {@code true} or {@code false}, the value of {@code key}.
     *
     * @param key the setting's name
     * @param text its value
     * @return the truth
     * @throws TurborelException SQLSTATE 08001 if it is neither
     */
    static boolean bool(String key, String text) throws TurborelException {
        return word(key, text, BOOLEANS);
    }
}
