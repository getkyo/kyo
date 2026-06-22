package kyo

class FatalFiberTest extends kyo.test.Test[Any]:

    "fatal throwable from a fiber computation" - {
        "LinkageError completes the IOPromise with a Panic before the worker rethrows" in {
            // The worker must complete the IOPromise with a Panic before it rethrows the fatal error
            // (IOTask.eval: `completeDiscard(Panic(ex))` then `if !NonFatal(ex) then throw ex`). Awaiting
            // the fiber's Result via `getResult` exercises that path without racing a wall-clock deadline:
            // if the worker rethrew before completing, the promise would never complete and the leaf would
            // time out instead of flaking on a tight latch await.
            val fatal                                 = new LinkageError("simulated NoClassDefFoundError")
            val body: Int < (Sync & Abort[Throwable]) = Sync.defer { throw fatal; 0 }
            Fiber.initUnscoped(body).map: fiber =>
                fiber.getResult.map:
                    case Result.Panic(thr) => assert(thr eq fatal)
                    case other             => fail(s"unexpected outcome: $other")
        }
    }

end FatalFiberTest
