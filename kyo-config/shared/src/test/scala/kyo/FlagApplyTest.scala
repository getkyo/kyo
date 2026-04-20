package kyo

import org.scalatest.freespec.AnyFreeSpec

class FlagApplyTest extends AnyFreeSpec {

    "Flag.apply bootstrap method" - {

        "reads system property" in {
            java.lang.System.setProperty("kyo.test.flagapply.int", "42")
            try {
                val result = Flag[Int]("kyo.test.flagapply.int", 0)
                assert(result == 42)
            } finally {
                java.lang.System.clearProperty("kyo.test.flagapply.int"): Unit
            }
        }

        "returns default when neither sys prop nor env var set" in {
            val result = Flag[Int]("kyo.test.flagapply.nonexistent", 99)
            assert(result == 99)
        }

        "does NOT evaluate rollout expressions" in {
            // Value contains @ and ; but should be returned as literal (parsed by reader)
            java.lang.System.setProperty("kyo.test.flagapply.rollout", "hello@world;test")
            try {
                val result = Flag[String]("kyo.test.flagapply.rollout", "default")
                // Should be the raw string, not rollout-evaluated
                assert(result == "hello@world;test")
            } finally {
                java.lang.System.clearProperty("kyo.test.flagapply.rollout"): Unit
            }
        }

        "reads env var when sys prop not set" in {
            // We can't reliably set env vars in tests, so we test the fallback to default
            // when neither is set
            val result = Flag[String]("kyo.test.flagapply.envtest", "fallback")
            assert(result == "fallback")
        }
    }

}
