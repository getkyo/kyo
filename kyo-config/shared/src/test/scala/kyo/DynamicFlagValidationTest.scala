package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class DynamicFlagValidationTest extends AnyFreeSpec {

    // On JVM, object init errors are wrapped in ExceptionInInitializerError.
    // On JS/Native, the FlagException is thrown directly.
    def interceptInitError(block: => Any): FlagException = {
        try {
            block
            fail("Expected exception was not thrown")
        } catch {
            case e: ExceptionInInitializerError =>
                e.getCause.asInstanceOf[FlagException]
            case e: FlagException =>
                e
        }
    }

    "DynamicFlag pre-parsing and validation" - {

        "all values parsed at init — bad value fails class load" in {
            java.lang.System.setProperty("kyo.DynValTestFlags.badValueInit", "1000@enterprise;notanumber;500")
            try {
                val cause = interceptInitError {
                    DynValTestFlags.badValueInit
                }
                assert(cause.getMessage.contains("notanumber"))
                assert(cause.getMessage.contains("Int"))
            } finally {
                java.lang.System.clearProperty("kyo.DynValTestFlags.badValueInit"): Unit
            }
        }

        "error message includes choice position" in {
            java.lang.System.setProperty("kyo.DynValTestFlags.choicePos", "1@x;bad;3")
            try {
                val cause = interceptInitError {
                    DynValTestFlags.choicePos
                }
                assert(cause.getMessage.contains("choice 2 of 3"))
            } finally {
                java.lang.System.clearProperty("kyo.DynValTestFlags.choicePos"): Unit
            }
        }

        "error message includes full expression" in {
            java.lang.System.setProperty("kyo.DynValTestFlags.fullExpr", "1@x;bad;3")
            try {
                val cause = interceptInitError {
                    DynValTestFlags.fullExpr
                }
                assert(cause.getMessage.contains("1@x;bad;3"))
            } finally {
                java.lang.System.clearProperty("kyo.DynValTestFlags.fullExpr"): Unit
            }
        }

        "error message includes expected type" in {
            java.lang.System.setProperty("kyo.DynValTestFlags.expectedType", "notanint")
            try {
                val cause = interceptInitError {
                    DynValTestFlags.expectedType
                }
                assert(cause.getMessage.contains("Int"))
            } finally {
                java.lang.System.clearProperty("kyo.DynValTestFlags.expectedType"): Unit
            }
        }

        "error message includes flag name" in {
            java.lang.System.setProperty("kyo.DynValTestFlags.flagName", "bad")
            try {
                val cause = interceptInitError {
                    DynValTestFlags.flagName
                }
                assert(cause.getMessage.contains("kyo.DynValTestFlags.flagName"))
            } finally {
                java.lang.System.clearProperty("kyo.DynValTestFlags.flagName"): Unit
            }
        }

        "validate runs at parse time — value is clamped" in {
            java.lang.System.setProperty("kyo.DynValTestFlags.validateParse", "0@enterprise;5")
            try {
                val flag = DynValTestFlags.validateParse
                // "0" with Math.max(1, _) should be clamped to 1
                assert(flag("user1", "enterprise") == 1)
                assert(flag("user1") == 5)
            } finally {
                java.lang.System.clearProperty("kyo.DynValTestFlags.validateParse"): Unit
            }
        }

        "empty expression is valid — update clears choices" in {
            val flag = DynValTestFlags.emptyExpr
            flag.update("")
            assert(flag("user1") == 42)
            assert(flag("user2") == 42)
        }

        "whitespace-only expression treated as empty" in {
            val flag = DynValTestFlags.whitespaceExpr
            flag.update("  ")
            assert(flag("user1") == 42)
        }

        "whitespace around choices trimmed" in {
            val flag = DynValTestFlags.whitespaceChoices
            flag.update(" a@x ; b@y ; c ")
            assert(flag("user1", "x") == "a")
            assert(flag("user1", "y") == "b")
            assert(flag("user1", "z") == "c")
        }

        "empty choice throws" in {
            val flag = DynValTestFlags.emptyChoice
            val ex = intercept[FlagException] {
                flag.update("a@x;;b")
            }
            assert(ex.getMessage.contains("empty choice"))
        }

        "empty selector throws" in {
            val flag = DynValTestFlags.emptySelector
            val ex = intercept[FlagException] {
                flag.update("a@")
            }
            assert(ex.getMessage.contains("empty selector"))
        }

        "double slash throws" in {
            val flag = DynValTestFlags.doubleSlash
            val ex = intercept[FlagException] {
                flag.update("a@prod//us")
            }
            assert(ex.getMessage.contains("empty path segment"))
            assert(ex.getMessage.contains("position 2"))
        }

        "leading slash throws" in {
            val flag = DynValTestFlags.leadingSlash
            val ex = intercept[FlagException] {
                flag.update("a@/prod")
            }
            assert(ex.getMessage.contains("empty path segment"))
            assert(ex.getMessage.contains("position 1"))
        }

        "trailing slash throws" in {
            val flag = DynValTestFlags.trailingSlash
            val ex = intercept[FlagException] {
                flag.update("a@prod/")
            }
            assert(ex.getMessage.contains("empty path segment"))
        }

        "negative percentage throws" in {
            val flag = DynValTestFlags.negPercent
            val ex = intercept[FlagException] {
                flag.update("a@prod/-5%")
            }
            assert(ex.getMessage.contains("negative percentage"))
        }

        "non-numeric percentage throws" in {
            val flag = DynValTestFlags.nonNumericPct
            val ex = intercept[FlagException] {
                flag.update("a@prod/abc%")
            }
            assert(ex.getMessage.contains("invalid percentage"))
        }

        "valid complex expression succeeds" in {
            val flag = DynValTestFlags.validComplex
            flag.update("50@prod/us-east-1/50%;30@staging;10")
            assert(flag("user1", "staging") == 30)
            assert(flag("user1", "other") == 10)
        }

        "empty value before @ throws" in {
            val flag = DynValTestFlags.emptyValueAt
            val ex = intercept[FlagException] {
                flag.update("@prod/50%")
            }
            assert(ex.getMessage.contains("empty value"))
        }
    }

}

object DynValTestFlags {
    object badValueInit      extends DynamicFlag[Int](0)
    object choicePos         extends DynamicFlag[Int](0)
    object fullExpr          extends DynamicFlag[Int](0)
    object expectedType      extends DynamicFlag[Int](0)
    object flagName          extends DynamicFlag[Int](0)
    object validateParse     extends DynamicFlag[Int](0, (a: Int) => Right(Math.max(1, a)))
    object emptyExpr         extends DynamicFlag[Int](42)
    object whitespaceExpr    extends DynamicFlag[Int](42)
    object whitespaceChoices extends DynamicFlag[String]("default")
    object emptyChoice       extends DynamicFlag[String]("default")
    object emptySelector     extends DynamicFlag[String]("default")
    object doubleSlash       extends DynamicFlag[String]("default")
    object leadingSlash      extends DynamicFlag[String]("default")
    object trailingSlash     extends DynamicFlag[String]("default")
    object negPercent        extends DynamicFlag[String]("default")
    object nonNumericPct     extends DynamicFlag[String]("default")
    object validComplex      extends DynamicFlag[Int](0)
    object emptyValueAt      extends DynamicFlag[Int](0)
}
