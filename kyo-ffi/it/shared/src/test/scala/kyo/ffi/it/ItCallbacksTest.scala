package kyo.ffi.it

import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe
import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Cross-platform callback spec.
  *
  * Transient callback: `kyoItSortInts` routes a Scala three-way comparator through a `qsort`-style C helper. The comparator lives only for
  * the duration of the FFI call; each backend allocates the upcall stub on a per-call scope (JVM panama arena, Native confined zone, JS
  * koffi `pointer(proto)` slot) and releases it on return.
  *
  * Retained callback: `kyoItRegisterListener` stashes the callback C-side; `kyoItFireListener`, a separate FFI call, invokes it with the
  * supplied int. The [[Ffi.Guard]] parameter pins the callback so it outlives the register call and survives long enough for the later fire
  * call (JVM/Native pin the upcall stub on the guard's arena; JS pins a close-time `KoffiFacade.unregister` of the registered handle). An
  * [[AtomicInteger]] witnesses the observed argument; the callback writes to it from the C-side invocation.
  *
  * Multi-input transient + retained rows. Each row invokes a binding, transient sort rows cross the FFI boundary via `kyoItSortInts`;
  * retained rows via `kyoItRegisterListener` + `kyoItFireListener`.
  */
class ItCallbacksTest extends ItTestBase:

    // Transient and retained callbacks claim slots from the process-global CallbackRegistry; run this suite's leaves
    // sequentially so concurrent leaves do not collide on registry slots (which corrupts the comparator/listener a C
    // call dispatches to).
    override def config = super.config.sequential

    import AllowUnsafe.embrace.danger

    "kyoItSortInts" - {
        "transient callback: ascending sort via (a, b) => a - b" in {
            val b = Ffi.load[ItCallbacksBindings]
            Buffer.use[Int, Unit](5) { buf =>
                val xs = Array(4, 1, 5, 2, 3)
                var i  = 0
                while i < xs.length do
                    buf.set(i, xs(i))
                    i += 1
                // `a - b` is the textbook three-way comparator. The comparator is transient,                // the upcall stub is released when `kyoItSortInts` returns.
                b.kyoItSortInts(buf, xs.length, (a, b) => a - b)
                val sorted = (0 until xs.length).map(buf.get).toList
                assert(sorted == List(1, 2, 3, 4, 5))
            }
        }

        "transient callback: descending sort (comparator inversion)" in {
            val b = Ffi.load[ItCallbacksBindings]
            Buffer.use[Int, Unit](4) { buf =>
                val xs = Array(10, 30, 20, 40)
                var i  = 0
                while i < xs.length do
                    buf.set(i, xs(i))
                    i += 1
                b.kyoItSortInts(buf, xs.length, (x, y) => if x > y then -1 else if x < y then 1 else 0)
                val sorted = (0 until xs.length).map(buf.get).toList
                assert(sorted == List(40, 30, 20, 10))
            }
        }

        "table-driven: ascending sort over varied input arrays" in {
            // Each row: load arr into a fresh buffer, sort ascending via callback,
            // confirm buffer contents match arr.sorted.
            val b = Ffi.load[ItCallbacksBindings]
            val cases: Seq[Array[Int]] = Seq(
                Array(1),
                Array(2, 1),
                Array(5, 3, 8, 1, 9, 2, 7, 4, 6),
                Array(0, 0, 0, 0),
                Array(-5, -1, -3, -2, -4),
                Array(100, -100, 50, -50, 0),
                Array(7, 7, 3, 3, 5, 5)
            )
            var last: Unit = succeed
            cases.foreach { xs =>
                Buffer.use[Int, Unit](xs.length) { buf =>
                    var i = 0
                    while i < xs.length do
                        buf.set(i, xs(i))
                        i += 1
                    b.kyoItSortInts(buf, xs.length, (a, bb) => a - bb)
                    val sorted = (0 until xs.length).map(buf.get).toList
                    val r      = assert(sorted == xs.sorted.toList)
                    last = r
                    r
                }
            }
            last
        }

        "table-driven: descending sort over varied input arrays" in {
            val b = Ffi.load[ItCallbacksBindings]
            val cases: Seq[Array[Int]] = Seq(
                Array(1, 2, 3, 4, 5),
                Array(10, 20, 30),
                Array(-1, -2, -3, -4),
                Array(0, 0, 1, 1, 2, 2)
            )
            var last: Unit = succeed
            cases.foreach { xs =>
                Buffer.use[Int, Unit](xs.length) { buf =>
                    var i = 0
                    while i < xs.length do
                        buf.set(i, xs(i))
                        i += 1
                    b.kyoItSortInts(buf, xs.length, (x, y) => if x > y then -1 else if x < y then 1 else 0)
                    val sorted = (0 until xs.length).map(buf.get).toList
                    val r      = assert(sorted == xs.sorted.toList.reverse)
                    last = r
                    r
                }
            }
            last
        }

        "already-sorted array is a no-op under ascending sort" in {
            val b = Ffi.load[ItCallbacksBindings]
            Buffer.use[Int, Unit](6) { buf =>
                val xs = Array(1, 2, 3, 4, 5, 6)
                var i  = 0
                while i < xs.length do
                    buf.set(i, xs(i))
                    i += 1
                b.kyoItSortInts(buf, xs.length, (a, bb) => a - bb)
                val sorted = (0 until xs.length).map(buf.get).toList
                assert(sorted == xs.toList)
            }
        }
    }

    "kyoItRegisterListener + kyoItFireListener" - {
        "retained callback: guard keeps callback live across two FFI calls" in {
            val b        = Ffi.load[ItCallbacksBindings]
            val observed = new AtomicInteger(-1)
            Ffi.Guard.use[Unit] { g =>
                // The register call pins the callback on `g`; without retention it would be
                // freed on return and the subsequent fire call would crash or observe garbage.
                b.kyoItRegisterListener((x: Int) => observed.set(x), g)
                b.kyoItFireListener(42)
                assert(observed.get() == 42)
            }
        }

        "table-driven: retained listener observes varied fire values" in {
            // Register once, fire several times, every fire crosses the FFI
            // boundary and invokes the pinned callback. The AtomicInteger
            // witnesses each fire value in turn.
            val b        = Ffi.load[ItCallbacksBindings]
            val observed = new AtomicInteger(0)
            Ffi.Guard.use[Unit] { g =>
                b.kyoItRegisterListener((x: Int) => observed.set(x), g)
                val fireValues = Seq(0, 1, -1, 42, 100, -500, 12345, Int.MaxValue, Int.MinValue, 7)
                var last: Unit = succeed
                fireValues.foreach { v =>
                    b.kyoItFireListener(v)
                    last = assert(observed.get() == v)
                }
                last
            }
        }

        "retained callback: re-registering replaces the prior listener" in {
            // The C side stores the listener in a single global slot, so a
            // second register overwrites. Both registers must be pinned by a
            // single guard scope; otherwise the first closure would be freed
            // before the second register (backend-dependent UB on JS/Native).
            val b         = Ffi.load[ItCallbacksBindings]
            val firstObs  = new AtomicInteger(0)
            val secondObs = new AtomicInteger(0)
            Ffi.Guard.use[Unit] { g =>
                b.kyoItRegisterListener((x: Int) => firstObs.set(x), g)
                b.kyoItRegisterListener((x: Int) => secondObs.set(x), g)
                b.kyoItFireListener(77)
                // Only the most recent listener should observe the fire.
                assert(firstObs.get() == 0)
                assert(secondObs.get() == 77)
            }
        }

        "retained callback: callback receives summing accumulator across fires" in {
            // An accumulator-style closure proves each fire invokes the
            // Scala-side callback with its own argument (not some cached
            // snapshot). The observed running total confirms all three fires
            // crossed the FFI boundary.
            val b     = Ffi.load[ItCallbacksBindings]
            val total = new AtomicInteger(0)
            Ffi.Guard.use[Unit] { g =>
                b.kyoItRegisterListener(
                    (x: Int) =>
                        discard(total.addAndGet(x)),
                    g
                )
                b.kyoItFireListener(1)
                b.kyoItFireListener(2)
                b.kyoItFireListener(3)
                assert(total.get() == 6)
            }
        }
    }
end ItCallbacksTest
