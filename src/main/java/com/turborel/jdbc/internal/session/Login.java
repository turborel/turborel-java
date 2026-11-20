package com.turborel.jdbc.internal.session;

import com.turborel.jdbc.internal.auth.Mechanism;

/**
 * A user and what proves it ([PROTOCOL Opening a connection]).
 *
 * @param user the user, read as an identifier
 * @param mechanism what proves it
 */
public record Login(String user, Mechanism mechanism) {}
