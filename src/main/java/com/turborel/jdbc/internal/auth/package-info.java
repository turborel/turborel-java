/**
 * The client's side of the mechanisms of authentication ([PROTOCOL Mechanisms]): trust,
 * password, certificate, and SCRAM-SHA-256 with SCRAM-SHA-256-PLUS and its key cache, as
 * Turborel's {@code client/src/mechanism.rs}, {@code scram.rs} and {@code protocol/src/scram} do
 * (RULES.md D1).
 */
@NullMarked
package com.turborel.jdbc.internal.auth;

import org.jspecify.annotations.NullMarked;
