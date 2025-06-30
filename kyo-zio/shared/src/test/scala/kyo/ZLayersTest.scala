package kyo

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.internal.LayerMacros.extractEnvs
import org.scalatest.compatible.Assertion
import scala.concurrent.Future
import zio.Cause
import zio.Runtime
import zio.Scope
import zio.Tag as ZTag
import zio.Unsafe
import zio.ZIO
import zio.ZLayer

class ZLayersTest extends Test:

    def runZIO[T](v: zio.Task[T]): Future[T] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(v)
        )

    def runKyo(v: => Assertion < (Abort[Throwable] & Async)): Future[Assertion] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(
                ZIOs.run(v)
            )
        )

    trait TestService:
        def getValue: Int

    case class TestServiceImpl(value: Int) extends TestService:
        def getValue: Int = value

    trait AnotherService:
        def getDescription: String

    case class AnotherServiceImpl(description: String) extends AnotherService:
        def getDescription: String = description

    ".get" - {
        "ZLayer.succeed" in runKyo {
            val zlayer: ZLayer[Any, Nothing, TestService] =
                ZLayer.succeed(TestServiceImpl(42))

            val klayer: Layer[TestService, Async & Resource] =
                ZLayers.get[Nothing, TestService](zlayer)

            Env.runLayer(klayer)(Env.use[TestService](service => assert(service.getValue == 42))).handle(Memo.run, Resource.run)
        }

        "ZLayer.fail" in runKyo {
            val zlayer: ZLayer[Any, String, TestService] =
                ZLayer.fail("layer failed")

            val klayer: Layer[TestService, Abort[String] & Async & Resource] =
                ZLayers.get(zlayer)

            val computation = Env.use[TestService](_.getValue)

            Env.runLayer(klayer)(computation).handle(Abort.run(_), Memo.run, Resource.run).map { result =>
                result match
                    case Result.Failure(msg) => assert(msg == "layer failed")
                    case _                   => fail("Expected LayerError")
            }
        }

        "memoized" in runKyo {
            val zlayer: ZLayer[Any, Nothing, TestService] =
                ZLayer.succeed[TestService] {
                    new AtomicInteger(0) with TestService:
                        def getValue: Int = incrementAndGet()
                }

            val klayer1: Layer[TestService, Abort[Nothing] & Async & Resource] =
                ZLayers.get(zlayer)

            val klayer2 = Layer(Env.use[TestService](_.getValue))

            val klayer3 = klayer1.using(klayer2)

            Env.runLayer(klayer3)(Env.use[TestService](_.getValue)).map(value => assert(value == 2)).handle(Memo.run, Resource.run)
        }

        def scopedTest(name: String)(expectedExit: zio.Exit[Any, Any])(effect: => Any < (Env[Int] & Abort[String] & Async)) =
            s"scoped - $name" in runKyo {
                given CanEqual[zio.Exit[Any, Any], zio.Exit[Any, Any]] = CanEqual.derived
                import zio.Exit
                var acquired = 0
                var exit     = AtomicReference[Exit[Any, Any]](Exit.fail("Exit was never updated"))
                val zlayer   = ZLayer.scoped(Scope.addFinalizerExit(e => ZIO.succeed(exit.set(e))) *> ZIO.succeed { acquired += 1; 42 })
                val klayer   = ZLayers.get(zlayer)
                Env.runLayer(klayer)(effect).handle(Memo.run, Resource.run, Abort.run(_)).andThen {
                    assert(acquired == 1)
                    assert(exit.get == expectedExit)
                }
            }

        scopedTest("success")(zio.Exit.unit)(Env.use[Int](value => assert(value == 42)))
        scopedTest("failure")(zio.Exit.fail("failure"))(Abort.fail("failure"))
        val ex = new RuntimeException("panic")
        scopedTest("panic")(zio.Exit.die(ex))(Abort.panic(ex))

    }

    ".run" - {
        "Sync" in runZIO {
            val klayer: Layer[TestService, Sync] = Layer(Sync.io(TestServiceImpl(0)))

            val zlayer = ZLayers.run(klayer)

            ZIO.serviceWith[TestService](service => assert(service.getValue == 0)).provide(zlayer)
        }
        "Abort" in runZIO {
            val klayer: Layer[TestService, Abort[String]] = Layer(Abort.fail("error"))

            val zlayer = ZLayers.run(klayer)

            ZIO.service[TestService].provide(zlayer).either.map(either => assert(either == Left("error")))
        }
    }

end ZLayersTest
