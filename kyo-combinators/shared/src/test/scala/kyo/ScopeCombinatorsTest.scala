package kyo

import kyo.Result.Error

class ScopeCombinatorsTest extends kyo.test.Test[Any]:

    "construct" - {
        "should construct a resource with acquireRelease" in {
            var state = 0
            val acquire = Sync.defer {
                (i: Int) => Sync.defer { state = i }
            }
            val resource = Kyo.acquireRelease(acquire)(_(0))
            val effect: Int < (Scope & Sync) =
                for
                    setter <- resource
                    _      <- setter(50)
                    result <- Sync.defer(state)
                yield result
            assert(state == 0)
            val handledResources: Int < Async = Scope.run(effect)
            Fiber.initUnscoped(handledResources).map(_.get).map { result =>
                assert(result == 50)
                assert(state == 0)
            }
        }

        "should construct a resource using addFinalizer" in {
            var state  = 0
            val effect = Kyo.addFinalizer(Sync.defer { state = 100 })
            Fiber.initUnscoped(Scope.run(effect)).map(_.get).andThen(assert(state == 100))
        }

        "should construct a resource from an AutoCloseable" in {
            var state = 0
            val closeable = new AutoCloseable:
                override def close(): Unit = state = 100
            val effect = Kyo.fromAutoCloseable(closeable)
            assert(state == 0)
            Fiber.initUnscoped(Scope.run(effect)).map(_.get).map { v =>
                assert(v.equals(closeable))
                assert(state == 100)
            }
        }
    }

    "combinators" - {
        "ensuring" in {
            var finalizerCalled                          = false
            def ensure: Unit < (Sync & Abort[Throwable]) = Sync.defer { finalizerCalled = true }
            Scope.run(Sync.defer(()).ensuring(ensure))
                .andThen(assert(finalizerCalled))
        }

        "ensuringError" in {
            var error: Maybe[Error[Any]] = Absent
            given [A]: CanEqual[A, A]    = CanEqual.derived

            val ensure: Maybe[Error[Any]] => Unit < (Sync & Abort[Throwable]) = ex => Sync.defer { error = ex }
            Abort.fail("failure").ensuringError(ensure).handle(Scope.run, Abort.run(_)).andThen {
                assert(error == Result.fail("failure"))
            }
        }
    }
end ScopeCombinatorsTest
