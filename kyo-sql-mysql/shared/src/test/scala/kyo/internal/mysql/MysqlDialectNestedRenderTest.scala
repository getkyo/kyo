package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies the SQL [[MysqlDialect]] renders for a query used as a source of another, including how the inner query's columns are
  * re-aliased on the way out.
  *
  * Each scenario pins the whole statement, because the re-aliasing is the property under test: the outer projection has to name the
  * subquery's alias while the inner projection keeps the base table's, and a check for a paren or a keyword sees neither.
  */
class MysqlDialectNestedRenderTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long)
    case class Order(id: Long, userId: Long)

    // SELECT FROM (simple subquery)

    "simple Nested subquery emits subquery parens on MySQL" in {
        val q = Sql.nested[Person]("sub", Sql.from[Person]("p"))
        assert(
            q.render(MysqlDialect).onlySql.get ==
                "SELECT `sub`.`id`, `sub`.`name`, `sub`.`age`, `sub`.`deptId` FROM (SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p`) `sub`"
        )
        assert(q.render(MysqlDialect).params.isEmpty)
    }

    // Nested wrapping a WHERE subquery with bind param

    "Nested with inner WHERE threads the bind param on MySQL" in {
        val q  = Sql.nested[Person]("sub", Sql.from[Person]("p").where(c => c.p.age >= 18))
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get ==
                "SELECT `sub`.`id`, `sub`.`name`, `sub`.`age`, `sub`.`deptId` FROM (SELECT `p`.`id`, `p`.`name`, `p`.`age`, `p`.`deptId` FROM `person` `p` WHERE (`p`.`age` >= ?)) `sub`"
        )
        assert(rm.params.size == 1)
        val bv: Sql.BoundValue[?] = rm.params.head
        given CanEqual[Any, Any]  = CanEqual.derived
        assert((bv.value: Any) == 18)
        assert(bv.schema eq SqlSchema.int)
    }

    // Nested in a JOIN position

    "Nested used as right-hand side of an INNER JOIN renders correctly" in {
        val q = Sql.from[Person]("p")
            .innerJoin(Sql.nested[Order]("sub", Sql.from[Order]("o")))
            .on(j => j.p.id == j.sub.userId)
            .select(j => (j.p.name, j.sub.id))
        val rm = q.render(MysqlDialect)
        assert(
            rm.onlySql.get ==
                "SELECT `p`.`name`, `sub`.`id` FROM `person` `p` INNER JOIN (SELECT `o`.`id`, `o`.`userId` FROM `order` `o`) `sub` ON (`p`.`id` = `sub`.`userId`)"
        )
        assert(rm.params.isEmpty)
    }

end MysqlDialectNestedRenderTest
