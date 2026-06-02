package kyo

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.concurrent.Await
import scala.concurrent.duration.Duration as ScalaDuration

object KyoBenchTimingProbe:
    given Frame                    = Frame.internal
    private inline def now(): Long = java.lang.System.nanoTime()

    val cpPath = "/tmp/kyo-bench-cp.txt"

    def main(args: Array[String]): Unit =
        val src = scala.io.Source.fromFile(cpPath)
        val roots =
            try src.getLines().filter(_.nonEmpty).toSeq
            finally src.close()
        println(s"kyo-bench classpath roots: ${roots.size}")

        val timings     = scala.collection.mutable.ArrayBuffer.empty[Long]
        var symbolCount = 0
        var errorCount  = 0

        // warm-up JIT
        for i <- 0 to 1 do
            val t = measureOnce(roots)
            println(s"warmup $i: ${t._1}ms, symbols=${t._2}, errors=${t._3}")
        // measure 5
        for i <- 0 to 4 do
            val t = measureOnce(roots)
            timings += t._1
            symbolCount = t._2
            errorCount = t._3
            println(s"measure $i: ${t._1}ms, symbols=${t._2}, errors=${t._3}")
        end for

        val sorted = timings.sortBy(identity)
        val median = sorted(timings.size / 2)
        val avg    = timings.sum / timings.size
        val mn     = sorted.head
        val mx     = sorted.last
        println()
        println(s"=== KYO-BENCH FULL CLASSPATH COLD-INIT TIMING ===")
        println(s"runs=5, min=${mn}ms, median=${median}ms, avg=${avg}ms, max=${mx}ms")
        println(s"final symbols=${symbolCount}, errors=${errorCount}")
        println(s"raw=${timings.mkString(",")}ms")
        println(s"roots=${roots.size}")
    end main

    def measureOnce(roots: Seq[String]): (Long, Int, Int) =
        val t0  = KyoBenchTimingProbe.now()
        val eff = Tasty.Classpath.init(roots).map(cp => (cp.symbols.size, cp.errors.size))
        val handled = eff.handle(
            Scope.run,
            Abort.run[TastyError],
            Fiber.initUnscoped,
            _.map(_.toFuture),
            Sync.Unsafe.evalOrThrow
        )
        val result  = Await.result(handled, ScalaDuration.Inf)
        val elapsed = (KyoBenchTimingProbe.now() - t0) / 1_000_000
        result match
            case Result.Success((s, e)) => (elapsed, s, e)
            case other =>
                println(s"unexpected result: $other")
                (elapsed, -1, -1)
        end match
    end measureOnce
end KyoBenchTimingProbe
