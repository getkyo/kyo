package kyo

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import kyo.*
import kyo.Result.Error
import kyo.Result.Panic
import kyo.debug.Debug
import scala.util.control.NoStackTrace

class ScopeInterruptTest extends Test:

    "can't be interrupted without registering the finalizer" in run {
        val r    = TestResource(1)
        val cdl1 = new CountDownLatch(1)
        val cdl2 = new CountDownLatch(1)
        for
            l <- Latch.init(1)
            fiber <-
                Fiber.init {
                    Scope.run {
                        Scope.acquire {
                            cdl1.countDown()
                            cdl2.await()
                            r
                        }
                    }
                }
            _   <- Sync.defer(cdl1.await())
            _   <- fiber.interrupt
            _   <- Sync.defer(cdl2.countDown())
            res <- fiber.getResult
            _   <- untilTrue(r.closes == 1)
        yield assert(res.isPanic)
        end for
    }
end ScopeInterruptTest
