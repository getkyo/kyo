package kyo.internal.mysql

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies that a per-field rename declared with `@column`, with no naming strategy in scope, reaches the identifiers
  * [[MysqlDialect]] emits, in both a projection and a predicate.
  */
class MysqlDialectRenameRenderTest extends Test:

    /** Single-field rename: `userId` -> `user_id`. */
    case class OrderRow(id: Long, @column("user_id") userId: Long)

    /** Two-field rename: `deptId` -> `department_id`, `empName` -> `employee_name`. */
    case class EmpRow(
        id: Long,
        @column("department_id") deptId: Long,
        @column("employee_name") empName: String
    )

    // Leaf 1: SELECT column list uses the renamed column in rendered SQL.
    "rename userId->user_id: select renders renamed column name" in {
        val q  = Sql.from[OrderRow]("o").select(c => c.o.userId)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT `o`.`user_id` FROM `orderrow` `o`")
    }

    // Leaf 2: WHERE predicate uses the renamed columns in rendered SQL.
    "rename deptId->department_id, empName->employee_name: where renders renamed column names" in {
        val q  = Sql.from[EmpRow]("e").where(c => c.e.deptId == 42L).select(c => c.e.empName)
        val rm = q.render(MysqlDialect)
        assert(rm.onlySql.get == "SELECT `e`.`employee_name` FROM `emprow` `e` WHERE (`e`.`department_id` = ?)")
    }

end MysqlDialectRenameRenderTest
