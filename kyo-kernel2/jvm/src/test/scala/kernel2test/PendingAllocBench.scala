package kernel2test

import kyo.Maybe
import kyo.Tag
import kyo.kernel2.*
import language.implicitConversions

object PendingAllocBench:

    private val tmx =
        java.lang.management.ManagementFactory.getThreadMXBean
            .asInstanceOf[com.sun.management.ThreadMXBean]

    private def allocated(): Long =
        tmx.getThreadAllocatedBytes(Thread.currentThread.threadId)

    private var sink: Any = null

    // one row per JVM when a filter is given: in-process row ordering
    // contaminates later rows' compiled units, same as the time bench
    private var rowFilter: String = null

    def measure(name: String, iters: Int)(body: => Any): Unit =
        if rowFilter != null && name != rowFilter then return
        var warm = 0
        while warm < iters * 2 do
            sink = body
            warm += 1
        var best  = Double.MaxValue
        var batch = 0
        while batch < 5 do
            val a0 = allocated()
            var i  = 0
            while i < iters do
                sink = body
                i += 1
            val perOp = (allocated() - a0).toDouble / iters
            if perOp < best then best = perOp
            batch += 1
        end while
        println(f"$name: $best%.1f B/op")
    end measure

    def main(args: Array[String]): Unit =
        rowFilter = if args.isEmpty then null else args(0)
        measure("eager5", 1000000):
            ((1: Int < Any).map(_ + 1).map(_ * 2).map(_ - 3).map(_ + 5).map(_ - 4)).eval

        measure("eager10", 500000):
            ((1: Int < Any).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)
                .map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)).eval

        measure("suspension", 500000):
            PendingBench.runEcho(PendingBench.echo(1).map(_ + 1).map(_ + 1).map(_ + 1))

        measure("suspensionStep", 500000):
            PendingBench.runEchoStepSuspension(PendingBench.echo(1).map(_ + 1).map(_ + 1).map(_ + 1))

        measure("stateCont10", 200000):
            def program: Int < BenchCounter =
                PendingBench.counterOp(Maybe.Absent).map(n => if n <= 0 then n else PendingBench.counterOp(Maybe(n - 1)).map(_ => program))
            PendingBench.runCounter(program, 10)

        measure("stateStep10", 200000):
            def program: Int < BenchCounter =
                PendingBench.counterOp(Maybe.Absent).map(n => if n <= 0 then n else PendingBench.counterOp(Maybe(n - 1)).map(_ => program))
            PendingBench.runCounterStep(program, 10)

        measure("narrowIter", 200000):
            def loop(i: Int): Int < BenchEcho =
                if i < 10 then
                    PendingBench.echo(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                        .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
                else PendingBench.echo(i)
            PendingBench.runEcho(PendingBench.echo(0).map(loop))

        locally {
            var k: Int < BenchEcho = PendingBench.echo(0)
            var i                  = 0
            while i < 10 do
                k = k.map(_ + 1)
                i += 1
            var parked: Any = null
            val _ = ControlEffect.handlePartial(PendingBench.echoTag, k)(
                [C] =>
                    (input, cont) =>
                        parked = cont
                        Maybe.Absent
            )
            val cont = parked.asInstanceOf[Arrow[Int, Int, BenchEcho]]
            measure("resumeFused10", 1000000):
                cont(7).asInstanceOf[Int < Any].eval
        }
    end main
end PendingAllocBench
