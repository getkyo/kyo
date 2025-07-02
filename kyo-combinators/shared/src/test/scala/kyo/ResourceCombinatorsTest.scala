package kyo

import scala.concurrent.Future

class ResourceCombinatorsTest extends Test:

    "construct" - {
        "should construct a resource with acquireRelease" in run {
            var state = 0
            val acquire = Sync.defer {
                (i: Int) => Sync.defer { state = i }
            }
            val resource = Kyo.acquireRelease(acquire)(_(0))
            val effect: Int < (Resource & Sync) =
                for
                    setter <- resource
                    _      <- setter(50)
                    result <- Sync.defer(state)
                yield result
            assert(state == 0)
            val handledResources: Int < Async = Resource.run(effect)
            Fiber.init(handledResources).map(_.toFuture).map { handled =>
                for
                    assertion1 <- handled.map(_ == 50)
                    assertion2 <- Future(assert(state == 0))
                yield assertion2
                end for
            }
        }

        "should construct a resource using addFinalizer" in run {
            var state  = 0
            val effect = Kyo.addFinalizer(Sync.defer { state = 100 })
            Fiber.init(Resource.run(effect)).map(_.toFuture).map { handled =>
                for
                    ass1 <- handled
                    ass2 <- Future(assert(state == 100))
                yield ass2
                end for
            }
        }

        "should construct a resource from an AutoCloseable" in run {
            var state = 0
            val closeable = new AutoCloseable:
                override def close(): Unit = state = 100
            val effect = Kyo.fromAutoCloseable(closeable)
            assert(state == 0)
            Fiber.init(Resource.run(effect)).map(_.toFuture).map { handled =>
                for
                    ass2 <- handled.map(v => assert(v.equals(closeable)))
                    ass3 <- Future(assert(state == 100))
                yield ass3
                end for
            }
        }
    }
end ResourceCombinatorsTest
