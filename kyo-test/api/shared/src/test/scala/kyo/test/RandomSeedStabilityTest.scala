package kyo.test

import kyo.Chunk
import kyo.test.internal.TestContext
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

/** Tests for `val randomSeed` stability.
  *
  * Verifies that:
  *   - Two reads of `randomSeed` on the same suite instance return the same Long (val, not def)
  *   - A subclass overriding `val randomSeed = 42L` consistently returns 42L
  *
  * `LeafExecution.observedPath` type verification (`Chunk[String]`) is tested in `kyo-test-runner` (requires runner classes).
  */
// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class RandomSeedStabilityTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // Set the next registration context, as the runner does, so the next base's constructor (which takes it from a thread-local) can be
    // instantiated outside the runner.
    private def installContexts(): Unit =
        TestContext.setForInstantiation(new TestContext(Chunk(0)))

    // Test 13: two reads of randomSeed on the same instance yield the same Long
    "randomSeed: two reads on same instance return same Long" in {
        installContexts()
        val suite = new SeedReader
        val seed1 = suite.readRandomSeed
        val seed2 = suite.readRandomSeed
        assert(seed1 == seed2, s"randomSeed was not stable: first=$seed1 second=$seed2")
        succeed
    }

    // Test 14: override val randomSeed = 42L returns 42L
    "randomSeed: override val randomSeed = 42L returns 42L" in {
        installContexts()
        val suite = new FixedSeedReader
        assert(suite.readRandomSeed == 42L, s"expected 42L but got ${suite.readRandomSeed}")
        succeed
    }

end RandomSeedStabilityTest

/** Top-level helper: exposes randomSeed for reading without running any leaves. */
class SeedReader extends kyo.test.Test[Any]:
    def readRandomSeed: Long = randomSeed
end SeedReader

/** Top-level helper: overrides randomSeed with a fixed value. */
class FixedSeedReader extends kyo.test.Test[Any]:
    override protected val randomSeed: Long = 42L
    def readRandomSeed: Long                = randomSeed
end FixedSeedReader
