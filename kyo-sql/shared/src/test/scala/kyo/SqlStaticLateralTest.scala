package kyo

import kyo.Sql.render
import kyo.SqlAst.*

/** Static-SQL coverage for [[SqlAst.Lateral]] (G-Probe-5).
  *
  * Verifies that `Lateral` nodes lift through [[SqlStatic.staticSql]] at compile time. Each leaf asserts the compiled SQL strings for both
  * backends. A compile-time failure (rather than a runtime assertion) would indicate a missing [[scala.quoted.FromExpr]] derivation for
  * `Lateral` or its fields.
  *
  * Note: `staticSql` requires the query to be written inline — storing it in a `val` introduces a local binding that `q.value` cannot
  * reduce through (same limitation as G-Probe-1). All byte-for-byte runtime parity checks duplicate the query expression.
  * STATIC-SQL-INLINE-ONLY: when editing any leaf below, keep both copies of the duplicated expression identical so drift cannot silently
  * weaken the parity check.
  *
  * Deviation from PHASE-9 PLAN.md (per audit W-5): the plan's snippet uses `Sql.lateral[Person]("lat", Sql.from[Department]("d"))` (Person
  * bound to a Department source — illustrative cross-typing). That shape does not compile because the inner-source row type must align with
  * the LATERAL row type, so leaves here use `Sql.lateral[Department]("d", Sql.from[Department]("dept"))` etc.
  */
class SqlStaticLateralTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives Schema
    case class Department(id: Long, name: String) derives Schema

    // ── Leaf 1 — simple LATERAL subquery (Sql.lateral entry point) ───────────

    "simple Lateral subquery lifts and emits LATERAL keyword" in {
        val r = SqlStatic.staticSql(
            Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        )
        assert(r.sql.postgres.contains("LATERAL"))
        assert(r.sql.mysql.contains("LATERAL"))
        assert(r.params.isEmpty)
    }

    "simple Lateral — PG SELECT lists Department column names" in {
        val r = SqlStatic.staticSql(
            Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        )
        assert(r.sql.postgres.contains(""""d"."id""""))
        assert(r.sql.postgres.contains(""""d"."name""""))
    }

    "simple Lateral — MySQL SELECT lists Department column names" in {
        val r = SqlStatic.staticSql(
            Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        )
        assert(r.sql.mysql.contains("`d`.`id`"))
        assert(r.sql.mysql.contains("`d`.`name`"))
    }

    "simple Lateral — PG SQL matches SqlRender.render byte-for-byte" in {
        val r  = SqlStatic.staticSql(Sql.lateral[Department]("d", Sql.from[Department]("dept")))
        val rt = Sql.lateral[Department]("d", Sql.from[Department]("dept")).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // ── Leaf 2 — correlated LATERAL (inner query has a WHERE bound param) ────

    "correlated Lateral with WHERE bind param lifts correctly" in {
        val r = SqlStatic.staticSql(
            Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18))
        )
        assert(r.sql.postgres.contains("LATERAL"))
        assert(r.sql.postgres.contains("$1"))
        assert(r.sql.mysql.contains("LATERAL"))
        assert(r.sql.mysql.contains("?"))
        assert(r.params.size == 1)
        val bv: BoundValue[?] = r.params.head
        // Narrow CanEqual scope to this single assertion (per audit W-3): widening at class level
        // would silently relax `==` semantics for every leaf in the suite.
        given CanEqual[Any, Any] = CanEqual.derived
        assert((bv.value: Any) == 18)
        { val ok = SqlSchema.boundSchemaEqRef(bv, summon[SqlSchema[Int]]); assert(ok) }
    }

    "correlated Lateral — PG SQL matches SqlRender.render byte-for-byte" in {
        val r = SqlStatic.staticSql(Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18)))
        val rt =
            Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18)).render(SqlBackend.Postgres)
        assert(r.sql.postgres == rt.sql)
    }

    // ── Leaf 3 — LATERAL with aggregate inner query ───────────────────────────

    "Lateral wrapping a GroupBy + aggregate inner query lifts and renders" in {
        val r = SqlStatic.staticSql(
            Sql.lateral[Person](
                "agg",
                Sql.from[Person]("p")
                    .where(c => c.p.deptId == 1L)
                    .groupBy(c => c.p.deptId)
                    .select(view => view.deptId.count)
            )
        )
        assert(r.sql.postgres.contains("LATERAL"))
        assert(r.sql.postgres.contains("GROUP BY"))
        assert(r.sql.mysql.contains("LATERAL"))
        assert(r.sql.mysql.contains("GROUP BY"))
    }

end SqlStaticLateralTest
