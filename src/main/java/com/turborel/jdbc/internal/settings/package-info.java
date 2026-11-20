/**
 * Connection settings from outside the program ([CLIENT Connecting by settings]): where the
 * servers are, as whom to connect, how, and what a server must be, read from layers of {@link
 * com.turborel.jdbc.internal.settings.Options} (the program's own or a connection URI's, then a
 * service's section of the service file, then the environment, then the defaults), and connecting
 * as they say to one of several servers, as Turborel's {@code client/src/settings.rs}, {@code
 * failover.rs}, {@code role.rs} and {@code tls/modes.rs} do (RULES.md D1).
 */
@NullMarked
package com.turborel.jdbc.internal.settings;

import org.jspecify.annotations.NullMarked;
