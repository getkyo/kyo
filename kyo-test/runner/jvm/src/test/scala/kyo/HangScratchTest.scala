package kyo

import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

// scratch: mirrors LeafPool + TestRunner.runToFuture with plain kyo-core primitives, to see whether a
// second fiber-to-future bridge hangs without the test runner involved. Removed once diagnosed.
class HangScratchTest extends AnyFreeSpec:

    given Frame = Frame.internal

    object Pool:
        private val channel: Channel[Unit < Async] =
            import AllowUnsafe.embrace.danger
            Channel.Unsafe.init[Unit < Async](1024).safe

        private val started = new java.util.concurrent.atomic.AtomicBoolean(false)
        val workers         = new java.util.concurrent.CopyOnWriteArrayList[Any]()

        private def worker(id: Int): Unit < Async =
            Sync.defer(java.lang.System.out.println(s"SCRATCH worker $id taking on ${Thread.currentThread().getName}")).andThen {
                Abort.run[Closed](channel.take).map {
                    case Result.Success(work) =>
                        java.lang.System.out.println(s"SCRATCH worker $id took work on ${Thread.currentThread().getName}")
                        work.andThen(worker(id))
                    case other =>
                        java.lang.System.out.println(s"SCRATCH worker $id exit: $other")
                        ()
                }
            }

        private def ensureStarted: Unit < Async =
            Sync.defer(started.compareAndSet(false, true)).map { won =>
                if won then Kyo.foreachDiscard(0 until 4)(i => Fiber.initUnscoped(worker(i)).map(f => workers.add(f)).unit)
                else ()
            }

        def dump(): Unit =
            import AllowUnsafe.embrace.danger
            val takes = Sync.Unsafe.evalOrThrow(Abort.run[Closed](channel.pendingTakes))
            val puts  = Sync.Unsafe.evalOrThrow(Abort.run[Closed](channel.pendingPuts))
            java.lang.System.out.println(
                s"SCRATCH dump: pendingTakes=$takes pendingPuts=$puts size=${Sync.Unsafe.evalOrThrow(Abort.run[Closed](channel.size))}"
            )
            workers.forEach(w => java.lang.System.out.println(s"SCRATCH dump worker: $w"))
            java.lang.System.out.println(s"SCRATCH dump scheduler: ${kyo.scheduler.Scheduler.get.status()}")
        end dump

        def submit[A](comp: A < Async): Promise[A, Any] < Async =
            ensureStarted.andThen {
                Promise.init[A, Any].map { promise =>
                    val work: Unit < Async =
                        Sync.ensure(promise.completeDiscard(Result.panic(new Exception("work did not complete")))) {
                            comp.map(a => promise.completeDiscard(Result.succeed(a)))
                        }
                    channel.put(work).handle(Abort.run[Closed]).map(_ => promise)
                }
            }
    end Pool

    def runToFuture[A](name: String)(comp: A < Async): Future[A] =
        bridge(name)(Pool.submit(comp).map(_.get))

    def bridge[A](name: String)(comp: A < Async): Future[A] =
        java.lang.System.out.println(s"SCRATCH $name bridge on ${Thread.currentThread().getName}")
        val asFuture: Future[A] < Sync =
            Scope.run(comp).handle(Fiber.initUnscoped).map(_.toFuture)
        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(asFuture)
    end bridge

    def wait[A](name: String)(f: Future[A]): A =
        try
            val r = Await.result(f, 10.seconds)
            java.lang.System.out.println(s"SCRATCH $name done: $r")
            r
        catch
            case ex: java.util.concurrent.TimeoutException =>
                java.lang.System.out.println(s"SCRATCH $name TIMED OUT")
                Pool.dump()
                throw ex
    end wait

    "sequential bridges through a global pool, each awaited on the test thread" in {
        val a = wait("first")(runToFuture("first")(Sync.defer(1)))
        val b = wait("second")(runToFuture("second")(Sync.defer(2)))
        val c = wait("third")(runToFuture("third")(Sync.defer(3)))
        assert((a, b, c) == (1, 2, 3))
    }

    "sequential bridges through a global pool, each started inside the previous completion" in {
        // parasitic: the callback runs on the thread completing the future, the scheduler worker that
        // finished the fiber, so the next bridge's eval runs on that worker's slot while its slice is
        // still in flight, as the runner's self-test does through ScalaTest's future chaining
        given ExecutionContext = ExecutionContext.parasitic
        val chained =
            runToFuture("c1")(Sync.defer(1)).flatMap { a =>
                runToFuture("c2")(Sync.defer(2)).flatMap { b =>
                    runToFuture("c3")(Sync.defer(3)).map(c => (a, b, c))
                }
            }
        assert(wait("chained")(chained) == (1, 2, 3))
    }

    "a bridge whose fiber forks and joins another fiber, three times" in {
        def body(n: Int): Int < Async =
            Fiber.initUnscoped(Sync.defer(n)).flatMap(_.get)
        val a = wait("j1")(runToFuture("j1")(body(1)))
        val b = wait("j2")(runToFuture("j2")(body(2)))
        val c = wait("j3")(runToFuture("j3")(body(3)))
        assert((a, b, c) == (1, 2, 3))
    }

    "two kyo-test suites through the runner's bridge, awaited on the test thread" in {
        val r1 = wait("suiteA")(kyo.test.runner.TestRunner.runToFuture(classOf[HangScratchFixtures.TinySuiteA]))
        val r2 = wait("suiteB")(kyo.test.runner.TestRunner.runToFuture(classOf[HangScratchFixtures.TinySuiteB]))
        assert(r1.passed == 1 && r2.passed == 1)
    }
end HangScratchTest

object HangScratchFixtures:
    class TinySuiteA extends kyo.test.internal.TestBase[Any]:
        override def config: kyo.test.RunConfig = super.config.failOnNoAssertion(false)
        "a" in { kyo.Sync.defer(1).map(_ => ()) }
    end TinySuiteA

    class TinySuiteB extends kyo.test.internal.TestBase[Any]:
        override def config: kyo.test.RunConfig = super.config.failOnNoAssertion(false)
        "b" in { kyo.Sync.defer(2).map(_ => ()) }
    end TinySuiteB
end HangScratchFixtures
