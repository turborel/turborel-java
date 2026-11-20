package com.turborel.jdbc.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The live tests' server (RULES.md R-TEST-8): a Turborel server on loopback, on the Java driver's
 * own port, 17434, in a data directory of its own, with test certificates, its owner ANN of a
 * password, and
 * SERVER.TRUST_LOCAL ON, as test/server/live.settings sets it, the image's entry point being
 * {@code turboreld}. The live suite runs only when TURBOREL_TEST_SERVER names the server: a
 * {@code turboreld} binary, or {@code docker:} and an image, as CI names
 * {@code ghcr.io/turborel/turborel:nightly}; so the unit tests stay deterministic (R-TEST-5).
 *
 * <p>One server serves every live test of a run, started as the first needs it, and stopped as
 * the run ends; only its own process is waited on.
 */
public final class LiveServer {
    /** The variable naming the server. */
    public static final String VARIABLE = "TURBOREL_TEST_SERVER";

    /** The Java driver's own port for live servers. */
    public static final int PORT = 17434;

    /** The loopback address the server takes connections at. */
    public static final String HOST = "127.0.0.1";

    /** The database's owner, made as the server starts. */
    public static final String OWNER = "ANN";

    /** The owner's password. */
    public static final String PASSWORD = "ann's";

    /** How a server's name says it runs in a container. */
    private static final String DOCKER = "docker:";

    /** The container's name, so it is removed as the run ends. */
    private static final String CONTAINER = "turborel-java-live-" + PORT;

    /** The server's settings, kept with its set-up (R-TEST-8). */
    private static final Path SETTINGS = Path.of("test", "server", "live.settings");

    /** What the server prints once it takes connections. */
    private static final String LISTENING = "listening at";

    private static final long START_WITHIN_SECONDS = 60;

    /** What guards the run's server. */
    private static final Object LOCK = new Object();

    private static @Nullable LiveServer shared;

    /** A short directory for sockets, whose paths must be shorter than SUN_LEN, about 100 bytes. */
    private static final Path SHORT = Path.of("/tmp");

    private final Process process;
    private final Path sockets;
    private final Certificates certificates;
    private final boolean docker;

    private LiveServer(Process process, Path sockets, Certificates certificates, boolean docker) {
        this.process = process;
        this.sockets = sockets;
        this.certificates = certificates;
        this.docker = docker;
    }

    /**
     * The run's server, started if it is not.
     *
     * @return the server
     * @throws IOException if it cannot be started
     */
    public static LiveServer get() throws IOException {
        synchronized (LOCK) {
            LiveServer server = shared;
            if (server == null) {
                server = start(String.valueOf(System.getenv(VARIABLE)));
                Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "turborel-live-server-stop"));
                shared = server;
            }
            return server;
        }
    }

    /**
     * The certificates it presents and trusts.
     *
     * @return the certificates
     */
    public Certificates certificates() {
        return certificates;
    }

    /**
     * Its Unix socket.
     *
     * @return the socket's path
     */
    public Path socket() {
        return sockets.resolve("turborel.sock");
    }

    /**
     * The address it takes TCP connections at.
     *
     * @return 127.0.0.1 and its port
     */
    public static InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT);
    }

    private static LiveServer start(String server) throws IOException {
        Path dir = Files.createTempDirectory("turborel-java-live");
        Certificates certificates = Certificates.make(dir.resolve("certs"));
        Path sockets = Files.isDirectory(SHORT)
                ? Files.createTempDirectory(SHORT, "tj")
                : Files.createDirectories(dir.resolve("sock"));
        boolean docker = server.startsWith(DOCKER);
        Path data = dir.resolve("data");
        owner(launcher(server, docker, dir, sockets, "setup"), data);
        List<String> command = launcher(server, docker, dir, sockets, "server");
        command.addAll(List.of("--dir", data.toString()));
        for (String setting : settings(certificates, sockets.resolve("turborel.sock"))) {
            command.addAll(List.of("--set", setting));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        LiveServer live = new LiveServer(process, sockets, certificates, docker);
        process.getOutputStream().close();
        live.awaitListening(dir.resolve("server.log"));
        return live;
    }

    /** What runs {@code turboreld}: the binary, or a container of the image, as the tests' own user. */
    private static List<String> launcher(String server, boolean docker, Path dir, Path sockets, String role)
            throws IOException {
        List<String> command = new ArrayList<>();
        if (!docker) {
            command.add(server);
            return command;
        }
        command.addAll(List.of(
                "docker",
                "run",
                "--rm",
                "-i",
                "--name",
                CONTAINER + "-" + role,
                "--network",
                "host",
                // As the tests' own user, so its socket and the files it reads and writes are theirs.
                "--user",
                Files.getAttribute(dir, "unix:uid") + ":" + Files.getAttribute(dir, "unix:gid"),
                "-v",
                dir + ":" + dir,
                "-v",
                sockets + ":" + sockets,
                server.substring(DOCKER.length())));
        return command;
    }

    /**
     * Makes the database of the data directory {@code data}, its owner ANN of the password fed on
     * standard input, as a database kept in a directory is given its owner.
     */
    private static void owner(List<String> launcher, Path data) throws IOException {
        List<String> command = new ArrayList<>(launcher);
        command.addAll(
                List.of("--dir", data.toString(), "user", "add", OWNER.toLowerCase(java.util.Locale.ROOT), "--owner"));
        Process setup = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream in = setup.getOutputStream()) {
            in.write((PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] said = setup.getInputStream().readAllBytes();
            if (!setup.waitFor(START_WITHIN_SECONDS, TimeUnit.SECONDS) || setup.exitValue() != 0) {
                throw new IOException(
                        "the live server's owner was not made: " + new String(said, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            setup.destroy();
        }
    }

    /** The settings of test/server/live.settings, the certificates' and the socket's paths put in. */
    private static List<String> settings(Certificates certificates, Path socket) throws IOException {
        Path certs = certificates.authority().getParent();
        List<String> settings = new ArrayList<>();
        for (String line : Files.readAllLines(SETTINGS, StandardCharsets.UTF_8)) {
            String setting = line.strip();
            if (!setting.isEmpty() && !setting.startsWith("#")) {
                settings.add(setting.replace("{certs}", String.valueOf(certs)).replace("{socket}", socket.toString()));
            }
        }
        return settings;
    }

    /** Copies the server's output to its log, until it says it listens, or it ends. */
    private void awaitListening(Path log) throws IOException {
        CountDownLatch listening = new CountDownLatch(1);
        Thread pump = new Thread(() -> copy(log, listening), "turborel-live-server-log");
        pump.setDaemon(true);
        pump.start();
        try {
            if (!listening.await(START_WITHIN_SECONDS, TimeUnit.SECONDS) || !process.isAlive()) {
                stop();
                throw new IOException("the live server did not start; see " + log);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IOException(e);
        }
    }

    private void copy(Path log, CountDownLatch listening) {
        try (BufferedReader out =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = out.readLine(); line != null; line = out.readLine()) {
                Files.writeString(
                        log,
                        line + "\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                if (line.contains(LISTENING)) {
                    listening.countDown();
                }
            }
        } catch (IOException ended) {
            // The server's output ends as it stops.
        }
        listening.countDown();
    }

    /** Stops the server, waiting on its own process alone. */
    private void stop() {
        if (docker) {
            try {
                new ProcessBuilder("docker", "rm", "-f", CONTAINER + "-server")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(START_WITHIN_SECONDS, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                // The container is removed by --rm as its process ends too.
            }
        }
        process.destroy();
        try {
            if (!process.waitFor(START_WITHIN_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }
    }
}
