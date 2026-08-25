package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[MysqlDialect]] renders for a `LATERAL` subquery, whose predicate may reference columns of the surrounding
  * `FROM`.
  *
  * The statement is pinned whole rather than by keyword, because what a `LATERAL` source has to get right is the nesting: the outer
  * projection names the lateral alias, the inner query keeps its own alias, and the closing paren carries the alias after it. Rendering here
  * targets the capability floor, which is above the 8.0.14 release that introduced `LATERAL`, so the keyword is available.
  */
class MysqlDialectLateralRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Department(id: Long, name: String)

    // simple LATERAL subquery (Sql.lateral entry point)

    "simple Lateral subquery emits LATERAL keyword on MySQL" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT `d`.`id`, `d`.`name` FROM LATERAL (SELECT `dept`.`id`, `dept`.`name` FROM `department` `dept`) `d`"
        )
        assert(q.render(MysqlDialect).params.isEmpty)
    }

    "simple Lateral, MySQL SELECT lists Department column names" in {
        val q = Sql.lateral[Department]("d", Sql.from[Department]("dept"))
        // The outer projection is the lateral alias, not the inner table alias.
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT `d`.`id`, `d`.`name` FROM LATERAL (SELECT `dept`.`id`, `dept`.`name` FROM `department` `dept`) `d`"
        )
    }

    // correlated LATERAL (inner query has a WHERE bound param)

    "correlated Lateral with WHERE bind param threads the bind on MySQL" in {
        val q  = Sql.lateral[Person]("lat", Sql.from[Person]("p").where(c => c.p.age >= 18))
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get ==
                "SELECT `lat`.`id`, `lat`.`name`, `lat`.`age`, `lat`.`deptId` FROM LATERAL (SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` >= ?)) `lat`"
        )
        assert(rm.params.size == 1)
        val bv: Sql.BoundValue[?] = rm.params.head
        given CanEqual[Any, Any]  = CanEqual.derived
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    // LATERAL with aggregate inner query

    "Lateral wrapping a GroupBy + aggregate inner query renders correctly on MySQL" in {
        val q = Sql.lateral[Person](
            "agg",
            Sql.from[Person]("p")
                .where(c => c.p.deptId == 1L)
                .groupBy(c => c.p.deptId)
                .select(view => view.deptId.count)
        )
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get ==
                "SELECT `agg`.`id`, `agg`.`name`, `agg`.`age`, `agg`.`deptId` FROM LATERAL (SELECT COUNT(`p`.`deptId`) FROM `person` `p` WHERE (`p`.`deptId` = ?) GROUP BY `p`.`deptId`) `agg`"
        )
        assert(rm.params.size == 1)
    }

end MysqlDialectLateralRenderTest
