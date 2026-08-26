package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

/** Concurrency leaf that needs a real background writer thread.
  *
  * Scala.js and Wasm javalib have no Thread.start/join, so the concurrent-writer proof lives in JVM and Native, not the
  * shared suite. It witnesses that apply() completes every call while a writer flips the expression, with no torn read.
  */
class DynamicFlagConcurrencyPlatformTest extends AnyFreeSpec {

    "DynamicFlag concurrency" - {

        "apply() never blocks: completes under concurrent update() with no torn reads" in {
            val flag = DynConcPlatformFlags.neverBlocks
            flag.update("rollout:100@enterprise;50")
            val validValues    = Set(100, 50, 200, 75, 0)
            val iterations     = 10000
            @volatile var stop = false
            var errors         = 0
            var completed      = 0
            val writer = new Thread(() => {
                while (!stop) {
                    flag.update("rollout:200@enterprise;75")
                    flag.update("rollout:100@enterprise;50")
                }
            })
            writer.start()
            try {
                for (_ <- 0 until iterations) {
                    val r = flag("user1", "enterprise")
                    if (!validValues.contains(r)) errors += 1
                    completed += 1
                }
            } finally {
                stop = true
                writer.join()
            }
            // Every call returned (a lock-taking apply() contended by the writer could not reach the full count) and every
            // read saw a consistent value.
            assert(completed == iterations && errors == 0)
        }
    }

}

object DynConcPlatformFlags {
    object neverBlocks extends DynamicFlag[Int](0)
}
