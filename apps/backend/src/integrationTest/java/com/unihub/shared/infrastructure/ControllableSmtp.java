package com.unihub.shared.infrastructure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An SMTP endpoint a test can break and repair while the application keeps the same address (Phase
 * 4 checklist 2.8): a TCP proxy in front of the singleton Mailpit. Mailpit cannot be asked to fail
 * on cue, and stopping it would take the other tests' mail catcher with it. The listening socket
 * stays open in every mode, so switching is instant and never races a re-bind.
 *
 * <ul>
 *   <li>{@link Mode#FORWARD}: bytes go to Mailpit and back, so the mail really arrives;
 *   <li>{@link Mode#CLOSE}: the connection is accepted and dropped at once, which is what a
 *       provider that is down in front of a load balancer looks like;
 *   <li>{@link Mode#HANG}: the connection is accepted and never answered, not even the greeting,
 *       which only a read timeout gets out of.
 * </ul>
 */
public final class ControllableSmtp implements AutoCloseable {

    public enum Mode {
        FORWARD,
        CLOSE,
        HANG
    }

    private final ServerSocket server;
    private final String upstreamHost;
    private final int upstreamPort;
    private final Set<Socket> held = ConcurrentHashMap.newKeySet();
    private volatile Mode mode = Mode.FORWARD;

    private ControllableSmtp(String upstreamHost, int upstreamPort) {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        try {
            this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open the SMTP proxy", e);
        }
        var acceptor = new Thread(this::acceptLoop, "controllable-smtp-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** In front of the singleton Mailpit; start {@link IntegrationTestSupport} first. */
    public static ControllableSmtp inFrontOfMailpit() {
        return new ControllableSmtp(
                IntegrationTestSupport.MAILPIT.getHost(),
                IntegrationTestSupport.MAILPIT.getMappedPort(1025));
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    public int port() {
        return server.getLocalPort();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                var client = server.accept();
                switch (mode) {
                    case CLOSE -> client.close();
                    case HANG -> held.add(client);
                    case FORWARD -> forward(client);
                }
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void forward(Socket client) throws IOException {
        Socket upstream;
        try {
            upstream = new Socket(upstreamHost, upstreamPort);
        } catch (IOException e) {
            client.close();
            throw e;
        }
        pump(client, upstream);
        pump(upstream, client);
    }

    private static void pump(Socket from, Socket to) {
        var thread =
                new Thread(
                        () -> {
                            try (var in = from.getInputStream()) {
                                in.transferTo(to.getOutputStream());
                            } catch (IOException ignored) {
                                // the other side went away: both sockets are closed below
                            } finally {
                                closeQuietly(from);
                                closeQuietly(to);
                            }
                        },
                        "controllable-smtp-pump");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void close() {
        closeQuietly(server);
        held.forEach(ControllableSmtp::closeQuietly);
        held.clear();
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
