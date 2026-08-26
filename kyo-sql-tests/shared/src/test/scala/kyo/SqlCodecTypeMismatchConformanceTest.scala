package kyo

import kyo.Sql.*
import kyo.internal.SqlTestBackend

/** Cross-backend battery for the column-type check every decode owes its caller: a column whose wire type cannot become the requested Scala
  * type aborts [[SqlDecodeException]] rather than answering a value.
  *
  * The `String` codec is the one this suite exists for. Every other codec resolves the wire representation from the column's type token, so
  * a numeric field over a wider column widens or aborts; the text codec read whatever bytes arrived as UTF-8, which every wire type
  * satisfies, so an `int4`, `int8`, `float8`, `date` or `bool` column read as `String` answered the raw protocol buffer reinterpreted as
  * text. The per-engine byte order is what proves it was the raw buffer: PostgreSQL sends `int4` 42 as `00 00 00 2A` and MySQL sends it as
  * `2A 00 00 00`, so the two engines corrupted the same column into two different strings.
  *
  * Three lanes reach that codec and all three are covered here, because the defect is one codec's and reaches every tier built on it:
  *   - the dynamic lane, [[SqlRow.decode]] by column name;
  *   - the raw typed lane, `sql"...".as[String]`;
  *   - the typed SQL-mirror DSL, where the `String` is a field of a row type (also the schema declaration) or a projected column.
  *
  * Both wire formats are covered too. The check is on the column's type token, not on the format, so the simple/text protocol refuses the
  * same read the extended/binary protocol refuses: a row type declaring `String` for a `date` column is wrong about the column, and which
  * protocol carried the row is not part of that. `SELECT CAST(x AS <text>)` is the spelling for a text rendering of a non-text column, and
  * the last group proves it still works.
  *
  * The final group is the negative control that bounds the check. Text into a numeric field must keep failing with the typed
  * [[SqlDecodeNumericException]] that names the value, and the two width conversions that already convert correctly (`float32` into
  * `Double`, `smallint` into `Long`) must keep converting. A check that over-reached into either would pass every group above and still be
  * wrong.
  */
