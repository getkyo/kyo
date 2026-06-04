package kyo.test

import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Verifies that [[RunConfig]] derives CanEqual so that == compiles under -language:strictEquality.
  */
// ScalaTest bootstrap: this file tests RunConfig equality under strictEquality; migrating to kyo.test.Test[Future] would add a circular dependency on kyo-test-runner.
class RunConfigTest extends AnyFunSuite with NonImplicitAssertions:

    // ── Test 1: equality compiles under strictEquality without a CanEqual import ───────────────

    test("test-1: RunConfig() == RunConfig() compiles under strictEquality (derives CanEqual)") {
        // The fact that this line compiles with -language:strictEquality is the assertion.
        // If RunConfig did not derive CanEqual the compiler would reject the == call.
        val result: Boolean = RunConfig() == RunConfig()
        assert(result, "RunConfig() == RunConfig() must be true")
    }

    // ── Test 2: equal configs compare equal ──────────────────────────────────────────────────

    test("test-2: RunConfig(parallelism = 2) == RunConfig(parallelism = 2) is true") {
        assert(
            RunConfig(parallelism = 2) == RunConfig(parallelism = 2),
            "two RunConfig instances with the same parallelism must be equal"
        )
    }

    // ── Test 3: distinct configs compare unequal ──────────────────────────────────────────────

    test("test-3: RunConfig(parallelism = 2) == RunConfig(parallelism = 4) is false") {
        assert(
            !(RunConfig(parallelism = 2) == RunConfig(parallelism = 4)),
            "RunConfig instances with different parallelism must not be equal"
        )
    }

    // ── RunConfig() == RunConfig() ───────────────────────────────────────────────────────────────

    test("phase8-test-1: RunConfig() == RunConfig() is true under strictEquality (post-Phase-1 reporter field)") {
        val result: Boolean = RunConfig() == RunConfig()
        assert(result, "RunConfig() == RunConfig() must be true")
    }

    // ── Feature B: failOnNoAssertion field and copy-helper ─────────────────────────────────────

    test("failOnNoAssertion defaults to true") {
        assert(RunConfig().failOnNoAssertion == true, "RunConfig().failOnNoAssertion should be true by default")
        assert(RunConfig.default.failOnNoAssertion == true, "RunConfig.default.failOnNoAssertion should be true")
    }

    test("failOnNoAssertion(false) returns a copy with failOnNoAssertion = false") {
        val disabled = RunConfig().failOnNoAssertion(false)
        assert(disabled.failOnNoAssertion == false, "after failOnNoAssertion(false), field should be false")
    }

    test("failOnNoAssertion copy-helper changes only the failOnNoAssertion field") {
        val base     = RunConfig()
        val disabled = base.failOnNoAssertion(false)
        assert(disabled.parallelism == base.parallelism)
        assert(disabled.timeout == base.timeout)
        assert(disabled.countOnly == base.countOnly)
        assert(disabled.listOnly == base.listOnly)
    }

    test("RunConfig with failOnNoAssertion(false) == RunConfig with the same flag (derives CanEqual)") {
        val a = RunConfig().failOnNoAssertion(false)
        val b = RunConfig().failOnNoAssertion(false)
        assert(a == b, "two RunConfig instances with failOnNoAssertion=false should be equal")
    }

end RunConfigTest
