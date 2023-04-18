package kyoTest.concurrent

import kyo.concurrent.atomics.AtomicInt
import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo.concurrent.latches._
import kyo.concurrent.timers._
import kyo.core._
import kyo.ios._
import kyo.locals._
import kyo.resources
import kyo.resources._
import kyo.tries._
import kyoTest.KyoTest

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.{AtomicInteger => JAtomicInteger}
import java.util.concurrent.atomic.{AtomicReference => JAtomicReference}
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Try
import org.scalatest.compatible.Assertion
import kyoTest.Platform

class fibersTest extends KyoTest {

  "promise" - {
    "complete" in run {
      for {
        p <- Fibers.promise[Int]
        a <- p.complete(1)
        b <- p.isDone
        c <- p.join
      } yield assert(a && b && c == 1)
    }
    "complete twice" in run {
      for {
        p <- Fibers.promise[Int]
        a <- p.complete(1)
        b <- p.complete(2)
        c <- p.isDone
        d <- p.join
      } yield assert(a && !b && c && d == 1)
    }
    "failure" in run {
      val ex = new Exception
      for {
        p <- Fibers.promise[Int]
        a <- p.complete(throw ex)
        b <- p.isDone
        c <- p.joinTry
      } yield assert(a && b && c == Failure(ex))
    }
  }

  "fork" - {
    "value" in run {
      for {
        v <- Fibers.fork(1)
      } yield assert(v == 1)
    }
    "executes in a different thread" in runJVM {
      val t1 = Thread.currentThread()
      for {
        t2 <- Fibers.fork(Thread.currentThread())
      } yield assert(t1 != t2)
    }
    "multiple" in run {
      for {
        v0               <- Fibers.fork(0)
        (v1, v2)         <- Fibers.fork(1, 2)
        (v3, v4, v5)     <- Fibers.fork(3, 4, 5)
        (v6, v7, v8, v9) <- Fibers.fork(6, 7, 8, 9)
      } yield assert(v0 + v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 == 45)
    }
    "nested" in runJVM {
      val t1 = Thread.currentThread()
      for {
        t2 <- Fibers.fork(IOs.value(Fibers.fork(Thread.currentThread())))
      } yield assert(t1 != t2)
    }
  }

  "sleep" in run {
    for {
      start <- IOs(System.currentTimeMillis())
      _     <- Fibers.sleep(10.millis)
      end   <- IOs(System.currentTimeMillis())
    } yield assert(end - start >= 10)
  }

  "timeout" in runJVM {
    Tries.run(Fibers.block(Fibers.timeout(10.millis)(Timers.run(Fibers.sleep(1.day)))))
      .map {
        case Failure(_: Fibers.Interrupted) => succeed
        case _                              => fail()
      }
  }

  "interrupt" - {

    def loop(ref: AtomicInt): Unit > IOs =
      ref.incrementAndGet.map(_ => loop(ref))

    def runLoop[T](started: Latch, done: Latch) =
      Resources.run {
        Resources.ensure(done.release).map { _ =>
          started.release.map(_ => Atomics.forInt(0).map(loop))
        }
      }

    "one fiber" in run {
      for {
        started     <- Latches(1)
        done        <- Latches(1)
        fiber       <- Fibers.forkFiber(runLoop(started, done))
        _           <- started.await
        interrupted <- fiber.interrupt
        _           <- assert(interrupted)
        _           <- done.await
      } yield succeed
    }
    "multiple fibers" in run {
      for {
        started      <- Latches(3)
        done         <- Latches(3)
        fiber1       <- Fibers.forkFiber(runLoop(started, done))
        fiber2       <- Fibers.forkFiber(runLoop(started, done))
        fiber3       <- Fibers.forkFiber(runLoop(started, done))
        _            <- started.await
        _            <- println("started")
        interrupted1 <- fiber1.interrupt
        interrupted2 <- fiber2.interrupt
        interrupted3 <- fiber3.interrupt
        _            <- println("interrupted " + done)
        _            <- assert(interrupted1 && interrupted2 && interrupted3)
        _            <- done.await
      } yield succeed
    }
  }

