package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[PostgresDialect]] renders for a `VALUES` constructor used as a query source.
  *
  * `ValuesFrom.rows` is pure data, one `Sql.BoundValue` per cell, and every cell binds: the text carries `$N` and the value travels in the
  * param list. That is why these scenarios pin the whole statement together with the bind list: the property under test is that each cell
  * is in the params and its text is a placeholder, which a keyword check cannot see.
  *
  * The alias column list is the other half of every expected string here, and it is what makes these statements executable. PostgreSQL names
  * the columns of a `VALUES` list `column1`, `column2`, and so on, so a projection of `"v"."x"` resolves only against an alias that renames
  * them. `SqlEndToEndTest` runs such a query against a live server; these leaves pin the text it sends.
  */
class PostgresDialectValuesRenderTest extends Test:

    // Sql.BoundValue's existential value field cannot satisfy CanEqual derivation, so bind values are compared
    // after widening to Any.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Point(x: Int, y: Int)
    case class Person(id: Long, name: String, age: Int, deptId: Long)

    // single-row VALUES

    "single-row Sql.values binds both cells behind PG placeholders" in {
        val r = Sql.values[Point]("v", Point(1, 2)).render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "v"."x", "v"."y" FROM (VALUES ($1, $2)) "v"("x", "y")""")
        assert(r.params.size == 2)
        assert((r.params(0).value: Any) == 1)
        assert((r.params(1).value: Any) == 2)
    }

    // multi-row VALUES

    "multi-row Sql.values numbers placeholders across rows in row-then-column order" in {
        val r = Sql.values[Point]("v", Point(1, 2), Point(3, 4)).render(PostgresDialect)
        // Row order is observable, and the rows are comma-separated inside one parenthesised VALUES.
        assert(r.onlySql.get == """SELECT "v"."x", "v"."y" FROM (VALUES ($1, $2), ($3, $4)) "v"("x", "y")""")
        assert(r.params.size == 4)
        assert(r.params.toSeq.map(_.value: Any) == Seq(1, 2, 3, 4))
    }

    // VALUES used with a multi-column case class

    "Sql.values with a 4-column Person row renders all column names in the SELECT clause" in {
        val r = Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)).render(PostgresDialect)
        assert(
            r.onlySql.get ==
                """SELECT "pv"."id", "pv"."name", "pv"."age", "pv"."deptId" FROM (VALUES ($1, $2, $3, $4)) "pv"("id", "name", "age", "deptId")"""
        )
        assert(r.params.size == 4)
        assert((r.params(1).value: Any) == "Alice")
    }

    // The String cell binds as a parameter rather than being interpolated into the text with quote-doubling as its
    // only transformation. PostgreSQL's defaults would make the doubling sufficient here, which is why the escape
    // only shows up on MySQL; binding is what removes the question on both.
    "a String cell carrying a quote and a backslash stays out of the statement text" in {
        val payload = """o'brien\"""
        val r       = Sql.values[Person]("pv", Person(1L, payload, 30, 1L)).render(PostgresDialect)
        val sql     = r.onlySql.get
        assert(
            sql ==
                """SELECT "pv"."id", "pv"."name", "pv"."age", "pv"."deptId" FROM (VALUES ($1, $2, $3, $4)) "pv"("id", "name", "age", "deptId")"""
        )
        assert(!sql.contains("'"))
        assert(!sql.contains("\\"))
        assert((r.params(1).value: Any) == payload)
    }

    // --- Compile-time vs runtime render parity leaves ---
    //
    // STATIC-SQL-INLINE-ONLY: `SqlStaticProbe.render` requires a fully-inline expression (`q.value` cannot
    // reduce through a `val` reference), so the same query expression is duplicated between the runtime and
    // probe calls. Keep both copies identical when editing.

    "single-row Sql.values, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.values[Point]("v", Point(1, 2)).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.values[Point]("v", Point(1, 2)))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

    "Sql.values 4-column Person, PG SQL matches the runtime render byte-for-byte" in {
        val rt = Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)).render(PostgresDialect)
        val rs = SqlStaticProbe.render(Sql.values[Person]("pv", Person(0L, "Alice", 30, 1L)))
        assert(rs.sqlFor(PostgresDialect.id).get == rt.onlySql.get)
    }

end PostgresDialectValuesRenderTest
