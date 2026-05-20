package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.AllowUnsafe.embrace.danger

class FatalFiberTest extends Test:

    "fatal throwable from a fiber computation" - {
        "LinkageError completes the IOPromise with a Panic before the worker rethrows" in {
            val fatal                                 = new LinkageError("simulated NoClassDefFoundError")
            val body: Int < (Sync & Abort[Throwable]) = Sync.defer { throw fatal; 0 }

            val latch    = new CountDownLatch(1)
            val captured = new AtomicReference[Result[Throwable, Int]](null)

            Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped(body).map { fiber =>
                    Sync.Unsafe.defer {
                        fiber.unsafe.onComplete { r =>
                            captured.set(r.asInstanceOf[Result[Throwable, Int]])
                            latch.countDown()
                        }
                    }
                }
            }

            assert(latch.await(2, TimeUnit.SECONDS), "IOTask never recorded the failure on the IOPromise")
            captured.get() match
                case Result.Panic(thr) if thr eq fatal => succeed
                case other                             => fail(s"unexpected outcome: $other")
            end match
        }
    }

end FatalFiberTest
