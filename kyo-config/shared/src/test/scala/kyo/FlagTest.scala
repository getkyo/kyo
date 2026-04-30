package kyo

import org.scalatest.freespec.AnyFreeSpec

class FlagTest extends AnyFreeSpec {

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

    "Flag value resolution" - {

        "default value when not configured" in {
            val flag = FlagTestFlags.defaultInt
            assert(flag.value == 42)
        }

        "system property plain value" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.sysPropInt", "99")
            try {
                val flag = FlagTestFlags.sysPropInt
                assert(flag.value == 99)
                assert(flag.source == Flag.Source.SystemProperty)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.sysPropInt"): Unit
            }
        }

        "validate transforms default" in {
            // Math.max(1, _) on default 10 -> 10 (identity for valid input)
            val flag = FlagTestFlags.validateIdentity
            assert(flag.value == 10)
        }

        "validate clamps parsed value" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.validateClamp", "-5")
            try {
                val flag = FlagTestFlags.validateClamp
                assert(flag.value == 1)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.validateClamp"): Unit
            }
        }

        "parse error fail-fast" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.parseError", "notanumber")
            try {
                val cause = interceptInitError {
                    FlagTestFlags.parseError
                }
                assert(cause.getMessage.contains("kyo.FlagTestFlags.parseError"))
                assert(cause.getMessage.contains("notanumber"))
                assert(cause.getMessage.contains("Int"))
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.parseError"): Unit
            }
        }

        "rollout expression with terminal" in {
            // Use a rollout expression where the terminal is the expected result.
            // This works regardless of the Rollout.path state.
            // The expression has a non-matching conditional + a terminal.
            java.lang.System.setProperty("kyo.FlagTestFlags.rolloutExpr", "50@__nonexistent_path__;10")
            try {
                val flag = FlagTestFlags.rolloutExpr
                // The conditional won't match any realistic path, so terminal "10" is selected
                assert(flag.value == 10)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.rolloutExpr"): Unit
            }
        }

        "rollout no match uses default" in {
            // Use a path that won't match any realistic rollout path
            java.lang.System.setProperty("kyo.FlagTestFlags.rolloutNoMatch", "50@__nonexistent_x_y_z__")
            try {
                val flag = FlagTestFlags.rolloutNoMatch
                // No match and no terminal, so uses constructor default (7)
                assert(flag.value == 7)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.rolloutNoMatch"): Unit
            }
        }

        "rollout error fail-fast" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.rolloutError", "50@prod/abc%")
            try {
                val cause = interceptInitError {
                    FlagTestFlags.rolloutError
                }
                assert(cause.getMessage.contains("rollout expression error"))
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.rolloutError"): Unit
            }
        }

        "apply() returns same as value" in {
            val flag = FlagTestFlags.applyTest
            assert(flag() eq flag.value.asInstanceOf[AnyRef])
        }

        "isDynamic is false" in {
            val flag = FlagTestFlags.defaultInt
            assert(!flag.isDynamic)
        }

        "toString format" in {
            val flag = FlagTestFlags.defaultInt
            val str  = flag.toString()
            assert(str.contains("StaticFlag("))
            assert(str.contains("kyo.FlagTestFlags.defaultInt"))
            assert(str.contains("42"))
            assert(str.contains("source="))
        }

        "seq reader" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.seqFlag", "1,2,3")
            try {
                val flag = FlagTestFlags.seqFlag
                assert(flag.value == Seq(1, 2, 3))
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.seqFlag"): Unit
            }
        }

        "boolean reader" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.boolFlag", "true")
            try {
                val flag = FlagTestFlags.boolFlag
                assert(flag.value == true)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.boolFlag"): Unit
            }
        }

        "string reader" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.stringFlag", "hello world")
            try {
                val flag = FlagTestFlags.stringFlag
                assert(flag.value == "hello world")
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.stringFlag"): Unit
            }
        }

        "long reader" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.longFlag", "9999999999")
            try {
                val flag = FlagTestFlags.longFlag
                assert(flag.value == 9999999999L)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.longFlag"): Unit
            }
        }

        "double reader" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.doubleFlag", "3.14")
            try {
                val flag = FlagTestFlags.doubleFlag
                assert(flag.value == 3.14)
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.doubleFlag"): Unit
            }
        }

        "validate function that throws is caught and wrapped" in {
            val cause = interceptInitError {
                FlagTestFlags.validateThrows
            }
            assert(cause.isInstanceOf[FlagValidationFailedException])
            val vfe = cause.asInstanceOf[FlagValidationFailedException]
            assert(vfe.cause.isInstanceOf[RuntimeException])
            assert(vfe.cause.getMessage == "validator exploded")
        }

        "validation failure includes flag name" in {
            java.lang.System.setProperty("kyo.FlagTestFlags.validateFail", "0")
            try {
                val cause = interceptInitError {
                    FlagTestFlags.validateFail
                }
                assert(cause.getMessage.contains("kyo.FlagTestFlags.validateFail"))
                assert(cause.getMessage.contains("validation failed"))
            } finally {
                java.lang.System.clearProperty("kyo.FlagTestFlags.validateFail"): Unit
            }
        }
    }

}

object FlagTestFlags {
    object defaultInt extends StaticFlag[Int](42)
    object sysPropInt extends StaticFlag[Int](0)

    object validateIdentity extends StaticFlag[Int](10, (a: Int) => Right(Math.max(1, a)))
    object validateClamp    extends StaticFlag[Int](10, (a: Int) => Right(Math.max(1, a)))

    object parseError extends StaticFlag[Int](0)

    object rolloutExpr    extends StaticFlag[Int](0)
    object rolloutNoMatch extends StaticFlag[Int](7)
    object rolloutError   extends StaticFlag[Int](0)

    object applyTest extends StaticFlag[String]("test")

    object seqFlag    extends StaticFlag[Seq[Int]](Seq.empty)
    object boolFlag   extends StaticFlag[Boolean](false)
    object stringFlag extends StaticFlag[String]("")
    object longFlag   extends StaticFlag[Long](0L)
    object doubleFlag extends StaticFlag[Double](0.0)

    object validateThrows extends StaticFlag[Int](
            10,
            (_: Int) => throw new RuntimeException("validator exploded")
        )

    object validateFail extends StaticFlag[Int](
            10,
            (a: Int) =>
                if (a > 0) Right(a)
                else Left(new IllegalArgumentException("must be positive"))
        )
}
