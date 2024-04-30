package kyoTest

import kyo.*
import org.scalatest.compatible.Assertion
import scala.concurrent.Future
import zio.*

class ziosTest extends KyoTest:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    def runKyo(v: => Assertion < (Fibers & ZIOs)): Future[Assertion] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(
                ZIOs.run(v)
            )
        )

    "aborts" in runKyo {
        val a: Nothing < (Aborts[String] & ZIOs) = ZIOs.get(ZIO.fail("error"))
        Aborts.run(a).map(e => assert(e.isLeft))
    }

    "env" in runKyo {
        val a: Int < (Envs[Int] & ZIOs) = ZIOs.get(ZIO.service[Int])
        val b: Int < ZIOs               = Envs.run(10)(a)
        b.map(v => assert(v == 10))
    }

    "fibers" in runKyo {
        for
            v1 <- ZIOs.get(ZIO.succeed(1))
            v2 <- Fibers.init(2).map(_.get)
            v3 <- ZIOs.get(ZIO.succeed(3))
        yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }

    "interrupts" - {

        def kyoLoop(a: AtomicInt): Unit < IOs =
            a.incrementAndGet.map(_ => kyoLoop(a))

        def zioLoop(a: Ref[Int]): Task[Unit] =
            a.update(_ + 1).flatMap(_ => zioLoop(a))

        if Platform.isJVM then

            "zio to kyo" in runZIO {
                val v =
                    for
                        a <- Atomics.initInt(0)
                        _ <- Fibers.init(kyoLoop(a)).map(_.get)
                    yield ()
                for
                    f <- ZIOs.run(v).fork
                    _ <- f.interrupt
                    r <- f.await
                yield assert(r.isFailure)
                end for
            }
            // "kyo to zio" in run {
            //     val v =
            //         for
            //             a <- Ref.make(0)
            //             _ <- zioLoop(a)
            //         yield ()
            //     for
            //         f <-
            //         _ <- f.interrupt
            //         r <- f.await
            //     yield assert(r.isFailure)
            //     end for
            // }
            // "both" in run {
            //     val v =
            //         for
            //             a  <- ZIOs.get(Ref.make(0))
            //             _  <- ZIOs.get(zioLoop(a))
            //             a2 <- Atomics.initInt(0)
            //             _  <- Fibers.fork(kyoLoop(a2))
            //         yield ()
            //     for
            //         f <- ZiosApp.runTask(v).fork
            //         _ <- f.interrupt
            //         r <- f.await
            //     yield assert(r.isFailure)
            //     end for
            // }
        end if
    }
end ziosTest
