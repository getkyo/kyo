package kyo

import kyo.Async
import kyo.millis
import kyo.seconds

/** Self-tests for kyo-test-api behaviors, exercised through the kyo-test framework itself.
  *
  * Each test case exercises an api-level behavior (assertion macros, decorators, etc.) through the new base.
  */
class TestApiSelfTest extends kyo.test.Test[Any]:

    // Power-assert instrumentation is compiled in only when KYO_TEST_POWER_ASSERT (or -Dkyo.test.powerAssert) is set (see
    // AssertMacro); the diagram-value case below gates on the same flag at runtime.
    private val powerAssertOn: Boolean =
        sys.props.get("kyo.test.powerAssert").orElse(sys.env.get("KYO_TEST_POWER_ASSERT"))
            .exists(v => Set("1", "true", "on", "yes").contains(v.trim.toLowerCase))

    "sync test passes" in {
        assert(1 + 1 == 2)
    }

    "async test passes" in {
        Async.sleep(1.millis).andThen(succeed)
    }

    val x = 42
    "for-comp setup works" in {
        assert(x == 42)
    }

    "power-assert diagram on failure" in {
        assume(powerAssertOn)
        val ex = intercept[kyo.test.AssertionFailed] {
            val n = 5
            assert(n > 10)
        }
        assert(ex.diagram.contains("5"))
    }

    "assert structural diff" in {
        val ex = intercept[kyo.test.AssertionFailed] {
            assert("actual" == "expected")
        }
        assert(ex.diagram.nonEmpty)
    }

    "intercept captures expected exception" in {
        val ex = intercept[IllegalArgumentException] {
            throw new IllegalArgumentException("boom")
        }
        assert(ex.getMessage == "boom")
    }

    "sequential asserts each evaluate" in {
        assert(1 == 1)
        assert(2 == 2)
        assert(3 == 3)
    }

    "decorators record metadata" in {
        val sb = "test".retry(3).timeout(5L.seconds).tagged("integration")
        assert(sb.retrySchedule.isDefined)
        assert(sb.timeout == Maybe(5L.seconds))
        assert(sb.tags.contains("integration"))
    }

end TestApiSelfTest
