package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** What a DSL expression COMPUTES, on every registered backend. The sibling suites pin how a stored value is spelled; a leaf here fails only
  * where a syntax difference has become a meaning difference.
  */
class SqlDslConformanceTest extends SqlBackendTest:

    case class Word(label: String) derives SqlSchema, CanEqual
    case class Placement(v: Maybe[Int]) derives SqlSchema, CanEqual
    case class Bound(v: Maybe[Int]) derives SqlSchema, CanEqual
    case class Amount(v: BigDecimal) derives SqlSchema, CanEqual
    case class Ratio(a: Int, b: Int) derives SqlSchema, CanEqual
    // Nullable operands so the quotient is optional too: the one-way nullability rule has no crossing form for a SET.
    case class Quot(a: Maybe[Int], b: Maybe[Int], q: Maybe[BigDecimal]) derives SqlSchema, CanEqual

    /** `héllo` is five characters and six UTF-8 bytes; the emoji case is five and nine. Chosen so a byte count cannot
      * coincide with the right answer.
      */
    "string length counts characters, not bytes" - {
        forEachBackend() { (backend, client, _) =>
            for
                _        <- client.executeRaw(s"CREATE TABLE word (label ${backend.textColumnType} NOT NULL)")
                _        <- client.executeRaw("INSERT INTO word VALUES ('héllo')")
                accented <- Sql.from[Word]("w").select(view => view.w.label.length).run
                _        <- client.executeRaw("DELETE FROM word")
                _        <- client.executeRaw("INSERT INTO word VALUES ('a😀béc')")
                astral   <- Sql.from[Word]("w").select(view => view.w.label.length).run
            yield
                assert(
                    accented.head == 5,
                    s"${backend.label}: 'héllo' is five characters and six bytes, got ${accented.head}"
                )
                assert(
                    astral.head == 5,
                    s"${backend.label}: an astral emoji string is five characters and nine bytes, got ${astral.head}"
                )
            end for
        }
    }

    "a bare ascending or descending order places absent values the same way" - {
        forEachBackend() { (backend, client, _) =>
            for
                _          <- client.executeRaw(s"CREATE TABLE placement (v ${backend.columnType(ColumnType.Int)})")
                _          <- client.executeRaw("INSERT INTO placement VALUES (2), (NULL), (1)")
                ascending  <- Sql.from[Placement]("r").orderBy(_.r.v.asc).run
                descending <- Sql.from[Placement]("r").orderBy(_.r.v.desc).run
            yield
                assert(
                    ascending.map(_.v) == Chunk(Present(1), Present(2), Absent),
                    s"${backend.label}: ascending expected 1, 2, absent, got ${ascending.map(_.v)}"
                )
                assert(
                    descending.map(_.v) == Chunk(Absent, Present(2), Present(1)),
                    s"${backend.label}: descending expected absent, 2, 1, got ${descending.map(_.v)}"
                )
            end for
        }
    }

    /** The narrow shape that breaks: an offset bound rather than `UNBOUNDED`, and `RANGE` rather than `ROWS`. A render assertion cannot see
      * it, since comparing rendered text never asks a server whether the text is executable.
      */
    "a window over a RANGE frame with an offset bound runs on every engine" - {
        forEachBackend() { (backend, client, _) =>
            val window = Sql.WindowSpec(
                Chunk.empty,
                Chunk.empty,
                Maybe(Sql.WindowFrame(Sql.WindowFrame.Kind.Range, Sql.FrameBound.preceding(2), Maybe(Sql.FrameBound.CurrentRow)))
            )
            for
                _ <- client.executeRaw(s"CREATE TABLE bound (v ${backend.columnType(ColumnType.Int)})")
                _ <- client.executeRaw("INSERT INTO bound VALUES (1), (2), (2), (3), (NULL)")
                rows <- Sql.from[Bound]("b")
                    .select(view => (view.b.v, view.b.v.sum.over(window.copy(orderBy = Chunk(view.b.v.asc)))))
                    .orderBy(_.b.v.asc)
                    .run
                sums = rows.map(_._2)
            yield
                // A RANGE frame takes every row whose value lies within 2 of this row's, not the two rows before it:
                // 1 -> 1, 2 -> 1+2+2, 3 -> 1+2+2+3, and the absent row sums nothing.
                assert(
                    sums == Chunk(Present(1), Present(5), Present(5), Present(8), Absent),
                    s"${backend.label}: expected 1, 5, 5, 8, absent, got $sums"
                )
            end for
        }
    }

    /** `SUM` ignores absent values, so where the absent rows sort cannot change any present row's total. What differs is the absent row's OWN
      * frame, gated on [[SqlTestBackend.windowRangeOffsetHonoursAbsentPlacement]].
      */
    "a window over a mixed-bound RANGE frame agrees on every present key" - {
        forEachBackend() { (backend, client, _) =>
            val window = Sql.WindowSpec(
                Chunk.empty,
                Chunk.empty,
                Maybe(Sql.WindowFrame(
                    Sql.WindowFrame.Kind.Range,
                    Sql.FrameBound.UnboundedPreceding,
                    Maybe(Sql.FrameBound.following(2))
                ))
            )
            for
                _ <- client.executeRaw(s"CREATE TABLE bound (v ${backend.columnType(ColumnType.Int)})")
                _ <- client.executeRaw("INSERT INTO bound VALUES (1), (2), (NULL), (10)")
                rows <- Sql.from[Bound]("b")
                    .select(view => (view.b.v, view.b.v.sum.over(window.copy(orderBy = Chunk(view.b.v.asc)))))
                    .run
                present   = rows.filter(_._1.nonEmpty).sortBy(_._1.getOrElse(0)).map(_._2)
                absentRow = Maybe.fromOption(rows.find(_._1.isEmpty).map(_._2))
            yield
                assert(
                    present == Chunk(Present(3L), Present(3L), Present(13L)),
                    s"${backend.label}: every present key must sum alike, got $present"
                )
                val expectedAbsent: Maybe[Maybe[Long]] =
                    Present(if backend.windowRangeOffsetHonoursAbsentPlacement then Present(13L) else Absent)
                assert(
                    absentRow == expectedAbsent,
                    s"${backend.label}: the absent row's own frame follows the placement this engine can express, got $absentRow"
                )
            end for
        }
    }

    /** A precision-free fixed-point cast means `DECIMAL(10,0)` on one engine, which rounds `2.5` to `3` and CLAMPS `12345678901.25` to
      * `9999999999` with only a warning.
      */
    "a cast to a fixed-point number keeps its value" - {
        forEachBackend() { (backend, client, _) =>
            for
                _          <- client.executeRaw(s"CREATE TABLE amount (v ${backend.columnType(ColumnType.Numeric)})")
                _          <- client.executeRaw("INSERT INTO amount VALUES (2.5)")
                fractional <- Sql.from[Amount]("a").select(view => view.a.v.cast[BigDecimal]).run
                _          <- client.executeRaw("DELETE FROM amount")
                _          <- client.executeRaw("INSERT INTO amount VALUES (12345678901.25)")
                wide       <- Sql.from[Amount]("a").select(view => view.a.v.cast[BigDecimal]).run
            yield
                assert(
                    fractional.head == BigDecimal("2.5"),
                    s"${backend.label}: a cast kept 2.5 as ${fractional.head}"
                )
                // Asserted separately from the fractional case because the failure is different in kind: not a lost
                // fraction but a value replaced wholesale by the largest the implied precision can hold.
                assert(
                    wide.head == BigDecimal("12345678901.25"),
                    s"${backend.label}: a cast turned 12345678901.25 into ${wide.head}"
                )
        }
    }

    /** The engines answer at different scales (`2.5000000000000000` against `2.5000`), which `BigDecimal` compares as equal; comparing the
      * rendered text would report a divergence no caller can observe. Columns rather than literals, so a renderer cannot fold them.
      */
    "dividing one whole number by another keeps the fraction" in {
        agreeAcrossBackends(expected = Present("five divided by two answered 2.5")) { (backend, client, _) =>
            val int = backend.columnType(ColumnType.Int)
            for
                _        <- client.executeRaw(s"CREATE TABLE ratio (a $int NOT NULL, b $int NOT NULL)")
                _        <- Sql.insert[Ratio].values(Ratio(5, 2)).run
                quotient <- Sql.from[Ratio]("r").select(view => view.r.a / view.r.b).run
            yield s"five divided by two answered ${quotient.head.bigDecimal.stripTrailingZeros.toPlainString}"
            end for
        }
    }

    "dividing by zero fails the same way" in {
        agreeAcrossBackends(expected = Present("refused with SqlDecodeColumnAbsentException")) { (backend, client, _) =>
            val int = backend.columnType(ColumnType.Int)
            for
                _        <- client.executeRaw(s"CREATE TABLE ratio (a $int NOT NULL, b $int NOT NULL)")
                _        <- Sql.insert[Ratio].values(Ratio(5, 0)).run
                quotient <- Sql.from[Ratio]("r").select(view => view.r.a / view.r.b).run
            yield s"five divided by zero answered ${quotient.head}"
            end for
        }
    }

    /** Here for the divisor guard rather than the arithmetic: `NULLIF(?, 0)` puts a bind beside an integer literal, and a parameter reaching
      * the server untyped would take its type from that literal and fail at parse time.
      */
    "dividing by a bound fractional value answers the same" in {
        agreeAcrossBackends(expected = Present("ten divided by four answered 2.5")) { (backend, client, _) =>
            for
                _        <- client.executeRaw(s"CREATE TABLE amount (v ${backend.columnType(ColumnType.Numeric)})")
                _        <- Sql.insert[Amount].values(Amount(BigDecimal(10))).run
                quotient <- Sql.from[Amount]("a").select(view => view.a.v / BigDecimal(4)).run
            yield s"ten divided by four answered ${quotient.head.bigDecimal.stripTrailingZeros.toPlainString}"
            end for
        }
    }

    /** Asserted apart from division: an implementation that guarded the word rather than the divergence would leave this behind. */
    "a modulo by zero fails the same way" in {
        agreeAcrossBackends(expected = Present("refused with SqlDecodeColumnAbsentException")) { (backend, client, _) =>
            val int = backend.columnType(ColumnType.Int)
            for
                _         <- client.executeRaw(s"CREATE TABLE ratio (a $int NOT NULL, b $int NOT NULL)")
                _         <- Sql.insert[Ratio].values(Ratio(5, 0)).run
                remainder <- Sql.from[Ratio]("r").select(view => view.r.a % view.r.b).run
            yield s"five modulo zero answered ${remainder.head}"
            end for
        }
    }

    /** The engines notice the overflow in different layers, so this asserts the [[kyo.SqlValueOutOfRange]] marker rather than a class: one
      * raises it server-side, the other widens the expression and fails the decode.
      */
    "arithmetic that overflows the operand type answers the same" in {
        agreeAcrossBackends(expected = Present("refused as a value out of range")) { (backend, client, _) =>
            val int = backend.columnType(ColumnType.Int)
            for
                _ <- client.executeRaw(s"CREATE TABLE ratio (a $int NOT NULL, b $int NOT NULL)")
                _ <- Sql.insert[Ratio].values(Ratio(Int.MaxValue, 1)).run
                answer <- Abort.run[SqlException](Sql.from[Ratio]("r").select(view => view.r.a + view.r.b).run).map {
                    case Result.Success(rows)                  => s"the largest Int plus one answered ${rows.head}"
                    case Result.Failure(_: SqlValueOutOfRange) => "refused as a value out of range"
                    case Result.Failure(e)                     => s"refused with ${e.getClass.getSimpleName}"
                    case Result.Panic(t)                       => s"panicked with ${t.getClass.getSimpleName}"
                }
            yield answer
            end for
        }
    }

    "case folding reaches past ASCII" - {
        forEachBackend() { (backend, client, _) =>
            for
                _       <- client.executeRaw(s"CREATE TABLE word (label ${backend.textColumnType} NOT NULL)")
                _       <- Sql.insert[Word].values(Word("éa")).run
                upper   <- Sql.from[Word]("w").select(view => view.w.label.upper).run
                _       <- client.executeRaw("DELETE FROM word")
                _       <- Sql.insert[Word].values(Word("ÉA")).run
                lowered <- Sql.from[Word]("w").select(view => view.w.label.lower).run
            yield
                assert(upper.head == "ÉA", s"${backend.label}: expected ÉA, got ${upper.head}")
                assert(lowered.head == "éa", s"${backend.label}: expected éa, got ${lowered.head}")
            end for
        }
    }

    /** `coalesce` is the one whose RESULT differs rather than only its argument: a fallback that cannot be absent makes the whole expression
      * non-optional, so this reads back as a plain `Int`.
      */
    "a plain value reaches a nullable column through arithmetic, a fallback and a sentinel" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(s"CREATE TABLE placement (v ${backend.columnType(ColumnType.Int)})")
                _ <- client.executeRaw("INSERT INTO placement VALUES (2), (NULL), (1)")
                // Term[Maybe[Int]]: arithmetic over an absent value is absent, and the column's nullability is kept.
                bumped <- Sql.from[Placement]("p").select(view => view.p.v + 1).orderBy(_.p.v.ascAbsentLast).run
                // Term[Int]: a fallback that cannot be absent makes the whole expression non-optional.
                filled <- Sql.from[Placement]("p").select(view => view.p.v.coalesce(0)).orderBy(_.p.v.ascAbsentLast).run
                // Term[Maybe[Int]]: the sentinel maps onto absence beside the absence already there.
                sentinel <- Sql.from[Placement]("p").select(view => view.p.v.absentIf(1)).orderBy(_.p.v.ascAbsentLast).run
            yield
                assert(
                    bumped == Chunk(Present(2), Present(3), Absent),
                    s"${backend.label}: expected 2, 3, absent, got $bumped"
                )
                assert(filled == Chunk(1, 2, 0), s"${backend.label}: expected 1, 2, 0, got $filled")
                assert(
                    sentinel == Chunk(Absent, Present(2), Absent),
                    s"${backend.label}: expected absent, 2, absent, got $sentinel"
                )
            end for
        }
    }

    /** The natural rendering of an empty list is `IN ()`, a syntax error rather than a false predicate. The seeded row is what makes the leaf
      * mean something: against an empty table every rendering answers zero rows.
      */
    "a membership test over no values selects no rows" - {
        forEachBackend() { (backend, client, _) =>
            val none = Chunk.empty[String]
            for
                _     <- client.executeRaw(s"CREATE TABLE word (label ${backend.textColumnType} NOT NULL)")
                _     <- Sql.insert[Word].values(Word("rivet")).run
                rows  <- Sql.from[Word]("w").where(view => view.w.label.in(none*)).run
                every <- Sql.from[Word]("w").run
            yield
                assert(rows.isEmpty, s"${backend.label}: nothing is a member of an empty set, got ${rows.size} rows")
                // Guards the leaf against passing on a table that never seeded: with no rows at all, the assertion
                // above holds for every rendering, including the ones this exists to catch.
                assert(every.size == 1, s"${backend.label}: expected the seeded row to be present, got ${every.size}")
            end for
        }
    }

    /** The SELECT leaves above are half the surface. In a data-change statement one engine's default `sql_mode` carries
      * `ERROR_FOR_DIVISION_BY_ZERO` and raises, and division reaches a `SET` because a `Term` IS a `SetValue`.
      */
    "dividing by zero inside an update means the same thing" in {
        agreeAcrossBackends(expected = Present("the update stored absent")) { (backend, client, _) =>
            val int = backend.columnType(ColumnType.Int)
            for
                // `q` rather than a word like `out`, which one engine reserves: the DDL here is hand-written text and
                // the renderer's quoting does not reach it.
                _ <- client.executeRaw(s"CREATE TABLE quot (a $int, b $int, q ${backend.columnType(ColumnType.Numeric)})")
                _ <- Sql.insert[Quot].values(Quot(Present(5), Present(0), Absent)).run
                answer <- Abort.run[SqlException](Sql.update[Quot].set(c => c.q := c.a / c.b).where(_.a == Present(5)).run).map {
                    case Result.Success(_) => "ran"
                    case Result.Failure(e) => s"refused with ${e.getClass.getSimpleName}"
                    case Result.Panic(t)   => s"panicked with ${t.getClass.getSimpleName}"
                }
                stored <- Sql.from[Quot]("q").run
            yield
                if answer != "ran" then answer
                else if stored.head.q.isEmpty then "the update stored absent"
                else s"the update stored ${stored.head.q}"
            end for
        }
    }

end SqlDslConformanceTest
