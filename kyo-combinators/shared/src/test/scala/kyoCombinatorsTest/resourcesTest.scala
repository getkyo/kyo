package KyoTest

import kyo.*
import scala.concurrent.Future
import kyoTest.KyoTest

class resourcesTest extends KyoTest:

    "construct" - {
        "should construct a resource with acquireRelease" in {
            var state = 0
            val acquire = IOs {
                (i: Int) => IOs { state = i }
            }
            val resource = Kyo.acquireRelease(acquire)(_(0))
            val effect: Int < Resources =
                for
                    setter <- resource
                    _      <- setter(50)
                    result <- IOs(state)
                yield result
            val beforeResources                = scala.concurrent.Future(assert(state == 0))
            val handledResources: Int < Fibers = Resources.run(effect)
            val handled = IOs.run(Fibers.run(handledResources).map(_.toFuture))
            for
                assertion1 <- beforeResources
                assertion2 <- handled.pure.map(_ == 50)
                assertion3 <- Future(assert(state == 0))
            yield assertion3
            end for
        }

        "should construct a resource using addFinalizer" in {
            var state   = 0
            val effect  = Kyo.addFinalizer(IOs { state = 100 })
            val handled = IOs.run(Fibers.run(Resources.run(effect)).map(_.toFuture))
            for
                ass1 <- handled.pure
                ass2 <- Future(assert(state == 100))
            yield ass2
            end for
        }

        "should construct a resource from an AutoCloseable" in {
            var state = 0
            val closeable = new AutoCloseable:
                override def close(): Unit = state = 100
            val effect     = Kyo.fromAutoCloseable(closeable)
            assert(state == 0)
            val handled    = IOs.run(Fibers.run(Resources.run(effect)).map(_.toFuture))
            for
                ass2 <- handled.pure.map(v => assert(v.equals(closeable)))
                ass3 <- Future(assert(state == 100))
            yield ass3
            end for
        }
    }
end resourcesTest
