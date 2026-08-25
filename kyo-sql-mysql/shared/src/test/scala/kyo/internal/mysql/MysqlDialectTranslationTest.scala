package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.SqlUnsupportedException
import kyo.Test
import kyo.db.Idiom

/** Verifies each render decision [[MysqlDialect]] makes where the two flavors disagree: case-insensitive matching, string
  * concatenation, full outer joins, conflict resolution on insert, and null placement in `ORDER BY`.
  *
  * These are the hooks the flavor-blind AST walk defers to, so they are the places where a wrong answer produces SQL that parses and means
  * something else.
  */
class MysqlDialectTranslationTest extends Test:

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

    // --- ILike / NotILike on MySQL ---

    // ilike on MySQL translates to LOWER(x) LIKE LOWER(?), one String bound param.
    "name.ilike renders on MySQL as 'LOWER(`name`) LIKE LOWER(?)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.ilike("ada%")
        val r       = t.render(MysqlDialect)
        assert(r.onlySql.get == "LOWER(`name`) LIKE LOWER(?)")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // notIlike on MySQL translates to LOWER(x) NOT LIKE LOWER(?), one String bound param.
    "name.notIlike renders on MySQL as 'LOWER(`name`) NOT LIKE LOWER(?)' with one String param" in {
        val nameCol = Column["name", String]("", "name", "name")
        val t       = nameCol.notIlike("ada%")
        val r       = t.render(MysqlDialect)
        assert(r.onlySql.get == "LOWER(`name`) NOT LIKE LOWER(?)")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "ada%")
    }

    // Static-path cross-check for ILike: compile-time render == runtime render byte-for-byte.
    "ILike compile-time render matches runtime render byte-for-byte" in {
        val rtm = Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")).render(MysqlDialect)
        val rsm = SqlStaticProbe.render(Sql.from[NameRow]("n").where(c => c.n.name.ilike("ada%")))
        assert(rsm.sqlFor(MysqlDialect.id).get == rtm.onlySql.get)
    }

    // --- Concat, emitted here as a flattened `CONCAT(…)` call ---

    // Two-column concat on MySQL, CONCAT function call, zero params.
    "first ++ last renders on MySQL as 'CONCAT(`first`, `last`)' with zero params" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ lastCol
        val r        = t.render(MysqlDialect)
        assert(r.onlySql.get == "CONCAT(`first`, `last`)")
        assert(r.params.isEmpty)
    }

    // Three-part concat on MySQL, flatConcatParts flattens nested Concat, CONCAT(...) with one String param.
    "first ++ \"-\" ++ last renders on MySQL as 'CONCAT(`first`, ?, `last`)' with one String param" in {
        val firstCol = Column["first", String]("", "first", "first")
        val lastCol  = Column["last", String]("", "last", "last")
        val t        = firstCol ++ "-" ++ lastCol
        val r        = t.render(MysqlDialect)
        assert(r.onlySql.get == "CONCAT(`first`, ?, `last`)")
        assert(r.params.size == 1)
        assert((r.params.head.value: Any) == "-")
    }

    // Static-path cross-check for Concat: compile-time render == runtime render byte-for-byte.
    "Concat compile-time render matches runtime render byte-for-byte" in {
        val rtm = Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last).render(MysqlDialect)
        val rsm = SqlStaticProbe.render(Sql.from[FullName]("f").select(c => c.f.first ++ c.f.last))
        assert(rsm.sqlFor(MysqlDialect.id).get == rtm.onlySql.get)
    }

    // --- FullOuter join, synthesised here from a UNION ---

    // fullOuterJoin on MySQL, LEFT JOIN UNION RIGHT JOIN synthesis wrapped as a derived table so the whole union is one FROM item.
    // Note: the UNION doubles the predicate, so MySQL bind count is 0 (no literal binds in this predicate).
    "fullOuterJoin renders on MySQL as LEFT JOIN UNION RIGHT JOIN synthesis" in {
        val q = Sql.from[TA]("a").fullOuterJoin(Sql.from[TB]("b")).on(j => j.a.id == j.b.id)
        val r = q.render(MysqlDialect)
        assert(
            r.onlySql.get == "SELECT * FROM (SELECT * FROM `ta` `a` LEFT JOIN `tb` `b` ON (`a`.`id` = `b`.`id`) UNION SELECT * FROM `ta` `a` RIGHT JOIN `tb` `b` ON (`a`.`id` = `b`.`id`)) `sub`"
        )
        // No literal binds in this predicate; both sides of the UNION have the same (zero) param count.
        assert(r.params.isEmpty)
    }

    // --- OnConflict DoNothing ---

    // The same insert on MySQL, INSERT IGNORE INTO, no ON CONFLICT clause.
    "onConflictDoNothing (zero targets) renders on MySQL as 'INSERT IGNORE INTO'" in {
        val s = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing()
        val r = s.render(MysqlDialect)
        assert(r.onlySql.get == "INSERT IGNORE INTO `user` (`id`, `name`) VALUES (?, ?)")
        assert(boundValues(r) == Seq(1L, "Alice"))
    }

    // Static-path cross-check for OnConflict DoNothing: compile-time render == runtime render byte-for-byte.
    "OnConflict DoNothing compile-time render matches runtime render byte-for-byte" in {
        val rtm = Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing().render(MysqlDialect)
        val rsm = SqlStaticProbe.render(Sql.insert[User].values(User(1L, "Alice")).onConflictDoNothing())
        assert(rsm.sqlFor(MysqlDialect.id).get == rtm.onlySql.get)
    }

    // --- OnConflict DoUpdate ---

    // The same upsert on MySQL, ON DUPLICATE KEY UPDATE ... VALUES(col).
    "onConflictDoUpdate renders on MySQL as 'ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)'" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        val r = s.render(MysqlDialect)
        assert(r.onlySql.get == "INSERT INTO `user` (`id`, `name`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)")
        assert(boundValues(r) == Seq(1L, "Alice"))
    }

    // Static-path cross-check for OnConflict DoUpdate: compile-time render == runtime render byte-for-byte.
    "OnConflict DoUpdate compile-time render matches runtime render byte-for-byte" in {
        val rtm = Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
            .render(MysqlDialect)
        val rsm = SqlStaticProbe.render(
            Sql.insert[User].values(User(1L, "Alice")).onConflictDoUpdate(_.name)(c => c.name := Excluded(c.name))
        )
        assert(rsm.sqlFor(MysqlDialect.id).get == rtm.onlySql.get)
    }

    // --- OnConflict DoUpdate WHERE ---

    // onConflictDoUpdate with WHERE on MySQL raises SqlUnsupportedException (not a silent drop).
    "ON DUPLICATE KEY UPDATE WHERE raises SqlUnsupportedException on MySQL" in {
        val s = Sql.insert[User].values(User(1L, "Alice"))
            .onConflictDoUpdate(_.name)
            .where(c => c.id > 0L)(c => c.name := Excluded(c.name))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            s.render(MysqlDialect)
        }
        assert(ex.feature == "ON CONFLICT ... WHERE", s"expected feature 'ON CONFLICT ... WHERE', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Absent,
            s"expected no requiredVersion, got: ${ex.requiredVersion}"
        )
        // A render naming no version carries no captured handshake version, so the failure reports Absent rather than
        // fabricating the capability floor as a server the caller never observed (the SqlException serverVersion contract,
        // pinned by IdiomTest "unsupported reports the handshake version only when the caller named one").
        assert(
            ex.serverVersion == Absent,
            s"expected no serverVersion, got: ${ex.serverVersion.map(_.show)}"
        )
    }

    // --- ORDER BY NULLS FIRST/LAST lowering ---

    /** Shared column for ORDER BY leaves. */
    case class Sortable(id: Long, score: Int)

    // DESC NULLS FIRST on MySQL, lowered to `IS NOT NULL, score DESC`.
    "ORDER BY score DESC NULLS FIRST lowers to IS NOT NULL, score DESC on MySQL" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.descAbsentFirst)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` IS NOT NULL, `s`.`score` DESC")
    }

    // ASC NULLS LAST on MySQL, lowered to `IS NULL, score ASC`.
    "ORDER BY score ASC NULLS LAST lowers to IS NULL, score ASC on MySQL" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.ascAbsentLast)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` IS NULL, `s`.`score` ASC")
    }

    // ASC NULLS FIRST on MySQL, unchanged (MySQL ASC default: NULLs first).
    "ORDER BY score ASC NULLS FIRST renders unchanged on MySQL (default)" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.ascAbsentFirst)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` ASC")
    }

    // DESC NULLS LAST on MySQL, unchanged (MySQL DESC default: NULLs last).
    "ORDER BY score DESC NULLS LAST renders unchanged on MySQL (default)" in {
        val q = Sql.from[Sortable]("s").orderBy(c => c.s.score.descAbsentLast)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == "SELECT `s`.`id`, `s`.`score` FROM `sortable` `s` ORDER BY `s`.`score` DESC")
    }

end MysqlDialectTranslationTest