  // "interruptAwait" in runJVM {

  //   def loop(ref: AtomicInt): Unit > IOs =
  //     ref.incrementAndGet.map(_ => loop(ref))

  //   def runLoop[T](started: Latch, done: Latch): Unit > IOs =
  //     Resources.run {
  //       Resources.ensure(done.release).map { _ =>
  //         started.release.map(_ => Atomics.forInt(0).map(loop))
  //       }
  //     }

  //   for {
  //     started     <- Latches(1)
  //     done        <- Latches(1)
  //     fiber       <- Fibers.forkFiber(runLoop(started, done))
  //     _           <- started.await
  //     interrupted <- fiber.interruptAwait
  //     pending     <- done.pending
  //   } yield assert(interrupted && pending == 0)
  // }

  "forkFiber" - {
    val thread = JAtomicReference[Thread]
    "executes in a different thread" in run {
      for {
        t1 <- IOs.value(Thread.currentThread())
        t2 <- Fibers.forkFiber(thread.set(Thread.currentThread()))
      } yield assert(t1 != t2)
    }
    "nested" in run {
      for {
        t1 <- IOs.value(Thread.currentThread())
        t2 <- Fibers.forkFiber(Fibers.forkFiber(thread.set(Thread.currentThread())))
      } yield assert(t1 != t2)
    }
  }

