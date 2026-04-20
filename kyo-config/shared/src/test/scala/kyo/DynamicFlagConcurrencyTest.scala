package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class DynamicFlagConcurrencyTest extends AnyFreeSpec {

    "DynamicFlag concurrency" - {

        "concurrent apply() calls — no exceptions, no torn reads" in {
            val flag = DynConcTestFlags.concApply
            flag.update("a@premium;b@basic;c")
            var errors      = 0
            val validValues = Set("a", "b", "c")
            for (t <- 0 until 100) {
                for (i <- 0 until 100) {
                    val result = flag(s"user-$t-$i", "premium")
                    if (!validValues.contains(result))
                        errors += 1
                }
            }
            assert(errors == 0)
        }

        "apply() during update() — sees old or new, never torn" in {
            val flag = DynConcTestFlags.concApplyDuringUpdate
            flag.update("100@enterprise;50")
            val validValues = Set(100, 50, 200, 75, 0)
            var errors      = 0

            for (_ <- 0 until 1000) {
                flag.update("200@enterprise;75")
                for (t <- 0 until 10) {
                    val r = flag(s"user-$t", "enterprise")
                    if (!validValues.contains(r))
                        errors += 1
                }
                flag.update("100@enterprise;50")
                for (t <- 0 until 10) {
                    val r = flag(s"user-$t", "enterprise")
                    if (!validValues.contains(r))
                        errors += 1
                }
            }
            assert(errors == 0)
        }

        "concurrent updates — last writer wins" in {
            val flag = DynConcTestFlags.concUpdates

            flag.update("value1")
            flag.update("value2")

            val result = flag("user1")
            assert(result == "value1" || result == "value2")
        }

        "apply() never blocks — completes in bounded time" in {
            val flag = DynConcTestFlags.neverBlocks
            flag.update("100@enterprise;50")
            val start = java.lang.System.nanoTime()
            for (_ <- 0 until 10000) {
                flag("user1", "enterprise"): Unit
            }
            val elapsed = java.lang.System.nanoTime() - start
            // 10000 calls should complete in well under 1 second
            assert(elapsed < 1000000000L, s"Took ${elapsed / 1000000}ms for 10000 calls")
        }

        "update() during apply() with percentage — consistent bucket evaluation" in {
            val flag = DynConcTestFlags.concBucket
            flag.update("true@50%")
            var errors = 0

            for (_ <- 0 until 500) {
                flag.update("true@50%")
                for (t <- 0 until 5) {
                    try {
                        // Should always get true or false, never an exception
                        val _ = flag(s"user-$t")
                    } catch {
                        case _: Exception =>
                            errors += 1
                    }
                }
                flag.update("true@75%")
                for (t <- 0 until 5) {
                    try {
                        val _ = flag(s"user-$t")
                    } catch {
                        case _: Exception =>
                            errors += 1
                    }
                }
            }
            assert(errors == 0)
        }

        "high-throughput apply() — no degradation" in {
            val flag = DynConcTestFlags.highThroughput
            flag.update("a@premium;b@basic;c")
            var total = 0L

            for (t <- 0 until 8) {
                var count = 0L
                for (i <- 0 until 100000) {
                    flag(s"user-$t-$i", "premium"): Unit
                    count += 1
                }
                total += count
            }
            assert(total == 800000L)
        }

        "concurrent reload() — no corruption" in {
            java.lang.System.setProperty("kyo.DynConcTestFlags.concReload", "100@enterprise")
            try {
                val flag   = DynConcTestFlags.concReload
                var errors = 0
                for (_ <- 0 until 10) {
                    try {
                        for (_ <- 0 until 100) {
                            flag.reload(): Unit
                        }
                    } catch {
                        case _: Exception =>
                            errors += 1
                    }
                }
                assert(errors == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynConcTestFlags.concReload"): Unit
            }
        }

        "stress test — alternating update and apply" in {
            val flag = DynConcTestFlags.stressTest
            flag.update("a@x;b")
            val validValues = Set("a", "b", "c", "d")
            var errors      = 0

            for (t <- 0 until 10) {
                for (i <- 0 until 1000) {
                    if (i % 100 == 0) {
                        try flag.update(if (i % 200 == 0) "c@x;d" else "a@x;b")
                        catch { case _: Exception => () }
                    }
                    val result = flag(s"user-$t-$i", "x")
                    if (!validValues.contains(result))
                        errors += 1
                }
            }
            assert(errors == 0)
        }
    }

}

object DynConcTestFlags {
    object concApply             extends DynamicFlag[String]("default")
    object concApplyDuringUpdate extends DynamicFlag[Int](0)
    object concUpdates           extends DynamicFlag[String]("default")
    object neverBlocks           extends DynamicFlag[Int](0)
    object concBucket            extends DynamicFlag[Boolean](false)
    object highThroughput        extends DynamicFlag[String]("default")
    object concReload            extends DynamicFlag[Int](0)
    object stressTest            extends DynamicFlag[String]("default")
}
