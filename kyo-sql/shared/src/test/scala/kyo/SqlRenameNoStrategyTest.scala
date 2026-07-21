package kyo

import kyo.SqlAst.*

/** Tests that verify per-field SQL column renames applied via [[SqlSchema.rename]] are correctly baked into
  * [[kyo.SqlAst.Column.sqlName]] at construction time and emitted by [[kyo.internal.SqlRender]].
  *
  * No [[NamingStrategy]] is attached; only explicit `rename(from, to)` transforms are in play. Two leaves exercise the SELECT column list
  * (via `Sql.from[T].select`) and the WHERE predicate (via `Sql.from[T].where`).
  */
class SqlRenameNoStrategyTest extends Test:

    /** Single-field rename: `userId` -> `user_id`. */
    case class OrderRow(id: Long, userId: Long)
    object OrderRow:
        given SqlSchema[OrderRow] = SqlSchema.derived[OrderRow].rename("userId", "user_id")

    /** Two-field rename: `deptId` -> `department_id`, `empName` -> `employee_name`. */
    case class EmpRow(id: Long, deptId: Long, empName: String)
    object EmpRow:
        given SqlSchema[EmpRow] = SqlSchema.derived[EmpRow]
            .rename("deptId", "department_id")
            .rename("empName", "employee_name")
    end EmpRow

    // Leaf 1: SELECT column list uses renamed sqlName in rendered SQL.
    "rename userId->user_id: select renders renamed column name" in {
        val q = Sql.from[OrderRow]("o").select(c => c.o.userId)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "o"."user_id" FROM "orderrow" "o"""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `o`.`user_id` FROM `orderrow` `o`")
    }

    // Leaf 2: WHERE predicate uses renamed sqlName in rendered SQL.
    "rename deptId->department_id, empName->employee_name: where renders renamed column names" in {
        val q = Sql.from[EmpRow]("e").where(c => c.e.deptId == 42L).select(c => c.e.empName)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "e"."employee_name" FROM "emprow" "e" WHERE ("e"."department_id" = $1)""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `e`.`employee_name` FROM `emprow` `e` WHERE (`e`.`department_id` = ?)")
    }

end SqlRenameNoStrategyTest
