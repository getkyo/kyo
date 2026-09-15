package kyo

import kyo.internal.SqlTestBackend
import kyo.internal.SqlTestBackend.ColumnType

/** The `SqlRow.text` contract on every registered backend: one stored value reads as ONE string, whichever engine holds it and whichever wire
  * protocol carried the row.
  *
  * The table below names one logical value per row, the literal each engine spells it with, and the single rendering both must answer, so an
  * engine rendering its own way fails even when it is self-consistent. Values are chosen where the engines or the protocols disagreed, not
  * where they happened to agree.
  */
class SqlRowTextConformanceTest extends SqlBackendTest:

    /** `literal` takes the descriptor so a literal-syntax difference stays out of the table: one logical value per row, no engine named. */
    private case class Case(
        name: String,
        columnType: ColumnType,
        literal: SqlTestBackend => String,
        expected: String,
        why: String
    )

    private def both(name: String, columnType: ColumnType, literal: String, expected: String, why: String): Case =
        Case(name, columnType, _ => literal, expected, why)

    private val cases: Chunk[Case] = Chunk(
        both("i", ColumnType.Int, "42", "42", "an integer is its digits on both"),
        both("big", ColumnType.BigInt, "9007199254740993", "9007199254740993", "a bigint past the exact-double range keeps its digits"),
        // The column declares (38,10), so the trailing zeros are the value's rather than a rendering choice.
        both("dec", ColumnType.Numeric, "2.50", "2.5000000000", "a fixed-point value keeps the scale its column declares"),
        // Boolean is absent: one engine has no boolean type, so a BOOLEAN column is an integer column and says so.
        // Pinning a shared `true` would assert it has a type it does not. The leaf below covers what IS conformable.
        both("f8a", ColumnType.Float64, "1e10", "10000000000", "a double inside the plain band"),
        both("f8b", ColumnType.Float64, "0.1", "0.1", "a double that is not exactly representable"),
        // Past the band the two engines spell the exponent differently: `1e+23` and `1e23`.
        both("f8c", ColumnType.Float64, "1e23", "1e+23", "a double past the band takes one exponent form on both"),
        // Reading the narrow width at the wide one turns 0.1 into 0.10000000149011612.
        both("f4", ColumnType.Float32, "0.1", "0.1", "a float renders at its own width, not widened"),
        both("d", ColumnType.Date, "'2026-08-25'", "2026-08-25", "a date"),
        both("t", ColumnType.Time, "'10:00:00'", "10:00:00", "a time keeps its seconds"),
        both("dt", ColumnType.DateTime, "'2026-08-25 10:00:00'", "2026-08-25 10:00:00", "a datetime is space-separated"),
        // Json is absent for a different reason: each engine normalizes a document on storage (key order, whitespace),
        // so the stored values genuinely differ and one pinned string would be untrue. The protocol half still holds.
        Case(
            "bin",
            ColumnType.Bytes,
            _.bytesLiteral("deadbeef"),
            "\\xdeadbeef",
            "a byte string, which the two engines spell differently as literals and identically here"
        )
    )

    "one value reads as one string, on every backend and under both wire protocols" - {
        forEachBackend() { (backend, client, _) =>
            val applicable = cases
            val columns    = applicable.map(c => s"${backend.quoteIdent(c.name)} ${backend.columnType(c.columnType)}").mkString(", ")
            val literals   = applicable.map(c => c.literal(backend)).mkString(", ")
            val select     = s"SELECT ${applicable.map(c => backend.quoteIdent(c.name)).mkString(", ")} FROM textrender"
            for
                _        <- client.executeRaw(s"CREATE TABLE textrender ($columns)")
                _        <- client.executeRaw(s"INSERT INTO textrender VALUES ($literals)")
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binary   <- Kyo.foreach(applicable)(c => extended.head.text(c.name))
                textual  <- Kyo.foreach(applicable)(c => simple.head.text(c.name))
            yield applicable.zipWithIndex.foreach { (c, i) =>
                assert(
                    binary(i) == Present(c.expected),
                    s"${backend.label} ${c.name} (${c.why}), extended protocol: expected ${c.expected}, got ${binary(i)}"
                )
                assert(
                    textual(i) == Present(c.expected),
                    s"${backend.label} ${c.name} (${c.why}), simple protocol: expected ${c.expected}, got ${textual(i)}"
                )
            }
            end for
        }
    }

    /** A boolean column is an integer column on an engine with no boolean type, and each engine normalizes a json document differently on
      * storage, so neither has one cross-engine rendering to assert. The protocol half still holds.
      */
    "a kind with no cross-engine rendering still reads the same under both protocols" - {
        forEachBackend() { (backend, client, _) =>
            val select = "SELECT b, j FROM perengine"
            for
                _ <- client.executeRaw(
                    s"""CREATE TABLE perengine (
                       |  b ${backend.columnType(ColumnType.Boolean)},
                       |  j ${backend.columnType(ColumnType.Json)}
                       |)""".stripMargin
                )
                _        <- client.executeRaw("""INSERT INTO perengine VALUES (true, '{"a":1}')""")
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binB     <- extended.head.text("b")
                binJ     <- extended.head.text("j")
                txtB     <- simple.head.text("b")
                txtJ     <- simple.head.text("j")
            yield
                assert(binB == txtB, s"${backend.label} bool: extended $binB against simple $txtB")
                assert(binJ == txtJ, s"${backend.label} json: extended $binJ against simple $txtJ")
                // Not empty, so the leaf cannot pass by both paths failing to render at all.
                assert(binB.nonEmpty && binJ.nonEmpty, s"${backend.label}: expected renderings, got $binB and $binJ")
            end for
        }
    }

    "a sub-second value renders the digits it has, not the scale its column declares" - {
        forEachBackend() { (backend, client, _) =>
            val dtType = backend.columnType(ColumnType.DateTime)
            val select = "SELECT dt FROM fracrender"
            for
                _        <- client.executeRaw(s"CREATE TABLE fracrender (dt $dtType)")
                _        <- client.executeRaw("INSERT INTO fracrender VALUES ('2026-08-25 10:00:00.5')")
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binary   <- extended.head.text("dt")
                textual  <- simple.head.text("dt")
            yield
                assert(binary == Present("2026-08-25 10:00:00.5"), s"${backend.label}, extended protocol: got $binary")
                assert(textual == Present("2026-08-25 10:00:00.5"), s"${backend.label}, simple protocol: got $textual")
            end for
        }
    }

    /** UTC is the only zone that is a property of the value rather than of the session. */
    "an instant renders at UTC" - {
        forEachBackend() { (backend, client, _) =>
            val tsType = backend.columnType(ColumnType.Timestamp)
            val select = "SELECT ts FROM tzrender"
            for
                _        <- client.executeRaw(s"CREATE TABLE tzrender (ts $tsType)")
                _        <- client.executeRaw("INSERT INTO tzrender VALUES ('2026-08-25 10:00:00+00:00')")
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binary   <- extended.head.text("ts")
                textual  <- simple.head.text("ts")
            yield
                assert(binary == Present("2026-08-25 10:00:00+00:00"), s"${backend.label}, extended protocol: got $binary")
                assert(textual == Present("2026-08-25 10:00:00+00:00"), s"${backend.label}, simple protocol: got $textual")
            end for
        }
    }

    /** One leaf rather than two: one engine carries the offset on the wire and is normalised on read, the other has nowhere to put one and is
      * pinned at connect.
      */
    "a mid-session zone change does not change what an instant reads as" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.instantWireCarriesOffset then
                succeed(s"${backend.label} carries no offset on the wire to normalise from")
            else
                val select = "SELECT ts FROM tzshift"
                for
                    _        <- client.executeRaw(s"CREATE TABLE tzshift (ts ${backend.columnType(ColumnType.Timestamp)})")
                    _        <- client.executeRaw("INSERT INTO tzshift VALUES ('2026-08-25 10:00:00+00:00')")
                    _        <- client.executeRaw("SET TimeZone='America/Sao_Paulo'")
                    extended <- client.query(select)
                    simple   <- client.simpleQuery(select)
                    binary   <- extended.head.text("ts")
                    textual  <- simple.head.text("ts")
                yield
                    // The server writes `2026-08-25 07:00:00-03` for this row under that zone.
                    assert(binary == Present("2026-08-25 10:00:00+00:00"), s"extended protocol: got $binary")
                    assert(textual == Present("2026-08-25 10:00:00+00:00"), s"simple protocol under a -03 session: got $textual")
                end for
        }
    }

    /** The write half of the leaf above: a zone change before the INSERT must not move the stored instant either. */
    "a stored instant survives a session that wrote it from another zone" - {
        forEachBackend() { (backend, client, _) =>
            if backend.instantWireCarriesOffset then succeed(s"${backend.label} carries its own offset")
            else
                for
                    _ <- client.executeRaw(
                        s"CREATE TABLE tzpin (id ${backend.columnType(ColumnType.Int)}, ts ${backend.columnType(ColumnType.Timestamp)})"
                    )
                    // Write the row from a session three hours west, naming the instant in that zone.
                    _        <- client.executeRaw("SET time_zone='-03:00'")
                    _        <- client.executeRaw("INSERT INTO tzpin VALUES (1, '2026-08-25 07:00:00')")
                    _        <- client.executeRaw("SET time_zone='+00:00'")
                    extended <- client.query("SELECT ts FROM tzpin")
                    simple   <- client.simpleQuery("SELECT ts FROM tzpin")
                    binary   <- extended.head.text("ts")
                    textual  <- simple.head.text("ts")
                yield
                    assert(binary == Present("2026-08-25 10:00:00+00:00"), s"extended protocol: got $binary")
                    assert(textual == Present("2026-08-25 10:00:00+00:00"), s"simple protocol: got $textual")
                end for
        }
    }

    /** A rendering has no single element type to decode at, which settles the two cases a typed read cannot express: an absent element, and one
      * whose text would be misread bare.
      */
    "an array renders its elements, including the ones a typed read refuses" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.hasNativeArrayColumns then succeed(s"${backend.label} has no array column type")
            else
                val select = "SELECT i, t, n FROM arrayrender"
                for
                    _ <- client.executeRaw(
                        s"""CREATE TABLE arrayrender (
                           |  i ${backend.columnType(ColumnType.IntArray)},
                           |  t ${backend.columnType(ColumnType.TextArray)},
                           |  n ${backend.columnType(ColumnType.IntArray)}
                           |)""".stripMargin
                    )
                    _ <- client.executeRaw(
                        """INSERT INTO arrayrender VALUES
                          |  ('{1,2,3}', '{"a b","c,d",""}', '{1,NULL,3}')""".stripMargin
                    )
                    extended <- client.query(select)
                    simple   <- client.simpleQuery(select)
                    names = Chunk("i", "t", "n")
                    binary  <- Kyo.foreach(names)(n => extended.head.text(n))
                    textual <- Kyo.foreach(names)(n => simple.head.text(n))
                yield
                    val expected = Chunk(
                        Present("{1,2,3}"),
                        // Quoted because a space and a comma would otherwise be read as structure, and the empty
                        // string because bare it would be nothing at all.
                        Present("""{"a b","c,d",""}"""),
                        // The element a typed read declines, since no Scala element type holds it.
                        Present("{1,NULL,3}")
                    )
                    assert(binary == expected, s"extended protocol: got $binary")
                    assert(textual == expected, s"simple protocol: got $textual")
                end for
        }
    }

    /** Only the BINARY array header names its element type, so the text protocol is where a passthrough hides. The element types come from
      * [[SqlTestBackend.arrayRenderCases]] and are chosen where the server's spelling and this module's differ.
      */
    "an array's elements render at their own type under both protocols" - {
        forEachBackend() { (backend, client, _) =>
            if backend.arrayRenderCases.isEmpty then succeed(s"${backend.label} has no array element types to render")
            else
                Kyo.foreachDiscard(backend.arrayRenderCases.zipWithIndex) { case ((columnType, literal, expected), i) =>
                    val table  = s"arrayelem$i"
                    val select = s"SELECT v FROM $table"
                    for
                        _        <- client.executeRaw(s"CREATE TABLE $table (v $columnType)")
                        _        <- client.executeRaw(s"INSERT INTO $table VALUES ($literal)")
                        extended <- client.query(select)
                        simple   <- client.simpleQuery(select)
                        binary   <- extended.head.text("v")
                        textual  <- simple.head.text("v")
                    yield
                        assert(
                            binary == Present(expected),
                            s"${backend.label}: $columnType under the extended protocol rendered $binary, expected $expected"
                        )
                        assert(
                            textual == Present(expected),
                            s"${backend.label}: $columnType under the simple protocol rendered $textual, expected $expected"
                        )
                    end for
                }.andThen(succeed)
        }
    }

    /** Values only one engine holds, so no cross-engine claim exists. The cross-PROTOCOL half does, and it is the half that breaks when a text
      * arm delegates to a parser narrower than the column. Cases from [[SqlTestBackend.protocolAgreementCases]].
      */
    "a value only one engine holds still reads the same under both protocols" - {
        forEachBackend() { (backend, client, _) =>
            if backend.protocolAgreementCases.isEmpty then succeed(s"${backend.label} names no engine-specific columns to probe")
            else
                Kyo.foreachDiscard(backend.protocolAgreementCases.zipWithIndex) { case ((columnType, literal, expected), i) =>
                    val table  = s"protoagree$i"
                    val select = s"SELECT v FROM $table"
                    for
                        _        <- client.executeRaw(s"CREATE TABLE $table (v $columnType)")
                        _        <- client.executeRaw(s"INSERT INTO $table VALUES ($literal)")
                        extended <- client.query(select)
                        simple   <- client.simpleQuery(select)
                        binary   <- extended.head.text("v")
                        textual  <- simple.head.text("v")
                    yield
                        assert(
                            binary == Present(expected),
                            s"${backend.label}: $columnType under the extended protocol rendered $binary, expected $expected"
                        )
                        assert(
                            textual == Present(expected),
                            s"${backend.label}: $columnType under the simple protocol rendered $textual, expected $expected"
                        )
                    end for
                }.andThen(succeed)
        }
    }

    /** One engine's `TIME` is a signed span reaching past a day in both directions, which no time-of-day type holds. */
    "a signed span renders over its whole range" - {
        forEachBackend() { (backend, client, _) =>
            if !backend.timeColumnIsSignedSpan then succeed(s"${backend.label} has no signed-span time type")
            else
                val select = "SELECT t FROM spanrender ORDER BY id"
                for
                    _ <- client.executeRaw(
                        s"CREATE TABLE spanrender (id ${backend.columnType(ColumnType.Int)}, t ${backend.columnType(ColumnType.Time)})"
                    )
                    _        <- client.executeRaw("INSERT INTO spanrender VALUES (1, '838:59:59'), (2, '-838:59:59'), (3, '-10:00:00')")
                    extended <- client.query(select)
                    simple   <- client.simpleQuery(select)
                    binary   <- Kyo.foreach(extended)(_.text("t"))
                    textual  <- Kyo.foreach(simple)(_.text("t"))
                yield
                    val expected = Chunk(Present("838:59:59"), Present("-838:59:59"), Present("-10:00:00"))
                    assert(binary == expected, s"extended protocol: got $binary")
                    assert(textual == expected, s"simple protocol: got $textual")
                end for
        }
    }

    /** `24:00:00` is a legal value one engine's `time` column reaches and `LocalTime` refuses. */
    "the end-of-day time renders rather than refusing" - {
        forEachBackend() { (backend, client, _) =>
            val select = "SELECT t FROM endofday"
            for
                _        <- client.executeRaw(s"CREATE TABLE endofday (t ${backend.columnType(ColumnType.Time)})")
                _        <- client.executeRaw("INSERT INTO endofday VALUES ('24:00:00')")
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binary   <- extended.head.text("t")
                textual  <- simple.head.text("t")
            yield
                assert(binary == Present("24:00:00"), s"${backend.label}, extended protocol: got $binary")
                assert(textual == Present("24:00:00"), s"${backend.label}, simple protocol: got $textual")
            end for
        }
    }

    /** The settings that make "the server's own rendering" undefined as a target, from [[SqlTestBackend.outputAffectingSettings]]. An engine
      * with no such knob contributes none and the leaf still asserts the two protocols agree.
      */
    "a session setting does not change what a value reads as" - {
        forEachBackend() { (backend, client, _) =>
            val select = "SELECT f, b FROM settingrender"
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE settingrender (f ${backend.columnType(ColumnType.Float64)}, b ${backend.columnType(ColumnType.Bytes)})"
                )
                _        <- client.executeRaw(s"INSERT INTO settingrender VALUES (1e23, ${backend.bytesLiteral("deadbeef")})")
                _        <- Kyo.foreachDiscard(backend.outputAffectingSettings)(client.executeRaw(_))
                extended <- client.query(select)
                simple   <- client.simpleQuery(select)
                binF     <- extended.head.text("f")
                binB     <- extended.head.text("b")
                txtF     <- simple.head.text("f")
                txtB     <- simple.head.text("b")
            yield
                assert(binF == Present("1e+23"), s"${backend.label}, extended protocol, float: got $binF")
                assert(txtF == Present("1e+23"), s"${backend.label}, simple protocol under ${backend.outputAffectingSettings}: got $txtF")
                assert(binB == Present("\\xdeadbeef"), s"${backend.label}, extended protocol, bytes: got $binB")
                assert(txtB == Present("\\xdeadbeef"), s"${backend.label}, simple protocol, bytes: got $txtB")
            end for
        }
    }

    /** A column whose text form is chosen by a session setting the connection is never told (`money` takes its digits and symbol from
      * `lc_monetary`) has no neutral value to render from. The ARRAY of one is a separate entry because it is a separate path: an array
      * dispatches its own elements and can render where the scalar refuses.
      */
    "a column with no neutral rendering fails the same way under both protocols" - {
        forEachBackend() { (backend, client, _) =>
            if backend.unrenderableColumns.isEmpty then succeed(s"${backend.label} has no column type this module declines to render")
            else
                def outcome(r: Result[SqlException, Maybe[String]]): String =
                    r match
                        case Result.Success(v) => s"answered ${v.getOrElse("absent")}"
                        case Result.Failure(e) => s"refused with ${e.getClass.getSimpleName}"
                        case Result.Panic(t)   => s"panicked with ${t.getClass.getSimpleName}"
                Kyo.foreachDiscard(backend.unrenderableColumns.zipWithIndex) { case ((columnType, literal), i) =>
                    val table  = s"unrenderable$i"
                    val select = s"SELECT v FROM $table"
                    for
                        _        <- client.executeRaw(s"CREATE TABLE $table (v $columnType)")
                        _        <- client.executeRaw(s"INSERT INTO $table VALUES ($literal)")
                        extended <- client.query(select)
                        simple   <- client.simpleQuery(select)
                        binary   <- Abort.run[SqlException](extended.head.text("v"))
                        textual  <- Abort.run[SqlException](simple.head.text("v"))
                    yield
                        assert(
                            outcome(binary) == outcome(textual),
                            s"${backend.label}: $columnType must read one way, and the extended protocol ${outcome(binary)} " +
                                s"while the simple protocol ${outcome(textual)}"
                        )
                        assert(
                            outcome(binary) == "refused with SqlDecodeColumnNotRenderableException",
                            s"${backend.label}: $columnType expected the typed refusal on both protocols, got ${outcome(binary)}"
                        )
                    end for
                }.andThen(succeed)
        }
    }

end SqlRowTextConformanceTest
