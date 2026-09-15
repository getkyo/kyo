package kyo

import kyo.internal.SqlTestBackend

/** Cross-backend battery for what a caller learns about a result set it did not type: the kind of value each column carries, the engine's
  * own name for that type, and the column's value rendered as text.
  *
  * The tool this exists for runs SQL nobody typed, so it has no row type to decode into: [[SqlRow.columnKind]] and
  * [[SqlRow.columnTypeName]] say what a column is, and [[SqlRow.text]] renders the value it holds. `decode[String]` is not the substitute,
  * since it refuses a column that is not text.
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
                // An engine with no boolean type stores a BOOLEAN declaration as its small integer and reports the
                // integer it is, which the descriptor states as a capability. Pinned per engine through that capability
                // rather than asserted as a disjunction: this is the accessor a generic caller acts on, and an
                // either-answer assertion would also pass if an engine that HAS the type started reporting an integer.
                assert(
                    row.columnKind("t") == backend.booleanColumnKind,
                    s"t: expected ${backend.booleanColumnKind}, got ${row.columnKind("t")}"
                )
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
                // Each engine names its own types, and the descriptor holds the names, so this asserts that the accessor
                // reports the engine's own word for the column without the body knowing which engine answered.
                val expected = Chunk(
                    "i" -> SqlTestBackend.ColumnType.Int,
                    "b" -> SqlTestBackend.ColumnType.BigInt,
                    "f" -> SqlTestBackend.ColumnType.Float64,
                    "d" -> SqlTestBackend.ColumnType.Date
                )
                expected.foreach { (column, kind) =>
                    val name = backend.typeNameFor(kind)
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
      * Gated on [[SqlTestBackend.hasCalendarIntervalColumn]] and [[SqlTestBackend.hasNetworkAddressColumn]], which is the capability pair
      * that decides whether these values exist to render at all. Neither type has a Scala type that spans it, so neither can be rendered by
      * decoding at a type and printing the value: an interval carries calendar and time parts that `java.time.Duration` and
      * `java.time.Period` each refuse half of, and a network address is a wire struct with no Scala type here at all. The renderings are
      * written against the wire layout, which makes this leaf the one that says the layout was read right, over values a real server encoded.
      *
      * The interval is deliberately one carrying every component at once, which is exactly what the two typed readings cannot hold.
      */
    "an interval and an inet render as text, and agree across both wire formats" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.hasCalendarIntervalColumn || !backend.hasNetworkAddressColumn then
                succeed(s"${backend.label} has neither an interval nor a network-address type")
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

    /** Every column type whose two protocols used to disagree, over the values that separated them.
      *
      * `SqlRow.text` answers one string per stored value, and the string is the driver's own rather than the server's, which is what lets
      * it be the same under both protocols: what the server writes is chosen by session settings the connection is never told about, so
      * reproducing it is not a target a driver can hit. The pairs that used to differ are all here: a bool spelled `t` one way and `true`
      * the other, a timestamptz `2026-08-25 10:00:00+00` against `2026-08-25T10:00:00Z`, a time `10:00:00` against `10:00`, and a float4
      * 0.1 against `0.10000000149011612`, having been widened to a Double before it was printed.
      *
      * Both halves are asserted: the two protocols must agree with each other, AND the string is pinned outright, so the leaf fails if
      * both paths drift together.
      */
    "every rendered column agrees under both wire formats" - {
        forEachBackend() { (backend, client, _) =>
            // The one capability this table needs: it stores 0.1, 3.4e38 and 2.50, nothing non-finite.
            if !backend.hasTimeWithOffsetColumn then
                succeed(s"${backend.label} has no time-with-offset column")
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
                    assert(
                        textual.head == Chunk(
                            Present("true"),
                            Present("2026-08-25 10:00:00+00:00"),
                            Present("2026-08-25 10:00:00"),
                            Present("10:00:00"),
                            Present("10:00:00+02:00"),
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

    /** The values with no Scala counterpart, which decoding at a type either refuses or answers wrongly.
      *
      * Gated on [[SqlTestBackend.hasNonFiniteSpecialValues]]: an engine whose numeric and temporal columns refuse `NaN` and the infinities has
      * no such value to store, so there is nothing here for it to render.
      */
    "a special value renders as the server writes it rather than refusing or guessing" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.hasNonFiniteSpecialValues then succeed(s"${backend.label} holds no non-finite special values")
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

    /** A raw query's columns are reachable by the names the caller gave them, on every engine.
      *
      * The raw lane is its own conformance surface. A caller writing SQL by hand gets a result set the typed API never rendered, and what
      * comes back diverges: measured, `SELECT 1 AS MyMixedCase` answers a column named `mymixedcase` on one engine and `MyMixedCase` on the
      * other, because one folds an unquoted alias before the driver ever sees it. A computed column with no alias diverges further, one
      * engine naming it `?column?` and the other repeating the expression text.
      *
      * So this pins the property a caller can actually rely on rather than either engine's spelling: an alias the caller QUOTED comes back
      * verbatim, and lookup by that name works. The folding of an UNQUOTED alias is deliberately not pinned, because that is the engine
      * parsing the caller's own text, which this module does not own; pinning it would codify one engine's parse rule as a contract.
      *
      * The value is asserted beside the name so the leaf cannot pass on a result set that came back empty or misaligned.
      */
    "a raw query's quoted aliases come back verbatim and are reachable by name" - {
        forEachBackend() { (backend, client, _) =>
            val alias = backend.quoteIdent("MyMixedCase")
            for
                rows   <- client.query(s"SELECT 1 AS $alias")
                byName <- rows(0).decode[Int]("MyMixedCase")
                names = rows(0).columnNames
            yield
                assert(byName == 1, s"${backend.label}: expected 1 through the quoted alias, got $byName")
                assert(
                    names == Chunk("MyMixedCase"),
                    s"${backend.label}: a quoted alias must survive verbatim, got $names"
                )
            end for
        }
    }

end SqlRowColumnMetadataConformanceTest
