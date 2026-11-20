/**
 * The native protocol's codec, version 1: its frames, without I/O, so tests check it byte by
 * byte ({@code doc/client/05-protocol.md}, RULES.md D1).
 *
 * <p>The module does not export this package; the driver's own classes alone use it.
 */
@NullMarked
package com.turborel.jdbc.internal.codec;

import org.jspecify.annotations.NullMarked;
