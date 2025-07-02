package kyo.bench.arena

import kyo.bench.BaseBench
import org.openjdk.jmh.annotations.*
import scala.compiletime.uninitialized

// TODO: What to call this?
abstract class ArenaBench2[A](val expectedResult: A) extends BaseBench:

    def forkCats(catsBench: cats.effect.IO[A])(using cats.effect.unsafe.IORuntime): A =
        cats.effect.IO.cede.flatMap(_ => catsBench).unsafeRunSync()

    def forkKyo(kyoBenchFiber: kyo.<[A, kyo.Async & kyo.Abort[Throwable]]): A =
        import kyo.*
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        Sync.Unsafe.evalOrThrow(Fiber.run(kyoBenchFiber).flatMap(_.block(Duration.Infinity))).getOrThrow
    end forkKyo

    def forkZIO(zioBench: zio.Task[A])(using zioRuntime: zio.Runtime[Any]): A = zio.Unsafe.unsafe(implicit u =>
        zioRuntime.unsafe.run(zio.ZIO.yieldNow.flatMap(_ => zioBench)).getOrThrow()
    )

end ArenaBench2

object ArenaBench2:

    @State(Scope.Benchmark)
    class CatsRuntime:

        var ioRuntime: cats.effect.unsafe.IORuntime = uninitialized
        given cats.effect.unsafe.IORuntime          = ioRuntime

        @Setup
        def setup() =
            ioRuntime =
                if System.getProperty("replaceCatsExecutor", "false") == "true" then
                    kyo.KyoSchedulerIORuntime.global
                else
                    cats.effect.unsafe.implicits.global
        end setup

    end CatsRuntime

    @State(Scope.Benchmark)
    class ZIORuntime:

        var zioRuntime: zio.Runtime[Any] = uninitialized

        private var finalizer: () => Unit = () => ()

        @Setup
        def setup(): Unit =
            val zioRuntimeLayer: zio.ZLayer[Any, Any, Any] =
                if System.getProperty("replaceZIOExecutor", "false") == "true" then
                    kyo.KyoSchedulerZIORuntime.layer
                else
                    zio.ZLayer.empty

            zioRuntime =
                if zioRuntimeLayer ne zio.ZLayer.empty then
                    val (runtime, finalizer) = ZIORuntime.fromLayerWithFinalizer(zioRuntimeLayer)
                    this.finalizer = finalizer
                    runtime
                else
                    zio.Runtime.default
        end setup

        @TearDown
        def tearDown(): Unit =
            finalizer()

    end ZIORuntime

end ArenaBench2
