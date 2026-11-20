/**
 * The JDBC driver for turborel, a relational database system.
 *
 * <p>The driver speaks turborel's native protocol, version 1, itself, in Java alone, with no
 * native code and no library beyond the JDK (RULES.md D1, D3). Its API is JDBC 4.3, in the
 * package {@code com.turborel.jdbc} (RULES.md D2). The protocol's codec and the client beneath
 * the JDBC classes live in packages this module does not export.
 *
 * <p>The module is in alpha, version 0.0.0: the client beneath connects to a server and runs its
 * statements, and the JDBC classes come at milestone M7 (RULES.md D2).
 */
module com.turborel.jdbc {
    requires transitive java.sql;
    // The parser of an XML value's nodes, whose types the exported API will carry.
    requires transitive java.xml;
    // Nullness annotations, read as the code compiles and absent at run time (RULES.md D10);
    // transitive, as the exported API's signatures carry them to the programs that read it.
    requires static transitive org.jspecify;

    exports com.turborel.jdbc;
}
