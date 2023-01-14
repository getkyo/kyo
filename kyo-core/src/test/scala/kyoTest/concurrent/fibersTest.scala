package kyoTest.concurrent

import kyo.core._
import kyo.ios._
import kyo.tries._
import scala.util.Failure
import kyo.concurrent.refs._
import kyo.concurrent.fibers._
import kyoTest.KyoTest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kyo.concurrent.refs.IntRef
import scala.concurrent.duration._

class fibersTest extends KyoTest {

  private def run[T](io: T > (IOs | Fibers)): T =
    // IOs.run(Fibers.block(io))
    val a: T > Fibers         = IOs.lazyRun(io)
    val b: Fiber[T] > Nothing = a << Fibers
    val c: Fiber[T]           = b
    IOs.run(c.block)

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
        a <- p.complete {
          print(1)
          throw ex
        }
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
        t2 <- Fibers.fork(Fibers.fork(Thread.currentThread()))
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

    "ref inc loop" -
      interruptTest {
        def loop(ref: IntRef): Unit > IOs =
          Thread.sleep(1)
          ref.incrementAndGet(_ => loop(ref))
        loop
      }

    "cpu task + ref inc loop" -
      interruptTest {
        def fib(n: Int): BigInt > IOs =
          if (n <= 1) IOs(BigInt(n))
          else fib(n - 1)(a => fib(n - 2)(b => a + b))

        def loop(ref: IntRef): Unit > IOs =
          Thread.sleep(1)
          fib(5)(_ => ref.incrementAndGet(_ => loop(ref)))
        loop
      }

    def interruptTest(loop: IntRef => Unit > IOs) = {
      "one fiber" in run {
        for {
          ref          <- IntRef(0)
          fiber        <- Fibers.forkFiber(loop(ref))
          value1       <- ref.get
          _            <- Fibers.sleep(10.millis)
          interrupted  <- fiber.interrupt
          interrupted2 <- fiber.interrupt
          value2       <- ref.get
          _            <- Fibers.sleep(10.millis)
          value3       <- ref.get
        } yield {
          assert(interrupted && !interrupted2 && value1 < value2 && value2 == value3)
        }
      }
      "multiple fibers" in run {
        for {
          ref          <- IntRef(0)
          fiber1       <- Fibers.forkFiber(loop(ref))
          fiber2       <- Fibers.forkFiber(loop(ref))
          fiber3       <- Fibers.forkFiber(loop(ref))
          value1       <- ref.get
          _            <- Fibers.sleep(50.millis)
          interrupted1 <- fiber1.interrupt
          value2       <- ref.get
          _            <- Fibers.sleep(50.millis)
          interrupted2 <- fiber2.interrupt
          value3       <- ref.get
          _            <- Fibers.sleep(50.millis)
          interrupted3 <- fiber3.interrupt
          value4       <- ref.get
          _            <- Fibers.sleep(50.millis)
          value5       <- ref.get
        } yield {
          assert(interrupted1 && interrupted2 && interrupted3 &&
            value1 < value2 && value2 < value3 && value3 < value4 &&
            value4 == value5)
        }
      }
    }
  }

  "forkFiber" - {
    val thread = AtomicReference[Thread]
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
    val ac = AtomicInteger(0)
    val bc = AtomicInteger(0)
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
    assert(bc.get() - 10 <= 1)
  }

  "raceFiber" in run {
    def loop(ref: IntRef): Unit > IOs =
      Thread.sleep(1)
      ref.incrementAndGet(_ => loop(ref))

    for {
      ref         <- IntRef(0)
      fiber       <- Fibers.raceFiber(List(loop(ref), loop(ref)))
      value1      <- ref.get
      _           <- Fibers.sleep(50.millis)
      interrupted <- fiber.interrupt
      value2      <- ref.get
      _           <- Fibers.sleep(50.millis)
      value3      <- ref.get
    } yield assert(interrupted && value1 < value2 && value2 == value3)
  }

  "await" in {
    val ac = AtomicInteger(0)
    val bc = AtomicInteger(0)
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
    val ac = AtomicInteger(0)
    val bc = AtomicInteger(0)
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
    val ac = AtomicInteger(0)
    val bc = AtomicInteger(0)
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
    val ac = AtomicInteger(0)
    val bc = AtomicInteger(0)
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
      def loop(ref: IntRef): Unit > IOs =
        Thread.sleep(1)
        ref.incrementAndGet(_ => loop(ref))

      def io(ref: IntRef) =
        for {
          _ <- Fibers.fork(loop(ref))
          _ <- Fibers.await(loop(ref))
          _ <- Fibers.fork(loop(ref), loop(ref))
          _ <- Fibers.collect(List(loop(ref), loop(ref)))
        } yield ref

      for {
        ref         <- IntRef(0)
        fiber       <- IOs.lazyRun(io(ref)) << Fibers
        value1      <- ref.get
        _           <- Fibers.sleep(10.millis)
        interrupted <- fiber.interrupt
        value2      <- ref.get
        _           <- Fibers.sleep(10.millis)
        value3      <- ref.get
      } yield assert(interrupted && value1 < value2 && value2 == value3)
    }
  }
}
