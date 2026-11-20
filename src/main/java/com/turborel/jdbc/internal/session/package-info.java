/**
 * The session: a connection opened by Hello and Ready, what it asks for and what the server
 * tells it, its frames sent and received, cancel and terminate ([PROTOCOL Opening a connection]),
 * as Turborel's {@code client/src/connection.rs}, {@code frames.rs}, {@code told.rs} and {@code
 * cancel.rs} do (RULES.md D1).
 */
@NullMarked
package com.turborel.jdbc.internal.session;

import org.jspecify.annotations.NullMarked;
