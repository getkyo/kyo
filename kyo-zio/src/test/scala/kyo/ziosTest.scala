package kyo

import kyoTest.KyoTest
import zio.ZIO
import kyo.core._
import kyo.zios._
import scala.concurrent.duration._
import kyo.concurrent.fibers._
import kyo.ios.IOs
import kyo.concurrent.atomics._
import zio._
import zio.Task
import kyo.aborts.Aborts
import kyo.envs.Envs

class ziosTest extends KyoTest {

  val run = KyoZioApp.run(1.second) _

  "aborts" in run {
    val a: Int > (Aborts[String] | ZIOs) = ZIOs(ZIO.fail("error"))
    Aborts[String].toOption(a)(opt => assert(opt.isEmpty))
  }

  "env" in run {
    Aborts[Nothing].run(Envs.let(10)(ZIOs(ZIO.environment[Int]))(v => assert(v.get == 10)))
  }

  "fibers" - {
    "kyo" in run {
      for {
        v1 <- ZIOs(ZIO.succeed(1))
        v2 <- Fibers.fork(2)
        v3 <- ZIOs(ZIO.succeed(3))
      } yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }
    "zio" in run {
      for {
        v1 <- IOs(1)
        v2 <- ZIOs(ZIO.succeed(2).fork.flatMap(_.join))
        v3 <- IOs(3)
      } yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }
    "both" in run {
      for {
        v1 <- IOs(1)
        v2 <- ZIOs(ZIO.succeed(2).fork.flatMap(_.join))
        v3 <- Fibers.fork(3)
      } yield assert(v1 == 1 && v2 == 2 && v3 == 3)
    }
  }

  "interrupts" - {

    def kyoLoop(a: AtomicInt): Unit > IOs =
      a.incrementAndGet(_ => kyoLoop(a))

    def zioLoop(a: Ref[Int]): Task[Unit] =
      a.update(_ + 1).flatMap(_ => zioLoop(a))

    "kyo" in {
      val v =
        for {
          a <- Atomics.forInt(0)
          _ <- Fibers.fork(kyoLoop(a))
        } yield ()
      KyoZioApp.block(1.day) {
        for {
          f <- KyoZioApp.runTask(v).fork
          _ <- f.interrupt
          r <- f.await
        } yield assert(r.isFailure)
      }
    }
    "zio" in {
      val v =
        for {
          a <- ZIOs(Ref.make(0))
          _ <- ZIOs(zioLoop(a))
        } yield ()
      KyoZioApp.block(1.second) {
        for {
          f <- KyoZioApp.runTask(v).fork
          _ <- f.interrupt
          r <- f.await
        } yield assert(r.isFailure)
      }
    }
    "both" in {
      val v =
        for {
          a  <- ZIOs(Ref.make(0))
          _  <- ZIOs(zioLoop(a))
          a2 <- Atomics.forInt(0)
          _  <- Fibers.fork(kyoLoop(a2))
        } yield ()
      KyoZioApp.block(1.second) {
        for {
          f <- KyoZioApp.runTask(v).fork
          _ <- f.interrupt
          r <- f.await
        } yield assert(r.isFailure)
      }
    }
  }
}