  "race" in run {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          loop(i - 1, s)
        } else {
          s
        }
      }
    Fibers.race(loop(1, "a"), loop(100, "b")).map { r =>
      if (Platform.isJS) {
        assert(r == "b")
        assert(ac.get() == 0)
        assert(bc.get() == 100)
      } else {
        assert(r == "a")
        assert(ac.get() == 1)
        assert(bc.get() < 100)
      }
    }
  }

  "await" in run {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          loop(i - 1, s)
        } else {
          s
        }
      }
    Fibers.await(loop(2, "a"), loop(4, "b")).map { r =>
      assert(ac.get() == 2)
      assert(bc.get() == 4)
    }
  }

  "awaitFiber" in run {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          loop(i - 1, s)
        } else {
          s
        }
      }
    Fibers.awaitFiber(List(loop(2, "a"), loop(5, "b"))).map(_.join).map { r =>
      assert(ac.get() == 2)
      assert(bc.get() == 5)
    }
  }

  "collect" in run {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          loop(i - 1, s)
        } else {
          s
        }
      }
    Fibers.collect(List(loop(1, "a"), loop(5, "b"))).map { r =>
      assert(r == List("a", "b"))
      assert(ac.get() == 1)
      assert(bc.get() == 5)
    }
  }

  "collectFiber" in run {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          loop(i - 1, s)
        } else {
          s
        }
      }
    Fibers.collectFiber(List(loop(2, "a"), loop(5, "b"))).map(_.join).map { r =>
      assert(r == List("a", "b"))
      assert(ac.get() == 2)
      assert(bc.get() == 5)
    }
  }

  "deep handler" - {
    "transform" in run {
      for {
        v1       <- Fibers.fork(1)
        _        <- Fibers.await(())
        (v2, v3) <- Fibers.fork(2, 3)
        l        <- Fibers.collect(List(4, 5))
      } yield assert(v1 + v2 + v3 + l.sum == 15)
    }
    "interrupt" in runJVM {
      def loop(ref: AtomicInt): Unit > IOs =
        ref.incrementAndGet.map(_ => loop(ref))

      def task(l: Latch): Unit > IOs =
        Resources.run {
          Resources.ensure(l.release).map { _ =>
            Atomics.forInt(0).map(loop)
          }
        }

      for {
        l           <- Latches(1)
        fiber       <- Fibers.run(IOs.lazyRun(Fibers.fork(task(l))))
        _           <- Fibers.sleep(10.millis)
        interrupted <- fiber.interrupt
        _           <- l.await
      } yield assert(interrupted)
    }
  }

  "with resources" - {
    val resource1 = new JAtomicInteger with Closeable {
      def close(): Unit =
        set(-1)
    }
    val resource2 = new JAtomicInteger with Closeable {
      def close(): Unit =
        set(-1)
    }
    "outer" in run {
      resource1.set(0)
      resource2.set(0)
      val io1: (JAtomicInteger & Closeable, Set[Int]) > (Resources | IOs | Fibers) =
        for {
          r        <- Resources.acquire(resource1)
          v1       <- IOs(r.getAndIncrement())
          (v2, v3) <- Fibers.fork(r.getAndIncrement(), r.getAndIncrement())
        } yield (r, Set(v1, v2, v3))
      Resources.run(io1).map { case (r, v) =>
        assert(v == Set(0, 1, 2))
        assert(r.get() == -1)
      }
    }
    "inner" in run {
      resource1.set(0)
      resource2.set(0)
      Fibers.fork(Resources.run(Resources.acquire(resource1).map(_.incrementAndGet()))).map { r =>
        assert(r == 1)
        assert(resource1.get() == -1)
      }
    }
    "multiple" in run {
      resource1.set(0)
      resource2.set(0)
      Fibers.fork(
          Resources.run(Resources.acquire(resource1).map(_.incrementAndGet())),
          Resources.run(Resources.acquire(resource2).map(_.incrementAndGet()))
      ).map { r =>
        assert(r == (1, 1))
        assert(resource1.get() == -1)
        assert(resource2.get() == -1)
      }
    }
    "mixed" in run {
      resource1.set(0)
      resource2.set(0)
      val io1: (Int, Int, Int, Int) > (Resources | (IOs | (IOs | Fibers))) =
        for {
          r        <- Resources.acquire(resource1)
          v1       <- IOs(r.incrementAndGet())
          (v2, v3) <- Fibers.fork(r.incrementAndGet(), r.incrementAndGet())
          v4       <- Resources.run(Resources.acquire(resource2).map(_.incrementAndGet()))
        } yield (v1, v2, v3, v4)
      Resources.run(io1).map { r =>
        assert(r == (1, 2, 3, 1) || r == (1, 3, 2, 1))
        assert(resource1.get() == -1)
        assert(resource2.get() == -1)
      }
    }
  }

  "locals" - {
    val l = Locals.init(10)
    "fork" - {
      "default" in run {
        Fibers.fork(l.get).map(v => assert(v == 10))
      }
      "let" in run {
        l.let(20)(Fibers.fork(l.get)).map(v => assert(v == 20))
      }
    }
    "race" - {
      "default" in run {
        Fibers.race(l.get, l.get).map(v => assert(v == 10))
      }
      "let" in run {
        l.let(20)(Fibers.race(l.get, l.get)).map(v => assert(v == 20))
      }
    }
    "collect" - {
      "default" in run {
        Fibers.collect(List(l.get, l.get)).map(v => assert(v == List(10, 10)))
      }
      "let" in run {
        l.let(20)(Fibers.collect(List(l.get, l.get)).map(v => assert(v == List(20, 20))))
      }
    }
    "await" - {
      "default" in run {
        Fibers.await(l.get.map(v => assert(v == 10))).map(_ => succeed)
      }
      "let" in run {
        l.let(20)(Fibers.await(l.get.map(v => assert(v == 20))).map(_ => succeed))
      }
    }
  }

  "stack safety" in run {
    def loop(i: Int): Assertion > (IOs | Fibers) =
      if (i > 0) {
        Fibers.fork(List.fill(1000)(())).map(_ => loop(i - 1))
      } else {
        succeed
      }
    loop(10000)
  }
}
