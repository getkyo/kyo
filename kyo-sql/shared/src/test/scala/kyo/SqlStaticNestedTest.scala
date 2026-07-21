package kyo

import kyo.SqlAst.*

/** Static-SQL coverage for [[SqlAst.Nested]] (G-Probe-7).
  *
  * Verifies that `Nested` nodes lift through [[SqlStatic.staticSql]] at compile time. Each leaf asserts the compiled SQL strings for both
  * backends. A compile-time failure (rather than a runtime assertion) would indicate a missing [[scala.quoted.FromExpr]] derivation for
  * `Nested` or its fields.
  *
  * Note: `staticSql` requires the query to be written inline, storing it in a `val` introduces a local binding that `q.value` cannot
  * reduce through. All byte-for-byte runtime parity checks duplicate the query expression. STATIC-SQL-INLINE-ONLY: keep both copies of the
  * duplicated query expression identical when editing any leaf below, drift between them would silently weaken the parity check.
  */
class SqlStaticNestedTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class Department(id: Long, name: String) derives Schema
    case class Order(id: Long, userId: Long) derives Schema

    // ── Leaf 1, SELECT FROM (simple subquery) ────────────────────────────────

    "simple Nested subquery lifts and emits subquery parens" in {
        val r = SqlStatic.staticSql(
            Sql.nested[Person]("sub", Sql.from[Person]("p"))
        )
        // Nested renders as: SELECT <cols> FROM (<inner>) "alias"
        assert(r.sql.postgres.contains("("))
        assert(r.sql.postgres.contains(")"))
        assert(r.sql.mysql.contains("("))
        assert(r.sql.mysql.contains(")"))
        assert(r.params.isEmpty)
    }

    "simple Nested, PG SELECT enumerates Person columns under alias sub" in {
        val r = SqlStatic.staticSql(
            Sql.nested[Person]("sub", Sql.from[Person]("p"))
        )
        assert(r.sql.postgres.contains(""""sub"."id""""))
        assert(r.sql.postgres.contains(""""sub"."name""""))
        assert(r.sql.postgres.contains(""""sub"."age""""))
        assert(r.sql.postgres.contains(""""sub"."deptId""""))
    }

    "simple Nested, PG SQL matches SqlRender.render byte-for-byte" in {
        val r  = SqlStatic.staticSql(Sql.nested[Person]("sub", Sql.from[Person]("p")))
        val rt = Sql.nested[Person]("sub", Sql.from[Person]("p")).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // ── Leaf 2, Nested wrapping a WHERE subquery with bind param ─────────────

    "Nested with inner WHERE lifts correctly and threads bind param" in {
        val r = SqlStatic.staticSql(
            Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18))
        )
        assert(r.sql.postgres.contains("$1"))
        assert(r.sql.mysql.contains("?"))
        assert(r.params.size == 1)
        val bv: SqlSchema.BoundValue[?] = r.params.head
        // Narrow CanEqual scope to this single assertion (per audit W-3).
        given CanEqual[Any, Any] = CanEqual.derived
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "Nested with inner WHERE, PG SQL matches SqlRender.render byte-for-byte" in {
        val r = SqlStatic.staticSql(Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18)))
        val rt =
            Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18)).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // ── Leaf 3, Nested in a JOIN position ───────────────────────────────────

    "Nested used as right-hand side of an INNER JOIN lifts correctly" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p")
                .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
                .on(j => j.p.id == j.sub.userId)
                .select(j => (j.p.name, j.sub.id))
        )
        assert(r.sql.postgres.contains("INNER JOIN"))
        assert(r.sql.postgres.contains("""("p"."id" = "sub"."userId")"""))
        assert(r.sql.mysql.contains("INNER JOIN"))
        assert(r.sql.mysql.contains("(`p`.`id` = `sub`.`userId`)"))
        assert(r.params.isEmpty)
    }

    "Nested in JOIN, PG SQL matches SqlRender.render byte-for-byte" in {
        val r = SqlStatic.staticSql(
            Sql.from[Person]("p")
                .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
                .on(j => j.p.id == j.sub.userId)
                .select(j => (j.p.name, j.sub.id))
        )
        val rt = Sql.from[Person]("p")
            .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
            .on(j => j.p.id == j.sub.userId)
            .select(j => (j.p.name, j.sub.id))
            .render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

end SqlStaticNestedTest
