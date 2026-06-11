package kyo

import kyo.Async
import kyo.millis
import kyo.seconds

/** Self-tests for kyo-test-api behaviors, exercised through the kyo-test framework itself.
  *
  * Each test case exercises an api-level behavior (assertion macros, decorators, etc.) through the new base.
  */
class TestApiSelfTest extends kyo.test.Test[Any]:

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
