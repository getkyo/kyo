package kyo

import kyo.SqlAst.*

/** Backend-specific renderer translation tests.
  *
  * 14 leaves verifying the 6 backend forks in [[kyo.internal.SqlRender]]: ILike / NotILike; Concat (PG `||`, MySQL `CONCAT(…)`); FullOuter
  * (PG native, MySQL LEFT/RIGHT UNION synthesis); OnConflict DoNothing (PG `ON CONFLICT DO NOTHING`, MySQL `INSERT IGNORE INTO`);
  * OnConflict DoUpdate (PG `ON CONFLICT … DO UPDATE`, MySQL `ON DUPLICATE KEY UPDATE`).
  *
  * Each leaf asserts byte-equality of the rendered SQL. Static-path cross-checks (SqlStatic.staticSql) are included for ILike, Concat, and
  * OnConflict leaves where the query shape is liftable.
  */
class SqlRenderTest extends Test:

    // --- Case classes used across leaves ---

    /** Single-name table, used for ILike leaves (1-4). */
    case class NameRow(id: Long, name: String) derives Schema

    /** First/last name table, used for Concat leaves (5-8). */
    case class FullName(id: Long, first: String, last: String) derives Schema

    /** Simple two-field tables for FullOuter join leaves (9-10). */
    case class TA(id: Long, value: String) derives Schema
    case class TB(id: Long, label: String) derives Schema

    /** User table, used for OnConflict leaves (11-14). */
    case class User(id: Long, name: String) derives Schema

    // --- CanEqual widening, needed to compare SqlSchema.BoundValue existentials ---
    given CanEqual[Any, Any] = CanEqual.derived

    // --- Leaves 1-4, ILike / NotILike ---

    // Leaf 1: ilike on Postgres, uses ILIKE keyword, one String bound param.
    "name.ilike renders on PG as '(\"name\" ILIKE $1)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.ilike("ada%")
        val r       = t.renderPostgres
        assert(r.sql == """("name" ILIKE $1)""")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // Leaf 2: ilike on MySQL, translates to LOWER(x) LIKE LOWER(?), one String bound param.
    "name.ilike renders on MySQL as 'LOWER(`name`) LIKE LOWER(?)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.ilike("ada%")
        val r       = t.renderMysql
        assert(r.sql == "LOWER(`name`) LIKE LOWER(?)")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // Leaf 3: notIlike on Postgres, uses NOT ILIKE keyword, one String bound param.
    "name.notIlike renders on PG as '(\"name\" NOT ILIKE $1)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.notIlike("ada%")
        val r       = t.renderPostgres
        assert(r.sql == """("name" NOT ILIKE $1)""")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // Leaf 4: notIlike on MySQL, translates to LOWER(x) NOT LIKE LOWER(?), one String bound param.
    "name.notIlike renders on MySQL as 'LOWER(`name`) NOT LIKE LOWER(?)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.notIlike("ada%")
        val r       = t.renderMysql
        assert(r.sql == "LOWER(`name`) NOT LIKE LOWER(?)")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // Static-path cross-check for ILike: static macro == runtime renderer byte-for-byte.
    "ILike staticSql matches SqlRender.render byte-for-byte" in {
        val rt  = Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")).renderPostgres
        val rs  = SqlStatic.staticSql(Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")))
        val rtm = Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")).renderMysql
        val rsm = SqlStatic.staticSql(Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")))
        assert(rs.sql.postgres == rt.sql)
        assert(rsm.sql.mysql == rtm.sql)
    }

    // --- Leaves 5-8, Concat (PG `||` with parens, MySQL `CONCAT(…)` flattened) ---

    // Leaf 5: two-column concat on Postgres, wrapped in parens with || operator, zero params.
    "first ++ last renders on PG as '(\"first\" || \"last\")' with zero params" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ lastCol
        val r        = t.renderPostgres
        assert(r.sql == """("first" || "last")""")
        assert(r.params.isEmpty)
    }

    // Leaf 6: two-column concat on MySQL, CONCAT function call, zero params.
    "first ++ last renders on MySQL as 'CONCAT(`first`, `last`)' with zero params" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ lastCol
        val r        = t.renderMysql
        assert(r.sql == "CONCAT(`first`, `last`)")
        assert(r.params.isEmpty)
    }

    // Leaf 7: three-part concat on Postgres, nested Concat nodes produce nested || with parens, one String param.
    "first ++ \"-\" ++ last renders on PG as '((\"first\" || $1) || \"last\")' with one String param" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ "-" ++ lastCol
        val r        = t.renderPostgres
        assert(r.sql == """(("first" || $1) || "last")""")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "-")
    }

    // Leaf 8: three-part concat on MySQL, flatConcatParts flattens nested Concat, CONCAT(...) with one String param.
    "first ++ \"-\" ++ last renders on MySQL as 'CONCAT(`first`, ?, `last`)' with one String param" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ "-" ++ lastCol
        val r        = t.renderMysql
        assert(r.sql == "CONCAT(`first`, ?, `last`)")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "-")
    }

    // Static-path cross-check for Concat: staticSql renders byte-identical SQL to the runtime renderer.
    "Concat staticSql matches SqlRender.render byte-for-byte" in {
        val rt  = Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last).renderPostgres
        val rs  = SqlStatic.staticSql(Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last))
        val rtm = Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last).renderMysql
        val rsm = SqlStatic.staticSql(Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last))
        assert(rs.sql.postgres == rt.sql)
        assert(rsm.sql.mysql == rtm.sql)
    }

    // --- Leaves 9-10, FullOuter join (PG native, MySQL UNION synthesis) ---

    // Leaf 9: fullOuterJoin on Postgres, FULL OUTER JOIN keyword.
    "fullOuterJoin renders on PG as FULL OUTER JOIN" in {
        val q = Sql.from[TA]("a").fullOuterJoin(Sql.from[TB]("b")).on(j => j.a.id == j.b.id)
        val r = q.renderPostgres
        assert(r.sql == """SELECT * FROM "ta" "a" FULL OUTER JOIN "tb" "b" ON ("a"."id" = "b"."id")""")
        assert(r.params.isEmpty)
    }

    // Leaf 10: fullOuterJoin on MySQL, LEFT JOIN UNION SELECT * FROM ... RIGHT JOIN synthesis.
    // Note: the UNION doubles the predicate, so MySQL bind count is 0 (no literal binds in this predicate).
    "fullOuterJoin renders on MySQL as LEFT JOIN UNION RIGHT JOIN synthesis" in {
        val q = Sql.from[TA]("a").fullOuterJoin(Sql.from[TB]("b")).on(j => j.a.id == j.b.id)
        val r = q.renderMysql
        assert(
            r.sql == "SELECT * FROM `ta` `a` LEFT JOIN `tb` `b` ON (`a`.`id` = `b`.`id`) UNION SELECT * FROM `ta` `a` RIGHT JOIN `tb` `b` ON (`a`.`id` = `b`.`id`)"
        )
        // No literal binds in this predicate; both sides of the UNION have the same (zero) param count.
        assert(r.params.isEmpty)
    }

    // --- Leaves 11-12, OnConflict DoNothing ---

    // Leaf 11: onConflictDoNothing (zero targets) on Postgres, ON CONFLICT DO NOTHING.
    "onConflictDoNothing (zero targets) renders on PG as 'ON CONFLICT DO NOTHING'" in {
        val s = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing()
        val r = s.renderPostgres
        assert(r.sql == """INSERT INTO "user" ("id", "name") VALUES (1, 'Alice') ON CONFLICT DO NOTHING RETURNING "id"""")
        assert(r.params.isEmpty)
    }

    // Leaf 12: same insert on MySQL, INSERT IGNORE INTO, no ON CONFLICT clause.
    "onConflictDoNothing (zero targets) renders on MySQL as 'INSERT IGNORE INTO'" in {
        val s = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing()
        val r = s.renderMysql
        assert(r.sql == "INSERT IGNORE INTO `user` (`id`, `name`) VALUES (1, 'Alice')")
        assert(r.params.isEmpty)
    }

    // Static-path cross-check for OnConflict DoNothing: static macro == runtime renderer byte-for-byte.
    "OnConflict DoNothing staticSql matches SqlRender.render byte-for-byte" in {
        val rt  = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing().renderPostgres
        val rs  = SqlStatic.staticSql(Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing())
        val rtm = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing().renderMysql
        val rsm = SqlStatic.staticSql(Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing())
        assert(rs.sql.postgres == rt.sql)
        assert(rsm.sql.mysql == rtm.sql)
    }

    // --- Leaves 13-14, OnConflict DoUpdate ---

    // Leaf 13: onConflictDoUpdate on Postgres, ON CONFLICT (target) DO UPDATE SET ... EXCLUDED.col.
    "onConflictDoUpdate renders on PG as 'ON CONFLICT (\"name\") DO UPDATE SET ... excluded.\"name\"'" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        val r = s.renderPostgres
        assert(
            r.sql == """INSERT INTO "user" ("id", "name") VALUES (1, 'Alice') ON CONFLICT ("name") DO UPDATE SET "name" = EXCLUDED."name" RETURNING "id""""
        )
        assert(r.params.isEmpty)
    }

    // Leaf 14: same upsert on MySQL, ON DUPLICATE KEY UPDATE ... VALUES(col).
    "onConflictDoUpdate renders on MySQL as 'ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)'" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        val r = s.renderMysql
        assert(r.sql == "INSERT INTO `user` (`id`, `name`) VALUES (1, 'Alice') ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)")
        assert(r.params.isEmpty)
    }

    // Static-path cross-check for OnConflict DoUpdate: static macro == runtime renderer byte-for-byte.
    "OnConflict DoUpdate staticSql matches SqlRender.render byte-for-byte" in {
        val rt = Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
            .renderPostgres
        val rs = SqlStatic.staticSql(
            Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        )
        val rtm = Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
            .renderMysql
        val rsm = SqlStatic.staticSql(
            Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        )
        assert(rs.sql.postgres == rt.sql)
        assert(rsm.sql.mysql == rtm.sql)
    }

    // --- Leaves 15-16, OnConflict DoUpdate WHERE (G-Parity-6) ---

    // Leaf 15: onConflictDoUpdate with WHERE on Postgres, WHERE predicate is emitted (regression).
    // The WHERE is set on the builder before calling apply(sets).
    // 0L is bound as a positional parameter, so we assert structural presence of WHERE rather than a
    // literal-inline value match.
    "ON CONFLICT DO UPDATE WHERE still emits predicate on PG (G-Parity-6 regression)" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)
            .where(c => c.id > 0L)(c => c.name := Excluded(c.name))
        val r = s.renderPostgres
        assert(r.sql.contains("WHERE"))
        assert(r.sql.contains("""ON CONFLICT ("name") DO UPDATE SET "name" = EXCLUDED."name" WHERE"""))
        assert(!r.sql.contains("ON DUPLICATE KEY UPDATE"))
    }

    // Leaf 16: onConflictDoUpdate with WHERE on MySQL, raises SqlException.Unsupported (not a silent drop).
    "ON DUPLICATE KEY UPDATE WHERE raises SqlException.Unsupported on MySQL (G-Parity-6)" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)
            .where(c => c.id > 0L)(c => c.name := Excluded(c.name))
        val ex = intercept[SqlException.Unsupported] {
            s.renderMysql
        }
        assert(ex.getMessage.contains("MySQL does not support a WHERE clause on ON DUPLICATE KEY UPDATE"))
        assert(ex.getMessage.contains("ON CONFLICT DO UPDATE WHERE on PG"))
    }

    // --- Leaves 17-21, ORDER BY NULLS FIRST/LAST lowering (G-Parity-2) ---

    /** Shared column for ORDER BY leaves. */
    case class Sortable(id: Long, score: Int) derives Schema

    // Leaf 17: DESC NULLS FIRST on Postgres, verbatim NULLS FIRST, no lowering.
    "ORDER BY score DESC NULLS FIRST renders verbatim on PG" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.descNullsFirst)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "s"."id", "s"."score" FROM "sortable" "s" ORDER BY "s"."score" DESC NULLS FIRST""")
    }

    // Leaf 18: DESC NULLS FIRST on MySQL, lowered to `IS NOT NULL, score DESC`.
    "ORDER BY score DESC NULLS FIRST lowers to IS NOT NULL, score DESC on MySQL" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.descNullsFirst)
        val r = q.renderMysql
        assert(r.sql == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` IS NOT NULL, `s`.`score` DESC")
    }

    // Leaf 19: ASC NULLS LAST on MySQL, lowered to `IS NULL, score ASC`.
    "ORDER BY score ASC NULLS LAST lowers to IS NULL, score ASC on MySQL" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.ascNullsLast)
        val r = q.renderMysql
        assert(r.sql == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` IS NULL, `s`.`score` ASC")
    }

    // Leaf 20: ASC NULLS FIRST on MySQL, unchanged (MySQL ASC default: NULLs first).
    "ORDER BY score ASC NULLS FIRST renders unchanged on MySQL (default)" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.ascNullsFirst)
        val r = q.renderMysql
        assert(r.sql == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` ASC")
    }

    // Leaf 21: DESC NULLS LAST on MySQL, unchanged (MySQL DESC default: NULLs last).
    "ORDER BY score DESC NULLS LAST renders unchanged on MySQL (default)" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.descNullsLast)
        val r = q.renderMysql
        assert(r.sql == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` DESC")
    }

    "two structurally-equal SqlRow values compare ==" in {
        import kyo.internal.postgres.FieldDescription
        import kyo.internal.postgres.types.Format
        val fd = FieldDescription("id", 0, 0.toShort, 23, 4.toShort, -1, 0.toShort)

        // 3a: NULL column (Absent), two rows are equal
        val nullRow1 = SqlRow(Chunk(Maybe.Absent), Chunk(fd), Format.Text)
        val nullRow2 = SqlRow(Chunk(Maybe.Absent), Chunk(fd), Format.Text)
        assert(nullRow1 == nullRow2)

        // 3b: byte-identical spans in DISTINCT Span instances, must compare == (content equality)
        val bytes1   = Span.from(Array[Byte](1, 2, 3))
        val bytes2   = Span.from(Array[Byte](1, 2, 3)) // distinct instance, same content
        val presRow1 = SqlRow(Chunk(Maybe.Present(bytes1)), Chunk(fd), Format.Text)
        val presRow2 = SqlRow(Chunk(Maybe.Present(bytes2)), Chunk(fd), Format.Text)
        assert(presRow1 == presRow2)

        // 3c: byte-differing spans, must compare !=
        val bytesX  = Span.from(Array[Byte](1, 2, 4))
        val diffRow = SqlRow(Chunk(Maybe.Present(bytesX)), Chunk(fd), Format.Text)
        assert(presRow1 != diffRow)
    }

end SqlRenderTest
