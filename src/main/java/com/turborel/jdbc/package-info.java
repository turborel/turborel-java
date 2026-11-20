/**
 * The JDBC 4.3 driver for turborel.
 *
 * <p>Every failure the driver reports is a {@link com.turborel.jdbc.TurborelException}, a {@link
 * java.sql.SQLException} whose SQLSTATE is turborel's and whose vendor code is its SQLCODE
 * (RULES.md R-ERR-1).
 *
 * <p>The package holds, from milestone M7, the driver, its connections, statements, result
 * sets, metadata and data source (RULES.md D2). In this alpha it holds the failure type alone.
 */
@NullMarked
package com.turborel.jdbc;

import org.jspecify.annotations.NullMarked;
