package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test
import kyo.db.Idiom
import scala.compiletime.testing.typeCheckErrors

/** Verifies the SQL type name [[MysqlDialect]] emits inside `CAST(… AS …)` for each cast target.
  *
  * MySQL's `CAST` takes a fixed short list of target types rather than every type it can store in a column, so this is not the Postgres
  * table with different spellings: several targets collapse onto one name (every integer width and Boolean onto `SIGNED`, UUID onto
  * `CHAR`), and several have no cast spelling at all and have to fail.
  */
class MysqlDialectCastTest extends Test:

    case class Row(id: Long, name: String, amount: BigDecimal)

    private val idColumn = Column["id", Long]("r", "id", "id")

    private def castSql[B](target: Term[B]): String =
        Sql.from[Row]("r").select(_ => target).render(MysqlDialect).onlySql.get

    // MySQL casts text through CHAR; it has no TEXT cast target.
    "casting to a text target uses CHAR" in {
        assert(castSql(idColumn.cast[String]).contains("CAST(`r`.`id` AS CHAR)"))
    }

    // SIGNED is MySQL's only signed-integer cast target, so every width and Boolean land on it. Emitting INTEGER,
    // SMALLINT, or BOOLEAN here would be SQL MySQL cannot parse.
    "every integer width and Boolean cast through SIGNED" in {
        assert(castSql(idColumn.cast[Byte]).contains("CAST(`r`.`id` AS SIGNED)"))
        assert(castSql(idColumn.cast[Short]).contains("CAST(`r`.`id` AS SIGNED)"))
        assert(castSql(idColumn.cast[Int]).contains("CAST(`r`.`id` AS SIGNED)"))
        assert(castSql(idColumn.cast[Long]).contains("CAST(`r`.`id` AS SIGNED)"))
        assert(castSql(idColumn.cast[Boolean]).contains("CAST(`r`.`id` AS SIGNED)"))
    }

    "casting to a floating-point or decimal target uses the MySQL names" in {
        assert(castSql(idColumn.cast[Float]).contains("CAST(`r`.`id` AS FLOAT)"))
        assert(castSql(idColumn.cast[Double]).contains("CAST(`r`.`id` AS DOUBLE)"))
        assert(castSql(idColumn.cast[BigDecimal]).contains("CAST(`r`.`id` AS DECIMAL)"))
    }

    // MySQL stores a UUID as a string, so it casts through CHAR rather than a type of its own. TEXT is not a cast
    // target MySQL accepts here.
    "a UUID casts through CHAR" in {
        assert(castSql(idColumn.cast[java.util.UUID]).contains("CAST(`r`.`id` AS CHAR)"))
    }

    // The zone-free and zone-carrying date-times are where the flavors visibly disagree: DATETIME here,
    // TIMESTAMP and TIMESTAMPTZ on Postgres.
    "casting to each supported temporal uses its own MySQL type name" in {
        assert(castSql(idColumn.cast[java.time.LocalDate]).contains("CAST(`r`.`id` AS DATE)"))
        assert(castSql(idColumn.cast[java.time.LocalTime]).contains("CAST(`r`.`id` AS TIME)"))
        assert(castSql(idColumn.cast[java.time.LocalDateTime]).contains("CAST(`r`.`id` AS DATETIME)"))
        assert(castSql(idColumn.cast[Instant]).contains("CAST(`r`.`id` AS DATETIME)"))
    }

    "casting to bytes uses BINARY and to a document uses JSON" in {
        assert(castSql(idColumn.cast[Span[Byte]]).contains("CAST(`r`.`id` AS BINARY)"))
        assert(castSql(idColumn.cast[JsonText]).contains("CAST(`r`.`id` AS JSON)"))
    }

    // Each target below has a portable SqlType.Type that MySQL's cast grammar has no name for. Reporting that is the
    // point: falling back to TEXT for any of them emits SQL the server rejects.
    "casting to a type MySQL cannot cast to fails naming that type" in {
        val interval = intercept[SqlUnsupportedDialectFeatureException] {
            castSql(idColumn.cast[java.time.Period])
        }
        assert(interval.feature == "CAST AS CalendarInterval", s"expected the SQL type named, got: ${interval.feature}")
        assert(interval.dialect == Idiom.Id("mysql"))

        val offsetTime = intercept[SqlUnsupportedDialectFeatureException] {
            castSql(idColumn.cast[java.time.OffsetTime])
        }
        assert(offsetTime.feature == "CAST AS TimeWithOffset", s"expected the SQL type named, got: ${offsetTime.feature}")

        val array = intercept[SqlUnsupportedDialectFeatureException] {
            castSql(idColumn.cast[Chunk[Int]])
        }
        assert(array.feature == "CAST AS Array(Int)", s"expected the SQL type named, got: ${array.feature}")
    }

    // A target with no portable SQL type (Duration) is a COMPILE error at `.cast`, before any dialect is chosen:
    // it has no `given SqlType`. This is distinct from the render refusals above, whose targets DO have a SqlType
    // that MySQL simply cannot spell.
    "casting to a target with no SQL type at all is a compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.Sql.*
            val idColumn = Column["id", Long]("r", "id", "id")
            idColumn.cast[java.time.Duration]
            """
        )
        val message = errors.map(_.message).mkString(" ")
        assert(errors.nonEmpty, "casting to a type with no SqlType must not compile")
        assert(message.contains("SqlType"), s"the error should name the missing SqlType evidence, got: $message")
    }

end MysqlDialectCastTest
