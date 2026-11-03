package com.unihub.shared.infrastructure;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A host port for a container that must keep its port across a stop and a start (the outage ITs).
 * Asking the operating system for port 0 hands out an ephemeral port (49152 and up), and the JVM's
 * own client sockets (Postgres, Redis, AMQP, retrying listeners) draw from the same range: the port
 * that was free when asked for can be a client socket's local port a moment later, and Docker then
 * fails to bind it ("address already in use"). A port below the ephemeral range cannot be taken
 * that way, so these are chosen from 20000-29999.
 */
public final class FixedPorts {

    private FixedPorts() {}

    public static int free() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(20_000, 30_000);
            try (var socket = new ServerSocket(port)) {
                return socket.getLocalPort();
            } catch (IOException taken) {
                // try another
            }
        }
        throw new IllegalStateException("no free port in 20000-29999");
    }
}
