package kyo.bench.arena

import kyo.bench.*
import kyo.bench.arena.ArenaBench.*
import org.scalatest.Assertions
import org.scalatest.freespec.AsyncFreeSpec

abstract class BenchTest extends AsyncFreeSpec with Assertions:

    enum Target:
        case Cats
        case Kyo
        case ZIO
    end Target

    def target: Target
    def runSync[A](b: ArenaBench.SyncAndFork[A]): A
    def runFork[A](b: ArenaBench.Fork[A]): A

    val targets = Seq("cats", "kyo", "zio")

    def detectRuntimeLeak() =
        Thread.getAllStackTraces().forEach { (thread, stack) =>
            for deny <- targets.filter(_ != target.toString().toLowerCase()) do
                if stack.filter(!_.toString.contains("kyo.bench")).mkString.toLowerCase.contains(deny) then
                    fail(s"Detected $deny threads in a $target benchmark: $thread")
        }
        succeed
    end detectRuntimeLeak

    inline given [A]: CanEqual[A, A] = CanEqual.derived

    def test[A](b: ArenaBench[A]): Unit =
        b match
            case b: SyncAndFork[A] =>
                s"sync$target" in {
                    assert(runSync(b) == b.expectedResult)
                    detectRuntimeLeak()
                }
            case _ =>
        end match
        b match
            case b: Fork[A] =>
                s"fork$target" in {
                    val result = runFork(b)
                    assert(result == b.expectedResult)
                    detectRuntimeLeak()
                }
            case _ =>
        end match
    end test

    Registry.loadAll().foreach { b =>
        b.getClass.getSimpleName - test(b)
    }

end BenchTest
