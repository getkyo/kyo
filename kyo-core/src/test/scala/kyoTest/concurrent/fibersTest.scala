package kyoTest.concurrent

import kyo.core._
import kyo.ios._
import kyo.tries._
import scala.util.Failure
import kyo.concurrent.atomics._
import kyo.concurrent.fibers._
import kyo.concurrent.latches._
import kyoTest.KyoTest
import java.util.concurrent.atomic.{AtomicReference => JAtomicReference}
import java.util.concurrent.atomic.{AtomicInteger => JAtomicInteger}
import kyo.concurrent.atomics.AtomicInteger
import scala.concurrent.duration._
import kyo.scopes._
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Closeable

class fibersTest extends KyoTest {

  private def run[T](io: T > (IOs | Fibers)): T =
    IOs.run((IOs.lazyRun(io) << Fibers).block)

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
    "executes in a different thread" in run {
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
    "nested" in run {
      val t1 = Thread.currentThread()
      for {
        t2 <- Fibers.fork(IOs.value(Fibers.fork(Thread.currentThread())))
      } yield assert(t1 != t2)
    }
  }

  "sleep" in {
    val start = System.currentTimeMillis()
    run(Fibers.sleep(10.millis))
    val end = System.currentTimeMillis()
    assert(end - start >= 10)
  }

  "interrupt" - {

    def loop(ref: AtomicInteger): Unit > IOs =
      Thread.sleep(1)
      ref.incrementAndGet(_ => loop(ref))

    def runLoop[T](started: Latch, done: Latch) =
      Scopes.close {
        Scopes.ensure(done.release) { _ =>
          started.release(_ => AtomicInteger(0)(loop))
        }
      }

    "one fiber" in run {
      for {
        started     <- Latch(1)
        done        <- Latch(1)
        fiber       <- Fibers.forkFiber(runLoop(started, done))
        _           <- started.await
        interrupted <- fiber.interrupt
        _           <- assert(interrupted)
        _           <- done.await
      } yield ()
    }
    "multiple fibers" in run {
      for {
        started      <- Latch(1)
        done         <- Latch(1)
        fiber1       <- Fibers.forkFiber(runLoop(started, done))
        fiber2       <- Fibers.forkFiber(runLoop(started, done))
        fiber3       <- Fibers.forkFiber(runLoop(started, done))
        _            <- started.await
        interrupted1 <- fiber1.interrupt
        interrupted2 <- fiber2.interrupt
        interrupted3 <- fiber3.interrupt
        _            <- assert(interrupted1 && interrupted2 && interrupted3)
        _            <- done.await
      } yield ()
    }
  }

  "forkFiber" - {
    val thread = JAtomicReference[Thread]
    "executes in a different thread" in {
      run(Fibers.forkFiber(thread.set(Thread.currentThread())))
      assert(thread.get() != Thread.currentThread())
    }
    "nested" in {
      run(Fibers.forkFiber(Fibers.forkFiber(thread.set(Thread.currentThread()))))
      assert(thread.get() != Thread.currentThread())
    }
  }

