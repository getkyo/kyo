package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*

class ForeachTest extends CompatTest:

    // All backends return CIO[CChunk[A]]. The CChunk extension method
    // `.toSeq` converts to a Scala stdlib Seq[A] for portable equality checks.

    "foreach(coll)(f) returns results in order" in run {
        val c = CIO.foreach(Seq(1, 2, 3))(i => CIO.defer { i * 2 })
        c.map(out => assert(out.toSeq == Seq(2, 4, 6)))
    }

    "foreach runs concurrently (peak-concurrency canary)" in run {
        // Concurrency is overlap, not wall-clock: each task marks itself active, samples the peak, and waits at a five-way barrier, so the
        // last to arrive samples 5. A sequential run never opens the barrier and fails via CompatTest's testTimeout (a fixed hold would race the sample).
        val active = new AtomicInteger(0)
        val peak   = new AtomicInteger(0)
        CLatch.init(5).flatMap { barrier =>
            CIO.foreach(1 to 5) { _ =>
                CIO.defer {
                    val cur = active.incrementAndGet()
                    peak.updateAndGet(_ max cur)
                    ()
                }.flatMap(_ => barrier.release)
                    .flatMap(_ => barrier.await)
                    .flatMap(_ => CIO.defer { active.decrementAndGet(); 7 })
            }.map { out =>
                assert(out.size == 5 && peak.get() == 5, s"out.size=${out.size} peak=${peak.get()}")
            }
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

    "foreach with concurrency=2 on 6 items observes exactly 2 concurrent items" in run {
        // Bounded path canary: peak must be exactly 2, never more (bound holds) nor fewer (bound engaged, not sequential). The two-way barrier
        // makes "never fewer" race-free: the first two cannot leave until both arrive, so peak reaches 2; later items pass through and the bound caps them.
        val active = new AtomicInteger(0)
        val peak   = new AtomicInteger(0)
        val c = CLatch.init(2).flatMap { barrier =>
            CIO.foreach(1 to 6, 2) { _ =>
                CIO.defer {
                    val cur = active.incrementAndGet()
                    peak.updateAndGet(_ max cur)
                    ()
                }.flatMap(_ => barrier.release)
                    .flatMap(_ => barrier.await)
                    .flatMap(_ =>
                        CIO.defer {
                            active.decrementAndGet()
                            ()
                        }
                    )
            }
        }
        c.map { _ =>
            assert(peak.get() == 2, s"peak concurrency ${peak.get()} (expected exactly 2)")
        }
    }

    "foreach unbounded (default concurrency) runs all 5 concurrently" in run {
        // Unbounded path canary: the default (Int.MaxValue) branch must run all 5 in parallel, so the five-way barrier only
        // opens if all 5 are active at once. A branch admitting fewer never opens it and fails through CompatTest's testTimeout.
        val active = new AtomicInteger(0)
        val peak   = new AtomicInteger(0)
        CLatch.init(5).flatMap { barrier =>
            CIO.foreach(1 to 5, Int.MaxValue) { _ =>
                CIO.defer {
                    val cur = active.incrementAndGet()
                    peak.updateAndGet(_ max cur)
                    ()
                }.flatMap(_ => barrier.release)
                    .flatMap(_ => barrier.await)
                    .flatMap(_ => CIO.defer { active.decrementAndGet(); 7 })
            }.map { out =>
                assert(out.size == 5, s"expected 5 results, got ${out.size}")
                assert(peak.get() == 5, s"unbounded must run all 5 concurrently, peak=${peak.get()}")
            }
        }
    }

end ForeachTest
