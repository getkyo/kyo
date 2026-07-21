package kyo.internal

import kyo.NamingStrategy
import kyo.Test

/** Tests for [[NamingStrategyFromExpr]] -- the `given FromExpr[NamingStrategy]` macro lifter.
  *
  * Uses [[NamingStrategyFromExprHarness]] (a thin macro wrapper in a separate source file, satisfying the Scala 3 rule that a macro
  * definition and its call site must be in different compilation units).
  */
class NamingStrategyFromExprTest extends Test:

    /** A user-defined NamingStrategy not known to [[NamingStrategyFromExpr]]. */
    object MyStrategy extends NamingStrategy:
        def tableName(s: String): String  = s.toUpperCase
        def columnName(s: String): String = s.toUpperCase

    "liftsSnakeCase" in {
        // NamingStrategyFromExpr.given FromExpr[NamingStrategy] should match NamingStrategy.snakeCase.
        assert(NamingStrategyFromExprHarness.matched(NamingStrategy.snakeCase))
    }

    "failsOnUnknownStrategy" in {
        // A user-defined NamingStrategy not in the known set returns None (false from matched).
        assert(!NamingStrategyFromExprHarness.matched(MyStrategy))
    }

end NamingStrategyFromExprTest
