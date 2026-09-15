package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** What happens at the EDGE of a neutral type's domain, on every registered backend.
  *
  * The codec battery round-trips values both engines agree about. These are values a Scala type admits and one engine's column does not, and
  * the domain limits are NOT conformable: no driver change makes a `DOUBLE` hold NaN or a `text` column hold a NUL byte.
  *
  * What IS conformable, and what every leaf asserts, is the one answer no engine may give:
  *
  * > A value is stored exactly as it was given, or it is refused. It is never stored as a DIFFERENT value.
  *
  * A silent substitution is the only failure, and it cannot be recovered downstream: the row is wrong for every later reader with nothing
  * raised to say so.
  */
class SqlValueDomainConformanceTest extends SqlBackendTest:

    case class Reading(v: Double) derives SqlSchema, CanEqual
    case class Label(v: String) derives SqlSchema, CanEqual
    case class Day(v: java.time.LocalDate) derives SqlSchema, CanEqual
    case class Elapsed(v: java.time.Duration) derives SqlSchema, CanEqual
    case class Narrow(v: String) derives SqlSchema, CanEqual

    /** Runs the probe and answers what happened, so a refusal at either end reads as a refusal rather than as a different value. */
    private def storedExactly[A](probe: A, readBack: A => Boolean)(
        roundTrip: A => A < (Async & Abort[SqlException] & DB)
    )(using Frame): Maybe[String] < (Async & Abort[SqlException] & DB) =
        Abort.run[SqlException](roundTrip(probe)).map {
            case Result.Success(back) => if readBack(back) then Absent else Present(s"stored it as $back")
            case Result.Failure(_)    => Absent
            case Result.Panic(t)      => Present(s"panicked with ${t.getClass.getSimpleName}")
        }

    "a non-finite double is stored exactly or refused" - {
        forEachBackend() { (backend, client, _) =>
            def roundTrip(value: Double)(using Frame) =
                client.executeRaw("DELETE FROM reading")
                    .andThen(Sql.insert[Reading].values(Reading(value)).run)
                    .andThen(Sql.from[Reading]("r").select(_.r.v).run.map(_.head))
            for
                _        <- client.executeRaw(s"CREATE TABLE reading (v ${backend.columnType(ColumnType.Float64)})")
                positive <- storedExactly(Double.PositiveInfinity, _ == Double.PositiveInfinity)(roundTrip)
                negative <- storedExactly(Double.NegativeInfinity, _ == Double.NegativeInfinity)(roundTrip)
                nan      <- storedExactly(Double.NaN, _.isNaN)(roundTrip)
            yield Chunk(("positive infinity", positive), ("negative infinity", negative), ("NaN", nan)).foreach {
                (name, outcome) =>
                    assert(
                        outcome.isEmpty,
                        s"${backend.label}: $name must round-trip or be refused, and the engine ${outcome.getOrElse("")}"
                    )
            }
            end for
        }
    }

    "a string containing a NUL byte is stored exactly or refused" - {
        forEachBackend() { (backend, client, _) =>
            val value = "a" + 0.toChar + "b"
            def roundTrip(v: String)(using Frame) =
                Sql.insert[Label].values(Label(v)).run
                    .andThen(Sql.from[Label]("l").run.map(_.head.v))
            for
                _       <- client.executeRaw(s"CREATE TABLE label (v ${backend.textColumnType} NOT NULL)")
                outcome <- storedExactly(value, _ == value)(roundTrip)
            yield assert(
                outcome.isEmpty,
                s"${backend.label}: a NUL byte must round-trip or be refused, and the engine ${outcome.getOrElse("")} " +
                    s"(code points ${value.map(_.toInt).mkString(",")})"
            )
            end for
        }
    }

    "a date outside one engine's year range is stored exactly or refused" - {
        forEachBackend() { (backend, client, _) =>
            def roundTrip(value: java.time.LocalDate)(using Frame) =
                client.executeRaw("DELETE FROM day")
                    .andThen(Sql.insert[Day].values(Day(value)).run)
                    .andThen(Sql.from[Day]("d").run.map(_.head.v))
            val ancient = java.time.LocalDate.of(1, 1, 1)
            val distant = java.time.LocalDate.of(10000, 1, 1)
            for
                _     <- client.executeRaw(s"CREATE TABLE day (v ${backend.columnType(ColumnType.Date)})")
                early <- storedExactly(ancient, _.equals(ancient))(roundTrip)
                late  <- storedExactly(distant, _.equals(distant))(roundTrip)
            yield Chunk((ancient, early), (distant, late)).foreach { (probe, outcome) =>
                assert(
                    outcome.isEmpty,
                    s"${backend.label}: $probe must round-trip or be refused, and the engine ${outcome.getOrElse("")}"
                )
            }
            end for
        }
    }

    /** The value this suite was written for: past its `TIME` range one engine substitutes its own ceiling and reports success, which is the
      * silent-substitution case. Refused before it is sent now.
      */
    "a duration longer than one engine's time column is stored exactly or refused" - {
        forEachBackend() { (backend, client, _) =>
            val value = java.time.Duration.ofHours(1000)
            def roundTrip(v: java.time.Duration)(using Frame) =
                Sql.insert[Elapsed].values(Elapsed(v)).run
                    .andThen(Sql.from[Elapsed]("e").run.map(_.head.v))
            for
                _       <- client.executeRaw(s"CREATE TABLE elapsed (v ${backend.columnType(ColumnType.Duration)})")
                outcome <- storedExactly(value, _.equals(value))(roundTrip)
            yield assert(
                outcome.isEmpty,
                s"${backend.label}: a thousand hours must round-trip or be refused, and the engine ${outcome.getOrElse("")}"
            )
            end for
        }
    }

    /** Truncation is the same failure in a different type, and one engine truncates rather than refusing unless its strict mode says
      * otherwise. Pinned so a mode change shows up here.
      */
    "a string longer than its column is refused by every backend" in {
        agreeAcrossBackends(expected = Present("refused with SqlServerErrorException")) { (_, client, _) =>
            for
                _    <- client.executeRaw("CREATE TABLE narrow (v VARCHAR(4) NOT NULL)")
                _    <- Sql.insert[Narrow].values(Narrow("abcdefgh")).run
                rows <- Sql.from[Narrow]("n").run
            yield s"eight characters in a four-character column read back as ${rows.head.v}"
            end for
        }
    }

    /** `||` is string concatenation on one engine and a logical OR on the other, so the typed API owes a lowering rather than a passthrough. */
    "concatenation through the typed API answers the same" in {
        agreeAcrossBackends(expected = Present("the concatenation answered rivet!")) { (backend, client, _) =>
            for
                _      <- client.executeRaw(s"CREATE TABLE label (v ${backend.textColumnType} NOT NULL)")
                _      <- Sql.insert[Label].values(Label("rivet")).run
                joined <- Sql.from[Label]("l").select(_.l.v ++ "!").run
            yield s"the concatenation answered ${joined.head}"
        }
    }

end SqlValueDomainConformanceTest
