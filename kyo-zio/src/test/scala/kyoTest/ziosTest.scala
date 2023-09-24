package kyoTest

import kyo.ZiosApp
import kyo.aborts.Aborts
import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo._
import kyo.aborts._
import kyo.envs._
import kyo.ios._
import kyo.zios._
import kyoTest.KyoTest
import zio.Task
import zio.ZIO
import zio.Ref

import scala.concurrent.duration._
import org.scalatest.compatible.Assertion
import scala.concurrent.Future
import zio.ZEnvironment

class ziosTest extends KyoTest {

  def runZIO(v: => Assertion > (IOs with Fibers with ZIOs)): Future[Assertion] =
    zio.Unsafe.unsafe(implicit u =>
      zio.Runtime.default.unsafe.runToFuture(
          ZiosApp.runTask(v)
      )
    )

  "aborts" in runZIO {
    val a: Int > (Aborts[String] with ZIOs) = ZIOs.fromIO(ZIO.fail("error"))
    Aborts[String].run(a).map(e => assert(e.isLeft))
  }

  "env" in runZIO {
    Aborts[Nothing].run(Envs[Int].run(10)(
        ZIOs.fromZIO[Int, Nothing, ZEnvironment[Int], Any](ZIO.environment[Int])
    ).map(_.get)).map(v =>
      assert(v == Right(10))
    )
  }

  "fibers" in runZIO {
    for {
      v1 <- ZIOs.fromTask(ZIO.succeed(1))
      v2 <- Fibers.fork(2)
      v3 <- ZIOs.fromTask(ZIO.succeed(3))
    } yield assert(v1 == 1 && v2 == 2 && v3 == 3)
  }

  "interrupts" - {

    def kyoLoop(a: AtomicInt): Unit > IOs =
      a.incrementAndGet.map(_ => kyoLoop(a))

    def zioLoop(a: Ref[Int]): Task[Unit] =
      a.update(_ + 1).flatMap(_ => zioLoop(a))

    if (Platform.isJVM) {

      "kyo" in {
        val v =
          for {
            a <- Atomics.initInt(0)
            _ <- Fibers.fork(kyoLoop(a))
          } yield ()
        ZiosApp.block(1.day) {
          for {
            f <- ZiosApp.runTask(v).fork
            _ <- f.interrupt
            r <- f.await
          } yield assert(r.isFailure)
        }
      }
      "zio" in {
        val v =
          for {
            a <- ZIOs.fromTask(Ref.make(0))
            _ <- ZIOs.fromTask(zioLoop(a))
          } yield ()
        ZiosApp.block(1.second) {
          for {
            f <- ZiosApp.runTask(v).fork
            _ <- f.interrupt
            r <- f.await
          } yield assert(r.isFailure)
        }
      }
      "both" in {
        val v =
          for {
            a  <- ZIOs.fromTask(Ref.make(0))
            _  <- ZIOs.fromTask(zioLoop(a))
            a2 <- Atomics.initInt(0)
            _  <- Fibers.fork(kyoLoop(a2))
          } yield ()
        ZiosApp.block(1.second) {
          for {
            f <- ZiosApp.runTask(v).fork
            _ <- f.interrupt
            r <- f.await
          } yield assert(r.isFailure)
        }
      }
    }
  }
}
