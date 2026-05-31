package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.internal.tasty.symbol.OnceCell

class OnceCellTest extends Test:

    // Test 2 (INV-009): first call returns the init result.
    "OnceCellTest INV-009: first call returns init result" in {
        import AllowUnsafe.embrace.danger
        val cell = OnceCell.init[Int](() => 42)
        assert(cell.get() == 42)
    }

    // Test 3 (INV-009): subsequent calls return the cached value without re-running init.
    "OnceCellTest INV-009: subsequent calls return cached value without re-running init" in {
        import AllowUnsafe.embrace.danger
        val counter = new AtomicInteger(0)
        val cell = OnceCell.init[Int](() =>
            counter.incrementAndGet()
            7
        )
        var idx      = 0
        var allSeven = true
        while idx < 10 do
            if cell.get() != 7 then allSeven = false
            idx += 1
        end while
        assert(allSeven, "Not all results were 7")
        assert(counter.get() == 1, s"init ran ${counter.get()} times, expected 1")
    }

    // Test 4 (INV-009, C2): debug mode detection flag is readable and correctly reflects the system property.
    "OnceCellTest INV-009 C2: debugIdempotent flag reflects system property default" in {
        // By default (no system property set), debug mode is off.
        // This test is deterministic: the flag is read once at class-load time.
        val expected = java.lang.System.getProperty("kyo.tasty.OnceCell.debug", "false").equalsIgnoreCase("true")
        assert(OnceCell.debugIdempotent == expected)
    }

    // Test 5 (INV-009, C2): concurrent race - all fibers see the same winner value.
    // Uses kyo.Async.foreach so the test compiles and runs on JVM, JS, and Native.
    "OnceCellTest INV-009 C2: concurrent callers all receive the same cached value" in run {
        // Construct cell inside Sync.Unsafe.defer boundary (OnceCell.init requires AllowUnsafe).
        Sync.Unsafe.defer(OnceCell.init[Int](() => 99)).map { cell =>
            Async.foreach(1 to 8, concurrency = 8) { _ =>
                Sync.Unsafe.defer(cell.get())
            }.map { results =>
                assert(results.size == 8, s"Expected 8 results, got ${results.size}")
                assert(results.forall(_ == 99), s"Not all fibers received 99: $results")
            }
        }
    }

    // Test 6 (INV-009, C2): when debug mode is active and init is non-idempotent,
    // a CAS-losing fiber that computed a different value throws IllegalStateException.
    // When debug mode is off, all fibers gracefully return the same cached AnyRef.
    // Uses kyo.Async.foreach so the test compiles and runs on JVM, JS, and Native.
    // Always collects per-fiber Result[IllegalStateException, AnyRef] so both branches
    // have a uniform effect row and Scala can unify the if/else arms.
    "OnceCellTest INV-009 C2: debug mode throws IllegalStateException for non-idempotent init" in run {
        // Construct cell inside Sync.Unsafe.defer boundary (OnceCell.init requires AllowUnsafe).
        Sync.Unsafe.defer(OnceCell.init[AnyRef](() => new AnyRef())).map { cell =>
            Async.foreach(1 to 8, concurrency = 8) { _ =>
                Abort.run[IllegalStateException] {
                    Abort.catching[IllegalStateException] {
                        Sync.Unsafe.defer(cell.get())
                    }
                }
            }.map { (results: Chunk[Result[IllegalStateException, AnyRef]]) =>
                assert(results.size == 8, s"Expected 8 results, got ${results.size}")
                if OnceCell.debugIdempotent then
                    val failures = results.filter(_.isFailure)
                    val allViolations = failures.nonEmpty &&
                        failures.forall { r =>
                            r.failure.fold(false)(_.getMessage.contains("idempotence violated"))
                        }
                    assert(
                        allViolations,
                        s"Expected at least one idempotence-violated ISE in debug mode; got: $failures"
                    )
                else
                    // Debug mode off: no exception thrown even for non-idempotent init (graceful discard).
                    val successes = results.filter(_.isSuccess)
                    assert(successes.size == 8, s"Expected 8 successful results, got ${successes.size}")
                    val values: Chunk[AnyRef] = successes.map(_.getOrThrow)
                    val first                 = values.head
                    assert(
                        values.forall(v => v eq first),
                        "All fibers should return the same cached AnyRef instance"
                    )
                end if
            }
        }
    }

    // Test 8 (T7, INV-009): 64-fiber concurrent first-call all receive the same cached Long.
    // OnceCell[Long] wraps System.nanoTime(). All 64 fibers race to call get(); exactly one
    // runs the init lambda, the rest receive the cached result. All returned Longs must be equal.
    // Uses kyo.Async.foreach so the test compiles and runs on JVM, JS, and Native.
    "OnceCellTest T7 INV-009: 64-fiber concurrent first-call all receive the same cached Long" in run {
        // Construct cell inside Sync.Unsafe.defer boundary (OnceCell.init requires AllowUnsafe).
        Sync.Unsafe.defer(OnceCell.init[Long](() => java.lang.System.nanoTime())).map { cell =>
            Async.foreach(1 to 64, concurrency = 64) { _ =>
                Sync.Unsafe.defer(cell.get())
            }.map { results =>
                assert(results.size == 64, s"Expected 64 results, got ${results.size}")
                val first    = results(0)
                var allEqual = true
                var idx      = 1
                while idx < results.size && allEqual do
                    if results(idx) != first then allEqual = false
                    idx += 1
                end while
                assert(allEqual, s"Not all 64 fibers received the same cached Long; first=$first, results=$results")
            }
        }
    }

    // Test 7 (T2, OnceCell): cell holding a null reference returns null on first and second get.
    // The plan draft used null.asInstanceOf[String] which is banned (no-casts rule). Instead we
    // use OnceCell[String | Null] whose init lambda returns null directly; Scala 3 treats null as
    // a valid inhabitant of String | Null, so no cast is required.
    // Given: OnceCell[String | Null](() => null) with AllowUnsafe.
    // When: cell.get() called twice.
    // Then: first call returns null; second call returns null (cached, init not re-run).
    // Pins: T2.
    "OnceCellTest T2: cell with null init returns null on first and subsequent get" in {
        import AllowUnsafe.embrace.danger
        val cell: OnceCell[String | Null] = OnceCell.init[String | Null](() => null)
        val first                         = cell.get()
        val second                        = cell.get()
        assert(first == null, s"Expected null on first get but got $first")
        assert(second == null, s"Expected null on second get but got $second")
    }

end OnceCellTest
