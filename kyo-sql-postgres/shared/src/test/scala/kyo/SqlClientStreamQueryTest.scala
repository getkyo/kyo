package kyo

import java.nio.charset.StandardCharsets

/** Pins what each [[SqlClient.streamQuery]] overload sends to the connection SPI, and what a consumer sees coming back.
  *
  * Driven by [[SqlClientProbe]]: the three overloads differ only in what they render and which batch size they choose, both of which the
  * probe reports exactly. The pool side of streaming, that a finished stream gives its connection back, is
  * [[SqlClientStreamSlotTest]]'s.
  */
class SqlClientStreamQueryTest extends Test:

    import SqlClientProbe.Call
    import SqlClientProbe.Statement

    private def row(value: String): SqlRow =
        SqlRowCodecMock.row(
            Chunk("v"),
            Chunk(Present(Span.from(value.getBytes(StandardCharsets.UTF_8)))),
            Chunk.empty
        )

    private def firstColumn(r: SqlRow): String =
        r.column(0).map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8)).getOrElse("")

    private val rows: Chunk[SqlRow] = Chunk(row("a"), row("b"), row("c"))

    "streaming a raw statement sends it as written, batched at the configured size" in {
        SqlClientProbe.withClient(config = SqlConfig(streamBatchSize = 7), rows = rows) { (client, recorded) =>
            Scope.run(client.streamQuery("SELECT v FROM t").run).flatMap { streamed =>
                recorded().map { calls =>
                    assert(streamed.map(firstColumn) == Chunk("a", "b", "c"), s"expected the probe's rows in order, got $streamed")
                    assert(
                        calls == Chunk(Call.Streamed(Statement("SELECT v FROM t", Chunk.empty), 7)),
                        s"expected one stream at the configured batch size; saw $calls"
                    )
                }
            }
        }
    }

    "streaming a DSL statement carries its bind values" in {
        SqlClientProbe.withClient(config = SqlConfig(streamBatchSize = 7), rows = rows) { (client, recorded) =>
            val min = 3
            Scope.run(client.streamQuery(sql"SELECT v FROM t WHERE v > $min").run).andThen {
                recorded().map { calls =>
                    assert(
                        calls == Chunk(Call.Streamed(Statement("SELECT v FROM t WHERE v > $1", Chunk(3)), 7)),
                        s"expected the fragment's bind to reach the SPI; saw $calls"
                    )
                }
            }
        }
    }

    "an explicit batch size overrides the configured one" in {
        SqlClientProbe.withClient(config = SqlConfig(streamBatchSize = 7), rows = rows) { (client, recorded) =>
            Scope.run(client.streamQuery(Sql.Fragment.lit[Any]("SELECT v FROM t"), 2).run).andThen {
                recorded().map { calls =>
                    assert(
                        calls == Chunk(Call.Streamed(Statement("SELECT v FROM t", Chunk.empty), 2)),
                        s"expected the explicit batch size; saw $calls"
                    )
                }
            }
        }
    }

    "a consumer that stops early takes only what it asked for" in {
        SqlClientProbe.withClient(rows = rows) { (client, _) =>
            // Not a hang fix: `kyo.test.internal.TestBase.timeout` already bounds this leaf at 120s and this
            // suite does not override it. What 30s buys is failing in a quarter of that time and naming what
            // the read was waiting for, where a bare per-test TimedOut says only which leaf died.
            Abort.run[Timeout](
                Scope.run(client.streamQuery("SELECT v FROM t").take(2).run)
            ).map {
                case Result.Success(streamed) =>
                    assert(streamed.map(firstColumn) == Chunk("a", "b"), s"expected the first two rows only, got $streamed")
                case Result.Failure(_: Timeout) =>
                    fail("timed out after 30s waiting for the first two rows of the stream")
                case Result.Panic(t) =>
                    fail(s"panicked waiting for the first two rows of the stream: ${t.getMessage}")
            }
        }
    }

    "the default batch size is the one the config documents" in {
        SqlClientProbe.withClient(rows = rows) { (client, recorded) =>
            Scope.run(client.streamQuery("SELECT v FROM t").run).andThen {
                recorded().map { calls =>
                    assert(
                        calls == Chunk(Call.Streamed(Statement("SELECT v FROM t", Chunk.empty), 64)),
                        s"expected SqlConfig.default.streamBatchSize; saw $calls"
                    )
                }
            }
        }
    }

end SqlClientStreamQueryTest
