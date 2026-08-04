package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.SqlUnsupportedException
import kyo.Test

/** Verifies each render decision [[PostgresDialect]] makes where the two flavors disagree: case-insensitive matching, string
  * concatenation, full outer joins, conflict resolution on insert, and null placement in `ORDER BY`.
  *
  * These are the hooks the flavor-blind AST walk defers to, so they are the places where a wrong answer produces SQL that parses and means
  * something else.
  */
class PostgresDialectTranslationTest extends Test:

    // --- Case classes used across leaves ---

    /** Single-name table, used for the ILike leaves. */
    case class NameRow(id: Long, name: String)

    /** First/last name table, used for the Concat leaves. */
    case class FullName(id: Long, first: String, last: String)

    /** Simple two-field tables for the FullOuter join leaves. */
    case class TA(id: Long, value: String)
    case class TB(id: Long, label: String)

    /** User table, used for the OnConflict leaves. */
    case class User(id: Long, name: String)

    // --- CanEqual widening, needed to compare Sql.BoundValue existentials ---
    given CanEqual[Any, Any] = CanEqual.derived

    /** The values a render collected, widened to `Any` so an existential `Sql.BoundValue` list can be compared. */
    private def boundValues(r: Sql.Rendered): Seq[Any] = r.params.toSeq.map(_.value)

    // --- ILike / NotILike on Postgres ---

    // ilike on Postgres uses ILIKE keyword, one String bound param.
    "name.ilike renders on PG as '(\"name\" ILIKE $1)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.ilike("ada%")
        val r       = t.render(PostgresDialect)
        assert(r.onlySql.get == """("name" ILIKE $1)""")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // notIlike on Postgres uses NOT ILIKE keyword, one String bound param.
    "name.notIlike renders on PG as '(\"name\" NOT ILIKE $1)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.notIlike("ada%")
        val r       = t.render(PostgresDialect)
        assert(r.onlySql.get == """("name" NOT ILIKE $1)""")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // Static-path cross-check for ILike: compile-time render == runtime render byte-for-byte.
    "ILike compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    // --- Concat, emitted here as the `||` operator with parens ---

    // Two-column concat on Postgres, wrapped in parens with || operator, zero params.
    "first ++ last renders on PG as '(\"first\" || \"last\")' with zero params" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ lastCol
        val r        = t.render(PostgresDialect)
        assert(r.onlySql.get == """("first" || "last")""")
        assert(r.params.isEmpty)
    }

    // Three-part concat on Postgres, nested Concat nodes produce nested || with parens, one String param.
    "first ++ \"-\" ++ last renders on PG as '((\"first\" || $1) || \"last\")' with one String param" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ "-" ++ lastCol
        val r        = t.render(PostgresDialect)
        assert(r.onlySql.get == """(("first" || $1) || "last")""")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "-")
    }

    // Static-path cross-check for Concat: compile-time render == runtime render byte-for-byte.
    "Concat compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    // --- FullOuter join, emitted here as the native keyword ---

    // fullOuterJoin on Postgres, FULL OUTER JOIN keyword.
    "fullOuterJoin renders on PG as FULL OUTER JOIN" in {
        val q = Sql.from[TA]("a").fullOuterJoin(Sql.from[TB]("b")).on(j => j.a.id == j.b.id)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT * FROM "ta" "a" FULL OUTER JOIN "tb" "b" ON ("a"."id" = "b"."id")""")
        assert(r.params.isEmpty)
    }

    // --- OnConflict DoNothing ---

    // onConflictDoNothing (zero targets) on Postgres, ON CONFLICT DO NOTHING.
    "onConflictDoNothing (zero targets) renders on PG as 'ON CONFLICT DO NOTHING'" in {
        val s = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing()
        val r = s.render(PostgresDialect)
        assert(r.onlySql.get == """INSERT INTO "user" ("id", "name") VALUES ($1, $2) ON CONFLICT DO NOTHING RETURNING "id"""")
        assert(boundValues(r) == Seq(1L, "Alice"))
    }

    // Static-path cross-check for OnConflict DoNothing: compile-time render == runtime render byte-for-byte.
    "OnConflict DoNothing compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing().render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing())
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    // --- OnConflict DoUpdate ---

    // onConflictDoUpdate on Postgres, ON CONFLICT (target) DO UPDATE SET ... EXCLUDED.col.
    "onConflictDoUpdate renders on PG as 'ON CONFLICT (\"name\") DO UPDATE SET ... excluded.\"name\"'" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        val r = s.render(PostgresDialect)
        assert(
            r.onlySql.get == """INSERT INTO "user" ("id", "name") VALUES ($1, $2) ON CONFLICT ("name") DO UPDATE SET "name" = EXCLUDED."name" RETURNING "id""""
        )
        assert(boundValues(r) == Seq(1L, "Alice"))
    }

    // Static-path cross-check for OnConflict DoUpdate: compile-time render == runtime render byte-for-byte.
    "OnConflict DoUpdate compile-time render matches runtime render byte-for-byte" in {
        val rt = Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
            .render(PostgresDialect)
        val rs = SqlStaticProbe.render(
            Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        )
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    // --- OnConflict DoUpdate WHERE ---

    // onConflictDoUpdate with WHERE on Postgres emits the WHERE predicate rather than silently dropping it.
    // The WHERE is set on the builder before calling apply(sets).
    // 0L is bound as a positional parameter, so we assert structural presence of WHERE rather than a
    // literal-inline value match.
    "ON CONFLICT DO UPDATE WHERE still emits predicate on PG" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)
            .where(c => c.id > 0L)(c => c.name := Excluded(c.name))
        val r = s.render(PostgresDialect)
        assert(r.onlySql.get.contains("WHERE"))
        assert(r.onlySql.get.contains("""ON CONFLICT ("name") DO UPDATE SET "name" = EXCLUDED."name" WHERE"""))
        assert(!r.onlySql.get.contains("ON DUPLICATE KEY UPDATE"))
    }

    // --- ORDER BY NULLS FIRST/LAST, emitted here verbatim ---

    /** Shared column for ORDER BY leaves. */
    case class Sortable(id: Long, score: Int)

    // DESC NULLS FIRST on Postgres, verbatim NULLS FIRST, no lowering.
    "ORDER BY score DESC NULLS FIRST renders verbatim on PG" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.descAbsentFirst)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "s"."id", "s"."score" FROM "sortable" "s" ORDER BY "s"."score" DESC NULLS FIRST""")
    }

end PostgresDialectTranslationTest
