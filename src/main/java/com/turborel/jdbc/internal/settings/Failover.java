package com.turborel.jdbc.internal.settings;

import com.turborel.jdbc.TurborelException;
import com.turborel.jdbc.internal.auth.Certificate;
import com.turborel.jdbc.internal.auth.ChannelBinding;
import com.turborel.jdbc.internal.auth.Mechanism;
import com.turborel.jdbc.internal.auth.Scram;
import com.turborel.jdbc.internal.auth.Trust;
import com.turborel.jdbc.internal.codec.Broken;
import com.turborel.jdbc.internal.session.Connect;
import com.turborel.jdbc.internal.session.Connection;
import com.turborel.jdbc.internal.session.Login;
import com.turborel.jdbc.internal.session.Offer;
import com.turborel.jdbc.internal.tls.TlsConfig;
import com.turborel.jdbc.internal.transport.Io;
import com.turborel.jdbc.internal.transport.Target;
import com.turborel.jdbc.internal.transport.Timeouts;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * Connecting as {@link Settings} say, to one of several servers ([CLIENT Several servers]): each
 * host tried in the order given, or in an order chosen at random for each connection under {@link
 * LoadBalance#RANDOM}, and each address a host's name resolves to in turn, every step within the
 * connect timeout. A server reached, logged in to and of the role the settings ask ({@link Role})
 * is kept; another is left and the next tried. No server kept, the failure says why each was not.
 * As Turborel's {@code client/src/failover.rs}.
 */
final class Failover {
    /** The multiplier of the generator that orders hosts at random (Knuth's MMIX linear congruential generator). */
    private static final long MULTIPLIER = 6_364_136_223_846_793_005L;

    /** Its increment (MMIX's). */
    private static final long INCREMENT = 1_442_695_040_888_963_407L;

    /** The high bits of its state taken for each draw, the low bits of a linear congruential generator being the weakest. */
    private static final int DRAW_SHIFT = 33;

    /** The SQLSTATE class of a refusal of the user. */
    private static final String REFUSED = "28";

    private Failover() {}

    /** A seed of the system's randomness. */
    static long seed() {
        return ThreadLocalRandom.current().nextLong();
    }

    /**
     * {@code items} in an order drawn from {@code seed} (Fisher and Yates, as Durstenfeld gives
     * it): each order as likely, for a seed drawn at random; the same order as the reference's for
     * the same seed.
     */
    static <T> void shuffle(List<T> items, long seed) {
        long state = seed;
        for (int i = items.size() - 1; i >= 1; i--) {
            state = state * MULTIPLIER + INCREMENT;
            long drawn = Long.remainderUnsigned(state >>> DRAW_SHIFT, i + 1L);
            items.set(i, items.set((int) drawn, items.get(i)));
        }
    }

    /** A target of a host, or why it has none, as a Rust result holds either. */
    @FunctionalInterface
    private interface Resolved {
        Target target() throws TurborelException;
    }

    /**
     * A target of a host, named as a failure names it.
     *
     * @param at its name
     * @param resolved the target, or why there is none
     */
    private record Reached(String at, Resolved resolved) {}

    /** The connection {@link Settings#connect} gives. */
    static Connection connect(Settings settings) throws TurborelException {
        List<Host> hosts = new ArrayList<>(settings.hosts());
        if (settings.loadBalance() == LoadBalance.RANDOM) {
            shuffle(hosts, seed());
        }
        TlsConfig tls = Modes.config(settings.tlsMode(), settings.tls());
        List<String> at = new ArrayList<>();
        List<TurborelException> tried = new ArrayList<>();
        for (Host host : hosts) {
            for (Reached reached : targets(host, settings.tls().serverName(), tls)) {
                try {
                    return attempt(settings, host, reached.resolved().target());
                } catch (TurborelException failure) {
                    at.add(reached.at());
                    tried.add(failure);
                }
            }
        }
        throw noneKept(at, tried);
    }

    /**
     * The targets of {@code host}, each named: its local socket, or each address its name resolves
     * to, over TLS as {@code tls} says, the server's certificate of the name {@code serverName},
     * else the host's.
     */
    private static List<Reached> targets(Host host, @Nullable String serverName, @Nullable TlsConfig tls) {
        if (host instanceof Host.Local local) {
            Target target = new Target.Local(local.path());
            return List.of(new Reached(local.path().toString(), () -> target));
        }
        Host.Tcp tcp = (Host.Tcp) host;
        String server = serverName == null ? tcp.name() : serverName;
        List<Reached> found = new ArrayList<>();
        try {
            for (InetAddress address : InetAddress.getAllByName(tcp.name())) {
                InetSocketAddress a = new InetSocketAddress(address, tcp.port());
                Target target = tls == null ? new Target.Tcp(a) : new Target.Tls(a, server, tls);
                found.add(new Reached(tcp.name() + " (" + text(a) + ")", () -> target));
            }
        } catch (UnknownHostException e) {
            TurborelException failure = Io.failed("resolve the name", e);
            found.add(new Reached(tcp.name() + ":" + tcp.port(), () -> {
                throw failure;
            }));
        }
        return found;
    }

    /** An address and port as Rust writes a socket address: an IPv6 address in brackets, in its shortest form. */
    static String text(InetSocketAddress a) {
        InetAddress address = a.getAddress();
        if (address instanceof Inet6Address v6) {
            int scope = v6.getScopeId();
            return "[" + Ipv6Text.of(v6.getAddress()) + (scope == 0 ? "" : "%" + scope) + "]:" + a.getPort();
        }
        return address.getHostAddress() + ":" + a.getPort();
    }

    /**
     * Refuses, before anything is sent, settings whose channel binding is required and that
     * cannot bind one to {@code target}: a connection not over TLS, or no password for SCRAM.
     */
    private static void bindable(Settings settings, Host host, Target target) throws TurborelException {
        if (settings.channelBinding() == ChannelBinding.REQUIRE
                && !(settings.passwordFor(host) != null && target.tls())) {
            throw new TurborelException(
                    Broken.SQLCODE,
                    Invalid.INVALID_AUTHORIZATION,
                    "[CLIENT TLS by mode] channel binding is required: SCRAM over TLS alone binds one");
        }
    }

    /** What proves the user at {@code host}. */
    private static Mechanism proof(Settings settings, Host host) {
        String password = settings.passwordFor(host);
        if (password != null) {
            return new Scram(password).channelBinding(settings.channelBinding()).withCache(settings.keyCache());
        }
        return settings.tls().certificate() != null ? new Certificate() : new Trust();
    }

    /**
     * A connection to {@code host} over {@code target}, logged in and of the role asked; under
     * {@link TlsMode#PREFER}, over plain TCP if TLS cannot be begun.
     */
    private static Connection attempt(Settings settings, Host host, Target target) throws TurborelException {
        bindable(settings, host, target);
        Timeouts timeouts = new Timeouts(settings.connectTimeout(), null);
        Target.Preferred reached = settings.tlsMode() == TlsMode.PREFER
                ? target.openedPreferring(timeouts.connect())
                : new Target.Preferred(target, target.opened(timeouts.connect()));
        try {
            bindable(settings, host, reached.target());
        } catch (TurborelException refused) {
            reached.opened().duplex().close();
            throw refused;
        }
        Login login = new Login(settings.user(), proof(settings, host));
        Connection connection = Connect.over(reached.target(), reached.opened(), login, timeouts, offer(settings));
        try {
            ofRole(settings, connection);
        } catch (TurborelException left) {
            connection.close();
            throw left;
        }
        return connection;
    }

    /**
     * What a connection of the settings asks for as it opens: every capability but compression,
     * and the program's name, if given, as the connection attribute {@code application}.
     */
    private static Offer offer(Settings settings) {
        String name = settings.applicationName();
        return name == null ? Offer.standard() : Offer.standard().attribute(Offer.APPLICATION, name);
    }

    /** Refuses {@code connection} if its server is not of the role the settings ask (SQLSTATE 08001). */
    private static void ofRole(Settings settings, Connection connection) throws TurborelException {
        Role wanted = switch (settings.target()) {
            case ANY -> null;
            case READ_WRITE -> Role.READ_WRITE;
            case READ_ONLY -> Role.READ_ONLY;
        };
        if (wanted == null) {
            return;
        }
        Role found = Role.of(connection);
        if (found != wanted) {
            throw Invalid.setting("[CLIENT Several servers] the server is " + found.describe() + ", and "
                    + wanted.describe() + " is asked");
        }
    }

    /**
     * The failure of a connection none of the addresses {@code at} gave, each the failure of
     * {@code tried} at its place.
     */
    static TurborelException noneKept(List<String> at, List<TurborelException> tried) {
        if (tried.size() == 1) {
            return tried.get(0);
        }
        boolean refused = !tried.isEmpty()
                && String.valueOf(tried.get(tried.size() - 1).getSQLState()).startsWith(REFUSED);
        List<String> reasons = new ArrayList<>(tried.size());
        for (int i = 0; i < tried.size(); i++) {
            reasons.add(at.get(i) + ": " + tried.get(i).getMessage());
        }
        String message = reasons.isEmpty()
                ? "[CLIENT Several servers] no server is named"
                : "[CLIENT Several servers] no server was kept: " + String.join("; ", reasons);
        TurborelException failure = new TurborelException(
                Broken.SQLCODE, refused ? Invalid.INVALID_AUTHORIZATION : Invalid.UNABLE_TO_CONNECT, message);
        tried.forEach(failure::addSuppressed);
        return failure;
    }
}
