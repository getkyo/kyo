package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test
import kyo.db.Idiom

/** Verifies the SQL [[MysqlDialect]] renders for a `VALUES` constructor used as a query source.
  *
  * `ValuesFrom.rows` is pure data, one `Sql.BoundValue` per cell, and every cell binds: the text carries `?` and the value travels in the
  * param list. That is why these scenarios pin the whole statement together with the bind list: the property under test is that each cell
  * is in the params and its text is a placeholder, which a keyword check cannot see.
  *
  * Two things in every expected string here are MySQL's alone. Its table value constructor names each row with the `ROW` keyword and cannot
  * parse the bare parenthesised row PostgreSQL takes, and the columns it produces are named `column_0`, `column_1`, so the projection of
  * `` `v`.`x` `` resolves only against an alias column list. MySQL gained the construct in 8.0.19, which the gate leaves at the end pin.
  */
class MysqlDialectValuesRenderTest extends Test:

    // Sql.BoundValue's existential value field cannot satisfy CanEqual derivation, so bind values are compared
    // after widening to Any.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Point(x: Int, y: Int)
    case class Person(id: Long, name: String, age: Int, deptId: Long)

    // single-row VALUES

    "single-row Sql.values binds both cells behind MySQL placeholders" in {
        val r = Sql.values[Point]("v", Point(1, 2)).render(MysqlDialect)
        assert(r.onlySql.get == "SELECT `v`.`x`, `v`.`y` FROM (VALUES ROW(?, ?)) `v`(`x`, `y`)")
        assert(r.params.size == 2)
        assert(r.params.toSeq.map(_.value: Any) == Seq(1, 2))
    }

    // multi-row VALUES

    "multi-row Sql.values binds every cell across rows in row-then-column order" in {
        val r = Sql.values[Point]("v", Point(1, 2), Point(3, 4)).render(MysqlDialect)
        assert(r.onlySql.get == "SELECT `v`.`x`, `v`.`y` FROM (VALUES ROW(?, ?), ROW(?, ?)) `v`(`x`, `y`)")
        assert(r.params.size == 4)
        assert(r.params.toSeq.map(_.value: Any) == Seq(1, 2, 3, 4))
    }

    // VALUES used with a multi-column case class

    "Sql.values with a 4-column Person row renders all column names in the SELECT clause" in {
        val r = Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)).render(MysqlDialect)
        assert(
            r.onlySql.get ==
                "SELECT `pv`.`id`, `pv`.`name`, `pv`.`age`, `pv`.`deptId` FROM (VALUES ROW(?, ?, ?, ?)) `pv`(`id`, `name`, `age`, `deptId`)"
        )
        assert(r.params.size == 4)
        assert((r.params(1).value: Any) == "Alice")
    }

    // Under MySQL's default sql_mode a backslash escapes the following character, so quote-doubling alone would
    // leave `\''` open and everything after it as statement text. This guard belongs on the MySQL side because the
    // escape it prevents is MySQL's, and it pins that the backslash-quote pair stays out of the statement text.
    "a String cell containing a backslash and a quote stays out of the statement text" in {
        val payload = """x\'), (2, 0x70776e6564) -- """
        val r       = Sql.values[Person]("pv", Person(1L, payload, 30, 1L)).render(MysqlDialect)
        val sql     = r.onlySql.get
        assert(
            sql == "SELECT `pv`.`id`, `pv`.`name`, `pv`.`age`, `pv`.`deptId` FROM (VALUES ROW(?, ?, ?, ?)) `pv`(`id`, `name`, `age`, `deptId`)"
        )
        assert(!sql.contains("'"))
        assert(!sql.contains("\\"))
        assert(!sql.contains("0x70776e6564"))
        assert(!sql.contains("--"))
        assert((r.params(1).value: Any) == payload)
    }

    // --- Version gate ---
    //
    // MySQL had no table value constructor before 8.0.19, so a render against an older server has no syntax to
    // fall back to and fails typed instead of sending a statement the server cannot parse.

    "Sql.values on MySQL 8.0.19 renders the constructor" in {
        val version = Present(Idiom.ServerVersion(8, 0, 19))
        val r       = Sql.values[Point]("v", Point(1, 2)).render(MysqlDialect, version)
        assert(r.onlySql.get == "SELECT `v`.`x`, `v`.`y` FROM (VALUES ROW(?, ?)) `v`(`x`, `y`)")
    }

    "Sql.values on MySQL 8.0.18 raises SqlUnsupportedDialectFeatureException" in {
        val version = Present(Idiom.ServerVersion(8, 0, 18))
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            Sql.values[Point]("v", Point(1, 2)).render(MysqlDialect, version)
        }
        assert(ex.feature == "VALUES source", s"expected feature 'VALUES source', got: ${ex.feature}")
        assert(ex.dialect == Idiom.Id("mysql"), s"expected dialect 'mysql', got: ${ex.dialect.value}")
        assert(
            ex.requiredVersion == Present(Idiom.ServerVersion(8, 0, 19)),
            s"expected requiredVersion '8.0.19', got: ${ex.requiredVersion.map(_.show)}"
        )
        assert(
            ex.serverVersion == Present(Idiom.ServerVersion(8, 0, 18)),
            s"expected serverVersion '8.0.18', got: ${ex.serverVersion.map(_.show)}"
        )
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "multi-row Sql.values, MySQL SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.values[Point]("v", Point(1, 2), Point(3, 4)).render(MysqlDialect)
        val rs = SqlStaticProbe.render(Sql.values[Point]("v", Point(1, 2), Point(3, 4)))
        assert(rs.sqlFor(MysqlDialect.id).get == rt.onlySql.get)
    }

end MysqlDialectValuesRenderTest
