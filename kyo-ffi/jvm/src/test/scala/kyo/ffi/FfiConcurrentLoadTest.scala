package kyo.ffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.discard

/** JVM-only concurrency test: [[Ffi.load]] and [[Ffi.unload]] under concurrent access (Phase R7 D12).
  *
  * `Ffi.load[T]` uses a [[java.util.concurrent.ConcurrentHashMap]] keyed by the trait's [[Class]]; `computeIfAbsent` ensures that the
  * instantiation closure runs at most once per key. This spec verifies:
  *   1. Concurrent `load[T]` from many threads always returns the same singleton, `computeIfAbsent` must not create two instances.
  *   2. Interleaved `unload[T]` + `load[T]` never produces a null or raises an unchecked exception, the only observable outcomes are a
  *      valid instance from before the eviction or a fresh valid instance after re-instantiation.
  *   3. `load[T]` concurrent with `load[OtherT]` does not corrupt either cache entry.
  */
class FfiConcurrentLoadTest extends Test:

    "Ffi.load, concurrent access (D12)" - {

        "all threads obtain the same singleton when no unload intervenes" in {
            val concurrency = 16
            Ffi.unload[FfiConcurrentLoadTest.StableBinding]
            val barrier = new CyclicBarrier(concurrency)
            val done    = new CountDownLatch(concurrency)
            val results = new Array[AnyRef](concurrency)
            val errors  = new AtomicReference[Throwable](null)

            var t = 0
            while t < concurrency do
                val idx = t
                val r: Runnable = () =>
                    try
                        barrier.await(5, TimeUnit.SECONDS)
                        results(idx) = Ffi.load[FfiConcurrentLoadTest.StableBinding]
                    catch
                        case th: Throwable => discard(errors.compareAndSet(null, th))
                    finally
                        done.countDown()
                val thread = new Thread(r, s"concurrent-load-$idx")
                thread.setDaemon(true)
                thread.start()
                t += 1
            end while

            assert(done.await(10, TimeUnit.SECONDS) == true)

            val err = errors.get()
            if err != null then fail(s"Unexpected exception from concurrent load(): $err")

            // All threads must have received the same (non-null) instance.
            val first = results(0)
            assert(first != null)
            var i = 1
            while i < concurrency do
                assert((results(i) eq first) == true)
                i += 1
            end while
        }

        "concurrent load + unload, every load result is a non-null valid instance" in {
            val readers    = 8
            val iterations = 100
            val done       = new CountDownLatch(readers)
            val errors     = new AtomicReference[Throwable](null)
            val start      = new CountDownLatch(1)

            // Unloader thread: repeatedly evicts and re-caches the binding.
            val unloaderDone = new CountDownLatch(1)
            val unloader: Runnable = () =>
                try
                    start.await(5, TimeUnit.SECONDS)
                    var i = 0
                    while i < iterations do
                        Ffi.unload[FfiConcurrentLoadTest.RaceBinding]
                        i += 1
                    end while
                catch
                    case t: Throwable => discard(errors.compareAndSet(null, t))
                finally
                    unloaderDone.countDown()
            val unloaderThread = new Thread(unloader, "unloader")
            unloaderThread.setDaemon(true)
            unloaderThread.start()

            // Reader threads: continuously call load and assert the result is non-null.
            var r = 0
            while r < readers do
                val idx = r
                val reader: Runnable = () =>
                    try
                        start.await(5, TimeUnit.SECONDS)
                        var i = 0
                        while i < iterations do
                            val inst = Ffi.load[FfiConcurrentLoadTest.RaceBinding]
                            if inst == null then
                                discard(errors.compareAndSet(
                                    null,
                                    new AssertionError(s"load() returned null at iteration $i in reader $idx")
                                ))
                            end if
                            i += 1
                        end while
                    catch
                        case t: Throwable => discard(errors.compareAndSet(null, t))
                    finally
                        done.countDown()
                val thread = new Thread(reader, s"reader-$idx")
                thread.setDaemon(true)
                thread.start()
                r += 1
            end while

            start.countDown()
            assert(done.await(30, TimeUnit.SECONDS) == true)
            assert(unloaderDone.await(10, TimeUnit.SECONDS) == true)

            val err = errors.get()
            if err != null then fail(s"Unexpected error during concurrent load+unload: $err")
        }

        "concurrent load of distinct bindings does not corrupt either cache entry" in {
            val concurrency = 16
            Ffi.unload[FfiConcurrentLoadTest.BindingA]
            Ffi.unload[FfiConcurrentLoadTest.BindingB]
            val barrier = new CyclicBarrier(concurrency)
            val done    = new CountDownLatch(concurrency)
            val errors  = new AtomicReference[Throwable](null)

            val resultsA = new Array[AnyRef](concurrency / 2)
            val resultsB = new Array[AnyRef](concurrency / 2)

            var t = 0
            while t < concurrency do
                val idx = t
                val r: Runnable = () =>
                    try
                        barrier.await(5, TimeUnit.SECONDS)
                        if idx < concurrency / 2 then
                            resultsA(idx) = Ffi.load[FfiConcurrentLoadTest.BindingA]
                        else
                            resultsB(idx - concurrency / 2) = Ffi.load[FfiConcurrentLoadTest.BindingB]
                        end if
                    catch
                        case th: Throwable => discard(errors.compareAndSet(null, th))
                    finally
                        done.countDown()
                val thread = new Thread(r, s"multi-load-$idx")
                thread.setDaemon(true)
                thread.start()
                t += 1
            end while

            assert(done.await(10, TimeUnit.SECONDS) == true)

            val err = errors.get()
            if err != null then fail(s"Unexpected exception from concurrent multi-binding load(): $err")

            // All A results must be the same (and of type BindingA).
            val firstA = resultsA(0)
            assert(firstA != null)
            assert(firstA.isInstanceOf[FfiConcurrentLoadTest.BindingA] == true)
            var i = 1
            while i < resultsA.length do
                assert((resultsA(i) eq firstA) == true)
                i += 1
            end while

            // All B results must be the same (and of type BindingB), and distinct from the A instance.
            val firstB = resultsB(0)
            assert(firstB != null)
            assert(firstB.isInstanceOf[FfiConcurrentLoadTest.BindingB] == true)
            assert((firstA eq firstB) == false)
            var j = 1
            while j < resultsB.length do
                assert((resultsB(j) eq firstB) == true)
                j += 1
            end while
        }

        "impl is instantiated at most once per load epoch (computeIfAbsent contract)" in {
            Ffi.unload[FfiConcurrentLoadTest.CountedBinding]
            FfiConcurrentLoadTest.CountedBinding.instanceCount.set(0)

            val concurrency = 32
            val barrier     = new CyclicBarrier(concurrency)
            val done        = new CountDownLatch(concurrency)
            val errors      = new AtomicReference[Throwable](null)

            var t = 0
            while t < concurrency do
                val idx = t
                val r: Runnable = () =>
                    try
                        barrier.await(5, TimeUnit.SECONDS)
                        discard(Ffi.load[FfiConcurrentLoadTest.CountedBinding])
                    catch
                        case th: Throwable => discard(errors.compareAndSet(null, th))
                    finally
                        done.countDown()
                val thread = new Thread(r, s"counted-load-$idx")
                thread.setDaemon(true)
                thread.start()
                t += 1
            end while

            assert(done.await(10, TimeUnit.SECONDS) == true)

            val err = errors.get()
            if err != null then fail(s"Unexpected exception: $err")

            // ConcurrentHashMap.computeIfAbsent guarantees the mapping function runs at most once.
            assert(FfiConcurrentLoadTest.CountedBinding.instanceCount.get() == 1)
        }
    }
end FfiConcurrentLoadTest

object FfiConcurrentLoadTest:

    /** Binding used for the "stable singleton" test, no unload between concurrent loads. */
    trait StableBinding     extends Ffi
    class StableBindingImpl extends StableBinding

    /** Binding used for the race between concurrent load and unload. */
    trait RaceBinding     extends Ffi
    class RaceBindingImpl extends RaceBinding

    /** Two distinct bindings for the "multi-binding no corruption" test. */
    trait BindingA     extends Ffi
    class BindingAImpl extends BindingA

    trait BindingB     extends Ffi
    class BindingBImpl extends BindingB

    /** Binding whose impl constructor increments a counter, used to verify computeIfAbsent runs at most once. */
    trait CountedBinding extends Ffi

    object CountedBinding:
        val instanceCount: AtomicInteger = new AtomicInteger(0)

    class CountedBindingImpl extends CountedBinding:
        discard(FfiConcurrentLoadTest.CountedBinding.instanceCount.incrementAndGet())
end FfiConcurrentLoadTest
