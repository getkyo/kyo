package kyo

import scala.concurrent.Future

class ResourceCombinatorTest extends Test:

    "construct" - {
        "should construct a resource with acquireRelease" in {
            var state = 0
            val acquire = IO {
                (i: Int) => IO { state = i }
            }
            val resource = Kyo.acquireRelease(acquire)(_(0))
            val effect: Int < (Resource & IO) =
                for
                    setter <- resource
                    _      <- setter(50)
                    result <- IO(state)
                yield result
            val beforeResources               = scala.concurrent.Future(assert(state == 0))
            val handledResources: Int < Async = Resource.run(effect)
            val handled                       = IO.run(Async.run(handledResources).map(_.toFuture))
            for
                assertion1 <- beforeResources
                assertion2 <- handled.eval.map(_ == 50)
                assertion3 <- Future(assert(state == 0))
            yield assertion3
            end for
        }

        "should construct a resource using addFinalizer" in {
            var state   = 0
            val effect  = Kyo.addFinalizer(IO { state = 100 })
            val handled = IO.run(Async.run(Resource.run(effect)).map(_.toFuture))
            for
                ass1 <- handled.eval
                ass2 <- Future(assert(state == 100))
            yield ass2
            end for
        }

        "should construct a resource from an AutoCloseable" in {
            var state = 0
            val closeable = new AutoCloseable:
                override def close(): Unit = state = 100
            val effect = Kyo.fromAutoCloseable(closeable)
            assert(state == 0)
            val handled = IO.run(Async.run(Resource.run(effect)).map(_.toFuture))
            for
                ass2 <- handled.eval.map(v => assert(v.equals(closeable)))
                ass3 <- Future(assert(state == 100))
            yield ass3
            end for
        }
    }
end ResourceCombinatorTest
