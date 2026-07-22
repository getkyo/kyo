package kyo

import kyo.SqlAst.*

/** Tests that verify all three [[kyo.internal.SqlNameResolver]] resolution paths -- [[SqlSchema.Naming]], table-name override via
  * `.withTableName`, per-field `.rename`, and combinations -- correctly flow through [[kyo.SqlAst.Column.sqlName]] into rendered SQL.
  *
  * Eight leaves are organised as four groups:
  *   1. Leaves 1-2: [[SqlSchema.Naming.SnakeCase]] strategy only (columns + table name).
  *   2. Leaves 3-4: `.withTableName` override (no strategy).
  *   3. Leaves 5-6: `.rename` overrides snakeCase for selected fields (rename wins over strategy).
  *   4. Leaves 7-8: combined strategy + table-name override + per-field rename.
  */
class SqlRenameStrategyTest extends Test:

    // --- Group 1: snakeCase strategy ---

    /** camelCase field names to be snake_cased: `createdAt` -> `created_at`, `firstName` -> `first_name`. */
    case class UserProfile(id: Long, firstName: String, createdAt: Long)
    object UserProfile:
        inline given SqlSchema[UserProfile] = SqlSchema.derived[UserProfile]
            .withNaming(SqlSchema.Naming.SnakeCase)

    // Leaf 1: snakeCase applied to all columns in SELECT.
    "snakeCase strategy: all columns rendered as snake_case in SELECT" in {
        val q = Sql.from[UserProfile]("u")
        val r = q.renderPostgres
        assert(r.sql == """SELECT "u"."id", "u"."first_name", "u"."created_at" FROM "user_profile" "u"""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `u`.`id`, `u`.`first_name`, `u`.`created_at` FROM `user_profile` `u`")
    }

    // Leaf 2: snakeCase applied to column in WHERE predicate.
    "snakeCase strategy: column in WHERE renders as snake_case" in {
        val q = Sql.from[UserProfile]("u").where(c => c.u.createdAt > 0L).select(c => c.u.firstName)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "u"."first_name" FROM "user_profile" "u" WHERE ("u"."created_at" > $1)""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `u`.`first_name` FROM `user_profile` `u` WHERE (`u`.`created_at` > ?)")
    }

    // --- Group 2: withTableName override ---

    /** Table name override: `Item` -> `line_items`. No column renames. */
    case class Item(id: Long, sku: String, qty: Int)
    object Item:
        inline given SqlSchema[Item] = SqlSchema.derived[Item].withTableName("line_items")

    // Leaf 3: table name override used in FROM clause; columns unchanged.
    "withTableName override: FROM uses custom table name, columns unchanged" in {
        val q = Sql.from[Item]("i")
        val r = q.renderPostgres
        assert(r.sql == """SELECT "i"."id", "i"."sku", "i"."qty" FROM "line_items" "i"""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `i`.`id`, `i`.`sku`, `i`.`qty` FROM `line_items` `i`")
    }

    // Leaf 4: table name override survives a WHERE + select chain.
    "withTableName override: table name survives WHERE + select chain" in {
        val q = Sql.from[Item]("i").where(c => c.i.qty > 0).select(c => c.i.sku)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "i"."sku" FROM "line_items" "i" WHERE ("i"."qty" > $1)""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `i`.`sku` FROM `line_items` `i` WHERE (`i`.`qty` > ?)")
    }

    // --- Group 3: per-field rename overrides snakeCase ---

    /** snakeCase strategy with one field rename that overrides the strategy for that field: `deptId` renamed to `dept_code` (overrides
      * `dept_id` that snakeCase would produce).
      */
    case class Employee(id: Long, deptId: Long, salary: BigDecimal)
    object Employee:
        inline given SqlSchema[Employee] = SqlSchema.derived[Employee]
            .withNaming(SqlSchema.Naming.SnakeCase)
            .rename("deptId", "dept_code")
    end Employee

    // Leaf 5: rename wins over snakeCase for the overridden field; snakeCase applies to the rest.
    "rename beats snakeCase: overridden field uses explicit name, others snake_case" in {
        val q = Sql.from[Employee]("e")
        val r = q.renderPostgres
        assert(r.sql == """SELECT "e"."id", "e"."dept_code", "e"."salary" FROM "employee" "e"""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `e`.`id`, `e`.`dept_code`, `e`.`salary` FROM `employee` `e`")
    }

    // Leaf 6: the renamed column is used in a WHERE predicate.
    "rename beats snakeCase: overridden field name used in WHERE predicate" in {
        val q = Sql.from[Employee]("e").where(c => c.e.deptId == 10L).select(c => c.e.salary)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "e"."salary" FROM "employee" "e" WHERE ("e"."dept_code" = $1)""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `e`.`salary` FROM `employee` `e` WHERE (`e`.`dept_code` = ?)")
    }

    // --- Group 4: combined strategy + table-name override + per-field rename ---

    /** Full combination: snakeCase + custom table name + per-field rename. */
    case class ProductCatalog(id: Long, productName: String, listPrice: BigDecimal)
    object ProductCatalog:
        inline given SqlSchema[ProductCatalog] = SqlSchema.derived[ProductCatalog]
            .withNaming(SqlSchema.Naming.SnakeCase)
            .withTableName("catalog")
            .rename("productName", "name")
    end ProductCatalog

    // Leaf 7: combined -- table name, per-field rename, and snakeCase for the remaining field.
    "combined: table override + per-field rename + snakeCase for remaining fields" in {
        val q = Sql.from[ProductCatalog]("p")
        val r = q.renderPostgres
        assert(r.sql == """SELECT "p"."id", "p"."name", "p"."list_price" FROM "catalog" "p"""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `p`.`id`, `p`.`name`, `p`.`list_price` FROM `catalog` `p`")
    }

    // Leaf 8: WHERE + select with all three transforms in play.
    "combined: WHERE + select use overridden table name, renamed and snake_cased columns" in {
        val q = Sql.from[ProductCatalog]("p").where(c => c.p.listPrice > BigDecimal(0)).select(c => c.p.productName)
        val r = q.renderPostgres
        assert(r.sql == """SELECT "p"."name" FROM "catalog" "p" WHERE ("p"."list_price" > $1)""")
        val rm = q.renderMysql
        assert(rm.sql == "SELECT `p`.`name` FROM `catalog` `p` WHERE (`p`.`list_price` > ?)")
    }

end SqlRenameStrategyTest
