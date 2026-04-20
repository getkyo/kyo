package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class DynamicFlagUpdateTest extends AnyFreeSpec {

    "DynamicFlag runtime updates" - {

        "update() changes apply() result" in {
            val flag = DynUpdateTestFlags.updateChanges
            flag.update("true@premium")
            assert(flag("user1", "premium") == true)
            flag.update("false@premium")
            assert(flag("user1", "premium") == false)
        }

        "update() safe rollback on parse error" in {
            val flag = DynUpdateTestFlags.rollbackParse
            flag.update("100@enterprise")
            assert(flag("user1", "enterprise") == 100)
            val ex = intercept[FlagException] {
                flag.update("notanumber")
            }
            // Old state preserved
            assert(flag("user1", "enterprise") == 100)
        }

        "update() safe rollback on validate error" in {
            val flag = DynUpdateTestFlags.rollbackValidate
            flag.update("10@enterprise")
            assert(flag("user1", "enterprise") == 10)
            val ex = intercept[FlagException] {
                flag.update("0@enterprise") // validate requires > 0
            }
            // Old state preserved
            assert(flag("user1", "enterprise") == 10)
        }

        "update() with plain value" in {
            val flag = DynUpdateTestFlags.plainValue
            flag.update("42")
            assert(flag("user1") == 42)
            assert(flag("user2") == 42)
        }

        "update() clears with empty string" in {
            val flag = DynUpdateTestFlags.clearExpr
            flag.update("100@enterprise")
            assert(flag("user1", "enterprise") == 100)
            flag.update("")
            assert(flag("user1", "enterprise") == 0) // validatedDefault
            assert(flag("user1") == 0)
        }

        "update() expression is reflected" in {
            val flag = DynUpdateTestFlags.exprReflected
            flag.update("new@expr")
            assert(flag.expression == "new@expr")
        }

        "multiple sequential updates — latest wins" in {
            val flag = DynUpdateTestFlags.seqUpdates
            flag.update("a@x")
            assert(flag("user1", "x") == "a")
            flag.update("b@x")
            assert(flag("user1", "x") == "b")
            flag.update("c@x")
            assert(flag("user1", "x") == "c")
        }

        "reload() picks up system property change" in {
            java.lang.System.setProperty("kyo.DynUpdateTestFlags.reloadProp", "100@enterprise")
            try {
                val flag = DynUpdateTestFlags.reloadProp
                assert(flag("user1", "enterprise") == 100)
                // Change the property
                java.lang.System.setProperty("kyo.DynUpdateTestFlags.reloadProp", "200@enterprise")
                val result = flag.reload()
                assert(result.isInstanceOf[Flag.ReloadResult.Updated])
                assert(flag("user1", "enterprise") == 200)
            } finally {
                java.lang.System.clearProperty("kyo.DynUpdateTestFlags.reloadProp"): Unit
            }
        }

        "reload() returns Unchanged when same expression" in {
            java.lang.System.setProperty("kyo.DynUpdateTestFlags.reloadUnchanged", "100@enterprise")
            try {
                val flag   = DynUpdateTestFlags.reloadUnchanged
                val result = flag.reload()
                assert(result == Flag.ReloadResult.Unchanged)
            } finally {
                java.lang.System.clearProperty("kyo.DynUpdateTestFlags.reloadUnchanged"): Unit
            }
        }

        "reload() returns NoSource for Default source" in {
            val flag   = DynUpdateTestFlags.reloadNoSource
            val result = flag.reload()
            assert(result == Flag.ReloadResult.NoSource)
        }

        "reload() returns NoSource when property removed" in {
            java.lang.System.setProperty("kyo.DynUpdateTestFlags.reloadRemoved", "100")
            try {
                val flag = DynUpdateTestFlags.reloadRemoved
                assert(flag.source == Flag.Source.SystemProperty)
            } finally {
                java.lang.System.clearProperty("kyo.DynUpdateTestFlags.reloadRemoved"): Unit
            }
            // Property removed — reload returns NoSource but keeps current state
            val result = DynUpdateTestFlags.reloadRemoved.reload()
            assert(result == Flag.ReloadResult.NoSource)
        }

        "toString reflects current state" in {
            val flag = DynUpdateTestFlags.toStringTest
            flag.update("new@expr")
            val str = flag.toString()
            assert(str.contains("DynamicFlag("))
            assert(str.contains("new@expr"))
        }

        "isDynamic is true" in {
            val flag = DynUpdateTestFlags.isDynamicTest
            assert(flag.isDynamic)
        }

        "state consistency — expression matches choices" in {
            val flag = DynUpdateTestFlags.stateConsistency
            flag.update("100@enterprise;50")
            assert(flag.expression == "100@enterprise;50")
            assert(flag("user1", "enterprise") == 100)
            assert(flag("user1") == 50)
        }

        "update with whitespace-only string returns default" in {
            val flag = DynUpdateTestFlags.whitespaceUpdate
            flag.update("42")
            assert(flag("user1") == 42)
            // Whitespace-only trims to empty, so choices become empty array, returns default
            flag.update("  ")
            assert(flag("user1") == 0) // validatedDefault
        }

        "update with validate that throws is caught and wrapped" in {
            val flag = DynUpdateTestFlags.validateThrows
            val ex = intercept[FlagValidationFailedException] {
                flag.update("42")
            }
            assert(ex.cause.isInstanceOf[RuntimeException])
            assert(ex.cause.getMessage == "validator boom")
        }

        "kill switch pattern — update to plain false" in {
            val flag = DynUpdateTestFlags.killSwitch
            flag.update("true@premium/50%")
            // Some calls might return true
            flag.update("false")
            // After kill switch, ALL calls return false
            assert(flag("user1", "premium") == false)
            assert(flag("user2") == false)
            assert(flag("user3", "enterprise") == false)
        }
    }

}

object DynUpdateTestFlags {
    object updateChanges extends DynamicFlag[Boolean](false)
    object rollbackParse extends DynamicFlag[Int](0)
    object rollbackValidate extends DynamicFlag[Int](
            1,
            (a: Int) =>
                if (a > 0) Right(a)
                else Left(new IllegalArgumentException("must be positive"))
        )
    object plainValue       extends DynamicFlag[Int](0)
    object clearExpr        extends DynamicFlag[Int](0)
    object exprReflected    extends DynamicFlag[String]("default")
    object seqUpdates       extends DynamicFlag[String]("default")
    object reloadProp       extends DynamicFlag[Int](0)
    object reloadUnchanged  extends DynamicFlag[Int](0)
    object reloadNoSource   extends DynamicFlag[Int](0)
    object reloadRemoved    extends DynamicFlag[Int](0)
    object toStringTest     extends DynamicFlag[String]("default")
    object isDynamicTest    extends DynamicFlag[Int](0)
    object stateConsistency extends DynamicFlag[Int](0)
    object killSwitch       extends DynamicFlag[Boolean](false)
    object whitespaceUpdate extends DynamicFlag[Int](0)
    object validateThrows extends DynamicFlag[Int](
            0,
            (a: Int) =>
                if (a == 0) Right(a)
                else throw new RuntimeException("validator boom")
        )
}
