package kyo

class FatalFiberTest extends kyo.test.Test[Any]:

    "fatal throwable from a fiber computation" - {
        "the promise is completed with a Panic before the worker rethrows" in {
            // `run` completes the promise with a Panic and only then rethrows, so a fiber that took a fatal still
            // reports it. Awaiting `getResult` exercises that order: with the rethrow first the promise would
            // never complete. InternalError because `IsFatal` counts only `VirtualMachineError` and
            // `ControlThrowable`.
            val fatal                                 = new InternalError("simulated fatal")
            val body: Int < (Sync & Abort[Throwable]) = Sync.defer { throw fatal; 0 }
            Fiber.initUnscoped(body).map: fiber =>
                fiber.getResult.map:
                    case Result.Panic(thr) => assert(thr eq fatal)
                    case other             => fail(s"unexpected outcome: $other")
        }

        // Scala treats a LinkageError as fatal and kyo does not.
        "a LinkageError is carried as a Panic rather than rethrown" in {
            val ex                                    = new LinkageError("simulated NoClassDefFoundError")
            val body: Int < (Sync & Abort[Throwable]) = Sync.defer { throw ex; 0 }
            Fiber.initUnscoped(body).map: fiber =>
                fiber.getResult.map:
                    case Result.Panic(thr) => assert(thr eq ex)
                    case other             => fail(s"unexpected outcome: $other")
        }
    }

end FatalFiberTest