class SqlCodecTypeMismatchConformanceTest extends SqlBackendTest:

    /** The row every group reads: one column per wire family, holding values whose corrupt renderings are recorded in the finding. */
    private def createProbe(backend: SqlTestBackend, client: SqlClient)(using Frame): Unit < (Async & Abort[SqlException]) =
        val ddl =
            s"""CREATE TABLE probe (
               |  i  ${backend.columnType(SqlTestBackend.ColumnType.Int)},
               |  b  ${backend.columnType(SqlTestBackend.ColumnType.BigInt)},
               |  s  ${backend.textColumnType},
               |  f  ${backend.columnType(SqlTestBackend.ColumnType.Float64)},
               |  d  ${backend.columnType(SqlTestBackend.ColumnType.Date)},
               |  t  ${backend.columnType(SqlTestBackend.ColumnType.Boolean)},
               |  r  ${backend.columnType(SqlTestBackend.ColumnType.Float32)},
               |  sm ${backend.columnType(SqlTestBackend.ColumnType.SmallInt)}
               |)""".stripMargin
        client.executeRaw(ddl).andThen(
            client.executeRaw("INSERT INTO probe VALUES (42, 9001, 'hello', 1.5, '2026-08-04', true, 18.0, 3)")
        ).unit
    end createProbe

    /** Runs `read` and requires it to abort a [[SqlDecodeException]], naming the value it answered instead when it did not.
      *
      * The value is rendered as its UTF-16 code units: a corrupt decode carries NULs and replacement characters, and printing those raw is
      * what killed the log forwarder mid-run when the defect was first probed.
      */
    private def assertDecodeRefused[A](label: String, columnType: String)(read: A < (Async & Abort[SqlException] & DB))(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & DB) =
        Abort.run[SqlException](read).map {
            case Result.Success(value) =>
                val text  = String.valueOf(value)
                val units = text.map(c => f"${c.toInt}%04x").mkString(" ")
                assert(false, s"$label: expected a type-mismatch abort, got Success with utf16 [$units] (length ${text.length})")
            case Result.Failure(e: SqlDecodeColumnTypeMismatchException) =>
                assert(
                    e.getMessage.contains(columnType),
                    s"$label: expected the failure to name the column type '$columnType', got: ${e.getMessage}"
                )
                assert(
                    e.getMessage.contains(e.scalaType),
                    s"$label: expected the failure to name the Scala type that asked, got: ${e.getMessage}"
                )
            case Result.Failure(e) =>
                assert(
                    false,
                    s"$label: expected SqlDecodeColumnTypeMismatchException, got ${e.getClass.getSimpleName}: ${e.getMessage}"
                )
            case Result.Panic(t) =>
                assert(false, s"$label: expected a type-mismatch abort, got a panic ${t.getClass.getSimpleName}: ${t.getMessage}")
        }
    end assertDecodeRefused

    /** How each engine names the probe table's non-text column types in a decode-mismatch report, by probe column.
      *
      * Asserted on rather than ignored: a report that does not name the column's own type leaves the reader with a failure and no way to
      * tell which of a row type's fields is the wrong one.
      */
    private def columnTypeName(backend: SqlTestBackend, column: String): String =
        val names =
            if backend.id == "mysql" then
                Map("i"  -> "INT", "b"  -> "BIGINT", "f" -> "DOUBLE", "d" -> "DATE", "t" -> "TINYINT")
            else Map("i" -> "int4", "b" -> "int8", "f"   -> "float8", "d" -> "date", "t" -> "bool")
        names(column)
    end columnTypeName

    // ── The dynamic lane: SqlRow.decode[String] by name ───────────────────────

    "a non-text column decoded as String aborts, extended/binary protocol" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createProbe(backend, client)
                rows <- client.query("SELECT i, b, f, d, t FROM probe")
                row = rows.head
                _ <- assertDecodeRefused("int as String", columnTypeName(backend, "i"))(row.decode[String]("i"))
                _ <- assertDecodeRefused("bigint as String", columnTypeName(backend, "b"))(row.decode[String]("b"))
                _ <- assertDecodeRefused("float64 as String", columnTypeName(backend, "f"))(row.decode[String]("f"))
                _ <- assertDecodeRefused("date as String", columnTypeName(backend, "d"))(row.decode[String]("d"))
                _ <- assertDecodeRefused("bool as String", columnTypeName(backend, "t"))(row.decode[String]("t"))
            yield ()
        }
    }

    "a non-text column decoded as String aborts, simple/text protocol" - {
        forEachBackend() { (backend, client, _) =>
            for
                _    <- createProbe(backend, client)
                rows <- client.simpleQuery("SELECT i, b, f, d, t FROM probe")
                row = rows.head
                _ <- assertDecodeRefused("int as String", columnTypeName(backend, "i"))(row.decode[String]("i"))
                _ <- assertDecodeRefused("bigint as String", columnTypeName(backend, "b"))(row.decode[String]("b"))
                _ <- assertDecodeRefused("float64 as String", columnTypeName(backend, "f"))(row.decode[String]("f"))
                _ <- assertDecodeRefused("date as String", columnTypeName(backend, "d"))(row.decode[String]("d"))
                _ <- assertDecodeRefused("bool as String", columnTypeName(backend, "t"))(row.decode[String]("t"))
            yield ()
        }
    }

    // ── The raw typed lane: sql"...".as[String] ───────────────────────────────

    "the raw typed lane refuses a non-text column as String" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- createProbe(backend, client)
                _ <- assertDecodeRefused("as[String] over int", columnTypeName(backend, "i"))(sql"SELECT i FROM probe".as[String].run)
                _ <- assertDecodeRefused("as[String] over bigint", columnTypeName(backend, "b"))(sql"SELECT b FROM probe".as[String].run)
                _ <- assertDecodeRefused("as[String] over float64", columnTypeName(backend, "f"))(sql"SELECT f FROM probe".as[String].run)
                _ <- assertDecodeRefused("as[String] over date", columnTypeName(backend, "d"))(sql"SELECT d FROM probe".as[String].run)
                _ <- assertDecodeRefused("as[String] over bool", columnTypeName(backend, "t"))(sql"SELECT t FROM probe".as[String].run)
            yield ()
        }
    }

    // ── The typed DSL: the String is a row-type field, and a projection ───────

    "the typed DSL refuses a row type declaring String for a non-text column" - {
        case class BadIntText(i: String) derives CanEqual
        case class BadDateText(d: String) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _ <- createProbe(backend, client)
                _ <- assertDecodeRefused("row type over int", columnTypeName(backend, "i"))(Sql.from[BadIntText]("p", "probe").run)
                _ <- assertDecodeRefused("row type over date", columnTypeName(backend, "d"))(Sql.from[BadDateText]("p", "probe").run)
            yield ()
        }
    }

    "the typed DSL refuses a projected non-text column read as String" - {
        case class BadIntText(i: String) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _ <- createProbe(backend, client)
                _ <- assertDecodeRefused("projection over int", columnTypeName(backend, "i"))(Sql.from[BadIntText]("p", "probe").select(r =>
                    r.p.i
                ).run)
            yield ()
        }
    }

    // ── A text column is still a String, in both protocols ────────────────────

    "a text column still decodes as String in both protocols" - {
        case class GoodText(s: String) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _        <- createProbe(backend, client)
                extended <- client.query("SELECT s FROM probe")
                binary   <- extended.head.decode[String]("s")
                simple   <- client.simpleQuery("SELECT s FROM probe")
                text     <- simple.head.decode[String]("s")
                typed    <- sql"SELECT s FROM probe".as[String].run
                dsl      <- Sql.from[GoodText]("p", "probe").run
            yield
                assert(binary == "hello", s"extended protocol: expected 'hello', got '$binary'")
                assert(text == "hello", s"simple protocol: expected 'hello', got '$text'")
                assert(typed.head == "hello", s"raw typed lane: expected 'hello', got '${typed.head}'")
                assert(dsl.head == GoodText("hello"), s"typed DSL: expected GoodText(hello), got ${dsl.head}")
        }
    }

    "a SQL cast is the spelling for a text rendering of a non-text column" - {
        case class ProbeInt(i: Int) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _    <- createProbe(backend, client)
                cast <- Sql.from[ProbeInt]("p", "probe").select(r => r.p.i.cast[String]).run
            yield assert(cast.head == "42", s"an int column cast to text: expected '42', got '${cast.head}'")
        }
    }

    // ── The negative control: what the check must NOT disturb ─────────────────

    "text into a numeric field still fails with the typed numeric error" - {
        case class BadTextNumeric(s: Long) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _      <- createProbe(backend, client)
                result <- Abort.run[SqlException](Sql.from[BadTextNumeric]("p", "probe").run)
            yield result match
                case Result.Failure(e: SqlDecodeNumericException) =>
                    assert(
                        e.getMessage.contains("hello"),
                        s"expected the numeric error to name the value 'hello', got: ${e.getMessage}"
                    )
                case other =>
                    assert(false, s"expected SqlDecodeNumericException, got $other")
        }
    }

    "width conversions still convert" - {
        case class WidenedFloat(r: Double) derives CanEqual
        case class WidenedInt(sm: Long) derives CanEqual

        forEachBackend() { (backend, client, _) =>
            for
                _     <- createProbe(backend, client)
                float <- Sql.from[WidenedFloat]("p", "probe").run
                int   <- Sql.from[WidenedInt]("p", "probe").run
            yield
                assert(float.head == WidenedFloat(18.0), s"float32 into Double: expected 18.0, got ${float.head}")
                assert(int.head == WidenedInt(3L), s"smallint into Long: expected 3, got ${int.head}")
        }
    }

end SqlCodecTypeMismatchConformanceTest
