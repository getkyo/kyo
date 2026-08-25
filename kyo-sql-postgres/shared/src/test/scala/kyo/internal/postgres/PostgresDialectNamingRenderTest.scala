package kyo.internal.postgres

import kyo.*
import kyo.Sql.*
import kyo.Test

/** Verifies that every name-resolution path reaches the SQL [[PostgresDialect]] emits: a query-scoped [[kyo.SqlNaming]] casing, the
  * table-name parameter, a per-field `@column` rename, and the combinations, each landing in the identifiers of the rendered
  * statement.
  *
  * The casing governs tables and columns alike: under `SnakeCase`, `UserProfile` reads `"user_profile"` below. With no casing in scope a
  * table with no name parameter is the lowercased type name, and a passed name always wins over both.
  */
class PostgresDialectNamingRenderTest extends Test:

    // --- Group 1: SnakeCase casing ---

    /** camelCase field names to be snake_cased: `createdAt` -> `created_at`, `firstName` -> `first_name`. */
    case class UserProfile(id: Long, firstName: String, createdAt: Long)

    // Leaf 1: SnakeCase applied to all columns in SELECT.
    "SnakeCase casing: all columns rendered as snake_case in SELECT" in {
        given SqlNaming = SqlNaming.SnakeCase
        val q           = Sql.from[UserProfile]("u")
        val r           = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "u"."id", "u"."first_name", "u"."created_at" FROM "user_profile" "u"""")
    }

    // Leaf 2: SnakeCase applied to a column in a WHERE predicate.
    "SnakeCase casing: column in WHERE renders as snake_case" in {
        given SqlNaming = SqlNaming.SnakeCase
        val q           = Sql.from[UserProfile]("u").where(c => c.u.createdAt > 0L).select(c => c.u.firstName)
        val r           = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "u"."first_name" FROM "user_profile" "u" WHERE ("u"."created_at" > $1)""")
    }

    // Leaf 3: with no casing in scope the same columns stay verbatim, which is what makes leaves 1 and 2 about the casing.
    "no casing in scope: the same columns render verbatim" in {
        val q = Sql.from[UserProfile]("u")
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "u"."id", "u"."firstName", "u"."createdAt" FROM "userprofile" "u"""")
    }

    // --- Group 2: the table-name parameter ---

    /** Table name override: `Item` -> `line_items`. No column renames. */
    case class Item(id: Long, sku: String, qty: Int)

    // Leaf 4: the table-name parameter is used in the FROM clause; columns unchanged.
    "table-name parameter: FROM uses the given table name, columns unchanged" in {
        val q = Sql.from[Item]("i", "line_items")
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "i"."id", "i"."sku", "i"."qty" FROM "line_items" "i"""")
    }

    // Leaf 5: the table name survives a WHERE + select chain.
    "table-name parameter: table name survives WHERE + select chain" in {
        val q = Sql.from[Item]("i", "line_items").where(c => c.i.qty > 0).select(c => c.i.sku)
        val r = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "i"."sku" FROM "line_items" "i" WHERE ("i"."qty" > $1)""")
    }

    // --- Group 3: per-field rename overrides the casing ---

    /** One field renamed explicitly, which overrides the casing for that field: `deptId` becomes `dept_code` rather than the `dept_id`
      * SnakeCase would produce.
      */
    case class Employee(id: Long, @column("dept_code") deptId: Long, salary: BigDecimal)

    // Leaf 6: rename wins over the casing for the annotated field; the casing applies to the rest.
    "rename beats casing: the annotated field uses its explicit name, others snake_case" in {
        given SqlNaming = SqlNaming.SnakeCase
        val q           = Sql.from[Employee]("e")
        val r           = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "e"."id", "e"."dept_code", "e"."salary" FROM "employee" "e"""")
    }

    // Leaf 7: the renamed column is used in a WHERE predicate.
    "rename beats casing: the annotated field name is used in a WHERE predicate" in {
        given SqlNaming = SqlNaming.SnakeCase
        val q           = Sql.from[Employee]("e").where(c => c.e.deptId == 10L).select(c => c.e.salary)
        val r           = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "e"."salary" FROM "employee" "e" WHERE ("e"."dept_code" = $1)""")
    }

    // --- Group 4: casing + table-name parameter + per-field rename ---

    /** Full combination: SnakeCase casing, a table-name parameter, and a per-field rename. */
    case class ProductCatalog(id: Long, @column("name") productName: String, listPrice: BigDecimal)

    // Leaf 8: combined, the table name, the per-field rename, and the casing for the remaining field.
    "combined: table-name parameter + per-field rename + casing for the remaining fields" in {
        given SqlNaming = SqlNaming.SnakeCase
        val q           = Sql.from[ProductCatalog]("p", "catalog")
        val r           = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "p"."id", "p"."name", "p"."list_price" FROM "catalog" "p"""")
    }

    // Leaf 9: WHERE + select with all three in play.
    "combined: WHERE + select use the given table name, the renamed and the snake_cased columns" in {
        given SqlNaming = SqlNaming.SnakeCase
        val q           = Sql.from[ProductCatalog]("p", "catalog").where(c => c.p.listPrice > BigDecimal(0)).select(c => c.p.productName)
        val r           = q.render(PostgresDialect)
        assert(r.onlySql.get == """SELECT "p"."name" FROM "catalog" "p" WHERE ("p"."list_price" > $1)""")
    }

end PostgresDialectNamingRenderTest
