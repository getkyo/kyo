package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test
import kyo.db.Idiom
import scala.compiletime.testing.typeCheckErrors

/** Verifies the SQL type name [[PostgresDialect]] emits inside `CAST(… AS …)` for each cast target.
  *
  * The spelling is a per-flavor decision made at render time rather than at `.cast[B]`, which is why it is pinned here and not beside the
  * DSL: the same `.cast[LocalDateTime]` is `TIMESTAMP` here and `DATETIME` on MySQL. The suite also pins the two ways a cast fails, because
  * a cast that cannot be spelled correctly has to fail rather than emit a type name the caller did not ask for.
  */
class PostgresDialectCastTest extends Test:

    case class Row(id: Long, name: String, amount: BigDecimal)

    private val idColumn = Column["id", Long]("r", "id", "id")

    private def castSql[B](target: Term[B]): String =
        Sql.from[Row]("r").select(_ => target).render(PostgresDialect).onlySql.get

    "casting to a text or numeric target uses the Postgres type names" in {
        assert(castSql(idColumn.cast[String]).contains("""CAST("r"."id" AS TEXT)"""))
        assert(castSql(idColumn.cast[Int]).contains("""CAST("r"."id" AS INTEGER)"""))
        assert(castSql(idColumn.cast[Long]).contains("""CAST("r"."id" AS BIGINT)"""))
        assert(castSql(idColumn.cast[Short]).contains("""CAST("r"."id" AS SMALLINT)"""))
        assert(castSql(idColumn.cast[Boolean]).contains("""CAST("r"."id" AS BOOLEAN)"""))
        assert(castSql(idColumn.cast[Float]).contains("""CAST("r"."id" AS REAL)"""))
        assert(castSql(idColumn.cast[Double]).contains("""CAST("r"."id" AS DOUBLE PRECISION)"""))
        assert(castSql(idColumn.cast[BigDecimal]).contains("""CAST("r"."id" AS NUMERIC)"""))
    }

    // Postgres has no one-byte integer type, so TINYINT, the name a flavor-blind Byte cast would reach for, is SQL
    // this server rejects outright.
    "casting to Byte uses SMALLINT, the narrowest integer Postgres has" in {
        assert(castSql(idColumn.cast[Byte]).contains("""CAST("r"."id" AS SMALLINT)"""))
    }

    // Each target below is a guard on one specific type name rather than on the CAST syntax: falling back to TEXT for
    // any of them renders a cast the server accepts and answers wrongly.
    "casting to a UUID uses the native UUID type" in {
        assert(castSql(idColumn.cast[java.util.UUID]).contains("""CAST("r"."id" AS UUID)"""))
    }

    "casting to each temporal uses its own Postgres type name" in {
        assert(castSql(idColumn.cast[java.time.LocalDate]).contains("""CAST("r"."id" AS DATE)"""))
        assert(castSql(idColumn.cast[java.time.LocalTime]).contains("""CAST("r"."id" AS TIME)"""))
        assert(castSql(idColumn.cast[java.time.OffsetTime]).contains("""CAST("r"."id" AS TIME WITH TIME ZONE)"""))
        assert(castSql(idColumn.cast[java.time.LocalDateTime]).contains("""CAST("r"."id" AS TIMESTAMP)"""))
        assert(castSql(idColumn.cast[Instant]).contains("""CAST("r"."id" AS TIMESTAMPTZ)"""))
        assert(castSql(idColumn.cast[java.time.Period]).contains("""CAST("r"."id" AS INTERVAL)"""))
    }

    "casting to the remaining native types uses their own names" in {
        assert(castSql(idColumn.cast[Span[Byte]]).contains("""CAST("r"."id" AS BYTEA)"""))
        assert(castSql(idColumn.cast[JsonText]).contains("""CAST("r"."id" AS JSONB)"""))
    }

    // An array target nests its element's spelling, the one recursive case in the table.
    "casting to an array target suffixes the element type" in {
        assert(castSql(idColumn.cast[Chunk[Int]]).contains("""CAST("r"."id" AS INTEGER[])"""))
        assert(castSql(idColumn.cast[Chunk[String]]).contains("""CAST("r"."id" AS TEXT[])"""))
    }

    // The remaining built-in cast targets, none pinned above: each maps to a Type already covered by another
    // target's spelling (URI/Locale/Currency reuse TEXT, OffsetDateTime/ZonedDateTime reuse Instant's TIMESTAMPTZ,
    // BigInt reuses BigDecimal's NUMERIC, Chunk[JsonText] is JSONB nested in the array suffix). Pinned so a
    // dropped or mis-mapped `SqlType` given surfaces here rather than silently.
    "the remaining built-in targets reuse their type's spelling" in {
        assert(castSql(idColumn.cast[java.net.URI]).contains("""CAST("r"."id" AS TEXT)"""))
        assert(castSql(idColumn.cast[java.util.Locale]).contains("""CAST("r"."id" AS TEXT)"""))
        assert(castSql(idColumn.cast[java.util.Currency]).contains("""CAST("r"."id" AS TEXT)"""))
        assert(castSql(idColumn.cast[java.time.OffsetDateTime]).contains("""CAST("r"."id" AS TIMESTAMPTZ)"""))
        assert(castSql(idColumn.cast[java.time.ZonedDateTime]).contains("""CAST("r"."id" AS TIMESTAMPTZ)"""))
        assert(castSql(idColumn.cast[BigInt]).contains("""CAST("r"."id" AS NUMERIC)"""))
        assert(castSql(idColumn.cast[Chunk[JsonText]]).contains("""CAST("r"."id" AS JSONB[])"""))
    }

    // A user or factory declaring a column type of their own (e.g. a Postgres extension) attaches a SqlType
    // through SqlType.of; the cast then names it. That is the escape hatch that keeps custom-type casts possible,
    // since `.cast` reads a SqlType rather than the column codec.
    "a user-declared SqlType for an extension type renders its name" in {
        class Geo
        given SqlSchema.Column[Geo] = PostgresTypes.custom[Geo]((_, w) => w.string("POINT(0 0)"))(_ => new Geo)
        given SqlType[Geo]          = SqlType.of(SqlType.Type.Extension("geometry"))
        assert(castSql(idColumn.cast[Geo]).contains("""CAST("r"."id" AS geometry)"""))
    }

    // A target with no portable SQL type is a COMPILE error at `.cast`, not a render-time refusal: there is no
    // `given SqlType[Duration]` (Duration's fixed count of seconds is a type neither backend casts to), so the cast
    // never type-checks and can never emit `CAST(x AS TEXT)`, which the server would accept and answer wrongly.
    "casting to a target with no SQL type is a compile error" in {
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
        assert(message.contains("Duration"), s"the error should name the offending target, got: $message")
    }

    "casting to a multi-column target is a compile error" in {
        val errors = typeCheckErrors(
            """
            import kyo.*
            import kyo.Sql.*
            case class Row(id: Long, name: String, amount: BigDecimal)
            val idColumn = Column["id", Long]("r", "id", "id")
            idColumn.cast[Row]
            """
        )
        val message = errors.map(_.message).mkString(" ")
        assert(errors.nonEmpty, "casting to a multi-column case class must not compile")
        assert(message.contains("SqlType"), s"the error should name the missing SqlType evidence, got: $message")
    }

end PostgresDialectCastTest
