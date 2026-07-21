package kyo.internal

import kyo.SqlSchema.Naming
import kyo.Test

/** Tests for [[SqlSchemaNamingFromExpr]] -- the `given FromExpr[Naming]` macro lifter.
  *
  * Uses [[SqlSchemaNamingFromExprHarness]] (a thin macro wrapper in a separate source file, satisfying the Scala 3 rule that a macro
  * definition and its call site must be in different compilation units).
  */
class SqlSchemaNamingFromExprTest extends Test:

    /** A user-defined Naming not known to [[SqlSchemaNamingFromExpr]]. */
    object MyStrategy extends Naming:
        def tableName(s: String): String  = s.toUpperCase
        def columnName(s: String): String = s.toUpperCase

    "liftsSnakeCase" in {
        // SqlSchemaNamingFromExpr.given FromExpr[Naming] should match Naming.snakeCase.
        assert(SqlSchemaNamingFromExprHarness.matched(Naming.snakeCase))
    }

    "failsOnUnknownStrategy" in {
        // A user-defined Naming not in the known set returns None (false from matched).
        assert(!SqlSchemaNamingFromExprHarness.matched(MyStrategy))
    }

end SqlSchemaNamingFromExprTest
