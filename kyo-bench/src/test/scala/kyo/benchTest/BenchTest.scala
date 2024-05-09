package kyo.benchTest

import kyo.bench.*
import kyo.bench.Bench.*
import org.scalatest.Assertions
import org.scalatest.freespec.AsyncFreeSpec

abstract class BenchTest extends AsyncFreeSpec with Assertions:

    enum Target:
        case Cats
        case Kyo
        case Zio
    end Target

    def target: Target
    def runSync[T](b: Bench.SyncAndFork[T]): T
    def runFork[T](b: Bench.Fork[T]): T

    val targets = Seq("cats", "kyo", "zio")

    def detectRuntimeLeak() =
        Thread.getAllStackTraces().forEach { (thread, stack) =>
            for deny <- targets.filter(_ != target.toString().toLowerCase()) do
                if stack.filter(!_.toString.contains("kyo.bench")).mkString.toLowerCase.contains(deny) then
                    fail(s"Detected $deny threads in a $target benchmark: $thread")
        }
        succeed
    end detectRuntimeLeak

    inline given [T]: CanEqual[T, T] = CanEqual.derived

    def test[T](b: Bench[T]): Unit =
        b match
            case b: SyncAndFork[T] =>
                "sync" in {
                    assert(runSync(b) == b.expectedResult)
                    detectRuntimeLeak()
                }
            case _ =>
        end match
        b match
            case b: Fork[T] =>
                "fork" in {
                    assert(runFork(b) == b.expectedResult)
                    detectRuntimeLeak()
                }
            case _ =>
        end match
    end test

    Registry.loadAll().foreach { b =>
        b.getClass.getSimpleName - test(b)
    }

end BenchTest
