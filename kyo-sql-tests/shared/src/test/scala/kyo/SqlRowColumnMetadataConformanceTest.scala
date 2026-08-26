package kyo

import kyo.internal.SqlTestBackend

/** Cross-backend battery for what a caller learns about a result set it did not type: the kind of value each column carries, the engine's
  * own name for that type, and the column's value rendered as text.
  *
  * The tool this exists for runs SQL nobody typed. It has no row type to decode into, so the reachable surface used to be a column name and
  * bytes it could not interpret, and the only reader that never failed was `decode[String]`, which answered those bytes as UTF-8 whatever
  * they were. That reader now refuses a column that is not text, which is correct and leaves the generic caller with nothing, so the two
  * halves are one change: [[SqlRow.columnKind]] and [[SqlRow.columnTypeName]] say what a column is, and [[SqlRow.text]] renders the value
  * the column actually holds.
  *
  * Both wire formats are covered. A text-protocol row already carries every value as its rendering; a binary-protocol row carries wire
  * representations that have to be decoded at the column's own type first, which is where a renderer that reinterpreted bytes would answer
  * mojibake. The two must agree, so each group asserts the same rendering under both.
  */
class SqlRowColumnMetadataConformanceTest extends SqlBackendTest:

    private def createProbe(backend: SqlTestBackend, client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        val ddl =
            s"""CREATE TABLE probe (
               |  i ${backend.columnType(SqlTestBackend.ColumnType.Int)},
               |  b ${backend.columnType(SqlTestBackend.ColumnType.BigInt)},
               |  s ${backend.textColumnType},
               |  f ${backend.columnType(SqlTestBackend.ColumnType.Float64)},
               |  d ${backend.columnType(SqlTestBackend.ColumnType.Date)},
               |  t ${backend.columnType(SqlTestBackend.ColumnType.Boolean)},
               |  n ${backend.columnType(SqlTestBackend.ColumnType.Numeric)}
               |)""".stripMargin
        client.executeRaw(ddl).andThen(
            client.executeRaw("INSERT INTO probe VALUES (42, 9001, 'hello', 1.5, '2026-08-04', true, 2.50)")
        ).unit
    end createProbe

    private val select = "SELECT i, b, s, f, d, t, n FROM probe"

    "a column reports the kind of value it carries" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createProbe(backend, client)
                rows <- client.query(select)
                row = rows.head
            yield
                assert(row.columnKind("i") == SqlRow.ColumnKind.Integer, s"i: got ${row.columnKind("i")}")
                assert(row.columnKind("b") == SqlRow.ColumnKind.Integer, s"b: got ${row.columnKind("b")}")
                assert(row.columnKind("s") == SqlRow.ColumnKind.Text, s"s: got ${row.columnKind("s")}")
                assert(row.columnKind("f") == SqlRow.ColumnKind.Float, s"f: got ${row.columnKind("f")}")
                assert(row.columnKind("d") == SqlRow.ColumnKind.Date, s"d: got ${row.columnKind("d")}")
                assert(row.columnKind("n") == SqlRow.ColumnKind.Decimal, s"n: got ${row.columnKind("n")}")
                // MySQL stores a BOOLEAN as TINYINT and reports it as one, so the neutral kind is the integer it is.
                // Pinned per engine rather than as a disjunction: this is the accessor a generic caller acts on, and a
                // regression mapping PostgreSQL's bool to Integer would satisfy an either-answer assertion.
                val expectedBoolKind =
                    if backend.id == "mysql" then SqlRow.ColumnKind.Integer else SqlRow.ColumnKind.Bool
                assert(row.columnKind("t") == expectedBoolKind, s"t: expected $expectedBoolKind, got ${row.columnKind("t")}")
                assert(row.columnKind("nosuch") == SqlRow.ColumnKind.Unknown, "an absent column has no kind")
        }
    }

    "a column reports the engine's own name for its type" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createProbe(backend, client)
                rows <- client.query(select)
                row = rows.head
            yield
                val expected =
                    if backend.id == "mysql" then Map("i" -> "INT", "b"  -> "BIGINT", "f" -> "DOUBLE", "d" -> "DATE")
                    else Map("i"                          -> "int4", "b" -> "int8", "f"   -> "float8", "d" -> "date")
                expected.foreach { (column, name) =>
                    assert(
                        row.columnTypeName(column) == Present(name),
                        s"$column: expected Present($name), got ${row.columnTypeName(column)}"
                    )
                }
                assert(row.columnTypeName("nosuch") == Absent, "an absent column has no type name")
        }
    }

    /** The types whose two renderings coincide, kept as the base case.
      *
      * `t` and `n` are deliberately not here: a bool renders `t` against the server and `true` through the JDK, which is the divergence
      * the leaf below covers over the full set. This one says the ordinary columns were not disturbed.
      */
    "a column renders as text under both wire formats" - {
        forEachBackend() { (backend, client, _) =>
            val expected = Map("i" -> "42", "b" -> "9001", "s" -> "hello", "f" -> "1.5", "d" -> "2026-08-04")
            for
                _        <- createProbe(backend, client)
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binary   <- Kyo.foreach(Chunk.from(expected.keys))(name => extended.head.text(name).map(name -> _))
                textual  <- Kyo.foreach(Chunk.from(expected.keys))(name => simple.head.text(name).map(name -> _))
            yield
                binary.foreach { (name, rendered) =>
                    assert(rendered == Present(expected(name)), s"binary protocol, $name: expected ${expected(name)}, got $rendered")
                }
                textual.foreach { (name, rendered) =>
                    assert(rendered == Present(expected(name)), s"text protocol, $name: expected ${expected(name)}, got $rendered")
                }
            end for
        }
    }

    /** The two column types whose text rendering the backend produces itself, checked against the server that stores them.
      *
      * Both are PostgreSQL's. Neither has a Scala type that spans it, so neither can be rendered by decoding at a type and printing the
      * value: an interval carries calendar and time parts that `java.time.Duration` and `java.time.Period` each refuse half of, and an
      * `inet` is a wire struct with no Scala type here at all. The renderings are written against the wire layout, which makes this leaf
      * the one that says the layout was read right, over values a real server encoded.
      *
      * The interval is deliberately one carrying every component at once, which is exactly what the two typed readings cannot hold.
      */
    "an interval and an inet render as text, and agree across both wire formats" - {
        forEachBackend() { (backend, client, _) =>
            if backend.id != "postgres" then succeed(s"${backend.label} has neither an interval nor an inet type")
            else
                // Three rows, chosen for where a hand-written rendering and the server's own are most likely to
                // disagree: every component at once, which is the value neither typed reading holds; a negative
                // interval, where the sign convention is per-component rather than leading; and an IPv6 address,
                // whose zero-run compression is a rule rather than a formatting choice.
                val select = "SELECT id, span, addr FROM netprobe ORDER BY id"
                for
                    _ <- client.executeRaw("CREATE TABLE netprobe (id int, span interval, addr inet)")
                    _ <- client.executeRaw(
                        """INSERT INTO netprobe VALUES
                          |  (1, interval '1 year 2 mons 3 days 04:05:06', '192.168.1.0/24'),
                          |  (2, interval '-1 year -2 mons -3 days -04:05:06', '2001:db8::1'),
                          |  (3, interval '90 minutes', '::1')""".stripMargin
                    )
                    extended <- client.query(select)
                    simple   <- client.simpleQuery(select)
                    binSpans <- Kyo.foreach(extended)(_.text("span"))
                    binAddrs <- Kyo.foreach(extended)(_.text("addr"))
                    txtSpans <- Kyo.foreach(simple)(_.text("span"))
                    txtAddrs <- Kyo.foreach(simple)(_.text("addr"))
                yield
                    val spans = Chunk(Present("P1Y2M3DT4H5M6S"), Present("P-1Y-2M-3DT-4H-5M-6S"), Present("PT1H30M"))
                    val addrs = Chunk(Present("192.168.1.0/24"), Present("2001:db8::1"), Present("::1"))
                    assert(binSpans == spans, s"binary protocol, spans: got $binSpans")
                    assert(txtSpans == spans, s"text protocol, spans: got $txtSpans")
                    assert(binAddrs == addrs, s"binary protocol, addrs: got $binAddrs")
                    assert(txtAddrs == addrs, s"text protocol, addrs: got $txtAddrs")
                end for
        }
    }

    "a NULL column renders as absent, and an unknown column name aborts" - {
        forEachBackend() { (backend, client, _) =>
            for
                _      <- createProbe(backend, client)
                _      <- client.executeRaw("INSERT INTO probe (i) VALUES (7)")
                rows   <- client.query("SELECT s FROM probe WHERE i = 7")
                value  <- rows.head.text(0)
                missed <- Abort.run[SqlException](rows.head.text("nosuch"))
            yield
                assert(value == Absent, s"a NULL column renders as Absent, got $value")
                missed match
                    case Result.Failure(e: SqlDecodeColumnNotFoundException) =>
                        assert(e.columnName == "nosuch", s"the failure must name the column asked for, got ${e.columnName}")
                        assert(e.availableColumns.nonEmpty, "the failure must list the row's own columns")
                    case other => assert(false, s"expected SqlDecodeColumnNotFoundException, got $other")
                end match
        }
    }

    /** Every column type whose text rendering the two wire protocols used to disagree on, checked against the server that stores them.
      *
      * `SqlRow.text` answers the server's own rendering. Under the text protocol that is what arrived; under the binary protocol the
      * value is decoded and re-rendered here, so the two agreeing is a property of this backend rather than of the wire. They did not
      * agree before: a bool read `true` against the server's `t`, a timestamptz read `2026-08-25T10:00:00Z` against
      * `2026-08-25 10:00:00+00`, a time read `10:00` against `10:00:00`, and a float4 0.1 read `0.10000000149011612`, having been
      * widened to a Double before it was printed.
      *
      * The assertion is against the server's answer for the same row read through the simple protocol, not against a literal written
      * here, so the leaf cannot drift from what PostgreSQL actually writes.
      */
    "every rendered column agrees with the server under both wire formats" - {
        forEachBackend() { (backend, client, _) =>
            if backend.id != "postgres" then succeed(s"${backend.label} renders its own types")
            else
                val select = "SELECT b, ts, tsn, t, tz, d, f4, f8, n FROM rendering ORDER BY id"
                for
                    _ <- client.executeRaw(
                        """CREATE TABLE rendering (
                          |  id int, b bool, ts timestamptz, tsn timestamp, t time,
                          |  tz timetz, d date, f4 float4, f8 float8, n numeric
                          |)""".stripMargin
                    )
                    _ <- client.executeRaw(
                        """INSERT INTO rendering VALUES
                          |  (1, true,  '2026-08-25 10:00:00+00',        '2026-08-25 10:00:00',   '10:00:00',
                          |      '10:00:00+02', '2026-08-25',   0.1,    1e10,  2.50),
                          |  (2, false, '2026-08-25 10:00:00.123456+00', '2026-08-25 10:00:00.5', '10:00:00.25',
                          |      '23:59:59-05', '0001-01-01', 3.4e38,   1.5,   0.000001)""".stripMargin
                    )
                    extended <- client.query(select)
                    simple   <- client.simpleQuery(select)
                    names = Chunk("b", "ts", "tsn", "t", "tz", "d", "f4", "f8", "n")
                    binary  <- Kyo.foreach(extended)(row => Kyo.foreach(names)(n => row.text(n)))
                    textual <- Kyo.foreach(simple)(row => Kyo.foreach(names)(n => row.text(n)))
                yield
                    assert(binary == textual, s"the two protocols must render one value one way:\nbinary $binary\ntext   $textual")
                    // Pinned outright as well, so the leaf fails if BOTH paths drift together.
                    assert(
                        textual.head == Chunk(
                            Present("t"),
                            Present("2026-08-25 10:00:00+00"),
                            Present("2026-08-25 10:00:00"),
                            Present("10:00:00"),
                            Present("10:00:00+02"),
                            Present("2026-08-25"),
                            Present("0.1"),
                            Present("10000000000"),
                            Present("2.50")
                        ),
                        s"row 1: got ${textual.head}"
                    )
                end for
        }
    }

    /** The values with no Scala counterpart, which decoding at a type either refuses or answers wrongly. */
    "a special value renders as the server writes it rather than refusing or guessing" - {
        forEachBackend() { (backend, client, _) =>
            if backend.id != "postgres" then succeed(s"${backend.label} has no such values")
            else
                val select = "SELECT n, d, ts FROM specials ORDER BY id"
                for
                    _ <- client.executeRaw("CREATE TABLE specials (id int, n numeric, d date, ts timestamptz)")
                    _ <- client.executeRaw(
                        """INSERT INTO specials VALUES
                          |  (1, 'NaN', 'infinity', 'infinity'),
                          |  (2, 'Infinity', '-infinity', '-infinity')""".stripMargin
                    )
                    extended <- client.query(select)
                    simple   <- client.simpleQuery(select)
                    names = Chunk("n", "d", "ts")
                    binary  <- Kyo.foreach(extended)(row => Kyo.foreach(names)(n => row.text(n)))
                    textual <- Kyo.foreach(simple)(row => Kyo.foreach(names)(n => row.text(n)))
                yield
                    assert(binary == textual, s"a special value must read one way:\nbinary $binary\ntext   $textual")
                    assert(
                        binary == Chunk(
                            Chunk(Present("NaN"), Present("infinity"), Present("infinity")),
                            Chunk(Present("Infinity"), Present("-infinity"), Present("-infinity"))
                        ),
                        s"got $binary"
                    )
                end for
        }
    }

end SqlRowColumnMetadataConformanceTest