  "race" in {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          Thread.sleep(10)
          loop(i - 1, s)
        } else {
          s
        }
      }
    val io = Fibers.race(loop(10, "a"), loop(20, "b"))
    assert(run(io) == "a")
    assert(ac.get() == 10)
    assert(bc.get() < 20)
  }

  "await" in {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          Thread.sleep(1)
          loop(i - 1, s)
        } else {
          s
        }
      }
    val io = Fibers.await(loop(10, "a"), loop(20, "b"))
    run(io)
    assert(ac.get() == 10)
    assert(bc.get() == 20)
  }

  "awaitFiber" in {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          Thread.sleep(1)
          loop(i - 1, s)
        } else {
          s
        }
      }
    val io = Fibers.awaitFiber(List(loop(10, "a"), loop(20, "b")))(_.join)
    run(io)
    assert(ac.get() == 10)
    assert(bc.get() == 20)
  }

  "collect" in {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          Thread.sleep(1)
          loop(i - 1, s)
        } else {
          s
        }
      }
    val io = Fibers.collect(List(loop(10, "a"), loop(20, "b")))
    assert(run(io) == List("a", "b"))
    assert(ac.get() == 10)
    assert(bc.get() == 20)
  }

  "collectFiber" in {
    val ac = JAtomicInteger(0)
    val bc = JAtomicInteger(0)
    def loop(i: Int, s: String): String > IOs =
      IOs {
        if (i > 0) {
          if (s.equals("a")) ac.incrementAndGet()
          else bc.incrementAndGet()
          Thread.sleep(1)
          loop(i - 1, s)
        } else {
          s
        }
      }
    val io = Fibers.collectFiber(List(loop(10, "a"), loop(20, "b")))(_.join)
    assert(run(io) == List("a", "b"))
    assert(ac.get() == 10)
    assert(bc.get() == 20)
  }

  "deep handler" - {
    "transform" in {
      val io =
        for {
          v1       <- Fibers.fork(1)
          _        <- Fibers.await(())
          (v2, v3) <- Fibers.fork(2, 3)
          l        <- Fibers.collect(List(4, 5))
        } yield v1 + v2 + v3 + l.sum
      val a = run((IOs.lazyRun(io) << Fibers)(_.join))
      assert(a == 15)
    }
    "interrupt" in run {
      def loop(ref: AtomicInteger): Unit > IOs =
        Thread.sleep(1)
        ref.incrementAndGet(_ => loop(ref))

      def task(l: Latch): Unit > IOs =
        Scopes.close {
          Scopes.ensure(l.release) { _ =>
            AtomicInteger(0)(loop)
          }
        }

      for {
        l           <- Latch(1)
        fiber       <- IOs.lazyRun(Fibers.fork(task(l))) << Fibers
        _           <- Fibers.sleep(10.millis)
        interrupted <- fiber.interrupt
        _           <- l.await
      } yield assert(interrupted)
    }
  }

  "with scopes" - {
    trait Context {
      val resource1 = new JAtomicInteger with Closeable {
        def close(): Unit =
          set(-1)
      }
      val resource2 = new JAtomicInteger with Closeable {
        def close(): Unit =
          set(-1)
      }
    }
    "outer" in new Context {
      val io1: (JAtomicInteger & Closeable, Set[Int]) > (Scopes | IOs | Fibers) =
        for {
          r        <- Scopes.acquire(resource1)
          v1       <- IOs(r.getAndIncrement())
          (v2, v3) <- Fibers.fork(r.getAndIncrement(), r.getAndIncrement())
        } yield (r, Set(v1, v2, v3))
      val io2: (JAtomicInteger, Set[Int]) > (IOs | Fibers) =
        Scopes.close(io1)
      assert(run(io2) == (resource1, Set(0, 1, 2)))
      assert(resource1.get() == -1)
    }
    "inner" in new Context {
      val io = Fibers.fork(Scopes.close(Scopes.acquire(resource1)(_.incrementAndGet())))
      assert(run(io) == 1)
      assert(resource1.get() == -1)
    }
    "multiple" in new Context {
      val io = Fibers.fork(
          Scopes.close(Scopes.acquire(resource1)(_.incrementAndGet())),
          Scopes.close(Scopes.acquire(resource2)(_.incrementAndGet()))
      )
      assert(run(io) == (1, 1))
      assert(resource1.get() == -1)
      assert(resource2.get() == -1)
    }
    "mixed" in new Context {
      val io1: (Int, Int, Int, Int) > (Scopes | (IOs | (IOs | Fibers))) =
        for {
          r        <- Scopes.acquire(resource1)
          v1       <- IOs(r.incrementAndGet())
          (v2, v3) <- Fibers.fork(r.incrementAndGet(), r.incrementAndGet())
          v4       <- Scopes.close(Scopes.acquire(resource2)(_.incrementAndGet()))
        } yield (v1, v2, v3, v4)
      val io2: (Int, Int, Int, Int) > (IOs | Fibers) =
        Scopes.close(io1)
      val r = run(io2)
      assert(r == (1, 2, 3, 1) || r == (1, 3, 2, 1))
      assert(resource1.get() == -1)
      assert(resource2.get() == -1)
    }
  }
}
