package examples.sql

// kyo-sql Postgres example
// ========================
// Prerequisites:
//   podman run -d \
//     --name kyo-sql-pg-demo \
//     -e POSTGRES_USER=demo \
//     -e POSTGRES_PASSWORD=secret \
//     -e POSTGRES_DB=demo \
//     -p 5432:5432 \
//     postgres:16
//
// Run:
//   sbt 'kyo-examples/runMain examples.sql.PostgresExample'
//
// The example creates a table, inserts rows, queries with a filter,
// streams the full table, demonstrates a transaction with rollback,
// and then cleans up. It requires a Postgres server on localhost:5432
// with the credentials above.
//
// Note: This example uses executeRaw for DDL and DML for simplicity.
// For parameterised queries in production, use the sql"..." interpolator
// with client.queryFrag / client.executeFrag.

import kyo.*
import kyo.sql.*
import kyo.sql.internal.RowDecoder
import kyo.sql.internal.postgres.BoundParam
import kyo.sql.internal.postgres.types.PostgresEncoder

// ── Data model ───────────────────────────────────────────────────────────────

case class Product(id: Long, name: String, price: BigDecimal, inStock: Boolean)
    derives CanEqual

given productDecoder: RowDecoder[Product] = RowDecoder.derived

// ── Example application ───────────────────────────────────────────────────────

object PostgresExample extends KyoApp:

    val pgUrl = "postgres://demo:secret@localhost:5432/demo"

    // Decode the value at column 0 as a String (for COUNT(*) etc.)
    private def col0Str(row: Row)(using Frame): String < Abort[SqlException.Decode] =
        row.columnAs[String](0)

    // ── Phases extracted into methods to avoid deep macro-expansion nesting ──────

    private def phase2CreateTable(
        client: SqlClient[Backend.Postgres]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        client.executeRaw(
            """CREATE TABLE IF NOT EXISTS products (
              |  id       BIGSERIAL PRIMARY KEY,
              |  name     TEXT        NOT NULL,
              |  price    NUMERIC     NOT NULL,
              |  in_stock BOOLEAN     NOT NULL DEFAULT TRUE
              |)""".stripMargin
        ).unit

    private def phase3Insert(
        client: SqlClient[Backend.Postgres]
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        // Using the extended protocol with explicit BoundParams for parameterised INSERTs.
        val insertSql = "INSERT INTO products (name, price, in_stock) VALUES ($1, $2, $3)"
        val rows = Chunk(
            ("Widget A", "9.99", "true"),
            ("Widget B", "14.99", "true"),
            ("Gadget X", "49.99", "false"),
            ("Gadget Y", "99.99", "true"),
            ("Doohickey", "4.99", "true")
        )
        Kyo.foreach(rows) { case (name, price, stock) =>
            val params = Chunk(
                BoundParam(name, PostgresEncoder.textText),
                BoundParam(price, PostgresEncoder.textText),
                BoundParam(stock, PostgresEncoder.textText)
            )
            client.execute(insertSql, params).unit
        }.unit
    end phase3Insert

    private def phase4Select(
        client: SqlClient[Backend.Postgres],
        minPrice: String
    )(using Frame): Chunk[Row] < (Async & Abort[SqlException]) =
        val params = Chunk(BoundParam(minPrice, PostgresEncoder.textText))
        client.query(
            "SELECT id, name, price, in_stock FROM products WHERE price >= $1::numeric ORDER BY price",
            params
        )
    end phase4Select

    private def phase5Stream(
        client: SqlClient[Backend.Postgres]
    )(using Frame): Chunk[Row] < (Async & Abort[SqlException] & Scope) =
        client.streamQuery(
            "SELECT id, name, price, in_stock FROM products ORDER BY id",
            batchSize = 2 // small batch to exercise the portal fetch-loop
        ).run

    private def phase6Transaction(
        client: SqlClient[Backend.Postgres]
    )(using Frame): Result[SqlException, Unit] < (Async & Abort[SqlException]) =
        Abort.run[SqlException] {
            client.transaction() {
                for
                    _ <- client.executeRaw(
                        "INSERT INTO products (name, price, in_stock) VALUES ('ROLLBACK_ME', 0.01, false)"
                    ).unit
                    count <- client.query("SELECT COUNT(*) FROM products")
                    n     <- col0Str(count.head)
                    _     <- Console.printLine(s"  Inside tx: $n rows (includes uncommitted row)")
                    _     <- Abort.fail(SqlException.Request("Intentional rollback for demo"))
                yield ()
            }
        }

    run {
        Scope.run {
            Abort.run[SqlException] {
                for
                    client <- SqlClient.init(pgUrl, SqlClientConfig.default.copy(maxConnections = 5))
                    _ <- SqlClient.let(client) {
                        for
                            // 2. Create table.
                            _ <- phase2CreateTable(client)
                            _ <- Console.printLine("Table ready.")

                            // 3. Insert five rows.
                            _ <- phase3Insert(client)
                            _ <- Console.printLine("Inserted 5 rows.")

                            // 4. Parameterised SELECT.
                            rows     <- phase4Select(client, "10.00")
                            products <- Kyo.foreach(rows)(r => productDecoder.decode(r))
                            _        <- Console.printLine("Products priced >= 10.00:")
                            _ <- Kyo.foreach(products)(p =>
                                Console.printLine(s"  ${p.id}  ${p.name}  ${p.price}")
                            )

                            // 5. Stream the full table using the portal protocol.
                            _        <- Console.printLine("Streaming all products:")
                            streamed <- Scope.run(phase5Stream(client))
                            _ <- Kyo.foreach(streamed) { row =>
                                for
                                    name  <- row.columnAs[String]("name")
                                    price <- row.columnAs[String]("price")
                                    _     <- Console.printLine(s"  stream: $name @ $price")
                                yield ()
                            }

                            // 6. Transaction with deliberate rollback.
                            _        <- Console.printLine("Transaction demo (deliberate rollback):")
                            txResult <- phase6Transaction(client)
                            _ <- txResult match
                                case Result.Failure(SqlException.Request(msg)) =>
                                    Console.printLine(s"  Rolled back as expected: $msg")
                                case other =>
                                    Console.printLine(s"  Unexpected result: $other")

                            // Verify rollback: row count should be 5 again.
                            finalCount <- client.query("SELECT COUNT(*) FROM products")
                            n          <- col0Str(finalCount.head)
                            _          <- Console.printLine(s"After rollback: $n rows (should be 5).")

                            // 7. Cleanup.
                            _ <- client.executeRaw("DROP TABLE IF EXISTS products").unit
                            _ <- Console.printLine("Table dropped. Done.")
                        yield ()
                    }
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

end PostgresExample
