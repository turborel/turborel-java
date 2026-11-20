/**
 * The transport layer: how the client reaches a server and how long it waits on it, over TCP, a
 * Unix socket, a Windows named pipe or TLS ([CLIENT Connecting], [CLIENT Timeouts]), as
 * Turborel's {@code client/src/target.rs}, {@code timeouts.rs} and {@code pipe.rs} do (RULES.md
 * D1).
 */
@NullMarked
package com.turborel.jdbc.internal.transport;

import org.jspecify.annotations.NullMarked;
