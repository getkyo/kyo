package kyo.ffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.discard

/** Tests that [[kyo.ffi.internal.GuardRegistry]] maintains a correct [[kyo.ffi.internal.GuardRegistry.size]] under concurrent open/close.
  *
  * Many threads race to open and immediately close guards. After all threads finish, the registry size must equal the size before the test
  * started, verifying that every open is matched by exactly one registry decrement.
  */
class GuardRegistryConcurrentTest extends Test:

    // Reads and asserts on the process-global GuardRegistry.size, so it must run alone: under the default parallel
    // execution other leaves opening and closing guards mutate that same global count, and the size == before checks
    // then observe their churn rather than this test's own.
    override def config = super.config.sequential

    "concurrent open/close maintains correct size" in {
        val threadCount  = 16
        val opsPerThread = 100
        val executor     = Executors.newFixedThreadPool(threadCount)
        val startLatch   = new CountDownLatch(1)
        val doneLatch    = new CountDownLatch(threadCount)
        val errors       = new AtomicReference[Throwable](null)

        val before = kyo.ffi.internal.GuardRegistry.size

        var t = 0
        while t < threadCount do
            executor.submit(new Runnable:
                def run(): Unit =
                    try
                        startLatch.await(5, TimeUnit.SECONDS)
                        var i = 0
                        while i < opsPerThread do
                            val g = Ffi.Guard.open()
                            g.close()
                            i += 1
                        end while
                    catch
                        case th: Throwable => discard(errors.compareAndSet(null, th))
                    finally
                        doneLatch.countDown())
            t += 1
        end while

        startLatch.countDown()
        assert(doneLatch.await(30, TimeUnit.SECONDS) == true)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val err = errors.get()
        if err != null then fail(s"Thread threw unexpected exception: $err")

        assert(kyo.ffi.internal.GuardRegistry.size == before)
    }

    "concurrent open without close inflates the registry; subsequent close deflates it" in {
        val n      = 32
        val guards = new Array[Ffi.Guard](n)

        val before = kyo.ffi.internal.GuardRegistry.size

        // Open all guards concurrently.
        val openLatch = new CountDownLatch(n)
        val executor  = Executors.newFixedThreadPool(n)
        val errors    = new AtomicReference[Throwable](null)

        var i = 0
        while i < n do
            val idx = i
            executor.submit(new Runnable:
                def run(): Unit =
                    try guards(idx) = Ffi.Guard.open()
                    catch
                        case th: Throwable =>
                            discard(errors.compareAndSet(null, th))
                    finally openLatch.countDown())
            i += 1
        end while

        assert(openLatch.await(10, TimeUnit.SECONDS) == true)
        executor.shutdown()

        val errOpen = errors.get()
        if errOpen != null then fail(s"Open thread threw: $errOpen")

        // All n guards must be registered.
        assert((kyo.ffi.internal.GuardRegistry.size - before) == n)

        // Close them all.
        guards.foreach(_.close())
        assert(kyo.ffi.internal.GuardRegistry.size == before)
    }
end GuardRegistryConcurrentTest
