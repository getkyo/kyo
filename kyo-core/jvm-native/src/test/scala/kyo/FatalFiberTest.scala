package kyo

class FatalFiberTest extends Test:

    "fatal throwable from a fiber computation" - {
        "LinkageError completes the IOPromise with a Panic before the worker rethrows" in run {
            val fatal                                 = new LinkageError("simulated NoClassDefFoundError")
            val body: Int < (Sync & Abort[Throwable]) = Sync.defer { throw fatal; 0 }
            for
                signal <- Fiber.Promise.init[Int, Abort[Throwable]]
                fiber  <- Fiber.initUnscoped(body)
                _ <- Sync.defer {
                    import AllowUnsafe.embrace.danger
                    fiber.unsafe.onComplete(signal.unsafe.completeDiscard)
                }
                captured <- signal.getResult
            yield captured match
                case Result.Panic(thr) if thr eq fatal => succeed
                case other                             => fail(s"unexpected outcome: $other")
            end for
        }
    }

end FatalFiberTest
