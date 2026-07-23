package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

/** Round-robin rotation across the `next()` AtomicLong wrap boundary.
  *
  * `next()` distributes handles by `counter.getAndIncrement() % drivers.length`. Once the counter wraps past `Long.MaxValue` it becomes
  * negative, and a plain `Math.abs(idx)` cannot recover (`Math.abs(Long.MinValue)` is still `Long.MinValue`, an overflow), so it would skew the
  * index or go out of bounds exactly at the wrap. Masking the sign bit before the modulo avoids that. This seeds the pool's counter near
  * `Long.MaxValue` (via the internal `init(drivers, initialCounter)` overload) and drives `next()` straight across the
  * `Long.MaxValue -> Long.MinValue` boundary, asserting a clean `0,1,..,N-1,0,..` rotation with no skew, no out-of-bounds, no duplicate at the
  * boundary. Pure arithmetic over a seeded counter, no timing. A `Math.abs` path would throw ArrayIndexOutOfBounds or skew at the wrap.
  */
class IoDriverPoolWrapRotationTest extends Test:

    import AllowUnsafe.embrace.danger

    /** A distinct no-op `IoDriver` per slot; the test identifies the returned slot by object identity, so the driver only needs to exist. */
    final private class TagDriver extends IoDriver[Unit]:
        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit     = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit       = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                           = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                         = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                    = ()
        def close()(using AllowUnsafe, Frame): Unit                                                                      = ()
        def label: String                                                                                                = "TagDriver"
        def handleLabel(handle: Unit): String                                                                            = "tag"
    end TagDriver

    private def mkDrivers(n: Int): Array[IoDriver[Unit]] =
        Array.fill[IoDriver[Unit]](n)(new TagDriver)

    "the returned slot matches the masked-modulo contract for every counter value across the wrap" in {
        // n = 3 is not a power of two, so the wrap discriminates the masked modulo from Math.abs: at counter Long.MinValue a
        // Math.abs(Long.MinValue % 3) = Math.abs(-2) = slot 2 (a mirror that re-hits a slot just visited), while (c & MaxValue) % 3 = 0.
        val n       = 3
        val drivers = mkDrivers(n)
        // Seed a few steps before the wrap so the run crosses Long.MaxValue -> Long.MinValue.
        val seed = Long.MaxValue - 2L
        val pool = IoDriverPool.init(drivers, seed)

        // The exact contract for the k-th call is ((seed + k) & Long.MaxValue) % n. Assert the returned slot (by identity) equals it for
        // every call straddling the wrap. A Math.abs path would instead land the wrap slot (k=3, counter Long.MinValue) on the mirror (2), not 0.
        (0 until 2 * n + 4).foreach { k =>
            val c        = seed + k.toLong
            val expected = ((c & Long.MaxValue) % n).toInt
            val picked = pool.next() // capture ONCE: indexWhere re-evaluates its predicate per element, so inline next() would over-consume
            val slot   = drivers.indexWhere(_ eq picked)
            assert(slot == expected, s"call $k (counter $c): expected slot $expected, got $slot (the wrap is where Math.abs skewed)")
        }
    }

    "every returned slot stays in [0, N) across the wrap (no negative / out-of-bounds index)" in {
        // The masked modulo guarantees a non-negative, in-bounds index for every counter value on both sides of the wrap. n = 3 (not a power of
        // two) is the discriminating case: at counter Long.MinValue a (Long.MinValue % 3 = -2).toInt path would feed a NEGATIVE index into
        // drivers(Math.abs(idx)); abs masks it for n = 3, but the negative intermediate is exactly the latent out-of-bounds the masked modulo
        // removes. This asserts the invariant directly: every slot is a valid array index.
        val n       = 3
        val drivers = mkDrivers(n)
        val pool    = IoDriverPool.init(drivers, Long.MaxValue - 4L)
        (0 until 12).foreach { _ =>
            val picked = pool.next()
            val slot   = drivers.indexWhere(_ eq picked)
            assert(slot >= 0 && slot < n, s"a returned slot was out of range across the wrap: $slot")
        }
    }

    "the first next() at counter Long.MinValue lands on slot 0 (not the Math.abs mirror)" in {
        // n = 3 again so Long.MinValue % 3 = -2 discriminates: Math.abs(-2) = slot 2, (MinValue & MaxValue) % 3 = 0.
        val n       = 3
        val drivers = mkDrivers(n)
        val pool    = IoDriverPool.init(drivers, Long.MinValue)
        val picked  = pool.next()
        assert(
            drivers.indexWhere(_ eq picked) == 0,
            "the first post-wrap next() did not land on slot 0 (the Math.abs mirror returned 2)"
        )
    }

end IoDriverPoolWrapRotationTest
