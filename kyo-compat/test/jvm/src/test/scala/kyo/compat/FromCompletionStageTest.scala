package kyo.compat

import java.util.concurrent.CompletableFuture
import kyo.compat.*
import scala.util.Failure
import scala.util.Success

/** JVM-only scenarios for `CIO.fromCompletionStage`.
  *
  * `java.util.concurrent.CompletionStage` is not available on Scala.js or Scala-native, so this trait lives in the JVM-only fixtures source
  * set. Per-backend Suites add this trait only when running on the JVM.
  */
class FromCompletionStageTest extends CompatTest:

    "fcs_completed_returns_value" in run {
        val cs = CompletableFuture.completedFuture(42)
        val c  = CIO.fromCompletionStage(cs)
        c.liftToTry.map {
            case Success(42) => succeed
            case other       => fail(s"expected Success(42), got: $other")
        }
    }

    "fcs_failure_propagates" in run {
        val cs = new CompletableFuture[Int]
        cs.completeExceptionally(new RuntimeException("cs-boom"))
        val c = CIO.fromCompletionStage(cs)
        c.liftToTry.map {
            case Failure(t: Throwable) =>
                // The CompletionStage may wrap the exception in a CompletionException;
                // unwrap one level if needed.
                val msg = if t.getMessage == "cs-boom" then t.getMessage
                else if t.getCause != null && t.getCause.getMessage == "cs-boom" then t.getCause.getMessage
                else t.getMessage
                assert(msg == "cs-boom", s"expected cs-boom (or wrapped), got: $t")
            case other => fail(s"expected Failure(Throwable), got: $other")
        }
    }

    "fcs_pending_suspends_until_complete" in run {
        val cs = new CompletableFuture[Int]
        // Complete the CS asynchronously after a short delay.
        val t = new Thread(() =>
            Thread.sleep(50)
            val _ = cs.complete(7)
        )
        t.setDaemon(true)
        t.start()
        val c = CIO.fromCompletionStage(cs)
        c.liftToTry.map {
            case Success(7) => succeed
            case other      => fail(s"expected Success(7), got: $other")
        }
    }

end FromCompletionStageTest
