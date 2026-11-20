# Transactions

[Contents](README.md)

This chapter will tell how transactions begin and end through the
driver: autocommit, which turborel runs as one request a statement,
COMMIT and ROLLBACK, savepoints, isolation, and how an open cursor or a
large object's locator keeps its transaction. M3 builds autocommit
beneath JDBC, and the chapter is written in M7.
