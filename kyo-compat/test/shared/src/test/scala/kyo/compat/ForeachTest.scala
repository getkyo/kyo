package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*

class ForeachTest extends CompatTest:

    // All backends return CIO[CChunk[A]]. The CChunk extension method
    // `.toSeq` converts to a Scala stdlib Seq[A] for portable equality checks.

    "foreach(coll)(f) returns results in order" in run {
        val c = CIO.foreach(Seq(1, 2, 3))(i => CIO.defer { i * 2 })
        c.map(out => assert(out.toSeq == Seq(2, 4, 6)))
    }

    "foreach runs concurrently (timing canary)" in run {
        // parallel 5×100ms ≈ 110ms; sequential = ~500ms.
        val start = java.lang.System.nanoTime()
        CIO.foreach(1 to 5)(_ => CIO.delay(100.millis)(CIO.defer { 7 })).map { out =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(out.size == 5 && elapsed < 500L, s"out.size=${out.size} elapsed=$elapsed ms")
        }
    }

    "foreachIndexed includes index in f" in run {
        val c = CIO.foreachIndexed(Seq("a", "b", "c"))((i, s) => CIO.defer { s"$i:$s" })
        c.map(out => assert(out.toSeq == Seq("0:a", "1:b", "2:c")))
    }

    "foreachDiscard returns Unit and runs all" in run {
        val ctr = new AtomicInteger(0)
        val c   = CIO.foreachDiscard(1 to 5)(_ => CIO.defer { val _ = ctr.incrementAndGet() })
        c.map(r => assert(r == ((): Unit) && ctr.get == 5))
    }

    "filter(coll)(p) keeps elements where p is true" in run {
        val c = CIO.filter(1 to 10)(i => CIO.defer { i % 2 == 0 })
        c.map(out => assert(out.toSeq == Seq(2, 4, 6, 8, 10)))
    }

    "collectAll(coll) sequences" in run {
        val c = CIO.collectAll(Seq(CIO.defer { 1 }, CIO.defer { 2 }))
        c.map(out => assert(out.toSeq == Seq(1, 2)))
    }

    "collectAllDiscard returns Unit and runs all" in run {
        val ctr = new AtomicInteger(0)
        val c = CIO.collectAllDiscard(Seq(
            CIO.defer { val _ = ctr.incrementAndGet() },
            CIO.defer { val _ = ctr.incrementAndGet() }
        ))
        c.map(r => assert(r == ((): Unit) && ctr.get == 2))
    }
    "foreach with empty collection returns empty Chunk" in run {
        // Empty input must produce an empty CChunk, not an error.
        val c = CIO.foreach(Vector.empty[Int])(i => CIO.value(i * 2))
        c.map { result =>
            assert(result.isEmpty == true, s"expected isEmpty=true, got: $result")
        }
    }

    "foreachIndexed with empty collection returns empty Chunk" in run {
        // Empty input with foreachIndexed must also return an empty CChunk.
        val c = CIO.foreachIndexed(Vector.empty[Int])((idx, i) => CIO.value(s"$idx:$i"))
        c.map { result =>
            assert(result.isEmpty == true, s"expected isEmpty=true, got: $result")
        }
    }

    "foreachDiscard with empty collection returns Unit immediately" in run {
        // Empty input returns Unit without running any effect.
        val c = CIO.foreachDiscard(Vector.empty[Int])(_ => CIO.value(0))
        c.map(r => assert(r == ((): Unit)))
    }

    "filter with empty collection returns empty Chunk" in run {
        // Empty input to filter yields an empty CChunk.
        val c = CIO.filter(Vector.empty[Int])(_ => CIO.value(true))
        c.map { result =>
            assert(result.isEmpty == true, s"expected isEmpty=true, got: $result")
        }
    }

    "collectAll with empty collection returns empty Chunk" in run {
        // Empty sequence of CIOs to collectAll yields an empty CChunk.
        val c = CIO.collectAll(Vector.empty[CIO[Int]])
        c.map { result =>
            assert(result.isEmpty == true, s"expected isEmpty=true, got: $result")
        }
    }

    "collectAllDiscard with empty collection returns Unit" in run {
        // Empty sequence of CIOs to collectAllDiscard resolves to Unit.
        val c = CIO.collectAllDiscard(Vector.empty[CIO[Any]])
        c.map(r => assert(r == ((): Unit)))
    }

    "foreach with one element failing propagates failure" in run {
        // When element 3 fails, foreach must propagate that failure.
        val c = CIO.foreach(1 to 5)(i =>
            if i == 3 then CIO.fail(TestError("at3"))
            else CIO.value(i)
        )
        c.liftToTry.map {
            case scala.util.Failure(_) => succeed
            case other                 => fail(s"expected Failure, got: $other")
        }
    }

    "collectAll with one element failing propagates failure" in run {
        // When element at index 2 fails, collectAll must propagate that failure.
        val c = CIO.collectAll((1 to 5).map(i =>
            if i == 3 then CIO.fail(TestError("at3"))
            else CIO.value(i)
        ))
        c.liftToTry.map {
            case scala.util.Failure(_) => succeed
            case other                 => fail(s"expected Failure, got: $other")
        }
    }

    "foreachIndexed with one element failing propagates failure" in run {
        // When the element at index 2 fails, foreachIndexed must propagate that failure.
        val c = CIO.foreachIndexed(Seq("a", "b", "c")) { (i, s) =>
            if i == 2 then CIO.fail(TestError(s"at $i"))
            else CIO.value(s"$i:$s")
        }
        c.liftToTry.map {
            case scala.util.Failure(_) => succeed
            case other                 => fail(s"expected Failure, got: $other")
        }
    }

    "foreachDiscard with one element failing propagates failure" in run {
        // When one element's effect fails, foreachDiscard must propagate that failure.
        val c = CIO.foreachDiscard(1 to 5)(i =>
            if i == 3 then CIO.fail(TestError("at3"))
            else CIO.unit
        )
        c.liftToTry.map {
            case scala.util.Failure(_) => succeed
            case other                 => fail(s"expected Failure, got: $other")
        }
    }

    "filter with one predicate failing propagates failure" in run {
        // When the predicate throws for one element, filter must propagate that failure.
        val c = CIO.filter(1 to 5)(i =>
            if i == 3 then CIO.fail(TestError("at3"))
            else CIO.value(i % 2 == 0)
        )
        c.liftToTry.map {
            case scala.util.Failure(_) => succeed
            case other                 => fail(s"expected Failure, got: $other")
        }
    }

    "collectAllDiscard with one element failing propagates failure" in run {
        // When one CIO fails, collectAllDiscard must propagate that failure.
        val c = CIO.collectAllDiscard((1 to 5).map(i =>
            if i == 3 then CIO.fail(TestError("at3"))
            else CIO.unit
        ))
        c.liftToTry.map {
            case scala.util.Failure(_) => succeed
            case other                 => fail(s"expected Failure, got: $other")
        }
    }

    "foreach with concurrency=2 on 6 items observes max 2 concurrent items" in run {
        // Bounded path canary: peak concurrent invocations must not exceed 2.
        val active = new AtomicInteger(0)
        val peak   = new AtomicInteger(0)
        val start  = java.lang.System.nanoTime()
        val c = CIO.foreach(1 to 6, 2) { _ =>
            CIO.defer {
                val cur = active.incrementAndGet()
                peak.updateAndGet(_ max cur)
                ()
            }.flatMap { _ =>
                CIO.delay(50.millis)(CIO.defer {
                    active.decrementAndGet()
                    ()
                })
            }
        }
        c.map { _ =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(peak.get() <= 2, s"peak concurrency ${peak.get()} exceeded bound of 2")
            assert(elapsed >= 150L, s"elapsed ${elapsed}ms less than 150ms (3 sequential batches of 2 × 50ms)")
        }
    }

    "foreach unbounded (default concurrency) completes 5 x 100ms in < 500ms" in run {
        // Unbounded path explicit canary: the default (Int.MaxValue) branch must
        // run all 5 tasks in parallel so total elapsed is well under 500ms.
        val start = java.lang.System.nanoTime()
        val c     = CIO.foreach(1 to 5, Int.MaxValue)(_ => CIO.delay(100.millis)(CIO.defer { 7 }))
        c.map { out =>
            val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
            assert(out.size == 5, s"expected 5 results, got ${out.size}")
            assert(elapsed < 500L, s"elapsed ${elapsed}ms >= 500ms (unbounded must complete in ~100ms)")
        }
    }

end ForeachTest
