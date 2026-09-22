package kyo

import org.scalatest.freespec.AnyFreeSpec

class CompileTimeFlagTest extends AnyFreeSpec:

    // Every key here is absent from the build environment, so these pin the default path. A leaf that
    // covers a key the build DOES set would have to set it on the compiler's own JVM, which is a build
    // change rather than a test one.
    "an absent key resolves to the default" - {

        "false" in {
            assert(!CompileTimeFlag.boolean("kyo.CompileTimeFlagTest.absent", false))
        }

        "true" in {
            assert(CompileTimeFlag.boolean("kyo.CompileTimeFlagTest.absent", true))
        }
    }

    // That the value is a literal, and so that a branch on it is eliminated, is not directly assertable:
    // `inline val` wants a literal constant TYPE and the macro answers at `Boolean`. What is observable is
    // that the value is fixed before the call runs.
    "resolution happens at compile time, not at the call" in {
        val key = "kyo.CompileTimeFlagTest.setAtRuntime"
        java.lang.System.setProperty(key, "true"): Unit
        try
            assert(!CompileTimeFlag.boolean("kyo.CompileTimeFlagTest.setAtRuntime", false))
        finally
            java.lang.System.clearProperty(key): Unit
        end try
    }

    "shares Flag's environment variable naming" in {
        // The macro reads the JDK directly rather than through FlagPlatform, so this rule is the only thing
        // the two resolutions have in common. If it drifts, an environment variable stops reaching the flag.
        assert(Flag.envName("kyo.CompileTimeFlagTest.absent") == "KYO_COMPILETIMEFLAGTEST_ABSENT")
        assert(Flag.envName("a.b.c") == "A_B_C")
    }
end CompileTimeFlagTest
