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

    def test(cls: Class[? <: ArenaBench[?]]): Unit =
        // Built inside the leaves rather than while the suite is constructed: a benchmark whose setup throws (the HTTP benchmarks
        // fork a server) fails its own leaves with that cause instead of aborting every benchmark in the suite.
        lazy val bench = Registry.instantiate(cls)
        if classOf[SyncAndFork[?]].isAssignableFrom(cls) then
            s"sync$target" in {
                val b = bench.asInstanceOf[SyncAndFork[Any]]
                assert(runSync(b) == b.expectedResult)
                detectRuntimeLeak()
            }
        end if
        if classOf[Fork[?]].isAssignableFrom(cls) then
            s"fork$target" in {
                val b = bench.asInstanceOf[Fork[Any]]
                assert(runFork(b) == b.expectedResult)
                detectRuntimeLeak()
            }
        end if
    end test

    Registry.classes().foreach(cls => cls.getSimpleName - test(cls))

end BenchTest
