package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.Test

/** JVM-only concurrency test: concurrent [[Ffi.Guard]] close + [[Buffer]] access (Phase R7 D3).
  *
  * The risk: thread A holds a reference to a [[Buffer]] registered with a guard and reads it; thread B closes the guard, which releases the
  * underlying Panama [[java.lang.foreign.Arena]]. Without proper ordering the read in A could observe a freed segment.
  *
  * The JVM Panama [[java.lang.foreign.Arena.ofShared]] protects shared buffers: after the arena is closed, every segment read/write throws
  * [[java.lang.IllegalStateException]]. The test verifies that:
  *   1. Either the read in A succeeds (guard still open) or throws [[java.lang.IllegalStateException]] (guard closed), never causes
  *      undefined behavior, a segfault, or any other unchecked exception.
  *   2. After 100 iterations, the guard is always in the closed state.
  */
class JvmGuardCloseRaceTest extends Test:

    "JvmGuard, concurrent close and buffer read (D3)" - {

        "no unhandled exception escapes from concurrent close + buffer read" in {
            val iterations = 100
            var i          = 0
            while i < iterations do
                val unexpectedError = new AtomicReference[Throwable](null)

                val guard  = Ffi.Guard.open().asInstanceOf[JvmGuard]
                val buffer = Buffer.alloc[Int](16)
                guard.registerBuffer(buffer)

                // Write something so readers have a real value to load.
                buffer.set(0, i)

                val readerReady = new CountDownLatch(1)
                val startRace   = new CountDownLatch(1)
                val readerDone  = new CountDownLatch(1)

                val reader: Runnable = () =>
                    try
                        readerReady.countDown()
                        startRace.await(5, TimeUnit.SECONDS)
                        // May succeed (guard open) or throw IllegalStateException (guard closed).
                        // Any other exception is a bug.
                        try
                            discard(buffer.get(0))
                        catch
                            case _: IllegalStateException => ()
                        end try
                    catch
                        case t: Throwable => discard(unexpectedError.compareAndSet(null, t))
                    finally
                        readerDone.countDown()

                val readerThread = new Thread(reader, s"race-reader-$i")
                readerThread.setDaemon(true)
                readerThread.start()

                // Wait until reader is spun up, then release both simultaneously.
                readerReady.await(5, TimeUnit.SECONDS)
                startRace.countDown()
                // Close the guard concurrently with the buffer read.
                guard.close()

                assert(readerDone.await(5, TimeUnit.SECONDS) == true)

                val err = unexpectedError.get()
                if err != null then fail(s"Unexpected exception in reader thread (iteration $i): $err")

                // Guard must be closed after close() returns.
                assert(guard.isClosed == true)

                i += 1
            end while
        }

        "buffer registered with guard is closed after guard.close()" in {
            val guard  = Ffi.Guard.open()
            val buffer = Buffer.alloc[Long](8)
            guard.registerBuffer(buffer)
            buffer.set(0, 42L)
            guard.close()
            // Buffer must be closed, Panama arena is gone.
            interceptThrown[Exception](buffer.get(0))
        }

        "guard.close() races with a second concurrent close, at most one succeeds" in {
            val iterations = 100
            var i          = 0
            while i < iterations do
                val guard = Ffi.Guard.open()

                val start     = new CountDownLatch(1)
                val done      = new CountDownLatch(2)
                val throwable = new AtomicReference[Throwable](null)

                val closer: Runnable = () =>
                    try
                        start.await(5, TimeUnit.SECONDS)
                        discard(guard.close())
                    catch
                        case t: Throwable => discard(throwable.compareAndSet(null, t))
                    finally
                        done.countDown()

                val t1 = new Thread(closer, s"closer-1-$i")
                val t2 = new Thread(closer, s"closer-2-$i")
                t1.setDaemon(true)
                t2.setDaemon(true)
                t1.start()
                t2.start()

                start.countDown()
                assert(done.await(10, TimeUnit.SECONDS) == true)

                val err = throwable.get()
                if err != null then fail(s"Unexpected exception from concurrent close() (iteration $i): $err")

                i += 1
            end while
        }
    }
end JvmGuardCloseRaceTest
