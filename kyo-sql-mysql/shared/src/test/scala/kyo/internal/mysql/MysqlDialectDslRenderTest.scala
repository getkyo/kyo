package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test
import scala.annotation.unused
import scala.compiletime.testing.typeChecks

/** Verifies the SQL [[MysqlDialect]] renders across the DSL surface, one statement shape at a time, asserting the rendered text and the
  * bind count.
  *
  * The breadth is the point: this is where a change to any clause's rendering shows up. The type-level properties of the same DSL, which
  * clauses compose and which combinations the compiler rejects, are asserted in core, where the DSL lives.
  */
class MysqlDialectDslRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)

    val people = Sql.from[Person]("p")

    // --- SELECT ---

    // --- WHERE ---

    // --- EXISTS ---

    // --- JOIN ---

    // --- GROUP BY ---

    // --- Eager groupBy view materialization ---

    // --- ORDER BY ---

    // --- LIMIT / DISTINCT ---

    // --- SET OPS ---

    // --- CTE ---

    // --- Aggregates ---

    // --- Window functions ---

    // --- Expression DSL ---

    // MySQL's `/` is fractional for every operand type, so the exact quotient is the bare operator and needs no cast. The
    // PostgreSQL dialect renders the same node with a CAST, because its integer `/` would truncate; see
    // PostgresDialectDslRenderTest.
    "an integral division renders the bare operator on MySQL" in {
        val q = people.select(c => c.p.age / c.p.age)
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == """SELECT (`p`.`age` / `p`.`age`) FROM `person` `p`""")
        assert(r.params.size == 0)
    }

    // The truncated quotient is the one MySQL needs its own operator for: `/` never truncates here.
    "divideTruncating renders DIV on MySQL" in {
        val q = people.select(c => c.p.age.divideTruncating(c.p.age))
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == """SELECT (`p`.`age` DIV `p`.`age`) FROM `person` `p`""")
    }

    "divideTruncating against a raw value binds it and renders DIV on MySQL" in {
        val q = people.select(c => c.p.age.divideTruncating(4))
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == """SELECT (`p`.`age` DIV ?) FROM `person` `p`""")
        assert(r.params.size == 1)
    }

    "the four shared arithmetic operators render as their symbols on MySQL" in {
        val q = people.select(c => (c.p.age + 1, c.p.age - 1, c.p.age * 2, c.p.age % 2))
        val r = q.render(MysqlDialect)
        assert(r.onlySql.get == """SELECT (`p`.`age` + ?), (`p`.`age` - ?), (`p`.`age` * ?), (`p`.`age` % ?) FROM `person` `p`""")
        assert(r.params.size == 4)
    }

    // --- Aggregates ---

    "whole-table aggregates render their SQL functions on MySQL" in {
        assert(people.sum(_.p.age).render(MysqlDialect).onlySql.get == """SELECT SUM(`p`.`age`) FROM `person` `p`""")
        assert(people.avg(_.p.age).render(MysqlDialect).onlySql.get == """SELECT AVG(`p`.`age`) FROM `person` `p`""")
        assert(people.min(_.p.age).render(MysqlDialect).onlySql.get == """SELECT MIN(`p`.`age`) FROM `person` `p`""")
        assert(people.max(_.p.age).render(MysqlDialect).onlySql.get == """SELECT MAX(`p`.`age`) FROM `person` `p`""")
    }

    // --- CASE WHEN ---

    // --- Cast / call / raw ---

    // --- Fragments (sql"..." interpolator) ---

    "MySQL backend renders frag binds as ? placeholders" in {
        val cutoff = 18
        val q      = people.where(_ => sql"age > $cutoff".as[Boolean])
        val r      = q.render(MysqlDialect)
        assert(r.onlySql.get == """SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE age > ?""")
        assert(r.params.size == 1)
    }

    // --- INSERT ---

    // --- UPDATE ---

    // --- DELETE ---

    // --- Locks ---

    // --- MySQL backend ---

    "MySQL backend uses backticks and `?` placeholders" in {
        val r = people.where(c => c.p.age >= 18).render(MysqlDialect)
        assert(r.onlySql.get == """SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` >= ?)""")
        assert(r.params.size == 1)
    }

end MysqlDialectDslRenderTest
