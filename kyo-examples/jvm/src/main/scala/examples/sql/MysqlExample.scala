package examples.sql

// kyo-sql MySQL example
// =====================
// Prerequisites:
//   podman run -d \
//     --name kyo-sql-mysql-demo \
//     -e MYSQL_ROOT_PASSWORD=secret \
//     -e MYSQL_DATABASE=demo \
//     -e MYSQL_USER=demo \
//     -e MYSQL_PASSWORD=secret \
//     -p 3306:3306 \
//     mysql:8.0 \
//     --default-authentication-plugin=mysql_native_password
//
// Run:
//   sbt 'kyo-examples/runMain examples.sql.MysqlExample'
//
// The example demonstrates connecting to MySQL via MysqlConnection, creating
// a table, inserting rows, querying with a filter, streaming, and a transaction
// with rollback.
//
// Note: MySQL access in kyo-sql v1 uses MysqlConnection directly (lower-level
// than the SqlClient.init pool path, which covers PostgreSQL in v1).

import kyo.*
import kyo.sql.*
import kyo.sql.internal.mysql.BoundMysqlParam
import kyo.sql.internal.mysql.MysqlConnection
import kyo.sql.internal.mysql.MysqlRow
import kyo.sql.internal.mysql.types.MysqlEncoder

// ── Example application ───────────────────────────────────────────────────────

object MysqlExample extends KyoApp:

    // Helper: decode column at `idx` of a MysqlRow as a UTF-8 String.
    private def colAsStr(row: MysqlRow, idx: Int): String =
        row.column(idx).fold("NULL")(bytes =>
            new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
        )

    run {
        Scope.run {
            Abort.run[SqlException] {
                for
                    // 1. Connect to MySQL.
                    conn <- MysqlConnection.connect(
                        host = "127.0.0.1",
                        port = 3306,
                        user = "demo",
                        password = Maybe.Present("secret"),
                        db = Maybe.Present("demo")
                    )
                    // Ensure the connection is closed when the Scope exits.
                    _ <- Scope.ensure(Abort.run(conn.quit()).unit)
                    _ <- Console.printLine("Connected to MySQL.")

                    // 2. Create table (idempotent).
                    _ <- conn.simpleExecute(
                        """CREATE TABLE IF NOT EXISTS items (
                              |  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
                              |  name     VARCHAR(255)  NOT NULL,
                              |  price    DECIMAL(10,2) NOT NULL,
                              |  active   TINYINT(1)    NOT NULL DEFAULT 1
                              |)""".stripMargin
                    ).unit
                    _ <- conn.simpleExecute("TRUNCATE TABLE items").unit
                    _ <- Console.printLine("Table ready.")

                    // 3. Insert five rows using the simple-query protocol.
                    _ <- conn.simpleExecute(
                        "INSERT INTO items (name, price, active) VALUES ('Widget A',  9.99, 1)"
                    ).unit
                    _ <- conn.simpleExecute(
                        "INSERT INTO items (name, price, active) VALUES ('Widget B', 14.99, 1)"
                    ).unit
                    _ <- conn.simpleExecute(
                        "INSERT INTO items (name, price, active) VALUES ('Gadget X', 49.99, 0)"
                    ).unit
                    _ <- conn.simpleExecute(
                        "INSERT INTO items (name, price, active) VALUES ('Gadget Y', 99.99, 1)"
                    ).unit
                    _ <- conn.simpleExecute(
                        "INSERT INTO items (name, price, active) VALUES ('Doohickey', 4.99, 1)"
                    ).unit
                    _ <- Console.printLine("Inserted 5 rows.")

                    // 4. Parameterised SELECT using the extended (binary) protocol.
                    minPrice = BigDecimal("10.00")
                    params   = Chunk(BoundMysqlParam(minPrice, MysqlEncoder.bigDecimalEncoder))
                    rows <- conn.extendedQuery(
                        "SELECT id, name, price FROM items WHERE price >= ? ORDER BY price",
                        params
                    )
                    _ <- Console.printLine(s"Items priced >= $minPrice:")
                    _ <- Kyo.foreach(rows) { row =>
                        val id    = colAsStr(row, 0)
                        val name  = colAsStr(row, 1)
                        val price = colAsStr(row, 2)
                        Console.printLine(s"  $id  $name  $price")
                    }

                    // 5. Stream rows packet-by-packet using streamQuery (collects to Chunk).
                    _ <- Console.printLine("Streaming all items:")
                    streamedRows <- Scope.run {
                        conn.streamQuery(
                            "SELECT id, name, price FROM items ORDER BY id",
                            Chunk.empty
                        ).run
                    }
                    _ <- Kyo.foreach(streamedRows) { row =>
                        val name  = colAsStr(row, 1)
                        val price = colAsStr(row, 2)
                        Console.printLine(s"  stream: $name @ $price")
                    }

                    // 6. Transaction with deliberate rollback.
                    _ <- Console.printLine("Transaction demo (deliberate rollback):")
                    txResult <- Abort.run[SqlException] {
                        for
                            _ <- conn.beginTransaction()
                            _ <- conn.simpleExecute(
                                "INSERT INTO items (name, price, active) VALUES ('ROLLBACK_ME', 0.01, 0)"
                            ).unit
                            countRows <- conn.simpleQuery("SELECT COUNT(*) FROM items")
                            _ <- Console.printLine(
                                s"  Inside tx: ${colAsStr(countRows.head, 0)} rows (includes uncommitted row)"
                            )
                            // Trigger rollback by aborting.
                            _ <- Abort.fail(SqlException.Request("Intentional rollback for demo"))
                        yield ()
                    }
                    // Always rollback if Abort was raised during the transaction body.
                    _ <- txResult match
                        case Result.Failure(_) =>
                            for
                                _ <- conn.rollbackTransaction
                                _ <- Console.printLine("  Transaction rolled back as expected.")
                            yield ()
                        case Result.Success(_) =>
                            conn.commitTransaction
                        case Result.Panic(t) =>
                            for
                                _ <- conn.rollbackTransaction
                                _ <- Console.printLine(s"  Panic during transaction: ${t.getMessage}")
                            yield ()

                    // Verify rollback: row count should be 5 again.
                    finalCount <- conn.simpleQuery("SELECT COUNT(*) FROM items")
                    _ <- Console.printLine(
                        s"After rollback: ${colAsStr(finalCount.head, 0)} rows (should be 5)."
                    )

                    // 7. Cleanup.
                    _ <- conn.simpleExecute("DROP TABLE IF EXISTS items").unit
                    _ <- Console.printLine("Table dropped. Done.")
                yield ()
                end for
            }.map {
                case Result.Success(_) => ()
                case Result.Failure(e) =>
                    java.lang.System.err.println(s"[kyo-sql] SqlException: ${e.getMessage}")
                case Result.Panic(t) =>
                    java.lang.System.err.println(s"[kyo-sql] Unexpected error: ${t.getMessage}")
                    t.printStackTrace(java.lang.System.err)
            }
        }
    }

end MysqlExample
