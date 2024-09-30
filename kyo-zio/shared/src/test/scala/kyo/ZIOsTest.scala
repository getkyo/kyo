package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.kernel.Platform
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.Eventually.*
import scala.concurrent.Future
import zio.Task
import zio.ZIO

class ZIOsTest extends Test:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    def runKyo(v: => Assertion < (Abort[Throwable] & ZIOs)): Future[Assertion] =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.runToFuture(
                ZIOs.run(v)
            )
        )

    "Abort[String]" in runKyo {
        val a: Nothing < (Abort[String] & ZIOs) = ZIOs.get(ZIO.fail("error"))
        Abort.run(a).map(e => assert(e.isFail))
    }
    "Abort[Throwable]" in runKyo {
        val a: Boolean < (Abort[Throwable] & ZIOs) = ZIOs.get(ZIO.fail(new RuntimeException).when(false).as(true))
        Abort.run(a).map(e => assert(e.isSuccess))
    }

    "Abort ordering" - {
        "kyo then zio" in runKyo {
            object zioFailure extends RuntimeException
            object kyoFailure extends RuntimeException
            val a = Abort.fail(kyoFailure)
            val b = ZIOs.get(ZIO.fail(zioFailure))
            Abort.run(a.map(_ => b)).map {
                case Result.Fail(ex) =>
                    assert(ex == kyoFailure)
                case _ =>
                    fail()
            }
        }
        "zio then kyo" in runKyo {
            object zioFailure extends RuntimeException
            object kyoFailure extends RuntimeException
            val a = ZIOs.get(ZIO.fail(zioFailure))
            val b = Abort.fail(kyoFailure)
            Abort.run(a.map(_ => b)).map {
                case Result.Fail(ex) =>
                    assert(ex == zioFailure)
                case _ =>
                    fail()
            }
        }
    }

    "Envs[Int]" in pendingUntilFixed {
        assertCompiles("""
            val a: Int < (Env[Int] & ZIOs) = ZIOs.get(ZIO.service[Int])
            val b: Int < ZIOs              = Env.run(10)(a)
            b.map(v => assert(v == 10))
        """)
    }
    "Envs[Int & Double]" in pendingUntilFixed {
        assertCompiles("""
            val a = ZIOs.get(ZIO.service[Int] *> ZIO.service[Double])
        """)
    }

    "A < ZIOs" in runKyo {
        val a: Int < ZIOs = ZIOs.get(ZIO.succeed(10))
        val b             = a.map(_ * 2)
        b.map(i => assert(i == 20))
    }

    "nested" in runKyo {
        val a: (String < ZIOs) < ZIOs = ZIOs.get(ZIO.succeed(ZIOs.get(ZIO.succeed("Nested"))))
        val b: String < ZIOs          = a.flatten
        b.map(s => assert(s == "Nested"))
    }

    "fibers" in runKyo {
        for
            v1 <- ZIOs.get(ZIO.succeed(1))
            v2 <- Async.run(2).map(_.get)
            v3 <- ZIOs.get(ZIO.succeed(3))
        yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }

    "interrupts" - {

        def kyoLoop(cdl: CountDownLatch): Unit < IO =
            def loop(): Unit < IO =
                IO(()).flatMap(_ => loop())
            IO.ensure(IO(cdl.countDown()))(loop())
        end kyoLoop

        def zioLoop(cdl: CountDownLatch): Task[Unit] =
            def loop(): Task[Unit] =
                ZIO.unit.flatMap(_ => loop())
            loop().ensuring(ZIO.attempt(cdl.countDown()).ignore)
        end zioLoop

        if Platform.isJVM then

            "zio to kyo" in runZIO {
                pending
                val cdl = new CountDownLatch(1)
                for
                    f <- ZIOs.run(kyoLoop(cdl)).fork
                    _ <- f.interrupt
                    r <- f.await
                yield
                    assert(cdl.await(100, TimeUnit.MILLISECONDS))
                    assert(r.isFailure)
                end for
            }
            "kyo to zio" in runKyo {
                pending
                val cdl = new CountDownLatch(1)
                for
                    f <- Async.run(ZIOs.run(zioLoop(cdl)))
                    _ <- f.interrupt(Result.Panic(new Exception))
                    r <- f.getResult
                yield
                    assert(cdl.await(100, TimeUnit.MILLISECONDS))
                    assert(r.isPanic)
                end for
            }
            "both" in runZIO {
                pending
                val cdl = new CountDownLatch(1)
                val v =
                    for
                        _ <- ZIOs.get(zioLoop(cdl))
                        _ <- Async.run(kyoLoop(cdl))
                    yield ()
                for
                    f <- ZIOs.run(v).fork
                    _ <- f.interrupt
                    r <- f.await
                yield
                    assert(cdl.await(100, TimeUnit.MILLISECONDS))
                    assert(r.isFailure)
                end for
            }
        end if
    }
end ZIOsTest
